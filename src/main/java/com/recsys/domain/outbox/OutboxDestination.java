package com.recsys.domain.outbox;

public enum OutboxDestination {
    KAFKA_ONLINE,
    SQS_SAGA
}
