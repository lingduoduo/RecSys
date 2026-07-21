package com.recsys.resilience;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Characterizes {@link WorkerBulkhead}: work beyond the {@code poolSize + queueCapacity} ceiling is
 * rejected immediately (bounded tail latency), not queued unbounded. Run:
 *   mvn test -DexcludedGroups="" -Dgroups=load -Dtest=WorkerBulkheadCharacterizationTest
 */
@Tag("load")
class WorkerBulkheadCharacterizationTest {

    private static final int POOL = 4;
    private static final int QUEUE = 8;
    private static final int CEILING = POOL + QUEUE; // 12
    private static final int OVERFLOW = 5;

    private static void awaitUntil(java.util.function.BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met within " + timeoutMs + "ms");
            Thread.sleep(5);
        }
    }

    @Test
    @Timeout(60)
    void ceilingIsPoolPlusQueueAndOverflowRejectsImmediately() throws Exception {
        WorkerBulkhead bh = new WorkerBulkhead("char", POOL, QUEUE);
        CountDownLatch block = new CountDownLatch(1);
        try {
            List<CompletableFuture<String>> futures = new ArrayList<>();
            int submitted = CEILING + OVERFLOW;
            for (int i = 0; i < submitted; i++) {
                futures.add(bh.submit(() -> { block.await(); return "ok"; }));
            }

            // Over-ceiling submits are rejected SYNCHRONOUSLY (future already exceptionally completed).
            int rejected = 0;
            for (CompletableFuture<String> f : futures) {
                if (f.isCompletedExceptionally()) {
                    rejected++;
                    assertThatThrownBy(f::join).hasCauseInstanceOf(RejectedExecutionException.class);
                }
            }

            // The accepted CEILING tasks are pending (blocked); let active+queued stabilize.
            awaitUntil(() -> bh.snapshot().active() + bh.snapshot().queued() == CEILING, 2_000);
            WorkerBulkhead.Snapshot snap = bh.snapshot();
            System.out.printf("%n[bulkhead] pool=%d queue=%d ceiling=%d submitted=%d%n", POOL, QUEUE, CEILING, submitted);
            System.out.printf("rejected=%d active=%d queued=%d snap.rejected=%d%n",
                    rejected, snap.active(), snap.queued(), snap.rejected());

            // Invariants
            assertThat(rejected).isEqualTo(OVERFLOW);                 // exactly the overflow rejected
            assertThat(snap.rejected()).isEqualTo((long) OVERFLOW);
            assertThat(snap.active() + snap.queued()).isEqualTo(CEILING);

            // Recovery: release the blocked tasks; the accepted ones complete, bulkhead drains.
            block.countDown();
            for (CompletableFuture<String> f : futures) {
                if (!f.isCompletedExceptionally()) f.get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
            awaitUntil(() -> bh.snapshot().active() == 0 && bh.snapshot().queued() == 0, 2_000);
            assertThat(bh.snapshot().active()).isZero();
        } finally {
            block.countDown();   // ensure no task stays blocked on failure
            bh.close();
        }
    }
}
