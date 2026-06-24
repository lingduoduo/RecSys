package com.recsys.application.online;

import com.recsys.api.serving.BaseApiService;
import com.recsys.application.recommendation.RecommendationPipeline;
import com.recsys.domain.item.Movie;
import com.recsys.domain.online.OnlineRecommendationRequest;
import com.recsys.domain.online.OnlineRecommendationResult;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.user.User;
import com.recsys.infrastructure.messaging.AsyncEventPublisher;
import com.recsys.metrics.OnlineServingMetricsService;
import com.recsys.loadshed.OnlineLoadShedder;
import com.recsys.ratelimit.RedisRateLimiter;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The two load-shed/rate-limited online serving handlers, grouped as nested
 * services over one shared request envelope:
 *
 * <ul>
 *   <li>{@link Prediction} — GET /online/recommendation (full recommendation snapshot).</li>
 *   <li>{@link Features} — GET /online/features (feature snapshot + async feature_view event).</li>
 * </ul>
 *
 * Both run the same envelope (admission shedding, Redis rate limiting, request
 * parsing, the shared {@code recommend(...)} call, success/failure metrics, and
 * load-shedder release); each contributes only its response shape, its metrics
 * strategy label, and — for {@link Features} — a post-success side effect.
 */
public final class OnlineServices {

    private OnlineServices() {}

    /**
     * Shared request envelope for the admission-gated online handlers. Subclasses
     * supply {@link #render}, {@link #strategyLabel}, and optionally {@link #afterSuccess}.
     */
    public abstract static class Guarded extends BaseApiService {

        protected final OnlineRecommendationService recommendationService;
        protected final OnlineServingMetricsService metricsService;
        protected final OnlineLoadShedder loadShedder;
        protected final RedisRateLimiter redisRateLimiter;
        protected final boolean admissionHandledExternally;

        protected Guarded(OnlineRecommendationService recommendationService,
                          OnlineServingMetricsService metricsService,
                          OnlineLoadShedder loadShedder,
                          RedisRateLimiter redisRateLimiter,
                          boolean admissionHandledExternally) {
            this.recommendationService = recommendationService;
            this.metricsService = metricsService;
            this.loadShedder = loadShedder;
            this.redisRateLimiter = redisRateLimiter;
            this.admissionHandledExternally = admissionHandledExternally;
        }

        @Override
        protected final HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.of(CompletableFuture.supplyAsync(() -> {
                long startedAtMs = System.currentTimeMillis();
                if (!admissionHandledExternally && !loadShedder.tryAcquire()) {
                    metricsService.recordRejected();
                    return writeErrorWithRetryAfter(HttpStatus.TOO_MANY_REQUESTS,
                            "online serving overloaded", Math.max(1, loadShedder.retryAfterSeconds()));
                }
                try {
                    RedisRateLimiter.Decision rateDecision = redisRateLimiter.tryAcquire("online");
                    if (!rateDecision.allowed()) {
                        metricsService.recordRejected();
                        return writeErrorWithRetryAfter(HttpStatus.TOO_MANY_REQUESTS,
                                "online serving rate limited", rateDecision.retryAfterSeconds());
                    }

                    int userId = requiredIntParam(ctx, "userId");
                    int k = optionalIntParam(ctx, "k", 5, 1, 20);
                    String window = ctx.queryParam("window");

                    OnlineRecommendationResult result = recommendationService.recommend(
                            new OnlineRecommendationRequest(userId, window, k));
                    HttpResponse response = render(userId, k, result);
                    metricsService.recordSuccess(elapsedMs(startedAtMs), strategyLabel(result));
                    afterSuccess(userId, result);
                    return response;
                } catch (BadRequestException | IllegalArgumentException e) {
                    metricsService.recordFailure(elapsedMs(startedAtMs));
                    return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
                } catch (OnlineRecommendationService.UnknownUserException e) {
                    metricsService.recordFailure(elapsedMs(startedAtMs));
                    return writeError(HttpStatus.NOT_FOUND, e.getMessage());
                } catch (Exception e) {
                    metricsService.recordFailure(elapsedMs(startedAtMs));
                    log.error("Unexpected error in {}", getClass().getSimpleName(), e);
                    return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
                } finally {
                    if (!admissionHandledExternally) {
                        loadShedder.release();
                    }
                }
            }, ctx.blockingTaskExecutor()));
        }

        /** Build the success response from the shared recall result. */
        protected abstract HttpResponse render(int userId, int k, OnlineRecommendationResult result);

        /** Metrics strategy label passed to {@code recordSuccess}. */
        protected abstract String strategyLabel(OnlineRecommendationResult result);

        /** Post-success side effect, run after {@code recordSuccess}. Default no-op. */
        protected void afterSuccess(int userId, OnlineRecommendationResult result) {}

        protected static long elapsedMs(long startedAtMs) {
            return Math.max(0L, System.currentTimeMillis() - startedAtMs);
        }
    }

    /** GET /online/recommendation — full recommendation snapshot. */
    public static final class Prediction extends Guarded {

        public Prediction(OnlineRecommendationService recommendationService) {
            this(recommendationService, new OnlineServingMetricsService(),
                    new OnlineLoadShedder(), RedisRateLimiter.disabled());
        }

        public Prediction(OnlineRecommendationService recommendationService,
                          OnlineServingMetricsService metricsService,
                          OnlineLoadShedder loadShedder) {
            this(recommendationService, metricsService, loadShedder, RedisRateLimiter.disabled());
        }

        public Prediction(OnlineRecommendationService recommendationService,
                          OnlineServingMetricsService metricsService,
                          OnlineLoadShedder loadShedder,
                          RedisRateLimiter redisRateLimiter) {
            super(recommendationService, metricsService, loadShedder, redisRateLimiter, false);
        }

        public Prediction(OnlineRecommendationService recommendationService,
                   OnlineServingMetricsService metricsService,
                   OnlineLoadShedder loadShedder,
                   RedisRateLimiter redisRateLimiter,
                   boolean admissionHandledExternally) {
            super(recommendationService, metricsService, loadShedder, redisRateLimiter,
                    admissionHandledExternally);
        }

        @Override
        protected HttpResponse render(int userId, int k, OnlineRecommendationResult result) {
            return writeJson(HttpStatus.OK, new OnlinePredictionResponse(
                    result.user(),
                    result.window(),
                    result.strategy(),
                    result.recentMovies(),
                    result.trendingMovies().stream().limit(k).toList(),
                    result.recommendations()
            ));
        }

        @Override
        protected String strategyLabel(OnlineRecommendationResult result) {
            return result.strategy();
        }

        private record OnlinePredictionResponse(User user,
                                                String window,
                                                String strategy,
                                                List<Movie> recentMovies,
                                                List<Movie> trendingMovies,
                                                List<Movie> recommendations) {}
    }

    /** GET /online/features — feature snapshot plus an async feature_view event. */
    public static final class Features extends Guarded {

        private final AsyncEventPublisher asyncEventPublisher;

        public Features(OnlineRecommendationService recommendationService) {
            this(recommendationService, new OnlineServingMetricsService(),
                    new OnlineLoadShedder(), RedisRateLimiter.disabled(), null);
        }

        public Features(OnlineRecommendationService recommendationService,
                        OnlineServingMetricsService metricsService,
                        OnlineLoadShedder loadShedder) {
            this(recommendationService, metricsService, loadShedder, RedisRateLimiter.disabled(), null);
        }

        public Features(OnlineRecommendationService recommendationService,
                        OnlineServingMetricsService metricsService,
                        OnlineLoadShedder loadShedder,
                        RedisRateLimiter redisRateLimiter) {
            this(recommendationService, metricsService, loadShedder, redisRateLimiter, null);
        }

        public Features(OnlineRecommendationService recommendationService,
                        OnlineServingMetricsService metricsService,
                        OnlineLoadShedder loadShedder,
                        RedisRateLimiter redisRateLimiter,
                        AsyncEventPublisher asyncEventPublisher) {
            super(recommendationService, metricsService, loadShedder, redisRateLimiter, false);
            this.asyncEventPublisher = asyncEventPublisher;
        }

        public Features(OnlineRecommendationService recommendationService,
                 OnlineServingMetricsService metricsService,
                 OnlineLoadShedder loadShedder,
                 RedisRateLimiter redisRateLimiter,
                 AsyncEventPublisher asyncEventPublisher,
                 boolean admissionHandledExternally) {
            super(recommendationService, metricsService, loadShedder, redisRateLimiter,
                    admissionHandledExternally);
            this.asyncEventPublisher = asyncEventPublisher;
        }

        @Override
        protected HttpResponse render(int userId, int k, OnlineRecommendationResult result) {
            return writeJson(HttpStatus.OK, new OnlineFeatureSnapshotResponse(
                    result.user(),
                    result.window(),
                    result.recentMovies(),
                    result.trendingMovies().stream().limit(k).toList()
            ));
        }

        @Override
        protected String strategyLabel(OnlineRecommendationResult result) {
            return "features";
        }

        @Override
        protected void afterSuccess(int userId, OnlineRecommendationResult result) {
            if (asyncEventPublisher != null) {
                asyncEventPublisher.publish(featureViewEvent(userId, result));
            }
        }

        private static String featureViewEvent(int userId, OnlineRecommendationResult result) {
            try {
                return MAPPER.writeValueAsString(Map.of(
                        "eventId",         UUID.randomUUID().toString(),
                        "userId",          userId,
                        "eventType",       "feature_view",
                        "window",          result.window(),
                        "eventTimeMillis", System.currentTimeMillis(),
                        "source",          "online-features"
                ));
            } catch (Exception e) {
                return "{}";
            }
        }

        private record OnlineFeatureSnapshotResponse(User user,
                                                     String window,
                                                     List<Movie> recentMovies,
                                                     List<Movie> trendingMovies) {}
    }

    /**
     * GET /health/live — liveness probe. Reports process viability and never fails merely
     * because the node is draining.
     */
    public static final class Live extends BaseApiService {
        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
            return writeJson(HttpStatus.OK, Map.of(
                    "ok", true,
                    "live", true,
                    "service", "online-serving"
            ));
        }
    }

    /** POST /v2/recommend — pipeline-driven recommendation from a JSON query. */
    public static final class RecommendV2 extends BaseApiService {

        private final RecommendationPipeline pipeline;

        public RecommendV2(RecommendationPipeline pipeline) {
            this.pipeline = pipeline;
        }

        @Override
        protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.of(req.aggregate().thenApplyAsync(agg -> {
                try {
                    RecommendationQuery query = readJsonBody(agg, RecommendationQuery.class);
                    return writeJson(HttpStatus.OK, pipeline.recommend(query));
                } catch (BadRequestException | IllegalArgumentException e) {
                    return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
                } catch (Exception e) {
                    log.error("Unexpected error in OnlineServices.RecommendV2", e);
                    return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
                }
            }, ctx.blockingTaskExecutor()));
        }
    }
}
