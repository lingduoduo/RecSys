package com.recsys.application.retrieval.multichannel;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-channel counters for recall work that never produced a result: rejected by the bounded
 * executor at submit time, or cancelled at the channel deadline. Publishes
 * {@code recsys.model.recall.tasks{result=rejected|timeout, channel=...}}.
 *
 * <p>The {@code channel} label set is closed at construction to the configured channel names
 * (plus one {@code unknown} bucket), so a label can never be request-derived and cardinality is
 * bounded regardless of what a caller passes. Every counter is registered at zero up front, so a
 * missing series means "never scraped", not "never happened".
 */
public class RecallTaskMetrics {

    public static final String METER = "recsys.model.recall.tasks";
    private static final String UNKNOWN = "unknown";

    /** Records nothing; for callers (6010/7010, tests) that do not publish these counters. */
    public static final RecallTaskMetrics NOOP = new RecallTaskMetrics();

    private final Map<String, Counter> rejected;
    private final Map<String, Counter> timedOut;

    private RecallTaskMetrics() {
        this.rejected = Map.of();
        this.timedOut = Map.of();
    }

    public RecallTaskMetrics(MeterRegistry registry, Collection<String> channelNames) {
        Objects.requireNonNull(registry, "registry");
        Map<String, Counter> rejected = new HashMap<>();
        Map<String, Counter> timedOut = new HashMap<>();
        for (String channel : channelNames) {
            register(registry, channel, rejected, timedOut);
        }
        register(registry, UNKNOWN, rejected, timedOut);
        this.rejected = Map.copyOf(rejected);
        this.timedOut = Map.copyOf(timedOut);
    }

    private static void register(MeterRegistry registry, String channel,
                                 Map<String, Counter> rejected, Map<String, Counter> timedOut) {
        rejected.put(channel, Counter.builder(METER)
                .description("Recall channel tasks that produced no result")
                .tag("result", "rejected").tag("channel", channel).register(registry));
        timedOut.put(channel, Counter.builder(METER)
                .description("Recall channel tasks that produced no result")
                .tag("result", "timeout").tag("channel", channel).register(registry));
    }

    /** The executor refused the task at submit time (queue full). */
    public void recordRejected(String channel) {
        increment(rejected, channel);
    }

    /** The task missed the channel deadline and was cancelled. */
    public void recordTimeout(String channel) {
        increment(timedOut, channel);
    }

    private static void increment(Map<String, Counter> counters, String channel) {
        if (counters.isEmpty()) {
            return;
        }
        Counter counter = channel == null ? null : counters.get(channel);
        (counter != null ? counter : counters.get(UNKNOWN)).increment();
    }
}
