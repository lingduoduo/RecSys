package com.recsys.application.outbox;

import com.recsys.domain.outbox.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxRelayTest {
    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

    @Test void marksDeliveredOnlyAfterBrokerAcknowledgementUsingClaimOwner() throws Exception {
        FakeRepository repository = new FakeRepository(claimed(1));
        CompletableFuture<DeliveryReceipt> acknowledgement = new CompletableFuture<>();
        OutboxRelay relay = relay(repository, event -> attempt(acknowledgement));

        assertThat(relay.runOnce()).isEqualTo(1);
        assertThat(repository.status).isEqualTo(OutboxStatus.IN_FLIGHT);
        acknowledgement.complete(new DeliveryReceipt(NOW.plusSeconds(3)));
        repository.awaitTerminal();

        assertThat(repository.status).isEqualTo(OutboxStatus.DELIVERED);
        assertThat(repository.terminalOwner).isEqualTo("claim-owner");
        assertThat(repository.terminalVersion).isEqualTo(9);
        assertThat(repository.acknowledgedAt).isEqualTo(NOW.plusSeconds(3));
    }

    @Test void reschedulesFailureAndDeadLettersAtCeiling() throws Exception {
        FakeRepository retryRepository = new FakeRepository(claimed(1));
        OutboxDeliveryAdapter failed = event -> attempt(CompletableFuture.failedFuture(new IOException("broker unavailable")));
        relay(retryRepository, failed).runOnce();
        retryRepository.awaitTerminal();
        assertThat(retryRepository.status).isEqualTo(OutboxStatus.PENDING);
        assertThat(retryRepository.terminalOwner).isEqualTo("claim-owner");
        assertThat(retryRepository.error).contains("broker unavailable");

        FakeRepository deadRepository = new FakeRepository(claimed(3));
        relay(deadRepository, failed).runOnce();
        deadRepository.awaitTerminal();
        assertThat(deadRepository.status).isEqualTo(OutboxStatus.DEAD);
        assertThat(deadRepository.terminalOwner).isEqualTo("claim-owner");
    }

    @Test void dispatchesByDestinationAndBoundsClaimsToConcurrency() {
        FakeRepository repository = new FakeRepository(claimed(1));
        OutboxDeliveryAdapter kafka = event -> attempt(CompletableFuture.completedFuture(new DeliveryReceipt(NOW)));
        OutboxRelay relay = new OutboxRelay(repository, Map.of(OutboxDestination.KAFKA_ONLINE, kafka), retryPolicy(),
                "configured-worker", Clock.fixed(NOW, ZoneOffset.UTC), 10, Duration.ofSeconds(30),
                Duration.ofSeconds(2), 2);

        relay.runOnce();
        assertThat(repository.claimLimit).isEqualTo(2);
    }

    @Test void rejectsAdapterDeadlineThatDiffersFromRelayDeadline() {
        FakeRepository repository = new FakeRepository(claimed(1));
        OutboxDeliveryAdapter adapter = new OutboxDeliveryAdapter() {
            @Override public DeliveryAttempt deliver(OutboxEvent event) {
                return attempt(CompletableFuture.completedFuture(new DeliveryReceipt(NOW)));
            }
            @Override public Optional<Duration> deliveryDeadline() { return Optional.of(Duration.ofSeconds(3)); }
        };
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() ->
                new OutboxRelay(repository, Map.of(OutboxDestination.KAFKA_ONLINE, adapter), retryPolicy(),
                        "configured-worker", Clock.fixed(NOW, ZoneOffset.UTC), 10, Duration.ofSeconds(30),
                        Duration.ofSeconds(2), 2)).withMessageContaining("deadline");
    }

    @Test void doesNotClaimAnotherBatchWhileSendCapacityIsExhausted() {
        FakeRepository repository = new FakeRepository(claimed(1));
        CompletableFuture<DeliveryReceipt> pending = new CompletableFuture<>();
        OutboxRelay relay = new OutboxRelay(repository,
                Map.of(OutboxDestination.KAFKA_ONLINE, event -> attempt(pending)), retryPolicy(),
                "configured-worker", Clock.fixed(NOW, ZoneOffset.UTC), 10, Duration.ofSeconds(30),
                Duration.ofSeconds(2), 1);

        assertThat(relay.runOnce()).isEqualTo(1);
        assertThat(relay.runOnce()).isZero();
        assertThat(repository.claimCalls).isEqualTo(1);
    }

    @Test void deadlineWaitsForConfirmedCancellationBeforeRetryAndCapacityRelease() throws Exception {
        FakeRepository repository = new FakeRepository(claimed(1));
        CompletableFuture<DeliveryReceipt> pending = new CompletableFuture<>();
        CompletableFuture<Void> cancelled = new CompletableFuture<>();
        AtomicInteger sends = new AtomicInteger();
        try (OutboxRelay relay = new OutboxRelay(repository,
                Map.of(OutboxDestination.KAFKA_ONLINE, event -> {
                    sends.incrementAndGet();
                    return new DeliveryAttempt(pending, () -> cancelled);
                }),
                retryPolicy(), "configured-worker", Clock.fixed(NOW, ZoneOffset.UTC), 10,
                Duration.ofSeconds(30), Duration.ofMillis(20), 1)) {
            assertThat(relay.runOnce()).isEqualTo(1);
            Thread.sleep(60);
            assertThat(relay.runOnce()).isZero();
            assertThat(sends).hasValue(1);
            assertThat(repository.status).isEqualTo(OutboxStatus.IN_FLIGHT);

            cancelled.complete(null);
            repository.awaitTerminal();
            assertThat(repository.status).isEqualTo(OutboxStatus.PENDING);
        }
    }

    @Test void neverCompletingDeliveryIsBoundedByDeadlineCancellation() throws Exception {
        FakeRepository repository = new FakeRepository(claimed(1));
        CompletableFuture<DeliveryReceipt> never = new CompletableFuture<>();
        AtomicInteger cancellations = new AtomicInteger();
        try (OutboxRelay relay = new OutboxRelay(repository,
                Map.of(OutboxDestination.KAFKA_ONLINE, event -> new DeliveryAttempt(never, () -> {
                    cancellations.incrementAndGet();
                    never.cancel(false);
                    return CompletableFuture.completedFuture(null);
                })), retryPolicy(), "configured-worker", Clock.fixed(NOW, ZoneOffset.UTC), 10,
                Duration.ofSeconds(30), Duration.ofMillis(20), 1)) {
            relay.runOnce();
            repository.awaitTerminal();
            assertThat(cancellations).hasValue(1);
            assertThat(repository.status).isEqualTo(OutboxStatus.PENDING);
            assertThat(relay.runOnce()).isEqualTo(1);
        }
    }

    @Test void lateCallbackRacingCancellationTransitionsExactlyOnce() throws Exception {
        FakeRepository repository = new FakeRepository(claimed(1));
        CompletableFuture<DeliveryReceipt> completion = new CompletableFuture<>();
        CompletableFuture<Void> cancellation = new CompletableFuture<>();
        try (OutboxRelay relay = new OutboxRelay(repository,
                Map.of(OutboxDestination.KAFKA_ONLINE,
                        event -> new DeliveryAttempt(completion, () -> cancellation)),
                retryPolicy(), "configured-worker", Clock.fixed(NOW, ZoneOffset.UTC), 10,
                Duration.ofSeconds(30), Duration.ofMillis(20), 1)) {
            relay.runOnce();
            Thread.sleep(50);
            cancellation.complete(null);
            completion.complete(new DeliveryReceipt(NOW));
            repository.awaitTerminal();
            Thread.sleep(30);
            assertThat(repository.terminalInvocations).isEqualTo(1);
        }
    }

    @Test void terminalRepositoryIoNeverRunsOnBrokerCallbackThread() throws Exception {
        FakeRepository repository = new FakeRepository(claimed(1));
        CompletableFuture<DeliveryReceipt> pending = new CompletableFuture<>();
        try (OutboxRelay relay = relay(repository, event -> attempt(pending))) {
            relay.runOnce();
            Thread broker = new Thread(() -> pending.complete(new DeliveryReceipt(NOW)), "kafka-callback");
            broker.start();
            broker.join();
            repository.awaitTerminal();
            assertThat(repository.terminalThread).startsWith("outbox-relay-").isNotEqualTo("kafka-callback");
        }
    }

    @Test void terminalRepositoryFailuresAreReported() throws Exception {
        FakeRepository repository = new FakeRepository(claimed(1));
        repository.terminalFailure = new IllegalStateException("database offline");
        AtomicReference<Throwable> observed = new AtomicReference<>();
        try (OutboxRelay relay = new OutboxRelay(repository,
                Map.of(OutboxDestination.KAFKA_ONLINE, (OutboxDeliveryAdapter)
                        event -> attempt(CompletableFuture.completedFuture(new DeliveryReceipt(NOW)))),
                retryPolicy(), "configured-worker", Clock.fixed(NOW, ZoneOffset.UTC), 10,
                Duration.ofSeconds(30), Duration.ofSeconds(1), 1, observed::set)) {
            relay.runOnce();
            repository.awaitTerminal();
            assertThat(observed.get()).isSameAs(repository.terminalFailure);
        }
    }

    @Test void rejectedTerminalTransitionIsObservedWithoutOverwritingNewerOwner() throws Exception {
        FakeRepository repository = new FakeRepository(claimed(1));
        repository.transitionAccepted = false;
        AtomicReference<Throwable> observed = new AtomicReference<>();
        CountDownLatch observation = new CountDownLatch(1);
        try (OutboxRelay relay = new OutboxRelay(repository,
                Map.of(OutboxDestination.KAFKA_ONLINE, event ->
                        attempt(CompletableFuture.completedFuture(new DeliveryReceipt(NOW)))),
                retryPolicy(), "configured-worker", Clock.fixed(NOW, ZoneOffset.UTC), 10,
                Duration.ofSeconds(30), Duration.ofSeconds(1), 1, failure -> {
                    observed.set(failure);
                    observation.countDown();
                })) {
            relay.runOnce();
            repository.awaitTerminal();
            assertThat(observation.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(repository.status).isEqualTo(OutboxStatus.IN_FLIGHT);
            assertThat(observed.get()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("transition conflict");
        }
    }

    @Test void synchronousAdapterAndTransitionFailuresDoNotAbortRemainingClaimedEvents() throws Exception {
        OutboxEvent first = claimed(1);
        OutboxEvent second = claimed(1);
        FakeRepository repository = new FakeRepository(List.of(first, second));
        repository.failFirstTerminal = true;
        AtomicInteger deliveries = new AtomicInteger();
        try (OutboxRelay relay = new OutboxRelay(repository,
                Map.of(OutboxDestination.KAFKA_ONLINE, (OutboxDeliveryAdapter) event -> {
                    if (deliveries.getAndIncrement() == 0) throw new IllegalStateException("sync send failure");
                    return attempt(CompletableFuture.completedFuture(new DeliveryReceipt(NOW)));
                }), retryPolicy(), "configured-worker", Clock.fixed(NOW, ZoneOffset.UTC), 10,
                Duration.ofSeconds(30), Duration.ofSeconds(1), 2, ignored -> {})) {
            assertThat(relay.runOnce()).isEqualTo(2);
            repository.awaitTerminals(2);
            assertThat(deliveries).hasValue(2);
            assertThat(repository.terminalSuccesses).isEqualTo(1);
        }
    }

    @Test void closeStopsClaimsAndDrainsCompletedTerminalWork() throws Exception {
        FakeRepository repository = new FakeRepository(claimed(1));
        CompletableFuture<DeliveryReceipt> pending = new CompletableFuture<>();
        OutboxRelay relay = relay(repository, event -> attempt(pending));
        relay.runOnce();
        pending.complete(new DeliveryReceipt(NOW));
        relay.close();
        assertThat(repository.status).isEqualTo(OutboxStatus.DELIVERED);
        assertThat(relay.runOnce()).isZero();
    }

    private static OutboxRelay relay(FakeRepository repository, OutboxDeliveryAdapter adapter) {
        return new OutboxRelay(repository, Map.of(OutboxDestination.KAFKA_ONLINE, adapter), retryPolicy(),
                "configured-worker", Clock.fixed(NOW, ZoneOffset.UTC), 10, Duration.ofSeconds(30),
                Duration.ofSeconds(2), 4);
    }

    private static OutboxRetryPolicy retryPolicy() {
        return new OutboxRetryPolicy(Duration.ofSeconds(5), Duration.ofMinutes(1), 3, () -> .5);
    }

    private static DeliveryAttempt attempt(CompletableFuture<DeliveryReceipt> completion) {
        return new DeliveryAttempt(completion, () -> {
            completion.cancel(false);
            return CompletableFuture.completedFuture(null);
        });
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

        private final CountDownLatch terminals;
        private String terminalThread;
        private RuntimeException terminalFailure;
        private boolean failFirstTerminal;
        private int terminalSuccesses;
        private int terminalInvocations;
        private boolean transitionAccepted = true;

        private FakeRepository(OutboxEvent event) { this(List.of(event)); }
        private FakeRepository(List<OutboxEvent> events) {
            this.claimed = events;
            this.terminals = new CountDownLatch(events.size());
        }
        @Override public List<OutboxEvent> claimBatch(String worker, Instant now, int limit, Duration lease) {
            claimCalls++;
            claimLimit = limit;
            return claimed;
        }
        @Override public boolean markDelivered(UUID id, long version, String owner, Instant at) {
            try { beforeTerminal(); if (!transitionAccepted) return false; capture(version, owner); acknowledgedAt = at; status = OutboxStatus.DELIVERED; terminalSuccesses++; return true; }
            finally { terminals.countDown(); }
        }
        @Override public boolean reschedule(UUID id, long version, String owner, Instant next, String message) {
            try { beforeTerminal(); if (!transitionAccepted) return false; capture(version, owner); error = message; status = OutboxStatus.PENDING; terminalSuccesses++; return true; }
            finally { terminals.countDown(); }
        }
        @Override public boolean markDead(UUID id, long version, String owner, String message) {
            try { beforeTerminal(); if (!transitionAccepted) return false; capture(version, owner); error = message; status = OutboxStatus.DEAD; terminalSuccesses++; return true; }
            finally { terminals.countDown(); }
        }
        private synchronized void beforeTerminal() {
            terminalInvocations++;
            terminalThread = Thread.currentThread().getName();
            if (failFirstTerminal) { failFirstTerminal = false; throw new IllegalStateException("first transition failed"); }
            if (terminalFailure != null) throw terminalFailure;
        }
        private void capture(long version, String owner) { terminalVersion = version; terminalOwner = owner; }
        private void awaitTerminal() throws InterruptedException { awaitTerminals(1); }
        private void awaitTerminals(int count) throws InterruptedException {
            assertThat(terminals.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }
}
