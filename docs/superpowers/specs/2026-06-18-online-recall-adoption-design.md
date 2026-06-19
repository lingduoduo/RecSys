# Online Serving Recall Adoption Design (Sub-project 2 of 3)

_Date: 2026-06-18_
_Scope: Port 7010 (`OnlinePredictionServer`) — adopt the shared `MultiChannelRecallService` for recommendation recall._
_Depends on: sub-project 1 (`QuotaPolicy`, `RecallConfig`, `MultiChannelRecallService.from`) — PR #125, branch `feat/shared-recall-core`. This work stacks on that branch until #125 merges._
_Followed by: sub-project 3 (converge config; retire `OnlineRecommendationEngine`)._

---

## 1. Problem Statement

Port 7010 runs its own bespoke recall in `OnlineRecommendationService.recommend()`: an `OnlineRecommendationEngine` blend (recent-history similarity + trending) reciprocal-rank-fused with embedding ANN candidates, with `OnlineLearner` reweighting applied inside `blend()`. Port 6010 uses the shared `MultiChannelRecallService` (5 channels, quota-aware cold/warm merge, `ChannelHealthMonitor`). The two paths have diverged.

Sub-project 1 made the quota policy injectable (`QuotaPolicy`) and added `RecallConfig` + `MultiChannelRecallService.from(...)`. This sub-project adopts that shared core on 7010 with a 7010-specific channel set and quota, **changing 7010's recommendation output** (the bespoke blend is replaced by the quota-based multichannel merge — this is intended, not a regression).

---

## 2. Chosen Approach

Restructure `OnlineRecommendationService.recommend()` into **recall → re-rank → snapshot**:

1. **Recall (shared core):** build a `RecommendationQuery` and call a 7010-wired `MultiChannelRecallService`.
2. **Re-rank (`OnlineLearner`):** add `onlineLearner.scoreAdjustment(itemId)` to each candidate's merge score, sort, exclude recent, take top `k` — preserving the learner's influence (moved out of the old `blend()`).
3. **Snapshot (response only):** compute `recentMovies` + per-request `trendingMovies(window)` directly (hybrid window decision — recall uses fixed windows; the response reflects the requested window).

Add an `OnlineRecentHistoryChannel` (7010's behavioral signal, analog of 6010's `GenreHistoryChannel`) and a `QuotaPolicy.defaultOnline()` factory.

**Decisions settled in brainstorming:**
- **Window: hybrid** — recall channels use fixed windows (`last_hour`, `last_day`); the response keeps a per-request `window` trending snapshot. No change to the shared `RecommendationQuery` DTO.
- **Channel set: full 5** — `embedding`, `online_recent_history`, `trending`, `popularity`, `cold_start` (requires building `GlobalPopularityStore` on 7010).
- **7010 output changes** — accepted; service + integration + regression test expectations are rewritten.

---

## 3. Architecture

```
OnlinePredictionService / OnlineFeaturesService (HTTP — load-shed, rate-limit unchanged)
        │
        ▼
OnlineRecommendationService.recommend(request)
   1. RecommendationQuery(userId, recallLimit, excludedItemIds=recentWatched, null)
   2. candidates = multiChannelRecallService.recall(query, recallLimit)   ← SHARED CORE
   3. re-rank: map MovieCandidate→Movie, + onlineLearner.scoreAdjustment, exclude recent, top-k
   4. snapshot: recentMovies (OnlineFeatureStore) + trendingMovies(window) (ShardedTopKStore)
   5. OnlineRecommendationResult(user, window, "multichannel", recentMovies, trendingMovies, recs)

MultiChannelRecallService (7010 RecallConfig):
   channels = [ EmbeddingChannel, OnlineRecentHistoryChannel(NEW), TrendingChannel(fixed),
                PopularityChannel, ColdStartChannel ]
   quotaPolicy = QuotaPolicy.defaultOnline()
   userEmbeddingStore = userEmbCache (LogicalExpiryEmbeddingCache)  ← cold detection reuses cached lookup
```

---

## 4. Components

### 4.1 `OnlineRecentHistoryChannel` (new — `service/retrieval/channels/OnlineRecentHistoryChannel.java`)

Implements `RecallChannel`; `name()` = `"online_recent_history"`.

```java
public OnlineRecentHistoryChannel(RecentHistoryStore recentHistoryStore, DataManager dataManager)

public List<MovieCandidate> recall(RecommendationQuery query, int limit)
```

Logic (ports `OnlineRecommendationEngine.scoreByRecentHistory`): read up to 3 recent movie ids via `recentHistoryStore.getRecentMovieIds(userId, 3)`; for each, fetch `dataManager.getSimilarMovies(seedId)` (cap 12); accumulate a blend weight `recencyBoost - rank` where `recencyBoost = 30.0 + seedIndex*8.0`, summed per candidate. That blend weight determines **intra-channel order only** — it is NOT the emitted score. The emitted `MovieCandidate.score()` is **rank-based, `1.0 / (rank + 1)`** (rank within this channel's ordered output), so its scale matches the other channels for the quota merge's gap fill (see the class Javadoc). Returns up to `limit` `MovieCandidate`s in that order, `channel` = `"online_recent_history"`. The recent watched ids themselves are NOT excluded here — the merge drops them via `excludedItemIds`. Returns `List.of()` when there is no recent history.

`userId` parse: `Integer.parseInt(query.userId())`; on `NumberFormatException` return `List.of()` (the merge/other channels cover it).

### 4.2 `QuotaPolicy.defaultOnline()` (new factory in `service/retrieval/coldstart/QuotaPolicy.java`)

```java
public static QuotaPolicy defaultOnline() {
    warm = {embedding 0.50, online_recent_history 0.25, trending 0.15}, residual popularity
    cold = {cold_start 0.50, trending 0.20, popularity 0.20}, residual online_recent_history
}
```

Built via the existing general helper (sub-project 1). `embedding` absent from cold and `cold_start` absent from warm → 0 quota slots there (gap-fill only), same pattern as `defaultMovie()`.

### 4.3 `OnlineRecommendationService` (modify — `online/serving/OnlineRecommendationService.java`)

- New constructor dependencies: `MultiChannelRecallService recallService`, `RecentHistoryStore recentHistoryStore`, `TrendingStore topkStore` (for the snapshot). Keeps `dataManager` and `onlineLearner`. The `OnlineRecommendationEngine` and `CandidateGenerator` constructor params are **removed** from the recommend path (engine retirement completes in sub-project 3; for now `OnlineRecommendationService` no longer calls them).
- `recommend()` implements the recall → re-rank → snapshot flow (§3). `recallLimit = max(k*4, 12)`.
- Re-rank: `score' = candidate.score() + onlineLearner.scoreAdjustment(movieId)`; sort desc; exclude recent; top `k`.
- Snapshot: `recentMovies` from `recentHistoryStore.getRecentMovieIds(userId, 3)` → `getMovieById`; `trendingMovies` from `topkStore.getTopKIds(normalizeWindow(window), k)` → `getMovieById`. Window normalization (`last_hour`/`last_day`/`last_month`, default `last_hour`, invalid → `IllegalArgumentException`) is preserved.
- `strategy` = `"multichannel"`.
- **Empty-recall fallback:** if `recall` returns empty, use the trending snapshot as `recommendations` (top `k`).
- `requireUser(userId)` / `UnknownUserException` unchanged.

### 4.4 `OnlinePredictionServer` (modify — `online/serving/OnlinePredictionServer.java`)

Build the 7010 recall service and pass it to `OnlineRecommendationService`:

```java
GlobalPopularityStore globalPopStore = new GlobalPopularityStore(jedisPool);
ExecutorService recallExecutor = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors() * 2, r -> new Thread(r, "online-recall-channel"));
MultiChannelRecallService recallService = MultiChannelRecallService.from(
        RecallConfig.builder()
                .channels(List.of(
                        new EmbeddingChannel(candidateGenerator),
                        new OnlineRecentHistoryChannel(onlineFeatureStore, dataManager),
                        new TrendingChannel(topkStore, List.of("last_hour", "last_day")),
                        new PopularityChannel(dataManager, globalPopStore),
                        new ColdStartChannel(topkStore, globalPopStore)))
                .quotaPolicy(QuotaPolicy.defaultOnline())
                .healthMonitor(new ChannelHealthMonitor())
                .executor(recallExecutor)
                .channelTimeoutMs(200L)
                .faultInjector(FaultInjector.NOOP)
                .userEmbeddingStore(userEmbCache)
                .build());
OnlineRecommendationService recommendationService =
        new OnlineRecommendationService(dataManager, recallService, onlineFeatureStore, topkStore, onlineLearner);
```

`recallExecutor` is added to the shutdown hook (`recallExecutor.shutdownNow()`). `onlineFeatureStore` is already an `OnlineFeatureStore` (implements `RecentHistoryStore`); `topkStore` is the existing `ShardedTopKStore`. The `OnlineRecommendationEngine` construction is removed.

> _Superseded by sub-project 3:_ the explicit `.channelTimeoutMs(200L)` shown above was later dropped from both ports' builder chains. Sub-project 3 made the per-channel timeout the env-tunable `RecallConfig.Builder` default (`RECALL_CHANNEL_TIMEOUT_MS`, default `200L`), so the shipped wiring no longer calls `.channelTimeoutMs(...)` — behavior is unchanged when the env var is unset.

**Unchanged:** HTTP services (`OnlineFeaturesService`, `OnlinePredictionService`), admission control, rate limiting, metrics, the response DTO shape (`OnlineRecommendationResult`, `OnlineFeatureSnapshotResponse`).

---

## 5. Data Flow & Real-Time Budget

- Recall channels run in parallel on `recallExecutor` with a 200 ms per-channel timeout — within 7010's 500 ms request timeout.
- Cold-start detection reuses `userEmbCache` (the `LogicalExpiryEmbeddingCache`); no extra Redis lookup beyond what `EmbeddingChannel` already does.
- Re-rank and snapshot are in-memory (`DataManager` + heap-cached stores).

---

## 6. Error Handling

| Condition | Behavior |
|---|---|
| Cold user (no embedding) | Cold `QuotaPolicy` → `cold_start`/`trending`/`popularity` lead (shared core) |
| Per-channel failure / Redis blip | Channel → empty + `ChannelHealthMonitor` backoff (shared core, unchanged) |
| Total recall empty (all channels fail) | Fall back to the per-request trending snapshot as recommendations |
| Unknown user | `UnknownUserException` → HTTP 404 (unchanged) |
| Invalid window | `IllegalArgumentException` → HTTP 400 (unchanged) |

---

## 7. Testing Strategy

### New
- `OnlineRecentHistoryChannelTest` — recent ids → recency-boosted similar movies; empty when no recent history; correct `channel()` name and rank-based scores; non-numeric userId → empty.
- `QuotaPolicyTest` (extend) — `defaultOnline()` warm/cold produce expected slot maps for several limits; totals ≤ limit; `embedding` has 0 cold slots, `cold_start` 0 warm slots.

### Reworked (output changes — not pass-unmodified)
- `OnlineRecommendationServiceTest` — recommendations come from the multichannel merge: warm user → embedding-led; cold user (null embedding) → `cold_start`/`trending`-led; `OnlineLearner`-boosted item ranks higher; recent excluded; response carries `recentMovies` + per-`window` `trendingMovies`; empty-recall → trending-snapshot fallback; `strategy == "multichannel"`.
- `OnlinePredictionServerIntegrationTest` / `OnlinePredictionRegressionTest` — update expected recommendation output + `strategy` value for the new mechanism.

### Pass unmodified
- `OnlineRecommendationEngineTest` (engine unchanged, off the path), shared-core tests (`QuotaPolicyTest` equivalence cases, `MultiChannelRecallServiceTest`, `RecallConfigTest`), HTTP-layer / load-shedding / metrics tests.

### Full-suite + load guard
- `mvn test` green; opt-in `OnlinePredictionLoadTest` passes (recall now parallel with a 200 ms channel budget — confirm no latency regression).

---

## 8. Out of Scope

- **Retiring `OnlineRecommendationEngine`** and removing its now-unused recommend-blend / `CandidateGenerator` coupling — sub-project 3.
- **Converging 6010 + 7010 behind one config/registry** — sub-project 3.
- **Per-request window in recall** — deliberately not adopted (hybrid decision; would require a shared-DTO change).
- **Tuning `QuotaPolicy.defaultOnline()` fractions or `channelTimeoutMs` via env** — sub-project 3 / later.

---

## 9. Files Changed

| File | Change |
|---|---|
| `service/retrieval/channels/OnlineRecentHistoryChannel.java` | New — recent-history-similarity recall channel |
| `service/retrieval/coldstart/QuotaPolicy.java` | Add `defaultOnline()` factory |
| `online/serving/OnlineRecommendationService.java` | Recall→re-rank→snapshot via shared `MultiChannelRecallService`; drop engine/CandidateGenerator from the path; `OnlineLearner` post-recall; `strategy="multichannel"` |
| `online/serving/OnlinePredictionServer.java` | Build `GlobalPopularityStore`, recall executor, `ChannelHealthMonitor`, 5-channel `RecallConfig`; remove engine construction; shutdown-hook the executor |
| `src/test/.../channels/OnlineRecentHistoryChannelTest.java` | New |
| `src/test/.../coldstart/QuotaPolicyTest.java` | Extend for `defaultOnline()` |
| `src/test/.../online/serving/OnlineRecommendationServiceTest.java` | Rework for multichannel recall |
| `src/test/.../online/serving/OnlinePredictionServerIntegrationTest.java` | Update expectations |
| `src/test/.../online/serving/OnlinePredictionRegressionTest.java` | Update expectations |
