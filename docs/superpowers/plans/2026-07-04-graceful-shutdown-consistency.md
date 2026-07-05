# Graceful Shutdown Consistency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the three Armeria servers to a consistent graceful-shutdown baseline — all drain in-flight requests before stopping, and the online server flips readiness to 503 + sheds new load on SIGTERM like the Spring app.

**Architecture:** One shared `GracefulServers` helper encapsulates the 1s/30s drain window used by all three Armeria servers. `OnlineLoadShedder` gains a one-way `shuttingDown` flag (mirroring the Spring `LoadShedder` API) that flips readiness and admission control; the online shutdown hook sets it before `server.stop()`. `ShardTopologyProvider.stop()` becomes a bounded graceful stop.

**Tech Stack:** Java 17, Armeria 1.28.4 (`ServerBuilder.gracefulShutdownTimeoutMillis`, `ServerConfig.gracefulShutdownQuietPeriod/Timeout`), JUnit 5, AssertJ, Mockito, Maven.

## Global Constraints

- Drain window: **1s quiet / 30s max** on all Armeria servers (matches the online server's existing value; 30s < K8s `terminationGracePeriodSeconds: 60`). Hardcoded shared constant — not env-configurable.
- `OnlineLoadShedder.DEFAULT_DRAIN_UTILIZATION` changes **0.90 → 0.95** to match Spring's `maxInFlightUtilization`.
- `shuttingDown` is a `volatile boolean`, one-way (never reset), mirroring `com.recsys.loadshed.LoadShedder`.
- LLM/online routes and non-LLM routes are untouched except the specified builder/hook lines.
- No `minSampleSize` guard on the online path (dropped — the online health service has no failure-rate/latency check to guard).
- No readiness-flip for RecSysServer/gateway (no load shedder; they get the drain window only).
- TDD throughout. Single-class test command: `mvn test -Dtest=<Class>`. Full compile: `mvn package -DskipTests`.
- Commit after every task. Work stays on branch `feat/graceful-shutdown-consistency` (already created). Do not merge to main.

## File Structure

- **Create** `src/main/java/com/recsys/loadshed/GracefulServers.java` — the shared drain-window helper.
- **Create** `src/test/java/com/recsys/loadshed/GracefulServersTest.java`.
- **Modify** `src/main/java/com/recsys/loadshed/OnlineLoadShedder.java` — `shuttingDown` flag, threshold, `Snapshot` field.
- **Modify** `src/test/java/com/recsys/loadshed/OnlineLoadShedderTest.java` — shutdown + threshold tests.
- **Modify** `src/main/java/com/recsys/health/OnlineHealthService.java` — surface `shuttingDown` in JSON.
- **Modify** `src/test/java/com/recsys/health/OnlineHealthServiceTest.java` — readiness-flip-on-shutdown test.
- **Modify** `src/main/java/com/recsys/api/serving/RecSysServer.java`, `.../api/gateway/MicroserviceGatewayServer.java`, `.../api/online/OnlinePredictionServer.java` — adopt the helper; online hook sets `markShuttingDown()`.
- **Modify** `src/main/java/com/recsys/infrastructure/redis/sharding/ShardTopologyProvider.java` — graceful `stop()`, widen `scheduler` field.
- **Modify** `src/test/java/com/recsys/infrastructure/redis/sharding/ShardTopologyProviderTest.java` — `stop()` tests.

---

### Task 1: `GracefulServers` drain-window helper

**Files:**
- Create: `src/main/java/com/recsys/loadshed/GracefulServers.java`
- Test: `src/test/java/com/recsys/loadshed/GracefulServersTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `public static com.linecorp.armeria.server.ServerBuilder GracefulServers.applyShutdownWindow(ServerBuilder sb)` — sets a 1s quiet / 30s max graceful shutdown window and returns the same builder. Package-visible constants `QUIET_PERIOD_MS = 1_000L`, `TIMEOUT_MS = 30_000L`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/loadshed/GracefulServersTest.java`:

```java
package com.recsys.loadshed;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GracefulServersTest {

    @Test
    void applyShutdownWindow_setsOneSecondQuietAndThirtySecondTimeout() {
        ServerBuilder sb = Server.builder().http(0);
        Server server = GracefulServers.applyShutdownWindow(sb)
                .service("/health", (ctx, req) ->
                        com.linecorp.armeria.common.HttpResponse.of(200))
                .build();

        assertThat(server.config().gracefulShutdownQuietPeriod()).isEqualTo(Duration.ofSeconds(1));
        assertThat(server.config().gracefulShutdownTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void applyShutdownWindow_returnsSameBuilderForChaining() {
        ServerBuilder sb = Server.builder().http(0);
        assertThat(GracefulServers.applyShutdownWindow(sb)).isSameAs(sb);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=GracefulServersTest`
Expected: FAIL — compile error: `GracefulServers` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/recsys/loadshed/GracefulServers.java`:

```java
package com.recsys.loadshed;

import com.linecorp.armeria.server.ServerBuilder;

/**
 * Shared graceful-shutdown window for the Armeria serving entrypoints. Applying this makes
 * {@code server.stop()} wait for in-flight requests to drain instead of cutting them off.
 */
public final class GracefulServers {

    // 1s quiet period matches the online server's original value. 30s max drain sits below the
    // K8s terminationGracePeriodSeconds: 60 so the pod is never SIGKILLed mid-drain.
    static final long QUIET_PERIOD_MS = 1_000L;
    static final long TIMEOUT_MS = 30_000L;

    private GracefulServers() {}

    /** Applies the standard drain window to the builder and returns it for chaining. */
    public static ServerBuilder applyShutdownWindow(ServerBuilder sb) {
        return sb.gracefulShutdownTimeoutMillis(QUIET_PERIOD_MS, TIMEOUT_MS);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=GracefulServersTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/loadshed/GracefulServers.java src/test/java/com/recsys/loadshed/GracefulServersTest.java
git commit -m "feat(shutdown): add GracefulServers drain-window helper

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `OnlineLoadShedder` shutdown flag + threshold alignment

**Files:**
- Modify: `src/main/java/com/recsys/loadshed/OnlineLoadShedder.java`
- Test: `src/test/java/com/recsys/loadshed/OnlineLoadShedderTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `public void markShuttingDown()` — sets the one-way `shuttingDown` flag.
  - `public boolean isShuttingDown()`.
  - `tryAcquire()` returns `false` when shutting down; `shouldDrain()` returns `true` when shutting down.
  - `Snapshot` record gains a trailing `boolean shuttingDown` field; `snapshot().shuttingDown()` accessor. `suggestedWeight` and `retryAfterSeconds` reflect draining while shutting down.
  - `DEFAULT_DRAIN_UTILIZATION` is now `0.95`.

- [ ] **Step 1: Write the failing tests**

Add these tests to `src/test/java/com/recsys/loadshed/OnlineLoadShedderTest.java` (keep existing tests; add inside the class):

```java
    @Test
    void markShuttingDown_rejectsNewRequestsAndFlipsDrain() {
        var shedder = new OnlineLoadShedder(4, 0.95); // plenty of headroom, not utilization-draining

        assertThat(shedder.isShuttingDown()).isFalse();
        assertThat(shedder.shouldDrain()).isFalse();

        shedder.markShuttingDown();

        assertThat(shedder.isShuttingDown()).isTrue();
        assertThat(shedder.tryAcquire()).isFalse();          // new work rejected during drain
        assertThat(shedder.shouldDrain()).isTrue();           // readiness will report 503

        var snap = shedder.snapshot();
        assertThat(snap.shuttingDown()).isTrue();
        assertThat(snap.suggestedWeight()).isEqualTo(0);      // advertise zero weight while draining
        assertThat(snap.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void markShuttingDown_isIdempotent() {
        var shedder = new OnlineLoadShedder(2, 0.95);
        shedder.markShuttingDown();
        shedder.markShuttingDown();
        assertThat(shedder.isShuttingDown()).isTrue();
        assertThat(shedder.tryAcquire()).isFalse();
    }

    @Test
    void defaultConstructor_drainUtilizationIsNinetyFive() {
        // Verifies DEFAULT_DRAIN_UTILIZATION; assumes ONLINE_DRAIN_UTILIZATION env is unset.
        assertThat(new OnlineLoadShedder().snapshot().drainUtilization()).isEqualTo(0.95);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=OnlineLoadShedderTest`
Expected: FAIL — compile error: `markShuttingDown` / `isShuttingDown` / `snapshot().shuttingDown()` do not exist; and `drainUtilization()` default assertion fails (0.90).

- [ ] **Step 3: Write minimal implementation**

In `src/main/java/com/recsys/loadshed/OnlineLoadShedder.java`:

3a. Change the default constant (line 13):

```java
    private static final double DEFAULT_DRAIN_UTILIZATION = 0.95;
```

3b. Add the flag field after the existing counters (after `rejectedRequests`):

```java
    // Set once on SIGTERM; volatile so all threads see it immediately. One-way, never reset.
    private volatile boolean shuttingDown = false;
```

3c. Add the two methods (place after the constructor, before `tryAcquire`):

```java
    /**
     * Called on SIGTERM so readiness returns 503 and admission control rejects new requests,
     * letting load balancers drain this instance before in-flight work is interrupted.
     */
    public void markShuttingDown() {
        shuttingDown = true;
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }
```

3d. In `tryAcquire()`, reject immediately when shutting down (add at the very top of the method, before the `while` loop):

```java
    public boolean tryAcquire() {
        if (shuttingDown) {
            rejectedRequests.incrementAndGet();
            return false;
        }
        while (true) {
            int current = inFlightRequests.get();
            if (current >= maxConcurrentRequests) {
                rejectedRequests.incrementAndGet();
                return false;
            }
            if (inFlightRequests.compareAndSet(current, current + 1)) {
                acceptedRequests.incrementAndGet();
                return true;
            }
        }
    }
```

3e. Update `shouldDrain()`:

```java
    public boolean shouldDrain() {
        return shuttingDown || utilization() >= drainUtilization;
    }
```

3f. Update `snapshot()` so weight/retry reflect draining while shutting down, and add the field:

```java
    public Snapshot snapshot() {
        double utilization = utilization();
        boolean draining = shuttingDown || utilization >= drainUtilization;
        int retryAfterSeconds = draining ? 1 : 0;
        int suggestedWeight = shuttingDown ? 0 : Math.max(0, (int) Math.round((1.0 - utilization) * 100.0));
        return new Snapshot(
                inFlightRequests.get(),
                maxConcurrentRequests,
                utilization,
                drainUtilization,
                acceptedRequests.get(),
                rejectedRequests.get(),
                suggestedWeight,
                retryAfterSeconds,
                shuttingDown
        );
    }
```

3g. Add the trailing field to the `Snapshot` record:

```java
    public record Snapshot(
            int inFlightRequests,
            int maxConcurrentRequests,
            double utilization,
            double drainUtilization,
            long acceptedRequests,
            long rejectedRequests,
            int suggestedWeight,
            int retryAfterSeconds,
            boolean shuttingDown
    ) {}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=OnlineLoadShedderTest`
Expected: PASS — 3 new tests + 4 existing (the existing `suggestedWeight_reflectsAvailableHeadroom` still yields 75 since not shutting down; existing tests construct with explicit drainUtilization so the default change does not affect them).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/loadshed/OnlineLoadShedder.java src/test/java/com/recsys/loadshed/OnlineLoadShedderTest.java
git commit -m "feat(shutdown): add SIGTERM drain flag to OnlineLoadShedder, align threshold to 0.95

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `OnlineHealthService` surfaces `shuttingDown`

**Files:**
- Modify: `src/main/java/com/recsys/health/OnlineHealthService.java`
- Test: `src/test/java/com/recsys/health/OnlineHealthServiceTest.java`

**Interfaces:**
- Consumes: `OnlineLoadShedder.snapshot().shuttingDown()` and `shouldDrain()` (Task 2).
- Produces: `/health/ready` (and `/health`) JSON body now includes `"shuttingDown"`; readiness already returns 503 when `shouldDrain()` (now true on SIGTERM).

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/recsys/health/OnlineHealthServiceTest.java` a second shedder + server (the existing static `SHEDDER` cannot be reused because `markShuttingDown()` is one-way and would poison other tests). Add the field, extension, and test inside the class:

```java
    // Dedicated shedder for the shutdown case — markShuttingDown() is one-way, so it must not be
    // shared with the utilization-draining test above.
    private static final OnlineLoadShedder SHUTDOWN_SHEDDER = new OnlineLoadShedder(4, 0.95);

    @RegisterExtension
    static final ServerExtension shutdownServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/health/ready",
                    new OnlineHealthService(new OnlineServingMetricsService(), SHUTDOWN_SHEDDER));
        }
    };

    @Test
    void sigtermFlipsReadinessToUnavailableAndBodyShowsShuttingDown() {
        // Healthy before SIGTERM: low utilization, not shutting down.
        var before = shutdownServer.blockingWebClient().get("/health/ready");
        assertThat(before.status()).isEqualTo(HttpStatus.OK);
        assertThat(before.contentUtf8()).contains("\"shuttingDown\":false");

        SHUTDOWN_SHEDDER.markShuttingDown();

        var after = shutdownServer.blockingWebClient().get("/health/ready");
        assertThat(after.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(after.contentUtf8()).contains("\"shuttingDown\":true");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=OnlineHealthServiceTest`
Expected: FAIL — body does not contain `"shuttingDown"` (the JSON map has no such key yet).

- [ ] **Step 3: Write minimal implementation**

In `src/main/java/com/recsys/health/OnlineHealthService.java`, add `shuttingDown` to the response map inside `doGet`. Replace the `Map.of(...)` block with:

```java
            return writeJson(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE, Map.of(
                    "ok", ready,
                    "ready", ready,
                    "service", "online-serving",
                    "qps", metrics.qps(),
                    "inFlightRequests", load.inFlightRequests(),
                    "maxConcurrentRequests", load.maxConcurrentRequests(),
                    "suggestedWeight", load.suggestedWeight(),
                    "shuttingDown", load.shuttingDown()
            ));
```

(`Map.of` supports up to 10 entries; this is 8 — fine.)

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=OnlineHealthServiceTest`
Expected: PASS — new shutdown test + existing `drainingChangesReadinessButNotLiveness`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/health/OnlineHealthService.java src/test/java/com/recsys/health/OnlineHealthServiceTest.java
git commit -m "feat(shutdown): surface shuttingDown in online readiness body

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Wire the drain window into all three Armeria servers + online SIGTERM flag

**Files:**
- Modify: `src/main/java/com/recsys/api/serving/RecSysServer.java`
- Modify: `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java`
- Modify: `src/main/java/com/recsys/api/online/OnlinePredictionServer.java`

**Interfaces:**
- Consumes: `GracefulServers.applyShutdownWindow(ServerBuilder)` (Task 1); `OnlineLoadShedder.markShuttingDown()` (Task 2).
- Produces: no new public API. Runtime behavior: all three servers drain in-flight requests up to 30s; the online server flips readiness/admission on SIGTERM.

This task is server-`main()` wiring (not unit-testable without booting the process). Verification is compile + the existing integration suites for regression; the helper values are covered by `GracefulServersTest` (Task 1) and the flag behavior by Tasks 2–3.

- [ ] **Step 1: RecSysServer — apply the drain window**

In `src/main/java/com/recsys/api/serving/RecSysServer.java`:

1a. Add the import (with the other `com.recsys.loadshed` imports; `GracefulExecutors` is already imported from that package):

```java
import com.recsys.loadshed.GracefulServers;
```

1b. Apply the window to the builder. After the `ServerBuilder sb = Server.builder().http(port)...` service chain and any CORS decorator, immediately before `Server server = sb.build();`, add:

```java
            GracefulServers.applyShutdownWindow(sb);
```

- [ ] **Step 2: Gateway — apply the drain window**

In `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java`:

2a. Add the import:

```java
import com.recsys.loadshed.GracefulServers;
```

2b. Change the builder line (currently `ServerBuilder sb = Server.builder().http(port);`) to apply the window right before `Server server = sb.build();`. Add this line immediately before `Server server = sb.build();`:

```java
        GracefulServers.applyShutdownWindow(sb);
```

- [ ] **Step 3: OnlinePredictionServer — adopt the helper + set the SIGTERM flag**

In `src/main/java/com/recsys/api/online/OnlinePredictionServer.java`:

3a. Add the import:

```java
import com.recsys.loadshed.GracefulServers;
```

3b. Replace the inline `.gracefulShutdownTimeoutMillis(1_000L, 30_000L)` in the builder chain with the helper. The current chain is:

```java
            sb.http(port)
              .requestTimeoutMillis(requestTimeoutMs)
              .gracefulShutdownTimeoutMillis(1_000L, 30_000L)
              .meterRegistry(registry)
```

Change it to drop that line and apply the helper to `sb` just before `Server server = sb.build();`:

```java
            sb.http(port)
              .requestTimeoutMillis(requestTimeoutMs)
              .meterRegistry(registry)
```

and immediately before `Server server = sb.build();` add:

```java
            GracefulServers.applyShutdownWindow(sb);
```

3c. In the shutdown hook, set the drain flag first. Change the hook body from:

```java
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.stop().join();
                asyncEventPublisher.close();
```

to:

```java
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                loadShedder.markShuttingDown();   // flip readiness to 503 + shed new load before draining
                server.stop().join();
                asyncEventPublisher.close();
```

(`loadShedder` is the same instance already passed to `OnlineHealthService` and `OnlineAdmissionControl`, so this flips both.)

- [ ] **Step 4: Compile and run regression suites**

Run: `mvn package -DskipTests`
Expected: BUILD SUCCESS.

Run: `mvn test -Dtest=GracefulServersTest,OnlineLoadShedderTest,OnlineHealthServiceTest,RecSysServerIntegrationTest,GatewayServerIntegrationTest,OnlinePredictionServerIntegrationTest`
Expected: PASS (all) — confirms the three servers still boot and route with the drain window applied.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/api/serving/RecSysServer.java src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java src/main/java/com/recsys/api/online/OnlinePredictionServer.java
git commit -m "feat(shutdown): drain window on all Armeria servers + online SIGTERM flag

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Graceful `ShardTopologyProvider.stop()`

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/redis/sharding/ShardTopologyProvider.java`
- Test: `src/test/java/com/recsys/infrastructure/redis/sharding/ShardTopologyProviderTest.java`

**Interfaces:**
- Consumes: nothing new (`TimeUnit` already imported).
- Produces: `stop()` now shuts the scheduler down gracefully (bounded 1s await, then `shutdownNow()`); `scheduler` field widened from `private` to package-private so the same-package test can assert `isShutdown()`.

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/com/recsys/infrastructure/redis/sharding/ShardTopologyProviderTest.java` (imports at top already cover Mockito/AssertJ; add these tests inside the class):

```java
    @Test
    void stop_shutsDownScheduler() {
        ShardTopologyStore store = mock(ShardTopologyStore.class);
        when(store.load()).thenReturn(snap(1, 2, 0L, null, null, null));
        ShardTopologyProvider p = new ShardTopologyProvider(store, 150, 2, 30_000L, () -> 0L);
        p.start();
        assertThat(p.scheduler).isNotNull();

        p.stop();

        assertThat(p.scheduler.isShutdown()).isTrue();
    }

    @Test
    void stop_onUnstartedProvider_doesNotThrow() {
        ShardTopologyStore store = mock(ShardTopologyStore.class);
        ShardTopologyProvider p = new ShardTopologyProvider(store, 150, 2, 30_000L, () -> 0L);
        // never started -> scheduler is null
        p.stop(); // must not throw
        assertThat(p.scheduler).isNull();
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=ShardTopologyProviderTest`
Expected: FAIL — `p.scheduler` is not accessible (currently `private`); once accessible, `stop_shutsDownScheduler` would still pass with the old `shutdownNow()`, but the field-visibility change is required to compile. (Both new tests fail to compile until Step 3.)

- [ ] **Step 3: Write minimal implementation**

In `src/main/java/com/recsys/infrastructure/redis/sharding/ShardTopologyProvider.java`:

3a. Widen the field visibility (line 27). Change:

```java
    private ScheduledExecutorService scheduler;
```

to:

```java
    ScheduledExecutorService scheduler;   // package-private for shutdown assertions in tests
```

3b. Replace `stop()`:

```java
    public void stop() {
        if (scheduler == null) return;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=ShardTopologyProviderTest`
Expected: PASS — 2 new tests + 4 existing.

- [ ] **Step 5: Full compile + commit**

Run: `mvn package -DskipTests`
Expected: BUILD SUCCESS.

```bash
git add src/main/java/com/recsys/infrastructure/redis/sharding/ShardTopologyProvider.java src/test/java/com/recsys/infrastructure/redis/sharding/ShardTopologyProviderTest.java
git commit -m "feat(shutdown): graceful bounded stop for ShardTopologyProvider scheduler

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Drain window on RecSysServer + gateway → Task 4 (steps 1–2) via Task 1 helper. ✓
- OnlinePredictionServer adopts helper (no behavior change) → Task 4 step 3b. ✓
- OnlineLoadShedder `shuttingDown` flag (markShuttingDown/isShuttingDown, tryAcquire reject, shouldDrain, snapshot weight/field) → Task 2. ✓
- Online shutdown hook sets flag before stop → Task 4 step 3c. ✓
- OnlineHealthService surfaces shuttingDown → Task 3. ✓
- Threshold 0.90 → 0.95 → Task 2 step 3a. ✓
- ShardTopologyProvider.stop() graceful bounded await → Task 5. ✓
- minSampleSize on online path → explicitly out of scope (spec Non-Goals); no task. ✓
- RecSysServer/gateway readiness-flip → out of scope (spec Non-Goals); no task. ✓

**Placeholder scan:** No TBD/TODO/"handle edge cases"; every code step shows complete code. ✓

**Type consistency:** `markShuttingDown()`/`isShuttingDown()`/`shouldDrain()`/`snapshot().shuttingDown()` used identically in Tasks 2–4; `GracefulServers.applyShutdownWindow(ServerBuilder)` signature identical in Tasks 1 and 4; `Snapshot` field order (…, suggestedWeight, retryAfterSeconds, shuttingDown) matches between the record definition and the `snapshot()` constructor call (Task 2 steps 3f/3g). ✓

**Risk notes (verify at implementation):**
- `defaultConstructor_drainUtilizationIsNinetyFive` (Task 2) assumes the `ONLINE_DRAIN_UTILIZATION` env var is unset in the test environment (consistent with the repo's other env-default-reliant tests).
- Widening `ShardTopologyProvider.scheduler` to package-private (Task 5) is a minimal, test-only visibility change; the same-package test reads it directly. Note it in the task report.
- Task 4 exact anchor lines (`Server server = sb.build();`, the online hook's first two lines) should be located by content, not line number, since earlier tasks do not touch these files but the LLM-warmup branch history may differ — match the surrounding code shown here.
