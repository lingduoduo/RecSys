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

    public KafkaOutboxDeliveryAdapter(String bootstrapServers, String topic) {
        this(new KafkaProducer<>(producerProps(bootstrapServers)), topic, Clock.systemUTC());
    }

    KafkaOutboxDeliveryAdapter(Producer<String, String> producer, String topic, Clock clock) {
        this.producer = Objects.requireNonNull(producer, "producer");
        this.topic = requireText(topic, "topic");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static Properties producerProps(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, requireText(bootstrapServers, "bootstrapServers"));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30_000);
        return props;
    }

    @Override public CompletionStage<DeliveryReceipt> deliver(OutboxEvent event) {
        Objects.requireNonNull(event, "event");
        CompletableFuture<DeliveryReceipt> result = new CompletableFuture<>();
        try {
            producer.send(new ProducerRecord<>(topic, event.partitionKey(), event.payload()), (metadata, error) -> {
                if (error != null) result.completeExceptionally(error);
                else {
                    long timestamp = metadata == null ? -1 : metadata.timestamp();
                    result.complete(new DeliveryReceipt(timestamp >= 0 ? Instant.ofEpochMilli(timestamp) : clock.instant()));
                }
            });
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    @Override public void close() { producer.close(Duration.ofSeconds(5)); }
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
