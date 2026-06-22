package com.recsys.serving;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.infrastructure.DataManager;
import com.recsys.infrastructure.vectordb.EmbeddingStore;
import com.recsys.infrastructure.vectordb.ExactVectorIndex;
import com.recsys.domain.Movie;
import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.domain.RecommendationResponse;
import com.recsys.domain.User;
import com.recsys.service.recommendation.RecommendationPipeline;
import com.recsys.service.retrieval.multichannel.MultiChannelRecallService;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Recommendation-family endpoints for the offline serving API: the v1
 * multichannel-recall lookup, the v2 pipeline endpoint, the embedding-based
 * "similar movies" search, and the liveness probe. They share no state but form
 * one cohesive surface, so they live together as nested services.
 */
public final class RecommendationService {

    private RecommendationService() {}

    /** GET /getrecommendation, /recommendation — v1 multichannel recall by user. */
    public static final class V1 extends BaseApiService {

        private static final int RECALL_MULTIPLIER = 3;

        private final DataManager dataManager;
        private final MultiChannelRecallService recallService;

        public V1(DataManager dataManager, MultiChannelRecallService recallService) {
            this.dataManager = dataManager;
            this.recallService = recallService;
        }

        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.of(CompletableFuture.supplyAsync(() -> {
                try {
                    int userId = requiredIntParam(ctx, "userId");
                    User user = dataManager.getUserById(userId);
                    if (user == null) return writeError(HttpStatus.NOT_FOUND, "user not found", "userId", userId);

                    int k = optionalIntParam(ctx, "k", 20, 1, 100);
                    Set<String> excludedItemIds = dataManager.getWatchedMovieIds(userId).stream()
                            .map(String::valueOf)
                            .collect(Collectors.toSet());
                    RecommendationQuery query = new RecommendationQuery(
                            String.valueOf(userId), k, excludedItemIds, null);

                    List<MovieCandidate> candidates = recallService.recall(query, k * RECALL_MULTIPLIER);
                    List<Movie> movies = candidates.stream()
                            .map(c -> {
                                try { return dataManager.getMovieById(Integer.parseInt(c.itemId())); }
                                catch (NumberFormatException e) { return null; }
                            })
                            .filter(Objects::nonNull)
                            .toList();

                    return writeJson(HttpStatus.OK, new RecommendationResponse(user, movies));

                } catch (BadRequestException | IllegalArgumentException e) {
                    return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
                } catch (Exception e) {
                    log.error("Unexpected error in RecommendationService.V1", e);
                    return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
                }
            }, ctx.blockingTaskExecutor()));
        }
    }

    /** POST /v2/recommend — pipeline-driven recommendation from a JSON query. */
    public static final class V2 extends BaseApiService {

        private final RecommendationPipeline pipeline;

        public V2(RecommendationPipeline pipeline) {
            this.pipeline = pipeline;
        }

        @Override
        protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.of(req.aggregate().thenApplyAsync(agg -> {
                try {
                    RecommendationQuery query = readJsonBody(agg, RecommendationQuery.class);
                    return writeJson(HttpStatus.OK, pipeline.recommend(query));
                } catch (BadRequestException | IllegalArgumentException e) {
                    return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
                } catch (Exception e) {
                    log.error("Unexpected error in RecommendationService.V2", e);
                    return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
                }
            }, ctx.blockingTaskExecutor()));
        }
    }

    /** GET /similar — nearest-neighbour movies by embedding cosine similarity. */
    public static final class Similar extends BaseApiService {

        private static final int LIMIT_PER_GENRE = 50;
        private static final int RECALL_MULTIPLIER = 5;

        private final EmbeddingStore store;
        private final DataManager dataManager;

        public Similar(EmbeddingStore store) {
            this(store, DataManager.getInstance());
        }

        public Similar(EmbeddingStore store, DataManager dataManager) {
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
                    log.error("Unexpected error in RecommendationService.Similar", e);
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

    /** GET /health — liveness probe. */
    public static final class Health extends BaseApiService {
        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
            return writeJson(HttpStatus.OK, Map.of("ok", true));
        }
    }
}
