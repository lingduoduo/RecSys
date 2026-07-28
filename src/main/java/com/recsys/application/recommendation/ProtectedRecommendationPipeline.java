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
 *
 * <p>Deliberately not {@code final}: {@code ModelRecommendationPipelineConfig} returns this from
 * a {@code @Bean} method, and {@code TraceIdAspect}'s {@code execution(* com.recsys..*(..))}
 * pointcut matches {@link #recommend}. Spring Boot's AOP autoconfiguration defaults
 * {@code spring.aop.proxy-target-class=true}, so the auto-proxy creator CGLIB-subclasses this
 * bean regardless of the config class's own {@code proxyBeanMethods=false} (that setting only
 * governs inter-@Bean-method calls within the configuration class, not AOP proxying of the
 * returned instance) — and CGLIB cannot subclass a final class.
 */
public class ProtectedRecommendationPipeline implements RecommendationPipeline {

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
