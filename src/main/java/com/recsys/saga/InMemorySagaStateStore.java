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
        int[] nextVersion = {-1};
        sagas.compute(saga.sagaId(), (id, stored) -> {
            int storedVersion = stored == null ? 0 : stored.version();
            if (storedVersion != saga.version()) {
                throw new SagaConflictException(id, saga.version(), storedVersion);
            }
            nextVersion[0] = storedVersion + 1;
            SagaInstance copy = saga.copy();
            copy.setVersion(nextVersion[0]);
            return copy;
        });
        saga.setVersion(nextVersion[0]);
    }
}
