package com.recsys.features;

import com.recsys.models.Movie;
import com.recsys.models.Rating;
import com.recsys.models.User;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class DataManager {

    private static final DataManager INSTANCE = new DataManager();

    private final Map<Integer, Movie> movies;
    private final Map<Integer, User> users;
    private final List<Rating> ratings;
    private final Map<Integer, List<Movie>> similarMovies;

    private DataManager() {
        movies = DataLoader.loadMovies();
        users = DataLoader.loadUsers();
        ratings = DataLoader.loadRatings();
        similarMovies = DataLoader.buildSimilarMovies(movies, ratings);
    }

    public static DataManager getInstance() {
        return INSTANCE;
    }

    public Movie getMovieById(int id) {
        return movies.get(id);
    }

    public User getUserById(int userId) {
        return users.get(userId);
    }

    public List<Movie> getSimilarMovies(int movieId) {
        return similarMovies.getOrDefault(movieId, List.of());
    }

    public Set<Integer> getAllMovieIds() {
        return movies.keySet();
    }

    public List<Rating> getRatingsByUser(int userId) {
        return ratings.stream().filter(r -> r.userId() == userId).toList();
    }

    public List<Rating> getAllRatings() {
        return ratings;
    }

    // Convenience: load all embeddings from Redis for movies known to this DataManager.
    public Map<Integer, float[]> loadEmbeddings(RedisEmbeddingStore store) {
        return store.loadValidEmbeddings(movies.keySet());
    }
}
