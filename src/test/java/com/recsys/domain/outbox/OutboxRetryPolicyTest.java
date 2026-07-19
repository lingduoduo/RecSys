package com.recsys.domain.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxRetryPolicyTest {
    @Test
    void retryDelayIsBoundedAndDeterministicWithInjectedJitter() {
        OutboxRetryPolicy policy = new OutboxRetryPolicy(
                Duration.ofSeconds(1), Duration.ofMinutes(5), 8, () -> 0.5);

        assertThat(policy.nextAttempt(3, Instant.EPOCH)).isEqualTo(Instant.EPOCH.plusSeconds(4));
        assertThat(policy.isDead(8)).isTrue();
    }

    @Test
    void retryDelaySaturatesWithoutOverflow() {
        OutboxRetryPolicy policy = new OutboxRetryPolicy(
                Duration.ofSeconds(1), Duration.ofMinutes(5), 100, () -> 1.0);

        assertThat(policy.nextAttempt(80, Instant.EPOCH)).isEqualTo(Instant.EPOCH.plusSeconds(300));
    }

    @Test
    void largeDurationsAndFailedAtAdditionSaturateWithoutOverflow() {
        OutboxRetryPolicy policy = new OutboxRetryPolicy(
                Duration.ofSeconds(Long.MAX_VALUE / 4), Duration.ofSeconds(Long.MAX_VALUE / 2), 100, () -> 1.0);

        assertThat(policy.nextAttempt(80, Instant.MAX.minusSeconds(1))).isEqualTo(Instant.MAX);
    }

    @Test
    void multiplicativeJitterMovesDelayDownAndUp() {
        var downward = new OutboxRetryPolicy(Duration.ofSeconds(10), Duration.ofMinutes(5), 8, () -> 0.0);
        var upward = new OutboxRetryPolicy(Duration.ofSeconds(10), Duration.ofMinutes(5), 8, () -> 1.0);

        assertThat(downward.nextAttempt(1, Instant.EPOCH)).isEqualTo(Instant.EPOCH.plusSeconds(5));
        assertThat(upward.nextAttempt(1, Instant.EPOCH)).isEqualTo(Instant.EPOCH.plusSeconds(15));
    }
}
