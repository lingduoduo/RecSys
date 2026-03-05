package com.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

public class RecommendationService extends HttpServlet {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response) throws IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Access-Control-Allow-Origin", "*");

        try {
            // NEW: mode switch (default is existing behavior)
            String mode = request.getParameter("mode"); // "topk" to enable trending mode

            // Optional (only used in topk mode)
            String window = request.getParameter("window"); // last_hour / last_day / last_month
            String kStr = request.getParameter("k");

            // 1) Validate userId (keep as-is so payload still contains user)
            String userIdStr = request.getParameter("userId");
            if (userIdStr == null || userIdStr.isBlank()) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.getWriter().println("{\"error\":\"missing required query parameter: userId\"}");
                return;
            }

            int userId = Integer.parseInt(userIdStr);

            // 2) Load user
            User user = DataManager.getInstance().getUserById(userId);
            if (user == null) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                response.getWriter().println("{\"error\":\"user not found\",\"userId\":" + userId + "}");
                return;
            }

            // NEW: if mode=topk, return trending instead of similar-items
            if ("topk".equalsIgnoreCase(mode) || "trending".equalsIgnoreCase(mode)) {
                String w = (window == null || window.isBlank()) ? "last_hour" : window.trim();

                int k = 20;
                if (kStr != null && !kStr.isBlank()) {
                    k = Integer.parseInt(kStr);
                }
                if (k <= 0) k = 20;
                if (k > 200) k = 200; // simple safety cap

                List<Movie> recs = getTopKMoviesFromRedis(w, k);

                RecommendationResponse payload = new RecommendationResponse(user, recs);
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().println(MAPPER.writeValueAsString(payload));
                return;
            }

            // 3) Optional seedMovieId (existing behavior)
            String seedMovieIdStr = request.getParameter("seedMovieId");

            List<Movie> recs;
            if (seedMovieIdStr != null && !seedMovieIdStr.isBlank()) {
                int seedMovieId = Integer.parseInt(seedMovieIdStr);

                // Ensure seed movie exists
                Movie seed = DataManager.getInstance().getMovieById(seedMovieId);
                if (seed == null) {
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    response.getWriter().println("{\"error\":\"seed movie not found\",\"seedMovieId\":" + seedMovieId + "}");
                    return;
                }

                recs = DataManager.getInstance().getSimilarMovies(seedMovieId);
            } else {
                // Fallback: pick a default seed (simple stub)
                recs = DataManager.getInstance().getSimilarMovies(1);
            }

            // 4) Build response payload
            RecommendationResponse payload = new RecommendationResponse(user, recs);

            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().println(MAPPER.writeValueAsString(payload));

        } catch (NumberFormatException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().println("{\"error\":\"invalid numeric parameter format\"}");
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().println("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().println("{\"error\":\"internal server error\"}");
        }
    }

    /**
     * Step 2.1 placeholder:
     * Next step we'll implement Redis ZSET read:
     * key: topk:<window> (e.g., topk:last_hour), ZREVRANGE 0..k-1
     */
    private List<Movie> getTopKMoviesFromRedis(String window, int k) {
        // Validate allowed windows early so callers get a clear error.
        if (!("last_hour".equals(window) || "last_day".equals(window) || "last_month".equals(window))) {
            throw new IllegalArgumentException("invalid window: " + window);
        }

        // TODO (next step): replace this stub with Redis lookup.
        // For now, fall back to existing stub behavior so endpoint still works.
        return DataManager.getInstance().getSimilarMovies(1);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}