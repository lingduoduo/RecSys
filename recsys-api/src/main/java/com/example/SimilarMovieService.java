package com.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class SimilarMovieService extends HttpServlet {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // hardcode for prototype; later read from env/config
    private final RedisEmbeddingStore store =
            new RedisEmbeddingStore("localhost", 6379, "i2vEmb");

    @Override
    protected void doGet(HttpServletRequest request,
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

            int k = 10;
            String kStr = request.getParameter("k");
            if (kStr != null && !kStr.isBlank()) k = Integer.parseInt(kStr);

            float[] queryVec = store.getMovieEmbedding(movieId);
            if (queryVec == null) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                response.getWriter().println("{\"error\":\"embedding not found for movieId\",\"movieId\":" + movieId + "}");
                return;
            }

            // brute-force over Redis keys (prototype)
            List<Integer> candidates = new ArrayList<>(store.scanMovieIds(5000));
            List<ScoredMovie> scored = new ArrayList<>();

            for (int candId : candidates) {
                if (candId == movieId) continue;
                float[] v = store.getMovieEmbedding(candId);
                if (v == null) continue;
                double score = VectorMath.cosine(queryVec, v);
                scored.add(new ScoredMovie(candId, score));
            }

            scored.sort(Comparator.comparingDouble(ScoredMovie::score).reversed());
            if (scored.size() > k) scored = scored.subList(0, k);

            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().println(MAPPER.writeValueAsString(
                    new SimilarMoviesResult(movieId, scored)
            ));

        } catch (NumberFormatException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().println("{\"error\":\"invalid numeric parameter format\"}");
        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().println("{\"error\":\"internal server error\"}");
        }
    }

    // small DTOs for JSON
    public record ScoredMovie(int movieId, double score) {}
    public record SimilarMoviesResult(int movieId, List<ScoredMovie> similar) {}
}
