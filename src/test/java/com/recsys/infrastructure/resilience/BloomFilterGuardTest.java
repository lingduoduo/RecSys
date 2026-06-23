package com.recsys.infrastructure.resilience;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BloomFilterGuardTest {

    @Test
    void add_thenMightContain_returnsTrue() {
        BloomFilterGuard filter = new BloomFilterGuard(1000, 0.01);
        filter.add(42);
        assertThat(filter.mightContain(42)).isTrue();
    }

    @Test
    void neverAdded_mightContainReturnsFalse() {
        BloomFilterGuard filter = new BloomFilterGuard(1000, 0.01);
        // Empty filter must return false for any ID.
        assertThat(filter.mightContain(0)).isFalse();
        assertThat(filter.mightContain(1)).isFalse();
        assertThat(filter.mightContain(Integer.MAX_VALUE)).isFalse();
    }

    @Test
    void allAddedIds_areRetained() {
        BloomFilterGuard filter = new BloomFilterGuard(500, 0.01);
        for (int i = 0; i < 500; i++) {
            filter.add(i);
        }
        for (int i = 0; i < 500; i++) {
            assertThat(filter.mightContain(i))
                    .as("id %d should be retained in the filter", i)
                    .isTrue();
        }
    }

    @Test
    void falsePositiveRate_isBounded() {
        int n = 10_000;
        BloomFilterGuard filter = new BloomFilterGuard(n, 0.01);
        for (int i = 0; i < n; i++) {
            filter.add(i);
        }

        // Query IDs in a disjoint range and count false positives.
        int falsePositives = 0;
        int probes = 10_000;
        for (int i = n; i < n + probes; i++) {
            if (filter.mightContain(i)) falsePositives++;
        }
        double observedRate = (double) falsePositives / probes;
        // Allow 3× headroom over the configured 1% rate for statistical variance.
        assertThat(observedRate).isLessThan(0.03);
    }

    @Test
    void negativeIds_areHandledCorrectly() {
        BloomFilterGuard filter = new BloomFilterGuard(100, 0.01);
        filter.add(-1);
        filter.add(Integer.MIN_VALUE);
        assertThat(filter.mightContain(-1)).isTrue();
        assertThat(filter.mightContain(Integer.MIN_VALUE)).isTrue();
        assertThat(filter.mightContain(-2)).isFalse();
    }

    @Test
    void concurrentAdds_doNotCorruptFilter() throws Exception {
        BloomFilterGuard filter = new BloomFilterGuard(200, 0.01);
        Thread t1 = new Thread(() -> { for (int i = 0; i < 100; i++) filter.add(i); });
        Thread t2 = new Thread(() -> { for (int i = 100; i < 200; i++) filter.add(i); });
        t1.start(); t2.start();
        t1.join(); t2.join();

        for (int i = 0; i < 200; i++) {
            assertThat(filter.mightContain(i)).isTrue();
        }
    }
}
