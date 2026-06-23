package com.recsys.reliability;
import com.recsys.reliability.RouteCircuitBreaker;

import org.junit.jupiter.api.Test;

import static com.recsys.reliability.RouteCircuitBreaker.State.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteCircuitBreakerTest {

    @Test
    void startsInClosedState() {
        assertThat(new RouteCircuitBreaker().state()).isEqualTo(CLOSED);
    }

    @Test
    void tryAcquire_trueWhenClosed() {
        assertThat(new RouteCircuitBreaker().tryAcquire()).isTrue();
    }

    @Test
    void opensAfterConsecutiveFailuresReachThreshold() {
        RouteCircuitBreaker cb = new RouteCircuitBreaker(3, 10_000L);

        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(CLOSED);

        cb.recordFailure(); // threshold reached
        assertThat(cb.state()).isEqualTo(OPEN);
        assertThat(cb.tryAcquire()).isFalse();
    }

    @Test
    void successResetsFailureCount() {
        RouteCircuitBreaker cb = new RouteCircuitBreaker(3, 10_000L);

        cb.recordFailure();
        cb.recordFailure();
        cb.recordSuccess();   // resets counter
        cb.recordFailure();   // back to 1
        cb.recordFailure();   // 2
        assertThat(cb.state()).isEqualTo(CLOSED); // threshold not reached again
    }

    @Test
    void transitionsToHalfOpenAfterCooldown() throws InterruptedException {
        RouteCircuitBreaker cb = new RouteCircuitBreaker(1, 50L); // 50 ms cooldown

        cb.recordFailure(); // opens immediately (threshold = 1)
        assertThat(cb.state()).isEqualTo(OPEN);

        Thread.sleep(60); // wait past cooldown

        assertThat(cb.state()).isEqualTo(HALF_OPEN);
        assertThat(cb.tryAcquire()).isTrue(); // probe request allowed
    }

    @Test
    void halfOpenClosesOnSuccess() throws InterruptedException {
        RouteCircuitBreaker cb = new RouteCircuitBreaker(1, 50L);

        cb.recordFailure();
        Thread.sleep(60);
        assertThat(cb.state()).isEqualTo(HALF_OPEN);

        cb.recordSuccess();
        assertThat(cb.state()).isEqualTo(CLOSED);
        assertThat(cb.tryAcquire()).isTrue();
    }

    @Test
    void halfOpenAllowsOnlyOneProbe() throws InterruptedException {
        RouteCircuitBreaker cb = new RouteCircuitBreaker(1, 50L);
        cb.recordFailure();
        Thread.sleep(60);
        assertThat(cb.state()).isEqualTo(HALF_OPEN);

        assertThat(cb.tryAcquire()).isTrue();  // first caller gets the probe token
        assertThat(cb.tryAcquire()).isFalse(); // second concurrent caller is fast-failed
    }

    @Test
    void halfOpenReopensOnFailure() throws InterruptedException {
        RouteCircuitBreaker cb = new RouteCircuitBreaker(1, 50L);

        cb.recordFailure();
        Thread.sleep(60);
        assertThat(cb.state()).isEqualTo(HALF_OPEN);

        cb.recordFailure(); // probe failed → reopen
        assertThat(cb.state()).isEqualTo(OPEN);
        assertThat(cb.tryAcquire()).isFalse();
    }

    @Test
    void rejectsInvalidThreshold() {
        assertThatThrownBy(() -> new RouteCircuitBreaker(0, 1000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failureThreshold");
    }

    @Test
    void rejectsNegativeCooldown() {
        assertThatThrownBy(() -> new RouteCircuitBreaker(1, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cooldownMs");
    }
}
