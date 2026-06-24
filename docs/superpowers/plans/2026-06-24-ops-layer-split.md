# Operational-Layer Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dissolve the two catch-all packages `com.recsys.observability` and `com.recsys.reliability` into seven focused, concern-named top-level layers, then collapse the duplicated rate-limiter / circuit-breaker / env-reader code into single implementations — with zero behavior change.

**Architecture:** Two mechanical "move + rewrite imports" tasks (one per old package), each gated by the full existing test suite, followed by four small behavior-preserving consolidations gated by their own unit tests. The existing test suite is the safety net for the moves; new/existing unit tests gate every code change.

**Tech Stack:** Java 17+, Maven (Surefire), JUnit 5, AssertJ, Mockito, Micrometer, Armeria, Spring Boot, Jedis. macOS `sed` (BSD flavor → `sed -i ''`).

## Global Constraints

- **No behavior change.** Identical env-var names, Micrometer meter names, gauge descriptions, HTTP status codes, and JSON field names — byte-for-byte.
- **`mvn test` must be green** at the end of every task. Never commit a non-compiling tree.
- Keep each test class in the **same package** as its class-under-test (several tests use package-private constructors).
- Work on the current branch `optimize/vectordb-cleanup` (or a fresh branch off it); **never merge to `main` directly — open a PR.**
- Do **not** touch the excluded `online/flink` or `training/rulebased` trees (they don't reference these packages).
- macOS sed: use `sed -i '' -e '...'`. Run all commands from repo root `/Users/linghuang/Git/Recsys-Backend-Service`.

### Canonical move map (symbol → new top-level package)

| Class | Old pkg | New pkg |
|---|---|---|
| InferenceMetricsService, OnlineServingMetricsService | observability | `metrics` |
| GcEventTracker, JvmMemoryMonitor | observability | `jvm` |
| TraceIdAspect | observability | `tracing` |
| TokenBucket, GatewayRateLimiter, LlmTokenRateLimiter, ModelRateLimiter, RedisRateLimiter | reliability | `ratelimit` |
| LoadShedder, OnlineLoadShedder, OnlineAdmissionControl, GracefulShutdownSupport | reliability | `loadshed` |
| RouteCircuitBreaker, WorkerBulkhead, FaultInjector | reliability | `resilience` |
| OnlineHealthService, OnlineOpsService, OnlineCapacityService | reliability | `health` |

> Use this table to resolve any compiler-reported "cannot find symbol" after a move: add `import com.recsys.<new-pkg>.<Symbol>;`.

---

### Task 1: Move `observability/` → `metrics/`, `jvm/`, `tracing/`

**Files:**
- Move (main): `src/main/java/com/recsys/observability/{InferenceMetricsService,OnlineServingMetricsService}.java` → `metrics/`; `{GcEventTracker,JvmMemoryMonitor}.java` → `jvm/`; `TraceIdAspect.java` → `tracing/`
- Move (test): `src/test/java/com/recsys/observability/{InferenceMetricsServiceTest,OnlineServingMetricsServiceTest}.java` → `metrics/`; `{GcEventTrackerTest,JvmMemoryMonitorTest}.java` → `jvm/`
- Modify: `src/main/java/com/recsys/api/rest/ModelApplication.java:14-15` (`scanBasePackages`)
- Modify (import rewrite, repo-wide): every `.java` referencing the five moved FQNs

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: classes now at `com.recsys.metrics.{InferenceMetricsService,OnlineServingMetricsService}`, `com.recsys.jvm.{GcEventTracker,JvmMemoryMonitor}`, `com.recsys.tracing.TraceIdAspect`. Later tasks (and the reliability move) import `OnlineServingMetricsService` from `com.recsys.metrics`.

- [ ] **Step 1: Create the new package directories**

```bash
mkdir -p src/main/java/com/recsys/metrics src/main/java/com/recsys/jvm src/main/java/com/recsys/tracing \
         src/test/java/com/recsys/metrics src/test/java/com/recsys/jvm src/test/java/com/recsys/tracing
```

- [ ] **Step 2: `git mv` the five main files + four test files**

```bash
git mv src/main/java/com/recsys/observability/InferenceMetricsService.java       src/main/java/com/recsys/metrics/
git mv src/main/java/com/recsys/observability/OnlineServingMetricsService.java    src/main/java/com/recsys/metrics/
git mv src/main/java/com/recsys/observability/GcEventTracker.java                 src/main/java/com/recsys/jvm/
git mv src/main/java/com/recsys/observability/JvmMemoryMonitor.java               src/main/java/com/recsys/jvm/
git mv src/main/java/com/recsys/observability/TraceIdAspect.java                  src/main/java/com/recsys/tracing/
git mv src/test/java/com/recsys/observability/InferenceMetricsServiceTest.java    src/test/java/com/recsys/metrics/
git mv src/test/java/com/recsys/observability/OnlineServingMetricsServiceTest.java src/test/java/com/recsys/metrics/
git mv src/test/java/com/recsys/observability/GcEventTrackerTest.java             src/test/java/com/recsys/jvm/
git mv src/test/java/com/recsys/observability/JvmMemoryMonitorTest.java           src/test/java/com/recsys/jvm/
```

- [ ] **Step 3: Rewrite the `package` declaration in each moved file**

```bash
sed -i '' 's|^package com\.recsys\.observability;|package com.recsys.metrics;|' \
  src/main/java/com/recsys/metrics/*.java src/test/java/com/recsys/metrics/*.java
sed -i '' 's|^package com\.recsys\.observability;|package com.recsys.jvm;|' \
  src/main/java/com/recsys/jvm/*.java src/test/java/com/recsys/jvm/*.java
sed -i '' 's|^package com\.recsys\.observability;|package com.recsys.tracing;|' \
  src/main/java/com/recsys/tracing/*.java
```

- [ ] **Step 4: Rewrite all fully-qualified references repo-wide (imports + any FQN usage)**

```bash
find src -name '*.java' -exec sed -i '' \
  -e 's|com\.recsys\.observability\.InferenceMetricsService|com.recsys.metrics.InferenceMetricsService|g' \
  -e 's|com\.recsys\.observability\.OnlineServingMetricsService|com.recsys.metrics.OnlineServingMetricsService|g' \
  -e 's|com\.recsys\.observability\.GcEventTracker|com.recsys.jvm.GcEventTracker|g' \
  -e 's|com\.recsys\.observability\.JvmMemoryMonitor|com.recsys.jvm.JvmMemoryMonitor|g' \
  -e 's|com\.recsys\.observability\.TraceIdAspect|com.recsys.tracing.TraceIdAspect|g' \
  {} +
```

- [ ] **Step 5: Update `scanBasePackages` in `ModelApplication.java`**

Replace the `"com.recsys.observability"` entry with the three new packages. The annotation (lines 14-15) must read exactly:

```java
@SpringBootApplication(scanBasePackages = {"com.recsys.api", "com.recsys.config", "com.recsys.exception",
        "com.recsys.metrics", "com.recsys.jvm", "com.recsys.tracing", "com.recsys.reliability", "com.recsys.application"})
```

- [ ] **Step 6: Verify the old package is empty and remove it**

```bash
ls src/main/java/com/recsys/observability src/test/java/com/recsys/observability   # expect: empty
rmdir src/main/java/com/recsys/observability src/test/java/com/recsys/observability
```

- [ ] **Step 7: Compile**

Run: `mvn -q package -DskipTests`
Expected: BUILD SUCCESS. (If "cannot find symbol", resolve via the move-map table in Global Constraints.)

- [ ] **Step 8: Run the full suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "refactor: split observability/ into metrics/, jvm/, tracing/ layers

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Move `reliability/` → `ratelimit/`, `loadshed/`, `resilience/`, `health/`

**Files:**
- Move (main, 15) + (test, 12) per the move map below.
- Modify: `src/main/java/com/recsys/api/rest/ModelApplication.java` (`scanBasePackages`)
- Modify: add 3 new cross-package imports to the `health/` main files; add the compiler-reported cross-package imports to moved tests.

**Interfaces:**
- Consumes: `com.recsys.metrics.OnlineServingMetricsService` (from Task 1).
- Produces: `com.recsys.ratelimit.{TokenBucket,GatewayRateLimiter,LlmTokenRateLimiter,ModelRateLimiter,RedisRateLimiter}`, `com.recsys.loadshed.{LoadShedder,OnlineLoadShedder,OnlineAdmissionControl,GracefulShutdownSupport}`, `com.recsys.resilience.{RouteCircuitBreaker,WorkerBulkhead,FaultInjector}`, `com.recsys.health.{OnlineHealthService,OnlineOpsService,OnlineCapacityService}`. Tasks 3–5 modify classes in `ratelimit/` and `resilience/`.

- [ ] **Step 1: Create the new package directories**

```bash
mkdir -p src/main/java/com/recsys/ratelimit src/main/java/com/recsys/loadshed \
         src/main/java/com/recsys/resilience src/main/java/com/recsys/health \
         src/test/java/com/recsys/ratelimit src/test/java/com/recsys/loadshed \
         src/test/java/com/recsys/resilience src/test/java/com/recsys/health
```

- [ ] **Step 2: `git mv` all 15 main files**

```bash
# ratelimit
git mv src/main/java/com/recsys/reliability/TokenBucket.java          src/main/java/com/recsys/ratelimit/
git mv src/main/java/com/recsys/reliability/GatewayRateLimiter.java   src/main/java/com/recsys/ratelimit/
git mv src/main/java/com/recsys/reliability/LlmTokenRateLimiter.java  src/main/java/com/recsys/ratelimit/
git mv src/main/java/com/recsys/reliability/ModelRateLimiter.java     src/main/java/com/recsys/ratelimit/
git mv src/main/java/com/recsys/reliability/RedisRateLimiter.java     src/main/java/com/recsys/ratelimit/
# loadshed
git mv src/main/java/com/recsys/reliability/LoadShedder.java             src/main/java/com/recsys/loadshed/
git mv src/main/java/com/recsys/reliability/OnlineLoadShedder.java       src/main/java/com/recsys/loadshed/
git mv src/main/java/com/recsys/reliability/OnlineAdmissionControl.java  src/main/java/com/recsys/loadshed/
git mv src/main/java/com/recsys/reliability/GracefulShutdownSupport.java src/main/java/com/recsys/loadshed/
# resilience
git mv src/main/java/com/recsys/reliability/RouteCircuitBreaker.java  src/main/java/com/recsys/resilience/
git mv src/main/java/com/recsys/reliability/WorkerBulkhead.java       src/main/java/com/recsys/resilience/
git mv src/main/java/com/recsys/reliability/FaultInjector.java        src/main/java/com/recsys/resilience/
# health
git mv src/main/java/com/recsys/reliability/OnlineHealthService.java   src/main/java/com/recsys/health/
git mv src/main/java/com/recsys/reliability/OnlineOpsService.java      src/main/java/com/recsys/health/
git mv src/main/java/com/recsys/reliability/OnlineCapacityService.java src/main/java/com/recsys/health/
```

- [ ] **Step 3: `git mv` all 12 test files (each next to its subject)**

```bash
# ratelimit tests
git mv src/test/java/com/recsys/reliability/GatewayRateLimiterTest.java  src/test/java/com/recsys/ratelimit/
git mv src/test/java/com/recsys/reliability/LlmTokenRateLimiterTest.java src/test/java/com/recsys/ratelimit/
git mv src/test/java/com/recsys/reliability/ModelRateLimiterTest.java    src/test/java/com/recsys/ratelimit/
git mv src/test/java/com/recsys/reliability/RedisRateLimiterTest.java    src/test/java/com/recsys/ratelimit/
# loadshed tests
git mv src/test/java/com/recsys/reliability/LoadShedderTest.java            src/test/java/com/recsys/loadshed/
git mv src/test/java/com/recsys/reliability/OnlineLoadShedderTest.java      src/test/java/com/recsys/loadshed/
git mv src/test/java/com/recsys/reliability/OnlineAdmissionControlTest.java src/test/java/com/recsys/loadshed/
# resilience tests
git mv src/test/java/com/recsys/reliability/RouteCircuitBreakerTest.java src/test/java/com/recsys/resilience/
git mv src/test/java/com/recsys/reliability/WorkerBulkheadTest.java      src/test/java/com/recsys/resilience/
git mv src/test/java/com/recsys/reliability/FaultInjectorTest.java       src/test/java/com/recsys/resilience/
# health tests
git mv src/test/java/com/recsys/reliability/OnlineHealthServiceTest.java   src/test/java/com/recsys/health/
git mv src/test/java/com/recsys/reliability/OnlineCapacityServiceTest.java src/test/java/com/recsys/health/
```

- [ ] **Step 4: Rewrite the `package` declaration in each moved file**

```bash
sed -i '' 's|^package com\.recsys\.reliability;|package com.recsys.ratelimit;|'  src/main/java/com/recsys/ratelimit/*.java  src/test/java/com/recsys/ratelimit/*.java
sed -i '' 's|^package com\.recsys\.reliability;|package com.recsys.loadshed;|'   src/main/java/com/recsys/loadshed/*.java   src/test/java/com/recsys/loadshed/*.java
sed -i '' 's|^package com\.recsys\.reliability;|package com.recsys.resilience;|' src/main/java/com/recsys/resilience/*.java src/test/java/com/recsys/resilience/*.java
sed -i '' 's|^package com\.recsys\.reliability;|package com.recsys.health;|'     src/main/java/com/recsys/health/*.java     src/test/java/com/recsys/health/*.java
```

- [ ] **Step 5: Rewrite all fully-qualified references repo-wide (15 mappings)**

```bash
find src -name '*.java' -exec sed -i '' \
  -e 's|com\.recsys\.reliability\.TokenBucket|com.recsys.ratelimit.TokenBucket|g' \
  -e 's|com\.recsys\.reliability\.GatewayRateLimiter|com.recsys.ratelimit.GatewayRateLimiter|g' \
  -e 's|com\.recsys\.reliability\.LlmTokenRateLimiter|com.recsys.ratelimit.LlmTokenRateLimiter|g' \
  -e 's|com\.recsys\.reliability\.ModelRateLimiter|com.recsys.ratelimit.ModelRateLimiter|g' \
  -e 's|com\.recsys\.reliability\.RedisRateLimiter|com.recsys.ratelimit.RedisRateLimiter|g' \
  -e 's|com\.recsys\.reliability\.LoadShedder|com.recsys.loadshed.LoadShedder|g' \
  -e 's|com\.recsys\.reliability\.OnlineLoadShedder|com.recsys.loadshed.OnlineLoadShedder|g' \
  -e 's|com\.recsys\.reliability\.OnlineAdmissionControl|com.recsys.loadshed.OnlineAdmissionControl|g' \
  -e 's|com\.recsys\.reliability\.GracefulShutdownSupport|com.recsys.loadshed.GracefulShutdownSupport|g' \
  -e 's|com\.recsys\.reliability\.RouteCircuitBreaker|com.recsys.resilience.RouteCircuitBreaker|g' \
  -e 's|com\.recsys\.reliability\.WorkerBulkhead|com.recsys.resilience.WorkerBulkhead|g' \
  -e 's|com\.recsys\.reliability\.FaultInjector|com.recsys.resilience.FaultInjector|g' \
  -e 's|com\.recsys\.reliability\.OnlineHealthService|com.recsys.health.OnlineHealthService|g' \
  -e 's|com\.recsys\.reliability\.OnlineOpsService|com.recsys.health.OnlineOpsService|g' \
  -e 's|com\.recsys\.reliability\.OnlineCapacityService|com.recsys.health.OnlineCapacityService|g' \
  {} +
```

> Note: `OnlineLoadShedder` mapping is listed **before** `LoadShedder` would matter only if one were a prefix of the other in a way sed could mis-handle; here each pattern is anchored by the full `com.recsys.reliability.` prefix and a distinct class name, so order is safe. The `LoadShedder` pattern does **not** match `OnlineLoadShedder` because the latter's FQN segment is `...reliability.OnlineLoadShedder`, which the `...reliability.LoadShedder` pattern cannot match (different char after the dot).

- [ ] **Step 6: Add the three known new cross-package imports to `health/` main files**

The `health/` classes referenced `OnlineLoadShedder` and `RedisRateLimiter` as same-package neighbors before the split; now they're cross-package. Add these imports (place them with the other `com.recsys` imports, alphabetically is fine):

In `src/main/java/com/recsys/health/OnlineCapacityService.java`:
```java
import com.recsys.loadshed.OnlineLoadShedder;
```

In `src/main/java/com/recsys/health/OnlineHealthService.java`:
```java
import com.recsys.loadshed.OnlineLoadShedder;
```

In `src/main/java/com/recsys/health/OnlineOpsService.java`:
```java
import com.recsys.loadshed.OnlineLoadShedder;
import com.recsys.ratelimit.RedisRateLimiter;
```

- [ ] **Step 7: Update `scanBasePackages` in `ModelApplication.java`**

Replace the `"com.recsys.reliability"` entry with the four new packages. The annotation must now read exactly:

```java
@SpringBootApplication(scanBasePackages = {"com.recsys.api", "com.recsys.config", "com.recsys.exception",
        "com.recsys.metrics", "com.recsys.jvm", "com.recsys.tracing",
        "com.recsys.ratelimit", "com.recsys.loadshed", "com.recsys.resilience", "com.recsys.health",
        "com.recsys.application"})
```

- [ ] **Step 8: Compile and resolve any remaining cross-package test imports**

Run: `mvn -q test-compile`
Expected: BUILD SUCCESS. If javac reports "cannot find symbol" in a moved **test** file (e.g. `OnlineCapacityServiceTest`/`OnlineHealthServiceTest` referencing `OnlineLoadShedder`), add `import com.recsys.<new-pkg>.<Symbol>;` resolved via the move-map table, then re-run. Repeat until green.

- [ ] **Step 9: Verify the old package is gone**

```bash
ls src/main/java/com/recsys/reliability src/test/java/com/recsys/reliability 2>/dev/null   # expect: empty / no such dir
rmdir src/main/java/com/recsys/reliability src/test/java/com/recsys/reliability 2>/dev/null || true
grep -rn "com\.recsys\.\(observability\|reliability\)" src --include='*.java'   # expect: ZERO hits
```

- [ ] **Step 10: Run the full suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "refactor: split reliability/ into ratelimit/, loadshed/, resilience/, health/ layers

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3 (C1): `ModelRateLimiter` reuses the shared `TokenBucket`

**Files:**
- Modify: `src/main/java/com/recsys/ratelimit/ModelRateLimiter.java` (delete inner `TokenBucket`, delegate to shared one)
- Test (gate, unchanged): `src/test/java/com/recsys/ratelimit/ModelRateLimiterTest.java`

**Interfaces:**
- Consumes: `com.recsys.ratelimit.TokenBucket` (same package, no import) and its `TokenBucket.Decision(boolean allowed, int limit, int remaining, Duration retryAfter)`.
- Produces: `ModelRateLimiter.Decision` and `ModelRateLimiter.tryAcquire(String)` **unchanged** in signature and behavior.

- [ ] **Step 1: Confirm the gate currently passes**

Run: `mvn -q test -Dtest=ModelRateLimiterTest`
Expected: PASS (baseline before the change).

- [ ] **Step 2: Delete the private inner `TokenBucket` and delegate to the shared one**

In `ModelRateLimiter.java`: remove the entire `private static final class TokenBucket { ... }` (lines ~89-121) and the now-unused imports `java.util.concurrent.TimeUnit`. Change the map type and `tryAcquire` to use the shared `TokenBucket`. The class body becomes:

```java
    private final double ratePerSecond;
    private final int burstSize;
    private final boolean enabled;
    private final Map<String, TokenBucket> buckets;
    private final LongSupplier tickerNanos;

    // ... constructors unchanged ...

    public Decision tryAcquire(String userId) {
        if (!enabled) return Decision.unlimited();
        String key = normalizeUserId(userId);
        TokenBucket bucket = buckets.computeIfAbsent(key,
                k -> new TokenBucket(ratePerSecond, burstSize, tickerNanos));
        TokenBucket.Decision d = bucket.tryAcquire();
        return new Decision(d.allowed(), d.limit(), d.remaining(), d.retryAfter());
    }
```

Keep the public `Decision` record exactly as-is:

```java
    public record Decision(boolean allowed, int limit, int remaining, Duration retryAfter) {
        public static Decision unlimited() {
            return new Decision(true, 0, 0, Duration.ZERO);
        }
    }
```

> The shared `TokenBucket` returns `remaining = floor(tokens)`; when denied, `tokens < 1` so `floor == 0` — identical to the old inner bucket's hard-coded `0`. `limit`, `allowed`, and `retryAfter` math are identical. No behavior change.

- [ ] **Step 3: Run the gate**

Run: `mvn -q test -Dtest=ModelRateLimiterTest`
Expected: PASS (LRU eviction, per-user buckets, refill, retryAfter ≥ 900ms, anonymous bucket).

- [ ] **Step 4: Run the full suite (catch any other caller of `ModelRateLimiter.Decision`)**

Run: `mvn -q test`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: ModelRateLimiter reuses shared TokenBucket (drop duplicate inner copy)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4 (C2): `RedisRateLimiter` uses `EnvConfig` for env reads

**Files:**
- Modify: `src/main/java/com/recsys/ratelimit/RedisRateLimiter.java`
- Test (gate, unchanged): `src/test/java/com/recsys/ratelimit/RedisRateLimiterTest.java`

**Interfaces:**
- Consumes: `com.recsys.config.EnvConfig.readInt(String,int)` and `readLong(String,long)` (parse-with-default-on-blank/error — same semantics as the methods being removed).
- Produces: no API change.

- [ ] **Step 1: Add the import and replace the env calls**

In `RedisRateLimiter.java`, add `import com.recsys.config.EnvConfig;`. In the public 1-arg constructor, change the two reads:

```java
    public RedisRateLimiter(Pool<Jedis> pool) {
        this(
                pool,
                "rate:online:",
                EnvConfig.readLong("ONLINE_REDIS_RATE_LIMIT_QPS", 0L),
                EnvConfig.readInt("ONLINE_REDIS_RATE_LIMIT_WINDOW_SECONDS", 1)
        );
    }
```

- [ ] **Step 2: Delete the now-unused private helpers**

Remove the `private static int readIntEnv(...)` and `private static long readLongEnv(...)` methods (lines ~213-231).

- [ ] **Step 3: Run the gate + full suite**

Run: `mvn -q test -Dtest=RedisRateLimiterTest && mvn -q test`
Expected: BUILD SUCCESS (env path is exercised by the default ctor; pkg-private ctors used by tests are unaffected).

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: RedisRateLimiter reads env via EnvConfig (drop duplicate readers)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5 (C3): Extract a shared `resilience.CircuitBreaker`

Three independently-committable sub-steps. If sub-step 5c ever fails a test, revert **only** 5c and keep 5a+5b (the spec permits RouteCircuitBreaker-only extraction).

**Files:**
- Create: `src/main/java/com/recsys/resilience/CircuitBreaker.java`
- Create: `src/test/java/com/recsys/resilience/CircuitBreakerTest.java`
- Modify: `src/main/java/com/recsys/resilience/RouteCircuitBreaker.java`
- Modify: `src/main/java/com/recsys/ratelimit/RedisRateLimiter.java`

**Interfaces:**
- Produces: `com.recsys.resilience.CircuitBreaker` with `enum State { CLOSED, OPEN, HALF_OPEN }`, `State state()`, `boolean tryAcquire()`, `void recordSuccess()`, `void recordFailure()`, `int failureCount()`, public ctor `(int failureThreshold, long cooldownMs)` and package-private `(int, long, LongSupplier clockMs)`.
- `RouteCircuitBreaker` keeps its own public `State` enum + `tryAcquire/recordSuccess/recordFailure/state` (delegates internally).
- `RedisRateLimiter` keeps its public `CircuitState` enum, `circuitState()`, and `Snapshot` (delegates internally).

#### 5a — Create `CircuitBreaker` + its unit test

- [ ] **Step 1: Write the failing test** — `src/test/java/com/recsys/resilience/CircuitBreakerTest.java`

```java
package com.recsys.resilience;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static com.recsys.resilience.CircuitBreaker.State.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CircuitBreakerTest {

    @Test
    void startsClosedAndAllows() {
        CircuitBreaker cb = new CircuitBreaker(3, 10_000L);
        assertThat(cb.state()).isEqualTo(CLOSED);
        assertThat(cb.tryAcquire()).isTrue();
    }

    @Test
    void opensAtThresholdThenHalfOpensAfterCooldown() {
        AtomicLong clock = new AtomicLong(0L);
        CircuitBreaker cb = new CircuitBreaker(2, 100L, clock::get);
        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(CLOSED);
        cb.recordFailure();                       // threshold reached
        assertThat(cb.state()).isEqualTo(OPEN);
        assertThat(cb.tryAcquire()).isFalse();
        clock.set(100L);                          // exactly cooldown elapsed
        assertThat(cb.state()).isEqualTo(HALF_OPEN);
        assertThat(cb.tryAcquire()).isTrue();     // single probe wins
        assertThat(cb.tryAcquire()).isFalse();    // second concurrent caller fails
    }

    @Test
    void successResetsAndProbeFailureReopens() {
        AtomicLong clock = new AtomicLong(0L);
        CircuitBreaker cb = new CircuitBreaker(1, 50L, clock::get);
        cb.recordFailure();                       // opens (threshold 1)
        assertThat(cb.state()).isEqualTo(OPEN);
        clock.set(50L);
        assertThat(cb.state()).isEqualTo(HALF_OPEN);
        cb.recordSuccess();
        assertThat(cb.state()).isEqualTo(CLOSED);
        cb.recordFailure();                       // reopen
        clock.set(100L);
        assertThat(cb.state()).isEqualTo(HALF_OPEN);
        cb.recordFailure();                       // probe failed → push window forward
        clock.set(120L);
        assertThat(cb.state()).isEqualTo(OPEN);   // 120 - 100 = 20 < 50 cooldown
    }

    @Test
    void rejectsInvalidArgs() {
        assertThatThrownBy(() -> new CircuitBreaker(0, 10L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("failureThreshold");
        assertThatThrownBy(() -> new CircuitBreaker(1, -1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cooldownMs");
    }
}
```

- [ ] **Step 2: Run it — expect FAIL (class not found)**

Run: `mvn -q test -Dtest=CircuitBreakerTest`
Expected: FAIL / compile error "cannot find symbol CircuitBreaker".

- [ ] **Step 3: Create `src/main/java/com/recsys/resilience/CircuitBreaker.java`**

```java
package com.recsys.resilience;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Shared CLOSED/OPEN/HALF_OPEN circuit-breaker state machine, consolidated from
 * {@code RouteCircuitBreaker} (gateway) and {@code RedisRateLimiter}'s embedded breaker.
 *
 * Thread-safe: all transitions are CAS-based, no global lock. The clock is injectable so
 * cooldown transitions can be tested deterministically.
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long cooldownMs;
    private final LongSupplier clockMs;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong openedAtMs = new AtomicLong(0L);
    private final AtomicBoolean probing = new AtomicBoolean(false);

    public CircuitBreaker(int failureThreshold, long cooldownMs) {
        this(failureThreshold, cooldownMs, System::currentTimeMillis);
    }

    CircuitBreaker(int failureThreshold, long cooldownMs, LongSupplier clockMs) {
        if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be >= 1");
        if (cooldownMs < 0)       throw new IllegalArgumentException("cooldownMs must be non-negative");
        this.failureThreshold = failureThreshold;
        this.cooldownMs = cooldownMs;
        this.clockMs = clockMs;
    }

    public State state() {
        if (consecutiveFailures.get() < failureThreshold) return State.CLOSED;
        long elapsed = clockMs.getAsLong() - openedAtMs.get();
        return elapsed >= cooldownMs ? State.HALF_OPEN : State.OPEN;
    }

    /** CLOSED → true; OPEN → false; HALF_OPEN → exactly one probe wins via CAS. */
    public boolean tryAcquire() {
        State s = state();
        if (s == State.CLOSED) return true;
        if (s == State.OPEN)   return false;
        return probing.compareAndSet(false, true);
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        probing.set(false);
    }

    public void recordFailure() {
        probing.set(false);
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openedAtMs.set(clockMs.getAsLong());
        }
    }

    public int failureCount() {
        return consecutiveFailures.get();
    }
}
```

- [ ] **Step 4: Run the test — expect PASS**

Run: `mvn -q test -Dtest=CircuitBreakerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add shared resilience.CircuitBreaker state machine

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

#### 5b — `RouteCircuitBreaker` delegates to `CircuitBreaker`

- [ ] **Step 1: Replace `RouteCircuitBreaker` internals with delegation**

Rewrite `src/main/java/com/recsys/resilience/RouteCircuitBreaker.java` to:

```java
package com.recsys.resilience;

/**
 * Per-route circuit breaker for the API gateway. Delegates the CLOSED/OPEN/HALF_OPEN
 * state machine to the shared {@link CircuitBreaker}; keeps this class's public State
 * enum and method surface for gateway callers.
 */
public final class RouteCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    public static final int  DEFAULT_FAILURE_THRESHOLD = 5;
    public static final long DEFAULT_COOLDOWN_MS       = 10_000L;

    private final CircuitBreaker delegate;

    public RouteCircuitBreaker() {
        this(DEFAULT_FAILURE_THRESHOLD, DEFAULT_COOLDOWN_MS);
    }

    public RouteCircuitBreaker(int failureThreshold, long cooldownMs) {
        this.delegate = new CircuitBreaker(failureThreshold, cooldownMs);
    }

    public State state() {
        return switch (delegate.state()) {
            case CLOSED    -> State.CLOSED;
            case OPEN      -> State.OPEN;
            case HALF_OPEN -> State.HALF_OPEN;
        };
    }

    public boolean tryAcquire()   { return delegate.tryAcquire(); }
    public void    recordSuccess(){ delegate.recordSuccess(); }
    public void    recordFailure(){ delegate.recordFailure(); }
}
```

> `CircuitBreaker` throws `IllegalArgumentException` with the same `"failureThreshold"` / `"cooldownMs"` message fragments the existing `RouteCircuitBreakerTest` asserts.

- [ ] **Step 2: Run the gate**

Run: `mvn -q test -Dtest=RouteCircuitBreakerTest`
Expected: PASS (all 11 cases: open/close/half-open/probe/reopen/validation).

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor: RouteCircuitBreaker delegates to shared CircuitBreaker

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

#### 5c — `RedisRateLimiter` delegates its breaker to `CircuitBreaker`

- [ ] **Step 1: Replace the embedded breaker with a `CircuitBreaker` field**

In `src/main/java/com/recsys/ratelimit/RedisRateLimiter.java`:

1. Add `import com.recsys.resilience.CircuitBreaker;`. Remove the now-unused imports `java.util.concurrent.atomic.AtomicBoolean` and `java.util.concurrent.atomic.AtomicInteger` (keep `AtomicLong` — still used by `localCount`).
2. **Keep** the public `enum CircuitState { CLOSED, OPEN, HALF_OPEN }` and the `Snapshot` record exactly as-is (JSON/Snapshot contract unchanged).
3. Remove the breaker fields `consecutiveFailures`, `circuitOpenUntilMs`, `probing`, `circuitFailureThreshold`, `circuitResetMs`. Add `private final CircuitBreaker circuit;`.
4. In the 7-arg constructor, replace those field assignments with:

```java
        this.circuit = new CircuitBreaker(Math.max(1, circuitFailureThreshold), Math.max(1L, circuitResetMs));
```

5. Replace `circuitState()` with a mapping over the delegate:

```java
    public CircuitState circuitState() {
        return switch (circuit.state()) {
            case CLOSED    -> CircuitState.CLOSED;
            case OPEN      -> CircuitState.OPEN;
            case HALF_OPEN -> CircuitState.HALF_OPEN;
        };
    }
```

6. Replace the circuit logic inside `tryAcquire(...)` (the block from `CircuitState state = circuitState();` through the `try/catch`) with:

```java
        // Above local threshold — consult the circuit before hitting Redis.
        // CLOSED → proceed; OPEN → fail open; HALF_OPEN → only the probe winner proceeds.
        if (!circuit.tryAcquire()) {
            return Decision.allowed(limit, 0, true);
        }

        String key = keyPrefix + normalizeBucket(bucket);
        try (Jedis jedis = pool.getResource()) {
            Object raw = jedis.eval(
                    SCRIPT,
                    List.of(key),
                    List.of(Long.toString(limit), Integer.toString(windowSeconds))
            );
            circuit.recordSuccess();
            return parseDecision(raw);
        } catch (Exception e) {
            circuit.recordFailure();
            log.warn("Redis rate limiter failed open for bucket '{}' (failures={}): {}",
                    bucket, circuit.failureCount(), e.toString());
            return Decision.allowed(limit, 0, true);
        }
```

> Equivalence: the old `circuitOpenUntilMs = now + reset` ≡ `openedAtMs = now` with `cooldownMs = reset`, so `state()` transitions are identical. The old OPEN/half-open-lost-probe branches and the CLOSED-proceed are exactly what `circuit.tryAcquire()` returns. `recordSuccess`/`recordFailure` reproduce the old `consecutiveFailures`/`probing` updates. `failureCount()` preserves the log diagnostic.

- [ ] **Step 2: Run the gate**

Run: `mvn -q test -Dtest=RedisRateLimiterTest`
Expected: PASS — including `circuitBreaker_opensAfterConsecutiveFailureThreshold`, `_failsOpenWithoutRedisCallWhenOpen` (verifies `getResource` called exactly once), `_halfOpenAfterResetWindow`, `_closesOnSuccessfulProbeInHalfOpen`, `snapshot_includesCircuitState`.

- [ ] **Step 3: Run the full suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: RedisRateLimiter delegates its circuit to shared CircuitBreaker

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Update docs + final verification

**Files:**
- Modify: `.claude/CLAUDE.md` (Package Map table)

- [ ] **Step 1: Update the CLAUDE.md Package Map**

Replace the two rows:
```
| `observability/` | Metrics, tracing aspect, JVM/GC monitors |
| `reliability/` | Load shedding, circuit breaking, rate limiting, bulkheads, admission control, graceful shutdown |
```
with:
```
| `metrics/` | Request/inference metrics services (Micrometer + Armeria online) |
| `jvm/` | JVM/GC monitors (`GcEventTracker`, `JvmMemoryMonitor`) |
| `tracing/` | `TraceIdAspect` (trace-id propagation) |
| `ratelimit/` | Token-bucket + Redis rate limiters (`TokenBucket`, gateway/LLM/model/Redis) |
| `loadshed/` | Load shedders, admission control, graceful shutdown |
| `resilience/` | Circuit breaker, bulkhead, fault injector (request-tier fault tolerance) |
| `health/` | Online-serving health/ops endpoints + capacity sizing |
```
Also update any prose in CLAUDE.md that names `observability/` or `reliability/` (e.g. the architecture/online-path notes) to the new package names.

- [ ] **Step 2: Final full verification**

```bash
grep -rn "com\.recsys\.\(observability\|reliability\)" src --include='*.java'   # expect: ZERO hits
mvn -q package -DskipTests                                                       # BUILD SUCCESS
mvn -q test                                                                      # BUILD SUCCESS
```

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "docs: update CLAUDE.md package map for ops-layer split

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 4: Open a PR (never merge to main directly)**

```bash
git push -u origin HEAD
gh pr create --title "refactor: split observability/reliability into focused operational layers" \
  --body "$(cat <<'EOF'
Splits the two catch-all packages into seven concern-named top-level layers (metrics, jvm, tracing, ratelimit, loadshed, resilience, health) and consolidates duplicated code:
- ModelRateLimiter reuses the shared TokenBucket (dropped a duplicate inner copy)
- RedisRateLimiter reads env via EnvConfig (dropped duplicate readers)
- New shared resilience.CircuitBreaker; RouteCircuitBreaker and RedisRateLimiter delegate to it

Behavior-preserving: no env-var, meter, gauge, HTTP, or JSON changes. Full `mvn test` green.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Deferred (out of scope for this plan)

- **C4 — shared `metrics.RollingWindowCounter`.** The two metrics services share a rolling-window deque + running-total `evict()` pattern, but they differ (OnlineServingMetricsService adds a `rejected` dimension, a 512-slot reservoir, and a strategy map; InferenceMetricsService has a variant map instead). Each window is only ~15 lines; a generic that fits both without changing either `Snapshot` is more code and more risk than the duplication it removes. Per the spec's "correctness outranks maximal dedup," this is deferred — revisit only if a future change needs to touch both windows.
- **C5 — load-shedder merge.** Decided during spec review: `LoadShedder` (Spring/Micrometer/Semaphore) and `OnlineLoadShedder` (plain/CAS/EnvConfig) stay as separate implementations, co-located in `loadshed/`. Not merged.

## Self-Review

- **Spec coverage:** §3 structure → Tasks 1–2 (all 20 files placed per the move map appendix). §4 C1 → Task 3. §4 C2 → Task 4. §4 C3 → Task 5 (both consumers). §4 C5 → Deferred section (co-locate only, per spec). §3 scanBasePackages → Task 1 Step 5 + Task 2 Step 7. §6 CLAUDE.md → Task 6. §6 PR-not-merge → Task 6 Step 4. C4 (spec "yes if identical") → Deferred with rationale (flag to reviewer).
- **Placeholder scan:** every code step shows full code; every move step shows exact `git mv`/`sed`; the only "resolve as reported" step (Task 2 Step 8) is bounded by an explicit symbol→package mapping table. No TBD/TODO.
- **Type consistency:** `CircuitBreaker.State {CLOSED,OPEN,HALF_OPEN}`, `state()/tryAcquire()/recordSuccess()/recordFailure()/failureCount()` used identically in 5a (def), 5b, 5c. `ModelRateLimiter.Decision(boolean,int,int,Duration)` consumed in Task 3 matches `TokenBucket.Decision`'s accessors. `RedisRateLimiter.CircuitState` + `Snapshot` preserved in 5c.
