package com.recsys.application.retrieval.coldstart;

import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.infrastructure.redis.GlobalPopularityStore;
import com.recsys.infrastructure.store.TrendingStore;
import com.recsys.application.retrieval.RecallChannel;
import com.recsys.application.retrieval.RecallScoring;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ColdStartChannel implements RecallChannel {

    private static final Map<String, Double> WINDOW_WEIGHTS = Map.of(
            "last_day",   0.7,
            "last_month", 0.5
    );
    private static final double POPULARITY_WEIGHT = 0.4;

    private final TrendingStore trendingStore;
    private final GlobalPopularityStore globalPopularityStore;

    public ColdStartChannel(TrendingStore trendingStore, GlobalPopularityStore globalPopularityStore) {
        this.trendingStore = Objects.requireNonNull(trendingStore, "trendingStore");
        this.globalPopularityStore = Objects.requireNonNull(globalPopularityStore, "globalPopularityStore");
    }

    @Override
    public String name() {
        return "cold_start";
    }

    @Override
    public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
        return recall(query, limit, false);
    }

    @Override
    public List<MovieCandidate> recallPrimary(RecommendationQuery query, int limit) {
        return recall(query, limit, true);
    }

    private List<MovieCandidate> recall(RecommendationQuery query, int limit, boolean primary) {
        Map<String, Double> blended = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : WINDOW_WEIGHTS.entrySet()) {
            List<String> ids = primary
                    ? trendingStore.getTopKIdsPrimary(entry.getKey(), limit)
                    : trendingStore.getTopKIds(entry.getKey(), limit);
            RecallScoring.blendRankDecay(blended, ids, entry.getValue());
        }
        RecallScoring.blendRankDecay(blended, primary
                ? globalPopularityStore.getTopIdsPrimary(limit)
                : globalPopularityStore.getTopIds(limit), POPULARITY_WEIGHT);

        return blended.entrySet().stream()
                .filter(e -> !query.excludedItemIds().contains(e.getKey()))
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> new MovieCandidate(e.getKey(), e.getValue(), name(), Map.of()))
                .toList();
    }
}
