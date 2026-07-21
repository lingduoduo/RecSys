package com.recsys.loadshed;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterizes {@link OnlineLoadShedder}'s admit/drain behavior under ramping concurrency.
 * Invariant assertions are deterministic; the printed profile feeds
 * docs/runbooks/overload-characterization.md. Run:
 *   mvn test -DexcludedGroups="" -Dgroups=load -Dtest=OnlineLoadShedderCharacterizationTest
 */
@Tag("load")
class OnlineLoadShedderCharacterizationTest {

    private static final int MAX = 64;
    private static final double DRAIN = 0.95;

    private record Level(int admitted, OnlineLoadShedder.Snapshot snap, boolean draining) {}

    /** Launches {@code threads} concurrent tryAcquire holders; snapshots while all hold, then releases. */
    private static Level rampAndHold(OnlineLoadShedder s, int threads) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, 256));
        try {
            CountDownLatch attempted = new CountDownLatch(threads);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger admitted = new AtomicInteger();
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    boolean ok = s.tryAcquire();
                    if (ok) admitted.incrementAndGet();
                    attempted.countDown();
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    if (ok) s.release();
                });
            }
            attempted.await();                     // every thread has attempted; holders are holding
            OnlineLoadShedder.Snapshot snap = s.snapshot();
            boolean draining = s.shouldDrain();    // captured while holders hold (Snapshot has no drain flag)
            int a = admitted.get();
            release.countDown();                   // let holders release their slots
            return new Level(a, snap, draining);
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(60)
    void characterizeAdmitDrainCurve() throws InterruptedException {
        int[] levels = {16, 32, 48, 61, 64, 80, 128};
        System.out.printf("%n[shedder] max=%d drain=%.2f%n", MAX, DRAIN);
        System.out.printf("%-8s %-9s %-9s %-8s %-7s %-7s%n",
                "offered", "admitted", "inflight", "util", "drain", "weight");
        for (int offered : levels) {
            OnlineLoadShedder s = new OnlineLoadShedder(MAX, DRAIN); // fresh per level for clean counters
            Level level = rampAndHold(s, offered);
            System.out.printf("%-8d %-9d %-9d %-8.3f %-7s %-7d%n",
                    offered, level.admitted(), level.snap().inFlightRequests(),
                    level.snap().utilization(), level.draining(), level.snap().suggestedWeight());

            // Invariants
            assertThat(level.snap().inFlightRequests()).isLessThanOrEqualTo(MAX);
            assertThat(level.admitted()).isEqualTo(Math.min(offered, MAX));
            // Slots freed after release (pool terminated in rampAndHold).
            assertThat(s.snapshot().inFlightRequests()).isZero();
        }
    }

    @Test
    void drainKneeIsExactlyAtSixtyOne() {
        OnlineLoadShedder s = new OnlineLoadShedder(MAX, DRAIN);
        for (int i = 0; i < 60; i++) assertThat(s.tryAcquire()).isTrue();
        assertThat(s.shouldDrain()).isFalse();  // 60/64 = 0.9375 < 0.95
        assertThat(s.tryAcquire()).isTrue();     // 61st
        assertThat(s.shouldDrain()).isTrue();    // 61/64 = 0.953 >= 0.95
    }

    @Test
    void suggestedWeightIsMonotonicDownToZero() {
        OnlineLoadShedder s = new OnlineLoadShedder(MAX, DRAIN);
        int prev = s.snapshot().suggestedWeight();      // 100 at 0 inflight
        assertThat(prev).isEqualTo(100);
        for (int i = 0; i < MAX; i++) {
            assertThat(s.tryAcquire()).isTrue();
            int w = s.snapshot().suggestedWeight();
            assertThat(w).isLessThanOrEqualTo(prev);
            prev = w;
        }
        assertThat(prev).isZero();                       // round((1-1)*100) = 0 at full
    }
}
