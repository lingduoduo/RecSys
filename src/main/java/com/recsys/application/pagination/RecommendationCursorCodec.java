package com.recsys.application.pagination;

import com.recsys.domain.recommendation.RecommendationQuery;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/** Encodes and validates signed, query-bound recommendation cursor positions. */
public final class RecommendationCursorCodec {
    private static final int MAX_TOKEN_LENGTH = 2_048;
    private static final String VERSION = "3";
    private static final String LEGACY_PREFIX = "v2:";
    private static final String INVALID_MESSAGE = "Invalid recommendation cursor";
    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();

    private final RecommendationPaginationConfig config;
    private final Clock clock;
    private final byte[] activeKey;
    private final byte[] previousKey;

    public RecommendationCursorCodec(RecommendationPaginationConfig config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.activeKey = config.activeSigningKey().getBytes(StandardCharsets.UTF_8);
        this.previousKey = config.previousVerificationKey() == null ? null
                : config.previousVerificationKey().getBytes(StandardCharsets.UTF_8);
    }

    public String encode(RecommendationQuery query, RankedListCursor position) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(position, "position");
        if (position.isStart()) {
            throw new IllegalArgumentException("the START cursor is not encodable");
        }
        String payload = String.join("\n",
                VERSION,
                Long.toString(clock.instant().getEpochSecond()),
                encodeText(query.userId()),
                RecommendationQueryFingerprint.of(query),
                Double.toHexString(position.score()),
                encodeText(position.itemId()));
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        return BASE64_ENCODER.encodeToString(payloadBytes) + "."
                + BASE64_ENCODER.encodeToString(sign(payloadBytes, activeKey));
    }

    public DecodedCursor decode(RecommendationQuery query, String token) {
        Objects.requireNonNull(query, "query");
        try {
            if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
                throw invalid(CursorFailureReason.MALFORMED);
            }
            if (!token.contains(".")) {
                return decodeLegacy(token);
            }
            return decodeSigned(query, token);
        } catch (InvalidCursorException e) {
            throw e;
        } catch (RuntimeException | CharacterCodingException e) {
            throw invalid(CursorFailureReason.MALFORMED);
        }
    }

    private DecodedCursor decodeSigned(RecommendationQuery query, String token)
            throws CharacterCodingException {
        int separator = token.indexOf('.');
        if (separator <= 0 || separator != token.lastIndexOf('.') || separator == token.length() - 1) {
            throw invalid(CursorFailureReason.MALFORMED);
        }
        byte[] payloadBytes = BASE64_DECODER.decode(token.substring(0, separator));
        byte[] suppliedSignature = BASE64_DECODER.decode(token.substring(separator + 1));
        boolean active = MessageDigest.isEqual(sign(payloadBytes, activeKey), suppliedSignature);
        boolean previous = !active && previousKey != null
                && MessageDigest.isEqual(sign(payloadBytes, previousKey), suppliedSignature);
        if (!active && !previous) {
            throw invalid(CursorFailureReason.SIGNATURE);
        }

        String[] fields = decodeUtf8(payloadBytes).split("\\n", -1);
        if (fields.length != 6) {
            throw invalid(CursorFailureReason.MALFORMED);
        }
        if (!VERSION.equals(fields[0])) {
            throw invalid(CursorFailureReason.UNSUPPORTED);
        }
        long issuedAt = parseEpochSecond(fields[1]);
        String userId = decodeText(fields[2]);
        String fingerprint = fields[3];
        double score = parseFiniteScore(fields[4]);
        String itemId = decodeText(fields[5]);
        RankedListCursor position = new RankedListCursor(score, itemId);

        Instant expiry = Instant.ofEpochSecond(issuedAt).plus(config.maxAge());
        if (!clock.instant().isBefore(expiry)) {
            throw invalid(CursorFailureReason.EXPIRED);
        }
        if (!query.userId().equals(userId)
                || !RecommendationQueryFingerprint.of(query).equals(fingerprint)) {
            throw invalid(CursorFailureReason.QUERY_MISMATCH);
        }
        return new DecodedCursor(position, false, previous);
    }

    private DecodedCursor decodeLegacy(String token) throws CharacterCodingException {
        if (!config.acceptLegacy()) {
            throw invalid(CursorFailureReason.LEGACY_DISABLED);
        }
        String decoded = decodeUtf8(BASE64_DECODER.decode(token));
        if (!decoded.startsWith(LEGACY_PREFIX)) {
            throw invalid(CursorFailureReason.UNSUPPORTED);
        }
        String body = decoded.substring(LEGACY_PREFIX.length());
        int separator = body.indexOf(':');
        if (separator < 0) {
            throw invalid(CursorFailureReason.MALFORMED);
        }
        double score = parseFiniteScore(body.substring(0, separator));
        RankedListCursor position = new RankedListCursor(score, body.substring(separator + 1));
        return new DecodedCursor(position, true, false);
    }

    private static long parseEpochSecond(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw invalid(CursorFailureReason.MALFORMED);
        }
    }

    private static double parseFiniteScore(String value) {
        try {
            double score = Double.parseDouble(value);
            if (!Double.isFinite(score)) {
                throw invalid(CursorFailureReason.MALFORMED);
            }
            return score;
        } catch (NumberFormatException e) {
            throw invalid(CursorFailureReason.MALFORMED);
        }
    }

    private static String encodeText(String value) {
        return BASE64_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeText(String value) throws CharacterCodingException {
        return decodeUtf8(BASE64_DECODER.decode(value));
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static byte[] sign(byte[] payload, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private static InvalidCursorException invalid(CursorFailureReason reason) {
        return new InvalidCursorException(reason);
    }

    public static final class InvalidCursorException extends IllegalArgumentException {
        private final CursorFailureReason reason;

        InvalidCursorException(CursorFailureReason reason) {
            super(INVALID_MESSAGE);
            this.reason = reason;
        }

        CursorFailureReason reason() {
            return reason;
        }
    }
}

record DecodedCursor(RankedListCursor position, boolean legacy, boolean previousKey) {
}

enum CursorFailureReason {
    MALFORMED,
    SIGNATURE,
    EXPIRED,
    QUERY_MISMATCH,
    UNSUPPORTED,
    LEGACY_DISABLED
}
