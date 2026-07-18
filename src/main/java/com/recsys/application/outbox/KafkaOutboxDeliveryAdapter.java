package com.recsys.application.outbox;

import com.recsys.domain.outbox.OutboxEvent;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public final class KafkaOutboxDeliveryAdapter implements OutboxDeliveryAdapter, AutoCloseable {
    private final Producer<String, String> producer;
    private final String topic;
    private final Clock clock;
    private final Duration deliveryDeadline;

    public KafkaOutboxDeliveryAdapter(String bootstrapServers, String topic, Duration deliveryDeadline) {
        this(new KafkaProducer<>(producerProps(bootstrapServers, deliveryDeadline)), topic, Clock.systemUTC(), deliveryDeadline);
    }

    KafkaOutboxDeliveryAdapter(Producer<String, String> producer, String topic, Clock clock, Duration deliveryDeadline) {
        this.producer = Objects.requireNonNull(producer, "producer");
        this.topic = requireText(topic, "topic");
        this.clock = Objects.requireNonNull(clock, "clock");
        deadlineMillis(deliveryDeadline);
        this.deliveryDeadline = deliveryDeadline;
    }

    public static Properties producerProps(String bootstrapServers, Duration deliveryDeadline) {
        int deadlineMillis = deadlineMillis(deliveryDeadline);
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, requireText(bootstrapServers, "bootstrapServers"));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, deadlineMillis);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, deadlineMillis);
        return props;
    }

    @Override public DeliveryAttempt deliver(OutboxEvent event) {
        Objects.requireNonNull(event, "event");
        CompletableFuture<DeliveryReceipt> result = new CompletableFuture<>();
        Future<RecordMetadata> nativeFuture;
        try {
            nativeFuture = producer.send(new ProducerRecord<>(topic, event.partitionKey(), event.payload()), (metadata, error) -> {
                if (error != null) result.completeExceptionally(error);
                else {
                    long timestamp = metadata == null ? -1 : metadata.timestamp();
                    result.complete(new DeliveryReceipt(timestamp >= 0 ? Instant.ofEpochMilli(timestamp) : clock.instant()));
                }
            });
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
            return new DeliveryAttempt(result, () -> CompletableFuture.completedFuture(null));
        }
        Future<RecordMetadata> sendFuture = nativeFuture;
        return new DeliveryAttempt(result, () -> {
            CompletableFuture<Void> settled = new CompletableFuture<>();
            if (sendFuture.cancel(false)) {
                result.cancel(false);
                settled.complete(null);
            } else if (sendFuture.isDone()) {
                settled.complete(null);
            } else {
                result.whenComplete((ignored, failure) -> settled.complete(null));
            }
            return settled;
        });
    }

    @Override public void close() { producer.close(Duration.ofSeconds(5)); }
    @Override public Optional<Duration> deliveryDeadline() { return Optional.of(deliveryDeadline); }
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
    private static int deadlineMillis(Duration deadline) {
        Objects.requireNonNull(deadline, "deliveryDeadline");
        if (deadline.isZero() || deadline.isNegative() || deadline.getNano() % 1_000_000 != 0
                || deadline.toMillis() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("deliveryDeadline must be a positive whole number of milliseconds <= Integer.MAX_VALUE");
        return Math.toIntExact(deadline.toMillis());
    }
}
