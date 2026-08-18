package com.recsys.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Binds the JVM's memory, GC, thread and processor metrics to a registry.
 *
 * <p>Armeria does not do this for us. {@code PrometheusMeterRegistries.configureRegistry} is a
 * no-op — it null-checks its argument and returns it — so a registry obtained from
 * {@code PrometheusMeterRegistries.defaultRegistry()} carries no JVM metrics at all. Heap usage
 * and GC pause time were unscrapeable on 6010, 7010 and 8010 until this class existed. The Spring
 * model service gets the same set from Actuator's auto-configuration and must not call this.
 */
public final class JvmMetricsBinder {

    private static final Logger log = LoggerFactory.getLogger(JvmMetricsBinder.class);

    /**
     * {@code PrometheusMeterRegistries.defaultRegistry()} is a JVM-wide singleton, and more than
     * one component may reasonably ask for the JVM metrics on it. Re-binding is not harmless:
     * {@link JvmGcMetrics} installs a JMX notification listener per bind, so a second call would
     * double-count every pause. Identity comparison, because MeterRegistry does not define
     * equality.
     */
    private static final Set<MeterRegistry> BOUND =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Holds every {@link JvmGcMetrics} for the life of the JVM. Two reasons, both load-bearing:
     * it makes the second-bind guard observable and testable — {@code
     * JvmMetricsBinderTest.secondBindOnSameRegistryDoesNotRetainAnotherGcMetrics} reads this
     * list's size directly — and {@link JvmGcMetrics} is {@code Closeable} and owns a JMX
     * listener, so holding this reference is what would let anything ever call {@code close()}
     * on it.
     *
     * <p><b>Not because of the Micrometer WeakReference trap.</b> That trap is real in general
     * (§8.3, and demonstrably real for {@code SplunkHecMetrics.RETAINED}), but measurement
     * disproved it for this field specifically:
     * {@code JvmGcMetrics.bindTo} registers its listener with each {@code
     * GarbageCollectorMXBean} via {@code NotificationEmitter.addNotificationListener}, which
     * stores it in a strong-reference list owned by the broadcaster behind that MXBean — a JVM
     * singleton reachable from a GC root for the life of the process. That listener is a
     * non-static inner class, so it holds an implicit reference to the whole {@code
     * JvmGcMetrics} instance, which stays strongly reachable regardless of whether this field
     * holds it too. Established empirically (a {@code WeakReference} canary collects in the
     * same run where an unretained {@code JvmGcMetrics} does not — see {@code
     * JvmMetricsBinderTest.metersStillReportAfterGarbageCollection}'s javadoc) and corroborated
     * by Micrometer's own design: {@code JvmGcMetrics.close()} calls {@code
     * removeNotificationListener} precisely because retention is otherwise strong. Do not
     * "clean up" this field regardless — the two reasons above still hold.
     */
    private static final List<JvmGcMetrics> RETAINED = new ArrayList<>();

    private JvmMetricsBinder() {}

    /**
     * Test-only view of how many {@link JvmGcMetrics} instances have been retained across every
     * registry ever bound in this JVM. Not for production use — it exists so a test can assert
     * on the one thing the {@link #BOUND} guard is load-bearing for: that a second bind on the
     * same registry does not install a second JMX notification listener (which would
     * double-count every GC pause), while a bind on a genuinely different registry still gets
     * its own listener.
     */
    static synchronized int retainedGcMetricsCount() {
        return RETAINED.size();
    }

    public static synchronized void bindTo(MeterRegistry registry) {
        if (registry == null || !BOUND.add(registry)) {
            return;
        }
        new JvmMemoryMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);

        JvmGcMetrics gcMetrics = new JvmGcMetrics();
        gcMetrics.bindTo(registry);
        RETAINED.add(gcMetrics);

        log.info("Bound JVM memory, GC, thread and processor metrics to the meter registry");
    }
}
