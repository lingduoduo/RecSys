package com.recsys.application.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies AWS Cognito RS256 JWTs against the pool's JWKS using only the JDK and Jackson.
 * Transport-agnostic: callers extract the bearer token and call {@link #verify(String)}.
 */
final class CognitoJwtVerifier {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final Duration JWKS_CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration UNKNOWN_KID_REFETCH_INTERVAL = Duration.ofSeconds(30);
    private static final Duration JWKS_REQUEST_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration STALE_RETRY_BACKOFF = Duration.ofSeconds(30);
    private static final long ALLOWED_CLOCK_SKEW_SECONDS = 60L;
    private static final Logger log = LoggerFactory.getLogger(CognitoJwtVerifier.class);

    private final CognitoConfig config;
    private final JwkProvider jwkProvider;
    private final Clock clock;

    CognitoJwtVerifier(CognitoConfig config, HttpClient httpClient, Clock clock) {
        this(config, new HttpJwkProvider(config.issuer(), httpClient), clock);
    }

    CognitoJwtVerifier(CognitoConfig config, JwkProvider jwkProvider, Clock clock) {
        this.config = Objects.requireNonNull(config, "config is required");
        this.jwkProvider = Objects.requireNonNull(jwkProvider, "jwkProvider is required");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    VerifiedClaims verify(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new JwtAuthException(401, "JWT must have header, payload, and signature");
        }

        JsonNode header = parseJson(parts[0], "JWT header");
        JsonNode payload = parseJson(parts[1], "JWT payload");
        String alg = text(header, "alg");
        if (!"RS256".equals(alg)) {
            throw new JwtAuthException(401, "unsupported JWT algorithm");
        }
        String kid = text(header, "kid");
        if (kid.isBlank()) {
            throw new JwtAuthException(401, "JWT kid is required");
        }

        PublicKey key = jwkProvider.key(kid);
        verifySignature(parts[0] + "." + parts[1], parts[2], key);
        validateClaims(payload);

        String subject = text(payload, "sub");
        String clientId = firstText(payload, "client_id", "aud");
        String tokenUse = text(payload, "token_use");
        return new VerifiedClaims(subject, clientId, tokenUse);
    }

    private void validateClaims(JsonNode payload) {
        String issuer = text(payload, "iss");
        if (!config.issuer().equals(issuer)) {
            throw new JwtAuthException(401, "JWT issuer mismatch");
        }

        long now = clock.instant().getEpochSecond();
        long exp = longClaim(payload, "exp", 0L);
        if (exp <= now - ALLOWED_CLOCK_SKEW_SECONDS) {
            throw new JwtAuthException(401, "JWT is expired");
        }
        long nbf = longClaim(payload, "nbf", 0L);
        if (nbf > 0L && nbf > now + ALLOWED_CLOCK_SKEW_SECONDS) {
            throw new JwtAuthException(401, "JWT is not valid yet");
        }

        String tokenUse = text(payload, "token_use");
        if (!config.tokenUses().isEmpty()
                && !tokenUse.isBlank()
                && !config.tokenUses().contains(tokenUse.toLowerCase())) {
            throw new JwtAuthException(403, "JWT token_use is not allowed");
        }

        if (config.audience() != null && !config.audience().isBlank() && !hasAudience(payload, config.audience())) {
            throw new JwtAuthException(403, "JWT audience mismatch");
        }
    }

    private boolean hasAudience(JsonNode payload, String expected) {
        JsonNode aud = payload.get("aud");
        if (aud != null && aud.isArray()) {
            for (JsonNode value : aud) {
                if (expected.equals(value.asText())) {
                    return true;
                }
            }
        }
        if (aud != null && expected.equals(aud.asText())) {
            return true;
        }
        return expected.equals(text(payload, "client_id"));
    }

    private void verifySignature(String signingInput, String signaturePart, PublicKey key) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(URL_DECODER.decode(signaturePart))) {
                throw new JwtAuthException(401, "JWT signature verification failed");
            }
        } catch (IllegalArgumentException e) {
            throw new JwtAuthException(401, "JWT signature is not base64url");
        } catch (GeneralSecurityException e) {
            throw new JwtAuthException(401, "JWT signature verification failed", e);
        }
    }

    private JsonNode parseJson(String part, String label) {
        try {
            return MAPPER.readTree(URL_DECODER.decode(part));
        } catch (IllegalArgumentException e) {
            throw new JwtAuthException(401, label + " is not base64url");
        } catch (IOException e) {
            throw new JwtAuthException(401, label + " is not valid JSON", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static String firstText(JsonNode node, String first, String second) {
        String value = text(node, first);
        return value.isBlank() ? text(node, second) : value;
    }

    private static long longClaim(JsonNode node, String field, long defaultValue) {
        JsonNode value = node.get(field);
        return value == null || !value.canConvertToLong() ? defaultValue : value.asLong();
    }

    record VerifiedClaims(String subject, String clientId, String tokenUse) {
    }

    interface JwkProvider {
        PublicKey key(String kid);
    }

    static final class StaticJwkProvider implements JwkProvider {
        private final Map<String, PublicKey> keys;

        StaticJwkProvider(Map<String, PublicKey> keys) {
            this.keys = Map.copyOf(keys);
        }

        @Override
        public PublicKey key(String kid) {
            PublicKey key = keys.get(kid);
            if (key == null) {
                throw new JwtAuthException(401, "unknown JWT kid");
            }
            return key;
        }
    }

    @FunctionalInterface
    interface KeyFetcher {
        Map<String, PublicKey> fetch();
    }

    static final class HttpJwkProvider implements JwkProvider {
        private final KeyFetcher fetcher;
        private final LongSupplier nowMillis;
        private volatile Map<String, PublicKey> cachedKeys = Map.of();
        private volatile long expiresAtMillis = 0L;
        private volatile long nextUnknownKidRefetchAtMillis = 0L;
        private final AtomicBoolean refetchInProgress = new AtomicBoolean(false);

        HttpJwkProvider(KeyFetcher fetcher, LongSupplier nowMillis) {
            this.fetcher = Objects.requireNonNull(fetcher, "fetcher is required");
            this.nowMillis = Objects.requireNonNull(nowMillis, "nowMillis is required");
        }

        HttpJwkProvider(String issuer, HttpClient httpClient) {
            this(httpFetcher(issuer, httpClient), System::currentTimeMillis);
        }

        private static KeyFetcher httpFetcher(String issuer, HttpClient httpClient) {
            Objects.requireNonNull(httpClient, "httpClient is required");
            String jwksUri = issuer + "/.well-known/jwks.json";
            return () -> httpFetch(jwksUri, httpClient);
        }

        @Override
        public PublicKey key(String kid) {
            Map<String, PublicKey> keys = cachedKeys;
            PublicKey key = keys.get(kid);
            long now = nowMillis.getAsLong();
            boolean expired = now >= expiresAtMillis;
            // Refetch when the cache has expired, or the kid is unknown and we have not
            // refetched for an unknown kid recently. The rate limit bounds refetches driven
            // by a flood of tokens carrying random kids; a single-flight guard (CAS) runs the
            // fetch WITHOUT holding a lock across the I/O.
            boolean needsRefetch = expired || (key == null && now >= nextUnknownKidRefetchAtMillis);
            if (needsRefetch && refetchInProgress.compareAndSet(false, true)) {
                try {
                    nextUnknownKidRefetchAtMillis = now + UNKNOWN_KID_REFETCH_INTERVAL.toMillis();
                    Map<String, PublicKey> fresh = fetcher.fetch();
                    cachedKeys = fresh;
                    expiresAtMillis = now + JWKS_CACHE_TTL.toMillis();
                    key = fresh.get(kid);
                } catch (JwtAuthException fetchError) {
                    // Serve-stale: a transient JWKS fetch failure must not reject tokens whose
                    // signing key we already hold. Keep the last-good cache and serve the stale
                    // key; retry at most once per STALE_RETRY_BACKOFF so a down endpoint is not
                    // hammered. Token exp/claims are validated independently in verify().
                    if (key == null) {
                        throw fetchError;
                    }
                    expiresAtMillis = now + STALE_RETRY_BACKOFF.toMillis();
                    log.warn("Cognito JWKS fetch failed; serving cached signing keys (stale), "
                            + "retrying in {}s: {}", STALE_RETRY_BACKOFF.toSeconds(), fetchError.getMessage());
                } finally {
                    refetchInProgress.set(false);
                }
            }
            if (key == null) {
                throw new JwtAuthException(401, "unknown JWT kid");
            }
            return key;
        }

        private static Map<String, PublicKey> httpFetch(String jwksUri, HttpClient httpClient) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(jwksUri))
                        .timeout(JWKS_REQUEST_TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    throw new JwtAuthException(503, "failed to fetch Cognito JWKS");
                }
                return parseJwks(response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JwtAuthException(503, "interrupted fetching Cognito JWKS", e);
            } catch (IOException | IllegalArgumentException e) {
                throw new JwtAuthException(503, "failed to fetch Cognito JWKS", e);
            }
        }

        private static Map<String, PublicKey> parseJwks(String body) throws IOException {
            JsonNode keysNode = MAPPER.readTree(body).get("keys");
            if (keysNode == null || !keysNode.isArray()) {
                throw new JwtAuthException(503, "Cognito JWKS response has no keys");
            }
            Map<String, PublicKey> parsed = new HashMap<>();
            Iterator<JsonNode> elements = keysNode.elements();
            while (elements.hasNext()) {
                JsonNode key = elements.next();
                if (!"RSA".equals(text(key, "kty"))) {
                    continue;
                }
                parsed.put(text(key, "kid"), rsaKey(text(key, "n"), text(key, "e")));
            }
            return parsed;
        }

        private static RSAPublicKey rsaKey(String modulus, String exponent) {
            try {
                BigInteger n = new BigInteger(1, URL_DECODER.decode(modulus));
                BigInteger e = new BigInteger(1, URL_DECODER.decode(exponent));
                return (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new RSAPublicKeySpec(n, e));
            } catch (GeneralSecurityException | IllegalArgumentException ex) {
                throw new JwtAuthException(503, "invalid RSA key in Cognito JWKS", ex);
            }
        }
    }

    static final class JwtAuthException extends RuntimeException {
        private final int status;

        JwtAuthException(int status, String message) {
            super(message);
            this.status = status;
        }

        JwtAuthException(int status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        int status() {
            return status;
        }
    }
}
