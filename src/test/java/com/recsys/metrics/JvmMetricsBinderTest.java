package com.recsys.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic assertions for {@link JvmMetricsBinder} — no {@code System.gc()}, no polling
 * loop waiting on a JMX notification. The GC-observation test ({@code
 * metersStillReportAfterGarbageCollection}) is split out into {@link
 * JvmMetricsBinderGcObservationTest} specifically so this class can run in the {@code
 * resilience} profile, mirroring the {@link com.recsys.jvm.GcEventTrackerTest} /
 * {@link com.recsys.jvm.GcEventTrackerLifecycleTest} split.
 */
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
}
