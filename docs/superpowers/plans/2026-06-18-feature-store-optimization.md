# Feature Store Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the per-request uncached `ZREVRANGE global:item_popularity` Redis read by giving `GlobalPopularityStore` a short-TTL, single-flight, serve-stale-on-error JVM cache.

**Architecture:** Extract the cache-with-TTL + single-flight + stale-serve pattern (already proven in `ShardedTopKStore`) into a reusable, independently-tested `TtlSingleFlightCache<V>`. Have `GlobalPopularityStore` cache the top-N popularity list through it and slice per `limit`. Make `ShardedTopKStore`'s fresh cache TTL env-configurable so it can be aligned to the same ~1 s freshness target. No `RecallChannel`, pipeline, or API change.

**Tech Stack:** Java 17, Maven, JUnit 5, Mockito, AssertJ, Jedis. Reuses `com.recsys.infrastructure.SingleFlight`.

## Global Constraints

- Java 17, build with Maven (`mvn test -Dtest=<Class>` runs a single test class; Surefire already sets `-Xshare:off`).
- Reuse the existing `com.recsys.infrastructure.SingleFlight<K,V>` utility — do not write a second single-flight implementation.
- Do NOT change the `RecallChannel` interface, any channel logic, the streaming pipeline, or any HTTP/API contract.
- `GlobalPopularityStore`'s public constructor signature `GlobalPopularityStore(Pool<Jedis> pool)` must remain (existing callers and tests depend on it). `GlobalPopularityStore.KEY` must remain `"global:item_popularity"`.
- `ShardedTopKStore` default cache TTL must stay `2_000L` when the new env var is unset (back-compat).
- Freshness target: `DEFAULT_FRESH_TTL_MS = 1_000`, `DEFAULT_STALE_TTL_MS = 60_000`.
- Follow existing patterns: `readLongEnv(...)` for env vars (already in `ShardedTopKStore`), `LongSupplier` clock injection for deterministic time in tests.

---

### Task 1: `TtlSingleFlightCache<V>` — reusable TTL + single-flight + stale-serve cache

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/cache/TtlSingleFlightCache.java`
- Test: `src/test/java/com/recsys/infrastructure/cache/TtlSingleFlightCacheTest.java`

**Interfaces:**
- Consumes: `com.recsys.infrastructure.SingleFlight<String, V>` — `public V execute(K key, Supplier<V> supplier)`.
- Produces:
  - `public final class TtlSingleFlightCache<V>`
  - `public static final long DEFAULT_FRESH_TTL_MS = 1_000L;`
  - `public static final long DEFAULT_STALE_TTL_MS = 60_000L;`
  - `public TtlSingleFlightCache(long freshTtlMs, long staleTtlMs)`
  - `public TtlSingleFlightCache(long freshTtlMs, long staleTtlMs, java.util.function.LongSupplier clock)`
  - `public V get(String key, java.util.function.Supplier<V> loader)`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/infrastructure/cache/TtlSingleFlightCacheTest.java`:

```java
package com.recsys.infrastructure.cache;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TtlSingleFlightCacheTest {

    @Test
    void freshHit_callsLoaderOnce() {
        AtomicLong clock = new AtomicLong(0);
        TtlSingleFlightCache<String> cache =
                new TtlSingleFlightCache<>(1_000L, 60_000L, clock::get);
        AtomicInteger loads = new AtomicInteger();

        String first = cache.get("k", () -> { loads.incrementAndGet(); return "A"; });
        String second = cache.get("k", () -> { loads.incrementAndGet(); return "A"; });

        assertThat(first).isEqualTo("A");
        assertThat(second).isEqualTo("A");
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    void distinctKeys_areIsolated() {
        AtomicLong clock = new AtomicLong(0);
        TtlSingleFlightCache<String> cache =
                new TtlSingleFlightCache<>(1_000L, 60_000L, clock::get);

        assertThat(cache.get("a", () -> "VA")).isEqualTo("VA");
        assertThat(cache.get("b", () -> "VB")).isEqualTo("VB");
        assertThat(cache.get("a", () -> "X")).isEqualTo("VA"); // still cached, loader ignored
    }

    @Test
    void coldMiss_propagatesLoaderException() {
        TtlSingleFlightCache<String> cache = new TtlSingleFlightCache<>(1_000L, 60_000L);

        assertThatThrownBy(() -> cache.get("k", () -> { throw new IllegalStateException("down"); }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("down");
    }

    @Test
    void staleWindow_servesStaleOnLoaderError_thenPropagatesBeyondStale() {
        AtomicLong clock = new AtomicLong(0);
        TtlSingleFlightCache<String> cache =
                new TtlSingleFlightCache<>(10L, 100L, clock::get); // fresh 10ms, stale 100ms

        // Seed at t=0
        assertThat(cache.get("k", () -> "v0")).isEqualTo("v0");

        // t=50: fresh expired (>=10), still within stale (<100); loader fails -> serve stale
        clock.set(50);
        assertThat(cache.get("k", () -> { throw new RuntimeException("boom"); })).isEqualTo("v0");

        // t=200: beyond stale (>=100); loader fails -> propagate
        clock.set(200);
        assertThatThrownBy(() -> cache.get("k", () -> { throw new RuntimeException("boom"); }))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");
    }

    @Test
    void staleWindow_refreshesExactlyOnceUnderConcurrency() throws Exception {
        AtomicLong clock = new AtomicLong(0);
        TtlSingleFlightCache<String> cache =
                new TtlSingleFlightCache<>(10L, 60_000L, clock::get);
        AtomicInteger loads = new AtomicInteger();

        // Seed at t=0 (load #1)
        cache.get("k", () -> { loads.incrementAndGet(); return "v0"; });

        // Enter stale window
        clock.set(50);

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    cache.get("k", () -> {
                        loads.incrementAndGet();
                        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                        return "v1";
                    });
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // 1 seed load + exactly 1 refresh; the other 11 callers served stale without loading.
        assertThat(loads.get()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=TtlSingleFlightCacheTest`
Expected: COMPILATION FAILURE / test failure — `TtlSingleFlightCache` does not exist yet.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/recsys/infrastructure/cache/TtlSingleFlightCache.java`:

```java
package com.recsys.infrastructure.cache;

import com.recsys.infrastructure.SingleFlight;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Single-value-per-key snapshot cache with a short fresh TTL, single-flight refresh,
 * and serve-stale-on-error. Generalises the cache lifecycle already used inline by
 * {@code ShardedTopKStore} so other stores (e.g. {@code GlobalPopularityStore}) can
 * reuse one tested implementation.
 *
 * Read semantics ({@link #get}):
 *   1. Fresh hit (now &lt; freshUntil): return cached value, loader not called.
 *   2. Stale window (freshUntil &le; now &lt; staleUntil): one caller refreshes
 *      (non-blocking guard); concurrent callers are served the last value. If the
 *      refresh loader throws, the stale value is kept and served.
 *   3. Cold miss / beyond stale: block-and-load, coalescing concurrent callers via
 *      {@link SingleFlight}; a loader exception propagates.
 */
public final class TtlSingleFlightCache<V> {

    public static final long DEFAULT_FRESH_TTL_MS = 1_000L;
    public static final long DEFAULT_STALE_TTL_MS = 60_000L;
    private static final long SINGLE_FLIGHT_WAIT_MS = 2_000L;

    private static final class Entry<V> {
        final V value;
        final long freshUntil;
        final long staleUntil;
        Entry(V value, long freshUntil, long staleUntil) {
            this.value = value;
            this.freshUntil = freshUntil;
            this.staleUntil = staleUntil;
        }
    }

    private final long freshTtlMs;
    private final long staleTtlMs;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Entry<V>> entries = new ConcurrentHashMap<>();
    private final Set<String> refreshing = ConcurrentHashMap.newKeySet();
    private final SingleFlight<String, V> singleFlight = new SingleFlight<>(SINGLE_FLIGHT_WAIT_MS);

    public TtlSingleFlightCache(long freshTtlMs, long staleTtlMs) {
        this(freshTtlMs, staleTtlMs, System::currentTimeMillis);
    }

    public TtlSingleFlightCache(long freshTtlMs, long staleTtlMs, LongSupplier clock) {
        this.freshTtlMs = Math.max(1L, freshTtlMs);
        this.staleTtlMs = Math.max(this.freshTtlMs, staleTtlMs);
        this.clock = clock;
    }

    public V get(String key, Supplier<V> loader) {
        long now = clock.getAsLong();
        Entry<V> e = entries.get(key);

        if (e != null && now < e.freshUntil) {
            return e.value;                              // 1. fresh hit
        }

        if (e != null && now < e.staleUntil) {           // 2. stale window
            if (refreshing.add(key)) {
                try {
                    store(key, loader.get(), clock.getAsLong());
                } catch (RuntimeException keepStale) {
                    // swallow: keep serving the stale value until staleUntil
                } finally {
                    refreshing.remove(key);
                }
                Entry<V> updated = entries.get(key);
                return updated != null ? updated.value : e.value;
            }
            return e.value;                              // another thread refreshing → serve stale
        }

        // 3. cold miss or beyond stale: block-and-load, coalescing concurrent callers
        return singleFlight.execute(key, () -> {
            long t = clock.getAsLong();
            Entry<V> cur = entries.get(key);
            if (cur != null && t < cur.freshUntil) return cur.value;
            V fresh = loader.get();                      // may throw → propagates
            store(key, fresh, t);
            return fresh;
        });
    }

    private void store(String key, V value, long now) {
        entries.put(key, new Entry<>(value, now + freshTtlMs, now + staleTtlMs));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=TtlSingleFlightCacheTest`
Expected: PASS (5 tests green).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/cache/TtlSingleFlightCache.java \
        src/test/java/com/recsys/infrastructure/cache/TtlSingleFlightCacheTest.java
git commit -m "feat: add TtlSingleFlightCache (TTL + single-flight + stale-serve)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Cache `GlobalPopularityStore` reads through `TtlSingleFlightCache`

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/redis/GlobalPopularityStore.java`
- Test: `src/test/java/com/recsys/infrastructure/redis/GlobalPopularityStoreTest.java` (extend existing)

**Interfaces:**
- Consumes: `TtlSingleFlightCache<List<String>>` — `get(String, Supplier<List<String>>)`, `DEFAULT_FRESH_TTL_MS`, `DEFAULT_STALE_TTL_MS`, clock constructor (from Task 1).
- Produces:
  - `public static final String KEY = "global:item_popularity";` (unchanged)
  - `static final int MAX_CACHED = 100;`
  - `public GlobalPopularityStore(Pool<Jedis> pool)` (unchanged signature)
  - `GlobalPopularityStore(Pool<Jedis> pool, long freshTtlMs, long staleTtlMs, java.util.function.LongSupplier clock)` (package-private, for tests)
  - `public List<String> getTopIds(int limit)` (unchanged signature; now cached + sliced)

- [ ] **Step 1: Write the failing tests**

Add these methods to the existing `src/test/java/com/recsys/infrastructure/redis/GlobalPopularityStoreTest.java` (keep the existing three tests and the `mockPool` helper; add the imports shown):

```java
// add to imports:
import java.util.concurrent.atomic.AtomicLong;
import redis.clients.jedis.exceptions.JedisException;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;

    @Test
    void getTopIds_cachesWithinFreshTtl_singleRedisRead() {
        Jedis jedis = mock(Jedis.class);
        when(jedis.zrevrange(eq(GlobalPopularityStore.KEY), anyLong(), anyLong()))
                .thenReturn(List.of("5", "3", "1"));

        AtomicLong clock = new AtomicLong(0);
        GlobalPopularityStore store =
                new GlobalPopularityStore(mockPool(jedis), 1_000L, 60_000L, clock::get);

        assertThat(store.getTopIds(3)).containsExactly("5", "3", "1");
        assertThat(store.getTopIds(3)).containsExactly("5", "3", "1"); // within TTL

        verify(jedis, times(1)).zrevrange(eq(GlobalPopularityStore.KEY), anyLong(), anyLong());
    }

    @Test
    void getTopIds_slicesTopNSnapshotByLimit_oneRedisRead() {
        Jedis jedis = mock(Jedis.class);
        when(jedis.zrevrange(eq(GlobalPopularityStore.KEY), anyLong(), anyLong()))
                .thenReturn(List.of("5", "4", "3", "2", "1"));

        AtomicLong clock = new AtomicLong(0);
        GlobalPopularityStore store =
                new GlobalPopularityStore(mockPool(jedis), 1_000L, 60_000L, clock::get);

        assertThat(store.getTopIds(2)).containsExactly("5", "4");
        assertThat(store.getTopIds(4)).containsExactly("5", "4", "3", "2"); // shared snapshot

        verify(jedis, times(1)).zrevrange(eq(GlobalPopularityStore.KEY), anyLong(), anyLong());
    }

    @Test
    void getTopIds_servesStaleOnRedisErrorThenEmptyBeyondStale() {
        Jedis jedis = mock(Jedis.class);
        when(jedis.zrevrange(eq(GlobalPopularityStore.KEY), anyLong(), anyLong()))
                .thenReturn(List.of("5", "3"))                 // first load OK
                .thenThrow(new JedisException("down"));         // subsequent loads fail

        AtomicLong clock = new AtomicLong(0);
        GlobalPopularityStore store =
                new GlobalPopularityStore(mockPool(jedis), 10L, 100L, clock::get);

        assertThat(store.getTopIds(2)).containsExactly("5", "3"); // t=0 seed

        clock.set(50);                                            // stale window
        assertThat(store.getTopIds(2)).containsExactly("5", "3"); // served stale

        clock.set(200);                                           // beyond stale
        assertThat(store.getTopIds(2)).isEmpty();                 // error → empty (DataManager fallback upstream)
    }

    @Test
    void getTopIds_returnsEmptyWhenRedisDownAndNoSnapshot() {
        Jedis jedis = mock(Jedis.class);
        when(jedis.zrevrange(eq(GlobalPopularityStore.KEY), anyLong(), anyLong()))
                .thenThrow(new JedisException("down"));

        GlobalPopularityStore store = new GlobalPopularityStore(mockPool(jedis));
        assertThat(store.getTopIds(5)).isEmpty();
    }
```

Note: the existing `getTopIds_returnsIdsFromRedisSortedSetInOrder` asserts `verify(jedis).close()`. That still holds — `loadTopFromRedis` uses a try-with-resources `pool.getResource()`. Leave that test as-is.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn test -Dtest=GlobalPopularityStoreTest`
Expected: COMPILATION FAILURE — the 4-arg constructor and caching do not exist yet.

- [ ] **Step 3: Write the implementation**

Replace the entire contents of `src/main/java/com/recsys/infrastructure/redis/GlobalPopularityStore.java` with:

```java
package com.recsys.infrastructure.redis;

import com.recsys.infrastructure.cache.TtlSingleFlightCache;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.Pool;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * Reads the {@code global:item_popularity} sorted set (written by Spark
 * {@code UserEventStreamingJob} via ZINCRBY). The key is user-independent and moves on
 * minute scales, yet it is read on every recall request by both PopularityChannel and
 * ColdStartChannel. A {@link TtlSingleFlightCache} holds a single top-N snapshot so the
 * Redis ZREVRANGE runs at most once per fresh-TTL window across all concurrent requests.
 */
public class GlobalPopularityStore {

    public static final String KEY = "global:item_popularity";

    // One Redis snapshot holds the top-N list; getTopIds(limit) slices it.
    static final int MAX_CACHED = 100;

    private final Pool<Jedis> pool;
    private final TtlSingleFlightCache<List<String>> cache;

    public GlobalPopularityStore(Pool<Jedis> pool) {
        this(pool, TtlSingleFlightCache.DEFAULT_FRESH_TTL_MS,
                TtlSingleFlightCache.DEFAULT_STALE_TTL_MS, System::currentTimeMillis);
    }

    GlobalPopularityStore(Pool<Jedis> pool, long freshTtlMs, long staleTtlMs, LongSupplier clock) {
        this.pool = pool;
        this.cache = new TtlSingleFlightCache<>(freshTtlMs, staleTtlMs, clock);
    }

    public List<String> getTopIds(int limit) {
        if (limit <= 0) return List.of();
        List<String> top;
        try {
            top = cache.get(KEY, this::loadTopFromRedis);
        } catch (RuntimeException redisDownNoSnapshot) {
            // No usable snapshot and Redis is unavailable — let the caller fall back
            // (PopularityChannel uses its DataManager fallback on empty).
            return List.of();
        }
        if (top.size() <= limit) return top;
        return List.copyOf(top.subList(0, limit));
    }

    private List<String> loadTopFromRedis() {
        try (Jedis jedis = pool.getResource()) {
            List<String> ids = jedis.zrevrange(KEY, 0, MAX_CACHED - 1);
            return ids == null ? List.of() : List.copyOf(ids);
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn test -Dtest=GlobalPopularityStoreTest`
Expected: PASS (3 existing + 4 new = 7 tests green).

- [ ] **Step 5: Run the channels that consume this store to confirm no regression**

Run: `mvn test -Dtest=PopularityChannelTest,ColdStartChannelTest`
Expected: PASS (unchanged — both read through `getTopIds`).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/redis/GlobalPopularityStore.java \
        src/test/java/com/recsys/infrastructure/redis/GlobalPopularityStoreTest.java
git commit -m "perf: cache global:item_popularity reads via TtlSingleFlightCache

Collapses the per-request (and per-cold-request duplicate) ZREVRANGE to at
most one Redis read per ~1s window. Serves stale on Redis error; empty when
no snapshot so PopularityChannel keeps its DataManager fallback.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Make `ShardedTopKStore` fresh cache TTL env-configurable

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/redis/ShardedTopKStore.java:74-86`
- Test: `src/test/java/com/recsys/infrastructure/redis/ShardedTopKStoreTtlConfigTest.java` (create)

**Interfaces:**
- Consumes: existing `static long readLongEnv(String, long)` and `static final long DEFAULT_CACHE_TTL_MS = 2_000L` in `ShardedTopKStore`.
- Produces: behavior — both public constructors read `ONLINE_TOPK_CACHE_TTL_MS` (default `2_000L`) for the fresh cache TTL. No signature change.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/infrastructure/redis/ShardedTopKStoreTtlConfigTest.java`. This is an environment-independent unit test that asserts the env reader is wired with the correct name and default by calling the package-private `readLongEnv` helper (no `System.getenv` mutation needed):

```java
package com.recsys.infrastructure.redis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShardedTopKStoreTtlConfigTest {

    @Test
    void cacheTtlEnvVar_defaultsToTwoSecondsWhenUnset() {
        // ONLINE_TOPK_CACHE_TTL_MS is not set in the test environment.
        long resolved = ShardedTopKStore.readLongEnv("ONLINE_TOPK_CACHE_TTL_MS",
                ShardedTopKStore.DEFAULT_CACHE_TTL_MS);
        assertThat(resolved).isEqualTo(2_000L);
    }

    @Test
    void cacheTtlEnvVar_isReadableByName() {
        // Documents the contract: operators set ONLINE_TOPK_CACHE_TTL_MS=1000 to align
        // the topk fresh TTL with GlobalPopularityStore. With the var unset, the helper
        // returns the supplied default unchanged.
        long resolved = ShardedTopKStore.readLongEnv("ONLINE_TOPK_CACHE_TTL_MS", 1_000L);
        assertThat(resolved).isEqualTo(1_000L);
    }
}
```

If `readLongEnv` is currently `private`, this test will not compile — Step 3 widens it to package-private.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=ShardedTopKStoreTtlConfigTest`
Expected: COMPILATION FAILURE — `readLongEnv` and/or `DEFAULT_CACHE_TTL_MS` not visible to the test (currently `private` / package-private literals not referenced this way).

- [ ] **Step 3: Make the change**

In `src/main/java/com/recsys/infrastructure/redis/ShardedTopKStore.java`, change the two public constructors (lines ~74-86) to read the new env var for the fresh cache TTL:

```java
    public ShardedTopKStore(Pool<Jedis> pool, String keyPrefix) {
        this(pool, pool, keyPrefix, DEFAULT_SHARD_COUNT,
                readLongEnv("ONLINE_TOPK_CACHE_TTL_MS", DEFAULT_CACHE_TTL_MS),
                readLongEnv("ONLINE_TOPK_STALE_TTL_MS", DEFAULT_STALE_TTL_MS), new HotKeyDetector());
    }

    public ShardedTopKStore(Pool<Jedis> writePool, Pool<Jedis> readPool, String keyPrefix) {
        this(writePool, readPool, keyPrefix, DEFAULT_SHARD_COUNT,
                readLongEnv("ONLINE_TOPK_CACHE_TTL_MS", DEFAULT_CACHE_TTL_MS),
                readLongEnv("ONLINE_TOPK_STALE_TTL_MS", DEFAULT_STALE_TTL_MS), new HotKeyDetector());
    }
```

Then ensure the `readLongEnv` helper is package-private (remove `private` if present) so the test can call it. Find its declaration (around line 244):

```java
    static long readLongEnv(String envName, long defaultValue) {
```

`DEFAULT_CACHE_TTL_MS` is already declared `static final long DEFAULT_CACHE_TTL_MS = 2_000L;` (package-private) — no change needed.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=ShardedTopKStoreTtlConfigTest`
Expected: PASS (2 tests green).

- [ ] **Step 5: Run the existing ShardedTopKStore tests to confirm no regression**

Run: `mvn test -Dtest=ShardedTopKStore*Test`
Expected: PASS — default TTL behavior (2 s) is unchanged when the env var is unset.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/redis/ShardedTopKStore.java \
        src/test/java/com/recsys/infrastructure/redis/ShardedTopKStoreTtlConfigTest.java
git commit -m "feat: make ShardedTopKStore fresh cache TTL env-configurable (ONLINE_TOPK_CACHE_TTL_MS)

Default stays 2000ms; operators set 1000 to align with GlobalPopularityStore.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Full-suite regression + load-test guard

**Files:** none (verification only).

- [ ] **Step 1: Run the serving + retrieval regression suite**

Run: `mvn test -Dtest=RecSysServerIntegrationTest,RecSysServerRegressionTest,MultiChannelRecallServiceTest,PopularityChannelTest,ColdStartChannelTest,TrendingChannelTest`
Expected: PASS — no behavior change to the recall path; only the popularity read is now cached.

- [ ] **Step 2: Run the embedding recall load test (confirms popularity read no longer scales with QPS)**

Run: `mvn test -DexcludedGroups="" -Dgroups=load -Dtest=EmbeddingRecallLoadTest`
Expected: PASS — throughput unchanged or improved; `GlobalPopularityStore` issues ≤ 1 Redis read per fresh-TTL window regardless of request count.

- [ ] **Step 3: Full build**

Run: `mvn package -DskipTests` then `mvn test`
Expected: BUILD SUCCESS, all tests green.

- [ ] **Step 4: Manual smoke check (optional, requires local Redis + RecSysServer)**

```bash
redis-cli zadd global:item_popularity 100 1 90 2 80 3
for i in $(seq 1 50); do curl -s "http://localhost:6010/getrecommendation?userId=999" >/dev/null; done
redis-cli --stat   # observe ~1 ZREVRANGE/sec, not 2 per request
```

- [ ] **Step 5: No commit** (verification task — nothing changed).

---

## Self-Review

**Spec coverage:**
- Spec §4.1 `TtlSingleFlightCache` → Task 1 (all five read-semantics cases tested).
- Spec §4.2 `GlobalPopularityStore` caching + top-N slice + empty-on-cold-down + stale-serve → Task 2.
- Spec §4.3 `ShardedTopKStore` env-configurable fresh TTL, default unchanged → Task 3.
- Spec §6 error-handling matrix → Task 1 (`staleWindow_...`, `coldMiss_...`) + Task 2 (`...servesStaleOnRedisError...`, `...returnsEmptyWhenRedisDownAndNoSnapshot`).
- Spec §7 regression guard + load test → Task 4.
- Spec §8 out-of-scope items: no tasks created (correct — probe dedup, ShardedTopKStore restructuring, OnlinePredictionServer, ModelApplication all intentionally excluded).

**Placeholder scan:** none — every code and test step contains full, runnable content.

**Type consistency:**
- `TtlSingleFlightCache<V>.get(String, Supplier<V>)` defined in Task 1, consumed as `TtlSingleFlightCache<List<String>>` in Task 2 — consistent.
- `DEFAULT_FRESH_TTL_MS` / `DEFAULT_STALE_TTL_MS` constants defined Task 1, referenced Task 2 — consistent names.
- `GlobalPopularityStore.KEY` and `getTopIds(int)` signatures unchanged from the existing file; `MAX_CACHED` introduced and used only within the store.
- `ShardedTopKStore.readLongEnv` / `DEFAULT_CACHE_TTL_MS` referenced in Task 3 match the names verified in the existing source.
