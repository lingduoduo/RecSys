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
    /** PENDING plus expired IN_FLIGHT rows that are eligible for retry. */
    default long countRetryableBacklog(Instant now) { return 0; }

    /** Delivered rows acknowledged in {@code [from, to)}, oldest first, for reconciliation scans. */
    default List<OutboxEvent> scanDelivered(Instant from, Instant to, int limit) {
        throw new UnsupportedOperationException("scanDelivered is not supported");
    }

    /**
     * Claims a short reconciliation lease on a delivered row so overlapping reconciliation runs
     * cannot republish the same event concurrently. Returns {@code false} when another worker
     * holds an unexpired lease.
     */
    default boolean claimReconciliationLease(UUID eventId, String worker, Instant now, Duration leaseDuration) {
        return false;
    }

    /** Releases a reconciliation lease this worker holds after the event's lineage is confirmed. */
    default boolean releaseReconciliationLease(UUID eventId, String worker) {
        return false;
    }
}
