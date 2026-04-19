package com.recsys.serving;

import com.recsys.features.RedisEmbeddingStore;
import com.recsys.features.VectorMath;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.stream.Collectors;

public class SetEmbeddingService extends BaseApiServlet {

    private final RedisEmbeddingStore store;

    public SetEmbeddingService(RedisEmbeddingStore store) {
        this.store = store;
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        prepareJson(response);
        try {
            String movieIdStr = request.getParameter("movieId");
            if (movieIdStr == null || movieIdStr.isBlank()) {
                writeError(response, HttpServletResponse.SC_BAD_REQUEST, "missing required query parameter: movieId");
                return;
            }
            int movieId = Integer.parseInt(movieIdStr);

            String body = "";
            String contentType = request.getContentType();
            if (contentType != null && contentType.startsWith("application/x-www-form-urlencoded")) {
                String vecParam = request.getParameter("vec");
                if (vecParam != null) body = vecParam.trim();
            }

            if (body.isBlank()) {
                body = request.getReader().lines().collect(Collectors.joining()).trim();
            }

            if (body.isBlank()) {
                String vecParam = request.getParameter("vec");
                if (vecParam != null) body = vecParam.trim();
            }

            if (body.isBlank()) {
                writeError(response, HttpServletResponse.SC_BAD_REQUEST, "empty request body");
                return;
            }

            float[] vec = VectorMath.parseVector(body);
            store.setMovieEmbedding(movieId, vec);

            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().println("{\"ok\":true,\"movieId\":" + movieId + ",\"dim\":" + vec.length + "}");

        } catch (NumberFormatException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid numeric parameter format");
        } catch (Exception e) {
            e.printStackTrace();
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "internal server error");
        }
    }
}
