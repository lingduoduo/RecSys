package com.recsys.online.serving;

import com.recsys.infrastructure.vectordb.CandidateGenerator;
import com.recsys.infrastructure.DataManager;
import com.recsys.online.learner.OnlineLearner;
import com.recsys.domain.Movie;
import com.recsys.domain.User;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class OnlineRecommendationService {

    // Online behavioral signals are the primary rank signal; embedding adds a secondary boost.
    private static final double ONLINE_WEIGHT = 1.0;
    private static final double MODEL_WEIGHT  = 0.5;

    private final DataManager dataManager;
    private final OnlineRecommendationEngine onlineEngine;
    private final CandidateGenerator candidateGenerator;
    private final OnlineLearner onlineLearner;

    public OnlineRecommendationService(DataManager dataManager,
                                       OnlineRecommendationEngine onlineEngine,
                                       CandidateGenerator candidateGenerator) {
        this(dataManager, onlineEngine, candidateGenerator, new OnlineLearner());
    }

    public OnlineRecommendationService(DataManager dataManager,
                                       OnlineRecommendationEngine onlineEngine,
                                       CandidateGenerator candidateGenerator,
                                       OnlineLearner onlineLearner) {
        this.dataManager = dataManager;
        this.onlineEngine = onlineEngine;
        this.candidateGenerator = candidateGenerator;
        this.onlineLearner = onlineLearner == null ? new OnlineLearner() : onlineLearner;
    }

    public OnlineRecommendationResult recommend(OnlineRecommendationRequest request) {
        User user = requireUser(request.userId());
        int k = Math.max(1, request.k());
        int recallLimit = Math.max(k * 4, 12);

        // Online path: recent history + trending signals, fetched with headroom for blending.
        OnlineRecommendationEngine.OnlineRecommendationResult online =
                onlineEngine.recommend(request.userId(), request.window(), recallLimit);

        // Model path: embedding-based ANN recall.
        List<Movie> modelCandidates = candidateGenerator.byEmbedding(request.userId(), recallLimit);

        List<Movie> recommendations;
        String strategy;

        if (modelCandidates.isEmpty()) {
            // No user embedding available: fall back to online signals only.
            List<Movie> onlineRecs = online.recommendations();
            recommendations = onlineRecs.subList(0, Math.min(k, onlineRecs.size()));
            strategy = "online";
        } else {
            recommendations = blend(online.recommendations(), modelCandidates,
                    online.recentMovies(), k);
            strategy = "online+model";
        }

        return new OnlineRecommendationResult(
                user,
                online.window(),
                strategy,
                online.recentMovies(),
                online.trendingMovies(),
                recommendations
        );
    }

    /**
     * Merges two ranked candidate lists using normalized reciprocal-rank scores so
     * movies that rank well in both paths float to the top.
     *
     * online score for rank i of n  = ONLINE_WEIGHT * (n - i) / n
     * model score  for rank i of m  = MODEL_WEIGHT  * (m - i) / m
     *
     * Recently-watched movies are excluded from the final output.
     */
    private List<Movie> blend(List<Movie> onlineRecs,
                              List<Movie> modelCandidates,
                              List<Movie> recentMovies,
                              int k) {
        int capacity = (int) ((onlineRecs.size() + modelCandidates.size()) / 0.75f) + 2;
        Map<Integer, Movie> movieById = new HashMap<>(capacity);
        Map<Integer, Double> scores   = new HashMap<>(capacity);

        int nOnline = onlineRecs.size();
        for (int i = 0; i < nOnline; i++) {
            Movie m = onlineRecs.get(i);
            movieById.put(m.id(), m);
            scores.put(m.id(), ONLINE_WEIGHT * (nOnline - i) / (double) nOnline);
        }

        int nModel = modelCandidates.size();
        for (int i = 0; i < nModel; i++) {
            Movie m = modelCandidates.get(i);
            movieById.put(m.id(), m);
            scores.merge(m.id(), MODEL_WEIGHT * (nModel - i) / (double) nModel, Double::sum);
        }

        Set<Integer> recentIds = new java.util.HashSet<>(recentMovies.size() * 2);
        for (Movie m : recentMovies) recentIds.add(m.id());
        scores.keySet().removeIf(recentIds::contains);
        scores.replaceAll((movieId, score) -> score + onlineLearner.scoreAdjustment(movieId));

        return scores.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry::getKey))
                .map(e -> movieById.get(e.getKey()))
                .filter(Objects::nonNull)
                .limit(k)
                .toList();
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
