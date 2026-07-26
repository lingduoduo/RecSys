package com.recsys.resilience;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;

import static com.recsys.resilience.RouteCircuitBreaker.State.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteCircuitBreakerTest {

    @Test
    void startsInClosedState() {
        assertThat(new RouteCircuitBreaker().state()).isEqualTo(CLOSED);
    }

    @Test
    void tryAcquire_trueWhenClosed() {
        assertThat(new RouteCircuitBreaker().tryAcquirePermit()).isNotNull();
    }

    @Test
    void opensAfterConsecutiveFailuresReachThreshold() {
        RouteCircuitBreaker cb = new RouteCircuitBreaker(3, 10_000L);

        cb.recordFailure(cb.tryAcquirePermit());
        cb.recordFailure(cb.tryAcquirePermit());
        assertThat(cb.state()).isEqualTo(CLOSED);

        cb.recordFailure(cb.tryAcquirePermit()); // threshold reached
        assertThat(cb.state()).isEqualTo(OPEN);
        assertThat(cb.tryAcquirePermit()).isNull();
    }

    @Test
    void successResetsFailureCount() {
        RouteCircuitBreaker cb = new RouteCircuitBreaker(3, 10_000L);

        RouteCircuitBreaker.Permit first = cb.tryAcquirePermit();
        RouteCircuitBreaker.Permit second = cb.tryAcquirePermit();
        RouteCircuitBreaker.Permit success = cb.tryAcquirePermit();
        cb.recordFailure(first);
        cb.recordFailure(second);
        cb.recordSuccess(success);   // resets counter
        cb.recordFailure(cb.tryAcquirePermit()); // back to 1
        cb.recordFailure(cb.tryAcquirePermit()); // 2
        assertThat(cb.state()).isEqualTo(CLOSED); // threshold not reached again
    }

    @Test
    void transitionsToHalfOpenAfterCooldown() {
        AtomicLong clock = new AtomicLong();
        RouteCircuitBreaker cb = new RouteCircuitBreaker(1, 50L, clock::get);

        cb.recordFailure(cb.tryAcquirePermit()); // opens immediately (threshold = 1)
        assertThat(cb.state()).isEqualTo(OPEN);

        clock.set(50L);

        assertThat(cb.state()).isEqualTo(HALF_OPEN);
        assertThat(cb.tryAcquirePermit()).isNotNull(); // probe request allowed
    }

    @Test
    void halfOpenClosesOnSuccess() {
        AtomicLong clock = new AtomicLong();
        RouteCircuitBreaker cb = new RouteCircuitBreaker(1, 50L, clock::get);

        cb.recordFailure(cb.tryAcquirePermit());
        clock.set(50L);
        assertThat(cb.state()).isEqualTo(HALF_OPEN);

        cb.recordSuccess(cb.tryAcquirePermit());
        assertThat(cb.state()).isEqualTo(CLOSED);
        assertThat(cb.tryAcquirePermit()).isNotNull();
    }

    @Test
    void halfOpenAllowsOnlyOneProbe() {
        AtomicLong clock = new AtomicLong();
        RouteCircuitBreaker cb = new RouteCircuitBreaker(1, 50L, clock::get);
        cb.recordFailure(cb.tryAcquirePermit());
        clock.set(50L);
        assertThat(cb.state()).isEqualTo(HALF_OPEN);

        assertThat(cb.tryAcquirePermit()).isNotNull(); // first caller gets the probe token
        assertThat(cb.tryAcquirePermit()).isNull();    // second concurrent caller is fast-failed
    }

    @Test
    void halfOpenReopensOnFailure() {
        AtomicLong clock = new AtomicLong();
        RouteCircuitBreaker cb = new RouteCircuitBreaker(1, 50L, clock::get);

        cb.recordFailure(cb.tryAcquirePermit());
        clock.set(50L);
        assertThat(cb.state()).isEqualTo(HALF_OPEN);

        cb.recordFailure(cb.tryAcquirePermit()); // probe failed → reopen
        assertThat(cb.state()).isEqualTo(OPEN);
        assertThat(cb.tryAcquirePermit()).isNull();
    }

    @Test
    void staleCompletionDoesNotCloseRecoveryProbe() {
        AtomicLong clock = new AtomicLong();
        RouteCircuitBreaker cb = new RouteCircuitBreaker(1, 50L, clock::get);

        RouteCircuitBreaker.Permit stale = cb.tryAcquirePermit();
        cb.recordFailure(stale); // opens the route under the initial generation
        clock.set(50L);

        assertThat(cb.tryAcquirePermit()).isNotNull(); // recovery probe claims the new generation
        cb.recordSuccess(stale); // completion from the prior generation must be ignored

        assertThat(cb.state()).isEqualTo(HALF_OPEN);
    }

    @Test
    void permitRejectsNullDelegate() {
        assertThatThrownBy(() -> new RouteCircuitBreaker.Permit(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("delegate");
    }

    @Test
    void completionRejectsNullPermit() {
        RouteCircuitBreaker cb = new RouteCircuitBreaker();

        assertThatThrownBy(() -> cb.recordSuccess(null))
                .isInstanceOf(NullPointerException.class);
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
