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

    @Test
    void ofJwt_isUserTierCarryingTheAppUserId() {
        GatewayPrincipal p = GatewayPrincipal.ofJwt(
                new CognitoJwtVerifier.VerifiedClaims("sub-1", "app-client", "access", "42"));
        assertEquals(GatewayPrincipal.Tier.USER, p.tier());
        assertEquals("42", p.appUserId());
    }

    @Test
    void ofJwt_isUserTierEvenWhenTheClaimIsMissing() {
        // Credential type decides the tier, not claim presence. A JWT whose userId claim did not
        // resolve must stay USER-tier so user-scoped routes deny it, rather than falling through
        // to service-tier freedom — that fall-through is how a claim-name typo becomes a bypass.
        GatewayPrincipal p = GatewayPrincipal.ofJwt(
                new CognitoJwtVerifier.VerifiedClaims("sub-1", "app-client", "access", ""));
        assertEquals(GatewayPrincipal.Tier.USER, p.tier());
        assertEquals("", p.appUserId());
    }

    @Test
    void ofApiKey_isServiceTierWithNoAppUserId() {
        GatewayPrincipal p = GatewayPrincipal.ofApiKey("super-secret-key");
        assertEquals(GatewayPrincipal.Tier.SERVICE, p.tier());
        assertEquals("", p.appUserId());
    }

    @Test
    void anonymous_isServiceTier() {
        // Anonymous exists only under GATEWAY_ALLOW_ANONYMOUS=true, which is dev/local. Tiering it
        // USER would 403 every local request against a user-scoped route.
        assertEquals(GatewayPrincipal.Tier.SERVICE, GatewayPrincipal.anonymous().tier());
    }

    @Test
    void appUserId_isNotForwardedAsAHeader() {
        // The finding being closed is "the gateway injects an identity header no backend reads".
        // A second unread header would reproduce it. The comparison stays at the gateway.
        GatewayPrincipal p = GatewayPrincipal.ofJwt(
                new CognitoJwtVerifier.VerifiedClaims("sub-1", "app-client", "access", "42"));
        assertFalse(p.identityHeaders().containsKey("x-authenticated-user-id"));
        assertEquals(Map.of(
                "x-authenticated-subject", "sub-1",
                "x-authenticated-client-id", "app-client",
                "x-authenticated-token-use", "access"), p.identityHeaders());
    }
}
