package com.recsys.streaming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis-backed fixed-window rate limiter for cross-instance request protection.
 *
 * Local pre-check: the first {@code localPassThreshold} requests in each window are
 * allowed without a Redis round-trip.  Only when the local count climbs above that
 * threshold does the limiter consult Redis for accurate cross-instance accounting.
 * This keeps the common case (well under limit) free of network latency while still
 * enforcing the global limit for burst traffic.
 */
public final class RedisRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final String SCRIPT = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
            end
            local ttl = redis.call('TTL', KEYS[1])
            if current > tonumber(ARGV[1]) then
              return {0, 0, ttl}
            end
            return {1, tonumber(ARGV[1]) - current, ttl}
            """;

    private final JedisPool pool;
    private final String keyPrefix;
    private final long limit;
    private final int windowSeconds;
    private final boolean enabled;
    private final long localPassThreshold;

    // Local window state — benign race at boundary is acceptable for a soft pre-check.
    private volatile long localWindowBucket = -1L;
    private final AtomicLong localCount = new AtomicLong(0L);

    public RedisRateLimiter(JedisPool pool) {
        this(
                pool,
                "rate:online:",
                readLongEnv("ONLINE_REDIS_RATE_LIMIT_QPS", 0L),
                readIntEnv("ONLINE_REDIS_RATE_LIMIT_WINDOW_SECONDS", 1)
        );
    }

    RedisRateLimiter(JedisPool pool, String keyPrefix, long limit, int windowSeconds) {
        this(pool, keyPrefix, limit, windowSeconds, 0.7);
    }

    RedisRateLimiter(JedisPool pool, String keyPrefix, long limit, int windowSeconds,
                     double localPassFraction) {
        this.pool = pool;
        this.keyPrefix = keyPrefix;
        this.limit = Math.max(0L, limit);
        this.windowSeconds = Math.max(1, windowSeconds);
        this.enabled = pool != null && this.limit > 0L;
        this.localPassThreshold = (long) (Math.max(0L, this.limit) * Math.max(0.0, Math.min(1.0, localPassFraction)));
    }

    public static RedisRateLimiter disabled() {
        return new RedisRateLimiter(null, "rate:online:", 0L, 1, 0.0);
    }

    public Decision tryAcquire(String bucket) {
        if (!enabled) {
            return Decision.allowed(limit, 0, false);
        }

        // Local fast path: skip Redis when clearly under threshold for this window.
        long nowBucket = System.currentTimeMillis() / (windowSeconds * 1_000L);
        if (nowBucket != localWindowBucket) {
            localWindowBucket = nowBucket;
            localCount.set(0L);
        }
        long local = localCount.incrementAndGet();
        if (local <= localPassThreshold) {
            return Decision.allowed(limit - local, 0, false);
        }

        // Above local threshold — consult Redis for accurate cross-instance count.
        String key = keyPrefix + normalizeBucket(bucket);
        try (Jedis jedis = pool.getResource()) {
            Object raw = jedis.eval(
                    SCRIPT,
                    List.of(key),
                    List.of(Long.toString(limit), Integer.toString(windowSeconds))
            );
            return parseDecision(raw);
        } catch (Exception e) {
            log.warn("Redis rate limiter failed open for bucket '{}': {}", bucket, e.toString());
            return Decision.allowed(limit, 0, true);
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(enabled, limit, windowSeconds, localPassThreshold);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long limit() {
        return limit;
    }

    public int windowSeconds() {
        return windowSeconds;
    }

    public long localPassThreshold() {
        return localPassThreshold;
    }

    private Decision parseDecision(Object raw) {
        if (raw instanceof List<?> values && values.size() >= 3) {
            boolean allowed = asLong(values.get(0)) == 1L;
            long remaining = asLong(values.get(1));
            int retryAfterSeconds = Math.max(0, (int) asLong(values.get(2)));
            return new Decision(allowed, remaining, retryAfterSeconds, false);
        }
        return Decision.allowed(limit, 0, true);
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignore) {
                return 0L;
            }
        }
        return 0L;
    }

    private static String normalizeBucket(String bucket) {
        if (bucket == null || bucket.isBlank()) {
            return "global";
        }
        return bucket.trim().replaceAll("[^a-zA-Z0-9:_-]", "_");
    }

    private static int readIntEnv(String envName, int defaultValue) {
        String raw = System.getenv(envName);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long readLongEnv(String envName, long defaultValue) {
        String raw = System.getenv(envName);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public record Decision(
            boolean allowed,
            long remaining,
            int retryAfterSeconds,
            boolean failOpen
    ) {
        static Decision allowed(long remaining, int retryAfterSeconds, boolean failOpen) {
            return new Decision(true, remaining, retryAfterSeconds, failOpen);
        }
    }

    public record Snapshot(
            boolean enabled,
            long limit,
            int windowSeconds,
            long localPassThreshold
    ) {}
}
