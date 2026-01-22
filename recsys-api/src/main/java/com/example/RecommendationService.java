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

    // Optional knobs (common in recsys APIs)
    String type = req.getParameter("type");         // e.g., "home", "similar", "trending"
    if (type == null || type.isBlank()) type = "home";

    String kStr = req.getParameter("k");            // number of items
    int k = 10;
    try {
        if (kStr != null) k = Integer.parseInt(kStr);
    } catch (NumberFormatException ignored) {}

    // Optional seed item for "similar" style requests
    String movieId = req.getParameter("movieId");   // optional

    resp.setContentType("application/json");
    resp.setStatus(HttpServletResponse.SC_OK);

    // Placeholder logic: return k items (simple deterministic stub)
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    sb.append("\"userId\":\"").append(userId).append("\",");
    sb.append("\"type\":\"").append(type).append("\",");
    if (movieId != null && !movieId.isBlank()) {
        sb.append("\"movieId\":\"").append(movieId).append("\",");
    }
    sb.append("\"k\":").append(k).append(",");
    sb.append("\"recommendations\":[");

    for (int i = 1; i <= k; i++) {
        if (i > 1) sb.append(",");
        sb.append("\"movie_").append(i).append("\"");
    }
    sb.append("]}");

    resp.getWriter().println(sb.toString());
    }

}
