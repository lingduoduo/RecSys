package com.recsys.application.outbox;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryAttemptTest {
    @Test void concurrentCancellationInvokesSupplierExactlyOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DeliveryAttempt attempt = new DeliveryAttempt(
                CompletableFuture.completedFuture(new DeliveryReceipt(Instant.EPOCH)),
                () -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(null); });
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Thread thread = new Thread(() -> {
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                attempt.cancel();
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) thread.join();
        assertThat(calls).hasValue(1);
    }
}
