package com.recsys.service.retrieval.multichannel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class ChannelHealthMonitorTest {

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final ChannelHealthMonitor monitor = new ChannelHealthMonitor(3, 1_000L, 30_000L, clock::get);

    @Test
    void newChannelIsAvailable() {
        assertThat(monitor.isAvailable("embedding")).isTrue();
    }

    @Test
    void belowThresholdFailureStaysAvailable() {
        monitor.recordFailure("trending");
        monitor.recordFailure("trending");
        assertThat(monitor.isAvailable("trending")).isTrue();
    }

    @Test
    void atThresholdChannelEntersBackoff() {
        failThrice("embedding");
        assertThat(monitor.isAvailable("embedding")).isFalse();
    }

    @Test
    void backoffExpiresAndChannelBecomesAvailableAgain() {
        failThrice("embedding");
        clock.addAndGet(1_001L);
        assertThat(monitor.isAvailable("embedding")).isTrue();
    }

    @Test
    void successAfterBackoffResetsToHealthy() {
        failThrice("embedding");
        clock.addAndGet(2_000L);
        monitor.recordSuccess("embedding");
        assertThat(monitor.isAvailable("embedding")).isTrue();
        assertThat(monitor.snapshot().get("embedding").consecutiveFailures()).isZero();
    }

    @Test
    void backoffDoublesOnFurtherFailures() {
        failThrice("channel:x");
        clock.addAndGet(1_001L); // past first backoff

        monitor.recordFailure("channel:x"); // 4th failure → backoff = 2_000ms
        clock.addAndGet(1_500L); // still within doubled backoff
        assertThat(monitor.isAvailable("channel:x")).isFalse();
        clock.addAndGet(600L);   // now past 2_000ms total
        assertThat(monitor.isAvailable("channel:x")).isTrue();
    }

    @Test
    void differentChannelsAreTrackedIndependently() {
        failThrice("a");
        assertThat(monitor.isAvailable("a")).isFalse();
        assertThat(monitor.isAvailable("b")).isTrue();
    }

    @Test
    void constructorRejectsZeroFailureThreshold() {
        assertThatThrownBy(() -> new ChannelHealthMonitor(0, 1_000L, 30_000L, clock::get))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorRejectsInvalidMaxBackoff() {
        assertThatThrownBy(() -> new ChannelHealthMonitor(3, 5_000L, 1_000L, clock::get))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxBackoffMs");
    }

    private void failThrice(String name) {
        monitor.recordFailure(name);
        monitor.recordFailure(name);
        monitor.recordFailure(name);
    }
}
