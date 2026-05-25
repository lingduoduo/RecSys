package com.recsys.saga;

public interface TccParticipant {
    void tryReserve(SagaInstance saga, SagaStep step);

    void confirm(SagaInstance saga, SagaStep step);

    void cancel(SagaInstance saga, SagaStep step);
}
