// src/test/java/com/recsys/streaming/WorkerBulkheadTest.java
package com.recsys.streaming;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
        // 1 thread, 0 queue capacity → every task beyond in-flight 1 is rejected
        bulkhead = new WorkerBulkhead("tight", 1, 0);
        CountDownLatch blocker = new CountDownLatch(1);
        // Occupy the sole thread
        bulkhead.submit(() -> { blocker.await(); return null; });

        AtomicInteger rejectedFutures = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            CompletableFuture<Void> f = bulkhead.submit(() -> null);
            f.exceptionally(ex -> { rejectedFutures.incrementAndGet(); return null; });
        }
        Thread.sleep(50); // let exceptionally callbacks fire
        assertThat(bulkhead.snapshot().rejected()).isGreaterThan(0);
        blocker.countDown();
    }

    @Test
    void snapshotReflectsPoolName() {
        bulkhead = new WorkerBulkhead("scoring", 4, 32);
        assertThat(bulkhead.snapshot().name()).isEqualTo("scoring");
        assertThat(bulkhead.snapshot().poolSize()).isEqualTo(4);
    }
}
