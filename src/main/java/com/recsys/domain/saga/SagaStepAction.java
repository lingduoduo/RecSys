package com.recsys.domain.saga;

@FunctionalInterface
public interface SagaStepAction {
    void run(SagaInstance saga, SagaStep step);
}
