package com.recsys.infrastructure.persistence;

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
        String password
) {
    private static final String DEFAULT_URL =
            "jdbc:mysql://localhost:3306/recsys?useSSL=false&serverTimezone=UTC"
                    + "&connectTimeout=1000&socketTimeout=2000";

    public MySqlConnectionSettings {
        url = normalizeUrl(url);
        username = username == null || username.isBlank() ? "recsys" : username.trim();
        password = password == null ? "" : password;
    }

    public static MySqlConnectionSettings disabled() {
        return new MySqlConnectionSettings(false, DEFAULT_URL, "recsys", "");
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
                env.getOrDefault("MYSQL_PASSWORD", "")
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
}
