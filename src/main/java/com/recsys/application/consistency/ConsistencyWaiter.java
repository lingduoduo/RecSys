package com.recsys.application.consistency;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Bounded poller for an event's materialized feature lineage. */
public final class ConsistencyWaiter {
    public enum WaitResult { APPLIED, PENDING }

    @FunctionalInterface public interface LineageReader {
        boolean contains(UUID eventId, int userId, Duration remaining);
    }
    @FunctionalInterface public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(2);
    private final LineageReader lineage;
    private final Sleeper sleeper;
    private final Duration pollInterval;
    private final LongSupplier monotonicNanos;

    public ConsistencyWaiter(LineageReader lineage) {
        this(lineage, Clock.systemUTC(), duration -> Thread.sleep(duration.toMillis()),
                Duration.ofMillis(50), System::nanoTime);
    }

    public ConsistencyWaiter(LineageReader lineage, Clock clock, Sleeper sleeper, Duration pollInterval) {
        this(lineage, clock, sleeper, pollInterval, System::nanoTime);
    }

    ConsistencyWaiter(LineageReader lineage, Clock clock, Sleeper sleeper,
                      Duration pollInterval, LongSupplier monotonicNanos) {
        this.lineage = Objects.requireNonNull(lineage, "lineage");
        Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.pollInterval = requirePositive(pollInterval, "pollInterval");
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
    }

    public WaitResult await(UUID eventId, int userId, Duration timeout) {
        Objects.requireNonNull(eventId, "eventId");
        Duration bounded = requirePositive(timeout, "timeout").compareTo(MAX_TIMEOUT) > 0
                ? MAX_TIMEOUT : timeout;
        long started = monotonicNanos.getAsLong();
        long budgetNanos = bounded.toNanos();
        while (true) {
            long elapsed = Math.max(0L, monotonicNanos.getAsLong() - started);
            if (elapsed >= budgetNanos) return WaitResult.PENDING;
            Duration remaining = Duration.ofNanos(budgetNanos - elapsed);
            boolean applied = lineage.contains(eventId, userId, remaining);
            elapsed = Math.max(0L, monotonicNanos.getAsLong() - started);
            if (elapsed >= budgetNanos) return WaitResult.PENDING;
            if (applied) return WaitResult.APPLIED;
            remaining = Duration.ofNanos(budgetNanos - elapsed);
            try {
                sleeper.sleep(remaining.compareTo(pollInterval) < 0 ? remaining : pollInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return WaitResult.PENDING;
            }
        }
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
