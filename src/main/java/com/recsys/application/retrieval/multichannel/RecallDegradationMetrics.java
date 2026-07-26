package com.recsys.application.retrieval.multichannel;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
// Not final: TraceIdAspect advises every com.recsys bean outside infrastructure,
// so Spring must be able to generate a CGLIB subclass of this component.
@Component
public class RecallDegradationMetrics {

    public enum Reason { REJECTED, TIMEOUT, ERROR }

    private final Map<String, Map<Reason, LongAdder>> byChannel = new ConcurrentHashMap<>();
    private final EnumMap<RecallResult.DegradationOutcome, LongAdder> byOutcome =
            new EnumMap<>(RecallResult.DegradationOutcome.class);
    private final AtomicLong totalRecalls = new AtomicLong();
    private final AtomicLong degradedRecalls = new AtomicLong();

    public RecallDegradationMetrics() {
        for (RecallResult.DegradationOutcome outcome : RecallResult.DegradationOutcome.values()) {
            byOutcome.put(outcome, new LongAdder());
        }
    }

    @Autowired
    public RecallDegradationMetrics(MeterRegistry registry) {
        this();
        registerMetrics(registry);
    }

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
    }

    /** One non-primary recall request that degraded at all (denominator for degradedRatio). */
    public void recordDegradedRequest() {
        degradedRecalls.incrementAndGet();
    }

    public void recordOutcome(RecallResult.DegradationOutcome outcome) {
        byOutcome.get(java.util.Objects.requireNonNull(outcome, "outcome")).increment();
    }

    /**
     * Registers only the fixed outcome dimension. Per-channel details remain
     * available in the operational snapshot and never become metric tags.
     */
    public void registerMetrics(MeterRegistry registry) {
        java.util.Objects.requireNonNull(registry, "registry");
        byOutcome.forEach((outcome, counter) ->
                FunctionCounter.builder("recsys.recall.degradation.outcomes", counter, LongAdder::sum)
                        .tag("outcome", outcome.wireValue())
                        .register(registry));
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
        Map<RecallResult.DegradationOutcome, Long> outcomeCounts =
                new EnumMap<>(RecallResult.DegradationOutcome.class);
        byOutcome.forEach((outcome, counter) -> outcomeCounts.put(outcome, counter.sum()));
        return new Snapshot(out, outcomeCounts, total, degraded, ratio);
    }

    public record Snapshot(Map<String, Map<Reason, Long>> byChannel,
                           Map<RecallResult.DegradationOutcome, Long> byOutcome,
                           long totalRecalls,
                           long degradedRecalls,
                           double degradedRatio) {}
}
