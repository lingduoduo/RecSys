package com.recsys.application.pagination;

import com.recsys.domain.recommendation.RecommendationQuery;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Canonically identifies the recommendation inputs that affect cursor eligibility. */
public final class RecommendationQueryFingerprint {
    private static final int FORMAT_VERSION = 1;

    private RecommendationQueryFingerprint() {
    }

    public static String of(RecommendationQuery query) {
        Objects.requireNonNull(query, "query");
        byte[] canonical = canonicalBytes(query);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            return toLowercaseHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static byte[] canonicalBytes(RecommendationQuery query) {
        byte[] user = utf8Bytes(query.userId(), "userId");
        List<byte[]> exclusions = new ArrayList<>(query.excludedItemIds().size());
        for (String excludedItemId : query.excludedItemIds()) {
            exclusions.add(utf8Bytes(excludedItemId, "excludedItemId"));
        }
        exclusions.sort(Comparator.comparing(RecommendationQueryFingerprint::byteArrayKey));

        long size = Integer.BYTES * 3L + user.length;
        for (byte[] exclusion : exclusions) {
            size += Integer.BYTES + exclusion.length;
        }
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("query fingerprint input is too large");
        }

        ByteBuffer bytes = ByteBuffer.allocate((int) size);
        bytes.putInt(FORMAT_VERSION);
        putLengthPrefixed(bytes, user);
        bytes.putInt(exclusions.size());
        for (byte[] exclusion : exclusions) {
            putLengthPrefixed(bytes, exclusion);
        }
        return bytes.array();
    }

    private static void putLengthPrefixed(ByteBuffer target, byte[] value) {
        target.putInt(value.length);
        target.put(value);
    }

    static byte[] utf8Bytes(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(fieldName + " must be well-formed UTF-8", e);
        }
    }

    private static String byteArrayKey(byte[] value) {
        return new String(value, StandardCharsets.ISO_8859_1);
    }

    private static String toLowercaseHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0xf, 16));
            result.append(Character.forDigit(value & 0xf, 16));
        }
        return result.toString();
    }
}
