package com.recsys.online.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.util.Pool;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Distributed lock with watchdog lease renewal — Redisson 看门狗 pattern.
 *
 * Problem with fixed TTL: if the lock holder's task runs longer than the initial TTL,
 * the lock expires while the critical section is still active.  Another client then
 * acquires the lock, causing concurrent modification of the shared resource.
 *
 * Solution — the watchdog:
 *  1. Acquire the lock with an initial TTL (default: {@value #DEFAULT_LEASE_TTL_SECONDS} s).
 *  2. Start a daemon background thread that renews (re-EXPIRE) the TTL every TTL/3 seconds
 *     as long as this lock object is alive — matching Redisson's internalLockLeaseTime logic.
 *  3. Call {@link #release} / {@link #close} when the critical section finishes:
 *     the watchdog is stopped and the key is deleted atomically via a Lua fencing-token check.
 *  4. If the JVM crashes or is killed, all daemon threads stop; the key expires naturally
 *     after at most one lease TTL with no manual cleanup.
 *
 * Renewal uses a Lua script so the TTL is extended only while this holder's token still
 * matches — a pre-empted process cannot accidentally renew a lock held by someone else.
 * Renewal state is tracked with one atomic "held" flag: release, token-mismatch renewal,
 * and lease-deadline expiry all race through the same transition so the caller never sees
 * the lock as held after ownership is known to be lost.
 *
 * Usage:
 * <pre>{@code
 *   WatchdogLock lock = WatchdogLock.tryAcquire(pool, "order:42");
 *   if (lock == null) return; // another instance holds the lock
 *   try (lock) {
 *     processOrderLongRunning(); // watchdog keeps TTL alive throughout
 *   }
 * }</pre>
 */
public final class WatchdogLock implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WatchdogLock.class);

    static final long DEFAULT_LEASE_TTL_SECONDS = 30L;
    // Renew at TTL/3 so at least two renewals succeed within one lease period.
    private static final long RENEWAL_DIVISOR = 3L;

    // Extend TTL only if this holder's token still matches (prevents ghost renewals).
    private static final String RENEW_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2]))
            end
            return 0
            """;

    // Delete key only if this holder's token still matches (fencing-token safe release).
    private static final String RELEASE_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    private final Pool<Jedis> pool;
    private final String lockKey;
    private final String token;
    private final long leaseTtlSeconds;
    private final long leaseTtlMillis;
    private final ScheduledExecutorService watchdog;
    private final AtomicBoolean held = new AtomicBoolean(true);
    private final AtomicBoolean lostOwnership = new AtomicBoolean(false);
    private volatile long leaseDeadlineMillis;

    private WatchdogLock(Pool<Jedis> pool, String lockKey, String token,
                         long leaseTtlSeconds, long leaseDeadlineMillis,
                         ScheduledExecutorService watchdog) {
        this.pool = pool;
        this.lockKey = lockKey;
        this.token = token;
        this.leaseTtlSeconds = leaseTtlSeconds;
        this.leaseTtlMillis = TimeUnit.SECONDS.toMillis(leaseTtlSeconds);
        this.leaseDeadlineMillis = leaseDeadlineMillis;
        this.watchdog = watchdog;
    }

    /**
     * Tries to acquire the distributed lock for {@code resource} with the default TTL.
     *
     * @return a {@link WatchdogLock} with a running renewal watchdog on success,
     *         or {@code null} if another client currently holds the lock
     */
    public static WatchdogLock tryAcquire(Pool<Jedis> pool, String resource) {
        return tryAcquire(pool, "wdlock:", resource, DEFAULT_LEASE_TTL_SECONDS);
    }

    static WatchdogLock tryAcquire(Pool<Jedis> pool, String keyPrefix, String resource,
                                   long leaseTtlSeconds) {
        String lockKey = keyPrefix + resource;
        String token = UUID.randomUUID().toString();

        // Atomic SET NX EX — single command, no crash window.
        try (Jedis jedis = pool.getResource()) {
            String result = jedis.set(lockKey, token,
                    SetParams.setParams().nx().ex(leaseTtlSeconds));
            if (!"OK".equals(result)) return null;
        }

        // Lock acquired — start the watchdog daemon.
        long renewIntervalSeconds = Math.max(1L, leaseTtlSeconds / RENEWAL_DIVISOR);
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "watchdog-" + lockKey);
            t.setDaemon(true); // does not prevent JVM exit
            return t;
        });

        WatchdogLock lock = new WatchdogLock(pool, lockKey, token, leaseTtlSeconds,
                System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(leaseTtlSeconds), watchdog);
        watchdog.scheduleWithFixedDelay(
                lock::renewLease,
                renewIntervalSeconds, renewIntervalSeconds, TimeUnit.SECONDS);
        return lock;
    }

    // Called by the watchdog on every renewal interval.
    void renewLease() {
        if (!held.get()) {
            watchdog.shutdownNow();
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            Object result = jedis.eval(RENEW_SCRIPT,
                    List.of(lockKey), List.of(token, Long.toString(leaseTtlMillis)));
            if (result instanceof Number n && n.longValue() == 1L) {
                leaseDeadlineMillis = System.currentTimeMillis() + leaseTtlMillis;
                if (held.get()) {
                    log.debug("Watchdog: renewed TTL for {} (+{}ms)", lockKey, leaseTtlMillis);
                }
            } else {
                // Token no longer matches — key expired or was deleted externally.
                log.warn("Watchdog: renewal failed for {} (token mismatch or key gone)", lockKey);
                markOwnershipLost();
            }
        } catch (Exception e) {
            log.warn("Watchdog: renewal error for {}: {}", lockKey, e.toString());
            if (System.currentTimeMillis() >= leaseDeadlineMillis) {
                markOwnershipLost();
            }
        }
    }

    private void markOwnershipLost() {
        lostOwnership.set(true);
        if (held.compareAndSet(true, false)) {
            watchdog.shutdownNow();
        }
    }

    /**
     * Stops the watchdog and releases the lock.  Safe to call multiple times.
     *
     * @return {@code true} if this holder deleted the key;
     *         {@code false} if already released or the token no longer matches
     */
    public boolean release() {
        if (!held.compareAndSet(true, false)) return false;
        watchdog.shutdownNow();
        try (Jedis jedis = pool.getResource()) {
            Object result = jedis.eval(RELEASE_SCRIPT, List.of(lockKey), List.of(token));
            return result instanceof Number n && n.longValue() == 1L;
        } catch (Exception e) {
            log.warn("Watchdog: release error for {}: {}", lockKey, e.toString());
            return false;
        }
    }

    /** Calls {@link #release} — enables try-with-resources usage. */
    @Override
    public void close() {
        release();
    }

    /** {@code true} while the lock is held and the watchdog is running. */
    public boolean isHeld() { return held.get(); }
    /** {@code true} once renewal proves the lock expired, disappeared, or was re-acquired. */
    public boolean hasLostOwnership() { return lostOwnership.get(); }
    /** Fencing token used for this lock instance. */
    public String token() { return token; }
    /** Configured lease TTL in seconds. */
    public long leaseTtlSeconds() { return leaseTtlSeconds; }
}
