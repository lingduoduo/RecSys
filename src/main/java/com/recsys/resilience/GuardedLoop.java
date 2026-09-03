package com.recsys.resilience;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * The body of a fixed-delay background loop, made unable to kill its own schedule.
 *
 * <p>{@code ScheduledExecutorService.scheduleWithFixedDelay} cancels the schedule the first time
 * the task throws — any {@code Throwable}, including an {@code OutOfMemoryError} raised on this
 * thread because it happened to be the one allocating when the heap ran out — and parks the
 * cause in a {@code ScheduledFuture} nobody reads. A body guarded with {@code catch (Exception)}
 * lets every {@code Error} straight through to that fate (measured; see
 * {@code 18_Fault_Tolerance} §9.3). This wrapper catches everything except {@link ThreadDeath},
 * logs it, counts it, and returns normally, so the next iteration runs.
 *
 * <p>Health is <em>read</em>, not written: {@link #secondsSinceLastSuccess()} is computed from a
 * timestamp at call (scrape) time, so a loop that has stopped succeeding — or stopped running at
 * all — shows an age that keeps growing, rather than a gauge frozen at whatever the loop last
 * wrote. Before the first success it reports {@code -1}, never {@code 0}: an alert must be able
 * to tell "never succeeded" from "just succeeded" (same sentinel as
 * {@code gateway_registry_snapshot_age_seconds}).
 *
 * <p>The {@code loop} label is the loop's fixed name, a closed set chosen in code.
 */
public final class GuardedLoop implements Runnable {

    public static final String SECONDS_SINCE_SUCCESS = "recsys.loop.seconds_since_success";
    public static final String FAILURES = "recsys.loop.failures";
    /** Reported by {@link #secondsSinceLastSuccess()} until the body has completed once. */
    public static final double NEVER_SUCCEEDED = -1.0;

    private static final Logger log = LoggerFactory.getLogger(GuardedLoop.class);
    private static final long NEVER = Long.MIN_VALUE;

    private final String name;
    private final Runnable body;
    private final LongSupplier nanoClock;
    private final LongAdder failures = new LongAdder();
    private volatile long lastSuccessNanos = NEVER;
    private volatile Throwable lastFailure;

    public GuardedLoop(String name, Runnable body) {
        this(name, body, System::nanoTime);
    }

    GuardedLoop(String name, Runnable body, LongSupplier nanoClock) {
        this.name = Objects.requireNonNull(name, "name");
        this.body = Objects.requireNonNull(body, "body");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    @Override
    @SuppressWarnings("removal")
    public void run() {
        try {
            body.run();
            lastSuccessNanos = nanoClock.getAsLong();
        } catch (ThreadDeath death) {
            throw death;   // the one Throwable that means "this thread is being stopped": honour it
        } catch (Throwable t) {
            failures.increment();
            lastFailure = t;
            log.warn("Scheduled loop '{}' iteration failed (failure #{}); the schedule continues",
                    name, failures.sum(), t);
        }
    }

    public String name() {
        return name;
    }

    /** Seconds since the body last completed normally, or {@link #NEVER_SUCCEEDED}. */
    public double secondsSinceLastSuccess() {
        long last = lastSuccessNanos;
        if (last == NEVER) return NEVER_SUCCEEDED;
        return Math.max(0L, nanoClock.getAsLong() - last) / 1_000_000_000d;
    }

    public long failureCount() {
        return failures.sum();
    }

    public Optional<Throwable> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }

    /**
     * Publishes {@code recsys_loop_seconds_since_success{loop}} (evaluated at scrape time) and
     * {@code recsys_loop_failures_total{loop}}. {@code strongReference(true)}: Micrometer holds
     * gauge state weakly by default, and this loop may be referenced by nothing but its executor.
     */
    public GuardedLoop bindTo(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        Gauge.builder(SECONDS_SINCE_SUCCESS, this, GuardedLoop::secondsSinceLastSuccess)
                .description("Seconds since a background loop last completed normally; -1 before its first success")
                .tag("loop", name)
                .strongReference(true)
                .register(registry);
        FunctionCounter.builder(FAILURES, failures, LongAdder::sum)
                .description("Background loop iterations that threw (any Throwable) and were absorbed")
                .tag("loop", name)
                .register(registry);
        return this;
    }
}
