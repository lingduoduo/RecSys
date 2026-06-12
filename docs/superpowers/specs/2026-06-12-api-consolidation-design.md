# API & Service Consolidation Design

**Date:** 2026-06-12
**Status:** Approved — ready for implementation planning
**Approach:** B — Pipeline interface + `RecommendationOrchestrator` as backbone

---

## 1. Context and Goals

The system currently has four serving processes, each with an independent recommendation
pipeline, incompatible request/response types, and a gateway route table that includes
four dead routes pointing to non-existent endpoints. The consolidation has three goals:

1. **Gateway alignment** — fix the route table so every route reaches a live endpoint;
   remove dead routes; add clean production domain routes.
2. **Unified API schema** — all four recommendation paths expose the same
   `POST /v2/recommend` endpoint accepting `RecommendationQuery` and returning
   `RecommendationResult` (both already exist in `domain/`).
3. **Shared pipeline interface** — extract `RecommendationPipeline` so each path is a
   named, bounded, independently testable implementation. Wire the orphaned
   `RecommendationOrchestrator` into Path 1 so it finally earns its keep.

**Out of scope for this pass:**
- Moving cross-cutting concerns (rate limiting, auth) from backends into the gateway
- Extracting the knowledge base service into its own process
- Implementing the Path 4 LLM/RL model (stub only)
- Infrastructure changes (Terraform, EKS manifests)

**Backward compatibility:** all existing endpoints (`/getrecommendation`,
`/api/v1/recommend`, `/online/recommendation`) are kept untouched. Old endpoints remain
for dev and backward-compatible clients. New endpoints are the production surface.

---

## 2. 4-Path Taxonomy

The system is reframed around four named recommendation paradigms:

| Path | Name | Paradigm | Service | New prod endpoint |
|------|------|----------|---------|-------------------|
| 1 | Rule-based recall | Heuristic + embeddings | `:6010` RecSysServer | `POST /v2/recommend` |
| 2 | Offline ML | ONNX two-tower, batch-trained | `:8080` ModelApplication | `POST /v2/recommend` |
| 3 | Online / real-time | Live signals + online learning | `:7010` OnlinePredictionServer | `POST /v2/recommend` |
| 4 | LLM / Sequential | Generative / sequence-aware (stub) | `:8080` ModelApplication | `POST /v2/sequential/recommend` (501) |

The KFServing pair-scoring endpoint (`POST /v1/models/recmodel:predict`) is Path 1
infrastructure — it stays in `:6010` but receives no gateway route. It is a dev/internal
scoring utility, not a recommendation path.

```
                       Gateway :8010
                           │
       ┌───────────────────┼────────────────────┬──────────────────────┐
       ▼                   ▼                    ▼                      ▼
 Path 1 :6010        Path 2 :8080         Path 3 :7010          Path 4 :8080
 Rule-based          Offline ML           Online/RT             LLM/Sequential
 recall              ONNX two-tower       live blending         (stub → future)

 /api/recommend/     /api/recommend/      /api/recommend/       /api/recommend/
 embedding           model               online                sequential (501)

 ─────────────────────────────────────────────────────────────────────────────
 All 4 share:  POST /v2/recommend
               IN:  RecommendationQuery  (domain/)
               OUT: RecommendationResult (domain/)
```

---

## 3. Service Responsibilities (post-consolidation)

### :6010 RecSysServer — Embedding Recall + Catalog

**Catalog**
- `GET /item` (`/movie`) — movie by ID
- `GET /getuser` (`/user`) — user by ID

**Recall / Recommendation**
- `GET /getrecommendation` (`/recommendation`) — old endpoint, unchanged
- `POST /v2/recommend` — NEW; delegates to `EmbeddingRecallPipeline`
- `GET /similar` — item-to-item cosine similarity
- `POST /v1/models/recmodel:predict` — KFServing pair scoring (dev/internal only)

**Embedding management**
- `POST /setembedding`, `POST /setuserembedding` — write embeddings to Redis + heap cache

**Infrastructure:** LocalEmbeddingCache (JVM heap L1) → RedisEmbeddingStore (L2) → Redis.
MultiChannelRecallService: EmbeddingChannel + TrendingChannel + GenreHistoryChannel +
PopularityChannel. No re-ranking step — pure recall output.

---

### :8080 ModelApplication — ONNX Inference + Knowledge Management

**Model inference**
- `POST /api/v1/recommend` — old endpoint, unchanged
- `POST /v2/recommend` — NEW; delegates to `OnnxInferencePipeline`
- `POST /v2/sequential/recommend` — NEW stub; returns 501

**Auth / Token**
- `GET /api/v1/token` — issue submit token
- `POST /login` — session login

**Knowledge base CRUD** (package: `model/knowledge/`)
- `GET/POST/DELETE/PATCH /api/v1/knowledge-bases` — unchanged

**Model admin**
- `GET /version` — model version + artifact metadata
- `POST /api/v1/model/versions/preload|activate|rollback` — model lifecycle

**Infrastructure:** ONNX Runtime (`dssm_model.onnx`), RecommendationCache, ABTestService,
FeatureFlagService (PostHog + env), ModelRateLimiter, LoadShedder, GcEventTracker.

---

### :7010 OnlinePredictionServer — Real-Time Feature Store + Online Blending

**Feature serving**
- `GET /online/features` — user recent history + trending snapshot; old endpoint, unchanged
- `GET /online/recommendation` — old endpoint, unchanged
- `POST /v2/recommend` — NEW; delegates to `OnlineBlendingPipeline`

**Ops / observability**
- `GET /online/ops` — fault injection, capacity controls
- `GET /metrics` — Prometheus scrape
- `GET /health/live`, `/health/ready`, `/health`

**Sharded storage**
- `prefix:/shards/` — ShardedRecordStore (backing OnlineFeatureStore)

**Infrastructure:** OnlineFeatureStore (Redis recent-history), ShardedTopKStore (trending),
CandidateGenerator (ANN), OnlineLearner (bias weights flushed to `bias:item:*` every 30 s).
Sub-500 ms SLA enforced by Armeria request timeout.

---

### :8010 MicroserviceGatewayServer — API Gateway

See Section 5 for the full route table.

**Resilience per route:** RouteCircuitBreaker (failure threshold + cooldown),
GatewayRateLimiter (token bucket), GatewayAuthenticator (API key).
LLM routes: dedicated LlmProxyService with token budget + response cache + retry.

---

## 4. RecommendationPipeline Interface

A single interface in `service/recommendation/` is the contract for all four paths:

```java
// service/recommendation/RecommendationPipeline.java
public interface RecommendationPipeline {
    RecommendationResult recommend(RecommendationQuery query);
}
```

### 4.1 EmbeddingRecallPipeline (Path 1, `serving/`)

`RecommendationOrchestrator` already has the correct signature. It implements
`RecommendationPipeline` directly — no logic changes, just adds `implements`.
`EmbeddingRecallPipeline` in `serving/` is a one-line wrapper that instantiates and
delegates to `RecommendationOrchestrator`.

### 4.2 OnnxInferencePipeline (Path 2, `model/service/`)

Adapts the existing `model.service.RecommendationService`:

1. Convert `RecommendationQuery` → `RecommendRequest`
2. Call `recommendationService.recommend(request, assignment)` (assignment resolved internally)
3. Convert `RecommendResponse` → `RecommendationResult`
4. Put `abTestVariant` and `modelVersion` into `metadata`

A/B assignment, caching, rate limiting, and load shedding remain internal to this pipeline.

### 4.3 OnlineBlendingPipeline (Path 3, `online/serving/`)

Adapts `OnlineRecommendationService`:

1. Convert `RecommendationQuery` → `OnlineRecommendationRequest`
2. Call `recommendationService.recommend(request)`
3. Convert `List<Movie>` recommendations → `List<RankedMovie>` using position-based scores
   (score = (n - i) / n for rank position i of n), since Path 3 produces a blended
   rank order but no raw numeric score
4. Convert `OnlineRecommendationResult` → `RecommendationResult`
5. Put `strategy` ("online" or "online+model") and `window` into `metadata`
6. Extra online context (`recentMovies`, `trendingMovies`) omitted from `RecommendationResult`
   — available via the unchanged `GET /online/features` endpoint for clients that need it

### 4.4 SequentialRecommendationPipeline (Path 4 stub, `service/recommendation/`)

```java
// service/recommendation/SequentialRecommendationPipeline.java
public class SequentialRecommendationPipeline implements RecommendationPipeline {
    @Override
    public RecommendationResult recommend(RecommendationQuery query) {
        throw new UnsupportedOperationException(
            "Sequential/LLM recommendation is not yet implemented. " +
            "Future: SASRec / BERT4Rec / LLM-based path.");
    }
}
```

The endpoint returns HTTP 501 with a descriptive JSON error body. When the LLM/RL model
is ready, this class is the only implementation that changes.

---

## 5. Unified Schema

Both types already exist in `domain/` — no changes needed:

```java
// domain/RecommendationQuery.java  (existing, no changes)
public record RecommendationQuery(
    String userId,
    int limit,
    Set<String> excludedItemIds,
    String cursor           // null on first request; opaque token for pagination
)

// domain/RecommendationResult.java  (existing, no changes)
public record RecommendationResult(
    String userId,
    List<RankedMovie> items,
    String nextCursor,
    Map<String, String> metadata
)
```

**Path-specific metadata conventions:**

| Path | Keys added to `metadata` |
|------|--------------------------|
| 1 — Embedding recall | `candidateCount`, `rankedCount` (already set by `RecommendationOrchestrator`) |
| 2 — ONNX | `abTestVariant`, `modelVersion` |
| 3 — Online | `strategy` ("online" or "online+model"), `window` ("last_hour" etc.) |
| 4 — Sequential stub | `reason` ("not implemented") |

### New endpoints

| Service | New endpoint | Method | Old endpoint (kept) |
|---------|-------------|--------|---------------------|
| `:6010` | `/v2/recommend` | POST | `GET /getrecommendation` |
| `:8080` | `/v2/recommend` | POST | `POST /api/v1/recommend` |
| `:7010` | `/v2/recommend` | POST | `GET /online/recommendation` |
| `:8080` | `/v2/sequential/recommend` | POST | — (new stub) |

**Implementation per service:**
- `:6010` (Armeria) — `RecommendV2Service extends BaseApiService`, reads JSON body,
  delegates to `EmbeddingRecallPipeline`
- `:8080` (Spring Boot) — `RecommendationV2Controller`, new `@PostMapping("/v2/recommend")`
  and `@PostMapping("/v2/sequential/recommend")`, delegates to respective pipelines
- `:7010` (Armeria) — `OnlineRecommendV2Service extends ApiService`, reads JSON body,
  delegates to `OnlineBlendingPipeline`

---

## 6. Gateway Route Table

### Routes to remove (dead — no endpoint exists at target)

```
recommendation-retrieval  /api/retrieval      → :8080   REMOVED
ranking                   /api/ranking        → :8080   REMOVED
agent-workflow            /api/agents         → :8080   REMOVED
observability             /api/observability  → :8080   REMOVED
```

### New production routes (added)

```
embed-recall      /api/recommend/embedding    → :6010/v2/recommend
model-inference   /api/recommend/model        → :8080/v2/recommend
online-blend      /api/recommend/online       → :7010/v2/recommend
sequential        /api/recommend/sequential   → :8080/v2/sequential/recommend
knowledge         /api/knowledge              → :8080/api/v1/knowledge-bases
```

### Full route table after consolidation

| Name | Prefix | Target | Purpose |
|------|--------|--------|---------|
| embed-recall | `/api/recommend/embedding` | `:6010` | Path 1 production |
| model-inference | `/api/recommend/model` | `:8080` | Path 2 production |
| online-blend | `/api/recommend/online` | `:7010` | Path 3 production |
| sequential | `/api/recommend/sequential` | `:8080` | Path 4 stub |
| knowledge | `/api/knowledge` | `:8080` | KB CRUD |
| user-profile | `/api/users` | `:6010` | User lookup |
| movie-metadata | `/api/movies` | `:6010` | Movie lookup |
| feature | `/api/features` | `:7010` | Feature snapshot |
| catalog | `/api/catalog` | `:6010` | Backward compat |
| model | `/api/model` | `:8080` | Backward compat |
| online | `/api/online` | `:7010` | Backward compat |
| llm | `/api/llm` | LLM | Optional |
| llm-explanation | `/api/explanations` | LLM | Optional |

All recommendation production routes share the `/api/recommend/` prefix, making them
visually distinct from data/catalog routes (`/api/users`, `/api/movies`, `/api/features`).

---

## 7. Knowledge Base Package Boundary

Move existing classes into a `model/knowledge/` sub-package (pure rename, no logic changes):

```
model/knowledge/
  KnowledgeBaseController.java        (was model/controller/)
  KnowledgeBaseFacadeService.java     (was model/service/)
  KnowledgeBaseConverter.java         (was model/converter/)
  dto/    entity/    request/    response/    vo/
```

`model/` root = ONNX inference. `model/knowledge/` = content management. Future extraction
to its own service requires only moving this package.

---

## 8. Error Handling

All new `/v2/recommend` endpoints return the same error shape as `model/dto/ApiError`:

```json
{ "status": 400, "error": "...", "message": "...", "timestamp": "..." }
```

| Status | Condition | Paths |
|--------|-----------|-------|
| 400 | Invalid `RecommendationQuery` (null userId, limit out of [1,100]) | All |
| 404 | Unknown userId | All |
| 429 | Rate limit exceeded | Paths 2 and 3 (Path 1 inherits gateway rate limiter) |
| 501 | Not implemented | Path 4 stub |
| 503 | Load shedding active | Paths 2 and 3 (Path 1 degrades via cache) |

---

## 9. Testing Strategy

### Unit tests — pipeline adapters (every build)

| Test class | What it verifies |
|---|---|
| `OnnxInferencePipelineTest` | Query→request conversion; response→result mapping; `abTestVariant` + `modelVersion` in metadata |
| `OnlineBlendingPipelineTest` | `OnlineRecommendationResult`→`RecommendationResult` mapping; `strategy` + `window` in metadata |
| `EmbeddingRecallPipelineTest` | Delegates to `RecommendationOrchestrator`; pagination cursor preserved |
| `SequentialRecommendationPipelineTest` | Throws `UnsupportedOperationException`; message contains "not implemented" |

### Regression tests — old endpoints unchanged (every build)

| Test class | What it verifies |
|---|---|
| `RecommendationControllerRegressionTest` | `POST /api/v1/recommend` still returns `RecommendResponse` with `userId`, `modelVersion`, `abTestVariant`, `recommendations` |
| `RecSysServerRegressionTest` | `GET /getrecommendation` still returns `RecommendationResponse` with `user` + `movies`; `GET /similar` unchanged |
| `OnlinePredictionRegressionTest` | `GET /online/recommendation` still returns `OnlinePredictionResponse` with `strategy`, `window`, `recentMovies` |

### Integration tests — new `/v2/recommend` endpoints (every build)

| Test class | What it verifies |
|---|---|
| `RecSysV2RecommendIntegrationTest` | Parses `RecommendationQuery`; returns `RecommendationResult`; respects `limit` and `cursor` |
| `ModelV2RecommendIntegrationTest` | Returns `RecommendationResult`; metadata has `abTestVariant`; 400 on invalid input |
| `OnlineV2RecommendIntegrationTest` | Returns `RecommendationResult`; metadata has `strategy` and `window` |
| `SequentialStubIntegrationTest` | Returns 501 with readable message |
| `GatewayRouteTableTest` | No duplicate prefixes; 4 dead routes removed; 5 new production routes present; all backward-compat routes present |
| `CrossPathConsistencyTest` | Same `userId` sent to all three `/v2/recommend` endpoints; each returns non-empty `RecommendationResult` with correct `userId` |

### Load tests — new paths (`@Tag("load")`, opt-in)

| Test class | Profile |
|---|---|
| `EmbeddingRecallLoadTest` | 20 threads, 200 requests against `:6010/v2/recommend`; P95 ≤ 500 ms |
| `V2CrossPathLoadTest` | All three `/v2/recommend` endpoints in parallel; P95 per path within existing thresholds; no cross-path degradation |

### Test execution

```bash
# Unit + regression + integration (every commit)
mvn test

# Load tests (pre-release / scheduled)
mvn test -DexcludedGroups="" -Dgroups=load
```

---

## 10. Out-of-Scope Technical Debt (future passes)

| Item | Rationale for deferral |
|------|------------------------|
| Move KB CRUD to its own service | Unclear coupling to LLM explanation path; wait until that relationship is defined |
| Move rate limiting / auth to gateway only | Requires gateway to absorb backpressure currently handled per-service; risky in one pass |
| Implement Path 4 LLM/sequential model | Separate ML work; stub interface is the correct boundary now |
| Terraform / EKS manifest updates | No process topology change; existing `k8s/` manifests remain valid |
