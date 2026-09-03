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

import java.time.Duration;
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
        // AbExposureLogger's (publisher, config, idGenerator, clock) ctor is package-private in
        // com.recsys.application.experiment and this fake lives in a different package, so it
        // cannot call it via super(...). The 2-arg public ctor is fine here because log() below
        // is fully overridden and never touches the real idGenerator/clock/publisher.
        RecordingExposureLogger() { super(null, new ABTestConfig()); }
        @Override
        public void log(String userId, ABTestService.Assignment assignment,
                        String servedVariant, boolean fellBack, String modelVersion) {
            this.userId = userId; this.servedVariant = servedVariant;
            this.fellBack = fellBack; this.modelVersion = modelVersion; this.calls++;
        }
    }

    /**
     * Denies every request, so the ordering assertion never depends on wall-clock refill.
     * A real limiter with rps=1/burst=1 only stays exhausted for ~1s after the priming call —
     * true by a huge margin normally, but not guaranteed under a throttled CI runner, a
     * stop-the-world GC, or a safepoint stall between the two recommend() calls in the test.
     */
    private static final class DenyingRateLimiter extends ModelRateLimiter {
        DenyingRateLimiter() { super(1.0, 1, 100); }
        @Override
        public Decision tryAcquire(String userId) {
            return new Decision(false, 1, 0, Duration.ofSeconds(1));
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
        // DenyingRateLimiter denies from the outset, so no priming call is needed — this
        // isolates the assertion to what it actually means: a denied call never reaches the
        // semaphore, and all 8 permits remain free.
        LoadShedder shedder = shedder(8);
        ProtectedRecommendationPipeline pipeline = new ProtectedRecommendationPipeline(
                q -> resultWithTrace("A", "v1"), new DenyingRateLimiter(), shedder, metrics(),
                new ABTestService(new ABTestConfig()), new RecordingExposureLogger());

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
    void servedControlUnderATreatmentAssignmentIsReportedAsAFallback() {
        // The pipeline writes the SERVED variant into the trace (OnnxInferencePipelineTest pins
        // that). Here: assigned "test", served "training" -> fellBack, and the success metric and
        // exposure event both name control, never the model that did not run.
        InferenceMetricsService metrics = metrics();
        RecordingExposureLogger exposure = new RecordingExposureLogger();
        ABTestService assignsTreatment = new ABTestService(new ABTestConfig()) {
            @Override
            public Assignment getAssignmentForUser(String userId) {
                return new Assignment("test", 3, "default", true);
            }
        };
        ProtectedRecommendationPipeline pipeline = new ProtectedRecommendationPipeline(
                q -> resultWithTrace("training", "dssm-demo-v1"), disabledLimiter(), shedder(4), metrics,
                assignsTreatment, exposure);

        pipeline.recommend(QUERY);

        assertThat(exposure.servedVariant).isEqualTo("training");
        assertThat(exposure.fellBack).isTrue();
        assertThat(exposure.modelVersion).isEqualTo("dssm-demo-v1");
        assertThat(metrics.snapshot().successCount()).isEqualTo(1);
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
    void badInputIsRethrownWithoutRecordingAnInferenceFailure() {
        // Parity with RecommendationController, which rethrows IllegalArgumentException from a
        // dedicated catch precisely so a client's bad input is not booked as an inference failure.
        // This matters more here than in V1: /health/ready derives "high failure rate" from this
        // snapshot, so counting 400s would let a client looping malformed cursors drive an
        // otherwise-healthy instance to degraded and out of the load balancer.
        InferenceMetricsService metrics = metrics();
        LoadShedder shedder = shedder(1);
        ProtectedRecommendationPipeline pipeline = new ProtectedRecommendationPipeline(
                q -> { throw new IllegalArgumentException("Invalid recommendation cursor"); },
                disabledLimiter(), shedder, metrics,
                new ABTestService(new ABTestConfig()), new RecordingExposureLogger());

        assertThatThrownBy(() -> pipeline.recommend(QUERY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid recommendation cursor");

        assertThat(metrics.snapshot().failureCount()).as("client error is not an inference failure").isZero();
        assertThat(metrics.snapshot().successCount()).as("and it is certainly not a success").isZero();
        // The permit must still come back, exactly as it does for a genuine inference failure.
        assertThat(shedder.tryAcquire()).as("permit released on the bad-input path").isTrue();
    }

    @Test
    void genuineInferenceFailuresAreStillRecorded() {
        // Guards the carve-out above from being widened into "swallow every failure".
        InferenceMetricsService metrics = metrics();
        ProtectedRecommendationPipeline pipeline = new ProtectedRecommendationPipeline(
                q -> { throw new IllegalStateException("model exploded"); },
                disabledLimiter(), shedder(4), metrics,
                new ABTestService(new ABTestConfig()), new RecordingExposureLogger());

        assertThatThrownBy(() -> pipeline.recommend(QUERY))
                .isInstanceOf(IllegalStateException.class);

        assertThat(metrics.snapshot().failureCount()).isEqualTo(1);
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
        return new ModelRateLimiter(0.0, 0, 100);   // rps=0 -> disabled -> always allowed
    }
}
