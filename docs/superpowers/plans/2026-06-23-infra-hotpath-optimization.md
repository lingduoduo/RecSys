# Hot-path Infrastructure Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove read-path lock contention and per-request allocation on the recommendation hot path, and make embedding/LLM caches observable — with no functional behavior change.

**Architecture:** Replace `LocalEmbeddingCache`'s `synchronized(LinkedHashMap)` backing map with a Caffeine cache (lock-free reads, `recordStats`); convert `CandidateGenerator.byEmbedding` from a Stream to a pre-sized loop; add hit/miss/eviction counters to `LlmResponseCache`. Each cache exposes a `stats()` accessor (no Micrometer binding — the offline serving server has no registry).

**Tech Stack:** Java 17, Maven, Caffeine 3.1.8 (new), JUnit 5 + AssertJ + JUnit Jupiter assertions.

## Global Constraints

- No functional behavior change to public APIs, HTTP contracts, wire formats, or Redis keys.
- `mvn clean test` must be green at the end of every task (the repo's Surefire config requires `-Xshare:off`, already set).
- New dependency limited to `com.github.ben-manes.caffeine:caffeine:3.1.8`.
- Follow existing code style: one top-level class per file; package-private test-only accessors stay package-private.
- All three tasks land on branch `optimize/infra-hotpath-cache` (already created; the design spec is already committed there).

---

### Task 1: `LocalEmbeddingCache` → Caffeine backing map + `stats()`

**Files:**
- Modify: `pom.xml` (add Caffeine dependency)
- Modify: `src/main/java/com/recsys/infrastructure/cache/LocalEmbeddingCache.java`
- Test: `src/test/java/com/recsys/infrastructure/cache/LocalEmbeddingCacheTest.java` (rewrite one test, add one test)

**Interfaces:**
- Consumes: nothing new.
- Produces: `public com.github.benmanes.caffeine.cache.stats.CacheStats stats()` on `LocalEmbeddingCache`. All existing public methods keep identical signatures and behavior (`getEmbedding`, `getEmbeddings`, `setEmbedding`, `setEmbeddings`, `scanIds`, `warmUp`, `preload`, `cacheSize`, `maxEntries`).

- [ ] **Step 1: Add the Caffeine dependency to `pom.xml`**

Find the `<dependencies>` block and add (next to other third-party deps):

```xml
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
    <version>3.1.8</version>
</dependency>
```

- [ ] **Step 2: Verify the dependency resolves**

Run: `mvn -q dependency:resolve | grep -i caffeine || mvn -q package -DskipTests`
Expected: build succeeds; Caffeine jar is on the classpath.

- [ ] **Step 3: Write the failing `stats()` test**

Add to `LocalEmbeddingCacheTest.java` (uses the existing `TrackingStore` fake and `import com.github.benmanes.caffeine.cache.stats.CacheStats;` — add that import at the top):

```java
    @Test
    void stats_recordsHitsAndMisses() {
        backing.put(1, new float[]{1f, 0f});

        cache.getEmbedding(1); // miss -> loads from backing, populates cache
        cache.getEmbedding(1); // hit  -> served from heap

        CacheStats stats = cache.stats();
        assertThat(stats.hitCount()).isEqualTo(1);
        assertThat(stats.missCount()).isEqualTo(1);
    }
```

- [ ] **Step 4: Run it to confirm it fails to compile**

Run: `mvn -q test-compile`
Expected: FAIL — `cannot find symbol: method stats()` in `LocalEmbeddingCache`.

- [ ] **Step 5: Rewrite `LocalEmbeddingCache` to use Caffeine**

Replace the imports block (lines 10-17) — drop `Collections.synchronizedMap`/`LinkedHashMap` usage, keep the rest, add Caffeine imports:

```java
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
```

Change the field declaration (was `private final Map<Integer, float[]> cache;`):

```java
    private final Cache<Integer, float[]> cache;
```

Replace the cache initialization inside the 3-arg constructor (the `Collections.synchronizedMap(new LinkedHashMap<>...)` block) with:

```java
        this.cache = Caffeine.newBuilder()
                .maximumSize(this.maxEntries)
                .recordStats()
                .executor(Runnable::run) // synchronous maintenance -> deterministic size/stats
                .build();
```

In `getEmbedding(int id)`, replace the `synchronized (cache) { cached = cache.get(id); }` block with:

```java
        float[] cached = cache.getIfPresent(id);
        if (cached != null) return cached;
```

In `getEmbeddings(Collection<Integer> ids)`, replace the `synchronized (cache) { ... }` population block (the loop building `result`/`misses`) with:

```java
        Map<Integer, float[]> present = cache.getAllPresent(ids);
        Map<Integer, float[]> result = new HashMap<>(present);
        Set<Integer> misses = new LinkedHashSet<>();
        for (int id : ids) {
            if (!present.containsKey(id)) misses.add(id);
        }
```

(Delete the old `Map<Integer, float[]> result = new HashMap<>(ids.size() * 2);` and `Set<Integer> misses = new LinkedHashSet<>();` declarations above the removed sync block — they are now created here.)

In `cacheSize()`:

```java
    public int cacheSize() {
        return (int) cache.estimatedSize();
    }
```

In `loadMissingEmbedding(int id)`, replace the `synchronized (cache) { float[] cached = cache.get(id); if (cached != null) return cached; }` block with:

```java
        float[] cached = cache.getIfPresent(id);
        if (cached != null) return cached;
```

In `putAll(Map<Integer, float[]> embeddings)`, remove the `synchronized (cache)` wrapper (keep the `forEach` body):

```java
    private void putAll(Map<Integer, float[]> embeddings) {
        if (embeddings == null || embeddings.isEmpty()) return;
        embeddings.forEach((id, vec) -> {
            if (id != null && vec != null) {
                cache.put(id, vec);
                bloom.add(id);
                nullSentinels.remove(id);
            }
        });
    }
```

In `put(Integer id, float[] vector)`, remove the `synchronized (cache)` wrapper:

```java
    private void put(Integer id, float[] vector) {
        if (id == null || vector == null) return;
        cache.put(id, vector);
    }
```

Add the `stats()` accessor (next to `maxEntries()`):

```java
    public CacheStats stats() {
        return cache.stats();
    }
```

- [ ] **Step 6: Rewrite the capacity-eviction test for Caffeine's policy**

In `LocalEmbeddingCacheTest.java`, replace the body of `cache_evictsOldEntriesWhenCapacityIsReached` (it currently asserts entry `1` specifically is evicted — Caffeine's W-TinyLFU is frequency-based, so assert the cap is enforced and exactly one entry was evicted, not which one):

```java
    @Test
    void cache_evictsOldEntriesWhenCapacityIsReached() {
        LocalEmbeddingCache tinyCache = new LocalEmbeddingCache(backing, 2);
        backing.put(1, new float[]{1f});
        backing.put(2, new float[]{2f});
        backing.put(3, new float[]{3f});

        tinyCache.getEmbedding(1);
        tinyCache.getEmbedding(2);
        tinyCache.getEmbedding(3);

        assertThat(tinyCache.cacheSize()).isEqualTo(2); // capacity enforced

        int getsBefore = backing.getCount;
        tinyCache.getEmbedding(1);
        tinyCache.getEmbedding(2);
        tinyCache.getEmbedding(3);
        // Exactly one of the three was evicted, so re-reading all three triggers exactly one backing fetch.
        assertThat(backing.getCount).isEqualTo(getsBefore + 1);
    }
```

- [ ] **Step 7: Run the cache test class**

Run: `mvn -q test -Dtest=LocalEmbeddingCacheTest`
Expected: PASS (all tests, including the new `stats_recordsHitsAndMisses` and the rewritten eviction test).

- [ ] **Step 8: Commit**

```bash
git add pom.xml src/main/java/com/recsys/infrastructure/cache/LocalEmbeddingCache.java src/test/java/com/recsys/infrastructure/cache/LocalEmbeddingCacheTest.java
git commit -m "perf: back LocalEmbeddingCache with Caffeine (lock-free reads + stats)"
```

---

### Task 2: `CandidateGenerator.byEmbedding` — imperative recall loop

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/vectordb/CandidateGenerator.java:101-104`

**Interfaces:**
- Consumes: nothing new.
- Produces: no signature change; `byEmbedding(int userId, int k)` returns the same `List<Movie>` (same order, nulls filtered, unmodifiable).

This is a behavior-identical refactor. The existing serving integration tests (`RecSysServerIntegrationTest`, `RecSysV2RecommendIntegrationTest`) exercise `byEmbedding` end-to-end and are the regression guard — no new unit test is added because the output contract is unchanged and already covered.

- [ ] **Step 1: Confirm green baseline for the covering tests**

Run: `mvn -q test -Dtest='RecSysServerIntegrationTest,RecSysV2RecommendIntegrationTest,CandidateGeneratorDimensionTest'`
Expected: PASS.

- [ ] **Step 2: Replace the Stream with a pre-sized loop**

In `byEmbedding` (lines 101-104), replace:

```java
        return embeddingIndex.search(userVec, k, watched).stream()
                .map(s -> dataManager.getMovieById(s.id()))
                .filter(m -> m != null)
                .collect(Collectors.toUnmodifiableList());
```

with:

```java
        List<SearchResult> hits = embeddingIndex.search(userVec, k, watched);
        List<Movie> out = new ArrayList<>(hits.size());
        for (SearchResult s : hits) {
            Movie m = dataManager.getMovieById(s.id());
            if (m != null) out.add(m);
        }
        return java.util.Collections.unmodifiableList(out);
```

Update imports at the top of the file: add `import java.util.ArrayList;`. Remove `import java.util.stream.Collectors;` **only if** `Collectors` is no longer referenced anywhere else in the file (grep first — see Step 3). `SearchResult` is already in the same package (`com.recsys.infrastructure.vectordb`), so no import is needed.

- [ ] **Step 3: Verify `Collectors` import is still needed or removed cleanly**

Run: `grep -n "Collectors" src/main/java/com/recsys/infrastructure/vectordb/CandidateGenerator.java`
Expected: no remaining references → the `import java.util.stream.Collectors;` line should be deleted. If any reference remains, keep the import.

- [ ] **Step 4: Re-run the covering tests**

Run: `mvn -q test -Dtest='RecSysServerIntegrationTest,RecSysV2RecommendIntegrationTest,CandidateGeneratorDimensionTest'`
Expected: PASS (identical behavior).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/vectordb/CandidateGenerator.java
git commit -m "perf: de-stream CandidateGenerator.byEmbedding recall path"
```

---

### Task 3: `LlmResponseCache` — hit/miss/eviction counters + `stats()`

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/cache/LlmResponseCache.java`
- Test: `src/test/java/com/recsys/infrastructure/cache/LlmResponseCacheTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: nested record `public record Stats(long hits, long misses, long evictions)` and accessor `public Stats stats()` on `LlmResponseCache`. Counting rules: a fresh hit increments `hits`; a not-found OR TTL-expired lookup increments `misses`; a capacity eviction (`removeEldestEntry` returning true) increments `evictions`. No change to caching behavior.

- [ ] **Step 1: Write the failing stats test**

Add to `LlmResponseCacheTest.java`:

```java
    @Test
    void statsCountHitsMissesAndEvictions() {
        LlmResponseCache cache = new LlmResponseCache(1, 60_000L); // capacity 1 -> forces eviction
        cache.put(BODY_A, 200, HEADERS, RESPONSE);
        cache.put(BODY_B, 200, HEADERS, RESPONSE); // evicts the eldest (capacity 1)

        assertNotNull(cache.get(BODY_B)); // hit
        assertNull(cache.get(BODY_A));    // miss (evicted)

        LlmResponseCache.Stats stats = cache.stats();
        assertEquals(1, stats.hits());
        assertEquals(1, stats.misses());
        assertEquals(1, stats.evictions());
    }
```

(Add `import static org.junit.jupiter.api.Assertions.assertEquals;` if not already present — it is, per the existing imports.)

- [ ] **Step 2: Run it to confirm it fails to compile**

Run: `mvn -q test-compile`
Expected: FAIL — `cannot find symbol: class Stats` / `method stats()`.

- [ ] **Step 3: Add counters and `stats()` to `LlmResponseCache`**

Add the import:

```java
import java.util.concurrent.atomic.AtomicLong;
```

Add the nested record (next to the existing `Entry` record):

```java
    public record Stats(long hits, long misses, long evictions) {}
```

Add counter fields (next to `cache`/`ttlMs`/`enabled`):

```java
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();
```

In the enabled-branch of the constructor, increment `evictions` inside `removeEldestEntry` when it evicts:

```java
            this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                    boolean evict = size() > cap;
                    if (evict) evictions.incrementAndGet();
                    return evict;
                }
            });
```

Rewrite `get` to count hits/misses (expired counts as a miss):

```java
    public Entry get(byte[] requestBody) {
        if (!enabled) return null;
        String key = hash(requestBody);
        Entry entry = cache.get(key);
        if (entry == null) {
            misses.incrementAndGet();
            return null;
        }
        if (System.currentTimeMillis() - entry.insertedAtMs() > ttlMs) {
            cache.remove(key);
            misses.incrementAndGet();
            return null;
        }
        hits.incrementAndGet();
        return entry;
    }
```

Add the accessor (next to `size()`):

```java
    public Stats stats() {
        return new Stats(hits.get(), misses.get(), evictions.get());
    }
```

- [ ] **Step 4: Run the cache test class**

Run: `mvn -q test -Dtest=LlmResponseCacheTest`
Expected: PASS (all existing tests plus `statsCountHitsMissesAndEvictions`).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/cache/LlmResponseCache.java src/test/java/com/recsys/infrastructure/cache/LlmResponseCacheTest.java
git commit -m "feat: add hit/miss/eviction stats to LlmResponseCache"
```

---

### Task 4: Full-suite verification

**Files:** none (verification only).

- [ ] **Step 1: Run the complete test suite**

Run: `mvn clean test`
Expected: `BUILD SUCCESS`, 0 failures/errors (baseline was 756 tests; this adds 2 new tests → expect ~758, all passing).

- [ ] **Step 2: If green, the branch is ready for the combined PR (spec + plan + code).** No commit needed here.

---

## Self-Review

**Spec coverage:**
- Spec change #1 (LocalEmbeddingCache Caffeine + no read contention) → Task 1. ✓
- Spec change #2 (CandidateGenerator imperative loop) → Task 2. ✓
- Spec change #3 metrics: LocalEmbeddingCache stats → Task 1; LlmResponseCache stats → Task 3. ✓
- Spec "Micrometer binding optional/when-available" → intentionally omitted (RecSysServer has no registry); `stats()` accessor is the metric surface. Documented in plan header. ✓
- Spec required test rewrite (eviction test) → Task 1, Step 6. ✓
- Out-of-scope items (cosine norms, Redis pipelining, MySQL pool, micro-opts) → not present in any task. ✓

**Placeholder scan:** No TBD/TODO; every code step shows complete code and exact commands. ✓

**Type consistency:** `stats()` returns `com.github.benmanes.caffeine.cache.stats.CacheStats` for `LocalEmbeddingCache` (Task 1) and the nested `LlmResponseCache.Stats` record for `LlmResponseCache` (Task 3) — two distinct, intentionally different types (one is Caffeine-native, one hand-rolled). `SearchResult`, `Movie` are existing types used with their real package locations. ✓
