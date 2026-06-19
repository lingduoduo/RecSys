# Model Serving Retrieval→Ranking via Shared Recall (Sub-project 1 of 3)

_Date: 2026-06-19_
_Scope: Port 8080 (`com.recsys.model`, Spring Boot ONNX serving) — restructure the recommend path into an explicit **retrieve → rank → hybrid-merge** pipeline, where retrieval is the shared `MultiChannelRecallService` and ranking is the ONNX two-tower model. `ModelRuntimeProvider` becomes the orchestrator of both stages, per variant._
_Part of: aligning port 8080 with the data-pipeline / feature-store / multi-channel-recall work already adopted on 6010 and 7010. Sub-project 2 (A/B reliability) and sub-project 3 (real-time user-tower features) depend on this and are out of scope here._

---

## 1. Problem Statement

Port 8080 is a clean single-model two-tower ONNX service, but it predates the shared recall / feature-store infrastructure and is **fully self-contained**:

- **Candidates** come from in-memory `DataManager` metadata (genre / top-rated / latest) via `CandidateSelectionService` ([CandidateSelectionService.java](../../src/main/java/com/recsys/model/service/CandidateSelectionService.java)). It does **not** use the shared `MultiChannelRecallService`, `ShardedTopKStore` (trending), or `GlobalPopularityStore` that 6010/7010 use.
- **Retrieval and ranking are not separated.** `RetrievalService` and `RankingService` exist but are **dormant/unused**; the live path is `CandidateSelectionService.selectCandidates → UserTowerInferenceService.scoreCandidates` (one monolithic step).
- `ModelRuntime` holds `candidateSelectionService` + `inferenceService` but exposes no first-class retrieval or ranking stage.

The goal is to make the recommend path a real two-stage **retrieve → rank** pipeline that consumes the shared recall core, so that trending / popular / cold-start signals (continuously refreshed by the Flink pipeline) drive candidate generation, while the ONNX model does what it is good at — ranking. `ModelRuntimeProvider` is the natural orchestrator.

This sub-project delivers only that. It does not touch A/B reliability or the user-tower feature inputs.

---

## 2. Chosen Approach

Restructure `RecommendationService` into **retrieve → rank → hybrid-merge**:

1. **Classify** the user from the assigned variant's **user vocab**: in vocab → *existing* (warm); `__UNK__` → *cold*. Model-vocab membership is the single source of truth for warm/cold.
2. **Retrieve (shared core):** call a per-variant `MultiChannelRecallService` (model-serving `RecallConfig`) with the warm/cold decision driving `QuotaPolicy.defaultModelRetrieval()`.
3. **Rank (ONNX, hybrid two-tier):** ONNX-score the candidates that are in the model item vocab (tier 1); items outside the model vocab keep their recall score (tier 2); the final list is `tier1 ++ tier2` truncated to `k` — **strict tiering** (the model's known items always rank above fresh/unknown ones), which avoids fragile cross-scale score calibration.

`ModelRuntime` gains a `retrievalStage` and a `rankingStage`; `ModelRuntimeProvider` wires both per variant, reusing shared channel singletons.

**Decisions settled in brainstorming:**
- **Retrieval source:** adopt the shared `MultiChannelRecallService` (not a self-enriched candidate selector, not an internal-only refactor).
- **Out-of-vocab ranking:** hybrid two-tier (strict), not drop-the-rest, not constrain-recall-to-vocab.
- **Cold-start source of truth:** model vocab membership (not embedding-presence, not strict-both). 8080 passes its vocab-based warm/cold decision into recall.
- **Retrieval is per-variant** because user vocab is loaded per artifact, so warm/cold classification is variant-specific.

**Invariant:** the HTTP contract is unchanged — `POST /api/v1/recommend` request and `RecommendResponse{userId, modelVersion, abTestVariant, recommendations}` shape are byte-compatible. The recommendation *content* changes (intended: candidates now come from shared recall).

---

## 3. Architecture

```
POST /api/v1/recommend   (controller: per-user rate-limit, submit-token, load-shed — UNCHANGED)
        │
        ▼
RecommendationService.recommend(request, assignment)
   1. runtime  = modelRuntimeProvider.getRuntime(assignment.variant())
   2. classify = runtime.userVocab.contains(userId) ? EXISTING : COLD(__UNK__)   // decides cold-start-pool caching
   3. RETRIEVE → runtime.retrievalStage().retrieve(query, recallLimit)  ← shared MultiChannelRecallService
                 channels: EmbeddingChannel, TrendingChannel, PopularityChannel, ColdStartChannel
                 quota:    QuotaPolicy.defaultModelRetrieval()
                 (warm/cold is resolved INSIDE recall via the per-variant VocabMembershipEmbeddingStore — §4.2)
   4. RANK (hybrid two-tier) → runtime.rankingStage().rank(encoded, candidates, k)
                 in-vocab  → ONNX scoreBatch (user tower × item tower)   [tier 1, by model score]
                 out-vocab → keep recall score                            [tier 2, appended]
                 exclude seen, take top-k
   5. cache (RecommendationKey{userId,k,excluded,variant,modelVersion}) — UNCHANGED
        │
        ▼
RecommendResponse{ userId, modelVersion, abTestVariant, recommendations }   (shape UNCHANGED)
```

Retrieval is **per-variant** (warm/cold uses that variant's vocab); the underlying channel stores (`CandidateGenerator` over `i2vEmb`, `ShardedTopKStore`, `GlobalPopularityStore`) are **shared singletons** reused across variants, so building a runtime stays cheap.

Sub-project 2 adds A/B reliability around variant selection; sub-project 3 feeds the user tower real-time features (Flink embedding / recent history).

---

## 4. Components

### 4.1 `ModelRetrievalStage` (new — `model/service/ModelRetrievalStage.java`)

Per-variant wrapper around the shared recall service.

```java
public ModelRetrievalStage(MultiChannelRecallService recallService)
public List<MovieCandidate> retrieve(RecommendationQuery query, int limit)
```

The warm/cold decision is encoded in the `RecallConfig.userEmbeddingStore` (see §4.2), so the stage just delegates to `recallService.recall(query, limit)`. One instance per `ModelRuntime`.

### 4.2 `VocabMembershipEmbeddingStore` (new — `model/service/VocabMembershipEmbeddingStore.java`)

Tiny `EmbeddingStore` adapter that makes recall's warm/cold classification equal **model vocab membership**, with no change to the shared recall service:

```java
public VocabMembershipEmbeddingStore(Map<String,Integer> userVocab)   // the variant's user vocab
public float[] getEmbedding(String userId)   // sentinel non-null iff userId ∈ userVocab (minus __UNK__), else null
```

`MultiChannelRecallService` classifies a user as cold when `userEmbeddingStore.getEmbedding(userId) == null`; this adapter therefore yields cold ⇔ `__UNK__`. The sentinel vector is never used for ANN (the `EmbeddingChannel` uses the shared `CandidateGenerator`, a separate store), only for the presence probe.

### 4.3 `QuotaPolicy.defaultModelRetrieval()` (new factory in `service/retrieval/coldstart/QuotaPolicy.java`)

```java
public static QuotaPolicy defaultModelRetrieval() {
    warm = {embedding 0.70, trending 0.15}, residual popularity
    cold = {cold_start 0.50, trending 0.25}, residual popularity
}
```

Embedding-heavy for warm (the ONNX ranker personalizes the rest); cold leans trending/popularity. `embedding` absent from cold and `cold_start` absent from warm → 0 quota slots (gap-fill only), same pattern as `defaultMovie()` / `defaultOnline()`. Built via the existing general helper.

### 4.4 `RankingStage` (repurpose the dormant `model/service/RankingService.java`)

Real ONNX ranking + the hybrid two-tier merge.

```java
public RankingStage(UserTowerInferenceService inference, FeatureEncoder encoder, ModelArtifactService artifacts)
public List<ScoredItem> rank(EncodedFeatures user, List<MovieCandidate> candidates, int k)
```

Logic:
1. Partition `candidates` by `artifacts.getItemVocab().containsKey(itemId)` into **in-vocab** and **out-of-vocab**.
2. ONNX-score in-vocab via `inference.scoreBatch` → sort desc (tier 1).
3. Out-of-vocab keep their `MovieCandidate.score()` (recall score) → sort desc (tier 2).
4. Return `tier1 ++ tier2` truncated to `k`, as `ScoredItem{itemId, score}`. (Tier-2 items carry their recall score; strict tiering means tier-1 always ranks above tier-2, so the two score scales never need reconciling.)

### 4.5 `ModelRuntime` (modify — `model/service/ModelRuntime.java`)

Record now carries the two stages:

```java
record ModelRuntime(
    String variant,
    ModelArtifactService artifactService,
    ModelRetrievalStage retrievalStage,   // NEW
    RankingStage rankingStage,            // NEW
    FeatureEncoder featureEncoder,
    UserTowerInferenceService inferenceService)
```

`CandidateSelectionService` is no longer a field of the runtime (it moves to the fallback path, §4.7). Accessors `modelVersion()` / `isReady()` unchanged.

### 4.6 `ModelRuntimeProvider` (modify)

- Build the **shared channel stores once** (lazily, like the existing Redis pool): `CandidateGenerator` (over `i2vEmb`), `ShardedTopKStore`, `GlobalPopularityStore`, a recall `ExecutorService` (`model-recall-channel` pool), and a `ChannelHealthMonitor`.
- `buildRuntime(variant)` additionally constructs, for that variant: a `VocabMembershipEmbeddingStore` from the variant's user vocab, a model-serving `RecallConfig` (4 channels + `QuotaPolicy.defaultModelRetrieval()` + the vocab store + shared executor/health monitor), the `MultiChannelRecallService.from(config)`, the `ModelRetrievalStage`, and the `RankingStage`.
- Shutdown: add the recall executor to `@PreDestroy` (`shutdownNow()`); shared channel stores closed once.

This is the concrete "make `ModelRuntimeProvider` better to support retrieval and ranking services."

### 4.7 `RecommendationService` (modify)

- `computeRecommendations` becomes retrieve → rank → hybrid:
  - `warm = runtime.artifactService().getUserVocab().containsKey(userId)` (excluding `__UNK__`).
  - `recallLimit = min(max(k * 5, 50), 100)` — the shared `RecommendationQuery` cap is 100; documented as a tunable constraint (raising it is out of scope, it is shared with 6010/7010).
  - `candidates = runtime.retrievalStage().retrieve(query, recallLimit)`; `runtime.rankingStage().rank(encoded, candidates, k)`.
- **Cold-start pool** (`computeColdStartPool`) recomputes via the **cold** retrieval path for `__UNK__`; the cache structure (per-user TTL-LRU + shared cold-start pool) is unchanged.
- **Empty-recall fallback:** if `retrieve` returns empty (e.g. Redis down), fall back to `CandidateSelectionService` over `DataManager` (in-memory) so 8080 still serves; then rank as usual. This keeps a Redis-independent resilience path.
- Cache keys, dedup (`getOrCompute`), degraded-cache path (`tryServeFromCache` via `getLoadedRuntime`) — unchanged.

### 4.8 Retired / demoted

- `RetrievalService` + `RetrievalServiceTest` — **retired** (dormant; superseded by `ModelRetrievalStage` + shared recall).
- `CandidateSelectionService` — **demoted to the empty-recall fallback only** (no longer a `ModelRuntime` field; called by `RecommendationService` solely when shared recall is empty). Full retirement is a later cleanup.

---

## 5. Data Flow & Behavior

- **Existing user** (in variant vocab): warm quota → embedding-led recall; ONNX user tower personalizes ranking; tier-1 dominated.
- **Cold user** (`__UNK__`): cold quota → `cold_start` / `trending` / `popularity` recall; ONNX scores any in-vocab hits with the generic `__UNK__` user vector; served from / cached into the shared cold-start pool.
- **Fresh/trending items** not in the model vocab now surface (tier 2) instead of being invisible — the main behavioral gain from adopting shared recall.

---

## 6. Error Handling

| Condition | Behavior |
|---|---|
| Per-channel recall failure / Redis blip | Channel → empty + `ChannelHealthMonitor` backoff (shared core, unchanged) |
| Total recall empty (all channels fail) | Fall back to `CandidateSelectionService` in-memory pool, then rank |
| Item recalled but absent from model item vocab | Hybrid tier 2 (kept at recall score, ranked below ONNX-scored items) |
| Overload / load-shed | Existing degraded-cache path (`getLoadedRuntime`, `X-Served-From: degraded-cache`) — unchanged |
| Unknown variant / runtime not ready | Existing `ModelRuntimeProvider` behavior — unchanged (A/B reliability is SP2) |

Real-time budget: recall channels run in parallel under `RECALL_CHANNEL_TIMEOUT_MS` (default 200 ms); ONNX ranking scores ≤ `recallLimit` (≤100) candidates — within the existing request envelope.

---

## 7. Testing Strategy

### New
- `ModelRetrievalStageTest` — delegates to recall; warm vs cold quota reflected in candidates; empty when all channels empty.
- `VocabMembershipEmbeddingStoreTest` — sentinel non-null for in-vocab user, null for `__UNK__` / unknown; `__UNK__` itself treated as absent.
- `RankingStageTest` — hybrid tiering: in-vocab ONNX-ranked above out-of-vocab; out-of-vocab kept by recall score; k-truncation; all-in-vocab and all-out-of-vocab edge cases.
- `QuotaPolicyTest` (extend) — `defaultModelRetrieval()` warm/cold slot maps; totals ≤ limit; `embedding` 0 cold, `cold_start` 0 warm.

### Reworked (output changes)
- `RecommendationServiceTest` — retrieve→rank→hybrid; existing vs cold user; empty-recall → `CandidateSelectionService` fallback; cold-start pool via cold retrieval; cache keys unchanged.
- `ModelRuntimeProviderTest` — per-variant retrieval + ranking wiring; shared channel singletons reused across variants; recall executor in shutdown.
- `PredictionIntegrationTest` — end-to-end retrieve→rank against the ONNX model.

### Retired / replaced
- `RetrievalServiceTest` — deleted with `RetrievalService`.
- `RankingServiceTest` — replaced by `RankingStageTest` (the class is repurposed into `RankingStage`).

### Pass unmodified
- `ABTestServiceTest`, controller / rate-limit / submit-token / cache / load-shed / metrics tests; shared-recall core tests (`QuotaPolicyTest` equivalence cases, `MultiChannelRecallServiceTest`, `RecallConfigTest`).

### Full-suite + load guard
- `mvn test` green; opt-in `InferenceLoadTest` passes (recall now parallel with a 200 ms channel budget — confirm no latency regression).

---

## 8. Out of Scope

- **A/B reliability** — stable bucketing under split changes, exposure logging, safe missing-variant fallback, immutable assignment (sub-project 2).
- **Real-time user-tower features** — feeding the user tower the Flink `feature:user:<id>:embedding` / `user:<id>:recent_movies` instead of vocab-only encoding (sub-project 3).
- **Score-blended (non-strict) hybrid ranking** — calibrating ONNX and recall scores onto one scale; SP1 uses strict tiering.
- **Raising the shared `RecommendationQuery` 100-candidate cap** — shared with 6010/7010; out of scope.
- **Fully retiring `CandidateSelectionService`** — kept as the Redis-down fallback; removal is a later cleanup.

---

## 9. Files Changed

| File | Change |
|---|---|
| `model/service/ModelRetrievalStage.java` | New — per-variant wrapper around `MultiChannelRecallService` |
| `model/service/VocabMembershipEmbeddingStore.java` | New — `EmbeddingStore` adapter: presence ⇔ model vocab membership |
| `service/retrieval/coldstart/QuotaPolicy.java` | Add `defaultModelRetrieval()` factory |
| `model/service/RankingService.java` → `RankingStage` | Repurpose into ONNX ranking + hybrid two-tier merge |
| `model/service/ModelRuntime.java` | Carry `retrievalStage` + `rankingStage`; drop `candidateSelectionService` field |
| `model/service/ModelRuntimeProvider.java` | Build shared channel stores once; wire per-variant retrieval + ranking; shutdown recall executor |
| `model/service/RecommendationService.java` | retrieve→rank→hybrid; cold-start via cold retrieval; empty-recall fallback to `CandidateSelectionService` |
| `model/service/RetrievalService.java` | Delete (dormant, superseded) |
| `src/test/.../model/service/ModelRetrievalStageTest.java` | New |
| `src/test/.../model/service/VocabMembershipEmbeddingStoreTest.java` | New |
| `src/test/.../model/service/RankingStageTest.java` | New |
| `src/test/.../service/retrieval/coldstart/QuotaPolicyTest.java` | Extend for `defaultModelRetrieval()` |
| `src/test/.../model/service/RecommendationServiceTest.java` | Rework for retrieve→rank→hybrid |
| `src/test/.../model/service/ModelRuntimeProviderTest.java` | Per-variant retrieval+ranking wiring |
| `src/test/.../model/service/PredictionIntegrationTest.java` | Update for new pipeline |
| `src/test/.../model/service/RetrievalServiceTest.java` | Delete |
