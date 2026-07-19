package com.recsys.application.consistency;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import com.recsys.metrics.ConsistencyMetrics;

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
    private final ConsistencyMetrics metrics;

    public ConsistencyWaiter(LineageReader lineage) {
        this(lineage, Clock.systemUTC(), duration -> Thread.sleep(duration.toMillis()),
                Duration.ofMillis(50), System::nanoTime, null);
    }

    public ConsistencyWaiter(LineageReader lineage, ConsistencyMetrics metrics) {
        this(lineage, Clock.systemUTC(), duration -> Thread.sleep(duration.toMillis()),
                Duration.ofMillis(50), System::nanoTime, Objects.requireNonNull(metrics));
    }

    public ConsistencyWaiter(LineageReader lineage, Clock clock, Sleeper sleeper, Duration pollInterval) {
        this(lineage, clock, sleeper, pollInterval, System::nanoTime, null);
    }

    ConsistencyWaiter(LineageReader lineage, Clock clock, Sleeper sleeper,
                      Duration pollInterval, LongSupplier monotonicNanos) {
        this(lineage, clock, sleeper, pollInterval, monotonicNanos, null);
    }

    ConsistencyWaiter(LineageReader lineage, Clock clock, Sleeper sleeper,
                      Duration pollInterval, LongSupplier monotonicNanos, ConsistencyMetrics metrics) {
        this.lineage = Objects.requireNonNull(lineage, "lineage");
        Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.pollInterval = requirePositive(pollInterval, "pollInterval");
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        this.metrics = metrics;
    }

    public WaitResult await(UUID eventId, int userId, Duration timeout) {
        Objects.requireNonNull(eventId, "eventId");
        Duration bounded = requirePositive(timeout, "timeout").compareTo(MAX_TIMEOUT) > 0
                ? MAX_TIMEOUT : timeout;
        long started = monotonicNanos.getAsLong();
        long budgetNanos = bounded.toNanos();
        while (true) {
            long elapsed = Math.max(0L, monotonicNanos.getAsLong() - started);
            if (elapsed >= budgetNanos) return finish(WaitResult.PENDING, started);
            Duration remaining = Duration.ofNanos(budgetNanos - elapsed);
            boolean applied;
            try {
                applied = lineage.contains(eventId, userId, remaining);
            } catch (RuntimeException failure) {
                if (metrics != null) metrics.recordWait(ConsistencyMetrics.WaitOutcome.UNAVAILABLE,
                        Duration.ofNanos(Math.max(0, monotonicNanos.getAsLong() - started)));
                throw failure;
            }
            elapsed = Math.max(0L, monotonicNanos.getAsLong() - started);
            if (elapsed >= budgetNanos) return finish(WaitResult.PENDING, started);
            if (applied) return finish(WaitResult.APPLIED, started);
            remaining = Duration.ofNanos(budgetNanos - elapsed);
            try {
                sleeper.sleep(remaining.compareTo(pollInterval) < 0 ? remaining : pollInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return finish(WaitResult.PENDING, started);
            }
        }
    }

    private WaitResult finish(WaitResult result, long started) {
        if (metrics != null) metrics.recordWait(result == WaitResult.APPLIED
                        ? ConsistencyMetrics.WaitOutcome.APPLIED : ConsistencyMetrics.WaitOutcome.TIMEOUT,
                Duration.ofNanos(Math.max(0, monotonicNanos.getAsLong() - started)));
        return result;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
