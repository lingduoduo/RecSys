package com.recsys.saga;

@FunctionalInterface
public interface SagaStepAction {
    void run(SagaInstance saga, SagaStep step);
}
