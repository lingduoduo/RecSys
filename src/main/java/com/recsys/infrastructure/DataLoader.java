package com.recsys.infrastructure;

import com.recsys.infrastructure.vectordb.VectorMath;
import com.recsys.domain.Movie;
import com.recsys.domain.Rating;
import com.recsys.domain.User;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Collectors;

public class DataLoader {

    private static final int SIMILAR_MOVIES_LIMIT = 5;
    // Cap co-rating pairs per user to prevent O(n²) blow-up on power users.
    private static final int MAX_RATED_PER_USER = 500;
    private static final String MOVIES_RESOURCE = "/com/recsys/data/movies.txt";
    private static final String USERS_RESOURCE = "/com/recsys/data/users.txt";
    private static final String RATINGS_RESOURCE = "/com/recsys/data/ratings.txt";
    private static final String MOVIE_EMBEDDINGS_RESOURCE = "/com/recsys/data/movie_embeddings.txt";
    private static final String USER_EMBEDDINGS_RESOURCE = "/com/recsys/data/user_embeddings.txt";

    public static Map<Integer, Movie> loadMovies() {
        Map<Integer, Movie> movies = new LinkedHashMap<>();
        try (BufferedReader reader = openResource(MOVIES_RESOURCE)) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) { header = false; continue; }
                String[] parts = line.split(",", 4);
                if (parts.length < 3) continue;
                int id = Integer.parseInt(parts[0].trim());
                String title = parts[1].trim();
                int year = Integer.parseInt(parts[2].trim());
                List<String> genres = parts.length > 3
                        ? Arrays.asList(parts[3].trim().split("\\|"))
                        : List.of();
                movies.put(id, new Movie(id, title, year, genres));
            }
        } catch (IOException e) {
            throw new RuntimeException("failed to load movies.txt", e);
        }
        return Map.copyOf(movies);
    }

    public static Map<Integer, User> loadUsers() {
        Map<Integer, User> users = new LinkedHashMap<>();
        try (BufferedReader reader = openResource(USERS_RESOURCE)) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) { header = false; continue; }
                String[] parts = line.split(",", 2);
                if (parts.length < 2) continue;
                int userId = Integer.parseInt(parts[0].trim());
                String name = parts[1].trim();
                users.put(userId, new User(userId, name));
            }
        } catch (IOException e) {
            throw new RuntimeException("failed to load users.txt", e);
        }
        return Map.copyOf(users);
    }

    public static List<Rating> loadRatings() {
        List<Rating> ratings = new ArrayList<>();
        try (BufferedReader reader = openResource(RATINGS_RESOURCE)) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) { header = false; continue; }
                String[] parts = line.split(",");
                if (parts.length < 4) continue;
                int userId = Integer.parseInt(parts[0].trim());
                int movieId = Integer.parseInt(parts[1].trim());
                float rating = Float.parseFloat(parts[2].trim());
                long timestamp = Long.parseLong(parts[3].trim());
                ratings.add(new Rating(userId, movieId, rating, timestamp));
            }
        } catch (IOException e) {
            throw new RuntimeException("failed to load ratings.txt", e);
        }
        return List.copyOf(ratings);
    }

    // Derives similar-movie lists from co-rating: movies rated by the same user are
    // considered similar, ranked by co-rating frequency.
    public static Map<Integer, List<Movie>> buildSimilarMovies(
            Map<Integer, Movie> movies, List<Rating> ratings) {

        // userId -> set of movieIds that user rated
        Map<Integer, Set<Integer>> userMovies = new HashMap<>();
        for (Rating r : ratings) {
            userMovies.computeIfAbsent(r.userId(), k -> new HashSet<>()).add(r.movieId());
        }

        // movieId -> (coMovieId -> co-rating count)
        Map<Integer, Map<Integer, Integer>> coRatings = new HashMap<>();
        for (Set<Integer> rated : userMovies.values()) {
            List<Integer> ratedList = new ArrayList<>(rated);
            if (ratedList.size() > MAX_RATED_PER_USER) {
                ratedList = ratedList.subList(0, MAX_RATED_PER_USER);
            }
            for (int i = 0; i < ratedList.size(); i++) {
                for (int j = i + 1; j < ratedList.size(); j++) {
                    int m1 = ratedList.get(i);
                    int m2 = ratedList.get(j);
                    coRatings.computeIfAbsent(m1, k -> new HashMap<>()).merge(m2, 1, Integer::sum);
                    coRatings.computeIfAbsent(m2, k -> new HashMap<>()).merge(m1, 1, Integer::sum);
                }
            }
        }

        Map<Integer, List<Movie>> similarMovies = new HashMap<>();
        for (Map.Entry<Integer, Map<Integer, Integer>> entry : coRatings.entrySet()) {
            List<Movie> similar = topSimilarMovies(entry.getValue(), movies);
            if (!similar.isEmpty()) {
                similarMovies.put(entry.getKey(), List.copyOf(similar));
            }
        }
        return Map.copyOf(similarMovies);
    }

    // Builds a genre → movies index, each list sorted by average rating descending.
    public static Map<String, List<Movie>> buildMoviesByGenre(
            Map<Integer, Movie> movies, Map<Integer, Double> avgRating) {

        Map<String, List<Movie>> byGenre = new HashMap<>();
        for (Movie movie : movies.values()) {
            for (String genre : movie.genres()) {
                byGenre.computeIfAbsent(genre, k -> new ArrayList<>()).add(movie);
            }
        }

        byGenre.replaceAll((genre, list) -> {
            list.sort(Comparator.comparingDouble(
                    (Movie m) -> avgRating.getOrDefault(m.id(), 0.0)).reversed());
            return List.copyOf(list);
        });

        return Map.copyOf(byGenre);
    }

    // All movies sorted by average rating descending.
    public static List<Movie> buildTopRatedMovies(
            Map<Integer, Movie> movies, Map<Integer, Double> avgRating) {
        return movies.values().stream()
                .sorted(Comparator.comparingDouble(
                        (Movie m) -> avgRating.getOrDefault(m.id(), 0.0)).reversed())
                .collect(Collectors.toUnmodifiableList());
    }

    // All movies sorted by release year descending.
    public static List<Movie> buildLatestMovies(Map<Integer, Movie> movies) {
        return movies.values().stream()
                .sorted(Comparator.comparingInt(Movie::year).reversed())
                .collect(Collectors.toUnmodifiableList());
    }

    public static Map<Integer, Double> computeAvgRatings(List<Rating> ratings) {
        return ratings.stream().collect(Collectors.groupingBy(
                Rating::movieId, Collectors.averagingDouble(Rating::rating)));
    }

    public static Map<Integer, float[]> loadMovieEmbeddings() {
        return loadEmbeddings(MOVIE_EMBEDDINGS_RESOURCE);
    }

    public static Map<Integer, float[]> loadUserEmbeddings() {
        return loadEmbeddings(USER_EMBEDDINGS_RESOURCE);
    }

    private static Map<Integer, float[]> loadEmbeddings(String path) {
        Map<Integer, float[]> out = new LinkedHashMap<>();
        try (BufferedReader reader = openResource(path)) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) { header = false; continue; }
                int comma = line.indexOf(',');
                if (comma < 0) continue;
                int id = Integer.parseInt(line.substring(0, comma).trim());
                float[] vec = VectorMath.parseVector(line.substring(comma + 1).trim());
                out.put(id, vec);
            }
        } catch (IOException e) {
            throw new RuntimeException("failed to load " + path, e);
        }
        return Map.copyOf(out);
    }

    private static List<Movie> topSimilarMovies(Map<Integer, Integer> coRatings, Map<Integer, Movie> movies) {
        PriorityQueue<Map.Entry<Integer, Integer>> best = new PriorityQueue<>(
                Map.Entry.comparingByValue()
        );
        for (Map.Entry<Integer, Integer> entry : coRatings.entrySet()) {
            if (best.size() < SIMILAR_MOVIES_LIMIT) {
                best.offer(entry);
            } else if (entry.getValue() > best.peek().getValue()) {
                best.poll();
                best.offer(entry);
            }
        }

        return best.stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                .map(e -> movies.get(e.getKey()))
                .filter(Objects::nonNull)
                .toList();
    }

    private static BufferedReader openResource(String path) {
        InputStream is = DataLoader.class.getResourceAsStream(path);
        if (is == null) throw new RuntimeException("classpath resource not found: " + path);
        return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
    }
}
