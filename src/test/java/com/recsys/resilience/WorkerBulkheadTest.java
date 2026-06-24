// src/test/java/com/recsys/streaming/WorkerBulkheadTest.java
package com.recsys.resilience;

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
        CountDownLatch blocker = new CountDownLatch(1);
        bulkhead.submit(() -> { blocker.await(); return null; }); // occupies thread
        Thread.sleep(20); // ensure thread is blocked before submitting

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
}
