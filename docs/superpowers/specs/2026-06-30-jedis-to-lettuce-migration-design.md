# Jedis → Lettuce Migration — Design

**Date:** 2026-06-30
**Status:** Approved (design)
**Scope:** Full cutover of all Redis access from Jedis to Lettuce across all four
services and the two excluded (Flink/Spark) modules; remove the `jedis`
dependency entirely.

## Background

The codebase currently uses **Jedis** as its only Redis client (`pom.xml`, the
sole Redis dependency). 25 source files import `redis.clients.jedis`. Prior work
*modernized* Jedis usage rather than replacing it: configuration moved into
Spring-managed `RedisProperties`/`RedisConfig`, `JedisPoolConfig` was replaced
with `GenericObjectPoolConfig<Jedis>`, and concrete `JedisPool` references were
widened to the `Pool<Jedis>` interface. That widening is useful groundwork but no
swap to a different client was ever performed.

This migration completes the move to **Lettuce** (Netty-based, thread-safe shared
connection, native async/reactive, first-class Sentinel/Cluster support).

### Usage inventory (what we must preserve)

- **No transactions / WATCH / MULTI.** Atomicity is achieved with Lua scripts.
- **No pub/sub. No blocking commands.**
- **Lua scripts (~10)** in `RedisMutex`, `RedisDistributedLock`, `WatchdogLock`,
  `SubmitTokenService`, `RedisRateLimiter`, and the Flink job (3 scripts). These
  are server-side and port verbatim — only the *invocation* changes.
- **Pipelining** in `RedisEmbeddingStore` (bulk SET), `ShardedTopKStore`
  (fan-out ZADD), `ShardedRecordStore` (HSET+EXPIRE+ZADD+XADD), and the Spark
  `ItemEmbeddingJob`.
- **SCAN** with cursor + timeout budget in `RedisEmbeddingStore`.
- **Command families:** GET, SET (NX/EX/PX), MGET, DEL, INCR, TTL, HSET, ZADD
  (NX/XX/GT), ZREVRANGE, XADD (maxlen/approx-trim), RPUSH, LTRIM, SADD, EXPIRE,
  PEXPIRE, SCAN. All have 1:1 Lettuce equivalents.
- **Pooling knobs:** maxTotal=50, maxIdle=10, minIdle=2, maxWaitMs=250,
  testOnBorrow=true, testWhileIdle=true, evictor every 30s, minEvictableIdle=60s.
- **Sentinel** support (`JedisSentinelPool`) selected by `REDIS_MODE=sentinel`.
- **AZ-aware read-replica routing** (`RedisReadReplicaRouter`): same-AZ replica
  preference, else random replica, else primary; driven by `REDIS_REPLICA_NODES`
  (`host:port@az`) and `AWS_AZ`/`AVAILABILITY_ZONE`.
- **Fail-fast timeouts:** latency-sensitive paths cap command timeout
  (e.g. `RECALL_REDIS_TIMEOUT_MS=150`).

## Goals

1. Replace all Jedis usage with Lettuce; delete the `jedis` dependency.
2. Preserve every behavior listed above (pooling semantics, AZ routing, Sentinel,
   fail-fast timeouts, pipelining, Lua atomicity, SCAN).
3. Minimize call-site churn via a small port abstraction.
4. Keep each migration step independently compilable and green.

## Non-goals

- Adopting reactive/async programming model in business code (we use Lettuce's
  sync API behind the port).
- Switching to Spring Data Redis / `RedisTemplate`.
- Replacing the custom AZ router with Lettuce `MasterReplica`/`ReadFrom`
  (it cannot express same-AZ affinity).
- Adopting Redis Cluster (out of scope; Sentinel + replicas only).

## Architecture

### The port

A small `RedisExecutor` port preserves the current
`try (Jedis j = pool.getResource()) { … }` ergonomics:

```java
public interface RedisExecutor extends Closeable {
    // Borrow the shared (thread-safe) connection; run sync commands in the lambda.
    <T> T execute(Function<RedisCommands<String, String>, T> fn);

    // Replica-preferring read (delegates to the AZ router); falls back to primary.
    <T> T executeRead(Function<RedisCommands<String, String>, T> fn);

    // Dedicated pooled connection with autoflush disabled, for pipelined batches.
    void executePipelined(Consumer<RedisAsyncCommands<String, String>> fn);
}
```

Call sites change from `jedis.set(k, v)` to `exec.execute(c -> c.set(k, v))`. A
multi-command sequence stays on one connection inside a single lambda, exactly as
a single `getResource()` block does today.

### Command mapping

| Jedis | Lettuce |
|---|---|
| `set(k,v,SetParams.nx().ex(n))` | `set(k,v,SetArgs.Builder.nx().ex(n))` |
| `zadd(k,s,m, ZAddParams.gt())` | `zadd(k, ZAddArgs.Builder.gt(), s, m)` |
| `xadd(k, params.maxLen(n).approximateTrimming(), map)` | `xadd(k, XAddArgs.Builder.maxlen(n).approximateTrimming(), map)` |
| `eval(script, keys, args)` | `eval(script, ScriptOutputType.X, keys[], args[])` |
| `scan(cursor, ScanParams.match(p).count(500))` | `scan(ScanCursor, ScanArgs.Builder.matches(p).limit(500))` |
| `mget(keys)` | `mget(keys)` → `List<KeyValue<K,V>>` (unwrap) |

**Lua output types (highest-risk area).** Lettuce requires declaring the result
type up front, unlike Jedis's `Object eval(...)`:

- Lock acquire/release, token consume, watchdog renew → `ScriptOutputType.INTEGER`
  (returns `Long`).
- Rate limiter (`{allowed, ttl}`) → `ScriptOutputType.MULTI` (returns
  `List<Object>`); cast each element.
- Flink "set-if-newer" scripts → `ScriptOutputType.INTEGER`/`VALUE` per script.

Each script gets an explicit output type and a focused test asserting the
returned value matches the previous Jedis behavior.

**Pipelining.** `jedis.pipelined()` → borrow a dedicated connection from the
commons-pool2 pool, `setAutoFlushCommands(false)`, issue async commands collecting
`RedisFuture`s, `flushCommands()`, then `LettuceFutures.awaitAll(timeout, futures)`.
Because disabling autoflush mutates per-connection state, pipelines must use a
pooled (not the shared) connection.

### Connection topology

`RedisConnectionFactory` (returns `Pool<Jedis>`) is replaced by
`LettuceClientFactory`, which builds:

- A `RedisClient` (standalone or Sentinel via `RedisURI`).
- One **shared** `StatefulRedisConnection<String,String>` for normal sync ops.
- A `GenericObjectPool<StatefulRedisConnection<String,String>>` (commons-pool2 —
  the same config object Jedis used internally) for pipeline paths.

Both are wrapped by `LettuceRedisExecutor` (implements `RedisExecutor`,
`Closeable`).

- **Pool config:** `RedisProperties` is reused almost unchanged; knobs map 1:1 to
  `GenericObjectPoolConfig`.
- **Sentinel:** `RedisURI.builder().withSentinel(host,port).withSentinelMasterId(master)`
  + `MasterReplica.connect(client, StringCodec.UTF8, uri)`.
- **AZ routing:** keep `RedisReadReplicaRouter`; replace each replica's
  `Pool<Jedis>` with a `RedisExecutor`. `readablePool()` → `readableExecutor()`,
  surfaced via `executeRead`.
- **Timeouts:** per-connection command timeout (`connection.setTimeout(Duration)`)
  preserves fail-fast caps such as `RECALL_REDIS_TIMEOUT_MS`.

### Spring + plain-Jetty wiring

- `@Bean Pool<Jedis> jedisPool()` → `@Bean RedisExecutor redisExecutor()`
  (+ the router bean) in `RedisConfig`.
- `LazyJedisPool` → `LazyRedisExecutor` — identical double-checked locking over a
  `Supplier<RedisExecutor>`.
- The three Jetty services (`RecSysServer`, `OnlinePredictionServer`,
  `MicroserviceGatewayServer`) build the executor via
  `LettuceClientFactory.fromEnv()`.
- `RedisClient.shutdown()` and pool/connection `close()` wired into existing
  `@PreDestroy`/JVM shutdown hooks.

### Excluded modules

- **Spark `ItemEmbeddingJob`:** per-partition `new Jedis(host,port)` →
  `RedisClient.create(uri)` + connection created **inside** `foreachPartition`
  (avoids Spark serialization of non-serializable clients); async pipelined SET;
  close per partition.
- **Flink `OnlineFeatureStreamingJob`:** `AbstractRedisSink`'s `JedisPool` →
  Lettuce `RedisClient` created in `open()`, closed in `close()`; the 3 Lua
  scripts ported with explicit output types.

## Error handling

- Connection/command failures surface as `RedisException`/`RedisCommandTimeoutException`;
  map to the same fallbacks the current code applies on Jedis exceptions
  (e.g. recall path falls back to in-memory candidate selection on timeout).
- Pool exhaustion: commons-pool2 `maxWait` honored as today (`blockWhenExhausted=true`).
- The port's `execute*` methods are responsible for borrowing/returning
  connections so call sites never leak connections.

## Testing strategy

- **Unit tests (~14):** rewrite Mockito mocks of `Pool<Jedis>`/`Jedis` to mock the
  **port** (`RedisExecutor`) or `RedisCommands<String,String>` — not Lettuce
  internals. Verify command invocations and Lua calls at the port boundary.
- **Lua tests:** one focused test per script asserting output-type mapping and
  return value parity with prior behavior.
- **Integration tests (7, Testcontainers `redis:7-alpine`):** backend-agnostic;
  only constructor/wiring updates. These exercise real Lettuce round-trips for
  sharding, locks, replica routing, TTL, sequence generation.
- **Factory test:** `RedisConnectionFactoryTest` → `LettuceClientFactoryTest`
  (standalone + Sentinel URI construction, pool config binding).

## Dependency changes

- Remove `redis.clients:jedis` from `pom.xml`.
- Add `io.lettuce:lettuce-core` (6.x).
- Make `org.apache.commons:commons-pool2` an explicit dependency (was transitive
  via Jedis).

## Migration sequencing

Each step must compile and pass tests before the next.

1. Add Lettuce dep + `RedisExecutor` port + `LettuceRedisExecutor` +
   `LettuceClientFactory` (both clients coexist; no call-site changes yet).
2. Migrate `infrastructure/redis/**`, `infrastructure/lock/**`, rate limiters,
   and token services to the port.
3. Migrate Spring config (`RedisConfig`/`RedisProperties`, `LazyJedisPool`) and
   the three Jetty servers.
4. Migrate the Flink and Spark modules.
5. Rewrite unit tests; update integration-test wiring.
6. Delete the `jedis` dependency; gate on `grep -rn "redis.clients.jedis" src`
   returning empty; run full `mvn package && mvn test`.

## Verification gates

- `mvn package` and `mvn test` green (including Testcontainers integration tests).
- Per-Lua-script output-type tests pass.
- Zero remaining `redis.clients.jedis` imports.
- Manual smoke: each of the four services starts and serves a request against a
  local Redis.

## Risks

- **Lua output-type mapping** is the most error-prone change — mitigated by
  explicit per-script tests.
- **Excluded modules** are not built/tested by CI, so regressions there are
  latent — mitigated by compiling them under a build profile during the migration
  and a manual run check where feasible.
- **Pipelining semantics** (autoflush off on a dedicated connection) differ from
  Jedis; covered by the bulk-write integration tests.
