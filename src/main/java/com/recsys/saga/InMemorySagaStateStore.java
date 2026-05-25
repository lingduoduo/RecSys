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
}
