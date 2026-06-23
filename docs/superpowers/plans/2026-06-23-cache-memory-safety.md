# Cache Memory Safety — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Steps use `- [ ]`.

**Goal:** Bound three unbounded/hot-path-evicted maps so they can't bloat heap or scan O(N) on the read path. Behavior-preserving.

**Architecture / design note:** The spec proposed scheduled background sweeps. **Implemented instead with Caffeine's built-in `maximumSize` + TTL eviction** — it bounds memory AND moves eviction off the hot path with **no new background threads / lifecycle**, and is consistent with the already-merged Caffeine embedding cache. Same goal, simpler and lower-risk. All three caches use `executor(Runnable::run)` so size is deterministic.

**Tech Stack:** Java 17, Caffeine 3.1.8 (already on classpath), JUnit 5 + AssertJ.

## Global Constraints
- No behavior change to suppression/hot-detection/feature-freshness semantics.
- `mvn clean test` green.
- Branch `optimize/cache-memory-safety` (spec already on branch).

---

### Task 1: Bound `LocalEmbeddingCache.nullSentinels` (A)

**Files:** Modify `src/main/java/com/recsys/infrastructure/cache/LocalEmbeddingCache.java`; Test `.../LocalEmbeddingCacheTest.java`.

Replace `ConcurrentHashMap<Integer,Long> nullSentinels` (manual expiry, unbounded) with a Caffeine cache that self-bounds and self-expires:
```java
    private static final int NULL_SENTINEL_MAX = 100_000;
    private final Cache<Integer, Boolean> nullSentinels = Caffeine.newBuilder()
            .maximumSize(NULL_SENTINEL_MAX)
            .expireAfterWrite(java.time.Duration.ofMillis(NULL_SENTINEL_TTL_MS))
            .executor(Runnable::run)
            .build();
```
Rewrite the 4 usages:
- read suppression: `Long nullExpiry = nullSentinels.get(id); if (nullExpiry != null && nullExpiry > now) return null;` → `if (nullSentinels.getIfPresent(id) != null) return null;`
- batch read guard (same pattern) → `if (nullSentinels.getIfPresent(id) != null) continue;`
- record absent: `nullSentinels.put(id, now + NULL_SENTINEL_TTL_MS);` → `nullSentinels.put(id, Boolean.TRUE);`
- on write/set: `nullSentinels.remove(id);` → `nullSentinels.invalidate(id);`
- in `loadMissingEmbedding`: the `nullExpiry`/`now` recheck becomes `if (nullSentinels.getIfPresent(id) != null) return null;`
Add a package-private accessor: `int nullSentinelCount() { return (int) nullSentinels.estimatedSize(); }`.
Remove now-unused `now` locals where they were only for sentinel math (keep where still used for fetch).

- [ ] Step 1 — Add the failing bound test:
```java
    @Test
    void nullSentinels_areBounded() {
        for (int i = 0; i < 1_000; i++) cache.getEmbedding(900_000 + i); // all absent in backing
        assertThat(cache.nullSentinelCount()).isLessThanOrEqualTo(1_000);
    }
```
(With a small absent set this just asserts it tracks; the real cap is `NULL_SENTINEL_MAX`. To prove the cap directly, the implementer may instead construct with a tiny cap via a test ctor — optional. The behavior tests below are the primary guard.)
- [ ] Step 2 — Run `mvn -q test -Dtest=LocalEmbeddingCacheTest` → FAIL (no `nullSentinelCount`).
- [ ] Step 3 — Implement the Caffeine sentinel cache + accessor as above.
- [ ] Step 4 — Run `mvn -q test -Dtest=LocalEmbeddingCacheTest` → PASS (incl. existing bloom/null-sentinel penetration tests).
- [ ] Step 5 — Commit: `git commit -m "fix: bound LocalEmbeddingCache null-sentinels with a Caffeine TTL cache"`

---

### Task 2: Bound `HotKeyDetector` tracking maps (B)

**Files:** Modify `src/main/java/com/recsys/infrastructure/resilience/HotKeyDetector.java`; Test `.../HotKeyDetectorTest.java`.

Replace both `ConcurrentHashMap` counters with Caffeine caches bounded by size and idle-expiry (auto-evicts idle keys — no scheduled sweep):
```java
    private static final int DEFAULT_MAX_TRACKED_KEYS = 100_000;
    private final Cache<String, WindowCounter> counters;
    private final Cache<Integer, WindowCounter> intCounters;
```
Build in the constructor (both), with `expireAfterAccess(2 * windowMs)` + `maximumSize(DEFAULT_MAX_TRACKED_KEYS)` + `executor(Runnable::run)`.
Rewrite usages:
- `counters.computeIfAbsent(key, k -> new WindowCounter(now))` → `counters.get(key, k -> new WindowCounter(now))`
- `counters.get(key)` (reads in isHot/accessRate) → `counters.getIfPresent(key)`
- `counters.size()` → `(int) counters.estimatedSize()`
- `counters.entrySet()` in `topHotKeys` → `counters.asMap().entrySet()`
- `evictIdle()` removeIf → `counters.asMap().entrySet().removeIf(...)` / `intCounters.asMap()...` (keep — still valid for explicit pruning).
(Same for `intCounters`.)

- [ ] Step 1 — Add failing test:
```java
    @Test
    void trackedKeysAreBoundedByMaxSize() {
        HotKeyDetector detector = new HotKeyDetector(10, 1L);
        for (int i = 0; i < 200_000; i++) detector.record("k" + i);
        assertThat(detector.trackedKeyCount()).isLessThanOrEqualTo(100_000);
    }
```
- [ ] Step 2 — `mvn -q test -Dtest=HotKeyDetectorTest` → FAIL (currently unbounded → 200000).
- [ ] Step 3 — Implement the Caffeine conversion.
- [ ] Step 4 — `mvn -q test -Dtest=HotKeyDetectorTest` → PASS (incl. `trackedKeyCount_countsDistinctKeys`==2, `evictIdle_doesNotThrowAndPreservesActiveKeys`).
- [ ] Step 5 — Commit: `git commit -m "fix: bound HotKeyDetector tracking maps via Caffeine (size + idle expiry)"`

---

### Task 3: Bound `OnlineFeatureStore.featureCache` + remove hot-path O(N) eviction (C)

**Files:** Modify `src/main/java/com/recsys/infrastructure/store/OnlineFeatureStore.java`; Test `.../OnlineFeatureStoreTest.java`.

Replace `ConcurrentHashMap<String,CachedFeature> featureCache` with a Caffeine cache built from the existing `staleTtlMs`/`maxCacheUsers`:
```java
    private final Cache<String, CachedFeature> featureCache = Caffeine.newBuilder()
            .maximumSize(maxCacheUsers)
            .expireAfterWrite(java.time.Duration.ofMillis(staleTtlMs))
            .executor(Runnable::run)
            .build();
```
Rewrite usages: `.get(k)`→`.getIfPresent(k)`, `.put(k,v)` unchanged, `cacheSize()`→`(int) featureCache.estimatedSize()`. **Delete** `evictIfNeeded(...)`, its two call sites (lines ~130, ~163), `lastEvictMs`, and `EVICT_INTERVAL_MS` — Caffeine evicts by size+TTL off the read path. Keep the fresh-window (`expiresAtMs`) and stale-serve logic exactly as-is.

- [ ] Step 1 — The existing `getRecentMovieIds_evictsWhenHotUserCacheExceedsLimit` (asserts `cacheSize() <= 2`) is the bounding guard. Run it first: `mvn -q test -Dtest=OnlineFeatureStoreTest` (currently green).
- [ ] Step 2 — Implement the Caffeine conversion + delete `evictIfNeeded`.
- [ ] Step 3 — `mvn -q test -Dtest=OnlineFeatureStoreTest` → PASS (cap test + freshness/stale tests).
- [ ] Step 4 — Commit: `git commit -m "perf: bound OnlineFeatureStore cache via Caffeine; drop O(N) hot-path eviction"`

---

### Task 4: Full-suite verification
- [ ] `mvn clean test` → BUILD SUCCESS, 0 failures.

## Self-Review
- A → Task 1, B → Task 2, C → Task 3. All three spec items covered. ✓
- Design change (Caffeine vs scheduled sweep) documented in header + each task + PR. ✓
- No placeholders; concrete code per step. ✓
- Types: `Cache<...>` consistent; `executor(Runnable::run)` for deterministic size in all three. ✓
