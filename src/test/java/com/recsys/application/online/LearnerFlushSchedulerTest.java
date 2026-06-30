package com.recsys.application.online;

import com.recsys.infrastructure.redis.RedisExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LearnerFlushSchedulerTest {

    private LearnerFlushScheduler scheduler;

    private static RedisExecutor stubExecutor() {
        return mock(RedisExecutor.class);
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) scheduler.close();
    }

    @Test
    void flushCalledAtLeastOnceWithinInterval() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        OnlineLearner learner = new OnlineLearner() {
            @Override
            public void flushToRedis(RedisExecutor exec, String keyPrefix) {
                latch.countDown();
            }
        };

        scheduler = new LearnerFlushScheduler(learner, stubExecutor(), "bias:", 1L);
        scheduler.start();

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(scheduler.snapshot().flushCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void flushErrorDoesNotStopFutureFlushes() throws Exception {
        AtomicInteger flushAttempts = new AtomicInteger();
        CountDownLatch twoSuccessful = new CountDownLatch(2);
        OnlineLearner learner = new OnlineLearner() {
            @Override
            public void flushToRedis(RedisExecutor exec, String keyPrefix) {
                int count = flushAttempts.incrementAndGet();
                if (count == 1) throw new RuntimeException("Redis gone");
                twoSuccessful.countDown();
            }
        };

        scheduler = new LearnerFlushScheduler(learner, stubExecutor(), "bias:", 1L);
        scheduler.start();

        assertThat(twoSuccessful.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(scheduler.snapshot().errorCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void snapshotContainsIntervalSeconds() {
        scheduler = new LearnerFlushScheduler(new OnlineLearner(), stubExecutor(), "bias:", 30L);
        assertThat(scheduler.snapshot().intervalSeconds()).isEqualTo(30L);
    }
}
