package com.recsys.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern URL_CREDENTIAL_PROPERTY = Pattern.compile(
            "(?i)([?&;](?:user|password)=)[^&;]*");
    private static final Pattern URL_USER_INFO = Pattern.compile(
            "(?i)(jdbc:mysql://)[^/?;]*@(?=[^/?;]+)");
    private static final Pattern URL_SSL_MODE = Pattern.compile("(?i)[?&;]sslMode=([^&;]*)");
    private static final Pattern URL_USE_SSL = Pattern.compile("(?i)[?&;]useSSL=");
    private static final Pattern URL_HOST = Pattern.compile("(?i)jdbc:mysql://([^/?;]+)");

    public MySqlConnectionSettings {
        url = normalizeUrl(url);
        username = username == null || username.isBlank() ? "recsys" : username.trim();
        password = password == null ? "" : password;
        cursorSigningKey = cursorSigningKey == null ? "" : cursorSigningKey;
        validateRange("MYSQL_QUERY_TIMEOUT_SECONDS", queryTimeoutSeconds, 1, 30);
        validateRange("MYSQL_READ_MAX_ATTEMPTS", maxReadAttempts, 1, 2);
        validateRange("MYSQL_READ_RETRY_BACKOFF_MS", retryBackoffMillis, 0, 1000);
        if (enabled && password.isEmpty()) {
            throw new IllegalArgumentException("MYSQL_PASSWORD is required when MySQL is enabled");
        }
        if (enabled && cursorSigningKey.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException(
                    "MYSQL_CURSOR_SIGNING_KEY must contain at least 32 UTF-8 bytes when MySQL is enabled");
        }
        if (enabled) {
            requireVerifiedTransport(url);
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
        boolean enabled = Boolean.parseBoolean(env.getOrDefault("MYSQL_ENABLED", "false"));
        if (enabled && (env.get("MYSQL_URL") == null || env.get("MYSQL_URL").isBlank())) {
            throw new IllegalArgumentException("MYSQL_URL is required when MySQL is enabled");
        }
        if (enabled && (env.get("MYSQL_USER") == null || env.get("MYSQL_USER").isBlank())) {
            throw new IllegalArgumentException("MYSQL_USER is required when MySQL is enabled");
        }
        return new MySqlConnectionSettings(
                enabled,
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
                + ", url='" + redactUrlCredentials(url) + '\''
                + ", username='" + username + '\''
                + ", password='***', cursorSigningKey='***'}";
    }

    @Override
    public String toString() {
        return safeDescription();
    }

    /**
     * Refuses a connection that would not verify the server it talks to.
     *
     * <p>Connector/J 8 defaults to {@code sslMode=PREFERRED}: it negotiates TLS when the server
     * offers it, verifies no certificate, and falls back to plaintext in silence. From here a
     * plaintext connection is indistinguishable from an encrypted one, which is why this refuses
     * rather than warns — the same reasoning as {@code LettuceClientFactory.requireAuthentication}.
     *
     * <p>{@code VERIFY_IDENTITY} rather than {@code REQUIRED} because REQUIRED encrypts without
     * verifying, which stops silent plaintext but not an active man-in-the-middle. Against RDS it
     * costs no extra provisioning: Amazon's CAs are already in the JVM truststore.
     *
     * <p>Loopback hosts are exempt in full, including the {@code useSSL} rejection — a loopback
     * connection has no network segment to intercept, and {@code application.yml}'s local default
     * carries {@code useSSL=false}. This is deliberately a host test rather than an opt-out flag:
     * the host in Kubernetes is {@code mysql} or an RDS endpoint, so no manifest can reach it.
     */
    private static void requireVerifiedTransport(String url) {
        if (isLoopback(url)) {
            return;
        }
        if (URL_USE_SSL.matcher(url).find()) {
            throw new IllegalArgumentException(
                    "MYSQL_URL uses the deprecated useSSL property; MySQL Connector/J 8 lets "
                            + "sslMode override it silently. Remove useSSL and set "
                            + "sslMode=VERIFY_IDENTITY.");
        }
        Matcher modes = URL_SSL_MODE.matcher(url);
        boolean sawMode = false;
        while (modes.find()) {
            sawMode = true;
            if (!"VERIFY_IDENTITY".equalsIgnoreCase(modes.group(1).trim())) {
                throw verifyIdentityRequired();
            }
        }
        if (!sawMode) {
            throw verifyIdentityRequired();
        }
    }

    private static IllegalArgumentException verifyIdentityRequired() {
        return new IllegalArgumentException(
                "MYSQL_URL must set sslMode=VERIFY_IDENTITY when MySQL is enabled. Connector/J "
                        + "defaults to PREFERRED, which falls back to plaintext without error and "
                        + "verifies no certificate, so the connection carrying catalog rows, outbox "
                        + "events and saga state would be unprotected and look identical to a "
                        + "protected one.");
    }

    private static boolean isLoopback(String url) {
        Matcher host = URL_HOST.matcher(url);
        if (!host.find()) {
            return false;
        }
        String authority = host.group(1);
        int colon = authority.lastIndexOf(':');
        String hostOnly = colon > 0 && authority.indexOf(']') < colon
                ? authority.substring(0, colon)
                : authority;
        return hostOnly.equalsIgnoreCase("localhost")
                || hostOnly.equals("127.0.0.1")
                || hostOnly.equals("[::1]");
    }

    private static String redactUrlCredentials(String url) {
        String withoutUserInfo = URL_USER_INFO.matcher(url).replaceFirst("$1***@");
        return URL_CREDENTIAL_PROPERTY.matcher(withoutUserInfo).replaceAll("$1***");
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
