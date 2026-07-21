# Task 3 Report: OverloadGateOrderingCharacterizationTest

## Implemented

Created `src/test/java/com/recsys/loadshed/OverloadGateOrderingCharacterizationTest.java` exactly
as specified in the task brief (`.superpowers/sdd/task-3-brief.md`), no modifications needed to the
brief's test code. The test:

- Computes `cores = Runtime.getRuntime().availableProcessors()`, `gate = 64`,
  `pool = cores*2`, `queue = pool*4`, `bulkheadCeiling = pool + queue` (`cores*10`).
- Determines which limit is smaller and drives exactly to that limit, asserting it trips
  (rejected bulkhead submit, or `tryAcquire()` returning `false`) while the other gate still has
  room.
- Uses the brief's `awaitUntil` poll helper to avoid a race between submitting bulkhead tasks and
  the pool/queue counters reflecting them (`active()+queued()` reaching `bulkheadCeiling` before
  checking overflow behavior).
- `@Tag("load")`, so excluded from the default suite (`excludedGroups=load,docker` in `pom.xml`)
  and only runs via `-DexcludedGroups="" -Dgroups=load`.

## Compile result

```
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test-compile
```
→ `BUILD SUCCESS`.

## Load-run result

```
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -DexcludedGroups="" -Dgroups=load -Dtest=OverloadGateOrderingCharacterizationTest
```
→ `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS`.

Captured `[ordering]` output verbatim:

```
[ordering] cores=8 gate=64 bulkheadCeiling=80 => gate trips first
[ordering] confirmed: gate trips first on this host
```

## This machine

- `cores = 8` (confirmed via `sysctl -n hw.ncpu` / `nproc`, matches `Runtime.getRuntime().availableProcessors()`)
- `gate = 64`
- `bulkheadCeiling = cores*10 = 80`
- Since `gate (64) < bulkheadCeiling (80)`, the **gate-first branch** executed: the 65th
  `shedder.tryAcquire()` call returns `false` while the bulkhead (pool=16, queue=64) still has
  room to accept and queue a submission.
- On this machine the "silent recall degradation before any 429" scenario described in sharp-edge
  #1 (bulkhead saturating before the gate) does NOT reproduce — that requires `cores <= 6`. This
  machine demonstrates the opposite, equally real ordering: the 64-concurrency shedder rejects
  with a clean signal before the recall bulkhead is ever stressed.

## Files changed

- `src/test/java/com/recsys/loadshed/OverloadGateOrderingCharacterizationTest.java` (new, 74 lines)

## Adjustments

None. The brief's test code compiled and passed on the first attempt; no race was observed in
reading `active()+queued()` (the `awaitUntil` poll in the brief already guards against it), and no
weakening of the ordering invariant was needed.

## Self-review

- Verified `OnlineLoadShedder` (`src/main/java/com/recsys/loadshed/OnlineLoadShedder.java`) and
  `WorkerBulkhead` (`src/main/java/com/recsys/resilience/WorkerBulkhead.java`) APIs match what the
  test calls: `tryAcquire()`, `snapshot().inFlightRequests()`, `submit(Callable<T>)`,
  `snapshot().active()`/`.queued()`, `close()` — all present with matching signatures.
- Confirmed `pom.xml` already wires `excludedGroups=load,docker` as the default and
  `-DexcludedGroups="" -Dgroups=load` as the documented opt-in override — no build config changes
  needed.
- Confirmed only the intended file was staged before commit (`git status --short` showed two
  unrelated pre-existing modified files from other tasks — `task-1-report.md` and
  `final-fix-report.md` — which were deliberately left out of this commit).
- The "tie" branch (`bulkheadCeiling == gate`, i.e. `cores == 6.4`) is correctly unreachable for
  integer core counts, as the brief notes; no explicit guard assertion was added since it isn't
  requested as a required step and the brief marks it optional ("If you prefer an explicit
  guard...").

## Note

This report path (`task-3-report.md`) previously held content for an unrelated earlier task
("Wire the canonical endpoint into the gateway"). That content has been replaced here with this
task's report, per the instruction to write this task's report to this exact path.

## Commit

`807f4bd` — `test: gate-vs-bulkhead ordering characterization (sharp-edge #1)`
