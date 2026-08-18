# Latency and Memory Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make request latency and JVM memory answerable in Splunk as edge-triggered per-event records, and in Prometheus as scrapeable series with alerts, with neither derived from the other.

**Architecture:** Two independent halves. Splunk gets a `SlowRequestLogger` Armeria decorator (three services) plus a Spring interceptor (one service) that log **only** slow or failed requests, and a `GcEventTracker` emitter that logs GC pauses and heap-pressure crossings. Prometheus gets a `JvmMetricsBinder` binding Micrometer's JVM binders to the three Armeria registries (which have never had them), Armeria's `MetricCollectingService` on the two servers missing it, and three new alerts.

**Tech Stack:** Java 17, Armeria 1.28.4, Micrometer 1.13.x (`micrometer-core` + `micrometer-registry-prometheus-simpleclient`), Logback + SLF4J MDC, Spring Boot (model service only), JUnit 5 + AssertJ, `promtool`.

**Spec:** [`docs/superpowers/specs/2026-08-18-latency-memory-observability-design.md`](../specs/2026-08-18-latency-memory-observability-design.md)

## Global Constraints

- **JDK 17 is required.** Prefix every Maven command: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`. Newer JDKs fail a clean compile of two pre-existing files.
- **Every new test must be added to the `resilience` profile's `<includes>` in `pom.xml`** with a comment explaining why it must block a merge. That profile is an allow-list and is the only thing the PR gate runs; a test outside it gates nothing.
- **No `@Tag("docker")` test can gate a merge** — `<excludedGroups>load,docker</excludedGroups>` excludes them regardless of any `<include>`.
- **Never attempt `docker-compose.splunk.yml` on this machine.** It is arm64; Splunk publishes no arm64 image and `splunkd` segfaults during first-boot indexing under emulation, burning ~8 minutes per attempt. Splunk end-to-end verification rides `SplunkHecIntegrationTest` on x86_64 CI only.
- **Every assertion must be shown to fail before it is trusted.** Each task has an explicit step that breaks the implementation and confirms the test goes red. A conformance test written against already-passing code proves only that it compiles.
- **`PrometheusMeterRegistries.defaultRegistry()` is a JVM-wide singleton.** Anything registered against it must tolerate being called more than once per JVM.
- **Emission is edge-triggered, never periodic.** No timer, no scheduled sampler ships anything to Splunk. This is the property that preserves the §8 no-derivation boundary.
- Environment variable defaults, verbatim:
  - `SLOW_REQUEST_LOG_THRESHOLD_MS` — per service: online serving `300`, catalog serving `500`, gateway `1000`, model serving `500`
  - `GC_PAUSE_LOG_THRESHOLD_MS` — `200`
  - `HEAP_PRESSURE_THRESHOLD` — `0.90`
  - `HEAP_PRESSURE_RECOVERY_THRESHOLD` — `0.80`

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `src/main/java/com/recsys/metrics/JvmMetricsBinder.java` | Binds Micrometer's four JVM binders to a registry, once per registry, retaining the closeables |
| `src/main/java/com/recsys/infrastructure/observability/SlowRequestLogger.java` | Armeria decorator: emits one WARN per slow/failed request, via MDC |
| `src/main/java/com/recsys/infrastructure/observability/RequestOutcome.java` | The shared slow/failed classification and MDC field names, used by both the decorator and the Spring interceptor |
| `src/main/java/com/recsys/config/SlowRequestInterceptor.java` | Spring `HandlerInterceptor`: the model service's equivalent of the decorator |
| `src/test/java/com/recsys/metrics/JvmMetricsBinderTest.java` | Pins the bound meter names and GC survival |
| `src/test/java/com/recsys/infrastructure/observability/SlowRequestLoggerTest.java` | Emit/suppress behaviour against a real in-process Armeria server |
| `src/test/java/com/recsys/infrastructure/observability/RequestOutcomeTest.java` | Classification rules and the `RESERVED_KEYS` non-collision invariant |
| `src/test/java/com/recsys/jvm/GcEventLoggingTest.java` | Edge-triggered GC/heap emission with hysteresis |
| `src/test/java/com/recsys/config/SlowRequestInterceptorTest.java` | Spring-side emit/suppress |

**Modified:**

| File | Change |
|---|---|
| `src/main/java/com/recsys/jvm/GcEventTracker.java` | `install()` → public `start()`/`stop()`; add the log emitter |
| `src/main/java/com/recsys/api/serving/RecSysServer.java` | Bind JVM metrics, mount `MetricCollectingService` + `SlowRequestLogger`, start `GcEventTracker` |
| `src/main/java/com/recsys/api/online/OnlinePredictionServer.java` | Bind JVM metrics, mount `SlowRequestLogger`, start `GcEventTracker` |
| `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java` | Bind JVM metrics, mount `MetricCollectingService` + `SlowRequestLogger`, start `GcEventTracker` |
| `src/main/java/com/recsys/config/WebConfig.java` | Register `SlowRequestInterceptor` |
| `k8s/base/prometheus-rules.yaml` | Three new alerts |
| `k8s/base/prometheus-rules.test.yaml` | Fire + near-miss per alert, plus the unset-max case |
| `pom.xml` | `resilience` profile includes |
| `docs/system_design/18_Fault_Tolerance.md`, `docs/runbooks/splunk-hec-logging.md`, `.claude/CLAUDE.md` | Documentation |

**Dependency order:** Task 3 must precede Task 4 (the emitter needs the lifecycle refactor). Task 2 must precede Task 7's latency alert (the alert needs the histograms to exist). Everything else is independent.

---

### Task 1: JVM metric binders for the three Armeria services

The core memory fix. Armeria's `PrometheusMeterRegistries.configureRegistry` is a verified no-op, so heap, GC and thread metrics have never been scrapeable on 6010, 7010 or 8010.

**Files:**
- Create: `src/main/java/com/recsys/metrics/JvmMetricsBinder.java`
- Create: `src/test/java/com/recsys/metrics/JvmMetricsBinderTest.java`
- Modify: `src/main/java/com/recsys/api/serving/RecSysServer.java:115` (after `SplunkHecMetrics.register(registry)`)
- Modify: `src/main/java/com/recsys/api/online/OnlinePredictionServer.java:152` (after `SplunkHecMetrics.register(registry)`)
- Modify: `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java:85` (after the registry is created)
- Modify: `pom.xml` (resilience includes)

**Interfaces:**
- Consumes: nothing.
- Produces: `com.recsys.metrics.JvmMetricsBinder.bindTo(io.micrometer.core.instrument.MeterRegistry registry)` — `public static void`, idempotent per registry, null-tolerant.

- [ ] **Step 1: Probe the actual meter names before pinning any**

Do not guess which meters the binders register — Micrometer registers some lazily (notably `jvm.gc.pause`, which only appears after a collection occurs). Write a throwaway probe first.

Create `src/test/java/com/recsys/metrics/MeterNameProbe.java`:

```java
package com.recsys.metrics;

import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MeterNameProbe {

    @Test
    void printRegisteredMeterNames() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new JvmMemoryMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
        JvmGcMetrics gc = new JvmGcMetrics();
        gc.bindTo(registry);

        System.gc();
        Thread.sleep(500);   // JMX GC notifications are delivered asynchronously

        registry.getMeters().stream()
                .map(m -> m.getId().getName())
                .distinct()
                .sorted()
                .forEach(System.out::println);
        gc.close();
    }
}
```

- [ ] **Step 2: Run the probe and record what it prints**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=MeterNameProbe -DfailIfNoTests=false
```

Read the printed list from the Surefire output. Note in particular whether `jvm.gc.pause` appears — if it does not appear even after the forced GC, the test in Step 3 must not assert it, and Task 7's `JvmGcTimeFractionHigh` alert needs that noted (the series exists in a real long-running service but not in a short test JVM).

- [ ] **Step 3: Write the failing test, pinning only names the probe actually printed**

Create `src/test/java/com/recsys/metrics/JvmMetricsBinderTest.java`. Replace the names in `EXPECTED` with what Step 2 printed if they differ — the list below is the expected result but the probe is the authority.

```java
package com.recsys.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class JvmMetricsBinderTest {

    private static final List<String> EXPECTED = List.of(
            "jvm.memory.used",
            "jvm.memory.committed",
            "jvm.memory.max",
            "jvm.threads.live",
            "jvm.threads.daemon",
            "system.cpu.count");

    private static Set<String> meterNames(SimpleMeterRegistry registry) {
        return registry.getMeters().stream()
                .map(m -> m.getId().getName())
                .collect(Collectors.toSet());
    }

    @Test
    void bindsTheJvmMeterSet() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        JvmMetricsBinder.bindTo(registry);
        assertThat(meterNames(registry)).containsAll(EXPECTED);
    }

    @Test
    void bindingTwiceDoesNotDuplicateOrThrow() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        JvmMetricsBinder.bindTo(registry);
        int afterFirst = registry.getMeters().size();
        JvmMetricsBinder.bindTo(registry);
        assertThat(registry.getMeters()).hasSize(afterFirst);
    }

    @Test
    void nullRegistryIsIgnored() {
        JvmMetricsBinder.bindTo(null);   // must not throw
    }

    /**
     * The trap documented in 18_Fault_Tolerance §8.3: Micrometer holds a gauge's backing state
     * by WeakReference, and a FunctionCounter whose state is collected freezes silently rather
     * than reporting NaN. JvmGcMetrics also holds a JMX listener that must not be collected.
     */
    @Test
    void metersStillReportAfterGarbageCollection() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        JvmMetricsBinder.bindTo(registry);

        WeakReference<Object> canary = new WeakReference<>(new Object());
        for (int i = 0; i < 20 && canary.get() != null; i++) {
            System.gc();
            byte[] pressure = new byte[8 * 1024 * 1024];
            assertThat(pressure).isNotNull();
        }
        assertThat(canary.get()).as("a real GC must have run for this test to mean anything")
                .isNull();

        Double used = registry.find("jvm.memory.used").gauges().stream()
                .map(g -> g.value()).filter(v -> !v.isNaN()).findFirst().orElse(null);
        assertThat(used).as("jvm.memory.used must still report a real value after GC")
                .isNotNull().isGreaterThan(0.0);
    }
}
```

- [ ] **Step 4: Run it and confirm it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=JvmMetricsBinderTest
```

Expected: compilation failure — `JvmMetricsBinder` does not exist.

- [ ] **Step 5: Implement `JvmMetricsBinder`**

Create `src/main/java/com/recsys/metrics/JvmMetricsBinder.java`:

```java
package com.recsys.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Binds the JVM's memory, GC, thread and processor metrics to a registry.
 *
 * <p>Armeria does not do this for us. {@code PrometheusMeterRegistries.configureRegistry} is a
 * no-op — it null-checks its argument and returns it — so a registry obtained from
 * {@code PrometheusMeterRegistries.defaultRegistry()} carries no JVM metrics at all. Heap usage
 * and GC pause time were unscrapeable on 6010, 7010 and 8010 until this class existed. The Spring
 * model service gets the same set from Actuator's auto-configuration and must not call this.
 */
public final class JvmMetricsBinder {

    private static final Logger log = LoggerFactory.getLogger(JvmMetricsBinder.class);

    /**
     * {@code PrometheusMeterRegistries.defaultRegistry()} is a JVM-wide singleton, and more than
     * one component may reasonably ask for the JVM metrics on it. Re-binding is not harmless:
     * {@link JvmGcMetrics} installs a JMX notification listener per bind, so a second call would
     * double-count every pause. Identity comparison, because MeterRegistry does not define
     * equality.
     */
    private static final Set<MeterRegistry> BOUND =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Holds every {@link JvmGcMetrics} for the life of the JVM. Two reasons, both load-bearing:
     * it is {@code Closeable} and owns a JMX listener that must outlive this method, and
     * Micrometer's gauge state is held by WeakReference — the exact bug
     * {@code SplunkHecMetrics.RETAINED} exists to prevent. Do not "clean up" this field.
     */
    private static final List<JvmGcMetrics> RETAINED = new ArrayList<>();

    private JvmMetricsBinder() {}

    public static synchronized void bindTo(MeterRegistry registry) {
        if (registry == null || !BOUND.add(registry)) {
            return;
        }
        new JvmMemoryMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);

        JvmGcMetrics gcMetrics = new JvmGcMetrics();
        gcMetrics.bindTo(registry);
        RETAINED.add(gcMetrics);

        log.info("Bound JVM memory, GC, thread and processor metrics to the meter registry");
    }
}
```

- [ ] **Step 6: Run the test and confirm it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=JvmMetricsBinderTest
```

Expected: PASS, all four tests.

- [ ] **Step 7: Prove the tests can fail**

Temporarily comment out `new JvmMemoryMetrics().bindTo(registry);` and re-run. `bindsTheJvmMeterSet` and `metersStillReportAfterGarbageCollection` must both go red and name `jvm.memory.used`. Then temporarily replace the `!BOUND.add(registry)` guard with `false` and re-run: `bindingTwiceDoesNotDuplicateOrThrow` must go red. Restore both.

- [ ] **Step 8: Delete the probe**

```bash
rm src/test/java/com/recsys/metrics/MeterNameProbe.java
```

It was a measuring instrument, not a test. Its finding lives in `EXPECTED`.

- [ ] **Step 9: Wire into the three Armeria mains**

In `RecSysServer.java`, immediately after the existing `SplunkHecMetrics.register(registry);` (around line 117):

```java
            // Armeria's configureRegistry is a no-op, so nothing binds the JVM metrics for us.
            JvmMetricsBinder.bindTo(registry);
```

Add `import com.recsys.metrics.JvmMetricsBinder;`.

In `OnlinePredictionServer.java`, immediately after its `SplunkHecMetrics.register(registry);` (around line 154), the identical two lines and import.

In `MicroserviceGatewayServer.java`, immediately after `PrometheusMeterRegistry meterRegistry = PrometheusMeterRegistries.defaultRegistry();` (around line 85):

```java
        // Armeria's configureRegistry is a no-op, so nothing binds the JVM metrics for us.
        JvmMetricsBinder.bindTo(meterRegistry);
```

Add the same import.

- [ ] **Step 10: Verify the exposition really contains the metrics**

Static wiring is what §8.2 calls "instrumentation that looked present and was observable by nothing". Confirm on the wire:

```bash
REDIS_ALLOW_NO_AUTH=true GATEWAY_ALLOW_ANONYMOUS=true \
  JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  mvn exec:java -Dexec.mainClass=com.recsys.api.gateway.MicroserviceGatewayServer &
sleep 20
curl -s localhost:8010/metrics | grep -c '^jvm_memory_used_bytes'
curl -s localhost:8010/metrics | grep '^jvm_threads_live_threads'
kill %1
```

Expected: a non-zero count for `jvm_memory_used_bytes` and a `jvm_threads_live_threads` line. If the count is 0, the binder is not reaching this registry — stop and diagnose before continuing.

- [ ] **Step 11: Add the test to the resilience profile**

In `pom.xml`, inside the `resilience` profile's `<includes>`, next to the existing `**/metrics/SplunkHecMetricsTest.java`:

```xml
                <!-- Armeria's configureRegistry is a no-op, so nothing binds the JVM metrics
                     unless this class does; heap and GC were unscrapeable on three of four
                     services until it existed. Also pins the Micrometer WeakReference trap:
                     JvmGcMetrics owns a JMX listener and a collected gauge state freezes
                     silently rather than erroring. Pure unit-level, no Redis, no timing. -->
                <include>**/metrics/JvmMetricsBinderTest.java</include>
```

- [ ] **Step 12: Run the gate and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
git add src/main/java/com/recsys/metrics/JvmMetricsBinder.java \
        src/test/java/com/recsys/metrics/JvmMetricsBinderTest.java \
        src/main/java/com/recsys/api/serving/RecSysServer.java \
        src/main/java/com/recsys/api/online/OnlinePredictionServer.java \
        src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java \
        pom.xml
git commit -m "feat(metrics): bind JVM memory, GC and thread metrics on the Armeria services

Armeria's PrometheusMeterRegistries.configureRegistry is a no-op, so heap
usage and GC pause time have never been scrapeable on 6010, 7010 or 8010.
Only the Spring model service had them, via Actuator.

Binding is idempotent per registry because defaultRegistry() is a JVM-wide
singleton and JvmGcMetrics installs a JMX listener per bind."
```

---

### Task 2: Request-duration histograms for catalog serving and the gateway

Online serving already mounts `MetricCollectingService`; the other two do not, so they have no latency series at all.

**Files:**
- Modify: `src/main/java/com/recsys/api/serving/RecSysServer.java:277-283` (the builder configuration method)
- Modify: `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java:140` (immediately after `ServerBuilder sb = Server.builder().http(port);`)

**Interfaces:**
- Consumes: nothing.
- Produces: Prometheus series `catalog_serving_request_duration_seconds_bucket` and `api_gateway_request_duration_seconds_bucket`, tagged `hostname_pattern`, `http_status`, `method`, `service`. Task 7's `RequestLatencyP99High` depends on these exact names.

**Cardinality is already settled — do not re-litigate it.** The gateway's data path is a catch-all `sb.service("prefix:/", ...)`, which raises the obvious fear of a per-URL label. Decompiling `DefaultMeterIdPrefixFunction` from armeria-1.28.4 shows the tag set is exactly `hostname.pattern`, `http.status`, `method`, `service` — **there is no path or route tag**. The label set is bounded. The flip side, worth knowing before someone asks for it: these histograms *cannot* be broken down by route. Per-route latency is Splunk's job in Task 5.

- [ ] **Step 1: Add the decorator to catalog serving**

In `RecSysServer.java`, the builder-configuring method around line 277 reads:

```java
        builder.meterRegistry(registry)
```

Change it to mount the metric decorator on the way through:

```java
        builder.meterRegistry(registry)
                // Matches OnlinePredictionServer's existing pattern. Yields
                // catalog_serving_request_duration_seconds_*, tagged by method/status/service —
                // no path tag, so cardinality is bounded.
                .decorator(MetricCollectingService.newDecorator(
                        MeterIdPrefixFunction.ofDefault("catalog_serving")))
```

Add imports:

```java
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.server.metric.MetricCollectingService;
```

- [ ] **Step 2: Add the decorator to the gateway**

In `MicroserviceGatewayServer.java`, immediately after `ServerBuilder sb = Server.builder().http(port);` (line 140):

```java
        // Request-duration histogram. Tagged by method/status/service only — the catch-all
        // "prefix:/" data path does NOT produce a per-URL label (verified against
        // DefaultMeterIdPrefixFunction in armeria-1.28.4).
        sb.decorator(MetricCollectingService.newDecorator(
                MeterIdPrefixFunction.ofDefault("api_gateway")));
```

Add the same two imports.

- [ ] **Step 3: Verify both on the wire, including the cardinality claim**

```bash
REDIS_ALLOW_NO_AUTH=true GATEWAY_ALLOW_ANONYMOUS=true \
  JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  mvn exec:java -Dexec.mainClass=com.recsys.api.gateway.MicroserviceGatewayServer &
sleep 20
for p in /aaa /bbb /ccc /ddd /eee; do curl -s -o /dev/null "localhost:8010$p"; done
curl -s localhost:8010/metrics | grep '^api_gateway_request_duration_seconds_count'
kill %1
```

Expected: a small, fixed number of `api_gateway_request_duration_seconds_count` lines whose labels do **not** contain `/aaa`, `/bbb` etc. If any label carries a request path, **stop** — the cardinality analysis is wrong for this Armeria version and the gateway decorator must be reverted pending a custom `MeterIdPrefixFunction`.

Repeat for catalog serving:

```bash
REDIS_ALLOW_NO_AUTH=true JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  mvn exec:java -Dexec.mainClass=com.recsys.api.serving.RecSysServer &
sleep 20
curl -s "localhost:6010/getrecommendation?user=123&n=5" -o /dev/null
curl -s localhost:6010/metrics | grep '^catalog_serving_request_duration_seconds_count'
kill %1
```

Expected: at least one line. Sample userIds in this repo start at 123.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/recsys/api/serving/RecSysServer.java \
        src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java
git commit -m "feat(metrics): request-duration histograms for catalog serving and the gateway

Online serving has mounted MetricCollectingService since it was written;
the other two Armeria servers never did, so neither had any latency series.

Tag set is method/status/service with no path tag (verified against
DefaultMeterIdPrefixFunction in armeria-1.28.4), so the gateway's catch-all
prefix:/ route cannot produce per-URL cardinality."
```

---

### Task 3: Make `GcEventTracker` usable outside Spring

It registers its JMX listeners from `@PostConstruct` and is a `@Service`, so only the model service can construct it. Task 4's emitter is useless until all four services run one.

**Files:**
- Modify: `src/main/java/com/recsys/jvm/GcEventTracker.java:78-99`
- Modify: `src/main/java/com/recsys/api/serving/RecSysServer.java`, `.../online/OnlinePredictionServer.java`, `.../gateway/MicroserviceGatewayServer.java`
- Modify: `src/test/java/com/recsys/jvm/GcEventTrackerTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `GcEventTracker.start()` — `public void`, idempotent, registers the JMX listeners. `GcEventTracker.stop()` — `public void`, idempotent, removes them. `destroy()` continues to exist and delegates to `stop()`. Task 4 builds its emitter on top of the same class.

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/com/recsys/jvm/GcEventTrackerTest.java`:

```java
    @Test
    void startIsIdempotentAndStopRemovesListeners() {
        GcEventTracker t = new GcEventTracker();
        t.start();
        t.start();   // second call must not double-register
        long before = t.snapshot().stwEventCount();

        System.gc();
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        long afterGc = t.snapshot().stwEventCount();
        t.stop();
        t.stop();    // must not throw

        System.gc();
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        assertThat(t.snapshot().stwEventCount())
                .as("no further events may be recorded after stop()")
                .isEqualTo(afterGc);
        assertThat(afterGc).isGreaterThanOrEqualTo(before);
    }
```

If `Snapshot`'s accessor for the STW count is named something other than `stwEventCount()`, read the record definition at the bottom of `GcEventTracker.java` and use the real name — do not add an accessor for the test's convenience.

- [ ] **Step 2: Run it and confirm it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=GcEventTrackerTest
```

Expected: compilation failure — `start()` and `stop()` are not accessible (`install()` is package-private, and there is no `stop()`).

- [ ] **Step 3: Refactor the lifecycle**

In `GcEventTracker.java`, replace the `@PostConstruct void install()` and `destroy()` block (lines 78–99) with:

```java
    /**
     * Registers the JMX notification listeners. Spring calls this via {@link PostConstruct}; the
     * three Armeria mains, which have no container, call it directly during boot. Idempotent —
     * a second call would double-count every pause.
     */
    @PostConstruct
    public synchronized void start() {
        if (!registrations.isEmpty()) {
            return;
        }
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (!(gc instanceof NotificationEmitter emitter)) continue;
            NotificationListener listener = this::onNotification;
            emitter.addNotificationListener(listener, null, null);
            registrations.add(new ListenerRegistration(emitter, listener));
            log.debug("GcEventTracker: registered on collector '{}'", gc.getName());
        }
    }

    /** Removes the listeners. Idempotent. */
    public synchronized void stop() {
        for (ListenerRegistration r : registrations) {
            try {
                r.emitter().removeNotificationListener(r.listener());
            } catch (Exception e) {
                log.debug("GcEventTracker: failed to remove listener: {}", e.getMessage());
            }
        }
        registrations.clear();
    }

    @Override
    public void destroy() {
        stop();
    }
```

The `registrations` field must become thread-safe now that `start()`/`stop()` are callable from a boot thread while notifications arrive on a JMX thread. Change its declaration (line ~68) from `new ArrayList<>()` to:

```java
    private final List<ListenerRegistration> registrations = new java.util.concurrent.CopyOnWriteArrayList<>();
```

- [ ] **Step 4: Run the test and confirm it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=GcEventTrackerTest
```

Expected: PASS.

- [ ] **Step 5: Prove the test can fail**

Temporarily delete the `if (!registrations.isEmpty()) return;` guard and re-run — the idempotency half must go red (double-registration produces roughly twice the events). Restore it.

- [ ] **Step 6: Start a tracker in each Armeria main**

In each of `RecSysServer`, `OnlinePredictionServer` and `MicroserviceGatewayServer`, alongside the `JvmMetricsBinder.bindTo(...)` call added in Task 1:

```java
            // Spring constructs its own; the Armeria mains have no container to do it for them.
            GcEventTracker gcEventTracker = new GcEventTracker();
            gcEventTracker.start();
            Runtime.getRuntime().addShutdownHook(new Thread(gcEventTracker::stop));
```

Add `import com.recsys.jvm.GcEventTracker;`. Assign it to a local that stays in scope — Task 4 gives it a logging role, and a collected tracker stops emitting silently.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/jvm/GcEventTracker.java \
        src/test/java/com/recsys/jvm/GcEventTrackerTest.java \
        src/main/java/com/recsys/api/serving/RecSysServer.java \
        src/main/java/com/recsys/api/online/OnlinePredictionServer.java \
        src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java
git commit -m "refactor(jvm): give GcEventTracker a container-free lifecycle

install() was package-private and @PostConstruct-only, so only the Spring
model service could ever construct one. start()/stop() are public and
idempotent, and the three Armeria mains now run a tracker each.

registrations becomes CopyOnWriteArrayList: start/stop run on a boot thread
while notifications arrive on a JMX thread."
```

---

### Task 4: GC pause and heap-pressure events into Splunk

**Files:**
- Modify: `src/main/java/com/recsys/jvm/GcEventTracker.java` (the `onNotification` handler)
- Create: `src/test/java/com/recsys/jvm/GcEventLoggingTest.java`
- Modify: `pom.xml`

**Interfaces:**
- Consumes: `GcEventTracker.start()`/`stop()` from Task 3.
- Produces: `GcEventTracker.evaluateForLogging(String gcName, String gcAction, String gcCause, long pauseMs, boolean stw, long heapUsedBytes, long heapMaxBytes)` — `void`, package-private, called from `onNotification` and directly by the test. Emitting through a seam like this is what makes the emitter testable without provoking real garbage collections of a controlled size, which is not possible.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/jvm/GcEventLoggingTest.java`:

```java
package com.recsys.jvm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GcEventLoggingTest {

    private static final long GB = 1024L * 1024L * 1024L;

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;
    private GcEventTracker tracker;

    @BeforeEach
    void setUp() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger(GcEventTracker.class);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        tracker = new GcEventTracker();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private List<ILoggingEvent> events() {
        return appender.list;
    }

    private void gc(long pauseMs, long heapUsedBytes) {
        tracker.evaluateForLogging("G1 Young Generation", "end of minor GC", "G1 Evacuation Pause",
                pauseMs, true, heapUsedBytes, 10 * GB);
    }

    @Test
    void logsAPauseOverTheThreshold() {
        gc(500, 1 * GB);
        assertThat(events()).hasSize(1);
        ILoggingEvent e = events().get(0);
        assertThat(e.getLevel()).isEqualTo(Level.WARN);
        assertThat(e.getMDCPropertyMap())
                .containsEntry("pauseMs", "500")
                .containsEntry("gcCause", "G1 Evacuation Pause")
                .containsKey("heapUsedFraction");
    }

    @Test
    void staysSilentBelowTheThreshold() {
        gc(10, 1 * GB);
        assertThat(events()).isEmpty();
    }

    @Test
    void concurrentCyclesNeverProduceAPauseEvent() {
        // A ZGC cycle's reported wall time includes concurrent phases; its true STW pause is
        // sub-millisecond. Thresholding on wall time would fire constantly on a healthy service.
        tracker.evaluateForLogging("ZGC Cycles", "end of GC cycle", "Warmup",
                5000, false, 1 * GB, 10 * GB);
        assertThat(events()).isEmpty();
    }

    @Test
    void heapPressureIsEdgeTriggeredWithHysteresis() {
        gc(1, 5 * GB);            // 0.50 — below threshold, silent
        assertThat(events()).isEmpty();

        gc(1, (long) (9.5 * GB)); // 0.95 — crosses 0.90 upward: one WARN
        assertThat(events()).hasSize(1);
        assertThat(events().get(0).getLevel()).isEqualTo(Level.WARN);

        gc(1, (long) (9.6 * GB)); // still above: no further event
        gc(1, (long) (9.7 * GB));
        assertThat(events()).hasSize(1);

        gc(1, (long) (8.5 * GB)); // 0.85 — between recovery and threshold: still no event
        assertThat(events()).hasSize(1);

        gc(1, (long) (7.0 * GB)); // 0.70 — below recovery: one INFO
        assertThat(events()).hasSize(2);
        assertThat(events().get(1).getLevel()).isEqualTo(Level.INFO);

        gc(1, (long) (9.5 * GB)); // crosses again: one more WARN
        assertThat(events()).hasSize(3);
    }

    @Test
    void anUnsetHeapMaxProducesNoPressureEvent() {
        // MemoryUsage.getMax() is -1 for pools with no maximum; a fraction is meaningless there.
        tracker.evaluateForLogging("G1 Young Generation", "end of minor GC", "Allocation Failure",
                1, true, 1 * GB, -1);
        assertThat(events()).isEmpty();
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=GcEventLoggingTest
```

Expected: compilation failure — `evaluateForLogging` does not exist.

- [ ] **Step 3: Implement the emitter**

Add to `GcEventTracker.java`, near the other fields:

```java
    private static final long PAUSE_LOG_THRESHOLD_MS =
            EnvConfig.readLong("GC_PAUSE_LOG_THRESHOLD_MS", 200);
    private static final double HEAP_PRESSURE_THRESHOLD =
            EnvConfig.readDouble("HEAP_PRESSURE_THRESHOLD", 0.90);
    private static final double HEAP_PRESSURE_RECOVERY_THRESHOLD =
            EnvConfig.readDouble("HEAP_PRESSURE_RECOVERY_THRESHOLD", 0.80);

    /** True while heap has crossed the pressure threshold and has not yet recovered. */
    private final java.util.concurrent.atomic.AtomicBoolean underHeapPressure =
            new java.util.concurrent.atomic.AtomicBoolean();
```

Add `import com.recsys.config.EnvConfig;`.

Then the emitter itself:

```java
    /**
     * Decides whether this collection deserves a log event, and emits it.
     *
     * <p>Both events are edge-triggered: a pause is a discrete thing that happened, and heap
     * pressure logs only on crossing a boundary, never on every collection. That is deliberate
     * and load-bearing — a periodic heap sample shipped to Splunk would be a metric wearing a
     * log's clothes and would break the no-derivation boundary in 18_Fault_Tolerance §8.
     *
     * <p>Package-private rather than private so the emission rules can be tested directly.
     * Provoking real collections of a controlled pause and heap size is not possible.
     */
    void evaluateForLogging(String gcName, String gcAction, String gcCause, long pauseMs,
                            boolean stw, long heapUsedBytes, long heapMaxBytes) {

        // Concurrent collectors report wall time including concurrent phases; a ZGC cycle can
        // read as seconds while its true STW pause is well under a millisecond.
        if (stw && pauseMs > PAUSE_LOG_THRESHOLD_MS) {
            emit(() -> log.warn(
                    "GC pause of {}ms exceeded the {}ms threshold ({} / {})",
                    pauseMs, PAUSE_LOG_THRESHOLD_MS, gcName, gcCause),
                    gcName, gcAction, gcCause, pauseMs, heapUsedBytes, heapMaxBytes);
        }

        // -1 means the pool has no maximum, so a used/max fraction is meaningless. Guarding here
        // matters for the same reason it does in the Prometheus alert: a bogus ratio silently
        // defeats a threshold comparison.
        if (heapMaxBytes <= 0) {
            return;
        }
        double fraction = (double) heapUsedBytes / heapMaxBytes;

        if (fraction >= HEAP_PRESSURE_THRESHOLD && underHeapPressure.compareAndSet(false, true)) {
            emit(() -> log.warn(
                    "Heap crossed the pressure threshold: {}% used after {} ({})",
                    Math.round(fraction * 100), gcName, gcCause),
                    gcName, gcAction, gcCause, pauseMs, heapUsedBytes, heapMaxBytes);
        } else if (fraction < HEAP_PRESSURE_RECOVERY_THRESHOLD
                && underHeapPressure.compareAndSet(true, false)) {
            emit(() -> log.info(
                    "Heap recovered below the pressure threshold: {}% used after {}",
                    Math.round(fraction * 100), gcName),
                    gcName, gcAction, gcCause, pauseMs, heapUsedBytes, heapMaxBytes);
        }
    }

    private void emit(Runnable logCall, String gcName, String gcAction, String gcCause,
                      long pauseMs, long heapUsedBytes, long heapMaxBytes) {
        MDC.put("gcName", gcName);
        MDC.put("gcAction", gcAction);
        MDC.put("gcCause", gcCause);
        MDC.put("pauseMs", Long.toString(pauseMs));
        MDC.put("heapUsedBytes", Long.toString(heapUsedBytes));
        MDC.put("heapMaxBytes", Long.toString(heapMaxBytes));
        if (heapMaxBytes > 0) {
            MDC.put("heapUsedFraction",
                    String.format(java.util.Locale.ROOT, "%.4f", (double) heapUsedBytes / heapMaxBytes));
        }
        try {
            logCall.run();
        } finally {
            // JMX delivers notifications on a shared thread. A leaked MDC entry would attach
            // itself to every later log line that thread emits.
            MDC.remove("gcName");
            MDC.remove("gcAction");
            MDC.remove("gcCause");
            MDC.remove("pauseMs");
            MDC.remove("heapUsedBytes");
            MDC.remove("heapMaxBytes");
            MDC.remove("heapUsedFraction");
        }
    }
```

Add `import org.slf4j.MDC;`.

- [ ] **Step 4: Call it from the notification handler**

In `onNotification`, after the existing `if (type.stw) { recordStwPause(pauseMs); }` block, add:

```java
        MemoryUsage heapAfter = gcInfo.getMemoryUsageAfterGc().values().stream()
                .reduce(null, (a, b) -> a == null ? b : a);
        long heapUsed = 0;
        long heapMax = 0;
        for (MemoryUsage u : gcInfo.getMemoryUsageAfterGc().values()) {
            heapUsed += u.getUsed();
            if (u.getMax() > 0) {
                heapMax += u.getMax();
            }
        }
        assert heapAfter == null || heapUsed >= 0;
        evaluateForLogging(info.getGcName(), info.getGcAction(), info.getGcCause(),
                pauseMs, type.stw, heapUsed, heapMax > 0 ? heapMax : -1);
```

Simplify to just the loop if the `heapAfter` local is unused after review — it is there only to make the null case explicit. `getMemoryUsageAfterGc()` includes non-heap pools on some JVMs; if the probe in Step 6 shows implausible fractions, filter to pools whose name appears in `ManagementFactory.getMemoryPoolMXBeans()` with `MemoryType.HEAP`.

- [ ] **Step 5: Run the test and confirm it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=GcEventLoggingTest
```

Expected: PASS, all five tests.

- [ ] **Step 6: Prove the tests can fail**

Replace `compareAndSet(false, true)` with a bare `fraction >= HEAP_PRESSURE_THRESHOLD` condition and re-run: `heapPressureIsEdgeTriggeredWithHysteresis` must go red on the "still above: no further event" assertion, because the emitter would now log on every collection. Remove the `heapMaxBytes <= 0` guard and re-run: `anUnsetHeapMaxProducesNoPressureEvent` must go red. Restore both.

- [ ] **Step 7: Sanity-check against a real JVM**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=GcEventTrackerTest \
  -DargLine="-Xmx64m -Xshare:off" 2>&1 | grep -i "GC pause\|Heap crossed"
```

A 64 MB heap under the test's allocation should produce at least one real event with a plausible `heapUsedFraction` between 0 and 1. If fractions exceed 1, the pool summing in Step 4 is including non-heap pools — apply the filter noted there.

- [ ] **Step 8: Add the test to the resilience profile**

In `pom.xml`, in the `resilience` profile's `<includes>`:

```xml
                <!-- The GC and heap-pressure events are the only memory signal that reaches
                     Splunk, and their whole correctness is that they are edge-triggered: a
                     regression to per-collection emission would flood a bounded, drop-on-full
                     HEC queue and take the ERROR events with it. Also pins the unset-heap-max
                     guard, the same -1 sentinel that defeats the Prometheus alert. No timing,
                     no JVM dependence — the emission seam is called directly. -->
                <include>**/jvm/GcEventLoggingTest.java</include>
```

- [ ] **Step 9: Run the gate and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
git add src/main/java/com/recsys/jvm/GcEventTracker.java \
        src/test/java/com/recsys/jvm/GcEventLoggingTest.java pom.xml
git commit -m "feat(jvm): log GC pauses and heap-pressure crossings

Both events are edge-triggered: a pause over the threshold is a discrete
thing that happened, and heap pressure logs on crossing 0.90 and again on
recovering below 0.80, never per collection. Periodic sampling into Splunk
was considered and rejected -- it is the one shape that would break the
no-derivation boundary in 18_Fault_Tolerance section 8.

Concurrent collectors are excluded from the pause event: a ZGC cycle's
reported wall time includes concurrent phases and would fire constantly.
An unset heap max (-1) produces no pressure event."
```

---

### Task 5: Slow-request events from the three Armeria services

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/observability/RequestOutcome.java`
- Create: `src/main/java/com/recsys/infrastructure/observability/SlowRequestLogger.java`
- Create: `src/test/java/com/recsys/infrastructure/observability/RequestOutcomeTest.java`
- Create: `src/test/java/com/recsys/infrastructure/observability/SlowRequestLoggerTest.java`
- Modify: the three Armeria mains
- Modify: `pom.xml`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `RequestOutcome.classify(int statusCode, long durationMs, long thresholdMs)` → `String` or `null`. Returns `"slow"`, `"failed"`, or `null` when the request does not warrant an event. Task 6's Spring interceptor calls the same method — the classification must live in exactly one place or the four services will disagree about what "slow" means.
  - `RequestOutcome.MDC_KEYS` → `Set<String>`, the exact MDC field names both emitters use.
  - `SlowRequestLogger.newDecorator(String serviceName, long thresholdMs)` → `Function<? super HttpService, ? extends HttpService>`.

- [ ] **Step 1: Write the failing classification test**

Create `src/test/java/com/recsys/infrastructure/observability/RequestOutcomeTest.java`:

```java
package com.recsys.infrastructure.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestOutcomeTest {

    @Test
    void aFastSuccessfulRequestWarrantsNoEvent() {
        assertThat(RequestOutcome.classify(200, 10, 500)).isNull();
    }

    @Test
    void aSlowSuccessfulRequestIsSlow() {
        assertThat(RequestOutcome.classify(200, 501, 500)).isEqualTo("slow");
    }

    @Test
    void theThresholdIsExclusive() {
        assertThat(RequestOutcome.classify(200, 500, 500)).isNull();
    }

    @Test
    void anyServerErrorIsFailedRegardlessOfSpeed() {
        assertThat(RequestOutcome.classify(500, 1, 500)).isEqualTo("failed");
        assertThat(RequestOutcome.classify(503, 1, 500)).isEqualTo("failed");
    }

    /**
     * 4xx is the one class an external caller can generate at will. Making it a log trigger
     * hands anyone with a URL the ability to fill a bounded, drop-on-full HEC queue -- and the
     * drops are indiscriminate, so it would take the ERROR events with it.
     */
    @Test
    void clientErrorsAreNotLoggedUnlessAlsoSlow() {
        assertThat(RequestOutcome.classify(400, 1, 500)).isNull();
        assertThat(RequestOutcome.classify(404, 1, 500)).isNull();
        assertThat(RequestOutcome.classify(429, 1, 500)).isNull();
        assertThat(RequestOutcome.classify(400, 900, 500)).isEqualTo("slow");
    }

    /**
     * SplunkHecEventSerializer silently drops MDC entries whose names collide with the five
     * event payload fields or the five envelope fields. A field named "time" would vanish with
     * no error anywhere. Asserted against the serializer's own set so adding a colliding field
     * later fails the build rather than the search.
     */
    @Test
    void noMdcFieldCollidesWithTheSerializersReservedKeys() {
        assertThat(RequestOutcome.MDC_KEYS)
                .isNotEmpty()
                .doesNotContainAnyElementsOf(SplunkHecEventSerializer.reservedKeys());
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=RequestOutcomeTest
```

Expected: compilation failure — neither `RequestOutcome` nor `SplunkHecEventSerializer.reservedKeys()` exists.

- [ ] **Step 3: Expose the serializer's reserved keys**

`RESERVED_KEYS` in `SplunkHecEventSerializer.java` is `private static final`. Add a package-private accessor immediately below it so the invariant is asserted against the real set rather than a copy that can drift:

```java
    /** Package-private for the collision test in RequestOutcomeTest. */
    static java.util.Set<String> reservedKeys() {
        return RESERVED_KEYS;
    }
```

- [ ] **Step 4: Implement `RequestOutcome`**

Create `src/main/java/com/recsys/infrastructure/observability/RequestOutcome.java`:

```java
package com.recsys.infrastructure.observability;

import java.util.Set;

/**
 * The single definition of which requests earn a Splunk event, shared by the Armeria decorator
 * and the Spring interceptor so the four services cannot disagree about what "slow" means.
 */
public final class RequestOutcome {

    /** MDC field names both emitters write. Must not intersect the HEC serializer's reserved set. */
    public static final Set<String> MDC_KEYS =
            Set.of("service", "route", "httpMethod", "statusCode", "outcome", "durationMs");

    private RequestOutcome() {}

    /**
     * @return {@code "slow"}, {@code "failed"}, or {@code null} when the request warrants no event.
     */
    public static String classify(int statusCode, long durationMs, long thresholdMs) {
        if (statusCode >= 500) {
            return "failed";
        }
        if (durationMs > thresholdMs) {
            return "slow";
        }
        return null;
    }
}
```

Note `httpMethod` and `statusCode` rather than `method` and `status`: `MeterIdPrefixFunction` already uses `method`/`http.status` as *metric tag* names, and keeping the log field names distinct avoids anyone reading a Splunk field and a Prometheus label as the same thing. Neither name is in the serializer's reserved set.

- [ ] **Step 5: Run the classification test and confirm it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=RequestOutcomeTest
```

Expected: PASS.

- [ ] **Step 6: Prove the collision test can fail**

Temporarily add `"time"` to `MDC_KEYS` and re-run — `noMdcFieldCollidesWithTheSerializersReservedKeys` must go red and name it. Restore.

- [ ] **Step 7: Write the failing decorator test**

Create `src/test/java/com/recsys/infrastructure/observability/SlowRequestLoggerTest.java`:

```java
package com.recsys.infrastructure.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SlowRequestLoggerTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.decorator(SlowRequestLogger.newDecorator("test-service", 100));
            sb.service("/fast", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
            sb.service("/slow", (ctx, req) -> {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return HttpResponse.of(HttpStatus.OK);
            });
            sb.service("/boom", (ctx, req) -> HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR));
            sb.service("/badrequest", (ctx, req) -> HttpResponse.of(HttpStatus.BAD_REQUEST));
        }
    };

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger(SlowRequestLogger.class);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private void get(String path) {
        WebClient.of(server.httpUri()).blocking().get(path);
    }

    @Test
    void aFastSuccessfulRequestLogsNothing() {
        get("/fast");
        // The event would be emitted from the request-log completion callback, so a bare
        // assertion could pass simply by racing it. Give it a real window to appear.
        await().pollDelay(300, TimeUnit.MILLISECONDS)
               .atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(appender.list).isEmpty());
    }

    @Test
    void aSlowRequestLogsOneWarnWithItsFields() {
        get("/slow");
        await().atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(appender.list).hasSize(1));

        ILoggingEvent e = appender.list.get(0);
        assertThat(e.getLevel()).isEqualTo(Level.WARN);
        assertThat(e.getMDCPropertyMap())
                .containsEntry("service", "test-service")
                .containsEntry("route", "/slow")
                .containsEntry("httpMethod", "GET")
                .containsEntry("statusCode", "200")
                .containsEntry("outcome", "slow")
                .containsKey("durationMs");
        assertThat(Long.parseLong(e.getMDCPropertyMap().get("durationMs")))
                .isGreaterThanOrEqualTo(250);
    }

    @Test
    void aFastServerErrorStillLogs() {
        get("/boom");
        await().atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(appender.list).hasSize(1));
        assertThat(appender.list.get(0).getMDCPropertyMap())
                .containsEntry("outcome", "failed")
                .containsEntry("statusCode", "500");
    }

    @Test
    void aFastClientErrorLogsNothing() {
        get("/badrequest");
        await().pollDelay(300, TimeUnit.MILLISECONDS)
               .atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(appender.list).isEmpty());
    }

    /**
     * Armeria runs the completion callback on a pooled event loop thread. A leaked MDC entry
     * would attach itself to unrelated later log lines from that thread.
     */
    @Test
    void mdcIsClearedAfterTheEvent() {
        get("/slow");
        await().atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(appender.list).hasSize(1));

        get("/fast");
        await().pollDelay(300, TimeUnit.MILLISECONDS)
               .atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(appender.list).hasSize(1));

        // Nothing new, and nothing from /slow leaked into the current thread either.
        assertThat(org.slf4j.MDC.getCopyOfContextMap()).satisfiesAnyOf(
                map -> assertThat(map).isNull(),
                map -> assertThat(map).doesNotContainKeys("route", "durationMs"));
    }
}
```

No new dependency is needed: `org.awaitility:awaitility:4.2.2` and `org.springframework:spring-test:6.1.13` are already resolved test-scope dependencies, and `ch.qos.logback:logback-core:1.5.8` (which provides `ListAppender`) is compile-scope. Verified with `mvn dependency:list`.

- [ ] **Step 8: Run it and confirm it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=SlowRequestLoggerTest
```

Expected: compilation failure — `SlowRequestLogger` does not exist.

- [ ] **Step 9: Implement the decorator**

Create `src/main/java/com/recsys/infrastructure/observability/SlowRequestLogger.java`:

```java
package com.recsys.infrastructure.observability;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.HttpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Emits one WARN per slow or failed request, for Splunk.
 *
 * <p>Deliberately silent for fast, successful requests. The HEC appender is at-most-once over a
 * bounded, drop-on-full queue: an event per request would push it into a régime where it discards
 * indiscriminately, taking the ERROR events the runbook searches depend on with it and firing
 * SplunkHecDroppingEvents as a side effect of an observability feature. Splunk's job here is
 * "show me the slow ones"; the distribution belongs to the Prometheus histograms.
 */
public final class SlowRequestLogger {

    private static final Logger log = LoggerFactory.getLogger(SlowRequestLogger.class);

    private SlowRequestLogger() {}

    public static Function<? super HttpService, ? extends HttpService> newDecorator(
            String serviceName, long thresholdMs) {

        return delegate -> (ctx, req) -> {
            ctx.log().whenComplete().thenAccept(requestLog -> emitIfNoteworthy(
                    requestLog, ctx.config().route().patternString(), serviceName, thresholdMs));
            return delegate.serve(ctx, req);
        };
    }

    private static void emitIfNoteworthy(RequestLog requestLog, String route,
                                         String serviceName, long thresholdMs) {
        long durationMs = TimeUnit.NANOSECONDS.toMillis(requestLog.totalDurationNanos());
        int statusCode = requestLog.responseStatus().code();

        String outcome = RequestOutcome.classify(statusCode, durationMs, thresholdMs);
        if (outcome == null) {
            return;
        }

        MDC.put("service", serviceName);
        MDC.put("route", route);
        MDC.put("httpMethod", requestLog.requestHeaders().method().name());
        MDC.put("statusCode", Integer.toString(statusCode));
        MDC.put("outcome", outcome);
        MDC.put("durationMs", Long.toString(durationMs));
        try {
            Throwable cause = requestLog.responseCause();
            if (cause != null) {
                log.warn("{} {} completed in {}ms with status {} ({})",
                        MDC.get("httpMethod"), route, durationMs, statusCode, outcome, cause);
            } else {
                log.warn("{} {} completed in {}ms with status {} ({})",
                        MDC.get("httpMethod"), route, durationMs, statusCode, outcome);
            }
        } finally {
            // The completion callback runs on a pooled event loop thread; a leaked entry would
            // attach itself to every later log line that thread emits.
            RequestOutcome.MDC_KEYS.forEach(MDC::remove);
        }
    }

    /** Suppresses an unused-import warning for HttpResponse in some IDE configurations. */
    private static final Class<?> UNUSED = HttpResponse.class;
}
```

Remove the `UNUSED` field and the `HttpResponse` import if the compiler does not need them — they are noted only because the lambda's return type inference sometimes wants the import present.

- [ ] **Step 10: Run the test and confirm it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=SlowRequestLoggerTest
```

Expected: PASS, all five tests.

- [ ] **Step 11: Prove the tests can fail**

Change `if (outcome == null) return;` to `if (false) return;` and re-run — `aFastSuccessfulRequestLogsNothing` and `aFastClientErrorLogsNothing` must both go red. Then delete the `finally` block's `MDC_KEYS.forEach(MDC::remove)` and re-run — `mdcIsClearedAfterTheEvent` must go red. Restore both.

- [ ] **Step 12: Mount on the three Armeria mains, with per-service thresholds**

The default must sit below each service's enforced request timeout, or the decorator only ever fires for requests that already timed out.

In `OnlinePredictionServer.java`, in the `sb.http(port)` chain (around line 223), immediately after the existing `MetricCollectingService` decorator:

```java
              .decorator(SlowRequestLogger.newDecorator("online-serving",
                      EnvConfig.readLong("SLOW_REQUEST_LOG_THRESHOLD_MS", 300)))
```

300 ms, because this server sets `requestTimeoutMillis` from `ONLINE_REQUEST_TIMEOUT_MS` with a default of 500.

In `RecSysServer.java`, in the same builder chain as Task 2's decorator:

```java
                .decorator(SlowRequestLogger.newDecorator("catalog-serving",
                        EnvConfig.readLong("SLOW_REQUEST_LOG_THRESHOLD_MS", 500)))
```

In `MicroserviceGatewayServer.java`, immediately after Task 2's decorator:

```java
        sb.decorator(SlowRequestLogger.newDecorator("api-gateway",
                EnvConfig.readLong("SLOW_REQUEST_LOG_THRESHOLD_MS", 1000)));
```

The gateway is loosest because its duration contains a backend's duration by construction. Add `import com.recsys.infrastructure.observability.SlowRequestLogger;` and `import com.recsys.config.EnvConfig;` where not already present.

- [ ] **Step 13: Verify against a running service**

```bash
REDIS_ALLOW_NO_AUTH=true JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  mvn exec:java -Dexec.mainClass=com.recsys.api.serving.RecSysServer > /tmp/recsys-serving.log 2>&1 &
sleep 20
curl -s "localhost:6010/getrecommendation?user=123&n=5" -o /dev/null
curl -s "localhost:6010/nonexistent" -o /dev/null
grep -c "completed in" /tmp/recsys-serving.log
kill %1
```

Expected: the 404 produces no line. A cold-start request may well exceed 500 ms and produce one — that is correct behaviour, not a bug. If nothing at all appears, force it by re-running with `SLOW_REQUEST_LOG_THRESHOLD_MS=0` and confirm every request then logs.

- [ ] **Step 14: Add both tests to the resilience profile**

```xml
                <!-- The classification is the single definition of which requests reach Splunk,
                     shared by the Armeria decorator and the Spring interceptor so four services
                     cannot disagree. Pins two things the gate must not lose: 4xx does not
                     trigger an event (it is caller-controlled, and a bounded drop-on-full HEC
                     queue makes that a remote way to discard the ERROR events), and no MDC field
                     name collides with SplunkHecEventSerializer's reserved set, where a
                     collision is silently dropped rather than reported. Pure unit-level. -->
                <include>**/observability/RequestOutcomeTest.java</include>
                <!-- The decorator half: emits for slow and 5xx, stays silent otherwise, and
                     clears MDC on the pooled event loop thread it runs on. A leaked MDC entry
                     would attach itself to unrelated later log lines from that thread, which
                     poisons exactly the searches this feature exists to enable. In-process
                     Armeria server, no Redis, no Docker. -->
                <include>**/observability/SlowRequestLoggerTest.java</include>
```

- [ ] **Step 15: Run the gate and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
git add src/main/java/com/recsys/infrastructure/observability/RequestOutcome.java \
        src/main/java/com/recsys/infrastructure/observability/SlowRequestLogger.java \
        src/main/java/com/recsys/infrastructure/observability/SplunkHecEventSerializer.java \
        src/test/java/com/recsys/infrastructure/observability/ \
        src/main/java/com/recsys/api/serving/RecSysServer.java \
        src/main/java/com/recsys/api/online/OnlinePredictionServer.java \
        src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java \
        pom.xml
git commit -m "feat(observability): log slow and failed requests on the Armeria services

Nothing on any request path logged a duration, so Splunk contained no
latency data at all. This emits one WARN per slow or 5xx request, with
route, status, duration and outcome as MDC fields the HEC serializer
already promotes.

Fast successful requests stay silent on purpose: the appender is
at-most-once over a bounded drop-on-full queue, so per-request events would
discard indiscriminately and take the ERROR events with them. 4xx does not
trigger for the same reason -- it is the one class a caller controls.

Thresholds default per service below each one's enforced request timeout;
online serving caps requests at 500ms, so its default is 300."
```

---

### Task 6: Slow-request events from the Spring model service

**Files:**
- Create: `src/main/java/com/recsys/config/SlowRequestInterceptor.java`
- Create: `src/test/java/com/recsys/config/SlowRequestInterceptorTest.java`
- Modify: `src/main/java/com/recsys/config/WebConfig.java`
- Modify: `pom.xml`

**Interfaces:**
- Consumes: `RequestOutcome.classify(...)` and `RequestOutcome.MDC_KEYS` from Task 5.
- Produces: `SlowRequestInterceptor` — a Spring `@Component` implementing `HandlerInterceptor`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/config/SlowRequestInterceptorTest.java`:

```java
package com.recsys.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

class SlowRequestInterceptorTest {

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;
    private SlowRequestInterceptor interceptor;

    @BeforeEach
    void setUp() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger(SlowRequestInterceptor.class);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
        interceptor = new SlowRequestInterceptor(100);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private MockHttpServletRequest request(String pattern) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/models/recmodel:predict");
        req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        return req;
    }

    private void run(MockHttpServletRequest req, MockHttpServletResponse res, long elapsedMs) {
        interceptor.preHandle(req, res, new Object());
        // Rewind the recorded start so the interceptor measures a controlled duration rather
        // than the test's own runtime.
        req.setAttribute(SlowRequestInterceptor.START_NANOS_ATTRIBUTE,
                System.nanoTime() - elapsedMs * 1_000_000L);
        interceptor.afterCompletion(req, res, new Object(), null);
    }

    @Test
    void aFastSuccessfulRequestLogsNothing() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(200);
        run(request("/v1/models/{name}:predict"), res, 5);
        assertThat(appender.list).isEmpty();
    }

    @Test
    void aSlowRequestLogsOneWarnWithItsFields() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(200);
        run(request("/v1/models/{name}:predict"), res, 250);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent e = appender.list.get(0);
        assertThat(e.getLevel()).isEqualTo(Level.WARN);
        assertThat(e.getMDCPropertyMap())
                .containsEntry("service", "model-serving")
                .containsEntry("route", "/v1/models/{name}:predict")
                .containsEntry("httpMethod", "GET")
                .containsEntry("statusCode", "200")
                .containsEntry("outcome", "slow");
    }

    @Test
    void aFastServerErrorStillLogs() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(500);
        run(request("/v1/models/{name}:predict"), res, 1);
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getMDCPropertyMap()).containsEntry("outcome", "failed");
    }

    @Test
    void aFastClientErrorLogsNothing() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(404);
        run(request("/v1/models/{name}:predict"), res, 1);
        assertThat(appender.list).isEmpty();
    }

    /**
     * The route field must be the matched pattern, never the raw URI. A path carrying an id
     * would make every request its own distinct route value in Splunk.
     */
    @Test
    void theRouteIsTheMatchedPatternNotTheRawUri() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(200);
        run(request("/v1/models/{name}:predict"), res, 250);
        assertThat(appender.list.get(0).getMDCPropertyMap().get("route"))
                .isEqualTo("/v1/models/{name}:predict");
    }

    @Test
    void mdcIsClearedAfterTheEvent() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(200);
        run(request("/v1/models/{name}:predict"), res, 250);
        assertThat(org.slf4j.MDC.getCopyOfContextMap()).satisfiesAnyOf(
                map -> assertThat(map).isNull(),
                map -> assertThat(map).doesNotContainKeys("route", "durationMs"));
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=SlowRequestInterceptorTest
```

Expected: compilation failure — `SlowRequestInterceptor` does not exist. `org.springframework.mock.web` resolves already via `spring-test:6.1.13`; no dependency change is needed.

- [ ] **Step 3: Implement the interceptor**

Create `src/main/java/com/recsys/config/SlowRequestInterceptor.java`:

```java
package com.recsys.config;

import com.recsys.infrastructure.observability.RequestOutcome;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.concurrent.TimeUnit;

/**
 * The model service's equivalent of {@code SlowRequestLogger}: one WARN per slow or failed
 * request. Shares {@link RequestOutcome} with the Armeria decorator so the four services cannot
 * disagree about what "slow" means.
 */
@Component
public class SlowRequestInterceptor implements HandlerInterceptor {

    static final String START_NANOS_ATTRIBUTE = SlowRequestInterceptor.class.getName() + ".start";
    private static final String SERVICE_NAME = "model-serving";

    private static final Logger log = LoggerFactory.getLogger(SlowRequestInterceptor.class);

    private final long thresholdMs;

    public SlowRequestInterceptor(
            @Value("${recsys.observability.slow-request-threshold-ms:${SLOW_REQUEST_LOG_THRESHOLD_MS:500}}")
            long thresholdMs) {
        this.thresholdMs = thresholdMs;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        request.setAttribute(START_NANOS_ATTRIBUTE, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler,
                                Exception ex) {
        Object start = request.getAttribute(START_NANOS_ATTRIBUTE);
        if (!(start instanceof Long startNanos)) {
            return;
        }
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        int statusCode = response.getStatus();

        String outcome = RequestOutcome.classify(statusCode, durationMs, thresholdMs);
        if (outcome == null) {
            return;
        }

        // The matched pattern, never the raw URI: a path carrying an id would make every
        // request its own route value in Splunk.
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = pattern instanceof String s ? s : "unmatched";

        MDC.put("service", SERVICE_NAME);
        MDC.put("route", route);
        MDC.put("httpMethod", request.getMethod());
        MDC.put("statusCode", Integer.toString(statusCode));
        MDC.put("outcome", outcome);
        MDC.put("durationMs", Long.toString(durationMs));
        try {
            if (ex != null) {
                log.warn("{} {} completed in {}ms with status {} ({})",
                        request.getMethod(), route, durationMs, statusCode, outcome, ex);
            } else {
                log.warn("{} {} completed in {}ms with status {} ({})",
                        request.getMethod(), route, durationMs, statusCode, outcome);
            }
        } finally {
            // Tomcat threads are pooled and TraceIdAspect also writes MDC on them.
            RequestOutcome.MDC_KEYS.forEach(MDC::remove);
        }
    }
}
```

- [ ] **Step 4: Register it in `WebConfig`**

Replace `WebConfig.java` entirely:

```java
package com.recsys.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @NonNull
    private final LoginInterceptor loginInterceptor;

    @NonNull
    private final SlowRequestInterceptor slowRequestInterceptor;

    public WebConfig(@NonNull LoginInterceptor loginInterceptor,
                     @NonNull SlowRequestInterceptor slowRequestInterceptor) {
        this.loginInterceptor = loginInterceptor;
        this.slowRequestInterceptor = slowRequestInterceptor;
    }

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        // Registered first so its preHandle runs before LoginInterceptor's and its
        // afterCompletion runs last — a request rejected by auth still gets timed.
        registry.addInterceptor(slowRequestInterceptor);
        registry.addInterceptor(loginInterceptor);
    }
}
```

- [ ] **Step 5: Run the test and confirm it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=SlowRequestInterceptorTest
```

Expected: PASS, all six tests.

- [ ] **Step 6: Prove the tests can fail**

Change the route resolution to `String route = request.getRequestURI();` and re-run — `theRouteIsTheMatchedPatternNotTheRawUri` must go red. Delete the `finally` block's removal loop — `mdcIsClearedAfterTheEvent` must go red. Restore both.

- [ ] **Step 7: Confirm the Spring context still starts**

Adding a constructor parameter to `WebConfig` breaks the context if `SlowRequestInterceptor` is not component-scanned.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='*ApplicationTests,*ContextTest' -DfailIfNoTests=false
```

If no context test exists, boot it directly and confirm it reaches "Started":

```bash
REDIS_ALLOW_NO_AUTH=true JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  timeout 90 mvn spring-boot:run 2>&1 | grep -E "Started ModelApplication|APPLICATION FAILED"
```

- [ ] **Step 8: Add the test to the resilience profile and commit**

```xml
                <!-- The model service's half of the slow-request event. Separate from the
                     Armeria decorator because it is a different mechanism (HandlerInterceptor,
                     Tomcat threads) reaching the same contract, and it pins the one thing the
                     Armeria side gets for free: route must be the matched pattern, not the raw
                     URI, or every request with an id in the path becomes its own route value.
                     MockHttpServletRequest only, no container. -->
                <include>**/config/SlowRequestInterceptorTest.java</include>
```

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
git add src/main/java/com/recsys/config/SlowRequestInterceptor.java \
        src/main/java/com/recsys/config/WebConfig.java \
        src/test/java/com/recsys/config/SlowRequestInterceptorTest.java pom.xml
git commit -m "feat(observability): log slow and failed requests on the model service

Shares RequestOutcome with the Armeria decorator so all four services agree
on what counts. Registered before LoginInterceptor so afterCompletion runs
last and an auth-rejected request is still timed.

route is the matched handler pattern, never the raw URI."
```

---

### Task 7: Heap, GC and latency alerts

**Files:**
- Modify: `k8s/base/prometheus-rules.yaml`
- Modify: `k8s/base/prometheus-rules.test.yaml`

**Interfaces:**
- Consumes: the metric names bound in Task 1 (`jvm_memory_used_bytes`, `jvm_memory_max_bytes`, `jvm_gc_pause_seconds_sum`) and the histograms mounted in Task 2 (`catalog_serving_request_duration_seconds_bucket`, `api_gateway_request_duration_seconds_bucket`, plus the pre-existing `online_serving_request_duration_seconds_bucket`).
- Produces: alerts `JvmHeapPressureHigh`, `JvmGcTimeFractionHigh`, `RequestLatencyP99High`.

- [ ] **Step 1: Confirm every metric name against a live exposition**

An alert on a metric that is never emitted looks like coverage and can never fire — the rule the file's own header comment states. Do not skip this.

```bash
REDIS_ALLOW_NO_AUTH=true GATEWAY_ALLOW_ANONYMOUS=true \
  JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  mvn exec:java -Dexec.mainClass=com.recsys.api.gateway.MicroserviceGatewayServer &
sleep 25
for i in $(seq 1 20); do curl -s -o /dev/null localhost:8010/health; done
curl -s localhost:8010/metrics | grep -E '^(jvm_memory_used_bytes|jvm_memory_max_bytes|jvm_gc_pause_seconds_sum|api_gateway_request_duration_seconds_bucket)' | head
kill %1
```

Every one of the four prefixes must appear. **`jvm_gc_pause_seconds_sum` may legitimately be absent** if no GC has occurred yet — Micrometer registers that timer on the first collection. If it is missing, force one and re-check rather than assuming; if it still does not appear, `JvmGcTimeFractionHigh` must be written against a name that does exist, and the discrepancy recorded in the docs task.

- [ ] **Step 2: Add the three alerts**

In `k8s/base/prometheus-rules.yaml`, append to the `recsys.data` group (or add a new `recsys.runtime` group after it, matching the file's existing grouping style):

```yaml
        - alert: JvmHeapPressureHigh
          expr: >-
            sum by (job, instance) (jvm_memory_used_bytes{area="heap"})
            /
            sum by (job, instance) (jvm_memory_max_bytes{area="heap"} > 0)
            > 0.90
          for: 10m
          labels:
            severity: warning
          annotations:
            summary: "{{ $labels.job }} heap is above 90% used"
            description: >-
              Sustained heap pressure. Expect longer GC pauses and, past this, allocation
              stalls. Cross-check the Splunk heap-pressure events from the same service for
              what was running at the crossing. The `> 0` filter on jvm_memory_max_bytes is
              load-bearing: pools with no maximum report -1, and including them makes the
              ratio meaningless.

        - alert: JvmGcTimeFractionHigh
          expr: sum by (job, instance) (rate(jvm_gc_pause_seconds_sum[5m])) > 0.10
          for: 10m
          labels:
            severity: warning
          annotations:
            summary: "{{ $labels.job }} is spending over 10% of wall time in GC pause"
            description: >-
              More than a tenth of elapsed time is stop-the-world pause, which shows up
              directly as request latency. Check JvmHeapPressureHigh on the same instance
              first — this is usually its consequence, not an independent fault.

        - alert: RequestLatencyP99High
          expr: >-
            histogram_quantile(0.99, sum by (le, job) (
              rate(online_serving_request_duration_seconds_bucket[5m]))) > 0.4
            or
            histogram_quantile(0.99, sum by (le, job) (
              rate(catalog_serving_request_duration_seconds_bucket[5m]))) > 1
            or
            histogram_quantile(0.99, sum by (le, job) (
              rate(api_gateway_request_duration_seconds_bucket[5m]))) > 2
          for: 10m
          labels:
            severity: warning
          annotations:
            summary: "{{ $labels.job }} p99 request latency is above its budget"
            description: >-
              Search Splunk for the same window with outcome=slow to see which routes and
              which requests. Thresholds differ per service and are bound to each one's
              enforced request timeout: online serving caps requests at 500ms
              (ONLINE_REQUEST_TIMEOUT_MS), so a threshold at or above 0.5 there could never
              fire. If a service gains or changes an explicit request timeout, its threshold
              must move with it.
```

- [ ] **Step 3: Write the promtool cases**

Append to `k8s/base/prometheus-rules.test.yaml`:

```yaml
  # --- JvmHeapPressureHigh ---
  - interval: 1m
    input_series:
      # 95% used: must fire.
      - series: 'jvm_memory_used_bytes{area="heap", job="recsys-api-gateway", instance="10.0.0.1:8010", id="G1 Old Gen"}'
        values: '950000000x20'
      - series: 'jvm_memory_max_bytes{area="heap", job="recsys-api-gateway", instance="10.0.0.1:8010", id="G1 Old Gen"}'
        values: '1000000000x20'
      # 85% used: near-miss, must NOT fire.
      - series: 'jvm_memory_used_bytes{area="heap", job="recsys-online-serving", instance="10.0.0.2:7010", id="G1 Old Gen"}'
        values: '850000000x20'
      - series: 'jvm_memory_max_bytes{area="heap", job="recsys-online-serving", instance="10.0.0.2:7010", id="G1 Old Gen"}'
        values: '1000000000x20'
    alert_rule_test:
      - eval_time: 15m
        alertname: JvmHeapPressureHigh
        exp_alerts:
          - exp_labels:
              severity: warning
              job: recsys-api-gateway
              instance: 10.0.0.1:8010
            exp_annotations:
              summary: "recsys-api-gateway heap is above 90% used"
              description: >-
                Sustained heap pressure. Expect longer GC pauses and, past this, allocation
                stalls. Cross-check the Splunk heap-pressure events from the same service for
                what was running at the crossing. The `> 0` filter on jvm_memory_max_bytes is
                load-bearing: pools with no maximum report -1, and including them makes the
                ratio meaningless.

  # --- JvmHeapPressureHigh: the -1 sentinel must not poison the ratio ---
  # A pool with no maximum reports -1. Summed in naively alongside a real pool it drags the
  # denominator down and can make a healthy heap read as over 100% used, or produce a
  # negative denominator and a comparison that silently never fires. Same class of trap as
  # RedisReplicaLagHigh's NaN and GatewayRegistryStale's -1, both recorded in 18 section 8.4.
  - interval: 1m
    input_series:
      # 50% used against the pool that HAS a max: must not fire.
      - series: 'jvm_memory_used_bytes{area="heap", job="recsys-catalog-serving", instance="10.0.0.3:6010", id="G1 Old Gen"}'
        values: '500000000x20'
      - series: 'jvm_memory_max_bytes{area="heap", job="recsys-catalog-serving", instance="10.0.0.3:6010", id="G1 Old Gen"}'
        values: '1000000000x20'
      - series: 'jvm_memory_used_bytes{area="heap", job="recsys-catalog-serving", instance="10.0.0.3:6010", id="G1 Eden Space"}'
        values: '100000000x20'
      - series: 'jvm_memory_max_bytes{area="heap", job="recsys-catalog-serving", instance="10.0.0.3:6010", id="G1 Eden Space"}'
        values: '-1x20'
    alert_rule_test:
      - eval_time: 15m
        alertname: JvmHeapPressureHigh
        exp_alerts: []

  # --- JvmGcTimeFractionHigh ---
  - interval: 1m
    input_series:
      # 0.2s of pause per second of wall time: must fire.
      - series: 'jvm_gc_pause_seconds_sum{job="recsys-online-serving", instance="10.0.0.2:7010", action="end of minor GC", cause="G1 Evacuation Pause"}'
        values: '0+12x25'
      # 0.05s per second: near-miss, must NOT fire.
      - series: 'jvm_gc_pause_seconds_sum{job="recsys-catalog-serving", instance="10.0.0.3:6010", action="end of minor GC", cause="G1 Evacuation Pause"}'
        values: '0+3x25'
    alert_rule_test:
      - eval_time: 20m
        alertname: JvmGcTimeFractionHigh
        exp_alerts:
          - exp_labels:
              severity: warning
              job: recsys-online-serving
              instance: 10.0.0.2:7010
            exp_annotations:
              summary: "recsys-online-serving is spending over 10% of wall time in GC pause"
              description: >-
                More than a tenth of elapsed time is stop-the-world pause, which shows up
                directly as request latency. Check JvmHeapPressureHigh on the same instance
                first — this is usually its consequence, not an independent fault.

  # --- RequestLatencyP99High: online serving fires at 0.4, not the 1s the others get ---
  # Its requestTimeoutMillis default is 500ms, so a threshold at or above 0.5 there could
  # never fire: the histogram is bounded by the timeout. promtool cannot detect an
  # unreachable threshold — an alert that can never fire passes a near-miss case perfectly.
  - interval: 1m
    input_series:
      # All observations in the 0.5s bucket: p99 lands at 0.5 > 0.4, must fire.
      - series: 'online_serving_request_duration_seconds_bucket{le="0.1", job="recsys-online-serving"}'
        values: '0+0x25'
      - series: 'online_serving_request_duration_seconds_bucket{le="0.5", job="recsys-online-serving"}'
        values: '0+100x25'
      - series: 'online_serving_request_duration_seconds_bucket{le="+Inf", job="recsys-online-serving"}'
        values: '0+100x25'
    alert_rule_test:
      - eval_time: 20m
        alertname: RequestLatencyP99High
        exp_alerts:
          - exp_labels:
              severity: warning
              job: recsys-online-serving
            exp_annotations:
              summary: "recsys-online-serving p99 request latency is above its budget"
              description: >-
                Search Splunk for the same window with outcome=slow to see which routes and
                which requests. Thresholds differ per service and are bound to each one's
                enforced request timeout: online serving caps requests at 500ms
                (ONLINE_REQUEST_TIMEOUT_MS), so a threshold at or above 0.5 there could never
                fire. If a service gains or changes an explicit request timeout, its threshold
                must move with it.

  # --- RequestLatencyP99High near-miss: everything fast, must NOT fire ---
  - interval: 1m
    input_series:
      - series: 'online_serving_request_duration_seconds_bucket{le="0.1", job="recsys-online-serving"}'
        values: '0+100x25'
      - series: 'online_serving_request_duration_seconds_bucket{le="0.5", job="recsys-online-serving"}'
        values: '0+100x25'
      - series: 'online_serving_request_duration_seconds_bucket{le="+Inf", job="recsys-online-serving"}'
        values: '0+100x25'
      - series: 'api_gateway_request_duration_seconds_bucket{le="1", job="recsys-api-gateway"}'
        values: '0+100x25'
      - series: 'api_gateway_request_duration_seconds_bucket{le="2", job="recsys-api-gateway"}'
        values: '0+100x25'
      - series: 'api_gateway_request_duration_seconds_bucket{le="+Inf", job="recsys-api-gateway"}'
        values: '0+100x25'
    alert_rule_test:
      - eval_time: 20m
        alertname: RequestLatencyP99High
        exp_alerts: []
```

- [ ] **Step 4: Run promtool**

```bash
cd k8s/base
yq '.spec' prometheus-rules.yaml > prometheus-rules.rules.yaml
promtool check rules prometheus-rules.rules.yaml
promtool test rules prometheus-rules.test.yaml
```

Expected: `SUCCESS` for both. If `exp_annotations` mismatch on whitespace, copy the rendered text promtool reports rather than reformatting the YAML — the `>-` folding must match exactly.

- [ ] **Step 5: Prove each new case can fail**

For each of the three alerts in turn, raise its threshold far above the firing input (e.g. `> 0.99` for heap), re-run `promtool test rules`, and confirm the fire case goes red. Then restore and instead lower the threshold below the near-miss input, re-run, and confirm the near-miss case goes red. An expression that passes both directions is doing real work; one that passes only the fire case may be firing on everything.

Do this specifically for the `-1` sentinel case: remove the `> 0` filter from the heap expression and re-run — the sentinel case must go red. This is the assertion that matters most, and it is the one nothing else in the repo would catch.

- [ ] **Step 6: Clean up and commit**

```bash
cd /Users/linghuang/Git/Recsys-Backend-Service
rm -f k8s/base/prometheus-rules.rules.yaml   # generated, gitignored
git add k8s/base/prometheus-rules.yaml k8s/base/prometheus-rules.test.yaml
git commit -m "feat(alerts): heap pressure, GC time fraction and p99 request latency

Three alerts over the metrics Tasks 1 and 2 made scrapeable. Each has a
promtool fire case and a near-miss.

Two traps are pinned by tests rather than prose. The heap expression filters
jvm_memory_max_bytes > 0 because pools with no maximum report -1, the same
sentinel class that silently defeated GatewayRegistryStale; a dedicated case
proves the filter is load-bearing. And the p99 thresholds are per service
because online serving caps requests at 500ms, so any threshold at or above
0.5 there could never fire -- which promtool cannot detect, since an
unreachable threshold passes a near-miss perfectly."
```

---

### Task 8: Documentation

**Files:**
- Modify: `docs/system_design/18_Fault_Tolerance.md` (§8 prose, §8.3 inventory, §8.4 alert table, §8.5)
- Modify: `docs/runbooks/splunk-hec-logging.md` ("Useful searches")
- Modify: `.claude/CLAUDE.md` (env vars)
- Modify: `docs/superpowers/specs/2026-08-18-latency-memory-observability-design.md` (one correction)

- [ ] **Step 1: Correct the spec's env-var count**

The spec's Documentation section says "the five new environment variables". There are four: `SLOW_REQUEST_LOG_THRESHOLD_MS`, `GC_PAUSE_LOG_THRESHOLD_MS`, `HEAP_PRESSURE_THRESHOLD`, `HEAP_PRESSURE_RECOVERY_THRESHOLD`. Change "five" to "four".

- [ ] **Step 2: Add the §8 boundary paragraph**

In `docs/system_design/18_Fault_Tolerance.md`, after the paragraph beginning "**Neither is derived from the other.**", insert:

```markdown
**Latency and memory appear in both tools, and that is not an exception to the rule.** Splunk
carries edge-triggered *events* — a request that exceeded a threshold, a GC pause, a heap
crossing — each a discrete occurrence with high-cardinality context attached. Prometheus carries
continuous *series* over the same underlying runtime state, sampled independently by a Micrometer
binder or an Armeria decorator. Neither reads the other: no alert parses a log line, and nothing
emits a log line so that a metric can be computed from it. The property that keeps this honest is
that **nothing ships to Splunk on a timer** — a periodic heap sample would be a metric wearing a
log's clothes, and was considered and rejected when this was built.
```

- [ ] **Step 3: Extend the §8.3 inventory**

Add a **Runtime** subsection after the **Splunk shipping** table:

```markdown
**Runtime** — JVM and request-duration metrics. Nothing bound these before 2026-08-18: Armeria's
`PrometheusMeterRegistries.configureRegistry` is a no-op, so heap usage, GC pause time and thread
counts were unscrapeable on 6010, 7010 and 8010 while looking entirely present. Only the model
service had them, from Actuator's auto-configuration.

| Metric | Registered in |
|---|---|
| `jvm_memory_used_bytes`, `_committed_bytes`, `_max_bytes`, `jvm_gc_pause_seconds`, `jvm_threads_live_threads`, `system_cpu_count` (and the rest of Micrometer's JVM binder set) | [`metrics/JvmMetricsBinder.java`](../../src/main/java/com/recsys/metrics/JvmMetricsBinder.java) |
| `online_serving_request_duration_seconds`, `catalog_serving_request_duration_seconds`, `api_gateway_request_duration_seconds` | Armeria `MetricCollectingService`, mounted in each service's main |
```

Then correct the existing omission — in the **Serving** table's first row, `online_serving_request_duration_seconds` was never listed despite having existed since the online server was written. It is now covered by the Runtime table above; add a parenthetical to the Serving row pointing there so a reader does not conclude the hand-rolled gauges are all that exists.

Also note in the Runtime prose that `JvmMetricsBinder` is idempotent per registry because `defaultRegistry()` is a JVM-wide singleton and `JvmGcMetrics` installs a JMX listener per bind — a second bind would double-count every pause.

- [ ] **Step 4: Add the three alerts to the §8.4 table**

Append three rows in the established four-column format (Alert / Means / Likely cause / First response). For `JvmHeapPressureHigh`, the first response must say to cross-check the Splunk heap-pressure events for the same service and window. For `RequestLatencyP99High`, it must say to search Splunk with `outcome=slow`.

Then extend the "**Three traps this file fell into once**" list to four:

```markdown
- **A threshold above an enforced timeout can never fire, and `promtool` cannot see it.**
  `RequestLatencyP99High` uses 0.4 s for online serving where the other two get 1 s and 2 s,
  because `OnlinePredictionServer` sets `requestTimeoutMillis` from `ONLINE_REQUEST_TIMEOUT_MS`
  (default 500 ms) — the histogram is bounded by the timeout, so a 1 s threshold there is
  unreachable. An unreachable threshold passes a near-miss case perfectly and looks like
  coverage, which makes this invisible to every mechanism in §8.6. If a service gains an
  explicit request timeout, its latency threshold has to move with it.
```

- [ ] **Step 5: Extend the §8.5 `traceId` bullet**

Append to the existing "No tracing backend" bullet:

```markdown
  The slow-request events added in 2026-08 inherit this limit exactly: they carry `traceId`
  where MDC has one, which is the model service only. A slow gateway request and the slow
  backend request it caused **cannot be correlated by `traceId`** — only by timestamp, service
  and route. The field being present on some events and absent on others invites precisely the
  wrong inference, so do not read its absence as "this request had no trace".
```

- [ ] **Step 6: Add searches to the Splunk runbook**

In `docs/runbooks/splunk-hec-logging.md`, under "Useful searches", add:

````markdown
Slow and failed requests, worst routes first. `outcome` is `slow` or `failed`; 4xx never appears
here (see below).

```
index=recsys sourcetype="recsys:app:log" outcome=* | stats count, avg(durationMs), max(durationMs) by source route outcome | sort -count
```

One service's slow requests over time:

```
index=recsys sourcetype="recsys:app:log" source="recsys-online-serving" outcome="slow" | timechart span=1m count
```

GC pauses and heap-pressure crossings:

```
index=recsys sourcetype="recsys:app:log" pauseMs=* | stats count, max(pauseMs), max(heapUsedFraction) by source gcCause
```

Whether a latency spike was GC. Run both over the same window and compare timestamps — there is
no join key, deliberately: correlating them is the operator's judgement, not something the data
asserts.

```
index=recsys sourcetype="recsys:app:log" (outcome="slow" OR pauseMs>200) | sort _time | table _time source route durationMs pauseMs heapUsedFraction
```

**Three limits on all of the above.** Delivery is at-most-once, so every count is a lower bound —
and under load it is a lower bound precisely when load is what you are investigating. A fast
successful request is never logged, so these searches cannot produce a latency distribution;
that is Prometheus's `*_request_duration_seconds` histograms. And 4xx responses are deliberately
excluded from `outcome` unless they were also slow, because a caller-controlled trigger on a
bounded drop-on-full queue is a remote way to discard everything else in it.
````

- [ ] **Step 7: Add the env vars to `CLAUDE.md`**

In the key-env-vars paragraph, add:

```markdown
`SLOW_REQUEST_LOG_THRESHOLD_MS` (per-service default: online serving 300, catalog serving 500,
gateway 1000, model serving 500) is the duration above which a request gets one WARN log event
carrying `route`/`statusCode`/`durationMs`/`outcome` in MDC, which the Splunk appender promotes to
searchable fields. Fast successful requests and all 4xx are deliberately never logged — the HEC
queue is bounded and drops indiscriminately when full. Each default sits below its service's
enforced request timeout; online serving's is 300 because `ONLINE_REQUEST_TIMEOUT_MS` defaults to
500. `GC_PAUSE_LOG_THRESHOLD_MS` (default 200) is the stop-the-world pause above which
`GcEventTracker` logs an event; concurrent collectors are excluded, since a ZGC cycle's reported
wall time includes concurrent phases. `HEAP_PRESSURE_THRESHOLD` (default 0.90) and
`HEAP_PRESSURE_RECOVERY_THRESHOLD` (default 0.80) bound an edge-triggered heap-pressure event with
hysteresis — it logs on crossing, never per collection. All four are shipping-only: none changes
serving behaviour.
```

Also amend the existing Prometheus paragraph to record that the three Armeria services now bind
the JVM metric set via `JvmMetricsBinder` (Armeria binds none of it itself) and that catalog
serving and the gateway now mount `MetricCollectingService`.

- [ ] **Step 8: Verify the docs tests still pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='DocumentationIndexTest,DocumentedMechanismTest'
```

Expected: PASS. `DocumentationIndexTest` is scoped to `docs/system_design/` and `docs/runbooks/`; no new file is added in either, so this should pass — but `DocumentedMechanismTest` may check that documented class names resolve, and the new tables name `JvmMetricsBinder`. If it fails, read what it wants rather than adjusting the doc to dodge it.

- [ ] **Step 9: Full gate, then commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
git add docs/system_design/18_Fault_Tolerance.md \
        docs/runbooks/splunk-hec-logging.md \
        .claude/CLAUDE.md \
        docs/superpowers/specs/2026-08-18-latency-memory-observability-design.md
git commit -m "docs: record the latency and memory observability work

18 section 8 gains a Runtime metric table, the three new alerts, and a
paragraph on why latency and memory living in both tools is not a boundary
violation -- edge-triggered events versus continuous series, neither
derived from the other, nothing shipped on a timer.

Two things corrected rather than added: online_serving_request_duration_
seconds existed since the online server was written and was never in the
inventory, and section 8.5's traceId caveat now says the slow-request events
inherit it, so a gateway request and its backend request cannot be
correlated by traceId.

Section 8.4's trap list gains a fourth entry: a threshold above an enforced
request timeout can never fire, and promtool cannot detect it."
```

---

### Task 9: Open the pull request

- [ ] **Step 1: Confirm the whole gate is green from a clean build**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn clean test -Presilience
```

- [ ] **Step 2: Review the full diff before opening**

```bash
git diff origin/main --stat
git diff origin/main
```

Check specifically that no `MeterNameProbe.java` survives, no `prometheus-rules.rules.yaml` is staged, and no debugging change from a "prove the test fails" step was left in place. Those steps deliberately break things; each has a restore instruction, and a missed restore is the likeliest way this lands broken.

- [ ] **Step 3: Push and open the PR**

```bash
git push -u origin feat/latency-memory-observability
gh pr create --title "Latency and memory observability: Splunk events and the Prometheus gap" --body "$(cat <<'EOF'
Implements docs/superpowers/specs/2026-08-18-latency-memory-observability-design.md.

## What this adds

**Splunk** gets two edge-triggered event types: one WARN per slow or 5xx request across all four
services, and GC pause / heap-pressure crossing events from `GcEventTracker`. Fast successful
requests and 4xx are never logged — the HEC queue is bounded and drops indiscriminately, so a
caller-controlled trigger would be a remote way to discard the ERROR events.

**Prometheus** gets the JVM metric set on the three Armeria services, request-duration histograms
on the two that lacked them, and three alerts.

## What measuring first changed

The request was "monitor latency and memory in Splunk". Two measurements moved most of the work:

- **Armeria's `PrometheusMeterRegistries.configureRegistry` is a no-op** — decompiled, it
  null-checks its argument and returns it. No JVM binder is called anywhere in `src/main`, so heap
  usage and GC pause time have never been scrapeable on 6010, 7010 or 8010. The memory gap was a
  Prometheus gap.
- **Nothing on any request path logged a duration.** No `AccessLogWriter`, and MDC was populated
  only by `TraceIdAspect` (Spring-only). Splunk contained no latency data to search.

Two hazards are pinned by tests because nothing else would catch them: `jvm_memory_max_bytes`
reports `-1` for pools with no maximum and silently poisons the heap ratio, and a p99 threshold
above a service's enforced request timeout can never fire — online serving caps requests at 500 ms,
so its threshold is 0.4 where the others get 1 and 2. `promtool` cannot detect an unreachable
threshold; it passes a near-miss case perfectly.

## Boundary

`18_Fault_Tolerance` §8's no-derivation rule is intact and §8 now says why: Splunk gets
edge-triggered events, Prometheus gets continuous series, neither reads the other, and **nothing
ships to Splunk on a timer**. Periodic heap sampling was considered and rejected as the one shape
that would break it.

## Verification

`mvn test -Presilience` green; five new tests added to that profile with justifications.
`promtool test rules` green, fire and near-miss per alert plus a dedicated `-1` sentinel case.
Metric names confirmed against a live `/metrics` exposition rather than assumed.

**The Splunk wire path is not verified locally and cannot be** — this host is arm64 and `splunkd`
segfaults under emulation during first-boot indexing. End-to-end verification rides
`SplunkHecIntegrationTest` on the x86_64 runner.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review

**Spec coverage.** Every section maps to a task: A1 → Tasks 5 and 6; A2 → Task 4; A3 → Task 3; B1 → Task 1; B2 → Task 2; B3 → Task 7; the Testing section is distributed across each task's own test steps plus the profile includes; Documentation → Task 8. The spec's three "measure, don't assume" items are all discharged — the cardinality question was resolved before writing this plan (`DefaultMeterIdPrefixFunction` tags by `hostname.pattern`/`http.status`/`method`/`service`, no path tag), the NaN hazard has a dedicated promtool case in Task 7 Step 3, and the arm64 Splunk constraint appears in Global Constraints and again in the PR body.

**One spec item the plan changes:** the spec says "five new environment variables"; there are four. Task 8 Step 1 corrects the spec rather than leaving the two documents disagreeing.

**Type consistency.** `RequestOutcome.classify(int, long, long) → String` is defined in Task 5 and consumed unchanged in Task 6. `RequestOutcome.MDC_KEYS` is used for cleanup in both emitters. `JvmMetricsBinder.bindTo(MeterRegistry)` is defined in Task 1 and called in Task 3's edits. `GcEventTracker.start()`/`stop()` are defined in Task 3 and used in Task 4's test setup. The MDC field names (`service`, `route`, `httpMethod`, `statusCode`, `outcome`, `durationMs`) are identical across Tasks 5, 6, 8's searches, and the `RESERVED_KEYS` collision test. The metric names Task 7 alerts on are the ones Tasks 1 and 2 produce, and Task 7 Step 1 re-confirms them against a live exposition before the alerts are written.

**Known soft spots, flagged rather than hidden.** Task 1 Step 3's `EXPECTED` list is written from expectation and explicitly subordinated to the probe's output — the probe is the authority, not the list. Task 4 Step 4's heap-pool summing may include non-heap pools on some JVMs; it carries its own detection step (implausible fractions above 1) and remedy (filter to `MemoryType.HEAP`). Task 7's `JvmGcTimeFractionHigh` depends on `jvm_gc_pause_seconds_sum`, which Micrometer registers only after the first collection — Task 7 Step 1 checks for it explicitly rather than assuming, and says what to do if it is absent.

**Dependencies confirmed present, not assumed:** `awaitility:4.2.2` and `spring-test:6.1.13` (test scope) and `logback-core:1.5.8` (compile scope, provides `ListAppender`), all verified with `mvn dependency:list`. No task adds a dependency.
