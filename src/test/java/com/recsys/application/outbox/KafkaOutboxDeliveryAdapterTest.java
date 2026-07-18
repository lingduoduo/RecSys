package com.recsys.application.outbox;

import com.recsys.domain.outbox.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaOutboxDeliveryAdapterTest {
    @Test void kafkaPropertiesEnableBoundedIdempotentDelivery() {
        Properties p = KafkaOutboxDeliveryAdapter.producerProps("broker:9092");
        assertThat(p).containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5)
                .containsKeys(ProducerConfig.RETRIES_CONFIG, ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                        ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG);
    }

    @Test void acknowledgementCompletesOnlyFromKafkaCallbackAndPreservesKeyAndPayload() {
        MockProducer<String, String> producer = new MockProducer<>(false, new StringSerializer(), new StringSerializer());
        KafkaOutboxDeliveryAdapter adapter = new KafkaOutboxDeliveryAdapter(producer, "online-events", java.time.Clock.systemUTC());
        OutboxEvent event = event();

        DeliveryAttempt attempt = adapter.deliver(event);
        var receipt = attempt.completion().toCompletableFuture();
        assertThat(receipt).isNotDone();
        assertThat(producer.history()).singleElement().satisfies(record -> {
            assertThat(record.key()).isEqualTo("user-42");
            assertThat(record.value()).isEqualTo("{\"eventId\":\"stable-id\"}");
        });

        producer.completeNext();
        assertThat(receipt).isCompletedWithValueMatching(r -> r.acknowledgedAt() != null);
    }

    @Test void cancellationConfirmationWaitsUntilNativeKafkaFutureSettles() {
        MockProducer<String, String> producer = new MockProducer<>(false, new StringSerializer(), new StringSerializer());
        DeliveryAttempt attempt = new KafkaOutboxDeliveryAdapter(producer, "online-events", java.time.Clock.systemUTC())
                .deliver(event());

        var cancellation = attempt.cancel().toCompletableFuture();
        assertThat(cancellation).isNotDone();
        producer.errorNext(new org.apache.kafka.common.errors.TimeoutException("delivery timeout"));
        assertThat(cancellation).isCompleted();
        assertThat(attempt.completion().toCompletableFuture()).isCompletedExceptionally();
    }

    private static OutboxEvent event() {
        return OutboxEvent.pending(UUID.randomUUID(), "user", "42", "rating",
                OutboxDestination.KAFKA_ONLINE, "user-42", "{\"eventId\":\"stable-id\"}", Instant.EPOCH);
    }
}
