package com.recsys.service.retrieval.channels;

import com.recsys.domain.MovieCandidate;
import com.recsys.domain.Rating;
import com.recsys.domain.RecommendationQuery;
import com.recsys.infrastructure.DataManager;
import com.recsys.service.retrieval.RecallChannel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

public class UserSimilarityChannel implements RecallChannel {

    static final String CHANNEL = "user_similarity";
    private static final int MAX_NEIGHBORS = 50;

    private final Map<Integer, Map<Integer, Double>> ratingsByUser;
    private final Map<Integer, Double> normsByUser;

    public UserSimilarityChannel(DataManager dataManager) {
        Objects.requireNonNull(dataManager, "dataManager");
        Map<Integer, Map<Integer, Double>> mutableRatings = new HashMap<>();
        for (Rating rating : dataManager.getAllRatings()) {
            mutableRatings
                    .computeIfAbsent(rating.userId(), ignored -> new HashMap<>())
                    .put(rating.movieId(), (double) rating.rating());
        }

        Map<Integer, Map<Integer, Double>> ratings = new HashMap<>();
        Map<Integer, Double> norms = new HashMap<>();
        for (Map.Entry<Integer, Map<Integer, Double>> entry : mutableRatings.entrySet()) {
            Map<Integer, Double> userRatings = Map.copyOf(entry.getValue());
            ratings.put(entry.getKey(), userRatings);
            norms.put(entry.getKey(), Math.sqrt(userRatings.values().stream()
                    .mapToDouble(score -> score * score)
                    .sum()));
        }

        this.ratingsByUser = Map.copyOf(ratings);
        this.normsByUser = Map.copyOf(norms);
    }

    @Override
    public String name() {
        return CHANNEL;
    }

    @Override
    public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
        Objects.requireNonNull(query, "query");
        if (limit <= 0) return List.of();

        int userId;
        try {
            userId = Integer.parseInt(query.userId());
        } catch (NumberFormatException e) {
            return List.of();
        }

        Map<Integer, Double> currentRatings = ratingsByUser.get(userId);
        if (currentRatings == null || currentRatings.isEmpty()) {
            return List.of();
        }

        Set<String> requestExcluded = query.excludedItemIds();
        List<Neighbor> neighbors = mostSimilarUsers(userId, currentRatings);
        Map<Integer, CandidateScore> scores = new LinkedHashMap<>();

        for (Neighbor neighbor : neighbors) {
            Map<Integer, Double> neighborRatings = ratingsByUser.getOrDefault(neighbor.userId(), Map.of());
            for (Map.Entry<Integer, Double> rating : neighborRatings.entrySet()) {
                int movieId = rating.getKey();
                if (currentRatings.containsKey(movieId) || requestExcluded.contains(String.valueOf(movieId))) {
                    continue;
                }
                scores.computeIfAbsent(movieId, ignored -> new CandidateScore())
                        .add(neighbor.similarity(), rating.getValue());
            }
        }

        return scores.entrySet().stream()
                .map(entry -> new MovieCandidate(
                        String.valueOf(entry.getKey()),
                        entry.getValue().score(),
                        name(),
                        Map.of("neighbors", entry.getValue().neighbors())))
                .sorted(Comparator.comparingDouble(MovieCandidate::score).reversed()
                        .thenComparing(MovieCandidate::itemId))
                .limit(limit)
                .toList();
    }

    private List<Neighbor> mostSimilarUsers(int userId, Map<Integer, Double> currentRatings) {
        PriorityQueue<Neighbor> best = new PriorityQueue<>(
                Comparator.comparingDouble(Neighbor::similarity)
                        .thenComparingInt(Neighbor::userId));

        for (Map.Entry<Integer, Map<Integer, Double>> entry : ratingsByUser.entrySet()) {
            int otherUserId = entry.getKey();
            if (otherUserId == userId) {
                continue;
            }
            double similarity = cosineSimilarity(userId, currentRatings, otherUserId, entry.getValue());
            if (similarity <= 0.0) {
                continue;
            }
            Neighbor neighbor = new Neighbor(otherUserId, similarity);
            if (best.size() < MAX_NEIGHBORS) {
                best.offer(neighbor);
            } else if (neighbor.similarity() > best.peek().similarity()) {
                best.poll();
                best.offer(neighbor);
            }
        }

        return best.stream()
                .sorted(Comparator.comparingDouble(Neighbor::similarity).reversed()
                        .thenComparingInt(Neighbor::userId))
                .toList();
    }

    private double cosineSimilarity(int userId,
                                    Map<Integer, Double> userRatings,
                                    int otherUserId,
                                    Map<Integer, Double> otherRatings) {
        double normA = normsByUser.getOrDefault(userId, 0.0);
        double normB = normsByUser.getOrDefault(otherUserId, 0.0);
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        double dotProduct = 0.0;
        Map<Integer, Double> smaller = userRatings.size() <= otherRatings.size() ? userRatings : otherRatings;
        Map<Integer, Double> larger = smaller == userRatings ? otherRatings : userRatings;
        for (Map.Entry<Integer, Double> entry : smaller.entrySet()) {
            dotProduct += entry.getValue() * larger.getOrDefault(entry.getKey(), 0.0);
        }
        return dotProduct / (normA * normB);
    }

    private record Neighbor(int userId, double similarity) {}

    private static final class CandidateScore {
        private double weightedRatings;
        private double similaritySum;
        private int neighbors;

        void add(double similarity, double rating) {
            weightedRatings += similarity * rating;
            similaritySum += similarity;
            neighbors++;
        }

        double score() {
            return weightedRatings;
        }

        int neighbors() {
            return neighbors;
        }
    }
}
