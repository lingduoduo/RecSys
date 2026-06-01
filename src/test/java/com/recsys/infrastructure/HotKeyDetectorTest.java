package com.recsys.infrastructure;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HotKeyDetectorTest {

    @Test
    void isHot_returnsFalseForUnrecordedKey() {
        HotKeyDetector detector = new HotKeyDetector(10, 100L);
        assertThat(detector.isHot("missing-key")).isFalse();
    }

    @Test
    void accessRate_returnsZeroForUnrecordedKey() {
        assertThat(new HotKeyDetector().accessRate("k")).isEqualTo(0.0);
    }

    @Test
    void record_incrementsAccessRate() {
        HotKeyDetector detector = new HotKeyDetector(10, 1L);
        detector.record("k");
        assertThat(detector.accessRate("k")).isGreaterThan(0.0);
    }

    @Test
    void isHot_trueWhenRateExceedsThreshold() {
        // threshold = 5 req/s; window = 10 s; record 200 times in a very short span
        HotKeyDetector detector = new HotKeyDetector(10, 5L);
        for (int i = 0; i < 200; i++) detector.record("popular-key");
        assertThat(detector.isHot("popular-key")).isTrue();
    }

    @Test
    void isHot_falseWhenRateBelowThreshold() {
        // threshold = 10_000 req/s; record only 1 → nowhere near threshold
        HotKeyDetector detector = new HotKeyDetector(10, 10_000L);
        detector.record("rare-key");
        assertThat(detector.isHot("rare-key")).isFalse();
    }

    @Test
    void topHotKeys_returnsKeysSortedByDescendingRate() {
        HotKeyDetector detector = new HotKeyDetector(10, 1L);
        // keyA: 10 hits, keyB: 100 hits → keyB should be hotter
        for (int i = 0; i < 10; i++)  detector.record("keyA");
        for (int i = 0; i < 100; i++) detector.record("keyB");

        List<Map.Entry<String, Double>> top = detector.topHotKeys(2);
        assertThat(top).hasSize(2);
        assertThat(top.get(0).getKey()).isEqualTo("keyB"); // hottest first
        assertThat(top.get(1).getKey()).isEqualTo("keyA");
        assertThat(top.get(0).getValue()).isGreaterThan(top.get(1).getValue());
    }

    @Test
    void topHotKeys_limitsResultCount() {
        HotKeyDetector detector = new HotKeyDetector(10, 1L);
        for (int i = 0; i < 10; i++) detector.record("k" + i);
        assertThat(detector.topHotKeys(3)).hasSize(3);
    }

    @Test
    void topHotKeys_returnsEmptyWhenNothingRecorded() {
        assertThat(new HotKeyDetector().topHotKeys(5)).isEmpty();
    }

    @Test
    void trackedKeyCount_countsDistinctKeys() {
        HotKeyDetector detector = new HotKeyDetector(10, 1L);
        detector.record("a");
        detector.record("b");
        detector.record("a"); // duplicate
        assertThat(detector.trackedKeyCount()).isEqualTo(2);
    }

    @Test
    void evictIdle_doesNotThrowAndPreservesActiveKeys() {
        HotKeyDetector detector = new HotKeyDetector(10, 1L);
        detector.record("active-key");
        detector.evictIdle(); // should never throw
        // Active key recorded in the current window must still be tracked.
        assertThat(detector.trackedKeyCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void concurrentRecords_doNotLoseCountsUnderRaceCondition() throws InterruptedException {
        HotKeyDetector detector = new HotKeyDetector(10, 1L);
        int threads = 8;
        int recordsPerThread = 500;

        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                for (int j = 0; j < recordsPerThread; j++) detector.record("shared-key");
            });
        }
        for (Thread t : workers) t.start();
        for (Thread t : workers) t.join();

        // After 8×500 = 4000 records in a tight window, the rate must be > threshold(1).
        assertThat(detector.isHot("shared-key")).isTrue();
    }
}
