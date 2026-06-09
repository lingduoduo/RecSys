package com.recsys.model.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GcEventTrackerTest {

    private GcEventTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new GcEventTracker();
        tracker.install();
    }

    @Test
    void snapshot_initialState_allCountersZero() {
        GcEventTracker.Snapshot snap = tracker.snapshot();
        assertThat(snap.stwEventCount()).isZero();
        assertThat(snap.stwTotalPauseMs()).isZero();
        assertThat(snap.stwLongestPauseMs()).isZero();
        assertThat(snap.evacuationFailures()).isZero();
        assertThat(snap.allocationStalls()).isZero();
    }

    @Test
    void snapshot_histogram_hasAllBuckets() {
        GcEventTracker.Snapshot snap = tracker.snapshot();
        assertThat(snap.stwPauseHistogram()).containsKeys(
                "<1ms", "1-10ms", "10-50ms", "50-200ms", "200-500ms", ">500ms");
    }

    @Test
    void snapshot_byType_keysAreGcTypeEnum() {
        // byType only contains types that have fired; verify no crash on empty map
        GcEventTracker.Snapshot snap = tracker.snapshot();
        snap.byType().forEach((type, stats) -> {
            assertThat(type).isNotNull();
            assertThat(stats.events()).isPositive();
            assertThat(stats.avgPauseMs()).isGreaterThanOrEqualTo(0.0);
        });
    }

    @Test
    void gcType_stw_flagCorrect() {
        assertThat(GcEventTracker.GcType.MINOR_GC.stw).isTrue();
        assertThat(GcEventTracker.GcType.G1_MIXED.stw).isTrue();
        assertThat(GcEventTracker.GcType.FULL_GC.stw).isTrue();
        assertThat(GcEventTracker.GcType.ZGC_STW_PAUSE.stw).isTrue();
        assertThat(GcEventTracker.GcType.ZGC_CYCLE.stw).isFalse();
        assertThat(GcEventTracker.GcType.CMS_PHASE.stw).isFalse();
    }

    @Test
    void gcType_allHaveDescriptions() {
        for (GcEventTracker.GcType type : GcEventTracker.GcType.values()) {
            assertThat(type.description).isNotBlank();
        }
    }

    @Test
    void typeStats_avgPauseMs_zeroEventsReturnsZero() {
        GcEventTracker.TypeStats stats = new GcEventTracker.TypeStats(GcEventTracker.GcType.FULL_GC, 0, 0);
        assertThat(stats.avgPauseMs()).isZero();
    }

    @Test
    void typeStats_avgPauseMs_computedCorrectly() {
        GcEventTracker.TypeStats stats = new GcEventTracker.TypeStats(GcEventTracker.GcType.MINOR_GC, 4, 200);
        assertThat(stats.avgPauseMs()).isEqualTo(50.0);
    }

    @Test
    void destroy_doesNotThrow() {
        // Verify listener deregistration is idempotent and exception-safe
        tracker.destroy();
        tracker.destroy();
    }

    @Test
    void snapshot_afterInstall_listensToLiveCollectors() {
        // After install(), at least the JVM's own GC beans are registered.
        // Trigger a minor GC and verify the tracker stays consistent (no crash).
        System.gc();
        GcEventTracker.Snapshot snap = tracker.snapshot();
        assertThat(snap.stwEventCount()).isGreaterThanOrEqualTo(0);
        assertThat(snap.stwLongestPauseMs()).isGreaterThanOrEqualTo(0);
    }
}
