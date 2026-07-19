package com.recsys.domain.outbox;

public enum OutboxStatus {
    PENDING,
    IN_FLIGHT,
    DELIVERED,
    DEAD
}
