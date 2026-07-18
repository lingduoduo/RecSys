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
}
