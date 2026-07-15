package com.recsys.application.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class CatalogCursorCodecTest {
    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private final CatalogCursorCodec codec = new CatalogCursorCodec(KEY);

    @Test
    void filteredCursorRoundTripsWithNormalizedExactGenreAndDecimal() {
        var position = new CatalogCursorCodec.Position("  Sci-Fi  ", new BigDecimal("98.1200"), 42L);

        String token = codec.encode(position);

        assertEquals(new CatalogCursorCodec.Position("Sci-Fi", new BigDecimal("98.1200"), 42L),
                codec.decode(token, " Sci-Fi "));
        assertEquals(token, codec.encode(codec.decode(token, "Sci-Fi")));
    }

    @Test
    void blankGenreRoundTripsAsExplicitNoFilter() {
        var position = new CatalogCursorCodec.Position("  ", new BigDecimal("0.000"), 7L);

        String token = codec.encode(position);

        assertEquals(new CatalogCursorCodec.Position(null, new BigDecimal("0.000"), 7L),
                codec.decode(token, null));
        assertEquals(codec.decode(token, ""), codec.decode(token, "   "));
    }

    @Test
    void noFilterCannotBeReplayedAsExactGenre() {
        String token = codec.encode(new CatalogCursorCodec.Position(null, BigDecimal.ONE, 1L));

        assertGenericInvalid(() -> codec.decode(token, "__NO_FILTER__"));
    }

    @Test
    void tamperingIsRejected() {
        String token = codec.encode(new CatalogCursorCodec.Position("Drama", BigDecimal.TEN, 9L));
        int index = token.length() / 3;
        char replacement = token.charAt(index) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, index) + replacement + token.substring(index + 1);

        assertGenericInvalid(() -> codec.decode(tampered, "Drama"));
    }

    @Test
    void differentExactFilterIsRejected() {
        String token = codec.encode(new CatalogCursorCodec.Position("Drama", BigDecimal.TEN, 9L));

        assertGenericInvalid(() -> codec.decode(token, "drama"));
    }

    @Test
    void unsupportedVersionIsRejectedWithGenericError() {
        assertGenericInvalid(() -> codec.decode(signed("2\n0\n\n1.0\n9"), null));
    }

    @Test
    void malformedBase64IsRejectedWithGenericError() {
        assertGenericInvalid(() -> codec.decode("not+base64.signature!", null));
    }

    @Test
    void malformedNumbersAreRejectedWithGenericError() {
        assertGenericInvalid(() -> codec.decode(signed("1\n0\n\nNaN\n9"), null));
        assertGenericInvalid(() -> codec.decode(signed("1\n0\n\n1.0\n9x"), null));
    }

    @Test
    void oversizedTokenIsRejectedBeforeDecode() {
        assertGenericInvalid(() -> codec.decode("A".repeat(2_049), null));
    }

    @Test
    void constructorRequiresAtLeastThirtyTwoUtf8Bytes() {
        assertThrows(IllegalArgumentException.class, () -> new CatalogCursorCodec("short"));
        assertThrows(IllegalArgumentException.class, () -> new CatalogCursorCodec(null));
    }

    private void assertGenericInvalid(Runnable decode) {
        var error = assertThrows(CatalogCursorCodec.InvalidCursorException.class, decode::run);
        assertEquals("Invalid catalog cursor", error.getMessage());
    }

    private String signed(String payload) {
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes) + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(payloadBytes));
    }

    private byte[] hmac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new AssertionError(e);
        }
    }
}
