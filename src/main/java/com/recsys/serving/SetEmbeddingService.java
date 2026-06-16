package com.recsys.serving;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.infrastructure.vectordb.CandidateGenerator;
import com.recsys.infrastructure.vectordb.EmbeddingStore;
import com.recsys.infrastructure.vectordb.VectorMath;

import java.util.Map;

public class SetEmbeddingService extends BaseApiService {

    private final EmbeddingStore store;
    private final CandidateGenerator candidateGenerator;

    public SetEmbeddingService(EmbeddingStore store, CandidateGenerator candidateGenerator) {
        this.store = store;
        this.candidateGenerator = candidateGenerator;
    }

    @Override
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
        return HttpResponse.of(req.aggregate().thenApplyAsync(agg -> {
            try {
                int movieId = requiredIntParam(ctx, "movieId");
                String vecParam = ctx.queryParam("vec");
                String body = (vecParam != null) ? vecParam.trim() : "";
                if (body.isBlank()) body = agg.contentUtf8().trim();
                if (body.isBlank()) return writeError(HttpStatus.BAD_REQUEST, "empty request body");
                float[] vec = VectorMath.parseVector(body);
                long ttl = optionalLongParam(ctx, "ttl", 86400);
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
                log.error("Unexpected error in SetEmbeddingService", e);
                return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
            }
        }, ctx.blockingTaskExecutor()));
    }
}
