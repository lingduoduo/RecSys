# Overload Protection Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add concurrency admission control to RecSys Serving 6010, wire the bounded `WorkerBulkhead` into both recall executors with graceful per-channel overflow, and document the existing rate-limit/load-shed values.

**Architecture:** Reuse the proven Online 7010 gate (`OnlineLoadShedder` + admission decorator) for 6010; generalize the decorator with an added `Runnable onReject` constructor so the 7010 path is untouched. Replace the unbounded fixed thread pools with `WorkerBulkhead`, and make `MultiChannelRecallService` treat a `RejectedExecutionException` as a graceful empty channel result.

**Tech Stack:** Java 17, Armeria, JUnit 5 + AssertJ + Mockito, Kustomize. Build needs JDK 17.

## Global Constraints

- Branch `feat/overload-protection` off `main`. Never merge to main directly — open a PR.
- Build/test with JDK 17: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`.
- **7010 behavior MUST be unchanged** by the decorator generalization (same 429, same metric on reject). The existing `OnlineServingMetricsService` constructor must keep working via delegation.
- **Do NOT change existing numeric limit values** (`ONLINE_REDIS_RATE_LIMIT_QPS=200`, gateway 100 RPS, etc.). Value work is docs-only.
- 6010 admission guards only the expensive routes (`/getrecommendation` + `/recommendation` alias, `/v2/recommend`, `/similar`); cheap routes (health, `/item`,`/movie`,`/getuser`,`/user`, `/setembedding`,`/setuserembedding`, predict) stay unguarded.
- Bulkhead overflow degrades **per channel** (empty `ChannelResult` + health-monitor failure), never throws out of the request.
- Config defaults: `CATALOG_MAX_CONCURRENT_REQUESTS`=64, `CATALOG_DRAIN_UTILIZATION`=0.90, `RECALL_BULKHEAD_QUEUE_CAPACITY`=poolSize×4 (poolSize = availableProcessors×2).

---

### Task 1: Generalize the admission decorator (add `Runnable onReject`), keep 7010 unchanged

Let `OnlineAdmissionControl` accept a generic reject callback so it can back 6010 too, while the existing `OnlineServingMetricsService` constructor keeps working unchanged.

**Files:**
- Modify: `src/main/java/com/recsys/loadshed/OnlineAdmissionControl.java`
- Modify/Create test: `src/test/java/com/recsys/loadshed/OnlineAdmissionControlTest.java` (extend if it exists, else create)

**Interfaces:**
- Produces: `OnlineAdmissionControl(HttpService, OnlineLoadShedder, Runnable onReject)` (new) plus the existing `OnlineAdmissionControl(HttpService, OnlineLoadShedder, OnlineServingMetricsService)` (now delegates). On reject it runs `onReject` and returns 429 + Retry-After.

- [ ] **Step 1: Check for an existing test**

Run: `ls src/test/java/com/recsys/loadshed/OnlineAdmissionControlTest.java 2>/dev/null && echo EXISTS || echo NONE`
Note the result; you either extend it (EXISTS) or create it (NONE) in Step 4.

- [ ] **Step 2: Replace the metrics field with a reject callback**

In `src/main/java/com/recsys/loadshed/OnlineAdmissionControl.java`:
- Change the field `private final OnlineServingMetricsService metricsService;` to `private final Runnable onReject;`
- Replace the single constructor with two:
```java
    /** Backward-compatible: reports rejections to the online metrics service. */
    public OnlineAdmissionControl(HttpService delegate,
                                  OnlineLoadShedder loadShedder,
                                  OnlineServingMetricsService metricsService) {
        this(delegate, loadShedder, metricsService::recordRejected);
    }

    /** General: {@code onReject} runs once per rejected request (e.g. a metrics counter or no-op). */
    public OnlineAdmissionControl(HttpService delegate,
                                  OnlineLoadShedder loadShedder,
                                  Runnable onReject) {
        super(delegate);
        this.loadShedder = loadShedder;
        this.onReject = onReject;
    }
```
- In `serve(...)`, change `metricsService.recordRejected();` to `onReject.run();`
- Remove the now-unused `import com.recsys.metrics.OnlineServingMetricsService;`? No — keep it; the delegating constructor still references the type.

- [ ] **Step 3: Compile**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -o compile 2>&1 | tail -5 || JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q compile 2>&1 | tail -5`
Expected: BUILD SUCCESS (the two OnlinePredictionServer call sites still use the metrics constructor, which now delegates).

- [ ] **Step 4: Write/extend the test**

Create or extend `src/test/java/com/recsys/loadshed/OnlineAdmissionControlTest.java` with a test for the new callback constructor. Use a real `OnlineLoadShedder(1, 0.9)` (capacity 1) and a trivial delegate:
```java
package com.recsys.loadshed;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OnlineAdmissionControlTest {

    private static ServiceRequestContext ctx() {
        return ServiceRequestContext.of(HttpRequest.of(com.linecorp.armeria.common.HttpMethod.GET, "/x"));
    }

    @Test
    void rejectsWithRetryAfterAndRunsCallbackWhenAtCapacity() throws Exception {
        OnlineLoadShedder shedder = new OnlineLoadShedder(1, 0.9);
        AtomicInteger rejects = new AtomicInteger();
        // Occupy the one slot so the next request is rejected.
        assertThat(shedder.tryAcquire()).isTrue();

        HttpService delegate = (c, r) -> HttpResponse.of(HttpStatus.OK);
        OnlineAdmissionControl gate = new OnlineAdmissionControl(delegate, shedder, rejects::incrementAndGet);

        AggregatedHttpResponse resp = gate.serve(ctx(), HttpRequest.of(
                com.linecorp.armeria.common.HttpMethod.GET, "/x")).aggregate().join();

        assertThat(resp.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(resp.headers().get(com.linecorp.armeria.common.HttpHeaderNames.RETRY_AFTER)).isNotNull();
        assertThat(rejects.get()).isEqualTo(1);
    }

    @Test
    void admitsAndReleasesWhenUnderCapacity() throws Exception {
        OnlineLoadShedder shedder = new OnlineLoadShedder(2, 0.9);
        HttpService delegate = (c, r) -> HttpResponse.of(HttpStatus.OK);
        OnlineAdmissionControl gate = new OnlineAdmissionControl(delegate, shedder, () -> {});

        AggregatedHttpResponse resp = gate.serve(ctx(), HttpRequest.of(
                com.linecorp.armeria.common.HttpMethod.GET, "/x")).aggregate().join();

        assertThat(resp.status()).isEqualTo(HttpStatus.OK);
        // slot released after completion
        assertThat(shedder.snapshot().inFlightRequests()).isZero();
    }
}
```

- [ ] **Step 5: Run the test**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest=OnlineAdmissionControlTest`
Expected: PASS. If an online admission/integration test already exists elsewhere, also run it to confirm 7010 wiring is unaffected.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/loadshed/OnlineAdmissionControl.java \
        src/test/java/com/recsys/loadshed/OnlineAdmissionControlTest.java
git commit -m "refactor(loadshed): add Runnable onReject ctor to OnlineAdmissionControl

Lets the admission decorator back any server (not just online-metrics-backed).
The existing OnlineServingMetricsService constructor delegates to it, so the
7010 path is unchanged.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Add concurrency admission control to RecSys Serving 6010

Guard the expensive routes with an `OnlineLoadShedder` gate + the decorator, and add a drain-aware readiness route.

**Files:**
- Modify: `src/main/java/com/recsys/api/serving/RecSysServer.java`

**Interfaces:**
- Consumes: `OnlineLoadShedder(int, double)`, `OnlineAdmissionControl(HttpService, OnlineLoadShedder, Runnable)` (Task 1), `com.recsys.config.EnvConfig`.
- Produces: 6010's `/getrecommendation`, `/recommendation`, `/v2/recommend`, `/similar` return 429 above `CATALOG_MAX_CONCURRENT_REQUESTS`; a `/health/ready` route returns 503 when draining.

- [ ] **Step 1: Build the shedder and wrap the expensive routes**

In `src/main/java/com/recsys/api/serving/RecSysServer.java`, add imports:
```java
import com.recsys.config.EnvConfig;
import com.recsys.loadshed.OnlineAdmissionControl;
import com.recsys.loadshed.OnlineLoadShedder;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
```
Before building the `ServerBuilder` (just before `ServerBuilder sb = Server.builder()`), create the shedder:
```java
            OnlineLoadShedder loadShedder = new OnlineLoadShedder(
                    EnvConfig.readInt("CATALOG_MAX_CONCURRENT_REQUESTS", 64),
                    EnvConfig.readDouble("CATALOG_DRAIN_UTILIZATION", 0.90));
```
Then wrap the four expensive route registrations with the decorator (leave the others as-is):
```java
                    .service(ROUTE_SIMILAR,
                            new OnlineAdmissionControl(new RecommendationService.Similar(embCache),
                                    loadShedder, () -> {}))
                    .service(ROUTE_RECOMMENDATION,
                            new OnlineAdmissionControl(recommendationService, loadShedder, () -> {}))
                    .service(ROUTE_RECOMMENDATION_ALIAS,
                            new OnlineAdmissionControl(recommendationService, loadShedder, () -> {}))
                    ...
                    .service(ROUTE_V2_RECOMMEND,
                            new OnlineAdmissionControl(new RecommendationService.V2(orchestrator),
                                    loadShedder, () -> {}))
```
(Keep `.service(ROUTE_ITEM, movieService)`, users, setembedding, health, predict unchanged.)

- [ ] **Step 2: Add a drain-aware readiness route**

Add a `/health/ready` route (liveness `/health` stays as `RecommendationService.Health()`):
```java
                    .service("/health/ready", (ctx, req) ->
                            loadShedder.shouldDrain()
                                    ? HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE)
                                    : HttpResponse.of(HttpStatus.OK))
```

- [ ] **Step 3: Compile**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q compile 2>&1 | tail -5`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Manual admission smoke test (unit-level)**

RecSysServer wiring is integration-level and hard to unit test in isolation; the decorator itself is covered by Task 1. Verify the server builds and the routes register by running the existing RecSys serving tests if any:
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest='com.recsys.api.serving.*' 2>&1 | tail -8 || echo "no serving tests — rely on compile + Task 1 gate test"
```
Expected: green (or "no serving tests").

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/api/serving/RecSysServer.java
git commit -m "feat(serving): concurrency admission control on RecSys 6010 expensive routes

Guard /getrecommendation, /recommendation, /v2/recommend, /similar with an
OnlineLoadShedder gate (CATALOG_MAX_CONCURRENT_REQUESTS=64) returning 429; add
a drain-aware /health/ready. Cheap metadata/health routes stay unguarded.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Wire `WorkerBulkhead` into both recall executors with graceful overflow

Replace the unbounded fixed pools and make a rejected channel degrade to empty.

**Files:**
- Modify: `src/main/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallService.java`
- Modify: `src/main/java/com/recsys/api/serving/RecSysServer.java`
- Modify: `src/main/java/com/recsys/api/online/OnlinePredictionServer.java`
- Test: `src/test/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallServiceBulkheadTest.java` (new)

**Interfaces:**
- Consumes: `WorkerBulkhead(String, int poolSize, int queueCapacity)` and `asExecutorService()`.
- Produces: recall channels submitted to a bounded executor; a `RejectedExecutionException` yields `ChannelResult(name, List.of(), rex)` + `healthMonitor.recordFailure(name)` and the request proceeds.

- [ ] **Step 1: Write the failing bulkhead-overflow test**

Create `src/test/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallServiceBulkheadTest.java`. It builds a `MultiChannelRecallService` over a `WorkerBulkhead` sized to force rejection (poolSize 1, queueCapacity 1) with channels that block, and asserts recall returns (does not throw) and the rejected channel recorded a failure. Model it on the existing `MultiChannelRecallServiceTest` construction (read that test first for the exact builder/API). Skeleton:
```java
package com.recsys.application.retrieval.multichannel;

import com.recsys.resilience.WorkerBulkhead;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class MultiChannelRecallServiceBulkheadTest {

    @Test
    void rejectedChannelDegradesGracefullyInsteadOfThrowing() {
        // Bulkhead with 1 worker + queue 1 → at most 2 in-flight; extra channels are rejected.
        WorkerBulkhead bulkhead = new WorkerBulkhead("test-recall", 1, 1);
        // Build a MultiChannelRecallService with several slow channels over bulkhead.asExecutorService()
        // (mirror MultiChannelRecallServiceTest's builder usage), then:
        assertThatCode(() -> {
            var result = /* recallService.recall(query, limit) */ null;
            assertThat(result).isNotNull(); // request completed, did not throw
        }).doesNotThrowAnyException();
        // Optionally assert healthMonitor recorded a failure for at least one channel.
    }
}
```
IMPORTANT: read `src/test/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallServiceTest.java` first and reuse its exact `RecallConfig`/channel/query construction so this test compiles and drives real recall. Make the channels sleep long enough that the queue saturates and later channels are rejected.

- [ ] **Step 2: Run it — verify it fails (throws today)**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest=MultiChannelRecallServiceBulkheadTest`
Expected: FAIL — today the synchronous `RejectedExecutionException` from `supplyAsync` propagates out of `recall(...)`, so the request throws.

- [ ] **Step 3: Make rejection graceful in `MultiChannelRecallService`**

In `src/main/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallService.java`, add the import `import java.util.concurrent.RejectedExecutionException;`, then wrap the per-channel submit (the `CompletableFuture.supplyAsync(...).orTimeout(...).exceptionally(...)` block, ~lines 117-125):
```java
            CompletableFuture<ChannelResult> future;
            try {
                future = CompletableFuture
                        .supplyAsync(() -> {
                            faultInjector.maybeInject("channel:" + name);
                            return new ChannelResult(name, channel.recall(query, limit), null);
                        }, executor)
                        .orTimeout(channelTimeoutMs, TimeUnit.MILLISECONDS)
                        .exceptionally(ex -> new ChannelResult(name, List.of(), ex));
            } catch (RejectedExecutionException rex) {
                healthMonitor.recordFailure(name);
                log.warn("Channel '{}' rejected by recall bulkhead (queue full)", name);
                future = CompletableFuture.completedFuture(new ChannelResult(name, List.of(), rex));
            }
            futures.add(future);
```

- [ ] **Step 4: Run the test — verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest=MultiChannelRecallServiceBulkheadTest`
Expected: PASS (recall completes; rejected channel degraded to empty).

- [ ] **Step 5: Swap the executors to WorkerBulkhead**

In `src/main/java/com/recsys/api/serving/RecSysServer.java`, replace:
```java
            ExecutorService executor = Executors.newFixedThreadPool(
                    Runtime.getRuntime().availableProcessors() * 2,
                    r -> new Thread(r, "recall-channel"));
```
with:
```java
            int recallPoolSize = Runtime.getRuntime().availableProcessors() * 2;
            WorkerBulkhead recallBulkhead = new WorkerBulkhead("recall-catalog", recallPoolSize,
                    EnvConfig.readInt("RECALL_BULKHEAD_QUEUE_CAPACITY", recallPoolSize * 4));
            ExecutorService executor = recallBulkhead.asExecutorService();
```
Add imports `import com.recsys.resilience.WorkerBulkhead;` (and `com.recsys.config.EnvConfig` if not already added in Task 2).

In `src/main/java/com/recsys/api/online/OnlinePredictionServer.java`, do the same swap for its recall executor (around line 84-86), using bulkhead name `"recall-online"` and the same `RECALL_BULKHEAD_QUEUE_CAPACITY` default. Add the same imports.

- [ ] **Step 6: Full suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test 2>&1 | grep -E 'Tests run: [0-9]+, Failures: [0-9]+, Errors|BUILD' | tail -3`
Expected: `Failures: 0, Errors: 0`, BUILD SUCCESS. (Known pre-existing flakes `RedisRateLimiterTest.tryAcquire_localPreCheck_fallsBackToRedisAboveThreshold` and `CognitoJwtVerifierTest.verify_rejectsTamperedSignature` — rerun once if they flake.)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallService.java \
        src/main/java/com/recsys/api/serving/RecSysServer.java \
        src/main/java/com/recsys/api/online/OnlinePredictionServer.java \
        src/test/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallServiceBulkheadTest.java
git commit -m "feat(recall): bound recall work with WorkerBulkhead, shed overflow per channel

Replace the unbounded fixed thread pools on 6010/7010 with WorkerBulkhead
(bounded queue, RECALL_BULKHEAD_QUEUE_CAPACITY=poolSize*4). A saturated queue
now degrades the rejected channel to an empty result + health backoff instead
of throwing out of the request.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Config keys + documentation

Add the new keys and document the existing limit values (docs-only, no numeric changes to existing keys).

**Files:**
- Modify: `k8s/base/configmap.yaml`
- Create: `docs/runbooks/overload-protection.md`
- Modify: `.claude/CLAUDE.md` (env-vars / architecture note — add the new keys + runbook link)

**Interfaces:**
- Consumes: Tasks 2-3 (the new env keys).
- Produces: `CATALOG_MAX_CONCURRENT_REQUESTS`, `CATALOG_DRAIN_UTILIZATION`, `RECALL_BULKHEAD_QUEUE_CAPACITY` in the configmap; a runbook explaining the full stack.

- [ ] **Step 1: Add the new keys with rationale comments**

In `k8s/base/configmap.yaml`, add near the existing rate-limit/load-shed keys:
```yaml
  # RecSys Serving (6010) concurrency admission (mirrors ONLINE_MAX_CONCURRENT_REQUESTS).
  # Guards the recall/ranking routes (/getrecommendation, /v2/recommend, /similar); 429 above the cap.
  CATALOG_MAX_CONCURRENT_REQUESTS: "64"
  CATALOG_DRAIN_UTILIZATION: "0.90"
  # Bounded backlog for the recall thread pool on 6010 & 7010; overflow sheds per channel.
  # Default = recall poolSize * 4 (poolSize = availableProcessors * 2). Set to override.
  RECALL_BULKHEAD_QUEUE_CAPACITY: "0"
```
Note: leave `RECALL_BULKHEAD_QUEUE_CAPACITY: "0"` as a sentinel meaning "use the code default (poolSize*4)"? NO — the code uses `readInt(name, poolSize*4)`, so an explicit `0` would set capacity 0. Instead OMIT the key from the configmap (so the code default applies) OR set it to a concrete value. Decision: OMIT `RECALL_BULKHEAD_QUEUE_CAPACITY` from the configmap (rely on the code default) and only document it in the runbook as an override knob. Remove the `RECALL_BULKHEAD_QUEUE_CAPACITY` line above; keep only the two `CATALOG_*` keys.

- [ ] **Step 2: Add rationale comments to the existing limit keys**

In `k8s/base/configmap.yaml`, add a short comment above the existing block documenting shape (do not change values):
```yaml
  # --- Overload protection (see docs/runbooks/overload-protection.md) ---
  # ONLINE_REDIS_RATE_LIMIT_QPS: single GLOBAL cluster-wide ceiling (fixed 1s window; up to
  #   ~2x across a window boundary). GATEWAY_RATE_LIMIT_*: per authenticated caller PER route.
  #   Concurrency gates (*_MAX_CONCURRENT_REQUESTS) are PER instance. Values need load-test validation.
```
(Place it directly above the `GATEWAY_RATE_LIMIT_RPS` line; keep every existing value unchanged.)

- [ ] **Step 3: Write the runbook**

Create `docs/runbooks/overload-protection.md`:
```markdown
# Runbook: Overload Protection & Rate Limits

The system sheds overload in layers. This documents what each layer protects, its shape,
and how to tune it. Design: `docs/superpowers/specs/2026-07-08-overload-protection-design.md`.

## Layers

| Layer | Mechanism | Scope | Config | Response |
|---|---|---|---|---|
| Gateway 8010 | per-route × per-caller token bucket | per instance | `GATEWAY_RATE_LIMIT_RPS`=100 / `_BURST`=200 (model 50/100) | 429 + Retry-After |
| Gateway 8010 | per-route circuit breaker | per instance | `GATEWAY_CB_FAILURE_THRESHOLD`=5 / `_COOLDOWN_MS`=10000 | 503 |
| Online 7010 | concurrency admission | per instance | `ONLINE_MAX_CONCURRENT_REQUESTS`=64 / `ONLINE_DRAIN_UTILIZATION`=0.90 | 429 |
| Online 7010 | Redis fixed-window QPS | **cluster-wide (global)** | `ONLINE_REDIS_RATE_LIMIT_QPS`=200 / `_WINDOW_SECONDS`=1 | 429 |
| Model 8080 | per-user token bucket + semaphore | per instance | `recsys.model.rate-limit.*`, `RECSYS_HEALTH_MAX_CONCURRENT_REQUESTS`=64 | 429 / 503 (degrade-to-cache first) |
| RecSys 6010 | concurrency admission | per instance | `CATALOG_MAX_CONCURRENT_REQUESTS`=64 / `CATALOG_DRAIN_UTILIZATION`=0.90 | 429 |
| 6010 & 7010 | recall WorkerBulkhead (bounded queue) | per instance | `RECALL_BULKHEAD_QUEUE_CAPACITY` (default poolSize×4) | per-channel empty result |
| all serving | request/recall timeouts | per request | `ONLINE_REQUEST_TIMEOUT_MS`=500, `RECALL_CHANNEL_TIMEOUT_MS`=200 | bounded work |

## Key caveats

- **Online QPS is a single GLOBAL ceiling** (bucket `rate:online:global`), not per-caller —
  200 QPS is the total across all online-serving instances. The gateway limits, by contrast,
  are per authenticated caller per route.
- **Fixed-window boundary burst:** the online limiter can admit up to ~2× the limit across a
  1s window boundary. Acceptable as a coarse safety ceiling; use a token bucket if smoothness
  matters.
- **Concurrency gates are per instance** — aggregate cluster concurrency = perInstance × replicas.
- **Rate limiters fail open** (disabled at 0, and allow on Redis error). **Load shedders are
  always on** and reject when the concurrency counter is full.

## Tuning

All values above are starting points, not load-validated. To tune: run a load test to find the
knee (latency/error inflection) per service, set the concurrency gate just below it, and set the
online global QPS to the aggregate sustainable throughput. Raise `RECALL_BULKHEAD_QUEUE_CAPACITY`
if normal bursts cause premature per-channel shedding; lower it to fail faster under sustained
overload.
```

- [ ] **Step 4: Link from CLAUDE.md**

In `.claude/CLAUDE.md`, add the new env vars to the "Key env vars" list and a pointer:
```markdown
`CATALOG_MAX_CONCURRENT_REQUESTS`/`CATALOG_DRAIN_UTILIZATION` (RecSys 6010 admission control),
`RECALL_BULKHEAD_QUEUE_CAPACITY` (bounded recall queue on 6010/7010). Overload-protection layers
are documented in `docs/runbooks/overload-protection.md`.
```

- [ ] **Step 5: Render + commit**

```bash
kubectl kustomize k8s/base | grep -E 'CATALOG_MAX_CONCURRENT_REQUESTS|CATALOG_DRAIN_UTILIZATION'
```
Expected: both keys render.
```bash
git add k8s/base/configmap.yaml docs/runbooks/overload-protection.md .claude/CLAUDE.md
git commit -m "docs(overload): add CATALOG_* admission keys + overload-protection runbook

Document the existing rate-limit/load-shed values (shapes, caveats, tuning) and
add the new RecSys 6010 admission keys. No existing numeric values changed.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Final verification + PR

**Files:** none.

- [ ] **Step 1: Full suite + renders**

Run:
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test 2>&1 | grep -E 'Tests run: [0-9]+, Failures: [0-9]+, Errors|BUILD SUCCESS|BUILD FAILURE' | tail -3
kubectl kustomize k8s/base >/dev/null && echo "base OK"
```
Expected: `Failures: 0, Errors: 0`, BUILD SUCCESS, base OK.

- [ ] **Step 2: Confirm the deliverables**

Run:
```bash
grep -rc 'OnlineAdmissionControl' src/main/java/com/recsys/api/serving/RecSysServer.java   # >=4 (guarded routes)
grep -rc 'WorkerBulkhead' src/main/java/com/recsys/api/serving/RecSysServer.java src/main/java/com/recsys/api/online/OnlinePredictionServer.java  # both wired
grep -n 'RejectedExecutionException' src/main/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallService.java  # graceful handling present
kubectl kustomize k8s/base | grep -c 'CATALOG_MAX_CONCURRENT_REQUESTS'  # 1
```
Expected: RecSysServer references the decorator on the guarded routes; both servers reference WorkerBulkhead; the recall service catches `RejectedExecutionException`; the config key renders.

- [ ] **Step 3: Push + open PR**

```bash
git push -u origin feat/overload-protection
gh pr create --fill --base main
```
Expected: PR against `main`. Do not merge directly.

---

## Self-Review

**Spec coverage:**
- RecSys 6010 concurrency admission (429 + Retry-After, expensive routes only, drain readiness) → Tasks 1-2. ✓
- Reuse `OnlineLoadShedder`, generalize decorator without changing 7010 → Task 1 (delegating ctor). ✓
- WorkerBulkhead on 6010 & 7010 with graceful per-channel rejection → Task 3. ✓
- `RECALL_BULKHEAD_QUEUE_CAPACITY` default poolSize×4 → Task 3 Step 5. ✓
- Document existing values, no numeric changes → Task 4 (docs-only; existing keys untouched). ✓
- Config keys `CATALOG_*` → Task 4. ✓
- Tests: admission gate, bulkhead graceful rejection, render → Tasks 1/3/4. ✓

**Placeholder scan:** No TBD/TODO. The bulkhead test skeleton (Task 3 Step 1) explicitly instructs reading `MultiChannelRecallServiceTest` first to reuse its real construction — the implementer fills the concrete builder calls from that existing test.

**Type/consistency:** `OnlineLoadShedder(int,double)` ctor reused for 6010; `OnlineAdmissionControl` gains a `Runnable onReject` ctor while the `OnlineServingMetricsService` ctor delegates (7010 unchanged); `RECALL_BULKHEAD_QUEUE_CAPACITY` read via `EnvConfig.readInt(name, poolSize*4)`; `CATALOG_*` defaults (64, 0.90) consistent across Task 2 code and Task 4 config. Task 4 Step 1 note resolves the `RECALL_BULKHEAD_QUEUE_CAPACITY` sentinel ambiguity by OMITTING it from the configmap (code default applies).
