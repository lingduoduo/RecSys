package com.recsys.metrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Publishes depth, capacity, utilization and reasoned rejection counts for a bounded queue.
 *
 * <p>Exists because the two bounded queues on the message path — {@code AsyncEventPublisher} and
 * {@code WorkerBulkhead} — each compute their own depth and publish it nowhere. Their drops were
 * visible only after the fact, so a queue filling up was invisible until it overflowed: the
 * "instrumentation that looks present and is observable by nothing" failure recorded in
 * 18_Fault_Tolerance §8.2.
 *
 * <p><b>Two Micrometer behaviours drive this class's shape, both measured against
 * micrometer-core 1.13.6 rather than assumed.</b>
 *
 * <p>First, registering a meter whose name and tags match an existing one <em>silently returns
 * the existing meter and discards the new state object</em> — two gauges built over different
 * objects under the same tag yield one meter, and both read the first object. A second queue
 * registering under a name already taken would therefore report the first queue's depth forever,
 * with no error anywhere. {@link #REGISTERED} exists to make that impossible: a duplicate
 * {@code (registry, queueName)} throws instead.
 *
 * <p>Second, {@link Gauge.Builder} offers {@code strongReference(boolean)} but
 * {@link FunctionCounter.Builder} <b>does not</b>. Micrometer holds meter state weakly by
 * default, and per §8.3 the two meter types then fail <em>differently</em>: a collected gauge
 * reports {@code NaN} — visibly wrong — while a {@code FunctionCounter} freezes at its last value
 * and reports no error, which is indistinguishable from a healthy quiet queue. The rejection
 * counter is therefore the one meter that cannot protect itself. It does not need to:
 * {@link #REGISTERED} holds every {@link Source} strongly for the JVM's life, so the retention
 * falls out of the duplicate-name guard rather than being a separate field whose rationale can
 * rot. <b>Do not "clean up" that map.</b>
 *
 * <p>Deliberately <em>not</em> relied on: that a live {@code WorkerBulkhead} or
 * {@code AsyncEventPublisher} is reachable through the Armeria service graph or its own drain
 * thread. Both are true today and neither is a mechanism — the drain-thread path evaporates at
 * {@code close()}, and the service-graph path is an argument a refactor could invalidate with no
 * test failing. That is the mistake {@code JvmMetricsBinder.RETAINED}'s javadoc made before it
 * was disproved.
 */
public final class QueueMetrics {

    private static final Logger log = LoggerFactory.getLogger(QueueMetrics.class);

    /** Why a queue refused work. Separated because they demand different responses. */
    public enum RejectionReason {
        /** The bound was reached. This is the backpressure signal, and the only alerted reason. */
        FULL("full"),
        /** The queue was closed and refused late work. Routine during a drain; never a page. */
        SHUTDOWN("shutdown"),
        /** The event carried no usable partition key. A data or configuration fault, not capacity. */
        INVALID_KEY("invalid_key");

        private final String tag;

        RejectionReason(String tag) { this.tag = tag; }

        public String tag() { return tag; }
    }

    /**
     * A bounded queue, as the metrics need to see it.
     *
     * <p>{@link #capacity()} must return a strictly positive value — see
     * {@link #register(MeterRegistry, String, Source)}. Implementations must also maintain
     * {@code 0 <= depth() <= capacity()}; that invariant is theirs, not this class's, and is not
     * clamped here because a violation is a bug worth seeing.
     */
    public interface Source {
        int depth();

        /** The <em>effective</em> bound of the queue that was actually constructed. */
        int capacity();

        long rejected(RejectionReason reason);
    }

    /**
     * Registered sources, keyed by registry identity then queue name. Serves two purposes at once:
     * it rejects duplicate queue names, and by holding each {@link Source} strongly it keeps every
     * meter's state alive. Identity-keyed because {@code MeterRegistry} does not define equality.
     */
    private static final Map<MeterRegistry, Map<String, Source>> REGISTERED = new IdentityHashMap<>();

    private QueueMetrics() {}

    /**
     * @throws IllegalArgumentException if {@code source.capacity()} is not strictly positive
     * @throws IllegalStateException    if {@code queueName} is already registered on {@code registry}
     */
    public static synchronized void register(MeterRegistry registry, String queueName, Source source) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(queueName, "queueName");
        Objects.requireNonNull(source, "source");

        int capacity = source.capacity();
        if (capacity <= 0) {
            throw new IllegalArgumentException(
                    "Queue '" + queueName + "' reported a non-positive capacity (" + capacity
                            + "). Capacity is a positive invariant: an unbounded queue needs its own "
                            + "metric shape, not a sentinel capacity threaded through this one.");
        }

        Map<String, Source> byName = REGISTERED.computeIfAbsent(registry, r -> new HashMap<>());
        if (byName.containsKey(queueName)) {
            throw new IllegalStateException(
                    "Queue '" + queueName + "' is already registered on this MeterRegistry. "
                            + "Micrometer would silently return the first meter and discard this "
                            + "source, so the second queue would report the first one's depth.");
        }

        Gauge.builder("recsys.queue.depth", source, Source::depth)
                .tag("queue", queueName)
                .description("Entries currently enqueued")
                .strongReference(true)
                .register(registry);

        Gauge.builder("recsys.queue.capacity", source, s -> s.capacity())
                .tag("queue", queueName)
                .description("Effective bound of the queue")
                .strongReference(true)
                .register(registry);

        // depth and capacity are read together here, in one call, so the two can never be
        // sampled at different instants the way a PromQL division of the two series would.
        Gauge.builder("recsys.queue.utilization", source, s -> (double) s.depth() / s.capacity())
                .tag("queue", queueName)
                .description("depth / capacity")
                .strongReference(true)
                .register(registry);

        for (RejectionReason reason : RejectionReason.values()) {
            FunctionCounter.builder("recsys.queue.rejected", source, s -> s.rejected(reason))
                    .tag("queue", queueName)
                    .tag("reason", reason.tag())
                    .description("Work refused by this queue")
                    .register(registry);
        }

        byName.put(queueName, source);
        log.info("Registered queue metrics for '{}' (capacity {})", queueName, capacity);
    }

    /** Package-private for tests. */
    static synchronized int registeredCount(MeterRegistry registry) {
        Map<String, Source> byName = REGISTERED.get(registry);
        return byName == null ? 0 : byName.size();
    }
}
