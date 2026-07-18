package com.recsys.application.saga;
import com.recsys.domain.saga.SagaException;
import com.recsys.domain.saga.SagaStep;
import com.recsys.domain.saga.SagaStepAction;
import com.recsys.application.saga.SagaEventPublisher;
import com.recsys.domain.saga.SagaEventType;
import com.recsys.domain.saga.SagaStatus;
import com.recsys.domain.saga.SagaInstance;
import com.recsys.domain.saga.SagaTransitionEvent;
import com.recsys.application.saga.InMemorySagaStateStore;
import com.recsys.application.saga.SagaDefinition;

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

class SagaOrchestratorTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-25T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void durableStoreDoesNotAlsoPublishTransitionDirectly() {
        AtomicInteger durableEvents = new AtomicInteger();
        AtomicInteger directEvents = new AtomicInteger();
        SagaStateStore store = new InMemorySagaStateStore() {
            @Override public void saveWithEvent(SagaInstance saga, SagaTransitionEvent event) {
                saveConditionally(saga);
                durableEvents.incrementAndGet();
            }

            @Override public boolean storesEventsDurably() { return true; }
        };

        new SagaOrchestrators.Standard(store, event -> directEvents.incrementAndGet(), clock).execute(
                "durable-1", "request-1", "{}",
                new SagaDefinition("single", List.of(SagaStep.local("step"))),
                Map.of("step", (saga, step) -> { }), Map.of());

        assertThat(durableEvents).hasValue(4);
        assertThat(directEvents).hasValue(0);
    }

    @Test
    void execute_completesAllStepsAndPublishesTransitions() {
        InMemorySagaStateStore store = new InMemorySagaStateStore();
        List<SagaTransitionEvent> events = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        SagaOrchestrators.Standard orchestrator = new SagaOrchestrators.Standard(store, events::add, clock);

        SagaInstance result = orchestrator.execute(
                "saga-1",
                "req-1",
                "{\"userId\":101}",
                definition(),
                Map.of(
                        "reserve-recommendation", (saga, step) -> calls.add(step.name()),
                        "publish-refresh-event", (saga, step) -> calls.add(step.name())
                ),
                Map.of()
        );

        assertThat(result.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(result.completedSteps()).containsExactly("reserve-recommendation", "publish-refresh-event");
        assertThat(calls).containsExactly("reserve-recommendation", "publish-refresh-event");
        assertThat(events).extracting(SagaTransitionEvent::type)
                .containsExactly(
                        SagaEventType.SAGA_STARTED,
                        SagaEventType.STEP_STARTED,
                        SagaEventType.STEP_COMPLETED,
                        SagaEventType.STEP_STARTED,
                        SagaEventType.STEP_COMPLETED,
                        SagaEventType.SAGA_COMPLETED
                );
    }

    @Test
    void execute_isIdempotentAfterTerminalStatus() {
        InMemorySagaStateStore store = new InMemorySagaStateStore();
        AtomicInteger actionCalls = new AtomicInteger();
        SagaOrchestrators.Standard orchestrator = new SagaOrchestrators.Standard(store, SagaEventPublisher.NOOP, clock);

        SagaDefinition definition = definition();
        Map<String, SagaStepAction> actions = Map.of(
                "reserve-recommendation", (saga, step) -> actionCalls.incrementAndGet(),
                "publish-refresh-event", (saga, step) -> actionCalls.incrementAndGet()
        );

        SagaInstance first = orchestrator.execute("saga-2", "req-2", "{}", definition, actions, Map.of());
        SagaInstance replay = orchestrator.execute("saga-2", "req-2", "{}", definition, actions, Map.of());

        assertThat(first.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(replay.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(actionCalls.get()).isEqualTo(2);
    }

    @Test
    void execute_retriesTransientStepFailure() {
        InMemorySagaStateStore store = new InMemorySagaStateStore();
        SagaOrchestrators.Standard orchestrator = new SagaOrchestrators.Standard(store, SagaEventPublisher.NOOP, clock);
        AtomicInteger attempts = new AtomicInteger();
        SagaDefinition definition = new SagaDefinition("recommendation-refresh", List.of(
                SagaStep.local("reserve-recommendation").withRetry(3, Duration.ZERO)
        ));

        SagaInstance result = orchestrator.execute(
                "saga-3",
                "req-3",
                "{}",
                definition,
                Map.of("reserve-recommendation", (saga, step) -> {
                    if (attempts.incrementAndGet() < 2) {
                        throw new IllegalStateException("temporary downstream timeout");
                    }
                }),
                Map.of()
        );

        assertThat(result.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void execute_compensatesCompletedStepsInReverseOrder() {
        InMemorySagaStateStore store = new InMemorySagaStateStore();
        List<String> calls = new ArrayList<>();
        SagaOrchestrators.Standard orchestrator = new SagaOrchestrators.Standard(store, SagaEventPublisher.NOOP, clock);

        SagaInstance result = orchestrator.execute(
                "saga-4",
                "req-4",
                "{}",
                definition(),
                Map.of(
                        "reserve-recommendation", (saga, step) -> calls.add("action:" + step.name()),
                        "publish-refresh-event", (saga, step) -> {
                            calls.add("action:" + step.name());
                            throw new IllegalStateException("event bus unavailable");
                        }
                ),
                Map.of("reserve-recommendation", (saga, step) -> calls.add("compensate:" + step.name()))
        );

        assertThat(result.status()).isEqualTo(SagaStatus.FAILED);
        assertThat(result.completedSteps()).containsExactly("reserve-recommendation");
        assertThat(result.compensatedSteps()).containsExactly("reserve-recommendation");
        assertThat(calls).containsExactly(
                "action:reserve-recommendation",
                "action:publish-refresh-event",
                "compensate:reserve-recommendation"
        );
    }

    @Test
    void execute_bestEffortCompensation_continuesAfterOneCompensationFailure() {
        InMemorySagaStateStore store = new InMemorySagaStateStore();
        List<String> calls = new ArrayList<>();
        SagaOrchestrators.Standard orchestrator = new SagaOrchestrators.Standard(store, SagaEventPublisher.NOOP, clock);

        SagaDefinition definition = new SagaDefinition("recommendation-refresh", List.of(
                SagaStep.local("step-a"),
                SagaStep.local("step-b"),
                SagaStep.local("step-c")
        ));

        // step-c always fails, triggering compensation of step-b then step-a in reverse order
        // step-b compensation throws — best-effort must still run step-a compensation
        assertThatThrownBy(() -> orchestrator.execute(
                "saga-5", "req-5", "{}",
                definition,
                Map.of(
                        "step-a", (saga, step) -> calls.add("action:" + step.name()),
                        "step-b", (saga, step) -> calls.add("action:" + step.name()),
                        "step-c", (saga, step) -> {
                            calls.add("action:" + step.name());
                            throw new IllegalStateException("step-c failed");
                        }
                ),
                Map.of(
                        "step-a", (saga, step) -> calls.add("compensate:" + step.name()),
                        "step-b", (saga, step) -> {
                            calls.add("compensate:" + step.name());
                            throw new IllegalStateException("step-b compensation failed");
                        }
                )
        )).isInstanceOf(SagaException.class)
                .hasMessageContaining("step-b")
                .hasMessageContaining("compensation errors");

        assertThat(calls).containsExactly(
                "action:step-a",
                "action:step-b",
                "action:step-c",
                "compensate:step-b",
                "compensate:step-a"
        );

        SagaInstance stored = store.find("saga-5").orElseThrow();
        assertThat(stored.status()).isEqualTo(SagaStatus.FAILED);
        // step-a was compensated even though step-b compensation failed
        assertThat(stored.compensatedSteps()).contains("step-a");
    }

    private static SagaDefinition definition() {
        return new SagaDefinition("recommendation-refresh", List.of(
                SagaStep.local("reserve-recommendation"),
                SagaStep.local("publish-refresh-event")
        ));
    }
}
