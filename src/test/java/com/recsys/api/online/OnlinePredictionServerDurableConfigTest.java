package com.recsys.api.online;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class OnlinePredictionServerDurableConfigTest {
    @Test void absentDurableConfigurationKeepsTokenlessServingEnabled() {
        assertThat(OnlinePredictionServer.durableConfig(Map.of()).enabled()).isFalse();
    }

    @Test void explicitlyRequestedDurabilityRequiresMysql() {
        assertThatThrownBy(() -> OnlinePredictionServer.durableConfig(Map.of(
                "ONLINE_DURABLE_EVENTS_ENABLED", "true")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MYSQL_ENABLED=true");
    }

    @Test void explicitlyRequestedDurabilityValidatesTokenSecretBeforeResourcesAreBuilt() {
        assertThatThrownBy(() -> OnlinePredictionServer.durableConfig(Map.of(
                "ONLINE_DURABLE_EVENTS_ENABLED", "true", "MYSQL_ENABLED", "true",
                "ONLINE_CONSISTENCY_TOKEN_SECRET", "short")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
