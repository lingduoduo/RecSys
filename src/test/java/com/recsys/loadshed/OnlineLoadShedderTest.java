package com.recsys.loadshed;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OnlineLoadShedderTest {

    @Test
    void tryAcquire_rejectsWhenConcurrencyLimitReached() {
        var shedder = new OnlineLoadShedder(2, 0.75);

        assertThat(shedder.tryAcquire()).isTrue();
        assertThat(shedder.tryAcquire()).isTrue();
        assertThat(shedder.tryAcquire()).isFalse();

        var snapshot = shedder.snapshot();
        assertThat(snapshot.inFlightRequests()).isEqualTo(2);
        assertThat(snapshot.acceptedRequests()).isEqualTo(2);
        assertThat(snapshot.rejectedRequests()).isEqualTo(1);
        assertThat(snapshot.utilization()).isEqualTo(1.0);
        assertThat(shedder.shouldDrain()).isTrue();
    }

    @Test
    void release_allowsAnotherRequest() {
        var shedder = new OnlineLoadShedder(1, 1.0);

        assertThat(shedder.tryAcquire()).isTrue();
        assertThat(shedder.tryAcquire()).isFalse();

        shedder.release();

        assertThat(shedder.tryAcquire()).isTrue();
        assertThat(shedder.snapshot().inFlightRequests()).isEqualTo(1);
    }

    @Test
    void suggestedWeight_reflectsAvailableHeadroom() {
        var shedder = new OnlineLoadShedder(4, 0.90);

        assertThat(shedder.tryAcquire()).isTrue();

        assertThat(shedder.snapshot().suggestedWeight()).isEqualTo(75);
    }

    @Test
    void retryAfterSeconds_isOneWhenDraining_zeroOtherwise() {
        var shedder = new OnlineLoadShedder(2, 0.75);

        // below drain threshold — no retry-after
        assertThat(shedder.snapshot().retryAfterSeconds()).isEqualTo(0);
        assertThat(shedder.retryAfterSeconds()).isEqualTo(0);

        shedder.tryAcquire();
        shedder.tryAcquire(); // 100% utilization >= 0.75 drain threshold

        assertThat(shedder.shouldDrain()).isTrue();
        assertThat(shedder.snapshot().retryAfterSeconds()).isEqualTo(1);
        assertThat(shedder.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void markShuttingDown_rejectsNewRequestsAndFlipsDrain() {
        var shedder = new OnlineLoadShedder(4, 0.95); // plenty of headroom, not utilization-draining

        assertThat(shedder.isShuttingDown()).isFalse();
        assertThat(shedder.shouldDrain()).isFalse();

        shedder.markShuttingDown();

        assertThat(shedder.isShuttingDown()).isTrue();
        assertThat(shedder.tryAcquire()).isFalse();          // new work rejected during drain
        assertThat(shedder.shouldDrain()).isTrue();           // readiness will report 503

        var snap = shedder.snapshot();
        assertThat(snap.shuttingDown()).isTrue();
        assertThat(snap.suggestedWeight()).isEqualTo(0);      // advertise zero weight while draining
        assertThat(snap.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void markShuttingDown_isIdempotent() {
        var shedder = new OnlineLoadShedder(2, 0.95);
        shedder.markShuttingDown();
        shedder.markShuttingDown();
        assertThat(shedder.isShuttingDown()).isTrue();
        assertThat(shedder.tryAcquire()).isFalse();
    }

    @Test
    void defaultConstructor_drainUtilizationIsNinetyFive() {
        // Verifies DEFAULT_DRAIN_UTILIZATION; assumes ONLINE_DRAIN_UTILIZATION env is unset.
        assertThat(new OnlineLoadShedder().snapshot().drainUtilization()).isEqualTo(0.95);
    }
}
