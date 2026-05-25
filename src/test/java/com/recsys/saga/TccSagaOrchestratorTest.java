package com.recsys.saga;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TccSagaOrchestratorTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-25T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void execute_triesThenConfirmsEveryParticipant() {
        InMemorySagaStateStore store = new InMemorySagaStateStore();
        List<String> calls = new ArrayList<>();
        List<SagaTransitionEvent> events = new ArrayList<>();
        TccSagaOrchestrator orchestrator = new TccSagaOrchestrator(store, events::add, clock);

        SagaInstance result = orchestrator.execute(
                "tcc-1",
                "req-1",
                "{\"userId\":101}",
                definition(),
                Map.of(
                        "reserve-candidate-set", participant(calls),
                        "reserve-feature-refresh", participant(calls)
                )
        );

        assertThat(result.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(result.triedSteps()).containsExactly("reserve-candidate-set", "reserve-feature-refresh");
        assertThat(result.confirmedSteps()).containsExactly("reserve-candidate-set", "reserve-feature-refresh");
        assertThat(result.cancelledSteps()).isEmpty();
        assertThat(calls).containsExactly(
                "try:reserve-candidate-set",
                "try:reserve-feature-refresh",
                "confirm:reserve-candidate-set",
                "confirm:reserve-feature-refresh"
        );
        assertThat(events).extracting(SagaTransitionEvent::type)
                .contains(SagaEventType.TRY_STARTED, SagaEventType.TRY_COMPLETED,
                        SagaEventType.CONFIRM_STARTED, SagaEventType.CONFIRM_COMPLETED,
                        SagaEventType.SAGA_COMPLETED);
    }

    @Test
    void execute_cancelsTriedReservationsWhenLaterTryFails() {
        InMemorySagaStateStore store = new InMemorySagaStateStore();
        List<String> calls = new ArrayList<>();
        TccSagaOrchestrator orchestrator = new TccSagaOrchestrator(store, SagaEventPublisher.NOOP, clock);

        SagaInstance result = orchestrator.execute(
                "tcc-2",
                "req-2",
                "{}",
                definition(),
                Map.of(
                        "reserve-candidate-set", participant(calls),
                        "reserve-feature-refresh", new RecordingParticipant(calls) {
                            @Override
                            public void tryReserve(SagaInstance saga, SagaStep step) {
                                calls.add("try:" + step.name());
                                throw new IllegalStateException("feature store reservation failed");
                            }
                        }
                )
        );

        assertThat(result.status()).isEqualTo(SagaStatus.FAILED);
        assertThat(result.triedSteps()).containsExactly("reserve-candidate-set");
        assertThat(result.confirmedSteps()).isEmpty();
        assertThat(result.cancelledSteps()).containsExactly("reserve-candidate-set");
        assertThat(calls).containsExactly(
                "try:reserve-candidate-set",
                "try:reserve-feature-refresh",
                "cancel:reserve-candidate-set"
        );
    }

    @Test
    void execute_retriesTransientTryFailureBeforeConfirming() {
        InMemorySagaStateStore store = new InMemorySagaStateStore();
        TccSagaOrchestrator orchestrator = new TccSagaOrchestrator(store, SagaEventPublisher.NOOP, clock);
        AtomicInteger tries = new AtomicInteger();
        List<String> calls = new ArrayList<>();
        SagaDefinition definition = new SagaDefinition("recommendation-refresh-tcc", List.of(
                SagaStep.local("reserve-candidate-set").withRetry(3, Duration.ZERO)
        ));

        SagaInstance result = orchestrator.execute(
                "tcc-3",
                "req-3",
                "{}",
                definition,
                Map.of("reserve-candidate-set", new RecordingParticipant(calls) {
                    @Override
                    public void tryReserve(SagaInstance saga, SagaStep step) {
                        calls.add("try:" + step.name());
                        if (tries.incrementAndGet() < 2) {
                            throw new IllegalStateException("temporary capacity race");
                        }
                    }
                })
        );

        assertThat(result.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(tries.get()).isEqualTo(2);
        assertThat(calls).containsExactly(
                "try:reserve-candidate-set",
                "try:reserve-candidate-set",
                "confirm:reserve-candidate-set"
        );
    }

    @Test
    void execute_doesNotCancelAlreadyConfirmedParticipantOnLaterConfirmFailure() {
        InMemorySagaStateStore store = new InMemorySagaStateStore();
        List<String> calls = new ArrayList<>();
        TccSagaOrchestrator orchestrator = new TccSagaOrchestrator(store, SagaEventPublisher.NOOP, clock);

        SagaInstance result = orchestrator.execute(
                "tcc-4",
                "req-4",
                "{}",
                definition(),
                Map.of(
                        "reserve-candidate-set", participant(calls),
                        "reserve-feature-refresh", new RecordingParticipant(calls) {
                            @Override
                            public void confirm(SagaInstance saga, SagaStep step) {
                                calls.add("confirm:" + step.name());
                                throw new IllegalStateException("commit acknowledgement timed out");
                            }
                        }
                )
        );

        assertThat(result.status()).isEqualTo(SagaStatus.FAILED);
        assertThat(result.confirmedSteps()).containsExactly("reserve-candidate-set");
        assertThat(result.cancelledSteps()).containsExactly("reserve-feature-refresh");
        assertThat(calls).containsExactly(
                "try:reserve-candidate-set",
                "try:reserve-feature-refresh",
                "confirm:reserve-candidate-set",
                "confirm:reserve-feature-refresh",
                "cancel:reserve-feature-refresh"
        );
    }

    private static SagaDefinition definition() {
        return new SagaDefinition("recommendation-refresh-tcc", List.of(
                SagaStep.local("reserve-candidate-set"),
                SagaStep.local("reserve-feature-refresh")
        ));
    }

    private static TccParticipant participant(List<String> calls) {
        return new RecordingParticipant(calls);
    }

    private static class RecordingParticipant implements TccParticipant {
        private final List<String> calls;

        RecordingParticipant(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public void tryReserve(SagaInstance saga, SagaStep step) {
            calls.add("try:" + step.name());
        }

        @Override
        public void confirm(SagaInstance saga, SagaStep step) {
            calls.add("confirm:" + step.name());
        }

        @Override
        public void cancel(SagaInstance saga, SagaStep step) {
            calls.add("cancel:" + step.name());
        }
    }
}
