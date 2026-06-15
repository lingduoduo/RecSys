package com.recsys.service.retrieval.channels;

import com.recsys.domain.Movie;
import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.infrastructure.DataManager;
import com.recsys.infrastructure.redis.GlobalPopularityStore;
import com.recsys.service.retrieval.RecallChannel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PopularityChannel implements RecallChannel {

    private static final double FALLBACK_SCORE = 0.4;

    private final DataManager dataManager;
    private final GlobalPopularityStore globalPopularityStore;

    public PopularityChannel(DataManager dataManager) {
        this(dataManager, null);
    }

    public PopularityChannel(DataManager dataManager, GlobalPopularityStore globalPopularityStore) {
        this.dataManager = Objects.requireNonNull(dataManager, "dataManager");
        this.globalPopularityStore = globalPopularityStore;
    }

    @Override
    public String name() {
        return "popularity";
    }

    @Override
    public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
        if (globalPopularityStore != null) {
            try {
                List<String> ids = globalPopularityStore.getTopIds(limit);
                if (!ids.isEmpty()) {
                    List<MovieCandidate> candidates = new ArrayList<>(ids.size());
                    for (int i = 0; i < ids.size(); i++) {
                        candidates.add(new MovieCandidate(ids.get(i), 1.0 / (i + 1.0), name(), Map.of()));
                    }
                    return candidates;
                }
            } catch (RuntimeException e) {
                // Redis unavailable — fall through to DataManager fallback
            }
        }
        // DataManager fallback
        Map<Integer, Movie> deduped = new LinkedHashMap<>();
        for (Movie m : dataManager.getTopRatedMovies(limit)) deduped.put(m.id(), m);
        for (Movie m : dataManager.getLatestMovies(limit)) deduped.put(m.id(), m);
        return deduped.values().stream()
                .map(m -> new MovieCandidate(String.valueOf(m.id()), FALLBACK_SCORE, name(), Map.of()))
                .toList();
    }
}
