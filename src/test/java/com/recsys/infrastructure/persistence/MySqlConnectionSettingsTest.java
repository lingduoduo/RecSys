package com.recsys.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MySqlConnectionSettingsTest {

    @Test
    void fromEnv_defaultsToDisabled() {
        MySqlConnectionSettings settings = MySqlConnectionSettings.fromEnv(Map.of());

        assertThat(settings.enabled()).isFalse();
        assertThat(settings.url()).startsWith("jdbc:mysql://localhost:3306/recsys");
        assertThat(settings.username()).isEqualTo("recsys");
        assertThat(settings.password()).isEmpty();
        assertThat(settings.queryTimeoutSeconds()).isEqualTo(2);
        assertThat(settings.maxReadAttempts()).isEqualTo(2);
        assertThat(settings.retryBackoffMillis()).isEqualTo(50);
        assertThat(settings.cursorSigningKey()).isEmpty();
    }

    @Test
    void fromEnv_readsExplicitMysqlSettings() {
        MySqlConnectionSettings settings = MySqlConnectionSettings.fromEnv(Map.of(
                "MYSQL_ENABLED", "true",
                "MYSQL_URL", "jdbc:mysql://db.internal:3306/recsys?sslMode=VERIFY_IDENTITY",
                "MYSQL_USER", "app",
                "MYSQL_PASSWORD", "secret",
                "MYSQL_QUERY_TIMEOUT_SECONDS", "10",
                "MYSQL_READ_MAX_ATTEMPTS", "1",
                "MYSQL_READ_RETRY_BACKOFF_MS", "250",
                "MYSQL_CURSOR_SIGNING_KEY", "0123456789abcdef0123456789abcdef"
        ));

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.url()).isEqualTo("jdbc:mysql://db.internal:3306/recsys?sslMode=VERIFY_IDENTITY");
        assertThat(settings.username()).isEqualTo("app");
        assertThat(settings.password()).isEqualTo("secret");
        assertThat(settings.queryTimeoutSeconds()).isEqualTo(10);
        assertThat(settings.maxReadAttempts()).isEqualTo(1);
        assertThat(settings.retryBackoffMillis()).isEqualTo(250);
        assertThat(settings.cursorSigningKey()).isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(settings.safeDescription()).contains("password='***'");
        assertThat(settings.safeDescription()).doesNotContain("secret");
        assertThat(settings.safeDescription()).doesNotContain("0123456789abcdef0123456789abcdef");
    }

    @Test
    void descriptions_doNotExposePasswordOrCursorSigningKey() {
        MySqlConnectionSettings settings = new MySqlConnectionSettings(
                true,
                "jdbc:mysql://db.internal:3306/catalog?sslMode=VERIFY_IDENTITY",
                "app",
                "record-password",
                2,
                2,
                50,
                "record-signing-key-0123456789abcdef");

        assertThat(settings.toString())
                .doesNotContain("record-password")
                .doesNotContain("record-signing-key-0123456789abcdef");
        assertThat(settings.safeDescription())
                .doesNotContain("record-password")
                .doesNotContain("record-signing-key-0123456789abcdef");
    }

    @Test
    void safeDescription_redactsJdbcUrlCredentialsButPreservesLocation() {
        MySqlConnectionSettings settings = new MySqlConnectionSettings(
                false,
                "jdbc:mysql://db.internal:3306/catalog?user=url-user&password=url-password"
                        + "&sslMode=VERIFY_IDENTITY",
                "app",
                "record-password",
                2,
                2,
                50,
                "");

        assertThat(settings.safeDescription())
                .contains("jdbc:mysql://db.internal:3306/catalog")
                .contains("sslMode=VERIFY_IDENTITY")
                .doesNotContain("url-user")
                .doesNotContain("url-password")
                .doesNotContain("record-password");
    }

    @Test
    void fromEnv_rejectsMissingSigningKeyWhenEnabled() {
        assertThatThrownBy(() -> MySqlConnectionSettings.fromEnv(Map.of(
                "MYSQL_ENABLED", "true",
                "MYSQL_URL", "jdbc:mysql://db/catalog", "MYSQL_USER", "app",
                "MYSQL_PASSWORD", "placeholder-password")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MYSQL_CURSOR_SIGNING_KEY");
    }

    @Test
    void fromEnv_requiresExplicitNonBlankUrlAndUserWhenEnabled() {
        Map<String, String> base = Map.of(
                "MYSQL_ENABLED", "true", "MYSQL_PASSWORD", "password",
                "MYSQL_CURSOR_SIGNING_KEY", "0123456789abcdef0123456789abcdef");
        assertThatThrownBy(() -> MySqlConnectionSettings.fromEnv(base))
                .hasMessageContaining("MYSQL_URL").hasMessageNotContaining("password");
        assertThatThrownBy(() -> MySqlConnectionSettings.fromEnv(new java.util.HashMap<>(base) {{
            put("MYSQL_URL", "jdbc:mysql://db/catalog"); put("MYSQL_USER", "   ");
        }})).hasMessageContaining("MYSQL_USER").hasMessageNotContaining("password");
    }

    @Test
    void fromEnv_rejectsMissingPasswordWhenEnabledWithoutLeakingOtherSecrets() {
        String signingKey = "signing-key-that-must-not-leak-123";

        assertThatThrownBy(() -> MySqlConnectionSettings.fromEnv(Map.of(
                "MYSQL_ENABLED", "true",
                "MYSQL_URL", "jdbc:mysql://db/catalog", "MYSQL_USER", "app",
                "MYSQL_CURSOR_SIGNING_KEY", signingKey)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MYSQL_PASSWORD")
                .hasMessageNotContaining(signingKey);
    }

    @Test
    void fromEnv_rejectsEmptyPasswordWhenEnabledWithoutLeakingOtherSecrets() {
        String signingKey = "signing-key-that-must-not-leak-123";

        assertThatThrownBy(() -> MySqlConnectionSettings.fromEnv(Map.of(
                "MYSQL_ENABLED", "true",
                "MYSQL_URL", "jdbc:mysql://db/catalog", "MYSQL_USER", "app",
                "MYSQL_PASSWORD", "",
                "MYSQL_CURSOR_SIGNING_KEY", signingKey)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MYSQL_PASSWORD")
                .hasMessageNotContaining(signingKey);
    }

    @Test
    void fromEnv_preservesWhitespacePasswordWhenEnabled() {
        MySqlConnectionSettings settings = MySqlConnectionSettings.fromEnv(Map.of(
                "MYSQL_ENABLED", "true",
                "MYSQL_URL", "jdbc:mysql://db/catalog?sslMode=VERIFY_IDENTITY", "MYSQL_USER", "app",
                "MYSQL_PASSWORD", "   ",
                "MYSQL_CURSOR_SIGNING_KEY", "0123456789abcdef0123456789abcdef"));

        assertThat(settings.password()).isEqualTo("   ");
    }

    @Test
    void fromEnv_rejectsSigningKeyShorterThan32Utf8BytesWhenEnabled() {
        assertThatThrownBy(() -> MySqlConnectionSettings.fromEnv(Map.of(
                "MYSQL_ENABLED", "true",
                "MYSQL_URL", "jdbc:mysql://db/catalog", "MYSQL_USER", "app",
                "MYSQL_PASSWORD", "placeholder-password",
                "MYSQL_CURSOR_SIGNING_KEY", "1234567890123456789012345678901"
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 UTF-8 bytes");
    }

    @Test
    void fromEnv_rejectsQueryTimeoutOutsideAllowedRange() {
        assertThatThrownBy(() -> settingsWith("MYSQL_QUERY_TIMEOUT_SECONDS", "0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MYSQL_QUERY_TIMEOUT_SECONDS");
        assertThatThrownBy(() -> settingsWith("MYSQL_QUERY_TIMEOUT_SECONDS", "31"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MYSQL_QUERY_TIMEOUT_SECONDS");
    }

    @Test
    void fromEnv_rejectsReadAttemptsOutsideAllowedRange() {
        assertThatThrownBy(() -> settingsWith("MYSQL_READ_MAX_ATTEMPTS", "0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MYSQL_READ_MAX_ATTEMPTS");
        assertThatThrownBy(() -> settingsWith("MYSQL_READ_MAX_ATTEMPTS", "3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MYSQL_READ_MAX_ATTEMPTS");
    }

    @Test
    void fromEnv_rejectsRetryBackoffOutsideAllowedRange() {
        assertThatThrownBy(() -> settingsWith("MYSQL_READ_RETRY_BACKOFF_MS", "-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MYSQL_READ_RETRY_BACKOFF_MS");
        assertThatThrownBy(() -> settingsWith("MYSQL_READ_RETRY_BACKOFF_MS", "1001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MYSQL_READ_RETRY_BACKOFF_MS");
    }

    @Test
    void constructor_rejectsNonMysqlJdbcUrls() {
        assertThatThrownBy(() -> new MySqlConnectionSettings(
                true, "jdbc:postgresql://localhost/db", "u", "p", 2, 2, 50, "x".repeat(32)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jdbc:mysql://");
    }

    private static MySqlConnectionSettings settingsWith(String name, String value) {
        return MySqlConnectionSettings.fromEnv(Map.of(name, value));
    }

    @Test
    void rejectsAnEnabledConnectionThatDoesNotVerifyTls() {
        assertThatThrownBy(() -> enabledWithUrl("jdbc:mysql://db.internal:3306/recsys"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sslMode=VERIFY_IDENTITY");
    }

    @Test
    void rejectsEverySslModeWeakerThanVerifyIdentity() {
        // PREFERRED is Connector/J 8's default and the reason this guard exists: it negotiates TLS
        // if offered, verifies no certificate, and falls back to plaintext in silence.
        for (String mode : new String[]{"DISABLED", "PREFERRED", "REQUIRED", "VERIFY_CA"}) {
            assertThatThrownBy(() -> enabledWithUrl(
                    "jdbc:mysql://db.internal:3306/recsys?sslMode=" + mode))
                    .as("sslMode=%s must be rejected", mode)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sslMode=VERIFY_IDENTITY");
        }

        // Connector/J property names are case-sensitive and an unknown property is dropped without
        // an exception, so both of these resolve to PREFERRED. Measured against 8.4.0. "sslmode="
        // is the spelling libpq and the MySQL CLI use, so it is the likely operator typo, and it
        // produces no second signal anywhere — the URL reads correct and the pod starts.
        for (String key : new String[]{"sslmode", "SSLMODE", "SslMode", "sslModE"}) {
            assertThatThrownBy(() -> enabledWithUrl(
                    "jdbc:mysql://db.internal:3306/recsys?" + key + "=VERIFY_IDENTITY"))
                    .as("%s= is not the property Connector/J reads; it must be rejected", key)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sslMode=VERIFY_IDENTITY");
        }
        assertThatThrownBy(() -> enabledWithUrl(
                "jdbc:mysql://db.internal:3306/recsys?sslmode=verify_identity"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sslMode=VERIFY_IDENTITY");
    }

    @Test
    void theSslModeValueMayBeAnyCaseBecauseTheDriverResolvesItThatWay() {
        // Only the *name* is case-sensitive to Connector/J. sslMode=verify_identity resolves to the
        // VERIFY_IDENTITY enum constant (measured), so rejecting it would be a false alarm.
        assertThat(enabledWithUrl("jdbc:mysql://db.internal:3306/recsys?sslMode=verify_identity")
                .enabled()).isTrue();
    }

    @Test
    void rejectsASslModeHiddenInsideAnotherPropertysValue() {
        // Connector/J splits the property block on '&' alone; ';' separates nothing. This URL is
        // one property named connectionAttributes whose value is the text
        // "x;sslMode=VERIFY_IDENTITY", so the effective sslMode is PREFERRED. Measured against
        // 8.4.0, which raises no error.
        assertThatThrownBy(() -> enabledWithUrl(
                "jdbc:mysql://db.prod/recsys?connectionAttributes=x;sslMode=VERIFY_IDENTITY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sslMode=VERIFY_IDENTITY");

        // Same reason with the operands swapped: the driver reads the value as
        // "VERIFY_IDENTITY;connectionAttributes=x" and rejects it as not a legal sslMode. Failing
        // here rather than at first connection keeps the guard and the driver in agreement.
        assertThatThrownBy(() -> enabledWithUrl(
                "jdbc:mysql://db.prod/recsys?sslMode=VERIFY_IDENTITY;connectionAttributes=x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sslMode=VERIFY_IDENTITY");
    }

    @Test
    void acceptsVerifyIdentity() {
        MySqlConnectionSettings settings =
                enabledWithUrl("jdbc:mysql://db.internal:3306/recsys?sslMode=VERIFY_IDENTITY");
        assertThat(settings.url()).contains("sslMode=VERIFY_IDENTITY");
    }

    @Test
    void rejectsTheDeprecatedUseSslPropertyEvenAlongsideVerifyIdentity() {
        // Where both appear, Connector/J lets sslMode win — so a URL carrying both reads as one
        // thing and behaves as another. Refusing the ambiguity is cheaper than resolving it.
        assertThatThrownBy(() -> enabledWithUrl(
                "jdbc:mysql://db.internal:3306/recsys?useSSL=true&sslMode=VERIFY_IDENTITY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("useSSL");
    }

    @Test
    void rejectsDisagreeingDuplicateSslModes() {
        assertThatThrownBy(() -> enabledWithUrl(
                "jdbc:mysql://db.internal:3306/recsys?sslMode=VERIFY_IDENTITY&sslMode=DISABLED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sslMode=VERIFY_IDENTITY");
    }

    @Test
    void aDisabledConnectionIsNotSubjectToTheTransportRule() {
        // The disabled case must stay inert: MYSQL_ENABLED=false is the default in k8s/base, and a
        // guard that fired here would stop every service from starting.
        MySqlConnectionSettings settings = new MySqlConnectionSettings(
                false, "jdbc:mysql://db.internal:3306/recsys", "app", "", 2, 2, 50, "");
        assertThat(settings.enabled()).isFalse();
    }

    @Test
    void loopbackHostsAreExemptFromTheTransportRule() {
        // Not an opt-out: a loopback connection has no network segment to intercept, and the host
        // in Kubernetes is "mysql" or an RDS endpoint, so this cannot be reached from a manifest.
        for (String host : new String[]{"localhost", "127.0.0.1", "[::1]"}) {
            assertThat(enabledWithUrl("jdbc:mysql://" + host + ":3306/recsys").enabled())
                    .as("%s must be exempt", host)
                    .isTrue();
        }
    }

    @Test
    void theLocalDevelopmentDefaultStillConstructs() {
        // The exact URL application.yml ships. It is loopback AND carries useSSL=false, so the
        // exemption has to cover the useSSL rejection too, not only the sslMode rule.
        assertThat(enabledWithUrl("jdbc:mysql://localhost:3306/recsys?useSSL=false"
                + "&serverTimezone=UTC&connectTimeout=1000&socketTimeout=2000").enabled()).isTrue();
    }

    @Test
    void aMultiHostUrlIsExemptOnlyIfEveryHostIsLoopback() {
        // Connector/J accepts a comma-separated host list and fails over across it — measured: it
        // parses two hosts from this URL. An exemption that inspected only one of them would let
        // the db.prod.internal leg run in plaintext.
        assertThatThrownBy(() -> enabledWithUrl(
                "jdbc:mysql://localhost:3306,db.prod.internal/recsys"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sslMode=VERIFY_IDENTITY");
        assertThatThrownBy(() -> enabledWithUrl(
                "jdbc:mysql://db.prod.internal,localhost:3306/recsys"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sslMode=VERIFY_IDENTITY");

        // All-loopback stays exempt: this is the local dev shape, not a production one.
        assertThat(enabledWithUrl("jdbc:mysql://localhost:3306,127.0.0.1:3307/recsys").enabled())
                .isTrue();
    }

    @Test
    void aNonLoopbackHostIsNotExempt() {
        // Guards against an exemption written loosely enough to match everything.
        assertThatThrownBy(() -> enabledWithUrl("jdbc:mysql://localhost.evil.example:3306/recsys"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** An enabled settings object with the given URL and otherwise-valid required values. */
    private static MySqlConnectionSettings enabledWithUrl(String url) {
        return new MySqlConnectionSettings(true, url, "app", "secret", 2, 2, 50,
                "0123456789abcdef0123456789abcdef");
    }
}
