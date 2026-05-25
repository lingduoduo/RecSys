package com.recsys.saga;

public enum SagaStatus {
    STARTED,
    STEP_STARTED,
    STEP_COMPLETED,
    TRYING,
    TRIED,
    CONFIRMING,
    CONFIRMED,
    CANCELLING,
    CANCELLED,
    COMPENSATING,
    COMPENSATED,
    COMPLETED,
    FAILED
}
