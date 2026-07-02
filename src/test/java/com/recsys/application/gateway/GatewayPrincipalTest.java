package com.recsys.application.gateway;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayPrincipalTest {

    @Test
    void ofJwt_keysOnSubjectAndEmitsIdentityHeaders() {
        GatewayPrincipal p = GatewayPrincipal.ofJwt(
                new CognitoJwtVerifier.VerifiedClaims("user-1", "app-client", "access"));
        assertEquals("user:user-1", p.rateLimitKey());
        assertEquals(Map.of(
                "x-authenticated-subject", "user-1",
                "x-authenticated-client-id", "app-client",
                "x-authenticated-token-use", "access"), p.identityHeaders());
    }

    @Test
    void ofJwt_fallsBackToClientWhenSubjectBlank() {
        GatewayPrincipal p = GatewayPrincipal.ofJwt(
                new CognitoJwtVerifier.VerifiedClaims("", "app-client", "access"));
        assertEquals("client:app-client", p.rateLimitKey());
    }

    @Test
    void ofApiKey_hashesKeyNeverLeaksIt_andIsStable() {
        GatewayPrincipal p = GatewayPrincipal.ofApiKey("super-secret-key");
        assertTrue(p.rateLimitKey().startsWith("apikey:"));
        assertFalse(p.rateLimitKey().contains("super-secret-key"));
        assertEquals(Map.of("x-authenticated-client-id", "service"), p.identityHeaders());
        assertEquals(p.rateLimitKey(), GatewayPrincipal.ofApiKey("super-secret-key").rateLimitKey());
    }

    @Test
    void anonymous_hasNoIdentityHeaders() {
        GatewayPrincipal p = GatewayPrincipal.anonymous();
        assertEquals("anonymous", p.rateLimitKey());
        assertTrue(p.identityHeaders().isEmpty());
    }
}
