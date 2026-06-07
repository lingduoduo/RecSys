package com.recsys.streaming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.Pool;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class LearnerFlushScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LearnerFlushScheduler.class);

    private final OnlineLearner learner;
    private final Pool<Jedis> pool;
    private final String keyPrefix;
    private final long intervalSeconds;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong flushCount = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private volatile long lastFlushMs = 0L;

    public LearnerFlushScheduler(OnlineLearner learner, Pool<Jedis> pool,
                                 String keyPrefix, long intervalSeconds) {
        this.learner         = learner;
        this.pool            = pool;
        this.keyPrefix       = keyPrefix;
        this.intervalSeconds = Math.max(1L, intervalSeconds);
        this.scheduler       = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "learner-flush");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(
                this::tryFlush, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private void tryFlush() {
        try {
            learner.flushToRedis(pool, keyPrefix);
            flushCount.incrementAndGet();
            lastFlushMs = System.currentTimeMillis();
        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.warn("LearnerFlushScheduler: flush error: {}", e.toString());
        }
    }

    @Override
    public void close() {
        scheduler.shutdown();
        tryFlush(); // best-effort final flush
    }

    public Snapshot snapshot() {
        return new Snapshot(flushCount.get(), errorCount.get(), lastFlushMs, intervalSeconds);
    }

    public record Snapshot(long flushCount, long errorCount, long lastFlushMs, long intervalSeconds) {}
}
