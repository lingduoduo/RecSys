package com.recsys.application.reconciliation;

import com.recsys.application.consistency.ConsistencyWaiter;
import com.recsys.application.outbox.OutboxDeliveryAdapter;
import com.recsys.application.outbox.OutboxRepository;
import com.recsys.domain.outbox.OutboxDestination;
import com.recsys.domain.outbox.OutboxEvent;
import com.recsys.domain.outbox.OutboxStatus;
import com.recsys.metrics.ConsistencyMetrics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Compares delivered {@code KAFKA_ONLINE} outbox rows against primary Redis lineage and, when
 * repair is enabled, republishes the original event for any row whose lineage is missing.
 *
 * <p>The reconciler is bounded (batch ceiling and a caller-supplied window), never retries
 * {@code DEAD} rows, and takes a per-event database lease so overlapping runs cannot republish the
 * same event concurrently.
 */
public final class OutboxReconciler {
    /** Hard cap on the batch a single pass will scan, regardless of the requested size. */
    public static final int MAX_BATCH_CEILING = 5_000;
    /** Bounded budget for each primary lineage lookup. */
    private static final Duration LINEAGE_LOOKUP_BUDGET = Duration.ofSeconds(2);

    private final OutboxRepository repository;
    private final ConsistencyWaiter.LineageReader lineage;
    private final OutboxDeliveryAdapter adapter;
    private final ConsistencyMetrics metrics;
    private final String worker;
    private final Duration leaseDuration;
    private final Clock clock;

    public OutboxReconciler(OutboxRepository repository, ConsistencyWaiter.LineageReader lineage,
                            OutboxDeliveryAdapter adapter, ConsistencyMetrics metrics,
                            String worker, Duration leaseDuration, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.lineage = Objects.requireNonNull(lineage, "lineage");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.metrics = metrics;
        this.worker = requireText(worker, "worker");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ReconciliationResult reconcile(Instant from, Instant to, int maxBatch, boolean repair) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!from.isBefore(to)) throw new IllegalArgumentException("from must be before to");
        int batch = Math.min(Math.max(1, maxBatch), MAX_BATCH_CEILING);

        List<OutboxEvent> events = repository.scanDelivered(from, to, batch);
        Instant now = clock.instant();
        int scanned = 0, missing = 0, republished = 0, repaired = 0, failed = 0;
        for (OutboxEvent event : events) {
            scanned++;
            record(ConsistencyMetrics.ReconciliationOutcome.SCANNED);
            if (event.destination() != OutboxDestination.KAFKA_ONLINE) continue;
            if (event.status() == OutboxStatus.DEAD) continue;   // never auto-repair dead rows
            Integer userId = onlineUserId(event.aggregateId());
            if (userId == null) continue;

            boolean applied = lineage.contains(event.eventId(), userId, LINEAGE_LOOKUP_BUDGET);
            if (applied) {
                if (event.leaseOwner() != null) {   // a prior pass republished it; confirm the repair now
                    repaired++;
                    record(ConsistencyMetrics.ReconciliationOutcome.REPAIRED);
                    repository.releaseReconciliationLease(event.eventId(), event.leaseOwner());
                }
                continue;
            }

            missing++;
            record(ConsistencyMetrics.ReconciliationOutcome.MISSING);
            if (!repair) continue;
            if (!repository.claimReconciliationLease(event.eventId(), worker, now, leaseDuration)) continue;
            try {
                adapter.deliver(event);
                republished++;
                record(ConsistencyMetrics.ReconciliationOutcome.REPUBLISHED);
            } catch (RuntimeException failure) {
                failed++;
                record(ConsistencyMetrics.ReconciliationOutcome.FAILED);
            }
        }
        return new ReconciliationResult(scanned, missing, republished, repaired, failed);
    }

    private static Integer onlineUserId(String aggregateId) {
        try {
            return Integer.valueOf(aggregateId);
        } catch (NumberFormatException notAnOnlineUser) {
            return null;
        }
    }

    private void record(ConsistencyMetrics.ReconciliationOutcome outcome) {
        if (metrics != null) metrics.recordReconciliation(outcome);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
