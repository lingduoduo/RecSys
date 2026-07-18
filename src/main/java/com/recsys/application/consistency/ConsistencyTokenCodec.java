package com.recsys.application.consistency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Versioned, subject-bound proof that an online event was durably accepted. */
public final class ConsistencyTokenCodec {
    public static final String HEADER_NAME = "X-Consistency-Token";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final Duration LIFETIME = Duration.ofHours(24);
    private static final String HEADER = "{\"alg\":\"HS256\",\"typ\":\"ECT\",\"v\":1}";
    private static final int MAX_TOKEN_LENGTH = 2_048;
    private final byte[] secret;
    private final Clock clock;

    public ConsistencyTokenCodec(String secret, Clock clock) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("Consistency token secret must contain at least 32 UTF-8 bytes");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8).clone();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ConsistencyTokenCodec(String secret) {
        this(secret, Clock.systemUTC());
    }

    public String encode(ConsistencyToken token) {
        Objects.requireNonNull(token, "token");
        if (!Duration.between(token.issuedAt(), token.expiresAt()).equals(LIFETIME)) {
            throw new IllegalArgumentException("Consistency token lifetime must be exactly 24 hours");
        }
        try {
            String header = encode(HEADER.getBytes(StandardCharsets.UTF_8));
            byte[] payloadBytes = JSON.writeValueAsBytes(new Payload(token.eventId().toString(), token.userId(),
                    token.issuedAt().toEpochMilli(), token.expiresAt().toEpochMilli()));
            String payload = encode(payloadBytes);
            String signingInput = header + "." + payload;
            return signingInput + "." + encode(sign(signingInput.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encode consistency token", e);
        }
    }

    public ConsistencyToken decodeAndVerify(String encoded) {
        try {
            if (encoded == null || encoded.isBlank() || encoded.length() > MAX_TOKEN_LENGTH) throw invalid();
            String[] parts = encoded.split("\\.", -1);
            if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) throw invalid();
            byte[] supplied = DECODER.decode(parts[2]);
            byte[] expected = sign((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!MessageDigest.isEqual(expected, supplied)) throw invalid();
            JsonNode header = JSON.readTree(DECODER.decode(parts[0]));
            if (!"HS256".equals(header.path("alg").asText()) || !"ECT".equals(header.path("typ").asText())
                    || header.path("v").asInt() != 1) throw invalid();
            JsonNode payload = JSON.readTree(DECODER.decode(parts[1]));
            if (!payload.hasNonNull("eventId") || !payload.has("userId") || !payload.has("issuedAt")
                    || !payload.has("expiresAt")) throw invalid();
            if (!payload.get("userId").canConvertToInt() || !payload.get("issuedAt").canConvertToLong()
                    || !payload.get("expiresAt").canConvertToLong()) throw invalid();
            ConsistencyToken token = new ConsistencyToken(UUID.fromString(payload.get("eventId").asText()),
                    payload.get("userId").intValue(), Instant.ofEpochMilli(payload.get("issuedAt").longValue()),
                    Instant.ofEpochMilli(payload.get("expiresAt").longValue()));
            if (!Duration.between(token.issuedAt(), token.expiresAt()).equals(LIFETIME)) throw invalid();
            if (token.issuedAt().isAfter(clock.instant())) throw invalid();
            if (!clock.instant().isBefore(token.expiresAt())) throw new ExpiredConsistencyTokenException();
            return token;
        } catch (InvalidConsistencyTokenException e) {
            throw e;
        } catch (Exception e) {
            throw invalid();
        }
    }

    private byte[] sign(byte[] bytes) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(bytes);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static String encode(byte[] bytes) { return ENCODER.encodeToString(bytes); }
    private static InvalidConsistencyTokenException invalid() { return new InvalidConsistencyTokenException(); }
    private record Payload(String eventId, int userId, long issuedAt, long expiresAt) {}

}
