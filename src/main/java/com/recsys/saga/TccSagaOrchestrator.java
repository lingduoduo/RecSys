package com.recsys.saga;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Try/Confirm/Cancel orchestration for stronger eventual consistency.
 *
 * Try must reserve capacity or state without making it externally final. Confirm
 * commits every successful Try. Cancel releases all unconfirmed Try reservations
 * in reverse order. Participants must be idempotent by sagaId + stepName + phase.
 */
public class TccSagaOrchestrator {
    private final SagaStateStore store;
    private final SagaEventPublisher publisher;
    private final Clock clock;

    public TccSagaOrchestrator(SagaStateStore store, SagaEventPublisher publisher, Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.publisher = publisher == null ? SagaEventPublisher.NOOP : publisher;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public SagaInstance execute(String sagaId,
                                String correlationId,
                                String payloadJson,
                                SagaDefinition definition,
                                Map<String, TccParticipant> participants) {
        Objects.requireNonNull(definition, "definition is required");
        Objects.requireNonNull(participants, "participants are required");

        SagaInstance saga = store.find(sagaId)
                .orElseGet(() -> {
                    SagaInstance created = new SagaInstance(sagaId, definition.name(), correlationId, payloadJson, now());
                    store.save(created);
                    publish(created, SagaEventType.SAGA_STARTED, null);
                    return created;
                });

        if (saga.status() == SagaStatus.COMPLETED || saga.status() == SagaStatus.FAILED) {
            return saga;
        }

        try {
            tryAll(saga, definition, participants);
            confirmAll(saga, definition, participants);
            transition(saga, SagaStatus.COMPLETED, SagaEventType.SAGA_COMPLETED, null);
            return saga.copy();
        } catch (RuntimeException e) {
            cancelTriedButUnconfirmed(saga, definition, participants, e);
            return saga.copy();
        }
    }

    private void tryAll(SagaInstance saga, SagaDefinition definition, Map<String, TccParticipant> participants) {
        for (SagaStep step : definition.steps()) {
            if (saga.triedSteps().contains(step.name())) {
                continue;
            }
            TccParticipant participant = participantFor(participants, step);
            transition(saga, SagaStatus.TRYING, SagaEventType.TRY_STARTED, step.name());
            runWithRetry(saga, step, participant::tryReserve);
            saga.markTried(step.name(), now());
            store.save(saga);
            publish(saga, SagaEventType.TRY_COMPLETED, step.name());
        }
    }

    private void confirmAll(SagaInstance saga, SagaDefinition definition, Map<String, TccParticipant> participants) {
        for (SagaStep step : definition.steps()) {
            if (saga.confirmedSteps().contains(step.name())) {
                continue;
            }
            TccParticipant participant = participantFor(participants, step);
            transition(saga, SagaStatus.CONFIRMING, SagaEventType.CONFIRM_STARTED, step.name());
            runWithRetry(saga, step, participant::confirm);
            saga.markConfirmed(step.name(), now());
            store.save(saga);
            publish(saga, SagaEventType.CONFIRM_COMPLETED, step.name());
        }
    }

    private void cancelTriedButUnconfirmed(SagaInstance saga,
                                           SagaDefinition definition,
                                           Map<String, TccParticipant> participants,
                                           RuntimeException originalFailure) {
        List<SagaStep> cancellable = new ArrayList<>();
        for (SagaStep step : definition.steps()) {
            if (saga.triedSteps().contains(step.name())
                    && !saga.confirmedSteps().contains(step.name())
                    && !saga.cancelledSteps().contains(step.name())) {
                cancellable.add(step);
            }
        }
        try {
            for (int i = cancellable.size() - 1; i >= 0; i--) {
                SagaStep step = cancellable.get(i);
                TccParticipant participant = participantFor(participants, step);
                transition(saga, SagaStatus.CANCELLING, SagaEventType.CANCEL_STARTED, step.name());
                runWithRetry(saga, step, participant::cancel);
                saga.markCancelled(step.name(), now());
                store.save(saga);
                publish(saga, SagaEventType.CANCEL_COMPLETED, step.name());
            }
            saga.fail(originalFailure.getMessage(), now());
            store.save(saga);
            publish(saga, SagaEventType.SAGA_FAILED, saga.currentStep());
        } catch (RuntimeException cancelFailure) {
            saga.fail("cancel failed after original error: "
                    + originalFailure.getMessage() + "; cancel error: " + cancelFailure.getMessage(), now());
            store.save(saga);
            publish(saga, SagaEventType.SAGA_FAILED, saga.currentStep());
            throw cancelFailure;
        }
    }

    private TccParticipant participantFor(Map<String, TccParticipant> participants, SagaStep step) {
        TccParticipant participant = participants.get(step.name());
        if (participant == null) {
            throw new SagaException("missing TCC participant for saga step: " + step.name());
        }
        return participant;
    }

    private void runWithRetry(SagaInstance saga, SagaStep step, SagaStepAction action) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= step.maxAttempts(); attempt++) {
            try {
                action.run(saga, step);
                return;
            } catch (RuntimeException e) {
                last = e;
                if (attempt == step.maxAttempts()) {
                    break;
                }
                sleep(step.backoff());
            }
        }
        throw new SagaException("TCC step failed after " + step.maxAttempts() + " attempts: " + step.name(), last);
    }

    private void transition(SagaInstance saga, SagaStatus status, SagaEventType eventType, String stepName) {
        saga.mark(status, stepName, now());
        store.save(saga);
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

    private void sleep(java.time.Duration backoff) {
        if (backoff == null || backoff.isZero() || backoff.isNegative()) {
            return;
        }
        try {
            Thread.sleep(backoff.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SagaException("interrupted during TCC retry backoff", e);
        }
    }
}
