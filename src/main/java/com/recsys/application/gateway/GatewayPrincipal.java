package com.recsys.application.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The authenticated caller identity carried through the gateway: used to key
 * per-principal rate limiting and to forward identity headers to backends.
 */
public record GatewayPrincipal(String subject, String clientId, String tokenUse, String rateLimitKey) {

    private static final GatewayPrincipal ANONYMOUS = new GatewayPrincipal("", "", "", "anonymous");

    public static GatewayPrincipal anonymous() {
        return ANONYMOUS;
    }

    public static GatewayPrincipal ofJwt(CognitoJwtVerifier.VerifiedClaims claims) {
        String subject = claims.subject() == null ? "" : claims.subject();
        String clientId = claims.clientId() == null ? "" : claims.clientId();
        String tokenUse = claims.tokenUse() == null ? "" : claims.tokenUse();
        String key = !subject.isBlank() ? "user:" + subject
                : !clientId.isBlank() ? "client:" + clientId
                : "anonymous";
        return new GatewayPrincipal(subject, clientId, tokenUse, key);
    }

    public static GatewayPrincipal ofApiKey(String matchedKey) {
        return new GatewayPrincipal("", "service", "", "apikey:" + sha256Prefix(matchedKey));
    }

    /** Identity headers to forward upstream (lowercase names; never the raw credential). */
    public Map<String, String> identityHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        if (!subject.isBlank()) headers.put("x-authenticated-subject", subject);
        if (!clientId.isBlank()) headers.put("x-authenticated-client-id", clientId);
        if (!tokenUse.isBlank()) headers.put("x-authenticated-token-use", tokenUse);
        return headers;
    }

    private static String sha256Prefix(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
