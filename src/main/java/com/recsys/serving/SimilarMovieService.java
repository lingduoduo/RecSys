package com.recsys.serving;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.infrastructure.DataManager;
import com.recsys.infrastructure.vectordb.EmbeddingStore;
import com.recsys.infrastructure.vectordb.ExactVectorIndex;
import com.recsys.domain.Movie;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class SimilarMovieService extends BaseApiService {

    private static final int LIMIT_PER_GENRE = 50;
    private static final int RECALL_MULTIPLIER = 5;

    private final EmbeddingStore store;
    private final DataManager dataManager;

    public SimilarMovieService(EmbeddingStore store) {
        this(store, DataManager.getInstance());
    }

    public SimilarMovieService(EmbeddingStore store, DataManager dataManager) {
        this.store = store;
        this.dataManager = dataManager;
    }

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
        return HttpResponse.of(CompletableFuture.supplyAsync(() -> {
            try {
                int movieId = requiredIntParam(ctx, "movieId");
                int k = optionalIntParam(ctx, "k", 10, 1, 200);
                float[] queryVec = store.getEmbedding(movieId);
                if (queryVec == null)
                    return writeError(HttpStatus.NOT_FOUND, "embedding not found for movieId", "movieId", movieId);
                Set<Integer> candidateIds = selectCandidates(movieId, k);
                Map<Integer, float[]> embeddings = store.getEmbeddings(candidateIds);
                List<ScoredMovie> scored = ExactVectorIndex.search(embeddings, queryVec, k, Set.of(movieId))
                        .stream().map(r -> new ScoredMovie(r.id(), r.score())).toList();
                return writeJson(HttpStatus.OK, new SimilarMoviesResult(movieId, scored));
            } catch (BadRequestException e) {
                return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
            } catch (Exception e) {
                log.error("Unexpected error in SimilarMovieService", e);
                return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
            }
        }, ctx.blockingTaskExecutor()));
    }

    private Set<Integer> selectCandidates(int movieId, int k) {
        Set<Integer> candidates = new LinkedHashSet<>();
        int max = k * RECALL_MULTIPLIER;
        for (Movie m : dataManager.getSimilarMovies(movieId)) {
            candidates.add(m.id());
            if (candidates.size() >= max) return candidates;
        }
        Movie seed = dataManager.getMovieById(movieId);
        if (seed != null) {
            for (String genre : seed.genres()) {
                for (Movie m : dataManager.getMoviesByGenre(genre, LIMIT_PER_GENRE)) {
                    if (m.id() != movieId) candidates.add(m.id());
                    if (candidates.size() >= max) return candidates;
                }
            }
        }
        for (Movie m : dataManager.getTopRatedMovies(max)) {
            if (m.id() != movieId) candidates.add(m.id());
            if (candidates.size() >= max) return candidates;
        }
        return candidates;
    }

    public record ScoredMovie(int movieId, double score) {}
    public record SimilarMoviesResult(int movieId, List<ScoredMovie> similar) {}
}
