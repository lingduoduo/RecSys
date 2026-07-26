package com.recsys.ratelimit;

import com.recsys.config.EnvConfig;
import com.recsys.infrastructure.redis.RedisExecutor;
import com.recsys.resilience.CircuitBreaker;
import io.lettuce.core.ScriptOutputType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
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
 * are admitted from a bounded local emergency token bucket without a Redis call for
 * {@code circuitResetMs}; then one HALF_OPEN probe is admitted (success closes it, failure
 * reopens). Emergency limiting can be explicitly disabled to restore unlimited fail-open
 * behavior during rollback. This keeps a Redis outage from cascading into unbounded latency
 * on every check.
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
    private final boolean emergencyEnabled;
    private final TokenBucket emergencyBucket;

    public enum CircuitState { CLOSED, OPEN, HALF_OPEN }

    public RedisRateLimiter(RedisExecutor exec) {
        this(
                exec,
                "rate:online:",
                EnvConfig.readLong("ONLINE_REDIS_RATE_LIMIT_QPS", 0L),
                EnvConfig.readInt("ONLINE_REDIS_RATE_LIMIT_WINDOW_SECONDS", 1),
                DEFAULT_CIRCUIT_FAILURE_THRESHOLD,
                DEFAULT_CIRCUIT_RESET_MS,
                emergencyRateFromEnvironment(),
                emergencyBurstFromEnvironment(),
                System::nanoTime,
                System::currentTimeMillis,
                emergencyLimitEnabledFromEnvironment()
        );
    }

    RedisRateLimiter(RedisExecutor exec, String keyPrefix, long limit, int windowSeconds) {
        this(exec, keyPrefix, limit, windowSeconds,
                DEFAULT_CIRCUIT_FAILURE_THRESHOLD, DEFAULT_CIRCUIT_RESET_MS,
                defaultEmergencyRate(limit), defaultEmergencyBurst(limit),
                System::nanoTime, System::currentTimeMillis, true);
    }

    /** Test seam: inject the clock feeding the Lua {@code nowMs} argument. */
    RedisRateLimiter(RedisExecutor exec, String keyPrefix, long limit, int windowSeconds,
                     LongSupplier nowMillis) {
        this(exec, keyPrefix, limit, windowSeconds,
                DEFAULT_CIRCUIT_FAILURE_THRESHOLD, DEFAULT_CIRCUIT_RESET_MS,
                defaultEmergencyRate(limit), defaultEmergencyBurst(limit),
                System::nanoTime, nowMillis, true);
    }

    RedisRateLimiter(RedisExecutor exec, String keyPrefix, long limit, int windowSeconds,
                     int circuitFailureThreshold, long circuitResetMs) {
        this(exec, keyPrefix, limit, windowSeconds,
                circuitFailureThreshold, circuitResetMs,
                defaultEmergencyRate(limit), defaultEmergencyBurst(limit),
                System::nanoTime, System::currentTimeMillis, true);
    }

    RedisRateLimiter(RedisExecutor exec, String keyPrefix, long limit, int windowSeconds,
                     int circuitFailureThreshold, long circuitResetMs, LongSupplier nowMillis) {
        this(exec, keyPrefix, limit, windowSeconds, circuitFailureThreshold, circuitResetMs,
                defaultEmergencyRate(limit), defaultEmergencyBurst(limit),
                System::nanoTime, nowMillis, true);
    }

    /** Test seam: separate monotonic ticker for the emergency bucket and circuit/script clock. */
    public RedisRateLimiter(RedisExecutor exec, String keyPrefix, long limit, int windowSeconds,
                            int circuitFailureThreshold, long circuitResetMs,
                            double emergencyRatePerSecond, int emergencyBurst,
                            LongSupplier tickerNanos, LongSupplier circuitClockMillis) {
        this(exec, keyPrefix, limit, windowSeconds, circuitFailureThreshold, circuitResetMs,
                emergencyRatePerSecond, emergencyBurst, tickerNanos, circuitClockMillis, true);
    }

    private RedisRateLimiter(RedisExecutor exec, String keyPrefix, long limit, int windowSeconds,
                             int circuitFailureThreshold, long circuitResetMs,
                             double emergencyRatePerSecond, int emergencyBurst,
                             LongSupplier tickerNanos, LongSupplier circuitClockMillis,
                             boolean emergencyConfiguredEnabled) {
        if (!Double.isFinite(emergencyRatePerSecond) || emergencyRatePerSecond < 0) {
            throw new IllegalArgumentException("emergencyRatePerSecond must be finite and non-negative");
        }
        if (emergencyBurst < 0) {
            throw new IllegalArgumentException("emergencyBurst must be non-negative");
        }
        this.exec = exec;
        this.keyPrefix = keyPrefix;
        this.limit = Math.max(0L, limit);
        this.windowSeconds = Math.max(1, windowSeconds);
        this.windowMs = (long) this.windowSeconds * 1000L;
        this.enabled = exec != null && this.limit > 0L;
        this.circuit = new CircuitBreaker(
                Math.max(1, circuitFailureThreshold),
                Math.max(1L, circuitResetMs),
                circuitClockMillis);
        this.nowMillis = circuitClockMillis;
        this.emergencyEnabled = enabled && emergencyConfiguredEnabled
                && emergencyRatePerSecond > 0 && emergencyBurst > 0;
        this.emergencyBucket = emergencyEnabled
                ? new TokenBucket(emergencyRatePerSecond, emergencyBurst, tickerNanos)
                : null;
    }

    public static RedisRateLimiter disabled() {
        return new RedisRateLimiter(null, "rate:online:", 0L, 1);
    }

    public Decision tryAcquire(String bucket) {
        if (!enabled) {
            return Decision.allowed(limit, 0, false, Source.DISABLED);
        }
        // CLOSED → proceed; OPEN → fail open; HALF_OPEN → only the probe winner proceeds.
        CircuitBreaker.Permit permit = circuit.tryAcquirePermit();
        if (permit == null) {
            return emergencyDecision();
        }

        String key = keyPrefix + normalizeBucket(bucket);
        try {
            List<Object> raw = exec.execute(c -> c.eval(
                    SCRIPT,
                    ScriptOutputType.MULTI,
                    new String[]{key},
                    Long.toString(limit), Long.toString(windowMs), Long.toString(nowMillis.getAsLong())
            ));
            Decision decision = parseRedisDecision(raw);
            if (decision == null) {
                circuit.recordFailure(permit);
                log.warn("Redis rate limiter returned malformed result for bucket '{}'", bucket);
                return emergencyDecision();
            }
            circuit.recordSuccess(permit);
            return decision;
        } catch (Exception e) {
            circuit.recordFailure(permit);
            log.warn("Redis rate limiter failed open for bucket '{}' (failures={}): {}",
                    bucket, circuit.failureCount(), e.toString());
            return emergencyDecision();
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

    private Decision emergencyDecision() {
        if (!emergencyEnabled) return Decision.allowed(limit, 0, true, Source.EMERGENCY);
        TokenBucket.Decision local = emergencyBucket.tryAcquire();
        int retry = local.allowed() ? 0
                : Math.max(1, (int) Math.ceil(local.retryAfter().toMillis() / 1000.0));
        return new Decision(local.allowed(), local.remaining(), retry, true, Source.EMERGENCY);
    }

    private Decision parseRedisDecision(Object raw) {
        if (raw instanceof List<?> values && values.size() >= 3) {
            Long allowedValue = parseLong(values.get(0));
            Long remaining = parseLong(values.get(1));
            Long retry = parseLong(values.get(2));
            if (allowedValue != null && (allowedValue == 0L || allowedValue == 1L)
                    && remaining != null && remaining >= 0
                    && retry != null && retry >= 0 && retry <= Integer.MAX_VALUE) {
                return new Decision(allowedValue == 1L, remaining, retry.intValue(), false, Source.REDIS);
            }
        }
        return null;
    }

    private static Long parseLong(Object value) {
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
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
            boolean failOpen,
            Source source
    ) {
        static Decision allowed(long remaining, int retryAfterSeconds, boolean failOpen, Source source) {
            return new Decision(true, remaining, retryAfterSeconds, failOpen, source);
        }
    }

    public enum Source { DISABLED, REDIS, EMERGENCY }

    private static double defaultEmergencyRate(long limit) {
        return Math.max(1L, Math.max(0L, limit) / 4L);
    }

    private static int defaultEmergencyBurst(long limit) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, (long) defaultEmergencyRate(limit)));
    }

    private static double emergencyRateFromEnvironment() {
        long redisLimit = EnvConfig.readLong("ONLINE_REDIS_RATE_LIMIT_QPS", 0L);
        return readStrictDouble("ONLINE_REDIS_EMERGENCY_RATE_PER_SECOND", defaultEmergencyRate(redisLimit));
    }

    private static int emergencyBurstFromEnvironment() {
        long redisLimit = EnvConfig.readLong("ONLINE_REDIS_RATE_LIMIT_QPS", 0L);
        return readStrictNonNegativeInt("ONLINE_REDIS_EMERGENCY_BURST", defaultEmergencyBurst(redisLimit));
    }

    private static boolean emergencyLimitEnabledFromEnvironment() {
        String raw = System.getenv("ONLINE_REDIS_EMERGENCY_LIMIT_ENABLED");
        if (raw == null || raw.isBlank()) return true;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(
                    "ONLINE_REDIS_EMERGENCY_LIMIT_ENABLED must be true or false");
        };
    }

    private static double readStrictDouble(String name, double defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            double value = Double.parseDouble(raw.trim());
            if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException(
                    name + " must be finite and non-negative");
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number", e);
        }
    }

    private static int readStrictNonNegativeInt(String name, int defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 0) throw new IllegalArgumentException(name + " must be non-negative");
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer", e);
        }
    }

    public record Snapshot(
            boolean enabled,
            long limit,
            int windowSeconds,
            CircuitState circuitState
    ) {}
}
