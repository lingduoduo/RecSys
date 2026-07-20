# Overload-Protection Characterization Harness Design

## Objective

The overload-protection defaults — `OnlineLoadShedder` (64 concurrent / 0.95 drain),
the recall `WorkerBulkhead` (pool `cores×2`, queue `pool×4`), and the per-channel
recall timeout — were flagged in the scalability investigation (`17_Scalability.md`,
sharp edge #3) as *unvalidated starting points, not load-tested knees*. Sharp edge #1
further noted that on catalog 6010 the recall bulkhead can **saturate before** the
64-concurrency gate rejects, so overload first shows as silent recall degradation
rather than a 429 — an ordering that is machine-dependent and was never measured.

Build a **characterization harness**: `@Tag("load")` tests that drive the in-memory
overload gates under ramping concurrency, assert their *invariants* deterministically,
and emit admit/reject/threshold **profiles**; plus a written report documenting what
the defaults actually do and the gate-vs-bulkhead ordering on the machine that ran it.

This validates the mechanism and gives a repeatable measurement tool. It does **not**
produce authoritative production numbers — absolute knees depend on prod-like hardware;
an in-repo `@Tag("load")` run measures the dev/CI box.

## Scope

In scope (test-only + one doc):

- `OnlineLoadShedderCharacterizationTest` (`com.recsys.loadshed`).
- `WorkerBulkheadCharacterizationTest` (`com.recsys.resilience`).
- `OverloadGateOrderingCharacterizationTest` (`com.recsys.loadshed`) — the #1 tie-in.
- `docs/runbooks/overload-characterization.md` — the written report.

Out of scope (explicit non-goals):

- **No production/Java code changes.** Tests + one runbook doc only.
- **`RedisRateLimiter` excluded** — it needs a real Redis (`@Tag("docker")`, not
  `@Tag("load")`); its boundary behavior is already characterized by the sliding-window
  docker test from PR #203. The report notes this.
- **No claim of authoritative production tuning.** Invariants are deterministic;
  absolute-latency numbers are directional only and are not asserted as thresholds
  beyond generous CI-safe ceilings.
- These tests are `@Tag("load")` — excluded from the default `mvn test`, run via
  `mvn test -Dgroups=load` (per the existing convention, e.g. `InferenceLoadTest`).

## Background (testable surfaces)

- `OnlineLoadShedder(maxConcurrentRequests, drainUtilization)`: `boolean tryAcquire()`
  (false at `inFlight >= max`), `void release()`, `boolean shouldDrain()`
  (`utilization >= drainUtilization`), and `Snapshot snapshot()` with
  `inFlightRequests, maxConcurrentRequests, utilization, drainUtilization,
  acceptedRequests, rejectedRequests, suggestedWeight (= round((1-util)*100)),
  retryAfterSeconds, shuttingDown`.
- `WorkerBulkhead(name, poolSize, queueCapacity)`: `CompletableFuture<T> submit(Callable)`
  — on queue-full the returned future completes exceptionally with
  `RejectedExecutionException` and `rejectedCount` increments; `Snapshot snapshot()`
  with `active, queued, poolSize, rejected`. Ceiling of concurrently-pending work is
  `poolSize + queueCapacity`.
- Existing `@Tag("load")` idiom (`InferenceLoadTest`): `@Tag("load")`,
  `@TestInstance(PER_CLASS)`, `ExecutorService` + latches, `@Timeout`, conservative
  CI thresholds.

## The three characterization tests

### 1. `OnlineLoadShedderCharacterizationTest`

Drive a shedder (`new OnlineLoadShedder(64, 0.95)`) with a ramp of concurrent holders
that `tryAcquire()` and hold (release only at the end of each level). For levels
`{16, 32, 48, 61, 64, 80, 128}`:

- record admitted vs rejected and the snapshot (`inFlightRequests`, `utilization`,
  `shouldDrain`, `suggestedWeight`) → a printed profile table.

**Invariant assertions** (deterministic):

- Concurrent in-flight **never exceeds 64** at any level (admitted count caps at 64).
- `shouldDrain()` is **false below** `inFlight = 61` (`⌈0.95×64⌉`) and **true at/above**
  it — locate the drain knee exactly.
- `suggestedWeight` is **monotonically non-increasing** as utilization rises, reaching
  0 at full saturation.
- After releasing all holders, `tryAcquire()` succeeds again (slots freed;
  `inFlightRequests == 0`).

### 2. `WorkerBulkheadCharacterizationTest`

Drive a bulkhead (`new WorkerBulkhead("char", P, Q)` with small explicit `P`, `Q`,
e.g. 4 and 8) with blocking tasks (each awaits a latch to occupy a pool thread), then
submit additional tasks to fill the queue and overflow. Sweep submit counts around the
`P+Q` ceiling.

- record accepted (future not exceptionally completed with `RejectedExecutionException`)
  vs rejected, plus `snapshot()` (`active`, `queued`, `rejected`) → profile.

**Invariant assertions**:

- The bulkhead accepts up to **`P + Q`** concurrently-pending tasks and **rejects**
  every submit beyond that (the ceiling), with `rejected` count matching the overflow.
- Rejection is **immediate**: an over-ceiling submit's future is already exceptionally
  completed (`isCompletedExceptionally()` with `RejectedExecutionException`) without
  waiting — demonstrating bounded tail latency (no unbounded queueing).
- After the latch releases and tasks drain, `active`/`queued` return to 0 and new
  submits are accepted again.

### 3. `OverloadGateOrderingCharacterizationTest` (sharp-edge-#1 tie-in)

With **catalog-like defaults** — `OnlineLoadShedder(64, 0.90)` and a
`WorkerBulkhead("recall", cores*2, (cores*2)*4)` where `cores =
Runtime.getRuntime().availableProcessors()` — ramp concurrent "recall-like" units of
work that each try the concurrency gate and submit a blocking task to the bulkhead, and
determine **which limit rejects first** as concurrency climbs.

- Compute and **report** (printed): `cores`, the bulkhead ceiling `cores*10`, the gate
  `64`, and which is smaller ⇒ trips first on this machine.

**Assertions** (deterministic — drive to just past the *smaller* limit, avoiding any
racy "which fired first" observation):

- Let `bulkheadCeiling = cores*10`, `gate = 64`, `smaller = min(bulkheadCeiling, gate)`.
- Occupy the gate with `min(gate, running)` holders and the bulkhead with blocking
  tasks, ramping to `smaller + 1` recall units. Assert the **smaller** limit rejects at
  its expected count while the **larger** still has headroom:
  - if `bulkheadCeiling < gate`: the `(bulkheadCeiling+1)`-th bulkhead submit is rejected
    (`RejectedExecutionException`) **while** the shedder's `inFlight < 64` (gate not yet
    tripped) — bulkhead-first;
  - if `gate < bulkheadCeiling`: the 65th `tryAcquire()` returns false **while** the
    bulkhead's `active + queued < bulkheadCeiling` (room remains) — gate-first;
  - if equal, either rejection is acceptable — assert both are at their ceiling.
- This is the empirical evidence for #1's "silent recall degradation before 429" on
  low-core hosts — documented in the report, not just asserted.

All three use conservative timeouts (`@Timeout`) and bounded thread pools so they finish
quickly and never hang CI when run under `-Dgroups=load`.

## The report: `docs/runbooks/overload-characterization.md`

- **How to run:** `mvn test -DexcludedGroups=docker -Dgroups=load -Dtest=OnlineLoadShedderCharacterizationTest,WorkerBulkheadCharacterizationTest,OverloadGateOrderingCharacterizationTest`
  (and the general `-Dgroups=load` form).
- **What each harness measures** and the invariants it locks in (drain knee at 61,
  bulkhead ceiling `P+Q`, gate ordering).
- **The gate-vs-bulkhead ordering finding** tied to sharp edge #1: on ≤6-core hosts the
  recall bulkhead saturates before the 64 gate, so overload surfaces as silent recall
  degradation (now observable via `/health/load` from PR #202) before any 429.
- **Honest caveat:** absolute latency/throughput numbers are hardware-dependent; the
  harness validates the *mechanism* and provides a repeatable way to find each knee, not
  authoritative production values. Real tuning needs a prod-like load environment.
- Note the rate limiter is characterized separately by the `@Tag("docker")`
  sliding-window test (PR #203).

## Testing / validation

- The three classes compile in the normal build and are **excluded** from `mvn test`
  (they are `@Tag("load")`); they pass when run with `-Dgroups=load`.
- Because the assertions are **invariants** (never-exceed-max, ceiling `=P+Q`, drain
  knee, analytical ordering), they are deterministic — not flaky perf thresholds.
- Run them once locally with `-Dgroups=load` and paste the observed profiles / ordering
  into the report.

## Acceptance Criteria

1. `OnlineLoadShedderCharacterizationTest` drives a ramp, emits a profile, and asserts:
   in-flight never exceeds 64, the drain knee is exactly at 61, `suggestedWeight` is
   monotonic to 0, and slots free after release.
2. `WorkerBulkheadCharacterizationTest` asserts the ceiling is `P+Q`, over-ceiling
   submits are immediately + exceptionally rejected (`RejectedExecutionException`), the
   `rejected` count matches, and the bulkhead recovers after drain.
3. `OverloadGateOrderingCharacterizationTest` reports `cores` / bulkhead ceiling / gate
   and asserts the first-tripping limit matches the analytical prediction
   (`cores*10 < 64 ⇒ bulkhead-first`).
4. `docs/runbooks/overload-characterization.md` documents how to run, what is measured,
   the #1 ordering finding, the rate-limiter exclusion, and the hardware-dependence
   caveat.
5. All three are `@Tag("load")` (excluded by default; pass under `-Dgroups=load`); no
   production/Java code changed; the default `mvn test` suite is unaffected.
