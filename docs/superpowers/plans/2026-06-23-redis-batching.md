# Redis Round-Trip Batching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Cut Redis round-trips on the trending-seed path and bound the startup embedding scan, behavior-preserving.

**Architecture:** (A) pipeline `ShardedTopKStore.seedAllShards` so all shard ZADDs go in one round-trip; (C) add a time budget to `RedisEmbeddingStore.loadAll` so a slow Redis can't block startup. **Part B** (ShardedRecordStore sequence-gen via Lua) is **carved into its own follow-up PR** — it changes id-allocation atomicity and warrants isolated review.

**Tech Stack:** Java 17, Jedis (`Pipeline`), JUnit 5 + Mockito + AssertJ.

## Global Constraints
- No behavior change to data written/returned on the happy path.
- `mvn clean test` green at the end.
- Branch `optimize/redis-batching` (already created; spec already on branch).

---

### Task 1: Pipeline `ShardedTopKStore.seedAllShards`

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/redis/ShardedTopKStore.java` (seedAllShards ~190; add `import redis.clients.jedis.Pipeline;`)
- Test: `src/test/java/com/recsys/infrastructure/redis/ShardedTopKStoreTest.java`

**Interfaces:** `seedAllShards(String, Map<String,Double>)` signature unchanged.

- [ ] **Step 1: Update the seed test to expect a pipeline**

Add `import redis.clients.jedis.Pipeline;`. In `setUp()` add a default pipeline stub so all seed-calling tests work:
```java
        when(jedis.pipelined()).thenReturn(mock(Pipeline.class));
```
Replace `seedAllShards_writesToEveryShardKey` body:
```java
    @Test
    void seedAllShards_writesToEveryShardKey() {
        Pipeline pipe = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(pipe);
        ShardedTopKStore store = new ShardedTopKStore(pool, pool, "topk:", 3, 5_000L, new HotKeyDetector());
        Map<String, Double> scores = Map.of("movie:1", 10.0, "movie:2", 8.0);

        store.seedAllShards("last_hour", scores);

        verify(pipe).zadd("topk:last_hour:s0", scores);
        verify(pipe).zadd("topk:last_hour:s1", scores);
        verify(pipe).zadd("topk:last_hour:s2", scores);
        verify(pipe).zadd("topk:last_hour", scores);
        verify(pipe).sync();
    }
```

- [ ] **Step 2: Run it — expect failure (still calls jedis.zadd, not pipe)**

Run: `mvn -q test -Dtest=ShardedTopKStoreTest`
Expected: FAIL on `seedAllShards_writesToEveryShardKey` (pipe.zadd never invoked).

- [ ] **Step 3: Implement the pipeline**

Add `import redis.clients.jedis.Pipeline;` to `ShardedTopKStore.java`. Replace `seedAllShards`:
```java
    public void seedAllShards(String window, Map<String, Double> memberScores) {
        if (memberScores == null || memberScores.isEmpty()) return;
        try (Jedis jedis = writePool.getResource()) {
            Pipeline pipe = jedis.pipelined();
            for (int shard = 0; shard < shardCount; shard++) {
                pipe.zadd(shardKey(window, shard), memberScores);
            }
            pipe.zadd(legacyKey(window), memberScores);
            pipe.sync();
        } catch (Exception e) {
            log.warn("Failed to seed shards for window {}: {}", window, e.toString());
        }
        hotCache.remove(window); // invalidate so next read reflects new data
    }
```

- [ ] **Step 4: Run the test class**

Run: `mvn -q test -Dtest=ShardedTopKStoreTest`
Expected: PASS (all tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/redis/ShardedTopKStore.java src/test/java/com/recsys/infrastructure/redis/ShardedTopKStoreTest.java
git commit -m "perf: pipeline ShardedTopKStore.seedAllShards fan-out (N+1 round-trips -> 1)"
```

---

### Task 2: Time-budget `RedisEmbeddingStore.loadAll`

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/redis/RedisEmbeddingStore.java`
- Test: `src/test/java/com/recsys/infrastructure/redis/RedisEmbeddingStoreTest.java`

**Interfaces:** new package-private constructor `RedisEmbeddingStore(Pool<Jedis>, String, double jitterFraction, int mgetBatchSize, long loadAllTimeoutMs, java.util.function.LongSupplier clock)`. `loadAll()` signature unchanged; returns partial results if the budget is exceeded.

- [ ] **Step 1: Write the failing timeout test**

Add imports: `import redis.clients.jedis.params.ScanParams;`, `import redis.clients.jedis.resps.ScanResult;`, `import java.util.concurrent.atomic.AtomicLong;`, `import java.util.function.LongSupplier;`, `import static org.mockito.ArgumentMatchers.any;`, `import static org.mockito.ArgumentMatchers.anyString;`, `import static org.mockito.Mockito.atMost;`.
```java
    @Test
    @SuppressWarnings("unchecked")
    void loadAll_stopsWhenTimeBudgetExceeded() {
        AtomicLong now = new AtomicLong(0L);
        LongSupplier clock = () -> now.getAndAdd(1000L); // each read advances 1s
        RedisEmbeddingStore store =
                new RedisEmbeddingStore(pool, "emb", 0.0, 500, 500L, clock); // 500ms budget

        ScanResult<String> page = mock(ScanResult.class);
        when(page.getResult()).thenReturn(List.of("emb:1"));
        when(page.getCursor()).thenReturn("99"); // never "0" -> would loop forever without the budget
        when(jedis.scan(anyString(), any(ScanParams.class))).thenReturn(page);
        when(jedis.mget(any(String[].class))).thenReturn(List.of("1.0 0.0"));

        Map<Integer, float[]> result = store.loadAll();

        assertThat(result).containsKey(1);                       // partial result returned
        verify(jedis, atMost(2)).scan(anyString(), any(ScanParams.class)); // budget broke the loop
    }
```

- [ ] **Step 2: Run it — expect compile failure (no 6-arg ctor)**

Run: `mvn -q test-compile`
Expected: FAIL — constructor not found.

- [ ] **Step 3: Implement clock + budget**

In `RedisEmbeddingStore.java` add imports `import java.util.function.LongSupplier;`. Add fields:
```java
    private final long loadAllTimeoutMs;
    private final LongSupplier clock;
```
Change the existing 4-arg constructor to delegate (default budget from env, system clock):
```java
    RedisEmbeddingStore(Pool<Jedis> pool, String keyPrefix, double jitterFraction, int mgetBatchSize) {
        this(pool, keyPrefix, jitterFraction, mgetBatchSize,
                readLongEnv("REDIS_LOADALL_TIMEOUT_MS", 30_000L), System::currentTimeMillis);
    }

    RedisEmbeddingStore(Pool<Jedis> pool, String keyPrefix, double jitterFraction, int mgetBatchSize,
                        long loadAllTimeoutMs, LongSupplier clock) {
        this.pool = pool;
        this.keyPrefix = keyPrefix;
        this.jitterFraction = Math.max(0.0, Math.min(0.5, jitterFraction));
        this.mgetBatchSize = Math.max(1, mgetBatchSize);
        this.loadAllTimeoutMs = Math.max(0L, loadAllTimeoutMs);
        this.clock = clock;
    }
```
Add a `readLongEnv` helper if the class only has `readIntEnv` (mirror it for `long`):
```java
    private static long readLongEnv(String envName, long defaultValue) {
        String raw = System.getenv(envName);
        if (raw == null || raw.isBlank()) return defaultValue;
        try { return Long.parseLong(raw.trim()); } catch (NumberFormatException e) { return defaultValue; }
    }
```
In `loadAll()`, capture the deadline start and break when exceeded:
```java
    public Map<Integer, float[]> loadAll() {
        Map<Integer, float[]> result = new HashMap<>();
        ScanParams scanParams = new ScanParams().match(keyPrefix + ":*").count(500);
        long start = clock.getAsLong();

        try (Jedis jedis = pool.getResource()) {
            String cursor = "0";
            do {
                ScanResult<String> res = jedis.scan(cursor, scanParams);
                List<String> pageKeys = res.getResult();
                if (!pageKeys.isEmpty()) {
                    List<String> values = jedis.mget(pageKeys.toArray(new String[0]));
                    for (int i = 0; i < pageKeys.size(); i++) {
                        String val = values.get(i);
                        if (val == null || val.isBlank()) continue;
                        int sep = pageKeys.get(i).lastIndexOf(':');
                        if (sep < 0) continue;
                        try {
                            int id = Integer.parseInt(pageKeys.get(i).substring(sep + 1));
                            result.put(id, VectorMath.parseVector(val));
                        } catch (NumberFormatException ignore) {}
                    }
                }
                cursor = res.getCursor();
                if (loadAllTimeoutMs > 0L && clock.getAsLong() - start > loadAllTimeoutMs) {
                    log.warn("loadAll exceeded {}ms budget after {} entries; returning partial result",
                            loadAllTimeoutMs, result.size());
                    break;
                }
            } while (!"0".equals(cursor));
        }
        return result;
    }
```

- [ ] **Step 4: Run the test class**

Run: `mvn -q test -Dtest=RedisEmbeddingStoreTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/redis/RedisEmbeddingStore.java src/test/java/com/recsys/infrastructure/redis/RedisEmbeddingStoreTest.java
git commit -m "perf: add time budget to RedisEmbeddingStore.loadAll startup scan"
```

---

### Task 3: Full-suite verification

- [ ] **Step 1:** Run `mvn clean test` → `BUILD SUCCESS`, 0 failures.
- [ ] **Step 2:** If green, branch ready for PR.

## Self-Review
- Spec A (pipeline seed) → Task 1. ✓
- Spec C (loadAll budget) → Task 2. ✓
- Spec B (ShardedRecordStore seq-gen) → **deferred to its own PR** (atomicity-sensitive); noted in plan header + PR body. ✓
- No placeholders; all steps show complete code + commands. ✓
- Types: `Pipeline` (Task 1), `LongSupplier`/`ScanResult` (Task 2) consistent. ✓
