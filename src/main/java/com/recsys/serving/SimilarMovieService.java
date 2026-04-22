package com.recsys.serving;

import com.recsys.features.ExactVectorIndex;
import com.recsys.features.RedisEmbeddingStore;
import com.recsys.features.SearchResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
            List<ScoredMovie> scored = new ExactVectorIndex(embeddings)
                    .search(queryVec, k, Set.of(movieId))
                    .stream()
                    .map(SimilarMovieService::toScoredMovie)
                    .toList();

            writeJson(response, HttpServletResponse.SC_OK, new SimilarMoviesResult(movieId, scored));

        } catch (BadRequestException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "internal server error");
        }
    }

    private static ScoredMovie toScoredMovie(SearchResult result) {
        return new ScoredMovie(result.id(), result.score());
    }

    public record ScoredMovie(int movieId, double score) {}
    public record SimilarMoviesResult(int movieId, List<ScoredMovie> similar) {}
}
