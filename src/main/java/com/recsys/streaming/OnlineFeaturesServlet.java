package com.recsys.streaming;

import com.recsys.model.Movie;
import com.recsys.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OnlineFeaturesServlet extends ApiServlet {
    private static final int SC_TOO_MANY_REQUESTS = 429;

    private final OnlineRecommendationService recommendationService;
    private final OnlineServingMetricsService metricsService;
    private final OnlineLoadShedder loadShedder;
    private final RedisRateLimiter redisRateLimiter;
    private final AsyncEventPublisher asyncEventPublisher;

    public OnlineFeaturesServlet(OnlineRecommendationService recommendationService) {
        this(recommendationService, new OnlineServingMetricsService(),
                new OnlineLoadShedder(), RedisRateLimiter.disabled(), null);
    }

    public OnlineFeaturesServlet(OnlineRecommendationService recommendationService,
                                 OnlineServingMetricsService metricsService,
                                 OnlineLoadShedder loadShedder) {
        this(recommendationService, metricsService, loadShedder, RedisRateLimiter.disabled(), null);
    }

    public OnlineFeaturesServlet(OnlineRecommendationService recommendationService,
                                 OnlineServingMetricsService metricsService,
                                 OnlineLoadShedder loadShedder,
                                 RedisRateLimiter redisRateLimiter) {
        this(recommendationService, metricsService, loadShedder, redisRateLimiter, null);
    }

    public OnlineFeaturesServlet(OnlineRecommendationService recommendationService,
                                 OnlineServingMetricsService metricsService,
                                 OnlineLoadShedder loadShedder,
                                 RedisRateLimiter redisRateLimiter,
                                 AsyncEventPublisher asyncEventPublisher) {
        this.recommendationService = recommendationService;
        this.metricsService = metricsService;
        this.loadShedder = loadShedder;
        this.redisRateLimiter = redisRateLimiter;
        this.asyncEventPublisher = asyncEventPublisher;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        prepareJson(response);
        long startedAtMs = System.currentTimeMillis();
        if (!loadShedder.tryAcquire()) {
            metricsService.recordRejected();
            setRetryAfter(response, loadShedder.retryAfterSeconds());
            writeError(response, SC_TOO_MANY_REQUESTS, "online serving overloaded");
            return;
        }
        try {
            RedisRateLimiter.Decision rateDecision = redisRateLimiter.tryAcquire("online");
            if (!rateDecision.allowed()) {
                metricsService.recordRejected();
                setRetryAfter(response, rateDecision.retryAfterSeconds());
                writeError(response, SC_TOO_MANY_REQUESTS, "online serving rate limited");
                return;
            }

            int userId = requiredIntParam(request, "userId");
            int k = optionalIntParam(request, "k", 5, 1, 20);
            String window = request.getParameter("window");

            OnlineRecommendationResult result = recommendationService.recommend(
                    new OnlineRecommendationRequest(userId, window, k));
            writeJson(response, HttpServletResponse.SC_OK, new OnlineFeatureSnapshotResponse(
                    result.user(),
                    result.window(),
                    result.recentMovies(),
                    result.trendingMovies().stream().limit(k).toList()
            ));
            metricsService.recordSuccess(elapsedMs(startedAtMs), "features");

            if (asyncEventPublisher != null) {
                asyncEventPublisher.publish(featureViewEvent(userId, result));
            }
        } catch (BadRequestException | IllegalArgumentException e) {
            metricsService.recordFailure(elapsedMs(startedAtMs));
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (OnlineRecommendationService.UnknownUserException e) {
            metricsService.recordFailure(elapsedMs(startedAtMs));
            writeError(response, HttpServletResponse.SC_NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            metricsService.recordFailure(elapsedMs(startedAtMs));
            log.error("Unexpected error in OnlineFeaturesServlet", e);
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "internal server error");
        } finally {
            loadShedder.release();
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

    private static long elapsedMs(long startedAtMs) {
        return Math.max(0L, System.currentTimeMillis() - startedAtMs);
    }

    private static void setRetryAfter(HttpServletResponse response, int retryAfterSeconds) {
        if (retryAfterSeconds > 0) {
            response.setHeader("Retry-After", Integer.toString(retryAfterSeconds));
        }
    }

    private record OnlineFeatureSnapshotResponse(User user,
                                                 String window,
                                                 List<Movie> recentMovies,
                                                 List<Movie> trendingMovies) {}
}
