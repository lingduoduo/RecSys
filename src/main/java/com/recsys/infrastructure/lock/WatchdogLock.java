package com.recsys.infrastructure.lock;

import com.recsys.infrastructure.redis.RedisExecutor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Distributed lock with watchdog lease renewal — Redisson watchdog pattern.
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
 *   WatchdogLock lock = WatchdogLock.tryAcquire(exec, "order:42");
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

    // One shared scheduler renews every lock's lease — O(1) threads regardless of how many
    // locks are held concurrently (was one daemon thread per lock).
    private static final ScheduledExecutorService SHARED_WATCHDOG =
            Executors.newScheduledThreadPool(
                    Math.max(1, Integer.getInteger("WATCHDOG_THREADS", 2)),
                    r -> {
                        Thread t = new Thread(r, "watchdog-shared");
                        t.setDaemon(true); // does not prevent JVM exit
                        return t;
                    });

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

    private final RedisExecutor exec;
    private final String lockKey;
    private final String token;
    private final long leaseTtlSeconds;
    private final long leaseTtlMillis;
    private final ScheduledExecutorService watchdog;
    private volatile ScheduledFuture<?> renewalTask;
    private final AtomicBoolean held = new AtomicBoolean(true);
    private final AtomicBoolean lostOwnership = new AtomicBoolean(false);
    private volatile long leaseDeadlineMillis;

    private WatchdogLock(RedisExecutor exec, String lockKey, String token,
                         long leaseTtlSeconds, long leaseDeadlineMillis,
                         ScheduledExecutorService watchdog) {
        this.exec = exec;
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
    public static WatchdogLock tryAcquire(RedisExecutor exec, String resource) {
        return tryAcquire(exec, "wdlock:", resource, DEFAULT_LEASE_TTL_SECONDS);
    }

    static WatchdogLock tryAcquire(RedisExecutor exec, String keyPrefix, String resource,
                                   long leaseTtlSeconds) {
        return tryAcquire(exec, keyPrefix, resource, leaseTtlSeconds, SHARED_WATCHDOG);
    }

    static WatchdogLock tryAcquire(RedisExecutor exec, String keyPrefix, String resource,
                                   long leaseTtlSeconds, ScheduledExecutorService executor) {
        String lockKey = keyPrefix + resource;
        String token = UUID.randomUUID().toString();

        // Atomic SET NX EX — single command, no crash window.
        String result = exec.execute(c -> c.set(lockKey, token,
                SetArgs.Builder.nx().ex(leaseTtlSeconds)));
        if (!"OK".equals(result)) return null;

        // Lock acquired — schedule renewal on the shared scheduler; the per-lock task is
        // cancelled (not the executor shut down) when this lock is released or lost.
        long renewIntervalSeconds = Math.max(1L, leaseTtlSeconds / RENEWAL_DIVISOR);
        WatchdogLock lock = new WatchdogLock(exec, lockKey, token, leaseTtlSeconds,
                System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(leaseTtlSeconds), executor);
        lock.renewalTask = executor.scheduleWithFixedDelay(
                lock::renewLease,
                renewIntervalSeconds, renewIntervalSeconds, TimeUnit.SECONDS);
        return lock;
    }

    // Called by the watchdog on every renewal interval.
    void renewLease() {
        if (!held.get()) {
            cancelRenewal();
            return;
        }
        try {
            Long result = exec.execute(c -> c.eval(RENEW_SCRIPT, ScriptOutputType.INTEGER,
                    new String[]{lockKey}, token, String.valueOf(leaseTtlMillis)));
            if (result != null && result == 1L) {
                leaseDeadlineMillis = System.currentTimeMillis() + leaseTtlMillis;
                if (held.get()) {
                    log.debug("Watchdog: renewed TTL for {} (+{}ms)", lockKey, leaseTtlMillis);
                }
            } else {
                // Token no longer matches — key expired or was deleted externally.
                log.warn("Watchdog: renewal failed for {} (token mismatch or key gone)", lockKey);
                markOwnershipLost();
            }
        } catch (Throwable e) {
            // Throwable, not Exception: a JVM Error escaping here would cancel the fixed-delay
            // schedule silently, and the lease would then expire in Redis while `held` stayed
            // true — two holders, neither told (18_Fault_Tolerance §9.4).
            log.warn("Watchdog: renewal error for {}: {}", lockKey, e.toString());
            if (System.currentTimeMillis() >= leaseDeadlineMillis) {
                markOwnershipLost();
            }
        }
    }

    private void markOwnershipLost() {
        lostOwnership.set(true);
        if (held.compareAndSet(true, false)) {
            cancelRenewal();
        }
    }

    // Stop this lock's renewal task without touching the shared executor (other locks use it).
    private void cancelRenewal() {
        ScheduledFuture<?> task = renewalTask;
        if (task != null) task.cancel(false);
    }

    /**
     * Stops the watchdog and releases the lock.  Safe to call multiple times.
     *
     * @return {@code true} if this holder deleted the key;
     *         {@code false} if already released or the token no longer matches
     */
    public boolean release() {
        if (!held.compareAndSet(true, false)) return false;
        cancelRenewal();
        try {
            Long result = exec.execute(c -> c.eval(RELEASE_SCRIPT, ScriptOutputType.INTEGER,
                    new String[]{lockKey}, token));
            return result != null && result == 1L;
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

    /**
     * {@code true} while the lock is held <em>and</em> the local lease deadline has not passed.
     * Checked at call time, so a renewal thread that died or never ran cannot leave this holder
     * believing it owns a key Redis has already expired.
     */
    public boolean isHeld() {
        if (held.get() && System.currentTimeMillis() >= leaseDeadlineMillis) {
            log.warn("Watchdog: lease deadline for {} passed without renewal; treating lock as lost", lockKey);
            markOwnershipLost();
        }
        return held.get();
    }
    /** {@code true} once renewal proves the lock expired, disappeared, or was re-acquired. */
    public boolean hasLostOwnership() { return lostOwnership.get(); }
    /** Fencing token used for this lock instance. */
    public String token() { return token; }
    /** Configured lease TTL in seconds. */
    public long leaseTtlSeconds() { return leaseTtlSeconds; }
}
