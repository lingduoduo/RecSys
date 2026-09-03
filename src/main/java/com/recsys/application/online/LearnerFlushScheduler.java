package com.recsys.application.online;

import com.recsys.infrastructure.redis.RedisExecutor;
import com.recsys.resilience.GuardedLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class LearnerFlushScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LearnerFlushScheduler.class);

    private final OnlineLearner learner;
    private final RedisExecutor exec;
    private final String keyPrefix;
    private final long intervalSeconds;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong flushCount = new AtomicLong();
    private volatile long lastFlushMs = 0L;
    /** Every flush, scheduled or the final one in close(), runs through this so no Throwable can cancel the schedule. */
    private final GuardedLoop loop = new GuardedLoop("learner-flush", this::flushOnce);

    public LearnerFlushScheduler(OnlineLearner learner, RedisExecutor exec,
                                 String keyPrefix, long intervalSeconds) {
        this.learner         = learner;
        this.exec            = exec;
        this.keyPrefix       = keyPrefix;
        this.intervalSeconds = Math.max(1L, intervalSeconds);
        this.scheduler       = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "learner-flush");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        // scheduleWithFixedDelay: interval measured after flush completes, preventing back-to-back writes
        scheduler.scheduleWithFixedDelay(
                loop, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    /** The loop's health (age since last good flush, failure count); bind it to a registry to publish. */
    public GuardedLoop loop() {
        return loop;
    }

    private void flushOnce() {
        if (exec == null) return;
        learner.flushToRedis(exec, keyPrefix);   // a throw here is counted and logged by the loop
        flushCount.incrementAndGet();
        lastFlushMs = System.currentTimeMillis();
    }

    @Override
    public void close() {
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(intervalSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        loop.run(); // final flush on calling thread; never throws
    }

    public Snapshot snapshot() {
        return new Snapshot(flushCount.get(), loop.failureCount(), lastFlushMs, intervalSeconds);
    }

    public record Snapshot(long flushCount, long errorCount, long lastFlushMs, long intervalSeconds) {}
}
