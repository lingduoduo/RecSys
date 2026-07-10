# Final Review Fixes Report

## Status

Implemented and committed the requested final-review coverage additions. Runtime verification, previously blocked by the codex sandbox's loopback-bind restriction, has now been completed outside the sandbox on JDK 17 — see "Runtime verification (completed)" below.

Original blocker (for the record): test execution was blocked by the codex environment's loopback-bind restriction and exhausted escalation allowance; JDK 17 test compilation completed successfully before Armeria setup failed.

## Changes

- Added a canonical endpoint integration test using a real `GatewayRateLimiter` built from a deterministic environment reader and fixed ticker. It consumes the `online-blend` burst, asserts the second online request returns exact `429`, `Retry-After`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and body values, then proves the unconfigured embedding route still succeeds.
- Added a canonical endpoint integration test with a real pre-opened `online-blend` `RouteCircuitBreaker`. It asserts online selection returns `503` naming that route and model selection still reaches its upstream.
- Added canonical non-2xx passthrough coverage through the real `GatewayRequestForwarder`, asserting exact upstream `422` status, `X-Upstream-Result` response header, and JSON body.
- Deleted the empty `GatewayProxyServiceTest` marker class.
- No production source, specification, plan, or progress file was changed.

## Files

- Modified: `src/test/java/com/recsys/application/gateway/RecommendationGatewayServiceTest.java`
- Deleted: `src/test/java/com/recsys/application/gateway/GatewayProxyServiceTest.java`
- Added report: `.superpowers/sdd/final-fixes-report.md`

## Pre-change coverage gap

The production forwarding behavior already existed, so no artificial failing RED was manufactured. Before this change, canonical endpoint tests covered routing, validation, authentication, and request-header reconstruction, but did not exercise a configured rate limiter, an open selected-route circuit breaker, or a non-2xx upstream response through the canonical service. The gateway server test's breaker check only called `RouteCircuitBreaker` directly and never issued a canonical HTTP request.

Each new policy test is selection-sensitive: the rate-limit fixture configures only `online-blend` and verifies embedding remains successful; the breaker fixture opens only `online-blend` and verifies model remains successful. Bypassing canonical selection or applying policy to a synthetic canonical route would fail these assertions.

## Verification commands and counts

All Maven commands used JDK 17 via `JAVA_HOME=$(/usr/libexec/java_home -v 17)`.

### Focused test attempt

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=RecommendationGatewayServiceTest
```

Outcome: Maven compiled all 164 test source files successfully with Java release 17. Surefire then reported 1 setup error before executing test methods because Armeria could not bind a local port in the sandbox: `bind(..) failed: Operation not permitted`. Therefore there is no valid focused pass count to report. The class contains 21 test invocations after this change (18 existing plus 3 new), but that is a static expected count, not an execution result.

The required outside-sandbox rerun was requested immediately. Automatic approval was rejected because the environment had exhausted its usage limit, so the focused suite and full suite could not be executed. The prior reviewed HEAD evidence was 890 full-suite tests passing; with the three new invocations the expected full-suite count is 893, but this was not claimed as a verified result.

### Static checks

```bash
git diff --check
git status --short
```

Outcome before commit: `git diff --check` exited 0 with no output; status listed only the two intended test-source changes (plus this report after it was created).

## Self-review

- Both policy fixtures use the actual `RecommendationGatewayService` and `GatewayRequestForwarder`; the forwarder is not mocked.
- The rate limiter is created using the public environment factory, route-specific online settings, and a constant ticker, making burst exhaustion and retry headers deterministic.
- Dedicated fixtures prevent mutable limiter/breaker state from leaking into the existing canonical tests.
- The breaker contrast request proves that a healthy strategy remains forwardable while online is open.
- The passthrough assertion matches the current aggregated-response behavior: status, ordinary end-to-end response headers, and body are returned from `aggResp.toHttpResponse()`.
- The empty marker test was removed as requested.
- Design, task briefs, and `.superpowers/sdd/progress.md` were not modified.

## Runtime verification (completed)

Rerun outside the codex sandbox with `JAVA_HOME=$(/usr/libexec/java_home -v 17)`; loopback binding succeeded, so Armeria-backed integration tests executed.

### Focused gateway suite

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test \
  -Dtest='RecommendationGatewayServiceTest,GatewayRequestForwarderTest,GatewayRouteTableTest,GatewayServerIntegrationTest'
```

Result: **BUILD SUCCESS**, 39 tests, 0 failures, 0 errors, 0 skipped.

- `GatewayServerIntegrationTest` — 10 (Armeria bound successfully; the prior sandbox setup error is gone)
- `RecommendationGatewayServiceTest` — 21 (matches the predicted 18 existing + 3 new policy tests)
- `GatewayRouteTableTest` — 6
- `GatewayRequestForwarderTest` — 2

### Full suite

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test
```

Result: **BUILD SUCCESS**, **893 tests, 0 failures, 0 errors, 0 skipped** — exactly the predicted count (890 prior HEAD + 3 new invocations).

## Concerns

- Runtime verification is no longer outstanding; both the focused gateway suite and the full Maven suite pass on JDK 17 (see above).
- The pre-existing mixed-Netty-version warning still appears at startup; it is benign and was already documented in earlier successful suite runs.
