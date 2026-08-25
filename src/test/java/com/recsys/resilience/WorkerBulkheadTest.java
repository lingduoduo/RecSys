// src/test/java/com/recsys/resilience/WorkerBulkheadTest.java
package com.recsys.resilience;

import com.recsys.metrics.QueueMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerBulkheadTest {

    private WorkerBulkhead bulkhead;

    @AfterEach
    void tearDown() {
        if (bulkhead != null) bulkhead.close();
    }

    @Test
    void tasksExecuteInNamedThreads() throws Exception {
        bulkhead = new WorkerBulkhead("test-lane", 2, 8);
        CompletableFuture<String> threadName = bulkhead.submit(() -> Thread.currentThread().getName());
        assertThat(threadName.get(2, TimeUnit.SECONDS)).startsWith("test-lane-worker-");
    }

    @Test
    void queueOverflowIncrementsRejectedCount() throws InterruptedException {
        // 1 thread, effective queue capacity 1 (Math.max(1, 0) = 1)
        // Thread is blocked → 1 slot in queue → tasks 3..7 are rejected
        bulkhead = new WorkerBulkhead("tight", 1, 0);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch blocker = new CountDownLatch(1);
        bulkhead.submit(() -> { started.countDown(); blocker.await(); return null; }); // occupies thread
        // A latch, not a sleep: this pins submission ordering explicitly rather than relying on
        // a fixed delay in a gate that is documented timing-free. (ThreadPoolExecutor itself
        // updates its worker count synchronously in execute(), before the worker thread runs, so
        // the original Thread.sleep(20) was not actually racing anything observed here — the
        // latch is still preferred because it makes the ordering an explicit assertion instead of
        // an assumption about executor internals that a future JDK could change.)
        assertThat(started.await(2, TimeUnit.SECONDS)).as("worker never started").isTrue();

        for (int i = 0; i < 5; i++) {
            bulkhead.submit(() -> null); // tasks 1-5; first fills queue, rest rejected
        }
        assertThat(bulkhead.snapshot().rejected()).isGreaterThan(0);
        blocker.countDown();
    }

    @Test
    void snapshotReflectsPoolName() {
        bulkhead = new WorkerBulkhead("scoring", 4, 32);
        WorkerBulkhead.Snapshot snap = bulkhead.snapshot();
        assertThat(snap.name()).isEqualTo("scoring");
        assertThat(snap.poolSize()).isEqualTo(4);
        assertThat(snap.active()).isZero();
        assertThat(snap.queued()).isZero();
    }

    @Test
    void reportsTheEffectiveCapacityItWasConstructedWith() {
        WorkerBulkhead bulkhead = new WorkerBulkhead("cap", 1, 5);
        try {
            assertThat(bulkhead.capacity()).isEqualTo(5);
            assertThat(bulkhead.snapshot().queueCapacity()).isEqualTo(5);
        } finally {
            bulkhead.close();
        }
    }

    /**
     * Both constructors clamp with Math.max(1, n), so a requested 0 yields a one-entry queue.
     * The metric must report the queue that exists, not the one that was asked for, or
     * utilization would be arithmetically wrong.
     */
    @Test
    void aNonPositiveRequestedCapacityIsClampedToOneAndReportedAsSuch() {
        WorkerBulkhead bulkhead = new WorkerBulkhead("clamped", 1, 0);
        try {
            assertThat(bulkhead.capacity()).isEqualTo(1);
        } finally {
            bulkhead.close();
        }
    }

    /**
     * ThreadPoolExecutor throws RejectedExecutionException for a full queue OR a shut-down
     * executor. Counting both as saturation would fire the queue alert on every rolling deploy.
     *
     * <p>The held task counts down {@code started} as its first action and the test waits on it
     * before submitting anything else. This is not working around a race — {@code
     * ThreadPoolExecutor} increments its worker count synchronously inside {@code execute()},
     * before the worker thread is even created, so a second submission from the same thread
     * reliably observes "one worker occupied" regardless of whether the first task has begun
     * running; 40,000 unsynchronized trials of this exact sequence produced no anomaly. The latch
     * is here so the ordering the test depends on is an explicit assertion in the test itself,
     * not an assumption resting on that {@code ThreadPoolExecutor} implementation detail, which a
     * future JDK is free to change.
     */
    @Test
    void rejectionsAreClassifiedFullVersusShutdown() throws Exception {
        WorkerBulkhead bulkhead = new WorkerBulkhead("reasons", 1, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            // Occupy the single worker, then fill the single queue slot.
            bulkhead.submit(() -> { started.countDown(); release.await(); return "held"; });
            assertThat(started.await(2, TimeUnit.SECONDS)).as("worker never started").isTrue();
            bulkhead.submit(() -> "queued");

            // Everything after this has nowhere to go: the queue is full.
            for (int i = 0; i < 5; i++) {
                bulkhead.submit(() -> "overflow");
            }

            assertThat(bulkhead.rejected(QueueMetrics.RejectionReason.FULL)).isEqualTo(5L);
            assertThat(bulkhead.rejected(QueueMetrics.RejectionReason.SHUTDOWN)).isZero();
        } finally {
            release.countDown();
            bulkhead.close();
        }

        // After close, the executor rejects for a different reason entirely.
        bulkhead.submit(() -> "after-close");
        assertThat(bulkhead.rejected(QueueMetrics.RejectionReason.SHUTDOWN)).isEqualTo(1L);
        assertThat(bulkhead.rejected(QueueMetrics.RejectionReason.FULL))
                .as("a shutdown rejection must not inflate the saturation counter")
                .isEqualTo(5L);
    }

    /**
     * Drives rejection through {@code asExecutorService()} -- the route production recall
     * actually uses ({@code CompletableFuture.supplyAsync(..., bulkhead.asExecutorService())} in
     * {@code MultiChannelRecallService}), not through {@code submit(Callable)}, which has zero
     * production callers. Before the {@code RejectedExecutionHandler} was added, the counters
     * only ever advanced from inside {@code submit}'s catch block, so
     * {@code recsys_queue_rejected_total} was structurally 0 forever on the production path even
     * though the metric was "registered" and looked like coverage.
     */
    @Test
    void rejectionThroughAsExecutorServiceAdvancesTheFullCounter() throws Exception {
        WorkerBulkhead bulkhead = new WorkerBulkhead("prod-route", 1, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.ExecutorService executorService = bulkhead.asExecutorService();
        try {
            // Occupy the single worker via the production entry point.
            CompletableFuture.supplyAsync(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "held";
            }, executorService);
            assertThat(started.await(2, TimeUnit.SECONDS)).as("worker never started").isTrue();

            // Fill the single queue slot, also via the production entry point.
            CompletableFuture.supplyAsync(() -> "queued", executorService);

            // Nowhere left to go: this must be rejected, and MultiChannelRecallService's own
            // catch around supplyAsync is exactly what observes the resulting
            // RejectedExecutionException in production.
            assertThatThrownBy(() -> CompletableFuture.supplyAsync(() -> "overflow", executorService))
                    .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);

            assertThat(bulkhead.rejected(QueueMetrics.RejectionReason.FULL))
                    .as("a rejection routed through asExecutorService() -- the production path --"
                            + " must be counted, not just one routed through submit()")
                    .isEqualTo(1L);
        } finally {
            release.countDown();
            bulkhead.close();
        }
    }

    @Test
    void invalidKeyIsNeverUsedByABulkhead() {
        WorkerBulkhead bulkhead = new WorkerBulkhead("nokey", 1, 1);
        try {
            assertThat(bulkhead.rejected(QueueMetrics.RejectionReason.INVALID_KEY)).isZero();
        } finally {
            bulkhead.close();
        }
    }
}
