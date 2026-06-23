# Spec: Hot-path Infrastructure Optimization (Caffeine cache + recall loop + cache metrics)

## Objective

Remove the highest-confidence performance and observability gaps on the recommendation
**read hot path**, with **zero functional behavior change** (one test-assertion nuance, see Risks).
Derived from a four-part read-only audit of `com.recsys.infrastructure`.

Three changes:
1. **Eliminate read contention** in `LocalEmbeddingCache` (every embedding lookup currently
   serializes behind a single `synchronized` block).
2. **De-stream** the `CandidateGenerator` recall path (per-request stream/lambda allocation).
3. **Add cache metrics** so the gains are measurable and cache sizing is tunable
   (`LocalEmbeddingCache` hit/miss/eviction; `LlmResponseCache` hit/miss/eviction for LLM cost visibility).

### Who benefits
Operators (latency/throughput under load; measurable cache effectiveness and LLM cost) and
maintainers of the serving + cache infrastructure.

### Success looks like
- `LocalEmbeddingCache` reads are lock-free (no `synchronized(cache)` on the read path).
- `CandidateGenerator.recall` builds its result without a `Stream`.
- `LocalEmbeddingCache` and `LlmResponseCache` expose hit/miss/eviction stats; `LocalEmbeddingCache`
  optionally binds to Micrometer when a registry is available.
- `mvn clean test` is green (with the one eviction-test rewrite described below).

## Tech Stack

- Java 17, Maven. Jetty (offline serving), Spring Boot (model serving), Micrometer (`micrometer-core` already present).
- **New dependency:** `com.github.ben-manes.caffeine:caffeine` (3.1.x, Java 17 compatible).
  `CaffeineCacheMetrics` ships in the already-present `micrometer-core`.

## Commands

```bash
mvn package -DskipTests
mvn test -Dtest='LocalEmbeddingCacheTest,MultiLevelEmbeddingCacheTest,LlmResponseCacheTest,CandidateGeneratorDimensionTest'
mvn clean test
```

## Scope

### 1. `LocalEmbeddingCache` → Caffeine storage
File: `src/main/java/com/recsys/infrastructure/cache/LocalEmbeddingCache.java`

Current: `cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true){ removeEldestEntry... })`.
Because the map is **access-order** (`accessOrder=true`), every `get` mutates the linked list, so all
reads must hold `synchronized(cache)` — serializing the entire read path (worst on the batch
`getEmbeddings` loop, which holds the lock across all ids).

Replace the backing map with a Caffeine cache:

```java
private final com.github.benmanes.caffeine.cache.Cache<Integer, float[]> cache =
    Caffeine.newBuilder()
        .maximumSize(maxEntries)
        .recordStats()
        .executor(Runnable::run)   // synchronous maintenance -> deterministic size/stats for tests
        .build();
```

Call-site swaps (mechanical, no logic change):
- `synchronized(cache){ cache.get(id) }` → `cache.getIfPresent(id)`
- batch read loop → `cache.getAllPresent(ids)` then compute the miss set from the returned keys
- `put(id, vec)` / `putAll(map)` → `cache.put(...)` / `cache.putAll(...)`
- `cacheSize()` → `cache.estimatedSize()`
- delete the `removeEldestEntry` override (Caffeine enforces `maximumSize`)

**Untouched:** `EmbeddingStore` interface, `BloomFilterGuard`, `nullSentinels`, `SingleFlight`,
warmUp/preload/setEmbedding semantics. Only the in-heap map implementation changes.

Metrics:
- `public com.github.benmanes.caffeine.cache.stats.CacheStats stats()` accessor (hit/miss/eviction counts + rates).
- `public void bindMetrics(MeterRegistry registry, String name)` → `CaffeineCacheMetrics.monitor(registry, cache, name)`.
  Called from `RecSysServer` for the item + user caches when a registry is available; a no-registry
  service simply keeps Caffeine's internal stats queryable via `stats()`.

### 2. `CandidateGenerator` recall loop
File: `src/main/java/com/recsys/infrastructure/vectordb/CandidateGenerator.java` (~line 101)

Replace:
```java
return embeddingIndex.search(userVec, k, watched).stream()
        .map(s -> dataManager.getMovieById(s.id()))
        .filter(m -> m != null)
        .collect(Collectors.toUnmodifiableList());
```
with a pre-sized imperative loop:
```java
List<SearchResult> hits = embeddingIndex.search(userVec, k, watched);
List<Movie> out = new ArrayList<>(hits.size());
for (SearchResult s : hits) {
    Movie m = dataManager.getMovieById(s.id());
    if (m != null) out.add(m);
}
return Collections.unmodifiableList(out);
```
Identical output (order preserved, nulls filtered); removes stream/lambda allocation per request.

### 3. Cache metrics
- `LocalEmbeddingCache`: covered by Caffeine `recordStats()` + `stats()` accessor + optional Micrometer bind (above).
- `LlmResponseCache` (`src/main/java/com/recsys/infrastructure/cache/LlmResponseCache.java`):
  add `AtomicLong hits, misses, evictions`; increment in `get` (hit vs miss) and on eviction
  (the `LinkedHashMap.removeEldestEntry` path); expose a `stats()` accessor (e.g. a small record or
  the three counts). Optionally log cumulative hit-rate every N evictions. No change to caching behavior.
- `MultiLevelEmbeddingCache` already has `tierStats()` counters — **out of scope** (left as-is).

## Out of Scope (deliberately deferred)
- `VectorMath.cosine()` norm precompute — **dead code** on the hot path (recall uses `innerProduct`); not optimized here. (Removing the unused method is optional trivial cleanup, not part of this spec.)
- Redis pipelining (`ShardedTopKStore`, `ShardedRecordStore`), Redis pool `testWhileIdle`/`testOnReturn`.
- MySQL connection pooling (impact uncertain — depends on whether MySQL is on a hot path).
- `System.currentTimeMillis()` reduction, `cosine` loop unrolling, `List.of()` micro-allocations.
- These belong to a possible benchmark-driven follow-up, not this behavior-preserving pass.

## Testing Strategy

- **Rewrite** `LocalEmbeddingCacheTest.cache_evictsOldEntriesWhenCapacityIsReached`: it currently asserts
  that *entry 1 specifically* is evicted (strict recency). Caffeine's W-TinyLFU admission is
  frequency-based, so assert instead: after loading 3 distinct ids into a cap-2 cache,
  `estimatedSize() <= 2` and at least one prior id is no longer present (an eviction occurred) —
  not *which* id. All other `LocalEmbeddingCache` tests (size, bloom, null-sentinel, single-flight) stay.
- **Add** a stats test: a hit followed by a miss moves `stats()` hit/miss counters.
- **Add** an `LlmResponseCacheTest` assertion that hit/miss/eviction counters increment.
- `CandidateGenerator` tests should pass unchanged (output is identical).
- Gate: `mvn clean test` green.

## Risks / Mitigations
- **Eviction policy nuance:** LRU → W-TinyLFU. Mitigation: after `warmUp()`/`preload()` the cache holds
  the full embedding set, so eviction effectively never fires in production; the only impact is the one
  rewritten unit test. Documented above.
- **`estimatedSize()` is eventually-consistent.** Mitigation: `executor(Runnable::run)` makes maintenance
  synchronous, so size and stats are deterministic for tests and assertions.
- **New dependency (Caffeine).** Widely used, Apache-2.0, no transitive bloat; pairs with existing Micrometer.

## File-change summary
- `pom.xml` — add Caffeine dependency.
- `infrastructure/cache/LocalEmbeddingCache.java` — Caffeine backing map + `stats()`/`bindMetrics()`.
- `infrastructure/vectordb/CandidateGenerator.java` — imperative recall loop.
- `infrastructure/cache/LlmResponseCache.java` — hit/miss/eviction counters + `stats()`.
- `api/serving/RecSysServer.java` — bind cache metrics when a registry is available.
- Tests: `LocalEmbeddingCacheTest` (rewrite eviction test, add stats test), `LlmResponseCacheTest` (add stats test).
