# `/v2/recommend` request-tier protection parity — design

**Date:** 2026-07-27
**Status:** Approved (design)
**Branch:** `fix/v2-recommend-protection-parity`
**Follows:** [gateway URL versioning](2026-07-27-gateway-url-versioning-design.md) (merged as PR #233),
which recorded this gap as an explicit non-goal.

## Problem

The canonical `POST /api/recommend` routes to the **least** protected recommendation path.

With the default `model` strategy, the gateway forwards to `/v2/recommend` on 8080, served by
`RecommendationV2Controller` — a thin `pipeline.recommend(query)` passthrough. The
`/api/v1/recommend` controller it bypasses carries the request-tier protections. The same shape
exists on 7010, where `/v2/recommend` is unwrapped while `/online/recommendation` beside it is
wrapped in `OnlineAdmissionControl`.

Verified state on `main`:

| Protection | `/api/v1/recommend` (8080) | `/v2/recommend` (8080) | `/v2/recommend` (7010) |
|---|---|---|---|
| Load shed / admission | yes (`LoadShedder`) | **no** | **no** |
| Per-user rate limit | yes (`ModelRateLimiter`) | **no** | n/a |
| Submit-token CSRF | yes | no | n/a |
| Inference metrics | yes | **no** | **no** |
| A/B assignment | yes | yes (inside `OnnxInferencePipeline`) | n/a |
| A/B exposure logging | yes | **no** | n/a |
| Degraded-cache fallback | yes | no | no |

Two corrections to how this gap was previously described, both established by reading the code:

- **A/B assignment is not missing.** `OnnxInferencePipeline` calls
  `abTestService.getAssignmentForUser(query.userId())` and writes `abTestVariant` and
  `modelVersion` into the result trace. Bucketing is therefore already consistent between paths;
  only the Kafka exposure event is absent.
- **The metrics gap has an operational consequence that was not previously traced.**
  `HealthController` computes 8080 readiness from `metricsService.snapshot()`. Because the v2 path
  records nothing, the `high failure rate` and `high inference latency` readiness reasons are
  blind to all canonical-path traffic — the endpoint the gateway actually routes to.

## Goal

Give the v2 routes the protections whose absence has real operational consequence, without
duplicating V1's guard logic and without changing the v2 request or response contract.

## Scope

**In:** load shed / admission on both services, inference metrics, per-user rate limiting, and A/B
exposure logging.

**Out, deliberately:**

- **Submit-token CSRF.** Disabled by default (`RECSYS_SUBMIT_TOKEN_ENABLED=false`) and aimed at a
  browser-style submit flow, not the gateway-to-backend hop this path serves.
- **Degraded-cache fallback.** V1's `tryServeFromCache` returns `RecommendResponse`; v2 speaks
  `RecommendationResult`. Reuse is impossible, so this would be a parallel implementation rather
  than a wiring change — its own piece of work.
- **`RecommendationV2Controller` itself.** It stays an unmodified passthrough.
- **V1 behavior**, apart from extracting one shared helper (§3).

## 1. Components

**7010 — no new abstraction.** Wrap the existing service in the existing decorator, matching
`/online/recommendation` directly above it:

```java
.service("/v2/recommend",
        new OnlineAdmissionControl(new OnlineServices.RecommendV2(blendingPipeline),
                loadShedder, metricsService))
```

`OnlineAdmissionControl` already calls `recordRejected()` on the metrics service it is given, so
this single change delivers both admission control and rejection metrics.

**8080 — a new `ProtectedRecommendationPipeline`** in `com.recsys.application.recommendation`,
implementing `RecommendationPipeline` and decorating a delegate:

```java
public final class ProtectedRecommendationPipeline implements RecommendationPipeline {
    ProtectedRecommendationPipeline(RecommendationPipeline delegate,
                                    ModelRateLimiter rateLimiter,
                                    LoadShedder loadShedder,
                                    InferenceMetricsService metrics,
                                    ABTestService abTestService,
                                    AbExposureLogger exposureLogger);
    @Override public RecommendationResult recommend(RecommendationQuery query);
}
```

All five collaborators are already Spring beans. The decorator is applied in
`ModelRecommendationPipelineConfig`, which already builds the `onnxRecommendationPipeline` bean;
the wrapper simply wraps what that method returns today.

`RecommendationV2Controller` needs no edit: it injects `@Qualifier("onnxRecommendationPipeline")`,
and that qualifier now resolves to the protected wrapper.

### Why a pipeline decorator rather than inline guards or a filter

- **Inline in the controller** (mirroring V1) would duplicate roughly forty lines of guard
  sequencing across two controllers, including the ordering rationale, and the two would drift.
- **A Spring interceptor or filter** is the wrong seam: the rate limiter keys on the *served*
  `userId`, which lives in the request body. An interceptor would have to parse or buffer the body
  to obtain it.
- **The decorator** sits behind an interface both services already share, keeps the controller a
  passthrough, is unit-testable without standing up MVC, and — because it throws the same
  exceptions V1 throws — inherits V1's exact HTTP contract with no new mapping code.

### `/v2/sequential/recommend` is deliberately NOT wrapped

`SequentialRecommendationPipeline` is a fifteen-line stub that unconditionally throws
`PipelineNotImplementedException`, mapped to `501`. Wrapping it would take and release a load-shed
permit and record an `InferenceMetricsService` **failure** on every call, feeding the
`high failure rate` readiness signal from an endpoint known to be unimplemented — actively
degrading the signal this change exists to restore. It stays a bare `501`.

This is a decision, not an oversight, and §4 pins it with a test.

## 2. Behavior contract

```
1. rateLimiter.tryAcquire(query.userId())   deny → throw RateLimitExceededException  → 429
2. assignment = abTestService.getAssignmentForUser(query.userId())
3. loadShedder.tryAcquire()                 deny → recordFailure(0, assigned)
                                                   throw ServiceOverloadedException → 503
4. try     result = delegate.recommend(query)
           recordSuccess(elapsedMs, servedVariant, modelVersion)      // from result.trace()
           exposureLogger.log(userId, assignment, servedVariant, fellBackFrom, modelVersion)
   catch   recordFailure(elapsedMs, assignment.variant()); rethrow
   finally loadShedder.release()
```

**The ordering is load-bearing**, inherited from V1: the per-user rate check runs *before* the
shared semaphore so a single user cannot burn concurrency slots other users need before being
limited. The decorator carries the same explanatory comment V1 does.

**Statuses require no new code.** `GlobalExceptionHandler` already maps
`RateLimitExceededException` to `429` with `Retry-After` and `ServiceOverloadedException` to `503`,
so v2 inherits V1's contract exactly.

**`fellBackFrom` is derived by recomputing the assignment** and comparing it against the trace's
`abTestVariant`, rather than threading the assignment out of the pipeline or pushing Kafka into it.
This is sound only if `getAssignmentForUser` is deterministic for a fixed `userId`. Implementation
must prove that with a test before relying on it; if it does not hold, the fallback is to expose
the assignment from the pipeline.

**Trace values are read defensively**, with a stated fallback rather than "handle it somehow".
`OnnxInferencePipeline` always writes both keys, but nothing in the `RecommendationPipeline`
interface guarantees it, and the decorator is typed against the interface. Specifically:

- `servedVariant` missing or blank → fall back to `assignment.variant()`, and treat
  `fellBackFrom` as `false` (no evidence of a fallback, so do not claim one).
- `modelVersion` missing or blank → pass the empty string, which is what
  `OnnxInferencePipeline` itself writes when the response carries no version.

A request that otherwise succeeded is never failed because a trace key was absent.

## 3. Shared helper extraction

`retryAfterSeconds(InferenceMetricsService.Snapshot)` is currently a private static in
`RecommendationController`, deriving a `Retry-After` from rolling average latency. The decorator
needs the same value.

It moves to a **public static method on `InferenceMetricsService`** — the type it already takes as
its only parameter, so the logic ends up beside the data it reads rather than in a new utility
class. `RecommendationController` is updated to call it and its private copy is deleted. The body
is moved verbatim; behavior is unchanged. This is the only edit to V1 in the whole change.

## 4. Testing

Every behavior gets a test verified to go **red when the change is reverted**, confirmed by
performing the revert rather than by inspection.

**`ProtectedRecommendationPipelineTest`** (unit, no MVC):

- rate-limit denial throws `RateLimitExceededException`
- shed denial throws `ServiceOverloadedException` and records a failure
- success records `recordSuccess` with the trace's variant and model version
- a delegate exception records a failure and rethrows
- the permit is released on both the success and the failure path
- **ordering:** with the limiter denying, `loadShedder.tryAcquire` is never called — this is what
  pins "limit before semaphore"; without it a reorder passes silently
- **determinism:** `getAssignmentForUser` returns the same variant across repeated calls for one
  `userId`, since `fellBackFrom` correctness depends on it
- trace values absent or blank do not fail the request

**The negative test that pins a deliberate decision:** `/v2/sequential/recommend` still returns
`501` and records **no** failure metric. Without it, a later well-meaning change wraps the stub and
quietly corrupts readiness.

**7010:** an integration test that `/v2/recommend` sheds to `429` when the shedder is saturated,
red if the `OnlineAdmissionControl` wrap is removed.

> **Corrected during implementation.** This section originally said `503`. `OnlineAdmissionControl`
> actually sheds with `429` + `Retry-After`; only 8080's `ServiceOverloadedException` maps to `503`.
> The error was caught by the test failing, not by review, and the shipped
> `OnlineV2RecommendIntegrationTest` asserts `429`. The two services genuinely differ here — see
> [18_Fault_Tolerance](../../system_design/18_Fault_Tolerance.md) and
> [the overload-protection runbook](../../runbooks/overload-protection.md), which are authoritative.

**Readiness:** a test that v2 traffic now moves `metricsService.snapshot()` — the operational point
of the change.

## 5. Rollout and risks

**No new configuration.** `ModelRateLimiter` defaults to `rps=0` (disabled), so v2 gains the
limiter as a no-op until `RECSYS_MODEL_RATE_LIMIT_RPS` is set — matching V1's behavior today. Load
shedding and metrics become active immediately, which is the intent.

**Risk 1 — `/health/ready` may begin reporting degraded where it previously looked healthy.** That
is the fix working: readiness was blind to canonical-path traffic and is now measuring it. It will
nonetheless look like a new problem to whoever sees it first, so it belongs in the operational
notes rather than only in a commit message.

**Risk 2 — exposure events on the canonical path increase `ab_exposures` volume.**
`AsyncEventPublisher` drops on overflow rather than blocking, so it fails open and cannot stall a
request. Topic headroom is worth confirming before this reaches production.

**Risk 3 — the decorator sits on the hot path.** It adds one map lookup, one hash-bucket
computation, and one asynchronous publish per request. The A/B assignment is computed twice per
request (once inside the pipeline, once in the decorator for `fellBackFrom`); this is a hash
computation, not I/O, and the alternative couples the pipeline to Kafka.
