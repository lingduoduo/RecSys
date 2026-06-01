package com.recsys.modelbased.service;

import com.recsys.modelbased.config.HealthProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoadShedderTest {

    @Test
    void tryAcquire_rejectsWhenConcurrencyLimitReached() {
        var props = new HealthProperties();
        props.setMaxConcurrentRequests(2);
        var shedder = new LoadShedder(props);

        assertThat(shedder.tryAcquire()).isTrue();
        assertThat(shedder.tryAcquire()).isTrue();
        assertThat(shedder.tryAcquire()).isFalse();

        var snapshot = shedder.snapshot();
        assertThat(snapshot.inFlightRequests()).isEqualTo(2);
        assertThat(snapshot.acceptedRequests()).isEqualTo(2);
        assertThat(snapshot.rejectedRequests()).isEqualTo(1);
        assertThat(snapshot.utilization()).isEqualTo(1.0);
    }

    @Test
    void release_allowsAnotherRequest() {
        var props = new HealthProperties();
        props.setMaxConcurrentRequests(1);
        var shedder = new LoadShedder(props);

        assertThat(shedder.tryAcquire()).isTrue();
        assertThat(shedder.tryAcquire()).isFalse();

        shedder.release();

        assertThat(shedder.tryAcquire()).isTrue();
        assertThat(shedder.snapshot().inFlightRequests()).isEqualTo(1);
    }

    @Test
    void shouldDrain_whenUtilizationExceedsReadinessThreshold() {
        var props = new HealthProperties();
        props.setMaxConcurrentRequests(4);
        props.setMaxInFlightUtilization(0.75);
        var shedder = new LoadShedder(props);

        assertThat(shedder.tryAcquire()).isTrue();
        assertThat(shedder.tryAcquire()).isTrue();
        assertThat(shedder.shouldDrain()).isFalse();

        assertThat(shedder.tryAcquire()).isTrue();
        assertThat(shedder.shouldDrain()).isTrue();
        assertThat(shedder.snapshot().suggestedWeight()).isEqualTo(25);
    }

    @Test
    void markShuttingDown_rejectsAllNewAcquires() {
        var props = new HealthProperties();
        props.setMaxConcurrentRequests(10);
        var shedder = new LoadShedder(props);

        assertThat(shedder.tryAcquire()).isTrue();
        shedder.markShuttingDown();

        assertThat(shedder.tryAcquire()).isFalse();
        assertThat(shedder.tryAcquire()).isFalse();

        var snapshot = shedder.snapshot();
        assertThat(snapshot.shuttingDown()).isTrue();
        assertThat(snapshot.suggestedWeight()).isZero();
        assertThat(shedder.shouldDrain()).isTrue();
        assertThat(snapshot.rejectedRequests()).isEqualTo(2);
    }

    @Test
    void markShuttingDown_doesNotAffectInFlightRelease() {
        var props = new HealthProperties();
        props.setMaxConcurrentRequests(5);
        var shedder = new LoadShedder(props);

        assertThat(shedder.tryAcquire()).isTrue();
        shedder.markShuttingDown();

        // In-flight request that acquired before shutdown must still release cleanly.
        shedder.release();
        assertThat(shedder.snapshot().inFlightRequests()).isZero();
    }
}
