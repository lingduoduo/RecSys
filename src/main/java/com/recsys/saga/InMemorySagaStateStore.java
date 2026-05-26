package com.recsys.saga;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemorySagaStateStore implements SagaStateStore {
    private final ConcurrentMap<String, SagaInstance> sagas = new ConcurrentHashMap<>();

    @Override
    public Optional<SagaInstance> find(String sagaId) {
        SagaInstance saga = sagas.get(sagaId);
        return saga == null ? Optional.empty() : Optional.of(saga.copy());
    }

    @Override
    public void save(SagaInstance saga) {
        sagas.put(saga.sagaId(), saga.copy());
    }

    @Override
    public void saveConditionally(SagaInstance saga) {
        sagas.compute(saga.sagaId(), (id, stored) -> {
            int storedVersion = stored == null ? 0 : stored.version();
            if (storedVersion != saga.version()) {
                throw new SagaConflictException(id, saga.version(), storedVersion);
            }
            SagaInstance copy = saga.copy();
            copy.setVersion(storedVersion + 1);
            return copy;
        });
        // Callers own their SagaInstance exclusively — reflect the stored version back.
        saga.setVersion(sagas.get(saga.sagaId()).version());
    }
}
