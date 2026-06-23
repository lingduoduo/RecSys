package com.recsys.application.experiment;
import com.recsys.application.experiment.AbExposureLogger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.config.ABTestConfig;
import com.recsys.infrastructure.messaging.AsyncEventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AbExposureLoggerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ABTestConfig enabledConfig() {
        ABTestConfig c = new ABTestConfig();
        c.setEnabled(true);
        return c;
    }

    private ABTestService.Assignment assignment() {
        return new ABTestService.Assignment("test", 1234, "default", true);
    }

    @Test
    void emitsExposureEventWithServedVariant() throws Exception {
        AsyncEventPublisher publisher = mock(AsyncEventPublisher.class);
        AbExposureLogger logger = new AbExposureLogger(publisher, enabledConfig(),
                () -> "fixed-event-id", () -> 1700L);

        logger.log("123", assignment(), "test", false, "v9");

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(publisher).publish(json.capture());
        JsonNode e = mapper.readTree(json.getValue());
        assertThat(e.get("userId").asText()).isEqualTo("123");
        assertThat(e.get("assignedVariant").asText()).isEqualTo("test");
        assertThat(e.get("servedVariant").asText()).isEqualTo("test");
        assertThat(e.get("fellBackFrom").isNull()).isTrue();
        assertThat(e.get("layer").asText()).isEqualTo("default");
        assertThat(e.get("slot").asInt()).isEqualTo(1234);
        assertThat(e.get("inExperiment").asBoolean()).isTrue();
        assertThat(e.get("modelVersion").asText()).isEqualTo("v9");
        assertThat(e.get("eventId").asText()).isEqualTo("fixed-event-id");
        assertThat(e.get("timestampMs").asLong()).isEqualTo(1700L);
    }

    @Test
    void fallbackRecordsFellBackFrom() throws Exception {
        AsyncEventPublisher publisher = mock(AsyncEventPublisher.class);
        AbExposureLogger logger = new AbExposureLogger(publisher, enabledConfig(),
                () -> "id", () -> 1L);

        logger.log("123", assignment(), "training", true, "v9");  // assigned test, served training

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(publisher).publish(json.capture());
        JsonNode e = mapper.readTree(json.getValue());
        assertThat(e.get("assignedVariant").asText()).isEqualTo("test");
        assertThat(e.get("servedVariant").asText()).isEqualTo("training");
        assertThat(e.get("fellBackFrom").asText()).isEqualTo("test");
    }

    @Test
    void disabled_isNoOp() {
        AsyncEventPublisher publisher = mock(AsyncEventPublisher.class);
        ABTestConfig disabled = new ABTestConfig();   // enabled defaults to false
        AbExposureLogger logger = new AbExposureLogger(publisher, disabled, () -> "id", () -> 1L);

        logger.log("123", assignment(), "test", false, "v9");

        verifyNoInteractions(publisher);
    }

    @Test
    void publisherFailureDoesNotThrow() {
        AsyncEventPublisher publisher = mock(AsyncEventPublisher.class);
        when(publisher.publish(anyString())).thenThrow(new RuntimeException("boom"));
        AbExposureLogger logger = new AbExposureLogger(publisher, enabledConfig(), () -> "id", () -> 1L);

        // Must not propagate — exposure logging never breaks the request.
        logger.log("123", assignment(), "test", false, "v9");
    }
}
