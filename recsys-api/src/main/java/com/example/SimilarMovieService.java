package com.example;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class SimilarMovieService extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req,
                         HttpServletResponse resp) throws IOException {

        // Allow query param like ?movieId=1 (optional)
        String movieId = req.getParameter("movieId");
        if (movieId == null || movieId.isBlank()) movieId = "1";

        resp.setContentType("application/json");
        resp.setStatus(HttpServletResponse.SC_OK);

        // Placeholder similar-movie result
        resp.getWriter().println(
            "{ \"movieId\": \"" + movieId + "\", " +
            "\"similar\": [\"Interstellar\", \"Tenet\", \"Memento\"] }"
        );
    }
}
