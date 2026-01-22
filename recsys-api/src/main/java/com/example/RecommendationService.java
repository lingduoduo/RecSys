package com.example;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class RecommendationService extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req,
                         HttpServletResponse resp) throws IOException {

        String userId = req.getParameter("userId");
        if (userId == null || userId.isBlank()) userId = "123";

        resp.setContentType("application/json");
        resp.setStatus(HttpServletResponse.SC_OK);

        // Fake recommendation logic (placeholder)
        resp.getWriter().println(
            "{ \"userId\": \"" + userId + "\", " +
            "\"recommendations\": [" +
                "\"Inception\", " +
                "\"Interstellar\", " +
                "\"The Dark Knight\"" +
            "] }"
        );
    }
}
