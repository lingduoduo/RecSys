package com.recsys.domain.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OutboxEvent(
        UUID eventId,
        String aggregateType,
        String aggregateId,
        String eventType,
        OutboxDestination destination,
        String partitionKey,
        String payload,
        OutboxStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        String leaseOwner,
        Instant leaseExpiresAt,
        Instant brokerAcknowledgedAt,
        String lastError,
        long version,
        Instant createdAt) {

    public OutboxEvent {
        Objects.requireNonNull(eventId, "eventId");
        requireText(aggregateType, "aggregateType");
        requireText(aggregateId, "aggregateId");
        requireText(eventType, "eventType");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        Objects.requireNonNull(createdAt, "createdAt");
        if (destination == OutboxDestination.KAFKA_ONLINE) requireText(partitionKey, "partitionKey");
        if (attemptCount < 0 || version < 0) throw new IllegalArgumentException("counts cannot be negative");
    }

    public static OutboxEvent pending(UUID eventId, String aggregateType, String aggregateId,
                                      String eventType, OutboxDestination destination,
                                      String partitionKey, String payload, Instant createdAt) {
        return new OutboxEvent(eventId, aggregateType, aggregateId, eventType, destination,
                partitionKey, payload, OutboxStatus.PENDING, 0, createdAt, null, null,
                null, null, 0, createdAt);
    }

    public OutboxEvent withPayload(String newPayload) {
        return new OutboxEvent(eventId, aggregateType, aggregateId, eventType, destination,
                partitionKey, newPayload, status, attemptCount, nextAttemptAt, leaseOwner,
                leaseExpiresAt, brokerAcknowledgedAt, lastError, version, createdAt);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
