package com.recsys.saga;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
/**
 * Durable orchestration core for eventual consistency workflows.
 *
 * The orchestrator persists each transition before publishing an event. Participant
 * commands should use sagaId + step name as their idempotency key because retries and
 * replay are expected in an at-least-once AWS event path.
 */
public class SagaOrchestrator {

    private final SagaStateStore store;
    private final SagaEventPublisher publisher;
    private final Clock clock;

    public SagaOrchestrator(SagaStateStore store, SagaEventPublisher publisher, Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.publisher = publisher == null ? SagaEventPublisher.NOOP : publisher;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public SagaInstance execute(String sagaId,
                                String correlationId,
                                String payloadJson,
                                SagaDefinition definition,
                                Map<String, SagaStepAction> actions,
                                Map<String, SagaStepAction> compensations) {
        Objects.requireNonNull(definition, "definition is required");
        Objects.requireNonNull(actions, "actions are required");
        Objects.requireNonNull(compensations, "compensations are required");

        SagaInstance saga = store.find(sagaId)
                .orElseGet(() -> {
                    SagaInstance created = new SagaInstance(sagaId, definition.name(), correlationId, payloadJson, now());
                    store.saveConditionally(created);
                    publish(created, SagaEventType.SAGA_STARTED, null);
                    return created;
                });

        if (saga.status() == SagaStatus.COMPLETED || saga.status() == SagaStatus.FAILED) {
            return saga;
        }

        List<SagaStep> completedInThisRun = new ArrayList<>();
        try {
            for (SagaStep step : definition.steps()) {
                if (saga.completedSteps().contains(step.name())) {
                    continue;
                }
                SagaStepAction action = actions.get(step.name());
                if (action == null) {
                    throw new SagaException("missing action for saga step: " + step.name());
                }
                transition(saga, SagaStatus.STEP_STARTED, SagaEventType.STEP_STARTED, step.name());
                runWithRetry(saga, step, action);
                saga.markStepCompleted(step.name(), now());
                store.saveConditionally(saga);
                completedInThisRun.add(step);
                publish(saga, SagaEventType.STEP_COMPLETED, step.name());
            }
            transition(saga, SagaStatus.COMPLETED, SagaEventType.SAGA_COMPLETED, null);
            return saga.copy();
        } catch (RuntimeException e) {
            compensate(saga, completedInThisRun, definition.steps(), compensations, e);
            return saga.copy();
        }
    }

    private void compensate(SagaInstance saga,
                            List<SagaStep> completedInThisRun,
                            List<SagaStep> allSteps,
                            Map<String, SagaStepAction> compensations,
                            RuntimeException originalFailure) {
        List<SagaStep> completed = completedInThisRun.isEmpty()
                ? allSteps.stream().filter(step -> saga.completedSteps().contains(step.name())).toList()
                : completedInThisRun;
        // Best-effort: attempt every compensation regardless of individual failures so no
        // step is silently left uncompensated. Failures are accumulated and reported together.
        List<String> compensationErrors = new ArrayList<>();
        for (int i = completed.size() - 1; i >= 0; i--) {
            SagaStep step = completed.get(i);
            SagaStepAction compensation = compensations.get(step.name());
            if (compensation == null) {
                continue;
            }
            try {
                transition(saga, SagaStatus.COMPENSATING, SagaEventType.COMPENSATION_STARTED, step.name());
                runWithRetry(saga, step, compensation);
                saga.markCompensated(step.name(), now());
                store.saveConditionally(saga);
                publish(saga, SagaEventType.COMPENSATION_COMPLETED, step.name());
            } catch (RuntimeException e) {
                compensationErrors.add(step.name() + ": " + e.getMessage());
            }
        }
        String failureMessage = originalFailure.getMessage();
        if (!compensationErrors.isEmpty()) {
            failureMessage += "; compensation errors: " + String.join(", ", compensationErrors);
        }
        saga.fail(failureMessage, now());
        store.saveConditionally(saga);
        publish(saga, SagaEventType.SAGA_FAILED, saga.currentStep());
        if (!compensationErrors.isEmpty()) {
            throw new SagaException(failureMessage, originalFailure);
        }
    }

    private void runWithRetry(SagaInstance saga, SagaStep step, SagaStepAction action) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= step.maxAttempts(); attempt++) {
            try {
                action.run(saga, step);
                return;
            } catch (RuntimeException e) {
                last = e;
                if (attempt < step.maxAttempts()) {
                    SagaBackoff.sleep(step.backoff(), attempt);
                }
            }
        }
        throw new SagaException("step failed after " + step.maxAttempts() + " attempts: " + step.name(), last);
    }

    private void transition(SagaInstance saga, SagaStatus status, SagaEventType eventType, String stepName) {
        saga.mark(status, stepName, now());
        store.saveConditionally(saga);
        publish(saga, eventType, stepName);
    }

    private void publish(SagaInstance saga, SagaEventType type, String stepName) {
        publisher.publish(new SagaTransitionEvent(
                saga.sagaId() + ":" + type + ":" + (stepName == null ? "saga" : stepName),
                saga.sagaId(),
                saga.sagaType(),
                saga.correlationId(),
                type,
                saga.status(),
                stepName,
                saga.payloadJson(),
                saga.updatedAt()
        ));
    }

    private Instant now() {
        return clock.instant();
    }

}
