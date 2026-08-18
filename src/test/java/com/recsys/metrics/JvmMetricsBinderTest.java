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
     * than reporting NaN. JvmGcMetrics also holds a JMX listener that must not be collected.
     */
    @Test
    void metersStillReportAfterGarbageCollection() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        JvmMetricsBinder.bindTo(registry);

        WeakReference<Object> canary = new WeakReference<>(new Object());
        for (int i = 0; i < 20 && canary.get() != null; i++) {
            System.gc();
            byte[] pressure = new byte[8 * 1024 * 1024];
            assertThat(pressure).isNotNull();
        }
        assertThat(canary.get()).as("a real GC must have run for this test to mean anything")
                .isNull();

        Double used = registry.find("jvm.memory.used").gauges().stream()
                .map(g -> g.value()).filter(v -> !v.isNaN()).findFirst().orElse(null);
        assertThat(used).as("jvm.memory.used must still report a real value after GC")
                .isNotNull().isGreaterThan(0.0);
    }
}
