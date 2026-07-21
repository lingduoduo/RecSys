package com.recsys.loadshed;

import com.recsys.resilience.WorkerBulkhead;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterizes WHICH overload gate trips first for catalog-like defaults: the 64-concurrency
 * shedder vs the recall bulkhead (pool=cores*2, queue=pool*4 => ceiling cores*10). The ordering is
 * machine-dependent — on <=6-core hosts the bulkhead saturates before the 64 gate, so overload shows
 * as silent recall degradation before any 429 (sharp-edge #1). Deterministic: drive to just past the
 * SMALLER limit and assert it trips while the larger still has room. Run:
 *   mvn test -DexcludedGroups="" -Dgroups=load -Dtest=OverloadGateOrderingCharacterizationTest
 */
@Tag("load")
class OverloadGateOrderingCharacterizationTest {

    private static void awaitUntil(java.util.function.BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met within " + timeoutMs + "ms");
            Thread.sleep(5);
        }
    }

    @Test
    @Timeout(60)
    void smallerLimitTripsFirst() throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        int gate = 64;
        int pool = cores * 2;
        int queue = pool * 4;
        int bulkheadCeiling = pool + queue;   // cores*10
        String firstToTrip = bulkheadCeiling < gate ? "bulkhead" : (gate < bulkheadCeiling ? "gate" : "tie");
        System.out.printf("%n[ordering] cores=%d gate=%d bulkheadCeiling=%d => %s trips first%n",
                cores, gate, bulkheadCeiling, firstToTrip);

        OnlineLoadShedder shedder = new OnlineLoadShedder(gate, 0.90); // catalog default drain
        WorkerBulkhead bh = new WorkerBulkhead("recall", pool, queue);
        CountDownLatch block = new CountDownLatch(1);
        try {
            if (bulkheadCeiling < gate) {
                // Fill the bulkhead exactly to its ceiling, acquiring the gate per unit too.
                for (int i = 0; i < bulkheadCeiling; i++) {
                    assertThat(shedder.tryAcquire()).isTrue();          // gate has room throughout
                    bh.submit(() -> { block.await(); return null; });
                }
                awaitUntil(() -> bh.snapshot().active() + bh.snapshot().queued() == bulkheadCeiling, 2_000);
                // The next bulkhead submit is rejected while the gate is NOT yet tripped.
                CompletableFuture<Object> overflow = bh.submit(() -> { block.await(); return null; });
                assertThat(overflow.isCompletedExceptionally()).isTrue();          // bulkhead-first
                assertThat(shedder.snapshot().inFlightRequests()).isLessThan(gate); // gate still has room
                assertThat(shedder.tryAcquire()).isTrue();
            } else {
                // gate < bulkheadCeiling: the 65th tryAcquire fails while the bulkhead still has room.
                for (int i = 0; i < gate; i++) assertThat(shedder.tryAcquire()).isTrue();
                assertThat(shedder.tryAcquire()).isFalse();                         // gate-first
                CompletableFuture<Object> f = bh.submit(() -> { block.await(); return null; });
                assertThat(f.isCompletedExceptionally()).isFalse();                 // bulkhead accepts
                assertThat(bh.snapshot().active() + bh.snapshot().queued()).isLessThan(bulkheadCeiling);
            }
            System.out.printf("[ordering] confirmed: %s trips first on this host%n", firstToTrip);
        } finally {
            block.countDown();
            bh.close();
        }
    }
}
