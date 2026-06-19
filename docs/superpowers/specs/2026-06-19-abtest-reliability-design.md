# A/B Test Reliability (Sub-project 2 of 3)

_Date: 2026-06-19_
_Scope: Port 8080 (`com.recsys.model`) — make A/B bucketing stable, log exposures for experiment analysis, and fall back safely when an assigned variant's model artifacts fail to load._
_Depends on: sub-project 1 (model-serving retrieval→ranking via shared recall, PR #129, branch `feat/model-serving-recall-adoption`). This work stacks on that branch._
_Followed by: sub-project 3 (real-time user-tower features)._

---

## 1. Problem Statement

Port 8080's `ABTestService` buckets users with `(("userId:layer").hashCode() & Integer.MAX_VALUE) % trafficSplitNumber`, mapping bucket `0 → A`, `1 → B`, all others → default ([ABTestService.java:75-82](../../src/main/java/com/recsys/model/service/ABTestService.java)). Three reliability gaps:

1. **Unstable bucketing.** Changing `trafficSplitNumber` changes the modulo base, **reshuffling every user** across buckets — you cannot adjust traffic allocation without scrambling who is in the experiment. `String.hashCode` also distributes sequential numeric userIds (`"1"`, `"2"`, …) poorly, so buckets are uneven.
2. **No exposure logging.** Assignments are only `log.debug`'d ([ABTestService.java:62](../../src/main/java/com/recsys/model/service/ABTestService.java)). There is no event stream recording who saw which variant, so experiments cannot be analyzed (lift = join exposures with outcomes). Port 8080 has no event-emit path today (`AsyncEventPublisher`/`LogCollector` live on the online/7010 side).
3. **No safe fallback.** If an assigned variant's artifacts fail to load, `ModelRuntimeProvider.getRuntime(variant)` throws ([ModelRuntimeProvider.java:118-143](../../src/main/java/com/recsys/model/service/ModelRuntimeProvider.java)), so every bucketed user gets a `500` during a bad experiment rollout. `computeIfAbsent` does not cache the exception, so the failing ONNX build is re-paid on every request.

Minor: `ABTestConfig.defaultVariant` is a mutable `volatile` read per call ([ABTestConfig.java:30](../../src/main/java/com/recsys/config/ABTestConfig.java)).

---

## 2. Chosen Approach

- **Stable bucketing (hash-to-keyspace + range allocation):** hash `userId:layer` with a well-distributed deterministic 64-bit hash into a fixed keyspace `[0, 10000)`; allocate variants as ordered ranges from config percentages. Changing an allocation moves only a boundary — users keep their slot, no global reshuffle.
- **Exposure logging (async Kafka):** emit a structured `ExposureEvent` per served request, non-blocking, via a reused `AsyncEventPublisher` to an `ab_exposures` topic; the offline pipeline joins exposures to outcomes.
- **Safe missing-variant fallback (serve control, log honestly):** if the assigned variant's runtime cannot load, serve the default/control runtime; the response and the exposure record the **served** variant (control) plus a `fellBackFrom` flag, with a metric/alert — so lift analysis is not polluted by phantom exposures to a broken variant. A cooldown guard prevents re-paying the failing build every request.

**Decisions settled in brainstorming:**
- Bucketing: hash-to-keyspace + range allocation (not modulo-stabilize, not a persistent store).
- Exposure logging: async event to Kafka (not log-line, not metrics-only).
- Fallback: serve control + log fallback honestly (not report-assigned, not fail-fast).

**Invariant:** the HTTP contract is unchanged — `RecommendResponse{userId, modelVersion, abTestVariant, recommendations}`. `abTestVariant` now always reports the **served** variant. With default config (A/B disabled), behavior is identical to today (every user → default variant, no exposure events when disabled).

---

## 3. Architecture

```
POST /api/v1/recommend
   1. assignment = abTestService.getAssignmentForUser(userId)         ← stable bucketer
        slot = StableBucketer.slot(userId, layer)            ∈ [0,10000)
        variant = ranges(aPct,bPct).variantFor(slot)          A | B | control
   2. resolved = variantRuntimeResolver.resolve(assignment.variant(), defaultVariant)
        → (runtime, servedVariant, fellBack)   (fallback to control if assigned unloadable)
   3. response = recommendationService.recommend(request, resolved)   ← uses servedVariant for cache key + abTestVariant
   4. abExposureLogger.log(userId, assignment, servedVariant, fellBack, modelVersion)
        → AsyncEventPublisher.publish(json)   (non-blocking, bounded, drops under backpressure)
   5. return response   (abTestVariant = servedVariant)
```

`StableBucketer`, `VariantRuntimeResolver`, and `AbExposureLogger` are small, independently-testable units. The controller orchestrates; `ABTestService` owns bucketing; `ModelRuntimeProvider` owns runtime resolution.

Sub-project 3 adds real-time user-tower features; it does not depend on this.

---

## 4. Components

### 4.1 `StableBucketer` (new — `model/service/StableBucketer.java`)

```java
public final class StableBucketer {
    public static final int KEYSPACE = 10_000;
    public static int slot(String userId, String layerName);   // murmur3-style 64-bit hash of "userId:layer", mod KEYSPACE, non-negative
}
```

- Deterministic, well-distributed 64-bit finalizer over the UTF-8 bytes of `userId + ":" + layerName` (no external dependency; stable across JVMs). Maps to `[0, KEYSPACE)`.
- A separate `VariantAllocation` value (built from config) maps a slot to a variant via ordered ranges:
  - `A = [0, aPct·100)`, `B = [aPct·100, (aPct+bPct)·100)`, control = `[(aPct+bPct)·100, KEYSPACE)`.
  - `slot < aRange → A`; `slot < a+bRange → B`; else control. Changing `bPct` moves only the B/control boundary; `A` users are untouched.

### 4.2 `ABTestConfig` (modify — `config/ABTestConfig.java`)

- **Remove** `trafficSplitNumber`. **Add** `int bucketAPercent` (default `20`) and `int bucketBPercent` (default `20`); validation: each `>= 0`, `bucketAPercent + bucketBPercent <= 100`. Control receives the remainder. Defaults preserve today's 20/20/60 split exactly.
- `defaultVariant` drops `volatile`. Keep `enabled`, `bucketAVariant`, `bucketBVariant`, `defaultVariant`, `layerName`.
- `application.yml` updated (`traffic-split-number` replaced by `bucket-a-percent` / `bucket-b-percent`). Leftover unknown properties are ignored by Spring binding, so this is non-breaking for deployed configs.

### 4.3 `ABTestService` (modify — `model/service/ABTestService.java`)

- Reads an **immutable snapshot** of the relevant config fields once per assignment (enabled, percents, variant names, layer, default) so allocation is consistent within a request.
- `getAssignmentForUser` uses `StableBucketer.slot` + `VariantAllocation` instead of `resolveBucket`/modulo. Disabled / blank userId → `Assignment.control(defaultVariant, layer)` (unchanged).
- `Assignment` record gains `int slot` (the keyspace position) for exposure logging; existing `variant`, `layerName`, `inExperiment` retained; `bucket` is replaced by `slot` (callers updated). The served-vs-assigned distinction is carried by the resolver (§4.4), not the Assignment.

### 4.4 `VariantRuntimeResolver` (new — `model/service/VariantRuntimeResolver.java`)

```java
public record Resolved(ModelRuntime runtime, String servedVariant, boolean fellBack) {}
public Resolved resolve(String assignedVariant, String defaultVariant);
```

- Tries `modelRuntimeProvider.getRuntime(assignedVariant)`. On success → `(runtime, assignedVariant, false)`.
- On `RuntimeException` (build/load failure): record `assignedVariant` in a **failed-variant cooldown map** (default `60_000 ms`), increment `recsys.abtest.variant_fallback` (tagged by variant), log a warning, and return `(getRuntime(defaultVariant), defaultVariant, true)`.
- On a subsequent request, if `assignedVariant` is in cooldown, skip straight to the default runtime (no rebuild attempt) until the cooldown expires, then allow one retry — so a redeployed/fixed artifact recovers without a process restart, but a persistently-broken variant never causes a retry storm.
- If the **default** variant also fails to load, the exception propagates (a broken control is a genuine outage — not silently masked).

### 4.5 `AbExposureLogger` (new — `model/service/AbExposureLogger.java`)

```java
public void log(String userId, ABTestService.Assignment assignment,
                String servedVariant, boolean fellBack, String modelVersion);
```

- Builds an `ExposureEvent` record → JSON: `{userId, assignedVariant, servedVariant, fellBackFrom (assignedVariant when fellBack, else null), layer, slot, inExperiment, modelVersion, eventId (UUID), timestampMs}`.
- Publishes non-blocking via an injected `AsyncEventPublisher` (the online side's bounded, fire-and-forget path) to topic `ab_exposures`. Under backpressure the publisher drops; the request never blocks or fails on exposure logging.
- **No-op when A/B is disabled** (`!config.isEnabled()`) — no exposure spam for the all-control default.

### 4.6 `RecommendationController` / `RecommendationService` (modify)

- Controller: compute `assignment` once (as today), call `variantRuntimeResolver.resolve(...)`, pass the `Resolved` to the service, then `abExposureLogger.log(...)` after the response is produced (so it reflects the served variant, including fallback).
- `RecommendationService.recommend(request, resolved)` uses `resolved.runtime()` and `resolved.servedVariant()` for the cache key (`RecommendationKey` variant field) and `RecommendResponse.abTestVariant`. The degraded-cache path (`tryServeFromCache`) likewise keys on the served variant.
- `metricsService.recordSuccess/Failure` continue to be tagged by the served variant.

---

## 5. Data Flow & Behavior

- **A/B disabled (default):** every user → default variant via `Assignment.control`; no resolver fallback needed (default loads); no exposure events. Byte-identical to today.
- **A/B enabled, healthy variants:** stable slot → A/B/control by range; exposure event per served request; `abTestVariant` = assigned = served.
- **A/B enabled, assigned variant broken:** resolver serves control, `abTestVariant` = control, exposure carries `fellBackFrom = <assigned>`; metric/alert fires; cooldown prevents rebuild storms.
- **Allocation change (e.g. B 20%→30%):** only slots near the B/control boundary move; A users and most B/control users keep their assignment.

---

## 6. Error Handling

| Condition | Behavior |
|---|---|
| Assigned variant artifacts fail to load | Serve control runtime; `fellBack=true`; metric + warning; cooldown guard |
| Default/control variant also fails | Exception propagates (genuine outage, not masked) |
| Exposure publisher queue full | Event dropped (bounded, non-blocking); request unaffected |
| A/B disabled / blank userId | `Assignment.control`; no exposure event |
| Config percents invalid (sum > 100, negative) | Bean-validation rejection at startup |

---

## 7. Testing Strategy

### New
- `StableBucketerTest` — determinism (same input → same slot); uniform distribution across the keyspace for sequential and varied ids (chi-square-ish bucket-count tolerance); `VariantAllocation` range boundaries; **no-reshuffle**: changing `bucketBPercent` leaves every A-slot and most B/control slots' variant unchanged.
- `VariantRuntimeResolverTest` — healthy variant returns itself; unloadable variant → control + `fellBack=true` + metric; cooldown skips rebuild within window then retries after; default-fails → propagates.
- `AbExposureLoggerTest` — event fields (incl. `fellBackFrom` set on fallback, null otherwise; served vs assigned); publishes via the injected publisher; no-op when disabled; non-blocking (does not throw on a full/failing publisher).

### Reworked
- `ABTestServiceTest` — percentages instead of `trafficSplitNumber`; slot-based assignment; immutable snapshot; disabled/blank → control; default-split (20/20/60) distribution.
- `RecommendationControllerTest` / `RecommendationControllerRegressionTest` / `ModelV2RecommendIntegrationTest` — served-variant in response + cache key; exposure emitted once per request; fallback path returns 200 with control.

### Pass unmodified
- Shared-recall + SP1 tests; cache / rate-limit / submit-token / load-shed tests; `RecommendationServiceTest` except where the runtime is now supplied via `Resolved`.

### Full-suite
- `mvn test` green; `application.yml` migration verified by context load.

---

## 8. Out of Scope

- Multi-layer experiments, mutual exclusion, holdback/holdout groups.
- Persistent per-user assignment store (stable hashing provides stickiness).
- Exposure sampling/dedup tuning and a dedicated Kafka transport subclass (the existing `AsyncEventPublisher` abstraction is reused; transport wiring is deployment config).
- Propagating a request/session id from exposure into downstream outcome events (deeper data lineage).
- Real-time user-tower features — sub-project 3.

---

## 9. Files Changed

| File | Change |
|---|---|
| `model/service/StableBucketer.java` | New — well-distributed hash → keyspace slot + range allocation |
| `config/ABTestConfig.java` | Replace `trafficSplitNumber` with `bucketAPercent`/`bucketBPercent`; drop `volatile` on `defaultVariant` |
| `model/service/ABTestService.java` | Slot-based stable bucketing; immutable config snapshot; `Assignment` carries `slot` |
| `model/service/VariantRuntimeResolver.java` | New — serve-control fallback + cooldown guard + metric |
| `model/service/AbExposureLogger.java` | New — async `ExposureEvent` → Kafka via `AsyncEventPublisher` |
| `model/controller/RecommendationController.java` | Resolve runtime (fallback-aware); emit exposure once; served-variant response |
| `model/service/RecommendationService.java` | Accept resolved runtime + served variant; cache key on served variant |
| `src/main/resources/application.yml` | `traffic-split-number` → `bucket-a-percent` / `bucket-b-percent` |
| `src/test/.../model/service/StableBucketerTest.java` | New |
| `src/test/.../model/service/VariantRuntimeResolverTest.java` | New |
| `src/test/.../model/service/AbExposureLoggerTest.java` | New |
| `src/test/.../model/service/ABTestServiceTest.java` | Rework for percentages + slots |
| `src/test/.../model/controller/RecommendationControllerTest.java` | Served-variant + exposure + fallback |
