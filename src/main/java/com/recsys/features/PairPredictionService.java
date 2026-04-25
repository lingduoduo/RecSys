package com.recsys.features;

import com.recsys.models.PredictInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PairPredictionService {

    private final Map<Integer, float[]> userEmbeddings;
    private final Map<Integer, float[]> movieEmbeddings;

    public PairPredictionService() {
        this(DataLoader.loadUserEmbeddings(), DataLoader.loadMovieEmbeddings());
    }

    public PairPredictionService(Map<Integer, float[]> userEmbeddings,
                                 Map<Integer, float[]> movieEmbeddings) {
        this.userEmbeddings = userEmbeddings;
        this.movieEmbeddings = movieEmbeddings;
    }

    public List<List<Double>> predict(List<PredictInstance> instances) {
        List<List<Double>> predictions = new ArrayList<>(instances.size());
        for (PredictInstance instance : instances) {
            int userId = instance.getUserId();
            int movieId = instance.getMovieId();
            if (userId <= 0) {
                throw new IllegalArgumentException("userId must be positive");
            }
            if (movieId <= 0) {
                throw new IllegalArgumentException("movieId must be positive");
            }

            float[] userEmbedding = userEmbeddings.get(userId);
            if (userEmbedding == null) {
                throw new IllegalArgumentException("user embedding not found for userId: " + userId);
            }

            float[] movieEmbedding = movieEmbeddings.get(movieId);
            if (movieEmbedding == null) {
                throw new IllegalArgumentException("movie embedding not found for movieId: " + movieId);
            }

            double score = VectorMath.innerProduct(userEmbedding, movieEmbedding);
            if (!Double.isFinite(score)) {
                throw new IllegalStateException("failed to score instance for userId "
                        + userId + " and movieId " + movieId);
            }

            predictions.add(List.of(score));
        }
        return List.copyOf(predictions);
    }
}
