package com.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class MovieService extends HttpServlet {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        // Common response headers
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Access-Control-Allow-Origin", "*");

        try {
            // 1. Get and validate movie id
            String movieIdStr = request.getParameter("id");
            if (movieIdStr == null || movieIdStr.isBlank()) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.getWriter().println(
                        "{\"error\":\"missing required query parameter: id\"}"
                );
                return;
            }

            int movieId = Integer.parseInt(movieIdStr);

            // 2. Fetch movie from DataManager (in-memory DB / real DB later)
            Movie movie = DataManager.getInstance().getMovieById(movieId);

            if (movie == null) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                response.getWriter().println(
                        "{\"error\":\"movie not found\",\"id\":" + movieId + "}"
                );
                return;
            }

            // 3. Serialize Movie -> JSON
            String jsonMovie = MAPPER.writeValueAsString(movie);

            // 4. Return success response
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().println(jsonMovie);

        } catch (NumberFormatException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().println(
                    "{\"error\":\"invalid movie id format\"}"
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
