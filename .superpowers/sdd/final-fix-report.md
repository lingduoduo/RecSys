# Final Fix Report — catalog recall degradation visibility

Branch: `feat/catalog-recall-degradation-visibility` (no branch switch).

## IMPORTANT fix — degradedRatio counted channels, not requests

### Root cause
`RecallDegradationMetrics.record(channel, reason)` incremented `degradedRecalls` on
*every* call, but `MultiChannelRecallService` called `record(...)` once per degraded
non-primary channel. Since `totalRecalls` is incremented once per request
(`recordTotal()`), a single request that degraded 2+ channels inflated `degradedRecalls`
past the number of requests, letting `degradedRatio = degradedRecalls / totalRecalls`
exceed 1.0.

### Fix
1. `src/main/java/com/recsys/application/retrieval/multichannel/RecallDegradationMetrics.java`
   - Removed `degradedRecalls.incrementAndGet()` from `record(channel, reason)` — it now
     only bumps the per-(channel,reason) `LongAdder` in `byChannel`.
   - Added `public void recordDegradedRequest()` which does
     `degradedRecalls.incrementAndGet()` — the once-per-request signal.
   - `recordTotal()`, `snapshot()`, the `degradedRatio` formula, and the `byChannel` map
     are unchanged.

2. `src/main/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallService.java`
   - `recall(query, limit, primary)` still calls `degradationMetrics.record(channel, reason)`
     once per degraded non-primary channel inside the result-collection loop (unchanged).
   - After that loop, added:
     ```java
     if (!primary && !degradedChannels.isEmpty()) {
         degradationMetrics.recordDegradedRequest();
     }
     ```
   - `recordTotal()` on the non-primary path is unchanged (still called before the
     futures are dispatched).

### Tests updated for the corrected semantics
- `src/test/java/com/recsys/application/retrieval/multichannel/RecallDegradationMetricsTest.java`
  — `snapshotCountsAndRatio` now calls `recordDegradedRequest()` explicitly for the
  degraded-request count (in addition to the two `record(...)` calls that exercise the
  per-channel counters), and asserts `degradedRatio() == 0.5` (1 degraded request / 2
  total) instead of the old (buggy) 1.0.
- `src/test/java/com/recsys/api/serving/CatalogLoadServiceTest.java` and
  `src/test/java/com/recsys/api/serving/RecSysServerHealthLoadRouteTest.java` — both
  hand-construct a `RecallDegradationMetrics` and simulate what the recall service does;
  added the missing `recordDegradedRequest()` call so they still assert the correct
  `degradedRatio` under the new semantics (these two were not in the task's explicit file
  list but broke under the corrected counting and are exercised by the full suite).

### NEW regression tests

**`RecallDegradationMetricsTest.recordDegradedRequestCountsOncePerRequestNotPerChannel`**
(metrics-level): records two per-channel degradations (`trending`, `popularity`) for a
single simulated request, then calls `recordDegradedRequest()` once.
Asserts: `totalRecalls() == 1`, `degradedRecalls() == 1`,
`degradedRatio() <= 1.0` and `== 1.0` (bounded, not inflated).

**`MultiChannelRecallDegradationTest.multipleDegradedChannelsInOneRequestCountAsOneDegradedRequest`**
(service-level, closer to the spec's suggested reproduction): constructs a
`MultiChannelRecallService` with two non-primary channels that both always throw
(`FailingChannel("trending")`, `FailingChannel("popularity")`), calls
`recallDetailed(...)` once, and asserts:
- `result.degradedChannels()` contains both `"trending"` and `"popularity"`
- `metrics.snapshot().totalRecalls() == 1`
- `metrics.snapshot().degradedRecalls() == 1`
- `metrics.snapshot().degradedRatio() <= 1.0` (and `== 1.0` exactly, since the one
  request that occurred was degraded)

### RED/GREEN evidence

RED: temporarily reinstated the old bug (kept `degradedRecalls.incrementAndGet()` inside
`record()` alongside the new `recordDegradedRequest()` method so the build still
compiled) and ran the affected classes:

```
mvn test -Dtest=RecallDegradationMetricsTest,MultiChannelRecallDegradationTest
...
[ERROR] MultiChannelRecallDegradationTest.multipleDegradedChannelsInOneRequestCountAsOneDegradedRequest:88
expected: 1L
 but was: 3L
[ERROR] MultiChannelRecallDegradationTest.nonPrimaryChannelErrorIsRecordedAndReportedButStillServes:66
expected: 1L
 but was: 2L
[ERROR] RecallDegradationMetricsTest.recordDegradedRequestCountsOncePerRequestNotPerChannel:59
expected: 1L
 but was: 3L
[ERROR] RecallDegradationMetricsTest.snapshotCountsAndRatio:40
expected: 1L
 but was: 3L
Tests run: 9, Failures: 4, Errors: 0, Skipped: 0
```

GREEN: reverted `RecallDegradationMetrics.java` to the fixed version (diffed byte-for-byte
against the pre-RED-experiment copy to confirm exact restoration), reran the same
classes — all 9 tests passed (see covering-test run below, which supersedes this).

## MINOR fixes applied (all)

**(a)** `src/test/java/com/recsys/application/retrieval/multichannel/RecallResultTest.java`
— removed the unused `import com.recsys.domain.item.MovieCandidate;`.

**(b)** `src/main/java/com/recsys/api/serving/BaseApiService.java` — added
`import java.util.stream.Collectors;` and changed
`writeJsonWithRecallDegraded` to use `Collectors.joining(",")` instead of the
fully-qualified `java.util.stream.Collectors.joining(",")`. Behavior unchanged: still
sorted, still comma-joined.

**(c)** `src/main/java/com/recsys/application/recommendation/RecommendationOrchestrator.java`
— added a one-line comment next to the `trace.put("degradedChannels", ...)` sort noting
that `BaseApiService#writeJsonWithRecallDegraded`'s second sort (re-sorting the already
comma-sorted string split back into a `LinkedHashSet` in `RecommendationService.V2`) is a
harmless no-op kept for API independence/defense-in-depth. Neither sort was removed —
both keep their respective outputs (the `trace` map value and the HTTP header value)
deterministic independently of each other.

**(d)** `src/test/java/com/recsys/api/serving/RecSysServerIntegrationTest.java` and
`src/test/java/com/recsys/api/serving/RecSysServerRegressionTest.java` — removed the dead
`when(recallService.recall(...))` / `when(mockRecall.recall(...))` stub lines. Verified
first that `RecommendationService.V1` (the only service registered against these mocks
that touches `MultiChannelRecallService`) calls only `recallDetailed(...)` — confirmed via
`grep -n "recallService\." src/main/java/com/recsys/api/serving/RecommendationService.java`,
which shows a single call site at `recallService.recallDetailed(query, k * RECALL_MULTIPLIER)`
and no other method on the mock. The `recallDetailed(...)` stubs were kept unchanged.

## Covering-test command + output

```
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=RecallDegradationMetricsTest,MultiChannelRecallDegradationTest,RecommendationV1DegradedHeaderTest,RecommendationOrchestratorDegradedTest,CatalogLoadServiceTest,RecSysServerIntegrationTest,RecSysServerRegressionTest
```

Result (final run, after fixing `CatalogLoadServiceTest` for the corrected semantics):

```
[INFO] Running com.recsys.api.serving.RecommendationV1DegradedHeaderTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.recsys.api.serving.RecSysServerRegressionTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.recsys.api.serving.RecSysServerIntegrationTest
[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.recsys.api.serving.CatalogLoadServiceTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.recsys.application.recommendation.RecommendationOrchestratorDegradedTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.recsys.application.retrieval.multichannel.RecallDegradationMetricsTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.recsys.application.retrieval.multichannel.MultiChannelRecallDegradationTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 37, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## Full-suite run

```
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test
...
[INFO] Tests run: 1176, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Note: the first full-suite run caught one additional pre-existing test relying on the old
(buggy) counting semantics that was *not* in the task's covering-test list —
`src/test/java/com/recsys/api/serving/RecSysServerHealthLoadRouteTest.java`
(`loadServiceReflectsMetricsRecordedElsewhere`). It hand-simulates the recall service
recording into a shared `RecallDegradationMetrics` instance and asserts
`degradedRatio == 1.0`; it needed the same `recordDegradedRequest()` call added. Fixed
and the full suite went green (1176/1176) on the second run.

## Files changed

- `src/main/java/com/recsys/application/retrieval/multichannel/RecallDegradationMetrics.java`
- `src/main/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallService.java`
- `src/main/java/com/recsys/api/serving/BaseApiService.java`
- `src/main/java/com/recsys/application/recommendation/RecommendationOrchestrator.java`
- `src/test/java/com/recsys/application/retrieval/multichannel/RecallDegradationMetricsTest.java`
- `src/test/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallDegradationTest.java`
- `src/test/java/com/recsys/application/retrieval/multichannel/RecallResultTest.java`
- `src/test/java/com/recsys/api/serving/CatalogLoadServiceTest.java`
- `src/test/java/com/recsys/api/serving/RecSysServerHealthLoadRouteTest.java`
- `src/test/java/com/recsys/api/serving/RecSysServerIntegrationTest.java`
- `src/test/java/com/recsys/api/serving/RecSysServerRegressionTest.java`
