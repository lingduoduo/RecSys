# Fault-Tolerance Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bound degraded traffic, make circuit recovery concurrency-safe, expose recommendation degradation explicitly, continuously verify resilience, and make DR capacity promotion safe and auditable.

**Architecture:** Preserve the existing availability-first design and add containment at its existing seams. Runtime work extends the shared circuit breaker with generation-bound permits, places an existing token bucket behind Redis fail-open decisions, and carries a bounded degradation enum alongside recall results. Build and operations work add Maven/GitHub verification plus an idempotent DR checker that never mutates data-tier roles or public traffic.

**Tech Stack:** Java 17, JUnit 5, AssertJ, Mockito, Micrometer, Maven Surefire/Enforcer, Armeria, Bash, Python 3 standard library, kubectl/kustomize, GitHub Actions.

## Global Constraints

- Preserve availability-first behavior; degraded dependency paths are bounded fail-open, not globally fail-closed.
- Add no production runtime library or infrastructure service.
- Preserve healthy-path status codes, response bodies, headers, and materially unchanged latency.
- Emergency rejection uses the existing HTTP `429` plus positive `Retry-After` contract.
- Metrics use bounded tags only; never tag by bucket, principal, user, or channel name.
- Traffic switching and data-tier promotion remain explicit operator actions.
- Performance invariants may fail CI; shared-runner absolute latency and throughput values are report-only.
- Every Java behavior change follows red-green-refactor and every asynchronous test has an explicit deadline.

---

## File Structure

### Runtime

- Modify `src/main/java/com/recsys/resilience/CircuitBreaker.java` — generation-bound acquisition permits and completion.
- Modify `src/main/java/com/recsys/resilience/RouteCircuitBreaker.java` — preserve its boolean public API while retaining permits per request at the gateway call site.
- Modify `src/main/java/com/recsys/application/gateway/GatewayRequestForwarder.java` — carry the route permit from acquire to completion.
- Modify `src/main/java/com/recsys/ratelimit/RedisRateLimiter.java` — emergency local limiter, permit-aware circuit completion, counters, and expanded snapshot.
- Modify `src/main/java/com/recsys/api/online/OnlinePredictionServer.java` — inject monotonic clock and register bounded metrics.
- Modify `k8s/base/configmap.yaml` — explicit emergency-limit production defaults.
- Modify `src/main/java/com/recsys/application/retrieval/multichannel/RecallResult.java` — bounded degradation outcome.
- Modify `src/main/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallService.java` — derive degradation outcome from attempts, failures, and candidates.
- Modify `src/main/java/com/recsys/application/retrieval/multichannel/RecallDegradationMetrics.java` — aggregate outcome counters.
- Modify `src/main/java/com/recsys/api/serving/BaseApiService.java` — emit `X-Recall-Degradation-Reason`.
- Modify `src/main/java/com/recsys/api/serving/RecommendationService.java` — pass degradation outcome through V1/V2.
- Modify `src/main/java/com/recsys/application/recommendation/RecommendationOrchestrator.java` — retain bounded degradation reason in the V2 trace.
- Modify `src/main/java/com/recsys/api/rest/RecommendationController.java` — mark overload cache recovery as `fallback`.

### Build and CI

- Modify `pom.xml` — dependency management, Enforcer convergence, resilience profile.
- Create `.github/workflows/resilience-pr.yml` — deterministic pull-request gate.
- Create `.github/workflows/resilience-scheduled.yml` — bounded scheduled/manual load and Docker jobs.
- Create `scripts/summarize-resilience-results.py` — machine-readable Surefire/characterization summary.

### DR

- Modify `scripts/dr-standby-capacity.sh` — context/region validation, idempotent convergence, readiness checks, cutover/failback checks, JSON report.
- Create `scripts/test-dr-standby-capacity.sh` — hermetic shell tests using a fake `kubectl`.
- Modify `docs/runbooks/dr-regional-failover.md`, `docs/runbooks/dr-failback.md`, and `docs/runbooks/dr-game-day.md` — evidence-driven operator workflow.
- Modify `docs/system_design/18_Fault_Tolerance.md` and `CONFIG_GUIDE.md` — runtime and CI contracts.

---

### Task 1: Generation-Bound Circuit-Breaker Permits

**Files:**

- Modify: `src/main/java/com/recsys/resilience/CircuitBreaker.java`
- Test: `src/test/java/com/recsys/resilience/CircuitBreakerTest.java`

**Interfaces:**

- Produces: `CircuitBreaker.Permit tryAcquirePermit()`
- Produces: `void recordSuccess(CircuitBreaker.Permit permit)`
- Produces: `void recordFailure(CircuitBreaker.Permit permit)`
- Preserves temporarily: `boolean tryAcquire()`, `void recordSuccess()`, `void recordFailure()` for source compatibility until Task 2 migrates callers.

- [x] **Step 1: Write the stale-success failing test**

Add a deterministic generation test to `CircuitBreakerTest`:

```java
@Test
void staleClosedSuccessCannotCloseHalfOpenGeneration() {
    AtomicLong clock = new AtomicLong();
    CircuitBreaker cb = new CircuitBreaker(1, 100L, clock::get);

    CircuitBreaker.Permit stale = cb.tryAcquirePermit();
    cb.recordFailure(stale);                 // generation 0 opens generation 1
    clock.set(100L);
    CircuitBreaker.Permit probe = cb.tryAcquirePermit();
    assertThat(probe).isNotNull();

    cb.recordSuccess(stale);                 // late completion from generation 0

    assertThat(cb.state()).isEqualTo(HALF_OPEN);
    assertThat(cb.tryAcquirePermit()).isNull();
    cb.recordFailure(probe);
    assertThat(cb.state()).isEqualTo(OPEN);
}
```

- [x] **Step 2: Run the focused test and verify RED**

Run:

```bash
mvn test -Dtest=CircuitBreakerTest#staleClosedSuccessCannotCloseHalfOpenGeneration
```

Expected: test compilation fails because `Permit`, `tryAcquirePermit`, and permit-aware completion do not exist.

- [x] **Step 3: Add the minimal permit/generation state**

Implement in `CircuitBreaker`:

```java
public record Permit(long generation, boolean probe) {}

private final AtomicLong generation = new AtomicLong();
private final AtomicLong probeGeneration = new AtomicLong(-1L);

public Permit tryAcquirePermit() {
    State current = state();
    long observed = generation.get();
    if (current == State.CLOSED) return new Permit(observed, false);
    if (current == State.OPEN) return null;
    return probeGeneration.compareAndSet(-1L, observed)
            ? new Permit(observed, true)
            : null;
}

public void recordSuccess(Permit permit) {
    if (permit == null || permit.generation() != generation.get()) return;
    if (state() == State.HALF_OPEN && !permit.probe()) return;
    consecutiveFailures.set(0);
    probeGeneration.set(-1L);
}

public void recordFailure(Permit permit) {
    if (permit == null || permit.generation() != generation.get()) return;
    State before = state();
    if (before == State.HALF_OPEN && !permit.probe()) return;
    if (consecutiveFailures.incrementAndGet() >= failureThreshold
            && generation.compareAndSet(permit.generation(), permit.generation() + 1)) {
        openedAtMs.set(clockMs.getAsLong());
        probeGeneration.set(-1L);
    }
}
```

Before setting `openedAtMs`, re-check that the generation CAS succeeded. This
makes exactly one completion own each CLOSED→OPEN or HALF_OPEN→OPEN transition.

- [x] **Step 4: Add remaining concurrency contract tests**

Add tests proving:

```java
@Test
void onlyOnePermitOwnsHalfOpenProbe() { /* two calls: one non-null, one null */ }

@Test
void staleFailureCannotReopenClosedRecoveredGeneration() { /* probe succeeds, stale failure ignored */ }

@Test
void currentProbeSuccessClosesAndAllowsNewClosedPermit() { /* state CLOSED */ }
```

Use only the injected `AtomicLong` clock; do not use `Thread.sleep`.

- [x] **Step 5: Run the circuit suite and verify GREEN**

Run:

```bash
mvn test -Dtest=CircuitBreakerTest
```

Expected: all tests pass.

- [x] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/resilience/CircuitBreaker.java \
  src/test/java/com/recsys/resilience/CircuitBreakerTest.java
git commit -m "fix: bind circuit recovery to probe generation"
```

---

### Task 2: Migrate Circuit-Breaker Callers to Permit Ownership

**Files:**

- Modify: `src/main/java/com/recsys/resilience/RouteCircuitBreaker.java`
- Modify: `src/main/java/com/recsys/application/gateway/GatewayRequestForwarder.java`
- Modify: `src/main/java/com/recsys/ratelimit/RedisRateLimiter.java`
- Test: `src/test/java/com/recsys/resilience/RouteCircuitBreakerTest.java`
- Test: `src/test/java/com/recsys/application/gateway/GatewayRequestForwarderTest.java`
- Test: `src/test/java/com/recsys/ratelimit/RedisRateLimiterTest.java`

**Interfaces:**

- Consumes: `CircuitBreaker.Permit`
- Produces: `RouteCircuitBreaker.Permit`, an opaque wrapper around the shared permit.
- Produces: `RouteCircuitBreaker.Permit tryAcquirePermit()`, `recordSuccess(Permit)`, and `recordFailure(Permit)`.

- [x] **Step 1: Write a route stale-completion characterization test**

Add a test that obtains a route permit, opens the route, advances the injected
clock through cooldown, admits the recovery probe, then records the stale
success and asserts the route remains HALF_OPEN. If the current route class
lacks an injectable clock constructor, make the test fail on that missing seam
first.

- [x] **Step 2: Verify the route test fails**

Run:

```bash
mvn test -Dtest=RouteCircuitBreakerTest
```

Expected: failure because route permits and/or injected clock support are absent.

- [x] **Step 3: Add the opaque route permit API**

Implement:

```java
public record Permit(CircuitBreaker.Permit delegate) {
    public Permit { java.util.Objects.requireNonNull(delegate, "delegate"); }
}

public Permit tryAcquirePermit() {
    CircuitBreaker.Permit permit = delegate.tryAcquirePermit();
    return permit == null ? null : new Permit(permit);
}

public void recordSuccess(Permit permit) { delegate.recordSuccess(permit.delegate()); }
public void recordFailure(Permit permit) { delegate.recordFailure(permit.delegate()); }
```

Add a package-private constructor accepting `LongSupplier clockMs` for tests.

- [x] **Step 4: Carry the route permit through gateway forwarding**

At the existing gateway circuit gate, replace the boolean acquire/completion
pair with:

```java
RouteCircuitBreaker.Permit permit = breaker.tryAcquirePermit();
if (permit == null) {
    return unavailable("circuit open");
}
```

Every terminal upstream response or failure records exactly once using that
same permit. A `5xx` or transport failure records failure; a non-`5xx` response
records success, preserving current classification.

- [x] **Step 5: Migrate Redis to permit-aware completion**

In `RedisRateLimiter.tryAcquire`, hold:

```java
CircuitBreaker.Permit permit = circuit.tryAcquirePermit();
```

Pass `permit` to `recordSuccess` or `recordFailure`. Do not add emergency
limiting yet; that is Task 3.

- [x] **Step 6: Remove the compatibility completion methods**

After `rg -n "recordSuccess\\(\\)|recordFailure\\(\\)|tryAcquire\\(\\)"` confirms
no circuit callers use the old API, delete the no-argument compatibility
methods from `CircuitBreaker` and boolean-only wrappers from
`RouteCircuitBreaker`.

- [x] **Step 7: Run adjacent tests**

Run:

```bash
mvn test -Dtest='CircuitBreakerTest,RouteCircuitBreakerTest,GatewayRequestForwarderTest,RedisRateLimiterTest'
```

Expected: all pass, with no sleep-based half-open test remaining in
`RedisRateLimiterTest`.

- [x] **Step 8: Commit**

```bash
git add src/main/java/com/recsys/resilience/CircuitBreaker.java \
  src/main/java/com/recsys/resilience/RouteCircuitBreaker.java \
  src/main/java/com/recsys/application/gateway/GatewayRequestForwarder.java \
  src/main/java/com/recsys/ratelimit/RedisRateLimiter.java \
  src/test/java/com/recsys/resilience/RouteCircuitBreakerTest.java \
  src/test/java/com/recsys/application/gateway/GatewayRequestForwarderTest.java \
  src/test/java/com/recsys/ratelimit/RedisRateLimiterTest.java
git commit -m "refactor: carry circuit permits through callers"
```

---

### Task 3: Bound Redis Fail-Open Traffic

**Files:**

- Modify: `src/main/java/com/recsys/ratelimit/RedisRateLimiter.java`
- Modify: `src/main/java/com/recsys/application/online/OnlineServices.java`
- Modify: `k8s/base/configmap.yaml`
- Test: `src/test/java/com/recsys/ratelimit/RedisRateLimiterTest.java`
- Test: `src/test/java/com/recsys/application/online/OnlineServicesTest.java`

**Interfaces:**

- Produces: `Decision(boolean allowed, long remaining, int retryAfterSeconds, boolean failOpen, Source source)`
- Produces: `enum Source { DISABLED, REDIS, EMERGENCY }`
- Produces constructor seam accepting `double emergencyRatePerSecond`, `int emergencyBurst`, `LongSupplier tickerNanos`, and circuit clock.

- [x] **Step 1: Write the emergency exhaustion test**

Use an injected monotonic clock and Redis executor that always throws:

```java
@Test
void redisFailureUsesBoundedEmergencyBudget() {
    AtomicLong ticker = new AtomicLong();
    RedisExecutor exec = failingRedis("redis down");
    RedisRateLimiter limiter = limiter(exec, 100L, 1, 2.0, 2, ticker::get);

    assertThat(limiter.tryAcquire("online").allowed()).isTrue();
    assertThat(limiter.tryAcquire("online").allowed()).isTrue();
    RedisRateLimiter.Decision rejected = limiter.tryAcquire("online");

    assertThat(rejected.allowed()).isFalse();
    assertThat(rejected.failOpen()).isTrue();
    assertThat(rejected.source()).isEqualTo(RedisRateLimiter.Source.EMERGENCY);
    assertThat(rejected.retryAfterSeconds()).isPositive();
}
```

- [x] **Step 2: Verify RED**

Run:

```bash
mvn test -Dtest=RedisRateLimiterTest#redisFailureUsesBoundedEmergencyBudget
```

Expected: compile failure because emergency configuration and `Source` are absent.

- [x] **Step 3: Add emergency configuration and validation**

Production environment variables:

```text
ONLINE_REDIS_EMERGENCY_RATE_PER_SECOND
ONLINE_REDIS_EMERGENCY_BURST
ONLINE_REDIS_EMERGENCY_LIMIT_ENABLED
```

Defaults:

```text
rate = max(1, ONLINE_REDIS_RATE_LIMIT_QPS / 4)
burst = max(1, rate)
enabled = true when Redis limiting is enabled
```

Use a strict local parser in `RedisRateLimiter` for these safety values:
negative rate/burst throws `IllegalArgumentException`; `0` explicitly disables
emergency limiting for rollback. Do not use `EnvConfig`'s parse-with-default
behavior for invalid configured values.

- [x] **Step 4: Set explicit Kubernetes defaults**

Add to `k8s/base/configmap.yaml` beside the global rate-limit settings:

```yaml
ONLINE_REDIS_EMERGENCY_LIMIT_ENABLED: "true"
ONLINE_REDIS_EMERGENCY_RATE_PER_SECOND: "50"
ONLINE_REDIS_EMERGENCY_BURST: "50"
```

The base global limit is `200`, so the emergency default is the specified
conservative quarter-rate. Existing `envFrom` wiring supplies these values to
online-serving without a Deployment change.

- [x] **Step 5: Add the emergency decision path**

Add one `TokenBucket` per limiter instance. Implement:

```java
private Decision emergencyDecision() {
    if (!emergencyEnabled) return Decision.allowed(limit, 0, true, Source.EMERGENCY);
    TokenBucket.Decision local = emergencyBucket.tryAcquire();
    int retry = local.allowed() ? 0
            : Math.max(1, (int) Math.ceil(local.retryAfter().toMillis() / 1000.0));
    return new Decision(local.allowed(), local.remaining(), retry, true, Source.EMERGENCY);
}
```

Use this method for executor exceptions, malformed Redis replies, an OPEN
circuit, and callers that lose the HALF_OPEN probe race.

- [x] **Step 6: Add refill, disabled, and authoritative-path tests**

Tests must prove:

- monotonic time refills exactly as configured;
- Redis allow/reject decisions use `Source.REDIS` and never consume emergency tokens;
- global limiting disabled uses `Source.DISABLED`;
- emergency limiting explicitly disabled restores unlimited fail-open;
- OPEN circuit makes no Redis call but consumes emergency tokens;
- malformed Redis result is treated as emergency, not unlimited allowed.

- [x] **Step 7: Pin the HTTP rejection contract**

Extend `OnlineServicesTest` so an emergency rejection returns:

```java
assertThat(response.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
assertThat(response.headers().get(HttpHeaderNames.RETRY_AFTER)).isEqualTo("1");
assertThat(response.contentUtf8()).contains("online serving rate limited");
```

- [x] **Step 8: Run focused suites**

Run:

```bash
mvn test -Dtest='RedisRateLimiterTest,OnlineServicesTest'
```

Expected: all pass.

- [x] **Step 9: Commit**

```bash
git add src/main/java/com/recsys/ratelimit/RedisRateLimiter.java \
  src/main/java/com/recsys/application/online/OnlineServices.java \
  k8s/base/configmap.yaml \
  src/test/java/com/recsys/ratelimit/RedisRateLimiterTest.java \
  src/test/java/com/recsys/application/online/OnlineServicesTest.java
git commit -m "feat: bound Redis fail-open traffic locally"
```

---

### Task 4: Expose Emergency-Limiter Metrics and Operations State

**Files:**

- Modify: `src/main/java/com/recsys/ratelimit/RedisRateLimiter.java`
- Modify: `src/main/java/com/recsys/api/online/OnlinePredictionServer.java`
- Modify: `src/main/java/com/recsys/health/OnlineOpsService.java`
- Test: `src/test/java/com/recsys/ratelimit/RedisRateLimiterTest.java`
- Test: `src/test/java/com/recsys/health/OnlineOpsServiceTest.java`

**Interfaces:**

- Produces: `void registerMetrics(MeterRegistry registry)`
- Extends `RedisRateLimiter.Snapshot` with emergency configuration and four cumulative outcome counters.

- [x] **Step 1: Write bounded-metric failing tests**

Use `SimpleMeterRegistry` and assert these meters:

```text
recsys_online_rate_limit_decisions_total{source="redis",result="allowed"}
recsys_online_rate_limit_decisions_total{source="redis",result="rejected"}
recsys_online_rate_limit_decisions_total{source="emergency",result="allowed"}
recsys_online_rate_limit_decisions_total{source="emergency",result="rejected"}
```

Assert the only tag keys are `source` and `result`. Assert repeated
`registerMetrics(registry)` does not create duplicate meters.

- [x] **Step 2: Verify RED**

Run:

```bash
mvn test -Dtest=RedisRateLimiterTest
```

Expected: failure because metric registration and counters are absent.

- [x] **Step 3: Implement counters and idempotent registration**

Keep four `LongAdder` fields in the limiter and increment exactly once per
returned decision. Register `FunctionCounter`s from those adders with the four
fixed tag combinations. Do not tag the request bucket.

Extend `Snapshot` with:

```java
boolean emergencyEnabled,
double emergencyRatePerSecond,
int emergencyBurst,
long redisAllowed,
long redisRejected,
long emergencyAllowed,
long emergencyRejected
```

- [x] **Step 4: Wire production registry**

Change:

```java
RedisRateLimiter redisRateLimiter = new RedisRateLimiter(jedisPool);
redisRateLimiter.registerMetrics(registry);
```

in `OnlinePredictionServer`.

- [x] **Step 5: Pin `/online/ops` JSON**

Extend `OnlineOpsServiceTest` to assert `rateLimit` contains the emergency
configuration, counters, and `circuitState`, and contains no bucket/principal
map.

- [x] **Step 6: Run focused tests**

Run:

```bash
mvn test -Dtest='RedisRateLimiterTest,OnlineOpsServiceTest,OnlinePredictionServerWiringTest'
```

Use the existing
`src/test/java/com/recsys/api/online/OnlinePredictionServerIntegrationTest.java`
as the server wiring test.

Expected: all selected tests pass.

- [x] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/ratelimit/RedisRateLimiter.java \
  src/main/java/com/recsys/api/online/OnlinePredictionServer.java \
  src/main/java/com/recsys/health/OnlineOpsService.java \
  src/test/java/com/recsys/ratelimit/RedisRateLimiterTest.java \
  src/test/java/com/recsys/health/OnlineOpsServiceTest.java
git commit -m "feat: expose bounded fail-open telemetry"
```

---

### Task 5: Make Recall Degradation Outcomes Explicit

**Files:**

- Modify: `src/main/java/com/recsys/application/retrieval/multichannel/RecallResult.java`
- Modify: `src/main/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallService.java`
- Modify: `src/main/java/com/recsys/application/retrieval/multichannel/RecallDegradationMetrics.java`
- Modify: `src/main/java/com/recsys/api/serving/BaseApiService.java`
- Modify: `src/main/java/com/recsys/api/serving/RecommendationService.java`
- Modify: `src/main/java/com/recsys/application/recommendation/RecommendationOrchestrator.java`
- Modify: `src/main/java/com/recsys/api/rest/RecommendationController.java`
- Test: `src/test/java/com/recsys/application/retrieval/multichannel/RecallResultTest.java`
- Test: `src/test/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallDegradationTest.java`
- Test: `src/test/java/com/recsys/application/retrieval/multichannel/RecallDegradationMetricsTest.java`
- Test: `src/test/java/com/recsys/api/serving/RecommendationV1DegradedHeaderTest.java`
- Test: `src/test/java/com/recsys/api/serving/RecommendationV2DegradedHeaderTest.java`
- Test: `src/test/java/com/recsys/application/recommendation/RecommendationOrchestratorDegradedTest.java`
- Test: `src/test/java/com/recsys/api/rest/RecommendationControllerTest.java`

**Interfaces:**

- Produces: `enum DegradationOutcome { HEALTHY, PARTIAL, ALL_CHANNELS, FALLBACK }`
- Produces: `RecallResult(..., DegradationOutcome outcome)`
- Produces header: `X-Recall-Degradation-Reason: partial|all_channels|fallback`

- [x] **Step 1: Write `RecallResult` outcome tests**

Add tests for explicit outcome and constructor invariants:

```java
assertThat(new RecallResult(List.of(), Set.of(), HEALTHY).outcome()).isEqualTo(HEALTHY);
assertThatThrownBy(() -> new RecallResult(List.of(), Set.of("x"), HEALTHY))
    .isInstanceOf(IllegalArgumentException.class);
```

Retain a two-argument compatibility constructor that derives `PARTIAL` for
non-empty degraded channels only during migration.

- [x] **Step 2: Verify RED**

Run:

```bash
mvn test -Dtest=RecallResultTest
```

Expected: compile failure because `DegradationOutcome` and the third component are absent.

- [x] **Step 3: Derive outcomes in recall**

Track `attemptedChannels` and successful channel results:

```java
DegradationOutcome outcome;
if (degradedChannels.isEmpty()) {
    outcome = DegradationOutcome.HEALTHY;
} else if (channelResults.isEmpty()) {
    outcome = DegradationOutcome.ALL_CHANNELS;
} else {
    outcome = DegradationOutcome.PARTIAL;
}
```

`MultiChannelRecallService` produces `HEALTHY`, `PARTIAL`, or `ALL_CHANNELS`.
It does not produce `FALLBACK`, because an empty user embedding alone is not
proof of degraded recovery. The Spring overload-cache branch in
`RecommendationController` is the component that produces the `fallback` wire
reason because it already proves recovery through `tryServeFromCache`.

- [x] **Step 4: Add aggregate outcome metrics**

Add a fixed `EnumMap<DegradationOutcome, LongAdder>` and expose counts in
`RecallDegradationMetrics.Snapshot`. Preserve existing per-channel operational
snapshot data, but register Micrometer metrics only by the bounded `outcome`
enum.

- [x] **Step 5: Extend the response helper**

Change the helper signature to:

```java
writeJsonWithRecallDegraded(
    HttpStatus status,
    Object payload,
    Set<String> degradedChannels,
    DegradationOutcome outcome)
```

Rules:

- `X-Recall-Degraded` remains the sorted channel CSV when channels degraded.
- `X-Recall-Degradation-Reason` is absent for `HEALTHY`.
- Other values are lowercase enum names, with `ALL_CHANNELS` serialized as
  `all_channels`.

- [x] **Step 6: Carry outcome through V1 and V2**

V1 passes `recall.outcome()` directly. The orchestrator adds:

```java
trace.put("degradationOutcome", recall.outcome().wireValue());
```

only when non-healthy. V2 reads that bounded value rather than re-inferring it
from item count.

- [x] **Step 7: Mark overload-cache recovery**

In the existing `tryServeFromCache` success branch, preserve:

```text
X-Served-From: degraded-cache
```

and add:

```text
X-Recall-Degradation-Reason: fallback
```

Extend `RecommendationControllerTest` to assert both headers and the unchanged
`200` body.

- [x] **Step 8: Prove healthy-empty versus degraded-empty**

Tests must assert:

- healthy empty: no degradation headers;
- partial result: both headers, reason `partial`;
- every attempted channel failed: status `200`, old header present, reason
  `all_channels`;
- fallback-only recovery: status `200`, reason `fallback`;
- channel names stay sorted and never appear as metric tags.

- [x] **Step 9: Run focused degradation suites**

Run:

```bash
mvn test -Dtest='RecallResultTest,MultiChannelRecallDegradationTest,RecallDegradationMetricsTest,RecommendationV1DegradedHeaderTest,RecommendationV2DegradedHeaderTest,RecommendationOrchestratorDegradedTest,RecommendationControllerTest'
```

Expected: all pass.

- [x] **Step 10: Commit**

```bash
git add src/main/java/com/recsys/application/retrieval/multichannel \
  src/main/java/com/recsys/api/serving/BaseApiService.java \
  src/main/java/com/recsys/api/serving/RecommendationService.java \
  src/main/java/com/recsys/application/recommendation/RecommendationOrchestrator.java \
  src/main/java/com/recsys/api/rest/RecommendationController.java \
  src/test/java/com/recsys/application/retrieval/multichannel \
  src/test/java/com/recsys/api/serving/RecommendationV1DegradedHeaderTest.java \
  src/test/java/com/recsys/api/serving/RecommendationV2DegradedHeaderTest.java \
  src/test/java/com/recsys/application/recommendation/RecommendationOrchestratorDegradedTest.java \
  src/test/java/com/recsys/api/rest/RecommendationControllerTest.java
git commit -m "feat: distinguish recall degradation outcomes"
```

---

### Task 6: Enforce Dependency Convergence and the PR Resilience Profile

**Files:**

- Modify: `pom.xml`
- Create: `.github/workflows/resilience-pr.yml`

**Interfaces:**

- Produces Maven profile: `-Presilience`
- Produces validation gate: `maven-enforcer-plugin:enforce`

- [x] **Step 1: Capture the current Netty warning as a failing build check**

Run:

```bash
mvn -Dtest=GracefulServersTest test 2>&1 | tee /tmp/recsys-netty-before.log
rg "Inconsistent Netty versions detected" /tmp/recsys-netty-before.log
```

Expected: the warning is present. Save the observed versions in the commit
message or task notes; do not commit `/tmp` output.

- [x] **Step 2: Add Enforcer convergence**

Add `maven-enforcer-plugin` version `3.5.0` in the `validate` phase with:

```xml
<rules>
  <dependencyConvergence/>
  <requireJavaVersion>
    <version>[17,18)</version>
  </requireJavaVersion>
</rules>
```

Run `mvn validate`. Use its exact convergence paths to choose the narrowest
exclusions or dependency-management alignment. Prefer framework BOM management;
do not add individual Netty versions until the dependency tree proves a BOM
cannot converge them.

- [x] **Step 3: Verify warning removal**

Run:

```bash
mvn validate
mvn -Dtest=GracefulServersTest test 2>&1 | tee /tmp/recsys-netty-after.log
test -z "$(rg 'Inconsistent Netty versions detected' /tmp/recsys-netty-after.log || true)"
```

Expected: validation succeeds and no warning matches.

- [x] **Step 4: Add the resilience Maven profile**

Create a `resilience` profile that sets a Surefire include list for deterministic
resilience packages/classes and keeps `load,docker` excluded. Include:

```text
**/resilience/*Test.java
**/loadshed/*Test.java
**/ratelimit/RedisRateLimiterTest.java
**/retrieval/multichannel/*DegradationTest.java
**/retrieval/multichannel/*BulkheadTest.java
**/outbox/OutboxRelayTest.java
**/outbox/OutboxRetryPolicyTest.java
**/saga/*OrchestratorTest.java
**/*DegradedHeaderTest.java
```

Do not include integration classes requiring Docker.

- [x] **Step 5: Prove the profile**

Run:

```bash
mvn -Presilience test
```

Expected: the deterministic resilience set runs with zero failures and no
Docker startup.

- [x] **Step 6: Add the pull-request workflow**

Create `.github/workflows/resilience-pr.yml`:

```yaml
name: resilience-pr
on:
  pull_request:
  workflow_dispatch:
permissions:
  contents: read
jobs:
  deterministic:
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
          cache: maven
      - run: mvn --batch-mode validate
      - run: mvn --batch-mode -Presilience test
      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: resilience-pr-surefire
          path: target/surefire-reports/
```

- [x] **Step 7: Commit**

```bash
git add pom.xml .github/workflows/resilience-pr.yml
git commit -m "ci: require deterministic resilience checks"
```

---

### Task 7: Schedule Load/Chaos Verification and Publish Evidence

**Files:**

- Create: `scripts/summarize-resilience-results.py`
- Create: `.github/workflows/resilience-scheduled.yml`
- Create: `src/test/java/com/recsys/resilience/ResilienceEvidenceSchemaTest.java`

**Interfaces:**

- Produces: `resilience-evidence.json` schema version `1`.
- Consumes: Surefire XML from `target/surefire-reports`.

- [x] **Step 1: Write the evidence summarizer test**

Create a Java resource fixture or invoke the Python script from
`ResilienceEvidenceSchemaTest` with a temporary Surefire directory. Assert:

```json
{
  "schemaVersion": 1,
  "suite": "load",
  "environment": {},
  "tests": {"run": 2, "failed": 1, "errors": 0, "skipped": 0},
  "invariantsPassed": false
}
```

The production change that makes the test pass is the new summarizer script.

- [x] **Step 2: Verify RED**

Run:

```bash
mvn test -Dtest=ResilienceEvidenceSchemaTest
```

Expected: failure because `scripts/summarize-resilience-results.py` does not exist.

- [x] **Step 3: Implement the standard-library summarizer**

Use `argparse`, `json`, `platform`, and `xml.etree.ElementTree`. Required CLI:

```text
python3 scripts/summarize-resilience-results.py \
  --suite load \
  --reports target/surefire-reports \
  --output resilience-evidence.json
```

Aggregate `tests`, `failures`, `errors`, `skipped`, elapsed seconds, Java
version from `JAVA_VERSION`, runner OS, git SHA from `GITHUB_SHA`, and set
`invariantsPassed` only when failures and errors are zero. Missing reports is a
nonzero script error, not a passing empty result.

- [x] **Step 4: Verify GREEN**

Run:

```bash
mvn test -Dtest=ResilienceEvidenceSchemaTest
```

Expected: pass.

- [x] **Step 5: Add the scheduled/manual workflow**

Create two bounded jobs:

```yaml
on:
  schedule:
    - cron: "17 5 * * 2"
  workflow_dispatch:
jobs:
  load:
    timeout-minutes: 35
    steps:
      # checkout + Java 17
      - run: mvn --batch-mode test -DexcludedGroups=docker -Dgroups=load
      - if: always()
        run: python3 scripts/summarize-resilience-results.py --suite load --reports target/surefire-reports --output resilience-evidence-load.json
      # upload evidence + surefire
  docker:
    timeout-minutes: 35
    steps:
      # checkout + Java 17; hosted runner Docker is used
      - run: mvn --batch-mode test -DexcludedGroups=load -Dgroups=docker
      - if: always()
        run: python3 scripts/summarize-resilience-results.py --suite docker --reports target/surefire-reports --output resilience-evidence-docker.json
      # upload evidence + surefire
```

Set `permissions: contents: read`, use `actions/upload-artifact@v4`, and set
artifact retention to 30 days. The test commands enforce correctness; evidence
generation remains `if: always()`.

- [x] **Step 6: Validate workflow syntax and Maven selectors locally**

Run:

```bash
python3 -c 'import pathlib; p=pathlib.Path(".github/workflows/resilience-scheduled.yml"); assert "timeout-minutes" in p.read_text()'
mvn test -Dtest=ResilienceEvidenceSchemaTest
```

If `actionlint` is installed, also run:

```bash
actionlint .github/workflows/resilience-pr.yml .github/workflows/resilience-scheduled.yml
```

- [x] **Step 7: Commit**

```bash
git add scripts/summarize-resilience-results.py \
  .github/workflows/resilience-scheduled.yml \
  src/test/java/com/recsys/resilience/ResilienceEvidenceSchemaTest.java
git commit -m "ci: schedule bounded resilience verification"
```

---

### Task 8: Make DR Capacity Promotion Idempotent and Auditable

**Files:**

- Modify: `scripts/dr-standby-capacity.sh`
- Create: `scripts/test-dr-standby-capacity.sh`

**Interfaces:**

- Preserves: `promote|demote|verify`
- Adds: `cutover-check|failback-check`
- Adds common flags: `--context NAME`, `--region us-west-2`, `--report FILE`, `--dry-run`
- Produces JSON report schema version `1`.

- [x] **Step 1: Build a fake-kubectl shell harness**

Create `scripts/test-dr-standby-capacity.sh` using `mktemp -d` and a trap.
Prepend a fake `kubectl` to `PATH`; it records argv and returns fixture output
selected by `FAKE_KUBECTL_SCENARIO`.

First failing cases:

```bash
assert_fails "wrong context" promote --context prod-us-east-1 --region us-west-2
assert_fails "placeholder image" promote --context prod-us-west-2 --region us-west-2
assert_passes "already promoted" promote --context prod-us-west-2 --region us-west-2
assert_no_recorded_command "apply"   # already converged is idempotent
```

- [x] **Step 2: Verify RED**

Run:

```bash
bash scripts/test-dr-standby-capacity.sh
```

Expected: failure because context/region validation, image validation, and
idempotent state comparison are absent.

- [x] **Step 3: Refactor checks into explicit functions**

Add focused functions:

```bash
require_command
validate_context_region
render_overlay
validate_image_identity
desired_hpas
observed_hpas
apply_hpas_if_needed
check_rollout
check_ready_replicas
check_pdbs
check_dependencies
write_report
```

Use task-specific variables such as `DR_REPORT_PATH`; do not repurpose `HOME`.
All mutation remains HPA-only.

- [x] **Step 4: Implement idempotent promote/demote**

Compare desired HPA `(name,minReplicas)` pairs to live HPA values. If equal,
skip apply and report `capacityChange: "none"`. Otherwise apply HPA documents,
then wait with bounded commands:

```bash
kubectl --context "$DR_CONTEXT" rollout status deployment --all \
  --namespace recsys --timeout=5m
kubectl --context "$DR_CONTEXT" wait pod --all \
  --namespace recsys --for=condition=Ready --timeout=5m
```

Query PDB status and reject when `disruptionsAllowed`/healthy replica evidence
does not satisfy the target posture.

- [x] **Step 5: Add the JSON report**

Write through Python 3 stdlib so escaping is correct:

```json
{
  "schemaVersion": 1,
  "command": "promote",
  "timestamp": "ISO-8601 UTC",
  "targetRegion": "us-west-2",
  "context": "prod-us-west-2",
  "gitCommit": "...",
  "manifestDigest": "...",
  "checks": [{"name": "context-region", "passed": true, "observed": "..."}],
  "capacityChange": "applied|none|dry-run",
  "ready": true,
  "remainingOperatorActions": [
    "promote data tier using the approved runbook",
    "run cutover-check",
    "perform operator-confirmed traffic cutover"
  ]
}
```

Write reports atomically via a temporary file in the report's parent directory,
then `mv`.

- [x] **Step 6: Implement read-only cutover/failback checks**

`cutover-check` and `failback-check` must not call `kubectl apply`, AWS mutation,
DNS mutation, or database promotion. They accept evidence through explicit
environment/configured read-only probes and fail on unknown writer identity,
replication direction, health, or capacity.

The shell test records every fake command and fails if these modes contain:

```text
apply
patch
delete
route53
failover-global-cluster
promote-read-replica
```

- [x] **Step 7: Add remaining hermetic cases**

Cover:

- partial rollout fails and writes `ready:false`;
- unhealthy PDB fails;
- dependency probe failure fails;
- dry-run never applies;
- second promote is a no-op;
- report includes every required field;
- `verify` remains offline and checks active/base drift;
- demote restores only standby HPA floors.

- [x] **Step 8: Run shell verification**

Run:

```bash
bash scripts/test-dr-standby-capacity.sh
command -v shellcheck >/dev/null && shellcheck scripts/dr-standby-capacity.sh scripts/test-dr-standby-capacity.sh
scripts/dr-standby-capacity.sh verify
```

Expected: tests pass, shellcheck has no findings when installed, and overlay
drift verification passes.

- [x] **Step 9: Commit**

```bash
git add scripts/dr-standby-capacity.sh scripts/test-dr-standby-capacity.sh
git commit -m "feat: make DR capacity promotion auditable"
```

---

### Task 9: Document Operations and Run Final Verification

**Files:**

- Modify: `CONFIG_GUIDE.md`
- Modify: `docs/system_design/18_Fault_Tolerance.md`
- Modify: `docs/runbooks/dr-regional-failover.md`
- Modify: `docs/runbooks/dr-failback.md`
- Modify: `docs/runbooks/dr-game-day.md`

**Interfaces:**

- Documents the exact environment variables, response headers, metrics, CI
  commands, DR report, and operator boundaries introduced above.

- [x] **Step 1: Update runtime configuration**

Document:

```text
ONLINE_REDIS_EMERGENCY_LIMIT_ENABLED
ONLINE_REDIS_EMERGENCY_RATE_PER_SECOND
ONLINE_REDIS_EMERGENCY_BURST
```

Include defaults, invalid-value startup behavior, rollback behavior, and the
fact that settings apply per replica only during Redis fail-open.

- [x] **Step 2: Update the fault-tolerance investigation**

Replace the unlimited fail-open description with the emergency-limiter flow.
Document generation-bound half-open ownership and:

```text
X-Recall-Degraded
X-Recall-Degradation-Reason
```

Document the four bounded outcomes and the PR/scheduled Maven commands.

- [x] **Step 3: Update DR runbooks**

The failover sequence becomes:

```bash
scripts/dr-standby-capacity.sh promote \
  --context prod-us-west-2 \
  --region us-west-2 \
  --report artifacts/dr-promote.json

# Operator performs approved data-tier promotion steps.

scripts/dr-standby-capacity.sh cutover-check \
  --context prod-us-west-2 \
  --region us-west-2 \
  --report artifacts/dr-cutover-check.json

# Operator reviews report and explicitly performs traffic cutover.
```

Failback uses `failback-check` before explicit traffic/data changes and `demote`
only after traffic has left the standby. Game-day instructions archive reports.

- [x] **Step 4: Run documentation consistency checks**

Run:

```bash
rg -n "fail open|fail-open|dr-standby-capacity|X-Recall-Degrad" \
  CONFIG_GUIDE.md docs README.md
git diff --check
```

Inspect every stale claim returned and update only statements contradicted by
the implemented behavior.

- [x] **Step 5: Run complete deterministic verification**

Run:

```bash
mvn --batch-mode validate
mvn --batch-mode -Presilience test
mvn --batch-mode test
bash scripts/test-dr-standby-capacity.sh
scripts/dr-standby-capacity.sh verify
python3 scripts/summarize-resilience-results.py \
  --suite deterministic \
  --reports target/surefire-reports \
  --output /tmp/recsys-resilience-evidence.json
git diff --check
git status --short
```

Expected:

- Maven validation succeeds with no dependency convergence error.
- Resilience profile and normal suite pass.
- DR tests and overlay verification pass.
- Evidence JSON reports `schemaVersion: 1` and `invariantsPassed: true`.
- No whitespace errors or unrelated working-tree changes.

- [x] **Step 6: Run optional environmental verification**

When Docker is available:

```bash
mvn --batch-mode test -DexcludedGroups=load -Dgroups=docker
```

Run load tests only on suitable hardware or through the scheduled workflow:

```bash
mvn --batch-mode test -DexcludedGroups=docker -Dgroups=load
```

Record if either optional group was not run; do not claim it passed.

- [x] **Step 7: Commit**

```bash
git add CONFIG_GUIDE.md docs/system_design/18_Fault_Tolerance.md \
  docs/runbooks/dr-regional-failover.md docs/runbooks/dr-failback.md \
  docs/runbooks/dr-game-day.md
git commit -m "docs: operationalize fault tolerance hardening"
```

---

## Plan Self-Review

- **Spec coverage:** Runtime containment is Tasks 1–5; dependency convergence
  and PR verification are Task 6; scheduled evidence is Task 7; safe DR
  orchestration is Task 8; rollout documentation and full verification are
  Task 9.
- **Safety boundaries:** No task introduces a production dependency, changes
  healthy status codes, performs data-tier promotion, or performs public traffic
  cutover.
- **Type consistency:** `CircuitBreaker.Permit` flows through route and Redis
  callers; `RedisRateLimiter.Source` is present in every decision;
  `DegradationOutcome` flows from `RecallResult` through V1/V2 headers and
  aggregate metrics.
- **TDD coverage:** Every Java behavior task starts with a failing focused test;
  the Python evidence script and Bash DR workflow also start with executable
  failing tests.
- **No placeholders:** All implementation interfaces, file targets, commands,
  accepted wire values, and example Kubernetes contexts are specified.

---

## Execution Record (2026-07-26)

All nine tasks are implemented. Three deviations from the plan as written, and
one gap the plan did not anticipate, are recorded here.

### Deviations

1. **Environmental evidence is a dedicated probe, not a Surefire-only summary.**
   Task 7 as written derived evidence from Surefire XML alone, which cannot
   report offered/accepted concurrency, rejection counts, degraded ratio,
   timeout recovery, Redis boundary behavior, or drain completion — the
   artifacts the design requires. Surefire counts alone would have produced
   evidence that looked authoritative while measuring none of it. Two dedicated
   probes now exercise those invariants and emit a validated JSON sidecar per
   scheduled job: `LoadResilienceEvidenceTest` (`@Tag("load")`) and
   `DockerRedisResilienceEvidenceTest` (`@Tag("docker")`). The summarizer
   requires `--measurements`, validates the sidecar's schema, cross-checks each
   named invariant against the measured values, and refuses to pass when they
   disagree. Each sidecar declares `applicability`, so neither suite can appear
   to cover the other's invariants.

2. **There is no `deterministic` evidence suite.** Task 9 Step 5 ran the
   summarizer with `--suite deterministic`; the summarizer rejects that suite
   because no environmental probe backs it. The deterministic gate is enforced
   by `mvn -Presilience test` itself, which is the honest signal.

3. **`manifest-digest` was added to the DR script.** Task 8 and the runbooks
   require operators to embed a canonical HPA manifest digest in the approved
   evidence files, and every mutating command validates evidence against the
   digest it renders — but nothing produced that value, so it had to be derived
   by hand. `manifest-digest --target promote|demote|cutover-check|failback-check`
   renders the matching overlay offline, prints the digest, and writes the same
   schema-1 audit report. It refuses `--context`, `--region`, and `--dry-run`.

### Defects found by the new gates

- `RecallDegradationMetrics` became a `@Component` (Task 5) while still
  `final`. `TraceIdAspect` advises every `com.recsys` bean outside
  `infrastructure`, so Spring could not generate a CGLIB subclass and every
  `ModelApplication` context failed to load — 14 errors. The resilience profile
  does not cover the Spring integration tests; only the full suite caught it.
- `EmbeddingRecallLoadTest` and `V2CrossPathLoadTest` stubbed
  `MultiChannelRecallService.recall` while `RecommendationOrchestrator` reads
  `recallDetailed`, so the unstubbed mock returned null and the embedding path
  failed every request (0% and 67% success). Pre-existing on `main` and
  invisible because the `load` group is excluded by default; promoting that
  group to a release-evidence gate surfaced it.

### Verification performed

| Check | Result |
|---|---|
| `mvn --batch-mode validate` | pass (Java 17 + dependency convergence) |
| `mvn --batch-mode -Presilience test` | 147 tests, 0 failures |
| `mvn --batch-mode test` | 1242 tests, 0 failures |
| `mvn test -DexcludedGroups=docker -Dgroups=load` | 12 tests, 0 failures |
| Load evidence sidecar + summarizer | `schemaVersion 1`, `invariantsPassed: true` |
| `bash scripts/test-dr-standby-capacity.sh` | 98 passed, 0 failed |
| `shellcheck` on both DR scripts | no findings |
| `scripts/dr-standby-capacity.sh verify` | pass (active overlay matches baseline) |
| `manifest-digest` against real overlays | distinct active/standby digests |
| `git diff --check` | clean |

**Not run:** the `docker` group (`-Dgroups=docker`), because no Docker daemon is
available in this environment. `DockerRedisResilienceEvidenceTest` and the
Redis-boundary invariant are therefore unverified here and remain to be proven
by the scheduled workflow's `docker` job or a local run with Docker up. The
GitHub workflows have not executed; they are validated by syntax and by running
their exact Maven and summarizer commands locally.
