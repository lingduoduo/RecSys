package com.recsys.infrastructure.redis;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Reports keys that have no TTL and are not declared durable.
 *
 * <p>{@code volatile-lru} makes "has a TTL" the eviction boundary: cache-like keys expire and
 * are evictable, keys without a TTL are authoritative and structurally protected. That is only
 * correct while every writer honours it, and a writer that forgets a TTL silently converts its
 * key into permanently-resident, unevictable state.
 *
 * <p>This watches the keyspace rather than the code on purpose. The highest-volume writer in the
 * system is the Flink job, which is excluded from the Maven compile and writes through Lua, so no
 * source-level check can see it — nor can one see anything written out-of-band. The cost is that
 * detection is probabilistic: one bounded {@code SCAN} page per tick, cursor carried across ticks,
 * so coverage accrues over time at fixed cost per tick.
 *
 * <p>It never mutates the keyspace. A key misjudged as unexpected is exactly the authoritative
 * state the invariant exists to protect, so remediation is a human decision.
 */
public final class RedisPersistentKeyProbe implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisPersistentKeyProbe.class);

    /** Redis reports -1 for "key exists, no expiry set". */
    private static final long NO_EXPIRY = -1L;
    private static final int DEFAULT_PAGE_SIZE = 200;
    private static final int MAX_EXAMPLES = 3;

    /**
     * The namespaces whose keys are authoritative and therefore legitimately TTL-less.
     *
     * <p>{@code sr:} is deliberately the bare sharded-record namespace, not a narrower prefix
     * like {@code sr:seq:}: it covers records ({@code sr:rec:<shard>:<seq>}), device indexes
     * ({@code sr:dev:<shard>:<id>}), streams ({@code sr:stream:<shard>}), and sequence counters
     * ({@code sr:seq:<shard>}), none of which are ever expired by {@code ShardedRecordStore} or
     * {@code SequenceGenerator}. Staying at the bare namespace also keeps generation-prefixed
     * keys covered after a reshard, since {@code Generations.keyPrefix(v)} rewrites these to
     * {@code sr:g2:seq:…}, {@code sr:g2:rec:…}, etc. — a narrower prefix would stop matching.
     */
    public static final List<String> DEFAULT_DURABLE_PREFIXES =
            List.of("shard:topology", "i2vEmb:", "u2vEmb:", "sr:", "bias:item:");

    /**
     * @param examples a bounded sample of offending key names — logged, never used as a metric
     *                 label, because key names are unbounded cardinality.
     */
    public record KeyspaceSample(boolean available, int scanned, int unexpected, List<String> examples) {
        public static KeyspaceSample unavailable() {
            return new KeyspaceSample(false, 0, 0, List.of());
        }
    }

    private final RedisExecutor redis;
    private final List<String> durablePrefixes;
    private final int pageSize;

    private ScanCursor cursor = ScanCursor.INITIAL;
    private ScheduledExecutorService executor;

    public RedisPersistentKeyProbe(RedisExecutor redis) {
        this(redis, DEFAULT_DURABLE_PREFIXES, DEFAULT_PAGE_SIZE);
    }

    public RedisPersistentKeyProbe(RedisExecutor redis, List<String> durablePrefixes, int pageSize) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.durablePrefixes = List.copyOf(Objects.requireNonNull(durablePrefixes, "durablePrefixes"));
        this.pageSize = Math.max(1, pageSize);
    }

    /** Cursor this probe will resume from; "0" means the next sample restarts the scan. */
    synchronized String cursorPosition() {
        return cursor.getCursor();
    }

    public synchronized KeyspaceSample sample() {
        try {
            ScanCursor from = cursor;
            KeyScanCursor<String> page =
                    redis.executeRead(c -> c.scan(from, ScanArgs.Builder.limit(pageSize)));
            if (page == null) return KeyspaceSample.unavailable();

            cursor = page.isFinished() ? ScanCursor.INITIAL : ScanCursor.of(page.getCursor());

            List<String> keys = page.getKeys();
            List<String> offenders = new ArrayList<>();
            for (String key : keys) {
                if (isDeclaredDurable(key)) continue;
                Long ttl = redis.executeRead(c -> c.ttl(key));
                if (ttl != null && ttl == NO_EXPIRY) offenders.add(key);
            }

            List<String> examples = List.copyOf(offenders.subList(0, Math.min(MAX_EXAMPLES, offenders.size())));
            if (!offenders.isEmpty()) {
                log.warn("{} sampled key(s) have no TTL and are not declared durable; "
                                + "under volatile-lru these can never be evicted. Examples: {}",
                        offenders.size(), examples);
            }
            return new KeyspaceSample(true, keys.size(), offenders.size(), examples);
        } catch (RuntimeException failure) {
            log.warn("Keyspace sample failed: {}", failure.toString());
            return KeyspaceSample.unavailable();
        }
    }

    private boolean isDeclaredDurable(String key) {
        for (String prefix : durablePrefixes) {
            if (key.startsWith(prefix)) return true;
        }
        return false;
    }

    public synchronized void start(Duration interval, Consumer<KeyspaceSample> observer) {
        Objects.requireNonNull(interval);
        Objects.requireNonNull(observer);
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        if (executor != null) throw new IllegalStateException("probe already started");
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "redis-persistent-key-probe");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                observer.accept(sample());
            } catch (Throwable ignored) {
                // An observer must not permanently cancel fixed-delay sampling.
            }
        }, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (executor == null) return;
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        executor = null;
    }
}
