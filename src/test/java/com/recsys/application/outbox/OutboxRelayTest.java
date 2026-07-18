package com.recsys.application.outbox;

import com.recsys.domain.outbox.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxRelayTest {
    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

    @Test void marksDeliveredOnlyAfterBrokerAcknowledgementUsingClaimOwner() {
        FakeRepository repository = new FakeRepository(claimed(1));
        CompletableFuture<DeliveryReceipt> acknowledgement = new CompletableFuture<>();
        OutboxRelay relay = relay(repository, event -> acknowledgement);

        assertThat(relay.runOnce()).isEqualTo(1);
        assertThat(repository.status).isEqualTo(OutboxStatus.IN_FLIGHT);
        acknowledgement.complete(new DeliveryReceipt(NOW.plusSeconds(3)));

        assertThat(repository.status).isEqualTo(OutboxStatus.DELIVERED);
        assertThat(repository.terminalOwner).isEqualTo("claim-owner");
        assertThat(repository.terminalVersion).isEqualTo(9);
        assertThat(repository.acknowledgedAt).isEqualTo(NOW.plusSeconds(3));
    }

    @Test void reschedulesFailureAndDeadLettersAtCeiling() {
        FakeRepository retryRepository = new FakeRepository(claimed(1));
        OutboxDeliveryAdapter failed = event -> CompletableFuture.failedFuture(new IOException("broker unavailable"));
        relay(retryRepository, failed).runOnce();
        assertThat(retryRepository.status).isEqualTo(OutboxStatus.PENDING);
        assertThat(retryRepository.terminalOwner).isEqualTo("claim-owner");
        assertThat(retryRepository.error).contains("broker unavailable");

        FakeRepository deadRepository = new FakeRepository(claimed(3));
        relay(deadRepository, failed).runOnce();
        assertThat(deadRepository.status).isEqualTo(OutboxStatus.DEAD);
        assertThat(deadRepository.terminalOwner).isEqualTo("claim-owner");
    }

    @Test void dispatchesByDestinationAndBoundsClaimsToConcurrency() {
        FakeRepository repository = new FakeRepository(claimed(1));
        OutboxDeliveryAdapter kafka = event -> CompletableFuture.completedFuture(new DeliveryReceipt(NOW));
        OutboxRelay relay = new OutboxRelay(repository, Map.of(OutboxDestination.KAFKA_ONLINE, kafka), retryPolicy(),
                "configured-worker", Clock.fixed(NOW, ZoneOffset.UTC), 10, Duration.ofSeconds(30),
                Duration.ofSeconds(2), 2);

        relay.runOnce();
        assertThat(repository.claimLimit).isEqualTo(2);
    }

    @Test void doesNotClaimAnotherBatchWhileSendCapacityIsExhausted() {
        FakeRepository repository = new FakeRepository(claimed(1));
        CompletableFuture<DeliveryReceipt> pending = new CompletableFuture<>();
        OutboxRelay relay = new OutboxRelay(repository,
                Map.of(OutboxDestination.KAFKA_ONLINE, event -> pending), retryPolicy(),
                "configured-worker", Clock.fixed(NOW, ZoneOffset.UTC), 10, Duration.ofSeconds(30),
                Duration.ofSeconds(2), 1);

        assertThat(relay.runOnce()).isEqualTo(1);
        assertThat(relay.runOnce()).isZero();
        assertThat(repository.claimCalls).isEqualTo(1);
    }

    private static OutboxRelay relay(FakeRepository repository, OutboxDeliveryAdapter adapter) {
        return new OutboxRelay(repository, Map.of(OutboxDestination.KAFKA_ONLINE, adapter), retryPolicy(),
                "configured-worker", Clock.fixed(NOW, ZoneOffset.UTC), 10, Duration.ofSeconds(30),
                Duration.ofSeconds(2), 4);
    }

    private static OutboxRetryPolicy retryPolicy() {
        return new OutboxRetryPolicy(Duration.ofSeconds(5), Duration.ofMinutes(1), 3, () -> .5);
    }

    private static OutboxEvent claimed(int attempts) {
        return new OutboxEvent(UUID.randomUUID(), "user", "42", "rating", OutboxDestination.KAFKA_ONLINE,
                "user-42", "{}", OutboxStatus.IN_FLIGHT, attempts, NOW, "claim-owner",
                NOW.plusSeconds(30), null, null, 9, NOW.minusSeconds(1));
    }

    private static final class FakeRepository implements OutboxRepository {
        private final List<OutboxEvent> claimed;
        private OutboxStatus status = OutboxStatus.IN_FLIGHT;
        private String terminalOwner;
        private long terminalVersion;
        private Instant acknowledgedAt;
        private String error;
        private int claimLimit;
        private int claimCalls;

        private FakeRepository(OutboxEvent event) { this.claimed = List.of(event); }
        @Override public List<OutboxEvent> claimBatch(String worker, Instant now, int limit, Duration lease) {
            claimCalls++;
            claimLimit = limit;
            return claimed;
        }
        @Override public boolean markDelivered(UUID id, long version, String owner, Instant at) {
            capture(version, owner); acknowledgedAt = at; status = OutboxStatus.DELIVERED; return true;
        }
        @Override public boolean reschedule(UUID id, long version, String owner, Instant next, String message) {
            capture(version, owner); error = message; status = OutboxStatus.PENDING; return true;
        }
        @Override public boolean markDead(UUID id, long version, String owner, String message) {
            capture(version, owner); error = message; status = OutboxStatus.DEAD; return true;
        }
        private void capture(long version, String owner) { terminalVersion = version; terminalOwner = owner; }
    }
}
