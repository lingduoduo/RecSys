package com.recsys.application.saga;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.domain.saga.SagaEventType;
import com.recsys.domain.saga.SagaException;
import com.recsys.domain.saga.SagaStatus;
import com.recsys.domain.saga.SagaTransitionEvent;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqsSagaEventPublisherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void publishSendsTransitionJsonAndRoutingAttributes() throws Exception {
        SqsClient sqs = mock(SqsClient.class);
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("m1").build());
        SqsSagaEventPublisher publisher = new SqsSagaEventPublisher(sqs, "queue-url", MAPPER, false);

        publisher.publish(event());

        org.mockito.ArgumentCaptor<SendMessageRequest> captor =
                org.mockito.ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(captor.capture());
        SendMessageRequest request = captor.getValue();

        assertThat(request.queueUrl()).isEqualTo("queue-url");
        JsonNode body = MAPPER.readTree(request.messageBody());
        assertThat(body.get("eventId").asText()).isEqualTo("saga-1:STEP_STARTED:reserve");
        assertThat(body.get("sagaId").asText()).isEqualTo("saga-1");
        assertThat(body.get("type").asText()).isEqualTo("STEP_STARTED");

        assertThat(request.messageAttributes().get("sagaId").stringValue()).isEqualTo("saga-1");
        assertThat(request.messageAttributes().get("sagaType").stringValue()).isEqualTo("recommendation-refresh");
        assertThat(request.messageAttributes().get("eventType").stringValue()).isEqualTo("STEP_STARTED");
        assertThat(request.messageAttributes().get("status").stringValue()).isEqualTo("STEP_STARTED");
        assertThat(request.messageAttributes().get("correlationId").stringValue()).isEqualTo("req-1");
    }

    @Test
    void publishThrowsSagaExceptionWhenStrictSendFails() {
        SqsClient sqs = mock(SqsClient.class);
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(new RuntimeException("sqs down"));
        SqsSagaEventPublisher publisher = new SqsSagaEventPublisher(sqs, "queue-url", MAPPER, false);

        assertThatThrownBy(() -> publisher.publish(event()))
                .isInstanceOf(SagaException.class)
                .hasMessageContaining("failed to publish saga event");
    }

    @Test
    void publishDoesNotThrowWhenBestEffortSendFails() {
        SqsClient sqs = mock(SqsClient.class);
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(new RuntimeException("sqs down"));
        SqsSagaEventPublisher publisher = new SqsSagaEventPublisher(sqs, "queue-url", MAPPER, true);

        assertThatCode(() -> publisher.publish(event())).doesNotThrowAnyException();
    }

    @Test
    void publishRejectsNullEvent() {
        SqsSagaEventPublisher publisher = new SqsSagaEventPublisher(mock(SqsClient.class), "queue-url", MAPPER, false);

        assertThatNullPointerException().isThrownBy(() -> publisher.publish(null));
    }

    private static SagaTransitionEvent event() {
        return new SagaTransitionEvent(
                "saga-1:STEP_STARTED:reserve",
                "saga-1",
                "recommendation-refresh",
                "req-1",
                SagaEventType.STEP_STARTED,
                SagaStatus.STEP_STARTED,
                "reserve",
                "{\"userId\":101}",
                Instant.parse("2026-06-25T12:00:00Z"));
    }
}
