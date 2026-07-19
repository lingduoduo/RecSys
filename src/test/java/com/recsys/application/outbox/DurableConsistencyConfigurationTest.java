package com.recsys.application.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DurableConsistencyConfigurationTest {
    private static final String VALID_SECRET = "0123456789abcdef0123456789abcdef"; // 32 bytes

    @Test void disabledConfigurationKeepsLegacyLocalMode() {
        DurableConsistencyConfiguration config = DurableConsistencyConfiguration.fromEnv(Map.of());
        assertThat(config.enabled()).isFalse();
        assertThat(config.tokenSecret()).isNull();
    }

    @Test void enabledConfigurationRequiresMysql() {
        assertThatThrownBy(() -> DurableConsistencyConfiguration.fromEnv(
                Map.of("ONLINE_DURABLE_EVENTS_ENABLED", "true")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MYSQL_ENABLED");
    }

    @Test void enabledConfigurationRejectsMissingTokenSecret() {
        assertThatThrownBy(() -> DurableConsistencyConfiguration.fromEnv(Map.of(
                "ONLINE_DURABLE_EVENTS_ENABLED", "true",
                "MYSQL_ENABLED", "true",
                "OUTBOX_KAFKA_BOOTSTRAP_SERVERS", "kafka:9092")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");
    }

    @Test void enabledConfigurationRejectsShortTokenSecret() {
        assertThatThrownBy(() -> DurableConsistencyConfiguration.fromEnv(Map.of(
                "ONLINE_DURABLE_EVENTS_ENABLED", "true",
                "MYSQL_ENABLED", "true",
                "ONLINE_CONSISTENCY_TOKEN_SECRET", "short",
                "OUTBOX_KAFKA_BOOTSTRAP_SERVERS", "kafka:9092")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");
    }

    @Test void enabledConfigurationRequiresKafkaBootstrap() {
        assertThatThrownBy(() -> DurableConsistencyConfiguration.fromEnv(Map.of(
                "ONLINE_DURABLE_EVENTS_ENABLED", "true",
                "MYSQL_ENABLED", "true",
                "ONLINE_CONSISTENCY_TOKEN_SECRET", VALID_SECRET)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OUTBOX_KAFKA_BOOTSTRAP_SERVERS");
    }

    @Test void enabledConfigurationParsesRelaySettings() {
        DurableConsistencyConfiguration config = DurableConsistencyConfiguration.fromEnv(Map.of(
                "ONLINE_DURABLE_EVENTS_ENABLED", "true",
                "MYSQL_ENABLED", "true",
                "ONLINE_CONSISTENCY_TOKEN_SECRET", VALID_SECRET,
                "OUTBOX_KAFKA_BOOTSTRAP_SERVERS", "kafka:9092",
                "OUTBOX_KAFKA_ONLINE_TOPIC", "online-events",
                "OUTBOX_DELIVERY_DEADLINE_MS", "4000"));
        assertThat(config.enabled()).isTrue();
        assertThat(config.tokenSecret()).isEqualTo(VALID_SECRET);
        assertThat(config.kafkaBootstrapServers()).isEqualTo("kafka:9092");
        assertThat(config.kafkaOnlineTopic()).isEqualTo("online-events");
        assertThat(config.deliveryDeadline()).isEqualTo(Duration.ofSeconds(4));
    }
}
