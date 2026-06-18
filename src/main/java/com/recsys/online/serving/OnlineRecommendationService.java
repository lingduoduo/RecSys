package com.recsys.online.serving;

import com.recsys.domain.Movie;
import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.domain.User;
import com.recsys.infrastructure.DataManager;
import com.recsys.online.learner.OnlineLearner;
import com.recsys.online.store.RecentHistoryStore;
import com.recsys.online.store.TrendingStore;
import com.recsys.service.retrieval.multichannel.MultiChannelRecallService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Online recommendation = recall (shared MultiChannelRecallService) -> re-rank (OnlineLearner) ->
 * response snapshot (recent history + per-request trending window). Cold-start detection is handled
 * inside the recall service via the injected user-embedding store.
 */
public final class OnlineRecommendationService {

    private static final Set<String> ALLOWED_WINDOWS = Set.of("last_hour", "last_day", "last_month");
    private static final int RECENT_HISTORY_LIMIT = 3;

    private final DataManager dataManager;
    private final MultiChannelRecallService recallService;
    private final RecentHistoryStore recentHistoryStore;
    private final TrendingStore topkStore;
    private final OnlineLearner onlineLearner;

    public OnlineRecommendationService(DataManager dataManager,
                                       MultiChannelRecallService recallService,
                                       RecentHistoryStore recentHistoryStore,
                                       TrendingStore topkStore,
                                       OnlineLearner onlineLearner) {
        this.dataManager = Objects.requireNonNull(dataManager, "dataManager");
        this.recallService = Objects.requireNonNull(recallService, "recallService");
        this.recentHistoryStore = Objects.requireNonNull(recentHistoryStore, "recentHistoryStore");
        this.topkStore = Objects.requireNonNull(topkStore, "topkStore");
        this.onlineLearner = onlineLearner == null ? new OnlineLearner() : onlineLearner;
    }

    public OnlineRecommendationResult recommend(OnlineRecommendationRequest request) {
        User user = requireUser(request.userId());
        int k = Math.max(1, request.k());
        int recallLimit = Math.min(Math.max(k * 4, 12), 100);
        String window = normalizeWindow(request.window());

        List<Integer> recentIds = recentHistoryStore.getRecentMovieIds(request.userId(), RECENT_HISTORY_LIMIT);
        Set<String> excluded = new LinkedHashSet<>();
        for (int id : recentIds) excluded.add(String.valueOf(id));

        RecommendationQuery query =
                new RecommendationQuery(String.valueOf(request.userId()), recallLimit, excluded, null);
        List<MovieCandidate> candidates = recallService.recall(query, recallLimit);

        List<Movie> recentMovies = mapMovies(recentIds);
        List<Movie> trendingMovies = mapMovies(parseIds(topkStore.getTopKIds(window, k)));

        List<Movie> recommendations = rerank(candidates, excluded, k);
        if (recommendations.isEmpty()) {
            recommendations = trendingMovies.stream().limit(k).toList();
        }

        return new OnlineRecommendationResult(
                user, window, "multichannel", recentMovies, trendingMovies, recommendations);
    }

    private List<Movie> rerank(List<MovieCandidate> candidates, Set<String> excluded, int k) {
        record Scored(int movieId, double score) {}
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (MovieCandidate c : candidates) {
            if (excluded.contains(c.itemId())) continue;
            int movieId;
            try {
                movieId = Integer.parseInt(c.itemId());
            } catch (NumberFormatException e) {
                continue;
            }
            scored.add(new Scored(movieId, c.score() + onlineLearner.scoreAdjustment(movieId)));
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparingInt(Scored::movieId))
                .map(s -> dataManager.getMovieById(s.movieId()))
                .filter(Objects::nonNull)
                .limit(k)
                .toList();
    }

    private List<Movie> mapMovies(List<Integer> ids) {
        List<Movie> movies = new ArrayList<>(ids.size());
        for (int id : ids) {
            Movie m = dataManager.getMovieById(id);
            if (m != null) movies.add(m);
        }
        return List.copyOf(movies);
    }

    private static List<Integer> parseIds(List<String> raw) {
        List<Integer> ids = new ArrayList<>(raw.size());
        for (String s : raw) {
            try {
                ids.add(Integer.parseInt(s));
            } catch (NumberFormatException ignore) {
                // skip malformed ids from Redis
            }
        }
        return ids;
    }

    private static String normalizeWindow(String window) {
        String normalized = (window == null || window.isBlank()) ? "last_hour" : window.trim();
        if (!ALLOWED_WINDOWS.contains(normalized)) {
            throw new IllegalArgumentException("invalid window: " + normalized);
        }
        return normalized;
    }

    private User requireUser(int userId) {
        User user = dataManager.getUserById(userId);
        if (user == null) throw new UnknownUserException(userId);
        return user;
    }

    public static final class UnknownUserException extends RuntimeException {
        private final int userId;

        public UnknownUserException(int userId) {
            super("user not found");
            this.userId = userId;
        }

        public int userId() { return userId; }
    }
}
