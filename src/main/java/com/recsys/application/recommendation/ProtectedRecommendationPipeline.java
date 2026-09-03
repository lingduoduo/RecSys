package com.recsys.application.recommendation;

import com.recsys.application.experiment.ABTestService;
import com.recsys.application.experiment.AbExposureLogger;
import com.recsys.api.request.RecommendRequest;
import com.recsys.api.response.RecommendResponse;
import com.recsys.domain.item.RankedMovie;
import com.recsys.domain.prediction.ScoredItem;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.recommendation.RecommendationResult;
import com.recsys.exception.RateLimitExceededException;
import com.recsys.exception.ServiceOverloadedException;
import com.recsys.loadshed.LoadShedder;
import com.recsys.metrics.InferenceMetricsService;
import com.recsys.ratelimit.ModelRateLimiter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
    /** Trace key + value the V2 controller turns into {@code X-Served-From: degraded-cache}. */
    public static final String TRACE_SERVED_FROM = "servedFrom";
    public static final String SERVED_FROM_DEGRADED_CACHE = "degraded-cache";

    private final RecommendationPipeline delegate;
    private final ModelRateLimiter rateLimiter;
    private final LoadShedder loadShedder;
    private final InferenceMetricsService metrics;
    private final ABTestService abTestService;
    private final AbExposureLogger exposureLogger;
    private final RecommendationService degradedCache;

    /** No degraded-cache fallback: overload is a plain 503, as before. */
    public ProtectedRecommendationPipeline(RecommendationPipeline delegate,
                                           ModelRateLimiter rateLimiter,
                                           LoadShedder loadShedder,
                                           InferenceMetricsService metrics,
                                           ABTestService abTestService,
                                           AbExposureLogger exposureLogger) {
        this(delegate, rateLimiter, loadShedder, metrics, abTestService, exposureLogger, null);
    }

    /**
     * @param degradedCache when non-null, an overloaded request is first offered this service's
     *                      {@link RecommendationService#tryServeFromCache} — the same fallback the
     *                      V1 controller performs inline — before the 503 is thrown.
     */
    public ProtectedRecommendationPipeline(RecommendationPipeline delegate,
                                           ModelRateLimiter rateLimiter,
                                           LoadShedder loadShedder,
                                           InferenceMetricsService metrics,
                                           ABTestService abTestService,
                                           AbExposureLogger exposureLogger,
                                           RecommendationService degradedCache) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.loadShedder = Objects.requireNonNull(loadShedder, "loadShedder");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.abTestService = Objects.requireNonNull(abTestService, "abTestService");
        this.exposureLogger = Objects.requireNonNull(exposureLogger, "exposureLogger");
        this.degradedCache = degradedCache;
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
            // Degradation before failure, as on V1: a cached window for the loaded assigned (or
            // control) runtime is served without a permit and without any model work. Nothing was
            // inferred, so no inference metric is recorded; the exposure is logged because the
            // user did see a list. Cursor continuations are excluded — the cached window is page
            // one of an uncursored request, and replaying it under a page-two cursor is wrong.
            Optional<RecommendationResult> degraded = degradedFromCache(query, assignment);
            if (degraded.isPresent()) {
                return degraded.get();
            }
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
        } catch (IllegalArgumentException e) {
            // Bad input, not an inference failure — GlobalExceptionHandler maps this to 400. The
            // V1 controller carves the same exception out of its failure recording for the same
            // reason; this path additionally reaches the pagination cursor codec, whose rejections
            // are entirely client-driven. Recording them would let a client looping malformed
            // cursors push recentFailureRate toward 1.0 and report a healthy instance degraded,
            // corrupting the very readiness signal this wrapper exists to make trustworthy.
            throw e;
        } catch (RuntimeException e) {
            metrics.recordFailure(elapsedMs(startNs), assignment.variant());
            throw e;
        } finally {
            loadShedder.release();
        }
    }

    private Optional<RecommendationResult> degradedFromCache(RecommendationQuery query,
                                                             ABTestService.Assignment assignment) {
        if (degradedCache == null || query.cursor() != null) {
            return Optional.empty();
        }
        RecommendRequest request = new RecommendRequest();
        request.setUserId(query.userId());
        request.setK(query.limit());
        if (!query.excludedItemIds().isEmpty()) {
            request.setExcludeItemIds(new ArrayList<>(query.excludedItemIds()));
        }
        Optional<RecommendResponse> cached =
                degradedCache.tryServeFromCache(request, assignment, abTestService.defaultVariant());
        if (cached.isEmpty()) {
            return Optional.empty();
        }
        RecommendResponse response = cached.get();
        String servedVariant = response.abTestVariant() == null ? assignment.variant() : response.abTestVariant();
        String modelVersion = response.modelVersion() == null ? "" : response.modelVersion();
        exposureLogger.log(query.userId(), assignment, servedVariant,
                !servedVariant.equals(assignment.variant()), modelVersion);

        Map<String, String> trace = new LinkedHashMap<>();
        trace.put(TRACE_VARIANT, servedVariant);
        trace.put(TRACE_MODEL_VERSION, modelVersion);
        trace.put(TRACE_SERVED_FROM, SERVED_FROM_DEGRADED_CACHE);
        return Optional.of(new RecommendationResult(
                query.userId(), toRanked(response.recommendations(), query.limit()), null, false, trace));
    }

    /** Same ordering as OnnxInferencePipeline: score descending, itemId as the tiebreak. */
    private static List<RankedMovie> toRanked(List<ScoredItem> raw, int limit) {
        List<ScoredItem> ordered = raw.stream()
                .sorted(Comparator.comparingDouble(ScoredItem::score).reversed()
                        .thenComparing(ScoredItem::itemId))
                .limit(Math.max(0, limit))
                .toList();
        List<RankedMovie> items = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            ScoredItem item = ordered.get(i);
            items.add(new RankedMovie(item.itemId(), item.score(), i + 1, Map.of()));
        }
        return items;
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
