package com.recsys.application.gateway;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * A gateway request path split into its API version and the version-free path the rest of the
 * gateway works with.
 *
 * <p>The gateway owns versioning: backends keep their current internal paths, and a client's
 * {@code /api/v1/users} becomes {@code /api/users} before routing, authorization, or rate-limit
 * keying sees it. An unversioned path is implicit v1, which is what keeps every existing client
 * working.
 *
 * <p>{@link #parse} is total — it never throws — so a hostile path cannot turn into a 500.
 */
public record ApiVersion(int version, String path, boolean explicit) {

    /** Versions this gateway serves. Adding a version is a one-line change here. */
    public static final Set<Integer> SUPPORTED_VERSIONS = Set.of(1);

    /** The version assumed when a request carries no explicit version segment. */
    public static final int DEFAULT_VERSION = 1;

    private static final String API_ROOT = "/api";
    private static final String API_PREFIX = API_ROOT + "/";

    /**
     * Bounds the digit run so {@link Integer#parseInt} cannot overflow. A longer run is not a
     * version segment at all, so {@code /api/v99999/x} routes as an ordinary path and 404s
     * rather than becoming a confusing 400.
     */
    private static final int MAX_VERSION_DIGITS = 4;

    public static ApiVersion parse(String requestPath) {
        String normalized = MicroserviceRoute.normalizePath(requestPath);
        if (!normalized.startsWith(API_PREFIX)) {
            return implicit(normalized);
        }
        int digitsStart = API_PREFIX.length() + 1;
        if (normalized.charAt(API_PREFIX.length()) != 'v') {
            return implicit(normalized);
        }
        int cursor = digitsStart;
        while (cursor < normalized.length() && Character.isDigit(normalized.charAt(cursor))) {
            cursor++;
        }
        int digits = cursor - digitsStart;
        if (digits == 0 || digits > MAX_VERSION_DIGITS) {
            return implicit(normalized);
        }
        // The digits must end the segment: "/api/v1x/foo" is a resource named "v1x", not v1.
        if (cursor < normalized.length() && normalized.charAt(cursor) != '/') {
            return implicit(normalized);
        }
        int version = Integer.parseInt(normalized.substring(digitsStart, cursor));
        String remainder = normalized.substring(cursor);
        return new ApiVersion(version, remainder.isEmpty() ? API_ROOT : API_ROOT + remainder, true);
    }

    public boolean supported() {
        return SUPPORTED_VERSIONS.contains(version);
    }

    /** Client-facing rejection text; names every version the gateway will accept. */
    public String unsupportedMessage() {
        String supported = SUPPORTED_VERSIONS.stream()
                .sorted()
                .map(v -> "v" + v)
                .collect(Collectors.joining(", "));
        return "unsupported API version: v" + version + "; supported: " + supported;
    }

    /**
     * The canonical versioned spelling of an already version-free path, e.g.
     * {@code /api/catalog/item} to {@code /api/v1/catalog/item}. Non-{@code /api} paths are
     * returned unchanged, so {@code /health} never grows a version.
     */
    public static String versioned(int version, String normalizedPath) {
        String normalized = MicroserviceRoute.normalizePath(normalizedPath);
        if (!normalized.equals(API_ROOT) && !normalized.startsWith(API_PREFIX)) {
            return normalized;
        }
        return API_ROOT + "/v" + version + normalized.substring(API_ROOT.length());
    }

    private static ApiVersion implicit(String normalizedPath) {
        return new ApiVersion(DEFAULT_VERSION, normalizedPath, false);
    }
}
