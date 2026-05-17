package com.recsys.streaming;

import com.recsys.models.Movie;
import com.recsys.models.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

public final class OnlinePredictionServlet extends ApiServlet {
    private static final int SC_TOO_MANY_REQUESTS = 429;

    private final OnlineRecommendationService recommendationService;
    private final OnlineServingMetricsService metricsService;
    private final OnlineLoadShedder loadShedder;
    private final RedisRateLimiter redisRateLimiter;

    public OnlinePredictionServlet(OnlineRecommendationService recommendationService) {
        this(recommendationService, new OnlineServingMetricsService(),
                new OnlineLoadShedder(), RedisRateLimiter.disabled());
    }

    public OnlinePredictionServlet(OnlineRecommendationService recommendationService,
                                   OnlineServingMetricsService metricsService,
                                   OnlineLoadShedder loadShedder) {
        this(recommendationService, metricsService, loadShedder, RedisRateLimiter.disabled());
    }

    public OnlinePredictionServlet(OnlineRecommendationService recommendationService,
                                   OnlineServingMetricsService metricsService,
                                   OnlineLoadShedder loadShedder,
                                   RedisRateLimiter redisRateLimiter) {
        this.recommendationService = recommendationService;
        this.metricsService = metricsService;
        this.loadShedder = loadShedder;
        this.redisRateLimiter = redisRateLimiter;
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
            writeJson(response, HttpServletResponse.SC_OK, new OnlinePredictionResponse(
                    result.user(),
                    result.window(),
                    result.strategy(),
                    result.recentMovies(),
                    result.trendingMovies().stream().limit(k).toList(),
                    result.recommendations()
            ));
            metricsService.recordSuccess(elapsedMs(startedAtMs), result.strategy());
        } catch (BadRequestException | IllegalArgumentException e) {
            metricsService.recordFailure(elapsedMs(startedAtMs));
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (OnlineRecommendationService.UnknownUserException e) {
            metricsService.recordFailure(elapsedMs(startedAtMs));
            writeError(response, HttpServletResponse.SC_NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            metricsService.recordFailure(elapsedMs(startedAtMs));
            log.error("Unexpected error in OnlinePredictionServlet", e);
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "internal server error");
        } finally {
            loadShedder.release();
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

    private record OnlinePredictionResponse(User user,
                                            String window,
                                            String strategy,
                                            List<Movie> recentMovies,
                                            List<Movie> trendingMovies,
                                            List<Movie> recommendations) {}
}
