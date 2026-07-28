# KV-store sharp edges — design

**Date:** 2026-07-28
**Status:** approved, ready for planning
**Scope:** four defects found while auditing the Redis key-value layer

## Problem

An audit of the key-value store implementation (`infrastructure/redis/`,
`infrastructure/redis/sharding/`, `infrastructure/store/`, `infrastructure/cache/`)
surfaced seven observations. Four were selected for repair. They share a root cause:
**the code and its stated intent have drifted apart.**

Three of the four are dead machinery that the documentation describes as live. That is
worse than absent machinery. `docs/system_design/03_DB_Scaling_Sharding.md` §2 and
`docs/system_design/14_Partitioning.md` currently tell an operator that hot-key sharding
protects the trending keys, and that a startup guard protects sequence-number integrity.
Neither is true in the deployed system.

The three observations deliberately excluded from this spec are recorded in
[Out of scope](#out-of-scope) so they are not silently lost.

## Findings and evidence

### F1 — the top-K shard fan-out is never written

`ShardedTopKStore` documents itself as spreading trending-key read QPS across N replica
keys (`topk:<window>:s0..s3`). Nothing writes those keys.

- `seedAllShards` has exactly one caller in the repository: `ShardedTopKStoreTest`.
- All three production wiring sites use the single-executor constructor, so the AZ-aware
  read/write split is also unexercised:
  `RecSysServer.java:92`, `OnlinePredictionServer.java:115`, `ModelRuntimeProvider.java:162`.
- Since commit `01870d2` ("honor canonical top-k snapshots"), `fetchFromRandomShard`
  first evaluates `READ_CANONICAL_SNAPSHOT` against `topk:{window}:value`. The Flink sink
  (`OnlineFeatureStreamingJob.java:1072`) writes that key atomically, so the canonical
  branch always hits and the shard and legacy branches are unreachable in production.

The practical read path today is: JVM cache (2 s) → miss → one Lua `EVAL` against a single
key per window. Hot-key sharding is not happening. The protection that *is* real comes from
the local cache, singleflight, and serve-stale.

### F2 — four unbounded in-memory maps

Plain `ConcurrentHashMap`s with no size cap and no eviction sweep. Entries expire
*logically* — a timestamp is compared on read — but are removed only on a write or a
completed refresh. Traffic touching many distinct or absent IDs grows them without limit.

| Map | File | Holds |
|---|---|---|
| `cache` | `LogicalExpiryEmbeddingCache.java:52` | full `float[]` embeddings — largest exposure |
| `nullSentinels` | `LogicalExpiryEmbeddingCache.java:51` | absent-ID timestamps |
| `refreshing` | `LogicalExpiryEmbeddingCache.java:54` | in-flight refresh flags |
| `nullSentinels` | `MultiLevelEmbeddingCache.java:51` | absent-ID timestamps |

`OnlineFeatureStore` already solved exactly this problem by moving to a bounded Caffeine
cache (`OnlineFeatureStore.java:84-88`), with a comment noting it "replaces the former
inline O(N) evictIfNeeded scan". The embedding caches did not follow.

`MultiLevelEmbeddingCache.l1` is **not** in scope: it is already capped at `l1Capacity`.
Its arbitrary-entry eviction is deliberate ("this L1 is purpose-built for hot keys") and
changing it would alter promotion semantics rather than fix unboundedness.

### F3 — the sequence-counter guard is inert and generation-blind

`SequenceGenerator.ensureCounterValid` guards a real hazard: after a Redis partial flush a
stale counter reissues sequence numbers, the device-index `ZADD NX` becomes a no-op, and
the record is silently dropped. Its javadoc says "Call once at startup per shard before
accepting writes."

Nothing calls it. Its only caller is `SequenceGeneratorTest`.

It is also wrong for any topology generation ≥ 2: it hardcodes `seqKey(1, shardIndex)` and
scans the unversioned `prefix + "dev:"` pattern, so after a reshard it inspects a keyspace
that new writes no longer use. Its `shardCount` parameter is unused.

### F4 — replica-selection javadoc contradicts the code

`RedisReadReplicaRouter.readable()` returns `replicas.get(0)`, a stable choice. The class
javadoc (`:15`) and the method javadoc (`:52`) both promise "a randomly selected replica"
for load balancing.

The stable behavior is correct and deliberate. It arrived in `fbe54cd`
("wire consistency health signals end-to-end"), which added a "per-process replica-lag
probe with correlated sequence check". `readable()` and `probeReadable()` must resolve to
the same node or the lag measurement is meaningless. Only the documentation is wrong.

## Design

Four independent, stacked pull requests. Each is separately reviewable and revertable.

### PR 1 — `topk`: remove the vestigial shard fan-out

Delete the unreachable machinery, keep everything that does real work.

**Removed:** `seedAllShards`, `shardKey`, `DEFAULT_SHARD_COUNT`, the `shardCount` field and
constructor parameters, `legacyFallbackFetches`, and the shard-read branch of
`fetchFromRandomShard`.

**Kept:** the 2 s fresh / 60 s stale JVM cache, singleflight, serve-stale-on-error, the
canonical Lua read, `HotKeyDetector`, and the hit-rate counters.

**Also kept — the legacy `topk:<window>` fallback.** It is reachable on a cold Redis before
Flink's first snapshot lands, which is a real startup window, so it is not dead code.

**Deviation from the original scoping — `legacyFallbackFetches` is kept.** It was
listed for removal, but since the legacy fallback branch survives, the counter that
reports how often it serves traffic remains meaningful. With no shard layer between
the canonical read and the fallback, a non-zero value now means exactly one thing:
the canonical snapshot is absent.

**Not done here — renaming the class.** `ShardedTopKStore` becomes a slight misnomer.
Renaming touches three servers, five documents, and two test classes for no behavioral gain
and would bury the substantive diff. Recorded as a follow-up.

**Documentation:** rewrite `03_DB_Scaling_Sharding.md` §2 and the affected
`14_Partitioning.md` paragraphs to describe the canonical-snapshot read path. Do not
renumber any `##` heading. Check `02_Caching.md`, `15_Eventual_Consistency.md`,
`17_Scalability.md`, and `10_MicroServices.md` for claims invalidated by the change.

### PR 2 — `cache`: bound the four unbounded maps

Convert each to a size-capped Caffeine cache with `expireAfterWrite`, matching the
`OnlineFeatureStore` precedent.

| Map | Bound |
|---|---|
| `LogicalExpiry.cache` | `maximumSize` + `expireAfterWrite(softTtl × 2)` |
| `LogicalExpiry.nullSentinels` | `maximumSize` + `expireAfterWrite(nullSentinelTtl)` |
| `LogicalExpiry.refreshing` | `maximumSize` + short `expireAfterWrite`, which doubles as a stuck-refresh backstop |
| `MultiLevel.nullSentinels` | `maximumSize` + `expireAfterWrite(30 s)` |

Sizes are env-configurable, defaulting to `10_000` to match the existing
`ONLINE_FEATURE_CACHE_MAX_USERS` precedent:
`LOGICAL_EXPIRY_CACHE_MAX_ENTRIES` bounds `LogicalExpiry.cache`, and
`EMBEDDING_NULL_SENTINEL_MAX_ENTRIES` bounds both `nullSentinels` maps.
`LogicalExpiry.refreshing` is bounded by the same
`LOGICAL_EXPIRY_CACHE_MAX_ENTRIES` value, since it can never hold more distinct
IDs than the cache it refreshes.

The stored `softExpiresAtMs` and sentinel-expiry timestamps are **retained**. Caffeine's
`expireAfterWrite` governs eviction; the timestamps govern the fresh-versus-stale decision,
which is a distinct question. Removing them would collapse serve-stale behavior.

`MultiLevel.l1` is unchanged.

### PR 3 — `sharding`: make the sequence guard real

**Signature:** `ensureCounterValid(int version, int shardIndex)`. The unused `shardCount`
parameter is dropped. Both the sequence key and the device-key scan pattern are built
through `Generations.keyPrefix(version)`, so the guard follows the active generation.

**Wiring:** `OnlinePredictionServer`, immediately after `topologyProvider.start()`
(`OnlinePredictionServer.java:176`), for each shard of the current topology.

**Off the boot thread.** The guard SCANs every device ZSet in a shard and issues a
`ZREVRANGEBYSCORE` per key. Running it synchronously would block server startup in
proportion to keyspace size. It runs in the background, wall-clock budgeted in the same
shape as `REDIS_LOADALL_TIMEOUT_MS`, and fails soft with a warning.

**Why a truncated run is safe:** a partial scan can only *under*-estimate `maxSeq`, and the
guard only ever raises the counter, never lowers it. A budget-exhausted run therefore
degrades to today's behavior — no repair — rather than corrupting the counter.

**Configuration:** `SHARDED_RECORD_SEQ_REPAIR_ENABLED` (default `true`) and
`SHARDED_RECORD_SEQ_REPAIR_TIMEOUT_MS`.

### PR 4 — `docs`: correct the replica-selection javadoc

Comments only, no behavior change. Correct `RedisReadReplicaRouter.java:15` and `:52` to
describe stable first-configured-replica selection, and state *why* stability is required —
that the lag probe and real reads must observe the same node — so the behavior is not
"fixed" back to random by a later reader.

## Testing

Test-driven throughout: a failing test precedes each implementation change.

New coverage:

- **F1** — a canonical-read test asserting no shard key is touched; a cold-Redis test
  asserting the legacy fallback still resolves.
- **F2** — unbounded-growth regression tests per map: insert far beyond the cap, assert the
  cache stays bounded. Serve-stale and negative-caching behavior must be re-asserted to
  prove the timestamp semantics survived the Caffeine migration.
- **F3** — a generation-2 test asserting the guard reads and writes `g2:`-prefixed keys; a
  budget-exhaustion test asserting partial repair leaves the counter valid; an
  `enabled=false` test asserting no Redis I/O at startup.
- **F4** — none. Comment-only change.

### CI gating

The PR gate runs the `resilience` Maven profile, which is an allow-list
(`pom.xml:330-345`), not a full run. Nothing under `infrastructure/cache/`,
`infrastructure/redis/`, or `infrastructure/redis/sharding/` is currently in it, so **tests
added by this work would not block a merge by default.**

Each PR adds its own test classes to that `<includes>` list. This is a required part of
each PR, not a follow-up — an ungated regression test provides no protection.

## Out of scope

Recorded from the same audit, deliberately not addressed here:

- **`readAllShards` is unbounded.** `ShardedRecordStore.java:209-225` drains every shard
  fully into one heap list with no cap, backing the admin `GET /shards/shard`. With
  `STREAM_MAXLEN = 1_000_000` per shard, one operator call can pull a great deal. Gated by
  admin token, but not bounded.
- **`ShardCursor` carries two incompatible meanings.** A ZSET score in `readDeviceAt`
  (`:156`), a Redis stream ID in `readShard` (`:192`). Same type, no discriminator; a
  cursor crossing between paths fails silently or throws `NumberFormatException`. Fixing it
  is an API-shape change to the sharding read path.
- **Pipeline connections return to the pool unvalidated.** `LettuceRedisExecutor.java:113-130`
  — if the callback throws after queueing but before `flushCommands()`,
  `setAutoFlushCommands(true)` does not flush the buffered queue and the connection is
  returned anyway. The timed-read path directly above it does invalidate on trouble
  (`:105-107`). No test exercises a throwing pipeline callback.

Follow-up from PR 1: rename `ShardedTopKStore` to reflect that it no longer shards.
