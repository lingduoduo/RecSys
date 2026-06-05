package com.recsys.serving;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.infrastructure.vectordb.EmbeddingStore;
import com.recsys.infrastructure.vectordb.VectorMath;

import java.util.Map;

public class SetEmbeddingService extends BaseApiService {

    private final EmbeddingStore store;

    public SetEmbeddingService(EmbeddingStore store) {
        this.store = store;
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
                return writeJson(HttpStatus.OK,
                        Map.of("ok", true, "movieId", movieId, "dim", vec.length, "ttl", ttl));
            } catch (BadRequestException e) {
                return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
            } catch (NumberFormatException e) {
                return writeError(HttpStatus.BAD_REQUEST, "invalid vector format: could not parse float");
            } catch (Exception e) {
                log.error("Unexpected error in SetEmbeddingService", e);
                return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
            }
        }, ctx.blockingTaskExecutor()));
    }
}
