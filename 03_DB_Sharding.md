# Database Sharding in Recsys-Backend-Service

An investigation of the sharding *subsystem* — the two sharded data stores the system
runs over Redis, their key schemas and operations, and the versioned topology that lets
them be resharded at runtime. Where [06_Consistent_Hashing](06_Consistent_Hashing.md)
explains the *ring algorithm* and [14_Partitioning](14_Partitioning.md) frames sharding
as one of five partition *dimensions*, this doc is the **data-store view**: how records
and trending are actually laid out across shards, written, read, and rebalanced.

## The big picture

Two distinct sharded stores share the same Redis and the same placement algorithm but
solve opposite problems:

| Store | Shards *what* | Sharding kind | Purpose |
|---|---|---|---|
| [`ShardedRecordStore`](src/main/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStore.java) | per-device event/feature/log records | **partition** — consistent-hash by `deviceId` | spread write/storage load; co-locate a device's records |
| [`ShardedTopKStore`](src/main/java/com/recsys/infrastructure/redis/ShardedTopKStore.java) | each trending window's sorted set | **replica** — N identical copies | spread hot-key *read* QPS |

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
([`SequenceGenerator`](src/main/java/com/recsys/infrastructure/redis/sharding/SequenceGenerator.java)),
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
([`ShardedRecordService`](src/main/java/com/recsys/infrastructure/store/ShardedRecordService.java))
mounts `/shards/` on port 7010; write/read curl lives in the README
[Sharded Record Store](README.md#sharded-record-store) section.

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
[`ShardTopologyStore`](src/main/java/com/recsys/infrastructure/redis/sharding/ShardTopologyStore.java)):
`bootstrap` is a `SETNX` of version 1 seeded from `SHARDED_RECORD_SHARD_COUNT` (default
**2**), and `publishReshard` is an atomic Lua read-modify-write that bumps `version + 1`
and stamps the previous generation's expiry.
[`ShardTopologyProvider`](src/main/java/com/recsys/infrastructure/redis/sharding/ShardTopologyProvider.java)
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
