# Redis Pool Validation + Shared Watchdog Executor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Steps use `- [ ]`.

**Goal:** Validate idle/returned pooled Redis connections, and make distributed-lock watchdog threads O(1) instead of O(locks). Behavior-preserving.

**Architecture:** (A) add `testWhileIdle` + idle-eviction sweep to both `RedisConnectionFactory` pool builders; (B) `WatchdogLock` schedules renewal on a shared static `ScheduledExecutorService` and cancels its own `ScheduledFuture` on release instead of owning/shutting-down a per-lock executor. **Part C** (GlobalPopularityStore fallback) is **dropped** — empty results are already handled downstream by `Channels.Popularity`'s `DataManager` fallback (documented in that class).

**Tech Stack:** Java 17, commons-pool2 2.12, Jedis, JUnit 5 + Mockito + AssertJ.

## Global Constraints
- No behavior change to lock semantics or happy-path pool behavior.
- `mvn clean test` green.
- Branch `optimize/redis-pool-lock-reliability` (spec already on branch).

---

### Task 1: Pool idle-validation (A)

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/redis/RedisConnectionFactory.java` (`poolConfig` ~209, `defaultPoolConfig` ~220)
- Test: `src/test/java/com/recsys/infrastructure/redis/RedisConnectionFactoryTest.java`

- [ ] **Step 1: Write the failing test**
```java
    @Test
    void defaultPoolConfig_enablesIdleConnectionValidation() {
        GenericObjectPoolConfig<Jedis> cfg = RedisConnectionFactory.defaultPoolConfig(java.util.Map.of());
        assertThat(cfg.getTestWhileIdle()).isTrue();
        assertThat(cfg.getNumTestsPerEvictionRun()).isEqualTo(-1);
    }
```
(Add `import static org.assertj.core.api.Assertions.assertThat;` and `import org.apache.commons.pool2.impl.GenericObjectPoolConfig;` / `redis.clients.jedis.Jedis;` if not already imported — the class already builds these in other tests.)

- [ ] **Step 2: Run — expect failure**
Run: `mvn -q test -Dtest=RedisConnectionFactoryTest`
Expected: FAIL (`getTestWhileIdle()` is false by default).

- [ ] **Step 3: Implement validation in both builders**
Add constants near the other pool defaults:
```java
    private static final long DEFAULT_EVICTION_RUN_MS = 30_000L;
    private static final long DEFAULT_MIN_EVICTABLE_IDLE_MS = 60_000L;
```
Add a shared helper and call it from both builders:
```java
    private static void applyIdleValidation(GenericObjectPoolConfig<Jedis> cfg) {
        cfg.setTestWhileIdle(true);
        cfg.setNumTestsPerEvictionRun(-1); // test every idle connection per sweep
        cfg.setDurationBetweenEvictionRuns(java.time.Duration.ofMillis(DEFAULT_EVICTION_RUN_MS));
        cfg.setMinEvictableIdleDuration(java.time.Duration.ofMillis(DEFAULT_MIN_EVICTABLE_IDLE_MS));
    }
```
Call `applyIdleValidation(cfg);` before `return cfg;` in both `poolConfig(...)` and `defaultPoolConfig(...)`.
(If `setDurationBetweenEvictionRuns`/`setMinEvictableIdleDuration` don't resolve on 2.12, fall back to the deprecated `setTimeBetweenEvictionRuns(Duration)`/`setMinEvictableIdleTime(Duration)` — confirm at compile.)

- [ ] **Step 4: Run — expect pass**
Run: `mvn -q test -Dtest=RedisConnectionFactoryTest`  → PASS.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/recsys/infrastructure/redis/RedisConnectionFactory.java src/test/java/com/recsys/infrastructure/redis/RedisConnectionFactoryTest.java
git commit -m "fix: validate idle Redis pool connections (testWhileIdle + eviction sweep)"
```

---

### Task 2: Shared watchdog executor (B)

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/lock/WatchdogLock.java`
- Test: `src/test/java/com/recsys/infrastructure/lock/WatchdogLockTest.java`

**Interfaces:** new overload `static WatchdogLock tryAcquire(Pool<Jedis>, String keyPrefix, String resource, long leaseTtlSeconds, ScheduledExecutorService executor)`; the existing 4-arg overload delegates to it with the shared singleton. Public API (`tryAcquire(pool,resource)`, `release`, `close`, `isHeld`, etc.) unchanged.

- [ ] **Step 1: Write the sharing-contract test**
Add imports: `import java.util.concurrent.ScheduledExecutorService;`, `import java.util.concurrent.ScheduledFuture;`.
```java
    @Test
    void release_cancelsOwnTaskButNeverShutsDownSharedExecutor() {
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn("OK");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        ScheduledExecutorService shared = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> taskA = mock(ScheduledFuture.class);
        ScheduledFuture<?> taskB = mock(ScheduledFuture.class);
        when(shared.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any()))
                .thenReturn((ScheduledFuture) taskA, (ScheduledFuture) taskB);

        WatchdogLock a = WatchdogLock.tryAcquire(pool, "wdlock:", "a", 30L, shared);
        WatchdogLock b = WatchdogLock.tryAcquire(pool, "wdlock:", "b", 30L, shared);
        a.release();
        b.release();

        verify(shared, times(2)).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
        verify(shared, never()).shutdown();
        verify(shared, never()).shutdownNow();
        verify(taskA).cancel(false);
        verify(taskB).cancel(false);
    }
```

- [ ] **Step 2: Run — expect compile failure (no 5-arg overload)**
Run: `mvn -q test-compile`  → FAIL (method not found).

- [ ] **Step 3: Refactor WatchdogLock to a shared executor + cancellable task**
Add a shared static executor near the constants:
```java
    private static final ScheduledExecutorService SHARED_WATCHDOG =
            Executors.newScheduledThreadPool(
                    Math.max(1, Integer.getInteger("WATCHDOG_THREADS", 2)),
                    r -> { Thread t = new Thread(r, "watchdog-shared"); t.setDaemon(true); return t; });
```
Replace the `watchdog` field semantics: keep an executor reference (shared/injected) for scheduling, add a cancellable task handle:
```java
    private final ScheduledExecutorService watchdog;
    private volatile java.util.concurrent.ScheduledFuture<?> renewalTask;
```
Constructor: keep the `ScheduledExecutorService watchdog` parameter (now the shared/injected executor). Update `tryAcquire`:
```java
    public static WatchdogLock tryAcquire(Pool<Jedis> pool, String resource) {
        return tryAcquire(pool, "wdlock:", resource, DEFAULT_LEASE_TTL_SECONDS);
    }

    static WatchdogLock tryAcquire(Pool<Jedis> pool, String keyPrefix, String resource, long leaseTtlSeconds) {
        return tryAcquire(pool, keyPrefix, resource, leaseTtlSeconds, SHARED_WATCHDOG);
    }

    static WatchdogLock tryAcquire(Pool<Jedis> pool, String keyPrefix, String resource,
                                   long leaseTtlSeconds, ScheduledExecutorService executor) {
        String lockKey = keyPrefix + resource;
        String token = UUID.randomUUID().toString();
        try (Jedis jedis = pool.getResource()) {
            String result = jedis.set(lockKey, token, SetParams.setParams().nx().ex(leaseTtlSeconds));
            if (!"OK".equals(result)) return null;
        }
        long renewIntervalSeconds = Math.max(1L, leaseTtlSeconds / RENEWAL_DIVISOR);
        WatchdogLock lock = new WatchdogLock(pool, lockKey, token, leaseTtlSeconds,
                System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(leaseTtlSeconds), executor);
        lock.renewalTask = executor.scheduleWithFixedDelay(
                lock::renewLease, renewIntervalSeconds, renewIntervalSeconds, TimeUnit.SECONDS);
        return lock;
    }
```
Add a cancel helper and use it everywhere the code currently calls `watchdog.shutdownNow()`:
```java
    private void cancelRenewal() {
        java.util.concurrent.ScheduledFuture<?> t = renewalTask;
        if (t != null) t.cancel(false);
    }
```
- In `renewLease()`: replace `watchdog.shutdownNow();` (the `!held` early-return branch) with `cancelRenewal();`.
- In `markOwnershipLost()`: replace `watchdog.shutdownNow();` with `cancelRenewal();`.
- In `release()`: replace `watchdog.shutdownNow();` with `cancelRenewal();`.

- [ ] **Step 4: Run the lock test class**
Run: `mvn -q test -Dtest=WatchdogLockTest`
Expected: PASS (new sharing test + all existing renewal/release/lifecycle tests).

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/recsys/infrastructure/lock/WatchdogLock.java src/test/java/com/recsys/infrastructure/lock/WatchdogLockTest.java
git commit -m "perf: WatchdogLock renews on a shared executor (O(1) threads, not per-lock)"
```

---

### Task 3: Full-suite verification
- [ ] Run `mvn clean test` → `BUILD SUCCESS`, 0 failures.

## Self-Review
- Spec A (pool validation) → Task 1. ✓
- Spec B (shared watchdog) → Task 2. ✓
- Spec C (popularity fallback) → **dropped** (already mitigated downstream); noted in header + PR. ✓
- No placeholders; commons-pool2 method fallback noted. ✓
- Types: `ScheduledExecutorService`/`ScheduledFuture` consistent across Task 2. ✓
