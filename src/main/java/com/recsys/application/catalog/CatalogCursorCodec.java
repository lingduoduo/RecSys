package com.recsys.application.catalog;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Encodes catalog keyset positions as versioned, filter-bound HMAC tokens. */
public final class CatalogCursorCodec {
    private static final int MAX_TOKEN_LENGTH = 2_048;
    private static final String VERSION = "1";
    private static final String INVALID_MESSAGE = "Invalid catalog cursor";
    private static final Pattern DECIMAL = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?");
    private static final Pattern INTEGER = Pattern.compile("-?(?:0|[1-9][0-9]*)");
    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();

    private final byte[] key;

    public CatalogCursorCodec(String key) {
        if (key == null) {
            throw new IllegalArgumentException("Cursor signing key is required");
        }
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalArgumentException("Cursor signing key must contain at least 32 UTF-8 bytes");
        }
        this.key = bytes.clone();
    }

    public String encode(Position position) {
        Objects.requireNonNull(position, "position");
        String genreMarker = position.genre() == null ? "0" : "1";
        String genre = position.genre() == null ? ""
                : BASE64_ENCODER.encodeToString(position.genre().getBytes(StandardCharsets.UTF_8));
        String payload = String.join("\n", VERSION, genreMarker, genre,
                position.popularityScore().toPlainString(), Long.toString(position.movieId()));
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        return BASE64_ENCODER.encodeToString(payloadBytes) + "."
                + BASE64_ENCODER.encodeToString(sign(payloadBytes));
    }

    public Position decode(String token, String expectedGenre) {
        try {
            // This guard deliberately precedes substring, split, and Base64 decoding allocations.
            if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
                throw invalid();
            }
            int separator = token.indexOf('.');
            if (separator <= 0 || separator != token.lastIndexOf('.') || separator == token.length() - 1) {
                throw invalid();
            }

            byte[] payloadBytes = BASE64_DECODER.decode(token.substring(0, separator));
            byte[] suppliedSignature = BASE64_DECODER.decode(token.substring(separator + 1));
            byte[] expectedSignature = sign(payloadBytes);
            if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)) {
                throw invalid();
            }

            String[] fields = decodeUtf8(payloadBytes).split("\\n", -1);
            if (fields.length != 5 || !VERSION.equals(fields[0])) {
                throw invalid();
            }
            String genre = decodeGenre(fields[1], fields[2]);
            if (!Objects.equals(genre, normalizeGenre(expectedGenre))) {
                throw invalid();
            }
            if (!DECIMAL.matcher(fields[3]).matches() || !INTEGER.matcher(fields[4]).matches()) {
                throw invalid();
            }
            return new Position(genre, new BigDecimal(fields[3]), Long.parseLong(fields[4]));
        } catch (InvalidCursorException e) {
            throw e;
        } catch (RuntimeException | CharacterCodingException e) {
            throw invalid();
        }
    }

    private String decodeGenre(String marker, String encodedGenre) throws CharacterCodingException {
        if ("0".equals(marker) && encodedGenre.isEmpty()) {
            return null;
        }
        if (!"1".equals(marker) || encodedGenre.isEmpty()) {
            throw invalid();
        }
        String genre = normalizeGenre(decodeUtf8(BASE64_DECODER.decode(encodedGenre)));
        if (genre == null) {
            throw invalid();
        }
        return genre;
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String normalizeGenre(String genre) {
        if (genre == null) {
            return null;
        }
        String normalized = genre.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static InvalidCursorException invalid() {
        return new InvalidCursorException();
    }

    public record Position(String genre, BigDecimal popularityScore, long movieId) {
        public Position {
            genre = normalizeGenre(genre);
            Objects.requireNonNull(popularityScore, "popularityScore");
        }
    }

    public static final class InvalidCursorException extends IllegalArgumentException {
        public InvalidCursorException() {
            super(INVALID_MESSAGE);
        }
    }
}
