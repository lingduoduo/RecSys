# Jedis → Lettuce Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace all Jedis usage with Lettuce behind a `RedisExecutor` port, across all four services plus the excluded Flink/Spark modules, and delete the `jedis` dependency.

**Architecture:** A `RedisExecutor` port preserves the current `try (Jedis j = pool.getResource()) { … }` ergonomics. A `LettuceRedisExecutor` adapter backs it with one shared thread-safe `StatefulRedisConnection` for normal sync commands and a commons-pool2 `GenericObjectPool<StatefulRedisConnection>` for pipelines. `LettuceClientFactory` replaces `RedisConnectionFactory`; the AZ-aware `RedisReadReplicaRouter` is retained but holds `RedisExecutor`s instead of `Pool<Jedis>`.

**Tech Stack:** Java 17, Lettuce 6.x (`io.lettuce:lettuce-core`), commons-pool2, Maven, JUnit 5, Mockito, Testcontainers (`redis:7-alpine`).

## Global Constraints

- Lettuce version: `io.lettuce:lettuce-core` 6.3.2.RELEASE (6.x line).
- `org.apache.commons:commons-pool2` becomes an explicit dependency (was transitive via Jedis).
- Codec is always `StringCodec.UTF8`; all keys/values are `String` (matches current Jedis `<String,String>` usage).
- No reactive/async APIs leak into business code — call sites use Lettuce **sync** commands inside the port lambdas.
- Preserve env-var names and defaults: `REDIS_HOST/PORT/MODE/PASSWORD/TIMEOUT_MS`, `REDIS_POOL_MAX_TOTAL=50/MAX_IDLE=10/MIN_IDLE=2/MAX_WAIT_MS=250/TEST_ON_BORROW=true`, evictor 30s / minEvictableIdle 60s, `REDIS_SENTINEL_MASTER=mymaster/REDIS_SENTINEL_NODES`, `REDIS_REPLICA_NODES` (`host:port@az`), `AWS_AZ`/`AVAILABILITY_ZONE`.
- Lua scripts are unchanged; only invocation changes. Declare `ScriptOutputType` explicitly per script.
- Final gate: `grep -rn "redis.clients.jedis" src` returns nothing; `mvn package && mvn test` green.

---

### Task 1: Dependencies + `RedisExecutor` port + Lettuce adapter + factory

**Files:**
- Modify: `pom.xml` (Redis dependency block, ~line 150)
- Create: `src/main/java/com/recsys/infrastructure/redis/RedisExecutor.java`
- Create: `src/main/java/com/recsys/infrastructure/redis/LettuceRedisExecutor.java`
- Create: `src/main/java/com/recsys/infrastructure/redis/LettuceClientFactory.java`
- Test: `src/test/java/com/recsys/infrastructure/redis/LettuceClientFactoryTest.java`

**Interfaces:**
- Produces:
  - `RedisExecutor` with `<T> T execute(Function<RedisCommands<String,String>,T>)`, `<T> T executeRead(Function<RedisCommands<String,String>,T>)`, `void executePipelined(Consumer<RedisAsyncCommands<String,String>>)`, `void close()`.
  - `LettuceClientFactory.fromEnv() : RedisExecutor`, `fromEnv(int maxTimeoutMs) : RedisExecutor`, `from(RedisProperties) : RedisExecutor`, `routerFromEnv() : RedisReadReplicaRouter`, `routerFrom(RedisProperties) : RedisReadReplicaRouter`.

- [ ] **Step 1: Add Lettuce + commons-pool2, keep jedis for now**

In `pom.xml`, leave the existing `jedis` block in place (removed in Task 12) and add after it:

```xml
    <!-- Lettuce (Redis client) -->
    <dependency>
      <groupId>io.lettuce</groupId>
      <artifactId>lettuce-core</artifactId>
      <version>6.3.2.RELEASE</version>
    </dependency>
    <dependency>
      <groupId>org.apache.commons</groupId>
      <artifactId>commons-pool2</artifactId>
      <version>2.12.0</version>
    </dependency>
```

- [ ] **Step 2: Create the `RedisExecutor` port**

```java
package com.recsys.infrastructure.redis;

import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;

import java.io.Closeable;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Abstraction over a Redis client that preserves the "borrow a connection, run a
 * few commands, return it" ergonomics of the previous {@code Pool<Jedis>} usage,
 * while hiding the Lettuce connection model from call sites.
 */
public interface RedisExecutor extends Closeable {

    /** Runs sync commands on the shared (thread-safe) primary connection. */
    <T> T execute(Function<RedisCommands<String, String>, T> fn);

    /** Like {@link #execute} but routed to a replica when one is available. */
    <T> T executeRead(Function<RedisCommands<String, String>, T> fn);

    /**
     * Runs a pipelined batch on a dedicated pooled connection with auto-flush
     * disabled. The callback issues async commands; the adapter flushes and waits.
     */
    void executePipelined(Consumer<RedisAsyncCommands<String, String>> fn);

    @Override
    void close();
}
```

- [ ] **Step 3: Create `LettuceRedisExecutor`**

```java
package com.recsys.infrastructure.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.support.ConnectionPoolSupport;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * {@link RedisExecutor} backed by Lettuce: one shared thread-safe connection for
 * normal sync commands and a commons-pool2 pool of dedicated connections for
 * pipelined batches (auto-flush is toggled per-connection, so pipelines must not
 * share the primary connection).
 */
public final class LettuceRedisExecutor implements RedisExecutor {

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> shared;
    private final GenericObjectPool<StatefulRedisConnection<String, String>> pool;
    private final boolean ownsClient;

    public LettuceRedisExecutor(RedisClient client,
                                StatefulRedisConnection<String, String> shared,
                                GenericObjectPoolConfig<StatefulRedisConnection<String, String>> poolCfg,
                                boolean ownsClient) {
        this.client = client;
        this.shared = shared;
        this.ownsClient = ownsClient;
        this.pool = ConnectionPoolSupport.createGenericObjectPool(
                () -> client.connect(StringCodec.UTF8), poolCfg);
    }

    @Override
    public <T> T execute(Function<RedisCommands<String, String>, T> fn) {
        return fn.apply(shared.sync());
    }

    @Override
    public <T> T executeRead(Function<RedisCommands<String, String>, T> fn) {
        return execute(fn); // single-node executor reads from the same connection
    }

    @Override
    public void executePipelined(Consumer<RedisAsyncCommands<String, String>> fn) {
        StatefulRedisConnection<String, String> conn = null;
        try {
            conn = pool.borrowObject();
            RedisAsyncCommands<String, String> async = conn.async();
            async.setAutoFlushCommands(false);
            try {
                fn.accept(async);
                async.flushCommands();
            } finally {
                async.setAutoFlushCommands(true);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Pipelined Redis batch failed", e);
        } finally {
            if (conn != null) pool.returnObject(conn);
        }
    }

    @Override
    public void close() {
        pool.close();
        shared.close();
        if (ownsClient) client.shutdown();
    }
}
```

Note: the pipeline callback is responsible for awaiting the `RedisFuture`s it issues (see Task 3's embedding-store recipe), because only the callback knows the timeout budget and which futures to await.

- [ ] **Step 4: Create `LettuceClientFactory`**

Mirror `RedisConnectionFactory`'s env/property parsing but build Lettuce objects. Reuse the existing `ReplicaConfig` record and `RedisProperties`. Key construction:

```java
package com.recsys.infrastructure.redis;

import com.recsys.config.RedisProperties;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class LettuceClientFactory {

    static final int DEFAULT_MAX_TOTAL = 50;
    static final int DEFAULT_MAX_IDLE  = 10;
    static final int DEFAULT_MIN_IDLE  = 2;
    static final int DEFAULT_MAX_WAIT_MS = 250;
    private static final int DEFAULT_TIMEOUT_MS = 2000; // Jedis Protocol.DEFAULT_TIMEOUT

    private LettuceClientFactory() {}

    public static RedisExecutor fromEnv() {
        return fromEnv(Integer.MAX_VALUE);
    }

    public static RedisExecutor fromEnv(int maxTimeoutMs) {
        Map<String, String> env = System.getenv();
        RedisURI uri = uriFromEnv(env, maxTimeoutMs);
        RedisClient client = RedisClient.create(uri);
        StatefulRedisConnection<String, String> shared = client.connect(StringCodec.UTF8);
        return new LettuceRedisExecutor(client, shared, poolConfig(defaultPoolKnobs(env)), true);
    }

    public static RedisExecutor from(RedisProperties props) {
        RedisURI uri = uriFrom(props);
        RedisClient client = RedisClient.create(uri);
        StatefulRedisConnection<String, String> shared = client.connect(StringCodec.UTF8);
        return new LettuceRedisExecutor(client, shared, poolConfig(props.getPool()), true);
    }

    static RedisURI uriFromEnv(Map<String, String> env, int maxTimeoutMs) {
        String mode = env.getOrDefault("REDIS_MODE", "standalone");
        String password = env.getOrDefault("REDIS_PASSWORD", "");
        int timeoutMs = Math.min(readPositiveInt(env, "REDIS_TIMEOUT_MS", DEFAULT_TIMEOUT_MS),
                Math.max(1, maxTimeoutMs));
        RedisURI uri;
        if ("sentinel".equalsIgnoreCase(mode)) {
            String master = env.getOrDefault("REDIS_SENTINEL_MASTER", "mymaster");
            RedisURI.Builder b = RedisURI.builder().withSentinelMasterId(master);
            for (String node : env.getOrDefault("REDIS_SENTINEL_NODES", "localhost:26379").split(",")) {
                node = node.strip();
                if (node.isEmpty()) continue;
                int c = node.lastIndexOf(':');
                b = (c > 0)
                        ? b.withSentinel(node.substring(0, c), Integer.parseInt(node.substring(c + 1)))
                        : b.withSentinel(node, 26379);
            }
            uri = b.build();
        } else {
            uri = RedisURI.create(env.getOrDefault("REDIS_HOST", "localhost"),
                    parsePort(env.getOrDefault("REDIS_PORT", "6379")));
        }
        if (password != null && !password.isBlank()) uri.setPassword(password.toCharArray());
        uri.setTimeout(Duration.ofMillis(timeoutMs));
        return uri;
    }

    // uriFrom(RedisProperties), routerFromEnv(), routerFrom(props): build per-replica
    // RedisExecutors (each its own RedisClient/connection) and pass them to
    // RedisReadReplicaRouter (see Task 2). Reuse ReplicaConfig.parse() for "host:port@az".

    static GenericObjectPoolConfig<StatefulRedisConnection<String, String>> poolConfig(RedisProperties.Pool pool) {
        GenericObjectPoolConfig<StatefulRedisConnection<String, String>> cfg = new GenericObjectPoolConfig<>();
        cfg.setMaxTotal(pool.getMaxTotal());
        cfg.setMaxIdle(pool.getMaxIdle());
        cfg.setMinIdle(pool.getMinIdle());
        cfg.setTestOnBorrow(pool.isTestOnBorrow());
        cfg.setBlockWhenExhausted(true);
        cfg.setMaxWait(Duration.ofMillis(pool.getMaxWaitMs()));
        cfg.setTestWhileIdle(true);
        cfg.setNumTestsPerEvictionRun(-1);
        cfg.setTimeBetweenEvictionRuns(Duration.ofMillis(30_000L));
        cfg.setMinEvictableIdleDuration(Duration.ofMillis(60_000L));
        return cfg;
    }

    // defaultPoolKnobs(env): build a RedisProperties.Pool from REDIS_POOL_* env vars
    // (same defaults as RedisConnectionFactory.defaultPoolConfig). parsePort/readPositiveInt
    // copied verbatim from RedisConnectionFactory.
}
```

- [ ] **Step 5: Write `LettuceClientFactoryTest`**

Port `RedisConnectionFactoryTest`: assert `uriFromEnv` builds a standalone URI with host/port/timeout from env, a Sentinel URI with master id + sentinel nodes when `REDIS_MODE=sentinel`, and that `poolConfig` maps knobs (maxTotal/maxIdle/minIdle/maxWait/testOnBorrow). No network calls — assert on `RedisURI`/`GenericObjectPoolConfig` getters only.

- [ ] **Step 6: Build**

Run: `mvn -q compile`
Expected: BUILD SUCCESS (new types compile; nothing consumes them yet).

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/java/com/recsys/infrastructure/redis/RedisExecutor.java \
  src/main/java/com/recsys/infrastructure/redis/LettuceRedisExecutor.java \
  src/main/java/com/recsys/infrastructure/redis/LettuceClientFactory.java \
  src/test/java/com/recsys/infrastructure/redis/LettuceClientFactoryTest.java
git commit -m "feat(redis): add Lettuce RedisExecutor port, adapter, and factory"
```

---

### Task 2: Migrate `RedisReadReplicaRouter` to `RedisExecutor`

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/redis/RedisReadReplicaRouter.java`
- Modify: `src/test/java/com/recsys/infrastructure/redis/RedisReadReplicaRouterTest.java`

**Interfaces:**
- Consumes: `RedisExecutor` (Task 1).
- Produces: `RedisReadReplicaRouter` with `AzPool(RedisExecutor exec, String az)`, `writable() : RedisExecutor`, `readable() : RedisExecutor`, `replicaCount()`, `localAz()`, `close()`.

- [ ] **Step 1:** Replace `Pool<Jedis>` with `RedisExecutor` throughout: record becomes `AzPool(RedisExecutor exec, String az)`; rename `writablePool()`→`writable()`, `readablePool()`→`readable()` returning `RedisExecutor`; `close()` calls `exec.close()` on primary + replicas. AZ-selection logic is unchanged.
- [ ] **Step 2:** Update `RedisReadReplicaRouterTest` to mock `RedisExecutor` instead of `Pool<Jedis>`; assertions on same-AZ/random/primary selection are unchanged.
- [ ] **Step 3:** Run `mvn -q test -Dtest=RedisReadReplicaRouterTest` → PASS.
- [ ] **Step 4:** Commit: `refactor(redis): RedisReadReplicaRouter routes RedisExecutors`.

---

### Task 3: Migrate `infrastructure/redis` stores

**Files (modify each + its test):**
- `RedisEmbeddingStore.java`, `RedisTopKStore.java`, `ShardedTopKStore.java`, `GlobalPopularityStore.java`

**Interfaces:**
- Consumes: `RedisExecutor`, `RedisReadReplicaRouter` (Tasks 1–2).

**Transformation recipe (apply to every store):**

1. Field `Pool<Jedis> pool` → `RedisExecutor exec`; constructors take `RedisExecutor`. Stores that took separate read/write `Pool<Jedis>` (e.g. `ShardedTopKStore`) take two `RedisExecutor`s or a `RedisReadReplicaRouter`.
2. `try (Jedis j = pool.getResource()) { return j.foo(); }` → `return exec.execute(c -> c.foo());`. Reads that used the replica pool → `exec.executeRead(...)`.
3. Command arg mapping: `SetParams.setParams().px(ms)` → `io.lettuce.core.SetArgs.Builder.px(ms)`; `.nx().ex(s)` → `SetArgs.Builder.nx().ex(s)`. `jedis.set(k,v,params)` returns `"OK"`/null — Lettuce `set` returns `"OK"`/null too. `mget(keys)` → `c.mget(keys)` returns `List<KeyValue<String,String>>`; use `kv.getValueOrElse(null)`.
4. ZSET: `zrevrangeWithScores`/`zrevrange` → `c.zrevrange(k, start, stop)` / `c.zrevrangeWithScores(...)` (returns `List<ScoredValue<String>>`). `ZADD` args → `io.lettuce.core.ZAddArgs.Builder.gt()/nx()/xx()`.

**`RedisEmbeddingStore` worked example** (the SCAN + pipeline cases):

```java
// setEmbedding (single, with jittered px TTL)
exec.execute(c -> c.set(keyPrefix + ":" + movieId, toVectorString(vector),
        io.lettuce.core.SetArgs.Builder.px(jitteredTtlMillis(ttlSeconds))));

// setEmbeddings (pipeline)
exec.executePipelined(async -> {
    java.util.List<io.lettuce.core.RedisFuture<?>> futures = new java.util.ArrayList<>();
    for (Map.Entry<Integer, float[]> e : vectors.entrySet()) {
        String key = keyPrefix + ":" + e.getKey();
        String val = toVectorString(e.getValue());
        futures.add(ttlSeconds > 0
                ? async.set(key, val, io.lettuce.core.SetArgs.Builder.px(jitteredTtlMillis(ttlSeconds)))
                : async.set(key, val));
    }
    async.flushCommands();
    io.lettuce.core.LettuceFutures.awaitAll(java.time.Duration.ofSeconds(5),
            futures.toArray(new io.lettuce.core.RedisFuture[0]));
});

// loadAll / scanIds (SCAN)
exec.execute(c -> {
    io.lettuce.core.ScanArgs args = io.lettuce.core.ScanArgs.Builder.matches(keyPrefix + ":*").limit(500);
    io.lettuce.core.KeyScanCursor<String> cursor = c.scan(args);
    while (true) {
        List<String> pageKeys = cursor.getKeys();
        if (!pageKeys.isEmpty()) {
            List<io.lettuce.core.KeyValue<String,String>> values = c.mget(pageKeys.toArray(new String[0]));
            // ... same parse loop, values.get(i).getValueOrElse(null) ...
        }
        if (cursor.isFinished()) break;
        cursor = c.scan(cursor, args);
        // ... same timeout-budget check ...
    }
    return null;
});
```

- [ ] **Step 1:** Apply the recipe to `RedisEmbeddingStore`; update `RedisEmbeddingStoreTest` to mock `RedisExecutor` (stub `execute`/`executePipelined` to invoke the lambda against a mocked `RedisCommands`/`RedisAsyncCommands`). Run its test → PASS.
- [ ] **Step 2:** Apply to `GlobalPopularityStore` (+ test) → PASS.
- [ ] **Step 3:** Apply to `RedisTopKStore` (+ test) → PASS.
- [ ] **Step 4:** Apply to `ShardedTopKStore` (read/write executors + pipelined ZADD) (+ test) → PASS.
- [ ] **Step 5:** `mvn -q test -Dtest='Redis*StoreTest,GlobalPopularityStoreTest,ShardedTopKStoreTest'` → PASS.
- [ ] **Step 6:** Commit: `refactor(redis): stores use RedisExecutor (Lettuce)`.

---

### Task 4: Migrate `infrastructure/redis/sharding`

**Files:** `ShardedRecordStore.java`, `SequenceGenerator.java`, `ShardTopologyStore.java` (+ their tests, incl. Testcontainers `RedisShardingTestBase` and subclasses).

- [ ] **Step 1:** Apply the Task-3 recipe. `ShardedRecordStore.doWrite` pipeline (HSET + EXPIRE + ZADD + XADD): use `executePipelined` collecting `RedisFuture`s; `xadd` → `c.xadd(key, XAddArgs.Builder.maxlen(n).approximateTrimming(), map)`; `zadd` with gt/nx/xx → `ZAddArgs`.
- [ ] **Step 2:** `RedisShardingTestBase` currently builds a `JedisPool` against the Testcontainers Redis — change it to build a `RedisExecutor` via `new LettuceRedisExecutor(RedisClient.create(uri), client.connect(UTF8), defaultPoolCfg, true)` pointed at the container host/port. Subclasses then construct stores with the executor.
- [ ] **Step 3:** Run all sharding tests (`-Dtest='Sharded*Test,SequenceGeneratorTest,ShardTopologyStoreTest'`). These are `@Tag("docker")` Testcontainers tests — run locally with `-Dgroups=docker` per project convention. Expected: PASS.
- [ ] **Step 4:** Commit: `refactor(redis): sharding stores use RedisExecutor (Lettuce)`.

---

### Task 5: Migrate `infrastructure/lock` (Lua)

**Files:** `RedisMutex.java`, `RedisDistributedLock.java`, `WatchdogLock.java` (+ tests).

**Recipe — Lua via Lettuce:**

```java
// acquire: SET NX EX returns "OK"/null
String r = exec.execute(c -> c.set(lockKey, token, io.lettuce.core.SetArgs.Builder.nx().ex(ttl)));
return "OK".equals(r) ? token : null;

// release (GET+DEL Lua), output is INTEGER (Long)
Long n = exec.execute(c -> c.eval(RELEASE_SCRIPT, io.lettuce.core.ScriptOutputType.INTEGER,
        new String[]{lockKey}, token));
return n != null && n == 1L;

// WatchdogLock renew (GET+PEXPIRE Lua): ScriptOutputType.INTEGER, args = new String[]{key}, token, String.valueOf(ttlMs)
```

Note Lettuce `eval` signature: `eval(String script, ScriptOutputType type, K[] keys, V... values)`. Keys and values are passed as separate varargs, not Jedis's `List<keys>, List<args>`.

- [ ] **Step 1:** Migrate `RedisMutex` (+ `RedisMutexTest` — verify `eval(..., INTEGER, ...)` called and return mapping). PASS.
- [ ] **Step 2:** Migrate `RedisDistributedLock` (acquire Lua `SET NX PX` → INTEGER/`"OK"` per its script; release Lua → INTEGER) (+ test). PASS.
- [ ] **Step 3:** Migrate `WatchdogLock` (renew + release Lua) (+ test). PASS.
- [ ] **Step 4:** `mvn -q test -Dtest='RedisMutexTest,RedisDistributedLockTest,WatchdogLockTest'` → PASS.
- [ ] **Step 5:** Commit: `refactor(lock): Redis locks use RedisExecutor + Lettuce eval`.

---

### Task 6: Migrate rate limiter, token services, `LazyJedisPool`

**Files:** `ratelimit/RedisRateLimiter.java`, `application/auth/LoginTokenService.java`, `application/auth/SubmitTokenService.java`, rename `application/model/LazyJedisPool.java` → `LazyRedisExecutor.java` (+ tests).

- [ ] **Step 1:** Create `LazyRedisExecutor` (copy `LazyJedisPool`'s double-checked locking, but `Supplier<RedisExecutor>` and an `execute(...)`/`executeRead(...)` delegate plus `close()`). Delete `LazyJedisPool`.
- [ ] **Step 2:** `RedisRateLimiter`: Lua INCR+EXPIRE+TTL returns a multi-value `{allowed, ttl}` → `ScriptOutputType.MULTI` returns `List<Object>`; cast elements to `Long`. Update `RedisRateLimiterTest`. PASS.
- [ ] **Step 3:** `LoginTokenService` (SET NX EX / GET / DEL) and `SubmitTokenService` (CONSUME Lua, `ScriptOutputType.INTEGER`) use `LazyRedisExecutor`. Update both tests. PASS.
- [ ] **Step 4:** `mvn -q test -Dtest='RedisRateLimiterTest,LoginTokenServiceTest,SubmitTokenServiceTest'` → PASS.
- [ ] **Step 5:** Commit: `refactor(redis): rate limiter, token services, LazyRedisExecutor on Lettuce`.

---

### Task 7: Migrate `OnlineFeatureStore` + online learner/flush

**Files:** `infrastructure/store/OnlineFeatureStore.java`, `application/online/OnlineLearner.java`, `application/online/LearnerFlushScheduler.java` (+ tests).

- [ ] **Step 1:** Apply the store recipe (GET/MGET) to `OnlineFeatureStore`; update `OnlineFeatureStoreTest` (mock `RedisExecutor`). PASS.
- [ ] **Step 2:** `OnlineLearner` + `LearnerFlushScheduler`: field `Pool<Jedis>` → `RedisExecutor`; null-pool guards become null-executor guards; flush writes use `execute`/`executePipelined`. Update `LearnerFlushSchedulerTest`. PASS.
- [ ] **Step 3:** `mvn -q test -Dtest='OnlineFeatureStoreTest,LearnerFlushSchedulerTest,OnlineLearnerTest'` → PASS.
- [ ] **Step 4:** Commit: `refactor(online): online store + learner on RedisExecutor`.

---

### Task 8: Spring config + Jetty servers + `ModelRuntimeProvider`

**Files:** `config/RedisConfig.java`, `config/RedisProperties.java` (unchanged binding; verify), `api/serving/RecSysServer.java`, `api/online/OnlinePredictionServer.java`, `api/gateway/MicroserviceGatewayServer.java`, `application/model/ModelRuntimeProvider.java`.

- [ ] **Step 1:** `RedisConfig`: `@Bean Pool<Jedis> jedisPool()` → `@Bean RedisExecutor redisExecutor()` via `LettuceClientFactory.from(props)`; `@Bean RedisReadReplicaRouter` via `LettuceClientFactory.routerFrom(props)`. Update any `@Bean` consumers' parameter types.
- [ ] **Step 2:** In the three Jetty servers, replace `RedisConnectionFactory.fromEnv()` (→ `Pool<Jedis>`) with `LettuceClientFactory.fromEnv()` (→ `RedisExecutor`); pass executors into store constructors; ensure shutdown hooks call `executor.close()`. `ModelRuntimeProvider`: `recallPool = RedisConnectionFactory.fromEnv(RECALL_REDIS_TIMEOUT_MS)` → `LettuceClientFactory.fromEnv(RECALL_REDIS_TIMEOUT_MS)`; close in `@PreDestroy`.
- [ ] **Step 3:** Delete `RedisConnectionFactory.java` and `RedisConnectionFactoryTest.java` (superseded by `LettuceClientFactory`).
- [ ] **Step 4:** `mvn -q compile` → SUCCESS, then `mvn -q test` (non-docker) → PASS.
- [ ] **Step 5:** Commit: `refactor(redis): wire Lettuce into config + all four services`.

---

### Task 9: Migrate Flink module `OnlineFeatureStreamingJob`

**Files:** `src/main/java/com/recsys/online/flink/OnlineFeatureStreamingJob.java`.

> Excluded from the Maven compile. Compile it during this task via the build profile that includes Flink/Spark sources (see project `pom.xml` profiles), or temporarily include it to verify it compiles, then restore exclusion.

- [ ] **Step 1:** `AbstractRedisSink`: replace `JedisPool` field with a Lettuce `RedisClient` + `StatefulRedisConnection<String,String>` created in `open(...)`, closed in `close()`. Replace `try (Jedis j = pool.getResource())` with `connection.sync()`.
- [ ] **Step 2:** Port the 3 Lua scripts: `SET_IF_NEWER` (GET+SETEX) → `ScriptOutputType.INTEGER`; `SET_IF_NEWER_WITH_LINEAGE` (SETEX×3/RPUSH/LTRIM/EXPIRE/SADD) → `ScriptOutputType.INTEGER` (or `STATUS`); `ZSET_IF_NEWER` (GET/DEL/ZADD/EXPIRE/SETEX) → `ScriptOutputType.INTEGER`. Use `sync.eval(script, type, keys[], args...)`.
- [ ] **Step 3:** Compile the module (profile) → SUCCESS.
- [ ] **Step 4:** Commit: `refactor(flink): OnlineFeatureStreamingJob sinks on Lettuce`.

---

### Task 10: Migrate Spark module `ItemEmbeddingJob`

**Files:** `src/main/java/com/recsys/training/rulebased/ItemEmbeddingJob.java`.

- [ ] **Step 1:** Inside `foreachPartition`, replace `new Jedis(host,port)` with `RedisClient client = RedisClient.create(RedisURI.create(host,port)); StatefulRedisConnection<String,String> conn = client.connect(StringCodec.UTF8);` (created inside the lambda so nothing non-serializable crosses the Spark boundary). Replace `Pipeline`/`pipeline.set(...).sync()` with async `setAutoFlushCommands(false)` + collect `RedisFuture`s + `flushCommands()` + `LettuceFutures.awaitAll`. Close conn + `client.shutdown()` in a `finally`.
- [ ] **Step 2:** Compile the module (profile) → SUCCESS.
- [ ] **Step 3:** Commit: `refactor(spark): ItemEmbeddingJob bulk write on Lettuce`.

---

### Task 11: Remove the Jedis dependency + final gates

**Files:** `pom.xml`.

- [ ] **Step 1:** Remove the `redis.clients:jedis` dependency block from `pom.xml`.
- [ ] **Step 2:** Run `grep -rn "redis.clients.jedis" src` → expect **no output**. Fix any stragglers.
- [ ] **Step 3:** `mvn -q clean package -DskipTests` → SUCCESS.
- [ ] **Step 4:** `mvn -q test` → PASS. Then docker-tagged tests: `mvn -q test -Dgroups=docker` → PASS.
- [ ] **Step 5:** Smoke: start each service against a local Redis and hit one endpoint (per `CLAUDE.md` run commands); confirm a recommendation/health response.
- [ ] **Step 6:** Commit: `chore(redis): remove Jedis dependency — migration complete`.

---

## Self-Review

- **Spec coverage:** port (T1), adapter+pooling (T1), factory+Sentinel+timeouts (T1), AZ router (T2), stores (T3–T4), Lua locks (T5), rate-limit/tokens/lazy (T6), online (T7), Spring+Jetty wiring (T8), Flink (T9), Spark (T10), dep removal + gates (T11). All spec sections mapped.
- **Type consistency:** `RedisExecutor.execute/executeRead/executePipelined` and `RedisReadReplicaRouter.writable()/readable()` used consistently across tasks; `eval(script, ScriptOutputType, String[] keys, String... values)` form used in T5/T6/T9.
- **Risk area (Lua output types):** explicit per-script `ScriptOutputType` in T5/T6/T9 with tests.
