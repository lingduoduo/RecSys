package com.recsys.service.retrieval;

import com.recsys.model.MovieCandidate;
import com.recsys.model.RecommendationQuery;
import com.recsys.streaming.TrendingStore;

import java.util.List;
import java.util.Map;

public class TrendingChannel implements RecallChannel {

    static final double SCORE = 0.6;

    private final TrendingStore trendingStore;

    public TrendingChannel(TrendingStore trendingStore) {
        this.trendingStore = trendingStore;
    }

    @Override
    public String name() {
        return "trending";
    }

    @Override
    public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
        return trendingStore.getTopKIds("last_hour", limit).stream()
                .map(id -> new MovieCandidate(id, SCORE, name(), Map.of()))
                .toList();
    }
}
