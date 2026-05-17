package com.recsys.streaming;

import com.recsys.models.Movie;
import com.recsys.models.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

public final class OnlineFeaturesServlet extends ApiServlet {
    private static final int SC_TOO_MANY_REQUESTS = 429;

    private final OnlineRecommendationService recommendationService;
    private final OnlineServingMetricsService metricsService;
    private final OnlineLoadShedder loadShedder;

    public OnlineFeaturesServlet(OnlineRecommendationService recommendationService) {
        this(recommendationService, new OnlineServingMetricsService(), new OnlineLoadShedder());
    }

    public OnlineFeaturesServlet(OnlineRecommendationService recommendationService,
                                 OnlineServingMetricsService metricsService,
                                 OnlineLoadShedder loadShedder) {
        this.recommendationService = recommendationService;
        this.metricsService = metricsService;
        this.loadShedder = loadShedder;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        prepareJson(response);
        long startedAtMs = System.currentTimeMillis();
        if (!loadShedder.tryAcquire()) {
            metricsService.recordRejected();
            writeError(response, SC_TOO_MANY_REQUESTS, "online serving overloaded");
            return;
        }
        try {
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

    private static long elapsedMs(long startedAtMs) {
        return Math.max(0L, System.currentTimeMillis() - startedAtMs);
    }

    private record OnlineFeatureSnapshotResponse(User user,
                                                 String window,
                                                 List<Movie> recentMovies,
                                                 List<Movie> trendingMovies) {}
}
