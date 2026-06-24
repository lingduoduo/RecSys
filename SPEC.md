# SPEC — Versioned, runtime-reconfigurable consistent-hash sharding

> Supersedes the previous SPEC.md. Scope evolved during review: from "dedup the hash" to
> **a dynamically reshardable, cross-instance-consistent sharding topology** with safe
> migration for TTL data. Decisions locked with the user:
> - Keep the consistent-hash **ring** (FNV-1a + virtual nodes) as the mapping; optimize it, don't swap it.
> - **Versioned topology snapshot** shared across instances (runtime dynamic shard membership).
> - **Generation-prefixed keys + dual-read window** as the resharding/migration mechanism.
> - Shared FNV-1a primitive lives in `com.recsys.infrastructure.redis.sharding`.
> - **Build all 3 phases** (full feature). Reshard is triggered by a **guarded admin HTTP endpoint**.
> - Accepted defaults: refresh interval **30 s**, dual-read window **= configured max record TTL**,
>   generation key scheme **`sr:g{version}:...`**, legacy unversioned-key fallback **first window only**.

## 1. Objective

Today `ConsistentHashRing(shardCount, 150)` is built once from a deploy-time env var
(`SHARDED_RECORD_SHARD_COUNT`, default 2) and is invisible to other instances; changing the
shard count means a redeploy and silently strands the data that remaps. This spec makes the
shard topology a **versioned, shared, runtime-swappable snapshot** so that:

1. **All instances agree** on the current topology (shardCount + ring) by reading one
   authoritative snapshot from Redis — the "consistent snapshot across services" (reliability).
2. **Shard count can change at runtime** (publish a new topology version) without a redeploy.
3. **Resharding is safe for TTL data**: each topology version owns a generation-prefixed
   keyspace; during a bounded **dual-read window** (≈ max record TTL), reads fall back to the
   previous generation so in-flight records aren't lost; old data then expires on its own.
4. The per-generation mapping stays the **consistent-hash ring** (minimal key movement on
   resize), now **optimized** (build once per generation, allocation-light lookup) and backed
   by a **single shared FNV-1a** primitive (dedup with `StableBucketer`).

### Target users
Operators who need to scale shards without a redeploy or data loss, and maintainers who want
one FNV-1a and one authoritative topology instead of per-instance, deploy-frozen rings.

### What "lower latency / higher throughput / reliability" actually comes from here
- **Reliability:** a single versioned snapshot removes per-instance topology drift; dual-read
  removes data loss on resize.
- **Latency/throughput:** the optimized, immutable per-generation ring (no rebuild on the hot
  path); resize moves only ~1/N keys (consistent hashing) instead of reshuffling everything.
- **Honest caveat:** raw single-lookup speed is already fine; the real win is *operational*
  (resize without redeploy/loss), not a micro-bench step-change. Any deeper hot-path
  micro-opt stays deferred pending a benchmark (repo precedent).

### Non-goals
- No algorithm swap (no jump/rendezvous) — the ring stays.
- No request→service-instance affinity routing (still Redis-shard scope).
- No change to `ShardedTopKStore` random-shard reads or `RedisReadReplicaRouter` AZ routing.
- No automatic/elastic reshard decisions — a reshard is an explicit operator action.

---

## 2. Commands

```bash
mvn package -DskipTests
mvn test
mvn test -Dtest='Fnv1a*,ConsistentHashRing*,StableBucketer*,ShardTopology*,Sharded*'
# Docker-backed sharding/migration tests
mvn test -Dgroups=docker -Dtest='ShardTopology*,ShardedRecordStore*'
# FNV-1a constants must live in exactly one production file afterward
grep -rn "0xcbf29ce484222325L\|0x100000001b3L" src/main --include="*.java"
```

---

## 3. Project Structure

New + modified components under `com.recsys.infrastructure.redis.sharding`:

```
infrastructure/redis/sharding/
├── Fnv1a.java                 (NEW)  shared 64-bit FNV-1a: hash(byte[]) / hash(String)
├── ConsistentHashRing.java    (MOD)  uses Fnv1a; mapping bytes unchanged within a version
├── ShardTopology.java         (NEW)  immutable snapshot: version, shardCount, vnodes, ring, createdAtMs
├── ShardTopologyStore.java    (NEW)  Redis-backed load/publish of the authoritative snapshot
├── ShardTopologyProvider.java (NEW)  in-memory volatile current+previous snapshot; periodic refresh
├── ShardedRecordStore.java    (MOD)  generation-prefixed keys; write=current, read=current→previous(window)
└── SequenceGenerator.java     (MOD)  per-(generation,shard) sequence keys
application/experiment/StableBucketer.java  (MOD)  reuse Fnv1a accumulation; keep its fmix64 finalizer
api/online/OnlinePredictionServer.java       (MOD)  wire provider instead of a fixed ring
infrastructure/store/ShardedRecordService.java (MOD, optional) admin endpoint to publish a reshard
```

### Topology snapshot (the "consistent snapshot")
- **Redis key:** `shard:topology` → small JSON
  `{ "version": int, "shardCount": int, "vnodes": int, "createdAtMs": long, "prevVersion": int|null, "prevShardCount": int|null, "prevExpiresAtMs": long|null }`.
- `ShardTopology` is **immutable**; `ConsistentHashRing` is built once when a version is loaded.
- `ShardTopologyProvider` holds a `volatile ShardTopology current` (+ an optional `previous` kept
  until `prevExpiresAtMs`), refreshed every `SHARD_TOPOLOGY_REFRESH_SECONDS` (default 30, matching
  the gateway's 30 s DNS-TTL precedent). Lock-free reads; atomic reference swap on refresh.
- **Bootstrap:** if `shard:topology` is absent, the provider initializes version 1 from
  `SHARDED_RECORD_SHARD_COUNT` and publishes it (idempotent `SETNX`).

### Generation-prefixed keys
Every record key gains a generation segment so versions never collide:
- `sr:g{version}:rec:{shard}:{seq}`, `sr:g{version}:dev:{shard}:{deviceId}`,
  `sr:g{version}:stream:{shard}`, `sr:g{version}:seq:{shard}`.
- **Legacy fallback:** the existing unversioned keys (`sr:rec:...`) are read as a final fallback
  during the first dual-read window after rollout (mirrors `ShardedTopKStore`'s existing
  legacy-key fallback), so deploying the feature loses no in-flight TTL data.

### Read/write semantics
- **Write:** always to `current` generation (its ring → shard, its key prefix).
- **Read (device or shard):** query `current`; if a `previous` generation is still within its
  dual-read window, also query it and merge (dedup by `deviceId:seq`), preferring `current`.
  After `prevExpiresAtMs`, skip the previous generation (its data has TTL'd out).
- **Reshard (operator action):** a **guarded admin endpoint** `POST /shards/topology {shardCount}`
  (auth-protected, on the online server) publishes a new `shard:topology` with `version+1`, the new
  `shardCount`, and `prev*` = the outgoing version with `prevExpiresAtMs = now + maxRecordTtl`.
  Instances adopt it on their next refresh; writes immediately use the new generation; reads
  dual-read until the window closes. A direct `publish(shardCount)` method backs the endpoint and
  is reusable from a break-glass script if ever needed.

---

## 4. Code Style

- `Fnv1a`: `final`, private ctor, exact constants/UTF-8 unchanged (see Boundaries).
- `ShardTopology`: a `record` (or final class) — immutable; no setters; ring built in a factory.
- `ShardTopologyProvider`: lock-free hot path (`volatile` snapshot ref); refresh on a single
  daemon scheduler; fail-safe — on Redis error keep serving the last-good snapshot (never block
  the request path on topology I/O).
- Reuse existing patterns: env reads via `EnvConfig`; JSON via the project's existing `ObjectMapper`;
  Redis access via the same write/read `Pool<Jedis>` split already used by `ShardedRecordStore`.
- No new dependencies. `StableBucketer` keeps its own `fmix64` finalizer inline (YAGNI).

---

## 5. Testing Strategy

- **Bar: `mvn test` green**, all existing `ConsistentHashRing*`, `StableBucketer*`, `Sharded*` pass.
- **`Fnv1aTest` (golden values):** pin `Fnv1a.hash("v0:0")`, `"v0:1"`, `"device-123"` to the exact
  longs produced by today's `ConsistentHashRing.fnv1a` — guards the dedup against bit-drift.
- **Within-version mapping stability:** for a fixed topology version, `shardFor(id)` matches a
  captured expected map (the ring is unchanged within a generation).
- **`StableBucketer.slot(...)`** golden outputs unchanged after the FNV-1a reuse.
- **Topology versioning (`ShardTopologyStoreTest`, `ShardTopologyProviderTest`):** load/publish
  round-trip; SETNX bootstrap; refresh adopts a newer version; provider keeps `previous` until
  `prevExpiresAtMs` then drops it; Redis-down → last-good snapshot retained.
- **Dual-read migration (`@Tag("docker")`):** write records under gen 1; publish gen 2 with a
  larger shardCount; assert a record whose key *moved* is still found via the previous-generation
  fallback **inside** the window and **not found** after `prevExpiresAtMs`; assert new writes land
  in gen-2 keys; assert legacy unversioned keys are read during the first window only.
- **Concurrency:** a reader sees a consistent snapshot across a refresh (no torn read of
  shardCount vs ring) — exercise an atomic swap under concurrent `shardFor`/read calls.
- **Sanity grep (§2):** FNV-1a constants in exactly one production file.

---

## 6. Boundaries

**Always:**
- Keep the per-generation mapping (hash bytes, vnode key `"v{i}:{shard}"`, default 150 vnodes,
  TreeMap ceiling) **identical within a version**; only crossing a version may remap.
- No data loss for records still within their TTL across a reshard (dual-read window ≥ max TTL).
- Topology I/O never blocks the request hot path; on Redis failure, serve the last-good snapshot.
- `mvn test` green; feature branch; open a **PR** — never merge to `main` directly.

**Resolved (was "ask first"):**
- Reshard trigger = **guarded admin HTTP endpoint** `POST /shards/topology {shardCount}`, backed by
  a reusable `publish(shardCount)` method.
- Refresh interval = **30 s**; dual-read window = **configured max record TTL**.
- Generation key scheme = **`sr:g{version}:...`**; legacy unversioned-key fallback = **first window only**.

**Still flag if encountered:**
- The auth mechanism for the admin endpoint — reuse the existing online-server guard/pattern; if none
  fits, surface options rather than inventing one.

**Never:**
- Change the FNV-1a constants, byte/UTF-8 handling, vnode key format, default vnode count, the
  TreeMap lookup, or `StableBucketer.KEYSPACE` (10000).
- Swap the hashing algorithm (no jump/rendezvous) or add request→instance routing.
- Make reshard decisions automatically/elastically — operator-triggered only.
- Touch `ShardedTopKStore` random-shard logic or `RedisReadReplicaRouter` AZ routing.

---

## 7. Suggested phasing (each phase independently shippable + green)

1. **FNV-1a dedup** — extract `Fnv1a`, wire `ConsistentHashRing` + `StableBucketer`, golden tests.
   Behavior-identical. (Smallest, zero-risk.)
2. **Topology snapshot read path** — `ShardTopology` + `ShardTopologyStore` + `ShardTopologyProvider`,
   bootstrap from env, instances read shardCount/ring from the snapshot. Still single version →
   behavior-identical (keys still effectively gen 1; introduce the `sr:g1:` prefix + legacy fallback).
3. **Generation keys + dual-read + reshard publish** — version-scoped keys, dual-read window,
   publish-new-version path + (optional) admin endpoint, migration tests. This is the feature.

> Phases 2–3 carry real distributed-systems risk (snapshot consistency, dual-read correctness,
> concurrency). If you'd rather not take that on now, Phase 1 alone is the safe dedup we scoped
> originally and can ship independently.
