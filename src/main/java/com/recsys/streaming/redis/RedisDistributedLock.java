package com.recsys.streaming.redis;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.util.Pool;

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
 * │   Runs SET key token NX PX ttl inside a Lua script.  This preserves a single     │
 * │   atomic Redis operation while leaving room to add fencing counters or metadata. │
 * │                                                                                  │
 * │ Approach 3 — SET NX PX       (acquire)      ← recommended                       │
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

    // ── Lua: SET NX PX wrapped atomically (approach 2) ───────────────────────────
    // Uses one Redis write operation inside Lua instead of SETNX + EXPIRE. This keeps
    // the Lua extension point without recreating the old two-step lock acquisition.
    static final String ACQUIRE_LUA = """
            local result = redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', tonumber(ARGV[2]))
            if result then
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

    private final Pool<Jedis> pool;
    private final String keyPrefix;
    private final long defaultTtlSeconds;
    private final long defaultTtlMillis;

    public RedisDistributedLock(Pool<Jedis> pool) {
        this(pool, "dlock:", 30L);
    }

    RedisDistributedLock(Pool<Jedis> pool, String keyPrefix, long defaultTtlSeconds) {
        this.pool = pool;
        this.keyPrefix = keyPrefix;
        this.defaultTtlSeconds = Math.max(1L, defaultTtlSeconds);
        this.defaultTtlMillis = this.defaultTtlSeconds * 1_000L;
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

    // ── Approach 2: Lua script (atomic SET NX PX) ────────────────────────────────

    /**
     * ATOMIC via Lua: SET key token NX PX ttlMillis is executed as one Redis command
     * inside the script. Functionally equivalent to approach 3, with an extension point
     * for future fencing counters or acquisition metadata.
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
                    List.of(token, Long.toString(defaultTtlMillis)));
            return result instanceof Number n && n.longValue() == 1L ? token : null;
        }
    }

    // ── Approach 3: SET NX PX (single atomic command — recommended) ──────────────

    /**
     * ATOMIC via {@code SET key value NX PX ttlMillis}: one round-trip, no Lua overhead.
     * This is the canonical production approach for distributed locking in Redis.
     *
     * @return a unique fencing token if acquired; {@code null} if the lock is held
     */
    public String acquire(String resource) {
        String key = keyPrefix + resource;
        String token = UUID.randomUUID().toString();
        try (Jedis jedis = pool.getResource()) {
            String result = jedis.set(key, token, SetParams.setParams().nx().px(defaultTtlMillis));
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
        if (token == null || token.isBlank()) return false;
        String key = keyPrefix + resource;
        try (Jedis jedis = pool.getResource()) {
            Object result = jedis.eval(RELEASE_LUA, List.of(key), List.of(token));
            return result instanceof Number n && n.longValue() == 1L;
        }
    }

    /** Returns the configured default TTL in seconds. */
    public long defaultTtlSeconds() { return defaultTtlSeconds; }
    /** Returns the configured default TTL in milliseconds. */
    public long defaultTtlMillis() { return defaultTtlMillis; }
}
