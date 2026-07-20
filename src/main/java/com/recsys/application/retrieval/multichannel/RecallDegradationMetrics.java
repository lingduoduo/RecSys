package com.recsys.application.retrieval.multichannel;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Cumulative, thread-safe counters for silent recall-channel degradation on the
 * catalog path. Recorded inside {@link MultiChannelRecallService} for every caller;
 * surfaced (on 6010) by {@code CatalogLoadService} at {@code GET /health/load}.
 */
public final class RecallDegradationMetrics {

    public enum Reason { REJECTED, TIMEOUT, ERROR }

    private final Map<String, Map<Reason, LongAdder>> byChannel = new ConcurrentHashMap<>();
    private final AtomicLong totalRecalls = new AtomicLong();
    private final AtomicLong degradedRecalls = new AtomicLong();

    public static Reason classify(Throwable t) {
        Throwable c = t;
        while (c instanceof CompletionException && c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        if (c instanceof RejectedExecutionException) return Reason.REJECTED;
        if (c instanceof TimeoutException) return Reason.TIMEOUT;
        return Reason.ERROR;
    }

    /** One non-primary recall invocation (the denominator for degradedRatio). */
    public void recordTotal() {
        totalRecalls.incrementAndGet();
    }

    /** One degraded non-primary channel within a recall. */
    public void record(String channel, Reason reason) {
        byChannel
                .computeIfAbsent(channel, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(reason, k -> new LongAdder())
                .increment();
        degradedRecalls.incrementAndGet();
    }

    public Snapshot snapshot() {
        Map<String, Map<Reason, Long>> out = new LinkedHashMap<>();
        byChannel.forEach((channel, reasons) -> {
            Map<Reason, Long> m = new EnumMap<>(Reason.class);
            reasons.forEach((reason, adder) -> m.put(reason, adder.sum()));
            out.put(channel, m);
        });
        long total = totalRecalls.get();
        long degraded = degradedRecalls.get();
        double ratio = total == 0 ? 0.0 : degraded / (double) total;
        return new Snapshot(out, total, degraded, ratio);
    }

    public record Snapshot(Map<String, Map<Reason, Long>> byChannel,
                           long totalRecalls,
                           long degradedRecalls,
                           double degradedRatio) {}
}
