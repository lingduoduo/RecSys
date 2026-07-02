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
