package com.recsys.ratelimit;

import com.recsys.config.EnvConfig;
import com.recsys.infrastructure.redis.RedisExecutor;
import com.recsys.resilience.CircuitBreaker;
import io.lettuce.core.ScriptOutputType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * Redis-backed <b>weighted sliding-window-counter</b> rate limiter for cross-instance
 * request protection (the online path's only cluster-wide limit).
 *
 * <p>Every enabled request consults Redis: the script keeps a counter per fixed window and
 * estimates the rolling rate as {@code prev * weight + cur}, where {@code weight} is the
 * fraction of the previous window still inside the trailing window. This bounds a rolling
 * window to ~1x {@code limit}, versus the ~2x a plain fixed window admits across a reset.
 * There is no per-instance local fast-path — that would let {@code N * fraction * limit}
 * requests through before the global limit was enforced.
 *
 * <p>Time is supplied by the caller ({@code nowMillis}) so the window math is consistent and
 * testable; window keys self-expire after {@code 2 * windowMs}.
 *
 * <p>Circuit breaker (cache avalanche / rate-limit degradation): after
 * {@code circuitFailureThreshold} consecutive Redis failures the circuit opens and requests
 * fail open without a Redis call for {@code circuitResetMs}; then one HALF_OPEN probe is
 * admitted (success closes it, failure reopens). This keeps a Redis outage from cascading
 * into unbounded latency on every check.
 */
public final class RedisRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    private static final String SCRIPT = """
            local limit    = tonumber(ARGV[1])
            local windowMs = tonumber(ARGV[2])
            local nowMs    = tonumber(ARGV[3])
            local windowId = math.floor(nowMs / windowMs)
            local elapsed  = nowMs - (windowId * windowMs)
            local weight   = (windowMs - elapsed) / windowMs
            local curKey   = KEYS[1] .. ':' .. windowId
            local prevKey  = KEYS[1] .. ':' .. (windowId - 1)
            local cur  = tonumber(redis.call('GET', curKey)  or '0')
            local prev = tonumber(redis.call('GET', prevKey) or '0')
            local estimated = prev * weight + cur
            if estimated + 1 > limit then
              local retryMs = windowMs - elapsed
              return {0, 0, math.max(1, math.ceil(retryMs / 1000))}
            end
            local newCur = redis.call('INCR', curKey)
            if newCur == 1 then
              redis.call('PEXPIRE', curKey, windowMs * 2)
            end
            local remaining = limit - (prev * weight + newCur)
            if remaining < 0 then remaining = 0 end
            return {1, math.floor(remaining), 0}
            """;

    private static final int  DEFAULT_CIRCUIT_FAILURE_THRESHOLD = 5;
    private static final long DEFAULT_CIRCUIT_RESET_MS          = 30_000L;

    private final RedisExecutor exec;
    private final String keyPrefix;
    private final long limit;
    private final int windowSeconds;
    private final long windowMs;
    private final boolean enabled;
    private final LongSupplier nowMillis;
    private final CircuitBreaker circuit;

    public enum CircuitState { CLOSED, OPEN, HALF_OPEN }

    public RedisRateLimiter(RedisExecutor exec) {
        this(
                exec,
                "rate:online:",
                EnvConfig.readLong("ONLINE_REDIS_RATE_LIMIT_QPS", 0L),
                EnvConfig.readInt("ONLINE_REDIS_RATE_LIMIT_WINDOW_SECONDS", 1)
        );
    }

    RedisRateLimiter(RedisExecutor exec, String keyPrefix, long limit, int windowSeconds) {
        this(exec, keyPrefix, limit, windowSeconds,
                DEFAULT_CIRCUIT_FAILURE_THRESHOLD, DEFAULT_CIRCUIT_RESET_MS, System::currentTimeMillis);
    }

    /** Test seam: inject the clock feeding the Lua {@code nowMs} argument. */
    RedisRateLimiter(RedisExecutor exec, String keyPrefix, long limit, int windowSeconds,
                     LongSupplier nowMillis) {
        this(exec, keyPrefix, limit, windowSeconds,
                DEFAULT_CIRCUIT_FAILURE_THRESHOLD, DEFAULT_CIRCUIT_RESET_MS, nowMillis);
    }

    RedisRateLimiter(RedisExecutor exec, String keyPrefix, long limit, int windowSeconds,
                     int circuitFailureThreshold, long circuitResetMs) {
        this(exec, keyPrefix, limit, windowSeconds,
                circuitFailureThreshold, circuitResetMs, System::currentTimeMillis);
    }

    RedisRateLimiter(RedisExecutor exec, String keyPrefix, long limit, int windowSeconds,
                     int circuitFailureThreshold, long circuitResetMs, LongSupplier nowMillis) {
        this.exec = exec;
        this.keyPrefix = keyPrefix;
        this.limit = Math.max(0L, limit);
        this.windowSeconds = Math.max(1, windowSeconds);
        this.windowMs = (long) this.windowSeconds * 1000L;
        this.enabled = exec != null && this.limit > 0L;
        this.circuit = new CircuitBreaker(
                Math.max(1, circuitFailureThreshold),
                Math.max(1L, circuitResetMs),
                nowMillis);
        this.nowMillis = nowMillis;
    }

    public static RedisRateLimiter disabled() {
        return new RedisRateLimiter(null, "rate:online:", 0L, 1);
    }

    public Decision tryAcquire(String bucket) {
        if (!enabled) {
            return Decision.allowed(limit, 0, false);
        }
        // CLOSED → proceed; OPEN → fail open; HALF_OPEN → only the probe winner proceeds.
        CircuitBreaker.Permit permit = circuit.tryAcquirePermit();
        if (permit == null) {
            return Decision.allowed(limit, 0, true);
        }

        String key = keyPrefix + normalizeBucket(bucket);
        try {
            List<Object> raw = exec.execute(c -> c.eval(
                    SCRIPT,
                    ScriptOutputType.MULTI,
                    new String[]{key},
                    Long.toString(limit), Long.toString(windowMs), Long.toString(nowMillis.getAsLong())
            ));
            circuit.recordSuccess(permit);
            return parseDecision(raw);
        } catch (Exception e) {
            circuit.recordFailure(permit);
            log.warn("Redis rate limiter failed open for bucket '{}' (failures={}): {}",
                    bucket, circuit.failureCount(), e.toString());
            return Decision.allowed(limit, 0, true);
        }
    }

    public CircuitState circuitState() {
        return switch (circuit.state()) {
            case CLOSED    -> CircuitState.CLOSED;
            case OPEN      -> CircuitState.OPEN;
            case HALF_OPEN -> CircuitState.HALF_OPEN;
        };
    }

    public Snapshot snapshot() {
        return new Snapshot(enabled, limit, windowSeconds, circuitState());
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
            CircuitState circuitState
    ) {}
}
