# Model Serving Real-Time Recent-History Recall (Sub-project 3 of 3)

_Date: 2026-06-19_
_Scope: Port 8080 (`com.recsys.model`) — add the Flink-written recent watch history (`user:<id>:recent_movies`) as a retrieval channel in the model-serving recall, and auto-exclude recent watches from recommendations._
_Depends on: sub-project 1 (model-serving retrieval→ranking via shared recall, PR #129) and sub-project 2 (A/B reliability, PR #130) — both merged to `main`._
_Completes the 3-part effort of aligning port 8080 with the data-pipeline / feature-store / multi-channel-recall infrastructure._

---

## 1. Problem Statement

The original intent for this sub-project was "feed the user tower the Flink `feature:user:<id>:embedding` / `user:<id>:recent_movies` instead of vocab-only encoding." **Investigation found that is architecturally infeasible without retraining the model:**

- The ONNX DSSM model accepts **only** `user_id` + `item_id` long vocab indices and emits a `score` ([UserTowerInferenceService.java:58-80](../../src/main/java/com/recsys/model/service/UserTowerInferenceService.java)). There is no input slot for embeddings or features, and no user-tower-export path.
- The Flink-written `u2vEmb:<id>` is a unit-normalized **hash-bucket engagement accumulator**, not a learned latent vector in the DSSM item-embedding space — so inner-product scoring against the model would be mathematically invalid.

So real-time behavior cannot be fed *into* the model. What **is** feasible and valuable: port 8080's recall (wired in sub-project 1) has channels `embedding`, `trending`, `popularity`, `cold_start` but **no recent-history channel** — even though port 7010 already uses `user:<id>:recent_movies` via `OnlineRecentHistoryChannel`. This sub-project brings real-time behavioral personalization to 8080's recommendations by adding recent watch history to the **retrieval** stage (the ONNX model then ranks), and excludes just-watched movies from the results.

---

## 2. Chosen Approach

- **Recent-history retrieval channel:** add the existing `OnlineRecentHistoryChannel` (+ a shared `OnlineFeatureStore`) to port 8080's `MultiChannelRecallService`, and give `online_recent_history` a warm quota slot in `QuotaPolicy.defaultModelRetrieval()`. Movies similar to recent watches enter the candidate set; the ONNX model ranks them.
- **Auto-exclude recent watches:** `ModelRetrievalStage` augments the recall query's `excludedItemIds` with the user's recent watched ids before recall, so just-watched movies do not reappear (matching port 7010).

**Decisions settled in brainstorming:**
- Direction: recent-history **retrieval channel** (not a post-ONNX re-rank boost, not both).
- Exclusion: **yes**, auto-exclude recent watches from recommendations.

**Key design choice — keep it contained:** both the channel and the exclusion live entirely within the recall wiring (`ModelRuntimeProvider` + `ModelRetrievalStage`). `RecommendationService`, `RankingStage`, and the controller are **untouched**. One `OnlineFeatureStore` (lazy, on SP1's fail-fast recall pool) feeds both the channel and the exclusion. No new Spring beans, no model change.

**Invariant:** HTTP contract unchanged. With no Redis (recent history empty), behavior is identical to sub-project 1. Recommendation *content* changes when recent history is present (intended).

---

## 3. Architecture

```
ModelRuntimeProvider.ensureRecallInfra()   (shared stores, built once)
   candidateGenerator, topkStore, globalPopStore, recallExecutor, sharedHealthMonitor   (SP1)
   + OnlineFeatureStore onlineFeatureStore = new OnlineFeatureStore(recallPool)          ← NEW

buildRecallService(variant):
   channels = [ EmbeddingChannel(candidateGenerator),
                OnlineRecentHistoryChannel(onlineFeatureStore, DataManager.getInstance()),   ← NEW
                TrendingChannel(topkStore, [last_hour,last_day]),
                PopularityChannel(dataManager, globalPopStore),
                ColdStartChannel(topkStore, globalPopStore) ]
   quotaPolicy = QuotaPolicy.defaultModelRetrieval()        ← rebalanced (warm += online_recent_history)
   userEmbeddingStore = VocabMembershipEmbeddingStore(variant vocab)    (SP1 — warm/cold unchanged)
   → ModelRetrievalStage(recallService, onlineFeatureStore)             ← gains the recent-history store

RecommendationService.recommend (UNCHANGED) → ModelRetrievalStage.retrieve(query, limit):
   recent = onlineFeatureStore.getRecentMovieIds(userId, RECENT_EXCLUDE_LIMIT)   (numeric userId only; errors → empty)
   augmented = query with excludedItemIds = query.excludedItemIds ∪ recent
   return recallService.recall(augmented, limit)
        → merge drops recent watches; RankingStage (SP1 hybrid two-tier) ranks (UNCHANGED)
```

This completes the 3-part effort; there is no sub-project 4.

---

## 4. Components

### 4.1 `ModelRuntimeProvider` (modify — `model/service/ModelRuntimeProvider.java`)

- In `ensureRecallInfra()`: build one shared `OnlineFeatureStore onlineFeatureStore = new OnlineFeatureStore(recallPool)` (reuses SP1's fail-fast recall pool — recent-history reads fail fast → empty → gap-fill). Add it as a field alongside `candidateGenerator`/`topkStore`/etc.
- In `buildRecallService(artifactService)`: add `new OnlineRecentHistoryChannel(onlineFeatureStore, DataManager.getInstance())` to the channel list (between `EmbeddingChannel` and `TrendingChannel`, mirroring 7010's order); construct the retrieval stage as `new ModelRetrievalStage(recallService, onlineFeatureStore)`.

### 4.2 `ModelRetrievalStage` (modify — `model/service/ModelRetrievalStage.java`)

```java
public ModelRetrievalStage(MultiChannelRecallService recallService, RecentHistoryStore recentHistoryStore)
public List<MovieCandidate> retrieve(RecommendationQuery query, int limit)
```

- New `RecentHistoryStore recentHistoryStore` field; `RECENT_EXCLUDE_LIMIT = 20`.
- `retrieve`: parse `query.userId()` to int; fetch `recentHistoryStore.getRecentMovieIds(userId, RECENT_EXCLUDE_LIMIT)`; build the excluded set = `query.excludedItemIds()` ∪ `{String.valueOf(id)}`; construct a new `RecommendationQuery(userId, limit, excluded, cursor)`; call `recallService.recall(augmented, limit)`.
- **Graceful degradation:** wrap the recent-history fetch + parse in try/catch — `NumberFormatException` (non-numeric userId) or any store/Redis error → skip augmentation, recall with the original query's excludes. Retrieval never fails on the recent-history path.
- The single 2-arg constructor replaces SP1's 1-arg constructor; `ModelRuntimeProvider` is the only production caller.

### 4.3 `QuotaPolicy.defaultModelRetrieval()` (modify — `service/retrieval/coldstart/QuotaPolicy.java`)

```java
public static QuotaPolicy defaultModelRetrieval() {
    warm = {embedding 0.55, online_recent_history 0.20, trending 0.10}, residual popularity
    cold = {cold_start 0.50, trending 0.25}, residual popularity     // unchanged
}
```

`online_recent_history` is absent from cold → 0 cold slots (gap-fill only), same pattern as `embedding` having 0 cold slots; cold users have no recent history so the channel returns empty regardless. Built via the existing general helper.

### 4.4 `OnlineRecentHistoryChannel` (reused — no change)

The existing `service/retrieval/channels/OnlineRecentHistoryChannel.java` (`name() = "online_recent_history"`, reads up to 3 recent ids, emits rank-based scored candidates from `dataManager.getSimilarMovies`). Reused verbatim; no modification.

---

## 5. Data Flow & Behavior

- **Warm user with recent history:** `online_recent_history` supplies ~20% of warm candidates (movies similar to recent watches); the watched movies themselves are excluded from the query; ONNX ranks in-vocab hits (SP1 tier-1), out-of-vocab kept by recall score (tier-2).
- **Warm user, no recent history:** channel returns empty → gap-filled by embedding/trending/popularity (SP1 behavior); nothing to exclude.
- **Cold user (`__UNK__`):** cold quota has no `online_recent_history` slot; channel empty.
- **No Redis / Redis down:** recent-history read fails fast → empty (no augmentation, empty channel); if all channels are empty, SP1's in-memory `CandidateSelectionService` fallback serves. Behavior identical to SP1.

---

## 6. Error Handling

| Condition | Behavior |
|---|---|
| Recent-history Redis read fails / times out (150 ms recall pool) | Empty recent → no exclusion + empty channel (gap-fill); request unaffected |
| Non-numeric userId | Skip augmentation; `OnlineRecentHistoryChannel` also returns empty |
| Per-channel failure / backoff | `ChannelHealthMonitor` (SP1, unchanged) |
| Total recall empty | SP1 in-memory `CandidateSelectionService` fallback (unchanged) |

---

## 7. Testing Strategy

### Reworked / extended
- `ModelRetrievalStageTest` — recent watched ids are unioned into the query's `excludedItemIds` before `recall` (capture the query passed to a mocked `MultiChannelRecallService`); client-provided excludes preserved in the union; non-numeric userId → no augmentation; a throwing `RecentHistoryStore` → no augmentation (graceful), recall still called with original excludes. Update SP1's existing delegation test to the 2-arg constructor.
- `QuotaPolicyTest` (extend) — `defaultModelRetrieval` warm now allocates `online_recent_history` (e.g. for limit 20: embedding 11, online_recent_history 4, trending 2, popularity 3); cold unchanged and has 0 `online_recent_history` slots; totals ≤ limit.
- `ModelRuntimeProviderTest` (extend) — a built runtime's recall path includes the recent-history channel (e.g. retrieval still works; the `OnlineFeatureStore` is wired without requiring a live Redis since pools are lazy).

### Pass unmodified
- `OnlineRecentHistoryChannelTest` (channel reused as-is); `RankingStageTest`, `VocabMembershipEmbeddingStoreTest`, `RecommendationServiceTest`, controller / cache / A/B (SP2) tests; shared-recall core tests.

### Full-suite
- `mvn test` green. Opt-in `InferenceLoadTest` passes (one more channel on the parallel recall path, still within the 200 ms per-channel budget + fail-fast recall pool from SP1).

---

## 8. Out of Scope

- **Feeding real-time features into the ONNX model** — requires retraining the DSSM to accept new inputs (a modeling-pipeline change, different effort). This is the architectural blocker documented in §1.
- **Post-ONNX recency re-rank boost** — the alternative direction, not chosen.
- **Inner-product scoring with Flink user embeddings** — incompatible embedding space.
- **Env-tuning the new quota fractions or `RECENT_EXCLUDE_LIMIT`; per-variant recent history** — later.

---

## 9. Files Changed

| File | Change |
|---|---|
| `model/service/ModelRuntimeProvider.java` | Build shared `OnlineFeatureStore`; add `OnlineRecentHistoryChannel`; pass the store to `ModelRetrievalStage` |
| `model/service/ModelRetrievalStage.java` | 2-arg ctor with `RecentHistoryStore`; augment query excludes with recent watches (graceful on error) |
| `service/retrieval/coldstart/QuotaPolicy.java` | `defaultModelRetrieval` warm += `online_recent_history` (rebalance) |
| `src/test/.../model/service/ModelRetrievalStageTest.java` | Extend: exclusion union, non-numeric/error graceful, 2-arg ctor |
| `src/test/.../service/retrieval/coldstart/QuotaPolicyTest.java` | Extend: `defaultModelRetrieval` warm slots incl. `online_recent_history` |
| `src/test/.../model/service/ModelRuntimeProviderTest.java` | Extend: recent-history channel wired |
