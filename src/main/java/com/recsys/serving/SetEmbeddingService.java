package com.recsys.serving;

import com.recsys.infrastructure.vectordb.EmbeddingStore;
import com.recsys.infrastructure.vectordb.VectorMath;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;

public class SetEmbeddingService extends BaseApiServlet {

    private final EmbeddingStore store;

    public SetEmbeddingService(EmbeddingStore store) {
        this.store = store;
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        prepareJson(response);
        try {
            int movieId = requiredIntParam(request, "movieId");

            // getParameter covers both URL query params and form-urlencoded body params
            String vecParam = request.getParameter("vec");
            String body = (vecParam != null) ? vecParam.trim() : "";

            if (body.isBlank()) {
                body = request.getReader().lines().collect(Collectors.joining()).trim();
            }

            if (body.isBlank()) {
                writeError(response, HttpServletResponse.SC_BAD_REQUEST, "empty request body");
                return;
            }

            float[] vec = VectorMath.parseVector(body);
            long ttl = optionalLongParam(request, "ttl", 86400);  // default: 24h, 0 = no expiry
            store.setEmbedding(movieId, vec, ttl);

            writeJson(response, HttpServletResponse.SC_OK, Map.of(
                    "ok", true,
                    "movieId", movieId,
                    "dim", vec.length,
                    "ttl", ttl
            ));

        } catch (BadRequestException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (NumberFormatException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid vector format: could not parse float");
        } catch (Exception e) {
            log.error("Unexpected error in SetEmbeddingService", e);
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "internal server error");
        }
    }
}
