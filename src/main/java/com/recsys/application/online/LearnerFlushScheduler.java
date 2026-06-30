package com.recsys.application.online;

import com.recsys.infrastructure.redis.RedisExecutor;
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
    private final AtomicLong errorCount = new AtomicLong();
    private volatile long lastFlushMs = 0L;

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
                this::tryFlush, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private void tryFlush() {
        if (exec == null) return;
        try {
            learner.flushToRedis(exec, keyPrefix);
            flushCount.incrementAndGet();
            lastFlushMs = System.currentTimeMillis();
        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.warn("LearnerFlushScheduler: flush error", e);
        }
    }

    @Override
    public void close() {
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(intervalSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        tryFlush(); // final flush on calling thread
    }

    public Snapshot snapshot() {
        return new Snapshot(flushCount.get(), errorCount.get(), lastFlushMs, intervalSeconds);
    }

    public record Snapshot(long flushCount, long errorCount, long lastFlushMs, long intervalSeconds) {}
}
