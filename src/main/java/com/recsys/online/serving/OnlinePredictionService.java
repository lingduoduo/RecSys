package com.recsys.online.serving;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.domain.Movie;
import com.recsys.domain.User;
import com.recsys.online.ops.OnlineLoadShedder;
import com.recsys.online.ops.OnlineServingMetricsService;
import com.recsys.online.redis.RedisRateLimiter;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class OnlinePredictionService extends ApiService {

    private final OnlineRecommendationService recommendationService;
    private final OnlineServingMetricsService metricsService;
    private final OnlineLoadShedder loadShedder;
    private final RedisRateLimiter redisRateLimiter;
    private final boolean admissionHandledExternally;

    public OnlinePredictionService(OnlineRecommendationService recommendationService) {
        this(recommendationService, new OnlineServingMetricsService(),
                new OnlineLoadShedder(), RedisRateLimiter.disabled());
    }

    public OnlinePredictionService(OnlineRecommendationService recommendationService,
                                   OnlineServingMetricsService metricsService,
                                   OnlineLoadShedder loadShedder) {
        this(recommendationService, metricsService, loadShedder, RedisRateLimiter.disabled());
    }

    public OnlinePredictionService(OnlineRecommendationService recommendationService,
                                   OnlineServingMetricsService metricsService,
                                   OnlineLoadShedder loadShedder,
                                   RedisRateLimiter redisRateLimiter) {
        this.recommendationService = recommendationService;
        this.metricsService = metricsService;
        this.loadShedder = loadShedder;
        this.redisRateLimiter = redisRateLimiter;
        this.admissionHandledExternally = false;
    }

    OnlinePredictionService(OnlineRecommendationService recommendationService,
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
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
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
                HttpResponse response = writeJson(HttpStatus.OK, new OnlinePredictionResponse(
                        result.user(),
                        result.window(),
                        result.strategy(),
                        result.recentMovies(),
                        result.trendingMovies().stream().limit(k).toList(),
                        result.recommendations()
                ));
                metricsService.recordSuccess(elapsedMs(startedAtMs), result.strategy());
                return response;
            } catch (BadRequestException | IllegalArgumentException e) {
                metricsService.recordFailure(elapsedMs(startedAtMs));
                return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
            } catch (OnlineRecommendationService.UnknownUserException e) {
                metricsService.recordFailure(elapsedMs(startedAtMs));
                return writeError(HttpStatus.NOT_FOUND, e.getMessage());
            } catch (Exception e) {
                metricsService.recordFailure(elapsedMs(startedAtMs));
                log.error("Unexpected error in OnlinePredictionService", e);
                return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
            } finally {
                if (!admissionHandledExternally) {
                    loadShedder.release();
                }
            }
        }, ctx.blockingTaskExecutor()));
    }

    private static long elapsedMs(long startedAtMs) {
        return Math.max(0L, System.currentTimeMillis() - startedAtMs);
    }

    private record OnlinePredictionResponse(User user,
                                            String window,
                                            String strategy,
                                            List<Movie> recentMovies,
                                            List<Movie> trendingMovies,
                                            List<Movie> recommendations) {}
}
