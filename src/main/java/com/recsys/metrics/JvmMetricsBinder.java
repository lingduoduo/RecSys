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
     * it is {@code Closeable} and owns a JMX listener that must outlive this method, and
     * Micrometer's gauge state is held by WeakReference — the exact bug
     * {@code SplunkHecMetrics.RETAINED} exists to prevent. Do not "clean up" this field.
     */
    private static final List<JvmGcMetrics> RETAINED = new ArrayList<>();

    private JvmMetricsBinder() {}

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
