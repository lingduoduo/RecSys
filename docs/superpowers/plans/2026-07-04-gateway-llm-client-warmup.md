# Gateway LLM Client Warmup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the gateway's LLM reverse proxy a tuned, injected Armeria `ClientFactory` and a best-effort startup pre-connect, so the first LLM request reuses an already-seated connection instead of paying the full cold DNS/TCP/TLS cost.

**Architecture:** A shared `ClientFactory` (built once in `MicroserviceGatewayServer.main`, only when LLM routes exist) is injected into each `LlmProxyService`, which builds its `WebClient` from it. A new `LlmProxyService.warmUp()` fires a best-effort GET to the upstream health path through that same client, so the warmed connection is the one real requests reuse. All warmup is async, on by default, and never delays or fails startup.

**Tech Stack:** Java 17, Armeria 1.28.4 (`WebClient`, `ClientFactory`, `ServerExtension` for tests), JUnit 5, AssertJ, Maven.

## Global Constraints

- Armeria version is **1.28.4** — verify exact `ClientFactoryBuilder` method names against this version at compile time.
- LLM routes only — do **not** touch `GatewayProxyService` or non-LLM routes.
- Warmup is **best-effort**: never block `server.start()`, never throw out of startup.
- New env vars, all with safe defaults: `LLM_CONNECT_TIMEOUT_MS`=2000, `LLM_IDLE_TIMEOUT_MS`=60000, `LLM_PING_INTERVAL_MS`=20000, `LLM_WARMUP_ENABLED`=true.
- Follow existing repo idioms: `EnvVars.read*` helpers, `MicroserviceRoute` record, package `com.recsys.application.gateway` for proxy logic, `ServerExtension` stub upstreams for integration tests.
- Build/test commands: `mvn test -Dtest=<Class>` for a single class; `mvn package -DskipTests` for a full compile check.
- Commit after every task. Work stays on branch `feat/gateway-llm-client-warmup` (already created). Do not merge to main.

## File Structure

- **Modify** `src/main/java/com/recsys/config/EnvVars.java` — add `readBool` (EnvReader + String overloads).
- **Create** `src/test/java/com/recsys/config/EnvVarsTest.java` — unit tests for `readBool`.
- **Modify** `src/main/java/com/recsys/application/gateway/LlmProxyService.java` — add `ClientFactory` constructor param (both overloads), build `WebClient` from it, add `warmUp()`, add slf4j logger.
- **Create** `src/test/java/com/recsys/application/gateway/LlmProxyServiceWarmupTest.java` — stub-upstream tests for `warmUp()` + factory `connectTimeout`.
- **Modify** `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java` — add `buildLlmClientFactory` + `registerLlmRoutes` static helpers, guard on non-empty LLM routes, close factory in shutdown hook.
- **Create** `src/test/java/com/recsys/api/gateway/LlmGatewayWarmupIntegrationTest.java` — `ServerExtension` test that `registerLlmRoutes` warms (or skips) the upstream health path.

---

### Task 1: `EnvVars.readBool`

**Files:**
- Modify: `src/main/java/com/recsys/config/EnvVars.java`
- Test: `src/test/java/com/recsys/config/EnvVarsTest.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `public static boolean readBool(EnvVars.EnvReader env, String name, boolean def)`
  - `public static boolean readBool(String name, boolean def)` (delegates to `System::getenv`)

  Semantics: unset/blank → `def`; trimmed+lowercased in {`true`,`1`,`yes`,`on`} → `true`; in {`false`,`0`,`no`,`off`} → `false`; any other value → `def`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/config/EnvVarsTest.java`:

```java
package com.recsys.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EnvVarsTest {

    private static EnvVars.EnvReader env(Map<String, String> values) {
        return values::get;
    }

    @Test
    void readBool_truthyValues_returnTrue() {
        for (String v : new String[]{"true", "TRUE", "1", "yes", "On"}) {
            assertThat(EnvVars.readBool(env(Map.of("FLAG", v)), "FLAG", false))
                    .as("value=%s", v).isTrue();
        }
    }

    @Test
    void readBool_falsyValues_returnFalse() {
        for (String v : new String[]{"false", "FALSE", "0", "no", "Off"}) {
            assertThat(EnvVars.readBool(env(Map.of("FLAG", v)), "FLAG", true))
                    .as("value=%s", v).isFalse();
        }
    }

    @Test
    void readBool_unsetOrBlank_returnsDefault() {
        assertThat(EnvVars.readBool(env(Map.of()), "FLAG", true)).isTrue();
        assertThat(EnvVars.readBool(env(Map.of()), "FLAG", false)).isFalse();
        assertThat(EnvVars.readBool(env(Map.of("FLAG", "   ")), "FLAG", true)).isTrue();
    }

    @Test
    void readBool_unrecognizedValue_returnsDefault() {
        assertThat(EnvVars.readBool(env(Map.of("FLAG", "maybe")), "FLAG", true)).isTrue();
        assertThat(EnvVars.readBool(env(Map.of("FLAG", "maybe")), "FLAG", false)).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=EnvVarsTest`
Expected: FAIL — compile error / `cannot find symbol: method readBool`.

- [ ] **Step 3: Write minimal implementation**

In `src/main/java/com/recsys/config/EnvVars.java`, add these methods before the closing brace (after `readDouble` and the existing `readInt(String,int)` / `readLong(String,int)` overloads):

```java
    public static boolean readBool(EnvReader env, String name, boolean def) {
        String raw = env.get(name);
        if (raw == null || raw.isBlank()) return def;
        switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true": case "1": case "yes": case "on":
                return true;
            case "false": case "0": case "no": case "off":
                return false;
            default:
                return def;
        }
    }

    public static boolean readBool(String name, boolean def) {
        return readBool(System::getenv, name, def);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=EnvVarsTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/config/EnvVars.java src/test/java/com/recsys/config/EnvVarsTest.java
git commit -m "feat(config): add EnvVars.readBool

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `LlmProxyService` — `ClientFactory` injection + `warmUp()`

**Files:**
- Modify: `src/main/java/com/recsys/application/gateway/LlmProxyService.java`
- Test: `src/test/java/com/recsys/application/gateway/LlmProxyServiceWarmupTest.java` (create)

**Interfaces:**
- Consumes: `MicroserviceRoute` (record; `baseUri()`, `name()`, package-private `healthUri()`), `LlmTokenRateLimiter`, `LlmResponseCache`, `RouteCircuitBreaker`, `GatewayAuthenticator`.
- Produces:
  - New constructor overload adding a trailing `com.linecorp.armeria.client.ClientFactory clientFactory` parameter to the existing 8-arg (with authenticator) constructor:
    `LlmProxyService(MicroserviceRoute, Duration, RouteCircuitBreaker, LlmTokenRateLimiter, LlmResponseCache, int defaultTokenEstimate, long maxRetryWaitMs, GatewayAuthenticator, ClientFactory)`
  - `public java.util.concurrent.CompletableFuture<Void> warmUp()` — best-effort GET to the route's health path via the service's own `webClient`; completes normally on success or handled failure, never throws.
  - Existing constructors remain valid: the current 7-arg and 8-arg constructors now delegate with `ClientFactory.ofDefault()` (backward-compatible; existing tests unaffected).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/application/gateway/LlmProxyServiceWarmupTest.java`:

```java
package com.recsys.application.gateway;

import com.recsys.infrastructure.cache.LlmResponseCache;
import com.recsys.ratelimit.LlmTokenRateLimiter;
import com.recsys.resilience.RouteCircuitBreaker;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmProxyServiceWarmupTest {

    // Records the paths the upstream is asked for so we can assert the warmup hit the health path.
    static final List<String> hits = new CopyOnWriteArrayList<>();

    @RegisterExtension
    static final ServerExtension upstream = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("prefix:/", (ctx, req) -> {
                hits.add(ctx.path());
                return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, "{\"ok\":true}");
            });
        }
    };

    private static LlmProxyService proxy(String baseUri, String healthPath, ClientFactory factory) {
        MicroserviceRoute route = new MicroserviceRoute(
                "llm", "/api/llm", "UNUSED", URI.create(baseUri), healthPath);
        return new LlmProxyService(
                route, Duration.ofSeconds(5),
                new RouteCircuitBreaker(3, 5000),
                LlmTokenRateLimiter.disabled(),
                LlmResponseCache.disabled(),
                1000, 30_000,
                GatewayAuthenticator.disabled(),
                factory);
    }

    @Test
    void warmUp_success_hitsHealthPathExactlyOnce() {
        hits.clear();
        LlmProxyService svc = proxy("http://127.0.0.1:" + upstream.httpPort(), "/api/tags",
                ClientFactory.ofDefault());

        svc.warmUp().join();

        assertThat(hits).containsExactly("/api/tags");
    }

    @Test
    void warmUp_deadUpstream_completesWithoutThrowing() {
        // Port 1 is not listening; the warmup must swallow the failure and complete normally.
        LlmProxyService svc = proxy("http://127.0.0.1:1", "/api/tags", ClientFactory.ofDefault());

        // join() would throw if warmUp() completed exceptionally.
        svc.warmUp().join();
    }

    @Test
    void warmUp_shortConnectTimeout_failsFastWellUnderResponseTimeout() {
        // 240.0.0.1 is reserved and never routable, so connect never completes; a 100ms
        // connectTimeout guarantees the future resolves quickly despite the 5s response timeout.
        ClientFactory factory = ClientFactory.builder()
                .connectTimeout(Duration.ofMillis(100))
                .build();
        try {
            LlmProxyService svc = proxy("http://240.0.0.1:80", "/api/tags", factory);
            long start = System.currentTimeMillis();
            svc.warmUp().join(); // handled internally -> completes normally
            long elapsedMs = System.currentTimeMillis() - start;
            assertThat(elapsedMs).isLessThan(5_000L);
        } finally {
            factory.close();
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=LlmProxyServiceWarmupTest`
Expected: FAIL — compile error: constructor with `ClientFactory` param and `warmUp()` do not exist.

- [ ] **Step 3: Write minimal implementation**

In `src/main/java/com/recsys/application/gateway/LlmProxyService.java`:

3a. Add imports (after line 9 `import com.linecorp.armeria.client.WebClient;`):

```java
import com.linecorp.armeria.client.ClientFactory;
```

and after the Armeria imports block, add slf4j:

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

3b. Add a logger field (just inside the class body, near the other `private static final` fields, e.g. after line 59 `private static final ObjectMapper MAPPER = ...`):

```java
    private static final Logger log = LoggerFactory.getLogger(LlmProxyService.class);
```

3c. Change the existing 7-arg constructor (the one that delegates to the authenticator overload) so it passes the default factory, and make the 8-arg (authenticator) constructor delegate to a new 9-arg constructor. Replace the two existing constructors (the 7-arg delegating one at ~line 78 and the 8-arg at ~line 89) with:

```java
    public LlmProxyService(MicroserviceRoute route,
                    Duration timeout,
                    RouteCircuitBreaker circuitBreaker,
                    LlmTokenRateLimiter tokenRateLimiter,
                    LlmResponseCache responseCache,
                    int defaultTokenEstimate,
                    long maxRetryWaitMs) {
        this(route, timeout, circuitBreaker, tokenRateLimiter, responseCache,
                defaultTokenEstimate, maxRetryWaitMs, GatewayAuthenticator.disabled(),
                ClientFactory.ofDefault());
    }

    public LlmProxyService(MicroserviceRoute route,
                    Duration timeout,
                    RouteCircuitBreaker circuitBreaker,
                    LlmTokenRateLimiter tokenRateLimiter,
                    LlmResponseCache responseCache,
                    int defaultTokenEstimate,
                    long maxRetryWaitMs,
                    GatewayAuthenticator authenticator) {
        this(route, timeout, circuitBreaker, tokenRateLimiter, responseCache,
                defaultTokenEstimate, maxRetryWaitMs, authenticator, ClientFactory.ofDefault());
    }

    public LlmProxyService(MicroserviceRoute route,
                    Duration timeout,
                    RouteCircuitBreaker circuitBreaker,
                    LlmTokenRateLimiter tokenRateLimiter,
                    LlmResponseCache responseCache,
                    int defaultTokenEstimate,
                    long maxRetryWaitMs,
                    GatewayAuthenticator authenticator,
                    ClientFactory clientFactory) {
        this.route = route;
        this.circuitBreaker = circuitBreaker;
        this.tokenRateLimiter = tokenRateLimiter;
        this.responseCache = responseCache;
        this.defaultTokenEstimate = defaultTokenEstimate;
        this.maxRetryWaitMs = maxRetryWaitMs;
        this.authenticator = authenticator == null ? GatewayAuthenticator.disabled() : authenticator;

        this.webClient = WebClient.builder(route.baseUri().toString())
                .factory(clientFactory == null ? ClientFactory.ofDefault() : clientFactory)
                .responseTimeoutMillis(timeout.toMillis())
                .build();
    }
```

3d. Add the `warmUp()` method (place it right after the constructors, before `serve`):

```java
    /**
     * Best-effort pre-connect: issues a GET to the upstream health path through this service's
     * own {@link WebClient}, seating the pooled connection (DNS + TCP + TLS + HTTP/2 preface)
     * that real requests will reuse. Never blocks startup and never throws — failures are logged.
     */
    public CompletableFuture<Void> warmUp() {
        URI healthUri = route.healthUri();
        String rawQuery = healthUri.getRawQuery();
        String target = rawQuery != null ? healthUri.getRawPath() + "?" + rawQuery : healthUri.getRawPath();
        return webClient.get(target).aggregate()
                .thenAccept(agg -> log.info("LLM warmup for {} -> {}", route.name(), agg.status()))
                .exceptionally(t -> {
                    log.warn("LLM warmup for {} failed (non-fatal): {}", route.name(), t.toString());
                    return null;
                })
                .toCompletableFuture();
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=LlmProxyServiceWarmupTest,LlmProxyServiceTest`
Expected: PASS — 3 warmup tests + 2 existing static-method tests (backward compatibility confirmed).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/application/gateway/LlmProxyService.java src/test/java/com/recsys/application/gateway/LlmProxyServiceWarmupTest.java
git commit -m "feat(gateway): inject ClientFactory into LlmProxyService and add warmUp()

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `MicroserviceGatewayServer` — factory build, LLM-route registration + warmup wiring

**Files:**
- Modify: `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java`
- Test: `src/test/java/com/recsys/api/gateway/LlmGatewayWarmupIntegrationTest.java` (create)

**Interfaces:**
- Consumes: `LlmProxyService(...9-arg...)` + `warmUp()` (Task 2), `EnvVars.readBool` (Task 1), `MicroserviceRoute`, `RouteCircuitBreaker`, `LlmTokenRateLimiter`, `LlmResponseCache`, `GatewayAuthenticator`, `ClientFactory`.
- Produces (new package-private static helpers on `MicroserviceGatewayServer`, testable without invoking `main`):
  - `static ClientFactory buildLlmClientFactory(EnvVars.EnvReader env)` — builds the tuned factory from the LLM env knobs.
  - `static List<CompletableFuture<Void>> registerLlmRoutes(ServerBuilder sb, List<MicroserviceRoute> llmRoutes, ClientFactory factory, Duration llmTimeout, Map<String,RouteCircuitBreaker> circuitBreakers, LlmTokenRateLimiter tokenRateLimiter, LlmResponseCache responseCache, int defaultTokenEstimate, long maxRetryWaitMs, GatewayAuthenticator authenticator, boolean warmupEnabled)` — registers each LLM route's `LlmProxyService`, fires `warmUp()` when `warmupEnabled`, and returns the (possibly empty) list of warmup futures.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/api/gateway/LlmGatewayWarmupIntegrationTest.java`:

```java
package com.recsys.api.gateway;

import com.recsys.application.gateway.GatewayAuthenticator;
import com.recsys.application.gateway.MicroserviceRoute;
import com.recsys.infrastructure.cache.LlmResponseCache;
import com.recsys.ratelimit.LlmTokenRateLimiter;
import com.recsys.resilience.RouteCircuitBreaker;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class LlmGatewayWarmupIntegrationTest {

    static final List<String> hits = new CopyOnWriteArrayList<>();

    @RegisterExtension
    static final ServerExtension upstream = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("prefix:/", (ctx, req) -> {
                hits.add(ctx.path());
                return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, "{\"ok\":true}");
            });
        }
    };

    private static MicroserviceRoute llmRoute() {
        return new MicroserviceRoute("llm", "/api/llm", "UNUSED",
                URI.create("http://127.0.0.1:" + upstream.httpPort()), "/api/tags");
    }

    private static List<CompletableFuture<Void>> register(boolean warmupEnabled, ClientFactory factory) {
        List<MicroserviceRoute> routes = List.of(llmRoute());
        ServerBuilder sb = Server.builder().http(0);
        Map<String, RouteCircuitBreaker> cbs = Map.of("llm", new RouteCircuitBreaker(3, 5000));
        return MicroserviceGatewayServer.registerLlmRoutes(
                sb, routes, factory, Duration.ofSeconds(5), cbs,
                LlmTokenRateLimiter.disabled(), LlmResponseCache.disabled(),
                1000, 30_000, GatewayAuthenticator.disabled(), warmupEnabled);
    }

    @Test
    void registerLlmRoutes_warmupEnabled_hitsHealthPath() {
        hits.clear();
        ClientFactory factory = ClientFactory.ofDefault();
        List<CompletableFuture<Void>> warmups = register(true, factory);

        CompletableFuture.allOf(warmups.toArray(new CompletableFuture[0])).join();

        assertThat(warmups).hasSize(1);
        assertThat(hits).containsExactly("/api/tags");
    }

    @Test
    void registerLlmRoutes_warmupDisabled_noUpstreamHit() {
        hits.clear();
        ClientFactory factory = ClientFactory.ofDefault();
        List<CompletableFuture<Void>> warmups = register(false, factory);

        assertThat(warmups).isEmpty();
        assertThat(hits).isEmpty();
    }

    @Test
    void buildLlmClientFactory_returnsUsableFactory() {
        ClientFactory factory = MicroserviceGatewayServer.buildLlmClientFactory(k -> null);
        try {
            assertThat(factory).isNotNull();
        } finally {
            factory.close();
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=LlmGatewayWarmupIntegrationTest`
Expected: FAIL — compile error: `registerLlmRoutes` / `buildLlmClientFactory` not found.

- [ ] **Step 3: Write minimal implementation**

In `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java`:

3a. Add imports (with the existing `com.linecorp.armeria.*` and `java.*` imports):

```java
import com.recsys.config.EnvVars;
import com.linecorp.armeria.client.ClientFactory;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
```

(Note: `com.recsys.config.EnvVars` is already imported via `EnvVars` usage — verify; if already present, skip. `java.util.List`, `java.util.Map` are already imported.)

3b. Replace the existing LLM setup + registration block in `main` (the block from `int llmTimeoutMs = ...` through the `for (MicroserviceRoute llmRoute : llmRoutes) { ... }` loop, lines ~67–96) with:

```java
        // LLM requests can take much longer than regular API calls (large context, slow inference).
        // Use a separate timeout so LLM latency does not block the shared proxy pool.
        int llmTimeoutMs = EnvVars.readInt("LLM_TIMEOUT_MS", LlmProxyService.DEFAULT_TIMEOUT_MS);
        Duration llmTimeout = Duration.ofMillis(llmTimeoutMs);
        LlmTokenRateLimiter llmTokenRateLimiter = LlmTokenRateLimiter.fromEnvironment();
        LlmResponseCache llmResponseCache = LlmResponseCache.fromEnvironment();
        int llmDefaultTokenEstimate = EnvVars.readInt("LLM_DEFAULT_TOKEN_ESTIMATE", LlmProxyService.DEFAULT_TOKEN_ESTIMATE);
        long llmMaxRetryWaitMs = EnvVars.readLong("LLM_MAX_RETRY_WAIT_MS", LlmProxyService.DEFAULT_MAX_RETRY_WAIT_MS);

        ServerBuilder sb = Server.builder().http(port);

        // Health endpoint — exposes per-route circuit state and upstream reachability.
        sb.service("/health", new GatewayHealthService(allRoutes, timeout, circuitBreakers, port));

        // LLM path: build a tuned, shared ClientFactory (only when LLM routes exist) and register
        // each LLM route from it, then best-effort pre-connect so the first request is warm.
        ClientFactory llmClientFactory = null;
        if (!llmRoutes.isEmpty()) {
            llmClientFactory = buildLlmClientFactory(System::getenv);
            boolean warmupEnabled = EnvVars.readBool("LLM_WARMUP_ENABLED", true);
            registerLlmRoutes(sb, llmRoutes, llmClientFactory, llmTimeout, circuitBreakers,
                    llmTokenRateLimiter, llmResponseCache, llmDefaultTokenEstimate, llmMaxRetryWaitMs,
                    authenticator, warmupEnabled);
            // Warmup futures are intentionally not joined — startup must not block on the upstream.
        }
```

3c. In the shutdown hook (currently `server.stop().join();`), also close the factory. Replace the hook body with:

```java
        final ClientFactory llmFactoryToClose = llmClientFactory;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down API gateway...");
            server.stop().join();
            if (llmFactoryToClose != null) {
                llmFactoryToClose.close();
            }
        }));
```

3d. Add the two static helpers before the closing brace of the class:

```java
    static ClientFactory buildLlmClientFactory(EnvVars.EnvReader env) {
        long connectMs = EnvVars.readLong(env, "LLM_CONNECT_TIMEOUT_MS", 2000L);
        long idleMs = EnvVars.readLong(env, "LLM_IDLE_TIMEOUT_MS", 60_000L);
        long pingMs = EnvVars.readLong(env, "LLM_PING_INTERVAL_MS", 20_000L);
        return ClientFactory.builder()
                .connectTimeout(Duration.ofMillis(connectMs))
                .idleTimeout(Duration.ofMillis(idleMs))
                .pingIntervalMillis(pingMs)
                .build();
    }

    static java.util.List<CompletableFuture<Void>> registerLlmRoutes(
            ServerBuilder sb,
            java.util.List<MicroserviceRoute> llmRoutes,
            ClientFactory llmClientFactory,
            Duration llmTimeout,
            Map<String, RouteCircuitBreaker> circuitBreakers,
            LlmTokenRateLimiter tokenRateLimiter,
            LlmResponseCache responseCache,
            int defaultTokenEstimate,
            long maxRetryWaitMs,
            GatewayAuthenticator authenticator,
            boolean warmupEnabled) {
        java.util.List<CompletableFuture<Void>> warmups = new ArrayList<>();
        for (MicroserviceRoute llmRoute : llmRoutes) {
            LlmProxyService llmProxyService = new LlmProxyService(
                    llmRoute,
                    llmTimeout,
                    circuitBreakers.get(llmRoute.name()),
                    tokenRateLimiter,
                    responseCache,
                    defaultTokenEstimate,
                    maxRetryWaitMs,
                    authenticator,
                    llmClientFactory);
            sb.service(
                    com.linecorp.armeria.server.Route.builder()
                            .pathPrefix(llmRoute.prefix() + "/")
                            .build(),
                    llmProxyService);
            if (warmupEnabled) {
                warmups.add(llmProxyService.warmUp());
            }
        }
        return warmups;
    }
```

Also add the needed imports referenced above if not already present: `com.linecorp.armeria.server.ServerBuilder` (already imported), `com.recsys.application.gateway.GatewayAuthenticator` (already imported), `com.recsys.infrastructure.cache.LlmResponseCache` (already imported), `com.recsys.ratelimit.LlmTokenRateLimiter` (already imported), `com.recsys.application.gateway.MicroserviceRoute` (already imported).

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=LlmGatewayWarmupIntegrationTest,GatewayServerIntegrationTest`
Expected: PASS — 3 new warmup-wiring tests + existing gateway integration tests unaffected.

- [ ] **Step 5: Full compile + gateway/config regression + commit**

Run: `mvn test -Dtest=EnvVarsTest,LlmProxyServiceTest,LlmProxyServiceWarmupTest,LlmGatewayWarmupIntegrationTest,GatewayServerIntegrationTest`
Expected: PASS (all).

Then a full compile to catch any wiring drift:

Run: `mvn package -DskipTests`
Expected: BUILD SUCCESS.

```bash
git add src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java src/test/java/com/recsys/api/gateway/LlmGatewayWarmupIntegrationTest.java
git commit -m "feat(gateway): wire tuned LLM ClientFactory + startup warmup

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Tuned `ClientFactory` (connectTimeout/idleTimeout/pingInterval) → Task 3 `buildLlmClientFactory`. ✓
- Injected into `LlmProxyService`, `WebClient.factory(...)` → Task 2. ✓
- `warmUp()` best-effort, reuses own client, uses `route.healthUri()` → Task 2. ✓
- Async, on by default, `LLM_WARMUP_ENABLED` → Task 3 (`registerLlmRoutes` + `EnvVars.readBool` default true). ✓
- `EnvVars.readBool` → Task 1. ✓
- Skip factory when no LLM routes → Task 3 `if (!llmRoutes.isEmpty())`. ✓
- Factory closed in shutdown hook → Task 3 step 3c. ✓
- Backward-compatible constructors → Task 2 (existing 7/8-arg delegate with `ClientFactory.ofDefault()`). ✓
- Non-fatal failure logged, startup never blocks (warmups not joined) → Task 2 `exceptionally` + Task 3 comment. ✓
- Testing: readBool, warmup success/failure, connectTimeout fast-fail, backward-compat, wiring enabled/disabled → Tasks 1–3. ✓

**Placeholder scan:** No TBD/TODO/"handle edge cases"; every code step shows complete code. ✓

**Type consistency:** `warmUp()` returns `CompletableFuture<Void>` everywhere; `registerLlmRoutes` returns `List<CompletableFuture<Void>>`; `buildLlmClientFactory(EnvVars.EnvReader)` matches the `EnvVars.readBool(EnvReader,...)` signature from Task 1; `MicroserviceRoute` 5-arg constructor matches its record definition; `LlmTokenRateLimiter.disabled()` / `LlmResponseCache.disabled()` are the existing disabled factories used in the codebase. ✓

**Risk note (verify at implementation):** Armeria 1.28.4 `ClientFactoryBuilder` method names — `connectTimeout(Duration)`, `idleTimeout(Duration)`, `pingIntervalMillis(long)`. If any differs in 1.28.4, adjust in Task 3 step 3d (compile step in Task 3 step 5 catches it). The 240.0.0.1 connectTimeout test asserts only an upper time bound the 100ms connectTimeout guarantees, so it is not timing-flaky.
