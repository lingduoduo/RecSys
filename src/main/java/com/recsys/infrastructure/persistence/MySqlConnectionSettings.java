package com.recsys.infrastructure.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
    private static final Logger log = LoggerFactory.getLogger(MySqlConnectionSettings.class);
    private static final String DEFAULT_URL =
            "jdbc:mysql://localhost:3306/recsys?useSSL=false&serverTimezone=UTC"
                    + "&connectTimeout=1000&socketTimeout=2000";
    private static final Pattern URL_CREDENTIAL_PROPERTY = Pattern.compile(
            "(?i)([?&;](?:user|password)=)[^&;]*");
    private static final Pattern URL_USER_INFO = Pattern.compile(
            "(?i)(jdbc:mysql://)[^/?;]*@(?=[^/?;]+)");
    private static final String SSL_MODE_PROPERTY = "sslMode";
    private static final String REQUIRED_SSL_MODE = "VERIFY_IDENTITY";
    /**
     * Only ever causes a rejection, so both the {@code (?i)} and the {@code ;} over-match and fail
     * closed. Deliberately left looser than {@link #sslModeValues} for that reason.
     */
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
     * carries {@code useSSL=false}. Taking the exemption logs one INFO line, so an unverified
     * connection is never silent. This is deliberately a host test rather than an opt-out flag:
     * the host in Kubernetes is {@code mysql} or an RDS endpoint, so no manifest can reach it.
     */
    private static void requireVerifiedTransport(String url) {
        if (isLoopback(url)) {
            log.info("MYSQL_URL points at a loopback host; skipping the sslMode=VERIFY_IDENTITY "
                    + "requirement for this connection. A loopback connection has no network "
                    + "segment to intercept. This exemption cannot be reached in Kubernetes.");
            return;
        }
        if (URL_USE_SSL.matcher(url).find()) {
            throw new IllegalArgumentException(
                    "MYSQL_URL uses the deprecated useSSL property; MySQL Connector/J 8 lets "
                            + "sslMode override it silently. Remove useSSL and set "
                            + "sslMode=VERIFY_IDENTITY.");
        }
        List<String> modes = sslModeValues(url);
        if (modes.isEmpty()) {
            throw verifyIdentityRequired();
        }
        for (String mode : modes) {
            if (!REQUIRED_SSL_MODE.equalsIgnoreCase(mode)) {
                throw verifyIdentityRequired();
            }
        }
    }

    /**
     * Every value the URL's property block gives {@code sslMode}, tokenized the way Connector/J 8.4
     * tokenizes that block — because a guard that reads the URL more loosely than the driver does
     * accepts URLs the driver runs at {@code PREFERRED}, which is the whole failure this class
     * exists to prevent. Three such divergences shipped, each measured against
     * {@code ConnectionUrl.getConnectionUrlInstance} and each fixed here:
     *
     * <ul>
     *   <li><b>Only the first {@code ?} opens the property block; a later one is an ordinary
     *       character.</b> {@code ?serverTimezone=UTC?sslMode=VERIFY_IDENTITY} is one property
     *       named {@code serverTimezone} whose value is {@code UTC?sslMode=VERIFY_IDENTITY}, and
     *       the effective mode is {@code PREFERRED}. That is {@code k8s/base}'s own URL with one
     *       {@code &} mistyped as {@code ?}. Treating {@code ?} as a separator accepted it.</li>
     *   <li><b>{@code ;} separates nothing.</b>
     *       {@code ?connectionAttributes=x;sslMode=VERIFY_IDENTITY} is likewise one
     *       {@code connectionAttributes} property; effective mode {@code PREFERRED}.</li>
     *   <li><b>The property name is case-sensitive.</b> {@code ?sslmode=verify_identity} and
     *       {@code ?SSLMODE=VERIFY_IDENTITY} resolve to {@code PREFERRED} — Connector/J drops an
     *       unknown property without an exception, so a wrong-case key is silent. The <i>value</i>
     *       is compared case-insensitively by the caller, because
     *       {@code sslMode=verify_identity} does resolve to {@code VERIFY_IDENTITY}.</li>
     * </ul>
     *
     * <p>So: cut the fragment at the first {@code #}, take everything after the first {@code ?},
     * split that on {@code &} alone, and split each pair at its first {@code =}. Name and value are
     * both trimmed, which the driver also does ({@code ? sslMode = VERIFY_IDENTITY } resolves to
     * {@code VERIFY_IDENTITY}). The {@code #} cut matters for the same reason as the {@code ?}
     * one: measured, {@code ?a=b#&sslMode=VERIFY_IDENTITY} is a URL whose fragment the driver
     * discards, leaving {@code PREFERRED}.
     *
     * <p><b>One step of the driver's parse is deliberately not reproduced:</b> Connector/J
     * percent-decodes each name and value after this tokenization ({@code URLDecoder.decode}).
     * Not decoding over-rejects {@code ?sslMode=VERIFY%5FIDENTITY}, which is safe, and leaves one
     * residual fail-open — {@code ?sslMode=VERIFY_IDENTITY&%73slMode=DISABLED} resolves to
     * {@code DISABLED} and is accepted here. Reproducing the decode would import
     * {@code URLDecoder}'s own quirks (it throws on a malformed {@code %} and turns {@code +} into
     * a space) into a guard whose job is catching operator typos, and percent-encoding a property
     * name is not a typo. Stated rather than silently left, because every hole this guard has had
     * was one nobody had written down.
     *
     * <p><b>Kept in sync by hand with {@code MySqlTlsManifestTest}</b>, which applies the same
     * tokenization to {@code k8s/base}'s {@code MYSQL_URL}. Change one and change the other: a
     * conformance test cannot catch a flaw it shares with the thing it checks, which is how each
     * divergence above survived review in both places at once.
     */
    private static List<String> sslModeValues(String url) {
        int fragment = url.indexOf('#');
        String beforeFragment = fragment < 0 ? url : url.substring(0, fragment);
        int propertyBlock = beforeFragment.indexOf('?');
        if (propertyBlock < 0) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String pair : beforeFragment.substring(propertyBlock + 1).split("&", -1)) {
            int equals = pair.indexOf('=');
            if (equals < 0) {
                continue;
            }
            if (SSL_MODE_PROPERTY.equals(pair.substring(0, equals).trim())) {
                values.add(pair.substring(equals + 1).trim());
            }
        }
        return values;
    }

    private static IllegalArgumentException verifyIdentityRequired() {
        return new IllegalArgumentException(
                "MYSQL_URL must set sslMode=VERIFY_IDENTITY when MySQL is enabled. Connector/J "
                        + "defaults to PREFERRED, which falls back to plaintext without error and "
                        + "verifies no certificate, so the connection carrying catalog rows, outbox "
                        + "events and saga state would be unprotected and look identical to a "
                        + "protected one.");
    }

    /**
     * True only when <em>every</em> host in the authority is loopback.
     *
     * <p>Connector/J accepts a comma-separated host list and fails over across it, so
     * {@code jdbc:mysql://localhost:3306,db.prod.internal/recsys} is a connection that can land on
     * {@code db.prod.internal} — measured: the driver parses two hosts from it. Testing only the
     * last host, or only the first, would exempt that URL from the TLS requirement and let the
     * failover leg run in plaintext.
     */
    private static boolean isLoopback(String url) {
        Matcher host = URL_HOST.matcher(url);
        if (!host.find()) {
            return false;
        }
        String[] hosts = host.group(1).split(",", -1);
        for (String each : hosts) {
            if (!isLoopbackHost(each)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLoopbackHost(String hostAndPort) {
        String authority = hostAndPort.trim();
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
