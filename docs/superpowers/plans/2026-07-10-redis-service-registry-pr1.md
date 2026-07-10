# Redis Service Registry — PR1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the core of an opt-in, Redis-backed service registry: a store, a producer heartbeat (`ServiceRegistrar`), a consumer poller (`ServiceRegistryProvider`), route↔service mapping on `MicroserviceRoute`, gateway consumption that overlays registered addresses onto the PR #183 endpoint groups, and self-registration in the two Armeria backends. Off by default; static/env behavior unchanged when off.

**Architecture:** New `com.recsys.infrastructure.registry` package cloning the `ShardTopologyStore`/`ShardTopologyProvider` pattern (Redis state → background poll → atomic volatile swap → fail-static). The gateway wraps `UpstreamEndpointGroups` (PR #183) in a `RegistryBackedUpstreams` that rebuilds+swaps groups when a resolved address changes. Redis is only touched when `SERVICE_REGISTRY_ENABLED=true`.

**Tech Stack:** Java 17, Lettuce (`RedisExecutor`, `RedisCommands.set(SetArgs)/mget/del`), Armeria 1.28.4, JUnit 5, AssertJ, Maven. Build with `JAVA_HOME=$(/usr/libexec/java_home -v 17)`.

## Global Constraints

- JDK 17 for all Maven commands: prefix `JAVA_HOME=$(/usr/libexec/java_home -v 17)`.
- Feature-flagged: with `SERVICE_REGISTRY_ENABLED=false` (default) no registry code runs and the gateway opens no Redis connection.
- Service-level registration only (advertised address), never pod IPs — preserves kube-proxy/`PreferClose`.
- Reuse the existing `RedisExecutor` (`c -> c.set(k,v,SetArgs.Builder.px(ttl))`, `c -> c.mget(keys...)`, `c -> c.del(k)`), `LettuceClientFactory.routingFromEnv()`, and `EnvVars`.
- Fail-static everywhere: registry I/O errors never break producing or serving.
- Never merge to main directly; branch `feat/service-registry-core`; integrate via PR. Commit after each task.

---

### Task 1: `MicroserviceRoute.serviceName`

**Files:**
- Modify: `src/main/java/com/recsys/application/gateway/MicroserviceRoute.java`
- Test: `src/test/java/com/recsys/application/gateway/MicroserviceRouteTest.java`

**Interfaces:**
- Produces: `MicroserviceRoute` gains a 6th record component `String serviceName` (nullable). A 5-arg convenience constructor `MicroserviceRoute(name, prefix, envVar, baseUri, healthPath)` delegates to the canonical 6-arg with `serviceName = null`, so existing callers compile unchanged. `defaults()` sets the documented service names.

- [ ] **Step 1: Add the failing test**

Append to `MicroserviceRouteTest.java`:

```java
    @org.junit.jupiter.api.Test
    void fiveArgConstructorDefaultsServiceNameToNull() {
        MicroserviceRoute r = new MicroserviceRoute("x", "/api/x", "X_URL",
                java.net.URI.create("http://localhost:6010"), "/health");
        org.assertj.core.api.Assertions.assertThat(r.serviceName()).isNull();
    }

    @org.junit.jupiter.api.Test
    void defaultsAssignBackendServiceNames() {
        java.util.Map<String, String> byName = MicroserviceRoute.defaults().stream()
                .collect(java.util.stream.Collectors.toMap(MicroserviceRoute::name,
                        r -> String.valueOf(r.serviceName())));
        org.assertj.core.api.Assertions.assertThat(byName.get("embed-recall")).isEqualTo("recsys-catalog-serving");
        org.assertj.core.api.Assertions.assertThat(byName.get("model-inference")).isEqualTo("recsys-model-serving");
        org.assertj.core.api.Assertions.assertThat(byName.get("online-blend")).isEqualTo("recsys-online-serving");
        org.assertj.core.api.Assertions.assertThat(byName.get("llm")).isEqualTo("null");
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test-compile 2>&1 | tail -15`
Expected: FAIL to compile — `serviceName()` does not exist.

- [ ] **Step 3: Add the component, convenience ctor, and mapping**

In `MicroserviceRoute.java`, change the record header to add the component:

```java
public record MicroserviceRoute(String name,
                         String prefix,
                         String envVar,
                         URI baseUri,
                         String healthPath,
                         String serviceName) {
```

Add a 5-arg convenience constructor (place just above the compact canonical constructor block):

```java
    public MicroserviceRoute(String name, String prefix, String envVar, URI baseUri, String healthPath) {
        this(name, prefix, envVar, baseUri, healthPath, null);
    }
```

Keep the existing compact canonical constructor (the `public MicroserviceRoute { ... }` validation block) as-is — it validates all components including the new one; `serviceName` may be null so add no null-check for it.

Update `fromEnv(...)` to accept and pass a service name, and `buildDefaults()` to supply it. Change the `fromEnv` signature and body:

```java
    private static MicroserviceRoute fromEnv(String name,
                                             String prefix,
                                             String envVar,
                                             String defaultBaseUri,
                                             String healthPath,
                                             String serviceName) {
        String raw = System.getenv().getOrDefault(envVar, defaultBaseUri);
        return new MicroserviceRoute(name, prefix, envVar, URI.create(raw), healthPath, serviceName);
    }
```

Update each `routes.add(fromEnv(...))` line in `buildDefaults()` to pass the service name (add a 6th argument):

```java
        routes.add(fromEnv("embed-recall",    "/api/recommend/embedding",   "EMBED_RECALL_SERVICE_URL",    "http://localhost:6010", "/health",       "recsys-catalog-serving"));
        routes.add(fromEnv("model-inference", "/api/recommend/model",       "MODEL_INFERENCE_SERVICE_URL", "http://localhost:8080", "/health/ready", "recsys-model-serving"));
        routes.add(fromEnv("online-blend",    "/api/recommend/online",      "ONLINE_BLEND_SERVICE_URL",    "http://localhost:7010", "/health",       "recsys-online-serving"));
        routes.add(fromEnv("sequential",      "/api/recommend/sequential",  "SEQUENTIAL_SERVICE_URL",      "http://localhost:8080", "/health/ready", "recsys-model-serving"));
        routes.add(fromEnv("user-profile",    "/api/users",    "USER_PROFILE_SERVICE_URL",    "http://localhost:6010", "/health",       "recsys-catalog-serving"));
        routes.add(fromEnv("movie-metadata",  "/api/movies",   "MOVIE_METADATA_SERVICE_URL",  "http://localhost:6010", "/health",       "recsys-catalog-serving"));
        routes.add(fromEnv("feature",         "/api/features", "FEATURE_SERVICE_URL",          "http://localhost:7010", "/health",       "recsys-online-serving"));
        routes.add(fromEnv("knowledge",       "/api/knowledge","KNOWLEDGE_SERVICE_URL",        "http://localhost:8080", "/health/ready", "recsys-model-serving"));
        routes.add(fromEnv("catalog", "/api/catalog", "CATALOG_SERVICE_URL", "http://localhost:6010", "/health",       "recsys-catalog-serving"));
        routes.add(fromEnv("model",   "/api/model",   "MODEL_SERVICE_URL",   "http://localhost:8080", "/health/ready", "recsys-model-serving"));
        routes.add(fromEnv("online",  "/api/online",  "ONLINE_SERVICE_URL",  "http://localhost:7010", "/health",       "recsys-online-serving"));
```

The two optional LLM routes keep using `fromEnvOptional(...)` and remain `serviceName = null` (it already builds via the 5-arg path or the 6-arg with null — update `fromEnvOptional` to pass `null` for the new component):

```java
        return java.util.Optional.of(new MicroserviceRoute(name, prefix, envVar, URI.create(raw), healthPath, null));
```

- [ ] **Step 4: Run tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest=MicroserviceRouteTest 2>&1 | grep -E "Tests run|BUILD"`
Expected: PASS.

- [ ] **Step 5: Compile everything (confirm 5-arg callers still work)**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test-compile 2>&1 | tail -15`
Expected: BUILD SUCCESS (the 7 files using the 5-arg constructor compile via the convenience constructor).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/application/gateway/MicroserviceRoute.java src/test/java/com/recsys/application/gateway/MicroserviceRouteTest.java
git commit -m "feat(gateway): add optional serviceName to MicroserviceRoute

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `ServiceRegistryStore`

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/registry/ServiceRegistryStore.java`
- Test: `src/test/java/com/recsys/infrastructure/registry/ServiceRegistryStoreTest.java`

**Interfaces:**
- Consumes: `RedisExecutor`.
- Produces: `new ServiceRegistryStore(RedisExecutor exec, String keyPrefix)`; `void register(String serviceName, String address, long ttlMs)`; `void deregister(String serviceName)`; `Map<String,String> lookup(Collection<String> serviceNames)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/infrastructure/registry/ServiceRegistryStoreTest.java`:

```java
package com.recsys.infrastructure.registry;

import com.recsys.infrastructure.redis.RedisExecutor;

import io.lettuce.core.KeyValue;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceRegistryStoreTest {

    @SuppressWarnings("unchecked")
    private static RedisExecutor executorOver(RedisCommands<String, String> cmds) {
        RedisExecutor exec = Mockito.mock(RedisExecutor.class);
        when(exec.execute(any(Function.class)))
                .thenAnswer(inv -> ((Function<RedisCommands<String, String>, Object>) inv.getArgument(0)).apply(cmds));
        when(exec.executeRead(any(Function.class)))
                .thenAnswer(inv -> ((Function<RedisCommands<String, String>, Object>) inv.getArgument(0)).apply(cmds));
        Mockito.lenient().doAnswer(inv -> {
            ((Consumer<StatefulRedisConnection<String, String>>) inv.getArgument(0)).accept(null);
            return null;
        }).when(exec).executePipelined(any());
        return exec;
    }

    @Test
    @SuppressWarnings("unchecked")
    void registerSetsKeyWithTtl() {
        RedisCommands<String, String> cmds = Mockito.mock(RedisCommands.class);
        ServiceRegistryStore store = new ServiceRegistryStore(executorOver(cmds), "svc:registry:");

        store.register("recsys-catalog-serving", "http://host:6010", 30_000L);

        verify(cmds).set(eq("svc:registry:recsys-catalog-serving"), eq("http://host:6010"), any(SetArgs.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void lookupMapsPresentEntriesAndOmitsAbsent() {
        RedisCommands<String, String> cmds = Mockito.mock(RedisCommands.class);
        when(cmds.mget("svc:registry:a", "svc:registry:b")).thenReturn(List.of(
                KeyValue.fromNullable("svc:registry:a", "http://a:1"),
                KeyValue.fromNullable("svc:registry:b", null)));
        ServiceRegistryStore store = new ServiceRegistryStore(executorOver(cmds), "svc:registry:");

        Map<String, String> got = store.lookup(List.of("a", "b"));

        assertThat(got).containsExactly(Map.entry("a", "http://a:1"));
    }

    @Test
    void lookupOfEmptyReturnsEmpty() {
        RedisCommands<String, String> cmds = Mockito.mock(RedisCommands.class);
        ServiceRegistryStore store = new ServiceRegistryStore(executorOver(cmds), "svc:registry:");
        assertThat(store.lookup(List.of())).isEmpty();
    }
}
```

Confirm Mockito is available: `grep -n "mockito" pom.xml`. If absent, rewrite the test with a hand-written `RedisExecutor`/`RedisCommands` stub implementing only `set`, `mget`, `del` (throw `UnsupportedOperationException` elsewhere) — do not add a dependency.

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test-compile 2>&1 | tail -15`
Expected: FAIL — `ServiceRegistryStore` does not exist.

- [ ] **Step 3: Implement `ServiceRegistryStore`**

Create `src/main/java/com/recsys/infrastructure/registry/ServiceRegistryStore.java`:

```java
package com.recsys.infrastructure.registry;

import com.recsys.infrastructure.redis.RedisExecutor;

import io.lettuce.core.KeyValue;
import io.lettuce.core.SetArgs;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Authoritative service-registry entries in Redis. One key per service —
 * {@code <prefix><serviceName>} → the advertised address string — written with a TTL that the
 * producer renews via heartbeat, so a service whose replicas all stop renewing expires automatically.
 */
public final class ServiceRegistryStore {

    public static final String DEFAULT_KEY_PREFIX = "svc:registry:";

    private final RedisExecutor exec;
    private final String keyPrefix;

    public ServiceRegistryStore(RedisExecutor exec, String keyPrefix) {
        this.exec = exec;
        this.keyPrefix = keyPrefix;
    }

    public void register(String serviceName, String address, long ttlMs) {
        String key = keyPrefix + serviceName;
        exec.execute(c -> c.set(key, address, SetArgs.Builder.px(ttlMs)));
    }

    public void deregister(String serviceName) {
        String key = keyPrefix + serviceName;
        exec.execute(c -> c.del(key));
    }

    public Map<String, String> lookup(Collection<String> serviceNames) {
        if (serviceNames.isEmpty()) {
            return Map.of();
        }
        String[] keys = serviceNames.stream().map(n -> keyPrefix + n).toArray(String[]::new);
        List<KeyValue<String, String>> values = exec.executeRead(c -> c.mget(keys));
        Map<String, String> result = new LinkedHashMap<>();
        for (KeyValue<String, String> kv : values) {
            if (kv.hasValue()) {
                result.put(stripPrefix(kv.getKey()), kv.getValue());
            }
        }
        return result;
    }

    private String stripPrefix(String key) {
        return key.startsWith(keyPrefix) ? key.substring(keyPrefix.length()) : key;
    }
}
```

- [ ] **Step 4: Run the test**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest=ServiceRegistryStoreTest 2>&1 | grep -E "Tests run|BUILD"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/registry/ServiceRegistryStore.java src/test/java/com/recsys/infrastructure/registry/ServiceRegistryStoreTest.java
git commit -m "feat(registry): Redis service-registry store (SET PX / MGET / DEL)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `ServiceRegistrar` (producer heartbeat)

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/registry/ServiceRegistrar.java`
- Test: `src/test/java/com/recsys/infrastructure/registry/ServiceRegistrarTest.java`

**Interfaces:**
- Consumes: `ServiceRegistryStore`.
- Produces: `new ServiceRegistrar(ServiceRegistryStore store, String serviceName, String address, long heartbeatMs, long ttlMs)`; `void start()`; `void close()`; a package-private `void heartbeat()` for tests. `static ServiceRegistrar fromEnvironment(ServiceRegistryStore store)` returning `null` when disabled or misconfigured (logs a warning).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/infrastructure/registry/ServiceRegistrarTest.java`:

```java
package com.recsys.infrastructure.registry;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

class ServiceRegistrarTest {

    @Test
    void heartbeatRegistersWithConfiguredTtl() {
        ServiceRegistryStore store = Mockito.mock(ServiceRegistryStore.class);
        ServiceRegistrar reg = new ServiceRegistrar(store, "svc-a", "http://a:1", 1000L, 3000L);
        reg.heartbeat();
        verify(store).register("svc-a", "http://a:1", 3000L);
    }

    @Test
    void heartbeatSwallowsStoreErrors() {
        ServiceRegistryStore store = Mockito.mock(ServiceRegistryStore.class);
        doThrow(new RuntimeException("redis down")).when(store).register(Mockito.any(), Mockito.any(), Mockito.anyLong());
        ServiceRegistrar reg = new ServiceRegistrar(store, "svc-a", "http://a:1", 1000L, 3000L);
        reg.heartbeat(); // must not throw
    }

    @Test
    void closeBestEffortDeregisters() {
        ServiceRegistryStore store = Mockito.mock(ServiceRegistryStore.class);
        ServiceRegistrar reg = new ServiceRegistrar(store, "svc-a", "http://a:1", 1000L, 3000L);
        reg.start();
        reg.close();
        verify(store, atLeastOnce()).register("svc-a", "http://a:1", 3000L);
        verify(store).deregister("svc-a");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test-compile 2>&1 | tail -15`
Expected: FAIL — `ServiceRegistrar` does not exist.

- [ ] **Step 3: Implement `ServiceRegistrar`**

Create `src/main/java/com/recsys/infrastructure/registry/ServiceRegistrar.java`:

```java
package com.recsys.infrastructure.registry;

import com.recsys.config.EnvVars;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Registers one service's advertised address into the {@link ServiceRegistryStore} and renews it on a
 * daemon heartbeat. All store I/O is best-effort — failures are logged and never break serving.
 */
public final class ServiceRegistrar implements java.io.Closeable {

    private static final Logger log = LoggerFactory.getLogger(ServiceRegistrar.class);

    private final ServiceRegistryStore store;
    private final String serviceName;
    private final String address;
    private final long heartbeatMs;
    private final long ttlMs;

    private ScheduledExecutorService scheduler;
    private volatile boolean closed;

    public ServiceRegistrar(ServiceRegistryStore store, String serviceName, String address,
                            long heartbeatMs, long ttlMs) {
        this.store = store;
        this.serviceName = serviceName;
        this.address = address;
        this.heartbeatMs = heartbeatMs;
        this.ttlMs = ttlMs;
    }

    /** Builds a registrar from env, or returns null when disabled/misconfigured. */
    public static ServiceRegistrar fromEnvironment(ServiceRegistryStore store) {
        if (!EnvVars.readBool("SERVICE_REGISTRY_ENABLED", false)) {
            return null;
        }
        String name = System.getenv("SERVICE_REGISTRY_SERVICE_NAME");
        String address = System.getenv("SERVICE_REGISTRY_ADVERTISE_URL");
        if (name == null || name.isBlank() || address == null || address.isBlank()) {
            log.warn("SERVICE_REGISTRY_ENABLED but SERVICE_REGISTRY_SERVICE_NAME/ADVERTISE_URL unset — not registering");
            return null;
        }
        long heartbeatMs = EnvVars.readLong("SERVICE_REGISTRY_HEARTBEAT_MS", 10_000L);
        long ttlMs = EnvVars.readLong("SERVICE_REGISTRY_TTL_MS", 30_000L);
        return new ServiceRegistrar(store, name, address, heartbeatMs, ttlMs);
    }

    public void start() {
        heartbeat();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "svc-registry-heartbeat");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::heartbeat, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
        log.info("Service registry: registered '{}' -> {} (heartbeat {} ms, ttl {} ms)",
                serviceName, address, heartbeatMs, ttlMs);
    }

    void heartbeat() {
        try {
            store.register(serviceName, address, ttlMs);
        } catch (Exception e) {
            log.warn("Service registry heartbeat for '{}' failed (non-fatal): {}", serviceName, e.toString());
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        try {
            store.deregister(serviceName);
        } catch (Exception e) {
            log.warn("Service registry deregister for '{}' failed (non-fatal): {}", serviceName, e.toString());
        }
    }
}
```

- [ ] **Step 4: Run the test**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest=ServiceRegistrarTest 2>&1 | grep -E "Tests run|BUILD"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/registry/ServiceRegistrar.java src/test/java/com/recsys/infrastructure/registry/ServiceRegistrarTest.java
git commit -m "feat(registry): ServiceRegistrar heartbeat producer

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: `ServiceRegistryProvider` (consumer poller)

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/registry/ServiceRegistryProvider.java`
- Test: `src/test/java/com/recsys/infrastructure/registry/ServiceRegistryProviderTest.java`

**Interfaces:**
- Consumes: `ServiceRegistryStore`.
- Produces: `new ServiceRegistryProvider(ServiceRegistryStore store, Collection<String> serviceNames, long refreshMs, Runnable onRefresh)`; `void start()`; `void refresh()`; `Optional<String> resolve(String serviceName)`; `void stop()`. `onRefresh` may be `null` (no-op); it is invoked after every snapshot swap (including the initial refresh), letting a consumer rebuild when the map changes.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/infrastructure/registry/ServiceRegistryProviderTest.java`:

```java
package com.recsys.infrastructure.registry;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class ServiceRegistryProviderTest {

    @Test
    void refreshSwapsSnapshotAndResolves() {
        ServiceRegistryStore store = Mockito.mock(ServiceRegistryStore.class);
        when(store.lookup(Mockito.anyCollection())).thenReturn(Map.of("a", "http://a:1"));
        ServiceRegistryProvider p = new ServiceRegistryProvider(store, List.of("a"), 0L, null);

        p.refresh();

        assertThat(p.resolve("a")).contains("http://a:1");
        assertThat(p.resolve("missing")).isEmpty();
    }

    @Test
    void failStaticKeepsLastGoodSnapshot() {
        ServiceRegistryStore store = Mockito.mock(ServiceRegistryStore.class);
        when(store.lookup(Mockito.anyCollection()))
                .thenReturn(Map.of("a", "http://a:1"))
                .thenThrow(new RuntimeException("redis down"));
        ServiceRegistryProvider p = new ServiceRegistryProvider(store, List.of("a"), 0L, null);

        p.refresh();          // good
        p.refresh();          // throws internally -> keep last good

        assertThat(p.resolve("a")).contains("http://a:1");
    }

    @Test
    void onRefreshCallbackFiresAfterSwap() {
        ServiceRegistryStore store = Mockito.mock(ServiceRegistryStore.class);
        when(store.lookup(Mockito.anyCollection())).thenReturn(Map.of("a", "http://a:1"));
        int[] calls = {0};
        ServiceRegistryProvider p = new ServiceRegistryProvider(store, List.of("a"), 0L, () -> calls[0]++);

        p.refresh();

        assertThat(calls[0]).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test-compile 2>&1 | tail -15`
Expected: FAIL — `ServiceRegistryProvider` does not exist.

- [ ] **Step 3: Implement `ServiceRegistryProvider`**

Create `src/main/java/com/recsys/infrastructure/registry/ServiceRegistryProvider.java`:

```java
package com.recsys.infrastructure.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically-refreshed view of the service registry. Reads are lock-free (a single volatile
 * immutable {@code Map}); a refresh atomically swaps it. On any refresh error the last-good snapshot
 * is retained — fail-static, so registry I/O never breaks the request path.
 */
public final class ServiceRegistryProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceRegistryProvider.class);

    private final ServiceRegistryStore store;
    private final List<String> serviceNames;
    private final long refreshMs;
    private final Runnable onRefresh;

    private volatile Map<String, String> snapshot = Map.of();
    ScheduledExecutorService scheduler; // package-private for shutdown assertions

    public ServiceRegistryProvider(ServiceRegistryStore store, Collection<String> serviceNames,
                                   long refreshMs, Runnable onRefresh) {
        this.store = store;
        this.serviceNames = List.copyOf(serviceNames);
        this.refreshMs = refreshMs;
        this.onRefresh = onRefresh == null ? () -> {} : onRefresh;
    }

    public void start() {
        refresh();
        if (refreshMs > 0) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "svc-registry-refresh");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(this::refresh, refreshMs, refreshMs, TimeUnit.MILLISECONDS);
        }
    }

    public void refresh() {
        try {
            Map<String, String> loaded = store.lookup(serviceNames);
            this.snapshot = Map.copyOf(loaded);
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

    public Optional<String> resolve(String serviceName) {
        if (serviceName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.get(serviceName));
    }

    public void stop() {
        if (scheduler == null) {
            return;
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 4: Run the test**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest=ServiceRegistryProviderTest 2>&1 | grep -E "Tests run|BUILD"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/registry/ServiceRegistryProvider.java src/test/java/com/recsys/infrastructure/registry/ServiceRegistryProviderTest.java
git commit -m "feat(registry): ServiceRegistryProvider poller (fail-static snapshot)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: `RegistryBackedUpstreams` (gateway consumer integration)

**Files:**
- Create: `src/main/java/com/recsys/application/gateway/RegistryBackedUpstreams.java`
- Test: `src/test/java/com/recsys/application/gateway/RegistryBackedUpstreamsTest.java`

**Interfaces:**
- Consumes: `MicroserviceRoute`, `UpstreamEndpointGroups` (+ its `HealthCheckConfig`), `ServiceRegistryProvider`.
- Produces: `new RegistryBackedUpstreams(List<MicroserviceRoute> routes, Duration timeout, Function<? super HttpClient, ? extends HttpClient> decorator, UpstreamEndpointGroups.HealthCheckConfig healthConfig, ServiceRegistryProvider provider)`; `WebClient clientFor(String routeName)`; `void rebuildIfChanged()`; `void close()`. It wires `provider`'s `onRefresh` is NOT set here (the wiring in Task 6 passes `rebuildIfChanged` as the provider's callback); this class only reacts when `rebuildIfChanged()` is invoked.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/application/gateway/RegistryBackedUpstreamsTest.java`:

```java
package com.recsys.application.gateway;

import com.recsys.infrastructure.registry.ServiceRegistryProvider;
import com.recsys.infrastructure.registry.ServiceRegistryStore;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class RegistryBackedUpstreamsTest {

    private static MicroserviceRoute route() {
        return new MicroserviceRoute("catalog", "/api/catalog", "CATALOG_SERVICE_URL",
                URI.create("http://static-host:6010"), "/health", "recsys-catalog-serving");
    }

    private static UpstreamEndpointGroups.HealthCheckConfig noProbe() {
        return new UpstreamEndpointGroups.HealthCheckConfig(false, 10_000L);
    }

    private static ServiceRegistryProvider providerReturning(Map<String, String> map) {
        ServiceRegistryStore store = Mockito.mock(ServiceRegistryStore.class);
        when(store.lookup(Mockito.anyCollection())).thenReturn(map);
        ServiceRegistryProvider p = new ServiceRegistryProvider(store, map.keySet(), 0L, null);
        p.refresh();
        return p;
    }

    @Test
    void resolvesRegisteredAddressOverStatic() {
        ServiceRegistryProvider provider =
                providerReturning(Map.of("recsys-catalog-serving", "http://registered-host:6010"));
        RegistryBackedUpstreams ru = new RegistryBackedUpstreams(
                List.of(route()), Duration.ofSeconds(2), null, noProbe(), provider);
        try {
            // The WebClient's base URI reflects the registered address, not the static one.
            assertThat(ru.clientFor("catalog").uri().toString()).contains("registered-host");
        } finally {
            ru.close();
        }
    }

    @Test
    void fallsBackToStaticWhenUnregistered() {
        ServiceRegistryProvider provider = providerReturning(Map.of()); // nothing registered
        RegistryBackedUpstreams ru = new RegistryBackedUpstreams(
                List.of(route()), Duration.ofSeconds(2), null, noProbe(), provider);
        try {
            assertThat(ru.clientFor("catalog").uri().toString()).contains("static-host");
        } finally {
            ru.close();
        }
    }
}
```

Note: verify Armeria `WebClient.uri()` exists (`javap -cp <armeria.jar> com.linecorp.armeria.client.WebClient | grep uri`); if the accessor differs, assert on `ru.clientFor("catalog").scheme()`/endpoint via whatever accessor is present, or assert indirectly by resolving the route and comparing the computed base URI through a package-private `resolvedBaseUri(route)` helper you expose for the test.

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test-compile 2>&1 | tail -15`
Expected: FAIL — `RegistryBackedUpstreams` does not exist.

- [ ] **Step 3: Implement `RegistryBackedUpstreams`**

Create `src/main/java/com/recsys/application/gateway/RegistryBackedUpstreams.java`:

```java
package com.recsys.application.gateway;

import com.recsys.infrastructure.registry.ServiceRegistryProvider;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Overlays registry-resolved addresses onto {@link UpstreamEndpointGroups}. Holds a current
 * {@code UpstreamEndpointGroups} built from routes whose base URI is the registered address (when
 * present) or the static route address (fallback). When {@link #rebuildIfChanged()} observes a change
 * in the resolved address map it rebuilds the groups, atomically swaps them, and closes the old set —
 * the {@code ShardTopologyProvider} swap pattern applied to endpoint groups.
 */
final class RegistryBackedUpstreams implements java.io.Closeable {

    private final List<MicroserviceRoute> routes;
    private final Duration timeout;
    private final Function<? super HttpClient, ? extends HttpClient> decorator;
    private final UpstreamEndpointGroups.HealthCheckConfig healthConfig;
    private final ServiceRegistryProvider provider;

    private volatile Map<String, String> resolvedAddresses;   // routeName -> effective base URI
    private volatile UpstreamEndpointGroups current;
    private volatile boolean closed;

    RegistryBackedUpstreams(List<MicroserviceRoute> routes,
                            Duration timeout,
                            Function<? super HttpClient, ? extends HttpClient> decorator,
                            UpstreamEndpointGroups.HealthCheckConfig healthConfig,
                            ServiceRegistryProvider provider) {
        this.routes = List.copyOf(routes);
        this.timeout = timeout;
        this.decorator = decorator;
        this.healthConfig = healthConfig;
        this.provider = provider;
        this.resolvedAddresses = resolveAddresses();
        this.current = build(this.resolvedAddresses);
    }

    private Map<String, String> resolveAddresses() {
        Map<String, String> resolved = new LinkedHashMap<>();
        for (MicroserviceRoute route : routes) {
            String effective = provider.resolve(route.serviceName())
                    .orElse(route.baseUri().toString());
            resolved.put(route.name(), effective);
        }
        return resolved;
    }

    private UpstreamEndpointGroups build(Map<String, String> resolved) {
        List<MicroserviceRoute> effectiveRoutes = new ArrayList<>(routes.size());
        for (MicroserviceRoute route : routes) {
            URI effective = URI.create(resolved.get(route.name()));
            effectiveRoutes.add(new MicroserviceRoute(route.name(), route.prefix(), route.envVar(),
                    effective, route.healthPath(), route.serviceName()));
        }
        return UpstreamEndpointGroups.create(effectiveRoutes, timeout, decorator, healthConfig);
    }

    WebClient clientFor(String routeName) {
        return current.clientFor(routeName);
    }

    synchronized void rebuildIfChanged() {
        if (closed) {
            return;
        }
        Map<String, String> next = resolveAddresses();
        if (next.equals(resolvedAddresses)) {
            return;
        }
        UpstreamEndpointGroups rebuilt = build(next);
        UpstreamEndpointGroups old = current;
        this.current = rebuilt;
        this.resolvedAddresses = next;
        old.close();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        current.close();
    }
}
```

- [ ] **Step 4: Run the test**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest=RegistryBackedUpstreamsTest 2>&1 | grep -E "Tests run|BUILD"`
Expected: PASS. If `WebClient.uri()` is unavailable, adjust the assertion per the Step 1 note.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/application/gateway/RegistryBackedUpstreams.java src/test/java/com/recsys/application/gateway/RegistryBackedUpstreamsTest.java
git commit -m "feat(gateway): RegistryBackedUpstreams overlays registered addresses

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Wire the gateway consumer behind the flag

**Files:**
- Modify: `src/main/java/com/recsys/application/gateway/GatewayRequestForwarder.java`
- Modify: `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java`

**Interfaces:**
- `GatewayRequestForwarder` gains an internal `UpstreamClients` seam so it can hold either a static `UpstreamEndpointGroups` or a `RegistryBackedUpstreams`. Simplest: add a package-private constructor that accepts an already-built `RegistryBackedUpstreams`, and route `clientFor`/`close` through a small internal functional pair. To avoid an interface refactor, store two nullable fields and delegate.

- [ ] **Step 1: Let the forwarder delegate to either upstreams impl**

In `GatewayRequestForwarder.java`, replace the single `private final UpstreamEndpointGroups upstreams;` field with two nullable fields and a delegating accessor:

```java
    private final UpstreamEndpointGroups staticUpstreams;   // non-null when registry disabled
    private final RegistryBackedUpstreams registryUpstreams; // non-null when registry enabled
```

Update the two constructors: the existing full constructor (with `HealthCheckConfig`) keeps building `staticUpstreams` and sets `registryUpstreams = null`. Add a new package-private constructor that takes a pre-built `RegistryBackedUpstreams` and sets `staticUpstreams = null`:

```java
    GatewayRequestForwarder(Map<String, RouteCircuitBreaker> circuitBreakers,
                            GatewayRateLimiter rateLimiter,
                            RegistryBackedUpstreams registryUpstreams) {
        this.circuitBreakers = Map.copyOf(circuitBreakers);
        this.rateLimiter = rateLimiter == null ? GatewayRateLimiter.disabled() : rateLimiter;
        this.staticUpstreams = null;
        this.registryUpstreams = registryUpstreams;
    }
```

In the existing constructor body, set `this.registryUpstreams = null;` and keep `this.staticUpstreams = UpstreamEndpointGroups.create(...)`.

Replace the client lookup in `forward(...)`:

```java
        WebClient client = clientFor(route.name());
```

Add the delegating accessor and update `close()`:

```java
    private WebClient clientFor(String routeName) {
        return registryUpstreams != null
                ? registryUpstreams.clientFor(routeName)
                : staticUpstreams.clientFor(routeName);
    }

    @Override
    public void close() {
        if (registryUpstreams != null) {
            registryUpstreams.close();
        } else {
            staticUpstreams.close();
        }
    }
```

Also expose a static factory the server uses to build the registry-backed forwarder, so the retry decorator stays defined in one place. Add:

```java
    /** Builds a forwarder whose upstreams are registry-overlaid. Used when the registry is enabled. */
    public static GatewayRequestForwarder registryBacked(
            List<MicroserviceRoute> routes, Duration timeout,
            Map<String, RouteCircuitBreaker> circuitBreakers, GatewayRateLimiter rateLimiter,
            com.recsys.infrastructure.registry.ServiceRegistryProvider provider) {
        RetryRule retryRule = RetryRule.builder()
                .onException((ctx, cause) ->
                        cause instanceof java.io.IOException
                                && !(cause instanceof java.net.SocketTimeoutException))
                .thenBackoff(Backoff.fixed(50));
        Function<? super com.linecorp.armeria.client.HttpClient, RetryingClient> retryDecorator =
                RetryingClient.builder(retryRule).maxTotalAttempts(2).newDecorator();
        RegistryBackedUpstreams upstreams = new RegistryBackedUpstreams(
                routes, timeout, retryDecorator,
                UpstreamEndpointGroups.HealthCheckConfig.fromEnvironment(), provider);
        return new GatewayRequestForwarder(circuitBreakers, rateLimiter, upstreams);
    }

    RegistryBackedUpstreams registryUpstreams() { return registryUpstreams; } // for wiring the provider callback
```

- [ ] **Step 2: Wire the server behind the flag**

In `MicroserviceGatewayServer.main`, after `GatewayRateLimiter`/`GatewayAuthenticator` are built and before `RecommendationGatewayService`, replace the `GatewayRequestForwarder forwarder = new GatewayRequestForwarder(...)` line with a flag-gated build. Add near the top imports:

```java
import com.recsys.infrastructure.redis.LettuceClientFactory;
import com.recsys.infrastructure.redis.RedisExecutor;
import com.recsys.infrastructure.registry.ServiceRegistryProvider;
import com.recsys.infrastructure.registry.ServiceRegistryStore;
```

Replace the forwarder construction with:

```java
        boolean registryEnabled = EnvVars.readBool("SERVICE_REGISTRY_ENABLED", false);
        RedisExecutor registryRedis = null;
        ServiceRegistryProvider registryProvider = null;
        GatewayRequestForwarder forwarder;
        if (registryEnabled) {
            registryRedis = LettuceClientFactory.routingFromEnv();
            ServiceRegistryStore registryStore =
                    new ServiceRegistryStore(registryRedis, ServiceRegistryStore.DEFAULT_KEY_PREFIX);
            java.util.List<String> serviceNames = proxyRoutes.stream()
                    .map(MicroserviceRoute::serviceName)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            long refreshMs = EnvVars.readLong("SERVICE_REGISTRY_REFRESH_MS", 10_000L);
            // Provider is created first; its onRefresh callback rebuilds the forwarder's upstreams.
            GatewayRequestForwarder[] holder = new GatewayRequestForwarder[1];
            registryProvider = new ServiceRegistryProvider(registryStore, serviceNames, refreshMs,
                    () -> { if (holder[0] != null) holder[0].registryUpstreams().rebuildIfChanged(); });
            forwarder = GatewayRequestForwarder.registryBacked(
                    proxyRoutes, timeout, circuitBreakers, rateLimiter, registryProvider);
            holder[0] = forwarder;
            registryProvider.start();
            log.info("Service registry consumer enabled ({} services, refresh {} ms)",
                    serviceNames.size(), refreshMs);
        } else {
            forwarder = new GatewayRequestForwarder(proxyRoutes, timeout, circuitBreakers, rateLimiter);
        }
```

Update the shutdown hook to stop the provider and close Redis when present. Change the hook body to:

```java
        final ServiceRegistryProvider providerToStop = registryProvider;
        final RedisExecutor redisToClose = registryRedis;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down API gateway...");
            server.stop().join();
            forwarder.close();
            if (providerToStop != null) providerToStop.stop();
            if (redisToClose != null) redisToClose.close();
            if (llmFactoryToClose != null) {
                llmFactoryToClose.close();
            }
        }));
```

(Remove the previous plain `GatewayRequestForwarder forwarder = new GatewayRequestForwarder(proxyRoutes, timeout, circuitBreakers, rateLimiter);` line — it is now inside the `else` branch.)

- [ ] **Step 3: Compile**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q compile 2>&1 | tail -15`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Run the gateway suite (flag off path unchanged)**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest='RecommendationGatewayServiceTest,GatewayServerIntegrationTest,GatewayRequestForwarderTest,RegistryBackedUpstreamsTest' 2>&1 | grep -E "Tests run:|BUILD"`
Expected: all PASS (flag defaults off, so these exercise the static path exactly as before).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/application/gateway/GatewayRequestForwarder.java src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java
git commit -m "feat(gateway): consume service registry behind SERVICE_REGISTRY_ENABLED

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Register the two Armeria backends

**Files:**
- Modify: `src/main/java/com/recsys/api/serving/RecSysServer.java`
- Modify: `src/main/java/com/recsys/api/online/OnlinePredictionServer.java`

**Interfaces:**
- Consumes: `ServiceRegistrar.fromEnvironment(ServiceRegistryStore)`, `ServiceRegistryStore`.

- [ ] **Step 1: Register in `RecSysServer`**

In `RecSysServer.main`, after `RedisExecutor jedisPool = LettuceClientFactory.routingFromEnv();` add:

```java
        com.recsys.infrastructure.registry.ServiceRegistrar registrar =
                com.recsys.infrastructure.registry.ServiceRegistrar.fromEnvironment(
                        new com.recsys.infrastructure.registry.ServiceRegistryStore(
                                jedisPool, com.recsys.infrastructure.registry.ServiceRegistryStore.DEFAULT_KEY_PREFIX));
        if (registrar != null) registrar.start();
```

In the server's shutdown hook (find `Runtime.getRuntime().addShutdownHook` in this file), add `if (registrar != null) registrar.close();` before the Redis/executor is closed. If `registrar` is not effectively final where the hook is defined, hoist its declaration so it is in scope and effectively final (assign once).

- [ ] **Step 2: Register in `OnlinePredictionServer`**

Apply the identical pattern in `OnlinePredictionServer.main` after its `RedisExecutor jedisPool = LettuceClientFactory.routingFromEnv();` (line ~66) and add `if (registrar != null) registrar.close();` to its shutdown hook.

- [ ] **Step 3: Compile**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q compile 2>&1 | tail -15`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Sanity test the servers still start (flag off = no registrar)**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest='RecSysServerIntegrationTest,OnlinePredictionServerIntegrationTest' 2>&1 | grep -E "Tests run:|BUILD"`
Expected: PASS (with the flag off, `fromEnvironment` returns null and no registrar starts).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/api/serving/RecSysServer.java src/main/java/com/recsys/api/online/OnlinePredictionServer.java
git commit -m "feat(registry): Armeria backends self-register when enabled

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Docs, full verification, PR

**Files:**
- Modify: `.claude/CLAUDE.md`

- [ ] **Step 1: Document the registry env vars + Redis conventions**

In `.claude/CLAUDE.md`, append to the gateway env-var sentence:

```
`SERVICE_REGISTRY_ENABLED` (default false; opt-in Redis-backed service registry — backends self-register their advertised address with a heartbeat and the gateway resolves upstreams from it, falling back to the static route address when a service is unregistered or Redis is unavailable), `SERVICE_REGISTRY_HEARTBEAT_MS` (default 10000), `SERVICE_REGISTRY_TTL_MS` (default 30000), `SERVICE_REGISTRY_REFRESH_MS` (gateway poll, default 10000), and per-service `SERVICE_REGISTRY_SERVICE_NAME` / `SERVICE_REGISTRY_ADVERTISE_URL`.
```

In the "Redis Conventions" section add a bullet:

```
- `svc:registry:<serviceName>` — opt-in service registry: advertised address string, TTL-renewed by each backend's heartbeat (liveness = key present)
```

- [ ] **Step 2: Full build + test**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test 2>&1 | grep -E "Tests run:|BUILD"`
Expected: final `Tests run: N, Failures: 0, Errors: 0` (N ≈ 893 + new tests) and `BUILD SUCCESS`.

- [ ] **Step 3: Boot the gateway with the flag off and on**

Flag off (default) — confirm no Redis/registry log lines and normal startup:

```bash
rm -f /tmp/gw-off.log
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q exec:java -Dexec.mainClass=com.recsys.api.gateway.MicroserviceGatewayServer > /tmp/gw-off.log 2>&1 &
GW=$!; for i in $(seq 1 30); do grep -q "Starting RecSys API gateway" /tmp/gw-off.log && break; sleep 1; done
grep -E "Starting RecSys API gateway|Service registry" /tmp/gw-off.log; kill "$GW" 2>/dev/null; pkill -f MicroserviceGatewayServer 2>/dev/null
```

Expected: startup line present; NO "Service registry consumer enabled" line.

- [ ] **Step 4: Commit docs**

```bash
git add .claude/CLAUDE.md
git commit -m "docs(registry): document service-registry env vars and Redis key

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 5: Push and open the PR**

```bash
git push -u origin feat/service-registry-core
gh pr create --title "feat(registry): Redis-backed service registry (PR1 core)" --body "$(cat <<'EOF'
## Summary
Adds an opt-in, Redis-backed **service registry** (Option B from the service-discovery investigation), off by default. Backends self-register their advertised **service-level** address with a heartbeat; the gateway resolves upstreams from the registry and overlays them onto the PR #183 health-checked endpoint groups, **falling back to the static route address** whenever a service is unregistered or Redis is unavailable (fail-static). Service-level (not pod-level) registration preserves kube-proxy/`PreferClose`.

PR1 = core + the two Armeria backends register + gateway consumer. PR2 will add the Spring model service and observability.

### Components (new `com.recsys.infrastructure.registry`)
- `ServiceRegistryStore` — `SET key addr PX ttl` / `MGET` / `DEL` over `RedisExecutor`.
- `ServiceRegistrar` — daemon heartbeat producer; best-effort, never breaks serving.
- `ServiceRegistryProvider` — background poll → atomic volatile snapshot → fail-static (clones `ShardTopologyProvider`).
- `RegistryBackedUpstreams` (gateway) — overlays registered addresses onto `UpstreamEndpointGroups`, rebuilding + swapping on change.
- `MicroserviceRoute` gains a nullable `serviceName` mapping routes to backend services.

### Flag-gated (default off)
`SERVICE_REGISTRY_ENABLED=false` → no registry code runs and **the gateway opens no Redis connection** — behavior is byte-for-byte unchanged.

New env vars: `SERVICE_REGISTRY_{ENABLED,HEARTBEAT_MS,TTL_MS,REFRESH_MS,SERVICE_NAME,ADVERTISE_URL}`.

## Testing
- `mvn test` — full suite green.
- Unit: store (SET-PX/MGET/DEL), registrar (heartbeat/close/error-swallow), provider (swap/fail-static/callback), route serviceName mapping, `RegistryBackedUpstreams` (registered-over-static, static fallback).
- Booted the gateway with the flag off: no registry path, normal startup.

Spec: `docs/superpowers/specs/2026-07-10-redis-service-registry-design.md`
Plan: `docs/superpowers/plans/2026-07-10-redis-service-registry-pr1.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: PR created against `main`.

---

## Self-Review

**Spec coverage:**
- Data model (per-service key, SET-PX/MGET/DEL, address-only) → Task 2. ✓
- Producer heartbeat + fromEnvironment gating → Task 3. ✓
- Consumer poller, fail-static, onRefresh callback → Task 4. ✓
- Route↔service mapping (nullable serviceName + convenience ctor + defaults) → Task 1. ✓
- Gateway overlay + rebuild-on-change + static fallback → Task 5. ✓
- Flag-gated wiring, Redis only when enabled, shutdown cleanup → Task 6. ✓
- Armeria backends register → Task 7. ✓
- Config docs + Redis key convention → Task 8. ✓
- Fail-static / non-breaking-when-off → Tasks 3,4,6 + boot check in Task 8. ✓
- Spring model service + observability explicitly deferred to PR2 → not in this plan. ✓

**Placeholder scan:** No TBD/TODO; each code step is complete. Two steps carry explicit verify-before-use guards (Mockito presence in Task 2, `WebClient.uri()` accessor in Task 5) — real API checks, with a concrete fallback each, not missing content.

**Type consistency:** `ServiceRegistryStore` (`register/deregister/lookup`) is consumed identically in Tasks 3–4. `ServiceRegistryProvider(store, names, refreshMs, onRefresh)` and `resolve()`/`refresh()`/`start()`/`stop()` match across Tasks 4–6. `RegistryBackedUpstreams(routes, timeout, decorator, healthConfig, provider)` + `clientFor`/`rebuildIfChanged`/`close` match Tasks 5–6. `MicroserviceRoute` 6-arg canonical + 5-arg convenience are used consistently (Task 1 defines; Task 5 constructs the 6-arg overlay copy). `HealthCheckConfig(boolean,long)` matches the PR #183 record.
