# Redis HA, Multi-AZ, and Backup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single-node `JedisPool` with a `RedisConnectionFactory` that supports Redis Sentinel (local/dev) and ElastiCache standalone (prod), add RDB snapshot persistence, and wire up K8s/Docker Compose HA infrastructure.

**Architecture:** A new `RedisConnectionFactory.fromEnv()` reads `REDIS_MODE` (sentinel|standalone) and returns either `JedisSentinelPool` or `JedisPool`, both typed as `Pool<Jedis>`. All eight Redis consumer classes widen their constructor/field types from `JedisPool` to `Pool<Jedis>`. Infrastructure adds a 3-node Sentinel cluster in K8s base and Docker Compose; the EKS overlay scales those to 0 and points at ElastiCache.

**Tech Stack:** Jedis 5.1.3 (`JedisSentinelPool`, `redis.clients.jedis.util.Pool`), Redis 7-alpine, Kubernetes Kustomize, Docker Compose.

**Spec:** `docs/superpowers/specs/2026-06-02-redis-ha-multiaz-backup-design.md`

---

## File Map

| Action | File |
|---|---|
| Create | `src/main/java/com/recsys/infrastructure/redis/RedisConnectionFactory.java` |
| Create | `src/test/java/com/recsys/infrastructure/redis/RedisConnectionFactoryTest.java` |
| Modify | `src/main/java/com/recsys/infrastructure/redis/RedisEmbeddingStore.java` |
| Modify | `src/main/java/com/recsys/infrastructure/redis/RedisTopKStore.java` |
| Modify | `src/main/java/com/recsys/infrastructure/redis/ShardedTopKStore.java` |
| Modify | `src/main/java/com/recsys/streaming/OnlineFeatureStore.java` |
| Modify | `src/main/java/com/recsys/streaming/RedisMutex.java` |
| Modify | `src/main/java/com/recsys/streaming/RedisRateLimiter.java` |
| Modify | `src/main/java/com/recsys/streaming/WatchdogLock.java` |
| Modify | `src/main/java/com/recsys/streaming/RedisDistributedLock.java` |
| Modify | `src/main/java/com/recsys/serving/RecSysServer.java` |
| Modify | `src/main/java/com/recsys/streaming/OnlinePredictionServer.java` |
| Modify | `src/main/java/com/recsys/modelbased/service/ModelRuntimeProvider.java` |
| Modify | `src/main/java/com/recsys/modelbased/service/SubmitTokenService.java` |
| Modify | `src/main/java/com/recsys/modelbased/config/SubmitTokenProperties.java` |
| Modify | `src/main/resources/application.yml` |
| Rewrite | `k8s/base/redis.yaml` → `k8s/base/redis-cluster.yaml` (delete old, create new) |
| Modify | `k8s/base/configmap.yaml` |
| Modify | `k8s/base/kustomization.yaml` |
| Create | `k8s/eks/redis-elasticache-patch.yaml` |
| Modify | `k8s/eks/kustomization.yaml` |
| Modify | `docker-compose.streaming.yml` |
| Create | `docker/redis/sentinel.conf` |

---

## Task 1: Create `RedisConnectionFactory`

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/redis/RedisConnectionFactory.java`
- Create: `src/test/java/com/recsys/infrastructure/redis/RedisConnectionFactoryTest.java`

- [ ] **Step 1.1: Write the failing tests**

Create `src/test/java/com/recsys/infrastructure/redis/RedisConnectionFactoryTest.java`:

```java
package com.recsys.infrastructure.redis;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.util.Pool;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RedisConnectionFactoryTest {

    private static JedisPoolConfig noIdleConfig() {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMinIdle(0);
        cfg.setTestOnBorrow(false);
        return cfg;
    }

    @Test
    void standaloneModeCreatesJedisPool() {
        Map<String, String> env = Map.of(
            "REDIS_MODE", "standalone",
            "REDIS_HOST", "localhost",
            "REDIS_PORT", "6379"
        );
        try (Pool<Jedis> pool = RedisConnectionFactory.create(env, noIdleConfig())) {
            assertInstanceOf(JedisPool.class, pool);
        }
    }

    @Test
    void defaultModeIsStandalone() {
        try (Pool<Jedis> pool = RedisConnectionFactory.create(Map.of(), noIdleConfig())) {
            assertInstanceOf(JedisPool.class, pool);
        }
    }

    @Test
    void parseSentinelNodesHandlesMultipleCommaSeparatedEntries() {
        Set<String> nodes = RedisConnectionFactory.parseSentinelNodes(
            "sentinel-1:26379,sentinel-2:26379, sentinel-3:26379"
        );
        assertEquals(Set.of("sentinel-1:26379", "sentinel-2:26379", "sentinel-3:26379"), nodes);
    }

    @Test
    void parseSentinelNodesDefaultsToLocalhostWhenBlank() {
        assertEquals(Set.of("localhost:26379"),
            RedisConnectionFactory.parseSentinelNodes(""));
        assertEquals(Set.of("localhost:26379"),
            RedisConnectionFactory.parseSentinelNodes(null));
    }

    @Test
    void parsePortReturnsDefaultOnInvalidValue() {
        assertEquals(6379, RedisConnectionFactory.parsePort("notANumber"));
    }

    @Test
    void parsePortParsesValidPort() {
        assertEquals(6380, RedisConnectionFactory.parsePort("6380"));
    }
}
```

- [ ] **Step 1.2: Run tests — verify they fail with "cannot find symbol"**

```bash
mvn test -Dtest=RedisConnectionFactoryTest -DskipTests=false 2>&1 | tail -20
```

Expected: compilation failure — `RedisConnectionFactory` does not exist yet.

- [ ] **Step 1.3: Create `RedisConnectionFactory`**

Create `src/main/java/com/recsys/infrastructure/redis/RedisConnectionFactory.java`:

```java
package com.recsys.infrastructure.redis;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisSentinelPool;
import redis.clients.jedis.util.Pool;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class RedisConnectionFactory {

    static final int DEFAULT_MAX_TOTAL = 50;
    static final int DEFAULT_MAX_IDLE  = 10;
    static final int DEFAULT_MIN_IDLE  = 2;

    private RedisConnectionFactory() {}

    public static Pool<Jedis> fromEnv() {
        return create(System.getenv(), defaultPoolConfig());
    }

    static Pool<Jedis> create(Map<String, String> env, JedisPoolConfig config) {
        String mode = env.getOrDefault("REDIS_MODE", "standalone");
        if ("sentinel".equalsIgnoreCase(mode)) {
            String master = env.getOrDefault("REDIS_SENTINEL_MASTER", "mymaster");
            String nodes  = env.getOrDefault("REDIS_SENTINEL_NODES", "");
            return new JedisSentinelPool(master, parseSentinelNodes(nodes), config);
        }
        String host = env.getOrDefault("REDIS_HOST", "localhost");
        int    port = parsePort(env.getOrDefault("REDIS_PORT", "6379"));
        return new JedisPool(config, host, port);
    }

    static Set<String> parseSentinelNodes(String nodes) {
        Set<String> result = new LinkedHashSet<>();
        if (nodes == null || nodes.isBlank()) {
            result.add("localhost:26379");
            return result;
        }
        Arrays.stream(nodes.split(","))
              .map(String::trim)
              .filter(s -> !s.isEmpty())
              .forEach(result::add);
        return result;
    }

    static int parsePort(String value) {
        if (value == null) return 6379;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 6379;
        }
    }

    private static JedisPoolConfig defaultPoolConfig() {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(DEFAULT_MAX_TOTAL);
        cfg.setMaxIdle(DEFAULT_MAX_IDLE);
        cfg.setMinIdle(DEFAULT_MIN_IDLE);
        cfg.setTestOnBorrow(true);
        cfg.setBlockWhenExhausted(true);
        return cfg;
    }
}
```

- [ ] **Step 1.4: Run tests — verify all pass**

```bash
mvn test -Dtest=RedisConnectionFactoryTest 2>&1 | tail -15
```

Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 1.5: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/redis/RedisConnectionFactory.java \
        src/test/java/com/recsys/infrastructure/redis/RedisConnectionFactoryTest.java
git commit -m "feat: add RedisConnectionFactory for sentinel/standalone pool abstraction"
```

---

## Task 2: Type migration — `infrastructure/redis` consumers

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/redis/RedisEmbeddingStore.java`
- Modify: `src/main/java/com/recsys/infrastructure/redis/RedisTopKStore.java`
- Modify: `src/main/java/com/recsys/infrastructure/redis/ShardedTopKStore.java`

The change in each file is identical in structure: replace `JedisPool` with `Pool<Jedis>` in import, field, and all constructor signatures. Logic is unchanged — `pool.getResource()` returns `Jedis` on both types.

- [ ] **Step 2.1: Run existing tests to establish a green baseline**

```bash
mvn test -Dtest="RedisEmbeddingStoreTest,RedisTopKStoreTest,ShardedTopKStoreTest" 2>&1 | tail -10
```

Expected: all tests pass (note the count for verification after migration).

- [ ] **Step 2.2: Migrate `RedisEmbeddingStore`**

In `src/main/java/com/recsys/infrastructure/redis/RedisEmbeddingStore.java`:

Replace:
```java
import redis.clients.jedis.JedisPool;
```
With:
```java
import redis.clients.jedis.util.Pool;
```

Replace field declaration:
```java
private final JedisPool pool;
```
With:
```java
private final Pool<Jedis> pool;
```

Replace all three constructor signatures — change `JedisPool pool` to `Pool<Jedis> pool` in:
```java
public RedisEmbeddingStore(Pool<Jedis> pool, String keyPrefix)
RedisEmbeddingStore(Pool<Jedis> pool, String keyPrefix, double jitterFraction)
RedisEmbeddingStore(Pool<Jedis> pool, String keyPrefix, double jitterFraction, int mgetBatchSize)
```

- [ ] **Step 2.3: Migrate `RedisTopKStore`**

In `src/main/java/com/recsys/infrastructure/redis/RedisTopKStore.java`:

Replace `import redis.clients.jedis.JedisPool;` with `import redis.clients.jedis.util.Pool;`

Replace `private final JedisPool pool;` with `private final Pool<Jedis> pool;`

Replace both constructor signatures — change `JedisPool pool` to `Pool<Jedis> pool`:
```java
public RedisTopKStore(Pool<Jedis> pool, String keyPrefix)
public RedisTopKStore(Pool<Jedis> pool, String keyPrefix, long cacheTtlMs)
```

- [ ] **Step 2.4: Migrate `ShardedTopKStore`**

In `src/main/java/com/recsys/infrastructure/redis/ShardedTopKStore.java`:

Replace `import redis.clients.jedis.JedisPool;` with `import redis.clients.jedis.util.Pool;`

Replace `private final JedisPool pool;` with `private final Pool<Jedis> pool;`

Replace both constructor signatures — change `JedisPool pool` to `Pool<Jedis> pool`:
```java
public ShardedTopKStore(Pool<Jedis> pool, String keyPrefix)
ShardedTopKStore(Pool<Jedis> pool, String keyPrefix, int shardCount, long cacheTtlMs, HotKeyDetector hotKeyDetector)
```

- [ ] **Step 2.5: Build and run tests — verify no regressions**

```bash
mvn test -Dtest="RedisEmbeddingStoreTest,RedisTopKStoreTest,ShardedTopKStoreTest" 2>&1 | tail -10
```

Expected: same test count as Step 2.1, all pass.

- [ ] **Step 2.6: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/redis/RedisEmbeddingStore.java \
        src/main/java/com/recsys/infrastructure/redis/RedisTopKStore.java \
        src/main/java/com/recsys/infrastructure/redis/ShardedTopKStore.java
git commit -m "refactor: widen JedisPool to Pool<Jedis> in infrastructure/redis consumers"
```

---

## Task 3: Type migration — `streaming` consumers

**Files:**
- Modify: `src/main/java/com/recsys/streaming/OnlineFeatureStore.java`
- Modify: `src/main/java/com/recsys/streaming/RedisMutex.java`
- Modify: `src/main/java/com/recsys/streaming/RedisRateLimiter.java`
- Modify: `src/main/java/com/recsys/streaming/WatchdogLock.java`
- Modify: `src/main/java/com/recsys/streaming/RedisDistributedLock.java`

- [ ] **Step 3.1: Run existing streaming tests for a green baseline**

```bash
mvn test -Dtest="OnlineFeatureStoreTest,RedisMutexTest,RedisRateLimiterTest,WatchdogLockTest,RedisDistributedLockTest" 2>&1 | tail -10
```

- [ ] **Step 3.2: Migrate `OnlineFeatureStore`**

In `src/main/java/com/recsys/streaming/OnlineFeatureStore.java`:

Replace `import redis.clients.jedis.JedisPool;` with `import redis.clients.jedis.util.Pool;`

Replace `private final JedisPool pool;` with `private final Pool<Jedis> pool;`

Replace all four constructor signatures — change `JedisPool pool` to `Pool<Jedis> pool`:
```java
public OnlineFeatureStore(Pool<Jedis> pool)
OnlineFeatureStore(Pool<Jedis> pool, long cacheTtlMs)
OnlineFeatureStore(Pool<Jedis> pool, long cacheTtlMs, int maxCacheUsers)
OnlineFeatureStore(Pool<Jedis> pool, long cacheTtlMs, int maxCacheUsers, int redisMgetBatchSize)
```

- [ ] **Step 3.3: Migrate `RedisMutex`**

In `src/main/java/com/recsys/streaming/RedisMutex.java`:

Replace `import redis.clients.jedis.JedisPool;` with `import redis.clients.jedis.util.Pool;`

Replace `private final JedisPool pool;` with `private final Pool<Jedis> pool;`

Replace both constructor signatures — change `JedisPool pool` to `Pool<Jedis> pool`:
```java
public RedisMutex(Pool<Jedis> pool)
RedisMutex(Pool<Jedis> pool, String keyPrefix, long lockTtlSeconds)
```

- [ ] **Step 3.4: Migrate `RedisRateLimiter`**

In `src/main/java/com/recsys/streaming/RedisRateLimiter.java`:

Replace `import redis.clients.jedis.JedisPool;` with `import redis.clients.jedis.util.Pool;`

Replace `private final JedisPool pool;` with `private final Pool<Jedis> pool;`

Replace all four constructor signatures — change `JedisPool pool` to `Pool<Jedis> pool`:
```java
public RedisRateLimiter(Pool<Jedis> pool)
RedisRateLimiter(Pool<Jedis> pool, String keyPrefix, long limit, int windowSeconds)
RedisRateLimiter(Pool<Jedis> pool, String keyPrefix, long limit, int windowSeconds, ...)
RedisRateLimiter(Pool<Jedis> pool, String keyPrefix, long limit, int windowSeconds, ..., ...)
```
(Keep all existing parameters after `pool` unchanged — only the declared type of the first parameter changes.)

- [ ] **Step 3.5: Migrate `WatchdogLock`**

In `src/main/java/com/recsys/streaming/WatchdogLock.java`:

Replace `import redis.clients.jedis.JedisPool;` with `import redis.clients.jedis.util.Pool;`

Replace `private final JedisPool pool;` with `private final Pool<Jedis> pool;`

Replace **all** method signatures that take `JedisPool pool` with `Pool<Jedis> pool` — there are four:
```java
private WatchdogLock(Pool<Jedis> pool, String lockKey, String token, ...)
public static WatchdogLock tryAcquire(Pool<Jedis> pool, String resource)
static WatchdogLock tryAcquire(Pool<Jedis> pool, String keyPrefix, String resource, ...)
```
Also update local variable types inside the method bodies if any are declared as `JedisPool`.

- [ ] **Step 3.6: Migrate `RedisDistributedLock`**

In `src/main/java/com/recsys/streaming/RedisDistributedLock.java`:

Replace `import redis.clients.jedis.JedisPool;` with `import redis.clients.jedis.util.Pool;`

Replace `private final JedisPool pool;` with `private final Pool<Jedis> pool;`

Replace constructor signatures — change `JedisPool pool` to `Pool<Jedis> pool`:
```java
public RedisDistributedLock(Pool<Jedis> pool)
RedisDistributedLock(Pool<Jedis> pool, String keyPrefix, long defaultTtlSeconds)
```

- [ ] **Step 3.7: Build and run tests — verify no regressions**

```bash
mvn test -Dtest="OnlineFeatureStoreTest,RedisMutexTest,RedisRateLimiterTest,WatchdogLockTest,RedisDistributedLockTest" 2>&1 | tail -10
```

Expected: same test count as Step 3.1, all pass.

- [ ] **Step 3.8: Full compile check**

```bash
mvn package -DskipTests 2>&1 | tail -15
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3.9: Commit**

```bash
git add src/main/java/com/recsys/streaming/OnlineFeatureStore.java \
        src/main/java/com/recsys/streaming/RedisMutex.java \
        src/main/java/com/recsys/streaming/RedisRateLimiter.java \
        src/main/java/com/recsys/streaming/WatchdogLock.java \
        src/main/java/com/recsys/streaming/RedisDistributedLock.java
git commit -m "refactor: widen JedisPool to Pool<Jedis> in streaming consumers"
```

---

## Task 4: Update Jetty server entry points

**Files:**
- Modify: `src/main/java/com/recsys/serving/RecSysServer.java`
- Modify: `src/main/java/com/recsys/streaming/OnlinePredictionServer.java`

- [ ] **Step 4.1: Update `RecSysServer`**

In `src/main/java/com/recsys/serving/RecSysServer.java`:

Add imports (both are needed — `Pool` for the field type, `Jedis` for the generic parameter):
```java
import com.recsys.infrastructure.redis.RedisConnectionFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.Pool;
```

In `run()`, replace:
```java
String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
int redisPort = readIntEnv("REDIS_PORT", 6379);
// ...
try (JedisPool jedisPool = new JedisPool(redisHost, redisPort)) {
```
With:
```java
try (Pool<Jedis> jedisPool = RedisConnectionFactory.fromEnv()) {
```

Remove the now-unused `redisHost` and `redisPort` local variables (the `readIntEnv` helper for `REDIS_PORT` remains — it's still used for `PORT`).

Remove import `import redis.clients.jedis.JedisPool;` (no longer used — `Pool` is used instead).

- [ ] **Step 4.2: Update `OnlinePredictionServer`**

In `src/main/java/com/recsys/streaming/OnlinePredictionServer.java`:

Add imports (both needed — same reason as RecSysServer):
```java
import com.recsys.infrastructure.redis.RedisConnectionFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.Pool;
```

In `main()`, replace:
```java
String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
int redisPort = readIntEnv("REDIS_PORT", 6379);
// ...
try (JedisPool jedisPool = new JedisPool(redisHost, redisPort);
```
With:
```java
try (Pool<Jedis> jedisPool = RedisConnectionFactory.fromEnv();
```

Remove the two now-unused local variables. Remove `import redis.clients.jedis.JedisPool;`.

- [ ] **Step 4.3: Build to verify**

```bash
mvn package -DskipTests 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4.4: Commit**

```bash
git add src/main/java/com/recsys/serving/RecSysServer.java \
        src/main/java/com/recsys/streaming/OnlinePredictionServer.java
git commit -m "refactor: use RedisConnectionFactory in Jetty server entry points"
```

---

## Task 5: Update Spring Boot services

**Files:**
- Modify: `src/main/java/com/recsys/modelbased/service/ModelRuntimeProvider.java`
- Modify: `src/main/java/com/recsys/modelbased/service/SubmitTokenService.java`
- Modify: `src/main/java/com/recsys/modelbased/config/SubmitTokenProperties.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 5.1: Run existing Spring Boot tests for a green baseline**

```bash
mvn test -Dtest="ModelArtifactServiceRedisTest,SubmitTokenServiceTest" 2>&1 | tail -10
```

- [ ] **Step 5.2: Update `SubmitTokenProperties`**

In `src/main/java/com/recsys/modelbased/config/SubmitTokenProperties.java`, **delete**:
- The `private String redisHost = "localhost";` field and its getter/setter
- The `private int redisPort = 6379;` field and its getter/setter

The class retains only `enabled`, `ttlSeconds`, and `keyPrefix`.

- [ ] **Step 5.3: Update `SubmitTokenService`**

In `src/main/java/com/recsys/modelbased/service/SubmitTokenService.java`:

Add imports:
```java
import com.recsys.infrastructure.redis.RedisConnectionFactory;
import redis.clients.jedis.util.Pool;
```

Change field declarations:
```java
// Before:
private final Supplier<JedisPool> poolFactory;
private volatile JedisPool pool;

// After:
private final Supplier<Pool<Jedis>> poolFactory;
private volatile Pool<Jedis> pool;
```

Update the public `@Autowired` constructor body:
```java
// Before:
this(properties, () -> new JedisPool(properties.getRedisHost(), properties.getRedisPort()));

// After:
this(properties, () -> RedisConnectionFactory.fromEnv());
```

Update the package-private test constructor signature:
```java
// Before:
SubmitTokenService(SubmitTokenProperties properties, Supplier<JedisPool> poolFactory)

// After:
SubmitTokenService(SubmitTokenProperties properties, Supplier<Pool<Jedis>> poolFactory)
```

Update `close()` method — change local variable type:
```java
// Before:
JedisPool current = pool;

// After:
Pool<Jedis> current = pool;
```

Update `jedis()` method — change local variable type (two occurrences):
```java
// Before:
JedisPool current = pool;
// (second occurrence inside synchronized block)
JedisPool current = pool;
current = poolFactory.get();
pool = current;

// After:
Pool<Jedis> current = pool;
// (second occurrence inside synchronized block)
Pool<Jedis> current = pool;
current = poolFactory.get();
pool = current;
```

Remove `import redis.clients.jedis.JedisPool;`.

- [ ] **Step 5.4: Update `SubmitTokenServiceTest` if needed**

Open `src/test/java/com/recsys/modelbased/service/SubmitTokenServiceTest.java`. Find any variable declarations of the form:
```java
Supplier<JedisPool> factory = ...;
```
Change to:
```java
Supplier<Pool<Jedis>> factory = ...;
```
(Lambda expressions passed inline — e.g., `() -> mockPool` — require no change as long as `mockPool` is typed as `JedisPool` or `Pool<Jedis>`.)

- [ ] **Step 5.5: Update `ModelRuntimeProvider`**

In `src/main/java/com/recsys/modelbased/service/ModelRuntimeProvider.java`:

Add imports:
```java
import com.recsys.infrastructure.redis.RedisConnectionFactory;
import redis.clients.jedis.util.Pool;
```

Remove import `import redis.clients.jedis.JedisPool;`.

Remove these two fields:
```java
private final String redisHost;
private final int redisPort;
```

Change field type:
```java
// Before:
private JedisPool redisItemEmbeddingPool;

// After:
private Pool<Jedis> redisItemEmbeddingPool;
```

Remove the no-arg constructor delegation of `"localhost"` and `6379`:
```java
// Before:
public ModelRuntimeProvider(ModelArtifactLocator artifactLocator, ABTestConfig abTestConfig) {
    this(artifactLocator, abTestConfig, "dssm_model.onnx", "classpath", "localhost", 6379, "i2vEmb");
}

// After:
public ModelRuntimeProvider(ModelArtifactLocator artifactLocator, ABTestConfig abTestConfig) {
    this(artifactLocator, abTestConfig, "dssm_model.onnx", "classpath", "i2vEmb");
}
```

Remove the two `@Value` parameters from the `@Autowired` constructor:
```java
// Before:
@Autowired
public ModelRuntimeProvider(ModelArtifactLocator artifactLocator,
                            ABTestConfig abTestConfig,
                            @Value("${recsys.model.file:dssm_model.onnx}") String modelFile,
                            @Value("${recsys.model.item-embeddings-source:classpath}") String itemEmbeddingsSource,
                            @Value("${recsys.model.redis.host:localhost}") String redisHost,
                            @Value("${recsys.model.redis.port:6379}") int redisPort,
                            @Value("${recsys.model.redis.item-embedding-prefix:i2vEmb}") String redisItemEmbeddingPrefix)

// After:
@Autowired
public ModelRuntimeProvider(ModelArtifactLocator artifactLocator,
                            ABTestConfig abTestConfig,
                            @Value("${recsys.model.file:dssm_model.onnx}") String modelFile,
                            @Value("${recsys.model.item-embeddings-source:classpath}") String itemEmbeddingsSource,
                            @Value("${recsys.model.redis.item-embedding-prefix:i2vEmb}") String redisItemEmbeddingPrefix)
```

Remove the two field assignments in the constructor body:
```java
// Delete these two lines:
this.redisHost = redisHost == null || redisHost.isBlank() ? "localhost" : redisHost.trim();
this.redisPort = redisPort;
```

Update `redisItemEmbeddingStoreIfEnabled()`:
```java
// Before:
if (redisItemEmbeddingPool == null) {
    redisItemEmbeddingPool = new JedisPool(redisHost, redisPort);
}

// After:
if (redisItemEmbeddingPool == null) {
    redisItemEmbeddingPool = RedisConnectionFactory.fromEnv();
}
```

In `destroy()`, update the local variable type:
```java
// Before (if present):
JedisPool current = redisItemEmbeddingPool;

// After:
Pool<Jedis> current = redisItemEmbeddingPool;
```
(If `destroy()` accesses `redisItemEmbeddingPool` directly, no change needed — `Pool<Jedis>` has `close()`.)

- [ ] **Step 5.6: Update `application.yml`**

In `src/main/resources/application.yml`, delete lines 83-84:
```yaml
    redis-host: ${REDIS_HOST:localhost}
    redis-port: ${REDIS_PORT:6379}
```

Delete lines 38-39 (under `recsys.model.redis`):
```yaml
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
```

The `recsys.model.redis` block retains only `item-embedding-prefix`.

- [ ] **Step 5.7: Build and run tests**

```bash
mvn test -Dtest="ModelArtifactServiceRedisTest,SubmitTokenServiceTest" 2>&1 | tail -10
```

Expected: same pass count as Step 5.1. Then full build:

```bash
mvn package -DskipTests 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5.8: Commit**

```bash
git add src/main/java/com/recsys/modelbased/service/ModelRuntimeProvider.java \
        src/main/java/com/recsys/modelbased/service/SubmitTokenService.java \
        src/main/java/com/recsys/modelbased/config/SubmitTokenProperties.java \
        src/main/resources/application.yml \
        src/test/java/com/recsys/modelbased/service/SubmitTokenServiceTest.java
git commit -m "refactor: use RedisConnectionFactory in Spring Boot services; remove redis host/port from properties"
```

---

## Task 6: K8s base — Redis Sentinel cluster

**Files:**
- Create: `k8s/base/redis-cluster.yaml`
- Delete: `k8s/base/redis.yaml`
- Modify: `k8s/base/configmap.yaml`
- Modify: `k8s/base/kustomization.yaml`

- [ ] **Step 6.1: Create `redis-cluster.yaml`**

Create `k8s/base/redis-cluster.yaml` with all resources:

```yaml
# Sentinel config template (read-only mount; init container copies to writable emptyDir)
apiVersion: v1
kind: ConfigMap
metadata:
  name: redis-sentinel-config
  namespace: recsys
data:
  sentinel-template.conf: |
    sentinel monitor mymaster redis-primary 6379 2
    sentinel down-after-milliseconds mymaster 5000
    sentinel failover-timeout mymaster 30000
    sentinel parallel-syncs mymaster 1
---
# Primary StatefulSet — 1 replica with RDB snapshots and a PVC for /data
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: redis-primary
  namespace: recsys
spec:
  serviceName: redis-primary
  replicas: 1
  selector:
    matchLabels:
      app: redis
      role: primary
  template:
    metadata:
      labels:
        app: redis
        role: primary
    spec:
      securityContext:
        runAsNonRoot: true
        runAsUser: 999
        fsGroup: 999
        seccompProfile:
          type: RuntimeDefault
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                labelSelector:
                  matchLabels:
                    app: redis
                topologyKey: topology.kubernetes.io/zone
      containers:
        - name: redis
          image: redis:7-alpine
          args:
            - "--save"
            - "900 1"
            - "--save"
            - "300 10"
            - "--save"
            - "60 10000"
            - "--maxmemory"
            - "200mb"
            - "--maxmemory-policy"
            - "allkeys-lru"
            - "--dir"
            - "/data"
          ports:
            - containerPort: 6379
          volumeMounts:
            - name: redis-primary-data
              mountPath: /data
          readinessProbe:
            exec:
              command: ["redis-cli", "ping"]
            initialDelaySeconds: 5
            periodSeconds: 10
          livenessProbe:
            exec:
              command: ["redis-cli", "ping"]
            initialDelaySeconds: 15
            periodSeconds: 20
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
            limits:
              cpu: 300m
              memory: 384Mi
  volumeClaimTemplates:
    - metadata:
        name: redis-primary-data
      spec:
        accessModes: ["ReadWriteOnce"]
        resources:
          requests:
            storage: 1Gi
---
apiVersion: v1
kind: Service
metadata:
  name: redis-primary
  namespace: recsys
spec:
  type: ClusterIP
  selector:
    app: redis
    role: primary
  ports:
    - name: redis
      port: 6379
      targetPort: 6379
---
# Replica StatefulSet — 2 replicas spread across AZs; no PVC (rebuilt from primary)
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: redis-replica
  namespace: recsys
spec:
  serviceName: redis-replica
  replicas: 2
  selector:
    matchLabels:
      app: redis
      role: replica
  template:
    metadata:
      labels:
        app: redis
        role: replica
    spec:
      securityContext:
        runAsNonRoot: true
        runAsUser: 999
        seccompProfile:
          type: RuntimeDefault
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                labelSelector:
                  matchLabels:
                    app: redis
                topologyKey: topology.kubernetes.io/zone
      containers:
        - name: redis
          image: redis:7-alpine
          args:
            - "--replicaof"
            - "redis-primary"
            - "6379"
            - "--maxmemory"
            - "200mb"
            - "--maxmemory-policy"
            - "allkeys-lru"
          ports:
            - containerPort: 6379
          readinessProbe:
            exec:
              command: ["redis-cli", "ping"]
            initialDelaySeconds: 5
            periodSeconds: 10
          livenessProbe:
            exec:
              command: ["redis-cli", "ping"]
            initialDelaySeconds: 15
            periodSeconds: 20
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
            limits:
              cpu: 300m
              memory: 384Mi
---
apiVersion: v1
kind: Service
metadata:
  name: redis-replica
  namespace: recsys
spec:
  type: ClusterIP
  selector:
    app: redis
    role: replica
  ports:
    - name: redis
      port: 6379
      targetPort: 6379
---
# Sentinel Deployment — 3 replicas; init container copies config to writable emptyDir
apiVersion: apps/v1
kind: Deployment
metadata:
  name: redis-sentinel
  namespace: recsys
spec:
  replicas: 3
  selector:
    matchLabels:
      app: redis-sentinel
  template:
    metadata:
      labels:
        app: redis-sentinel
    spec:
      securityContext:
        runAsNonRoot: true
        runAsUser: 999
        seccompProfile:
          type: RuntimeDefault
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                labelSelector:
                  matchLabels:
                    app: redis-sentinel
                topologyKey: topology.kubernetes.io/zone
      initContainers:
        - name: copy-sentinel-config
          image: redis:7-alpine
          command:
            - sh
            - -c
            - cp /etc/redis/sentinel-template.conf /data/sentinel.conf
          volumeMounts:
            - name: sentinel-config-template
              mountPath: /etc/redis
            - name: sentinel-data
              mountPath: /data
      containers:
        - name: sentinel
          image: redis:7-alpine
          command: ["redis-sentinel", "/data/sentinel.conf"]
          ports:
            - containerPort: 26379
          volumeMounts:
            - name: sentinel-data
              mountPath: /data
          readinessProbe:
            exec:
              command: ["redis-cli", "-p", "26379", "ping"]
            initialDelaySeconds: 5
            periodSeconds: 10
          livenessProbe:
            exec:
              command: ["redis-cli", "-p", "26379", "ping"]
            initialDelaySeconds: 15
            periodSeconds: 20
          resources:
            requests:
              cpu: 50m
              memory: 64Mi
            limits:
              cpu: 100m
              memory: 128Mi
      volumes:
        - name: sentinel-config-template
          configMap:
            name: redis-sentinel-config
        - name: sentinel-data
          emptyDir: {}
---
apiVersion: v1
kind: Service
metadata:
  name: redis-sentinel
  namespace: recsys
spec:
  type: ClusterIP
  selector:
    app: redis-sentinel
  ports:
    - name: sentinel
      port: 26379
      targetPort: 26379
```

- [ ] **Step 6.2: Delete the old single-node `redis.yaml`**

```bash
git rm k8s/base/redis.yaml
```

- [ ] **Step 6.3: Update `k8s/base/configmap.yaml`**

Add three lines to the `data:` section of `k8s/base/configmap.yaml`:

```yaml
  REDIS_MODE: "sentinel"
  REDIS_SENTINEL_MASTER: "mymaster"
  REDIS_SENTINEL_NODES: "redis-sentinel:26379"
```

Keep the existing `REDIS_HOST: "redis"` and `REDIS_PORT: "6379"` lines — they are used in standalone mode as fallbacks.

- [ ] **Step 6.4: Update `k8s/base/kustomization.yaml`**

Replace `- redis.yaml` with `- redis-cluster.yaml`:

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - namespace.yaml
  - configmap.yaml
  - redis-cluster.yaml
  - catalog-serving.yaml
  - model-serving.yaml
  - online-serving.yaml
  - api-gateway.yaml
  - pdb.yaml
  - hpa.yaml
  - network-policy.yaml
```

- [ ] **Step 6.5: Validate the kustomize output**

```bash
kubectl kustomize k8s/base 2>&1 | grep "kind:" | sort
```

Expected output includes:
```
kind: ConfigMap
kind: Deployment        # redis-sentinel
kind: StatefulSet       # redis-primary
kind: StatefulSet       # redis-replica
kind: Service           # redis-primary, redis-replica, redis-sentinel
```

- [ ] **Step 6.6: Commit**

```bash
git add k8s/base/redis-cluster.yaml \
        k8s/base/configmap.yaml \
        k8s/base/kustomization.yaml
git commit -m "feat: replace single-node redis with Sentinel cluster in K8s base (RDB snapshots, multi-AZ affinity)"
```

---

## Task 7: Docker Compose — local Sentinel cluster

**Files:**
- Create: `docker/redis/sentinel.conf`
- Modify: `docker-compose.streaming.yml`

- [ ] **Step 7.1: Create sentinel config**

Create `docker/redis/sentinel.conf`:

```
sentinel monitor mymaster redis-primary 6379 2
sentinel down-after-milliseconds mymaster 5000
sentinel failover-timeout mymaster 30000
sentinel parallel-syncs mymaster 1
```

- [ ] **Step 7.2: Update `docker-compose.streaming.yml`**

Remove the existing `redis` service block entirely:
```yaml
  redis:
    image: redis:7-alpine
    container_name: redis-dev
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5
```

Add five new service blocks in its place:

```yaml
  redis-primary:
    image: redis:7-alpine
    container_name: redis-primary
    ports:
      - "6379:6379"
    command: >
      redis-server
      --save 900 1
      --save 300 10
      --maxmemory 200mb
      --maxmemory-policy allkeys-lru
    volumes:
      - redis-primary-data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5

  redis-replica:
    image: redis:7-alpine
    container_name: redis-replica
    command: >
      redis-server
      --replicaof redis-primary 6379
      --maxmemory 200mb
      --maxmemory-policy allkeys-lru
    depends_on:
      redis-primary:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5

  redis-sentinel-1:
    image: redis:7-alpine
    container_name: redis-sentinel-1
    volumes:
      - ./docker/redis/sentinel.conf:/etc/redis/sentinel-template.conf:ro
      - redis-sentinel-1-data:/data
    command: >
      sh -c "cp /etc/redis/sentinel-template.conf /data/sentinel.conf && redis-sentinel /data/sentinel.conf"
    depends_on:
      redis-primary:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "redis-cli", "-p", "26379", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5

  redis-sentinel-2:
    image: redis:7-alpine
    container_name: redis-sentinel-2
    volumes:
      - ./docker/redis/sentinel.conf:/etc/redis/sentinel-template.conf:ro
      - redis-sentinel-2-data:/data
    command: >
      sh -c "cp /etc/redis/sentinel-template.conf /data/sentinel.conf && redis-sentinel /data/sentinel.conf"
    depends_on:
      redis-primary:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "redis-cli", "-p", "26379", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5

  redis-sentinel-3:
    image: redis:7-alpine
    container_name: redis-sentinel-3
    volumes:
      - ./docker/redis/sentinel.conf:/etc/redis/sentinel-template.conf:ro
      - redis-sentinel-3-data:/data
    command: >
      sh -c "cp /etc/redis/sentinel-template.conf /data/sentinel.conf && redis-sentinel /data/sentinel.conf"
    depends_on:
      redis-primary:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "redis-cli", "-p", "26379", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5
```

Add a `volumes:` top-level block (if not already present, or append to existing):

```yaml
volumes:
  redis-primary-data:
  redis-sentinel-1-data:
  redis-sentinel-2-data:
  redis-sentinel-3-data:
```

Any service in the compose file that previously referenced `REDIS_HOST: redis-dev` should be updated to use Sentinel:

```yaml
environment:
  REDIS_MODE: sentinel
  REDIS_SENTINEL_MASTER: mymaster
  REDIS_SENTINEL_NODES: "redis-sentinel-1:26379,redis-sentinel-2:26379,redis-sentinel-3:26379"
```

- [ ] **Step 7.3: Validate Docker Compose config**

```bash
docker compose -f docker-compose.streaming.yml config 2>&1 | grep "name:" | head -20
```

Expected: all five redis service names appear (`redis-primary`, `redis-replica`, `redis-sentinel-1/2/3`).

- [ ] **Step 7.4: Smoke test the local cluster**

```bash
docker compose -f docker-compose.streaming.yml up redis-primary redis-replica redis-sentinel-1 redis-sentinel-2 redis-sentinel-3 -d
sleep 10
docker exec redis-sentinel-1 redis-cli -p 26379 sentinel masters
```

Expected: output shows `mymaster` with status `ok` and `flags` not containing `s_down` or `o_down`.

- [ ] **Step 7.5: Tear down**

```bash
docker compose -f docker-compose.streaming.yml down -v
```

- [ ] **Step 7.6: Commit**

```bash
git add docker/redis/sentinel.conf docker-compose.streaming.yml
git commit -m "feat: replace single-node Redis with local Sentinel cluster in Docker Compose"
```

---

## Task 8: EKS overlay — ElastiCache patch

**Files:**
- Create: `k8s/eks/redis-elasticache-patch.yaml`
- Modify: `k8s/eks/kustomization.yaml`

- [ ] **Step 8.1: Create ElastiCache ConfigMap patch**

Create `k8s/eks/redis-elasticache-patch.yaml`:

```yaml
# ConfigMap patch: switch prod Redis to ElastiCache (standalone, DNS-based failover).
#
# Before deploying, set REDIS_HOST to your ElastiCache primary endpoint.
# Required AWS-side configuration:
#   - Engine mode: Redis (not Cluster Mode)
#   - Multi-AZ: enabled
#   - Automatic failover: enabled
#   - Snapshot retention window: >= 1 day
#   - Preferred snapshot window: set to an off-peak hour (e.g. 03:00-04:00 UTC)
#
# ElastiCache primary endpoint flips DNS within ~30s on failover.
# JedisPool will throw JedisConnectionException during the failover window;
# callers already handle this as a transient error.
apiVersion: v1
kind: ConfigMap
metadata:
  name: recsys-config
  namespace: recsys
data:
  REDIS_MODE: "standalone"
  REDIS_HOST: "<elasticache-primary-endpoint>.cache.amazonaws.com"
  REDIS_PORT: "6379"
  REDIS_SENTINEL_MASTER: ""
  REDIS_SENTINEL_NODES: ""
```

- [ ] **Step 8.2: Update `k8s/eks/kustomization.yaml`**

Add the new patch and scale the base Sentinel resources to 0 (ElastiCache replaces them):

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - ../base
  - gateway-irsa.yaml
  - patches/irsa-model-serving.yaml

images:
  - name: recsys-backend-service
    newName: 123456789012.dkr.ecr.us-east-1.amazonaws.com/recsys-backend-service
    newTag: latest

replicas:
  - name: redis-primary
    count: 0
  - name: redis-replica
    count: 0
  - name: redis-sentinel
    count: 0

patches:
  - path: cloud-map-service-patch.yaml
  - path: configmap-patch.yaml
  - path: image-pull-policy-patch.yaml
  - path: redis-elasticache-patch.yaml
```

- [ ] **Step 8.3: Validate the EKS kustomize output**

```bash
kubectl kustomize k8s/eks 2>&1 | grep -A2 "name: redis"
```

Expected: `redis-primary`, `redis-replica`, `redis-sentinel` StatefulSets/Deployment appear with `replicas: 0`; the ConfigMap shows `REDIS_MODE: standalone`.

- [ ] **Step 8.4: Commit**

```bash
git add k8s/eks/redis-elasticache-patch.yaml k8s/eks/kustomization.yaml
git commit -m "feat: add ElastiCache patch for EKS overlay; scale base Sentinel resources to 0 in prod"
```

---

## Task 9: Full regression and final verification

- [ ] **Step 9.1: Run the full test suite**

```bash
mvn test 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`, 0 failures.

- [ ] **Step 9.2: Build the full artifact**

```bash
mvn package -DskipTests 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`

- [ ] **Step 9.3: Validate both Kustomize overlays**

```bash
kubectl kustomize k8s/base 2>&1 | grep "kind:\|name:" | grep -v "^--"
kubectl kustomize k8s/eks  2>&1 | grep "REDIS_MODE" -A1
```

Base expected: StatefulSets + Deployment for redis-primary/replica/sentinel present.
EKS expected: `REDIS_MODE: standalone` in ConfigMap output.

- [ ] **Step 9.4: Final commit if any fixes were needed**

```bash
git status
# If clean, nothing to do. Otherwise:
git add -p
git commit -m "fix: address compilation issues found during full regression"
```
