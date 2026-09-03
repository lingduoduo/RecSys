package com.recsys.resilience;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A {@code ScheduledExecutorService} cancels a fixed-delay schedule the first time the task
 * throws, and stores the Throwable in a future nobody reads — measured in 18_Fault_Tolerance
 * §9.3. {@link GuardedLoop} exists so no scheduled body in this repo can do that, and so the
 * loop's health is read at scrape time from a timestamp, not written by the loop itself.
 * Pure unit-level apart from the last test, which waits on a latch with a generous timeout
 * rather than sleeping to assert ordering.
 */
class GuardedLoopTest {

    private static final String LOOP = "probe-loop";

    @Test
    void anExceptionFromTheBodyIsSwallowedAndCounted() {
        GuardedLoop loop = new GuardedLoop(LOOP, () -> { throw new IllegalStateException("boom"); });

        loop.run();

        assertThat(loop.failureCount()).isEqualTo(1);
        assertThat(loop.lastFailure()).get().isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aJvmErrorFromTheBodyIsSwallowedAndCountedToo() {
        AtomicInteger calls = new AtomicInteger();
        GuardedLoop loop = new GuardedLoop(LOOP, () -> {
            if (calls.incrementAndGet() == 1) throw new StackOverflowError("boom");
            if (calls.get() == 2) throw new OutOfMemoryError("boom");
        });

        loop.run();
        loop.run();
        loop.run();

        assertThat(calls).hasValue(3);
        assertThat(loop.failureCount()).isEqualTo(2);
        assertThat(loop.lastFailure()).get().isInstanceOf(OutOfMemoryError.class);
    }

    @Test
    @SuppressWarnings("removal")
    void threadDeathIsTheOneThrowableThatStillPropagates() {
        GuardedLoop loop = new GuardedLoop(LOOP, () -> { throw new ThreadDeath(); });

        assertThatThrownBy(loop::run).isInstanceOf(ThreadDeath.class);
    }

    @Test
    void secondsSinceLastSuccessIsReadFromTheClockNotWrittenByTheBody() {
        AtomicLong nanos = new AtomicLong(0);
        AtomicInteger calls = new AtomicInteger();
        GuardedLoop loop = new GuardedLoop(LOOP, () -> {
            if (calls.incrementAndGet() == 2) throw new StackOverflowError("boom");
        }, nanos::get);

        assertThat(loop.secondsSinceLastSuccess()).as("-1 sentinel before any success, never 0").isEqualTo(-1.0);

        nanos.set(TimeUnit.SECONDS.toNanos(10));
        loop.run();                                   // success at t=10s
        nanos.set(TimeUnit.SECONDS.toNanos(14));
        assertThat(loop.secondsSinceLastSuccess()).isEqualTo(4.0);

        loop.run();                                   // failure at t=14s must not move the mark
        nanos.set(TimeUnit.SECONDS.toNanos(20));
        assertThat(loop.secondsSinceLastSuccess()).isEqualTo(10.0);
    }

    @Test
    void bindToPublishesAgeGaugeAndFailureCounterTaggedByLoopName() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicLong nanos = new AtomicLong(TimeUnit.SECONDS.toNanos(100));
        AtomicInteger calls = new AtomicInteger();
        GuardedLoop loop = new GuardedLoop(LOOP, () -> {
            if (calls.incrementAndGet() == 1) throw new NoClassDefFoundError("boom");
        }, nanos::get).bindTo(registry);

        loop.run();                                   // failure
        loop.run();                                   // success at t=100s
        nanos.set(TimeUnit.SECONDS.toNanos(103));

        assertThat(registry.get(GuardedLoop.SECONDS_SINCE_SUCCESS).tag("loop", LOOP).gauge().value()).isEqualTo(3.0);
        assertThat(registry.get(GuardedLoop.FAILURES).tag("loop", LOOP).functionCounter().count()).isEqualTo(1.0);
    }

    @Test
    void aScheduledLoopKeepsRunningAfterAnError() throws Exception {
        // The property everything else here exists for: with the guard, the schedule survives.
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch threeRuns = new CountDownLatch(3);
        AtomicInteger calls = new AtomicInteger();
        GuardedLoop loop = new GuardedLoop(LOOP, () -> {
            threeRuns.countDown();
            if (calls.incrementAndGet() == 1) throw new StackOverflowError("boom");
        });
        try {
            ScheduledFuture<?> schedule = scheduler.scheduleWithFixedDelay(loop, 0, 10, TimeUnit.MILLISECONDS);

            assertThat(threeRuns.await(5, TimeUnit.SECONDS)).as("ran again after the Error").isTrue();
            assertThat(schedule.isDone()).as("schedule not cancelled").isFalse();
            assertThat(loop.failureCount()).isEqualTo(1);
        } finally {
            scheduler.shutdownNow();
        }
    }
}
