package com.recsys.api.serving;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.infrastructure.dataloading.DataManager;
import com.recsys.infrastructure.vectordb.EmbeddingStore;
import com.recsys.infrastructure.vectordb.ExactVectorIndex;
import com.recsys.domain.item.Movie;
import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.recommendation.RecommendationResponse;
import com.recsys.domain.recommendation.RecommendationResult;
import com.recsys.domain.user.User;
import com.recsys.application.recommendation.RecommendationPipeline;
import com.recsys.application.retrieval.multichannel.MultiChannelRecallService;
import com.recsys.application.retrieval.multichannel.RecallResult;

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

                    RecallResult recall = recallService.recallDetailed(query, k * RECALL_MULTIPLIER);
                    List<MovieCandidate> candidates = recall.candidates();
                    List<Movie> movies = candidates.stream()
                            .map(c -> {
                                try { return dataManager.getMovieById(Integer.parseInt(c.itemId())); }
                                catch (NumberFormatException e) { return null; }
                            })
                            .filter(Objects::nonNull)
                            .toList();

                    return writeJsonWithRecallDegraded(HttpStatus.OK,
                            new RecommendationResponse(user, movies), recall.degradedChannels());

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
                    RecommendationResult result = pipeline.recommend(query);
                    String degraded = result.trace().get("degradedChannels");
                    Set<String> degradedSet = (degraded == null || degraded.isBlank())
                            ? Set.of()
                            : new LinkedHashSet<>(List.of(degraded.split(",")));
                    return writeJsonWithRecallDegraded(HttpStatus.OK, result, degradedSet);
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

        // Embeddings can be rewritten by POST /setembedding, so the fresh window is short.
        // Bulk reloads additionally require an explicit CDN invalidation — see
        // docs/runbooks/cdn-operations.md.
        private static final String CACHE_CONTROL = HttpCaching.publicCache(300, 3600);

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
                        return writeNoStoreJson(HttpStatus.NOT_FOUND, Map.of(
                                "error", "embedding not found for movieId", "movieId", movieId));
                    Set<Integer> candidateIds = selectCandidates(movieId, k);
                    Map<Integer, float[]> embeddings = store.getEmbeddings(candidateIds);
                    List<ScoredMovie> scored = ExactVectorIndex.search(embeddings, queryVec, k, Set.of(movieId))
                            .stream().map(r -> new ScoredMovie(r.id(), r.score())).toList();
                    return writeCacheableJson(HttpStatus.OK,
                            new SimilarMoviesResult(movieId, scored), CACHE_CONTROL, req);
                } catch (BadRequestException e) {
                    // Errors are never cacheable: no-store, else CloudFront's default Error
                    // Caching Minimum TTL (10s) would pin a 400 at the edge.
                    return writeNoStoreError(HttpStatus.BAD_REQUEST, e.getMessage());
                } catch (Exception e) {
                    log.error("Unexpected error in RecommendationService.Similar", e);
                    return writeNoStoreError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
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
