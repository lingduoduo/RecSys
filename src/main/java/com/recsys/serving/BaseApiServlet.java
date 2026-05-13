package com.recsys.serving;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

public abstract class BaseApiServlet extends HttpServlet {

    protected static final ObjectMapper MAPPER = new ObjectMapper();
    protected static final Logger log = LoggerFactory.getLogger(BaseApiServlet.class);

    // Restrict cross-origin access: only add the header when CORS_ALLOWED_ORIGIN is explicitly set.
    // Use "*" only for local development; restrict to a specific origin in production.
    private static final String CORS_ORIGIN = System.getenv("CORS_ALLOWED_ORIGIN");

    protected static void prepareJson(HttpServletResponse response) {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        if (CORS_ORIGIN != null && !CORS_ORIGIN.isBlank()) {
            response.setHeader("Access-Control-Allow-Origin", CORS_ORIGIN);
        }
    }

    protected static void writeJson(HttpServletResponse response, int status, Object payload) throws IOException {
        response.setStatus(status);
        response.getWriter().println(MAPPER.writeValueAsString(payload));
    }

    protected static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        writeJson(response, status, Map.of("error", message == null ? "" : message));
    }

    protected static void writeError(HttpServletResponse response, int status, String message, String field, int value)
            throws IOException {
        writeJson(response, status, Map.of(
                "error", message == null ? "" : message,
                field, value
        ));
    }

    protected static <T> T readJsonBody(HttpServletRequest request, Class<T> bodyType) throws IOException {
        if (request.getContentLengthLong() == 0) {
            throw new BadRequestException("empty request body");
        }
        try {
            return MAPPER.readValue(request.getInputStream(), bodyType);
        } catch (MismatchedInputException e) {
            throw new BadRequestException("empty or invalid json request body");
        } catch (IOException e) {
            throw new BadRequestException("invalid json request body");
        }
    }

    protected static int requiredIntParam(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            throw new BadRequestException("missing required query parameter: " + name);
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("invalid numeric parameter format");
        }
    }

    protected static int optionalIntParam(HttpServletRequest request,
                                          String name,
                                          int defaultValue,
                                          int min,
                                          int max) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < min) return defaultValue;
            return Math.min(parsed, max);
        } catch (NumberFormatException e) {
            throw new BadRequestException("invalid numeric parameter format");
        }
    }

    protected static long optionalLongParam(HttpServletRequest request, String name, long defaultValue) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("invalid numeric parameter format");
        }
    }

    protected static final class BadRequestException extends RuntimeException {
        BadRequestException(String message) {
            super(message);
        }
    }
}
