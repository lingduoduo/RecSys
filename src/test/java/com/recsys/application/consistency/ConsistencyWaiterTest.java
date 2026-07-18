package com.recsys.application.consistency;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConsistencyWaiterTest {
    private static final UUID EVENT_ID = UUID.fromString("82fb7ddf-9e77-4cd9-91e4-8a34f13cc738");

    @Test void waiterStopsWhenLineageAppears() {
        MutableClock clock = new MutableClock();
        Queue<Boolean> answers = new ArrayDeque<>();
        answers.add(false); answers.add(false); answers.add(true);
        ConsistencyWaiter waiter = new ConsistencyWaiter(
                (eventId, userId) -> answers.remove(), clock, clock::advance, Duration.ofMillis(50));

        assertThat(waiter.await(EVENT_ID, 42, Duration.ofSeconds(2)))
                .isEqualTo(ConsistencyWaiter.WaitResult.APPLIED);
        assertThat(clock.instant()).isEqualTo(Instant.EPOCH.plusMillis(100));
    }

    @Test void waiterReturnsPendingAtDeadline() {
        MutableClock clock = new MutableClock();
        ConsistencyWaiter waiter = new ConsistencyWaiter(
                (eventId, userId) -> false, clock, clock::advance, Duration.ofMillis(50));

        assertThat(waiter.await(EVENT_ID, 42, Duration.ofSeconds(2)))
                .isEqualTo(ConsistencyWaiter.WaitResult.PENDING);
        assertThat(clock.instant()).isEqualTo(Instant.EPOCH.plusSeconds(2));
    }

    @Test void timeoutIsCappedAtTwoSeconds() {
        MutableClock clock = new MutableClock();
        ConsistencyWaiter waiter = new ConsistencyWaiter(
                (eventId, userId) -> false, clock, clock::advance, Duration.ofMillis(50));

        assertThat(waiter.await(EVENT_ID, 42, Duration.ofSeconds(30)))
                .isEqualTo(ConsistencyWaiter.WaitResult.PENDING);
        assertThat(clock.instant()).isEqualTo(Instant.EPOCH.plusSeconds(2));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.EPOCH;
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
