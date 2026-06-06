package com.recsys.streaming;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight request metrics for the Armeria online-serving path.
 */
public final class OnlineServingMetricsService {
    private static final Logger log = LoggerFactory.getLogger(OnlineServingMetricsService.class);
    private static final int DEFAULT_WINDOW_SECONDS = 60;
    private static final int MAX_STRATEGIES = 50;

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

    private static final int RESERVOIR_SIZE = 512;
    private final long[] reservoir = new long[RESERVOIR_SIZE];
    private long reservoirCount = 0L;
    private final Object reservoirLock = new Object();

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
        recordStrategy(latencyMs, false, strategy);
    }

    public void recordFailure(long latencyMs) {
        record(latencyMs, true, false);
    }

    public void recordFailure(long latencyMs, String strategy) {
        record(latencyMs, true, false);
        recordStrategy(latencyMs, true, strategy);
    }

    public void recordRejected() {
        record(0L, true, true);
    }

    public Snapshot snapshot() {
        long total = totalRequests.get();
        double allTimeAvgLatencyMs = total > 0 ? (double) totalLatencyMs.get() / total : 0.0;

        long now = System.currentTimeMillis() / 1000L;
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

        Map<String, StrategySnapshot> strategies = strategySnapshot(total);
        double recentFailureRate = recentTotal > 0 ? (double) recentFailures / recentTotal : 0.0;
        double recentRejectedRate = recentTotal > 0 ? (double) recentRejected / recentTotal : 0.0;
        double qps = (double) recentTotal / windowSeconds;

        long[] pctls = percentiles(50, 95, 99);
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
                pctls[0],
                pctls[1],
                pctls[2],
                strategies
        );
    }

    // Volatile snapshot shared across all gauge lambdas during a single Prometheus scrape.
    // Each scrape fires all lambdas sequentially from one thread; the first lambda refreshes
    // the cache so all 6 gauges reflect the same snapshot computation.
    private volatile Snapshot gaugeSnapshot = null;
    private volatile long gaugeSnapshotExpiresMs = 0L;

    private Snapshot scrapeSnapshot() {
        long now = System.currentTimeMillis();
        if (now < gaugeSnapshotExpiresMs && gaugeSnapshot != null) {
            return gaugeSnapshot;
        }
        Snapshot s = snapshot();
        gaugeSnapshot = s;
        gaugeSnapshotExpiresMs = now + 1_000L; // 1-second scrape cache
        return s;
    }

    public void registerGauges(MeterRegistry registry) {
        if (registry.find("online_serving_qps").gauge() != null) {
            return; // already registered in this registry — skip to prevent duplicate-meter error
        }
        Gauge.builder("online_serving_qps", this, s -> s.scrapeSnapshot().qps())
                .description("Observed QPS in the recent window")
                .register(registry);
        Gauge.builder("online_serving_failure_rate", this, s -> s.scrapeSnapshot().recentFailureRate())
                .description("Recent request failure rate (0.0–1.0)")
                .register(registry);
        Gauge.builder("online_serving_rejected_rate", this, s -> s.scrapeSnapshot().recentRejectedRate())
                .description("Recent request rejection rate (0.0–1.0)")
                .register(registry);
        Gauge.builder("online_serving_p50_ms", this, s -> (double) s.scrapeSnapshot().p50Ms())
                .description("P50 request latency in milliseconds (lifetime reservoir estimate)")
                .baseUnit("ms")
                .register(registry);
        Gauge.builder("online_serving_p95_ms", this, s -> (double) s.scrapeSnapshot().p95Ms())
                .description("P95 request latency in milliseconds (lifetime reservoir estimate)")
                .baseUnit("ms")
                .register(registry);
        Gauge.builder("online_serving_p99_ms", this, s -> (double) s.scrapeSnapshot().p99Ms())
                .description("P99 request latency in milliseconds (lifetime reservoir estimate)")
                .baseUnit("ms")
                .register(registry);
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

        // Reservoir: replace a random slot once full (Vitter's Algorithm R, simplified).
        synchronized (reservoirLock) {
            long n = ++reservoirCount;
            long slotL = n <= RESERVOIR_SIZE ? n - 1 : ThreadLocalRandom.current().nextLong(n);
            if (slotL < RESERVOIR_SIZE) {
                reservoir[(int) slotL] = latencyMs;
            }
        }

        long now = System.currentTimeMillis() / 1000L;
        synchronized (lock) {
            evict(now);
            window.addLast(new RequestRecord(now, latencyMs, failed, rejected));
            windowTotal++;
            if (failed) windowFailures++;
            if (rejected) windowRejected++;
            windowLatencyMs += latencyMs;
        }
    }

    private long[] percentiles(int... ranks) {
        long[] result = new long[ranks.length];
        long[] sorted;
        int count;
        synchronized (reservoirLock) {
            count = (int) Math.min(reservoirCount, RESERVOIR_SIZE);
            if (count == 0) return result;
            sorted = java.util.Arrays.copyOf(reservoir, count);
        }
        java.util.Arrays.sort(sorted);
        for (int i = 0; i < ranks.length; i++) {
            int idx = Math.max(0, Math.min(count - 1, (int) Math.ceil(ranks[i] / 100.0 * count) - 1));
            result[i] = sorted[idx];
        }
        return result;
    }

    private void evict(long nowSeconds) {
        long cutoff = nowSeconds - windowSeconds;
        while (!window.isEmpty() && window.peekFirst().timestampSeconds < cutoff) {
            RequestRecord r = window.pollFirst();
            windowTotal--;
            if (r.failed) windowFailures--;
            if (r.rejected) windowRejected--;
            windowLatencyMs -= r.latencyMs;
        }
    }

    private void recordStrategy(long latencyMs, boolean failed, String strategy) {
        String key = strategy == null || strategy.isBlank() ? "unknown" : strategy.trim();
        synchronized (strategyLock) {
            if (!strategyMetrics.containsKey(key) && strategyMetrics.size() >= MAX_STRATEGIES) {
                log.warn("Strategy metrics map has {} entries; ignoring unknown strategy '{}'", MAX_STRATEGIES, key);
                return;
            }
            StrategyMetrics metrics = strategyMetrics.computeIfAbsent(key, ignored -> new StrategyMetrics());
            metrics.requests++;
            if (failed) metrics.failureCount++;
            metrics.totalLatencyMs += latencyMs;
        }
    }

    private Map<String, StrategySnapshot> strategySnapshot(long total) {
        synchronized (strategyLock) {
            Map<String, StrategySnapshot> copy = new LinkedHashMap<>();
            for (Map.Entry<String, StrategyMetrics> entry : strategyMetrics.entrySet()) {
                StrategyMetrics m = entry.getValue();
                double avgLatencyMs = m.requests > 0 ? (double) m.totalLatencyMs / m.requests : 0.0;
                double failureRate = m.requests > 0 ? (double) m.failureCount / m.requests : 0.0;
                double share = total > 0 ? (double) m.requests / total : 0.0;
                copy.put(entry.getKey(), new StrategySnapshot(m.requests, m.failureCount, avgLatencyMs, failureRate, share));
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
            // Lifetime reservoir estimates (512-slot, Vitter Algorithm R) — not window-scoped.
            // Values converge to the true distribution but lag window-scoped metrics after traffic changes.
            long p50Ms,
            long p95Ms,
            long p99Ms,
            Map<String, StrategySnapshot> strategies
    ) {}

    public record StrategySnapshot(
            long requests,
            long failureCount,
            double avgLatencyMs,
            double failureRate,
            double share
    ) {}

    private record RequestRecord(long timestampSeconds, long latencyMs, boolean failed, boolean rejected) {}

    private static final class StrategyMetrics {
        private long requests;
        private long failureCount;
        private long totalLatencyMs;
    }
}
