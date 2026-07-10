# Prometheus Registry Metrics — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose service-registry consumer metrics from the gateway on a Prometheus `/metrics` endpoint: services resolved-vs-total, snapshot age, and refresh success/failure counters.

**Architecture:** `ServiceRegistryProvider` gains two refresh counters. A new `GatewayRegistryMetrics` binder registers pull-based gauges/function-counters against a `MeterRegistry`. `MicroserviceGatewayServer` always exposes `PrometheusExpositionService` at `/metrics` (like the other three services) and registers the registry meters when the registry consumer is active.

**Tech Stack:** Java 17, Micrometer (`Gauge`, `FunctionCounter`, `MeterRegistry`, `SimpleMeterRegistry`), Armeria `PrometheusMeterRegistries` + `PrometheusExpositionService`, JUnit 5, AssertJ, Mockito. Build with `JAVA_HOME=$(/usr/libexec/java_home -v 17)`.

## Global Constraints

- JDK 17 for all Maven commands: prefix `JAVA_HOME=$(/usr/libexec/java_home -v 17)`.
- Meter names are snake_case with prefix `gateway_registry_` (repo convention, e.g. `online_serving_qps`).
- Gauges are pull-based (sample provider state on scrape); no background threads, no Redis reads at scrape time.
- `/metrics` is always present; `gateway_registry_*` meters are registered only when `registryProvider != null`.
- No change to registration semantics, the Redis schema, `/health` (#185), or the feature flag. Registry off → no registry meters, behavior otherwise unchanged.
- Never merge to main directly; branch `feat/registry-metrics`; integrate via PR. Commit after each task.

---

### Task 1: `ServiceRegistryProvider` refresh counters

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/registry/ServiceRegistryProvider.java`
- Test: `src/test/java/com/recsys/infrastructure/registry/ServiceRegistryProviderTest.java`

**Interfaces:**
- Produces: `long refreshSuccessCount()`, `long refreshFailureCount()` — monotonic counts of successful/failed refreshes.

- [ ] **Step 1: Add the failing test**

Append to `ServiceRegistryProviderTest.java`:

```java
    @Test
    void refreshCountersTrackSuccessAndFailure() {
        ServiceRegistryStore store = Mockito.mock(ServiceRegistryStore.class);
        when(store.lookup(Mockito.anyCollection()))
                .thenReturn(Map.of("a", "http://a:1"))
                .thenThrow(new RuntimeException("redis down"))
                .thenReturn(Map.of("a", "http://a:1"));
        ServiceRegistryProvider p = new ServiceRegistryProvider(store, List.of("a"), 0L, null);

        assertThat(p.refreshSuccessCount()).isZero();
        assertThat(p.refreshFailureCount()).isZero();
        p.refresh();  // success
        p.refresh();  // failure (kept static)
        p.refresh();  // success
        assertThat(p.refreshSuccessCount()).isEqualTo(2L);
        assertThat(p.refreshFailureCount()).isEqualTo(1L);
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test-compile 2>&1 | tail -15`
Expected: FAIL — `refreshSuccessCount()` does not exist.

- [ ] **Step 3: Implement**

In `ServiceRegistryProvider.java`, add fields beside `lastRefreshAtMs`:

```java
    private volatile long refreshSuccessCount;
    private volatile long refreshFailureCount;
```

In `refresh()`, increment success after the timestamp set, and failure in the catch:

```java
    public void refresh() {
        try {
            Map<String, String> loaded = store.lookup(serviceNames);
            this.snapshot = Map.copyOf(loaded);
            this.lastRefreshAtMs = System.currentTimeMillis();
            this.refreshSuccessCount++;
        } catch (Exception e) {
            this.refreshFailureCount++;
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

Add accessors near `lastRefreshAtMs()`:

```java
    /** Number of successful refreshes since construction. */
    public long refreshSuccessCount() {
        return refreshSuccessCount;
    }

    /** Number of failed (fail-static) refreshes since construction. */
    public long refreshFailureCount() {
        return refreshFailureCount;
    }
```

- [ ] **Step 4: Run the test**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest=ServiceRegistryProviderTest 2>&1 | grep -E "Tests run|BUILD"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/registry/ServiceRegistryProvider.java src/test/java/com/recsys/infrastructure/registry/ServiceRegistryProviderTest.java
git commit -m "feat(registry): count refresh successes and failures on provider

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `GatewayRegistryMetrics` binder

**Files:**
- Create: `src/main/java/com/recsys/metrics/GatewayRegistryMetrics.java`
- Test: `src/test/java/com/recsys/metrics/GatewayRegistryMetricsTest.java`

**Interfaces:**
- Consumes: `MeterRegistry`, `ServiceRegistryProvider`, `Collection<String>`, `LongSupplier`.
- Produces: `static void register(MeterRegistry registry, ServiceRegistryProvider provider, Collection<String> serviceNames, LongSupplier clockMs)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/metrics/GatewayRegistryMetricsTest.java`:

```java
package com.recsys.metrics;

import com.recsys.infrastructure.registry.ServiceRegistryProvider;
import com.recsys.infrastructure.registry.ServiceRegistryStore;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class GatewayRegistryMetricsTest {

    private static ServiceRegistryProvider providerResolving(Map<String, String> map, List<String> known) {
        ServiceRegistryStore store = Mockito.mock(ServiceRegistryStore.class);
        when(store.lookup(Mockito.anyCollection())).thenReturn(map);
        return new ServiceRegistryProvider(store, known, 0L, null);
    }

    @Test
    void registersRegistryGaugesAndCounters() {
        List<String> known = List.of("svc-a", "svc-b");
        ServiceRegistryProvider provider = providerResolving(Map.of("svc-a", "http://a:1"), known);
        provider.refresh(); // one success; svc-a resolves, svc-b does not
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        long fixedNow = 10_000L;

        GatewayRegistryMetrics.register(registry, provider, known, () -> fixedNow + provider.lastRefreshAtMs());

        assertThat(registry.get("gateway_registry_services_total").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("gateway_registry_services_resolved").gauge().value()).isEqualTo(1.0);
        // age = (lastRefreshAtMs + fixedNow) - lastRefreshAtMs = fixedNow ms = 10 s
        assertThat(registry.get("gateway_registry_snapshot_age_seconds").gauge().value()).isEqualTo(10.0);
        assertThat(registry.get("gateway_registry_refresh_total").functionCounter().count()).isEqualTo(1.0);
        assertThat(registry.get("gateway_registry_refresh_failures_total").functionCounter().count()).isEqualTo(0.0);
    }

    @Test
    void snapshotAgeIsMinusOneBeforeFirstRefresh() {
        List<String> known = List.of("svc-a");
        ServiceRegistryProvider provider = providerResolving(Map.of(), known); // never refreshed
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        GatewayRegistryMetrics.register(registry, provider, known, () -> 5_000L);

        assertThat(registry.get("gateway_registry_snapshot_age_seconds").gauge().value()).isEqualTo(-1.0);
        assertThat(registry.get("gateway_registry_services_resolved").gauge().value()).isEqualTo(0.0);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test-compile 2>&1 | tail -15`
Expected: FAIL — `GatewayRegistryMetrics` does not exist.

- [ ] **Step 3: Implement**

Create `src/main/java/com/recsys/metrics/GatewayRegistryMetrics.java`:

```java
package com.recsys.metrics;

import com.recsys.infrastructure.registry.ServiceRegistryProvider;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Collection;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Registers Prometheus meters for the gateway's service-registry consumption. All meters are
 * pull-based — they sample {@link ServiceRegistryProvider} state at scrape time, so no background
 * thread runs and no Redis call happens during a scrape.
 */
public final class GatewayRegistryMetrics {

    private GatewayRegistryMetrics() {}

    public static void register(MeterRegistry registry,
                                ServiceRegistryProvider provider,
                                Collection<String> serviceNames,
                                LongSupplier clockMs) {
        List<String> names = List.copyOf(serviceNames);

        Gauge.builder("gateway_registry_services_total", names, List::size)
                .description("Number of distinct backend services mapped to the registry")
                .register(registry);

        Gauge.builder("gateway_registry_services_resolved", provider,
                        p -> resolvedCount(p, names))
                .description("How many mapped services currently resolve from the registry (rest use static fallback)")
                .register(registry);

        Gauge.builder("gateway_registry_snapshot_age_seconds", provider,
                        p -> snapshotAgeSeconds(p, clockMs))
                .description("Seconds since the last successful registry refresh, or -1 if never refreshed")
                .baseUnit("s")
                .register(registry);

        FunctionCounter.builder("gateway_registry_refresh_total", provider,
                        p -> (double) p.refreshSuccessCount())
                .description("Successful registry refreshes since startup")
                .register(registry);

        FunctionCounter.builder("gateway_registry_refresh_failures_total", provider,
                        p -> (double) p.refreshFailureCount())
                .description("Failed (fail-static) registry refreshes since startup")
                .register(registry);
    }

    private static double resolvedCount(ServiceRegistryProvider provider, List<String> names) {
        long resolved = names.stream().filter(n -> provider.resolve(n).isPresent()).count();
        return (double) resolved;
    }

    private static double snapshotAgeSeconds(ServiceRegistryProvider provider, LongSupplier clockMs) {
        long last = provider.lastRefreshAtMs();
        if (last == 0L) {
            return -1.0;
        }
        return (clockMs.getAsLong() - last) / 1000.0;
    }
}
```

- [ ] **Step 4: Run the test**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest=GatewayRegistryMetricsTest 2>&1 | grep -E "Tests run|BUILD"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/metrics/GatewayRegistryMetrics.java src/test/java/com/recsys/metrics/GatewayRegistryMetricsTest.java
git commit -m "feat(metrics): GatewayRegistryMetrics binder for registry consumption

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Wire `/metrics` and register the meters in the gateway

**Files:**
- Modify: `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java`
- Test: `src/test/java/com/recsys/api/gateway/GatewayMetricsEndpointTest.java` (new)

**Interfaces:**
- Consumes: `PrometheusMeterRegistries`, `PrometheusExpositionService`, `GatewayRegistryMetrics`.

- [ ] **Step 1: Add imports**

In `MicroserviceGatewayServer.java`, add:

```java
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import com.linecorp.armeria.server.metric.PrometheusExpositionService;
import com.recsys.metrics.GatewayRegistryMetrics;
import io.micrometer.prometheus.PrometheusMeterRegistry;
```

- [ ] **Step 2: Build the registry, expose `/metrics`, register registry meters**

Immediately after `ServerBuilder sb = Server.builder().http(port);`, add:

```java
        // Prometheus metrics endpoint (always present, matching the other services). Registry meters
        // are registered only when the registry consumer is active.
        PrometheusMeterRegistry meterRegistry = PrometheusMeterRegistries.defaultRegistry();
        sb.service("/metrics", PrometheusExpositionService.of(meterRegistry.getPrometheusRegistry()));
        if (registryProvider != null) {
            List<String> registrySvcNames = proxyRoutes.stream()
                    .map(MicroserviceRoute::serviceName)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            GatewayRegistryMetrics.register(meterRegistry, registryProvider, registrySvcNames,
                    System::currentTimeMillis);
        }
```

(`registryProvider` is already declared earlier in `main` and is null when the registry is disabled.)

- [ ] **Step 3: Write the endpoint test**

Create `src/test/java/com/recsys/api/gateway/GatewayMetricsEndpointTest.java`:

```java
package com.recsys.api.gateway;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.metric.PrometheusExpositionService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayMetricsEndpointTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            PrometheusMeterRegistry reg = PrometheusMeterRegistries.defaultRegistry();
            sb.service("/metrics", PrometheusExpositionService.of(reg.getPrometheusRegistry()));
        }
    };

    @Test
    void metricsEndpointReturns200() {
        AggregatedHttpResponse resp = server.blockingWebClient().get("/metrics");
        assertThat(resp.status()).isEqualTo(HttpStatus.OK);
    }
}
```

This mirrors the exact wiring used in `MicroserviceGatewayServer` (default registry + exposition service), verifying the endpoint shape without needing Redis. The registry-meter registration itself is covered by `GatewayRegistryMetricsTest` in Task 2.

- [ ] **Step 4: Compile and run**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest='GatewayMetricsEndpointTest,GatewayServerIntegrationTest' 2>&1 | grep -E "Tests run:|BUILD"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java src/test/java/com/recsys/api/gateway/GatewayMetricsEndpointTest.java
git commit -m "feat(gateway): expose /metrics and register registry meters

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Docs, full verification, PR

**Files:**
- Modify: `.claude/CLAUDE.md`

- [ ] **Step 1: Document the endpoint + meters**

In `.claude/CLAUDE.md`, extend the registry `/health` sentence (in the Redis-conventions registry bullet or the gateway architecture paragraph) with:

```
The gateway also exposes Prometheus at `/metrics`; when the registry is enabled it publishes `gateway_registry_services_total`, `gateway_registry_services_resolved`, `gateway_registry_snapshot_age_seconds`, and `gateway_registry_refresh_total` / `_failures_total`.
```

- [ ] **Step 2: Full build + test**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test 2>&1 | grep -E "Tests run:|BUILD"`
Expected: final `Tests run: N, Failures: 0, Errors: 0` (N ≈ 913 + new) and `BUILD SUCCESS`.

- [ ] **Step 3: Boot the gateway and scrape /metrics**

```bash
rm -f /tmp/gw-m.log
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q exec:java -Dexec.mainClass=com.recsys.api.gateway.MicroserviceGatewayServer > /tmp/gw-m.log 2>&1 &
GW=$!; for i in $(seq 1 30); do grep -q "Starting RecSys API gateway" /tmp/gw-m.log && break; sleep 1; done
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8010/metrics
kill "$GW" 2>/dev/null; pkill -f MicroserviceGatewayServer 2>/dev/null
```

Expected: `200`. (Flag off, so no `gateway_registry_*` series — the endpoint still responds.)

- [ ] **Step 4: Commit docs**

```bash
git add .claude/CLAUDE.md
git commit -m "docs(registry): note gateway /metrics endpoint and registry meters

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 5: Push and open the PR**

```bash
git push -u origin feat/registry-metrics
gh pr create --title "feat(gateway): Prometheus registry metrics + /metrics endpoint" --body "$(cat <<'EOF'
## Summary
Adds Prometheus observability for the service-registry consumer, the deferred piece from #185.

- The gateway now exposes a Prometheus `/metrics` endpoint (always-on, matching the other three services).
- When the registry is enabled it publishes consumer metrics: `gateway_registry_services_total`, `gateway_registry_services_resolved`, `gateway_registry_snapshot_age_seconds` (`-1` if never refreshed), and `gateway_registry_refresh_total` / `gateway_registry_refresh_failures_total`.
- `ServiceRegistryProvider` gained `refreshSuccessCount()` / `refreshFailureCount()`; all meters are pull-based (sample provider state at scrape, no Redis call, no background thread).

Scope per brainstorming: gateway consumer metrics only (producer/heartbeat metrics deferred); Armeria request-level + JVM binders are a noted follow-up. Registry off → no `gateway_registry_*` series, behavior otherwise unchanged.

## Testing
- `mvn test` — full suite green.
- Unit: provider refresh counters; `GatewayRegistryMetrics` against a `SimpleMeterRegistry` (resolved/total/age/refresh values, `-1` age before first refresh).
- `/metrics` endpoint returns 200; booted the gateway and scraped it.

Spec: `docs/superpowers/specs/2026-07-10-registry-prometheus-metrics-design.md`
Plan: `docs/superpowers/plans/2026-07-10-registry-prometheus-metrics.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: PR created against `main`.

---

## Self-Review

**Spec coverage:**
- Provider refresh counters → Task 1. ✓
- `GatewayRegistryMetrics` (5 meters, pull-based, injected clock, `-1` sentinel) → Task 2. ✓
- Always-on `/metrics` + conditional registry-meter registration → Task 3. ✓
- Docs + full verify + live scrape + PR → Task 4. ✓
- Registry-off unchanged, no Redis at scrape → Tasks 2 (pull-based) + 3 (conditional) + Step 3. ✓
- Non-goals (producer metrics, request/JVM binders) → not implemented. ✓

**Placeholder scan:** No TBD/TODO; every code step is complete.

**Type consistency:** `ServiceRegistryProvider.refreshSuccessCount()/refreshFailureCount()/lastRefreshAtMs()/resolve()` defined in Task 1 (and prior) are consumed exactly in Task 2's binder. `GatewayRegistryMetrics.register(MeterRegistry, ServiceRegistryProvider, Collection<String>, LongSupplier)` defined in Task 2 is called identically in Task 3. Meter names in the test (`gateway_registry_*`) match the binder. Armeria APIs (`PrometheusMeterRegistries.defaultRegistry()`, `PrometheusExpositionService.of(...)`) match the verified `OnlinePredictionServer` usage.
