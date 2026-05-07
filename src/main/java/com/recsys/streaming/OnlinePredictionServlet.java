package com.recsys.streaming;

import com.recsys.models.Movie;
import com.recsys.models.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

public final class OnlinePredictionServlet extends ApiServlet {
    private final OnlineRecommendationService recommendationService;

    public OnlinePredictionServlet(OnlineRecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        prepareJson(response);
        try {
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
        } catch (BadRequestException | IllegalArgumentException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (OnlineRecommendationService.UnknownUserException e) {
            writeError(response, HttpServletResponse.SC_NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error in OnlinePredictionServlet", e);
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "internal server error");
        }
    }

    private record OnlinePredictionResponse(User user,
                                            String window,
                                            String strategy,
                                            List<Movie> recentMovies,
                                            List<Movie> trendingMovies,
                                            List<Movie> recommendations) {}
}
