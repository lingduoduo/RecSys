package com.recsys.infrastructure.observability;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SplunkHecConfigTest {

    @Test
    void disabledWhenTokenAbsent() {
        SplunkHecConfig config = SplunkHecConfig.from(Map.of());

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.isMisconfigured()).isFalse();
        assertThat(config.disabledReason()).contains("SPLUNK_HEC_TOKEN");
    }

    @Test
    void blankTokenIsTreatedAsAbsent() {
        SplunkHecConfig config = SplunkHecConfig.from(Map.of("SPLUNK_HEC_TOKEN", "   "));

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.isMisconfigured()).isFalse();
    }

    @Test
    void enabledWithTokenAndDefaults() {
        SplunkHecConfig config = SplunkHecConfig.from(Map.of("SPLUNK_HEC_TOKEN", "tok-1"));

        assertThat(config.isEnabled()).isTrue();
        assertThat(config.disabledReason()).isNull();
        assertThat(config.token()).isEqualTo("tok-1");
        assertThat(config.uri()).hasToString("http://splunk:8088/services/collector/event");
        assertThat(config.index()).isEqualTo("recsys");
        assertThat(config.sourcetype()).isEqualTo("recsys:app:log");
        assertThat(config.serviceName()).isEqualTo("recsys");
        assertThat(config.queueCapacity()).isEqualTo(10_000);
        assertThat(config.batchSize()).isEqualTo(100);
        assertThat(config.linger()).isEqualTo(Duration.ofMillis(1_000));
        assertThat(config.timeout()).isEqualTo(Duration.ofMillis(2_000));
        assertThat(config.insecureTls()).isFalse();
    }

    @Test
    void overridesEveryField() {
        SplunkHecConfig config = SplunkHecConfig.from(Map.ofEntries(
                Map.entry("SPLUNK_HEC_TOKEN", "tok-2"),
                Map.entry("SPLUNK_HEC_URL", "https://splunk.internal:8088/services/collector/event"),
                Map.entry("SPLUNK_HEC_INDEX", "prod"),
                Map.entry("SPLUNK_HEC_SOURCETYPE", "recsys:prod:log"),
                Map.entry("SPLUNK_SERVICE_NAME", "api-gateway"),
                Map.entry("SPLUNK_HEC_QUEUE_CAPACITY", "500"),
                Map.entry("SPLUNK_HEC_BATCH_SIZE", "25"),
                Map.entry("SPLUNK_HEC_LINGER_MS", "250"),
                Map.entry("SPLUNK_HEC_TIMEOUT_MS", "750"),
                Map.entry("SPLUNK_HEC_INSECURE_TLS", "true")));

        assertThat(config.isEnabled()).isTrue();
        assertThat(config.uri()).hasToString("https://splunk.internal:8088/services/collector/event");
        assertThat(config.index()).isEqualTo("prod");
        assertThat(config.sourcetype()).isEqualTo("recsys:prod:log");
        assertThat(config.serviceName()).isEqualTo("api-gateway");
        assertThat(config.queueCapacity()).isEqualTo(500);
        assertThat(config.batchSize()).isEqualTo(25);
        assertThat(config.linger()).isEqualTo(Duration.ofMillis(250));
        assertThat(config.timeout()).isEqualTo(Duration.ofMillis(750));
        assertThat(config.insecureTls()).isTrue();
    }

    @Test
    void malformedIntegerFallsBackToDefault() {
        SplunkHecConfig config = SplunkHecConfig.from(Map.of(
                "SPLUNK_HEC_TOKEN", "tok-3",
                "SPLUNK_HEC_QUEUE_CAPACITY", "not-a-number"));

        assertThat(config.queueCapacity()).isEqualTo(10_000);
    }

    @Test
    void nonPositiveIntegerFallsBackToDefault() {
        SplunkHecConfig config = SplunkHecConfig.from(Map.of(
                "SPLUNK_HEC_TOKEN", "tok-4",
                "SPLUNK_HEC_BATCH_SIZE", "0"));

        assertThat(config.batchSize()).isEqualTo(100);
    }

    @Test
    void blankUrlWithTokenIsMisconfigured() {
        SplunkHecConfig config = SplunkHecConfig.from(Map.of(
                "SPLUNK_HEC_TOKEN", "tok-5",
                "SPLUNK_HEC_URL", "   "));

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.isMisconfigured()).isTrue();
        assertThat(config.disabledReason()).contains("SPLUNK_HEC_URL");
    }

    @Test
    void nonHttpUrlIsMisconfigured() {
        SplunkHecConfig config = SplunkHecConfig.from(Map.of(
                "SPLUNK_HEC_TOKEN", "tok-6",
                "SPLUNK_HEC_URL", "ftp://splunk:8088/services/collector/event"));

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.isMisconfigured()).isTrue();
        assertThat(config.disabledReason()).contains("SPLUNK_HEC_URL");
    }

    @Test
    void relativeUrlIsMisconfigured() {
        SplunkHecConfig config = SplunkHecConfig.from(Map.of(
                "SPLUNK_HEC_TOKEN", "tok-7",
                "SPLUNK_HEC_URL", "/services/collector/event"));

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.isMisconfigured()).isTrue();
    }

    @Test
    void nullEnvironmentIsDisabledNotAnError() {
        SplunkHecConfig config = SplunkHecConfig.from(null);

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.isMisconfigured()).isFalse();
    }
}
