package com.recsys.domain.outbox;

import java.time.Duration;
import java.time.Instant;
import java.time.DateTimeException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;
import java.util.function.DoubleSupplier;

public final class OutboxRetryPolicy {
    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);
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
        BigInteger baseNanos = nanos(baseDelay);
        BigInteger maxNanos = nanos(maxDelay);
        int shift = attempt - 1;
        BigInteger unjittered = shift >= maxNanos.bitLength()
                ? maxNanos
                : baseNanos.shiftLeft(shift).min(maxNanos);
        double sample = jitter.getAsDouble();
        if (!Double.isFinite(sample) || sample < 0 || sample > 1) {
            throw new IllegalArgumentException("jitter must be between zero and one");
        }
        BigDecimal factor = BigDecimal.valueOf(0.5 + sample);
        BigInteger delayed = new BigDecimal(unjittered).multiply(factor).toBigInteger().min(maxNanos);
        BigInteger[] secondsAndNanos = delayed.divideAndRemainder(NANOS_PER_SECOND);
        try {
            return failedAt.plusSeconds(secondsAndNanos[0].longValueExact())
                    .plusNanos(secondsAndNanos[1].longValueExact());
        } catch (ArithmeticException | DateTimeException overflow) {
            return Instant.MAX;
        }
    }

    public boolean isDead(int attemptCount) {
        return attemptCount >= maxAttempts;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static BigInteger nanos(Duration duration) {
        return BigInteger.valueOf(duration.getSeconds()).multiply(NANOS_PER_SECOND)
                .add(BigInteger.valueOf(duration.getNano()));
    }
}
