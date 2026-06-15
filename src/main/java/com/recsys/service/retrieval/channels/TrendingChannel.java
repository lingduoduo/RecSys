package com.recsys.service.retrieval.channels;

import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.online.store.TrendingStore;
import com.recsys.service.retrieval.RecallChannel;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class TrendingChannel implements RecallChannel {

    private static final Map<String, Double> PREDEFINED_WEIGHTS = Map.of(
            "last_hour",  1.0,
            "last_day",   0.6,
            "last_month", 0.4
    );

    private final TrendingStore trendingStore;
    private final Map<String, Double> windowWeights;

    public TrendingChannel(TrendingStore trendingStore) {
        this(trendingStore, List.of("last_hour"));
    }

    public TrendingChannel(TrendingStore trendingStore, List<String> windows) {
        this.trendingStore = Objects.requireNonNull(trendingStore, "trendingStore");
        Map<String, Double> weights = new LinkedHashMap<>();
        for (String w : Objects.requireNonNull(windows, "windows")) {
            Objects.requireNonNull(w, "windows element");
            weights.put(w, PREDEFINED_WEIGHTS.getOrDefault(w, 1.0));
        }
        this.windowWeights = Map.copyOf(weights);
    }

    @Override
    public String name() {
        return "trending";
    }

    @Override
    public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
        Map<String, Double> blended = new HashMap<>();
        for (Map.Entry<String, Double> entry : windowWeights.entrySet()) {
            List<String> ids = trendingStore.getTopKIds(entry.getKey(), limit);
            double weight = entry.getValue();
            for (int i = 0; i < ids.size(); i++) {
                blended.merge(ids.get(i), weight * (1.0 / (i + 1.0)), Double::sum);
            }
        }
        return blended.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> new MovieCandidate(e.getKey(), e.getValue(), name(), Map.of()))
                .toList();
    }
}
