package com.recsys.serving;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.infrastructure.vectordb.CandidateGenerator;
import com.recsys.infrastructure.vectordb.EmbeddingStore;
import com.recsys.infrastructure.vectordb.VectorMath;

import java.util.Map;

/**
 * Embedding write endpoints. Both accept a vector either as a {@code vec} query
 * parameter or as the request body, persist it with a TTL, and echo the result.
 * The vector-parsing handshake is shared; the movie variant additionally pushes
 * the new vector into the {@link CandidateGenerator} index.
 */
public final class EmbeddingService {

    private static final long DEFAULT_TTL_SECONDS = 86400;

    private EmbeddingService() {}

    /**
     * Reads the vector from the {@code vec} query param, falling back to the
     * aggregated request body. Throws {@link BaseApiService.BadRequestException}
     * when neither is present.
     */
    private static float[] parseVectorBody(ServiceRequestContext ctx, AggregatedHttpRequest agg) {
        String vecParam = ctx.queryParam("vec");
        String body = (vecParam != null) ? vecParam.trim() : "";
        if (body.isBlank()) body = agg.contentUtf8().trim();
        if (body.isBlank()) throw new BaseApiService.BadRequestException("empty request body");
        return VectorMath.parseVector(body);
    }

    /** POST /setembedding — store a movie embedding and refresh the recall index. */
    public static final class SetMovie extends BaseApiService {

        private final EmbeddingStore store;
        private final CandidateGenerator candidateGenerator;

        public SetMovie(EmbeddingStore store, CandidateGenerator candidateGenerator) {
            this.store = store;
            this.candidateGenerator = candidateGenerator;
        }

        @Override
        protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.of(req.aggregate().thenApplyAsync(agg -> {
                try {
                    int movieId = requiredIntParam(ctx, "movieId");
                    float[] vec = parseVectorBody(ctx, agg);
                    long ttl = optionalLongParam(ctx, "ttl", DEFAULT_TTL_SECONDS);
                    store.setEmbedding(movieId, vec, ttl);
                    candidateGenerator.updateEmbedding(movieId, vec);
                    return writeJson(HttpStatus.OK,
                            Map.of("ok", true, "movieId", movieId, "dim", vec.length, "ttl", ttl));
                } catch (BadRequestException e) {
                    return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
                } catch (NumberFormatException e) {
                    return writeError(HttpStatus.BAD_REQUEST, "invalid vector format: could not parse float");
                } catch (IllegalArgumentException e) {
                    // Dimension mismatch, non-finite/blank vector — bad client input, not a server fault.
                    return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
                } catch (Exception e) {
                    log.error("Unexpected error in EmbeddingService.SetMovie", e);
                    return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
                }
            }, ctx.blockingTaskExecutor()));
        }
    }

    /** POST /setuserembedding — store a user embedding. */
    public static final class SetUser extends BaseApiService {

        private final EmbeddingStore store;

        public SetUser(EmbeddingStore store) {
            this.store = store;
        }

        @Override
        protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.of(req.aggregate().thenApplyAsync(agg -> {
                try {
                    int userId = requiredIntParam(ctx, "userId");
                    float[] vec = parseVectorBody(ctx, agg);
                    long ttl = optionalLongParam(ctx, "ttl", DEFAULT_TTL_SECONDS);
                    store.setEmbedding(userId, vec, ttl);
                    return writeJson(HttpStatus.OK,
                            Map.of("ok", true, "userId", userId, "dim", vec.length, "ttl", ttl));
                } catch (BadRequestException e) {
                    return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
                } catch (NumberFormatException e) {
                    return writeError(HttpStatus.BAD_REQUEST, "invalid vector format: could not parse float");
                } catch (Exception e) {
                    log.error("Unexpected error in EmbeddingService.SetUser", e);
                    return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
                }
            }, ctx.blockingTaskExecutor()));
        }
    }
}
