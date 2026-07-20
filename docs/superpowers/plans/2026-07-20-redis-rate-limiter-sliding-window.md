# Redis Rate Limiter Sliding Window Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the online global rate limiter's ceiling actually hold — replace the fixed-window algorithm with a weighted sliding-window counter and remove the per-instance local fast-path so every request consults Redis.

**Architecture:** One Lua script (weighted two-window estimate) evaluated on every enabled, circuit-closed request. Time is passed in from the app clock (`nowMillis` seam) as `nowMs`, which also makes the boundary behavior deterministically testable against a real Redis. Fail-open + circuit breaker unchanged; `Snapshot` drops `localPassThreshold`.

**Tech Stack:** Java 17, Lettuce (Redis `eval`), JUnit 5 + AssertJ + Mockito, Testcontainers (`redis:7-alpine`) for the `@Tag("docker")` test, Maven.

## Global Constraints

- **Build/test with JDK 17:** `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`.
- **Only `RedisRateLimiter`** changes (+ its tests). No change to `OnlineServices`, `OnlinePredictionServer`, `OnlineOpsService`, or the other limiters.
- **Public surface preserved:** `RedisRateLimiter(RedisExecutor)`, `disabled()`, `tryAcquire(String) → Decision`, `isEnabled()`, `limit()`, `windowSeconds()`, `snapshot()`, `circuitState()`, the `Decision`/`Snapshot`/`CircuitState` types, and `normalizeBucket` semantics.
- **Removed:** the local fast-path (`localWindowBucket`, `localCount`, `localPassThreshold`, `localPassFraction`) and the `localPassThreshold()` getter + `Snapshot.localPassThreshold` component. Removing the record component auto-drops it from `/online/ops` (which just embeds the record) — no `OnlineOpsService` edit.
- **Env vars unchanged:** `ONLINE_REDIS_RATE_LIMIT_QPS` (default 0 = disabled), `ONLINE_REDIS_RATE_LIMIT_WINDOW_SECONDS` (default 1). Key prefix `rate:online:`.
- **Circuit breaker unchanged:** `CircuitBreaker`, default 5 failures / 30_000 ms reset; fail-open on Redis exception.
- **`@Tag("docker")` tests are opt-in** (excluded by default; run via `mvn test -DexcludedGroups=load -Dgroups=docker`). CI does not exist.

---

## File Structure

- Modify `src/main/java/com/recsys/ratelimit/RedisRateLimiter.java` — new Lua, drop fast-path, ctor set, `windowMs`, `Snapshot` field removal.
- Rewrite `src/test/java/com/recsys/ratelimit/RedisRateLimiterTest.java` — control-flow unit tests (mocked exec), no fast-path tests, updated ctors, clock-arg assertion.
- Create `src/test/java/com/recsys/ratelimit/RedisRateLimiterSlidingWindowIntegrationTest.java` — `@Tag("docker")` real-Redis boundary/expiry test.

---

## Task 1: Rewrite `RedisRateLimiter` (sliding window + drop fast-path) with unit tests

**Files:**
- Modify: `src/main/java/com/recsys/ratelimit/RedisRateLimiter.java`
- Rewrite: `src/test/java/com/recsys/ratelimit/RedisRateLimiterTest.java`

**Interfaces:**
- Consumes: `RedisExecutor`, `CircuitBreaker` (existing).
- Produces:
  - `public RedisRateLimiter(RedisExecutor exec)` — unchanged.
  - `static RedisRateLimiter disabled()` — unchanged.
  - Package-private test ctors: `(exec, keyPrefix, limit, windowSeconds)`, `(exec, keyPrefix, limit, windowSeconds, LongSupplier nowMillis)`, `(exec, keyPrefix, limit, windowSeconds, int circuitFailureThreshold, long circuitResetMs)`, `(exec, keyPrefix, limit, windowSeconds, int circuitFailureThreshold, long circuitResetMs, LongSupplier nowMillis)`.
  - `Decision tryAcquire(String bucket)`; records `Decision(boolean allowed, long remaining, int retryAfterSeconds, boolean failOpen)` and `Snapshot(boolean enabled, long limit, int windowSeconds, CircuitState circuitState)`; `enum CircuitState { CLOSED, OPEN, HALF_OPEN }`.

- [ ] **Step 1: Replace the unit test file with the new contract (RED)**

Overwrite `src/test/java/com/recsys/ratelimit/RedisRateLimiterTest.java` with:

```java
package com.recsys.ratelimit;

import com.recsys.infrastructure.redis.RedisExecutor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisRateLimiterTest {

    @SuppressWarnings("unchecked")
    private static RedisExecutor execFor(RedisCommands<String, String> cmd) {
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any())).thenAnswer(i -> i.getArgument(0, Function.class).apply(cmd));
        return exec;
    }

    @SuppressWarnings("unchecked")
    private static RedisCommands<String, String> mockCommands() {
        return mock(RedisCommands.class);
    }

    @Test
    void disabledLimiter_allowsRequests() {
        RedisRateLimiter limiter = RedisRateLimiter.disabled();
        RedisRateLimiter.Decision decision = limiter.tryAcquire("recommendation");
        assertThat(decision.allowed()).isTrue();
        assertThat(limiter.isEnabled()).isFalse();
    }

    @Test
    void tryAcquire_consultsRedisOnEveryRequest_noLocalFastPath() {
        RedisCommands<String, String> cmd = mockCommands();
        when(cmd.eval(any(String.class), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(List.of(1L, 99L, 0L));
        RedisExecutor exec = execFor(cmd);
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:test:", 100L, 1);

        // Even the very first request must hit Redis — there is no local free budget.
        limiter.tryAcquire("online");
        limiter.tryAcquire("online");
        limiter.tryAcquire("online");
        verify(exec, times(3)).execute(any());
    }

    @Test
    void tryAcquire_parsesAllowedRedisDecision() {
        RedisCommands<String, String> cmd = mockCommands();
        when(cmd.eval(any(String.class), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(List.of(1L, 99L, 0L));
        RedisRateLimiter limiter = new RedisRateLimiter(execFor(cmd), "rate:test:", 100L, 1);

        RedisRateLimiter.Decision decision = limiter.tryAcquire("recommendation");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(99L);
        assertThat(decision.retryAfterSeconds()).isEqualTo(0);
        assertThat(decision.failOpen()).isFalse();
    }

    @Test
    void tryAcquire_parsesRejectedRedisDecision() {
        RedisCommands<String, String> cmd = mockCommands();
        when(cmd.eval(any(String.class), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(List.of(0L, 0L, 1L));
        RedisRateLimiter limiter = new RedisRateLimiter(execFor(cmd), "rate:test:", 100L, 1);

        RedisRateLimiter.Decision decision = limiter.tryAcquire("features");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void tryAcquire_passesLimitWindowMsAndClockToScript() {
        RedisCommands<String, String> cmd = mockCommands();
        when(cmd.eval(any(String.class), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(List.of(1L, 1L, 0L));
        RedisExecutor exec = execFor(cmd);
        // window=2s → windowMs=2000; fixed clock=1_234_000
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:test:", 50L, 2, () -> 1_234_000L);

        limiter.tryAcquire("online");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<String[]> args = ArgumentCaptor.forClass(String[].class);
        verify(cmd).eval(any(String.class), eq(ScriptOutputType.MULTI), any(String[].class), args.capture());
        String[] argv = args.getValue();
        assertThat(argv[0]).isEqualTo("50");         // limit
        assertThat(argv[1]).isEqualTo("2000");       // windowMs
        assertThat(argv[2]).isEqualTo("1234000");    // nowMs
    }

    @Test
    void tryAcquire_failsOpenWhenRedisUnavailable() {
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any())).thenThrow(new IllegalStateException("redis down"));
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:test:", 100L, 1);

        RedisRateLimiter.Decision decision = limiter.tryAcquire("recommendation");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.failOpen()).isTrue();
    }

    // ── Circuit breaker (cache-avalanche / rate-limit degradation) ──────────

    @Test
    void circuitBreaker_startsInClosedState() {
        RedisRateLimiter limiter = new RedisRateLimiter(
                mock(RedisExecutor.class), "rate:", 100L, 1, 3, 10_000L);
        assertThat(limiter.circuitState()).isEqualTo(RedisRateLimiter.CircuitState.CLOSED);
    }

    @Test
    void circuitBreaker_opensAfterConsecutiveFailureThreshold() {
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any())).thenThrow(new RuntimeException("redis down"));
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:", 100L, 1, 3, 10_000L);

        for (int i = 0; i < 3; i++) limiter.tryAcquire("x");

        assertThat(limiter.circuitState()).isEqualTo(RedisRateLimiter.CircuitState.OPEN);
    }

    @Test
    void circuitBreaker_failsOpenWithoutRedisCallWhenOpen() {
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any())).thenThrow(new RuntimeException("redis down"));
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:", 100L, 1, 1, 10_000L);

        limiter.tryAcquire("x"); // opens circuit (1 failure)

        RedisRateLimiter.Decision d = limiter.tryAcquire("x");
        assertThat(d.allowed()).isTrue();
        assertThat(d.failOpen()).isTrue();
        verify(exec, times(1)).execute(any()); // only the first call hit Redis
    }

    @Test
    void circuitBreaker_halfOpenAfterResetWindow() throws Exception {
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any())).thenThrow(new RuntimeException("redis down"));
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:", 100L, 1, 1, 20L);

        limiter.tryAcquire("x");
        Thread.sleep(30);

        assertThat(limiter.circuitState()).isEqualTo(RedisRateLimiter.CircuitState.HALF_OPEN);
    }

    @Test
    void circuitBreaker_closesOnSuccessfulProbeInHalfOpen() throws Exception {
        RedisCommands<String, String> cmd = mockCommands();
        when(cmd.eval(any(String.class), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(List.of(1L, 99L, 0L));
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any()))
                .thenThrow(new RuntimeException("redis down"))
                .thenAnswer(i -> i.getArgument(0, Function.class).apply(cmd));
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:", 100L, 1, 1, 20L);

        limiter.tryAcquire("x"); // opens
        Thread.sleep(30);        // → HALF_OPEN

        RedisRateLimiter.Decision probe = limiter.tryAcquire("x"); // probe succeeds → CLOSED
        assertThat(probe.allowed()).isTrue();
        assertThat(probe.failOpen()).isFalse();
        assertThat(limiter.circuitState()).isEqualTo(RedisRateLimiter.CircuitState.CLOSED);
    }

    @Test
    void snapshot_hasNoLocalPassThreshold_andCarriesCircuitState() {
        RedisRateLimiter limiter = new RedisRateLimiter(
                mock(RedisExecutor.class), "rate:", 100L, 1, 5, 30_000L);

        RedisRateLimiter.Snapshot snap = limiter.snapshot();

        assertThat(snap.enabled()).isTrue();
        assertThat(snap.limit()).isEqualTo(100L);
        assertThat(snap.windowSeconds()).isEqualTo(1);
        assertThat(snap.circuitState()).isEqualTo(RedisRateLimiter.CircuitState.CLOSED);
        // Snapshot must NOT expose a local-fast-path threshold anymore.
        assertThat(java.util.Arrays.stream(RedisRateLimiter.Snapshot.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("localPassThreshold");
    }
}
```

- [ ] **Step 2: Run the unit tests to confirm they fail (RED)**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=RedisRateLimiterTest`
Expected: FAIL / compile error — the new ctors (`(exec, prefix, limit, window)`, `(…, LongSupplier)`, `(…, int, long)`) and the removed `localPassThreshold` don't exist on the current class yet.

- [ ] **Step 3: Rewrite `RedisRateLimiter.java`**

Replace the entire file with:

```java
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
        this.circuit = new CircuitBreaker(Math.max(1, circuitFailureThreshold), Math.max(1L, circuitResetMs));
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
        if (!circuit.tryAcquire()) {
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
            circuit.recordSuccess();
            return parseDecision(raw);
        } catch (Exception e) {
            circuit.recordFailure();
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
```

- [ ] **Step 4: Run the unit tests to confirm they pass (GREEN)**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=RedisRateLimiterTest`
Expected: PASS (all tests).

- [ ] **Step 5: Run the full suite to confirm no regression**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test`
Expected: BUILD SUCCESS. In particular `OnlineOpsService`-related tests still pass — removing the `Snapshot.localPassThreshold` component only drops a field from the embedded ops JSON, and no test asserts that field.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/ratelimit/RedisRateLimiter.java \
        src/test/java/com/recsys/ratelimit/RedisRateLimiterTest.java
git commit -m "feat: sliding-window Redis rate limiter, drop per-instance fast-path"
```

---

## Task 2: `@Tag("docker")` integration test for sliding-window boundary behavior

**Files:**
- Create: `src/test/java/com/recsys/ratelimit/RedisRateLimiterSlidingWindowIntegrationTest.java`

**Interfaces:**
- Consumes: `RedisRateLimiter(exec, keyPrefix, limit, windowSeconds, LongSupplier nowMillis)` (Task 1), `LettuceRedisExecutor`, Testcontainers `redis:7-alpine`.

**Why a docker test:** the sliding-window math lives in the Lua script, which the mocked-`exec` unit tests cannot execute. This test runs the real script against a real Redis and drives the window boundary deterministically via the injected clock (no wall-clock flakiness).

- [ ] **Step 1: Write the integration test**

Create `src/test/java/com/recsys/ratelimit/RedisRateLimiterSlidingWindowIntegrationTest.java`:

```java
package com.recsys.ratelimit;

import com.recsys.infrastructure.redis.LettuceRedisExecutor;
import com.recsys.infrastructure.redis.RedisExecutor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("docker")
@Testcontainers
class RedisRateLimiterSlidingWindowIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static RedisExecutor exec;

    @BeforeAll
    static void startRedis() {
        RedisClient client = RedisClient.create(
                RedisURI.create(REDIS.getHost(), REDIS.getMappedPort(6379)));
        GenericObjectPoolConfig<StatefulRedisConnection<String, String>> cfg =
                new GenericObjectPoolConfig<>();
        exec = new LettuceRedisExecutor(client, cfg, true);
    }

    @AfterAll
    static void stopRedis() {
        if (exec != null) exec.close();
    }

    @AfterEach
    void flush() {
        exec.execute(c -> { c.flushall(); return null; });
    }

    private static int admitted(RedisRateLimiter limiter, int attempts) {
        int allowed = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.tryAcquire("online").allowed()) allowed++;
        }
        return allowed;
    }

    @Test
    void steadyState_admitsUpToLimitThenRejects() {
        AtomicLong clock = new AtomicLong(5_000L); // window 5 (windowMs=1000), elapsed 0
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:test:", 100L, 1, clock::get);

        assertThat(admitted(limiter, 100)).isEqualTo(100);        // fills the window exactly
        assertThat(limiter.tryAcquire("online").allowed()).isFalse(); // 101st rejected
        assertThat(limiter.tryAcquire("online").retryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void boundaryBurst_staysNearLimit_notDouble() {
        AtomicLong clock = new AtomicLong(5_000L); // window 5, elapsed 0
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:test:", 100L, 1, clock::get);

        // Fill window 5 to the limit.
        assertThat(admitted(limiter, 100)).isEqualTo(100);

        // Step to the very start of window 6 (elapsed 0 → prev-window weight ~1.0).
        clock.set(6_000L);
        // A fixed-window limiter would reset here and admit another 100 (→ 200 in a rolling 1s).
        // The sliding window estimates prev(100)*1.0 + cur(0) = 100, so it admits ~0.
        int admittedAtBoundary = admitted(limiter, 100);
        assertThat(admittedAtBoundary).isLessThanOrEqualTo(1);

        // Halfway into window 6 (elapsed 500 → weight 0.5), ~50 of the prior window has aged out.
        clock.set(6_500L);
        int admittedMidWindow = admitted(limiter, 100);
        assertThat(admittedMidWindow).isBetween(45, 55);
    }

    @Test
    void windowKey_selfExpiresAfterTwoWindows() {
        AtomicLong clock = new AtomicLong(5_000L);
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:test:", 100L, 1, clock::get);

        limiter.tryAcquire("online"); // writes rate:test:global:5, PEXPIRE 2000ms

        Long pttl = exec.execute((RedisCommands<String, String> c) -> c.pttl("rate:test:global:5"));
        assertThat(pttl).isGreaterThan(0L).isLessThanOrEqualTo(2000L);
    }
}
```

> Before running, confirm `LettuceRedisExecutor`'s constructor signature matches
> `new LettuceRedisExecutor(client, cfg, true)` (copied from
> `RedisShardingTestBase`); if it differs, match the real constructor. Confirm
> `RedisCommands.pttl(String)` is the correct Lettuce accessor (it is in Lettuce
> 6); adjust the `exec.execute(...)` lambda's return handling if the executor's
> generic signature requires it.

- [ ] **Step 2: Compile the test (docker tests are excluded from the default run)**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test-compile`
Expected: BUILD SUCCESS (the file compiles; it is not executed by the default suite).

- [ ] **Step 3: Run the docker test if a Docker daemon is available**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -DexcludedGroups=load -Dgroups=docker -Dtest=RedisRateLimiterSlidingWindowIntegrationTest`
Expected: PASS — all three tests green. If no Docker daemon is available in this environment, report that the test compiles and is correctly `@Tag("docker")`-gated but could not be executed here (it must be run locally with Docker per repo convention); do NOT mark the task blocked for this reason.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/recsys/ratelimit/RedisRateLimiterSlidingWindowIntegrationTest.java
git commit -m "test: docker integration test proving sliding-window boundary bound"
```

---

## Task 3: Docs — note the tightened global limiter

**Files:**
- Modify: `docs/runbooks/overload-protection.md`
- Modify: `README.md` (only if it documents the fixed-window ~2× behavior or `localPassThreshold`)

- [ ] **Step 1: Update the overload-protection runbook**

Read `docs/runbooks/overload-protection.md`. Find the note describing the online Redis QPS limiter as fixed-window with ~2× boundary admission and the local fast-path. Replace it with a note that the limiter is now a weighted sliding-window counter consulting Redis on every request (bounding a rolling window to ~1× `limit`, no per-instance fast-path), with fail-open + circuit breaker unchanged. Keep the surrounding table/format intact.

- [ ] **Step 2: Check README for stale references**

Run: `rg -n "localPassThreshold|2× the limit|fixed-window|local fast-path|localPassFraction" README.md`
For each hit that describes the online `RedisRateLimiter`, update it to the sliding-window / always-consult-Redis behavior. If there are no such hits, make no README change.

- [ ] **Step 3: Verify docs render and nothing else changed**

Run: `git diff --stat`
Expected: only `docs/runbooks/overload-protection.md` (and possibly `README.md`) changed; tables well-formed.

- [ ] **Step 4: Commit**

```bash
git add docs/runbooks/overload-protection.md README.md
git commit -m "docs: online rate limiter is now sliding-window, no local fast-path"
```

---

## Self-Review Notes (author)

- **Spec coverage:** sliding-window Lua (T1 Step 3) ✓; fast-path removal (T1) ✓; `Snapshot` drops `localPassThreshold` + auto-propagates to ops (T1, verified no other consumer) ✓; env vars / `disabled()` / `tryAcquire`/`Decision` preserved (T1 ctors + public methods) ✓; circuit + fail-open unchanged (T1) ✓; unit control-flow tests (T1 Step 1) ✓; `@Tag("docker")` boundary proof (T2) ✓; docs (T3) ✓. Acceptance criteria 1–6 mapped.
- **Determinism:** the docker boundary test injects the clock, so window math is exact; key-expiry is asserted via `PTTL` bound (≤2000ms), not a real sleep.
- **Type consistency:** `Decision(allowed, remaining, retryAfterSeconds, failOpen)` and `Snapshot(enabled, limit, windowSeconds, circuitState)` used identically in class and tests; the four package-private ctors match the test call sites (`(exec,prefix,limit,window)`, `(…,LongSupplier)`, `(…,int,long)`, `(…,int,long,LongSupplier)`).
- **Pre-write checks (flagged inline, not code changes):** `LettuceRedisExecutor(client, cfg, true)` ctor shape and `RedisCommands.pttl(String)` accessor — confirm against the real sources before running T2.
