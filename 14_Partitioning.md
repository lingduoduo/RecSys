# Partitioning in Recsys-Backend-Service

An investigation of how the system splits data and traffic so no single key,
node, or task owns everything: consistent-hash record shards, windowed top-K
replica shards, a userId-partitioned Kafka/Flink pipeline, and keyset cursor
windows over large result sets. This is the partitioning counterpart to the
[Scalability investigation](17_Scalability.md) — where that doc asks *how does
throughput grow*, this one asks *along which key is each dataset divided, and
why*.

## The big picture

Partitioning here is **one idea applied at five layers**, each with a
deliberately chosen partition key:

| Dimension | What is partitioned | Partition key | Why this key |
|---|---|---|---|
| Record sharding | Per-device event/feature/log records in Redis | `deviceId` on a consistent-hash ring | Co-locate a device's records; resize moves only ~1/N |
| Top-K replica sharding | Each trending window's sorted set | `window` → N identical replica keys | Spread hot-key read QPS; absorb cache-expiry herds |
| Kafka / Flink | The `movie_events_v2` event stream | `userId` | Per-user ordering on one partition; users spread across 24 |
| Keyset pagination | A large ranked/relational result set | `(sort, id)` seek anchor | Stable pages that never skip/dup under churn |
| A/B bucketing | Users into experiment variants | `userId` via the same FNV-1a primitive | Stable, uniform assignment (see [`StableBucketer`]) |

Two principles recur:

- **The partition key is a contract.** The FNV-1a hash
  ([`Hashing`](src/main/java/com/recsys/infrastructure/redis/sharding/Hashing.java))
  is shared by the record ring *and* A/B bucketing, and its constants are
  documented as un-changeable without a remapping migration; the Kafka partition
  key (`userId`) is a contract between the producer and the Flink source.
- **Partition count is changeable at runtime, but carefully.** Consistent hashing
  makes a record-shard resize move only ~1/N of keys, and a versioned topology
  with a bounded dual-read window makes the resize online. Kafka partition
  increases are a planned cutover, not a config flip.

### Where the shards physically live — Redis and MySQL

The Redis partitioning (dimensions 1 and 2) is **logical, client-side keyspace
partitioning on a single primary**, not server-side sharding. Redis is deployed in
**Sentinel** mode (`REDIS_MODE=sentinel`, one `mymaster` primary + 3 sentinels for
failover, plus AZ-aware read replicas — see the
[Fault Tolerance investigation](18_Fault_Tolerance.md#redis-resilience)), **not
Redis Cluster**, and `ShardedRecordStore` / `ShardedTopKStore` each hold a single
`RedisExecutor`. So the N shards are **key-prefixes on the same primary**
(`sr:g{v}:rec:{shard}:…`, `topk:<window>:s{n}`), not separate nodes and not
server-side hash slots. The consistent-hash ring partitions the *keyspace* — even
distribution, hot-key contention spreading, and an online reshard — and makes it
**ready** to map those logical shards onto separate Redis nodes (or a cluster)
without a data migration, but today the win is contention-spreading and
reshardability, not multi-node capacity.

**MySQL is not partitioned.** It is one intentionally-small, opt-in relational read
model that scales *within* a single table via covering indexes and keyset
pagination (dimension 4) rather than by table partitioning or cross-DB sharding.
Native MySQL table partitioning is deferred — see the sharp edges below.

## 1. Consistent-hash record sharding

`ShardedRecordStore`
([src/…/sharding/ShardedRecordStore.java](src/main/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStore.java))
distributes per-device event, feature, and log records across N Redis shards so
no single key owns all of a device's data.

### The ring

[`ConsistentHashRing`](src/main/java/com/recsys/infrastructure/redis/sharding/ConsistentHashRing.java)
is an immutable 64-bit ring (`TreeMap` ceiling lookup) that places each shard at
**150 virtual nodes** (`"v{i}:{shard}"` hashed by
[`Hashing.fnv1a64`](src/main/java/com/recsys/infrastructure/redis/sharding/Hashing.java)),
so device IDs spread uniformly and a resize remaps only the keys between adjacent
vnodes — roughly `1/N`. `shardFor(deviceId)` resolves a device to its shard;
`distribution()` exposes per-shard load for hot-shard diagnosis. The FNV-1a
primitive (`offset basis 0xcbf29ce484222325`, `prime 0x100000001b3`, UTF-8) is a
compatibility contract — the same hash backs `StableBucketer` for A/B assignment,
and changing it would silently remap every device and every bucket.

### Write fan-out

Each write resolves `topology.current().shardFor(device)` and pipelines three
Redis ops against that shard: `HSET` the full record, `ZADD` a per-device index
(`NX` for insert, `XX + GT` for update) for per-device cursor reads, and `XADD` a
per-shard stream for ordered replay (approx-trimmed at `STREAM_MAXLEN =
1_000_000`). A `ZADD NX` that returns 0 is a duplicate `eventId` → `DUPLICATE`
status, so writes are idempotent and safe to retry. Sequence numbers come from a
per-`(version, shard)` `INCR`
([`SequenceGenerator`](src/main/java/com/recsys/infrastructure/redis/sharding/SequenceGenerator.java)) —
shard-scoped, not globally unique.

### Versioned topology and online reshard

The live topology is an authoritative versioned snapshot in Redis
(`shard:topology`,
[`ShardTopologyStore`](src/main/java/com/recsys/infrastructure/redis/sharding/ShardTopologyStore.java)):
`bootstrap` is a `SETNX` of version 1 (first-writer-wins from
`SHARDED_RECORD_SHARD_COUNT`), and `publishReshard` is an atomic Lua read-modify-
write that bumps `version + 1` and records the previous generation's expiry.
[`ShardTopologyProvider`](src/main/java/com/recsys/infrastructure/redis/sharding/ShardTopologyProvider.java)
refreshes every `SHARD_TOPOLOGY_REFRESH_SECONDS` (default 30) into a **lock-free
volatile snapshot** on a daemon thread, retaining the last-good view if Redis is
briefly unreachable — topology lookups never block the request path.

A reshard is online because of two mechanisms:

- **Generation-scoped keys** —
  [`Generations.keyPrefix`](src/main/java/com/recsys/infrastructure/redis/sharding/Generations.java)
  returns `""` for generation 1 (legacy unversioned keys, `sr:rec:{shard}:{seq}`)
  and `"g{version}:"` for generation ≥2 (`sr:g2:rec:…`), so a new topology writes
  into a disjoint keyspace rather than colliding with in-flight data.
- **Bounded dual-read window** — for `SHARDED_RECORD_MAX_TTL_SECONDS` (default
  86400, reused as the dual-read window) after a reshard, per-device reads
  (`readDevice`) read *both* `current()` and `previousIfActive()` and merge them
  (dedupe by `device:seq`, current wins), so records written before the change are
  still served until they TTL out and the previous generation self-heals away.
  Shard-level scans (`readShard` / `readAllShards`, behind `GET /shards/shard`)
  are generation-current and do **not** dual-read.

The HTTP façade
([`ShardedRecordService`](src/main/java/com/recsys/infrastructure/store/ShardedRecordService.java))
mounts `/shards/` on 7010; the reshard endpoint `POST /shards/topology` is
`AdminTokenGuard`-gated and **fails closed** (`403`) unless `SHARD_ADMIN_TOKEN` is
set and the `X-Admin-Token` header matches. Operational usage — write/read curl
and the reshard call — stays in the README
[Sharded Record Store](README.md#sharded-record-store) section.

| Env var | Default | Partitions |
|---|---:|---|
| `SHARDED_RECORD_SHARD_COUNT` | `2` | Bootstrap record-shard count (version 1) |
| `SHARD_TOPOLOGY_REFRESH_SECONDS` | `30` | How often each instance refreshes `shard:topology` |
| `SHARDED_RECORD_MAX_TTL_SECONDS` | `86400` | Record TTL **and** the post-reshard dual-read window |
| `SHARD_ADMIN_TOKEN` | _(unset → reshard disabled)_ | Reshard / shard-dump authz |
| virtual nodes | `150` (hardcoded) | Ring vnodes per shard |

## 2. Windowed Top-K replica sharding

Trending is a read hot spot — every request wants the same few window keys — so
[`ShardedTopKStore`](src/main/java/com/recsys/infrastructure/redis/ShardedTopKStore.java)
partitions each window's sorted set into **N identical replica keys**
(`topk:<window>:s0..s3`, default shard count **4**) purely to spread read QPS.
Unlike record sharding, every shard holds the *same* data; the win is that a read
picks one at random (`fetchFromRandomShard` via `ThreadLocalRandom`), giving an
N-fold per-key QPS reduction — one hot sorted set becomes N, so no single key
takes the full read fan-out (see the deployment note below on why this is
per-key, not per-node, spreading).

Two more layers sit in front of Redis: a per-window local JVM `hotCache`
(`ONLINE_TOPK_CACHE_TTL_MS`, default 2000) and a **single-flight** guard so that
when the cache expires only one thread recomputes while the rest wait — the
classic thundering-herd fix for a hot expired key. On a Redis failure the store
serves the cached snapshot until `ONLINE_TOPK_STALE_TTL_MS` (default 60000)
elapses (fail-open, degraded but available). Writes (`seedAllShards`, from the
Flink/sync jobs) fan a single pipelined `ZADD` to all N shard keys plus a legacy
unsharded fallback key, then invalidate the local cache. Windows served on the
recall path are `last_hour` and `last_day`.

| Env var | Default | Purpose |
|---|---:|---|
| `ONLINE_TOPK_CACHE_TTL_MS` | `2000` | Local hot-key cache TTL for the sharded trending store |
| `ONLINE_TOPK_STALE_TTL_MS` | `60000` | Max stale Top-K age served during Redis errors |
| Top-K shard count | `4` | Identical replica keys per window |

## 3. Kafka topic partitioning + Flink keyed pipeline

The behavioral event stream is partitioned so that **all events for a user land
on one partition** (strict per-user ordering) while different users spread across
24 partitions for throughput.

Of the three event transports only Kafka partitions. The SQS transport
([`SqsAsyncEventPublisher`](src/main/java/com/recsys/infrastructure/messaging/SqsAsyncEventPublisher.java))
writes JSON bodies to a **single standard queue** (`SendMessageBatch`, no
`MessageGroupId`/FIFO), which exposes no user-controlled partition key and gives
no ordering guarantee, and the default log-only publisher is a no-op — so
partitioning is a Kafka-specific, load-bearing decision, and the Flink feature
pipeline consumes only from `movie_events_v2`. SQS is an alternative
fire-and-forget sink, not a source for the keyed streaming job.

- **Producer key = `userId`** —
  [`MovieEventKafkaKeyExtractor`](src/main/java/com/recsys/infrastructure/messaging/MovieEventKafkaKeyExtractor.java)
  pulls `userId` from each event, and
  [`KafkaAsyncEventPublisher`](src/main/java/com/recsys/infrastructure/messaging/KafkaAsyncEventPublisher.java)
  emits `ProducerRecord(topic, key=userId, value)` with an idempotent producer
  (`enable.idempotence=true`, `acks=all`, `max.in.flight=5`). An event with no
  valid key is rejected rather than sent to an arbitrary partition. The topic is
  `ONLINE_EVENTS_KAFKA_TOPIC` (default `movie_events_v2`).
- **Flink honors the same key** —
  [`OnlineFeatureStreamingJob`](src/main/java/com/recsys/online/flink/OnlineFeatureStreamingJob.java)
  defaults `expected-topic-partitions = source-parallelism = operator-parallelism
  = 24` and `max-parallelism = 128` (key groups), validated at startup by
  [`KafkaTopicPartitionValidator`](src/main/java/com/recsys/online/flink/KafkaTopicPartitionValidator.java)
  (actual partition count must equal expected, or the job fails fast). All
  stateful operators `keyBy(userId)` (idempotency dedup, recent-movies,
  user-embedding), so per-user ordering carries end to end.
- **Two-stage Top-K removes the single-task bottleneck** — instead of a global
  `windowAll` (one task), a partial stage `keyBy`s a **movie bucket**
  (`floorMod(hashCode(movieId), bucketCount)`, default `bucketCount =
  operatorParallelism`) and a final stage merges by `windowEnd`. This is itself a
  partitioning move: sharding the aggregation by movie so it parallelizes.

Because the partition key is a producer↔consumer contract, changing the partition
count is a planned cutover (bridge-mode replay, savepoint/UID discipline, a fresh
consumer group for rollback) — documented in
[kafka-partition-cutover.md](docs/runbooks/kafka-partition-cutover.md) and the
[Kafka/Flink partition optimization design](docs/superpowers/specs/2026-07-18-kafka-flink-partition-optimization-design.md).
The `@Tag("load")` `KafkaFlinkPartitionLoadTest` pins the 24-partition / **50k
events/sec** target.

## 4. Keyset / cursor pagination — partitioning a result set

Deep pagination is a partitioning problem: split a large ordered result into
stable pages **without** an `OFFSET` scan, so inserts, exclusions, or re-scores
between requests never skip or duplicate rows. The system uses `(sort, id)` seek
anchors in three places.

### In-memory ranked list — `/v2/recommend`

`POST /v2/recommend` (on 6010 via
[`RecommendationOrchestrator`](src/main/java/com/recsys/application/recommendation/RecommendationOrchestrator.java))
returns an opaque **seek cursor** anchored on the last item's `(score, itemId)` —
*not* an absolute offset.
[`CursorPaginationService`](src/main/java/com/recsys/application/pagination/CursorPaginationService.java)
resumes after the anchor item if it is still present, else seeks by the
`(score desc, itemId asc)` predicate, so a shifting ranked list or a changing
`excludedItemIds` never silently skips. Recall is bounded to a fresh window of
`limit × recallMultiplier` (default 5) candidates recomputed each request — this
paginates within a fresh recall window, not over a frozen snapshot. The
[`RankedListCursor`](src/main/java/com/recsys/application/pagination/RankedListCursor.java)
wire format is `base64url("v2:<score>:<itemId>")`.

> **Cursor vs. exclusion — two models, don't mix them.** For a *fixed* result set
> (catalog-style browse) keep `excludedItemIds` constant and advance the cursor
> until `nextCursor` is `null`. For an *ever-fresh* "For You" feed, omit the
> cursor and accumulate seen IDs into `excludedItemIds` — each call re-recalls and
> excludes what's seen, picking up live trending/learner changes. The seek cursor
> tolerates a changing exclusion set, but a saved cursor *plus* a growing
> exclusion set conflates the two. The endpoint request/response contract stays in
> the README [`/v2/recommend`](README.md#recommendations-v2--cursor-pagination-v2recommend)
> reference.

Note the two `/v2/recommend` implementations differ: the 6010 route
(`RecommendationOrchestrator`) does real keyset paging; the 7010 route
([`OnlineBlendingPipeline`](src/main/java/com/recsys/application/online/OnlineBlendingPipeline.java))
assigns positional scores and returns a `null` next cursor.

### Relational catalog — HMAC-signed, filter-bound cursors

The MySQL catalog browse
([`CatalogCursorCodec`](src/main/java/com/recsys/application/catalog/CatalogCursorCodec.java))
uses a keyset position on `(popularity_score, movieId)` that is **HMAC-SHA256
signed and bound to the genre filter**: the wire format is
`base64(payload).base64(HMAC(payload))`, verified with a constant-time compare and
rebound to the expected genre on decode, so a tampered cursor or a filter change
returns `400`. The signing key `MYSQL_CURSOR_SIGNING_KEY` must be ≥32 UTF-8 bytes
when MySQL is enabled. The generic SQL toolkit
([`MillionScalePaginationSql`](src/main/java/com/recsys/application/pagination/MillionScalePaginationSql.java))
emits the covering-index, keyset (`(sort > ? OR (sort = ? AND id > ?))`), and
delayed-join (index-only key walk + outer join for deep offset) variants that back
these reads — see [MySQL Index Inventory](README.md#mysql-index-inventory).

## 5. Testing partitioning

The partition invariants are exercised, not just asserted:

- **Ring / hashing / topology** — `ConsistentHashRingTest` (distribution,
  vnodes), `HashingTest` (FNV-1a/`fmix64` constant characterization),
  `ShardTopologyProviderTest` (last-good on refresh failure),
  `ShardTopologyStoreTest` (`@Tag("docker")`: bootstrap `SETNX` + reshard Lua),
  `SequenceGeneratorTest`.
- **Record store** — `ShardedRecordStoreGenerationKeyTest` (generation
  prefixing), **`ShardedRecordStoreDualReadTest`** (the dual-read merge/dedupe
  window), `ShardedRecordStoreReplicaRoutingTest`, and
  `ShardedRecordServiceReshardTest` (the reshard flow).
- **Top-K sharding** — `ShardedTopKStoreTest`, `ShardedTopKStoreTtlConfigTest`
  (env/TTL defaults).
- **Kafka / Flink** — `KafkaTopicPartitionValidatorTest` (mismatch throws),
  `KafkaFlinkPartitionIntegrationTest` (`@Tag("docker")`, 24 partitions, keyed
  assertions), `KafkaFlinkPartitionLoadTest` (`@Tag("load")`, 24 partitions @ 50k
  evt/s), `MovieEventKafkaKeyExtractorTest`.
- **Pagination** — `MillionScalePaginationSqlTest`, `CursorPaginationServiceTest`,
  `CatalogCursorCodecTest`, `RecommendationOrchestratorTest`, `V2CrossPathLoadTest`
  (`@Tag("load")`).

## Sharp edges — notes

1. **The hash and the partition keys are frozen contracts.** The FNV-1a constants
   (record ring + A/B bucketing) and the Kafka `userId` key can't change without a
   remapping migration or a topic cutover; the code comments and the
   [consistent-hashing consolidation design](docs/superpowers/specs/2026-06-24-consistent-hashing-consolidation-design.md)
   spell this out.
2. **Record resize is online; Kafka resize is not.** A record-shard reshard is a
   guarded runtime call with a bounded dual-read window
   ([dynamic shard topology design](docs/superpowers/specs/2026-06-24-dynamic-shard-topology-design.md));
   a Kafka partition increase is a planned cutover
   ([kafka-partition-cutover.md](docs/runbooks/kafka-partition-cutover.md)).
3. **Shard-level scans don't dual-read.** During a reshard window, per-device
   reads merge both generations but `GET /shards/shard` / `readAllShards` are
   generation-current — an operator scanning shards mid-migration sees only the
   new generation.
4. **Top-K shards are replicas, not partitions of data.** All `topk:<window>:sN`
   keys hold the same set; the sharding is a QPS-spreading device, so a stale or
   missing shard degrades read latency, not correctness (fail-open to cache).
5. **MySQL table partitioning is not yet done.** Sharding covers Redis records,
   Redis Top-K, and Kafka today; native MySQL table partitioning is called out as
   a separate later cycle in the
   [Kafka/Flink partition optimization design](docs/superpowers/specs/2026-07-18-kafka-flink-partition-optimization-design.md).
