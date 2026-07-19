package com.recsys.infrastructure.outbox;

public final class OutboxConflictException extends RuntimeException {
    public OutboxConflictException(String message) {
        super(message);
    }
}
