package com.recsys.streaming;

import com.recsys.models.Movie;
import com.recsys.models.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

public final class OnlineFeaturesServlet extends ApiServlet {
    private final OnlineRecommendationService recommendationService;

    public OnlineFeaturesServlet(OnlineRecommendationService recommendationService) {
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
            writeJson(response, HttpServletResponse.SC_OK, new OnlineFeatureSnapshotResponse(
                    result.user(),
                    result.window(),
                    result.recentMovies(),
                    result.trendingMovies().stream().limit(k).toList()
            ));
        } catch (BadRequestException | IllegalArgumentException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (OnlineRecommendationService.UnknownUserException e) {
            writeError(response, HttpServletResponse.SC_NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error in OnlineFeaturesServlet", e);
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "internal server error");
        }
    }

    private record OnlineFeatureSnapshotResponse(User user,
                                                 String window,
                                                 List<Movie> recentMovies,
                                                 List<Movie> trendingMovies) {}
}
