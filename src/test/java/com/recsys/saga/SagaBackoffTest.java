package com.recsys.saga;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SagaBackoffTest {

    @Test
    void sleepIsNoOpForNullOrZeroBackoff() {
        long start = System.nanoTime();
        SagaBackoff.sleep(null, 1);
        SagaBackoff.sleep(Duration.ZERO, 1);
        SagaBackoff.sleep(Duration.ofMillis(-1), 1);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isLessThan(200);
    }

    @Test
    void sleepReturnsWithinJitterCeiling() {
        // 10 ms base, attempt=1 → ceiling = min(30000, 10) = 10 ms → sleep in [0, 10]
        long start = System.nanoTime();
        SagaBackoff.sleep(Duration.ofMillis(10), 1);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isLessThanOrEqualTo(50);
    }

    @Test
    void maxBackoffCapIsThirtySeconds() {
        assertThat(SagaBackoff.MAX_BACKOFF_MS).isEqualTo(30_000L);
    }

    @Test
    void interruptedThreadPropagatesSagaException() {
        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> SagaBackoff.sleep(Duration.ofMillis(100), 1))
                .isInstanceOf(SagaException.class)
                .hasMessageContaining("interrupted");
        // clear interrupted flag set by assertThatThrownBy looping
        Thread.interrupted();
    }
}
