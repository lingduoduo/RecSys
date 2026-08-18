package com.recsys.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class JvmMetricsBinderTest {

    private static final List<String> EXPECTED = List.of(
            "jvm.memory.used",
            "jvm.memory.committed",
            "jvm.memory.max",
            "jvm.threads.live",
            "jvm.threads.daemon",
            "system.cpu.count");

    private static Set<String> meterNames(SimpleMeterRegistry registry) {
        return registry.getMeters().stream()
                .map(m -> m.getId().getName())
                .collect(Collectors.toSet());
    }

    @Test
    void bindsTheJvmMeterSet() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        JvmMetricsBinder.bindTo(registry);
        assertThat(meterNames(registry)).containsAll(EXPECTED);
    }

    /**
     * {@code registry.getMeters().size()} does not distinguish a guarded rebind from an
     * unguarded one: Micrometer's own {@code Gauge.Builder.register()} already dedupes
     * {@code JvmMemoryMetrics}/{@code JvmThreadMetrics}/{@code ProcessorMetrics} by meter ID, so
     * that count stays flat either way. The thing the {@code BOUND} guard is actually load-bearing
     * for — not installing a second {@link io.micrometer.core.instrument.binder.jvm.JvmGcMetrics}
     * (and therefore a second JMX notification listener that would double-count every GC pause)
     * on a second bind of the *same* registry — has to be asserted on the retained-instance count
     * directly.
     */
    @Test
    void secondBindOnSameRegistryDoesNotRetainAnotherGcMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        int before = JvmMetricsBinder.retainedGcMetricsCount();

        JvmMetricsBinder.bindTo(registry);
        int afterFirst = JvmMetricsBinder.retainedGcMetricsCount();
        assertThat(afterFirst).as("first bind must retain exactly one JvmGcMetrics")
                .isEqualTo(before + 1);

        JvmMetricsBinder.bindTo(registry);
        int afterSecond = JvmMetricsBinder.retainedGcMetricsCount();
        assertThat(afterSecond)
                .as("re-binding the same registry must not retain a second JvmGcMetrics — that "
                        + "would install a second JMX listener and double-count every GC pause")
                .isEqualTo(afterFirst);

        SimpleMeterRegistry otherRegistry = new SimpleMeterRegistry();
        JvmMetricsBinder.bindTo(otherRegistry);
        int afterOther = JvmMetricsBinder.retainedGcMetricsCount();
        assertThat(afterOther).as("binding a genuinely different registry must retain its own JvmGcMetrics")
                .isEqualTo(afterSecond + 1);
    }

    @Test
    void nullRegistryIsIgnored() {
        JvmMetricsBinder.bindTo(null);   // must not throw
    }

    /**
     * The trap documented in 18_Fault_Tolerance §8.3: Micrometer holds a gauge's backing state
     * by WeakReference, and a FunctionCounter whose state is collected freezes silently rather
     * than reporting NaN. {@code JvmMetricsBinder.RETAINED}'s javadoc claims it exists to keep
     * {@code JvmGcMetrics} itself reachable so this trap cannot hit its {@code jvm.gc.*} meters.
     *
     * <p>Unlike the previous version of this test, which asserted on {@code jvm.memory.used} — a
     * {@code JvmMemoryMetrics} gauge with no relationship to {@code JvmGcMetrics} or {@code
     * RETAINED} at all, so deleting {@code RETAINED} would not have touched it — this asserts on
     * {@code jvm.gc.pause}, the meter {@code RETAINED}'s javadoc names.
     *
     * <p><b>Mutation result, recorded honestly:</b> removing {@code RETAINED.add(gcMetrics)} and
     * rerunning this test does <i>not</i> turn it red. {@code jvm.gc.pause} keeps recording new
     * pauses across repeated real, provoked GC (verified standalone with 2000 forced-GC cycles
     * under a constrained 256m {@code -XX:+UseSerialGC} heap, and against this exact mutation in
     * this class) regardless of whether {@code RETAINED} holds a reference. The reason:
     * {@code JvmGcMetrics.bindTo} registers its listener with each {@code GarbageCollectorMXBean}
     * via {@code NotificationEmitter.addNotificationListener}, and that MXBean is itself a
     * GC-root-reachable JVM singleton holding the listener (and, via its implicit outer-class
     * reference, the whole {@code JvmGcMetrics} instance) strongly for the life of the JVM —
     * independent of any application-level reference. So the WeakReference trap this test's
     * javadoc-adjacent class comment describes is real in general (§8.3, and demonstrably real
     * for {@code SplunkHecMetrics.RETAINED}) but is not the mechanism protecting {@code
     * JvmGcMetrics} specifically; {@code RETAINED}'s only mutation-provable effect here is what
     * {@link #secondBindOnSameRegistryDoesNotRetainAnotherGcMetrics()} already pins — that a
     * second bind on the same registry does not install a second JMX listener. This test is kept
     * anyway because "meters keep reporting after real GC pressure" is still a true, worthwhile
     * regression guard; it is just not evidence for {@code RETAINED} specifically.
     */
    @Test
    void metersStillReportAfterGarbageCollection() throws InterruptedException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        JvmMetricsBinder.bindTo(registry);

        WeakReference<Object> canary = new WeakReference<>(new Object());
        provokeGcUntilCollected(canary);

        // JMX GC notifications are delivered asynchronously off the GC thread, so the count may
        // not have caught up the instant System.gc() returns -- poll rather than read once.
        long pauseCountAfterFirstRound = awaitGcPauseCountAbove(registry, 0L);

        // A second, independent round of provoked GC. If JvmGcMetrics (or the AtomicLong/JMX
        // listener state behind its meters) had been collected between rounds, this round's
        // pauses would go unrecorded and the count would never move past its first-round value.
        WeakReference<Object> secondCanary = new WeakReference<>(new Object());
        provokeGcUntilCollected(secondCanary);

        long pauseCountAfterSecondRound = awaitGcPauseCountAbove(registry, pauseCountAfterFirstRound);
        assertThat(pauseCountAfterSecondRound)
                .as("jvm.gc.pause must keep recording new pauses after further GC, not freeze at "
                        + "its first-round value")
                .isGreaterThan(pauseCountAfterFirstRound);
    }

    private static void provokeGcUntilCollected(WeakReference<Object> canary) {
        for (int i = 0; i < 40 && canary.get() != null; i++) {
            System.gc();
            byte[] pressure = new byte[8 * 1024 * 1024];
            assertThat(pressure).isNotNull();
        }
        assertThat(canary.get()).as("a real GC must have run for this test to mean anything")
                .isNull();
    }

    /** Polls up to five seconds for jvm.gc.pause's recorded count to exceed {@code floor}. */
    private static long awaitGcPauseCountAbove(SimpleMeterRegistry registry, long floor) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        long count;
        do {
            count = gcPauseCount(registry);
            if (count > floor) {
                return count;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return count;
    }

    private static long gcPauseCount(SimpleMeterRegistry registry) {
        return registry.find("jvm.gc.pause").timers().stream()
                .mapToLong(io.micrometer.core.instrument.Timer::count)
                .sum();
    }
}
