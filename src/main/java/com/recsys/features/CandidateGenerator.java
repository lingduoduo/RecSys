package com.recsys.features;

import com.recsys.models.Movie;
import com.recsys.models.Rating;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class CandidateGenerator {

    private final DataManager dataManager;
    private final Map<Integer, float[]> movieEmbeddings;
    private final Map<Integer, float[]> userEmbeddings;
    private final VectorIndex embeddingIndex;

    public CandidateGenerator(DataManager dataManager) {
        this.dataManager = dataManager;
        this.movieEmbeddings = DataLoader.loadMovieEmbeddings();
        this.userEmbeddings = DataLoader.loadUserEmbeddings();
        this.embeddingIndex = createEmbeddingIndex(movieEmbeddings);
        System.out.printf("[CandidateGenerator] embedding backend=%s, movies=%d, users=%d%n",
                embeddingIndex.name(), movieEmbeddings.size(), userEmbeddings.size());
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

    // Embedding-based retrieval delegates vector search to the selected index.
    // Backends are configured with RECSYS_VECTOR_BACKEND or -Drecsys.vector.backend:
    // lsh (default) or exact. FAISS belongs behind this interface for Linux/JNI deployments.
    public List<Movie> byEmbedding(int userId, int k) {
        float[] userVec = userEmbeddings.get(userId);
        if (userVec == null) return List.of();

        Set<Integer> watched = dataManager.getRatingsByUser(userId).stream()
                .map(Rating::movieId).collect(Collectors.toSet());

        return embeddingIndex.search(userVec, k, watched).stream()
                .map(s -> dataManager.getMovieById(s.id()))
                .filter(m -> m != null)
                .collect(Collectors.toUnmodifiableList());
    }

    private static VectorIndex createEmbeddingIndex(Map<Integer, float[]> embeddings) {
        if (embeddings.isEmpty()) return new ExactVectorIndex(Map.of());

        String backend = System.getProperty("recsys.vector.backend");
        if (backend == null || backend.isBlank()) {
            backend = System.getenv().getOrDefault("RECSYS_VECTOR_BACKEND", "lsh");
        }

        return switch (backend.trim().toLowerCase()) {
            case "exact", "flat" -> new ExactVectorIndex(embeddings);
            case "lsh", "ann" -> new LshVectorIndex(embeddings);
            case "faiss" -> {
                System.err.println("[CandidateGenerator] FAISS backend requested, but native Java FAISS is not enabled in the portable build. Falling back to LSH.");
                yield new LshVectorIndex(embeddings);
            }
            default -> throw new IllegalArgumentException("Unknown vector backend: " + backend);
        };
    }
}
