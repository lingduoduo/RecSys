# Spec: Consolidate `online/serving` enveloped handlers onto a shared base

## Objective

`OnlinePredictionService` and `OnlineFeaturesService` (port 7010) duplicate a
near-identical request-handling envelope — admission shedding, Redis rate
limiting, request parsing, the shared `recommend(...)` call, success/failure
metrics, the catch ladder, the load-shedder release, and an identical
`elapsedMs` helper. This spec extracts that envelope into one shared base and
merges both handlers (plus the base) into a single `OnlineServices.java` as
nested classes — **with zero change to routes, request parsing, response JSON,
status codes, metrics, or emitted events.**

Pure internal refactor. No behavior change.

### Who benefits
Maintainers of the online serving path — one place to change admission/rate-limit/
metrics/error handling instead of two copies that can drift.

### Success looks like
- The request envelope + `elapsedMs` exist once, in a shared base.
- The two handlers live in one file as nested classes, each contributing only its
  response shape, metrics label, and (Features) the post-success event publish.
- Every route resolves identically; `mvn test` stays green.

## Tech Stack

- Java 17, Armeria, Jackson, Micrometer; JUnit 5 + Mockito. Build: Maven.

## Commands

```bash
mvn package -DskipTests
mvn test -Dtest='OnlinePredictionRegressionTest,OnlinePredictionServerIntegrationTest,OnlineV2RecommendIntegrationTest,OnlineRecommendationServiceTest,OnlineBlendingPipelineTest'
mvn test
```

## Scope — one consolidation (merge into nested classes, as selected)

New `OnlineServices.java` — a non-instantiable namespace holding the shared base
and both handlers:

```java
public final class OnlineServices {
    private OnlineServices() {}

    /** Shared request envelope: admission shed -> rate limit -> parse -> recommend
     *  -> render -> success/failure metrics -> release. */
    abstract static class Guarded extends ApiService {
        // shared fields: recommendationService, metricsService, loadShedder,
        //                redisRateLimiter, admissionHandledExternally
        protected Guarded(OnlineRecommendationService rec, OnlineServingMetricsService metrics,
                          OnlineLoadShedder shedder, RedisRateLimiter rate,
                          boolean admissionHandledExternally) { ... }

        @Override protected final HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) { ...envelope... }

        /** Build the success response from the shared recall result. */
        protected abstract HttpResponse render(int userId, int k, OnlineRecommendationResult result);
        /** Metrics strategy label for recordSuccess. */
        protected abstract String strategyLabel(OnlineRecommendationResult result);
        /** Post-success side effect (default no-op; Features publishes an event). */
        protected void afterSuccess(int userId, OnlineRecommendationResult result) {}

        protected static long elapsedMs(long startedAtMs) { ... }
    }

    /** GET /online/recommendation (was OnlinePredictionService). */
    public static final class Prediction extends Guarded {
        // 4 public ctors + 1 package-private (rec, metrics, shedder, rate, boolean) — preserved
        @Override protected HttpResponse render(...) { /* OnlinePredictionResponse */ }
        @Override protected String strategyLabel(OnlineRecommendationResult r) { return r.strategy(); }
    }

    /** GET /online/features (was OnlineFeaturesService). */
    public static final class Features extends Guarded {
        // own asyncEventPublisher field
        // 4 public ctors + 1 package-private (rec, metrics, shedder, rate, asyncPublisher, boolean) — preserved
        @Override protected HttpResponse render(...) { /* OnlineFeatureSnapshotResponse */ }
        @Override protected String strategyLabel(OnlineRecommendationResult r) { return "features"; }
        @Override protected void afterSuccess(int userId, OnlineRecommendationResult r) { /* publish feature_view */ }
    }
}
```

The `Guarded.doGet` reproduces today's envelope exactly, in this order:
1. `startedAtMs`; if `!admissionHandledExternally && !loadShedder.tryAcquire()` →
   `recordRejected` + 429 retry-after.
2. `redisRateLimiter.tryAcquire("online")`; if not allowed → `recordRejected` + 429.
3. parse `userId` (required), `k` (1–20, default 5), `window` (query param).
4. `result = recommendationService.recommend(new OnlineRecommendationRequest(userId, window, k))`.
5. `HttpResponse resp = render(userId, k, result)`.
6. `recordSuccess(elapsedMs, strategyLabel(result))`; then `afterSuccess(userId, result)`.
7. catch: `BadRequest|IllegalArgument` → 400; `UnknownUserException` → 404;
   `Exception` → 500 — each `recordFailure`.
8. `finally`: if `!admissionHandledExternally`, `loadShedder.release()`.

> Order note: today Features calls `recordSuccess` then publishes — preserved by
> doing `afterSuccess` after `recordSuccess`.

Delete `OnlinePredictionService.java` and `OnlineFeaturesService.java`. The
response records (`OnlinePredictionResponse`, `OnlineFeatureSnapshotResponse`) and
the `featureViewEvent` builder move into their respective nested classes,
unchanged.

### Explicitly out of scope
- `ApiService` (the online-wide base, also extended by `OnlineHealthService`,
  `OnlineOpsService`, `ShardedRecordService`) — kept as-is.
- `OnlineLiveService`, `OnlineRecommendV2Service`, `OnlineBlendingPipeline`,
  `OnlineRecommendationService`, the server, and DTOs — unchanged.

## Project Structure (after)

```
online/serving/
  OnlineServices.java                 NEW — Guarded + Prediction + Features (replaces 2 files)
  ApiService.java                     (unchanged)
  OnlineLiveService.java              (unchanged)
  OnlineRecommendV2Service.java       (unchanged)
  OnlineBlendingPipeline.java         (unchanged)
  OnlineRecommendationService.java    (unchanged)
  OnlinePredictionServer.java         (wiring: new OnlineServices.Prediction/.Features)
  OnlineRecommendationRequest.java / OnlineRecommendationResult.java (unchanged)
```

## Code Style

- Container `public final` + private ctor; `Guarded` is package-private
  `abstract static`; handlers are `public static final class … extends Guarded`.
- `doGet` is `final` in `Guarded`; subclasses override only `render`/`strategyLabel`/
  `afterSuccess`.
- Preserve every status code, JSON field, metrics label, retry-after value, and the
  `feature_view` event JSON exactly.
- Keep all existing constructor signatures (public + package-private) so callers are
  unchanged apart from the `OnlineServices.` qualifier.

## Routes / contract — MUST stay identical (hard constraints)

| Route | Today | After |
|---|---|---|
| `/online/recommendation` | `OnlinePredictionService` | `OnlineServices.Prediction` |
| `/online/features` | `OnlineFeaturesService` | `OnlineServices.Features` |

Both are wrapped by `OnlineAdmissionControl(...)` in the server — that wrapping and
the `admissionHandledExternally=true` flag are preserved.

## Testing Strategy

JUnit 5 + Mockito. No new tests. Existing integration/regression/load tests pin the
HTTP behavior (status codes, 429 shedding, 404 unknown-user, JSON shape, metrics).
Mechanical construction-site updates only (same args, new qualifier):

- Server wiring (`OnlinePredictionServer`): 2 sites →
  `new OnlineServices.Prediction(...)`, `new OnlineServices.Features(...)`.
- Tests (4 sites): `OnlinePredictionServerIntegrationTest` (×2),
  `OnlinePredictionRegressionTest` (×1), `OnlinePredictionLoadTest` (×1).
  All in-package (no imports to change).

Verify: the named online tests pass, then full `mvn test` green.

## Boundaries

- **Always:** preserve routes, parsing, JSON, status codes, metrics labels,
  retry-after, and the feature_view event; preserve all constructor signatures;
  update server + tests in the same change and run them.
- **Ask first:** changing any response shape, metrics label, or the admission/
  rate-limit order; touching `ApiService` or any non-enveloped handler.
- **Never:** change wire output; alter the 429/404/400/500 mapping; delete a test
  to make the build pass.

## Success Criteria

1. `OnlineServices.java` exists with `Guarded` + `Prediction` + `Features`;
   `OnlinePredictionService.java` and `OnlineFeaturesService.java` are deleted.
2. The envelope + `elapsedMs` exist once (in `Guarded`).
3. Both routes resolve to the nested handlers; server `git diff` shows only the
   two handler-construction lines changed.
4. `mvn test` green; no file outside `online/serving/` changed.

## Open Questions

1. **Base nested name.** `Guarded` chosen for the shared base. Alternative:
   `Base`. Default: `Guarded`. Flag if you prefer otherwise.
