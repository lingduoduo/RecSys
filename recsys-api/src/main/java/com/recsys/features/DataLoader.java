package com.recsys.features;

import com.recsys.models.Movie;
import com.recsys.models.Rating;
import com.recsys.models.User;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class DataLoader {

    private static final int SIMILAR_MOVIES_LIMIT = 5;

    public static Map<Integer, Movie> loadMovies() {
        Map<Integer, Movie> movies = new LinkedHashMap<>();
        try (BufferedReader reader = openResource("/com/recsys/data/movies.txt")) {
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
        return Collections.unmodifiableMap(movies);
    }

    public static Map<Integer, User> loadUsers() {
        Map<Integer, User> users = new LinkedHashMap<>();
        try (BufferedReader reader = openResource("/com/recsys/data/users.txt")) {
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
        return Collections.unmodifiableMap(users);
    }

    public static List<Rating> loadRatings() {
        List<Rating> ratings = new ArrayList<>();
        try (BufferedReader reader = openResource("/com/recsys/data/ratings.txt")) {
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
        return Collections.unmodifiableList(ratings);
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
            List<Movie> similar = entry.getValue().entrySet().stream()
                    .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                    .limit(SIMILAR_MOVIES_LIMIT)
                    .map(e -> movies.get(e.getKey()))
                    .filter(Objects::nonNull)
                    .toList();
            if (!similar.isEmpty()) {
                similarMovies.put(entry.getKey(), similar);
            }
        }
        return Collections.unmodifiableMap(similarMovies);
    }

    public static Map<Integer, float[]> loadMovieEmbeddings() {
        return loadEmbeddings("/com/recsys/data/embeddings.txt", "movieId");
    }

    public static Map<Integer, float[]> loadUserEmbeddings() {
        return loadEmbeddings("/com/recsys/data/user_embeddings.txt", "userId");
    }

    private static Map<Integer, float[]> loadEmbeddings(String path, String idColumn) {
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
        return Collections.unmodifiableMap(out);
    }

    private static BufferedReader openResource(String path) {
        InputStream is = DataLoader.class.getResourceAsStream(path);
        if (is == null) throw new RuntimeException("classpath resource not found: " + path);
        return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
    }
}
