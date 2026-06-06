package com.recsys.service.retrieval;

import com.recsys.infrastructure.DataManager;
import com.recsys.model.Movie;
import com.recsys.model.MovieCandidate;
import com.recsys.model.RecommendationQuery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PopularityChannel implements RecallChannel {

    static final double SCORE = 0.4;

    private final DataManager dataManager;

    public PopularityChannel(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    @Override
    public String name() {
        return "popularity";
    }

    @Override
    public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
        Map<Integer, Movie> deduped = new LinkedHashMap<>();
        for (Movie m : dataManager.getTopRatedMovies(limit)) deduped.put(m.id(), m);
        for (Movie m : dataManager.getLatestMovies(limit)) deduped.put(m.id(), m);
        return deduped.values().stream()
                .map(m -> new MovieCandidate(String.valueOf(m.id()), SCORE, name(), Map.of()))
                .toList();
    }
}
