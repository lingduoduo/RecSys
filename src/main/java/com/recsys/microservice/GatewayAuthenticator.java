package com.recsys.microservice;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

final class GatewayAuthenticator {
    private static final GatewayAuthenticator DISABLED = new GatewayAuthenticator(Set.of(), Set.of("/health"));
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String AUTHORIZATION_PREFIX = "Bearer ";

    private final Set<String> apiKeys;
    private final Set<String> publicPaths;

    private GatewayAuthenticator(Set<String> apiKeys, Set<String> publicPaths) {
        this.apiKeys = Set.copyOf(apiKeys);
        this.publicPaths = Set.copyOf(publicPaths);
    }

    static GatewayAuthenticator disabled() {
        return DISABLED;
    }

    static GatewayAuthenticator fromEnvironment() {
        return fromEnvironment(System::getenv);
    }

    static GatewayAuthenticator fromEnvironment(EnvVars.EnvReader env) {
        Set<String> keys = parseCsv(env.get("GATEWAY_API_KEYS"));
        if (keys.isEmpty()) {
            return disabled();
        }
        Set<String> publicPaths = parseCsv(env.get("GATEWAY_PUBLIC_PATHS"));
        if (publicPaths.isEmpty()) {
            publicPaths = Set.of("/health");
        }
        return new GatewayAuthenticator(keys, publicPaths);
    }

    boolean isEnabled() {
        return !apiKeys.isEmpty();
    }

    boolean authenticate(HttpServletRequest request, HttpServletResponse response, String path)
            throws IOException {
        if (!isEnabled() || isPublic(path)) {
            return true;
        }

        String provided = firstNonBlank(request.getHeader(API_KEY_HEADER), bearerToken(request.getHeader("Authorization")));
        if (provided != null && apiKeys.stream().anyMatch(key -> constantTimeEquals(key, provided))) {
            return true;
        }

        response.setHeader("WWW-Authenticate", "Bearer");
        GatewayProxyServlet.writeGatewayError(response, HttpServletResponse.SC_UNAUTHORIZED,
                "missing or invalid gateway API key");
        return false;
    }

    private boolean isPublic(String path) {
        return publicPaths.stream().anyMatch(publicPath ->
                path.equals(publicPath) || path.startsWith(publicPath + "/"));
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        return authorization.regionMatches(true, 0, AUTHORIZATION_PREFIX, 0, AUTHORIZATION_PREFIX.length())
                ? authorization.substring(AUTHORIZATION_PREFIX.length()).trim()
                : null;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second != null && !second.isBlank() ? second.trim() : null;
    }

    private static boolean constantTimeEquals(String expected, String provided) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static Set<String> parseCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }
}
