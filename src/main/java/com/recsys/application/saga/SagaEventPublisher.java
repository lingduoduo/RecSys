package com.recsys.application.saga;
import com.recsys.domain.saga.SagaTransitionEvent;

@FunctionalInterface
public interface SagaEventPublisher {
    SagaEventPublisher NOOP = event -> { };

    void publish(SagaTransitionEvent event);
}
