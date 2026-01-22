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
            // 1) Validate userId
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

            // 3) Optional seedMovieId (for "similar-items" style recommendations)
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
        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().println("{\"error\":\"internal server error\"}");
        }
    }
}
