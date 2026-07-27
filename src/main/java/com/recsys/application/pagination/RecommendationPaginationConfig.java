package com.recsys.application.pagination;

import com.recsys.config.EnvVars;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

public record RecommendationPaginationConfig(
        String activeSigningKey,
        String previousVerificationKey,
        Duration maxAge,
        boolean acceptLegacy,
        int maxCandidates
) {
    public RecommendationPaginationConfig {
        activeSigningKey = requireKey(activeSigningKey, "RECOMMENDATION_CURSOR_SIGNING_KEY");
        previousVerificationKey = optionalKey(previousVerificationKey,
                "RECOMMENDATION_CURSOR_PREVIOUS_KEY");
        maxAge = Objects.requireNonNull(maxAge, "maxAge must not be null");
        if (maxAge.getSeconds() < 1 || maxAge.getSeconds() > 86_400) {
            throw new IllegalArgumentException(
                    "RECOMMENDATION_CURSOR_MAX_AGE_SECONDS must be between 1 and 86400");
        }
        if (maxCandidates < 101 || maxCandidates > 10_000) {
            throw new IllegalArgumentException(
                    "RECOMMENDATION_PAGINATION_MAX_CANDIDATES must be between 101 and 10000");
        }
    }

    public static RecommendationPaginationConfig fromEnvironment() {
        return fromEnvironment(System::getenv);
    }

    static RecommendationPaginationConfig fromEnvironment(EnvVars.EnvReader env) {
        long maxAgeSeconds = EnvVars.readLong(env,
                "RECOMMENDATION_CURSOR_MAX_AGE_SECONDS", 900);
        int maxCandidates = EnvVars.readInt(env,
                "RECOMMENDATION_PAGINATION_MAX_CANDIDATES", 500);
        boolean acceptLegacy = strictBoolean(env,
                "RECOMMENDATION_CURSOR_ACCEPT_LEGACY", true);
        return new RecommendationPaginationConfig(
                env.get("RECOMMENDATION_CURSOR_SIGNING_KEY"),
                env.get("RECOMMENDATION_CURSOR_PREVIOUS_KEY"),
                Duration.ofSeconds(maxAgeSeconds), acceptLegacy, maxCandidates);
    }

    private static String requireKey(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required and must contain at least 32 UTF-8 bytes");
        }
        return validateKey(value, name);
    }

    private static String optionalKey(String value, String name) {
        return value == null || value.isBlank() ? null : validateKey(value, name);
    }

    private static String validateKey(String value, String name) {
        if (value.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException(name + " must contain at least 32 UTF-8 bytes");
        }
        return value;
    }

    private static boolean strictBoolean(EnvVars.EnvReader env, String name, boolean defaultValue) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(name + " must be true or false");
        };
    }
}
