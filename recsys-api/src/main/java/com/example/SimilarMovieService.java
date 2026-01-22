package com.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

public class SimilarMovieService extends HttpServlet {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response) throws IOException {

        // Common headers
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Access-Control-Allow-Origin", "*");

        try {
            // 1. Validate movieId
            String movieIdStr = request.getParameter("movieId");
            if (movieIdStr == null || movieIdStr.isBlank()) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.getWriter().println(
                        "{\"error\":\"missing required query parameter: movieId\"}"
                );
                return;
            }

            int movieId = Integer.parseInt(movieIdStr);

            // 2. Check base movie exists
            Movie baseMovie = DataManager.getInstance().getMovieById(movieId);
            if (baseMovie == null) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                response.getWriter().println(
                        "{\"error\":\"movie not found\",\"movieId\":" + movieId + "}"
                );
                return;
            }

            // 3. Fetch similar movies
            List<Movie> similarMovies =
                    DataManager.getInstance().getSimilarMovies(movieId);

            // 4. Serialize response
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().println(
                    MAPPER.writeValueAsString(
                            new SimilarMovieResponse(movieId, similarMovies)
                    )
            );

        } catch (NumberFormatException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().println(
                    "{\"error\":\"invalid movieId format\"}"
            );
        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().println(
                    "{\"error\":\"internal server error\"}"
            );
        }
    }
}
