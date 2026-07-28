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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;

class GatewayAuthenticatorTest {
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-25T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void check_allowsValidApiKeyViaHeader() {
        GatewayAuthenticator auth = authenticator(rsaKeyPairOrThrow());
        RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/model/predict",
                HttpHeaderNames.of("x-api-key"), "key-1");
        assertFalse(auth.check(headers, "/model/predict").rejected());
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
        GatewayAuthResult r = auth.check(headers, "/model/predict");
        assertFalse(r.rejected());
        assertEquals("user:user-1", r.principal().rateLimitKey());
    }

    @Test
    void check_rejectsWhenNeitherCredentialPresent() {
        GatewayAuthenticator auth = authenticator(rsaKeyPairOrThrow());
        RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/model/predict");
        GatewayAuthResult r = auth.check(headers, "/model/predict");
        assertTrue(r.rejected());
        assertEquals(401, r.rejection().aggregate().join().status().code());
    }

    @Test
    void check_bypassesPublicPath() {
        GatewayAuthenticator auth = authenticator(rsaKeyPairOrThrow());
        RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/health");
        assertFalse(auth.check(headers, "/health").rejected());
    }

    @Test
    void check_disabledPassesThrough() {
        RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/model/predict");
        assertFalse(GatewayAuthenticator.disabled().check(headers, "/model/predict").rejected());
    }

    @Test
    void check_allowsBearerTokenAsApiKey() {
        // Backward-compat: an API key presented as a bearer token is still accepted (no JWT involved).
        GatewayAuthenticator auth = GatewayAuthenticator.fromEnvironment(Map.of("GATEWAY_API_KEYS", "alpha")::get);
        RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/model/predict",
                HttpHeaderNames.AUTHORIZATION, "Bearer alpha");
        assertFalse(auth.check(headers, "/model/predict").rejected());
    }

    @Test
    void fromEnvironment_failsClosedWhenNothingConfigured() {
        // Security: with no API keys and no Cognito issuer, the gateway must NOT silently run
        // wide open. Startup fails unless anonymous access is explicitly opted into.
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> GatewayAuthenticator.fromEnvironment(Map.<String, String>of()::get));
        assertTrue(ex.getMessage().contains("GATEWAY_ALLOW_ANONYMOUS"));
    }

    @Test
    void fromEnvironment_allowsAnonymousWhenExplicitlyOptedIn() {
        // Local/dev escape hatch: an explicit opt-in disables auth (all callers anonymous).
        GatewayAuthenticator auth = GatewayAuthenticator.fromEnvironment(
                Map.of("GATEWAY_ALLOW_ANONYMOUS", "true")::get);
        assertFalse(auth.isEnabled());
        assertFalse(auth.check(RequestHeaders.of(HttpMethod.GET, "/model/predict"), "/model/predict").rejected());
    }

    @Test
    void fromEnvironment_apiKeysStillEnableAuthWithoutOptIn() {
        // The opt-in is only consulted when nothing else is configured; keys alone still enable auth.
        GatewayAuthenticator auth = GatewayAuthenticator.fromEnvironment(
                Map.of("GATEWAY_API_KEYS", "alpha")::get);
        assertTrue(auth.isEnabled());
    }

    @Test
    void fromEnvironment_requiresAudienceWhenIssuerSet() {
        java.util.Map<String, String> env = java.util.Map.of(
                "GATEWAY_COGNITO_ISSUER", "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_demo");
        assertThrows(IllegalStateException.class, () -> GatewayAuthenticator.fromEnvironment(env::get));
    }

    @Test
    void check_exactPublicPaths_allowCatalogReadsButRejectUserRouteAndBarePrefix() {
        // Production value (k8s/base/configmap.yaml): exact catalog read paths only.
        GatewayAuthenticator auth = GatewayAuthenticator.forTesting(
                Set.of("key-1"),
                Set.of("/health", "/api/catalog/item", "/api/catalog/similar"),
                null);

        RequestHeaders anonymous = RequestHeaders.of(HttpMethod.GET, "/api/catalog/item");
        assertFalse(auth.check(anonymous, "/api/catalog/item").rejected());
        RequestHeaders anonymousSimilar = RequestHeaders.of(HttpMethod.GET, "/api/catalog/similar");
        assertFalse(auth.check(anonymousSimilar, "/api/catalog/similar").rejected());

        // The whole point: the PII route must still require auth — it is not in the public set.
        RequestHeaders userRoute = RequestHeaders.of(HttpMethod.GET, "/api/catalog/user");
        assertTrue(auth.check(userRoute, "/api/catalog/user").rejected());

        // The bare prefix itself (with nothing configured to expose it) is also rejected.
        RequestHeaders bareCatalog = RequestHeaders.of(HttpMethod.GET, "/api/catalog");
        assertTrue(auth.check(bareCatalog, "/api/catalog").rejected());
    }

    @Test
    void check_catalogMoviesRequiresConfiguredApiKey() {
        GatewayAuthenticator auth = GatewayAuthenticator.forTesting(
                Set.of("key-1"),
                Set.of("/health", "/api/catalog/item", "/api/catalog/similar"),
                null);
        String path = "/api/catalog/v1/catalog/movies";

        RequestHeaders anonymous = RequestHeaders.of(HttpMethod.GET, path);
        assertTrue(auth.check(anonymous, path).rejected());

        RequestHeaders authenticated = RequestHeaders.of(
                HttpMethod.GET, path, HttpHeaderNames.of("x-api-key"), "key-1");
        assertFalse(auth.check(authenticated, path).rejected());
    }

    @Test
    void check_barePrefixPublicPath_cannotExposeProtectedUserRoute() {
        // The never-public guard: even a mis-set bare-prefix GATEWAY_PUBLIC_PATHS=/api/catalog
        // (which would otherwise match /api/catalog/user via the isPublic boundary rule) can NOT
        // expose the user-data route — PROTECTED_PREFIXES overrides the public-path match.
        GatewayAuthenticator auth = GatewayAuthenticator.forTesting(
                Set.of("key-1"), Set.of("/api/catalog"), null);

        RequestHeaders userRoute = RequestHeaders.of(HttpMethod.GET, "/api/catalog/user");
        assertTrue(auth.check(userRoute, "/api/catalog/user").rejected());
        // A sibling read that is not protected is still exposed by the bare prefix (documents the
        // matching rule; only the user-data prefixes are guarded).
        RequestHeaders movieRoute = RequestHeaders.of(HttpMethod.GET, "/api/catalog/movie");
        assertFalse(auth.check(movieRoute, "/api/catalog/movie").rejected());
    }

    @Test
    void check_protectedPaths_rejectedEvenWhenExplicitlyPublic() {
        // Even a direct, exact misconfiguration cannot open the user-data routes.
        GatewayAuthenticator auth = GatewayAuthenticator.forTesting(
                Set.of("key-1"),
                Set.of("/api/catalog/user", "/api/users", "/api/catalog/item"),
                null);

        assertTrue(auth.check(RequestHeaders.of(HttpMethod.GET, "/api/catalog/user"),
                "/api/catalog/user").rejected());
        assertTrue(auth.check(RequestHeaders.of(HttpMethod.GET, "/api/users/profile"),
                "/api/users/profile").rejected());
        // Unrelated path sharing a textual prefix with a protected one is NOT guarded.
        assertFalse(auth.check(RequestHeaders.of(HttpMethod.GET, "/api/catalog/item"),
                "/api/catalog/item").rejected());
        // An authenticated caller still reaches the protected route — it is auth-required, not blocked.
        RequestHeaders authed = RequestHeaders.of(HttpMethod.GET, "/api/catalog/user",
                HttpHeaderNames.of("x-api-key"), "key-1");
        assertFalse(auth.check(authed, "/api/catalog/user").rejected());
    }

    @Test
    void check_versionedProtectedPathIsStillProtectedAfterNormalization() {
        GatewayAuthenticator auth = authenticator(rsaKeyPairOrThrow());
        // The gateway normalizes before calling check(), so a caller cannot reach a protected
        // path by adding a version segment. This asserts the normalized form is still rejected.
        RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/api/v1/users/profile");

        String normalized = ApiVersion.parse("/api/v1/users/profile").path();
        assertThat(normalized).isEqualTo("/api/users/profile");
        assertTrue(auth.check(headers, normalized).rejected());
    }

    @Test
    void check_versionedCatalogUserIsStillProtectedAfterNormalization() {
        GatewayAuthenticator auth = authenticator(rsaKeyPairOrThrow());
        RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/api/v1/catalog/user?userId=1");

        String normalized = ApiVersion.parse("/api/v1/catalog/user").path();
        assertThat(normalized).isEqualTo("/api/catalog/user");
        assertTrue(auth.check(headers, normalized).rejected());
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
