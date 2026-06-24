package com.recsys.resilience;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static com.recsys.resilience.CircuitBreaker.State.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CircuitBreakerTest {

    @Test
    void startsClosedAndAllows() {
        CircuitBreaker cb = new CircuitBreaker(3, 10_000L);
        assertThat(cb.state()).isEqualTo(CLOSED);
        assertThat(cb.tryAcquire()).isTrue();
    }

    @Test
    void opensAtThresholdThenHalfOpensAfterCooldown() {
        AtomicLong clock = new AtomicLong(0L);
        CircuitBreaker cb = new CircuitBreaker(2, 100L, clock::get);
        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(CLOSED);
        cb.recordFailure();                       // threshold reached
        assertThat(cb.state()).isEqualTo(OPEN);
        assertThat(cb.tryAcquire()).isFalse();
        clock.set(100L);                          // exactly cooldown elapsed
        assertThat(cb.state()).isEqualTo(HALF_OPEN);
        assertThat(cb.tryAcquire()).isTrue();     // single probe wins
        assertThat(cb.tryAcquire()).isFalse();    // second concurrent caller fails
    }

    @Test
    void successResetsAndProbeFailureReopens() {
        AtomicLong clock = new AtomicLong(0L);
        CircuitBreaker cb = new CircuitBreaker(1, 50L, clock::get);
        cb.recordFailure();                       // opens (threshold 1)
        assertThat(cb.state()).isEqualTo(OPEN);
        clock.set(50L);
        assertThat(cb.state()).isEqualTo(HALF_OPEN);
        cb.recordSuccess();
        assertThat(cb.state()).isEqualTo(CLOSED);
        cb.recordFailure();                       // reopen
        clock.set(100L);
        assertThat(cb.state()).isEqualTo(HALF_OPEN);
        cb.recordFailure();                       // probe failed → push window forward
        clock.set(120L);
        assertThat(cb.state()).isEqualTo(OPEN);   // 120 - 100 = 20 < 50 cooldown
    }

    @Test
    void rejectsInvalidArgs() {
        assertThatThrownBy(() -> new CircuitBreaker(0, 10L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("failureThreshold");
        assertThatThrownBy(() -> new CircuitBreaker(1, -1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cooldownMs");
    }
}
