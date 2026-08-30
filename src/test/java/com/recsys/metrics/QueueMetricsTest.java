package com.recsys.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueueMetricsTest {

    /** Minimal Source whose values the test controls directly. */
    private static final class FakeQueue implements QueueMetrics.Source {
        private final AtomicInteger depth = new AtomicInteger();
        private final int capacity;
        private final Map<QueueMetrics.RejectionReason, AtomicInteger> rejects = Map.of(
                QueueMetrics.RejectionReason.FULL, new AtomicInteger(),
                QueueMetrics.RejectionReason.SHUTDOWN, new AtomicInteger(),
                QueueMetrics.RejectionReason.INVALID_KEY, new AtomicInteger());

        FakeQueue(int capacity) { this.capacity = capacity; }

        @Override public int depth() { return depth.get(); }
        @Override public int capacity() { return capacity; }
        @Override public long rejected(QueueMetrics.RejectionReason reason) {
            return rejects.get(reason).get();
        }
        void setDepth(int d) { depth.set(d); }
        void reject(QueueMetrics.RejectionReason reason) { rejects.get(reason).incrementAndGet(); }
    }

    private static Double gauge(SimpleMeterRegistry registry, String name, String queue) {
        return registry.find(name).tag("queue", queue).gauge().value();
    }

    @Test
    void registersTheFourMeterFamiliesForAQueue() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueMetrics.register(registry, "alpha", new FakeQueue(100));

        assertThat(gauge(registry, "recsys.queue.depth", "alpha")).isZero();
        assertThat(gauge(registry, "recsys.queue.capacity", "alpha")).isEqualTo(100.0);
        assertThat(gauge(registry, "recsys.queue.utilization", "alpha")).isZero();
        assertThat(registry.find("recsys.queue.rejected")
                .tag("queue", "alpha").tag("reason", "full").functionCounter()).isNotNull();
        assertThat(registry.find("recsys.queue.rejected")
                .tag("queue", "alpha").tag("reason", "shutdown").functionCounter()).isNotNull();
        assertThat(registry.find("recsys.queue.rejected")
                .tag("queue", "alpha").tag("reason", "invalid_key").functionCounter()).isNotNull();
    }

    @Test
    void gaugesTrackTheLiveSource() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FakeQueue q = new FakeQueue(200);
        QueueMetrics.register(registry, "beta", q);

        q.setDepth(50);
        assertThat(gauge(registry, "recsys.queue.depth", "beta")).isEqualTo(50.0);
        assertThat(gauge(registry, "recsys.queue.utilization", "beta")).isEqualTo(0.25);

        q.setDepth(200);
        assertThat(gauge(registry, "recsys.queue.utilization", "beta")).isEqualTo(1.0);
    }

    /**
     * Not clamped on purpose: a Source whose depth exceeds its own capacity is a bug worth
     * seeing, and smoothing it to 1.0 would hide it.
     */
    @Test
    void utilizationIsNotClampedAboveOne() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FakeQueue q = new FakeQueue(10);
        QueueMetrics.register(registry, "gamma", q);
        q.setDepth(15);
        assertThat(gauge(registry, "recsys.queue.utilization", "gamma")).isEqualTo(1.5);
    }

    @Test
    void rejectionCountersAreSeparatedByReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FakeQueue q = new FakeQueue(10);
        QueueMetrics.register(registry, "delta", q);

        q.reject(QueueMetrics.RejectionReason.FULL);
        q.reject(QueueMetrics.RejectionReason.FULL);
        q.reject(QueueMetrics.RejectionReason.SHUTDOWN);

        assertThat(registry.find("recsys.queue.rejected")
                .tag("queue", "delta").tag("reason", "full").functionCounter().count()).isEqualTo(2.0);
        assertThat(registry.find("recsys.queue.rejected")
                .tag("queue", "delta").tag("reason", "shutdown").functionCounter().count()).isEqualTo(1.0);
        assertThat(registry.find("recsys.queue.rejected")
                .tag("queue", "delta").tag("reason", "invalid_key").functionCounter().count()).isZero();
    }

    /**
     * Capacity is a positive invariant, not a runtime state. A non-positive value is a
     * programming error in a Source, so registration fails at startup where it is attributable —
     * and must register NOTHING, or it would publish exactly the misleading series the throw
     * exists to prevent.
     */
    @Test
    void nonPositiveCapacityIsRejectedAndRegistersNothing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        assertThatThrownBy(() -> QueueMetrics.register(registry, "zero", new FakeQueue(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero");
        assertThat(registry.getMeters()).isEmpty();

        assertThatThrownBy(() -> QueueMetrics.register(registry, "neg", new FakeQueue(-5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(registry.getMeters()).isEmpty();
    }

    /**
     * Measured against micrometer-core 1.13.6: registering a duplicate name+tags returns the
     * FIRST meter and discards the second's state object. Without this guard the second queue
     * would report the first queue's depth forever, with no error anywhere.
     */
    @Test
    void duplicateQueueNameOnTheSameRegistryIsRejected() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueMetrics.register(registry, "dup", new FakeQueue(10));

        assertThatThrownBy(() -> QueueMetrics.register(registry, "dup", new FakeQueue(20)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dup");
    }

    @Test
    void sameQueueNameOnDifferentRegistriesIsAllowed() {
        SimpleMeterRegistry a = new SimpleMeterRegistry();
        SimpleMeterRegistry b = new SimpleMeterRegistry();
        QueueMetrics.register(a, "shared", new FakeQueue(10));
        QueueMetrics.register(b, "shared", new FakeQueue(20));

        assertThat(gauge(a, "recsys.queue.capacity", "shared")).isEqualTo(10.0);
        assertThat(gauge(b, "recsys.queue.capacity", "shared")).isEqualTo(20.0);
    }

    @Test
    void twoQueuesOnOneRegistryDoNotAlias() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FakeQueue first = new FakeQueue(10);
        FakeQueue second = new FakeQueue(20);
        QueueMetrics.register(registry, "first", first);
        QueueMetrics.register(registry, "second", second);

        first.setDepth(3);
        second.setDepth(7);

        assertThat(gauge(registry, "recsys.queue.depth", "first")).isEqualTo(3.0);
        assertThat(gauge(registry, "recsys.queue.depth", "second")).isEqualTo(7.0);
    }

    @Test
    void nullArgumentsAreRejected() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        assertThatThrownBy(() -> QueueMetrics.register(null, "x", new FakeQueue(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> QueueMetrics.register(registry, null, new FakeQueue(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> QueueMetrics.register(registry, "x", null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * A defensive double-call with the exact same Source instance is provably not the aliasing
     * bug the guard exists to prevent (same object, same readings), so it must be a no-op rather
     * than a boot failure.
     */
    @Test
    void reRegisteringTheSameSourceInstanceIsANoOp() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FakeQueue q = new FakeQueue(10);
        QueueMetrics.register(registry, "same-instance", q);

        QueueMetrics.register(registry, "same-instance", q);

        assertThat(registry.find("recsys.queue.depth").tag("queue", "same-instance").gauges())
                .hasSize(1);
        assertThat(registry.find("recsys.queue.capacity").tag("queue", "same-instance").gauges())
                .hasSize(1);
        assertThat(registry.find("recsys.queue.utilization").tag("queue", "same-instance").gauges())
                .hasSize(1);
        for (QueueMetrics.RejectionReason reason : QueueMetrics.RejectionReason.values()) {
            assertThat(registry.find("recsys.queue.rejected")
                    .tag("queue", "same-instance").tag("reason", reason.tag()).functionCounters())
                    .hasSize(1);
        }
    }

    /**
     * A *different* Source under an existing name is the real aliasing bug: Micrometer would
     * silently keep serving the first Source's readings under the new object's name. This must
     * keep throwing even after same-instance re-registration became a no-op.
     */
    @Test
    void differentSourceUnderAnExistingNameStillThrows() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueMetrics.register(registry, "existing", new FakeQueue(10));

        assertThatThrownBy(() -> QueueMetrics.register(registry, "existing", new FakeQueue(20)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("existing");
    }

    /**
     * The important assertion in this test suite: after unregister + re-register with a new
     * Source, the gauges must read the NEW source's values, not the old one's. Reading stale
     * values here would be exactly the silent aliasing failure this whole guard exists to
     * prevent, just triggered through the teardown path instead of a raw duplicate name.
     */
    @Test
    void unregisterThenReRegisterWithDifferentSourceReadsTheNewValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FakeQueue oldQueue = new FakeQueue(10);
        oldQueue.setDepth(3);
        QueueMetrics.register(registry, "epsilon", oldQueue);

        QueueMetrics.unregister(registry, "epsilon");

        FakeQueue newQueue = new FakeQueue(999);
        newQueue.setDepth(42);
        QueueMetrics.register(registry, "epsilon", newQueue);

        assertThat(gauge(registry, "recsys.queue.depth", "epsilon")).isEqualTo(42.0);
        assertThat(gauge(registry, "recsys.queue.capacity", "epsilon")).isEqualTo(999.0);
        assertThat(gauge(registry, "recsys.queue.utilization", "epsilon")).isEqualTo(42.0 / 999.0);
    }

    @Test
    void unregisterOnAnUnregisteredNameIsANoOp() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueMetrics.unregister(registry, "never-registered");
        // Must not throw, and must not disturb an unrelated registered queue.
        QueueMetrics.register(registry, "zeta", new FakeQueue(5));
        assertThat(gauge(registry, "recsys.queue.capacity", "zeta")).isEqualTo(5.0);
    }

    @Test
    void unregisterRemovesAllFourMeterFamiliesFromTheRegistry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueMetrics.register(registry, "eta", new FakeQueue(10));

        QueueMetrics.unregister(registry, "eta");

        assertThat(registry.find("recsys.queue.depth").tag("queue", "eta").gauge()).isNull();
        assertThat(registry.find("recsys.queue.capacity").tag("queue", "eta").gauge()).isNull();
        assertThat(registry.find("recsys.queue.utilization").tag("queue", "eta").gauge()).isNull();
        for (QueueMetrics.RejectionReason reason : QueueMetrics.RejectionReason.values()) {
            assertThat(registry.find("recsys.queue.rejected")
                    .tag("queue", "eta").tag("reason", reason.tag()).functionCounter()).isNull();
        }
    }
}
