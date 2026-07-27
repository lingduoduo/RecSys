package com.recsys.application.pagination;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecommendationPaginationConfigTest {

    @Test
    void readsValidatedConfiguration() {
        Map<String, String> env = Map.of(
                "RECOMMENDATION_CURSOR_SIGNING_KEY", "a".repeat(32),
                "RECOMMENDATION_CURSOR_PREVIOUS_KEY", "b".repeat(32),
                "RECOMMENDATION_CURSOR_MAX_AGE_SECONDS", "900",
                "RECOMMENDATION_CURSOR_ACCEPT_LEGACY", "true",
                "RECOMMENDATION_PAGINATION_MAX_CANDIDATES", "500");

        var config = RecommendationPaginationConfig.fromEnvironment(env::get);

        assertEquals("a".repeat(32), config.activeSigningKey());
        assertEquals("b".repeat(32), config.previousVerificationKey());
        assertEquals(Duration.ofMinutes(15), config.maxAge());
        assertTrue(config.acceptLegacy());
        assertEquals(500, config.maxCandidates());
    }

    @Test
    void defaultsOptionalEnvironmentValues() {
        var config = RecommendationPaginationConfig.fromEnvironment(
                name -> name.equals("RECOMMENDATION_CURSOR_SIGNING_KEY") ? "a".repeat(32) : null);

        assertEquals(Duration.ofSeconds(900), config.maxAge());
        assertTrue(config.acceptLegacy());
        assertEquals(500, config.maxCandidates());
    }

    @Test
    void rejectsMissingAndShortActiveSigningKeys() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> RecommendationPaginationConfig.fromEnvironment(name -> null));
        assertTrue(missing.getMessage().contains("RECOMMENDATION_CURSOR_SIGNING_KEY"));

        IllegalArgumentException shortKey = assertThrows(IllegalArgumentException.class,
                () -> RecommendationPaginationConfig.fromEnvironment(
                        name -> name.equals("RECOMMENDATION_CURSOR_SIGNING_KEY") ? "short" : null));
        assertTrue(shortKey.getMessage().contains("32 UTF-8 bytes"));
    }

    @Test
    void treatsBlankPreviousKeyAsAbsent() {
        var config = RecommendationPaginationConfig.fromEnvironment(name ->
                name.equals("RECOMMENDATION_CURSOR_SIGNING_KEY") ? "a".repeat(32)
                        : name.equals("RECOMMENDATION_CURSOR_PREVIOUS_KEY") ? "  " : null);

        assertNull(config.previousVerificationKey());
    }

    @Test
    void rejectsShortPreviousVerificationKey() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> RecommendationPaginationConfig.fromEnvironment(name ->
                        name.equals("RECOMMENDATION_CURSOR_SIGNING_KEY") ? "a".repeat(32)
                                : name.equals("RECOMMENDATION_CURSOR_PREVIOUS_KEY") ? "short" : null));

        assertTrue(exception.getMessage().contains("RECOMMENDATION_CURSOR_PREVIOUS_KEY"));
        assertTrue(exception.getMessage().contains("32 UTF-8 bytes"));
    }

    @Test
    void rejectsMaximumAgeOutsideAllowedRange() {
        assertEquals("RECOMMENDATION_CURSOR_MAX_AGE_SECONDS must be between 1 and 86400",
                assertThrows(IllegalArgumentException.class,
                        () -> configWith("RECOMMENDATION_CURSOR_MAX_AGE_SECONDS", "0")).getMessage());
        assertEquals("RECOMMENDATION_CURSOR_MAX_AGE_SECONDS must be between 1 and 86400",
                assertThrows(IllegalArgumentException.class,
                        () -> configWith("RECOMMENDATION_CURSOR_MAX_AGE_SECONDS", "86401")).getMessage());
    }

    @Test
    void acceptsMaximumAgeBoundaryValues() {
        assertEquals(Duration.ofSeconds(1),
                configWith("RECOMMENDATION_CURSOR_MAX_AGE_SECONDS", "1").maxAge());
        assertEquals(Duration.ofSeconds(86_400),
                configWith("RECOMMENDATION_CURSOR_MAX_AGE_SECONDS", "86400").maxAge());
    }

    @Test
    void rejectsMaximumCandidatesOutsideAllowedRange() {
        assertEquals("RECOMMENDATION_PAGINATION_MAX_CANDIDATES must be between 101 and 10000",
                assertThrows(IllegalArgumentException.class,
                        () -> configWith("RECOMMENDATION_PAGINATION_MAX_CANDIDATES", "100")).getMessage());
        assertEquals("RECOMMENDATION_PAGINATION_MAX_CANDIDATES must be between 101 and 10000",
                assertThrows(IllegalArgumentException.class,
                        () -> configWith("RECOMMENDATION_PAGINATION_MAX_CANDIDATES", "10001")).getMessage());
    }

    @Test
    void acceptsMaximumCandidatesBoundaryValues() {
        assertEquals(101,
                configWith("RECOMMENDATION_PAGINATION_MAX_CANDIDATES", "101").maxCandidates());
        assertEquals(10_000,
                configWith("RECOMMENDATION_PAGINATION_MAX_CANDIDATES", "10000").maxCandidates());
    }

    @Test
    void parsesOnlyCaseInsensitiveTrueAndFalseForAcceptLegacy() {
        assertFalse(configWith("RECOMMENDATION_CURSOR_ACCEPT_LEGACY", "FALSE").acceptLegacy());
        assertThrows(IllegalArgumentException.class,
                () -> configWith("RECOMMENDATION_CURSOR_ACCEPT_LEGACY", "yes"));
    }

    @Test
    void validatesComponentsWhenConstructedDirectly() {
        assertThrows(IllegalArgumentException.class,
                () -> new RecommendationPaginationConfig("short", null, Duration.ofSeconds(900), true, 500));
        assertThrows(IllegalArgumentException.class,
                () -> new RecommendationPaginationConfig("a".repeat(32), "short", Duration.ofSeconds(900), true, 500));
        assertThrows(NullPointerException.class,
                () -> new RecommendationPaginationConfig("a".repeat(32), null, null, true, 500));
        assertThrows(IllegalArgumentException.class,
                () -> new RecommendationPaginationConfig("a".repeat(32), null, Duration.ZERO, true, 500));
        assertThrows(IllegalArgumentException.class,
                () -> new RecommendationPaginationConfig("a".repeat(32), null, Duration.ofSeconds(900), true, 100));
    }

    @Test
    void acceptsMultibyteKeyWithAtLeastThirtyTwoUtf8Bytes() {
        String multibyteKey = "é".repeat(16);

        var config = RecommendationPaginationConfig.fromEnvironment(name ->
                name.equals("RECOMMENDATION_CURSOR_SIGNING_KEY") ? multibyteKey : null);

        assertEquals(multibyteKey, config.activeSigningKey());
    }

    private static RecommendationPaginationConfig configWith(String name, String value) {
        return RecommendationPaginationConfig.fromEnvironment(variable -> {
            if (variable.equals("RECOMMENDATION_CURSOR_SIGNING_KEY")) return "a".repeat(32);
            return variable.equals(name) ? value : null;
        });
    }
}
