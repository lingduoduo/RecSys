package com.recsys.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal MySQL settings holder.
 *
 * MySQL is opt-in: callers should check {@link #enabled()} before opening a connection.
 * Values are read from environment-style maps so this class stays independent of Spring.
 */
public record MySqlConnectionSettings(
        boolean enabled,
        String url,
        String username,
        String password,
        int queryTimeoutSeconds,
        int maxReadAttempts,
        long retryBackoffMillis,
        String cursorSigningKey
) {
    private static final String DEFAULT_URL =
            "jdbc:mysql://localhost:3306/recsys?useSSL=false&serverTimezone=UTC"
                    + "&connectTimeout=1000&socketTimeout=2000";

    public MySqlConnectionSettings {
        url = normalizeUrl(url);
        username = username == null || username.isBlank() ? "recsys" : username.trim();
        password = password == null ? "" : password;
        cursorSigningKey = cursorSigningKey == null ? "" : cursorSigningKey;
        validateRange("MYSQL_QUERY_TIMEOUT_SECONDS", queryTimeoutSeconds, 1, 30);
        validateRange("MYSQL_READ_MAX_ATTEMPTS", maxReadAttempts, 1, 2);
        validateRange("MYSQL_READ_RETRY_BACKOFF_MS", retryBackoffMillis, 0, 1000);
        if (enabled && cursorSigningKey.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException(
                    "MYSQL_CURSOR_SIGNING_KEY must contain at least 32 UTF-8 bytes when MySQL is enabled");
        }
    }

    public static MySqlConnectionSettings disabled() {
        return new MySqlConnectionSettings(false, DEFAULT_URL, "recsys", "", 2, 2, 50, "");
    }

    public static MySqlConnectionSettings fromEnv() {
        return fromEnv(System.getenv());
    }

    static MySqlConnectionSettings fromEnv(Map<String, String> env) {
        Objects.requireNonNull(env, "env");
        return new MySqlConnectionSettings(
                Boolean.parseBoolean(env.getOrDefault("MYSQL_ENABLED", "false")),
                env.getOrDefault("MYSQL_URL", DEFAULT_URL),
                env.getOrDefault("MYSQL_USER", "recsys"),
                env.getOrDefault("MYSQL_PASSWORD", ""),
                parseInt(env, "MYSQL_QUERY_TIMEOUT_SECONDS", 2),
                parseInt(env, "MYSQL_READ_MAX_ATTEMPTS", 2),
                parseLong(env, "MYSQL_READ_RETRY_BACKOFF_MS", 50),
                env.getOrDefault("MYSQL_CURSOR_SIGNING_KEY", "")
        );
    }

    public String safeDescription() {
        return "MySqlConnectionSettings{enabled=" + enabled
                + ", url='" + url + '\''
                + ", username='" + username + '\''
                + ", password='***'}";
    }

    private static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return DEFAULT_URL;
        }
        String trimmed = url.trim();
        if (!trimmed.startsWith("jdbc:mysql://")) {
            throw new IllegalArgumentException("MYSQL_URL must start with jdbc:mysql://");
        }
        return trimmed;
    }

    private static int parseInt(Map<String, String> env, String name, int defaultValue) {
        try {
            return Integer.parseInt(env.getOrDefault(name, Integer.toString(defaultValue)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer", e);
        }
    }

    private static long parseLong(Map<String, String> env, String name, long defaultValue) {
        try {
            return Long.parseLong(env.getOrDefault(name, Long.toString(defaultValue)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer", e);
        }
    }

    private static void validateRange(String name, long value, long minimum, long maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum + " inclusive");
        }
    }
}
