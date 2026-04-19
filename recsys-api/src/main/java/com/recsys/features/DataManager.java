package com.recsys.features;

import com.recsys.models.Movie;
import com.recsys.models.User;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class DataManager {

    private static final DataManager INSTANCE = new DataManager();

    private final Map<Integer, Movie> movies;
    private final Map<Integer, User> users;
    private final Map<Integer, List<Movie>> similarMovies;

    private DataManager() {
        Movie inception = new Movie(1, "Inception", 2010);
        Movie interstellar = new Movie(2, "Interstellar", 2014);
        Movie darkKnight = new Movie(3, "The Dark Knight", 2008);

        movies = Map.of(
                inception.getId(), inception,
                interstellar.getId(), interstellar,
                darkKnight.getId(), darkKnight
        );
        users = Map.of(
                123, new User(123, "Alice"),
                456, new User(456, "Bob")
        );
        similarMovies = Map.of(
                inception.getId(), List.of(interstellar, darkKnight),
                interstellar.getId(), List.of(inception, darkKnight)
        );
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
}
