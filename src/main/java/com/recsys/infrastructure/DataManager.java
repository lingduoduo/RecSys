package com.recsys.infrastructure;

import com.recsys.model.Movie;
import com.recsys.model.Rating;
import com.recsys.model.User;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class DataManager {

    private static final DataManager INSTANCE;
    static {
        try {
            INSTANCE = new DataManager();
        } catch (RuntimeException e) {
            throw new ExceptionInInitializerError(
                    new RuntimeException("DataManager failed to initialize: " + e.getMessage(), e));
        }
    }

    private final Map<Integer, Movie> movies;
    private final Map<Integer, User> users;
    private final List<Rating> ratings;
    private final Map<Integer, List<Rating>> ratingsByUser;
    private final Map<Integer, Set<Integer>> watchedByUser;
    private final Map<Integer, List<Movie>> similarMovies;
    private final Map<String, List<Movie>> moviesByGenre;
    private final List<Movie> topRatedMovies;
    private final List<Movie> latestMovies;

    private DataManager() {
        movies = DataLoader.loadMovies();
        users = DataLoader.loadUsers();
        ratings = DataLoader.loadRatings();
        ratingsByUser = ratings.stream()
                .collect(Collectors.groupingBy(Rating::userId))
                .entrySet()
                .stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue())
                ));
        watchedByUser = ratingsByUser.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream()
                                .map(Rating::movieId)
                                .collect(Collectors.toUnmodifiableSet())
                ));
        similarMovies = DataLoader.buildSimilarMovies(movies, ratings);
        Map<Integer, Double> avgRatings = DataLoader.computeAvgRatings(ratings);
        moviesByGenre = DataLoader.buildMoviesByGenre(movies, avgRatings);
        topRatedMovies = DataLoader.buildTopRatedMovies(movies, avgRatings);
        latestMovies = DataLoader.buildLatestMovies(movies);
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

    public List<Movie> getMoviesByGenre(String genre, int limit) {
        List<Movie> all = moviesByGenre.getOrDefault(genre, List.of());
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    public List<Movie> getTopRatedMovies(int limit) {
        return topRatedMovies.size() <= limit ? topRatedMovies : topRatedMovies.subList(0, limit);
    }

    public List<Movie> getLatestMovies(int limit) {
        return latestMovies.size() <= limit ? latestMovies : latestMovies.subList(0, limit);
    }

    public Set<Integer> getAllMovieIds() {
        return movies.keySet();
    }

    public List<Rating> getRatingsByUser(int userId) {
        return ratingsByUser.getOrDefault(userId, List.of());
    }

    public Set<Integer> getWatchedMovieIds(int userId) {
        return watchedByUser.getOrDefault(userId, Set.of());
    }

    public List<Rating> getAllRatings() {
        return ratings;
    }
}
