package com.recsys.application.pagination;

import com.recsys.domain.recommendation.RecommendationQuery;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecommendationCursorCodecTest {
    private static final String ACTIVE_KEY = "a".repeat(32);
    private static final String PREVIOUS_KEY = "b".repeat(32);
    private static final Instant ISSUED_AT = Instant.parse("2026-07-27T12:00:00Z");

    private final RecommendationPaginationConfig config = config(ACTIVE_KEY, null, true);
    private final RecommendationCursorCodec codec = codecAt(ISSUED_AT);
    private final RecommendationQuery query = query("u1", Set.of("seen"));

    @Test
    void signedCursorRoundTripsPosition() {
        var position = new RankedListCursor(0.75, "movie:42");

        assertThat(codec.decode(query, codec.encode(query, position)))
                .isEqualTo(new DecodedCursor(position, false, false));
    }

    @Test
    void signedCursorIsBoundToUserAndExclusionsButNotLimit() {
        String token = codec.encode(query, new RankedListCursor(0.75, "42"));

        assertThat(codec.decode(new RecommendationQuery("u1", 50, Set.of("seen"), token), token).position())
                .isEqualTo(new RankedListCursor(0.75, "42"));
        assertInvalid(() -> codec.decode(query("u2", Set.of("seen")), token),
                CursorFailureReason.QUERY_MISMATCH);
        assertInvalid(() -> codec.decode(query("u1", Set.of("other")), token),
                CursorFailureReason.QUERY_MISMATCH);
    }

    @Test
    void rejectsTamperedSignedCursor() {
        String token = codec.encode(query, new RankedListCursor(0.75, "42"));
        int signatureStart = token.indexOf('.') + 1;
        char replacement = token.charAt(signatureStart) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, signatureStart) + replacement
                + token.substring(signatureStart + 1);

        assertInvalid(() -> codec.decode(query, tampered), CursorFailureReason.SIGNATURE);
    }

    @Test
    void expiresAtIssuedAtPlusConfiguredMaximumAge() {
        String token = codec.encode(query, new RankedListCursor(0.75, "42"));
        RecommendationCursorCodec expiringCodec = new RecommendationCursorCodec(config,
                Clock.fixed(ISSUED_AT.plusSeconds(60), ZoneOffset.UTC));

        assertInvalid(() -> expiringCodec.decode(query, token), CursorFailureReason.EXPIRED);
    }

    @Test
    void verifiesActiveAndPreviousRotationKeys() {
        String activeToken = codec.encode(query, new RankedListCursor(0.75, "active"));
        String previousToken = new RecommendationCursorCodec(config(PREVIOUS_KEY, null, true),
                Clock.fixed(ISSUED_AT, ZoneOffset.UTC))
                .encode(query, new RankedListCursor(0.5, "previous"));
        RecommendationCursorCodec rotatingCodec = new RecommendationCursorCodec(
                config(ACTIVE_KEY, PREVIOUS_KEY, true), Clock.fixed(ISSUED_AT, ZoneOffset.UTC));

        assertThat(rotatingCodec.decode(query, activeToken).previousKey()).isFalse();
        assertThat(rotatingCodec.decode(query, previousToken))
                .isEqualTo(new DecodedCursor(new RankedListCursor(0.5, "previous"), false, true));
    }

    @Test
    void rejectsOversizedAndMalformedTokensBeforeUse() {
        assertInvalid(() -> codec.decode(query, "a".repeat(2_049)), CursorFailureReason.MALFORMED);
        assertInvalid(() -> codec.decode(query, "%%.%%"), CursorFailureReason.MALFORMED);
    }

    @Test
    void rejectsMalformedUtf8AndUnsupportedVersions() {
        String malformedUtf8 = sign(new byte[] {(byte) 0xc3, (byte) 0x28}, ACTIVE_KEY);
        assertInvalid(() -> codec.decode(query, malformedUtf8), CursorFailureReason.MALFORMED);

        String unsupported = signedPayload("4\n1785240000\ndTE\nignored\n0x1.0p0\nNDI", ACTIVE_KEY);
        assertInvalid(() -> codec.decode(query, unsupported), CursorFailureReason.UNSUPPORTED);
    }

    @Test
    void rejectsBlankAndOversizedItemIdsAndNonFiniteScores() {
        String fingerprint = RecommendationQueryFingerprint.of(query);
        assertInvalid(() -> codec.decode(query, signedPayload("3\n1785240000\ndTE\n" + fingerprint
                + "\n0x1.0p0\n", ACTIVE_KEY)), CursorFailureReason.MALFORMED);
        assertInvalid(() -> codec.decode(query, signedPayload("3\n1785240000\ndTE\n" + fingerprint
                + "\n0x1.0p0\n" + b64("x".repeat(513)), ACTIVE_KEY)), CursorFailureReason.MALFORMED);
        for (String score : Set.of("NaN", "Infinity", "-Infinity")) {
            assertInvalid(() -> codec.decode(query, signedPayload("3\n1785240000\ndTE\n" + fingerprint
                    + "\n" + score + "\nNDI", ACTIVE_KEY)), CursorFailureReason.MALFORMED);
        }
    }

    @Test
    void rankedListCursorRejectsInvalidNonStartPositions() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RankedListCursor(Double.NaN, "42"));
        assertThatIllegalArgumentException().isThrownBy(() -> new RankedListCursor(Double.POSITIVE_INFINITY, "42"));
        assertThatIllegalArgumentException().isThrownBy(() -> new RankedListCursor(0.75, " "));
        assertThatIllegalArgumentException().isThrownBy(() -> new RankedListCursor(0.75, "x".repeat(513)));
        assertThat(RankedListCursor.START.isStart()).isTrue();
    }

    @Test
    void legacyCursorIsAcceptedOnlyWhenEnabledAndMarkedForUpgrade() {
        String legacy = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("v2:0.75:42".getBytes(StandardCharsets.UTF_8));

        assertThat(codec.decode(query, legacy))
                .isEqualTo(new DecodedCursor(new RankedListCursor(0.75, "42"), true, false));
        RecommendationCursorCodec legacyDisabled = new RecommendationCursorCodec(
                config(ACTIVE_KEY, null, false), Clock.fixed(ISSUED_AT, ZoneOffset.UTC));
        assertInvalid(() -> legacyDisabled.decode(query, legacy), CursorFailureReason.LEGACY_DISABLED);
        String nonFiniteLegacy = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("v2:NaN:42".getBytes(StandardCharsets.UTF_8));
        assertInvalid(() -> codec.decode(query, nonFiniteLegacy), CursorFailureReason.MALFORMED);
    }

    private RecommendationCursorCodec codecAt(Instant now) {
        return new RecommendationCursorCodec(config, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static RecommendationPaginationConfig config(String active, String previous, boolean acceptLegacy) {
        return new RecommendationPaginationConfig(active, previous, Duration.ofSeconds(60), acceptLegacy, 500);
    }

    private static RecommendationQuery query(String userId, Set<String> exclusions) {
        return new RecommendationQuery(userId, 10, exclusions, null);
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
                                      CursorFailureReason reason) {
        assertThatThrownBy(action)
                .isInstanceOf(RecommendationCursorCodec.InvalidCursorException.class)
                .hasMessage("Invalid recommendation cursor")
                .satisfies(error -> assertThat(((RecommendationCursorCodec.InvalidCursorException) error).reason())
                        .isEqualTo(reason));
    }

    private static String signedPayload(String payload, String key) {
        return sign(payload.getBytes(StandardCharsets.UTF_8), key);
    }

    private static String sign(byte[] payload, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(payload) + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String b64(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
