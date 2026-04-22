package com.recsys.features;

import com.recsys.models.Movie;
import com.recsys.models.Rating;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Collectors;

public class CandidateGenerator {

    private final DataManager dataManager;
    private final Map<Integer, float[]> movieEmbeddings;
    private final Map<Integer, float[]> userEmbeddings;

    public CandidateGenerator(DataManager dataManager) {
        this.dataManager = dataManager;
        this.movieEmbeddings = DataLoader.loadMovieEmbeddings();
        this.userEmbeddings = DataLoader.loadUserEmbeddings();
    }

    // Genre-based: for each genre on the seed movie, pull top-rated candidates,
    // deduplicate via map, remove the seed itself.
    public List<Movie> byGenre(Movie seed, int limitPerGenre) {
        Map<Integer, Movie> candidates = new LinkedHashMap<>();
        for (String genre : seed.genres()) {
            for (Movie m : dataManager.getMoviesByGenre(genre, limitPerGenre)) {
                candidates.put(m.id(), m);
            }
        }
        candidates.remove(seed.id());
        return List.copyOf(candidates.values());
    }

    // Multi-strategy: genres derived from user history + global top-rated + latest releases,
    // with already-watched movies excluded.
    public List<Movie> byUserHistory(int userId, int limitPerGenre) {
        List<Rating> history = dataManager.getRatingsByUser(userId);
        Set<Integer> watched = history.stream().map(Rating::movieId).collect(Collectors.toSet());

        Set<String> genres = new HashSet<>();
        for (Rating r : history) {
            Movie m = dataManager.getMovieById(r.movieId());
            if (m != null) genres.addAll(m.genres());
        }

        Map<Integer, Movie> candidates = new LinkedHashMap<>();
        for (String genre : genres) {
            for (Movie m : dataManager.getMoviesByGenre(genre, limitPerGenre)) {
                candidates.put(m.id(), m);
            }
        }
        for (Movie m : dataManager.getTopRatedMovies(100)) candidates.put(m.id(), m);
        for (Movie m : dataManager.getLatestMovies(100)) candidates.put(m.id(), m);
        watched.forEach(candidates::remove);
        return List.copyOf(candidates.values());
    }

    // Embedding-based: scores all movies with classpath embeddings against the user vector,
    // returns top-k by cosine similarity. Precomputes user normSq; min-heap gives O(n log k).
    public List<Movie> byEmbedding(int userId, int k) {
        float[] userVec = userEmbeddings.get(userId);
        if (userVec == null) return List.of();

        Set<Integer> watched = dataManager.getRatingsByUser(userId).stream()
                .map(Rating::movieId).collect(Collectors.toSet());

        double userNormSq = VectorMath.normSq(userVec);
        PriorityQueue<ScoredId> best = new PriorityQueue<>(Comparator.comparingDouble(ScoredId::score));

        for (Map.Entry<Integer, float[]> entry : movieEmbeddings.entrySet()) {
            int movieId = entry.getKey();
            if (watched.contains(movieId)) continue;
            double score = VectorMath.cosine(userVec, userNormSq, entry.getValue());
            if (score == Double.NEGATIVE_INFINITY) continue;
            if (best.size() < k) {
                best.offer(new ScoredId(movieId, score));
            } else if (score > best.peek().score()) {
                best.poll();
                best.offer(new ScoredId(movieId, score));
            }
        }

        return best.stream()
                .sorted(Comparator.comparingDouble(ScoredId::score).reversed())
                .map(s -> dataManager.getMovieById(s.movieId()))
                .filter(m -> m != null)
                .collect(Collectors.toUnmodifiableList());
    }

    private record ScoredId(int movieId, double score) {}
}
