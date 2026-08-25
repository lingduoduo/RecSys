// src/test/java/com/recsys/resilience/WorkerBulkheadTest.java
package com.recsys.resilience;

import com.recsys.metrics.QueueMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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
        // Wait for the worker to actually pick up the task before relying on the queue being
        // empty: ThreadPoolExecutor only routes a submission to the queue once a core thread has
        // taken the first one. A fixed sleep is a race (see rejectionsAreClassifiedFullVersusShutdown).
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
     * <p>Deterministic by construction: {@code ThreadPoolExecutor} only routes a submission to
     * the queue once a core thread has actually picked up the previous one. Submitting a second
     * task immediately after the first, with no synchronization, races that pickup — if the pool
     * hasn't taken the first task yet, the second can land on the pool instead of the queue, and
     * one of the five "overflow" submissions below queues instead of rejecting (issue #261's
     * failure class). The held task counts down {@code started} as its first action and the test
     * waits on it before submitting anything else, so the single worker is provably occupied and
     * the single queue slot is provably empty before the fill begins.
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
