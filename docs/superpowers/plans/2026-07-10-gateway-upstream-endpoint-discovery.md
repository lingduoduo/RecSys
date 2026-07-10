# Health-Aware Upstream Discovery (Option A1) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **As-built deviations (kept for the record):** During TDD the endpoint-group
> mechanism changed from the sketch below. `DnsAddressEndpointGroup` was dropped —
> it issues Netty DNS *queries* that fail on literal IPs / `/etc/hosts` names — in
> favor of a static `Endpoint.of(host, port)` wrapped in the
> `HealthCheckedEndpointGroup`, so host resolution stays with Armeria's default
> per-connection resolver (unchanged from the previous plain-`WebClient` path).
> Consequently the `GATEWAY_UPSTREAM_DNS_TTL_MAX_S` knob was removed. Two
> robustness additions: the group is built with `allowEmptyEndpoints(false)` so an
> all-unhealthy group fails a selection immediately (→ 503) rather than waiting out
> the selection timeout, and `UpstreamEndpointGroups.create(...)` performs a
> bounded, non-fatal wait on `EndpointGroup.whenReady()` so an already-up upstream
> is selectable on the first request instead of racing a cold health check. The
> integration test asserts the two deterministic outcomes (healthy → forwarded,
> no-healthy-endpoint → fast 503) rather than timing a live health flip. See the
> updated design doc for the authoritative description.

**Goal:** Give the gateway's data path application-layer, health-aware upstream discovery — resolve each upstream through an Armeria endpoint group (TTL-driven DNS refresh) wrapped in a health-checked group so a down upstream is dropped from selection and the gateway fast-fails with 503 instead of forwarding into a black hole. ClusterIP/kube-proxy/PreferClose are untouched.

**Architecture:** A new package-internal `UpstreamEndpointGroups` builds, per unique `(host, port, healthPath)`, a `HealthCheckedEndpointGroup(DnsAddressEndpointGroup)` (or a plain `DnsAddressEndpointGroup` when health checks are disabled), shares it across all routes mapping to that backend, and exposes a `WebClient` per route name plus `close()` for the owned groups. `GatewayRequestForwarder` builds its clients through it, becomes `Closeable`, and `MicroserviceGatewayServer`'s shutdown hook closes it.

**Tech Stack:** Java 17, Armeria 1.28.4 (`DnsAddressEndpointGroup`, `HealthCheckedEndpointGroup`, `EndpointGroup`, `SessionProtocol`, `EmptyEndpointGroupException`), JUnit 5, AssertJ, Maven. Build with `JAVA_HOME=$(/usr/libexec/java_home -v 17)`.

## Global Constraints

- JDK 17 for all Maven commands: prefix with `JAVA_HOME=$(/usr/libexec/java_home -v 17)`.
- No Kubernetes/ConfigMap/Cloud Map changes. No changes to `LlmProxyService` or `GatewayHealthService`.
- Preserve existing routing, auth, rate-limit, retry (one retry-on-IOException, 50 ms backoff, max 2 attempts), circuit-breaker, and timeout behavior — compose the endpoint group with them, don't replace them.
- Armeria API facts (verified against 1.28.4): `DnsAddressEndpointGroup.builder(String host)` → `.port(int)` `.ttl(int minSec,int maxSec)` `.selectionTimeout(Duration)` `.build()`. `HealthCheckedEndpointGroup.builder(EndpointGroup, String path)` → `.protocol(SessionProtocol)` `.retryIntervalMillis(long)` `.selectionTimeoutMillis(long)` `.build()`. `WebClient.builder(SessionProtocol, EndpointGroup)`. `EndpointGroup extends AutoCloseable` (`close()` is synchronous). `EmptyEndpointGroupException` in `com.linecorp.armeria.client.endpoint`.
- Never merge to main directly; work stays on branch `feat/gateway-upstream-endpoint-discovery` and integrates via PR.
- Commit after each task.

---

### Task 1: `UpstreamEndpointGroups` — build, dedup, and close

**Files:**
- Create: `src/main/java/com/recsys/application/gateway/UpstreamEndpointGroups.java`
- Test: `src/test/java/com/recsys/application/gateway/UpstreamEndpointGroupsTest.java`

**Interfaces:**
- Consumes: `MicroserviceRoute` (`name()`, `baseUri()`, `healthPath()`), `EnvVars`.
- Produces:
  - `record HealthCheckConfig(boolean healthCheckEnabled, long healthCheckIntervalMs, int dnsTtlMaxSeconds)` with `static HealthCheckConfig fromEnvironment()`.
  - `static UpstreamEndpointGroups create(List<MicroserviceRoute> routes, Duration responseTimeout, Function<? super com.linecorp.armeria.client.HttpClient, ? extends com.linecorp.armeria.client.HttpClient> decorator, HealthCheckConfig config)`.
  - Instance methods `WebClient clientFor(String routeName)`, `int groupCount()` (test/introspection), and `void close()` (closes every owned `EndpointGroup` once).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/application/gateway/UpstreamEndpointGroupsTest.java`:

```java
package com.recsys.application.gateway;

import com.linecorp.armeria.client.WebClient;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UpstreamEndpointGroupsTest {

    private static MicroserviceRoute route(String name, String baseUri, String healthPath) {
        return new MicroserviceRoute(name, "/api/" + name, name.toUpperCase() + "_URL",
                URI.create(baseUri), healthPath);
    }

    private static UpstreamEndpointGroups.HealthCheckConfig cfg(boolean enabled) {
        return new UpstreamEndpointGroups.HealthCheckConfig(enabled, 10_000L, 30);
    }

    @Test
    void dedupsRoutesSharingHostPortAndHealthPath() {
        List<MicroserviceRoute> routes = List.of(
                route("a", "http://localhost:6010", "/health"),
                route("b", "http://localhost:6010", "/health"),   // same authority + health path as a
                route("c", "http://localhost:8080", "/health/ready"));
        UpstreamEndpointGroups groups = UpstreamEndpointGroups.create(
                routes, Duration.ofSeconds(3), null, cfg(true));
        try {
            // Two unique (host,port,healthPath) keys -> two endpoint groups.
            assertThat(groups.groupCount()).isEqualTo(2);
            // But every route still resolves to its own WebClient.
            assertThat(groups.clientFor("a")).isInstanceOf(WebClient.class);
            assertThat(groups.clientFor("b")).isInstanceOf(WebClient.class);
            assertThat(groups.clientFor("c")).isInstanceOf(WebClient.class);
        } finally {
            groups.close();
        }
    }

    @Test
    void healthCheckDisabledStillBuildsAClientPerRoute() {
        List<MicroserviceRoute> routes = List.of(route("a", "http://localhost:6010", "/health"));
        UpstreamEndpointGroups groups = UpstreamEndpointGroups.create(
                routes, Duration.ofSeconds(3), null, cfg(false));
        try {
            assertThat(groups.groupCount()).isEqualTo(1);
            assertThat(groups.clientFor("a")).isNotNull();
        } finally {
            groups.close();
        }
    }

    @Test
    void closeIsIdempotent() {
        List<MicroserviceRoute> routes = List.of(route("a", "http://localhost:6010", "/health"));
        UpstreamEndpointGroups groups = UpstreamEndpointGroups.create(
                routes, Duration.ofSeconds(3), null, cfg(true));
        groups.close();
        groups.close(); // must not throw
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test-compile 2>&1 | tail -15`
Expected: FAIL to compile — `UpstreamEndpointGroups` does not exist.

- [ ] **Step 3: Implement `UpstreamEndpointGroups`**

Create `src/main/java/com/recsys/application/gateway/UpstreamEndpointGroups.java`:

```java
package com.recsys.application.gateway;

import com.recsys.config.EnvVars;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.dns.DnsAddressEndpointGroup;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckedEndpointGroup;
import com.linecorp.armeria.common.SessionProtocol;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds one Armeria {@link EndpointGroup} per unique {@code (host, port, healthPath)} backend and a
 * {@link WebClient} per route over the shared group. When health checking is enabled each group is a
 * {@link HealthCheckedEndpointGroup} over a {@link DnsAddressEndpointGroup}, so a down upstream is
 * dropped from selection and requests fast-fail instead of hanging. The groups own background DNS and
 * health-check schedulers and must be released via {@link #close()}.
 */
final class UpstreamEndpointGroups implements java.io.Closeable {

    record HealthCheckConfig(boolean healthCheckEnabled, long healthCheckIntervalMs, int dnsTtlMaxSeconds) {
        static HealthCheckConfig fromEnvironment() {
            boolean enabled = EnvVars.readBool("GATEWAY_UPSTREAM_HEALTHCHECK_ENABLED", true);
            long intervalMs = EnvVars.readLong("GATEWAY_UPSTREAM_HEALTHCHECK_INTERVAL_MS", 10_000L);
            int dnsTtlMax = EnvVars.readInt("GATEWAY_UPSTREAM_DNS_TTL_MAX_S", 30);
            return new HealthCheckConfig(enabled, intervalMs, dnsTtlMax);
        }
    }

    private final Map<String, WebClient> clientsByRoute;
    private final List<EndpointGroup> ownedGroups;
    private volatile boolean closed;

    private UpstreamEndpointGroups(Map<String, WebClient> clientsByRoute, List<EndpointGroup> ownedGroups) {
        this.clientsByRoute = clientsByRoute;
        this.ownedGroups = ownedGroups;
    }

    static UpstreamEndpointGroups create(List<MicroserviceRoute> routes,
                                         Duration responseTimeout,
                                         Function<? super HttpClient, ? extends HttpClient> decorator,
                                         HealthCheckConfig config) {
        Map<String, EndpointGroup> groupsByKey = new LinkedHashMap<>();
        List<EndpointGroup> owned = new ArrayList<>();
        Map<String, WebClient> clients = new HashMap<>();

        for (MicroserviceRoute route : routes) {
            URI baseUri = route.baseUri();
            SessionProtocol protocol = "https".equalsIgnoreCase(baseUri.getScheme())
                    ? SessionProtocol.HTTPS : SessionProtocol.HTTP;
            String host = baseUri.getHost();
            int port = baseUri.getPort() != -1 ? baseUri.getPort() : protocol.defaultPort();
            String healthPath = route.healthPath();
            String key = protocol.uriText() + "://" + host + ":" + port + healthPath;

            EndpointGroup group = groupsByKey.computeIfAbsent(key, k -> {
                EndpointGroup built = buildGroup(protocol, host, port, healthPath, responseTimeout, config);
                owned.add(built);
                return built;
            });

            WebClientBuilder wcb = WebClient.builder(protocol, group)
                    .responseTimeoutMillis(responseTimeout.toMillis());
            if (decorator != null) {
                wcb.decorator(decorator);
            }
            clients.put(route.name(), wcb.build());
        }
        return new UpstreamEndpointGroups(Map.copyOf(clients), List.copyOf(owned));
    }

    private static EndpointGroup buildGroup(SessionProtocol protocol, String host, int port,
                                            String healthPath, Duration responseTimeout,
                                            HealthCheckConfig config) {
        // DNS refresh bounded by the record TTL (upper bound = configured max), fast selection timeout
        // so an empty group never waits longer than a normal request would.
        DnsAddressEndpointGroup dns = DnsAddressEndpointGroup.builder(host)
                .port(port)
                .ttl(1, Math.max(1, config.dnsTtlMaxSeconds()))
                .selectionTimeout(responseTimeout)
                .build();
        if (!config.healthCheckEnabled()) {
            return dns;
        }
        return HealthCheckedEndpointGroup.builder(dns, healthPath)
                .protocol(protocol)
                .retryIntervalMillis(config.healthCheckIntervalMs())
                .selectionTimeoutMillis(responseTimeout.toMillis())
                .build();
    }

    WebClient clientFor(String routeName) {
        return clientsByRoute.get(routeName);
    }

    int groupCount() {
        return ownedGroups.size();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (EndpointGroup group : ownedGroups) {
            try {
                group.close();
            } catch (RuntimeException ignored) {
                // best-effort release; shutdown must not fail on a group already closing
            }
        }
    }
}
```

Note on API: if `SessionProtocol.uriText()` or `defaultPort()` does not resolve at compile time, substitute the equivalent (`protocol.isTls()` / literal `80`/`443` and `host+":"+port+healthPath` for the dedup key). The dedup key only needs to be stable per `(protocol, host, port, healthPath)`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest=UpstreamEndpointGroupsTest 2>&1 | grep -E "Tests run|BUILD"`
Expected: `Tests run: 3, Failures: 0, Errors: 0` and `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/application/gateway/UpstreamEndpointGroups.java src/test/java/com/recsys/application/gateway/UpstreamEndpointGroupsTest.java
git commit -m "feat(gateway): add UpstreamEndpointGroups (DNS + health-checked groups)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Wire `GatewayRequestForwarder` to endpoint groups; make it Closeable

**Files:**
- Modify: `src/main/java/com/recsys/application/gateway/GatewayRequestForwarder.java`

**Interfaces:**
- Consumes: `UpstreamEndpointGroups.create(...)` and `UpstreamEndpointGroups.HealthCheckConfig.fromEnvironment()`.
- Produces: `GatewayRequestForwarder implements java.io.Closeable`; new `public void close()`. Constructor signature unchanged for callers, but internally it now builds clients through `UpstreamEndpointGroups`. `forward(...)` maps an empty endpoint group (`EmptyEndpointGroupException`) to `503`.

- [ ] **Step 1: Replace the client map with `UpstreamEndpointGroups`**

In `GatewayRequestForwarder.java`, change the field declarations (currently `private final Map<String, WebClient> routeClients;`) to:

```java
    private final UpstreamEndpointGroups upstreams;
    private final Map<String, RouteCircuitBreaker> circuitBreakers;
    private final GatewayRateLimiter rateLimiter;
```

Replace the client-building block at the end of the constructor (the `this.routeClients = routes.stream()...build()));` assignment) with:

```java
        this.upstreams = UpstreamEndpointGroups.create(
                routes, timeout, retryDecorator, UpstreamEndpointGroups.HealthCheckConfig.fromEnvironment());
```

Keep the existing `retryRule` / `retryDecorator` construction above it exactly as-is (the decorator is now passed into `UpstreamEndpointGroups` instead of applied inline).

- [ ] **Step 2: Implement `Closeable` and `close()`**

Change the class declaration:

```java
public final class GatewayRequestForwarder implements java.io.Closeable {
```

Add near the bottom of the class:

```java
    @Override
    public void close() {
        upstreams.close();
    }
```

- [ ] **Step 3: Look up the client via `upstreams` and fast-fail an empty group**

In `forward(...)`, replace `WebClient client = routeClients.get(route.name());` with:

```java
        WebClient client = upstreams.clientFor(route.name());
```

Replace the trailing `.exceptionally(...)` handler so an empty endpoint group returns `503` (upstream unavailable) while other failures keep the existing `502`:

```java
                .exceptionally(t -> {
                    if (cb != null) cb.recordFailure();
                    if (isEmptyEndpointGroup(t)) {
                        return GatewayProxyService.gatewayError(HttpStatus.SERVICE_UNAVAILABLE,
                                route.name() + " upstream unavailable — no healthy endpoint");
                    }
                    return GatewayProxyService.gatewayError(HttpStatus.BAD_GATEWAY, "upstream unreachable");
                }));
```

Add this helper method to the class:

```java
    private static boolean isEmptyEndpointGroup(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof com.linecorp.armeria.client.endpoint.EmptyEndpointGroupException) {
                return true;
            }
        }
        return false;
    }
```

- [ ] **Step 4: Remove now-unused imports**

After the edits, `WebClient` is still used (the `client` local). Verify whether the retry imports are still used (they are — `retryRule`/`retryDecorator` remain). Run:

Run: `grep -nE "Collectors|routeClients" src/main/java/com/recsys/application/gateway/GatewayRequestForwarder.java`
Expected: no matches. If `import java.util.stream.Collectors;` is now unused (the old `.collect(Collectors.toUnmodifiableMap(...))` is gone), remove that import line. Leave all other imports.

- [ ] **Step 5: Compile**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q compile 2>&1 | tail -15`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Run the existing forwarder/gateway unit tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest='GatewayRequestForwarderTest,GatewayRouteTableTest,RecommendationGatewayServiceTest' 2>&1 | grep -E "Tests run|BUILD"`
Expected: all PASS. (These construct a forwarder; they must still work with the endpoint-group-backed clients. If a test expects a specific behavior against a live upstream, it uses a real Armeria test server and health checks against it succeed.)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/application/gateway/GatewayRequestForwarder.java
git commit -m "feat(gateway): forward through health-aware endpoint groups

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Close the forwarder on gateway shutdown

**Files:**
- Modify: `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java`

**Interfaces:**
- Consumes: `GatewayRequestForwarder.close()`.

- [ ] **Step 1: Close the forwarder in the shutdown hook**

In `MicroserviceGatewayServer.main`, the shutdown hook currently stops the server and closes the LLM factory. Add a forwarder close. Change the hook body from:

```java
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down API gateway...");
            server.stop().join();
            if (llmFactoryToClose != null) {
                llmFactoryToClose.close();
            }
        }));
```

to:

```java
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down API gateway...");
            server.stop().join();
            forwarder.close();
            if (llmFactoryToClose != null) {
                llmFactoryToClose.close();
            }
        }));
```

The `forwarder` local is already in scope (built earlier in `main` as `GatewayRequestForwarder forwarder = new GatewayRequestForwarder(...)`). It is effectively final, so it is usable in the lambda.

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q compile 2>&1 | tail -15`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java
git commit -m "feat(gateway): release upstream endpoint groups on shutdown

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Integration test — health-aware endpoint dropping

**Files:**
- Create: `src/test/java/com/recsys/api/gateway/GatewayUpstreamHealthCheckIntegrationTest.java`

**Interfaces:**
- Consumes: `GatewayRequestForwarder`, `MicroserviceRoute`, `RouteCircuitBreaker`, `GatewayRateLimiter`, `GatewayPrincipal`.

This test drives a real Armeria upstream whose health path can be toggled, points a forwarder at it with a short health-check interval, and asserts the route is dropped when unhealthy and recovers when healthy again. It uses the forwarder directly (the smallest surface that exercises endpoint selection).

- [ ] **Step 1: Write the integration test**

Create `src/test/java/com/recsys/api/gateway/GatewayUpstreamHealthCheckIntegrationTest.java`:

```java
package com.recsys.api.gateway;

import com.recsys.application.gateway.GatewayPrincipal;
import com.recsys.application.gateway.GatewayRequestForwarder;
import com.recsys.application.gateway.MicroserviceRoute;
import com.recsys.ratelimit.GatewayRateLimiter;
import com.recsys.resilience.RouteCircuitBreaker;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class GatewayUpstreamHealthCheckIntegrationTest {

    static final AtomicBoolean healthy = new AtomicBoolean(true);

    @RegisterExtension
    static final ServerExtension upstream = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/health", (ctx, req) ->
                    healthy.get() ? HttpResponse.of(HttpStatus.OK)
                                  : HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE));
            sb.service("prefix:/api", (ctx, req) ->
                    HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, "{\"ok\":true}"));
        }
    };

    private static GatewayRequestForwarder forwarder() {
        MicroserviceRoute route = new MicroserviceRoute(
                "catalog", "/api/catalog", "CATALOG_SERVICE_URL",
                URI.create("http://127.0.0.1:" + upstream.httpPort()), "/health");
        Map<String, RouteCircuitBreaker> cbs = Map.of("catalog", new RouteCircuitBreaker(5, 5000));
        return new GatewayRequestForwarder(
                List.of(route), Duration.ofSeconds(2), cbs, GatewayRateLimiter.disabled());
    }

    private static HttpStatus proxyOnce(GatewayRequestForwarder fwd) {
        MicroserviceRoute route = new MicroserviceRoute(
                "catalog", "/api/catalog", "CATALOG_SERVICE_URL",
                URI.create("http://127.0.0.1:" + upstream.httpPort()), "/health");
        ServiceRequestContext ctx = ServiceRequestContext.builder(
                com.linecorp.armeria.common.HttpRequest.of(HttpMethod.GET, "/api/catalog/x")).build();
        AggregatedHttpRequest req = AggregatedHttpRequest.of(HttpMethod.GET, "/api/catalog/x");
        HttpResponse resp = fwd.forward(ctx, req, route, "/api/x", GatewayPrincipal.anonymous());
        return resp.aggregate().join().status();
    }

    @Test
    void healthyUpstreamServes_thenDroppedWhenUnhealthy_thenRecovers() {
        healthy.set(true);
        GatewayRequestForwarder fwd = forwarder();
        try {
            // Healthy: request passes through (200). Allow the first health check to register the endpoint.
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> proxyOnce(fwd) == HttpStatus.OK);

            // Flip unhealthy: within a couple of health-check cycles the endpoint is dropped -> 503.
            healthy.set(false);
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> proxyOnce(fwd) == HttpStatus.SERVICE_UNAVAILABLE);

            // Recover: endpoint returns and requests succeed again.
            healthy.set(true);
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> proxyOnce(fwd) == HttpStatus.OK);
        } finally {
            fwd.close();
        }
    }
}
```

Note: `MicroserviceRoute` and `GatewayPrincipal.anonymous()` / `GatewayRateLimiter.disabled()` factory names must match the codebase — verify with `grep -n "anonymous\|static GatewayPrincipal" src/main/java/com/recsys/application/gateway/GatewayPrincipal.java` and `grep -n "disabled" src/main/java/com/recsys/ratelimit/GatewayRateLimiter.java` before running; adjust the calls to the real factory names if they differ. Confirm `org.awaitility.Awaitility` is already a test dependency (`grep -n awaitility pom.xml`); if absent, replace the `await()...until(...)` calls with a bounded poll loop using `Thread.sleep(200)` inside a `for` loop up to the same timeout.

- [ ] **Step 2: Run the integration test**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest=GatewayUpstreamHealthCheckIntegrationTest 2>&1 | grep -E "Tests run|BUILD|ERROR"`
Expected: `Tests run: 1, Failures: 0, Errors: 0` and `BUILD SUCCESS`. If it fails, first confirm the health-check interval registers within the await windows; the config default is 10 s, so this test relies on the first check landing quickly — if flaky, set `GATEWAY_UPSTREAM_HEALTHCHECK_INTERVAL_MS` low for the test via a system-env shim or widen the await timeout to 15 s.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/recsys/api/gateway/GatewayUpstreamHealthCheckIntegrationTest.java
git commit -m "test(gateway): cover health-aware upstream dropping and recovery

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Document env vars, full verification, PR

**Files:**
- Modify: `.claude/CLAUDE.md` (Key env vars line).

- [ ] **Step 1: Document the new env vars**

In `.claude/CLAUDE.md`, append to the gateway env-var sentence (after the LLM `ClientFactory` clause, before "Keep `LLM_PING_INTERVAL_MS`..."):

```
`GATEWAY_UPSTREAM_HEALTHCHECK_ENABLED` (default true; gateway resolves each upstream through a health-checked Armeria endpoint group so a down backend is dropped from selection and requests fast-fail with 503 instead of hanging), `GATEWAY_UPSTREAM_HEALTHCHECK_INTERVAL_MS` (default 10000), `GATEWAY_UPSTREAM_DNS_TTL_MAX_S` (default 30, upper bound on upstream DNS caching) tune the gateway data-path discovery.
```

- [ ] **Step 2: Full build + test**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test 2>&1 | grep -E "Tests run:|BUILD"`
Expected: final `Tests run: N, Failures: 0, Errors: 0` (N ≈ 892, i.e. 888 + 4 new) and `BUILD SUCCESS`.

- [ ] **Step 3: Boot the gateway and confirm it starts and closes cleanly**

Run the gateway in the background, confirm the startup line, then stop it:

```bash
rm -f /tmp/gw2.log
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q exec:java -Dexec.mainClass=com.recsys.api.gateway.MicroserviceGatewayServer > /tmp/gw2.log 2>&1 &
GW_PID=$!
for i in $(seq 1 30); do grep -q "Starting RecSys API gateway" /tmp/gw2.log && break; sleep 1; done
grep -E "Starting RecSys API gateway|Route (embed-recall|model-inference)" /tmp/gw2.log
kill "$GW_PID" 2>/dev/null; pkill -f "MicroserviceGatewayServer" 2>/dev/null
grep -c "Shutting down API gateway" /tmp/gw2.log
```

Expected: the startup line and route lines appear; the gateway registers routes and starts. (With health checking on and no backends running, health checks fail in the background — that is expected and must not prevent startup.)

- [ ] **Step 4: Commit docs**

```bash
git add .claude/CLAUDE.md
git commit -m "docs(gateway): document upstream health-check env vars

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 5: Push and open the PR**

```bash
git push -u origin feat/gateway-upstream-endpoint-discovery
gh pr create --title "feat(gateway): health-aware upstream discovery (endpoint groups)" --body "$(cat <<'EOF'
## Summary
Gives the gateway data path application-layer, health-aware upstream discovery on top of the existing ClusterIP DNS. Each upstream is resolved through an Armeria `HealthCheckedEndpointGroup(DnsAddressEndpointGroup)`; a down backend is dropped from selection so the gateway fast-fails with `503` instead of forwarding into a black hole and waiting for the response timeout. DNS refresh is now TTL-driven rather than the blunt 30 s JVM cache.

Option A1 from the investigation: **no headless Services, no client-side per-pod LB, no AZ-aware selection** — kube-proxy + `trafficDistribution: PreferClose` keep doing same-AZ per-pod balancing, so the cross-AZ-traffic work is untouched. No Kubernetes/ConfigMap/Cloud Map changes.

- New `UpstreamEndpointGroups`: builds one endpoint group per unique `(host, port, healthPath)` (the 11 routes collapse onto ~3 backends), shares it across routes, exposes a `WebClient` per route and `close()`.
- `GatewayRequestForwarder` forwards through the groups, is now `Closeable`, and maps an empty group to `503`.
- `MicroserviceGatewayServer` closes the forwarder on shutdown.
- `LlmProxyService` and `GatewayHealthService` unchanged.

## Config (new env vars, safe defaults)
- `GATEWAY_UPSTREAM_HEALTHCHECK_ENABLED` (default true; set false to skip probing, e.g. local dev without all backends)
- `GATEWAY_UPSTREAM_HEALTHCHECK_INTERVAL_MS` (default 10000)
- `GATEWAY_UPSTREAM_DNS_TTL_MAX_S` (default 30)

## Testing
- `mvn test` — full suite green.
- New unit tests: `(host,port,healthPath)` dedup, disabled-flag path, idempotent close.
- New integration test: healthy upstream serves → flip unhealthy → route dropped, gateway returns 503 → recovers.
- Booted the gateway locally: starts and registers routes with health checking on.

Spec: `docs/superpowers/specs/2026-07-10-gateway-upstream-endpoint-discovery-design.md`
Plan: `docs/superpowers/plans/2026-07-10-gateway-upstream-endpoint-discovery.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: PR created against `main`.

---

## Self-Review

**Spec coverage:**
- Endpoint group per `(host,port,healthPath)` with dedup, WebClient per route, `close()` → Task 1. ✓
- `DnsAddressEndpointGroup` TTL-driven refresh + `HealthCheckedEndpointGroup` active probing, disabled-flag fallback → Task 1 `buildGroup`. ✓
- Forwarder builds via the component, keeps retry + circuit breaker, maps empty group to 503, becomes Closeable → Task 2. ✓
- Shutdown closes the forwarder → Task 3. ✓
- Config env vars with defaults → Task 1 `HealthCheckConfig.fromEnvironment` + Task 5 docs. ✓
- Integration + unit tests (healthy/unhealthy/recover, dedup, close) → Tasks 1, 4. ✓
- LlmProxyService / GatewayHealthService / manifests untouched → not modified in any task. ✓

**Placeholder scan:** No TBD/TODO. Each code step shows full content. Two steps include explicit "verify the real factory name / dependency and adjust" guards (GatewayPrincipal/GatewayRateLimiter factories, awaitility presence, `SessionProtocol` helper method names) rather than assuming — these are real API-surface checks the implementer must confirm, not placeholders for missing content.

**Type consistency:** `UpstreamEndpointGroups.create(List<MicroserviceRoute>, Duration, Function<? super HttpClient, ? extends HttpClient>, HealthCheckConfig)` defined in Task 1 is called identically in Task 2 with `retryDecorator` (whose type `Function<? super HttpClient, RetryingClient>` is assignment-compatible with the `? extends HttpClient` bound since `RetryingClient` is an `HttpClient`). `clientFor(String)`, `groupCount()`, `close()`, and `HealthCheckConfig.fromEnvironment()` are used exactly as declared. Forwarder constructor signature is unchanged, so Task 4's `new GatewayRequestForwarder(List, Duration, Map, GatewayRateLimiter)` matches the existing constructor.
