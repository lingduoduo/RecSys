package com.recsys.microservice;
import com.recsys.config.EnvVars;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

final class GatewayAuthenticator {
    private static final GatewayAuthenticator DISABLED = new GatewayAuthenticator(Set.of(), Set.of("/health"));
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

    /**
     * Check whether the request is authorized. Returns {@code null} if the request
     * is allowed through; returns a non-null {@link HttpResponse} (401) if it must
     * be rejected.
     */
    HttpResponse check(RequestHeaders headers, String path) {
        if (!isEnabled() || isPublic(path)) return null;

        String provided = firstNonBlank(
                headers.get(HttpHeaderNames.of("x-api-key")),
                bearerToken(headers.get(HttpHeaderNames.AUTHORIZATION)));

        if (provided != null) {
            boolean matched = false;
            for (String key : apiKeys) {
                matched |= constantTimeEquals(key, provided);
            }
            if (matched) return null;
        }

        return HttpResponse.of(
                ResponseHeaders.builder(HttpStatus.UNAUTHORIZED)
                        .set(HttpHeaderNames.WWW_AUTHENTICATE, "Bearer")
                        .contentType(MediaType.JSON_UTF_8)
                        .build(),
                HttpData.ofUtf8("{\"error\":\"missing or invalid gateway API key\"}"));
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
