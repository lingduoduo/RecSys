package com.recsys.online.ops;

import com.recsys.infrastructure.EnvConfig;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple concurrency gate for protecting the online serving path under QPS spikes.
 */
public final class OnlineLoadShedder {
    private static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 64;
    private static final double DEFAULT_DRAIN_UTILIZATION = 0.90;

    private final int maxConcurrentRequests;
    private final double drainUtilization;
    private final AtomicInteger inFlightRequests = new AtomicInteger();
    private final AtomicLong acceptedRequests = new AtomicLong();
    private final AtomicLong rejectedRequests = new AtomicLong();

    public OnlineLoadShedder() {
        this(
                EnvConfig.readInt("ONLINE_MAX_CONCURRENT_REQUESTS", DEFAULT_MAX_CONCURRENT_REQUESTS),
                EnvConfig.readDouble("ONLINE_DRAIN_UTILIZATION", DEFAULT_DRAIN_UTILIZATION)
        );
    }

    public OnlineLoadShedder(int maxConcurrentRequests, double drainUtilization) {
        this.maxConcurrentRequests = Math.max(1, maxConcurrentRequests);
        this.drainUtilization = Math.min(1.0, Math.max(0.0, drainUtilization));
    }

    public boolean tryAcquire() {
        while (true) {
            int current = inFlightRequests.get();
            if (current >= maxConcurrentRequests) {
                rejectedRequests.incrementAndGet();
                return false;
            }
            if (inFlightRequests.compareAndSet(current, current + 1)) {
                acceptedRequests.incrementAndGet();
                return true;
            }
        }
    }

    public void release() {
        int value = inFlightRequests.decrementAndGet();
        if (value < 0) {
            inFlightRequests.compareAndSet(value, 0);
            throw new IllegalStateException("in-flight request count went negative");
        }
    }

    public boolean shouldDrain() {
        return utilization() >= drainUtilization;
    }

    /** Suggested Retry-After value in seconds; 1 when draining, 0 otherwise. */
    public int retryAfterSeconds() {
        return shouldDrain() ? 1 : 0;
    }

    public Snapshot snapshot() {
        double utilization = utilization();
        int retryAfterSeconds = utilization >= drainUtilization ? 1 : 0;
        return new Snapshot(
                inFlightRequests.get(),
                maxConcurrentRequests,
                utilization,
                drainUtilization,
                acceptedRequests.get(),
                rejectedRequests.get(),
                Math.max(0, (int) Math.round((1.0 - utilization) * 100.0)),
                retryAfterSeconds
        );
    }

    private double utilization() {
        return (double) inFlightRequests.get() / maxConcurrentRequests;
    }

    public record Snapshot(
            int inFlightRequests,
            int maxConcurrentRequests,
            double utilization,
            double drainUtilization,
            long acceptedRequests,
            long rejectedRequests,
            int suggestedWeight,
            int retryAfterSeconds
    ) {}
}
