package com.recsys.application.saga;
import com.recsys.domain.saga.SagaBackoff;
import com.recsys.domain.saga.SagaTransitionEvent;
import com.recsys.domain.saga.SagaException;
import com.recsys.domain.saga.TccParticipant;
import com.recsys.domain.saga.SagaEventType;
import com.recsys.domain.saga.SagaStatus;
import com.recsys.domain.saga.SagaStepAction;
import com.recsys.domain.saga.SagaStep;
import com.recsys.domain.saga.SagaInstance;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Durable orchestration cores for eventual-consistency workflows, sharing one
 * persistence/retry/event-publishing base:
 *
 * <ul>
 *   <li>{@link Standard} — compensation-based saga: run steps forward, compensate
 *       completed steps in reverse on failure.</li>
 *   <li>{@link Tcc} — Try/Confirm/Cancel: reserve (Try) every step, then Confirm
 *       all; on failure Cancel every tried-but-unconfirmed reservation.</li>
 * </ul>
 *
 * Both persist each transition before publishing its event. Participant commands
 * should use sagaId + step name (+ phase, for TCC) as their idempotency key
 * because retries and replay are expected on an at-least-once AWS event path.
 */
public final class SagaOrchestrators {

    private SagaOrchestrators() {}

    /** Shared infrastructure: state persistence, retry, transitions, event publishing. */
    abstract static class Base {

        protected final SagaStateStore store;
        protected final SagaEventPublisher publisher;
        protected final Clock clock;
        private final String retryFailureNoun;

        protected Base(SagaStateStore store, SagaEventPublisher publisher, Clock clock,
                       String retryFailureNoun) {
            this.store = Objects.requireNonNull(store, "store is required");
            this.publisher = publisher == null ? SagaEventPublisher.NOOP : publisher;
            this.clock = clock == null ? Clock.systemUTC() : clock;
            this.retryFailureNoun = retryFailureNoun;
        }

        /** Load the saga or create+persist it and publish SAGA_STARTED. */
        protected SagaInstance findOrCreate(String sagaId, String correlationId,
                                            String payloadJson, SagaDefinition definition) {
            return store.find(sagaId)
                    .orElseGet(() -> {
                        SagaInstance created = new SagaInstance(
                                sagaId, definition.name(), correlationId, payloadJson, now());
                        store.saveConditionally(created);
                        publish(created, SagaEventType.SAGA_STARTED, null);
                        return created;
                    });
        }

        protected void runWithRetry(SagaInstance saga, SagaStep step, SagaStepAction action) {
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
            throw new SagaException(
                    retryFailureNoun + " failed after " + step.maxAttempts() + " attempts: " + step.name(), last);
        }

        protected void transition(SagaInstance saga, SagaStatus status, SagaEventType eventType, String stepName) {
            saga.mark(status, stepName, now());
            store.saveConditionally(saga);
            publish(saga, eventType, stepName);
        }

        protected void publish(SagaInstance saga, SagaEventType type, String stepName) {
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

        protected Instant now() {
            return clock.instant();
        }
    }

    /**
     * Compensation-based saga orchestration core.
     *
     * The orchestrator persists each transition before publishing an event. Participant
     * commands should use sagaId + step name as their idempotency key because retries and
     * replay are expected in an at-least-once AWS event path.
     */
    public static final class Standard extends Base {

        public Standard(SagaStateStore store, SagaEventPublisher publisher, Clock clock) {
            super(store, publisher, clock, "step");
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

            SagaInstance saga = findOrCreate(sagaId, correlationId, payloadJson, definition);

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
    }

    /**
     * Try/Confirm/Cancel orchestration core for stronger eventual consistency.
     *
     * Try must reserve capacity or state without making it externally final. Confirm
     * commits every successful Try. Cancel releases all unconfirmed Try reservations
     * in reverse order. Participants must be idempotent by sagaId + stepName + phase.
     */
    public static final class Tcc extends Base {

        public Tcc(SagaStateStore store, SagaEventPublisher publisher, Clock clock) {
            super(store, publisher, clock, "TCC step");
        }

        public SagaInstance execute(String sagaId,
                                    String correlationId,
                                    String payloadJson,
                                    SagaDefinition definition,
                                    Map<String, TccParticipant> participants) {
            Objects.requireNonNull(definition, "definition is required");
            Objects.requireNonNull(participants, "participants are required");

            SagaInstance saga = findOrCreate(sagaId, correlationId, payloadJson, definition);

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
                store.saveConditionally(saga);
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
                store.saveConditionally(saga);
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
            // Best-effort: attempt every cancel regardless of individual failures so no
            // reservation is silently left dangling. Failures are accumulated and reported together.
            List<String> cancelErrors = new ArrayList<>();
            for (int i = cancellable.size() - 1; i >= 0; i--) {
                SagaStep step = cancellable.get(i);
                TccParticipant participant = participantFor(participants, step);
                try {
                    transition(saga, SagaStatus.CANCELLING, SagaEventType.CANCEL_STARTED, step.name());
                    runWithRetry(saga, step, participant::cancel);
                    saga.markCancelled(step.name(), now());
                    store.saveConditionally(saga);
                    publish(saga, SagaEventType.CANCEL_COMPLETED, step.name());
                } catch (RuntimeException e) {
                    cancelErrors.add(step.name() + ": " + e.getMessage());
                }
            }
            String failureMessage = originalFailure.getMessage();
            if (!cancelErrors.isEmpty()) {
                failureMessage += "; cancel errors: " + String.join(", ", cancelErrors);
            }
            saga.fail(failureMessage, now());
            store.saveConditionally(saga);
            publish(saga, SagaEventType.SAGA_FAILED, saga.currentStep());
            if (!cancelErrors.isEmpty()) {
                throw new SagaException(failureMessage, originalFailure);
            }
        }

        private TccParticipant participantFor(Map<String, TccParticipant> participants, SagaStep step) {
            TccParticipant participant = participants.get(step.name());
            if (participant == null) {
                throw new SagaException("missing TCC participant for saga step: " + step.name());
            }
            return participant;
        }
    }
}
