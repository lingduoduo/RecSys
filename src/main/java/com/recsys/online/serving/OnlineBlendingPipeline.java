package com.recsys.online.serving;

import com.recsys.domain.Movie;
import com.recsys.domain.RankedMovie;
import com.recsys.domain.RecommendationQuery;
import com.recsys.domain.RecommendationResult;
import com.recsys.service.recommendation.RecommendationPipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class OnlineBlendingPipeline implements RecommendationPipeline {

    private final OnlineRecommendationService recommendationService;

    public OnlineBlendingPipeline(OnlineRecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @Override
    public RecommendationResult recommend(RecommendationQuery query) {
        int userId;
        try {
            userId = Integer.parseInt(query.userId());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "userId must be numeric for online path: " + query.userId());
        }
        OnlineRecommendationResult online = recommendationService.recommend(
                new OnlineRecommendationRequest(userId, null, query.limit()));
        Map<String, String> trace = Map.of(
                "strategy", online.strategy() != null ? online.strategy() : "online",
                "window",   online.window()   != null ? online.window()   : "");
        return new RecommendationResult(
                query.userId(), toRanked(online.recommendations()), null, trace);
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
