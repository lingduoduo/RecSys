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

Two distinct sharded stores share the same Redis and the same placement algorithm but
solve opposite problems:

| Store | Shards *what* | Sharding kind | Purpose |
|---|---|---|---|
| [`ShardedRecordStore`](../../src/main/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStore.java) | per-device event/feature/log records | **partition** — consistent-hash by `deviceId` | spread write/storage load; co-locate a device's records |
| [`ShardedTopKStore`](../../src/main/java/com/recsys/infrastructure/redis/ShardedTopKStore.java) | each trending window's sorted set | **replica** — N identical copies | spread hot-key *read* QPS |

Both share three properties: keys are placed by the shared FNV-1a ring (record store) or
a fixed shard count (top-K), the shard count is a **versioned, runtime-swappable
topology** (no redeploy to reshard), and — importantly — the "shards" are *logical key
prefixes on a single Redis primary* (with AZ replicas for reads), not separate nodes.
That physical reality is the same one described in
[14_Partitioning](14_Partitioning.md#where-the-shards-physically-live--redis-and-mysql)
and served by [04_Replication](04_Replication.md).

## 1. `ShardedRecordStore` — the sharded record database

This is the partition store: per-device records distributed across N shards by the
consistent-hash ring.

**Key schema.** Each shard holds three Redis structures, generation-prefixed
(`Generations.keyPrefix` → `""` for gen 1, `"g{version}:"` for gen ≥2):

- `sr:g{v}:rec:{shard}:{seq}` — an **HSET** with the full record;
- `sr:g{v}:dev:{shard}:{device}` — a **ZADD** device index (score = sequence) for
  ordered per-device reads;
- `sr:g{v}:stream:{shard}` — an **XADD** stream (approx-trimmed at 1,000,000) for ordered
  replay.

**Write fan-out.** `doWrite` resolves `topology.current().shardFor(device)`, gets a
sequence from a per-`(gen, shard)` atomic `INCR`
([`SequenceGenerator`](../../src/main/java/com/recsys/infrastructure/redis/sharding/SequenceGenerator.java)),
and pipelines the HSET + ZADD + XADD against that one shard on the **primary**. A `ZADD
NX` that returns 0 means a duplicate `eventId` → `DUPLICATE` status, so writes are
idempotent and safe to retry.

**Reads — two shapes, two consistency levels.**

- **Per-device reads** (`readDevice`, behind `GET /shards/device`) walk the device ZSET
  and, during a reshard window, **dual-read** the current *and* previous generation and
  merge them (dedupe by `(device, seq)`, current wins) — so no record is lost mid-reshard.
- **Shard scans** (`readShard`/`readAllShards`, behind `GET /shards/shard`) are
  **current-generation only** and silently miss previous-generation records — a known
  sharp edge.

The HTTP façade
([`ShardedRecordService`](../../src/main/java/com/recsys/infrastructure/store/ShardedRecordService.java))
mounts `/shards/` on port 7010.

## 2. `ShardedTopKStore` — sharded trending

The trending store is the opposite kind of sharding — **replica, not partition**. Each
window's sorted set is copied into N identical shard keys (`topk:<window>:s0..s3`,
default **4**), purely to spread read QPS:

- **Reads** pick one shard at random (`fetchFromRandomShard`), giving an N-fold per-key
  QPS reduction — one hot sorted set becomes N. A per-window local cache
  (`ONLINE_TOPK_CACHE_TTL_MS`, default 2000) plus **single-flight** absorb the
  cache-expiry herd, and a Redis failure serves the cached snapshot until
  `ONLINE_TOPK_STALE_TTL_MS` (default 60000) — fail-open.
- **Writes** (`seedAllShards`, from the Flink/sync jobs) fan a single pipelined `ZADD` to
  all N shard keys plus a legacy unsharded fallback key.

Because every shard holds the same data, a stale or missing shard degrades read latency,
not correctness. This is deep-dived from the partition angle in
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
- **Partitioning context** — the record and top-K stores are two of the five partition
  dimensions in [14_Partitioning](14_Partitioning.md).

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
| 2 | **Add top-K replicas** | top-K | N identical copies, one picked at random per refresh (§2) | ~N-fold cut in per-key read QPS on the hottest keys | the same primary |
| 3 | **JVM hot cache** | top-K | `window → CachedIds`, TTL-bounded | absorbs most reads *before* Redis; sharding only spreads the leftover refreshes | staleness ≤ TTL |
| 4 | **AZ-local replica reads** | both | separate write/read executors — writes to primary, reads to an AZ-local replica | read QPS off the primary; less cross-AZ traffic | replica lag ([04](04_Replication.md)) |
| 5 | **Vertical** | Redis | larger ElastiCache node | the only lever that raises *real* single-primary capacity | one node's CPU/RAM |
| 6 | **Index + keyset** | MySQL | covering indexes, HMAC cursor pagination | scales *within* one table, no partitioning | pool size, one instance |

Defaults worth knowing: the ring uses **150 virtual nodes** per shard
([`ConsistentHashRing`](../../src/main/java/com/recsys/infrastructure/redis/sharding/ConsistentHashRing.java)),
top-K defaults to **4 replica shards**
([`ShardedTopKStore`](../../src/main/java/com/recsys/infrastructure/redis/ShardedTopKStore.java)),
and each record shard's stream is approx-trimmed at **1,000,000** entries — so lever 1
also multiplies total retained replay capacity, not just spread.

### Lever 3 in detail — the cache is doing most of the work

For the trending windows the JVM cache, not the sharding, is the primary scaling
mechanism: a **2 s** cache TTL (`ONLINE_TOPK_CACHE_TTL_MS`) with **60 s**
serve-stale-on-error (`ONLINE_TOPK_STALE_TTL_MS`) plus inline single-flight means Redis
sees only refreshes. The 4 replica shards spread *those refreshes*. Read the two together:
sharding raises the ceiling for the traffic the cache fails to absorb, which is exactly
the cold-start and stampede case.

### Lever 4 in detail — replica routing

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

Levers 1 and 2 do **not** add nodes. Both stores hold a single `RedisExecutor`, and the
shards are key prefixes (`sr:g{v}:rec:{shard}:…`, `topk:<window>:sN`) on one Sentinel
primary — not Redis Cluster hash slots. The full physical account is in
[14_Partitioning](14_Partitioning.md#where-the-shards-physically-live--redis-and-mysql).

The payoff is that the ring makes the system **ready** to map logical shards onto separate
nodes without a data migration: the placement function and the generation-versioned
reshard already exist, so that move becomes a routing change rather than a rewrite. Read
lever 1 as an investment that makes future horizontal scaling cheap, and lever 5 as the
one that adds capacity today.

### What breaks first

- **Record store — primary write throughput.** Every write is HSET + ZADD + XADD
  pipelined to one primary. Adding shards spreads *which keys* are touched; it does not
  reduce total ops/sec against that primary. This is the first real wall.
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

## Sharp edges — notes

1. **Shard scans drop previous-generation data during a reshard.** Only per-device reads
   dual-read; `GET /shards/shard` / `readAllShards` are current-generation only.
2. **"Shards" are logical, on one primary.** The ring is cluster-ready, but today all
   shards are key prefixes on a single Sentinel primary — the win is contention-spreading
   and reshardability, not multi-node capacity.
3. **Top-K shards are replicas, not partitions.** All `topk:<window>:sN` hold the same
   set; a missing shard degrades latency, not correctness.
4. **Reshard is operator-gated and generation-scoped.** It needs `SHARD_ADMIN_TOKEN` and
   only moves ~1/N of keys, but there's a ~30 s fleet propagation gap where instances
   straddle generations.
5. **The record store and MySQL are different things.** This is Redis record sharding;
   the relational catalog is a single un-sharded MySQL read model (see
   [14_Partitioning](14_Partitioning.md#where-the-shards-physically-live--redis-and-mysql)).
