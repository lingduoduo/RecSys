package com.recsys.application.online;

import com.recsys.application.pagination.RecommendationPaginationCoordinator;
import com.recsys.application.pagination.RecommendationPaginationCoordinator.DecodedRequest;
import com.recsys.application.pagination.RecommendationPaginationCoordinator.RecommendationPage;
import com.recsys.application.recommendation.RecommendationPipeline;
import com.recsys.domain.online.OnlineRecommendationRequest;
import com.recsys.domain.online.OnlineRecommendationResult;
import com.recsys.domain.item.Movie;
import com.recsys.domain.item.RankedMovie;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.recommendation.RecommendationResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class OnlineBlendingPipeline implements RecommendationPipeline {

    private final OnlineRecommendationService recommendationService;
    private final RecommendationPaginationCoordinator pagination;
    private final int maxCandidates;

    public OnlineBlendingPipeline(
            OnlineRecommendationService recommendationService,
            RecommendationPaginationCoordinator pagination,
            int maxCandidates
    ) {
        this.recommendationService =
                Objects.requireNonNull(recommendationService, "recommendationService");
        this.pagination = Objects.requireNonNull(pagination, "pagination");
        this.maxCandidates = maxCandidates;
    }

    @Override
    public RecommendationResult recommend(RecommendationQuery query) {
        DecodedRequest decoded = pagination.decode(query);
        int userId;
        try {
            userId = Integer.parseInt(query.userId());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "userId must be numeric for online path: " + query.userId());
        }
        OnlineRecommendationResult online = recommendationService.recommend(
                new OnlineRecommendationRequest(userId, null, maxCandidates));
        List<RankedMovie> ranked = toRanked(online.recommendations()).stream()
                .filter(movie -> !query.excludedItemIds().contains(movie.itemId()))
                .sorted(Comparator.comparingDouble(RankedMovie::score).reversed()
                        .thenComparing(RankedMovie::itemId))
                .toList();
        boolean sourceTruncated = online.recommendations().size() == maxCandidates;
        RecommendationPage page = pagination.page(decoded, ranked, sourceTruncated);
        Map<String, String> trace = Map.of(
                "strategy", online.strategy() != null ? online.strategy() : "online",
                "window",   online.window()   != null ? online.window()   : "");
        return new RecommendationResult(
                query.userId(), page.items(), page.nextCursor(), page.hasMore(), trace);
    }

    private static List<RankedMovie> toRanked(List<Movie> movies) {
        int n = movies.size();
        List<RankedMovie> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Movie m = movies.get(i);
            double score = n > 0 ? (double) (n - i) / n : 0.0;
            result.add(new RankedMovie(String.valueOf(m.id()), score, i + 1, Map.of()));
        }
        return result;
    }
}
