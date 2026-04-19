package com.recsys.serving;

import com.recsys.features.RedisEmbeddingStore;
import com.recsys.features.VectorMath;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public class SimilarMovieService extends BaseApiServlet {

    private final RedisEmbeddingStore store;

    public SimilarMovieService(RedisEmbeddingStore store) {
        this.store = store;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        prepareJson(response);
        try {
            String movieIdStr = request.getParameter("movieId");
            if (movieIdStr == null || movieIdStr.isBlank()) {
                writeError(response, HttpServletResponse.SC_BAD_REQUEST, "missing required query parameter: movieId");
                return;
            }
            int movieId = Integer.parseInt(movieIdStr);

            int k = 10;
            String kStr = request.getParameter("k");
            if (kStr != null && !kStr.isBlank()) k = Integer.parseInt(kStr);
            if (k <= 0) k = 10;
            if (k > 200) k = 200;

            float[] queryVec = store.getMovieEmbedding(movieId);
            if (queryVec == null) {
                writeError(response, HttpServletResponse.SC_NOT_FOUND, "embedding not found for movieId", "movieId", movieId);
                return;
            }

            List<Integer> candidates = new ArrayList<>(store.scanMovieIds(5000));
            candidates.removeIf(candId -> candId == movieId);
            Map<Integer, float[]> embeddings = store.getMovieEmbeddings(candidates);
            PriorityQueue<ScoredMovie> best = new PriorityQueue<>(
                    Comparator.comparingDouble(ScoredMovie::score)
            );

            for (int candId : candidates) {
                float[] v = embeddings.get(candId);
                if (v == null) continue;
                double score = VectorMath.cosine(queryVec, v);
                if (score == Double.NEGATIVE_INFINITY) continue;

                ScoredMovie scoredMovie = new ScoredMovie(candId, score);
                if (best.size() < k) {
                    best.offer(scoredMovie);
                } else if (score > best.peek().score()) {
                    best.poll();
                    best.offer(scoredMovie);
                }
            }

            List<ScoredMovie> scored = new ArrayList<>(best);
            scored.sort(Comparator.comparingDouble(ScoredMovie::score).reversed());

            writeJson(response, HttpServletResponse.SC_OK, new SimilarMoviesResult(movieId, scored));

        } catch (NumberFormatException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid numeric parameter format");
        } catch (Exception e) {
            e.printStackTrace();
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "internal server error");
        }
    }

    public record ScoredMovie(int movieId, double score) {}
    public record SimilarMoviesResult(int movieId, List<ScoredMovie> similar) {}
}
