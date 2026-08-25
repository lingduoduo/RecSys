package com.recsys.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deliberately NOT in the resilience profile: it forces a real collection with System.gc() and an
 * allocation loop, and that profile is documented timing-free (issue #261). Same split as
 * GcEventTrackerTest / GcEventTrackerLifecycleTest and JvmMetricsBinderTest /
 * JvmMetricsBinderGcObservationTest.
 *
 * <p>The FunctionCounter is the meter this test exists for. Gauges pass strongReference(true) and
 * a collected one reports NaN, which is visibly wrong; FunctionCounter.Builder has no such option
 * and a collected one FREEZES at its last value reporting no error — indistinguishable from a
 * quiet queue. A liveness test covering only the gauges would pass while the one unprotected
 * meter was broken.
 */
class QueueMetricsGcObservationTest {

    private static final class CountingSource implements QueueMetrics.Source {
        private int depth = 7;
        private long full = 3;
        @Override public int depth() { return depth; }
        @Override public int capacity() { return 100; }
        @Override public long rejected(QueueMetrics.RejectionReason reason) {
            return reason == QueueMetrics.RejectionReason.FULL ? full : 0L;
        }
    }

    @Test
    void allMetersStillReportAfterGarbageCollection() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueMetrics.register(registry, "gc-probe", new CountingSource());

        WeakReference<Object> canary = new WeakReference<>(new Object());
        for (int i = 0; i < 20 && canary.get() != null; i++) {
            System.gc();
            byte[] pressure = new byte[8 * 1024 * 1024];
            assertThat(pressure).isNotNull();
        }
        assertThat(canary.get())
                .as("a real GC must have run for this test to mean anything")
                .isNull();

        assertThat(registry.find("recsys.queue.depth").tag("queue", "gc-probe").gauge().value())
                .as("gauge state must survive GC").isEqualTo(7.0);
        assertThat(registry.find("recsys.queue.rejected")
                .tag("queue", "gc-probe").tag("reason", "full").functionCounter().count())
                .as("FunctionCounter state must survive GC — it freezes silently if collected")
                .isEqualTo(3.0);
    }
}
