package com.recsys.domain.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.DoubleSupplier;

public final class OutboxRetryPolicy {
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final int maxAttempts;
    private final DoubleSupplier jitter;

    public OutboxRetryPolicy(Duration baseDelay, Duration maxDelay, int maxAttempts, DoubleSupplier jitter) {
        this.baseDelay = positive(baseDelay, "baseDelay");
        this.maxDelay = positive(maxDelay, "maxDelay");
        if (baseDelay.compareTo(maxDelay) > 0) throw new IllegalArgumentException("baseDelay exceeds maxDelay");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        this.maxAttempts = maxAttempts;
        this.jitter = Objects.requireNonNull(jitter, "jitter");
    }

    public Instant nextAttempt(int attempt, Instant failedAt) {
        if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
        Objects.requireNonNull(failedAt, "failedAt");
        long baseNanos = baseDelay.toNanos();
        long maxNanos = maxDelay.toNanos();
        long multiplier = attempt >= 63 ? Long.MAX_VALUE : 1L << (attempt - 1);
        long unjittered = baseNanos > maxNanos / multiplier ? maxNanos : baseNanos * multiplier;
        double sample = jitter.getAsDouble();
        if (!Double.isFinite(sample) || sample < 0 || sample > 1) {
            throw new IllegalArgumentException("jitter must be between zero and one");
        }
        double factor = 0.5 + sample;
        long delayed = factor >= 1 || unjittered > (long) (maxNanos / factor)
                ? Math.min(unjittered, maxNanos)
                : Math.min(maxNanos, (long) (unjittered * factor));
        return failedAt.plusNanos(delayed);
    }

    public boolean isDead(int attemptCount) {
        return attemptCount >= maxAttempts;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
