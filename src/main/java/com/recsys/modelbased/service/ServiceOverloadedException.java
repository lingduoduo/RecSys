package com.recsys.modelbased.service;

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
