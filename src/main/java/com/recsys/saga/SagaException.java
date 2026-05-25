package com.recsys.saga;

public class SagaException extends RuntimeException {
    public SagaException(String message) {
        super(message);
    }

    public SagaException(String message, Throwable cause) {
        super(message, cause);
    }
}
