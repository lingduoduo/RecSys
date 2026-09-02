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
 *
 * <p><b>What this test does and does not prove.</b> It proves all four meters — the three
 * strongReference(true) gauges and the one unprotected FunctionCounter — still report correctly
 * after a real GC. It does <em>not</em> prove which of the two independent strong paths to the
 * shared {@link QueueMetrics.Source} (the gauges' own {@code strongReference(true)}, or
 * {@code QueueMetrics.REGISTERED}'s retention) is responsible: removing either one alone leaves
 * the other holding, so this test stays green under either mutation in isolation. That was
 * measured directly — commenting out {@code REGISTERED}'s {@code byName.put(queueName, source)}
 * alone left this test green twice in a row. The discriminating mutation removes <em>both</em>
 * at once: comment out {@code byName.put(queueName, source)} in {@code QueueMetrics.register}
 * <em>and</em> change all three {@code .strongReference(true)} calls to
 * {@code .strongReference(false)} (or delete the calls), force-delete
 * {@code target/classes/com/recsys/metrics/QueueMetrics*.class} and
 * {@code target/test-classes/com/recsys/metrics/QueueMetrics*.class}, then rerun this test. Only
 * that combined mutation isolates the retention story; a re-verifier who wants to check the claim
 * again should reach for that mutation, not the single-field one.
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

    /**
     * The registry map holds its keys weakly, so a registry nobody references any more takes its
     * entry — and its sources — with it. Production never exercises this: the registry there is
     * a JVM-wide singleton. Tests do, one {@code SimpleMeterRegistry} per case, and with strong
     * keys every one of them leaked for the life of the test JVM.
     *
     * <p>The second assertion is the load-bearing one. Weak *keys* must not weaken the retention
     * of a *live* registry's sources: those are held strongly as map values, and that is what
     * keeps a {@code FunctionCounter} — which has no {@code strongReference} option of its own —
     * from silently freezing.
     */
    @Test
    void aCollectedRegistryReleasesItsEntryWhileALiveOneKeepsIts() {
        SimpleMeterRegistry survivor = new SimpleMeterRegistry();
        QueueMetrics.register(survivor, "survivor", new CountingSource());

        SimpleMeterRegistry doomed = new SimpleMeterRegistry();
        QueueMetrics.register(doomed, "doomed", new CountingSource());
        int withBoth = QueueMetrics.registeredRegistryCount();
        assertThat(withBoth).isGreaterThanOrEqualTo(2);

        WeakReference<SimpleMeterRegistry> ref = new WeakReference<>(doomed);
        doomed = null;
        for (int i = 0; i < 20 && ref.get() != null; i++) {
            System.gc();
            byte[] pressure = new byte[8 * 1024 * 1024];
            assertThat(pressure).isNotNull();
        }
        assertThat(ref.get()).as("a real GC must have run for this test to mean anything").isNull();

        // WeakHashMap clears entries lazily, on access.
        assertThat(QueueMetrics.registeredRegistryCount())
                .as("the collected registry's entry must be gone")
                .isLessThan(withBoth);

        assertThat(survivor.find("recsys.queue.rejected").tag("queue", "survivor")
                .tag("reason", "full").functionCounter().count())
                .as("a LIVE registry's FunctionCounter must still read its source after GC")
                .isEqualTo(3.0);
    }
}
