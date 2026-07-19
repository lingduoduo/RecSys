package com.recsys.application.retrieval.channels;

import com.recsys.domain.item.Movie;
import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.rating.Rating;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.infrastructure.dataloading.DataManager;
import com.recsys.infrastructure.redis.GlobalPopularityStore;
import com.recsys.infrastructure.vectordb.CandidateGenerator;
import com.recsys.infrastructure.store.RecentHistoryStore;
import com.recsys.infrastructure.store.TrendingStore;
import com.recsys.application.retrieval.RecallChannel;
import com.recsys.application.retrieval.RecallScoring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The recall channels for the offline/model/online serving paths, grouped as
 * nested services. Each {@link #name()} string is the channel's wire identity —
 * it keys the per-port quota fraction maps in
 * {@link com.recsys.application.retrieval.coldstart.QuotaPolicy} and MUST NOT change.
 */
public final class Channels {

    private Channels() {}

    /** Embedding nearest-neighbour recall over the user's vector. */
    public static final class Embedding implements RecallChannel {

        private final CandidateGenerator candidateGenerator;

        public Embedding(CandidateGenerator candidateGenerator) {
            this.candidateGenerator = candidateGenerator;
        }

        @Override
        public String name() {
            return "embedding";
        }

        @Override
        public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
            int userId = Integer.parseInt(query.userId());
            List<Movie> movies = candidateGenerator.byEmbedding(userId, limit);
            // Rank-based score: 1.0 for rank-0, decaying by 1/(rank+1).
            // Preserves relative ordering from the vector index without exposing raw cosine values.
            List<String> ids = new ArrayList<>(movies.size());
            for (Movie m : movies) ids.add(String.valueOf(m.id()));
            return RecallScoring.rankScored(ids, name());
        }

        @Override
        public List<MovieCandidate> recallPrimary(RecommendationQuery query, int limit) {
            int userId = Integer.parseInt(query.userId());
            List<Movie> movies = candidateGenerator.byEmbeddingPrimary(userId, limit);
            return RecallScoring.rankScored(movies.stream().map(m -> String.valueOf(m.id())).toList(), name());
        }
    }

    /** Movies drawn from the user's genre history, at a fixed mid score. */
    public static final class GenreHistory implements RecallChannel {

        static final double SCORE = 0.5;

        private final CandidateGenerator candidateGenerator;

        public GenreHistory(CandidateGenerator candidateGenerator) {
            this.candidateGenerator = candidateGenerator;
        }

        @Override
        public String name() {
            return "genre_history";
        }

        @Override
        public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
            int userId = Integer.parseInt(query.userId());
            List<Movie> movies = candidateGenerator.byUserHistory(userId, limit);
            return movies.stream()
                    .map(m -> new MovieCandidate(String.valueOf(m.id()), SCORE, name(), Map.of()))
                    .toList();
        }

        @Override
        public List<MovieCandidate> recallPrimary(RecommendationQuery query, int limit) {
            return recall(query, limit);
        }
    }

    /** Global popularity, preferring the Redis store and falling back to DataManager. */
    public static final class Popularity implements RecallChannel {

        private static final double FALLBACK_SCORE = 0.4;

        private final DataManager dataManager;
        private final GlobalPopularityStore globalPopularityStore;

        public Popularity(DataManager dataManager) {
            this(dataManager, null);
        }

        public Popularity(DataManager dataManager, GlobalPopularityStore globalPopularityStore) {
            this.dataManager = Objects.requireNonNull(dataManager, "dataManager");
            this.globalPopularityStore = globalPopularityStore;
        }

        @Override
        public String name() {
            return "popularity";
        }

        @Override
        public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
            return recall(query, limit, false);
        }

        @Override
        public List<MovieCandidate> recallPrimary(RecommendationQuery query, int limit) {
            return recall(query, limit, true);
        }

        private List<MovieCandidate> recall(RecommendationQuery query, int limit, boolean primary) {
            if (globalPopularityStore != null) {
                try {
                    List<String> ids = primary
                            ? globalPopularityStore.getTopIdsPrimary(limit)
                            : globalPopularityStore.getTopIds(limit);
                    if (!ids.isEmpty()) {
                        return RecallScoring.rankScored(ids, name());
                    }
                } catch (RuntimeException e) {
                    if (primary) throw e;
                    // Redis unavailable — tokenless reads may use the DataManager fallback
                }
                if (primary) return List.of();
            }
            if (primary) {
                throw new IllegalStateException("Primary popularity store is not configured");
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

    /** Trending items blended across time windows with per-window weights. */
    public static final class Trending implements RecallChannel {

        private static final Map<String, Double> PREDEFINED_WEIGHTS = Map.of(
                "last_hour",  1.0,
                "last_day",   0.6,
                "last_month", 0.4
        );

        private final TrendingStore trendingStore;
        private final Map<String, Double> windowWeights;

        public Trending(TrendingStore trendingStore) {
            this(trendingStore, List.of("last_hour"));
        }

        public Trending(TrendingStore trendingStore, List<String> windows) {
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
            return recall(query, limit, false);
        }

        @Override
        public List<MovieCandidate> recallPrimary(RecommendationQuery query, int limit) {
            return recall(query, limit, true);
        }

        private List<MovieCandidate> recall(RecommendationQuery query, int limit, boolean primary) {
            Map<String, Double> blended = new HashMap<>();
            for (Map.Entry<String, Double> entry : windowWeights.entrySet()) {
                List<String> ids = primary
                        ? trendingStore.getTopKIdsPrimary(entry.getKey(), limit)
                        : trendingStore.getTopKIds(entry.getKey(), limit);
                RecallScoring.blendRankDecay(blended, ids, entry.getValue());
            }
            return blended.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(limit)
                    .map(e -> new MovieCandidate(e.getKey(), e.getValue(), name(), Map.of()))
                    .toList();
        }
    }

    /** Collaborative filtering: candidates from the most similar users' ratings. */
    public static final class UserSimilarity implements RecallChannel {

        static final String CHANNEL = "user_similarity";
        private static final int MAX_NEIGHBORS = 50;

        private final Map<Integer, Map<Integer, Double>> ratingsByUser;
        private final Map<Integer, Double> normsByUser;

        public UserSimilarity(DataManager dataManager) {
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

            OptionalInt parsedUserId = RecallScoring.parseUserId(query);
            if (parsedUserId.isEmpty()) return List.of();
            int userId = parsedUserId.getAsInt();

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
                    .sorted(RecallScoring.BY_SCORE_DESC)
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<MovieCandidate> recallPrimary(RecommendationQuery query, int limit) {
            return recall(query, limit);
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

    /**
     * Port-7010 behavioral recall: blends movies similar to the user's most recent watches, with a
     * recency boost favoring the latest seed. Emits rank-based scores ({@code 1/(rank+1)}) so its
     * scale matches the other channels for the quota merge's gap fill — the recency boost only
     * determines intra-channel order. Analog of 6010's {@link GenreHistory}.
     */
    public static final class OnlineRecentHistory implements RecallChannel {

        private static final int RECENT_SEED_LIMIT = 3;
        private static final int SIMILAR_PER_SEED = 12;

        private final RecentHistoryStore recentHistoryStore;
        private final DataManager dataManager;

        public OnlineRecentHistory(RecentHistoryStore recentHistoryStore, DataManager dataManager) {
            this.recentHistoryStore = Objects.requireNonNull(recentHistoryStore, "recentHistoryStore");
            this.dataManager = Objects.requireNonNull(dataManager, "dataManager");
        }

        @Override
        public String name() {
            return "online_recent_history";
        }

        @Override
        public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
            return recall(query, limit, false);
        }

        @Override
        public List<MovieCandidate> recallPrimary(RecommendationQuery query, int limit) {
            return recall(query, limit, true);
        }

        private List<MovieCandidate> recall(RecommendationQuery query, int limit, boolean primary) {
            OptionalInt parsedUserId = RecallScoring.parseUserId(query);
            if (parsedUserId.isEmpty()) return List.of();
            int userId = parsedUserId.getAsInt();

            List<Integer> recentIds = primary
                    ? recentHistoryStore.getRecentMovieIdsPrimary(userId, RECENT_SEED_LIMIT)
                    : recentHistoryStore.getRecentMovieIds(userId, RECENT_SEED_LIMIT);
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

            List<String> ranked = blended.entrySet().stream()
                    .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed()
                            .thenComparing(Map.Entry::getKey))
                    .map(e -> String.valueOf(e.getKey()))
                    .limit(limit)
                    .toList();

            return RecallScoring.rankScored(ranked, name());
        }
    }
}
