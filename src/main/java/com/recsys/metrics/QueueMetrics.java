package com.recsys.metrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
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
 * <p><b>That throw is three-way, not binary, and the distinction is load-bearing.</b>
 * {@link #register(MeterRegistry, String, Source)} compares the incoming {@link Source} to
 * whatever is already registered under that name by <em>identity</em> ({@code ==}, not
 * {@code equals}): the same object re-registered under the same name is a no-op, while a
 * <em>different</em> object under that name still throws {@link IllegalStateException}. Collapsing
 * this to "any duplicate name is fine" — making registration idempotent across the board — would
 * reproduce, in this class's own layer, exactly the silent-aliasing failure described above: a
 * caller that constructs a fresh {@code Source} and registers it under a name still in use would
 * succeed quietly and read the first object's numbers forever, which is the one outcome this class
 * exists to make impossible. A same-instance double-call is provably not that bug — same object,
 * same readings — so treating it as harmless costs nothing, but a different-instance collision is
 * always either a real second queue (a bug elsewhere) or a caller that meant to replace the first
 * registration and forgot to say so; either way it must throw, not merge silently.
 * {@link #unregister(MeterRegistry, String)} is the explicit way to say "replace it": it removes
 * both the {@link #REGISTERED} entry and the four meters from {@code registry}, after which
 * {@link #register} can be called again — for a new {@link Source} or the same one — as a normal
 * first registration. It is not wired into any {@code Source}'s {@code close()}; PR #296
 * deliberately decided meters survive {@code close()} so a drained queue's last readings stay
 * visible, and this method must not quietly reverse that.
 *
 * <p>Second, {@link Gauge.Builder} offers {@code strongReference(boolean)} but
 * {@link FunctionCounter.Builder} <b>does not</b>. Micrometer holds meter state weakly by
 * default, and per §8.3 the two meter types then fail <em>differently</em>: a collected gauge
 * reports {@code NaN} — visibly wrong — while a {@code FunctionCounter} freezes at its last value
 * and reports no error, which is indistinguishable from a healthy quiet queue. The rejection
 * counter is therefore the one meter that cannot protect itself on its own.
 *
 * <p><b>Two independent strong paths keep the single {@link Source} shared by all four meters
 * alive, and this class deliberately keeps both rather than picking one.</b> The three gauges
 * pass {@code strongReference(true)}, which holds {@code source} strongly for as long as the
 * gauge itself is reachable through the registry — a path that has nothing to do with
 * {@link #REGISTERED}. Separately, {@link #REGISTERED} holds every {@link Source} strongly for
 * the JVM's life, independent of whether any gauge does. Either path alone is sufficient; the
 * unprotected {@code FunctionCounter} rides on whichever one currently holds. <b>Because the two
 * paths are redundant, no test run against this class can attribute liveness to one of them in
 * isolation</b> — removing {@code REGISTERED}'s retention while the gauges still hold strongly
 * changes nothing observable, and the reverse is equally true. Proving the map's contribution
 * specifically requires removing both {@code strongReference(true)} and the map's retention
 * together and confirming the meters break; see the discriminating mutation documented on
 * {@code QueueMetricsGcObservationTest}. <b>Do not "clean up" that map</b> — its primary job is
 * rejecting a duplicate queue name (see above), and it also contributes to keeping every
 * {@link Source} reachable, even though that contribution cannot be isolated by a test while the
 * gauges' own strong references still stand.
 *
 * <p>Deliberately <em>not</em> relied on: that a live {@code WorkerBulkhead} or
 * {@code AsyncEventPublisher} is reachable through the Armeria service graph or its own drain
 * thread. Both are true today and neither is a mechanism — the drain-thread path evaporates at
 * {@code close()}, and the service-graph path is an argument a refactor could invalidate with no
 * test failing. That is the same category of mistake {@code JvmMetricsBinder.RETAINED}'s javadoc
 * made before it was disproved, and this class's own retention story was caught making a
 * narrower version of it: an earlier draft of this javadoc claimed {@link #REGISTERED} alone
 * "closes" the weak-reference failure mode, which a mutation run showed is not attributable —
 * removing only the map's {@code byName.put} left {@code QueueMetricsGcObservationTest} green,
 * because the gauges' {@code strongReference(true)} already covered the same object.
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
     * Registered sources, keyed by registry identity then queue name. Its primary job is
     * rejecting duplicate queue names (see the class javadoc); holding each {@link Source}
     * strongly also contributes to keeping every meter's state alive, redundantly with the three
     * gauges' own {@code strongReference(true)}. Because that second contribution cannot be
     * isolated from the gauges' own strong references (see the class javadoc's discriminating
     * mutation), do not read "also contributes to retention" as a claim this map is what makes
     * any specific meter survive GC on its own. Identity-keyed because {@code MeterRegistry}
     * does not define equality. {@link #unregister(MeterRegistry, String)} is the only path that
     * removes an entry — a normal {@link #register} call never does, whether it succeeds, no-ops
     * on a same-instance re-registration, or throws on a different-instance one.
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

        Map<String, Source> byName = REGISTERED.computeIfAbsent(registry, r -> new HashMap<>());
        Source existing = byName.get(queueName);
        if (existing != null) {
            if (existing == source) {
                // Same object, same readings: provably not the aliasing bug below, so a
                // defensive double-call (e.g. idempotent startup code) is harmless.
                log.debug("Queue '{}' re-registered with the same Source instance; no-op.", queueName);
                return;
            }
            throw new IllegalStateException(
                    "Queue '" + queueName + "' is already registered on this MeterRegistry. "
                            + "Micrometer would silently return the first meter and discard this "
                            + "source, so the second queue would report the first one's depth.");
        }

        int capacity = source.capacity();
        if (capacity <= 0) {
            throw new IllegalArgumentException(
                    "Queue '" + queueName + "' reported a non-positive capacity (" + capacity
                            + "). Capacity is a positive invariant: an unbounded queue needs its own "
                            + "metric shape, not a sentinel capacity threaded through this one.");
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

    /**
     * Explicit teardown: removes {@code queueName}'s entry from {@link #REGISTERED} and its four
     * meter families from {@code registry}, so a second boot in the same JVM (a re-run against
     * {@code PrometheusMeterRegistries.defaultRegistry()}, or in-process co-hosting of two mains)
     * can {@link #register} again afterward. A no-op, not an error, when {@code queueName} was
     * never registered on {@code registry} — teardown code should not have to know whether
     * registration happened.
     *
     * <p>Not called automatically from a queue's {@code close()}: PR #296 deliberately decided
     * meters survive {@code close()} so a drained queue's last readings stay visible rather than
     * vanishing from the registry. This method is an explicit affordance for the narrower case
     * that decision didn't cover — replacing a registration outright — not a reversal of it.
     */
    public static synchronized void unregister(MeterRegistry registry, String queueName) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(queueName, "queueName");

        Map<String, Source> byName = REGISTERED.get(registry);
        if (byName == null || byName.remove(queueName) == null) {
            return;
        }

        removeMeter(registry, "recsys.queue.depth", queueName);
        removeMeter(registry, "recsys.queue.capacity", queueName);
        removeMeter(registry, "recsys.queue.utilization", queueName);
        for (RejectionReason reason : RejectionReason.values()) {
            Meter meter = registry.find("recsys.queue.rejected")
                    .tag("queue", queueName)
                    .tag("reason", reason.tag())
                    .meter();
            if (meter != null) {
                registry.remove(meter);
            }
        }

        log.info("Unregistered queue metrics for '{}'", queueName);
    }

    private static void removeMeter(MeterRegistry registry, String meterName, String queueName) {
        Meter meter = registry.find(meterName).tag("queue", queueName).meter();
        if (meter != null) {
            registry.remove(meter);
        }
    }
}
