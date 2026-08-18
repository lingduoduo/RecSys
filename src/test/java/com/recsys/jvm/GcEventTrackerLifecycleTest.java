package com.recsys.jvm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure state assertions for {@link GcEventTracker}'s start/stop lifecycle — no
 * {@code System.gc()}, no {@code Thread.sleep}, nothing that waits on the JVM actually
 * delivering a JMX notification. Split out of {@link GcEventTrackerTest} (which keeps the
 * GC-observation tests) specifically so this class can run in the {@code resilience} profile:
 * it exercises {@link GcEventTracker#registeredCollectorCount()} directly instead of inferring
 * registration state from whether a pause was recorded.
 */
class GcEventTrackerLifecycleTest {

    @Test
    void startIsIdempotent_secondCallRegistersNoAdditionalListeners() {
        GcEventTracker t = new GcEventTracker();

        t.start();
        int afterFirstStart = t.registeredCollectorCount();

        t.start(); // second call must not double-register
        assertThat(t.registeredCollectorCount())
                .as("a second start() must not register more listeners than the first")
                .isEqualTo(afterFirstStart);

        t.stop();
        assertThat(t.registeredCollectorCount())
                .as("stop() must remove every registered listener")
                .isZero();
    }

    @Test
    void stopIsIdempotent_doesNotThrowAndStaysAtZero() {
        GcEventTracker t = new GcEventTracker();
        t.start();

        t.stop();
        t.stop(); // must not throw

        assertThat(t.registeredCollectorCount()).isZero();
    }
}
