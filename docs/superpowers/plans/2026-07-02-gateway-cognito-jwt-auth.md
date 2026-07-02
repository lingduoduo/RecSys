# Gateway Cognito JWT Authentication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the Armeria gateway authorize a request that presents **either** a valid static API key **or** a valid Cognito JWT, by salvaging a dependency-free RS256/JWKS verifier and wiring it into the existing `GatewayAuthenticator`.

**Architecture:** Task 1 adds a standalone, transport-agnostic `CognitoJwtVerifier` (+ `CognitoConfig`) in `com.recsys.application.gateway`, ported from the retired branch with the Jetty servlet shim removed. Task 2 extends the existing `GatewayAuthenticator` to an accept-either credential check (strict superset of today's API-key behavior) and adds the `GATEWAY_COGNITO_*` config. No proxy call-site or transport changes; no new Maven dependencies.

**Tech Stack:** Java 17, Armeria, JDK crypto (`java.security.Signature`, `RSAPublicKey`), Jackson (already present), JUnit 5 + JUnit Jupiter Assertions.

## Global Constraints

- Java package root `com.recsys`; tests mirror under `src/test/java/com/recsys/...`.
- All new Java lives in `com.recsys.application.gateway`. Config in `k8s/base/configmap.yaml`. No changes to `GatewayProxyService`/`LlmProxyService` call sites.
- **No new Maven dependencies** — JDK + Jackson only.
- Backward-compatible: with no `GATEWAY_COGNITO_*` env set, the gateway behaves exactly as today. The accept-either check must remain a **strict superset** of the current API-key behavior (a request accepted today must still be accepted).
- Only `RS256` accepted; 60-second clock-skew allowance; JWKS cached 5 minutes; JWKS fetch failure fails closed.
- Env var names: `GATEWAY_COGNITO_ISSUER`, `GATEWAY_COGNITO_AUDIENCE`, `GATEWAY_COGNITO_TOKEN_USE` (default `access`), following the existing `GATEWAY_*` convention.
- Test framework: JUnit 5 (`org.junit.jupiter.api.Test`) with `org.junit.jupiter.api.Assertions` (`assertEquals`/`assertThrows`) — matching the salvaged test.
- Surefire runs with `-Xshare:off` (already configured); no special flags needed for these tests.
- One commit per task. Never commit to `main`; work stays on branch `feat/gateway-cognito-jwt-auth`.
- Verification: `mvn test -Dtest=...` per task and `mvn test` for the full suite; `kubectl kustomize k8s/base` for the config change.

---

### Task 1: `CognitoConfig` + `CognitoJwtVerifier` (salvaged verifier)

**Files:**
- Create: `src/main/java/com/recsys/application/gateway/CognitoConfig.java`
- Create: `src/main/java/com/recsys/application/gateway/CognitoJwtVerifier.java`
- Test: `src/test/java/com/recsys/application/gateway/CognitoJwtVerifierTest.java`

**Interfaces:**
- Consumes: `com.recsys.config.EnvVars.EnvReader` (functional `String get(String)`), already used by `GatewayAuthenticator.fromEnvironment`.
- Produces (used by Task 2):
  - `CognitoConfig` — record `(String issuer, String audience, java.util.Set<String> tokenUses)` with `static CognitoConfig fromEnvironment(EnvVars.EnvReader env)` and `boolean isConfigured()` (true iff issuer non-blank).
  - `CognitoJwtVerifier` — package-private final class; `CognitoJwtVerifier(CognitoConfig, java.net.http.HttpClient, java.time.Clock)` (prod) and `CognitoJwtVerifier(CognitoConfig, JwkProvider, Clock)` (tests); `VerifiedClaims verify(String token)` throwing `CognitoJwtVerifier.JwtAuthException`. Nested: `record VerifiedClaims(String subject, String clientId, String tokenUse)`, `interface JwkProvider { PublicKey key(String kid); }`, `static final class StaticJwkProvider`, `static final class JwtAuthException extends RuntimeException { int status(); }`.

- [ ] **Step 1: Write the failing test** (salvaged from the retired branch, repackaged)

Create `src/test/java/com/recsys/application/gateway/CognitoJwtVerifierTest.java`:

```java
package com.recsys.application.gateway;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CognitoJwtVerifierTest {
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-25T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void verify_acceptsValidCognitoJwt() throws Exception {
        KeyPair keyPair = rsaKeyPair();
        CognitoJwtVerifier verifier = verifier(keyPair);

        CognitoJwtVerifier.VerifiedClaims claims = verifier.verify(token(keyPair,
                "\"iss\":\"https://cognito-idp.us-east-1.amazonaws.com/us-east-1_demo\","
                        + "\"sub\":\"user-123\","
                        + "\"client_id\":\"app-client\","
                        + "\"token_use\":\"access\","
                        + "\"exp\":1780000000"));

        assertEquals("user-123", claims.subject());
        assertEquals("app-client", claims.clientId());
        assertEquals("access", claims.tokenUse());
    }

    @Test
    void verify_rejectsAudienceMismatch() throws Exception {
        KeyPair keyPair = rsaKeyPair();
        CognitoJwtVerifier verifier = verifier(keyPair);

        String token = token(keyPair,
                "\"iss\":\"https://cognito-idp.us-east-1.amazonaws.com/us-east-1_demo\","
                        + "\"sub\":\"user-123\","
                        + "\"client_id\":\"wrong-client\","
                        + "\"token_use\":\"access\","
                        + "\"exp\":1780000000");

        assertThrows(CognitoJwtVerifier.JwtAuthException.class, () -> verifier.verify(token));
    }

    @Test
    void verify_rejectsExpiredJwt() throws Exception {
        KeyPair keyPair = rsaKeyPair();
        CognitoJwtVerifier verifier = verifier(keyPair);

        String token = token(keyPair,
                "\"iss\":\"https://cognito-idp.us-east-1.amazonaws.com/us-east-1_demo\","
                        + "\"sub\":\"user-123\","
                        + "\"client_id\":\"app-client\","
                        + "\"token_use\":\"access\","
                        + "\"exp\":1");

        assertThrows(CognitoJwtVerifier.JwtAuthException.class, () -> verifier.verify(token));
    }

    @Test
    void verify_rejectsTamperedSignature() throws Exception {
        KeyPair keyPair = rsaKeyPair();
        CognitoJwtVerifier verifier = verifier(keyPair);
        String valid = token(keyPair,
                "\"iss\":\"https://cognito-idp.us-east-1.amazonaws.com/us-east-1_demo\","
                        + "\"sub\":\"user-123\","
                        + "\"client_id\":\"app-client\","
                        + "\"token_use\":\"access\","
                        + "\"exp\":1780000000");
        String tampered = valid.substring(0, valid.length() - 2) + "xx";

        assertThrows(CognitoJwtVerifier.JwtAuthException.class, () -> verifier.verify(tampered));
    }

    private CognitoJwtVerifier verifier(KeyPair keyPair) {
        CognitoConfig config = new CognitoConfig(
                "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_demo",
                "app-client",
                Set.of("access", "id"));
        return new CognitoJwtVerifier(
                config,
                new CognitoJwtVerifier.StaticJwkProvider(Map.of("kid-1", keyPair.getPublic())),
                clock);
    }

    private static String token(KeyPair keyPair, String payloadFields) throws Exception {
        String header = base64("{\"alg\":\"RS256\",\"kid\":\"kid-1\",\"typ\":\"JWT\"}");
        String payload = base64("{" + payloadFields + "}");
        String signingInput = header + "." + payload;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + URL_ENCODER.encodeToString(signature.sign());
    }

    private static String base64(String json) {
        return URL_ENCODER.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails (does not compile — classes absent)**

Run: `mvn test -Dtest=CognitoJwtVerifierTest`
Expected: FAIL — compilation error, `CognitoConfig` / `CognitoJwtVerifier` cannot be resolved.

- [ ] **Step 3: Create `CognitoConfig.java`**

Create `src/main/java/com/recsys/application/gateway/CognitoConfig.java`:

```java
package com.recsys.application.gateway;

import com.recsys.config.EnvVars;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Cognito verification parameters. Built from the GATEWAY_COGNITO_* environment.
 * A blank issuer means Cognito auth is not configured ({@link #isConfigured()} is false).
 */
record CognitoConfig(String issuer, String audience, Set<String> tokenUses) {

    static CognitoConfig fromEnvironment(EnvVars.EnvReader env) {
        String issuer = stripTrailingSlash(read(env, "GATEWAY_COGNITO_ISSUER", ""));
        String audience = read(env, "GATEWAY_COGNITO_AUDIENCE", "");
        String tokenUseCsv = read(env, "GATEWAY_COGNITO_TOKEN_USE", "access");
        Set<String> tokenUses = Stream.of(tokenUseCsv.split(","))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        return new CognitoConfig(issuer, audience, tokenUses);
    }

    boolean isConfigured() {
        return issuer != null && !issuer.isBlank();
    }

    private static String read(EnvVars.EnvReader env, String name, String defaultValue) {
        String value = env.get(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String stripped = value.trim();
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }
}
```

- [ ] **Step 4: Create `CognitoJwtVerifier.java`** (ported core; servlet shim removed)

Create `src/main/java/com/recsys/application/gateway/CognitoJwtVerifier.java`:

```java
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

/**
 * Verifies AWS Cognito RS256 JWTs against the pool's JWKS using only the JDK and Jackson.
 * Transport-agnostic: callers extract the bearer token and call {@link #verify(String)}.
 */
final class CognitoJwtVerifier {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final Duration JWKS_CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration JWKS_REQUEST_TIMEOUT = Duration.ofSeconds(2);
    private static final long ALLOWED_CLOCK_SKEW_SECONDS = 60L;

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

    private static final class HttpJwkProvider implements JwkProvider {
        private final String jwksUri;
        private final HttpClient httpClient;
        private volatile Map<String, PublicKey> cachedKeys = Map.of();
        private volatile long expiresAtMillis = 0L;

        private HttpJwkProvider(String issuer, HttpClient httpClient) {
            this.jwksUri = issuer + "/.well-known/jwks.json";
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient is required");
        }

        @Override
        public PublicKey key(String kid) {
            Map<String, PublicKey> keys = cachedKeys;
            long now = System.currentTimeMillis();
            if (!keys.containsKey(kid) || now >= expiresAtMillis) {
                synchronized (this) {
                    if (!cachedKeys.containsKey(kid) || now >= expiresAtMillis) {
                        cachedKeys = fetchKeys();
                        expiresAtMillis = now + JWKS_CACHE_TTL.toMillis();
                    }
                    keys = cachedKeys;
                }
            }
            PublicKey key = keys.get(kid);
            if (key == null) {
                throw new JwtAuthException(401, "unknown JWT kid");
            }
            return key;
        }

        private Map<String, PublicKey> fetchKeys() {
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

        private Map<String, PublicKey> parseJwks(String body) throws IOException {
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

        private RSAPublicKey rsaKey(String modulus, String exponent) {
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
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn test -Dtest=CognitoJwtVerifierTest`
Expected: PASS — `Tests run: 4, Failures: 0, Errors: 0`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/application/gateway/CognitoConfig.java \
        src/main/java/com/recsys/application/gateway/CognitoJwtVerifier.java \
        src/test/java/com/recsys/application/gateway/CognitoJwtVerifierTest.java
git commit -m "feat(gateway): add dependency-free Cognito JWT verifier

Salvaged from the retired feature/aws-saga-tcc-orchestration branch: RS256
verification via JDK crypto + Jackson, JWKS fetch with 5-min cache, claims
validation. Transport-agnostic verify(token); servlet shim dropped.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Accept-either `GatewayAuthenticator` + Cognito config

**Files:**
- Modify: `src/main/java/com/recsys/application/gateway/GatewayAuthenticator.java`
- Modify: `k8s/base/configmap.yaml` (add three blank-default `GATEWAY_COGNITO_*` keys)
- Test: `src/test/java/com/recsys/application/gateway/GatewayAuthenticatorTest.java`

**Interfaces:**
- Consumes: `CognitoJwtVerifier` and `CognitoConfig` from Task 1.
- Produces: unchanged public seam `HttpResponse check(RequestHeaders headers, String path)` (null=allow, 401=reject) and `boolean isEnabled()`, now also honoring Cognito JWTs. `fromEnvironment(EnvVars.EnvReader)` builds a verifier when `GATEWAY_COGNITO_ISSUER` is set.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/application/gateway/GatewayAuthenticatorTest.java`:

```java
package com.recsys.application.gateway;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GatewayAuthenticatorTest {
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-25T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void check_allowsValidApiKeyViaHeader() {
        GatewayAuthenticator auth = authenticator(rsaKeyPairOrThrow());
        RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/model/predict",
                HttpHeaderNames.of("x-api-key"), "key-1");
        assertNull(auth.check(headers, "/model/predict"));
    }

    @Test
    void check_allowsValidCognitoJwt() throws Exception {
        KeyPair keyPair = rsaKeyPair();
        GatewayAuthenticator auth = authenticator(keyPair);
        String jwt = token(keyPair,
                "\"iss\":\"https://cognito-idp.us-east-1.amazonaws.com/us-east-1_demo\","
                        + "\"sub\":\"user-1\",\"client_id\":\"app-client\","
                        + "\"token_use\":\"access\",\"exp\":1780000000");
        RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/model/predict",
                HttpHeaderNames.AUTHORIZATION, "Bearer " + jwt);
        assertNull(auth.check(headers, "/model/predict"));
    }

    @Test
    void check_rejectsWhenNeitherCredentialPresent() {
        GatewayAuthenticator auth = authenticator(rsaKeyPairOrThrow());
        RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/model/predict");
        HttpResponse rejection = auth.check(headers, "/model/predict");
        assertNotNull(rejection);
        assertEquals(401, rejection.aggregate().join().status().code());
    }

    @Test
    void check_bypassesPublicPath() {
        GatewayAuthenticator auth = authenticator(rsaKeyPairOrThrow());
        RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/health");
        assertNull(auth.check(headers, "/health"));
    }

    @Test
    void check_disabledPassesThrough() {
        RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/model/predict");
        assertNull(GatewayAuthenticator.disabled().check(headers, "/model/predict"));
    }

    private GatewayAuthenticator authenticator(KeyPair keyPair) {
        CognitoConfig config = new CognitoConfig(
                "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_demo",
                "app-client",
                Set.of("access", "id"));
        CognitoJwtVerifier verifier = new CognitoJwtVerifier(
                config,
                new CognitoJwtVerifier.StaticJwkProvider(Map.of("kid-1", keyPair.getPublic())),
                clock);
        return GatewayAuthenticator.forTesting(Set.of("key-1"), Set.of("/health"), verifier);
    }

    private static String token(KeyPair keyPair, String payloadFields) throws Exception {
        String header = base64("{\"alg\":\"RS256\",\"kid\":\"kid-1\",\"typ\":\"JWT\"}");
        String payload = base64("{" + payloadFields + "}");
        String signingInput = header + "." + payload;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + URL_ENCODER.encodeToString(signature.sign());
    }

    private static String base64(String json) {
        return URL_ENCODER.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static KeyPair rsaKeyPairOrThrow() {
        try {
            return rsaKeyPair();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=GatewayAuthenticatorTest`
Expected: FAIL — compilation error: `GatewayAuthenticator.forTesting(...)` does not exist yet.

- [ ] **Step 3: Extend `GatewayAuthenticator.java`**

Apply these edits to `src/main/java/com/recsys/application/gateway/GatewayAuthenticator.java`.

(a) Add imports after the existing `import com.recsys.config.EnvVars;` line:

```java
import java.net.http.HttpClient;
import java.time.Clock;
```

(b) Replace the field/constructor region. Change:

```java
    private static final GatewayAuthenticator DISABLED = new GatewayAuthenticator(Set.of(), Set.of("/health"));
    private static final String AUTHORIZATION_PREFIX = "Bearer ";

    private final Set<String> apiKeys;
    private final Set<String> publicPaths;

    private GatewayAuthenticator(Set<String> apiKeys, Set<String> publicPaths) {
        this.apiKeys = Set.copyOf(apiKeys);
        this.publicPaths = Set.copyOf(publicPaths);
    }
```

to:

```java
    private static final GatewayAuthenticator DISABLED = new GatewayAuthenticator(Set.of(), Set.of("/health"), null);
    private static final String AUTHORIZATION_PREFIX = "Bearer ";

    private final Set<String> apiKeys;
    private final Set<String> publicPaths;
    private final CognitoJwtVerifier jwtVerifier;

    private GatewayAuthenticator(Set<String> apiKeys, Set<String> publicPaths, CognitoJwtVerifier jwtVerifier) {
        this.apiKeys = Set.copyOf(apiKeys);
        this.publicPaths = Set.copyOf(publicPaths);
        this.jwtVerifier = jwtVerifier;
    }

    // Test-visible factory: inject a verifier backed by a static JWK provider.
    static GatewayAuthenticator forTesting(Set<String> apiKeys, Set<String> publicPaths, CognitoJwtVerifier jwtVerifier) {
        return new GatewayAuthenticator(apiKeys, publicPaths, jwtVerifier);
    }
```

(c) Replace `fromEnvironment(EnvVars.EnvReader env)`. Change:

```java
    public static GatewayAuthenticator fromEnvironment(EnvVars.EnvReader env) {
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
```

to:

```java
    public static GatewayAuthenticator fromEnvironment(EnvVars.EnvReader env) {
        Set<String> keys = parseCsv(env.get("GATEWAY_API_KEYS"));
        CognitoConfig cognito = CognitoConfig.fromEnvironment(env);
        CognitoJwtVerifier verifier = cognito.isConfigured()
                ? new CognitoJwtVerifier(cognito, HttpClient.newHttpClient(), Clock.systemUTC())
                : null;
        if (keys.isEmpty() && verifier == null) {
            return disabled();
        }
        Set<String> publicPaths = parseCsv(env.get("GATEWAY_PUBLIC_PATHS"));
        if (publicPaths.isEmpty()) {
            publicPaths = Set.of("/health");
        }
        return new GatewayAuthenticator(keys, publicPaths, verifier);
    }
```

(d) Replace `isEnabled()`. Change:

```java
    public boolean isEnabled() {
        return !apiKeys.isEmpty();
    }
```

to:

```java
    public boolean isEnabled() {
        return !apiKeys.isEmpty() || jwtVerifier != null;
    }
```

(e) Replace the `check(...)` body's credential logic. Change:

```java
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
```

to:

```java
        String bearer = bearerToken(headers.get(HttpHeaderNames.AUTHORIZATION));
        String provided = firstNonBlank(headers.get(HttpHeaderNames.of("x-api-key")), bearer);

        if (provided != null) {
            boolean matched = false;
            for (String key : apiKeys) {
                matched |= constantTimeEquals(key, provided);
            }
            if (matched) return null;
        }

        if (jwtVerifier != null && bearer != null && jwtAccepts(bearer)) {
            return null;
        }

        return HttpResponse.of(
```

(f) Add a private helper immediately after the `check(...)` method (before `isPublic`):

```java
    private boolean jwtAccepts(String token) {
        try {
            jwtVerifier.verify(token);
            return true;
        } catch (CognitoJwtVerifier.JwtAuthException e) {
            return false;
        }
    }
```

Rationale: `check()` stays a strict superset of the current behavior — an API key via `x-api-key` or `Authorization: Bearer <key>` is still accepted; additionally, a `Bearer <jwt>` that verifies is accepted.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=GatewayAuthenticatorTest`
Expected: PASS — `Tests run: 5, Failures: 0, Errors: 0`.

- [ ] **Step 5: Add the Cognito config keys**

In `k8s/base/configmap.yaml`, add these three keys in the `data:` block, immediately after the existing `LLM_EXPLANATION_SERVICE_URL` line (any location within `data:` is fine; keep them grouped):

```yaml
  # Cognito JWT auth (blank = disabled; gateway then uses API keys only).
  GATEWAY_COGNITO_ISSUER: ""
  GATEWAY_COGNITO_AUDIENCE: ""
  GATEWAY_COGNITO_TOKEN_USE: "access"
```

- [ ] **Step 6: Verify config renders and the full suite is green**

Run:
```bash
kubectl kustomize k8s/base | grep -E 'GATEWAY_COGNITO_(ISSUER|AUDIENCE|TOKEN_USE)'
mvn test
```
Expected: the three keys render (`ISSUER`/`AUDIENCE` blank, `TOKEN_USE` "access"); the full suite passes with no regression (`BUILD SUCCESS`).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/application/gateway/GatewayAuthenticator.java \
        src/test/java/com/recsys/application/gateway/GatewayAuthenticatorTest.java \
        k8s/base/configmap.yaml
git commit -m "feat(gateway): accept either API key or Cognito JWT

Extend GatewayAuthenticator to authorize a request presenting a valid API key
OR a valid Cognito JWT (strict superset of the prior API-key check). Wire the
verifier from GATEWAY_COGNITO_* env; blank issuer keeps behavior unchanged.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Component 1 (`CognitoJwtVerifier` salvage + `CognitoConfig`, dependency-free, `verify()` core, JwkProvider/Static/Http, 5-min cache, RS256, 60s skew) → Task 1. ✓
- Component 2 (`GatewayAuthenticator` accept-either, keep `check()→HttpResponse`, `isEnabled` = keys OR cognito, `fromEnvironment` builds verifier) → Task 2 Steps 3. ✓
- Component 3 (config `GATEWAY_COGNITO_ISSUER/AUDIENCE/TOKEN_USE`, blank defaults) → Task 2 Step 5. ✓
- Component 4 (verifier test salvaged; authenticator accept-either test) → Task 1 Step 1, Task 2 Step 1. ✓
- Scope boundaries: no `GatewayPrincipal`/`GatewayAuthResult`, no per-principal rate limiting, no WAF, no new deps, no call-site changes → none added. ✓
- Backward-compat superset preserved → Task 2 Step 3(e) keeps x-api-key/bearer-as-key acceptance and only ADDS JWT. ✓

**Placeholder scan:** every code step shows complete file content or exact before/after blocks; every verify step is a concrete `mvn`/`kubectl` command with expected output. No TBD/TODO. ✓

**Type consistency:** `CognitoConfig(String issuer, String audience, Set<String> tokenUses)`, `CognitoConfig.fromEnvironment(EnvVars.EnvReader)`, `CognitoConfig.isConfigured()`, `CognitoJwtVerifier(CognitoConfig, JwkProvider, Clock)` / `(CognitoConfig, HttpClient, Clock)`, `CognitoJwtVerifier.verify(String) → VerifiedClaims`, `CognitoJwtVerifier.StaticJwkProvider`, `CognitoJwtVerifier.JwtAuthException`, and `GatewayAuthenticator.forTesting(Set,Set,CognitoJwtVerifier)` are used identically across Tasks 1 and 2 and both tests. ✓
