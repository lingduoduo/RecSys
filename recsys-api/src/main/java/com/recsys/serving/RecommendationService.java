package com.recsys.serving;

import com.recsys.features.DataManager;
import com.recsys.features.RedisTopKStore;
import com.recsys.models.Movie;
import com.recsys.models.RecommendationResponse;
import com.recsys.models.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public class RecommendationService extends BaseApiServlet {

    private final DataManager dataManager;
    private final RedisTopKStore topkStore;

    public RecommendationService(DataManager dataManager, RedisTopKStore topkStore) {
        this.dataManager = dataManager;
        this.topkStore = topkStore;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        prepareJson(response);
        try {
            String mode = request.getParameter("mode");
            String window = request.getParameter("window");

            int userId = requiredIntParam(request, "userId");
            User user = dataManager.getUserById(userId);
            if (user == null) {
                writeError(response, HttpServletResponse.SC_NOT_FOUND, "user not found", "userId", userId);
                return;
            }

            if ("topk".equalsIgnoreCase(mode) || "trending".equalsIgnoreCase(mode)) {
                String w = (window == null || window.isBlank()) ? "last_hour" : window.trim();
                int k = optionalIntParam(request, "k", 20, 1, 200);

                List<Movie> recs = getTopKMoviesFromRedis(w, k);
                writeJson(response, HttpServletResponse.SC_OK, new RecommendationResponse(user, recs));
                return;
            }

            String seedMovieIdStr = request.getParameter("seedMovieId");
            List<Movie> recs;
            if (seedMovieIdStr != null && !seedMovieIdStr.isBlank()) {
                int seedMovieId = requiredIntParam(request, "seedMovieId");
                Movie seed = dataManager.getMovieById(seedMovieId);
                if (seed == null) {
                    writeError(response, HttpServletResponse.SC_NOT_FOUND, "seed movie not found", "seedMovieId", seedMovieId);
                    return;
                }
                recs = dataManager.getSimilarMovies(seedMovieId);
            } else {
                recs = dataManager.getSimilarMovies(1);
            }

            writeJson(response, HttpServletResponse.SC_OK, new RecommendationResponse(user, recs));

        } catch (BadRequestException | IllegalArgumentException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "internal server error");
        }
    }

    private List<Movie> getTopKMoviesFromRedis(String window, int k) {
        if (!("last_hour".equals(window) || "last_day".equals(window) || "last_month".equals(window))) {
            throw new IllegalArgumentException("invalid window: " + window);
        }
        return topkStore.getTopKIds(window, k).stream()
                .map(idStr -> {
                    try { return dataManager.getMovieById(Integer.parseInt(idStr)); }
                    catch (NumberFormatException ignore) { return null; }
                })
                .filter(Objects::nonNull)
                .toList();
    }
}
