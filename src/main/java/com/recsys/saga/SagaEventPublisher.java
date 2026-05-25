package com.recsys.saga;

@FunctionalInterface
public interface SagaEventPublisher {
    SagaEventPublisher NOOP = event -> { };

    void publish(SagaTransitionEvent event);
}
