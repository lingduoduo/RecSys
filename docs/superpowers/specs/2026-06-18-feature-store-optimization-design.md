# Feature Store Optimization Design

_Date: 2026-06-18_
_Scope: Serving-side feature store read path — `GlobalPopularityStore` caching_
_Related: [cold-start-multi-channel-recall-design.md](2026-06-15-cold-start-multi-channel-recall-design.md), [data-pipeline-improvement-design.md](2026-06-15-data-pipeline-improvement-design.md)_

---

## 1. Problem Statement

The recently-merged cold-start + multi-channel recall work (port 6010, `RecSysServer`) and the data-pipeline unification added new per-request reads of user-independent global signals (`topk:*` windows, `global:item_popularity`). An audit of the resulting feature-store read path found that **all but one** of these reads are already well-optimized — and isolates the single remaining gap.

**Already optimized — no work required:**

- **`ShardedTopKStore`** (the `topk:last_hour` / `last_day` / `last_month` windows) already has a JVM hot-cache keyed by window: 2 s fresh TTL (`DEFAULT_CACHE_TTL_MS`), single-flight refresh (`inflight` map), serve-stale-on-error (60 s `staleTtlMs`), plus shard fan-out reduction. Used by both `RecSysServer` (6010) and `OnlinePredictionServer` (7010). `TrendingChannel` and `ColdStartChannel` window reads are therefore heap-served and coalesced.
- **The cold-start probe** (`MultiChannelRecallService.recall` → `userEmbeddingStore.getEmbedding(userId)`) is *not* an extra Redis round-trip. `userEmbCache` is a `LocalEmbeddingCache` (Bloom filter + 30 s null-sentinel + single-flight), and the **same instance** backs both the probe and `EmbeddingChannel`'s `CandidateGenerator`. Cold users short-circuit on the Bloom filter with zero Redis traffic; warm users are heap-served after the first load.

**The one real gap:**

- **`GlobalPopularityStore` has no caching.** Every `getTopIds(limit)` issues a fresh `ZREVRANGE global:item_popularity 0 limit-1`. It is read by **both** `PopularityChannel` and `ColdStartChannel`, so a single cold request performs **2 uncached Redis reads** of the same slow-moving global key, and every request repeats them. `global:item_popularity` is a cumulative event counter (written by Spark `UserEventStreamingJob` via `ZINCRBY`) that moves on minute scales — there is no reason to read it from Redis per request.

---

## 2. Chosen Approach

Bring `GlobalPopularityStore` to parity with the caching standard `ShardedTopKStore` already sets: a JVM snapshot cache with a short fresh TTL, single-flight refresh, and serve-stale-on-error. Extract the cache-with-TTL + single-flight pattern into one small tested helper (`TtlSingleFlightCache`) reusing the existing `com.recsys.infrastructure.SingleFlight` utility, and have `GlobalPopularityStore` delegate to it.

`ShardedTopKStore` is left **structurally intact** (it is a tested hot-path store; restructuring carries risk for marginal gain). Only its cache fresh-TTL is made env-configurable so it can be aligned to the chosen freshness target. The new helper may be adopted by `ShardedTopKStore` in a later change if duplication becomes a real cost.

**Freshness target:** ~1 s (near-real-time), per design decision. Acceptable because both global signals change on minute scales.

**Invariants preserved:** no `RecallChannel` interface change, no pipeline change, no channel-logic change, no API contract change.

---

## 3. Architecture

```
                         ┌───────────────────────────────────────────────┐
recsys_events ─► Redis   │  Port 6010  MultiChannelRecallService          │
  Spark writes:          │                                                │
    global:item_pop ────►│  PopularityChannel ─┐                          │
                         │                     ├─► GlobalPopularityStore   │
                         │  ColdStartChannel ──┘     │                     │
                         │                           ▼                     │
                         │                   TtlSingleFlightCache (NEW)     │
                         │                   fresh 1s / stale 60s / 1-flight│
                         │                           │ (miss only)         │
                         └───────────────────────────┼─────────────────────┘
                                                      ▼
                                          Redis ZREVRANGE global:item_popularity

  Flink writes:
    topk:last_hour ─► ShardedTopKStore (already cached: 2s→1s TTL, single-flight, stale 60s)
    topk:last_day  ─►   read by TrendingChannel + ColdStartChannel (6010), OnlinePredictionServer (7010)
    topk:last_month
```

**Per-request read count for `global:item_popularity`:**

| | Before | After |
|---|---|---|
| Cold request (Popularity + ColdStart) | 2 Redis reads | 0 (cache hit) or shared 1 (refresh) |
| Across N concurrent requests in one TTL window | 2N Redis reads | ≤ 1 Redis read (single-flight) |

---

## 4. Components

### 4.1 `TtlSingleFlightCache<V>` (NEW — `infrastructure/cache/TtlSingleFlightCache.java`)

Generic, thread-safe, single-value-per-key snapshot cache. Mirrors the proven `ShardedTopKStore.CachedIds` lifecycle, generalized and independently testable.

```java
public final class TtlSingleFlightCache<V> {
    public TtlSingleFlightCache(long freshTtlMs, long staleTtlMs);   // staleTtlMs >= freshTtlMs
    public V get(String key, Supplier<V> loader);
}
```

Per-key state: `{ V value; long freshUntil; long staleUntil; }`.

Read semantics (`get`):
1. **Fresh hit** (`now < freshUntil`) — return cached `value`, loader not called.
2. **Stale-but-refreshable** (`freshUntil <= now`) — one thread runs `loader` via the shared `SingleFlight` keyed by `key`; concurrent callers return the last `value` immediately (no blocking) while the refresh is in flight.
3. **Refresh success** — store new `value`, set `freshUntil = now + freshTtlMs`, `staleUntil = now + staleTtlMs`.
4. **Refresh failure (loader throws)** — if `now < staleUntil`, serve the stale `value`; otherwise propagate the exception (no usable snapshot).
5. **Cold miss, no prior value** — run loader inline; on failure propagate.

Defaults provided as constants: `DEFAULT_FRESH_TTL_MS = 1_000`, `DEFAULT_STALE_TTL_MS = 60_000`. TTLs are constructor-injected so tests can drive expiry deterministically without sleeping where possible (inject a clock or use small TTLs).

### 4.2 `GlobalPopularityStore` (CHANGED — `infrastructure/redis/GlobalPopularityStore.java`)

Gains one `TtlSingleFlightCache<List<String>>` field. The cache holds the **top-N** list once (N = `MAX_CACHED = 100`); `getTopIds(limit)` returns the first `min(limit, list.size())` entries (slicing, identical to `ShardedTopKStore.slice()`), so different `limit` values share one snapshot.

```java
public static final String KEY = "global:item_popularity";
static final int MAX_CACHED = 100;

private final Pool<Jedis> pool;
private final TtlSingleFlightCache<List<String>> cache;

public GlobalPopularityStore(Pool<Jedis> pool);                       // default TTLs
GlobalPopularityStore(Pool<Jedis> pool, long freshTtlMs, long staleTtlMs); // for tests

public List<String> getTopIds(int limit) {
    if (limit <= 0) return List.of();
    List<String> top = cache.get(KEY, this::loadTopFromRedis);   // ZREVRANGE 0..MAX_CACHED-1
    return top.size() <= limit ? top : List.copyOf(top.subList(0, limit));
}
```

`loadTopFromRedis()` performs `jedis.zrevrange(KEY, 0, MAX_CACHED - 1)` inside a pooled resource. If `limit > MAX_CACHED` (not expected on the recall path, where `limit` is small), the result is capped at `MAX_CACHED`; this is documented behavior and asserted in tests.

Behavior on Redis error mid-refresh: `TtlSingleFlightCache` serves the last snapshot until `staleUntil`. If no snapshot exists (cold JVM + Redis down), `loadTopFromRedis` propagates; `getTopIds` catches and returns `List.of()`, so `PopularityChannel` falls through to its existing `DataManager` fallback — current behavior preserved.

### 4.3 `ShardedTopKStore` (MINIMAL CHANGE — `infrastructure/redis/ShardedTopKStore.java`)

No structural change. Make the fresh cache TTL env-configurable to align with the freshness target, following the existing `ONLINE_TOPK_STALE_TTL_MS` pattern:

```java
// DEFAULT_CACHE_TTL_MS stays 2_000L (back-compat default)
this.cacheTtlMs = readLongEnv("ONLINE_TOPK_CACHE_TTL_MS", DEFAULT_CACHE_TTL_MS);
```

Operators set `ONLINE_TOPK_CACHE_TTL_MS=1000` to match `GlobalPopularityStore`. Default unchanged, so existing deployments and tests are unaffected.

---

## 5. Data Flow & Freshness

- `PopularityChannel.recall` and `ColdStartChannel.recall` both call `globalPopularityStore.getTopIds(limit)`.
- First call after a fresh-TTL expiry triggers exactly one `ZREVRANGE` (single-flight); all other calls in the window — including the duplicate within the same request and any concurrent requests — return the cached snapshot.
- `freshTtlMs = 1000` bounds staleness at ~1 s. `global:item_popularity` is a cumulative counter on minute scales, so 1 s is effectively real-time.
- The intra-request duplicate read (Popularity + ColdStart on a cold request) collapses to the shared snapshot automatically — no request-scoped plumbing needed.

---

## 6. Error Handling

| Condition | Behavior |
|---|---|
| Redis up, fresh hit | Serve cached snapshot, no Redis call |
| Redis up, fresh expired | One thread refreshes (single-flight); others serve last snapshot |
| Redis error mid-refresh, snapshot age < `staleTtlMs` | Serve stale snapshot (60 s grace), matching `ShardedTopKStore` |
| Redis error, no snapshot yet (cold JVM) | `getTopIds` returns `List.of()` → `PopularityChannel` `DataManager` fallback |
| Single-flight refresh throws | Isolated to refreshing thread; waiters get stale value, never an exception |

---

## 7. Testing Strategy

### Unit tests

| Test class | Coverage |
|---|---|
| `TtlSingleFlightCacheTest` (new) | Fresh hit serves cache (loader called once); expiry triggers exactly one refresh under concurrent `get` (single-flight); loader exception serves stale within `staleTtlMs` then propagates after; distinct keys isolated; cold miss runs loader inline |
| `GlobalPopularityStoreTest` (extend) | Repeated `getTopIds` within fresh TTL → one `ZREVRANGE`; slicing by `limit` from a single top-N snapshot; `limit <= 0` → `List.of()`; cold Redis-down → `List.of()`; stale-serve on mid-flight Redis error |

### Regression guard (must pass unmodified)

`ColdStartChannelTest`, `PopularityChannelTest`, `MultiChannelRecallServiceTest`, `RecSysServerIntegrationTest`, `RecSysServerRegressionTest`, `EmbeddingRecallLoadTest`. The load test confirms `global:item_popularity` Redis reads no longer scale with QPS (≤ 1 per fresh-TTL window).

### Integration smoke test

```bash
# Seed popularity and hit a cold user repeatedly
redis-cli zadd global:item_popularity 100 1 90 2 80 3
for i in $(seq 1 50); do curl -s "http://localhost:6010/getrecommendation?userId=999" >/dev/null; done
# MONITOR (or INFO commandstats) shows ~1 ZREVRANGE per second, not 2 per request
redis-cli --stat
```

---

## 8. Out of Scope (with rationale)

- **Cold-start probe dedup** — already Bloom/heap/null-sentinel-served by the shared `LocalEmbeddingCache`; not a Redis cost. Removing the residual heap lookup would require threading the embedding through the frozen `RecallChannel` interface — not worth it.
- **`ShardedTopKStore` restructuring** — already cached with single-flight + stale fallback; only its fresh TTL is exposed as an env var.
- **`OnlinePredictionServer` (7010)** — reads `topk:*` via the already-cached `ShardedTopKStore`; does not read `global:item_popularity`. No change needed.
- **`ModelApplication` (8080)** — reads neither global key. No change needed.
- **Adopting `TtlSingleFlightCache` inside `ShardedTopKStore`** — deferred; a behavior-preserving refactor to do once, separately, if duplication becomes a maintenance cost.

---

## 9. Files Changed

| File | Change |
|---|---|
| `infrastructure/cache/TtlSingleFlightCache.java` | New — generic TTL + single-flight + stale-serve snapshot cache |
| `infrastructure/redis/GlobalPopularityStore.java` | Add `TtlSingleFlightCache`; cache top-N + slice; test-injectable TTLs |
| `infrastructure/redis/ShardedTopKStore.java` | Fresh cache TTL env-configurable (`ONLINE_TOPK_CACHE_TTL_MS`), default unchanged |
| `src/test/.../cache/TtlSingleFlightCacheTest.java` | New unit tests |
| `src/test/.../redis/GlobalPopularityStoreTest.java` | Extend for cache hit/slice/stale/empty paths |
