# Spec: Redis Round-Trip Batching

## Objective
Cut Redis round-trips on the sharded write/seed paths and bound a startup scan, with **no behavior change** to results. Reduces tail latency and startup risk. From the infra audit (Tier 3 / batching).

## Scope

### A. Pipeline `ShardedTopKStore.seedAllShards` fan-out
File: `src/main/java/com/recsys/infrastructure/redis/ShardedTopKStore.java` (~line 190).
Today the seed loop acquires a connection and issues one `ZADD` **per shard** (N sequential round-trips), plus a separate legacy-key write. Replace with a single pipelined batch on one `writePool` connection: queue all per-shard `ZADD` (+ optional `EXPIRE`) commands, then `sync()` once. N+1 round-trips → 1.
- Preserve: identical members/scores/TTL per shard; same shard set; same legacy fallback key.

### B. Pipeline `ShardedRecordStore` sequence-gen + write
File: `src/main/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStore.java` (`doWrite`, ~line 76; `seqGen.next` ~line 78).
Each write does (1) a sequence `INCR` round-trip then (2) a pipelined `HSET`/`ZADD`/`XADD`. If `SequenceGenerator` is Redis-`INCR`-backed, fold the `INCR` into the same pipeline (queue INCR, read its response within the pipeline, then queue the dependent writes — or use a single Lua script that allocates the id and writes atomically). 2 round-trips → 1.
- Decision to settle in the plan: pipeline-with-deferred-read vs a Lua script. Lua also gives atomicity; prefer Lua if `SequenceGenerator` already centralizes the key.
- Preserve: identical id allocation semantics, record layout, stream entries.

### C. Timeout/guard `RedisEmbeddingStore.loadAll` startup scan
File: `src/main/java/com/recsys/infrastructure/redis/RedisEmbeddingStore.java` (`loadAll`, SCAN + batched MGET; MGET is already batched via `mgetBatchSize`).
Add an overall deadline (env `REDIS_LOADALL_TIMEOUT_MS`, default e.g. 30000) so a slow/unavailable Redis fails fast and the caller falls back to file-system seeding instead of blocking startup indefinitely.
- Preserve: same returned map on the happy path; only adds a bound + fallback signal.

## Out of Scope
- `ShardedRecordStore.readAllShards` concurrency/bounding — separate concern; only add a defensive max-records guard if trivial, else defer.
- Any change to shard count, hashing, or key naming.

## Testing
- Unit: a fake/embedded `Jedis` (existing tests use `RedisServer`/mocks — follow that harness) asserting `seedAllShards` issues one pipeline (verify all shards present with correct scores after one logical call).
- `ShardedRecordStore` write/read round-trip tests must pass unchanged (id monotonicity, record retrieval).
- `loadAll` timeout test: a backing store that stalls returns within the deadline and signals fallback.
- `mvn clean test` green.

## Risks
- Pipelining changes error-handling granularity (one failed command in a batch). Mitigation: keep per-call atomicity expectations identical; for B prefer Lua for true atomicity.
- Lua adds a script to maintain; document it next to the store.

## Success
- `seedAllShards` = 1 round-trip; `ShardedRecordStore.doWrite` = 1 round-trip; `loadAll` bounded; all existing tests green.
