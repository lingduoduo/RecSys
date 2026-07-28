# `/v2/recommend` Protection Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the `/v2/recommend` routes — the ones the canonical `POST /api/recommend` actually reaches — the request-tier protections that today only `/api/v1/recommend` has.

**Architecture:** On 8080, a `ProtectedRecommendationPipeline` decorator implements `RecommendationPipeline` and wraps the existing `onnxRecommendationPipeline` bean, holding the rate limiter, load shedder, inference metrics, and A/B exposure logging. It throws the same exceptions `/api/v1/recommend` throws, so `GlobalExceptionHandler` maps them to 429/503 with no new code and the controller needs no edit. On 7010, `/v2/recommend` is wrapped in the existing `OnlineAdmissionControl`, matching `/online/recommendation` beside it.

**Tech Stack:** Java 17, Spring Boot, Armeria 1.28.4, JUnit 5, AssertJ, Micrometer, Maven.

**Spec:** [2026-07-27-v2-recommend-protection-parity-design.md](../specs/2026-07-27-v2-recommend-protection-parity-design.md)

## Global Constraints

- JDK 17 required: prefix every Maven command with `JAVA_HOME=$(/usr/libexec/java_home -v 17)`.
- Surefire's multi-class separator in this repo is a **comma**: `-Dtest='A,B'`. `+` does not work.
- **Mockito IS available**, transitively via `spring-boot-starter-test` — `ModelV2RecommendIntegrationTest` uses `@MockBean` and `org.mockito.Mockito`. Do not add it to `pom.xml`. Plain unit tests in this repo nonetheless often build real collaborators (`new LoadShedder(props, new SimpleMeterRegistry())`) or hand-written fakes; both styles are acceptable. Task 2 uses a hand-written fake deliberately, so the exposure assertions read as data rather than as verify() calls.
- **Ordering is load-bearing:** the per-user rate check runs BEFORE the load-shed semaphore, so one user cannot burn shared concurrency slots before being limited. This mirrors `RecommendationController` and must not be reordered.
- `RecommendationV2Controller` is **not modified** by any task.
- **`/v2/sequential/recommend` is deliberately NOT wrapped.** `SequentialRecommendationPipeline` is a stub that always throws `PipelineNotImplementedException` (→ `501`); wrapping it would record a failure metric per call and corrupt the `high failure rate` readiness signal this change exists to restore.
- The only edit to the V1 path is the `retryAfterSeconds` extraction in Task 1.
- **Every `src/main` behavior needs a test that goes RED when that behavior is reverted.** Verify by actually reverting, then restore. Report the result.
- Branch: `fix/v2-recommend-protection-parity`. Commit after every task.

---

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `src/main/java/com/recsys/application/recommendation/ProtectedRecommendationPipeline.java` | Request-tier guards around any `RecommendationPipeline`. No transport knowledge. |
| `src/test/java/com/recsys/application/recommendation/ProtectedRecommendationPipelineTest.java` | Guard behavior, ordering, permit release, trace fallbacks. |

**Modified:**

| File | Change |
|---|---|
| `src/main/java/com/recsys/metrics/InferenceMetricsService.java` | Gains `public static int retryAfterSeconds(Snapshot)`. |
| `src/main/java/com/recsys/api/rest/RecommendationController.java:139-146` | Deletes its private copy; calls the shared one. |
| `src/main/java/com/recsys/config/ModelRecommendationPipelineConfig.java` | Wraps the returned pipeline in the decorator. |
| `src/main/java/com/recsys/api/online/OnlinePredictionServer.java:220` | Wraps `/v2/recommend` in `OnlineAdmissionControl`. |
| `src/test/java/com/recsys/api/rest/ModelV2RecommendIntegrationTest.java` | v2 traffic moves metrics; sequential stays unwrapped. |
| `src/test/java/com/recsys/api/online/OnlineV2RecommendIntegrationTest.java` | v2 sheds to 503. |
| `docs/system_design/09_API_Gateway.md` | Sharp edge 6 currently asserts the gap this change closes. |
| `docs/system_design/18_Fault_Tolerance.md`, `docs/runbooks/overload-protection.md` | Record the readiness-signal change. |

---

### Task 1: Extract `retryAfterSeconds` to `InferenceMetricsService`

**Files:**
- Modify: `src/main/java/com/recsys/metrics/InferenceMetricsService.java`
- Modify: `src/main/java/com/recsys/api/rest/RecommendationController.java:139-146`
- Test: `src/test/java/com/recsys/metrics/InferenceMetricsServiceTest.java` (create if absent)

**Interfaces:**
- Produces: `public static int InferenceMetricsService.retryAfterSeconds(InferenceMetricsService.Snapshot metrics)` — used by Task 2.

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/recsys/metrics/InferenceMetricsServiceTest.java` (create the file with this content if it does not exist):

```java
package com.recsys.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.recsys.config.HealthProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InferenceMetricsServiceRetryAfterTest {

    @Test
    void retryAfterSeconds_returnsOneWhenSnapshotIsNull() {
        assertThat(InferenceMetricsService.retryAfterSeconds(null)).isEqualTo(1);
    }

    @Test
    void retryAfterSeconds_returnsOneWhenNoLatencyRecorded() {
        InferenceMetricsService svc =
                new InferenceMetricsService(new HealthProperties(), new SimpleMeterRegistry());
        assertThat(InferenceMetricsService.retryAfterSeconds(svc.snapshot())).isEqualTo(1);
    }

    @Test
    void retryAfterSeconds_roundsLatencyUpToWholeSeconds() {
        InferenceMetricsService svc =
                new InferenceMetricsService(new HealthProperties(), new SimpleMeterRegistry());
        svc.recordSuccess(2400L);   // 2.4s -> ceil -> 3
        assertThat(InferenceMetricsService.retryAfterSeconds(svc.snapshot())).isEqualTo(3);
    }

    @Test
    void retryAfterSeconds_isClampedToTenSeconds() {
        InferenceMetricsService svc =
                new InferenceMetricsService(new HealthProperties(), new SimpleMeterRegistry());
        svc.recordSuccess(60_000L);  // 60s -> clamped
        assertThat(InferenceMetricsService.retryAfterSeconds(svc.snapshot())).isEqualTo(10);
    }
}
```

Put this class in its own file `InferenceMetricsServiceRetryAfterTest.java` if `InferenceMetricsServiceTest.java` already exists — do not rewrite an existing test file.

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=InferenceMetricsServiceRetryAfterTest
```

Expected: FAIL — compilation error, `retryAfterSeconds` is not a member of `InferenceMetricsService`.

- [ ] **Step 3: Add the method to `InferenceMetricsService`**

Move the body verbatim from `RecommendationController`. Add near the other public methods:

```java
    /**
     * Retry-After hint derived from recent inference latency: back off for roughly one inference
     * cycle so clients do not pile up retries while the instance is still working through the
     * current batch. Shared by the V1 controller and the protected v2 pipeline so both paths give
     * callers the same guidance.
     */
    public static int retryAfterSeconds(Snapshot metrics) {
        if (metrics == null) return 1;
        double avgMs = metrics.recentAvgLatencyMs();
        if (avgMs > 0) {
            return Math.min(10, Math.max(1, (int) Math.ceil(avgMs / 1000.0)));
        }
        return 1;
    }
```

- [ ] **Step 4: Delete the private copy and update the caller**

In `RecommendationController`, delete the private `retryAfterSeconds` method (and the javadoc block above it) and change its one call site:

```java
            throw new ServiceOverloadedException(
                    InferenceMetricsService.retryAfterSeconds(metricsService.snapshot()));
```

`InferenceMetricsService` is already imported in that file.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='InferenceMetricsServiceRetryAfterTest,RecommendationControllerTest,RecommendationControllerRegressionTest'
```

Expected: PASS, including all pre-existing controller tests — the extraction must not change V1 behavior.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/metrics/InferenceMetricsService.java \
        src/main/java/com/recsys/api/rest/RecommendationController.java \
        src/test/java/com/recsys/metrics/InferenceMetricsServiceRetryAfterTest.java
git commit -m "refactor(metrics): share retryAfterSeconds between the V1 and v2 paths"
```

---

### Task 2: `ProtectedRecommendationPipeline`

**Files:**
- Create: `src/main/java/com/recsys/application/recommendation/ProtectedRecommendationPipeline.java`
- Test: `src/test/java/com/recsys/application/recommendation/ProtectedRecommendationPipelineTest.java`

**Interfaces:**
- Consumes: `InferenceMetricsService.retryAfterSeconds(Snapshot)` from Task 1.
- Consumes (existing): `ModelRateLimiter.tryAcquire(String)` → `Decision(boolean allowed, int limit, int remaining, Duration retryAfter)`; `LoadShedder.tryAcquire()` / `release()` / `snapshot()`; `InferenceMetricsService.recordSuccess(long, String, String)` / `recordFailure(long, String)` / `snapshot()`; `ABTestService.getAssignmentForUser(String)` → `Assignment(String variant, int slot, String layerName, boolean inExperiment)`; `AbExposureLogger.log(String userId, ABTestService.Assignment assignment, String servedVariant, boolean fellBack, String modelVersion)`.
- Produces: `public ProtectedRecommendationPipeline(RecommendationPipeline delegate, ModelRateLimiter rateLimiter, LoadShedder loadShedder, InferenceMetricsService metrics, ABTestService abTestService, AbExposureLogger exposureLogger)` — used by Task 3.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/application/recommendation/ProtectedRecommendationPipelineTest.java`:

```java
package com.recsys.application.recommendation;

import com.recsys.application.experiment.ABTestService;
import com.recsys.application.experiment.AbExposureLogger;
import com.recsys.config.ABTestConfig;
import com.recsys.config.HealthProperties;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.recommendation.RecommendationResult;
import com.recsys.exception.RateLimitExceededException;
import com.recsys.exception.ServiceOverloadedException;
import com.recsys.loadshed.LoadShedder;
import com.recsys.metrics.InferenceMetricsService;
import com.recsys.ratelimit.ModelRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtectedRecommendationPipelineTest {

    private static final RecommendationQuery QUERY = new RecommendationQuery("u1", 5, Set.of(), null);

    /** Records what the exposure logger was called with, since this repo has no mocking library. */
    private static final class RecordingExposureLogger extends AbExposureLogger {
        String userId; String servedVariant; boolean fellBack; String modelVersion; int calls;
        RecordingExposureLogger() { super(null, new ABTestConfig(), () -> "id", () -> 0L); }
        @Override
        public void log(String userId, ABTestService.Assignment assignment,
                        String servedVariant, boolean fellBack, String modelVersion) {
            this.userId = userId; this.servedVariant = servedVariant;
            this.fellBack = fellBack; this.modelVersion = modelVersion; this.calls++;
        }
    }

    private static RecommendationResult resultWithTrace(String variant, String modelVersion) {
        return new RecommendationResult("u1", List.of(), null, false,
                Map.of("abTestVariant", variant, "modelVersion", modelVersion));
    }

    private static LoadShedder shedder(int maxConcurrent) {
        HealthProperties props = new HealthProperties();
        props.setMaxConcurrentRequests(maxConcurrent);
        return new LoadShedder(props, new SimpleMeterRegistry());
    }

    private static InferenceMetricsService metrics() {
        return new InferenceMetricsService(new HealthProperties(), new SimpleMeterRegistry());
    }

    @Test
    void rateLimitDenialThrowsBeforeTouchingTheSemaphore() {
        // rps=1, burst=1 -> second call in the same instant is denied.
        ModelRateLimiter limiter = new ModelRateLimiter(1.0, 1, 100, () -> 0L);
        LoadShedder shedder = shedder(8);
        ProtectedRecommendationPipeline pipeline = new ProtectedRecommendationPipeline(
                q -> resultWithTrace("A", "v1"), limiter, shedder, metrics(),
                new ABTestService(new ABTestConfig()), new RecordingExposureLogger());

        pipeline.recommend(QUERY);   // consumes the only token

        assertThatThrownBy(() -> pipeline.recommend(QUERY))
                .isInstanceOf(RateLimitExceededException.class);

        // ORDERING: the limiter must run before the semaphore, so no permit was taken by the
        // denied call. All 8 permits must still be available.
        for (int i = 0; i < 8; i++) {
            assertThat(shedder.tryAcquire()).as("permit %d still free", i).isTrue();
        }
    }

    @Test
    void shedDenialThrowsOverloadedAndRecordsAFailure() {
        LoadShedder shedder = shedder(1);
        assertThat(shedder.tryAcquire()).isTrue();   // saturate it
        InferenceMetricsService metrics = metrics();
        ProtectedRecommendationPipeline pipeline = new ProtectedRecommendationPipeline(
                q -> resultWithTrace("A", "v1"), disabledLimiter(), shedder, metrics,
                new ABTestService(new ABTestConfig()), new RecordingExposureLogger());

        assertThatThrownBy(() -> pipeline.recommend(QUERY))
                .isInstanceOf(ServiceOverloadedException.class);
        assertThat(metrics.snapshot().failureCount()).isEqualTo(1);
    }

    @Test
    void successRecordsMetricsFromTheTraceAndLogsExposure() {
        InferenceMetricsService metrics = metrics();
        RecordingExposureLogger exposure = new RecordingExposureLogger();
        ProtectedRecommendationPipeline pipeline = new ProtectedRecommendationPipeline(
                q -> resultWithTrace("B", "model-7"), disabledLimiter(), shedder(4), metrics,
                new ABTestService(new ABTestConfig()), exposure);

        RecommendationResult result = pipeline.recommend(QUERY);

        assertThat(result.trace()).containsEntry("abTestVariant", "B");
        assertThat(metrics.snapshot().successCount()).isEqualTo(1);
        assertThat(exposure.calls).isEqualTo(1);
        assertThat(exposure.userId).isEqualTo("u1");
        assertThat(exposure.servedVariant).isEqualTo("B");
        assertThat(exposure.modelVersion).isEqualTo("model-7");
    }

    @Test
    void delegateFailureRecordsFailureAndRethrows() {
        InferenceMetricsService metrics = metrics();
        ProtectedRecommendationPipeline pipeline = new ProtectedRecommendationPipeline(
                q -> { throw new IllegalStateException("boom"); },
                disabledLimiter(), shedder(4), metrics,
                new ABTestService(new ABTestConfig()), new RecordingExposureLogger());

        assertThatThrownBy(() -> pipeline.recommend(QUERY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
        assertThat(metrics.snapshot().failureCount()).isEqualTo(1);
    }

    @Test
    void permitIsReleasedOnBothSuccessAndFailure() {
        LoadShedder shedder = shedder(1);
        ProtectedRecommendationPipeline ok = new ProtectedRecommendationPipeline(
                q -> resultWithTrace("A", "v1"), disabledLimiter(), shedder, metrics(),
                new ABTestService(new ABTestConfig()), new RecordingExposureLogger());
        ok.recommend(QUERY);
        assertThat(shedder.tryAcquire()).as("released after success").isTrue();
        shedder.release();

        AtomicBoolean threw = new AtomicBoolean();
        ProtectedRecommendationPipeline boom = new ProtectedRecommendationPipeline(
                q -> { throw new IllegalStateException("boom"); },
                disabledLimiter(), shedder, metrics(),
                new ABTestService(new ABTestConfig()), new RecordingExposureLogger());
        try { boom.recommend(QUERY); } catch (IllegalStateException e) { threw.set(true); }
        assertThat(threw).isTrue();
        assertThat(shedder.tryAcquire()).as("released after failure").isTrue();
    }

    @Test
    void missingTraceValuesFallBackInsteadOfFailingTheRequest() {
        InferenceMetricsService metrics = metrics();
        RecordingExposureLogger exposure = new RecordingExposureLogger();
        ProtectedRecommendationPipeline pipeline = new ProtectedRecommendationPipeline(
                q -> new RecommendationResult("u1", List.of(), null, false, Map.of()),
                disabledLimiter(), shedder(4), metrics, new ABTestService(new ABTestConfig()), exposure);

        pipeline.recommend(QUERY);   // must not throw

        assertThat(metrics.snapshot().successCount()).isEqualTo(1);
        // No served variant in the trace -> fall back to the assignment, and claim no fallback.
        assertThat(exposure.fellBack).isFalse();
        assertThat(exposure.modelVersion).isEmpty();
    }

    @Test
    void assignmentIsDeterministicSoFellBackFromIsTrustworthy() {
        // fellBackFrom is derived by recomputing the assignment. If this ever stops holding,
        // exposure events would be silently wrong — so pin it.
        ABTestService abTestService = new ABTestService(new ABTestConfig());
        String first = abTestService.getAssignmentForUser("stable-user").variant();
        for (int i = 0; i < 20; i++) {
            assertThat(abTestService.getAssignmentForUser("stable-user").variant()).isEqualTo(first);
        }
    }

    private static ModelRateLimiter disabledLimiter() {
        return new ModelRateLimiter(0.0, 0, 100, () -> 0L);   // rps=0 -> disabled -> always allowed
    }
}
```

Note: `RecommendationQuery`, `RecommendationResult`, `ABTestService`, and `AbExposureLogger` constructor shapes must be confirmed against the real classes before running — adjust the fixtures if a signature differs, and record the adjustment in your report. Do NOT change production code to fit the test.

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=ProtectedRecommendationPipelineTest
```

Expected: FAIL — compilation error, `ProtectedRecommendationPipeline` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/recsys/application/recommendation/ProtectedRecommendationPipeline.java`:

```java
package com.recsys.application.recommendation;

import com.recsys.application.experiment.ABTestService;
import com.recsys.application.experiment.AbExposureLogger;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.recommendation.RecommendationResult;
import com.recsys.exception.RateLimitExceededException;
import com.recsys.exception.ServiceOverloadedException;
import com.recsys.loadshed.LoadShedder;
import com.recsys.metrics.InferenceMetricsService;
import com.recsys.ratelimit.ModelRateLimiter;

import java.util.Map;
import java.util.Objects;

/**
 * Request-tier guards around any {@link RecommendationPipeline}.
 *
 * <p>Exists because the canonical {@code POST /api/recommend} routes to {@code /v2/recommend},
 * which was a bare pipeline passthrough while {@code /api/v1/recommend} carried the limiter,
 * shedder, metrics, and exposure logging. Wrapping the pipeline rather than duplicating the
 * guards in a second controller keeps one copy of the ordering rule below.
 *
 * <p>Throws the same exceptions the V1 controller throws, so {@code GlobalExceptionHandler}
 * maps them to 429 and 503 without any new mapping code.
 */
public final class ProtectedRecommendationPipeline implements RecommendationPipeline {

    private static final String TRACE_VARIANT = "abTestVariant";
    private static final String TRACE_MODEL_VERSION = "modelVersion";

    private final RecommendationPipeline delegate;
    private final ModelRateLimiter rateLimiter;
    private final LoadShedder loadShedder;
    private final InferenceMetricsService metrics;
    private final ABTestService abTestService;
    private final AbExposureLogger exposureLogger;

    public ProtectedRecommendationPipeline(RecommendationPipeline delegate,
                                           ModelRateLimiter rateLimiter,
                                           LoadShedder loadShedder,
                                           InferenceMetricsService metrics,
                                           ABTestService abTestService,
                                           AbExposureLogger exposureLogger) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.loadShedder = Objects.requireNonNull(loadShedder, "loadShedder");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.abTestService = Objects.requireNonNull(abTestService, "abTestService");
        this.exposureLogger = Objects.requireNonNull(exposureLogger, "exposureLogger");
    }

    @Override
    public RecommendationResult recommend(RecommendationQuery query) {
        long startNs = System.nanoTime();

        // The per-user rate check runs BEFORE the shared semaphore so a single user cannot burn
        // concurrency slots other users need before being limited. Same ordering, and same
        // reason, as RecommendationController. Do not reorder.
        ModelRateLimiter.Decision decision = rateLimiter.tryAcquire(query.userId());
        if (!decision.allowed()) {
            int retryAfter = Math.max(1, (int) Math.ceil(decision.retryAfter().toMillis() / 1000.0));
            throw new RateLimitExceededException(retryAfter);
        }

        // Recomputed rather than threaded out of the delegate: assignment is deterministic hash
        // bucketing, so this yields the same variant the pipeline used, without coupling the
        // pipeline to Kafka. ProtectedRecommendationPipelineTest pins that determinism.
        ABTestService.Assignment assignment = abTestService.getAssignmentForUser(query.userId());

        if (!loadShedder.tryAcquire()) {
            metrics.recordFailure(0L, assignment.variant());
            throw new ServiceOverloadedException(
                    InferenceMetricsService.retryAfterSeconds(metrics.snapshot()));
        }
        try {
            RecommendationResult result = delegate.recommend(query);

            // The delegate is typed as RecommendationPipeline, so the trace keys are a convention
            // of OnnxInferencePipeline, not a guarantee of the interface. Never fail an otherwise
            // successful request because a key was absent.
            String servedVariant = trace(result, TRACE_VARIANT);
            String modelVersion = trace(result, TRACE_MODEL_VERSION);
            boolean known = !servedVariant.isBlank();
            String effectiveVariant = known ? servedVariant : assignment.variant();
            boolean fellBack = known && !effectiveVariant.equals(assignment.variant());

            metrics.recordSuccess(elapsedMs(startNs), effectiveVariant, modelVersion);
            exposureLogger.log(query.userId(), assignment, effectiveVariant, fellBack, modelVersion);
            return result;
        } catch (RuntimeException e) {
            metrics.recordFailure(elapsedMs(startNs), assignment.variant());
            throw e;
        } finally {
            loadShedder.release();
        }
    }

    private static String trace(RecommendationResult result, String key) {
        Map<String, String> trace = result == null ? null : result.trace();
        if (trace == null) return "";
        String value = trace.get(key);
        return value == null ? "" : value;
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=ProtectedRecommendationPipelineTest
```

Expected: PASS, 7 tests.

- [ ] **Step 5: Revert-check the ordering guarantee**

Temporarily move the `rateLimiter.tryAcquire` block to AFTER the `loadShedder.tryAcquire` block. Run `rateLimitDenialThrowsBeforeTouchingTheSemaphore` and confirm it goes RED. Restore, confirm green, and confirm `git diff` on the file is empty. Report the observed failure message.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/application/recommendation/ProtectedRecommendationPipeline.java \
        src/test/java/com/recsys/application/recommendation/ProtectedRecommendationPipelineTest.java
git commit -m "feat(model): add ProtectedRecommendationPipeline request-tier guards"
```

---

### Task 3: Wire the decorator on 8080

**Files:**
- Modify: `src/main/java/com/recsys/config/ModelRecommendationPipelineConfig.java`
- Test: `src/test/java/com/recsys/api/rest/ModelV2RecommendIntegrationTest.java`

**Interfaces:**
- Consumes: `ProtectedRecommendationPipeline`'s constructor from Task 2.
- Produces: the `onnxRecommendationPipeline` bean now resolves to the protected wrapper.

- [ ] **Step 1: Write the failing tests**

Append to `ModelV2RecommendIntegrationTest`. That class is `@SpringBootTest @AutoConfigureMockMvc`
with `@MockBean RecommendationService` / `ABTestService` / `ModelRuntimeProvider`. Add one field and
two tests, matching the stubbing style its existing tests already use:

```java
    @Autowired InferenceMetricsService metricsService;

    @Test
    void v2RecommendTrafficIsVisibleToInferenceMetrics() throws Exception {
        // The point of the change: /health/ready computes "high failure rate" and "high inference
        // latency" from this snapshot, and was blind to canonical-path traffic.
        when(abTestService.getAssignmentForUser("u1")).thenReturn(
                new ABTestService.Assignment("training", 0, "default", true));
        when(recommendationService.recommendWindow(
                any(RecommendRequest.class), any(), anyInt())).thenReturn(
                new RecommendationWindow(
                        new RecommendResponse("u1", "v1.0", "training",
                                List.of(new ScoredItem("42", 0.9))),
                        false));

        long before = metricsService.snapshot().successCount();

        mockMvc.perform(post("/v2/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\",\"limit\":5,\"excludedItemIds\":[],\"cursor\":null}"))
                .andExpect(status().isOk());

        assertThat(metricsService.snapshot().successCount()).isEqualTo(before + 1);
    }

    @Test
    void sequentialRouteStaysUnwrappedAndRecordsNoFailureMetric() throws Exception {
        // SequentialRecommendationPipeline is a stub that always throws 501. It is deliberately
        // NOT wrapped: wrapping it would record a failure per call and feed the high-failure-rate
        // readiness signal this change exists to restore. If someone wraps it later, this fails.
        long failuresBefore = metricsService.snapshot().failureCount();

        mockMvc.perform(post("/v2/sequential/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\",\"limit\":5,\"excludedItemIds\":[],\"cursor\":null}"))
                .andExpect(status().isNotImplemented());

        assertThat(metricsService.snapshot().failureCount()).isEqualTo(failuresBefore);
    }
```

Add imports `com.recsys.metrics.InferenceMetricsService` and
`static org.assertj.core.api.Assertions.assertThat`. The before/after deltas matter: `@SpringBootTest`
shares one context across the class, so metrics accumulate across tests — never assert absolutes.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=ModelV2RecommendIntegrationTest
```

Expected: `v2RecommendTrafficIsVisibleToInferenceMetrics` FAILS (successes unchanged — nothing records). The sequential test may already pass; that is fine, it is a regression pin, and Step 5 verifies it can fail.

- [ ] **Step 3: Wire the decorator**

In `ModelRecommendationPipelineConfig`, wrap the returned pipeline. Add the new parameters to the bean method — Spring injects them, all are existing beans:

```java
    @Bean("onnxRecommendationPipeline")
    RecommendationPipeline onnxRecommendationPipeline(
            RecommendationService recommendationService,
            ABTestService abTestService,
            Environment environment,
            MeterRegistry registry,
            ModelRateLimiter rateLimiter,
            LoadShedder loadShedder,
            InferenceMetricsService metrics,
            AbExposureLogger exposureLogger
    ) {
        RecommendationPaginationRuntime pagination =
                RecommendationPaginationRuntime.fromEnvironment(
                        environment::getProperty, registry, Clock.systemUTC());
        RecommendationPipeline onnx = new OnnxInferencePipeline(
                recommendationService,
                abTestService,
                pagination.coordinator(),
                pagination.maxCandidates());
        // The canonical POST /api/recommend reaches this bean via /v2/recommend, so the guards
        // belong here rather than in the controller. /v2/sequential/recommend is deliberately
        // NOT wrapped — see the spec.
        return new ProtectedRecommendationPipeline(
                onnx, rateLimiter, loadShedder, metrics, abTestService, exposureLogger);
    }
```

Add the imports: `com.recsys.application.recommendation.ProtectedRecommendationPipeline`, `com.recsys.ratelimit.ModelRateLimiter`, `com.recsys.loadshed.LoadShedder`, `com.recsys.metrics.InferenceMetricsService`, `com.recsys.application.experiment.AbExposureLogger`.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='ModelV2RecommendIntegrationTest,RecommendationControllerTest,SequentialStubIntegrationTest'
```

Expected: PASS, including all pre-existing tests in those classes.

- [ ] **Step 5: Revert-check both new tests**

1. Remove the `ProtectedRecommendationPipeline` wrap (return `onnx` directly). Confirm `v2RecommendTrafficIsVisibleToInferenceMetrics` goes RED. Restore.
2. Wrap the sequential pipeline too (in `RecommendationV2Controller`, or by making it a wrapped bean). Confirm `sequentialRouteStaysUnwrappedAndRecordsNoFailureMetric` goes RED. Restore.

Confirm `git diff` is clean afterwards and report both observed failures.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/config/ModelRecommendationPipelineConfig.java \
        src/test/java/com/recsys/api/rest/ModelV2RecommendIntegrationTest.java
git commit -m "feat(model): protect the canonical /v2/recommend path"
```

---

### Task 4: Wrap `/v2/recommend` on 7010

**Files:**
- Modify: `src/main/java/com/recsys/api/online/OnlinePredictionServer.java:220`
- Test: `src/test/java/com/recsys/api/online/OnlineV2RecommendIntegrationTest.java`

**Interfaces:**
- Consumes (existing): `OnlineAdmissionControl(HttpService delegate, OnlineLoadShedder loadShedder, OnlineServingMetricsService metricsService)`.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the failing test**

`OnlineV2RecommendIntegrationTest` builds its own `ServerExtension` that registers `/v2/recommend`
**bare**, mirroring production. Update the harness to mirror the wrapped production form, then add
the test.

First, add two static fields and wrap the route in `configure`:

```java
    static final OnlineLoadShedder LOAD_SHEDDER = new OnlineLoadShedder(1, 0.9);
    static final OnlineServingMetricsService METRICS = new OnlineServingMetricsService();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            RecommendationPipeline pipeline =
                    new OnlineBlendingPipeline(mockService, pagination(), MAX_CANDIDATES);
            // Mirrors OnlinePredictionServer: /v2/recommend is admission-controlled like
            // /online/recommendation beside it.
            sb.service("/v2/recommend",
                    new OnlineAdmissionControl(
                            new OnlineServices.RecommendV2(pipeline), LOAD_SHEDDER, METRICS));
        }
    };
```

Then append the test:

```java
    @Test
    void v2RecommendShedsWhenTheShedderIsSaturated() throws Exception {
        // /online/recommendation has been admission-controlled all along; /v2/recommend was not,
        // so the canonical POST /api/recommend path had unbounded concurrency on this service.
        assertThat(LOAD_SHEDDER.tryAcquire()).isTrue();   // maxConcurrentRequests = 1 -> saturated
        try {
            String body = MAPPER.writeValueAsString(
                    new RecommendationQuery("1", 5, Set.of(), null));
            AggregatedHttpResponse r = server.blockingWebClient()
                    .execute(HttpRequest.of(
                            RequestHeaders.of(
                                    HttpMethod.POST, "/v2/recommend",
                                    HttpHeaderNames.CONTENT_TYPE, "application/json"),
                            HttpData.ofUtf8(body)));

            assertThat(r.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        } finally {
            LOAD_SHEDDER.release();
        }
    }
```

Add imports `com.recsys.loadshed.OnlineAdmissionControl`, `com.recsys.loadshed.OnlineLoadShedder`,
and `com.recsys.metrics.OnlineServingMetricsService`.

**Run this test class in isolation when checking it** — the shared static shedder has a capacity of
1, so a saturated permit leaking would fail sibling tests. The `finally` release prevents that; keep it.

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=OnlineV2RecommendIntegrationTest
```

Expected: FAIL — `/v2/recommend` returns `200` because nothing sheds it.

- [ ] **Step 3: Wrap the route**

In `OnlinePredictionServer`, change the `/v2/recommend` registration. Keep the existing pipeline-name comment above it:

```java
              // "v2" is the PIPELINE name (recall -> rank -> hydrate -> paginate), not API version 2.
              // API versions live only at the gateway edge as /api/v{n} — see docs/api-compatibility-policy.md.
              // Admission-controlled to match /online/recommendation above: the canonical
              // POST /api/recommend reaches this route, so it must not have unbounded concurrency.
              .service("/v2/recommend",
                      new OnlineAdmissionControl(
                              new OnlineServices.RecommendV2(blendingPipeline),
                              loadShedder, metricsService))
```

`OnlineAdmissionControl`, `loadShedder`, and `metricsService` are all already in scope in that method — the two routes above use exactly this form.

- [ ] **Step 4: Run the test to verify it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='OnlineV2RecommendIntegrationTest,OnlinePredictionServerIntegrationTest,OnlinePredictionRegressionTest'
```

Expected: PASS, including all pre-existing tests in those classes.

- [ ] **Step 5: Revert-check**

Remove the `OnlineAdmissionControl` wrap (back to the bare `new OnlineServices.RecommendV2(...)`). Confirm the new test goes RED. Restore, confirm green, confirm `git diff` clean. Report the observed failure.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/api/online/OnlinePredictionServer.java \
        src/test/java/com/recsys/api/online/OnlineV2RecommendIntegrationTest.java
git commit -m "fix(online): admission-control /v2/recommend to match /online/recommendation"
```

---

### Task 5: Documentation

**Files:**
- Modify: `docs/system_design/09_API_Gateway.md` (sharp edge 6)
- Modify: `docs/system_design/18_Fault_Tolerance.md`
- Modify: `docs/runbooks/overload-protection.md`

**Interfaces:**
- Consumes: nothing. Documentation only.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Rewrite `09_API_Gateway.md` sharp edge 6**

It currently asserts the gap this change closes. Replace it in full with:

```markdown
6. **`/v2` is a pipeline name, and it is now protected like V1.** The canonical
   `POST /api/recommend` with the default `model` strategy forwards to `/v2/recommend` on 8080,
   which used to be a bare `pipeline.recommend(query)` passthrough while `/api/v1/recommend`
   carried every request-tier control. Both paths now share those controls:
   `ProtectedRecommendationPipeline` wraps the `onnxRecommendationPipeline` bean with the per-user
   `ModelRateLimiter`, the `LoadShedder`, `InferenceMetricsService`, and A/B exposure logging, and
   throws the same exceptions so the 429/503 contract is identical. On 7010, `/v2/recommend` is
   wrapped in `OnlineAdmissionControl` like `/online/recommendation` beside it.
   `/v2/sequential/recommend` is deliberately left unwrapped — it is a stub that always returns
   `501`, and recording a failure per call would corrupt the readiness signal.
   Still V1-only, by choice: the submit-token CSRF check and the degraded-cache fallback.
```

- [ ] **Step 2: Note the readiness change in `18_Fault_Tolerance.md`**

Find the section covering load shedding / overload protection on the model service and add:

```markdown
Inference metrics now cover both recommendation paths. Before, `InferenceMetricsService` recorded
only `/api/v1/recommend`, so the `high failure rate` and `high inference latency` reasons behind
model-serving readiness were computed without any of the canonical `POST /api/recommend` traffic.
Readiness may therefore report degraded in situations where it previously looked healthy — that is
the signal being measured correctly for the first time, not a new fault.
```

- [ ] **Step 3: Add the operational note to `overload-protection.md`**

In the section describing model-serving load shedding, add:

```markdown
> **Changed 2026-07-27:** `/v2/recommend` — the route the canonical `POST /api/recommend` reaches —
> is now load-shed and metered on both 8080 and 7010. If model-serving readiness starts flapping
> after this change, check whether the instance was always overloaded and simply not measuring it:
> compare `/health/load` against the pre-change baseline before assuming a regression.
```

- [ ] **Step 4: Verify links and run the full suite**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test
```

Expected: PASS. This is the first full-suite run of the branch. Note that `OnlineAdmissionControlTest` is a known intermittent failure in this repo — if it fails, rerun that class alone to confirm, and say so explicitly in your report rather than silently accepting it.

Also confirm every relative link in the three touched docs still resolves.

- [ ] **Step 5: Commit**

```bash
git add docs/system_design/09_API_Gateway.md docs/system_design/18_Fault_Tolerance.md \
        docs/runbooks/overload-protection.md
git commit -m "docs: record v2 protection parity and the readiness-signal change"
```

---

## Notes for the reviewer

- The ordering rule (rate limit before semaphore) is the one thing in Task 2 that a plausible-looking refactor could silently break. Task 2 Step 5 revert-checks it specifically.
- Task 3's sequential test looks like it asserts nothing changing — that is the point. It pins a deliberate decision that a future contributor would otherwise "fix" by wrapping the stub.
- No new configuration is introduced. `ModelRateLimiter` defaults to `rps=0` (disabled), so v2 gains the limiter as a no-op until `RECSYS_MODEL_RATE_LIMIT_RPS` is set, matching V1 today.
