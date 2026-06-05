package com.recsys.serving;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.infrastructure.DataManager;
import com.recsys.infrastructure.vectordb.CandidateGenerator;
import com.recsys.model.Movie;
import com.recsys.model.RecommendationResponse;
import com.recsys.model.User;
import com.recsys.streaming.TrendingStore;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class RecommendationService extends BaseApiService {

    private final DataManager dataManager;
    private final CandidateGenerator candidates;
    private final TrendingStore topkStore;

    public RecommendationService(DataManager dataManager, CandidateGenerator candidates, TrendingStore topkStore) {
        this.dataManager = dataManager;
        this.candidates = candidates;
        this.topkStore = topkStore;
    }

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
        return HttpResponse.of(CompletableFuture.supplyAsync(() -> {
            try {
                String mode = ctx.queryParam("mode");
                String window = ctx.queryParam("window");
                int userId = requiredIntParam(ctx, "userId");
                User user = dataManager.getUserById(userId);
                if (user == null) return writeError(HttpStatus.NOT_FOUND, "user not found", "userId", userId);

                if ("topk".equalsIgnoreCase(mode) || "trending".equalsIgnoreCase(mode)) {
                    String w = (window == null || window.isBlank()) ? "last_hour" : window.trim();
                    int k = optionalIntParam(ctx, "k", 20, 1, 200);
                    return writeJson(HttpStatus.OK, new RecommendationResponse(user, getTopKMovies(w, k)));
                }

                if ("embedding".equalsIgnoreCase(mode)) {
                    int k = optionalIntParam(ctx, "k", 20, 1, 200);
                    List<Movie> recs = candidates.byEmbedding(userId, k);
                    if (recs.isEmpty())
                        return writeError(HttpStatus.NOT_FOUND, "no embedding found for user", "userId", userId);
                    return writeJson(HttpStatus.OK, new RecommendationResponse(user, recs));
                }

                String seedStr = ctx.queryParam("seedMovieId");
                List<Movie> recs;
                if (seedStr != null && !seedStr.isBlank()) {
                    int seedMovieId = requiredIntParam(ctx, "seedMovieId");
                    Movie seed = dataManager.getMovieById(seedMovieId);
                    if (seed == null)
                        return writeError(HttpStatus.NOT_FOUND, "seed movie not found", "seedMovieId", seedMovieId);
                    recs = candidates.byGenre(seed, 100);
                } else {
                    recs = candidates.byUserHistory(userId, 20);
                }
                return writeJson(HttpStatus.OK, new RecommendationResponse(user, recs));

            } catch (BadRequestException | IllegalArgumentException e) {
                return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
            } catch (Exception e) {
                log.error("Unexpected error in RecommendationService", e);
                return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
            }
        }, ctx.blockingTaskExecutor()));
    }

    private List<Movie> getTopKMovies(String window, int k) {
        if (!("last_hour".equals(window) || "last_day".equals(window) || "last_month".equals(window))) {
            throw new IllegalArgumentException("invalid window: " + window);
        }
        return topkStore.getTopKIds(window, k).stream()
                .map(id -> {
                    try { return dataManager.getMovieById(Integer.parseInt(id)); }
                    catch (NumberFormatException e) { return null; }
                })
                .filter(Objects::nonNull).toList();
    }
}
