# Redis Service Registry — PR2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Register the Spring Boot model service into the registry (opt-in, same env contract as the Armeria backends) and add consumer observability: the gateway `/health` endpoint reports which upstreams are registry-resolved vs static and the registry snapshot age.

**Architecture:** A reusable null-safe `ServiceRegistrarLifecycle` wraps a `ServiceRegistrar`; a Spring `ServiceRegistryConfig` exposes it as an `initMethod=start`/`destroyMethod=close` bean built from the existing `RedisExecutor` bean. `ServiceRegistryProvider` records `lastRefreshAtMs`. `GatewayHealthService` takes an optional provider and adds a `registry` section to its JSON.

**Tech Stack:** Java 17, Spring Boot 3, Armeria 1.28.4, Lettuce, JUnit 5, AssertJ, Mockito (transitive). Build with `JAVA_HOME=$(/usr/libexec/java_home -v 17)`.

## Global Constraints

- JDK 17 for all Maven commands: prefix `JAVA_HOME=$(/usr/libexec/java_home -v 17)`.
- Off by default: with `SERVICE_REGISTRY_ENABLED=false` the model service registers nothing and the `/health` response omits the `registry` key entirely (byte-for-byte unchanged).
- Reuse PR1's `ServiceRegistrar.fromEnvironment`, `ServiceRegistryStore`, `ServiceRegistryProvider`; do not change registration semantics or the Redis schema.
- No new Micrometer dependency in the gateway; observability rides the existing `/health` JSON.
- Never merge to main directly; branch `feat/service-registry-observability`; integrate via PR. Commit after each task.

---

### Task 1: `ServiceRegistryProvider.lastRefreshAtMs()`

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/registry/ServiceRegistryProvider.java`
- Test: `src/test/java/com/recsys/infrastructure/registry/ServiceRegistryProviderTest.java`

**Interfaces:**
- Produces: `long lastRefreshAtMs()` — 0 until the first successful refresh, then the wall-clock ms of the most recent successful snapshot swap.

- [ ] **Step 1: Add the failing test**

Append to `ServiceRegistryProviderTest.java`:

```java
    @Test
    void lastRefreshAtMsIsZeroUntilSuccessThenSet() {
        ServiceRegistryStore store = Mockito.mock(ServiceRegistryStore.class);
        when(store.lookup(Mockito.anyCollection()))
                .thenReturn(Map.of("a", "http://a:1"))
                .thenThrow(new RuntimeException("redis down"));
        ServiceRegistryProvider p = new ServiceRegistryProvider(store, List.of("a"), 0L, null);

        assertThat(p.lastRefreshAtMs()).isZero();
        p.refresh();
        long afterGood = p.lastRefreshAtMs();
        assertThat(afterGood).isGreaterThan(0L);
        p.refresh(); // fails internally -> timestamp unchanged
        assertThat(p.lastRefreshAtMs()).isEqualTo(afterGood);
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test-compile 2>&1 | tail -15`
Expected: FAIL — `lastRefreshAtMs()` does not exist.

- [ ] **Step 3: Implement**

In `ServiceRegistryProvider.java`, add a field beside `snapshot`:

```java
    private volatile long lastRefreshAtMs;
```

In `refresh()`, set it after a successful swap and before `onRefresh`:

```java
    public void refresh() {
        try {
            Map<String, String> loaded = store.lookup(serviceNames);
            this.snapshot = Map.copyOf(loaded);
            this.lastRefreshAtMs = System.currentTimeMillis();
        } catch (Exception e) {
            log.warn("Service registry refresh failed — keeping last-good snapshot: {}", e.toString());
            return;
        }
        try {
            onRefresh.run();
        } catch (Exception e) {
            log.warn("Service registry onRefresh callback failed (non-fatal): {}", e.toString());
        }
    }
```

Add the accessor:

```java
    /** Wall-clock ms of the most recent successful refresh, or 0 if none has succeeded yet. */
    public long lastRefreshAtMs() {
        return lastRefreshAtMs;
    }
```

- [ ] **Step 4: Run the test**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest=ServiceRegistryProviderTest 2>&1 | grep -E "Tests run|BUILD"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/registry/ServiceRegistryProvider.java src/test/java/com/recsys/infrastructure/registry/ServiceRegistryProviderTest.java
git commit -m "feat(registry): expose last-refresh timestamp on provider

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `ServiceRegistrarLifecycle`

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/registry/ServiceRegistrarLifecycle.java`
- Test: `src/test/java/com/recsys/infrastructure/registry/ServiceRegistrarLifecycleTest.java`

**Interfaces:**
- Produces: `new ServiceRegistrarLifecycle(ServiceRegistrar registrarOrNull)`; `void start()`; `void close()`. Both delegate to the registrar when non-null, and are safe no-ops when null.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/infrastructure/registry/ServiceRegistrarLifecycleTest.java`:

```java
package com.recsys.infrastructure.registry;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.Mockito.verify;

class ServiceRegistrarLifecycleTest {

    @Test
    void delegatesToNonNullRegistrar() {
        ServiceRegistrar registrar = Mockito.mock(ServiceRegistrar.class);
        ServiceRegistrarLifecycle lifecycle = new ServiceRegistrarLifecycle(registrar);
        lifecycle.start();
        lifecycle.close();
        verify(registrar).start();
        verify(registrar).close();
    }

    @Test
    void nullRegistrarIsNoOp() {
        ServiceRegistrarLifecycle lifecycle = new ServiceRegistrarLifecycle(null);
        lifecycle.start(); // must not throw
        lifecycle.close(); // must not throw
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test-compile 2>&1 | tail -15`
Expected: FAIL — `ServiceRegistrarLifecycle` does not exist.

- [ ] **Step 3: Implement**

Create `src/main/java/com/recsys/infrastructure/registry/ServiceRegistrarLifecycle.java`:

```java
package com.recsys.infrastructure.registry;

/**
 * Null-safe start/stop wrapper around a {@link ServiceRegistrar}, so a DI container (or any caller)
 * can manage a registrar uniformly whether or not the registry is enabled. When the wrapped registrar
 * is null — the feature is off or the advertise env vars are unset — both operations are no-ops.
 */
public final class ServiceRegistrarLifecycle implements java.io.Closeable {

    private final ServiceRegistrar registrar; // nullable

    public ServiceRegistrarLifecycle(ServiceRegistrar registrar) {
        this.registrar = registrar;
    }

    public void start() {
        if (registrar != null) {
            registrar.start();
        }
    }

    @Override
    public void close() {
        if (registrar != null) {
            registrar.close();
        }
    }
}
```

- [ ] **Step 4: Run the test**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest=ServiceRegistrarLifecycleTest 2>&1 | grep -E "Tests run|BUILD"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/registry/ServiceRegistrarLifecycle.java src/test/java/com/recsys/infrastructure/registry/ServiceRegistrarLifecycleTest.java
git commit -m "feat(registry): null-safe ServiceRegistrarLifecycle wrapper

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Spring `ServiceRegistryConfig` (model service registers)

**Files:**
- Create: `src/main/java/com/recsys/config/ServiceRegistryConfig.java`
- Test: `src/test/java/com/recsys/config/ServiceRegistryConfigTest.java`

**Interfaces:**
- Consumes: the existing `RedisExecutor` bean (from `RedisConfig`), `ServiceRegistryStore`, `ServiceRegistrar`, `ServiceRegistrarLifecycle`.
- Produces: a `ServiceRegistrarLifecycle` bean with `initMethod = "start"`, `destroyMethod = "close"`. `ModelApplication` already component-scans `com.recsys.config`, so no change to `ModelApplication` is needed.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/config/ServiceRegistryConfigTest.java`:

```java
package com.recsys.config;

import com.recsys.infrastructure.redis.RedisExecutor;
import com.recsys.infrastructure.registry.ServiceRegistrarLifecycle;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceRegistryConfigTest {

    @Test
    void buildsLifecycleBeanThatIsSafeNoOpWhenRegistryDisabled() {
        // SERVICE_REGISTRY_ENABLED is unset in the test env -> ServiceRegistrar.fromEnvironment is null
        // -> the lifecycle is a safe no-op (start/close do not touch Redis and do not throw).
        RedisExecutor redis = Mockito.mock(RedisExecutor.class);
        ServiceRegistrarLifecycle lifecycle = new ServiceRegistryConfig().serviceRegistrarLifecycle(redis);

        assertThat(lifecycle).isNotNull();
        lifecycle.start();
        lifecycle.close();
        Mockito.verifyNoInteractions(redis); // disabled -> no registration writes
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test-compile 2>&1 | tail -15`
Expected: FAIL — `ServiceRegistryConfig` does not exist.

- [ ] **Step 3: Implement**

Create `src/main/java/com/recsys/config/ServiceRegistryConfig.java`:

```java
package com.recsys.config;

import com.recsys.infrastructure.redis.RedisExecutor;
import com.recsys.infrastructure.registry.ServiceRegistrar;
import com.recsys.infrastructure.registry.ServiceRegistrarLifecycle;
import com.recsys.infrastructure.registry.ServiceRegistryStore;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers this Spring Boot service (the model service) into the Redis service registry when
 * {@code SERVICE_REGISTRY_ENABLED} is set and the advertise env vars are present. The lifecycle bean is
 * started after context refresh and closed on shutdown; when the registry is disabled it is an inert
 * no-op and no Redis registration occurs.
 */
@Configuration
public class ServiceRegistryConfig {

    @Bean(initMethod = "start", destroyMethod = "close")
    public ServiceRegistrarLifecycle serviceRegistrarLifecycle(RedisExecutor redisExecutor) {
        ServiceRegistryStore store =
                new ServiceRegistryStore(redisExecutor, ServiceRegistryStore.DEFAULT_KEY_PREFIX);
        return new ServiceRegistrarLifecycle(ServiceRegistrar.fromEnvironment(store));
    }
}
```

- [ ] **Step 4: Run the test**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest=ServiceRegistryConfigTest 2>&1 | grep -E "Tests run|BUILD"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/config/ServiceRegistryConfig.java src/test/java/com/recsys/config/ServiceRegistryConfigTest.java
git commit -m "feat(registry): Spring model service self-registers when enabled

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Gateway `/health` registry section

**Files:**
- Modify: `src/main/java/com/recsys/application/gateway/GatewayHealthService.java`
- Modify: `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java`
- Test: `src/test/java/com/recsys/application/gateway/GatewayHealthServiceRegistryTest.java` (new)

**Interfaces:**
- `GatewayHealthService` gains a 5-arg constructor `(routes, timeout, circuitBreakers, gatewayPort, ServiceRegistryProvider registryProvider)`; the existing 4-arg constructor delegates with `null`. When the provider is non-null the JSON payload gains a `registry` object; when null the key is omitted.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/application/gateway/GatewayHealthServiceRegistryTest.java`:

```java
package com.recsys.application.gateway;

import com.recsys.infrastructure.registry.ServiceRegistryProvider;
import com.recsys.infrastructure.registry.ServiceRegistryStore;
import com.recsys.resilience.RouteCircuitBreaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class GatewayHealthServiceRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static MicroserviceRoute route(String name, String service) {
        return new MicroserviceRoute(name, "/api/" + name, name.toUpperCase() + "_URL",
                URI.create("http://127.0.0.1:1"), "/health", service);
    }

    private static JsonNode healthBody(GatewayHealthService svc) throws Exception {
        ServiceRequestContext ctx = ServiceRequestContext.builder(
                HttpRequest.of(HttpMethod.GET, "/health")).build();
        AggregatedHttpResponse resp = svc.serve(ctx, ctx.request()).aggregate().join();
        return MAPPER.readTree(resp.contentUtf8());
    }

    @Test
    void includesRegistrySectionWhenProviderPresent() throws Exception {
        ServiceRegistryStore store = Mockito.mock(ServiceRegistryStore.class);
        when(store.lookup(Mockito.anyCollection())).thenReturn(Map.of("svc-a", "http://registered:6010"));
        ServiceRegistryProvider provider = new ServiceRegistryProvider(store, List.of("svc-a", "svc-b"), 0L, null);
        provider.refresh();

        List<MicroserviceRoute> routes = List.of(route("a", "svc-a"), route("b", "svc-b"));
        Map<String, RouteCircuitBreaker> cbs = Map.of("a", new RouteCircuitBreaker(), "b", new RouteCircuitBreaker());
        GatewayHealthService svc = new GatewayHealthService(routes, Duration.ofMillis(200), cbs, 8010, provider);

        JsonNode registry = healthBody(svc).get("registry");
        assertThat(registry).isNotNull();
        assertThat(registry.get("enabled").asBoolean()).isTrue();
        assertThat(registry.get("services").get("svc-a").get("source").asText()).isEqualTo("registry");
        assertThat(registry.get("services").get("svc-a").get("address").asText()).isEqualTo("http://registered:6010");
        assertThat(registry.get("services").get("svc-b").get("source").asText()).isEqualTo("static");
    }

    @Test
    void omitsRegistrySectionWhenNoProvider() throws Exception {
        List<MicroserviceRoute> routes = List.of(route("a", "svc-a"));
        Map<String, RouteCircuitBreaker> cbs = Map.of("a", new RouteCircuitBreaker());
        GatewayHealthService svc = new GatewayHealthService(routes, Duration.ofMillis(200), cbs, 8010);

        assertThat(healthBody(svc).has("registry")).isFalse();
    }
}
```

Note: `serve(ctx, req)` is the `BaseApiService` entry point; confirm its visibility (`grep -n "serve\|doGet" src/main/java/com/recsys/api/serving/BaseApiService.java`). If `serve` is not directly callable, drive through a `ServerExtension` that registers the service at `/health` and issue a real GET, as `GatewayServerIntegrationTest` does. The upstream health checks target `127.0.0.1:1` (closed) and fail fast, which is fine — the test only asserts the `registry` section.

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test-compile 2>&1 | tail -15`
Expected: FAIL — 5-arg constructor does not exist.

- [ ] **Step 3: Add the provider field, constructor, and `registry` section**

In `GatewayHealthService.java`:

Add the field near the others:

```java
    private final com.recsys.infrastructure.registry.ServiceRegistryProvider registryProvider; // nullable
```

Replace the existing constructor with a 5-arg canonical + 4-arg convenience:

```java
    public GatewayHealthService(List<MicroserviceRoute> routes,
                         Duration timeout,
                         Map<String, RouteCircuitBreaker> circuitBreakers,
                         int gatewayPort) {
        this(routes, timeout, circuitBreakers, gatewayPort, null);
    }

    public GatewayHealthService(List<MicroserviceRoute> routes,
                         Duration timeout,
                         Map<String, RouteCircuitBreaker> circuitBreakers,
                         int gatewayPort,
                         com.recsys.infrastructure.registry.ServiceRegistryProvider registryProvider) {
        this.routes = List.copyOf(routes);
        this.circuitBreakers = Map.copyOf(circuitBreakers);
        this.gatewayPort = gatewayPort;
        this.registryProvider = registryProvider;
        this.healthClients = routes.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        MicroserviceRoute::name,
                        r -> WebClient.builder(r.baseUri().toString())
                                .responseTimeoutMillis(timeout.toMillis() + 500)
                                .build()));
    }
```

In `doGet`, after `payload.put("services", services);` add the registry section:

```java
                            Map<String, Object> registry = registrySection();
                            if (registry != null) {
                                payload.put("registry", registry);
                            }
```

Add the helper method:

```java
    /** Registry observability, or null when the registry consumer is not wired. */
    private Map<String, Object> registrySection() {
        if (registryProvider == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", Boolean.TRUE);
        long last = registryProvider.lastRefreshAtMs();
        out.put("snapshotAgeMs", last == 0 ? null : System.currentTimeMillis() - last);
        Map<String, Object> services = new LinkedHashMap<>();
        for (MicroserviceRoute route : routes) {
            String service = route.serviceName();
            if (service == null || services.containsKey(service)) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            String resolved = registryProvider.resolve(service).orElse(null);
            entry.put("source", resolved != null ? "registry" : "static");
            entry.put("address", resolved);
            services.put(service, entry);
        }
        out.put("services", services);
        return out;
    }
```

- [ ] **Step 4: Pass the provider from the server**

In `MicroserviceGatewayServer.java`, update the health-service registration to pass `registryProvider` (already declared in PR1, nullable). Change:

```java
        sb.service("/health", new GatewayHealthService(allRoutes, timeout, circuitBreakers, port));
```

to:

```java
        sb.service("/health", new GatewayHealthService(allRoutes, timeout, circuitBreakers, port, registryProvider));
```

`registryProvider` is in scope (declared earlier in `main`); it is null when the registry is disabled, so the `/health` response is unchanged in that case.

- [ ] **Step 5: Run the tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest='GatewayHealthServiceRegistryTest,GatewayServerIntegrationTest' 2>&1 | grep -E "Tests run:|BUILD"`
Expected: PASS (the existing 4-arg constructor in `GatewayServerIntegrationTest` still compiles via the convenience constructor).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/application/gateway/GatewayHealthService.java src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java src/test/java/com/recsys/application/gateway/GatewayHealthServiceRegistryTest.java
git commit -m "feat(gateway): report registry resolution + snapshot age in /health

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Docs, full verification, PR

**Files:**
- Modify: `.claude/CLAUDE.md`

- [ ] **Step 1: Note the /health registry section**

In `.claude/CLAUDE.md`, in the API Gateway architecture paragraph (or beside the existing registry env-var text), add a sentence:

```
When the service registry is enabled, the gateway `/health` response includes a `registry` section reporting each service's resolution `source` (`registry` vs `static` fallback) and the snapshot age.
```

- [ ] **Step 2: Full build + test**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test 2>&1 | grep -E "Tests run:|BUILD"`
Expected: final `Tests run: N, Failures: 0, Errors: 0` (N ≈ 907 + new) and `BUILD SUCCESS`.

- [ ] **Step 3: Sanity — the Spring model service starts with the flag off**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest='PredictionIntegrationTest' 2>&1 | grep -E "Tests run:|BUILD"`
Expected: PASS (a Spring context test; the registry bean is an inert no-op when the flag is off, so context startup is unaffected). If no such Spring-context test exists, run any `@SpringBootTest` in `com.recsys.api.rest` (e.g. `grep -rl "@SpringBootTest" src/test/java/com/recsys/api/rest | head -1`) to confirm the context loads with the new config bean.

- [ ] **Step 4: Commit docs**

```bash
git add .claude/CLAUDE.md
git commit -m "docs(registry): note /health registry observability section

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 5: Push and open the PR**

```bash
git push -u origin feat/service-registry-observability
gh pr create --title "feat(registry): Spring model producer + /health observability (PR2)" --body "$(cat <<'EOF'
## Summary
Completes the Redis service registry (Option B) begun in #184.

- **Spring model service self-registers** — a `ServiceRegistryConfig` exposes a lifecycle-managed `ServiceRegistrarLifecycle` (built from the existing `RedisExecutor` bean via `ServiceRegistrar.fromEnvironment`), so `ModelApplication` (8080) participates on the same opt-in env contract as the Armeria backends. A null-safe wrapper makes it an inert no-op when the flag is off.
- **Consumer observability** — the gateway `/health` response gains a `registry` section: per service, whether the gateway resolved it from the `registry` or fell back to `static`, plus the snapshot age. `ServiceRegistryProvider` now exposes `lastRefreshAtMs()`. No new Micrometer dependency — it rides the existing health JSON.

Off by default (`SERVICE_REGISTRY_ENABLED=false`): the model service registers nothing and `/health` omits the `registry` key entirely.

## Testing
- `mvn test` — full suite green.
- Unit: `lastRefreshAtMs` (zero → set on success, unchanged on failure), `ServiceRegistrarLifecycle` (delegate vs null no-op), `ServiceRegistryConfig` (builds a safe-no-op bean when disabled), `/health` registry section (registry vs static, omitted when no provider).
- Spring context loads with the new config bean; model service starts with the flag off.

Spec: `docs/superpowers/specs/2026-07-10-redis-service-registry-pr2-design.md`
Plan: `docs/superpowers/plans/2026-07-10-redis-service-registry-pr2.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: PR created against `main`.

---

## Self-Review

**Spec coverage:**
- Snapshot age accessor → Task 1. ✓
- Null-safe lifecycle wrapper → Task 2. ✓
- Spring config bean, model service registers, no ModelApplication change (already scans `com.recsys.config`) → Task 3. ✓
- `/health` registry section (source registry/static + snapshot age), provider wired, omitted when disabled → Task 4. ✓
- Docs + full verification + Spring-context sanity + PR → Task 5. ✓
- Off-by-default / unchanged-when-off → Tasks 3,4 + Step 3. ✓
- No Micrometer, no schema/semantic change → Global Constraints. ✓

**Placeholder scan:** No TBD/TODO; each code step is complete. Two verify-before-use guards (BaseApiService `serve` visibility in Task 4; presence of a Spring-context test in Task 5) carry concrete fallbacks, not missing content.

**Type consistency:** `ServiceRegistrarLifecycle(ServiceRegistrar)` with `start()`/`close()` is defined in Task 2 and consumed identically in Task 3. `ServiceRegistryProvider.lastRefreshAtMs()` defined in Task 1 is used in Task 4's `registrySection()`. `GatewayHealthService` 5-arg constructor defined in Task 4 is called in Task 4 Step 4 (`MicroserviceGatewayServer`) and the new test; the 4-arg convenience keeps `GatewayServerIntegrationTest` compiling. `ServiceRegistrar.fromEnvironment(ServiceRegistryStore)` and `ServiceRegistryStore(RedisExecutor, String)` match PR1.
