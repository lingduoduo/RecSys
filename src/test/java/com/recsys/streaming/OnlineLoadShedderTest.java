package com.recsys.streaming;

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
}
