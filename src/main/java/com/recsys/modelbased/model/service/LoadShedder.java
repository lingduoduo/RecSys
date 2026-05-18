package com.recsys.modelbased.model.service;

import com.recsys.modelbased.model.config.HealthProperties;
import org.springframework.stereotype.Service;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class LoadShedder {

    private final int maxConcurrentRequests;
    private final double maxReadinessUtilization;
    private final Semaphore requestSlots;
    private final AtomicInteger inFlightRequests = new AtomicInteger();
    private final AtomicLong acceptedRequests = new AtomicLong();
    private final AtomicLong rejectedRequests = new AtomicLong();
    // Set once on SIGTERM; volatile ensures all threads see it immediately.
    private volatile boolean shuttingDown = false;

    public LoadShedder(HealthProperties props) {
        this.maxConcurrentRequests = props.getMaxConcurrentRequests();
        this.maxReadinessUtilization = props.getMaxInFlightUtilization();
        this.requestSlots = new Semaphore(maxConcurrentRequests);
    }

    /**
     * Called on SIGTERM so that the readiness probe immediately returns 503 and
     * load balancers drain this instance before in-flight requests are interrupted.
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
        if (!requestSlots.tryAcquire()) {
            rejectedRequests.incrementAndGet();
            return false;
        }
        inFlightRequests.incrementAndGet();
        acceptedRequests.incrementAndGet();
        return true;
    }

    public void release() {
        int value = inFlightRequests.decrementAndGet();
        if (value < 0) {
            inFlightRequests.compareAndSet(value, 0);
            throw new IllegalStateException("in-flight request count went negative");
        }
        requestSlots.release();
    }

    public Snapshot snapshot() {
        int inFlight = inFlightRequests.get();
        double utilization = maxConcurrentRequests > 0 ? (double) inFlight / maxConcurrentRequests : 1.0;
        int suggestedWeight = shuttingDown ? 0 : Math.max(0, (int) Math.round((1.0 - utilization) * 100.0));
        return new Snapshot(
                inFlight,
                maxConcurrentRequests,
                utilization,
                maxReadinessUtilization,
                acceptedRequests.get(),
                rejectedRequests.get(),
                suggestedWeight,
                shuttingDown
        );
    }

    /** True when the instance should be drained from load-balancer rotation. */
    public boolean shouldDrain() {
        if (shuttingDown) return true;
        // Inline the utilization check to avoid allocating a full Snapshot record.
        return maxConcurrentRequests > 0
                && (double) inFlightRequests.get() / maxConcurrentRequests >= maxReadinessUtilization;
    }

    public record Snapshot(
            int inFlightRequests,
            int maxConcurrentRequests,
            double utilization,
            double maxReadinessUtilization,
            long acceptedRequests,
            long rejectedRequests,
            int suggestedWeight,
            boolean shuttingDown
    ) {
    }
}
