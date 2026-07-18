package com.recsys.infrastructure.redis;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Writes a primary marker and observes the latest marker visible through replica routing. */
public final class RedisReplicaLagProbe implements AutoCloseable {
    public static final String DEFAULT_KEY = "recsys:replica-lag-probe";
    public record ProbeResult(boolean available, double lagSeconds) {}

    private final RedisExecutor redis;
    private final Clock clock;
    private final String key;
    private final AtomicLong sequence = new AtomicLong();
    private ScheduledExecutorService executor;

    public RedisReplicaLagProbe(RedisExecutor redis, Clock clock) { this(redis, clock, DEFAULT_KEY); }
    public RedisReplicaLagProbe(RedisExecutor redis, Clock clock, String key) {
        this.redis = Objects.requireNonNull(redis); this.clock = Objects.requireNonNull(clock);
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key must not be blank");
        this.key = key;
    }

    public ProbeResult sample() {
        long now = clock.millis();
        try {
            redis.execute(commands -> commands.set(key, sequence.incrementAndGet() + ":" + now));
            String observed = redis.executeRead(commands -> commands.get(key));
            if (observed == null) return unavailable();
            int separator = observed.indexOf(':');
            if (separator < 1) return unavailable();
            long observedMillis = Long.parseLong(observed.substring(separator + 1));
            return new ProbeResult(true, Math.max(0, now - observedMillis) / 1000d);
        } catch (RuntimeException failure) {
            return unavailable();
        }
    }

    public synchronized void start(Duration interval, Consumer<ProbeResult> observer) {
        Objects.requireNonNull(interval); Objects.requireNonNull(observer);
        if (interval.isZero() || interval.isNegative()) throw new IllegalArgumentException("interval must be positive");
        if (executor != null) throw new IllegalStateException("probe already started");
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "redis-replica-lag-probe"); thread.setDaemon(true); return thread;
        });
        executor.scheduleWithFixedDelay(() -> observer.accept(sample()), 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override public synchronized void close() { if (executor != null) executor.shutdownNow(); }
    private static ProbeResult unavailable() { return new ProbeResult(false, Double.NaN); }
}
