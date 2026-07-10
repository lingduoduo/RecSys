# Task 3 Report: Wire the canonical endpoint into the gateway

## Changes

- Constructed one `GatewayRequestForwarder` in `MicroserviceGatewayServer` and shared it between `RecommendationGatewayService` and `GatewayProxyService`.
- Registered exact `/api/recommend` after optional LLM prefixes and before the `prefix:/` catch-all.
- Preserved `/health` construction from `allRoutes`, optional LLM registration precedence, and legacy catch-all aliases.
- Added the canonical routing availability/default-model startup log.
- Made the reviewed shared-forwarder `GatewayProxyService` constructor public because production assembly and its integration fixture live in a different package.
- Extended the gateway integration fixture with all four canonical strategy routes and an upstream that echoes forwarded path/body.
- Added integration coverage for default model dispatch, explicit online dispatch, and the legacy `/api/model/...` alias.

## Files

- `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java`
- `src/main/java/com/recsys/application/gateway/GatewayProxyService.java`
- `src/test/java/com/recsys/api/gateway/GatewayServerIntegrationTest.java`

## TDD Evidence

### RED

Command:

`env JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=GatewayServerIntegrationTest`

Result: BUILD FAILURE during test compilation. The new production-style shared-forwarder fixture exposed that `GatewayProxyService(List<MicroserviceRoute>, GatewayRequestForwarder, GatewayAuthenticator)` was package-private and therefore inaccessible from `com.recsys.api.gateway`. This was the expected missing assembly interface needed by both the fixture and production server.

### GREEN

Same command after the minimal visibility and production wiring changes: BUILD SUCCESS; 9 tests run, 0 failures, 0 errors, 0 skipped.

## Exact Test Results

- `mvn test -Dtest=GatewayServerIntegrationTest`: 9 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS.
- `mvn test -Dtest='*Gateway*Test'`: 55 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS.
- `mvn test`: 889 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS.
- All commands ran with JDK 17 (`JAVA_HOME=$(/usr/libexec/java_home -v 17)`).
- `git diff --check`: clean.

## Self-review

- Registration is `/health`, optional exact LLM prefixes, exact `/api/recommend`, then `prefix:/`.
- Canonical routing uses only `proxyRoutes`; no route was added to `MicroserviceRoute.defaults()` and health remains based on `allRoutes`.
- Both canonical and legacy non-LLM requests share the same forwarder, preserving timeout, circuit-breaker, rate-limit, auth/principal, and forwarding behavior.
- Existing optional LLM factory, route registration, warmup, and shutdown behavior are unchanged.
- Integration assertions verify the canonical target path is `/v2/recommend`, the strategy discriminator is removed, and a legacy model suffix still rewrites correctly.

## Concerns

- The task brief listed two modified files, but the reviewed shared-forwarder constructor was package-private. A one-line public API visibility change in `GatewayProxyService` was necessary to satisfy the explicit shared-forwarder production assembly requirement across package boundaries.
- The build continues to emit its pre-existing mixed-Netty-version warning; it did not affect test outcomes.

## Review fixes

- Split the model and online integration fixture traffic across distinct fake upstream servers. Their JSON responses now identify the selected upstream, while retaining forwarded path and request-body assertions.
- Added exact-route coverage for `GET /api/recommend`: JSON `405 Method Not Allowed` with `Allow: POST`, proving the exact service registration handles the request ahead of the catch-all.

### RED

Command (JDK 17):

`env JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=GatewayServerIntegrationTest`

With the new identity assertions intentionally exercised against the old shared fake-upstream URI, 10 tests ran with 1 failure and 0 errors. `canonicalRecommendationRoutesExplicitOnlineStrategy` received `"upstream":"model"` instead of the required `"upstream":"online"`. This demonstrated that the prior path/body-only assertion could not prove strategy selection. The new exact-route non-POST test passed in this run.

### GREEN

- `mvn test -Dtest=GatewayServerIntegrationTest`: 10 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS.
- `mvn test -Dtest='*Gateway*Test'`: 56 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS.
- Both commands ran with JDK 17 (`JAVA_HOME=$(/usr/libexec/java_home -v 17)`).
- No production change was required; the reviewed behavior was already correct and only the integration fixture lacked observable strategy identity.
