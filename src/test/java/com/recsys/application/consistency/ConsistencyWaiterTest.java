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
                (eventId, userId, remaining) -> answers.remove(), clock, clock::advance,
                Duration.ofMillis(50), clock::nanoTime);

        assertThat(waiter.await(EVENT_ID, 42, Duration.ofSeconds(2)))
                .isEqualTo(ConsistencyWaiter.WaitResult.APPLIED);
        assertThat(clock.instant()).isEqualTo(Instant.EPOCH.plusMillis(100));
    }

    @Test void waiterReturnsPendingAtDeadline() {
        MutableClock clock = new MutableClock();
        ConsistencyWaiter waiter = new ConsistencyWaiter(
                (eventId, userId, remaining) -> false, clock, clock::advance,
                Duration.ofMillis(50), clock::nanoTime);

        assertThat(waiter.await(EVENT_ID, 42, Duration.ofSeconds(2)))
                .isEqualTo(ConsistencyWaiter.WaitResult.PENDING);
        assertThat(clock.instant()).isEqualTo(Instant.EPOCH.plusSeconds(2));
    }

    @Test void timeoutIsCappedAtTwoSeconds() {
        MutableClock clock = new MutableClock();
        ConsistencyWaiter waiter = new ConsistencyWaiter(
                (eventId, userId, remaining) -> false, clock, clock::advance,
                Duration.ofMillis(50), clock::nanoTime);

        assertThat(waiter.await(EVENT_ID, 42, Duration.ofSeconds(30)))
                .isEqualTo(ConsistencyWaiter.WaitResult.PENDING);
        assertThat(clock.instant()).isEqualTo(Instant.EPOCH.plusSeconds(2));
    }

    @Test void doesNotPerformFinalLookupAfterDeadline() {
        MutableClock clock = new MutableClock();
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        ConsistencyWaiter waiter = new ConsistencyWaiter(
                (eventId, userId, remaining) -> { reads.incrementAndGet(); return false; },
                clock, clock::advance, Duration.ofSeconds(2), clock::nanoTime);

        assertThat(waiter.await(EVENT_ID, 42, Duration.ofSeconds(2)))
                .isEqualTo(ConsistencyWaiter.WaitResult.PENDING);
        assertThat(reads).hasValue(1);
    }

    @Test void passesRemainingBudgetToEveryLineageReadDespiteWallClockRollback() {
        MutableClock clock = new MutableClock();
        java.util.List<Duration> budgets = new java.util.ArrayList<>();
        ConsistencyWaiter waiter = new ConsistencyWaiter((eventId, userId, remaining) -> {
            budgets.add(remaining);
            return false;
        }, clock, duration -> {
            clock.advance(duration);
            if (budgets.size() == 1) clock.rewind(Duration.ofHours(1));
        }, Duration.ofSeconds(1), clock::nanoTime);

        waiter.await(EVENT_ID, 42, Duration.ofSeconds(2));

        assertThat(budgets).allMatch(d -> !d.isZero() && !d.isNegative()
                && d.compareTo(Duration.ofSeconds(2)) <= 0);
    }

    @Test void slowLineageResultArrivingAfterDeadlineIsNotAccepted() {
        MutableClock clock = new MutableClock();
        ConsistencyWaiter waiter = new ConsistencyWaiter((eventId, userId, remaining) -> {
            clock.advance(remaining.plusNanos(1));
            return true;
        }, clock, duration -> {}, Duration.ofMillis(50), clock::nanoTime);

        assertThat(waiter.await(EVENT_ID, 42, Duration.ofSeconds(2)))
                .isEqualTo(ConsistencyWaiter.WaitResult.PENDING);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.EPOCH;
        private long nanos;
        void advance(Duration duration) { now = now.plus(duration); nanos += duration.toNanos(); }
        void rewind(Duration duration) { now = now.minus(duration); }
        long nanoTime() { return nanos; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
