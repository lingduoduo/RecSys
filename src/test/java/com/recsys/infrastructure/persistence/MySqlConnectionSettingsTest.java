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
                "MYSQL_URL", "jdbc:mysql://db.internal:3306/recsys",
                "MYSQL_USER", "app",
                "MYSQL_PASSWORD", "secret",
                "MYSQL_QUERY_TIMEOUT_SECONDS", "10",
                "MYSQL_READ_MAX_ATTEMPTS", "1",
                "MYSQL_READ_RETRY_BACKOFF_MS", "250",
                "MYSQL_CURSOR_SIGNING_KEY", "0123456789abcdef0123456789abcdef"
        ));

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.url()).isEqualTo("jdbc:mysql://db.internal:3306/recsys");
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
    void fromEnv_rejectsMissingSigningKeyWhenEnabled() {
        assertThatThrownBy(() -> MySqlConnectionSettings.fromEnv(Map.of("MYSQL_ENABLED", "true")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MYSQL_CURSOR_SIGNING_KEY");
    }

    @Test
    void fromEnv_rejectsSigningKeyShorterThan32Utf8BytesWhenEnabled() {
        assertThatThrownBy(() -> MySqlConnectionSettings.fromEnv(Map.of(
                "MYSQL_ENABLED", "true",
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
}
