package com.recsys.saga;

import java.util.Optional;

public interface SagaStateStore {
    Optional<SagaInstance> find(String sagaId);

    void save(SagaInstance saga);
}
