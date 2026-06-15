# Cold Start & Multi-Channel Recall Improvement Design

_Date: 2026-06-15_
_Scope: Port 6010 (`RecSysServer`) — `service/retrieval/` package_
_Related: [data-pipeline-improvement-design.md](2026-06-15-data-pipeline-improvement-design.md)_

---

## 1. Problem Statement

Port 6010 (`RecSysServer`) has four recall channels — `EmbeddingChannel`, `TrendingChannel`, `GenreHistoryChannel`, `PopularityChannel` — dispatched in parallel by `MultiChannelRecallService`. Two concrete problems:

**Cold start:** `EmbeddingChannel.byEmbedding()` returns `List.of()` when no `u2vEmb:{userId}` exists in Redis. `GenreHistoryChannel.byUserHistory()` also returns nothing for users with no watch history. A brand-new or event-sparse user falls through to `PopularityChannel` alone (static `DataManager.getTopRatedMovies()` + `getLatestMovies()`), which is disconnected from the live pipeline.

**Pipeline data not wired in:** The data pipeline improvement spec established two live Redis signals that no channel at port 6010 reads:
- `global:item_popularity` — written by Spark `UserEventStreamingJob` via `ZINCRBY`, reflects cumulative real event counts.
- `topk:last_day`, `topk:last_month` — written by Flink alongside `topk:last_hour`; `TrendingChannel` only reads `last_hour`.

**Max-score-wins merge:** All channels compete for the same slot pool. When `EmbeddingChannel` is warm, it takes all top slots. No mechanism guarantees diversity or cold-start coverage.

---

## 2. Chosen Approach

Add a `ColdStartChannel` and `QuotaSpec`, and make `MultiChannelRecallService` quota-aware with per-request cold-start detection. Fix `PopularityChannel` and `TrendingChannel` to read live pipeline data. Zero changes to the pipeline, `RecallChannel` interface, `ChannelHealthMonitor`, or `RecommendationOrchestrator`.

---

## 3. Architecture

```
                          ┌──────────────────────────────────────────────────────┐
recsys_events ──► Redis   │  Port 6010  MultiChannelRecallService                │
  Flink writes:           │                                                      │
    u2vEmb:{id}    ──────►│  EmbeddingChannel      (quota: 60% warm /  0% cold) │
    topk:last_hour  ─────►│  TrendingChannel        (quota: 20% warm / 20% cold)│
    topk:last_day   ──┐   │  GenreHistoryChannel    (quota: 15% warm / 10% cold)│
    topk:last_month ──┤   │  PopularityChannel      (quota:  5% warm / 20% cold)│
  Spark writes:        └─►│  ColdStartChannel (NEW) (quota:  0% warm / 50% cold)│
    global:item_pop ─────►│                                                      │
                          └─────────────── QuotaSpec (per-request) ─────────────┘
```

**Cold-start detection:** Before dispatching channels, `MultiChannelRecallService` calls `userEmbeddingStore.getEmbedding(userId)`. A heap hit returns in ~0ms (warm path). A heap miss falls through to one Redis GET (cold path). The cost is zero for warm users because `EmbeddingChannel` would have made the same lookup; for cold users the single Redis GET replaces what would have been an empty embedding lookup anyway.

---

## 4. New Components

### 4.1 `ColdStartChannel` (`service/retrieval/ColdStartChannel.java`)

Blends three pipeline-written Redis sources with time-decay weights:

| Source | Redis key | Weight | Rationale |
|---|---|---|---|
| Flink topK | `topk:last_day` | 0.7 | Recency signal, more stable than `last_hour` |
| Flink topK | `topk:last_month` | 0.5 | Long-tail coverage, very stable |
| Spark popularity | `global:item_popularity` | 0.4 | Cumulative event-count, backstop |

Scores are summed per item across all three sources (union, not intersection). Items in `query.excludedItemIds()` are dropped. If all three Redis keys are empty (pipeline not running), the channel returns `List.of()` and the gap-fill phase of the merge handles coverage via other channels.

Channel name: `"cold_start"`.

### 4.2 `QuotaSpec` (`service/retrieval/QuotaSpec.java`)

A record mapping channel name → slot count, computed once per request:

```java
record QuotaSpec(Map<String, Integer> slots) {
    static QuotaSpec warm(int limit);  // embedding=60%, trending=20%, genre=15%, popularity=5%
    static QuotaSpec cold(int limit);  // cold_start=50%, trending=20%, popularity=20%, genre=10%
}
```

Fractional slots round up; total is capped at `limit`. Channels absent from the spec get 0 quota slots — their candidates are only used in gap fill.

---

## 5. Modified Components

### 5.1 `MultiChannelRecallService`

**New field:** `EmbeddingStore userEmbeddingStore` (injected via a new 6-arg constructor). The existing 5-arg and 1-arg constructors remain unchanged; when `userEmbeddingStore` is `null`, cold-start detection is skipped and the service always uses `QuotaSpec.warm(limit)` — preserving backward compatibility for tests.

**Per-request cold-start detection:**
```java
int userId = Integer.parseInt(query.userId());
boolean isCold = userEmbeddingStore.getEmbedding(userId) == null;
QuotaSpec quota = isCold ? QuotaSpec.cold(limit) : QuotaSpec.warm(limit);
```

**Quota-aware two-phase merge** (replaces max-score-wins):

1. **Quota fill** — for each channel in registration order, take up to `quota.slots(channel)` of its returned candidates (sorted by score desc), adding each to the result if not already present and not excluded.
2. **Gap fill** — if `result.size() < limit` after quota fill, append remaining candidates from all channels sorted by score desc until `limit` is reached.

The existing `ChannelHealthMonitor` backoff continues unchanged: a backed-off channel is skipped before quota fill, and its allocated slots spill into gap fill.

### 5.2 `TrendingChannel`

Accepts a list of windows at construction (default: `["last_hour", "last_day"]`). Scores use time-decay: `last_hour` weight 1.0, `last_day` weight 0.6. Items appearing in multiple windows sum their weighted scores; highest score wins per item.

`RecSysServer` constructs with `List.of("last_hour", "last_day")`.

### 5.3 `PopularityChannel`

**Primary source:** `global:item_popularity` sorted set in Redis (`ZREVRANGE ... WITHSCORES`), accessed via a new `GlobalPopularityStore` thin wrapper around `jedisPool`.

**Fallback:** if `global:item_popularity` has no entries (pipeline not running or first boot), fall back to `DataManager.getTopRatedMovies(limit)` + `getLatestMovies(limit)` as before.

Score: normalised rank-based (same as `EmbeddingChannel` — `1.0 / (rank + 1.0)`) when reading from Redis. Flat `SCORE = 0.4` when using the DataManager fallback.

---

## 6. `RecSysServer` Wiring Changes

```java
// Pass userEmbCache to MultiChannelRecallService for cold-start detection
GlobalPopularityStore globalPopStore = new GlobalPopularityStore(jedisPool);

MultiChannelRecallService recallService = new MultiChannelRecallService(
    List.of(
        new EmbeddingChannel(candidateGenerator),
        new TrendingChannel(topkStore, List.of("last_hour", "last_day")),
        new GenreHistoryChannel(candidateGenerator),
        new PopularityChannel(dataManager, globalPopStore),
        new ColdStartChannel(topkStore, globalPopStore)
    ),
    new ChannelHealthMonitor(),
    executor,
    DEFAULT_CHANNEL_TIMEOUT_MS,
    FaultInjector.NOOP,
    userEmbCache           // <-- new: for cold-start detection
);
```

`RecommendationOrchestrator`, `RecommendationService`, `RecommendV2Service`, and all other callers are unchanged.

---

## 7. Testing Strategy

### Unit Tests

| Test class | Coverage |
|---|---|
| `ColdStartChannelTest` | Blended score calculation, `excludedItemIds` filtering, empty-Redis fallback returns `List.of()` |
| `QuotaSpecTest` | Slot rounding, warm + cold totals sum to `limit`, zero-slot channels absent from map |
| `MultiChannelRecallServiceTest` (extend) | Quota fill order, gap fill when channel returns fewer than quota, cold detection (null embedding → cold quota), warm detection (non-null → warm quota), backoff channel slots spill to gap fill |
| `TrendingChannelTest` (extend) | Multi-window score blend, time-decay weights, single-window construction |
| `PopularityChannelTest` (extend) | Redis-primary path produces rank-based scores, DataManager fallback when Redis empty |

### Integration Smoke Test

```bash
# Cold user — no embedding in Redis
curl "http://localhost:6010/getrecommendation?userId=999"
# Expect: non-empty results sourced from cold_start + trending + popularity

# Warm user — embedding seeded at startup
curl "http://localhost:6010/getrecommendation?userId=1"
# Expect: embedding channel dominates top results

# Trending multi-window
redis-cli zrange topk:last_day 0 4 WITHSCORES
curl "http://localhost:6010/getrecommendation?userId=999"
# Expect: last_day items appear in cold_start and trending channels
```

### Regression Guard

`RecSysServerIntegrationTest`, `RecSysServerRegressionTest`, and `EmbeddingRecallLoadTest` must pass without modification. The load test validates that the cold-start detection probe (one heap lookup per request) does not degrade throughput.

---

## 8. Out of Scope

- Context-feature genre seeding (device, country from `RecommendationQuery`) — deferred; requires API contract change to propagate context.
- `ColdStartChannel` personalisation per user segment — deferred until event volume supports user clustering.
- Flink writing a pre-blended `coldstart:global` key — serving-layer blending is sufficient and avoids adding a pipeline sink for a serving concern.
- Changes to `OnlinePredictionServer` (port 7010) — separate concern, covered in data pipeline improvement spec.

---

## 9. Files Changed

| File | Repo | Change |
|---|---|---|
| `service/retrieval/ColdStartChannel.java` | Backend | New — blends `topk:last_day`, `topk:last_month`, `global:item_popularity` |
| `service/retrieval/QuotaSpec.java` | Backend | New — warm/cold slot maps, slot rounding |
| `service/retrieval/MultiChannelRecallService.java` | Backend | Add `userEmbeddingStore`, cold-start detection, quota-aware two-phase merge |
| `service/retrieval/TrendingChannel.java` | Backend | Multi-window support, time-decay weights |
| `service/retrieval/PopularityChannel.java` | Backend | Redis-primary via `global:item_popularity`, DataManager fallback |
| `infrastructure/redis/GlobalPopularityStore.java` | Backend | New thin wrapper — `ZREVRANGE global:item_popularity` |
| `serving/RecSysServer.java` | Backend | Wire `ColdStartChannel`, `GlobalPopularityStore`, pass `userEmbCache` to `MultiChannelRecallService` |
