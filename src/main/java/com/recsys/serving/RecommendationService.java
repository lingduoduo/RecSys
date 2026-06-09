package com.recsys.serving;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.infrastructure.DataManager;
import com.recsys.domain.Movie;
import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.domain.RecommendationResponse;
import com.recsys.domain.User;
import com.recsys.service.retrieval.MultiChannelRecallService;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class RecommendationService extends BaseApiService {

    private static final int RECALL_MULTIPLIER = 3;

    private final DataManager dataManager;
    private final MultiChannelRecallService recallService;

    public RecommendationService(DataManager dataManager, MultiChannelRecallService recallService) {
        this.dataManager = dataManager;
        this.recallService = recallService;
    }

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
        return HttpResponse.of(CompletableFuture.supplyAsync(() -> {
            try {
                int userId = requiredIntParam(ctx, "userId");
                User user = dataManager.getUserById(userId);
                if (user == null) return writeError(HttpStatus.NOT_FOUND, "user not found", "userId", userId);

                int k = optionalIntParam(ctx, "k", 20, 1, 100);
                Set<String> excludedItemIds = dataManager.getWatchedMovieIds(userId).stream()
                        .map(String::valueOf)
                        .collect(Collectors.toSet());
                RecommendationQuery query = new RecommendationQuery(
                        String.valueOf(userId), k, excludedItemIds, null);

                List<MovieCandidate> candidates = recallService.recall(query, k * RECALL_MULTIPLIER);
                List<Movie> movies = candidates.stream()
                        .map(c -> {
                            try { return dataManager.getMovieById(Integer.parseInt(c.itemId())); }
                            catch (NumberFormatException e) { return null; }
                        })
                        .filter(Objects::nonNull)
                        .toList();

                return writeJson(HttpStatus.OK, new RecommendationResponse(user, movies));

            } catch (BadRequestException | IllegalArgumentException e) {
                return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
            } catch (Exception e) {
                log.error("Unexpected error in RecommendationService", e);
                return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
            }
        }, ctx.blockingTaskExecutor()));
    }
}
