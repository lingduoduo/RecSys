package com.recsys.application.outbox;

import com.recsys.domain.outbox.OutboxEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository {
    default OutboxEvent enqueue(OutboxEvent event) {
        throw new UnsupportedOperationException("enqueue is not supported");
    }
    List<OutboxEvent> claimBatch(String worker, Instant now, int limit, Duration leaseDuration);
    boolean markDelivered(UUID eventId, long version, String leaseOwner, Instant acknowledgedAt);
    boolean reschedule(UUID eventId, long version, String leaseOwner, Instant nextAttemptAt, String error);
    boolean markDead(UUID eventId, long version, String leaseOwner, String error);
}
