package com.recsys.model.exception;

public class ServiceOverloadedException extends RuntimeException {

    private final int retryAfterSeconds;

    public ServiceOverloadedException(int retryAfterSeconds) {
        super("recommendation service is overloaded");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
