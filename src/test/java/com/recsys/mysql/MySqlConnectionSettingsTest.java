package com.recsys.mysql;

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
    }

    @Test
    void fromEnv_readsExplicitMysqlSettings() {
        MySqlConnectionSettings settings = MySqlConnectionSettings.fromEnv(Map.of(
                "MYSQL_ENABLED", "true",
                "MYSQL_URL", "jdbc:mysql://db.internal:3306/recsys",
                "MYSQL_USER", "app",
                "MYSQL_PASSWORD", "secret"
        ));

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.url()).isEqualTo("jdbc:mysql://db.internal:3306/recsys");
        assertThat(settings.username()).isEqualTo("app");
        assertThat(settings.password()).isEqualTo("secret");
        assertThat(settings.safeDescription()).contains("password='***'");
        assertThat(settings.safeDescription()).doesNotContain("secret");
    }

    @Test
    void constructor_rejectsNonMysqlJdbcUrls() {
        assertThatThrownBy(() -> new MySqlConnectionSettings(true, "jdbc:postgresql://localhost/db", "u", "p"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jdbc:mysql://");
    }
}
