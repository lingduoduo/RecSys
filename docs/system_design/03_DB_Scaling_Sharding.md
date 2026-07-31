# Database Scaling & Sharding in Recsys-Backend-Service

An investigation of the sharding *subsystem* — the two sharded data stores the system
runs over Redis, their key schemas and operations, the versioned topology that lets them
be resharded at runtime, and **how each store is actually scaled** (§6). Where
[06_Consistent_Hashing](06_Consistent_Hashing.md) explains the *ring algorithm* and
[14_Partitioning](14_Partitioning.md) frames sharding as one of five partition
*dimensions*, this doc is the **data-store view**: how records and trending are laid out
across shards, written, read, rebalanced, and grown.

Scope note: [17_Scalability §3](17_Scalability.md#3-data-tier-scaling--horizontal-everywhere)
covers data-tier scaling as one part of a whole-system picture that also includes compute
and overload protection. §6 here is the narrower, store-level companion — which knob to
turn on *which store*, what each turn actually buys, and where it stops working.

## The big picture

One store shards. A second one used to, and no longer does:

| Store | Shards *what* | Sharding kind | Purpose |
|---|---|---|---|
| [`ShardedRecordStore`](../../src/main/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStore.java) | per-device event/feature/log records | **partition** — consistent-hash by `deviceId` | spread write/storage load; co-locate a device's records |
| [`ShardedTopKStore`](../../src/main/java/com/recsys/infrastructure/redis/ShardedTopKStore.java) | nothing — one canonical snapshot per window | **none** (name is historical) | hot-key load absorbed by a 2 s JVM cache + single-flight |

The record store places keys by the shared FNV-1a ring, its shard count is a
**versioned, runtime-swappable topology** (no redeploy to reshard), and — importantly —
the "shards" are *logical key prefixes on a single Redis primary* (with AZ replicas for
reads), not separate nodes.
That physical reality is the same one described in
[14_Partitioning](14_Partitioning.md#where-the-shards-physically-live--redis-and-mysql)
and served by [04_Replication](04_Replication.md).

## 1. `ShardedRecordStore` — the sharded record database

This is the partition store: per-device records distributed across N shards by the
consistent-hash ring.

**Key schema.** Each shard holds four Redis structures, generation-prefixed
(`Generations.keyPrefix` → `""` for gen 1, `"g{version}:"` for gen ≥2):

- `sr:g{v}:seq:{shard}` — an **INCR** counter that assigns each write's sequence number;
- `sr:g{v}:rec:{shard}:{seq}` — an **HSET** with the full record;
- `sr:g{v}:dev:{shard}:{device}` — a **ZADD** device index (score = sequence) for
  ordered per-device reads;
- `sr:g{v}:stream:{shard}` — an **XADD** stream (approx-trimmed at 1,000,000) for ordered
  replay.

Under key format 2 the shard index itself carries a hash tag, e.g. `sr:g3:seq:{0}`,
`sr:g3:rec:{0}:42`, `sr:g3:dev:{0}:dev-1`, `sr:g3:stream:{0}` — all four land in the same
Cluster slot, which is what the write script below depends on.

**Write fan-out.** `doWrite` resolves `topology.current().shardFor(device)` and evaluates a
single Lua script against that one shard on the **primary**: it INCRs the shard's sequence
counter, claims the device index, writes the record hash (with TTL) and appends to the
shard stream — atomically, in one round-trip. A `ZADD NX` that returns 0 means a duplicate
`eventId`, and the script returns at that point without writing anything else, so writes
are idempotent and safe to retry. Under key format 2 all four keys share a hash tag and
therefore one Cluster slot, which is what makes the multi-key script legal.

**Reads — two shapes, two consistency levels.**

- **Per-device reads** (`readDevice`, behind `GET /shards/device`) walk the device ZSET
  and, during a reshard window, **dual-read** the current *and* previous generation and
  merge them (dedupe by `(deviceId, eventId)`, current wins) — at-least-once, not
  lossless; see sharp edge 8 for what that guarantees and what it doesn't.
- **Shard scans** (`readShard`, behind `GET /shards/shard`) are
  **current-generation only** and silently miss previous-generation records — a known
  sharp edge.

The HTTP façade
([`ShardedRecordService`](../../src/main/java/com/recsys/infrastructure/store/ShardedRecordService.java))
mounts `/shards/` on port 7010.

## 2. `ShardedTopKStore` — sharded trending

> **Changed 2026-07-28 — the shard fan-out was removed.** This section previously
> described N identical replica keys per window (`topk:<window>:s0..s3`) written by
> `seedAllShards`. Nothing had written them since the canonical snapshot path landed in
> `01870d2`, so every read already resolved through `topk:{window}:value`. The dead
> machinery was deleted; see
> `docs/superpowers/specs/2026-07-28-kv-store-sharp-edges-design.md`. The class name is
> now historical.

Despite the name, the trending store does not shard. Each window is a **single canonical
snapshot** that the Flink job writes atomically:

- **Writes** come from the Flink sink
  ([`OnlineFeatureStreamingJob`](../../src/main/java/com/recsys/online/flink/OnlineFeatureStreamingJob.java)),
  which sets `topk:{window}:value` and its guard `topk:{window}:version` in one Lua
  script, so a reader never observes a half-written snapshot. Note the `{window}` hash
  tag: it co-locates the value and its version in the same Redis Cluster slot, which is
  what makes the guarded read possible.
- **Reads** evaluate a small Lua script that returns the snapshot only when the version
  key exists — an empty-but-present snapshot is authoritative, and is cached as such.
  Absent a canonical snapshot, the read falls back to the unversioned `topk:<window>`
  key, which matters on a cold Redis before Flink's first write.

What protects the key is not replication but three layers in front of it: a per-window
local cache (`ONLINE_TOPK_CACHE_TTL_MS`, default 2000), **single-flight** so only one
thread per JVM refills it, and serve-stale until `ONLINE_TOPK_STALE_TTL_MS` (default
60000) when Redis fails — fail-open. With a 2 s cache, each instance contributes at most
0.5 reads/sec per window regardless of request volume.

The partition-angle discussion is in
[14_Partitioning §2](14_Partitioning.md#2-windowed-top-k-replica-sharding).

## 3. Versioned topology & online reshard

The shard count is not a static config — it's an authoritative **versioned snapshot** in
Redis (`shard:topology`,
[`ShardTopologyStore`](../../src/main/java/com/recsys/infrastructure/redis/sharding/ShardTopologyStore.java)):
`bootstrap` is a `SETNX` of version 1 seeded from `SHARDED_RECORD_SHARD_COUNT` (default
**2**), and `publishReshard` is an atomic Lua read-modify-write that bumps `version + 1`
and stamps the previous generation's expiry.
[`ShardTopologyProvider`](../../src/main/java/com/recsys/infrastructure/redis/sharding/ShardTopologyProvider.java)
refreshes every `SHARD_TOPOLOGY_REFRESH_SECONDS` (default **30**) into a lock-free
`volatile` snapshot, keeping the last-good view on error so lookups never block the
request path.

A reshard is online because of two mechanisms (both detailed in
[14_Partitioning](14_Partitioning.md#versioned-topology-and-online-reshard)): **generation-
scoped keys** write the new topology into a disjoint keyspace, and a **bounded dual-read
window** (`SHARDED_RECORD_MAX_TTL_SECONDS`, default **24 h**) lets per-device reads merge
both generations until the old one TTLs out. Because placement is consistent hashing, a
resize moves only ~1/N of keys.

```bash
# Publish a new generation (version+1) with 4 shards. Operator-only, shared-secret guarded.
curl -X POST http://localhost:7010/shards/topology \
  -H "X-Admin-Token: $SHARD_ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"shardCount":4}'
# {"version":2,"shardCount":4,"prevVersion":1,"prevExpiresAtMs":...}
```

The reshard endpoint is `AdminTokenGuard`-gated and **fails closed** (`403`) unless
`SHARD_ADMIN_TOKEN` is set and the header matches.

| Env var | Default | Purpose |
|---|---:|---|
| `SHARDED_RECORD_SHARD_COUNT` | `2` | Bootstrap record-shard count (version 1) |
| `SHARD_TOPOLOGY_REFRESH_SECONDS` | `30` | How often each instance refreshes `shard:topology` |
| `SHARDED_RECORD_MAX_TTL_SECONDS` | `86400` | Record TTL **and** the post-reshard dual-read window |
| `SHARD_ADMIN_TOKEN` | _(unset → reshard disabled)_ | Reshard / shard-dump authz |
| `ONLINE_TOPK_CACHE_TTL_MS` / `ONLINE_TOPK_STALE_TTL_MS` | `2000` / `60000` | Top-K local cache / stale-serve windows |

## 4. How it composes

Database sharding here is one layer of a stack, not a standalone system:

- **Placement** is the FNV-1a consistent-hash ring — [06_Consistent_Hashing](06_Consistent_Hashing.md)
  (150 virtual nodes, minimal ~1/N remap).
- **Physical storage** is a single Redis primary with AZ-aware read replicas —
  [04_Replication](04_Replication.md); a "shard" is a key prefix, not a node, so this is
  logical/client-side sharding that is *cluster-ready* but not cluster-deployed today.
- **Consistency** during a reshard (generation dual-read, the 30 s propagation gap) is a
  CAP/eventual-consistency concern — [05_CAP](05_CAP.md) and
  [15_Eventual_Consistency](15_Eventual_Consistency.md).
- **Partitioning context** — the record store is one of the partition dimensions in
  [14_Partitioning](14_Partitioning.md).

## 5. Testing

- **Ring / topology** — `ConsistentHashRingTest` (distribution + golden maps),
  `ShardTopologyStoreTest` (`@Tag("docker")`: bootstrap `SETNX` + reshard Lua),
  `ShardTopologyProviderTest` (last-good on refresh failure), `SequenceGeneratorTest`.
- **Record store** — `ShardedRecordStoreGenerationKeyTest` (generation prefixing),
  `ShardedRecordStoreDualReadTest` (the dual-read merge window),
  `ShardedRecordStoreReplicaRoutingTest`, and `ShardedRecordServiceReshardTest` (the
  reshard flow).
- **Top-K store** — `ShardedTopKStoreTest`, `ShardedTopKStoreTtlConfigTest`.

## 6. Scaling — the levers, and what each actually buys

The two stores solve opposite problems (see The big picture), so they scale by different
mechanisms. The honest summary up front: **most levers here buy contention-spreading and
read distribution, not multi-node capacity.** Everything below still lands on one Redis
primary — a deliberate stage rather than an oversight, for the reason given under "What
sharding here does *not* buy".

### The levers

| # | Lever | Store | Mechanism | Buys | Bounded by |
|---|---|---|---|---|---|
| 1 | **Add shards** (reshard) | record | `POST /shards/topology`, online, no redeploy (§3) | even keyspace spread; shorter per-shard ZSETs/streams; more total retained stream | the single primary |
| 2 | **JVM hot cache + single-flight** | top-K | `window → CachedIds`, TTL-bounded, one refill per JVM (§2) | caps each instance at ~0.5 reads/sec/window regardless of request volume | staleness ≤ TTL |
| 3 | **AZ-local replica reads** | both | separate write/read executors — writes to primary, reads to an AZ-local replica | read QPS off the primary; less cross-AZ traffic | replica lag ([04](04_Replication.md)) |
| 4 | **Vertical** | Redis | larger ElastiCache node | the only lever that raises *real* single-primary capacity | one node's CPU/RAM |
| 5 | **Index + keyset** | MySQL | covering indexes, HMAC cursor pagination | scales *within* one table, no partitioning | pool size, one instance |

Defaults worth knowing: the ring uses **150 virtual nodes** per shard
([`ConsistentHashRing`](../../src/main/java/com/recsys/infrastructure/redis/sharding/ConsistentHashRing.java)),
and each record shard's stream is approx-trimmed at **1,000,000** entries — so lever 1
also multiplies total retained replay capacity, not just spread.

### Lever 2 in detail — the cache is doing all of the work

For the trending windows the JVM cache is the *only* scaling mechanism: a **2 s** cache
TTL (`ONLINE_TOPK_CACHE_TTL_MS`) with **60 s** serve-stale-on-error
(`ONLINE_TOPK_STALE_TTL_MS`) plus inline single-flight means Redis sees only refreshes.
An instance therefore issues at most one read per window per 2 s no matter how much
traffic it serves, so per-key QPS scales with *instance count*, not request volume.

This used to be phrased as "the cache does most of the work, and 4 replica shards spread
the leftover refreshes." The replica shards were never written, so the cache was always
doing all of it — see the note under §2.

### Lever 3 in detail — replica routing

[`RedisReadReplicaRouter`](../../src/main/java/com/recsys/infrastructure/redis/RedisReadReplicaRouter.java)
+ `RoutingRedisExecutor` send writes to the primary and reads to the **same-AZ** replica —
lowest latency, and it survives loss of the primary's AZ. Replicas are declared via
`REDIS_REPLICA_NODES` (`host:port@az`) against `AWS_AZ`, each with its own connection pool
(`REDIS_POOL_MAX_TOTAL`, default **50**). Consistency implications are
[04_Replication](04_Replication.md)'s subject, not this doc's.

### Keeping cache misses from capping scale

Read levers only pay off if a miss storm cannot undo them. Three guards, all in
`infrastructure/resilience`:

- **`SingleFlight`** — per-key miss dedup, so N concurrent misses become one backend read.
- **`HotKeyDetector`** — flags keys above **500 accesses/s**, tracking up to **100,000**
  keys.
- **`BloomFilterGuard`** — skips Redis entirely for definitely-absent IDs, so lookups for
  nonexistent items never reach the store.

Without these, adding shards or replicas raises the ceiling while a single cold hot key
still concentrates load on one shard.

### What sharding here does *not* buy

Lever 1 does **not** add nodes. The record store holds a single `RedisExecutor`, and its
shards are key prefixes (`sr:g{v}:rec:{shard}:…`) on one Sentinel primary — not Redis
Cluster hash slots. The full physical account is in
[14_Partitioning](14_Partitioning.md#where-the-shards-physically-live--redis-and-mysql).

The payoff is that the ring makes the system **ready** to map logical shards onto separate
nodes without a data migration: the placement function and the generation-versioned
reshard already exist, so that move becomes a routing change rather than a rewrite. That
readiness is conditional on key format: a generation still on format 1 carries no hash
tag, so its shard's record, device-index, and stream keys are not guaranteed to share a
Cluster slot (sharp edge 7); only a generation resharded onto format 2 gets keys
co-located well enough for the atomic multi-key script (sharp edge 6). Read lever 1 as
an investment that makes future horizontal scaling cheap, and lever 4 as the one that adds
capacity today.

### What breaks first

- **Record store — primary write throughput.** Every write is one Lua script (INCR +
  HSET + ZADD + XADD) against one primary. Adding shards spreads *which keys* are
  touched; it does not reduce total ops/sec against that primary. This is the first real
  wall.
- **Top-K — a cold or stampeding JVM cache.** With the cache warm, Redis sees little
  traffic; the replicas matter precisely when it is cold, and that is when read QPS spikes
  hardest.
- **MySQL — the pool, deliberately.** `MYSQL_POOL_MAX_SIZE` defaults to **5**, on a
  **read-only** HikariCP pool built lazily on first use, with a **2 s** query timeout and
  at most **2** read attempts. That is a small budget on purpose: MySQL is an opt-in
  (`MYSQL_ENABLED=false` by default) relational read model, not a primary serving store,
  and the tight ceiling keeps it from becoming one by accident.

### Scaling out, in practice

Resharding is the §3 procedure. Two operational notes that matter when you use it to
scale rather than to rebalance:

- **A reshard moves ~1/N of keys and costs a dual-read window**, so it is cheap but not
  free — during the window, per-device reads merge two generations.
- **Do not verify a migration with `GET /shards/shard`.** Shard scans are
  generation-current and will under-report while the previous generation is still live
  (sharp edge 1). Per-device reads are the ones that dual-read.

## 7. Cross-shard atomicity — where the transaction stops

The usual way to make two writes all-or-nothing is to wrap them in one database
transaction: deduct inventory and insert the order row, commit, and let the database
own the consistency guarantee. That works, and this repo relies on it — in exactly one
place.

[`MySqlSagaStateStore.saveWithEvent`](../../src/main/java/com/recsys/infrastructure/saga/MySqlSagaStateStore.java)
mutates two different tables — `saga_instance` and `event_outbox` — inside a single
[`TransactionalMySql`](../../src/main/java/com/recsys/infrastructure/persistence/TransactionalMySql.java)
transaction. Both rows land or neither does. That is legal only because MySQL here is
deliberately **un-sharded**
([14_Partitioning](14_Partitioning.md#where-the-shards-physically-live--redis-and-mysql)).
The atomicity is not free; it is bought by not sharding.

### Three ways sharding breaks it

Usually only the first is named. All three are live concerns here.

1. **Separate shards are separate transaction domains.** Inventory hashed by item and
   orders hashed by user land in different places. There is no XA and no two-phase
   commit anywhere in this codebase, and the writable MySQL boundary holds a single
   JDBC URL and one pool.
2. **A multi-key write inside one shard is atomic now, but only within that shard.** One
   Lua script commits the record store's sequence, device-index, and stream writes
   together (§1, sharp edge 6). That still stops at the shard boundary — nothing here
   makes two *different* shards atomic.
3. **The shard map is itself eventually consistent.** Topology propagates across the
   fleet over ~30 s and a reshard opens a 24 h dual-read window (§3). A transaction
   cannot span a boundary that is still moving.

### What the system uses instead

| Mechanism | Where | Shape for "deduct inventory, create order" |
|---|---|---|
| Local transaction + outbox | [`DurableEventPublisher`](../../src/main/java/com/recsys/application/outbox/DurableEventPublisher.java), `OutboxRelay` | Order row and the "deduct inventory" outbox row commit together in one database; delivery is asynchronous, leased, and retried |
| Compensation saga | [`SagaOrchestrators`](../../src/main/java/com/recsys/application/saga/SagaOrchestrators.java) `Standard` | Reserve inventory, then create the order; on failure compensate completed steps in reverse |
| TCC | `SagaOrchestrators` `Tcc` | Try reserves without making the reservation externally final; Confirm commits all; Cancel releases every unconfirmed reservation |

For inventory specifically **TCC fits better than the plain saga**, because compensation
is not rollback. Between a reserve step committing and its compensation running, every
other reader sees stock that was never actually sold. TCC's Try holds a reservation that
is not yet externally final, which closes that window — at the cost of modelling
`available` and `reserved` separately.

### What you now owe, that the transaction gave you free

- **Idempotency keys.** Participants key on saga id plus step name, plus the phase for
  TCC, because the event path is at-least-once and replay is expected.
- **Optimistic concurrency instead of row locks.** Saga state advances under
  `WHERE saga_id = ? AND version = ?`, raising a conflict rather than blocking. That is
  the sharded-world substitute for `SELECT … FOR UPDATE`.
- **A failure state ACID never had.** Compensation and cancel are deliberately
  best-effort: every step is attempted, errors accumulate, and the saga still fails. A
  compensation that itself fails leaves real inconsistency for an operator to resolve.

### Where it bottoms out

The saga machinery is not an escape from transactions — it is built on one. The
coordinator needs its state row and its outbox row to commit atomically, or it can lose
track of a workflow it already started. So the design bottoms out at exactly one
un-sharded database.

The practical rule, and the arrangement this repo already has: **shard the high-volume
aggregates, and keep coordinator state and its outbox together and un-sharded.** One
consequence worth planning for — the outbox relay claims work through a single index on
a single table, so per-shard outboxes would need per-shard relays and would give up
global delivery ordering.

Note that the saga orchestrators are reference machinery: no production request path
constructs one today.

## Sharp edges — notes

1. **Shard scans drop previous-generation data during a reshard.** Only per-device reads
   dual-read; `GET /shards/shard` (`readShard`) is current-generation only.
2. **"Shards" are logical, on one primary.** The ring is cluster-ready, but today all
   shards are key prefixes on a single Sentinel primary — the win is contention-spreading
   and reshardability, not multi-node capacity. "Cluster-ready" itself is conditional on
   key format (see sharp edge 7).
3. **Top-K does not shard at all, despite the class name.** Each window is one canonical
   snapshot key; the JVM cache and single-flight are what keep it off Redis. The replica
   keys this document used to describe were removed on 2026-07-28 — nothing had written
   them since the canonical path landed.
4. **Reshard is operator-gated and generation-scoped.** It needs `SHARD_ADMIN_TOKEN` and
   only moves ~1/N of keys, but there's a ~30 s fleet propagation gap where instances
   straddle generations.
5. **The record store and MySQL are different things.** This is Redis record sharding;
   the relational catalog is a single un-sharded MySQL read model (see
   [14_Partitioning](14_Partitioning.md#where-the-shards-physically-live--redis-and-mysql)).
6. **A single-shard write is atomic; a cross-shard one is not.** One Lua script assigns the
   sequence and writes all three structures together, so a partial write is no longer
   possible within a shard. Nothing makes two *different* shards atomic — that is §7's
   subject.
7. **Key format is per generation, and format 1 is not Cluster-safe.** Generations published
   after the atomic-write change tag the shard index (`sr:g3:rec:{0}:42`) so a shard's keys
   share one slot. Generations created before it stay untagged for their whole life. An
   existing deployment therefore keeps a non-Cluster-safe keyspace until an operator
   publishes a reshard — deploying the code alone does not migrate it.
8. **The dual-read merge identifies records by `eventId`, and its page cap is soft.**
   Sequence numbers are per generation — each generation's counter starts again at 1 — so
   they are not an identity and not a total order across a migration window. The merge
   therefore dedupes on `(deviceId, eventId)`, the device index's own member. Two
   consequences. A merged page never ends part-way through a group of records sharing one
   sequence, because the cursor *is* a sequence and a straggler at that same sequence could
   never be reached by a later page — so a page may return `limit + 1` records during a
   window. And a record whose `eventId` exists in both generations at different sequences
   can still be skipped or delivered twice: the merge sees one page at a time and cannot
   know the other generation holds the same event further along. That case needs the same
   `eventId` written on both sides of a reshard, and reads remain at-least-once, not
   exactly-once, for its duration.
