package com.recsys.streaming;

import com.recsys.features.DataManager;
import com.recsys.models.Movie;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class OnlineRecommendationEngine {
    private static final Set<String> ALLOWED_WINDOWS = Set.of("last_hour", "last_day", "last_month");

    private final DataManager dataManager;
    private final TrendingStore topkStore;
    private final RecentHistoryStore onlineFeatureStore;

    public OnlineRecommendationEngine(DataManager dataManager,
                                      TrendingStore topkStore,
                                      RecentHistoryStore onlineFeatureStore) {
        this.dataManager = dataManager;
        this.topkStore = topkStore;
        this.onlineFeatureStore = onlineFeatureStore;
    }

    public OnlineRecommendationResult recommend(int userId, String window, int k) {
        String normalizedWindow = normalizeWindow(window);
        List<Integer> recentMovieIds = onlineFeatureStore.getRecentMovieIds(userId, 3);
        List<Integer> trendingMovieIds = parseMovieIds(topkStore.getTopKIds(normalizedWindow, Math.max(k * 4, 12)));

        Map<Integer, Double> scores = new HashMap<>();
        Set<Integer> exclude = new HashSet<>(recentMovieIds);

        scoreByRecentHistory(recentMovieIds, scores, exclude);
        scoreByTrending(trendingMovieIds, scores, exclude);

        List<Movie> recentMovies = mapMovies(recentMovieIds);
        List<Movie> trendingMovies = mapMovies(trendingMovieIds);
        List<Movie> recommendations = scores.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> dataManager.getMovieById(entry.getKey()))
                .filter(Objects::nonNull)
                .limit(k)
                .toList();

        return new OnlineRecommendationResult(normalizedWindow, recentMovies, trendingMovies, recommendations);
    }

    private void scoreByRecentHistory(List<Integer> recentMovieIds,
                                      Map<Integer, Double> scores,
                                      Set<Integer> exclude) {
        for (int i = 0; i < recentMovieIds.size(); i++) {
            int seedMovieId = recentMovieIds.get(i);
            double recencyBoost = 30.0 + (i * 8.0);
            List<Movie> similarMovies = dataManager.getSimilarMovies(seedMovieId);
            int limit = Math.min(similarMovies.size(), 12);
            for (int rank = 0; rank < limit; rank++) {
                Movie movie = similarMovies.get(rank);
                if (exclude.contains(movie.id())) continue;
                scores.merge(movie.id(), recencyBoost - rank, Double::sum);
            }
        }
    }

    private void scoreByTrending(List<Integer> trendingMovieIds,
                                 Map<Integer, Double> scores,
                                 Set<Integer> exclude) {
        for (int rank = 0; rank < trendingMovieIds.size(); rank++) {
            int movieId = trendingMovieIds.get(rank);
            if (exclude.contains(movieId)) continue;
            double trendScore = Math.max(2.0, 20.0 - rank);
            scores.merge(movieId, trendScore, Double::sum);
        }
    }

    private List<Movie> mapMovies(List<Integer> movieIds) {
        List<Movie> movies = new ArrayList<>(movieIds.size());
        for (Integer movieId : movieIds) {
            Movie movie = dataManager.getMovieById(movieId);
            if (movie != null) {
                movies.add(movie);
            }
        }
        return List.copyOf(movies);
    }

    private List<Integer> parseMovieIds(List<String> rawIds) {
        List<Integer> movieIds = new ArrayList<>(rawIds.size());
        for (String rawId : rawIds) {
            try {
                movieIds.add(Integer.parseInt(rawId));
            } catch (NumberFormatException ignore) {
                // Ignore malformed IDs from Redis so the demo remains inspectable.
            }
        }
        return List.copyOf(movieIds);
    }

    private static String normalizeWindow(String window) {
        String normalized = (window == null || window.isBlank()) ? "last_hour" : window.trim();
        if (!ALLOWED_WINDOWS.contains(normalized)) {
            throw new IllegalArgumentException("invalid window: " + normalized);
        }
        return normalized;
    }

    public record OnlineRecommendationResult(String window,
                                             List<Movie> recentMovies,
                                             List<Movie> trendingMovies,
                                             List<Movie> recommendations) {}
}
