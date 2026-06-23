package com.recsys.service.retrieval.coldstart;

import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.infrastructure.redis.GlobalPopularityStore;
import com.recsys.online.store.TrendingStore;
import com.recsys.service.retrieval.RecallChannel;
import com.recsys.service.retrieval.RecallScoring;

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
        Map<String, Double> blended = new LinkedHashMap<>();

        RecallScoring.blendWindows(blended, trendingStore, WINDOW_WEIGHTS, limit);
        RecallScoring.blendRankDecay(blended, globalPopularityStore.getTopIds(limit), POPULARITY_WEIGHT);

        return blended.entrySet().stream()
                .filter(e -> !query.excludedItemIds().contains(e.getKey()))
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> new MovieCandidate(e.getKey(), e.getValue(), name(), Map.of()))
                .toList();
    }
}
