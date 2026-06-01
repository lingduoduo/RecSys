package com.recsys.infrastructure;

import java.util.AbstractMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Sliding-window per-key access frequency counter for hot-key detection.
 *
 * Every call to {@link #record} increments a per-key counter.  The counter uses a
 * two-bucket sliding window: a "current" bucket covers the in-progress time window and
 * a "previous" bucket covers the one just before it.  The estimated access rate blends
 * both buckets so there are no sharp step-function resets at window boundaries.
 *
 * {@link #isHot} returns {@code true} when the smoothed per-second rate for a key
 * exceeds the configured threshold — a signal to apply sharding, extended local
 * caching, or other hot-key mitigation strategies.
 *
 * Thread-safe. Designed for high-throughput serving paths; all per-key operations
 * are lock-free (AtomicLong + volatile).
 */
public final class HotKeyDetector {

    private static final int  DEFAULT_WINDOW_SECONDS   = 10;
    private static final long DEFAULT_HOT_THRESHOLD_PS = 500L; // accesses / second

    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, WindowCounter> intCounters = new ConcurrentHashMap<>();
    private final long windowMs;
    private final double hotThresholdPerSecond;

    public HotKeyDetector() {
        this(DEFAULT_WINDOW_SECONDS, DEFAULT_HOT_THRESHOLD_PS);
    }

    public HotKeyDetector(int windowSeconds, long hotThresholdPerSecond) {
        this.windowMs = Math.max(1L, windowSeconds) * 1_000L;
        this.hotThresholdPerSecond = Math.max(1L, hotThresholdPerSecond);
    }

    /** Records one access for {@code key}. Thread-safe, lock-free per-key. */
    public void record(String key) {
        long now = System.currentTimeMillis();
        counters.computeIfAbsent(key, k -> new WindowCounter(now)).record(now, windowMs);
    }

    /** Records one access for integer {@code id}. Avoids String allocation on hot paths. */
    public void record(int id) {
        long now = System.currentTimeMillis();
        intCounters.computeIfAbsent(id, k -> new WindowCounter(now)).record(now, windowMs);
    }

    /** Returns {@code true} when the smoothed per-second access rate exceeds the threshold. */
    public boolean isHot(String key) {
        WindowCounter c = counters.get(key);
        return c != null && c.rate(System.currentTimeMillis(), windowMs) >= hotThresholdPerSecond;
    }

    /** Integer-keyed variant of {@link #isHot(String)}. */
    public boolean isHot(int id) {
        WindowCounter c = intCounters.get(id);
        return c != null && c.rate(System.currentTimeMillis(), windowMs) >= hotThresholdPerSecond;
    }

    /** Returns the smoothed per-second access rate for {@code key}, or 0 if never recorded. */
    public double accessRate(String key) {
        WindowCounter c = counters.get(key);
        return c == null ? 0.0 : c.rate(System.currentTimeMillis(), windowMs);
    }

    /**
     * Returns the top {@code n} hottest keys sorted by descending access rate.
     * Each entry is {@code (key, accessRatePerSecond)}.
     */
    public List<Map.Entry<String, Double>> topHotKeys(int n) {
        long now = System.currentTimeMillis();
        return counters.entrySet().stream()
                .map(e -> (Map.Entry<String, Double>)
                        new AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue().rate(now, windowMs)))
                .filter(e -> e.getValue() > 0.0)
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(Math.max(0, n))
                .collect(Collectors.toList());
    }

    /** Returns the total number of distinct keys being tracked. */
    public int trackedKeyCount() { return counters.size(); }

    /**
     * Evicts keys that have been idle for at least two full window periods.
     * Call periodically (e.g., from a scheduled task) to prevent unbounded map growth.
     */
    public void evictIdle() {
        long now = System.currentTimeMillis();
        counters.entrySet().removeIf(e -> {
            long sinceWindow = now - e.getValue().windowStartMs;
            return sinceWindow > 2 * windowMs && e.getValue().currentCount.get() == 0L;
        });
        intCounters.entrySet().removeIf(e -> {
            long sinceWindow = now - e.getValue().windowStartMs;
            return sinceWindow > 2 * windowMs && e.getValue().currentCount.get() == 0L;
        });
    }

    // ── Two-bucket sliding window counter ──────────────────────────────────────────

    static final class WindowCounter {
        final AtomicLong currentCount = new AtomicLong(0L);
        volatile long prevCount = 0L;
        volatile long windowStartMs;

        WindowCounter(long nowMs) { this.windowStartMs = nowMs; }

        void record(long nowMs, long windowMs) {
            // Roll over if the current window has fully elapsed.
            // Benign race on rollover: at worst prevCount is overwritten and currentCount
            // is reset twice, losing a few counts — acceptable for a frequency estimator.
            if (nowMs - windowStartMs > windowMs) {
                prevCount = currentCount.getAndSet(0L);
                windowStartMs = nowMs;
            }
            currentCount.incrementAndGet();
        }

        // Weighted blend of previous window rate and current window rate.
        // alpha = fraction of the current window elapsed (0 → full weight on previous; 1 → full weight on current).
        double rate(long nowMs, long windowMs) {
            long elapsed = Math.max(1L, nowMs - windowStartMs);
            if (elapsed > windowMs) return 0.0; // window fully elapsed, no recent activity
            double alpha = Math.min(1.0, (double) elapsed / windowMs);
            double currentRate = currentCount.get() * 1000.0 / elapsed;   // per second
            double prevRate    = prevCount * 1000.0 / windowMs;            // per second
            return alpha * currentRate + (1.0 - alpha) * prevRate;
        }
    }
}
