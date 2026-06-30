package com.recsys.loadshed;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class GracefulExecutorsTest {

    @Test
    void shutdownGracefully_letsInFlightTaskComplete() throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        AtomicBoolean finished = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);

        ex.submit(() -> {
            started.countDown();
            try {
                Thread.sleep(100);
                finished.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        started.await(1, TimeUnit.SECONDS);

        GracefulExecutors.shutdownGracefully(ex, Duration.ofSeconds(2));

        assertThat(ex.isTerminated()).isTrue();
        assertThat(finished).isTrue();
    }

    @Test
    void shutdownGracefully_forceCancelsPastDeadline() {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);

        ex.submit(() -> {
            started.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            started.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long before = System.nanoTime();
        GracefulExecutors.shutdownGracefully(ex, Duration.ofMillis(200));
        long elapsedMs = (System.nanoTime() - before) / 1_000_000;

        assertThat(ex.isShutdown()).isTrue();
        assertThat(elapsedMs).isLessThan(5_000);
    }

    @Test
    void shutdownGracefully_isNullSafe() {
        GracefulExecutors.shutdownGracefully(null, Duration.ofMillis(10));
        // no exception thrown
    }

    @Test
    void defaultTimeout_isFiveSecondsByDefault() {
        // RECSYS_EXECUTOR_SHUTDOWN_TIMEOUT_MS unset in test env
        assertThat(GracefulExecutors.defaultTimeout()).isEqualTo(Duration.ofMillis(5000));
    }
}
