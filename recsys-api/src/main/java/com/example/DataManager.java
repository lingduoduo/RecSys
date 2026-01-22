package com.example;

import java.util.Set;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DataManager {

    private static final DataManager INSTANCE = new DataManager();

    private final Map<Integer, Movie> movies = new ConcurrentHashMap<>();
    private final Map<Integer, User> users = new ConcurrentHashMap<>();

    private DataManager() {
        // Movies
        movies.put(1, new Movie(1, "Inception", 2010));
        movies.put(2, new Movie(2, "Interstellar", 2014));
        movies.put(3, new Movie(3, "The Dark Knight", 2008));

        // Users
        users.put(123, new User(123, "Alice"));
        users.put(456, new User(456, "Bob"));
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
        if (movieId == 1) {
            return Arrays.asList(
                    movies.get(2), // Interstellar
                    movies.get(3)  // The Dark Knight
            );
        }
        if (movieId == 2) {
            return Arrays.asList(
                    movies.get(1),
                    movies.get(3)
            );
        }
        return List.of();
    }

    public Set<Integer> getAllMovieIds() {
    return movies.keySet();
    }
}
