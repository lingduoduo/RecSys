package com.recsys.domain.saga;

import java.time.Duration;
import java.util.Objects;

public record SagaStep(String name,
                       String awsResourceArn,
                       String confirmResourceArn,
                       String compensationResourceArn,
                       int maxAttempts,
                       Duration backoff) {
    public SagaStep {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("step name is required");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        Objects.requireNonNull(backoff, "backoff is required");
    }

    public static SagaStep local(String name) {
        return new SagaStep(name, "", "", "", 1, Duration.ZERO);
    }

    public static SagaStep awsTask(String name, String awsResourceArn, String compensationResourceArn) {
        return new SagaStep(name, awsResourceArn, "", compensationResourceArn, 3, Duration.ofSeconds(2));
    }

    public static SagaStep tccAwsTask(String name,
                                      String tryResourceArn,
                                      String confirmResourceArn,
                                      String cancelResourceArn) {
        return new SagaStep(name, tryResourceArn, confirmResourceArn, cancelResourceArn, 3, Duration.ofSeconds(2));
    }

    public SagaStep withRetry(int maxAttempts, Duration backoff) {
        return new SagaStep(name, awsResourceArn, confirmResourceArn, compensationResourceArn, maxAttempts, backoff);
    }

    public boolean hasCompensation() {
        return compensationResourceArn != null && !compensationResourceArn.isBlank();
    }
}
