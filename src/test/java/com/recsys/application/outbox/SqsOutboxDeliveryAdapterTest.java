package com.recsys.application.outbox;

import com.recsys.domain.outbox.*;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SqsOutboxDeliveryAdapterTest {
    @Test void includesStableIdentityAttributesAndCompletesOnSqsAcknowledgement() {
        SqsAsyncClient sqs = mock(SqsAsyncClient.class);
        CompletableFuture<SendMessageResponse> ack = new CompletableFuture<>();
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(ack);
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.pending(eventId, "saga", "order-7", "PaymentRequested",
                OutboxDestination.SQS_SAGA, null, "{\"amount\":4}", Instant.EPOCH);

        DeliveryAttempt attempt = new SqsOutboxDeliveryAdapter(sqs, "https://sqs/queue", java.time.Clock.systemUTC(), Duration.ofMillis(750))
                .deliver(event);
        var receipt = attempt.completion().toCompletableFuture();
        assertThat(receipt).isNotDone();
        var request = org.mockito.ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(request.capture());
        assertThat(request.getValue().messageBody()).isEqualTo(event.payload());
        assertThat(request.getValue().messageAttributes())
                .extractingByKey("eventId").extracting(MessageAttributeValue::stringValue).isEqualTo(eventId.toString());
        assertThat(request.getValue().messageAttributes())
                .extractingByKey("aggregateId").extracting(MessageAttributeValue::stringValue).isEqualTo("order-7");
        assertThat(request.getValue().messageAttributes())
                .extractingByKey("eventType").extracting(MessageAttributeValue::stringValue).isEqualTo("PaymentRequested");
        assertThat(request.getValue().overrideConfiguration().flatMap(c -> c.apiCallTimeout()))
                .contains(Duration.ofMillis(750));
        assertThat(request.getValue().overrideConfiguration().flatMap(c -> c.apiCallAttemptTimeout()))
                .contains(Duration.ofMillis(750));

        ack.complete(SendMessageResponse.builder().messageId("broker-id").build());
        assertThat(receipt).isCompleted();
    }

    @Test void cancellationWaitsForDefinitiveSdkSettlementWithoutCancellingTheLocalFuture() {
        SqsAsyncClient sqs = mock(SqsAsyncClient.class);
        CompletableFuture<SendMessageResponse> nativeFuture = new CompletableFuture<>();
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(nativeFuture);
        OutboxEvent event = OutboxEvent.pending(UUID.randomUUID(), "saga", "order-7", "PaymentRequested",
                OutboxDestination.SQS_SAGA, null, "{}", Instant.EPOCH);

        DeliveryAttempt attempt = new SqsOutboxDeliveryAdapter(sqs, "https://sqs/queue", Duration.ofSeconds(1)).deliver(event);
        CompletableFuture<Void> cancellation = attempt.cancel().toCompletableFuture();
        assertThat(cancellation).isNotDone();
        assertThat(nativeFuture).isNotCancelled();
        nativeFuture.completeExceptionally(new RuntimeException("SDK API-call timeout"));
        assertThat(cancellation).isCompleted();
    }
}
