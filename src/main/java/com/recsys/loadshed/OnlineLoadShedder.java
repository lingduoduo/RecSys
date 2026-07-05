package com.recsys.loadshed;

import com.recsys.config.EnvConfig;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple concurrency gate for protecting the online serving path under QPS spikes.
 */
public final class OnlineLoadShedder {
    private static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 64;
    private static final double DEFAULT_DRAIN_UTILIZATION = 0.95;

    private final int maxConcurrentRequests;
    private final double drainUtilization;
    private final AtomicInteger inFlightRequests = new AtomicInteger();
    private final AtomicLong acceptedRequests = new AtomicLong();
    private final AtomicLong rejectedRequests = new AtomicLong();

    // Set once on SIGTERM; volatile so all threads see it immediately. One-way, never reset.
    private volatile boolean shuttingDown = false;

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

    /**
     * Called on SIGTERM so readiness returns 503 and admission control rejects new requests,
     * letting load balancers drain this instance before in-flight work is interrupted.
     */
    public void markShuttingDown() {
        shuttingDown = true;
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    public boolean tryAcquire() {
        if (shuttingDown) {
            rejectedRequests.incrementAndGet();
            return false;
        }
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
        return shuttingDown || utilization() >= drainUtilization;
    }

    /** Suggested Retry-After value in seconds; 1 when draining, 0 otherwise. */
    public int retryAfterSeconds() {
        return shouldDrain() ? 1 : 0;
    }

    public Snapshot snapshot() {
        double utilization = utilization();
        boolean draining = shuttingDown || utilization >= drainUtilization;
        int retryAfterSeconds = draining ? 1 : 0;
        int suggestedWeight = shuttingDown ? 0 : Math.max(0, (int) Math.round((1.0 - utilization) * 100.0));
        return new Snapshot(
                inFlightRequests.get(),
                maxConcurrentRequests,
                utilization,
                drainUtilization,
                acceptedRequests.get(),
                rejectedRequests.get(),
                suggestedWeight,
                retryAfterSeconds,
                shuttingDown
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
            int retryAfterSeconds,
            boolean shuttingDown
    ) {}
}
