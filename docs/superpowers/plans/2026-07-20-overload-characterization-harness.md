# Overload-Protection Characterization Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `@Tag("load")` characterization tests that drive the in-memory overload gates (`OnlineLoadShedder`, `WorkerBulkhead`) under ramping concurrency, assert their invariants deterministically, emit profiles, and document the machine-dependent gate-vs-bulkhead ordering behind sharp-edge #1 — plus a written report.

**Architecture:** Three `@Tag("load")` test classes (excluded from the default suite) + one runbook doc. No production code. Assertions are invariants (never-exceed-max, `P+Q` ceiling, drain knee at 61, analytical ordering) so they are deterministic, not flaky perf thresholds.

**Tech Stack:** Java 17, JUnit 5 (`@Tag`, `@Timeout`, `@TestInstance`), AssertJ, `ExecutorService`/`CountDownLatch`, Maven. No new dependencies.

## Global Constraints

- **Run load tests with:** `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -DexcludedGroups="" -Dgroups=load -Dtest=<Class>`. They are `@Tag("load")` → excluded from the default `mvn test`.
- **No production/Java code changes.** Three new test files + one doc.
- **Deterministic invariant assertions only** — never assert absolute latency/throughput as a pass/fail threshold; those are printed for the report, not gated.
- **Component facts:** `OnlineLoadShedder(max, drain)`: `tryAcquire()` (false at inFlight≥max; increments accepted/rejected counters), `release()`, `shouldDrain()` (`utilization ≥ drain`), `snapshot()` → `Snapshot(inFlightRequests, maxConcurrentRequests, utilization, drainUtilization, acceptedRequests, rejectedRequests, suggestedWeight (=round((1-util)*100)), retryAfterSeconds, shuttingDown)`. `WorkerBulkhead(name, poolSize, queueCapacity)`: `submit(Callable)` → future that completes exceptionally with `RejectedExecutionException` (synchronously) when the queue is full and increments `rejected`; `snapshot()` → `Snapshot(String name, int active, int queued, int poolSize, long rejected)`; `close()`. Ceiling of concurrently-pending work = `poolSize + queueCapacity`.
- **`@Tag("load")` idiom** (per `InferenceLoadTest`): `@Tag("load")`, `@TestInstance(PER_CLASS)` where helpful, `@Timeout`, `ExecutorService` + latches.

---

## File Structure

- Create `src/test/java/com/recsys/loadshed/OnlineLoadShedderCharacterizationTest.java`
- Create `src/test/java/com/recsys/resilience/WorkerBulkheadCharacterizationTest.java`
- Create `src/test/java/com/recsys/loadshed/OverloadGateOrderingCharacterizationTest.java`
- Create `docs/runbooks/overload-characterization.md`

---

## Task 1: `OnlineLoadShedderCharacterizationTest`

**Files:**
- Create: `src/test/java/com/recsys/loadshed/OnlineLoadShedderCharacterizationTest.java`

**Interfaces:** consumes `OnlineLoadShedder` (existing). Produces a `@Tag("load")` test class.

- [ ] **Step 1: Write the test**

```java
package com.recsys.loadshed;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterizes {@link OnlineLoadShedder}'s admit/drain behavior under ramping concurrency.
 * Invariant assertions are deterministic; the printed profile feeds
 * docs/runbooks/overload-characterization.md. Run:
 *   mvn test -DexcludedGroups="" -Dgroups=load -Dtest=OnlineLoadShedderCharacterizationTest
 */
@Tag("load")
class OnlineLoadShedderCharacterizationTest {

    private static final int MAX = 64;
    private static final double DRAIN = 0.95;

    private record Level(int admitted, OnlineLoadShedder.Snapshot snap, boolean draining) {}

    /** Launches {@code threads} concurrent tryAcquire holders; snapshots while all hold, then releases. */
    private static Level rampAndHold(OnlineLoadShedder s, int threads) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, 256));
        try {
            CountDownLatch attempted = new CountDownLatch(threads);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger admitted = new AtomicInteger();
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    boolean ok = s.tryAcquire();
                    if (ok) admitted.incrementAndGet();
                    attempted.countDown();
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    if (ok) s.release();
                });
            }
            attempted.await();                     // every thread has attempted; holders are holding
            OnlineLoadShedder.Snapshot snap = s.snapshot();
            boolean draining = s.shouldDrain();    // captured while holders hold (Snapshot has no drain flag)
            int a = admitted.get();
            release.countDown();                   // let holders release their slots
            return new Level(a, snap, draining);
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(60)
    void characterizeAdmitDrainCurve() throws InterruptedException {
        int[] levels = {16, 32, 48, 61, 64, 80, 128};
        System.out.printf("%n[shedder] max=%d drain=%.2f%n", MAX, DRAIN);
        System.out.printf("%-8s %-9s %-9s %-8s %-7s %-7s%n",
                "offered", "admitted", "inflight", "util", "drain", "weight");
        for (int offered : levels) {
            OnlineLoadShedder s = new OnlineLoadShedder(MAX, DRAIN); // fresh per level for clean counters
            Level level = rampAndHold(s, offered);
            System.out.printf("%-8d %-9d %-9d %-8.3f %-7s %-7d%n",
                    offered, level.admitted(), level.snap().inFlightRequests(),
                    level.snap().utilization(), level.draining(), level.snap().suggestedWeight());

            // Invariants
            assertThat(level.snap().inFlightRequests()).isLessThanOrEqualTo(MAX);
            assertThat(level.admitted()).isEqualTo(Math.min(offered, MAX));
            // Slots freed after release (pool terminated in rampAndHold).
            assertThat(s.snapshot().inFlightRequests()).isZero();
        }
    }

    @Test
    void drainKneeIsExactlyAtSixtyOne() {
        OnlineLoadShedder s = new OnlineLoadShedder(MAX, DRAIN);
        for (int i = 0; i < 60; i++) assertThat(s.tryAcquire()).isTrue();
        assertThat(s.shouldDrain()).isFalse();  // 60/64 = 0.9375 < 0.95
        assertThat(s.tryAcquire()).isTrue();     // 61st
        assertThat(s.shouldDrain()).isTrue();    // 61/64 = 0.953 >= 0.95
    }

    @Test
    void suggestedWeightIsMonotonicDownToZero() {
        OnlineLoadShedder s = new OnlineLoadShedder(MAX, DRAIN);
        int prev = s.snapshot().suggestedWeight();      // 100 at 0 inflight
        assertThat(prev).isEqualTo(100);
        for (int i = 0; i < MAX; i++) {
            assertThat(s.tryAcquire()).isTrue();
            int w = s.snapshot().suggestedWeight();
            assertThat(w).isLessThanOrEqualTo(prev);
            prev = w;
        }
        assertThat(prev).isZero();                       // round((1-1)*100) = 0 at full
    }
}
```

> Before running, confirm `OnlineLoadShedder.Snapshot` accessor names `inFlightRequests()`,
> `utilization()`, `suggestedWeight()` (the `Snapshot` record has NO drain flag — this test correctly
> captures drain state via `s.shouldDrain()` on the live shedder while holders hold). If any accessor
> name differs, fix the TEST to match; the invariant (drain true at inFlight≥61) is unchanged.

- [ ] **Step 2: Compile (test is load-tagged, excluded from default run)**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Run under the load group**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -DexcludedGroups="" -Dgroups=load -Dtest=OnlineLoadShedderCharacterizationTest`
Expected: PASS (3 tests). Note the printed `[shedder]` profile table for the report (Task 4).

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/recsys/loadshed/OnlineLoadShedderCharacterizationTest.java
git commit -m "test: load-shedder admit/drain characterization harness"
```

---

## Task 2: `WorkerBulkheadCharacterizationTest`

**Files:**
- Create: `src/test/java/com/recsys/resilience/WorkerBulkheadCharacterizationTest.java`

**Interfaces:** consumes `WorkerBulkhead` (existing). Produces a `@Tag("load")` test class.

- [ ] **Step 1: Write the test**

```java
package com.recsys.resilience;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Characterizes {@link WorkerBulkhead}: work beyond the {@code poolSize + queueCapacity} ceiling is
 * rejected immediately (bounded tail latency), not queued unbounded. Run:
 *   mvn test -DexcludedGroups="" -Dgroups=load -Dtest=WorkerBulkheadCharacterizationTest
 */
@Tag("load")
class WorkerBulkheadCharacterizationTest {

    private static final int POOL = 4;
    private static final int QUEUE = 8;
    private static final int CEILING = POOL + QUEUE; // 12
    private static final int OVERFLOW = 5;

    private static void awaitUntil(java.util.function.BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met within " + timeoutMs + "ms");
            Thread.sleep(5);
        }
    }

    @Test
    @Timeout(60)
    void ceilingIsPoolPlusQueueAndOverflowRejectsImmediately() throws Exception {
        WorkerBulkhead bh = new WorkerBulkhead("char", POOL, QUEUE);
        CountDownLatch block = new CountDownLatch(1);
        try {
            List<CompletableFuture<String>> futures = new ArrayList<>();
            int submitted = CEILING + OVERFLOW;
            for (int i = 0; i < submitted; i++) {
                futures.add(bh.submit(() -> { block.await(); return "ok"; }));
            }

            // Over-ceiling submits are rejected SYNCHRONOUSLY (future already exceptionally completed).
            int rejected = 0;
            for (CompletableFuture<String> f : futures) {
                if (f.isCompletedExceptionally()) {
                    rejected++;
                    assertThatThrownBy(f::join).hasCauseInstanceOf(RejectedExecutionException.class);
                }
            }

            // The accepted CEILING tasks are pending (blocked); let active+queued stabilize.
            awaitUntil(() -> bh.snapshot().active() + bh.snapshot().queued() == CEILING, 2_000);
            WorkerBulkhead.Snapshot snap = bh.snapshot();
            System.out.printf("%n[bulkhead] pool=%d queue=%d ceiling=%d submitted=%d%n", POOL, QUEUE, CEILING, submitted);
            System.out.printf("rejected=%d active=%d queued=%d snap.rejected=%d%n",
                    rejected, snap.active(), snap.queued(), snap.rejected());

            // Invariants
            assertThat(rejected).isEqualTo(OVERFLOW);                 // exactly the overflow rejected
            assertThat(snap.rejected()).isEqualTo((long) OVERFLOW);
            assertThat(snap.active() + snap.queued()).isEqualTo(CEILING);

            // Recovery: release the blocked tasks; the accepted ones complete, bulkhead drains.
            block.countDown();
            for (CompletableFuture<String> f : futures) {
                if (!f.isCompletedExceptionally()) f.get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
            awaitUntil(() -> bh.snapshot().active() == 0 && bh.snapshot().queued() == 0, 2_000);
            assertThat(bh.snapshot().active()).isZero();
        } finally {
            block.countDown();   // ensure no task stays blocked on failure
            bh.close();
        }
    }
}
```

> Confirm `WorkerBulkhead.Snapshot` accessors are `active()`, `queued()`, `rejected()` (record
> `Snapshot(String name, int active, int queued, int poolSize, long rejected)`), and that `submit`
> returns a `CompletableFuture` that is `isCompletedExceptionally()` immediately on queue-full. If
> the reject is not synchronous in this version, replace the immediate check with a short
> `awaitUntil(f::isCompletedExceptionally, 1000)` per overflow future — but the count invariant
> (`OVERFLOW` rejected, `snap.rejected()==OVERFLOW`) stands.

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Run under the load group**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -DexcludedGroups="" -Dgroups=load -Dtest=WorkerBulkheadCharacterizationTest`
Expected: PASS. Note the printed `[bulkhead]` profile for the report.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/recsys/resilience/WorkerBulkheadCharacterizationTest.java
git commit -m "test: worker-bulkhead ceiling/reject characterization harness"
```

---

## Task 3: `OverloadGateOrderingCharacterizationTest` (sharp-edge #1 tie-in)

**Files:**
- Create: `src/test/java/com/recsys/loadshed/OverloadGateOrderingCharacterizationTest.java`

**Interfaces:** consumes `OnlineLoadShedder` + `WorkerBulkhead`. Produces a `@Tag("load")` test class.

- [ ] **Step 1: Write the test**

```java
package com.recsys.loadshed;

import com.recsys.resilience.WorkerBulkhead;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterizes WHICH overload gate trips first for catalog-like defaults: the 64-concurrency
 * shedder vs the recall bulkhead (pool=cores*2, queue=pool*4 => ceiling cores*10). The ordering is
 * machine-dependent — on <=6-core hosts the bulkhead saturates before the 64 gate, so overload shows
 * as silent recall degradation before any 429 (sharp-edge #1). Deterministic: drive to just past the
 * SMALLER limit and assert it trips while the larger still has room. Run:
 *   mvn test -DexcludedGroups="" -Dgroups=load -Dtest=OverloadGateOrderingCharacterizationTest
 */
@Tag("load")
class OverloadGateOrderingCharacterizationTest {

    private static void awaitUntil(java.util.function.BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met within " + timeoutMs + "ms");
            Thread.sleep(5);
        }
    }

    @Test
    @Timeout(60)
    void smallerLimitTripsFirst() throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        int gate = 64;
        int pool = cores * 2;
        int queue = pool * 4;
        int bulkheadCeiling = pool + queue;   // cores*10
        String firstToTrip = bulkheadCeiling < gate ? "bulkhead" : (gate < bulkheadCeiling ? "gate" : "tie");
        System.out.printf("%n[ordering] cores=%d gate=%d bulkheadCeiling=%d => %s trips first%n",
                cores, gate, bulkheadCeiling, firstToTrip);

        OnlineLoadShedder shedder = new OnlineLoadShedder(gate, 0.90); // catalog default drain
        WorkerBulkhead bh = new WorkerBulkhead("recall", pool, queue);
        CountDownLatch block = new CountDownLatch(1);
        try {
            if (bulkheadCeiling < gate) {
                // Fill the bulkhead exactly to its ceiling, acquiring the gate per unit too.
                for (int i = 0; i < bulkheadCeiling; i++) {
                    assertThat(shedder.tryAcquire()).isTrue();          // gate has room throughout
                    bh.submit(() -> { block.await(); return null; });
                }
                awaitUntil(() -> bh.snapshot().active() + bh.snapshot().queued() == bulkheadCeiling, 2_000);
                // The next bulkhead submit is rejected while the gate is NOT yet tripped.
                CompletableFuture<Object> overflow = bh.submit(() -> { block.await(); return null; });
                assertThat(overflow.isCompletedExceptionally()).isTrue();          // bulkhead-first
                assertThat(shedder.snapshot().inFlightRequests()).isLessThan(gate); // gate still has room
                assertThat(shedder.tryAcquire()).isTrue();
            } else {
                // gate < bulkheadCeiling: the 65th tryAcquire fails while the bulkhead still has room.
                for (int i = 0; i < gate; i++) assertThat(shedder.tryAcquire()).isTrue();
                assertThat(shedder.tryAcquire()).isFalse();                         // gate-first
                CompletableFuture<Object> f = bh.submit(() -> { block.await(); return null; });
                assertThat(f.isCompletedExceptionally()).isFalse();                 // bulkhead accepts
                assertThat(bh.snapshot().active() + bh.snapshot().queued()).isLessThan(bulkheadCeiling);
            }
            System.out.printf("[ordering] confirmed: %s trips first on this host%n", firstToTrip);
        } finally {
            block.countDown();
            bh.close();
        }
    }
}
```

> `cores*10 == 64` requires `cores == 6.4`, impossible for an integer core count, so the "tie" branch
> is unreachable — the two real branches (`<` and `>`) cover every host. If you prefer an explicit
> guard, add an `assertThat(bulkheadCeiling).isNotEqualTo(gate)` at the top.

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Run under the load group**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -DexcludedGroups="" -Dgroups=load -Dtest=OverloadGateOrderingCharacterizationTest`
Expected: PASS. Note the printed `[ordering]` line (cores / ceilings / winner) for the report.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/recsys/loadshed/OverloadGateOrderingCharacterizationTest.java
git commit -m "test: gate-vs-bulkhead ordering characterization (sharp-edge #1)"
```

---

## Task 4: The report + run-all + default-suite sanity

**Files:**
- Create: `docs/runbooks/overload-characterization.md`

- [ ] **Step 1: Confirm the harness is EXCLUDED from the default suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test 2>&1 | tail -3`
Expected: BUILD SUCCESS with the same test count as before this feature (the three `@Tag("load")` classes do NOT run in the default suite). Report the count.

- [ ] **Step 2: Run all three characterization classes under the load group and capture the profiles**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -DexcludedGroups="" -Dgroups=load -Dtest=OnlineLoadShedderCharacterizationTest,WorkerBulkheadCharacterizationTest,OverloadGateOrderingCharacterizationTest 2>&1 | tee /tmp/overload-char.log`
Expected: all PASS. Keep the printed `[shedder]`, `[bulkhead]`, `[ordering]` blocks — paste the actual observed numbers into the report.

- [ ] **Step 3: Write the report**

Create `docs/runbooks/overload-characterization.md`:

```markdown
# Runbook: Overload-Protection Characterization

`@Tag("load")` harnesses that characterize the in-memory overload gates and lock in
their invariants. They are **excluded from the default `mvn test`** and opt-in:

```bash
mvn test -DexcludedGroups="" -Dgroups=load -Dtest=OnlineLoadShedderCharacterizationTest,WorkerBulkheadCharacterizationTest,OverloadGateOrderingCharacterizationTest
```

> These validate the overload **mechanism** and give a repeatable way to find each knee.
> Absolute latency/throughput numbers are **hardware-dependent** — an in-repo run measures
> the dev/CI box, not production. Real tuning needs a prod-like load environment. The Redis
> rate limiter is characterized separately by the `@Tag("docker")` sliding-window test.

## What each harness measures

| Harness | Locks in (invariant) | Profile emitted |
|---|---|---|
| `OnlineLoadShedderCharacterizationTest` | in-flight never exceeds `max` (64); drain knee exactly at inFlight 61 (`⌈0.95×64⌉`); `suggestedWeight` monotonic 100→0 | offered/admitted/inflight/util/drain/weight per level |
| `WorkerBulkheadCharacterizationTest` | accepts up to `poolSize+queueCapacity`; work beyond is rejected **immediately** (bounded tail latency, no unbounded queue); recovers after drain | rejected/active/queued at the ceiling |
| `OverloadGateOrderingCharacterizationTest` | the smaller of {64 gate, `cores×10` bulkhead ceiling} trips first; the other still has room | cores / gate / bulkhead ceiling / winner |

## The gate-vs-bulkhead ordering (sharp-edge #1)

The catalog recall path has a 64-concurrency shedder AND a recall bulkhead sized
`pool=cores×2, queue=pool×4` (ceiling `cores×10`). Which trips first is **machine-dependent**:

- **≤6 cores** (`cores×10 < 64`): the bulkhead saturates first → non-primary recall channels
  drop to empty results on HTTP 200 **before** any 429 (silent recall degradation). This is
  the empirical basis for sharp-edge #1; it is now observable via `GET /health/load`
  (`recall.degradedRatio`) from PR #202.
- **≥7 cores** (`cores×10 > 64`): the concurrency gate trips first → overload surfaces as 429.

Observed on the machine that last ran this harness:

<!-- Paste the actual [ordering] line here, e.g.: cores=8 gate=64 bulkheadCeiling=80 => gate trips first -->

## Observed profiles (last local run)

<!-- Paste the [shedder] and [bulkhead] tables from the load run here. -->
```

Fill the three `<!-- Paste … -->` placeholders with the **actual** captured output from Step 2.

- [ ] **Step 4: Verify docs render, only intended files changed**

Run: `git diff --stat`
Expected: only `docs/runbooks/overload-characterization.md` changed in this task; tables well-formed; the pasted profiles present (no leftover `<!-- Paste … -->` placeholders).

- [ ] **Step 5: Commit**

```bash
git add docs/runbooks/overload-characterization.md
git commit -m "docs: overload-protection characterization runbook with observed profiles"
```

---

## Self-Review Notes (author)

- **Spec coverage:** shedder admit/drain + never-exceed-64 + knee@61 + monotonic weight (T1) ✓; bulkhead `P+Q` ceiling + immediate reject + recovery (T2) ✓; gate ordering, deterministic drive-past-smaller (T3) ✓; report with run command, per-harness table, #1 ordering, rate-limiter-exclusion + hardware caveat, and pasted real profiles (T4) ✓; `@Tag("load")` excluded from default suite, no production code (all tasks) ✓. Acceptance criteria 1–5 mapped.
- **Determinism:** all pass/fail assertions are invariants (CAS-bounded admit, synchronous reject count, integer drain knee, analytical ordering); `awaitUntil` polls stabilize `active()/queued()` snapshots rather than sleeping a fixed time; no latency threshold is gated.
- **Pre-write checks (flagged inline):** `OnlineLoadShedder.Snapshot` accessor names / whether it exposes `shouldDrain`/`draining` (T1 note); `WorkerBulkhead.Snapshot` accessors + synchronous-reject assumption (T2 note); the unreachable ordering "tie" (T3 note). Fix the TESTS to the real accessors if they differ; never touch production.
- **Ordering test host-independence:** both branches (`cores≤6` and `cores≥7`) are exercised by the single machine-appropriate path; the assertion form holds on any host.
