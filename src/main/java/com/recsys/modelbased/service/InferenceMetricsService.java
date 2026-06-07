package com.recsys.modelbased.service;

import com.recsys.modelbased.config.HealthProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks inference metrics for health probes and observability.
 *
 * All-time counters use AtomicLong (lock-free). The rolling window for
 * recent throughput, latency, and failure rate uses a synchronized deque
 * because evict+aggregate is a compound operation that can't be done atomically.
 */
@Service
public class InferenceMetricsService {

    private static final Logger log = LoggerFactory.getLogger(InferenceMetricsService.class);
    private static final int MAX_VARIANTS = 50;

    private final int windowSeconds;

    // All-time counters — updated with a single CAS, no lock needed
    private final AtomicLong totalRequests  = new AtomicLong();
    private final AtomicLong successCount   = new AtomicLong();
    private final AtomicLong failureCount   = new AtomicLong();
    private final AtomicLong totalLatencyMs = new AtomicLong();

    // Micrometer counters
    private final Counter successCounter;
    private final Counter failureCounter;

    // Rolling window — needed for recency-sensitive readiness checks.
    // Running totals (windowTotal, windowFailures, windowLatencyMs) mirror the deque so that
    // snapshot() reads are O(1) instead of O(window-size). They are decremented in evict().
    private final Deque<RequestRecord> window = new ArrayDeque<>();
    private long windowTotal     = 0;
    private long windowFailures  = 0;
    private long windowLatencyMs = 0;
    private final Object lock = new Object();

    // Variant metrics use a separate lock so recording a variant doesn't block window eviction.
    private final Map<String, VariantMetrics> variantMetrics = new TreeMap<>();
    private final Object variantLock = new Object();

    public InferenceMetricsService(HealthProperties props, MeterRegistry registry) {
        this.windowSeconds = props.getWindowSeconds();
        this.successCounter = registry.counter("recsys.inference.requests", "result", "success");
        this.failureCounter = registry.counter("recsys.inference.requests", "result", "failure");
        Gauge.builder("recsys.inference.recent_failure_rate", this,
                s -> s.snapshot().recentFailureRate())
                .description("Rolling-window failure rate (0–1)")
                .register(registry);
        Gauge.builder("recsys.inference.throughput_per_second", this,
                s -> s.snapshot().throughputPerSecond())
                .description("Rolling-window requests per second")
                .register(registry);
    }

    public void recordSuccess(long latencyMs) {
        record(latencyMs, false);
    }

    public void recordSuccess(long latencyMs, String variant, String modelVersion) {
        record(latencyMs, false);
        recordVariant(latencyMs, false, variant, modelVersion);
    }

    public void recordFailure(long latencyMs) {
        record(latencyMs, true);
    }

    public void recordFailure(long latencyMs, String variant) {
        record(latencyMs, true);
        recordVariant(latencyMs, true, variant, null);
    }

    private void record(long latencyMs, boolean failed) {
        // Lock-free updates to all-time counters
        totalRequests.incrementAndGet();
        if (failed) failureCount.incrementAndGet();
        else         successCount.incrementAndGet();
        totalLatencyMs.addAndGet(latencyMs);

        // Micrometer counter increments (thread-safe, no lock needed)
        if (failed) failureCounter.increment();
        else         successCounter.increment();

        // Rolling window requires a lock: evict + add + running-total updates must be atomic
        long now = System.currentTimeMillis() / 1_000L;
        synchronized (lock) {
            evict(now);
            window.addLast(new RequestRecord(now, latencyMs, failed));
            windowTotal++;
            if (failed) windowFailures++;
            windowLatencyMs += latencyMs;
        }
    }

    public Snapshot snapshot() {
        // Read all-time atomics — no lock needed
        long total    = totalRequests.get();
        long success  = successCount.get();
        long failures = failureCount.get();
        double allTimeAvgLatencyMs = total > 0 ? (double) totalLatencyMs.get() / total : 0.0;

        // Read rolling window under lock for consistency — O(1) via running totals
        long now = System.currentTimeMillis() / 1_000L;
        long recentTotal;
        long recentFailures;
        double recentAvgLatencyMs;
        synchronized (lock) {
            evict(now);
            recentTotal        = windowTotal;
            recentFailures     = windowFailures;
            recentAvgLatencyMs = windowTotal > 0 ? (double) windowLatencyMs / windowTotal : 0.0;
        }

        double recentFailureRate    = recentTotal > 0 ? (double) recentFailures / recentTotal : 0.0;
        double throughputPerSecond  = windowSeconds > 0 ? (double) recentTotal / windowSeconds : 0.0;

        return new Snapshot(
                total, success, failures, allTimeAvgLatencyMs,
                recentTotal, recentFailures, recentAvgLatencyMs, recentFailureRate,
                throughputPerSecond
        );
    }

    public ABTestSnapshot abTestSnapshot(String controlVariant) {
        synchronized (variantLock) {
            VariantMetrics control = variantMetrics.get(controlVariant);
            double controlSuccessRate = control == null ? 0.0 : control.successRate();
            double controlAvgLatencyMs = control == null ? 0.0 : control.avgLatencyMs();

            Map<String, VariantSnapshot> variants = new LinkedHashMap<>();
            for (Map.Entry<String, VariantMetrics> entry : variantMetrics.entrySet()) {
                VariantMetrics metrics = entry.getValue();
                variants.put(entry.getKey(), new VariantSnapshot(
                        entry.getKey(),
                        metrics.modelVersion,
                        metrics.totalRequests,
                        metrics.successCount,
                        metrics.failureCount,
                        metrics.successRate(),
                        metrics.avgLatencyMs(),
                        control == null ? null : metrics.successRate() - controlSuccessRate,
                        control == null ? null : metrics.avgLatencyMs() - controlAvgLatencyMs
                ));
            }
            return new ABTestSnapshot(controlVariant, variants);
        }
    }

    private void evict(long nowSeconds) {
        long cutoff = nowSeconds - windowSeconds;
        while (!window.isEmpty() && window.peekFirst().timestampSeconds < cutoff) {
            RequestRecord r = window.pollFirst();
            windowTotal--;
            if (r.failed) windowFailures--;
            windowLatencyMs -= r.latencyMs;
        }
    }

    private void recordVariant(long latencyMs, boolean failed, String variant, String modelVersion) {
        String key = normalizeVariant(variant);
        synchronized (variantLock) {
            if (!variantMetrics.containsKey(key) && variantMetrics.size() >= MAX_VARIANTS) {
                log.warn("Variant metrics map has {} entries; ignoring unknown variant '{}'", MAX_VARIANTS, key);
                return;
            }
            VariantMetrics metrics = variantMetrics.computeIfAbsent(key, ignored -> new VariantMetrics());
            metrics.totalRequests++;
            if (failed) {
                metrics.failureCount++;
            } else {
                metrics.successCount++;
            }
            metrics.totalLatencyMs += latencyMs;
            if (modelVersion != null && !modelVersion.isBlank()) {
                metrics.modelVersion = modelVersion;
            }
        }
    }

    private static String normalizeVariant(String variant) {
        if (variant == null || variant.isBlank()) {
            return "unknown";
        }
        return variant.trim();
    }

    public record Snapshot(
            long   totalRequests,
            long   successCount,
            long   failureCount,
            double allTimeAvgLatencyMs,
            long   recentRequests,
            long   recentFailures,
            double recentAvgLatencyMs,
            double recentFailureRate,
            double throughputPerSecond
    ) {}

    public record ABTestSnapshot(
            String controlVariant,
            Map<String, VariantSnapshot> variants
    ) {}

    public record VariantSnapshot(
            String variant,
            String modelVersion,
            long totalRequests,
            long successCount,
            long failureCount,
            double successRate,
            double avgLatencyMs,
            Double successRateDeltaVsControl,
            Double avgLatencyDeltaVsControlMs
    ) {}

    private record RequestRecord(long timestampSeconds, long latencyMs, boolean failed) {}

    private static final class VariantMetrics {
        private String modelVersion;
        private long totalRequests;
        private long successCount;
        private long failureCount;
        private long totalLatencyMs;

        private double successRate() {
            return totalRequests > 0 ? (double) successCount / totalRequests : 0.0;
        }

        private double avgLatencyMs() {
            return totalRequests > 0 ? (double) totalLatencyMs / totalRequests : 0.0;
        }
    }
}
