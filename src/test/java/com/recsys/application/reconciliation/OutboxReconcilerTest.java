package com.recsys.application.reconciliation;

import com.recsys.application.consistency.ConsistencyWaiter;
import com.recsys.application.outbox.OutboxDeliveryAdapter;
import com.recsys.application.outbox.OutboxRepository;
import com.recsys.domain.outbox.OutboxDestination;
import com.recsys.domain.outbox.OutboxEvent;
import com.recsys.domain.outbox.OutboxStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class OutboxReconcilerTest {
    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");
    private final Instant from = NOW.minus(Duration.ofHours(24));
    private final Instant to = NOW;

    @Test void republishesDeliveredEventMissingFromLineage() {
        OutboxEvent event = delivered("11111111-1111-1111-1111-111111111111", 42);
        FakeRepository repository = new FakeRepository(event);
        FakeLineage lineage = new FakeLineage();
        lineage.missing(event.eventId());
        OutboxDeliveryAdapter adapter = mock(OutboxDeliveryAdapter.class);
        OutboxReconciler reconciler = reconciler(repository, lineage, adapter, "worker-a");

        assertThat(reconciler.reconcile(from, to, 100, true).republished()).isEqualTo(1);
        verify(adapter).deliver(event);
    }

    @Test void skipsPresentAndDeadEvents() {
        OutboxEvent present = delivered("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 1);
        OutboxEvent dead = dead("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", 2);
        FakeRepository repository = new FakeRepository(present, dead);
        FakeLineage lineage = new FakeLineage();
        lineage.present(present.eventId());
        OutboxDeliveryAdapter adapter = mock(OutboxDeliveryAdapter.class);
        OutboxReconciler reconciler = reconciler(repository, lineage, adapter, "worker-a");

        assertThat(reconciler.reconcile(from, to, 100, true).republished()).isZero();
        verifyNoInteractions(adapter);
    }

    @Test void reportOnlyCountsMissingWithoutRepublishing() {
        OutboxEvent event = delivered("cccccccc-cccc-cccc-cccc-cccccccccccc", 7);
        FakeRepository repository = new FakeRepository(event);
        FakeLineage lineage = new FakeLineage();
        lineage.missing(event.eventId());
        OutboxDeliveryAdapter adapter = mock(OutboxDeliveryAdapter.class);
        OutboxReconciler reconciler = reconciler(repository, lineage, adapter, "worker-a");

        assertThat(reconciler.reconcile(from, to, 100, false).missing()).isEqualTo(1);
        verifyNoInteractions(adapter);
    }

    @Test void overlappingLeasePreventsDuplicateRepair() {
        OutboxEvent event = delivered("dddddddd-dddd-dddd-dddd-dddddddddddd", 9);
        FakeRepository repository = new FakeRepository(event);
        repository.reconciliationLeaseHeldBy("worker-a", event.eventId());
        FakeLineage lineage = new FakeLineage();
        lineage.missing(event.eventId());
        OutboxDeliveryAdapter adapter = mock(OutboxDeliveryAdapter.class);
        OutboxReconciler workerB = reconciler(repository, lineage, adapter, "worker-b");

        assertThat(workerB.reconcile(from, to, 100, true).republished()).isZero();
        verifyNoInteractions(adapter);
    }

    private OutboxReconciler reconciler(OutboxRepository repository, ConsistencyWaiter.LineageReader lineage,
                                        OutboxDeliveryAdapter adapter, String worker) {
        return new OutboxReconciler(repository, lineage, adapter, null, worker,
                Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OutboxEvent delivered(String id, int userId) {
        return event(id, userId, OutboxStatus.DELIVERED);
    }

    private static OutboxEvent dead(String id, int userId) {
        return event(id, userId, OutboxStatus.DEAD);
    }

    private static OutboxEvent event(String id, int userId, OutboxStatus status) {
        return new OutboxEvent(UUID.fromString(id), "online_user", Integer.toString(userId),
                "ONLINE_INTERACTION", OutboxDestination.KAFKA_ONLINE, Integer.toString(userId),
                "{}", status, 1, NOW, null, null, NOW, null, 2, NOW);
    }

    private static final class FakeRepository implements OutboxRepository {
        private final List<OutboxEvent> events;
        private final Map<UUID, String> leases = new HashMap<>();

        FakeRepository(OutboxEvent... events) {
            this.events = List.of(events);
        }

        void reconciliationLeaseHeldBy(String worker, UUID eventId) {
            leases.put(eventId, worker);
        }

        @Override public List<OutboxEvent> scanDelivered(Instant from, Instant to, int limit) {
            return events;
        }

        @Override public boolean claimReconciliationLease(UUID eventId, String worker, Instant now, Duration leaseDuration) {
            String holder = leases.get(eventId);
            if (holder != null && !holder.equals(worker)) return false;
            leases.put(eventId, worker);
            return true;
        }

        @Override public List<OutboxEvent> claimBatch(String worker, Instant now, int limit, Duration leaseDuration) {
            throw new UnsupportedOperationException();
        }

        @Override public boolean markDelivered(UUID eventId, long version, String leaseOwner, Instant acknowledgedAt) {
            throw new UnsupportedOperationException();
        }

        @Override public boolean reschedule(UUID eventId, long version, String leaseOwner, Instant nextAttemptAt, String error) {
            throw new UnsupportedOperationException();
        }

        @Override public boolean markDead(UUID eventId, long version, String leaseOwner, String error) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeLineage implements ConsistencyWaiter.LineageReader {
        private final Set<UUID> present = new HashSet<>();

        void present(UUID eventId) { present.add(eventId); }

        void missing(UUID eventId) { present.remove(eventId); }

        @Override public boolean contains(UUID eventId, int userId, Duration remaining) {
            return present.contains(eventId);
        }
    }
}
