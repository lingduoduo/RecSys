package com.recsys.infrastructure.lock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.util.Pool;
import redis.clients.jedis.params.SetParams;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies the WatchdogLock lifecycle:
 *  - acquire with SET NX EX
 *  - watchdog renews the TTL at TTL/3 intervals while held
 *  - release stops the watchdog and deletes the key via fencing-token Lua script
 *  - watchdog stops renewing after release
 *  - try-with-resources (AutoCloseable) releases automatically
 */
class WatchdogLockTest {

    private Jedis jedis;
    private Pool<Jedis> pool;

    @BeforeEach
    void setUp() {
        jedis = mock(Jedis.class);
        pool  = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
    }

    // ── Acquisition ──────────────────────────────────────────────────────────────

    @Test
    void tryAcquire_returnsLockWhenKeyIsFree() {
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn("OK");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L); // for release

        WatchdogLock lock = WatchdogLock.tryAcquire(pool, "wdlock:", "order:1", 30L);
        try {
            assertThat(lock).isNotNull();
            assertThat(lock.isHeld()).isTrue();
            assertThat(lock.token()).isNotBlank();
            assertThat(lock.leaseTtlSeconds()).isEqualTo(30L);
        } finally {
            if (lock != null) lock.release();
        }
    }

    @Test
    void tryAcquire_returnsNullWhenKeyIsHeld() {
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn(null);

        WatchdogLock lock = WatchdogLock.tryAcquire(pool, "wdlock:", "order:1", 30L);

        assertThat(lock).isNull();
    }

    @Test
    void tryAcquire_usesSingleAtomicSetNxExCommand() {
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn("OK");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        WatchdogLock lock = WatchdogLock.tryAcquire(pool, "wdlock:", "res", 30L);
        lock.release();

        // Exactly one SET NX EX (acquisition), then one eval (release) — no SETNX/EXPIRE.
        verify(jedis, times(1)).set(eq("wdlock:res"), anyString(), any(SetParams.class));
        verify(jedis, never()).setnx(anyString(), anyString());
        verify(jedis, never()).expire(anyString(), anyLong());
    }

    // ── Watchdog renewal ─────────────────────────────────────────────────────────

    @Test
    void watchdog_renewsLeaseAtTtlThirdInterval() throws InterruptedException {
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn("OK");

        // Latch counts down on each RENEW eval call; release eval also counts down (ok).
        CountDownLatch renewedTwice = new CountDownLatch(2);
        when(jedis.eval(anyString(), anyList(), anyList())).thenAnswer(inv -> {
            renewedTwice.countDown();
            return 1L;
        });

        // 3 s TTL → watchdog fires every 1 s (Math.max(1, 3/3) = 1)
        WatchdogLock lock = WatchdogLock.tryAcquire(pool, "wdlock:", "res", 3L);
        assertThat(lock).isNotNull();

        boolean renewedInTime = renewedTwice.await(5, TimeUnit.SECONDS);
        lock.release();

        assertThat(renewedInTime).as("watchdog should renew at least twice within 5 s").isTrue();
    }

    @Test
    void watchdog_usesRenewScriptWithMatchingToken() throws InterruptedException {
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn("OK");
        CountDownLatch firstRenew = new CountDownLatch(1);
        AtomicInteger renewCallCount = new AtomicInteger();

        when(jedis.eval(anyString(), anyList(), anyList())).thenAnswer(inv -> {
            renewCallCount.incrementAndGet();
            firstRenew.countDown();
            return 1L;
        });

        WatchdogLock lock = WatchdogLock.tryAcquire(pool, "wdlock:", "res", 3L);
        assertThat(lock).isNotNull();
        boolean renewed = firstRenew.await(4, TimeUnit.SECONDS);
        lock.release();

        assertThat(renewed).isTrue();
        // First eval call is the renewal; verify it carries the correct token and TTL.
        verify(jedis, atLeastOnce()).eval(
                contains("PEXPIRE"),         // renewal uses millisecond TTL precision
                eq(java.util.List.of("wdlock:res")),
                argThat(args -> args.contains(lock.token()) && args.contains("3000")));
    }

    @Test
    void watchdog_marksLockLostWhenRenewalTokenNoLongerMatches() {
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn("OK");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(0L);

        WatchdogLock lock = WatchdogLock.tryAcquire(pool, "wdlock:", "res", 30L);
        assertThat(lock).isNotNull();

        lock.renewLease();

        assertThat(lock.isHeld()).isFalse();
        assertThat(lock.hasLostOwnership()).isTrue();
        assertThat(lock.release()).isFalse();
    }

    @Test
    void watchdog_marksLockLostWhenRenewalErrorsPastLocalLeaseDeadline() throws InterruptedException {
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn("OK");
        when(jedis.eval(anyString(), anyList(), anyList())).thenThrow(new RuntimeException("redis down"));

        WatchdogLock lock = WatchdogLock.tryAcquire(pool, "wdlock:", "res", 1L);
        assertThat(lock).isNotNull();

        Thread.sleep(1_050L);
        lock.renewLease();

        assertThat(lock.isHeld()).isFalse();
        assertThat(lock.hasLostOwnership()).isTrue();
    }

    // ── Release ──────────────────────────────────────────────────────────────────

    @Test
    void release_stopsWatchdogAndDeletesKeyViaLua() {
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn("OK");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        WatchdogLock lock = WatchdogLock.tryAcquire(pool, "wdlock:", "res", 30L);
        assertThat(lock).isNotNull();

        boolean released = lock.release();

        assertThat(released).isTrue();
        assertThat(lock.isHeld()).isFalse();
        // Release must call eval (Lua fencing-token delete).
        verify(jedis, atLeastOnce()).eval(
                contains("DEL"),
                eq(java.util.List.of("wdlock:res")),
                argThat(args -> args.contains(lock.token())));
    }

    @Test
    void release_returnsFalseOnSecondCall_idempotentRelease() {
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn("OK");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        WatchdogLock lock = WatchdogLock.tryAcquire(pool, "wdlock:", "res", 30L);
        lock.release();                    // first call — succeeds
        boolean second = lock.release();   // second call — already released

        assertThat(second).isFalse();
    }

    @Test
    void release_isAtomicAcrossConcurrentCallers() throws Exception {
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn("OK");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);
        WatchdogLock lock = WatchdogLock.tryAcquire(pool, "wdlock:", "res", 30L);
        assertThat(lock).isNotNull();

        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successfulReleases = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                ready.countDown();
                start.await();
                if (lock.release()) {
                    successfulReleases.incrementAndGet();
                }
                return null;
            });
        }

        assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();

        assertThat(successfulReleases.get()).isEqualTo(1);
        verify(jedis, times(1)).eval(contains("DEL"), anyList(), anyList());
    }

    @Test
    void close_releasesLockViaAutoCloseable() {
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn("OK");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        WatchdogLock lock = WatchdogLock.tryAcquire(pool, "wdlock:", "res", 30L);
        assertThat(lock).isNotNull();

        lock.close(); // try-with-resources path

        assertThat(lock.isHeld()).isFalse();
    }

    @Test
    void watchdog_stopsRenewingAfterRelease() throws InterruptedException {
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn("OK");
        AtomicInteger evalCount = new AtomicInteger();
        when(jedis.eval(anyString(), anyList(), anyList())).thenAnswer(inv -> {
            evalCount.incrementAndGet();
            return 1L;
        });

        WatchdogLock lock = WatchdogLock.tryAcquire(pool, "wdlock:", "res", 3L);
        assertThat(lock).isNotNull();

        lock.release(); // stops the watchdog, calls eval once (release script)
        int countAfterRelease = evalCount.get();

        Thread.sleep(1_500); // wait past one potential renewal interval

        // No additional eval calls after watchdog was stopped.
        assertThat(evalCount.get()).isEqualTo(countAfterRelease);
    }
}
