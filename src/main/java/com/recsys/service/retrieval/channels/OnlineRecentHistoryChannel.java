package com.recsys.service.retrieval.channels;

import com.recsys.domain.Movie;
import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.infrastructure.DataManager;
import com.recsys.online.store.RecentHistoryStore;
import com.recsys.service.retrieval.RecallChannel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Port-7010 behavioral recall: blends movies similar to the user's most recent watches, with a
 * recency boost favoring the latest seed. Emits rank-based scores ({@code 1/(rank+1)}) so its
 * scale matches the other channels for the quota merge's gap fill — the recency boost only
 * determines intra-channel order. Analog of 6010's GenreHistoryChannel.
 */
public class OnlineRecentHistoryChannel implements RecallChannel {

    private static final int RECENT_SEED_LIMIT = 3;
    private static final int SIMILAR_PER_SEED = 12;

    private final RecentHistoryStore recentHistoryStore;
    private final DataManager dataManager;

    public OnlineRecentHistoryChannel(RecentHistoryStore recentHistoryStore, DataManager dataManager) {
        this.recentHistoryStore = Objects.requireNonNull(recentHistoryStore, "recentHistoryStore");
        this.dataManager = Objects.requireNonNull(dataManager, "dataManager");
    }

    @Override
    public String name() {
        return "online_recent_history";
    }

    @Override
    public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
        int userId;
        try {
            userId = Integer.parseInt(query.userId());
        } catch (NumberFormatException e) {
            return List.of();
        }

        List<Integer> recentIds = recentHistoryStore.getRecentMovieIds(userId, RECENT_SEED_LIMIT);
        if (recentIds.isEmpty()) return List.of();

        Map<Integer, Double> blended = new LinkedHashMap<>();
        for (int i = 0; i < recentIds.size(); i++) {
            double recencyBoost = 30.0 + (i * 8.0);
            List<Movie> similar = dataManager.getSimilarMovies(recentIds.get(i));
            int cap = Math.min(similar.size(), SIMILAR_PER_SEED);
            for (int rank = 0; rank < cap; rank++) {
                Movie m = similar.get(rank);
                blended.merge(m.id(), recencyBoost - rank, Double::sum);
            }
        }

        List<Integer> ranked = blended.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .limit(limit)
                .toList();

        List<MovieCandidate> out = new ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            out.add(new MovieCandidate(String.valueOf(ranked.get(i)), 1.0 / (i + 1.0), name(), Map.of()));
        }
        return out;
    }
}
