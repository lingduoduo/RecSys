# Online Serving User-Embedding Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the per-request uncached `GET u2vEmb:<userId>` on `OnlinePredictionServer` (port 7010) by wrapping the user-embedding store in `LogicalExpiryEmbeddingCache` (30s soft TTL), and add a short null-sentinel negative cache so brand-new users don't re-hit Redis every request.

**Architecture:** `LogicalExpiryEmbeddingCache` already provides soft-TTL serve-stale + single background refresh + batched MGET, with no Bloom guard — correct for 7010's continuously-Flink-written `u2vEmb:*` keyspace. This plan adds a null-sentinel to that class, then wires it into `OnlinePredictionServer` between the `RedisEmbeddingStore` and `CandidateGenerator`.

**Tech Stack:** Java 17, Maven, JUnit 5, AssertJ, Jedis, Armeria. Reuses the existing `LogicalExpiryEmbeddingCache` and its `SingleFlight`/executor design.

## Global Constraints

- Java 17, Maven. `mvn test -Dtest=<Class>` runs a single test class (Surefire sets `-Xshare:off`).
- Do NOT change the `EmbeddingStore` interface, `CandidateGenerator`, the pipeline, or any HTTP/API contract.
- `LogicalExpiryEmbeddingCache`'s existing public constructor `(EmbeddingStore, long softTtlSeconds)` and existing package-private `(EmbeddingStore, long softTtlMs, Executor)` constructor MUST remain (existing tests depend on the 3-arg one). The class stays `final`.
- Null-sentinel default TTL: `DEFAULT_NULL_SENTINEL_TTL_MS = 30_000L`. Sentinels are recorded ONLY on a confirmed `null` from a successful backing call — NEVER on a thrown exception.
- 7010 soft TTL default: `30` seconds, env-overridable via `ONLINE_USER_EMB_SOFT_TTL_SECONDS` (use the existing `readIntEnv` helper in `OnlinePredictionServer`).
- No `preload`/`warmUp` on 7010 (the class has none; cache fills on demand).
- Out of scope: item embeddings on 7010, RecSysServer, removing `MultiLevelEmbeddingCache`, `LocalEmbeddingCache` contention.

---

### Task 1: Add null-sentinel negative cache to `LogicalExpiryEmbeddingCache`

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/cache/LogicalExpiryEmbeddingCache.java`
- Test: `src/test/java/com/recsys/infrastructure/cache/LogicalExpiryEmbeddingCacheTest.java` (extend)

**Interfaces:**
- Consumes: existing `EmbeddingStore` backing store; existing `SingleFlight`, `Executor` refresh design.
- Produces:
  - `public static final long DEFAULT_NULL_SENTINEL_TTL_MS = 30_000L;`
  - new package-private constructor `LogicalExpiryEmbeddingCache(EmbeddingStore backingStore, long softTtlMs, long nullSentinelTtlMs, Executor refreshExecutor)`
  - test accessor `boolean hasNullSentinel(int id)`
  - behavior: absent IDs cached negatively for `nullSentinelTtlMs`; sentinel skipped on a hit; cleared on write; recorded only on confirmed `null`; a background refresh resolving `null` removes the stale entry and records a sentinel.

- [ ] **Step 1: Write the failing tests**

Append these methods to `src/test/java/com/recsys/infrastructure/cache/LogicalExpiryEmbeddingCacheTest.java` (inside the class, after the existing tests). They reuse the existing `TrackingStore` stub and `SYNC_EXECUTOR`:

```java
    @Test
    void absentId_isNegativeCached_andNotRefetchedWithinSentinelTtl() {
        var backing = new TrackingStore(); // id 7 absent
        var cache = new LogicalExpiryEmbeddingCache(backing, 60_000L, 60_000L, SYNC_EXECUTOR);

        assertThat(cache.getEmbedding(7)).isNull();
        assertThat(backing.getCount).isEqualTo(1);
        assertThat(cache.hasNullSentinel(7)).isTrue();

        assertThat(cache.getEmbedding(7)).isNull(); // served from sentinel
        assertThat(backing.getCount).isEqualTo(1);  // no second backing call
    }

    @Test
    void sentinelExpiry_reQueriesBackingStore() throws Exception {
        var backing = new TrackingStore(); // id 7 absent initially
        // 1ms sentinel TTL so it expires almost instantly.
        var cache = new LogicalExpiryEmbeddingCache(backing, 60_000L, 1L, SYNC_EXECUTOR);

        assertThat(cache.getEmbedding(7)).isNull();
        assertThat(backing.getCount).isEqualTo(1);

        Thread.sleep(5); // sentinel expires
        backing.put(7, new float[]{7f});
        assertThat(cache.getEmbedding(7)).containsExactly(7f); // re-queried after expiry
        assertThat(backing.getCount).isEqualTo(2);
    }

    @Test
    void setEmbedding_clearsNullSentinel() {
        var backing = new TrackingStore();
        var cache = new LogicalExpiryEmbeddingCache(backing, 60_000L, 60_000L, SYNC_EXECUTOR);

        assertThat(cache.getEmbedding(7)).isNull(); // sentinel recorded
        assertThat(cache.hasNullSentinel(7)).isTrue();

        cache.setEmbedding(7, new float[]{1f, 2f}, 300L);

        assertThat(cache.hasNullSentinel(7)).isFalse();
        assertThat(cache.getEmbedding(7)).containsExactly(1f, 2f);
    }

    @Test
    void backingException_doesNotRecordSentinel() {
        var backing = new ThrowingThenValueStore(new float[]{4f});
        var cache = new LogicalExpiryEmbeddingCache(backing, 60_000L, 60_000L, SYNC_EXECUTOR);

        // First call: backing throws -> propagates, NO sentinel recorded.
        try {
            cache.getEmbedding(7);
            org.junit.jupiter.api.Assertions.fail("expected exception");
        } catch (RuntimeException expected) {
            // expected
        }
        assertThat(cache.hasNullSentinel(7)).isFalse();

        // Second call: backing now returns a value (would be null if a sentinel had been set).
        assertThat(cache.getEmbedding(7)).containsExactly(4f);
    }

    @Test
    void getEmbeddings_recordsSentinelForBatchAbsentIds() {
        var backing = new TrackingStore();
        backing.put(1, new float[]{1f});
        var cache = new LogicalExpiryEmbeddingCache(backing, 60_000L, 60_000L, SYNC_EXECUTOR);

        Map<Integer, float[]> result = cache.getEmbeddings(List.of(1, 2));
        assertThat(result).containsKey(1).doesNotContainKey(2);
        assertThat(cache.hasNullSentinel(2)).isTrue();

        // Single-key read for the absent id is now served from the sentinel.
        int before = backing.getCount;
        assertThat(cache.getEmbedding(2)).isNull();
        assertThat(backing.getCount).isEqualTo(before);
    }

    @Test
    void backgroundRefreshResolvingNull_removesEntryAndRecordsSentinel() throws Exception {
        var backing = new TrackingStore();
        backing.put(1, new float[]{1f});
        // 1ms soft TTL, 60s sentinel, inline executor so refresh runs synchronously.
        var cache = new LogicalExpiryEmbeddingCache(backing, 1L, 60_000L, SYNC_EXECUTOR);

        assertThat(cache.getEmbedding(1)).containsExactly(1f); // cold miss populates
        Thread.sleep(5); // soft expiry passes
        backing.data.remove(1); // key vanished in backing (e.g. Redis TTL elapsed)

        // Past soft expiry: returns stale value once, schedules refresh (runs inline) which finds null.
        float[] stale = cache.getEmbedding(1);
        assertThat(stale).containsExactly(1f);

        // Entry removed, sentinel recorded -> subsequent read returns null without re-hitting backing.
        assertThat(cache.hasNullSentinel(1)).isTrue();
        int before = backing.getCount;
        assertThat(cache.getEmbedding(1)).isNull();
        assertThat(backing.getCount).isEqualTo(before);
    }

    // Backing stub that throws on the first getEmbedding, then returns a fixed value.
    private static final class ThrowingThenValueStore implements EmbeddingStore {
        private final float[] value;
        private boolean thrown = false;
        ThrowingThenValueStore(float[] value) { this.value = value; }
        @Override public float[] getEmbedding(int id) {
            if (!thrown) { thrown = true; throw new RuntimeException("redis down"); }
            return value;
        }
        @Override public Map<Integer, float[]> getEmbeddings(Collection<Integer> ids) { return Map.of(); }
        @Override public void setEmbedding(int id, float[] v, long ttl) {}
        @Override public void setEmbeddings(Map<Integer, float[]> v, long ttl) {}
        @Override public Set<Integer> scanIds(int maxKeys) { return Set.of(); }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn test -Dtest=LogicalExpiryEmbeddingCacheTest`
Expected: COMPILATION FAILURE — the 4-arg constructor and `hasNullSentinel` do not exist yet.

- [ ] **Step 3: Modify `LogicalExpiryEmbeddingCache`**

Make these edits to `src/main/java/com/recsys/infrastructure/cache/LogicalExpiryEmbeddingCache.java`:

(a) Add the constant and fields (after the `log` field / near the other fields):

```java
    public static final long DEFAULT_NULL_SENTINEL_TTL_MS = 30_000L;
```

Add two instance fields next to `softTtlMs`:

```java
    private final long nullSentinelTtlMs;
    // Negative cache: absent ID → sentinel expiry timestamp (ms). Skips the backing store
    // for recently-confirmed-absent IDs so brand-new users do not re-hit Redis each request.
    private final ConcurrentHashMap<Integer, Long> nullSentinels = new ConcurrentHashMap<>();
```

(b) Replace the two existing constructors with these three (keep the public 2-arg and package-private 3-arg signatures intact; add the 4-arg):

```java
    public LogicalExpiryEmbeddingCache(EmbeddingStore backingStore, long softTtlSeconds) {
        this(backingStore, softTtlSeconds * 1_000L, DEFAULT_NULL_SENTINEL_TTL_MS, ForkJoinPool.commonPool());
    }

    // softTtlMs is in milliseconds — allows sub-second TTLs for testing.
    LogicalExpiryEmbeddingCache(EmbeddingStore backingStore, long softTtlMs, Executor refreshExecutor) {
        this(backingStore, softTtlMs, DEFAULT_NULL_SENTINEL_TTL_MS, refreshExecutor);
    }

    LogicalExpiryEmbeddingCache(EmbeddingStore backingStore, long softTtlMs,
                                long nullSentinelTtlMs, Executor refreshExecutor) {
        this.backingStore = backingStore;
        this.softTtlMs = Math.max(1L, softTtlMs);
        this.nullSentinelTtlMs = Math.max(1L, nullSentinelTtlMs);
        this.refreshExecutor = refreshExecutor;
    }
```

(c) Replace `getEmbedding(int id)` with the sentinel-aware version:

```java
    @Override
    public float[] getEmbedding(int id) {
        long now = System.currentTimeMillis();
        LogicalEntry entry = cache.get(id);

        if (entry != null) {
            if (entry.softExpiresAtMs() <= now) {
                // Past soft expiry: return stale value and schedule one background refresh.
                scheduleRefresh(id);
            }
            return entry.value();
        }

        // No positive entry — check the negative cache before hitting the backing store.
        Long sentinelExpiry = nullSentinels.get(id);
        if (sentinelExpiry != null && sentinelExpiry > now) {
            return null;
        }

        // Cold miss: synchronous fetch, deduped across concurrent callers.
        return coldMissSingleFlight.execute(id, () -> loadColdMiss(id));
    }
```

(d) Replace `loadColdMiss(int id)` to record a sentinel on confirmed null:

```java
    private float[] loadColdMiss(int id) {
        LogicalEntry cached = cache.get(id);
        if (cached != null) return cached.value();

        float[] value = backingStore.getEmbedding(id); // throws → propagates, no sentinel recorded
        if (value != null) {
            cache.put(id, new LogicalEntry(value, System.currentTimeMillis() + softTtlMs));
        } else {
            nullSentinels.put(id, System.currentTimeMillis() + nullSentinelTtlMs);
        }
        return value;
    }
```

(e) In `getEmbeddings(Collection<Integer> ids)`, skip sentinel'd IDs and record sentinels for batch-absent IDs. Replace the per-id loop and the cold-miss block with:

```java
        for (int id : ids) {
            LogicalEntry entry = cache.get(id);
            if (entry != null) {
                if (entry.softExpiresAtMs() <= now) scheduleRefresh(id);
                result.put(id, entry.value());
            } else {
                Long sentinelExpiry = nullSentinels.get(id);
                if (sentinelExpiry != null && sentinelExpiry > now) continue; // known absent
                coldMisses.add(id);
            }
        }

        // Batch-resolve all cold misses in a single backing-store call (one Redis MGET instead of N
        // individual round-trips). Concurrent single-key getEmbedding() calls are still deduped via
        // coldMissSingleFlight; concurrent batch callers may race on writes, but those are idempotent.
        if (!coldMisses.isEmpty()) {
            Map<Integer, float[]> batchResult = backingStore.getEmbeddings(coldMisses);
            long writeNow = System.currentTimeMillis();
            long softExpiry = writeNow + softTtlMs;
            long sentinelExpiry = writeNow + nullSentinelTtlMs;
            for (int id : coldMisses) {
                float[] value = batchResult.get(id);
                if (value != null) {
                    cache.put(id, new LogicalEntry(value, softExpiry));
                    result.put(id, value);
                } else {
                    nullSentinels.put(id, sentinelExpiry);
                }
            }
        }
```

(f) In `setEmbedding` and `setEmbeddings`, clear the sentinel for written IDs:

```java
    @Override
    public void setEmbedding(int id, float[] vector, long ttlSeconds) {
        backingStore.setEmbedding(id, vector, ttlSeconds);
        nullSentinels.remove(id);
        cache.put(id, new LogicalEntry(vector, System.currentTimeMillis() + softTtlMs));
    }

    @Override
    public void setEmbeddings(Map<Integer, float[]> vectors, long ttlSeconds) {
        backingStore.setEmbeddings(vectors, ttlSeconds);
        long softExpiry = System.currentTimeMillis() + softTtlMs;
        vectors.forEach((id, vec) -> {
            nullSentinels.remove(id);
            cache.put(id, new LogicalEntry(vec, softExpiry));
        });
    }
```

(g) In `scheduleRefresh(int id)`, when the refresh resolves `null`, remove the stale entry and record a sentinel:

```java
    private void scheduleRefresh(int id) {
        // putIfAbsent is the singleflight guard: only one refresh task per ID.
        if (refreshing.putIfAbsent(id, Boolean.TRUE) != null) return;
        refreshExecutor.execute(() -> {
            try {
                float[] fresh = backingStore.getEmbedding(id);
                if (fresh != null) {
                    cache.put(id, new LogicalEntry(fresh, System.currentTimeMillis() + softTtlMs));
                } else {
                    // Key vanished in the backing store: stop serving the stale vector.
                    cache.remove(id);
                    nullSentinels.put(id, System.currentTimeMillis() + nullSentinelTtlMs);
                }
            } catch (Exception e) {
                log.warn("Background refresh failed for embedding {}: {}", id, e.toString());
            } finally {
                refreshing.remove(id);
            }
        });
    }
```

(h) Add the test accessor next to the existing `cacheSize()`/`isRefreshing(int)` accessors:

```java
    boolean hasNullSentinel(int id) {
        Long expiry = nullSentinels.get(id);
        return expiry != null && expiry > System.currentTimeMillis();
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn test -Dtest=LogicalExpiryEmbeddingCacheTest`
Expected: PASS — the 6 new tests plus all 8 existing tests green (14 total), output pristine.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/cache/LogicalExpiryEmbeddingCache.java \
        src/test/java/com/recsys/infrastructure/cache/LogicalExpiryEmbeddingCacheTest.java
git commit -m "feat: add null-sentinel negative cache to LogicalExpiryEmbeddingCache

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Wire `LogicalExpiryEmbeddingCache` into `OnlinePredictionServer`

**Files:**
- Modify: `src/main/java/com/recsys/online/serving/OnlinePredictionServer.java:50-51`

**Interfaces:**
- Consumes: `LogicalExpiryEmbeddingCache(EmbeddingStore, long softTtlSeconds)` (public constructor); existing `readIntEnv(String, int)` helper in this class; `CandidateGenerator(DataManager, EmbeddingStore)`.
- Produces: 7010's `CandidateGenerator` now reads user embeddings through the cache; new env var `ONLINE_USER_EMB_SOFT_TTL_SECONDS` (default 30).

- [ ] **Step 1: Add the wiring**

In `src/main/java/com/recsys/online/serving/OnlinePredictionServer.java`, add the import near the other `com.recsys.infrastructure` imports:

```java
import com.recsys.infrastructure.cache.LogicalExpiryEmbeddingCache;
import com.recsys.infrastructure.vectordb.EmbeddingStore;
```

Then replace lines 50-51:

```java
            RedisEmbeddingStore userEmbeddingStore = new RedisEmbeddingStore(jedisPool, "u2vEmb");
            CandidateGenerator candidateGenerator = new CandidateGenerator(dataManager, userEmbeddingStore);
```

with:

```java
            RedisEmbeddingStore userEmbeddingStore = new RedisEmbeddingStore(jedisPool, "u2vEmb");
            // u2vEmb is continuously rewritten by Flink: use a soft-TTL cache (serve-stale +
            // background refresh, no Bloom guard) so new users are still found and updates land
            // within ~one soft TTL. Default 30s, overridable for tuning.
            int userEmbSoftTtlSeconds = readIntEnv("ONLINE_USER_EMB_SOFT_TTL_SECONDS", 30);
            EmbeddingStore userEmbCache =
                    new LogicalExpiryEmbeddingCache(userEmbeddingStore, userEmbSoftTtlSeconds);
            CandidateGenerator candidateGenerator = new CandidateGenerator(dataManager, userEmbCache);
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS (no compile errors; the cache implements `EmbeddingStore`, which `CandidateGenerator` accepts).

- [ ] **Step 3: Run the online-serving regression tests**

Run: `mvn test -Dtest=OnlinePredictionServerIntegrationTest,OnlinePredictionRegressionTest,OnlineRecommendationServiceTest,OnlineRecommendationEngineTest`
Expected: PASS — recommendation behavior is unchanged for warm users; the wrap only adds caching.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/recsys/online/serving/OnlinePredictionServer.java
git commit -m "perf: cache 7010 user embeddings via LogicalExpiryEmbeddingCache (30s soft TTL)

Wraps the u2vEmb RedisEmbeddingStore so per-request GETs collapse to <=1 per
soft-TTL window; serve-stale + background refresh keep Flink updates fresh
within ~30s. Soft TTL overridable via ONLINE_USER_EMB_SOFT_TTL_SECONDS.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Full-suite + load-test regression guard

**Files:** none (verification only).

- [ ] **Step 1: Full build + test suite**

Run: `mvn test`
Expected: BUILD SUCCESS, all tests green (≥ 715 + 6 new from Task 1).

- [ ] **Step 2: Online prediction load test (opt-in)**

Run: `mvn test -DexcludedGroups="" -Dgroups=load -Dtest=OnlinePredictionLoadTest`
Expected: PASS — throughput unchanged or improved; user-embedding Redis GETs no longer scale 1:1 with request count.

- [ ] **Step 3: Manual smoke check (optional, requires local Redis + OnlinePredictionServer on 7010)**

```bash
redis-cli set u2vEmb:1 "0.1 0.2 0.3"
for i in $(seq 1 50); do curl -s "http://localhost:7010/online/recommendation?userId=1" >/dev/null; done
redis-cli --stat   # ~1 GET u2vEmb:1 per 30s window, not 1 per request

for i in $(seq 1 50); do curl -s "http://localhost:7010/online/recommendation?userId=999999" >/dev/null; done
# MONITOR shows ~1 GET u2vEmb:999999 per 30s (null-sentinel), not 50
```

- [ ] **Step 4: No commit** (verification only).

---

## Self-Review

**Spec coverage:**
- Spec §3.1 (OnlinePredictionServer wiring, env-configurable soft TTL, no preload/warmUp) → Task 2.
- Spec §3.2 (null-sentinel: skip on hit, record on confirmed null, clear on write, batch path, background-refresh-null removes entry + sentinel, no sentinel on exception) → Task 1 steps (c)-(h) with matching tests.
- Spec §4 data flow / §5 error handling → Task 1 tests (`backingException_doesNotRecordSentinel`, `backgroundRefreshResolvingNull_...`) + Task 2 regression.
- Spec §6 testing strategy → Task 1 unit tests, Task 2 regression, Task 3 full suite + load + smoke.
- Spec §7 out-of-scope items → no tasks created (correct).

**Placeholder scan:** none — every step has full, runnable code and exact commands.

**Type consistency:**
- `DEFAULT_NULL_SENTINEL_TTL_MS` (Task 1) referenced only within Task 1.
- 4-arg constructor `(EmbeddingStore, long softTtlMs, long nullSentinelTtlMs, Executor)` defined in Task 1 step (b), used by Task 1 tests in step 1 — signatures match (millis + millis).
- Public `LogicalExpiryEmbeddingCache(EmbeddingStore, long softTtlSeconds)` used in Task 2 — unchanged from current source.
- `hasNullSentinel(int)`, `readIntEnv(String,int)`, `EmbeddingStore`, `CandidateGenerator(DataManager, EmbeddingStore)` all match existing/defined signatures.
- Note: Task 1 tests pass `60_000L` as `softTtlMs` to the 4-arg constructor (milliseconds), distinct from the public 2-arg constructor's `softTtlSeconds` — intentional and consistent with the existing test file's use of the package-private millis constructor.
