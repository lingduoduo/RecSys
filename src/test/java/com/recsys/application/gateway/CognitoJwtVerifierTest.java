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
        // Flip the FIRST char of the signature segment so a whole signature byte changes.
        // (Tampering the trailing base64url chars is not reliable: the final byte's low
        // bits are unrepresented, so e.g. an "xx" suffix can decode to the same bytes —
        // the flake. The first sig char encodes the top bits of signature byte 0.)
        int sigStart = valid.lastIndexOf('.') + 1;
        char orig = valid.charAt(sigStart);
        char flipped = (orig == 'A') ? 'B' : 'A';
        String tampered = valid.substring(0, sigStart) + flipped + valid.substring(sigStart + 1);

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
