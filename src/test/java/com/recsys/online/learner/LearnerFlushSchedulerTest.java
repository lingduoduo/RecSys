package com.recsys.online.learner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.util.Pool;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LearnerFlushSchedulerTest {

    private LearnerFlushScheduler scheduler;

    @AfterEach
    void tearDown() {
        if (scheduler != null) scheduler.close();
    }

    @Test
    void flushCalledAtLeastOnceWithinInterval() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        OnlineLearner learner = new OnlineLearner() {
            @Override
            public void flushToRedis(Pool<Jedis> pool, String keyPrefix) {
                latch.countDown();
            }
        };

        scheduler = new LearnerFlushScheduler(learner, new JedisPool(), "bias:", 1L);
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
            public void flushToRedis(Pool<Jedis> pool, String keyPrefix) {
                int count = flushAttempts.incrementAndGet();
                if (count == 1) throw new RuntimeException("Redis gone");
                twoSuccessful.countDown();
            }
        };

        scheduler = new LearnerFlushScheduler(learner, new JedisPool(), "bias:", 1L);
        scheduler.start();

        assertThat(twoSuccessful.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(scheduler.snapshot().errorCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void snapshotContainsIntervalSeconds() {
        scheduler = new LearnerFlushScheduler(new OnlineLearner(), new JedisPool(), "bias:", 30L);
        assertThat(scheduler.snapshot().intervalSeconds()).isEqualTo(30L);
    }
}
