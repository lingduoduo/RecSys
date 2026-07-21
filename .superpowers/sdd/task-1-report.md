# Task 1 Report: `OnlineLoadShedderCharacterizationTest`

## Implemented

Created `src/test/java/com/recsys/loadshed/OnlineLoadShedderCharacterizationTest.java` exactly as
specified in `.superpowers/sdd/task-1-brief.md` — no accessor-name fixes were required (see
"Accessor verification" below). Test-only change; no production code touched.

## Accessor verification

Read `src/main/java/com/recsys/loadshed/OnlineLoadShedder.java`. The `Snapshot` record is:

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

`inFlightRequests()`, `utilization()`, `suggestedWeight()` all match the brief's test code
verbatim — no `draining` field exists on `Snapshot` (confirmed), so the test's use of
`s.shouldDrain()` on the live shedder while holders hold is the correct way to capture drain
state, exactly as the brief anticipated. The test file was written unmodified from the brief.

## Compile result

```
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test-compile
```
BUILD SUCCESS (exit 0).

## Load-run result

```
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -DexcludedGroups="" -Dgroups=load -Dtest=OnlineLoadShedderCharacterizationTest
```

```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

All 3 tests passed on the first run:
- `characterizeAdmitDrainCurve`
- `drainKneeIsExactlyAtSixtyOne`
- `suggestedWeightIsMonotonicDownToZero`

### Captured `[shedder]` profile table (verbatim)

```
[shedder] max=64 drain=0.95
offered  admitted  inflight  util     drain   weight 
16       16        16        0.250    false   75     
32       32        32        0.500    false   50     
48       48        48        0.750    false   25     
61       61        61        0.953    true    5      
64       64        64        1.000    true    0      
80       64        64        1.000    true    0      
128      64        64        1.000    true    0      
```

This confirms:
- Admission never exceeds `MAX=64` regardless of offered concurrency (levels 80, 128 both cap at
  64 admitted/inflight).
- The drain knee is exactly at 61/64 (util 0.953 ≥ 0.95 drain threshold); 60/64 (util 0.9375,
  tested separately in `drainKneeIsExactlyAtSixtyOne`) does not drain.
- `suggestedWeight` decreases monotonically from 100 (idle) to 0 (full), and is 0 at/above the
  drain knee (5 at 61, 0 at 64+).

## Files changed

- Created: `/Users/linghuang/Git/Recsys-Backend-Service/src/test/java/com/recsys/loadshed/OnlineLoadShedderCharacterizationTest.java`

## Accessor fixes

None needed — brief's code compiled and passed unmodified.

## Self-review

- Test is correctly `@Tag("load")`, so it is excluded from the default `mvn test` run (verified
  via the project's `pom.xml` `<excludedGroups>load,docker</excludedGroups>` default and the
  explicit `-DexcludedGroups="" -Dgroups=load` override used to run it).
- No production code was modified; only a new test file was added.
- Concurrency harness (`rampAndHold`) uses latches to ensure a deterministic snapshot point (all
  threads have attempted `tryAcquire` before the snapshot is taken, and slots are held open until
  release), so the run should be non-flaky. Observed one full run with all invariants holding,
  including the "never exceed MAX=64" and "knee@61" assertions — no weakening was needed.
- Git status was checked before staging to ensure only the intended new test file was added to
  the commit (`.superpowers/sdd/final-fix-report.md`, a pre-existing unstaged modification, was
  left untouched and unstaged).
- Commit: `9c4eb22` — "test: load-shedder admit/drain characterization harness"

## Note

This file previously held a report for an unrelated "Task 1: Key-Aware Bounded Publisher" (Kafka
event publishing) from a different sdd cycle. It has been overwritten per this task's explicit
instruction to write the report here. If that earlier content is still needed, retrieve it from
git history (the prior report was not committed to this path in the current branch's tracked
history at the time of this write, per `git log -- .superpowers/sdd/task-1-report.md`).
