package com.recsys.streaming;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight request metrics for the Jetty online-serving path.
 */
public final class OnlineServingMetricsService {
    private static final int DEFAULT_WINDOW_SECONDS = 60;

    private final int windowSeconds;
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong successCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();
    private final AtomicLong totalLatencyMs = new AtomicLong();

    private final Deque<RequestRecord> window = new ArrayDeque<>();
    private long windowTotal;
    private long windowFailures;
    private long windowRejected;
    private long windowLatencyMs;
    private final Object lock = new Object();

    private final Map<String, StrategyMetrics> strategyMetrics = new TreeMap<>();
    private final Object strategyLock = new Object();

    public OnlineServingMetricsService() {
        this(readIntEnv("ONLINE_METRICS_WINDOW_SECONDS", DEFAULT_WINDOW_SECONDS));
    }

    OnlineServingMetricsService(int windowSeconds) {
        this.windowSeconds = Math.max(1, windowSeconds);
    }

    public void recordSuccess(long latencyMs, String strategy) {
        record(latencyMs, false, false);
        recordStrategy(latencyMs, strategy);
    }

    public void recordFailure(long latencyMs) {
        record(latencyMs, true, false);
    }

    public void recordRejected() {
        record(0L, true, true);
    }

    public Snapshot snapshot() {
        long total = totalRequests.get();
        double allTimeAvgLatencyMs = total > 0 ? (double) totalLatencyMs.get() / total : 0.0;

        long now = Instant.now().getEpochSecond();
        long recentTotal;
        long recentFailures;
        long recentRejected;
        double recentAvgLatencyMs;
        synchronized (lock) {
            evict(now);
            recentTotal = windowTotal;
            recentFailures = windowFailures;
            recentRejected = windowRejected;
            recentAvgLatencyMs = windowTotal > 0 ? (double) windowLatencyMs / windowTotal : 0.0;
        }

        Map<String, StrategySnapshot> strategies = strategySnapshot();
        double recentFailureRate = recentTotal > 0 ? (double) recentFailures / recentTotal : 0.0;
        double recentRejectedRate = recentTotal > 0 ? (double) recentRejected / recentTotal : 0.0;
        double qps = (double) recentTotal / windowSeconds;

        return new Snapshot(
                total,
                successCount.get(),
                failureCount.get(),
                rejectedCount.get(),
                allTimeAvgLatencyMs,
                recentTotal,
                recentFailures,
                recentRejected,
                recentAvgLatencyMs,
                recentFailureRate,
                recentRejectedRate,
                qps,
                strategies
        );
    }

    private void record(long latencyMs, boolean failed, boolean rejected) {
        totalRequests.incrementAndGet();
        if (failed) {
            failureCount.incrementAndGet();
        } else {
            successCount.incrementAndGet();
        }
        if (rejected) {
            rejectedCount.incrementAndGet();
        }
        totalLatencyMs.addAndGet(latencyMs);

        long now = Instant.now().getEpochSecond();
        synchronized (lock) {
            evict(now);
            window.addLast(new RequestRecord(now, latencyMs, failed, rejected));
            windowTotal++;
            if (failed) windowFailures++;
            if (rejected) windowRejected++;
            windowLatencyMs += latencyMs;
        }
    }

    private void evict(long nowSeconds) {
        long cutoff = nowSeconds - windowSeconds;
        while (!window.isEmpty() && window.peekFirst().timestampSeconds < cutoff) {
            RequestRecord record = window.pollFirst();
            windowTotal--;
            if (record.failed) windowFailures--;
            if (record.rejected) windowRejected--;
            windowLatencyMs -= record.latencyMs;
        }
    }

    private void recordStrategy(long latencyMs, String strategy) {
        String key = strategy == null || strategy.isBlank() ? "unknown" : strategy.trim();
        synchronized (strategyLock) {
            StrategyMetrics metrics = strategyMetrics.computeIfAbsent(key, ignored -> new StrategyMetrics());
            metrics.requests++;
            metrics.totalLatencyMs += latencyMs;
        }
    }

    private Map<String, StrategySnapshot> strategySnapshot() {
        synchronized (strategyLock) {
            Map<String, StrategySnapshot> copy = new LinkedHashMap<>();
            for (Map.Entry<String, StrategyMetrics> entry : strategyMetrics.entrySet()) {
                StrategyMetrics metrics = entry.getValue();
                double avgLatencyMs = metrics.requests > 0
                        ? (double) metrics.totalLatencyMs / metrics.requests
                        : 0.0;
                copy.put(entry.getKey(), new StrategySnapshot(metrics.requests, avgLatencyMs));
            }
            return copy;
        }
    }

    private static int readIntEnv(String envName, int defaultValue) {
        String raw = System.getenv(envName);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public record Snapshot(
            long totalRequests,
            long successCount,
            long failureCount,
            long rejectedCount,
            double allTimeAvgLatencyMs,
            long recentRequests,
            long recentFailures,
            long recentRejected,
            double recentAvgLatencyMs,
            double recentFailureRate,
            double recentRejectedRate,
            double qps,
            Map<String, StrategySnapshot> strategies
    ) {}

    public record StrategySnapshot(long requests, double avgLatencyMs) {}

    private record RequestRecord(long timestampSeconds, long latencyMs, boolean failed, boolean rejected) {}

    private static final class StrategyMetrics {
        private long requests;
        private long totalLatencyMs;
    }
}
