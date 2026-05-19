package com.recsys.modelbased.model.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JvmMemoryMonitorTest {

    private final JvmMemoryMonitor monitor = new JvmMemoryMonitor();

    @Test
    void snapshot_heap_hasPositiveUsed() {
        JvmMemoryMonitor.Snapshot snap = monitor.snapshot();
        assertThat(snap.heap().usedBytes()).isPositive();
        assertThat(snap.heap().committedBytes()).isGreaterThanOrEqualTo(snap.heap().usedBytes());
    }

    @Test
    void snapshot_heap_usedFractionIsBetweenZeroAndOne() {
        JvmMemoryMonitor.RegionStats heap = monitor.snapshot().heap();
        assertThat(heap.usedFraction()).isBetween(0.0, 1.0);
    }

    @Test
    void snapshot_heap_poolsIncludeAtLeastOneEntry() {
        assertThat(monitor.snapshot().heap().pools()).isNotEmpty();
    }

    @Test
    void snapshot_nonHeap_metaspacePoolPresent() {
        // Metaspace (方法区) must exist on any HotSpot JVM running Java 8+.
        boolean hasMetaspace = monitor.snapshot().nonHeap().pools().keySet().stream()
                .anyMatch(name -> name.toLowerCase().contains("metaspace"));
        assertThat(hasMetaspace).isTrue();
    }

    @Test
    void snapshot_nonHeap_usedIsPositive() {
        assertThat(monitor.snapshot().nonHeap().usedBytes()).isPositive();
    }

    @Test
    void snapshot_threads_liveCountIsPositive() {
        JvmMemoryMonitor.ThreadStats threads = monitor.snapshot().threads();
        assertThat(threads.live()).isPositive();
        assertThat(threads.daemon()).isGreaterThanOrEqualTo(0);
        assertThat(threads.peak()).isGreaterThanOrEqualTo(threads.live());
    }

    @Test
    void snapshot_gc_countersAreNonNegative() {
        JvmMemoryMonitor.GcStats gc = monitor.snapshot().gc();
        assertThat(gc.youngCollections()).isGreaterThanOrEqualTo(0);
        assertThat(gc.youngCollectionTimeMs()).isGreaterThanOrEqualTo(0);
        assertThat(gc.fullCollections()).isGreaterThanOrEqualTo(0);
        assertThat(gc.fullCollectionTimeMs()).isGreaterThanOrEqualTo(0);
        assertThat(gc.concurrentCollections()).isGreaterThanOrEqualTo(0);
        assertThat(gc.concurrentCollectionTimeMs()).isGreaterThanOrEqualTo(0);
        assertThat(gc.stwCollections()).isGreaterThanOrEqualTo(0);
        assertThat(gc.stwCollectionTimeMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void snapshot_gc_collectorsIncludeRoleBreakdown() {
        assertThat(monitor.snapshot().gc().collectors())
                .isNotEmpty()
                .allSatisfy((name, stats) -> {
                    assertThat(name).isEqualTo(stats.name());
                    assertThat(stats.role()).isIn("young/minor", "full/major", "concurrent", "stw-other");
                    assertThat(stats.collections()).isGreaterThanOrEqualTo(0);
                    assertThat(stats.collectionTimeMs()).isGreaterThanOrEqualTo(0);
                });
    }

    @Test
    void regionStats_usedMb_matchesBytesConversion() {
        JvmMemoryMonitor.RegionStats heap = monitor.snapshot().heap();
        assertThat(heap.usedMb()).isEqualTo(heap.usedBytes() / (1024L * 1024L));
    }

    @Test
    void threadStats_nonDaemon_equalsLiveMinusDaemon() {
        JvmMemoryMonitor.ThreadStats t = monitor.snapshot().threads();
        assertThat(t.nonDaemon()).isEqualTo(t.live() - t.daemon());
    }
}
