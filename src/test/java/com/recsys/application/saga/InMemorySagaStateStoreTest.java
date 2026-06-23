package com.recsys.application.saga;
import com.recsys.domain.saga.SagaConflictException;
import com.recsys.application.saga.InMemorySagaStateStore;
import com.recsys.domain.saga.SagaInstance;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemorySagaStateStoreTest {

    private static SagaInstance newSaga(String id) {
        return new SagaInstance(id, "test-saga", "corr-1", "{}", Instant.EPOCH);
    }

    @Test
    void saveConditionally_firstSaveSucceedsWithVersionZero() {
        InMemorySagaStateStore store = new InMemorySagaStateStore();
        SagaInstance saga = newSaga("saga-1");

        store.saveConditionally(saga);

        assertThat(saga.version()).isEqualTo(1);
        assertThat(store.find("saga-1")).isPresent()
                .get().extracting(SagaInstance::version).isEqualTo(1);
    }

    @Test
    void saveConditionally_incrementsVersionOnEachSuccessfulSave() {
        InMemorySagaStateStore store = new InMemorySagaStateStore();
        SagaInstance saga = newSaga("saga-2");

        store.saveConditionally(saga); // version 0 → 1
        store.saveConditionally(saga); // version 1 → 2
        store.saveConditionally(saga); // version 2 → 3

        assertThat(saga.version()).isEqualTo(3);
        assertThat(store.find("saga-2")).isPresent()
                .get().extracting(SagaInstance::version).isEqualTo(3);
    }

    @Test
    void saveConditionally_throwsSagaConflictExceptionOnVersionMismatch() {
        InMemorySagaStateStore store = new InMemorySagaStateStore();
        SagaInstance saga = newSaga("saga-3");
        store.saveConditionally(saga); // version → 1

        // Simulate a stale copy still at version 0
        SagaInstance staleCopy = newSaga("saga-3");
        assertThat(staleCopy.version()).isEqualTo(0);

        assertThatThrownBy(() -> store.saveConditionally(staleCopy))
                .isInstanceOf(SagaConflictException.class)
                .satisfies(ex -> {
                    SagaConflictException conflict = (SagaConflictException) ex;
                    assertThat(conflict.sagaId()).isEqualTo("saga-3");
                    assertThat(conflict.expectedVersion()).isEqualTo(0);
                    assertThat(conflict.actualVersion()).isEqualTo(1);
                });
    }

    @Test
    void saveConditionally_doesNotMutateStoredCopyWhenCallerVersionChanges() {
        InMemorySagaStateStore store = new InMemorySagaStateStore();
        SagaInstance saga = newSaga("saga-4");
        store.saveConditionally(saga); // version → 1

        // Mutate caller; stored copy must remain at version 1
        saga.setVersion(99);

        assertThat(store.find("saga-4")).isPresent()
                .get().extracting(SagaInstance::version).isEqualTo(1);
    }

    @Test
    void save_doesNotEnforceVersioning() {
        InMemorySagaStateStore store = new InMemorySagaStateStore();
        SagaInstance saga = newSaga("saga-5");
        store.save(saga);

        SagaInstance stale = newSaga("saga-5");
        stale.setVersion(0); // version mismatch would be rejected by saveConditionally
        store.save(stale);   // plain save must always succeed

        assertThat(store.find("saga-5")).isPresent();
    }
}
