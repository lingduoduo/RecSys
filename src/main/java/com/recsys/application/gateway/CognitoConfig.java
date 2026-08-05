package com.recsys.application.gateway;

import com.recsys.config.EnvVars;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Cognito verification parameters. Built from the GATEWAY_COGNITO_* environment.
 * A blank issuer means Cognito auth is not configured ({@link #isConfigured()} is false).
 */
record CognitoConfig(String issuer, String audience, Set<String> tokenUses, String userIdClaim) {

    /** Cognito's own subject claim. The default, so a pool that mints app userIds as `sub` needs no config. */
    static final String DEFAULT_USER_ID_CLAIM = "sub";

    /** Callers that predate the user-scope work; `sub` is the claim they implicitly meant. */
    CognitoConfig(String issuer, String audience, Set<String> tokenUses) {
        this(issuer, audience, tokenUses, DEFAULT_USER_ID_CLAIM);
    }

    CognitoConfig {
        userIdClaim = userIdClaim == null || userIdClaim.isBlank()
                ? DEFAULT_USER_ID_CLAIM
                : userIdClaim.trim();
    }

    static CognitoConfig fromEnvironment(EnvVars.EnvReader env) {
        String issuer = stripTrailingSlash(read(env, "GATEWAY_COGNITO_ISSUER", ""));
        String audience = read(env, "GATEWAY_COGNITO_AUDIENCE", "");
        if (!issuer.isBlank() && audience.isBlank()) {
            throw new IllegalStateException(
                    "GATEWAY_COGNITO_AUDIENCE is required when GATEWAY_COGNITO_ISSUER is set");
        }
        String tokenUseCsv = read(env, "GATEWAY_COGNITO_TOKEN_USE", "access");
        Set<String> tokenUses = Stream.of(tokenUseCsv.split(","))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        String userIdClaim = read(env, "GATEWAY_COGNITO_USER_ID_CLAIM", DEFAULT_USER_ID_CLAIM);
        return new CognitoConfig(issuer, audience, tokenUses, userIdClaim);
    }

    boolean isConfigured() {
        return issuer != null && !issuer.isBlank();
    }

    private static String read(EnvVars.EnvReader env, String name, String defaultValue) {
        String value = env.get(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String stripped = value.trim();
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }
}
