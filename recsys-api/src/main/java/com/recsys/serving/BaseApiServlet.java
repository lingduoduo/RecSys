package com.recsys.serving;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

abstract class BaseApiServlet extends HttpServlet {

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    protected static void prepareJson(HttpServletResponse response) {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Access-Control-Allow-Origin", "*");
    }

    protected static void writeJson(HttpServletResponse response, int status, Object payload) throws IOException {
        response.setStatus(status);
        response.getWriter().println(MAPPER.writeValueAsString(payload));
    }

    protected static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.getWriter().println("{\"error\":\"" + escapeJson(message) + "\"}");
    }

    protected static void writeError(HttpServletResponse response, int status, String message, String field, int value)
            throws IOException {
        response.setStatus(status);
        response.getWriter().println("{\"error\":\"" + escapeJson(message) + "\",\"" + field + "\":" + value + "}");
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
