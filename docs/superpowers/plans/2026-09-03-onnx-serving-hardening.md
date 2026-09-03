# ONNX Model Serving Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make embedded ONNX serving artifact-consistent, contract-validated, correctly attributed under A/B fallback, bounded under overload, explicitly tuned, observable, and measurably exercised by load tests.

**Architecture:** Add a backward-compatible manifest/snapshot boundary before runtime construction, then validate and smoke-test each ONNX session before readiness. Keep control startup fail-fast while isolating experimental failures, bound recall work, and expose actual session runs so tests and operations measure inference rather than caches.

**Tech Stack:** Java 17, Spring Boot 3.3, ONNX Runtime Java 1.18, Jackson, Micrometer, JUnit 5, Mockito, AssertJ, Maven, k6, Kubernetes/Kustomize, Prometheus rules.

**Spec:** `docs/superpowers/specs/2026-09-03-onnx-serving-hardening-design.md`

## Global Constraints

- Existing manifest-less model bundles must continue to load and emit one warning per variant.
- Any present `model_manifest.json` is strict: malformed content, unsafe paths, inconsistency, or checksum failure must reject the variant without legacy fallback.
- Default/control runtime initialization failure remains fatal; non-default experiment failure degrades to control.
- No remote inference server, model registry, training pipeline, dependency upgrade, database migration, or recommendation response-schema change.
- Production defaults are ONNX intra-op `1`, inter-op `1`, `SEQUENTIAL`, and model request concurrency `8`.
- All behavior changes follow red-green TDD and each task ends in an independently reviewable commit.

---

## File Map

- `src/main/java/com/recsys/config/ModelServingProperties.java`: validated recall and ONNX execution settings.
- `src/main/java/com/recsys/application/model/ModelArtifactManifest.java`: immutable manifest schema and validation.
- `src/main/java/com/recsys/application/model/ModelArtifactSnapshot.java`: verified in-memory bytes and resolved model contract.
- `src/main/java/com/recsys/application/model/ModelArtifactLocator.java`: safe optional-manifest lookup and snapshot reads.
- `src/main/java/com/recsys/application/model/ModelArtifactService.java`: parse feature metadata from snapshot and expose the resolved model contract.
- `src/main/java/com/recsys/application/retrieval/UserTowerInferenceService.java`: configured session creation, metadata validation, smoke inference, run metrics, and lifecycle.
- `src/main/java/com/recsys/application/model/ModelRuntimeProvider.java`: control-first warmup, experiment isolation, bounded recall executor, and dependency wiring.
- `src/main/java/com/recsys/application/experiment/VariantRuntimeResolver.java`: externally record warmup failure and resolve loaded control without cold work.
- `src/main/java/com/recsys/application/model/OnnxInferencePipeline.java`: trace the actually served variant.
- `src/main/java/com/recsys/application/recommendation/RecommendationService.java`: overload fallback through loaded control cache.
- `src/main/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallService.java`: cancellable submissions and rejection/timeout recording.
- `src/main/java/com/recsys/application/retrieval/multichannel/RecallTaskMetrics.java`: bounded-label Micrometer metrics for channel execution outcomes.
- `src/main/resources/application.yml`, `CONFIG_GUIDE.md`: settings and artifact contract documentation.
- `k8s/base/model-serving.yaml`, `k8s/base/prometheus-rules.yaml`, `k8s/base/prometheus-rules.test.yaml`: production defaults and alert coverage.
- `src/test/**`: unit, integration, configuration, and load characterization coverage.
- `scripts/load-test/model-serving.js`: cache-independent ONNX scenario with counter-delta proof.

---

### Task 1: Add Validated Model-Serving Configuration

**Files:**
- Create: `src/main/java/com/recsys/config/ModelServingProperties.java`
- Create: `src/test/java/com/recsys/config/ModelServingPropertiesTest.java`
- Modify: `src/main/java/com/recsys/api/rest/ModelApplication.java:20-26`
- Modify: `src/main/resources/application.yml:42-51,79-92`
- Modify: `CONFIG_GUIDE.md:128-140`

**Interfaces:**
- Produces: `ModelServingProperties` with nested `Onnx` and `Recall` values.
- Produces: `ModelServingProperties.ExecutionMode { SEQUENTIAL, PARALLEL }`.
- Consumed later by `ModelRuntimeProvider` and `UserTowerInferenceService`.

- [ ] **Step 1: Write binding/default/validation tests**

```java
class ModelServingPropertiesTest {
    @Test void defaultsAreConservative() {
        ModelServingProperties p = new ModelServingProperties();
        assertThat(p.getOnnx().getIntraOpThreads()).isEqualTo(1);
        assertThat(p.getOnnx().getInterOpThreads()).isEqualTo(1);
        assertThat(p.getOnnx().getExecutionMode()).isEqualTo(SEQUENTIAL);
        assertThat(p.getRecall().getQueueCapacity()).isEqualTo(256);
        assertThat(p.getRecall().getTimeoutMs()).isEqualTo(200);
    }

    @Test void rejectsNonPositiveThreadAndQueueValues() {
        ModelServingProperties p = new ModelServingProperties();
        assertThatThrownBy(() -> p.getOnnx().setIntraOpThreads(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.getRecall().setQueueCapacity(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run the new test and verify it fails**

Run: `mvn -q -Dtest=ModelServingPropertiesTest test`

Expected: compilation failure because `ModelServingProperties` does not exist.

- [ ] **Step 3: Implement the configuration object and register it**

```java
@ConfigurationProperties(prefix = "recsys.model")
public class ModelServingProperties {
    private final Onnx onnx = new Onnx();
    private final Recall recall = new Recall();
    public enum ExecutionMode { SEQUENTIAL, PARALLEL }
    // setters reject values below one; recall coreThreads defaults to
    // Math.max(1, Runtime.getRuntime().availableProcessors() * 2)
}
```

Add `ModelServingProperties.class` to `@EnableConfigurationProperties` in `ModelApplication`. Add exact environment bindings:

```yaml
onnx:
  intra-op-threads: ${RECSYS_MODEL_ONNX_INTRA_OP_THREADS:1}
  inter-op-threads: ${RECSYS_MODEL_ONNX_INTER_OP_THREADS:1}
  execution-mode: ${RECSYS_MODEL_ONNX_EXECUTION_MODE:SEQUENTIAL}
recall:
  core-threads: ${RECSYS_MODEL_RECALL_CORE_THREADS:0}
  queue-capacity: ${RECSYS_MODEL_RECALL_QUEUE_CAPACITY:256}
  timeout-ms: ${RECSYS_MODEL_RECALL_TIMEOUT_MS:200}
```

Treat configured `core-threads=0` as the computed default; reject negative values.

- [ ] **Step 4: Run focused configuration tests**

Run: `mvn -q -Dtest=ModelServingPropertiesTest,ConfigurationBindingTest test`

Expected: all selected tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/config/ModelServingProperties.java \
  src/main/java/com/recsys/api/rest/ModelApplication.java \
  src/main/resources/application.yml CONFIG_GUIDE.md \
  src/test/java/com/recsys/config/ModelServingPropertiesTest.java
git commit -m "feat(model): configure ONNX and recall execution"
```

---

### Task 2: Load Strict Manifest-Backed Artifact Snapshots

**Files:**
- Create: `src/main/java/com/recsys/application/model/ModelArtifactManifest.java`
- Create: `src/main/java/com/recsys/application/model/ModelArtifactSnapshot.java`
- Create: `src/test/java/com/recsys/application/model/ModelArtifactManifestTest.java`
- Modify: `src/main/java/com/recsys/application/model/ModelArtifactLocator.java:42-80,131-166`
- Modify: `src/main/java/com/recsys/application/model/ModelArtifactService.java:12-60`
- Modify: `src/test/java/com/recsys/application/model/ModelArtifactLocatorTest.java`
- Modify: `src/test/java/com/recsys/application/model/ModelArtifactServiceTest.java`

**Interfaces:**
- Produces: `Optional<ModelArtifactSnapshot> ModelArtifactLocator.loadManifestSnapshot(String variant)`.
- Produces: `ModelArtifactSnapshot(byte[] featureConfig, byte[] model, String modelFile, String modelVersion, ModelContract contract)`.
- Produces: `ModelArtifactManifest.ModelContract` describing two inputs and one output.
- `ModelArtifactService.loadArtifacts()` consumes snapshot feature bytes when present and exposes `resolvedModelFile()`, `modelBytes()`, and `modelContract()`.

- [ ] **Step 1: Write manifest parsing and safety tests**

Create fixtures in `@TempDir` and test:

```java
@Test void validManifestReturnsVerifiedSnapshot() { /* assert bytes, filename, version, contract */ }
@Test void absentManifestReturnsEmptyForLegacyBundle() { /* assert Optional.empty() */ }
@Test void malformedPresentManifestDoesNotFallBack() { /* assert clear IllegalStateException */ }
@Test void rejectsUnsupportedSchemaVersion() { /* schema_version=2 */ }
@Test void rejectsTraversalAndAbsoluteModelPaths() { /* ../x.onnx and /x.onnx */ }
@Test void rejectsMissingAndMismatchedChecksums() { /* exact filename in message */ }
@Test void rejectsManifestFeatureVersionMismatch() { /* manifest v2, config v1 */ }
```

- [ ] **Step 2: Run manifest tests and verify they fail**

Run: `mvn -q -Dtest=ModelArtifactManifestTest test`

Expected: compilation failure for missing manifest/snapshot types.

- [ ] **Step 3: Implement schema validation and constant-time checksum comparison**

Use Jackson records or immutable classes. Normalize checksum keys as simple relative paths, calculate SHA-256 with `MessageDigest`, encode lowercase hex, and compare byte digests with `MessageDigest.isEqual`. Reject duplicate semantic input names, absent required inputs, unexpected required inputs, invalid rank, and unsupported tensor types.

```java
public record ModelContract(
        String userInput, String itemInput, TensorType inputType, int inputRank,
        String outputName, TensorType outputType, int outputRank) {
    public static ModelContract legacy() { return new ModelContract(
            "user_id", "item_id", INT64, 1, "score", FLOAT, 1); }
}
```

- [ ] **Step 4: Implement snapshot loading and integrate `ModelArtifactService`**

The locator must check `<variant>/model_manifest.json` only. If it exists, read all referenced files once from that same variant directory; do not use flat-root or classpath fallback for referenced files. If it does not exist, preserve existing lookup exactly and log one legacy warning per normalized variant from `ModelRuntimeProvider`.

- [ ] **Step 5: Run artifact regression tests**

Run: `mvn -q -Dtest=ModelArtifactManifestTest,ModelArtifactLocatorTest,ModelArtifactServiceTest,ModelArtifactServiceRedisTest test`

Expected: all selected tests pass, including existing legacy fixtures.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/application/model/ModelArtifactManifest.java \
  src/main/java/com/recsys/application/model/ModelArtifactSnapshot.java \
  src/main/java/com/recsys/application/model/ModelArtifactLocator.java \
  src/main/java/com/recsys/application/model/ModelArtifactService.java \
  src/test/java/com/recsys/application/model/ModelArtifactManifestTest.java \
  src/test/java/com/recsys/application/model/ModelArtifactLocatorTest.java \
  src/test/java/com/recsys/application/model/ModelArtifactServiceTest.java
git commit -m "feat(model): verify manifest-backed artifact snapshots"
```

---

### Task 3: Validate and Smoke-Test ONNX Sessions

**Files:**
- Modify: `src/main/java/com/recsys/application/retrieval/UserTowerInferenceService.java`
- Modify: `src/main/java/com/recsys/application/model/ModelRuntimeProvider.java:210-239`
- Modify: `src/test/java/com/recsys/application/retrieval/UserTowerInferenceServiceTest.java`
- Create: `src/test/java/com/recsys/application/retrieval/UserTowerInferenceContractTest.java`

**Interfaces:**
- `UserTowerInferenceService` constructor consumes model bytes, `ModelContract`, `ModelServingProperties.Onnx`, variant, and `MeterRegistry`.
- Produces: `long runCount()` for package-private characterization tests.
- Preserves: `score`, `scoreCandidates`, `isReady`, and idempotent `close` behavior.

- [ ] **Step 1: Write lifecycle and real-model contract tests**

```java
@Test void initValidatesMetadataAndRunsSmokeInference() {
    service.init();
    assertThat(service.isReady()).isTrue();
    assertThat(service.runCount()).isEqualTo(1);
}

@Test void closeClearsReadinessAndIsIdempotent() throws Exception {
    service.init(); service.close(); service.close();
    assertThat(service.isReady()).isFalse();
    assertThatThrownBy(() -> service.score(features, 1L))
            .isInstanceOf(IllegalStateException.class);
}
```

Use Mockito around a package-private session adapter to cover missing inputs, unexpected inputs, wrong dtype/rank, missing/wrong output, short batch output, and `NaN` without needing to generate malformed ONNX binaries.

- [ ] **Step 2: Run contract tests and verify failure**

Run: `mvn -q -Dtest=UserTowerInferenceServiceTest,UserTowerInferenceContractTest test`

Expected: failures because current init neither validates nor smokes, and close leaves readiness true.

- [ ] **Step 3: Introduce a narrow session adapter and transactional initialization**

```java
interface OnnxSessionHandle extends AutoCloseable {
    Map<String, NodeInfo> inputInfo();
    Map<String, NodeInfo> outputInfo();
    float[] run(String userInput, String itemInput, String output, long[] users, long[] items);
}
```

Keep the production adapter private/package-private in the same package. Create and close `OrtSession.SessionOptions` with try-with-resources, apply thread counts and execution mode, validate metadata, assign the session only after the smoke result is exactly one finite float, and close partial state on failure.

- [ ] **Step 4: Enforce runtime output contract and metrics**

Before returning scores, require `scores.length == itemIds.length` and every value finite. Increment a package-owned `AtomicLong` and Micrometer counter named `recsys.model.onnx.runs` immediately before each native run, tagged by normalized variant.

- [ ] **Step 5: Run ONNX and runtime-provider tests**

Run: `mvn -q -Dtest=UserTowerInferenceServiceTest,UserTowerInferenceContractTest,ModelRuntimeProviderTest,RankingStageTest test`

Expected: all selected tests pass against the bundled real ONNX model.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/application/retrieval/UserTowerInferenceService.java \
  src/main/java/com/recsys/application/model/ModelRuntimeProvider.java \
  src/test/java/com/recsys/application/retrieval/UserTowerInferenceServiceTest.java \
  src/test/java/com/recsys/application/retrieval/UserTowerInferenceContractTest.java
git commit -m "feat(model): validate ONNX sessions before readiness"
```

---

### Task 4: Make Variant Warmup Resilient and Attribution Correct

**Files:**
- Modify: `src/main/java/com/recsys/application/model/ModelRuntimeProvider.java:92-167`
- Modify: `src/main/java/com/recsys/application/experiment/VariantRuntimeResolver.java`
- Modify: `src/main/java/com/recsys/application/model/OnnxInferencePipeline.java:41-92`
- Modify: `src/test/java/com/recsys/application/model/ModelRuntimeProviderWarmUpTest.java`
- Modify: `src/test/java/com/recsys/application/experiment/VariantRuntimeResolverTest.java`
- Modify: `src/test/java/com/recsys/application/model/OnnxInferencePipelineTest.java`
- Modify: `src/test/java/com/recsys/application/recommendation/ProtectedRecommendationPipelineTest.java`

**Interfaces:**
- Produces: `VariantRuntimeResolver.recordLoadFailure(String variant, RuntimeException failure, String phase)`.
- `OnnxInferencePipeline` trace key `abTestVariant` becomes the actual `RecommendResponse.abTestVariant()`.

- [ ] **Step 1: Add failing warmup behavior tests**

```java
@Test void warmUpKeepsServingWhenTreatmentFailsButControlLoads() { /* no throw; control ready */ }
@Test void warmUpPropagatesControlFailure() { /* assert root cause */ }
@Test void warmUpRecordsTreatmentCooldownSoFirstRequestDoesNotRetry() { /* build count remains one */ }
```

- [ ] **Step 2: Add failing served-variant attribution test**

Stub `RecommendationService.recommendWindow` to return a response served by `training` for an assignment to `test`, then assert:

```java
assertThat(result.trace()).containsEntry("abTestVariant", "training");
verify(exposureLogger).log(userId, assignment, "training", true, modelVersion);
```

- [ ] **Step 3: Run tests and verify the old behavior fails**

Run: `mvn -q -Dtest=ModelRuntimeProviderWarmUpTest,VariantRuntimeResolverTest,OnnxInferencePipelineTest,ProtectedRecommendationPipelineTest test`

Expected: new treatment-failure and served-trace assertions fail.

- [ ] **Step 4: Implement control-first warmup and failure recording**

Normalize/deduplicate variants. Call `getRuntime(defaultVariant)` synchronously. Submit only distinct non-default variants to the warmup pool, catch each exception inside its future, call `recordLoadFailure`, and join all futures without propagating treatment failures. Register `recsys.model.runtime_load_failures` with bounded normalized variant and phase tags.

- [ ] **Step 5: Write actual served variant into the trace**

Replace the assignment argument to `toResult` with `response.abTestVariant()`, falling back to assignment only when the response value is null/blank for compatibility.

- [ ] **Step 6: Run focused tests**

Run: `mvn -q -Dtest=ModelRuntimeProviderWarmUpTest,VariantRuntimeResolverTest,OnnxInferencePipelineTest,ProtectedRecommendationPipelineTest test`

Expected: all selected tests pass and exposure fallback is true.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/application/model/ModelRuntimeProvider.java \
  src/main/java/com/recsys/application/experiment/VariantRuntimeResolver.java \
  src/main/java/com/recsys/application/model/OnnxInferencePipeline.java \
  src/test/java/com/recsys/application/model/ModelRuntimeProviderWarmUpTest.java \
  src/test/java/com/recsys/application/experiment/VariantRuntimeResolverTest.java \
  src/test/java/com/recsys/application/model/OnnxInferencePipelineTest.java \
  src/test/java/com/recsys/application/recommendation/ProtectedRecommendationPipelineTest.java
git commit -m "fix(model): isolate variant failure and report served model"
```

---

### Task 5: Use Loaded Control Cache During Overload Fallback

**Files:**
- Modify: `src/main/java/com/recsys/application/recommendation/RecommendationService.java:188-239`
- Modify: `src/main/java/com/recsys/application/recommendation/ProtectedRecommendationPipeline.java:79-83`
- Modify: `src/main/java/com/recsys/api/rest/RecommendationController.java`
- Modify: `src/test/java/com/recsys/application/recommendation/RecommendationServiceTest.java`
- Modify: `src/test/java/com/recsys/application/recommendation/ProtectedRecommendationPipelineTest.java`

**Interfaces:**
- Produces: `Optional<RecommendResponse> tryServeFromCache(RecommendRequest request, Assignment assignment, String defaultVariant)`.
- Must only call `ModelRuntimeProvider.getLoadedRuntime`; overload fallback may never call `getRuntime`.

- [ ] **Step 1: Write cache fallback tests**

Cover assigned cache hit, absent treatment plus control cache hit, absent both, and verify no cold build:

```java
verify(provider, never()).getRuntime(anyString());
assertThat(response).get().extracting(RecommendResponse::abTestVariant)
        .isEqualTo(defaultVariant);
```

- [ ] **Step 2: Run tests and verify control fallback fails**

Run: `mvn -q -Dtest=RecommendationServiceTest,ProtectedRecommendationPipelineTest test`

Expected: treatment-absent/control-cached case returns empty under existing code.

- [ ] **Step 3: Implement assigned-then-control cache resolution**

Extract a private `cachedResponseForRuntime` helper that uses the runtime variant and version for both recommendation and cold-start keys. Try assigned loaded runtime first; when absent and assignment differs from default, try loaded control. Thread the actual served variant through the returned response.

- [ ] **Step 4: Wire both v1 and v2 overload paths**

Ensure the controllers/wrapper return the cached response with `X-Served-From: degraded-cache` and preserve existing `Retry-After` behavior when neither cache is available.

- [ ] **Step 5: Run focused tests**

Run: `mvn -q -Dtest=RecommendationServiceTest,RecommendationControllerTest,ProtectedRecommendationPipelineTest test`

Expected: all selected tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/application/recommendation/RecommendationService.java \
  src/main/java/com/recsys/application/recommendation/ProtectedRecommendationPipeline.java \
  src/main/java/com/recsys/api/rest/RecommendationController.java \
  src/test/java/com/recsys/application/recommendation/RecommendationServiceTest.java \
  src/test/java/com/recsys/application/recommendation/ProtectedRecommendationPipelineTest.java
git commit -m "fix(model): use control cache for failed variants"
```

---

### Task 6: Bound and Cancel Recall Work

**Files:**
- Create: `src/main/java/com/recsys/application/retrieval/multichannel/RecallTaskMetrics.java`
- Create: `src/test/java/com/recsys/application/retrieval/multichannel/RecallTaskMetricsTest.java`
- Modify: `src/main/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallService.java:31-227`
- Modify: `src/main/java/com/recsys/application/model/ModelRuntimeProvider.java:169-207`
- Modify: `src/test/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallServiceTest.java`
- Modify: `src/test/java/com/recsys/application/model/ModelRuntimeProviderTest.java`

**Interfaces:**
- `RecallTaskMetrics.recordRejected(String channel)` and `recordTimeout(String channel)`.
- `MultiChannelRecallService` continues accepting `ExecutorService`; production supplies `ThreadPoolExecutor` with `ArrayBlockingQueue` and `AbortPolicy`.

- [ ] **Step 1: Write rejection, cancellation, and interruption tests**

Use a one-thread/one-slot executor and latches. Saturate worker and queue, then assert the third channel degrades immediately and records rejection. For timeout, block interruptibly, assert `Thread.currentThread().isInterrupted()` or caught `InterruptedException`, and verify timeout metrics.

- [ ] **Step 2: Run tests and verify timeout cancellation fails**

Run: `mvn -q -Dtest=MultiChannelRecallServiceTest,RecallTaskMetricsTest test`

Expected: current `orTimeout` completes exceptionally but the underlying task remains blocked.

- [ ] **Step 3: Replace detached `orTimeout` composition with retained task handles**

Submit `Future<ChannelResult>` tasks directly. Await each against a shared per-channel deadline, call `cancel(true)` on `TimeoutException`, and convert timeout/rejection to the same degraded `ChannelResult` used today. Preserve primary recall exceptions and thread interruption:

```java
catch (InterruptedException e) {
    task.cancel(true);
    Thread.currentThread().interrupt();
    throw new PrimaryRecallUnavailableException("Recall interrupted", e);
}
```

- [ ] **Step 4: Build the bounded production executor**

Construct `ThreadPoolExecutor(coreThreads, coreThreads, 0L, MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity), threadFactory, new AbortPolicy())`. Use configured timeout in `RecallConfig`. Preserve graceful shutdown.

- [ ] **Step 5: Run recall and recommendation regressions**

Run: `mvn -q -Dtest=MultiChannelRecallServiceTest,RecallTaskMetricsTest,ModelRuntimeProviderTest,RecommendationServiceTest test`

Expected: all selected tests pass; no test leaves a worker thread running.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/application/retrieval/multichannel/RecallTaskMetrics.java \
  src/main/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallService.java \
  src/main/java/com/recsys/application/model/ModelRuntimeProvider.java \
  src/test/java/com/recsys/application/retrieval/multichannel/RecallTaskMetricsTest.java \
  src/test/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallServiceTest.java \
  src/test/java/com/recsys/application/model/ModelRuntimeProviderTest.java
git commit -m "fix(model): bound and cancel recall channel work"
```

---

### Task 7: Add Production Defaults and Prometheus Alerts

**Files:**
- Modify: `k8s/base/model-serving.yaml:55-138`
- Modify: `k8s/base/prometheus-rules.yaml:66-99`
- Modify: `k8s/base/prometheus-rules.test.yaml`
- Modify: `k8s/base/configmap.yaml`
- Create: `src/test/java/com/recsys/infrastructure/k8s/ModelServingManifestTest.java`

**Interfaces:**
- Environment: `RECSYS_MODEL_ONNX_INTRA_OP_THREADS=1`, `RECSYS_MODEL_ONNX_INTER_OP_THREADS=1`, `RECSYS_MODEL_ONNX_EXECUTION_MODE=SEQUENTIAL`, `RECSYS_HEALTH_MAX_CONCURRENT_REQUESTS=8`, failure rate `0.05`, latency `500`.
- Metrics: `recsys_model_runtime_load_failures_total`, `recsys_load_shedder_requests_total`, `recsys_inference_*`, and ready replica kube-state metrics.

- [ ] **Step 1: Add failing rule cases before rules**

Add promtool fixtures for each alert at below-threshold, exact-boundary, sustained-above-threshold, and insufficient-traffic cases. Use a request-volume guard of at least 100 accepted plus rejected requests in ten minutes for rejection ratio.

- [ ] **Step 2: Run current rule tests and verify missing alerts fail**

Run: `cd k8s/base && promtool test rules prometheus-rules.test.yaml`

Expected: new expected alerts are absent.

- [ ] **Step 3: Add four alerts and conservative environment defaults**

Use these alert names: `ModelServingUnavailable`, `ModelServingShedding`, `ModelInferenceLatencyHigh`, and `ModelRuntimeLoadFailure`. Scope every query to the model-serving job/app and guard ratios against absent/zero denominators.

- [ ] **Step 4: Validate rendered Kubernetes manifests and alert rules**

Run: `kubectl kustomize k8s/base >/tmp/recsys-onnx-base.yaml && test -s /tmp/recsys-onnx-base.yaml`

Run: `cd k8s/base && promtool test rules prometheus-rules.test.yaml`

Expected: Kustomize renders, client validation succeeds, and all alert tests pass. If `kubectl` or `promtool` is unavailable, run the repository's containerized equivalent and record that command in the PR.

- [ ] **Step 5: Commit**

```bash
git add k8s/base/model-serving.yaml k8s/base/configmap.yaml \
  k8s/base/prometheus-rules.yaml k8s/base/prometheus-rules.test.yaml \
  src/test/java/com/recsys/infrastructure/k8s/ModelServingManifestTest.java
git commit -m "feat(model): alert on ONNX serving degradation"
```

---

### Task 8: Make Load Tests Prove ONNX Execution

**Files:**
- Modify: `src/test/java/com/recsys/application/model/InferenceLoadTest.java`
- Modify: `scripts/load-test/model-serving.js`
- Modify: `src/test/java/com/recsys/application/retrieval/UserTowerInferenceServiceTest.java` if the run counter helper needs direct coverage.

**Interfaces:**
- Consumes: `UserTowerInferenceService.runCount()` and Prometheus `recsys_model_onnx_runs_total`.
- Produces: separate k6 scenarios/tags `onnx_inference` and `cache_behavior`.

- [ ] **Step 1: Write a characterization assertion that fails on cached requests**

Disable both caches with `RecommendationCacheProperties`, use known model users, vary exclusion lists per request, record `runCount` immediately before the timed section, and assert the delta is at least the successful uncached request count.

- [ ] **Step 2: Run the load test and verify the old setup fails the run assertion**

Run: `mvn --batch-mode -Dtest=InferenceLoadTest -DexcludedGroups= -Dgroups=load test`

Expected: the new run-count assertion exposes the prior cache-only path until setup is corrected.

- [ ] **Step 3: Correct Java load setup and retain characterization reporting**

Remove universal hardware claims from latency assertions. Keep a generous timeout/success safety gate, print average/P95/RPS, and assert actual ONNX run count. Always shut down the executor in `finally` and await termination.

- [ ] **Step 4: Split k6 inference and cache scenarios**

In `setup()`, fetch `/actuator/prometheus`, parse the sum of `recsys_model_onnx_runs_total`, and return it. The inference scenario uses only known users plus rotating exclusions; the cache scenario deliberately repeats stable requests. In `teardown(data)`, refetch and throw when the counter did not increase. Tag thresholds per scenario.

- [ ] **Step 5: Run Java load verification and syntax-check k6**

Run: `mvn --batch-mode -Dtest=InferenceLoadTest -DexcludedGroups= -Dgroups=load test`

Run: `k6 inspect scripts/load-test/model-serving.js`

Expected: Java load test passes with positive run delta; k6 parses and lists both scenarios. If k6 is unavailable, use its official container image against the mounted script and document the command.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/recsys/application/model/InferenceLoadTest.java \
  scripts/load-test/model-serving.js
git commit -m "test(model): measure real ONNX inference under load"
```

---

### Task 9: Document Artifact Publication and Reconcile Stale Guidance

**Files:**
- Modify: `README.md:203-213,352-369`
- Modify: `CONFIG_GUIDE.md`
- Create: `docs/runbooks/model-artifact-rollout.md`
- Modify: `.gitignore:25-36` only if its comments conflict with verified tracked assets.

**Interfaces:**
- Documents: manifest schema, legacy warning, immutable generation layout, validation failure behavior, rollout, rollback, metrics, and exact local startup checks.

- [ ] **Step 1: Write documentation against verified repository state**

State explicitly that the root demo ONNX model and two variant feature configs are tracked, while production bundles require pipeline-produced compatible artifacts. Include this exact layout:

```text
<artifact-root>/releases/<generation>/<variant>/
  model_manifest.json
  feature_config.json
  dssm_model.onnx
current -> releases/<generation>
```

Document that the symlink switches between deployments and files under `current` are never edited in place.

- [ ] **Step 2: Add manifest generation/verification examples**

Include portable SHA-256 commands for Linux and macOS, a complete version-one manifest example, expected startup error categories, and rollback to the previous generation.

- [ ] **Step 3: Check documentation links and stale claims**

Run: `rg -n 'does not track|no acquisition|model_manifest|RECSYS_MODEL_ONNX|RECSYS_MODEL_RECALL' README.md CONFIG_GUIDE.md docs/runbooks/model-artifact-rollout.md .gitignore`

Run: `git ls-files src/main/resources/dssm_model.onnx src/main/resources/artifacts/model`

Expected: no claim says the tracked demo files are absent; configuration names match Task 1.

- [ ] **Step 4: Commit**

```bash
git add README.md CONFIG_GUIDE.md docs/runbooks/model-artifact-rollout.md .gitignore
git commit -m "docs: define immutable model artifact rollout"
```

---

### Task 10: Integrated Verification and PR Preparation

**Files:**
- Modify only files required by failures attributable to Tasks 1-9.

**Interfaces:**
- Produces: a verified branch and PR description with compatibility/rollout notes.

- [ ] **Step 1: Run focused model-serving regression tests**

Run:

```bash
mvn --batch-mode -Dtest='ModelServingPropertiesTest,ModelArtifactManifestTest,ModelArtifactLocatorTest,ModelArtifactServiceTest,ModelArtifactServiceRedisTest,UserTowerInferenceServiceTest,UserTowerInferenceContractTest,ModelRuntimeProviderTest,ModelRuntimeProviderWarmUpTest,VariantRuntimeResolverTest,OnnxInferencePipelineTest,ProtectedRecommendationPipelineTest,RecommendationServiceTest,RecommendationControllerTest,MultiChannelRecallServiceTest,RecallTaskMetricsTest,RankingStageTest' test
```

Expected: zero failures and errors.

- [ ] **Step 2: Run the full default Maven suite**

Run: `mvn --batch-mode test`

Expected: `BUILD SUCCESS` with zero failures/errors. If a known environment-dependent test is excluded by the existing build, list it rather than changing exclusions.

- [ ] **Step 3: Run load, manifest, and deployment verification**

Run:

```bash
mvn --batch-mode -Dtest=InferenceLoadTest -DexcludedGroups= -Dgroups=load test
k6 inspect scripts/load-test/model-serving.js
kubectl kustomize k8s/base >/tmp/recsys-onnx-base.yaml
test -s /tmp/recsys-onnx-base.yaml
(cd k8s/base && promtool test rules prometheus-rules.test.yaml)
git diff --check
git status --short
```

Expected: load test reports a positive ONNX-run delta, k6 lists both scenarios, rendered YAML is nonempty, all rule tests pass, diff check emits nothing, and status contains only intended branch changes.

- [ ] **Step 4: Run a Docker model startup smoke test**

Create a temporary shell script with a cleanup trap, then run these commands:

```bash
docker build -t recsys-model-serving:onnx-hardening .
docker run --rm -d --name recsys-model-onnx-smoke -p 18080:8080 \
  -e RECSYS_MAIN_CLASS=com.recsys.api.rest.ModelApplication \
  -e SERVER_PORT=8080 \
  -e REDIS_ALLOW_NO_AUTH=true \
  -e RECOMMENDATION_CURSOR_SIGNING_KEY=0123456789abcdef0123456789abcdef \
  recsys-model-serving:onnx-hardening
for attempt in $(seq 1 36); do
  curl -fsS http://localhost:18080/health/ready && break
  sleep 5
done
curl -fsS -H 'Content-Type: application/json' \
  -d '{"userId":"123","limit":10}' http://localhost:18080/v2/recommend
docker stop recsys-model-onnx-smoke
```

Expected: readiness returns `200`, the recommendation response contains ranked items, and the container exits cleanly. If Docker is unavailable, record the exact daemon error in the PR without claiming container verification.

- [ ] **Step 5: Request code review and address findings**

Invoke `superpowers:requesting-code-review`; review the complete branch diff against the approved spec. Apply accepted corrections through red-green tests and rerun affected plus full verification.

- [ ] **Step 6: Finish the branch and create the PR**

Invoke `superpowers:finishing-a-development-branch`. Push `feat/onnx-serving-hardening` and create a PR whose description contains:

```markdown
## Summary
- validates manifest-backed ONNX artifacts and session contracts before readiness
- isolates experimental runtime failure while reporting the model actually served
- bounds recall work and adds conservative ONNX execution settings
- makes alerts and load tests prove real inference behavior

## Compatibility
Legacy bundles continue to load with a warning. Present manifests are strict.

## Rollout
Deploy the compatible reader, canary an immutable manifest-backed generation,
verify load/fallback/run metrics, then promote it to control.

## Verification
- `mvn --batch-mode test` — BUILD SUCCESS, with the observed test count
- tagged load test — positive ONNX run delta and observed latency summary
- k6/Kustomize/promtool — exact pass results
- Docker smoke test — readiness and recommendation result, or the exact unavailable-daemon error
```

Expected: PR URL returned and working tree clean.
