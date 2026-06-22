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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TccSagaOrchestratorTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-25T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void execute_triesThenConfirmsEveryParticipant() {
        InMemorySagaStateStore store = new InMemorySagaStateStore();
        List<String> calls = new ArrayList<>();
        List<SagaTransitionEvent> events = new ArrayList<>();
        SagaOrchestrators.Tcc orchestrator = new SagaOrchestrators.Tcc(store, events::add, clock);

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
        SagaOrchestrators.Tcc orchestrator = new SagaOrchestrators.Tcc(store, SagaEventPublisher.NOOP, clock);

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
        SagaOrchestrators.Tcc orchestrator = new SagaOrchestrators.Tcc(store, SagaEventPublisher.NOOP, clock);
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
        SagaOrchestrators.Tcc orchestrator = new SagaOrchestrators.Tcc(store, SagaEventPublisher.NOOP, clock);

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

    @Test
    void execute_bestEffortCancel_continuesAfterOneCancelFailure() {
        InMemorySagaStateStore store = new InMemorySagaStateStore();
        List<String> calls = new ArrayList<>();
        SagaOrchestrators.Tcc orchestrator = new SagaOrchestrators.Tcc(store, SagaEventPublisher.NOOP, clock);

        SagaDefinition definition = new SagaDefinition("recommendation-refresh-tcc", List.of(
                SagaStep.local("step-a"),
                SagaStep.local("step-b"),
                SagaStep.local("step-c")
        ));

        // step-c try fails, triggering cancel of step-b then step-a in reverse order
        // step-b cancel throws — best-effort must still run step-a cancel
        assertThatThrownBy(() -> orchestrator.execute(
                "tcc-5", "req-5", "{}",
                definition,
                Map.of(
                        "step-a", participant(calls),
                        "step-b", new RecordingParticipant(calls) {
                            @Override
                            public void cancel(SagaInstance saga, SagaStep step) {
                                calls.add("cancel:" + step.name());
                                throw new IllegalStateException("step-b cancel failed");
                            }
                        },
                        "step-c", new RecordingParticipant(calls) {
                            @Override
                            public void tryReserve(SagaInstance saga, SagaStep step) {
                                calls.add("try:" + step.name());
                                throw new IllegalStateException("step-c try failed");
                            }
                        }
                )
        )).isInstanceOf(SagaException.class)
                .hasMessageContaining("step-b")
                .hasMessageContaining("cancel errors");

        assertThat(calls).containsExactly(
                "try:step-a",
                "try:step-b",
                "try:step-c",
                "cancel:step-b",
                "cancel:step-a"
        );

        SagaInstance stored = store.find("tcc-5").orElseThrow();
        assertThat(stored.status()).isEqualTo(SagaStatus.FAILED);
        // step-a was cancelled even though step-b cancel failed
        assertThat(stored.cancelledSteps()).contains("step-a");
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
