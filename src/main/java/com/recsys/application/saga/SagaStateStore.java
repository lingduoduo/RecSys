package com.recsys.application.saga;
import com.recsys.domain.saga.SagaInstance;
import com.recsys.domain.saga.SagaTransitionEvent;

import java.util.Optional;

public interface SagaStateStore {
    Optional<SagaInstance> find(String sagaId);

    void save(SagaInstance saga);

    /**
     * Save with optimistic locking: throws {@link SagaConflictException} if the stored
     * version does not match {@code saga.version()} at the time of the call. On success
     * increments {@code saga}'s version to reflect the newly stored state.
     * Default implementation delegates to {@link #save} for stores that do not need
     * concurrency control.
     */
    default void saveConditionally(SagaInstance saga) {
        save(saga);
    }

    /** Persist a transition and its event. Durable stores override this atomically. */
    default void saveWithEvent(SagaInstance saga, SagaTransitionEvent event) {
        saveConditionally(saga);
    }

    /** Whether {@link #saveWithEvent} durably records the event for asynchronous delivery. */
    default boolean storesEventsDurably() {
        return false;
    }
}
