package com.recsys.api.serving;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * HTTP cache-header helpers for the CDN edge.
 *
 * <p>See docs/superpowers/specs/2026-07-14-cdn-edge-acceleration-design.md. Only
 * non-personalized, shared responses may use {@link #publicCache}; anything keyed by
 * user identity uses {@link #NO_STORE}.
 */
public final class HttpCaching {

    public static final String NO_STORE = "no-store";

    private static final int ETAG_BYTES = 16; // 16 bytes -> 32 hex chars

    private HttpCaching() {}

    public static String publicCache(long sMaxAgeSeconds, long staleWhileRevalidateSeconds) {
        return "public, s-maxage=" + sMaxAgeSeconds
                + ", stale-while-revalidate=" + staleWhileRevalidateSeconds;
    }

    /** Strong ETag: a quoted 32-hex-character SHA-256 prefix of the serialized body. */
    public static String etagFor(byte[] body) {
        byte[] hash = sha256(body);
        StringBuilder sb = new StringBuilder(ETAG_BYTES * 2 + 2);
        sb.append('"');
        for (int i = 0; i < ETAG_BYTES; i++) {
            sb.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
            sb.append(Character.forDigit(hash[i] & 0xF, 16));
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * RFC 7232 weak comparison of an {@code If-None-Match} header against an ETag.
     * GET revalidation uses weak comparison, so a {@code W/} prefix on either side is ignored.
     */
    public static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank() || etag == null) {
            return false;
        }
        String candidate = normalize(etag);
        for (String raw : ifNoneMatch.split(",")) {
            String value = raw.trim();
            if (value.equals("*") || normalize(value).equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String tag) {
        String value = tag.trim();
        if (value.regionMatches(true, 0, "W/", 0, 2)) {
            value = value.substring(2).trim();
        }
        return value;
    }

    private static byte[] sha256(byte[] body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(body);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
