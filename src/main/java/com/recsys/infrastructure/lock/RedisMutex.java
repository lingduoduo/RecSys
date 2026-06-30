package com.recsys.infrastructure.lock;

import com.recsys.infrastructure.redis.RedisExecutor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Redis-based distributed mutex for cache breakdown (thundering herd) prevention.
 *
 * When a hot cache key expires simultaneously on all JVM instances, this mutex ensures
 * at most ONE instance across the cluster rebuilds the cache while the others either
 * wait briefly (then read the freshly populated cache) or return a degraded response.
 *
 * This complements the JVM-local singleflight in {@link OnlineFeatureStore}:
 * singleflight deduplicates within one JVM; this mutex coordinates across the cluster
 * via Redis {@code SET key value NX EX ttl}.
 *
 * Typical usage pattern:
 * <pre>{@code
 *   String token = mutex.tryAcquire("user:" + userId);
 *   if (token != null) {
 *     try {
 *       Value v = expensiveRecompute();
 *       redis.set(cacheKey, serialize(v), SetParams.setParams().ex(ttl));
 *       return v;
 *     } finally {
 *       mutex.release("user:" + userId, token);
 *     }
 *   } else {
 *     // Another instance is rebuilding; return stale/default value.
 *     return fallbackValue;
 *   }
 * }</pre>
 *
 * Or with the convenience wrapper:
 * <pre>{@code
 *   return mutex.withLock("user:" + userId, this::expensiveRecompute, () -> fallbackValue);
 * }</pre>
 */
public final class RedisMutex {

    // Atomically deletes the key only if its value equals the caller's token,
    // preventing a holder from releasing a lock it no longer owns (e.g., after TTL expiry).
    private static final String RELEASE_SCRIPT = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('del', KEYS[1])
            end
            return 0
            """;

    private final RedisExecutor exec;
    private final String keyPrefix;
    private final long lockTtlSeconds;

    public RedisMutex(RedisExecutor exec) {
        this(exec, "mutex:", 5L);
    }

    RedisMutex(RedisExecutor exec, String keyPrefix, long lockTtlSeconds) {
        this.exec = exec;
        this.keyPrefix = keyPrefix;
        this.lockTtlSeconds = Math.max(1L, lockTtlSeconds);
    }

    /**
     * Tries to acquire the distributed lock for {@code resource}.
     *
     * @return a unique fencing token on success (pass to {@link #release}),
     *         or {@code null} if another holder currently owns the lock
     */
    public String tryAcquire(String resource) {
        String lockKey = keyPrefix + resource;
        String token = UUID.randomUUID().toString();
        String result = exec.execute(c -> c.set(lockKey, token, SetArgs.Builder.nx().ex(lockTtlSeconds)));
        return "OK".equals(result) ? token : null;
    }

    /**
     * Releases the lock for {@code resource}, but only if {@code token} still matches
     * the stored value.  Returns {@code false} if the token did not match (lock expired
     * or already released).
     */
    public boolean release(String resource, String token) {
        String lockKey = keyPrefix + resource;
        Long result = exec.execute(c -> c.eval(RELEASE_SCRIPT, ScriptOutputType.INTEGER,
                new String[]{lockKey}, token));
        return result != null && result == 1L;
    }

    /**
     * Acquires the lock, runs {@code action}, releases the lock.
     * If the lock cannot be acquired, runs {@code fallback} instead (non-blocking degradation).
     */
    public <T> T withLock(String resource, Supplier<T> action, Supplier<T> fallback) {
        String token = tryAcquire(resource);
        if (token == null) return fallback.get();
        try {
            return action.get();
        } finally {
            release(resource, token);
        }
    }
}
