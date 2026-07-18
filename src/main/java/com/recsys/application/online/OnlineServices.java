package com.recsys.application.online;

import com.recsys.api.serving.BaseApiService;
import com.recsys.application.recommendation.RecommendationPipeline;
import com.recsys.application.consistency.ConsistencyToken;
import com.recsys.application.consistency.ConsistencyTokenCodec;
import com.recsys.application.consistency.ConsistencyWaiter;
import com.recsys.application.consistency.ExpiredConsistencyTokenException;
import com.recsys.application.consistency.InvalidConsistencyTokenException;
import com.recsys.application.outbox.DurableEventPublisher;
import com.recsys.application.retrieval.multichannel.MultiChannelRecallService;
import com.recsys.domain.item.Movie;
import com.recsys.domain.online.OnlineRecommendationRequest;
import com.recsys.domain.online.OnlineRecommendationResult;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.user.User;
import com.recsys.infrastructure.messaging.AsyncEventPublisher;
import com.recsys.infrastructure.outbox.OutboxConflictException;
import com.recsys.metrics.OnlineServingMetricsService;
import com.recsys.metrics.ConsistencyMetrics;
import com.recsys.loadshed.OnlineLoadShedder;
import com.recsys.ratelimit.RedisRateLimiter;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.time.Duration;

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
        private final ConsistencyTokenCodec consistencyTokenCodec;
        private final ConsistencyWaiter consistencyWaiter;
        private final ConsistencyMetrics consistencyMetrics;

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
            this.consistencyTokenCodec = null;
            this.consistencyWaiter = null;
            this.consistencyMetrics = null;
        }

        protected Guarded(OnlineRecommendationService recommendationService,
                          OnlineServingMetricsService metricsService,
                          OnlineLoadShedder loadShedder,
                          RedisRateLimiter redisRateLimiter,
                          boolean admissionHandledExternally,
                          ConsistencyTokenCodec consistencyTokenCodec,
                          ConsistencyWaiter consistencyWaiter) {
            this(recommendationService, metricsService, loadShedder, redisRateLimiter,
                    admissionHandledExternally, consistencyTokenCodec, consistencyWaiter, null);
        }

        protected Guarded(OnlineRecommendationService recommendationService,
                          OnlineServingMetricsService metricsService, OnlineLoadShedder loadShedder,
                          RedisRateLimiter redisRateLimiter, boolean admissionHandledExternally,
                          ConsistencyTokenCodec consistencyTokenCodec, ConsistencyWaiter consistencyWaiter,
                          ConsistencyMetrics consistencyMetrics) {
            this.recommendationService = recommendationService;
            this.metricsService = metricsService;
            this.loadShedder = loadShedder;
            this.redisRateLimiter = redisRateLimiter;
            this.admissionHandledExternally = admissionHandledExternally;
            this.consistencyTokenCodec = Objects.requireNonNull(consistencyTokenCodec, "consistencyTokenCodec");
            this.consistencyWaiter = Objects.requireNonNull(consistencyWaiter, "consistencyWaiter");
            this.consistencyMetrics = consistencyMetrics;
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

                    OnlineRecommendationRequest recommendationRequest =
                            new OnlineRecommendationRequest(userId, window, k);
                    OnlineRecommendationResult result;
                    String encodedToken = req.headers().get(ConsistencyTokenCodec.HEADER_NAME);
                    if (encodedToken == null || encodedToken.isBlank()) {
                        result = recommendationService.recommend(recommendationRequest);
                    } else {
                        if (consistencyTokenCodec == null) throw new InvalidConsistencyTokenException();
                        ConsistencyToken token = consistencyTokenCodec.decodeAndVerify(encodedToken);
                        if (token.userId() != userId) throw new ConsistencySubjectMismatchException();
                        if (consistencyMetrics != null) consistencyMetrics.recordTokenValidation(ConsistencyMetrics.TokenOutcome.VALID);
                        ConsistencyWaiter.WaitResult waitResult;
                        try {
                            waitResult = consistencyWaiter.await(token.eventId(), userId, Duration.ofSeconds(2));
                        } catch (RuntimeException primaryReadFailure) {
                            log.warn("Primary consistency lookup unavailable", primaryReadFailure);
                            return writeErrorWithRetryAfter(HttpStatus.SERVICE_UNAVAILABLE,
                                    "consistency lookup unavailable", 1);
                        }
                        if (waitResult == ConsistencyWaiter.WaitResult.PENDING) {
                            return writeErrorWithRetryAfter(HttpStatus.ACCEPTED,
                                    "event materialization pending", 1);
                        }
                        result = recommendationService.recommendPrimary(recommendationRequest);
                    }
                    HttpResponse response = render(ctx, userId, k, result);
                    metricsService.recordSuccess(elapsedMs(startedAtMs), strategyLabel(result));
                    afterSuccess(userId, result);
                    return response;
                } catch (ExpiredConsistencyTokenException e) {
                    if (consistencyMetrics != null) consistencyMetrics.recordTokenValidation(ConsistencyMetrics.TokenOutcome.EXPIRED);
                    metricsService.recordFailure(elapsedMs(startedAtMs));
                    return writeError(HttpStatus.CONFLICT, "consistency token expired");
                } catch (ConsistencySubjectMismatchException e) {
                    if (consistencyMetrics != null) consistencyMetrics.recordTokenValidation(ConsistencyMetrics.TokenOutcome.SUBJECT_MISMATCH);
                    metricsService.recordFailure(elapsedMs(startedAtMs));
                    return writeError(HttpStatus.FORBIDDEN, "consistency token subject mismatch");
                } catch (InvalidConsistencyTokenException e) {
                    if (consistencyMetrics != null) consistencyMetrics.recordTokenValidation(ConsistencyMetrics.TokenOutcome.INVALID);
                    metricsService.recordFailure(elapsedMs(startedAtMs));
                    return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
                } catch (BadRequestException | IllegalArgumentException e) {
                    metricsService.recordFailure(elapsedMs(startedAtMs));
                    return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
                } catch (OnlineRecommendationService.UnknownUserException e) {
                    metricsService.recordFailure(elapsedMs(startedAtMs));
                    return writeError(HttpStatus.NOT_FOUND, e.getMessage());
                } catch (OnlineRecommendationService.PrimaryReadUnavailableException e) {
                    metricsService.recordFailure(elapsedMs(startedAtMs));
                    log.warn("Primary recommendation unavailable", e);
                    return writeErrorWithRetryAfter(HttpStatus.SERVICE_UNAVAILABLE,
                            "primary recommendation unavailable", 1);
                } catch (OutboxConflictException e) {
                    metricsService.recordFailure(elapsedMs(startedAtMs));
                    return writeError(HttpStatus.CONFLICT, "event ID already accepted with different content");
                } catch (DurableEventPublisher.PersistenceException e) {
                    metricsService.recordFailure(elapsedMs(startedAtMs));
                    log.error("Unable to durably accept online event", e);
                    return writeError(HttpStatus.SERVICE_UNAVAILABLE, "event acceptance unavailable");
                } catch (MultiChannelRecallService.PrimaryRecallUnavailableException e) {
                    metricsService.recordFailure(elapsedMs(startedAtMs));
                    log.warn("Primary recommendation unavailable", e);
                    return writeErrorWithRetryAfter(HttpStatus.SERVICE_UNAVAILABLE,
                            "primary recommendation unavailable", 1);
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
        protected abstract HttpResponse render(ServiceRequestContext ctx, int userId, int k,
                                               OnlineRecommendationResult result);

        /** Metrics strategy label passed to {@code recordSuccess}. */
        protected abstract String strategyLabel(OnlineRecommendationResult result);

        /** Post-success side effect, run after {@code recordSuccess}. Default no-op. */
        protected void afterSuccess(int userId, OnlineRecommendationResult result) {}

        protected static long elapsedMs(long startedAtMs) {
            return Math.max(0L, System.currentTimeMillis() - startedAtMs);
        }
    }

    private static final class ConsistencySubjectMismatchException extends RuntimeException {}

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
                          ConsistencyTokenCodec tokenCodec,
                          ConsistencyWaiter consistencyWaiter) {
            super(recommendationService, new OnlineServingMetricsService(), new OnlineLoadShedder(),
                    RedisRateLimiter.disabled(), false, tokenCodec, consistencyWaiter);
        }

        public Prediction(OnlineRecommendationService recommendationService,
                          OnlineServingMetricsService metricsService,
                          OnlineLoadShedder loadShedder,
                          RedisRateLimiter redisRateLimiter,
                          boolean admissionHandledExternally,
                          ConsistencyTokenCodec tokenCodec,
                          ConsistencyWaiter consistencyWaiter) {
            super(recommendationService, metricsService, loadShedder, redisRateLimiter,
                    admissionHandledExternally, tokenCodec, consistencyWaiter);
        }

        public Prediction(OnlineRecommendationService recommendationService,
                          OnlineServingMetricsService metricsService, OnlineLoadShedder loadShedder,
                          RedisRateLimiter redisRateLimiter, boolean admissionHandledExternally,
                          ConsistencyTokenCodec tokenCodec, ConsistencyWaiter consistencyWaiter,
                          ConsistencyMetrics consistencyMetrics) {
            super(recommendationService, metricsService, loadShedder, redisRateLimiter,
                    admissionHandledExternally, tokenCodec, consistencyWaiter, consistencyMetrics);
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
        protected HttpResponse render(ServiceRequestContext ctx, int userId, int k,
                                      OnlineRecommendationResult result) {
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
        private final DurableEventPublisher durableEventPublisher;
        private final ConsistencyTokenCodec tokenCodec;

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
            this.durableEventPublisher = null;
            this.tokenCodec = null;
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
            this.durableEventPublisher = null;
            this.tokenCodec = null;
        }

        public Features(OnlineRecommendationService recommendationService,
                        DurableEventPublisher durableEventPublisher,
                        ConsistencyTokenCodec tokenCodec) {
            this(recommendationService, new OnlineServingMetricsService(), new OnlineLoadShedder(),
                    RedisRateLimiter.disabled(), durableEventPublisher, tokenCodec, false);
        }

        public Features(OnlineRecommendationService recommendationService,
                        OnlineServingMetricsService metricsService,
                        OnlineLoadShedder loadShedder,
                        RedisRateLimiter redisRateLimiter,
                        DurableEventPublisher durableEventPublisher,
                        ConsistencyTokenCodec tokenCodec,
                        boolean admissionHandledExternally) {
            super(recommendationService, metricsService, loadShedder, redisRateLimiter,
                    admissionHandledExternally);
            this.asyncEventPublisher = null;
            this.durableEventPublisher = Objects.requireNonNull(durableEventPublisher, "durableEventPublisher");
            this.tokenCodec = Objects.requireNonNull(tokenCodec, "tokenCodec");
        }

        @Override
        protected HttpResponse render(ServiceRequestContext ctx, int userId, int k,
                                      OnlineRecommendationResult result) {
            OnlineFeatureSnapshotResponse snapshot = new OnlineFeatureSnapshotResponse(
                    result.user(),
                    result.window(),
                    result.recentMovies(),
                    result.trendingMovies().stream().limit(k).toList()
            );
            if (durableEventPublisher == null) return writeJson(HttpStatus.OK, snapshot);
            String rawEventId = ctx.queryParam("eventId");
            if (rawEventId == null || rawEventId.isBlank()) {
                return writeJson(HttpStatus.OK, snapshot);
            }
            UUID eventId = requiredEventId(rawEventId);
            byte[] responseBody = serialize(snapshot);
            String payload = featureViewEvent(eventId, userId, result);
            DurableEventPublisher.Acceptance acceptance =
                    durableEventPublisher.publishOnline(eventId, userId, "feature_view", payload);
            String token = tokenCodec.encode(new ConsistencyToken(acceptance.eventId(), userId,
                    acceptance.acceptedAt(), acceptance.acceptedAt().plus(Duration.ofHours(24))));
            return writeJsonWithToken(responseBody, token);
        }

        @Override
        protected String strategyLabel(OnlineRecommendationResult result) {
            return "features";
        }

        @Override
        protected void afterSuccess(int userId, OnlineRecommendationResult result) {
            if (asyncEventPublisher != null) {
                asyncEventPublisher.publish(featureViewEvent(UUID.randomUUID(), userId, result));
            }
        }

        private static String featureViewEvent(UUID eventId, int userId, OnlineRecommendationResult result) {
            try {
                return MAPPER.writeValueAsString(Map.of(
                        "eventId",         eventId.toString(),
                        "userId",          userId,
                        "eventType",       "feature_view",
                        "window",          result.window(),
                        "source",          "online-features"
                ));
            } catch (Exception e) {
                throw new IllegalStateException("unable to serialize feature-view event", e);
            }
        }

        private static UUID requiredEventId(String raw) {
            try { return UUID.fromString(raw); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("eventId must be a UUID"); }
        }

        private static byte[] serialize(Object payload) {
            try {
                return MAPPER.writeValueAsBytes(payload);
            } catch (Exception e) {
                throw new IllegalStateException("unable to serialize feature snapshot", e);
            }
        }

        private static HttpResponse writeJsonWithToken(byte[] body, String token) {
            return HttpResponse.of(ResponseHeaders.builder(HttpStatus.OK)
                    .contentType(MediaType.JSON_UTF_8)
                    .set(ConsistencyTokenCodec.HEADER_NAME, token).build(), HttpData.wrap(body));
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
