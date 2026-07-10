# Canonical Recommendation Gateway Entry Point Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add one canonical `POST /api/recommend` gateway endpoint that selects a recommendation strategy from JSON, defaults to `model`, and preserves every existing route as a deprecated alias.

**Architecture:** Extract the existing authenticated-request forwarding pipeline into a reusable `GatewayRequestForwarder`; its constructor is public for server assembly while forwarding operations remain package-private to the gateway application layer. Keep prefix matching and authentication in `GatewayProxyService`, and add a focused `RecommendationGatewayService` that validates JSON, removes the gateway-only `strategy`, then delegates to the same forwarder using one of the four existing recommendation routes.

**Tech Stack:** Java 17, Armeria HTTP server/client, Jackson Databind, JUnit 5, AssertJ, Maven.

## Global Constraints

- Canonical route is exact path `POST /api/recommend`.
- Supported strategies are exactly `embedding`, `model`, `online`, and `sequential`; comparison is trimmed and case-insensitive.
- Missing `strategy` defaults to `model`.
- Forward canonical requests to `/v2/recommend`, preserving the original query string.
- Remove `strategy` from the forwarded JSON object; preserve all other JSON values and structure.
- Keep all current routes and environment variables operational as deprecated aliases.
- Do not change backend request/response contracts or health aggregation.
- Canonical and legacy paths must share auth, identity propagation, credential stripping, rate limiting, circuit breaking, retry, timeout, and upstream error behavior.
- No new Maven dependencies.

---

## File structure

- Create `src/main/java/com/recsys/application/gateway/GatewayRequestForwarder.java`: shared upstream clients and policy-preserving forwarding pipeline; public construction for server wiring, package-private forwarding methods for gateway services.
- Create `src/main/java/com/recsys/application/gateway/RecommendationGatewayService.java`: exact canonical endpoint validation and strategy dispatch.
- Modify `src/main/java/com/recsys/application/gateway/GatewayProxyService.java`: retain matching/authentication and delegate forwarding.
- Modify `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java`: construct one forwarder and register the canonical exact route before the catch-all.
- Create `src/test/java/com/recsys/application/gateway/GatewayRequestForwarderTest.java`: shared header-policy regression tests.
- Create `src/test/java/com/recsys/application/gateway/RecommendationGatewayServiceTest.java`: request validation, mapping, payload rewrite, and policy integration tests.
- Modify `src/test/java/com/recsys/api/gateway/GatewayServerIntegrationTest.java`: canonical route registration and legacy-alias regression.
- Modify `src/test/java/com/recsys/application/gateway/GatewayProxyServiceTest.java`: remove tests moved to the shared forwarder.
- Modify `src/test/java/com/recsys/application/gateway/GatewayRouteTableTest.java`: assert strategy-to-route mapping and aliases.
- Modify `README.md`: document the canonical route first and synchronize the route table with code.

---

### Task 1: Extract the shared forwarding pipeline

**Files:**
- Create: `src/main/java/com/recsys/application/gateway/GatewayRequestForwarder.java`
- Modify: `src/main/java/com/recsys/application/gateway/GatewayProxyService.java`
- Create: `src/test/java/com/recsys/application/gateway/GatewayRequestForwarderTest.java`
- Modify: `src/test/java/com/recsys/application/gateway/GatewayProxyServiceTest.java`

**Interfaces:**
- Produces: public `GatewayRequestForwarder(List<MicroserviceRoute>, Duration, Map<String, RouteCircuitBreaker>, GatewayRateLimiter)`.
- Produces: `HttpResponse forward(ServiceRequestContext, AggregatedHttpRequest, MicroserviceRoute, String, GatewayPrincipal)`.
- Produces: package-visible static `RequestHeaders buildUpstreamHeaders(RequestHeaders, String, ServiceRequestContext, GatewayPrincipal)` for focused tests.
- `GatewayProxyService` keeps its public constructor unchanged and gains a package-private constructor accepting `GatewayRequestForwarder` for server wiring.

- [ ] **Step 1: Move header-policy tests to the new owner and make them fail**

Create `GatewayRequestForwarderTest` by moving both existing tests from `GatewayProxyServiceTest`, changing calls to:

```java
RequestHeaders upstream = GatewayRequestForwarder.buildUpstreamHeaders(
        incoming, "/model/predict", ctx, principal);
```

Delete the moved tests from `GatewayProxyServiceTest` only after the new test file exists.

- [ ] **Step 2: Run the focused test and verify the missing type failure**

Run: `mvn test -Dtest=GatewayRequestForwarderTest`

Expected: compilation fails because `GatewayRequestForwarder` does not exist.

- [ ] **Step 3: Extract the forwarding implementation**

Create `GatewayRequestForwarder` and move from `GatewayProxyService`:

```java
public final class GatewayRequestForwarder {
    private final Map<String, WebClient> routeClients;
    private final Map<String, RouteCircuitBreaker> circuitBreakers;
    private final GatewayRateLimiter rateLimiter;

    public GatewayRequestForwarder(List<MicroserviceRoute> routes,
                                   Duration timeout,
                                   Map<String, RouteCircuitBreaker> circuitBreakers,
                                   GatewayRateLimiter rateLimiter) {
        // Copy the existing retry rule, RetryingClient decorator, per-route
        // WebClient construction, defensive Map.copyOf, and disabled limiter fallback verbatim.
    }

    HttpResponse forward(ServiceRequestContext ctx,
                         AggregatedHttpRequest request,
                         MicroserviceRoute route,
                         String targetPath,
                         GatewayPrincipal principal) {
        // Apply the existing per-(route, principal) limiter and Retry-After headers.
        // Apply the existing route circuit breaker.
        // Build filtered upstream headers, execute the buffered request, record
        // success/failure, pass upstream responses through, and map transport
        // failures to GatewayProxyService.gatewayError(502, "upstream unreachable").
    }

    static RequestHeaders buildUpstreamHeaders(RequestHeaders incoming,
                                                String targetPath,
                                                ServiceRequestContext ctx,
                                                GatewayPrincipal principal) {
        // Move the existing implementation without semantic changes.
    }
}
```

Move `HOP_BY_HOP`, `GATEWAY_CONSUMED_CREDENTIALS`, `routeClients`, rate-limit handling, circuit-breaker handling, retry creation, upstream execution, and header helpers into this class. Keep `GatewayProxyService.gatewayError` public and unchanged so both services share the JSON error helper.

Change `GatewayProxyService.serve` to authenticate, match, rewrite, aggregate once, and delegate:

```java
return HttpResponse.of(req.aggregate().thenCompose(aggregated ->
        forwarder.forward(ctx, aggregated, route, targetPath, principal)));
```

Its existing public constructor creates a `GatewayRequestForwarder`; the package-private constructor receives one for sharing:

```java
GatewayProxyService(List<MicroserviceRoute> routes,
                    GatewayRequestForwarder forwarder,
                    GatewayAuthenticator authenticator) {
    this.routeTable = new MicroserviceRouteTable(List.copyOf(routes));
    this.forwarder = Objects.requireNonNull(forwarder, "forwarder");
    this.authenticator = authenticator == null
            ? GatewayAuthenticator.disabled() : authenticator;
}
```

- [ ] **Step 4: Run proxy and forwarder tests**

Run: `mvn test -Dtest=GatewayRequestForwarderTest,GatewayProxyServiceTest,GatewayServerIntegrationTest`

Expected: all tests pass with no behavior change on legacy routes.

- [ ] **Step 5: Commit the extraction**

```bash
git add src/main/java/com/recsys/application/gateway/GatewayRequestForwarder.java \
  src/main/java/com/recsys/application/gateway/GatewayProxyService.java \
  src/test/java/com/recsys/application/gateway/GatewayRequestForwarderTest.java \
  src/test/java/com/recsys/application/gateway/GatewayProxyServiceTest.java
git commit -m "refactor(gateway): share request forwarding pipeline"
```

---

### Task 2: Add canonical request parsing and strategy dispatch

**Files:**
- Create: `src/main/java/com/recsys/application/gateway/RecommendationGatewayService.java`
- Create: `src/test/java/com/recsys/application/gateway/RecommendationGatewayServiceTest.java`
- Modify: `src/test/java/com/recsys/application/gateway/GatewayRouteTableTest.java`

**Interfaces:**
- Consumes: `GatewayRequestForwarder.forward(...)` from Task 1.
- Produces: `public RecommendationGatewayService(List<MicroserviceRoute>, GatewayRequestForwarder, GatewayAuthenticator)`.
- Produces: package-visible static `Map<String, String> STRATEGY_ROUTES` with mappings `embedding -> embed-recall`, `model -> model-inference`, `online -> online-blend`, `sequential -> sequential`.

- [ ] **Step 1: Write failing canonical-service tests**

Build four Armeria `ServerExtension` fake upstreams, each returning its strategy name plus the received path/body. Construct the existing four `MicroserviceRoute` values with those URIs, a disabled authenticator/limiter, and closed circuit breakers. Register the service at `/api/recommend`.

Add parameterized dispatch cases:

```java
@ParameterizedTest
@CsvSource({
    "embedding,embed-recall",
    "model,model-inference",
    "online,online-blend",
    "sequential,sequential",
    "'  ONLINE  ',online-blend"
})
void dispatchesSupportedStrategies(String strategy, String expectedRoute) {
    AggregatedHttpResponse response = postJson(
            "{\"userId\":42,\"strategy\":\"" + strategy + "\"}");
    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(response.contentUtf8()).contains(expectedRoute, "/v2/recommend");
}
```

Add separate tests proving:

```java
postJson("{\"userId\":42}")                 // reaches model-inference
postJson("{\"userId\":42,\"strategy\":\"online\"}")
                                                   // forwarded JSON lacks strategy
postJson("")                                      // 400
postJson("not-json")                              // 400
postJson("[]")                                    // 400
postJson("{\"strategy\":3}")                    // 400
postJson("{\"strategy\":\"unknown\"}")          // 400 listing supported values
```

Send `GET /api/recommend` and assert `405`, JSON content type, and `Allow: POST`.

- [ ] **Step 2: Run the new tests and verify failure**

Run: `mvn test -Dtest=RecommendationGatewayServiceTest`

Expected: compilation fails because `RecommendationGatewayService` does not exist.

- [ ] **Step 3: Implement strict JSON validation and mapping**

Create the service with these core members:

```java
public final class RecommendationGatewayService implements HttpService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final Map<String, String> STRATEGY_ROUTES = Map.of(
            "embedding", "embed-recall",
            "model", "model-inference",
            "online", "online-blend",
            "sequential", "sequential");
    private static final String DEFAULT_STRATEGY = "model";

    private final Map<String, MicroserviceRoute> routesByName;
    private final GatewayRequestForwarder forwarder;
    private final GatewayAuthenticator authenticator;
}
```

In `serve`:

1. Return a JSON `405` with `Allow: POST` unless `req.method() == HttpMethod.POST`.
2. Run `authenticator.check(req.headers(), ctx.path())` and return its rejection unchanged.
3. Aggregate the body and parse it with `MAPPER.readTree`.
4. Require `ObjectNode`; require `strategy` to be textual when present.
5. Normalize with `trim().toLowerCase(Locale.ROOT)`, default to `model`, and look up the existing route name.
6. Copy with `objectNode.deepCopy()`, remove `strategy`, serialize, and construct an `AggregatedHttpRequest` whose headers preserve the incoming method/headers but whose content is the rewritten JSON.
7. Build target path `/v2/recommend` plus the original raw query and call `forwarder.forward(ctx, rewritten, route, targetPath, principal)`.

Return errors through a local helper that calls `GatewayProxyService.gatewayError(HttpStatus.BAD_REQUEST, message)`. Use stable messages: `request body must be a JSON object`, `strategy must be a string`, and `unsupported strategy; expected one of embedding, model, online, sequential`.

- [ ] **Step 4: Add route-mapping assertions**

Extend `GatewayRouteTableTest`:

```java
assertThat(RecommendationGatewayService.STRATEGY_ROUTES).containsExactlyInAnyOrderEntriesOf(
        Map.of("embedding", "embed-recall",
               "model", "model-inference",
               "online", "online-blend",
               "sequential", "sequential"));
assertThat(routes.stream().map(MicroserviceRoute::name))
        .contains("embed-recall", "model-inference", "online-blend", "sequential");
```

Retain the existing backward-compatibility assertions.

- [ ] **Step 5: Run canonical and route-table tests**

Run: `mvn test -Dtest=RecommendationGatewayServiceTest,GatewayRouteTableTest`

Expected: all tests pass, including default model dispatch and all validation cases.

- [ ] **Step 6: Commit canonical dispatch**

```bash
git add src/main/java/com/recsys/application/gateway/RecommendationGatewayService.java \
  src/test/java/com/recsys/application/gateway/RecommendationGatewayServiceTest.java \
  src/test/java/com/recsys/application/gateway/GatewayRouteTableTest.java
git commit -m "feat(gateway): add canonical recommendation endpoint"
```

---

### Task 3: Wire the canonical endpoint into the gateway

**Files:**
- Modify: `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java`
- Modify: `src/test/java/com/recsys/api/gateway/GatewayServerIntegrationTest.java`

**Interfaces:**
- Consumes: `RecommendationGatewayService` and shared `GatewayRequestForwarder` from Tasks 1–2.
- Produces: server registration order `/health`, optional specific LLM prefixes, exact `/api/recommend`, then catch-all `prefix:/`.

- [ ] **Step 1: Extend the gateway integration fixture with canonical routes**

Change the fake upstream to echo request path and body. Add four recommendation routes to the fixture using the fake URI. Construct one shared forwarder, then register:

```java
sb.service("/health", new GatewayHealthService(routes, timeout, cbs, GATEWAY_SELF_PORT))
  .service("/api/recommend", new RecommendationGatewayService(routes, forwarder, auth))
  .service("prefix:/", new GatewayProxyService(routes, forwarder, auth));
```

The exact-path service handles valid POST requests and returns the specified JSON `405` for other methods.

Add tests that `POST /api/recommend` defaults to `/v2/recommend`, that explicit `online` succeeds, and that `/api/model/...` still proxies.

- [ ] **Step 2: Run integration tests and verify canonical requests fail before wiring**

Run: `mvn test -Dtest=GatewayServerIntegrationTest`

Expected: new canonical tests fail because production-style registration is not yet represented by `MicroserviceGatewayServer`.

- [ ] **Step 3: Share one forwarder and register canonical service in production**

In `main`, after creating auth and rate limiting:

```java
GatewayRequestForwarder forwarder = new GatewayRequestForwarder(
        proxyRoutes, timeout, circuitBreakers, rateLimiter);
RecommendationGatewayService recommendationService =
        new RecommendationGatewayService(proxyRoutes, forwarder, authenticator);
```

After optional LLM route registration and before the catch-all, register the exact canonical path once with `sb.service("/api/recommend", recommendationService)`. Construct `GatewayProxyService` with the same forwarder. Do not add `/api/recommend` to `MicroserviceRoute.defaults()` or `GatewayHealthService`.

Add an info log stating that canonical recommendation routing is available and defaults to `model`.

- [ ] **Step 4: Run the gateway-focused suite**

Run: `mvn test -Dtest='*Gateway*Test'`

Expected: all gateway unit and integration tests pass; legacy aliases remain green.

- [ ] **Step 5: Commit server wiring**

```bash
git add src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java \
  src/test/java/com/recsys/api/gateway/GatewayServerIntegrationTest.java
git commit -m "feat(gateway): register canonical recommendation route"
```

---

### Task 4: Synchronize public documentation and verify the repository

**Files:**
- Modify: `README.md`

**Interfaces:**
- Documents the endpoint and route behavior produced by Tasks 1–3.

- [ ] **Step 1: Replace the stale route-table section**

At the start of `README.md`'s Microservice Gateway route table, add:

```markdown
| Method | Canonical path | Behavior |
|---|---|---|
| `POST` | `/api/recommend` | Optional JSON `strategy`: `embedding`, `model`, `online`, or `sequential`; defaults to `model` |
```

Add request examples with and without `strategy`. State that the selector is removed before forwarding.

Replace stale route names (`recommendation-retrieval`, `ranking`, `agent-workflow`, `observability`) with the exact current entries in `MicroserviceRoute.defaults()`. Mark `/api/recommend/{strategy}`, `/api/catalog`, `/api/model`, and `/api/online` as deprecated aliases that remain supported.

- [ ] **Step 2: Check documentation against code**

Run: `rg -n "recommendation-retrieval|agent-workflow|/api/recommend|embed-recall|model-inference|online-blend|sequential" README.md src/main/java/com/recsys/application/gateway/MicroserviceRoute.java`

Expected: stale route names are absent from the gateway route-table section; canonical and current route names are present in both appropriate code/docs locations.

- [ ] **Step 3: Run focused tests**

Run: `mvn test -Dtest=RecommendationGatewayServiceTest,GatewayRequestForwarderTest,GatewayProxyServiceTest,GatewayRouteTableTest,GatewayServerIntegrationTest`

Expected: all focused tests pass with zero failures and errors.

- [ ] **Step 4: Run the full test suite**

Run: `mvn test`

Expected: Maven reports `BUILD SUCCESS` with zero test failures and errors. Load-tagged tests remain excluded by the existing Maven configuration.

- [ ] **Step 5: Check the final diff**

Run: `git diff --check && git status --short`

Expected: `git diff --check` prints nothing; status lists only the intended README change before commit.

- [ ] **Step 6: Commit documentation**

```bash
git add README.md
git commit -m "docs(gateway): document canonical recommendation API"
```

- [ ] **Step 7: Record final verification evidence**

Run: `git status --short && git log -5 --oneline`

Expected: the worktree is clean and the extraction, canonical endpoint, server wiring, and documentation commits appear after the design commit.
