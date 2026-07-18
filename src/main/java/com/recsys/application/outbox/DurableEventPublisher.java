package com.recsys.application.outbox;

import com.recsys.domain.outbox.OutboxDestination;
import com.recsys.domain.outbox.OutboxEvent;
import com.recsys.infrastructure.outbox.OutboxConflictException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Synchronously commits online events to the outbox before acknowledging the API call. */
public class DurableEventPublisher {
    private final OutboxRepository repository;
    private final Clock clock;

    public DurableEventPublisher(OutboxRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DurableEventPublisher(OutboxRepository repository) {
        this(repository, Clock.systemUTC());
    }

    public Acceptance publishOnline(UUID eventId, int userId, String eventType, String payload) {
        Objects.requireNonNull(eventId, "eventId");
        if (userId < 0) throw new IllegalArgumentException("userId cannot be negative");
        Instant now = clock.instant();
        OutboxEvent event = OutboxEvent.pending(eventId, "user", Integer.toString(userId), eventType,
                OutboxDestination.KAFKA_ONLINE, "user-" + userId, payload, now);
        try {
            OutboxEvent stored = repository.enqueue(event);
            return new Acceptance(stored.eventId(), stored.createdAt());
        } catch (OutboxConflictException conflict) {
            throw conflict;
        } catch (RuntimeException failure) {
            throw new PersistenceException(failure);
        }
    }

    public record Acceptance(UUID eventId, Instant acceptedAt) {
        public Acceptance {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
        }
    }

    public static final class PersistenceException extends RuntimeException {
        public PersistenceException(Throwable cause) { super("Unable to durably accept event", cause); }
    }
}
