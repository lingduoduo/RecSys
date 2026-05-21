package com.recsys.streaming;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.util.List;
import java.util.UUID;

/**
 * Demonstrates three approaches to distributed locking in Redis, in ascending order of
 * correctness.  All three share the same Lua-script safe-release pattern.
 *
 * ┌──────────────────────────────────────────────────────────────────────────────────┐
 * │ Approach 1 — SETNX + EXPIRE  (acquireNaive)                                      │
 * │   Two separate Redis commands.  If the process crashes between SETNX and EXPIRE  │
 * │   the key has no TTL and the lock is stuck until manual intervention.            │
 * │                                                                                  │
 * │ Approach 2 — Lua script      (acquireWithLua)                                    │
 * │   Wraps SETNX + EXPIRE inside a Lua script; Redis executes the script as a       │
 * │   single atomic unit, closing the crash window.                                  │
 * │                                                                                  │
 * │ Approach 3 — SET NX EX       (acquire)      ← recommended                       │
 * │   A single Redis command (available since 2.6.12) that is both atomic and more   │
 * │   efficient than a round-trip Lua eval.                                          │
 * └──────────────────────────────────────────────────────────────────────────────────┘
 *
 * All acquire methods return a unique fencing token on success or {@code null} when the
 * lock is already held.  Pass the token to {@link #release} to release safely: the key
 * is deleted only if the token still matches, preventing a slow holder from releasing a
 * lock that has already been re-acquired by another client after TTL expiry.
 *
 * For long-running tasks that may outlast a fixed TTL, use {@link WatchdogLock} instead.
 */
public final class RedisDistributedLock {

    // ── Lua: SETNX + EXPIRE wrapped atomically (approach 2) ───────────────────────
    // Redis executes the entire script as one atomic operation, so there is no window
    // between the existence check and the TTL assignment.
    static final String ACQUIRE_LUA = """
            local result = redis.call('SETNX', KEYS[1], ARGV[1])
            if result == 1 then
              redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
              return 1
            end
            return 0
            """;

    // ── Lua: safe release — only DELETE if the stored value equals the caller's token ─
    // Without this guard a slow holder (whose TTL has expired) would delete a lock
    // already re-acquired by another client.
    static final String RELEASE_LUA = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    private final JedisPool pool;
    private final String keyPrefix;
    private final long defaultTtlSeconds;

    public RedisDistributedLock(JedisPool pool) {
        this(pool, "dlock:", 30L);
    }

    RedisDistributedLock(JedisPool pool, String keyPrefix, long defaultTtlSeconds) {
        this.pool = pool;
        this.keyPrefix = keyPrefix;
        this.defaultTtlSeconds = Math.max(1L, defaultTtlSeconds);
    }

    // ── Approach 1: SETNX + EXPIRE (non-atomic) ───────────────────────────────────

    /**
     * NON-ATOMIC — for educational purposes only; do not use in production.
     *
     * Issues {@code SETNX} and {@code EXPIRE} as two separate Redis commands.
     * If the process crashes, loses the connection, or is killed between the two
     * commands, the key has no TTL and the lock remains held until manual cleanup.
     *
     * @return a unique fencing token if acquired; {@code null} if the lock is held
     */
    public String acquireNaive(String resource) {
        String key = keyPrefix + resource;
        String token = UUID.randomUUID().toString();
        try (Jedis jedis = pool.getResource()) {
            long acquired = jedis.setnx(key, token);   // step 1 — NOT atomic with step 2
            if (acquired == 1L) {
                jedis.expire(key, defaultTtlSeconds);   // step 2 — crash here → stuck lock ⚠
                return token;
            }
            return null;
        }
    }

    // ── Approach 2: Lua script (atomic SETNX + EXPIRE) ────────────────────────────

    /**
     * ATOMIC via Lua: SETNX and EXPIRE are executed as a single Redis operation.
     * Functionally equivalent to approach 3 but makes the atomicity mechanism explicit.
     *
     * @return a unique fencing token if acquired; {@code null} if the lock is held
     */
    public String acquireWithLua(String resource) {
        String key = keyPrefix + resource;
        String token = UUID.randomUUID().toString();
        try (Jedis jedis = pool.getResource()) {
            Object result = jedis.eval(
                    ACQUIRE_LUA,
                    List.of(key),
                    List.of(token, Long.toString(defaultTtlSeconds)));
            return result instanceof Number n && n.longValue() == 1L ? token : null;
        }
    }

    // ── Approach 3: SET NX EX (single atomic command — recommended) ───────────────

    /**
     * ATOMIC via {@code SET key value NX EX ttl}: one round-trip, no Lua overhead.
     * This is the canonical production approach for distributed locking in Redis.
     *
     * @return a unique fencing token if acquired; {@code null} if the lock is held
     */
    public String acquire(String resource) {
        String key = keyPrefix + resource;
        String token = UUID.randomUUID().toString();
        try (Jedis jedis = pool.getResource()) {
            String result = jedis.set(key, token, SetParams.setParams().nx().ex(defaultTtlSeconds));
            return "OK".equals(result) ? token : null;
        }
    }

    // ── Safe release (共用) ────────────────────────────────────────────────────────

    /**
     * Releases the lock using a Lua fencing-token check.
     * The key is deleted only if the stored value still equals {@code token}.
     *
     * @return {@code true} if the lock was deleted by this caller;
     *         {@code false} if the token did not match (lock expired or re-acquired)
     */
    public boolean release(String resource, String token) {
        String key = keyPrefix + resource;
        try (Jedis jedis = pool.getResource()) {
            Object result = jedis.eval(RELEASE_LUA, List.of(key), List.of(token));
            return result instanceof Number n && n.longValue() == 1L;
        }
    }

    /** Returns the configured default TTL in seconds. */
    public long defaultTtlSeconds() { return defaultTtlSeconds; }
}
