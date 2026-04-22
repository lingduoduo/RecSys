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
            int movieId = requiredIntParam(request, "movieId");
            int k = optionalIntParam(request, "k", 10, 1, 200);

            float[] queryVec = store.getEmbedding(movieId);
            if (queryVec == null) {
                writeError(response, HttpServletResponse.SC_NOT_FOUND, "embedding not found for movieId", "movieId", movieId);
                return;
            }

            Map<Integer, float[]> embeddings = store.loadAll();
            embeddings.remove(movieId);

            PriorityQueue<ScoredMovie> best = new PriorityQueue<>(
                    Comparator.comparingDouble(ScoredMovie::score)
            );

            double queryNormSq = VectorMath.normSq(queryVec);
            for (Map.Entry<Integer, float[]> entry : embeddings.entrySet()) {
                float[] v = entry.getValue();
                double score = VectorMath.cosine(queryVec, queryNormSq, v);
                if (score == Double.NEGATIVE_INFINITY) continue;

                ScoredMovie scoredMovie = new ScoredMovie(entry.getKey(), score);
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

        } catch (BadRequestException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "internal server error");
        }
    }

    public record ScoredMovie(int movieId, double score) {}
    public record SimilarMoviesResult(int movieId, List<ScoredMovie> similar) {}
}
