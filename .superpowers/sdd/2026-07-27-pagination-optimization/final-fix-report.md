# Final Review Fix Report

## Status

Final-review remediation is implemented in:

```text
a944f556e9b717ef9b9b9a6fe0f792aedac8ee6f
fix pagination serving integration gaps
```

An independent final review found one Important cold-start candidate-budget
gap. That issue was reproduced, fixed, regression-tested, and approved on
follow-up with no remaining Critical or Important findings.

## Finding-to-fix mapping

### Spring model-serving pagination (`8080`)

- Added `ModelRecommendationPipelineConfig` so the actual
  `RecommendationV2Controller` default endpoint receives an
  `OnnxInferencePipeline` built with the shared
  `RecommendationPaginationRuntime`.
- The pipeline now decodes and validates a signed, query-bound cursor before
  A/B assignment or recommendation/model work.
- The model path sorts the ranked window by `(score DESC, itemId ASC)`, delegates
  page assembly to `RecommendationPaginationCoordinator`, and returns its exact
  `nextCursor`/`hasMore` result.
- Added the explicit `RecommendationWindow(response, sourceTruncated)` contract.
  `OnnxInferencePipeline` no longer infers truncation from response length, and
  exposes `paginationBudgetExhausted=true` when the coordinator reaches the
  terminal page of a budget-truncated source.
- `RecommendationService` now retrieves and ranks with the configured internal
  candidate budget even though the public query limit remains capped at 100.
  Cached windows preserve truncation metadata.
- Cold-start pools are keyed by acquisition budget and use
  `max(coldStartMaxK, candidateBudget)`. An unknown user can therefore traverse
  a window beyond the legacy 100-item cold-start pool limit.
- Added a test-only signing key in `src/test/resources/application.properties`
  so full Spring test contexts satisfy the same fail-closed production wiring.

### Catalog metrics and scrape path (`6010`)

- `RecSysServer` now mounts Armeria's Prometheus exposition service at
  `/metrics` and configures Armeria with the same
  `PrometheusMeterRegistry` used by `RecommendationPaginationMetrics`.
- Added a real Armeria HTTP test that records a pagination event, scrapes
  `/metrics`, and observes the expected pagination series and labels.
- Added the catalog Service metadata label selected by a new ServiceMonitor.
- Added Prometheus ingress to the catalog NetworkPolicy on port 6010.
- Added the missing model Service label selected by its existing
  ServiceMonitor.

### Shared cursor-key operations

- Model serving now reads the same required active and optional previous Secret
  keys already used by catalog and online serving:
  `recsys-secrets/recommendation-cursor-signing-key` and
  `recsys-secrets/recommendation-cursor-previous-key`.
- Added `docs/runbooks/recommendation-cursor-key-rotation.md` with a safe
  multi-replica, multi-region two-stage rotation:
  `K1 active/K2 previous`, then `K2 active/K1 previous`, followed by a wait of
  at least the maximum cursor age plus safety margin before retiring `K1`.
- Updated the configuration guide, README, and pagination design documentation
  to require byte-identical key pairs across all three serving paths and every
  region.

### Cursor correctness and API cleanup

- Tuple seek now uses `Double.compare` consistently for both order validation
  and anchor seeking, including `+0.0` versus `-0.0`.
- Removed the public unsigned `RankedListCursor.encode/decode`, string-based
  pagination overload, and `Page` string cursor adapters.
- Retained unsigned `v2` decoding only inside
  `RecommendationCursorCodec`, guarded by
  `RECOMMENDATION_CURSOR_ACCEPT_LEGACY`.
- Replaced stale future-tense scalability wording with the implemented
  three-path signed pagination behavior.

## TDD and review evidence

The principal regressions were observed before their fixes:

- Signed zero: expected continuation `[b, c]`, but the raw `<` comparison
  returned only `[c]`.
- Spring HTTP path: the first response incorrectly reported no continuation,
  and a tampered cursor reached model work and produced `500` instead of the
  generic pre-work `400`.
- Internal candidate budget: the service lacked a window API capable of
  requesting more than the public 100-item limit.
- Catalog metrics: the production server had no `/metrics` registration seam.
- Cold start: the new regression requested 500 candidates but observed
  `ModelRetrievalStage.retrieve(..., 100)`.

Each regression passed after its corresponding production change. The final
reviewer's cold-start follow-up confirmed the explicit truncation contract,
budget-aware cache, exact acquisition budget, 100+50 continuation regression,
and budget-exhaustion metadata resolved the only blocking finding.

## Final verification

### Focused Java suite

```bash
mvn -q \
  -Dtest=CursorPaginationServiceTest,RecommendationPaginationCoordinatorTest,\
RecommendationCursorCodecTest,RecommendationPaginationMetricsTest,\
RecommendationCacheTest,RecommendationServiceTest,OnnxInferencePipelineTest,\
ModelV2RecommendIntegrationTest,CrossPathConsistencyTest,\
RecSysServerMetricsEndpointTest,RecSysServerCatalogWiringTest \
  test
```

Result: **71 tests, 0 failures, 0 errors, 0 skipped**.

This includes:

- 14 tuple-pagination tests, including signed zero;
- 13 signed cursor codec tests and 9 coordinator tests;
- 14 recommendation-service tests, including an unknown cold-start user that
  traverses 150 results as 100 plus 50 under a 500-candidate budget;
- 3 ONNX pipeline tests, including explicit budget-exhaustion propagation;
- 4 real Spring MockMvc model-endpoint tests;
- 6 cross-path contract tests;
- a real Armeria `/metrics` scrape test and 4 catalog wiring tests.

Mockito's committed inline mock maker was unchanged. The focused suite ran
outside the restricted sandbox because inline attachment and an ephemeral
localhost server socket require local process/socket capabilities.

### Kubernetes rendering

All deployable targets rendered successfully:

```bash
kubectl kustomize k8s/base
kubectl kustomize k8s/eks
kubectl kustomize k8s/eks-us-west-2
kubectl kustomize k8s/eks-us-west-2-active
```

The rendered base contains the shared active/previous Secret references on all
three serving Deployments, catalog and model Service labels, the catalog
`/metrics` ServiceMonitor, and monitoring ingress on port 6010.

### Static checks

- `mvn -q -DskipTests test-compile`: passed.
- `git diff --check`: passed before the implementation commit.
- Searches found no remaining public `RankedListCursor.encode/decode` or
  compatibility varargs adapter.
- Final independent review verdict: **APPROVE**, with no remaining Critical or
  Important findings.

## Remaining risks and limitations

- The ordinary full suite was not rerun in this remediation pass, per final
  review direction. The preceding project verification compiled and ran 1,308
  tests with 31 known errors caused only by missing generated model and Spark
  artifacts.
- A source that returns exactly the requested acquisition budget is
  conservatively marked budget-truncated. The important ambiguity is now
  explicit and preserved through caches and the serving response trace; no
  adapter guesses from a post-ranking response length.
- Unsigned `v2` cursor acceptance remains intentionally enabled in the base
  migration configuration. Operations must observe legacy-use metrics, disable
  it after at least one maximum cursor age, and remove the legacy decoder in a
  later cleanup.
- Safe key rotation still depends on the secret manager distributing identical
  key bytes to every path and region before each rollout stage; the new runbook
  makes that operational dependency explicit and testable through cross-region
  continuation checks.
- Docker-backed MySQL integration was outside this remediation scope and was
  not rerun.
