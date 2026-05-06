package com.recsys.serving;

import com.recsys.features.ExactVectorIndex;
import com.recsys.features.DataManager;
import com.recsys.features.RedisEmbeddingStore;
import com.recsys.features.SearchResult;
import com.recsys.models.Movie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SimilarMovieService extends BaseApiServlet {

    private static final int LIMIT_PER_GENRE = 50;
    private static final int RECALL_MULTIPLIER = 5;

    private final RedisEmbeddingStore store;
    private final DataManager dataManager = DataManager.getInstance();

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

            Set<Integer> candidateIds = selectCandidates(movieId, k);
            Map<Integer, float[]> embeddings = store.getEmbeddings(candidateIds);
            List<ScoredMovie> scored = ExactVectorIndex.search(embeddings, queryVec, k, Set.of(movieId))
                    .stream()
                    .map(SimilarMovieService::toScoredMovie)
                    .toList();

            writeJson(response, HttpServletResponse.SC_OK, new SimilarMoviesResult(movieId, scored));

        } catch (BadRequestException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error in SimilarMovieService", e);
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "internal server error");
        }
    }

    private static ScoredMovie toScoredMovie(SearchResult result) {
        return new ScoredMovie(result.id(), result.score());
    }

    private Set<Integer> selectCandidates(int movieId, int k) {
        Set<Integer> candidates = new LinkedHashSet<>();
        int maxCandidates = k * RECALL_MULTIPLIER;

        for (Movie movie : dataManager.getSimilarMovies(movieId)) {
            candidates.add(movie.id());
            if (candidates.size() >= maxCandidates) {
                return candidates;
            }
        }

        Movie seed = dataManager.getMovieById(movieId);
        if (seed != null) {
            for (String genre : seed.genres()) {
                for (Movie movie : dataManager.getMoviesByGenre(genre, LIMIT_PER_GENRE)) {
                    if (movie.id() != movieId) {
                        candidates.add(movie.id());
                    }
                    if (candidates.size() >= maxCandidates) {
                        return candidates;
                    }
                }
            }
        }

        for (Movie movie : dataManager.getTopRatedMovies(maxCandidates)) {
            if (movie.id() != movieId) {
                candidates.add(movie.id());
            }
            if (candidates.size() >= maxCandidates) {
                return candidates;
            }
        }

        return candidates;
    }

    public record ScoredMovie(int movieId, double score) {}
    public record SimilarMoviesResult(int movieId, List<ScoredMovie> similar) {}
}
