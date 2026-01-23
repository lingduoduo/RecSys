package com.example;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class SetEmbeddingService extends HttpServlet {

    private final RedisEmbeddingStore store =
            new RedisEmbeddingStore("localhost", 6379, "i2vEmb");

    @Override
    protected void doPost(HttpServletRequest request,
                          HttpServletResponse response) throws IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Access-Control-Allow-Origin", "*");

        try {
            String movieIdStr = request.getParameter("movieId");
            if (movieIdStr == null || movieIdStr.isBlank()) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.getWriter().println("{\"error\":\"missing required query parameter: movieId\"}");
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
                body = request.getReader().lines().reduce("", (a, b) -> a + b).trim();
            }

            if (body.isBlank()) {
                String vecParam = request.getParameter("vec");
                if (vecParam != null) body = vecParam.trim();
            }

            if (body.isBlank()) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.getWriter().println("{\"error\":\"empty request body\"}");
                return;
            }

            float[] vec = parseVector(body);
            store.setMovieEmbedding(movieId, vec);

            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().println("{\"ok\":true,\"movieId\":" + movieId + ",\"dim\":" + vec.length + "}");

        } catch (NumberFormatException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().println("{\"error\":\"invalid numeric parameter format\"}");
        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().println("{\"error\":\"internal server error\"}");
        }
    }

    private static float[] parseVector(String s) {
        String[] parts = s.trim().split("\\s+");
        float[] vec = new float[parts.length];
        for (int i = 0; i < parts.length; i++) vec[i] = Float.parseFloat(parts[i]);
        return vec;
    }
}
