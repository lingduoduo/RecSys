# Observability Phase 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the Prometheus path — make all four services actually scraped, expose the Splunk appender's counters as metrics, add alerts that are unit-tested, and consolidate both observability phases into one document.

**Architecture:** No new observability system. Four services already instrument with Micrometer and expose Prometheus exposition; two of them are not collected. This closes the collection gap at all three layers that silently block it, bridges Phase 1's log-shipping counters into Micrometer, and adds a `PrometheusRule` whose expressions are executed by `promtool` in CI.

**Tech Stack:** Micrometer + `micrometer-registry-prometheus-simpleclient` (already present), Prometheus Operator CRDs (`ServiceMonitor`, `PrometheusRule`), `promtool` for rule unit tests, snakeyaml 2.2 (already on the classpath) for manifest assertions, JUnit 5 + AssertJ.

**Spec:** [2026-07-31-observability-phase-2-design.md](../specs/2026-07-31-observability-phase-2-design.md)

## Global Constraints

These bind **every** task.

- **Build with JDK 17.** Prefix every Maven command: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`. On newer JDKs a clean compile fails on two pre-existing unrelated files.
- **No new Maven dependencies.** Micrometer, snakeyaml, JUnit and AssertJ are all already present.
- **Never call slf4j from `com.recsys.infrastructure.observability`.** That package is the Splunk appender; an slf4j call from it recurses into itself. Task 2 adds a class in `com.recsys.metrics`, which is ordinary application code where slf4j is fine — but it must not push logging *into* the appender's package.
- **Every alert expression must use a metric name that exists in the source tree.** All eight in Task 4 were verified before this plan was written. If you add one, verify it the same way: `grep -rn '"<metric_name>"' src/main/java`.
- **Adding a manifest file is not enough.** `k8s/base/kustomization.yaml` has an explicit `resources:` list; a file that exists but is not listed renders as nothing, with no error.
- **Commit after every task.** End commit messages with:
  `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`
- Work on branch `feat/observability-phase-2`, cut from `main`.

## The finding that shapes Task 1

The spec said to verify ServiceMonitor selectors against real Service labels. Doing so found the gap is **three layers deep**, and every layer fails silently:

| Layer | model-serving | catalog-serving | online-serving | api-gateway |
|---|---|---|---|---|
| `ServiceMonitor` exists | yes | yes | **no** | **no** |
| Service has `metadata.labels.app` | yes | yes | **no** | **no** |
| NetworkPolicy allows Prometheus ingress | yes | yes | **no** | **no** |

`ServiceMonitor.spec.selector.matchLabels` matches **Service labels**, not the Service's pod selector. Adding only a `ServiceMonitor` would match nothing; adding labels too would still be blocked by NetworkPolicy. Task 1 fixes all three, and adds a test so the next person cannot half-fix it.

## File Structure

**Create — production / manifests:**

| File | Responsibility |
|---|---|
| `src/main/java/com/recsys/metrics/SplunkHecMetrics.java` | Register the Splunk appender's `Snapshot` as Micrometer gauges |
| `src/main/java/com/recsys/config/SplunkHecMetricsConfig.java` | Spring `@Configuration` that calls the above for the model service |
| `k8s/base/prometheus-rules.yaml` | `PrometheusRule` with the alert set |
| `k8s/base/prometheus-rules.test.yaml` | promtool unit tests for those alerts |
| `.github/workflows/prometheus-rules.yml` | Runs `promtool check rules` + `promtool test rules` |
| `docs/system_design/21_Observability.md` | The consolidated two-phase document |

**Create — tests:**

| File | Covers |
|---|---|
| `src/test/java/com/recsys/metrics/SplunkHecMetricsTest.java` | Task 2 |
| `src/test/java/com/recsys/metrics/ScrapeTargetManifestTest.java` | Task 1 |

**Modify:**

| File | Change |
|---|---|
| `k8s/base/online-serving.yaml`, `k8s/base/api-gateway.yaml` | Add `metadata.labels.app` to the Service; add Prometheus ingress to the NetworkPolicy |
| `k8s/base/network-policy.yaml` | Prometheus ingress rules for the two services |
| `k8s/base/servicemonitor.yaml` | Two new `ServiceMonitor`s |
| `k8s/base/kustomization.yaml` | Add `prometheus-rules.yaml` to `resources:` |
| `src/main/java/com/recsys/api/serving/RecSysServer.java`, `.../online/OnlinePredictionServer.java`, `.../gateway/MicroserviceGatewayServer.java` | Call `SplunkHecMetrics.register(...)` after building the registry |
| `pom.xml` | Add the two new tests to the `resilience` profile |
| `README.md`, `.claude/CLAUDE.md` | Index + env documentation |

---

### Task 1: Make all four services actually scrapeable

**Files:**
- Modify: `k8s/base/online-serving.yaml` (Service `metadata`)
- Modify: `k8s/base/api-gateway.yaml` (Service `metadata`)
- Modify: `k8s/base/network-policy.yaml` (Prometheus ingress for both)
- Modify: `k8s/base/servicemonitor.yaml` (two new monitors)
- Test: `src/test/java/com/recsys/metrics/ScrapeTargetManifestTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing for later tasks. Task 4's `RecsysTargetDown` alert assumes these targets exist.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/metrics/ScrapeTargetManifestTest.java`:

```java
package com.recsys.metrics;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A metric that is exposed but never collected is indistinguishable from a metric that was
 * never written. This asserts the three independent layers that must ALL line up before a
 * Prometheus scrape actually happens — each of which fails silently on its own:
 *
 * <ol>
 *   <li>a {@code ServiceMonitor} exists for the service;</li>
 *   <li>its {@code spec.selector.matchLabels} matches the <em>Service's own labels</em> —
 *       not the Service's pod selector, which is the easy thing to confuse it with;</li>
 *   <li>the service's {@code NetworkPolicy} admits ingress from Prometheus.</li>
 * </ol>
 *
 * <p>Before this test, online-serving and api-gateway failed all three: they published
 * Prometheus exposition that nothing in the cluster could have collected.
 */
class ScrapeTargetManifestTest {

    /** Every service that serves Prometheus exposition and therefore must be scraped. */
    private static final Set<String> EXPECTED_SCRAPE_TARGETS = Set.of(
            "recsys-model-serving",
            "recsys-catalog-serving",
            "recsys-online-serving",
            "recsys-api-gateway");

    private static final Path BASE = Path.of("k8s", "base");

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> documentsOf(String fileName) throws IOException {
        List<Map<String, Object>> docs = new ArrayList<>();
        try (InputStream in = Files.newInputStream(BASE.resolve(fileName))) {
            for (Object doc : new Yaml().loadAll(in)) {
                if (doc instanceof Map<?, ?> map) docs.add((Map<String, Object>) map);
            }
        }
        return docs;
    }

    private static List<Map<String, Object>> allBaseDocuments() throws IOException {
        List<Map<String, Object>> all = new ArrayList<>();
        try (var files = Files.list(BASE)) {
            List<Path> yamls = files.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
            for (Path p : yamls) all.addAll(documentsOf(p.getFileName().toString()));
        }
        return all;
    }

    private static List<Map<String, Object>> ofKind(
            List<Map<String, Object>> docs, String kind) {
        return docs.stream().filter(d -> kind.equals(d.get("kind"))).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapAt(Map<String, Object> doc, String... path) {
        Map<String, Object> cursor = doc;
        for (String key : path) {
            Object next = cursor == null ? null : cursor.get(key);
            cursor = next instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        }
        return cursor;
    }

    private static String nameOf(Map<String, Object> doc) {
        Map<String, Object> metadata = mapAt(doc, "metadata");
        return metadata == null ? null : String.valueOf(metadata.get("name"));
    }

    @Test
    void everyExposedServiceHasAServiceMonitor() throws IOException {
        List<Map<String, Object>> docs = allBaseDocuments();

        Set<String> monitored = ofKind(docs, "ServiceMonitor").stream()
                .map(ScrapeTargetManifestTest::nameOf)
                .collect(Collectors.toSet());

        assertThat(monitored)
                .as("every service exposing Prometheus metrics must have a ServiceMonitor, "
                        + "otherwise its metrics are published to nobody")
                .containsAll(EXPECTED_SCRAPE_TARGETS);
    }

    @Test
    void everyServiceMonitorSelectorMatchesRealServiceLabels() throws IOException {
        List<Map<String, Object>> docs = allBaseDocuments();

        List<Map<String, Object>> services = ofKind(docs, "Service");

        for (Map<String, Object> monitor : ofKind(docs, "ServiceMonitor")) {
            Map<String, Object> selector = mapAt(monitor, "spec", "selector", "matchLabels");
            assertThat(selector)
                    .as("ServiceMonitor %s has no spec.selector.matchLabels", nameOf(monitor))
                    .isNotNull();

            boolean matched = services.stream().anyMatch(service -> {
                Map<String, Object> labels = mapAt(service, "metadata", "labels");
                return labels != null && labels.entrySet().containsAll(selector.entrySet());
            });

            assertThat(matched)
                    .as("ServiceMonitor %s selects %s, but no Service carries those "
                            + "metadata.labels. Note the selector matches the SERVICE's labels, "
                            + "not the Service's pod selector — a monitor that matches nothing "
                            + "collects nothing, silently.", nameOf(monitor), selector)
                    .isTrue();
        }
    }

    @Test
    void everyScrapedServiceAdmitsPrometheusIngress() throws IOException {
        List<Map<String, Object>> policies = ofKind(allBaseDocuments(), "NetworkPolicy");

        for (String target : EXPECTED_SCRAPE_TARGETS) {
            Map<String, Object> policy = policies.stream()
                    .filter(p -> target.equals(nameOf(p)))
                    .findFirst()
                    .orElse(null);
            if (policy == null) {
                continue; // No policy means no restriction; nothing to assert.
            }
            String rendered = new Yaml().dump(policy);
            assertThat(rendered)
                    .as("NetworkPolicy %s restricts ingress but does not admit Prometheus, so "
                            + "its ServiceMonitor would be created and still scrape nothing",
                            target)
                    .contains("app.kubernetes.io/name: prometheus");
        }
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=ScrapeTargetManifestTest`
Expected: **FAIL** — `everyExposedServiceHasAServiceMonitor` reports the two missing monitors, and `everyScrapedServiceAdmitsPrometheusIngress` reports the two missing ingress rules. This failure is the bug being fixed; read it before continuing.

- [ ] **Step 3: Add `metadata.labels` to the two Services**

In `k8s/base/online-serving.yaml`, the Service's `metadata` currently has only `name` and `namespace`. Add labels:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: recsys-online-serving
  namespace: recsys
  # ServiceMonitor.spec.selector matches THESE labels — not spec.selector below, which
  # selects pods. Without them the ServiceMonitor matches nothing and scrapes nothing.
  labels:
    app: recsys-online-serving
spec:
  type: ClusterIP
  selector:
    app: recsys-online-serving
```

In `k8s/base/api-gateway.yaml`, the Service's `metadata` has `name`, `namespace` and a block of `annotations`. Add a `labels:` block alongside them (order within `metadata` does not matter):

```yaml
  labels:
    app: recsys-api-gateway
```

- [ ] **Step 4: Add Prometheus ingress to both NetworkPolicies**

In `k8s/base/network-policy.yaml`, both `recsys-online-serving` and `recsys-api-gateway` policies need an ingress rule matching the one `recsys-catalog-serving` already has. For online-serving, add to its `ingress:` list:

```yaml
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: monitoring
          podSelector:
            matchLabels:
              app.kubernetes.io/name: prometheus
      ports:
        - port: 7010
```

For the gateway, the same block but `- port: 8010`.

Match the surrounding indentation exactly, and keep the existing rules — you are appending an alternative source, not replacing one.

- [ ] **Step 5: Add the two ServiceMonitors**

Append to `k8s/base/servicemonitor.yaml`, following the shape of the two already there:

```yaml
---
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: recsys-online-serving
  namespace: recsys
  labels:
    app: recsys-online-serving
    # Must match the Prometheus CR's serviceMonitorSelector; adjust for your cluster.
    # kube-prometheus-stack default: release: <helm-release-name>
    release: kube-prometheus-stack
spec:
  namespaceSelector:
    matchNames:
      - recsys
  selector:
    matchLabels:
      app: recsys-online-serving
  endpoints:
    - port: http
      path: /metrics
      interval: 15s
      scrapeTimeout: 10s
      scheme: http
---
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: recsys-api-gateway
  namespace: recsys
  labels:
    app: recsys-api-gateway
    release: kube-prometheus-stack
spec:
  namespaceSelector:
    matchNames:
      - recsys
  selector:
    matchLabels:
      app: recsys-api-gateway
  endpoints:
    - port: http
      path: /metrics
      interval: 15s
      scrapeTimeout: 10s
      scheme: http
```

`port: http` is the **Service port name**, which both Services already define. Prometheus resolves it to the pod's `targetPort`, so the gateway is scraped on 8010 directly rather than through its LoadBalancer.

- [ ] **Step 6: Run the test and confirm it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=ScrapeTargetManifestTest`
Expected: PASS, 3 tests.

- [ ] **Step 7: Verify the manifests render**

```bash
kubectl kustomize k8s/base > /dev/null && \
kubectl kustomize k8s/eks > /dev/null && \
kubectl kustomize k8s/eks-us-west-2 > /dev/null && echo "all overlays build"
kubectl kustomize k8s/base | grep -c "kind: ServiceMonitor"
```
Expected: all three build; the count is **4**.

- [ ] **Step 8: Commit**

```bash
git add k8s/base/online-serving.yaml k8s/base/api-gateway.yaml \
        k8s/base/network-policy.yaml k8s/base/servicemonitor.yaml \
        src/test/java/com/recsys/metrics/ScrapeTargetManifestTest.java
git commit -m "fix: actually collect online-serving and gateway metrics

Both services have exposed Prometheus metrics all along and nothing has ever
scraped them. The gap was three layers deep, each failing silently: no
ServiceMonitor, no metadata.labels on the Service for a selector to match,
and no NetworkPolicy ingress from Prometheus. Fixing only the first would
have changed nothing.

ScrapeTargetManifestTest now asserts all three layers line up for every
service that exposes metrics.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `SplunkHecMetrics` — bridge the appender's counters into Micrometer

**Files:**
- Create: `src/main/java/com/recsys/metrics/SplunkHecMetrics.java`
- Test: `src/test/java/com/recsys/metrics/SplunkHecMetricsTest.java`

**Interfaces:**
- Consumes: `SplunkHecAppender.snapshot()` returning `record Snapshot(int queued, long sent, long dropped, long failed, long indeterminate)`. The appender is `public final class com.recsys.infrastructure.observability.SplunkHecAppender`; `snapshot()` is `public`.
- Produces: `public static void SplunkHecMetrics.register(MeterRegistry registry)` — used by Task 3 from all four services.

**Why this exists:** Phase 1 computes `sent`/`dropped`/`failed`/`indeterminate` and exposes them to nothing outside its own package. Log-shipping loss is currently visible only as `WARN in ch.qos.logback...` on stdout — where nobody looks when asking "are we losing logs?".

**Two things that are easy to get wrong:**

1. **Gauges, not counters.** The appender already holds `AtomicLong`s. Registering a Micrometer `Counter` would need a second increment site and would double-count. A gauge reading a monotonic source is correct, and `rate()`/`increase()` work over it.
2. **The appender is built by Logback before any registry exists.** That is why Phase 1 skipped Micrometer. So this looks the appender up from the root logger by name rather than being handed one.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/metrics/SplunkHecMetricsTest.java`:

```java
package com.recsys.metrics;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.Appender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Iterator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SplunkHecMetricsTest {

    @Test
    void registersNothingWhenNoSplunkAppenderIsAttached() {
        MeterRegistry registry = new SimpleMeterRegistry();
        LoggerContext bare = new LoggerContext(); // no appenders at all

        assertThatCode(() -> SplunkHecMetrics.register(registry, bare))
                .as("a JVM whose Logback config has no SPLUNK appender must not blow up")
                .doesNotThrowAnyException();

        assertThat(registry.find("splunk_hec_events_sent_total").gauge()).isNull();
    }

    @Test
    void registersGaugesWhenTheAppenderIsPresent() throws Exception {
        MeterRegistry registry = new SimpleMeterRegistry();
        LoggerContext context = configuredContext();
        try {
            SplunkHecMetrics.register(registry, context);

            assertThat(registry.find("splunk_hec_events_sent_total").gauge()).isNotNull();
            assertThat(registry.find("splunk_hec_events_dropped_total").gauge()).isNotNull();
            assertThat(registry.find("splunk_hec_events_failed_total").gauge()).isNotNull();
            assertThat(registry.find("splunk_hec_events_indeterminate_total").gauge()).isNotNull();
            assertThat(registry.find("splunk_hec_queue_depth").gauge()).isNotNull();
        } finally {
            context.stop();
        }
    }

    @Test
    void gaugesReportTheAppendersSnapshot() throws Exception {
        MeterRegistry registry = new SimpleMeterRegistry();
        LoggerContext context = configuredContext();
        try {
            SplunkHecMetrics.register(registry, context);

            // With no SPLUNK_HEC_TOKEN the appender is inert, so every counter is zero —
            // which is exactly what a healthy idle service should report.
            assertThat(registry.get("splunk_hec_events_sent_total").gauge().value()).isZero();
            assertThat(registry.get("splunk_hec_events_dropped_total").gauge().value()).isZero();
            assertThat(registry.get("splunk_hec_queue_depth").gauge().value()).isZero();
        } finally {
            context.stop();
        }
    }

    @Test
    void theRealLogbackConfigStillDeclaresAnAppenderNamedSplunk() throws Exception {
        // register() finds the appender by the name "SPLUNK". If someone renames it in
        // logback-common.xml, the lookup silently returns null and every metric vanishes.
        // This turns that rename into a test failure instead.
        LoggerContext context = configuredContext();
        try {
            Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
            boolean found = false;
            for (Iterator<Appender<ch.qos.logback.classic.spi.ILoggingEvent>> it =
                    root.iteratorForAppenders(); it.hasNext(); ) {
                if ("SPLUNK".equals(it.next().getName())) found = true;
            }
            assertThat(found)
                    .as("logback-common.xml must keep an appender named SPLUNK, or "
                            + "SplunkHecMetrics.register finds nothing and reports no metrics")
                    .isTrue();
        } finally {
            context.stop();
        }
    }

    /** Configures a context from the real logback.xml, as the running services do. */
    private static LoggerContext configuredContext() throws Exception {
        LoggerContext context = new LoggerContext();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(new File("src/main/resources/logback.xml"));
        return context;
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=SplunkHecMetricsTest`
Expected: FAIL — compilation error, `SplunkHecMetrics` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/recsys/metrics/SplunkHecMetrics.java`:

```java
package com.recsys.metrics;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import com.recsys.infrastructure.observability.SplunkHecAppender;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.LoggerFactory;

import java.util.Iterator;

/**
 * Publishes {@link SplunkHecAppender}'s delivery counters as Micrometer gauges.
 *
 * <p>Phase 1 shipped those counters and exposed them to nothing: log-shipping loss shows up
 * only as {@code WARN in ch.qos.logback...} lines on stdout, which is not where anyone looks
 * when asking "are we losing logs?". This is the bridge that makes them alertable.
 *
 * <p>Registered as <strong>gauges over a monotonic source</strong> rather than counters. The
 * appender already owns {@code AtomicLong}s; a Micrometer {@code Counter} would need a second
 * increment site and would double-count. Prometheus {@code rate()} and {@code increase()}
 * work correctly over a monotonically increasing gauge.
 *
 * <p>The appender is constructed by Logback before any {@link MeterRegistry} exists, which is
 * why this looks it up from the root logger instead of being handed one. It is a no-op when
 * the appender is absent or the config declares no {@code SPLUNK} appender.
 */
public final class SplunkHecMetrics {

    /** Must match the appender's name in {@code logback-common.xml}. */
    private static final String APPENDER_NAME = "SPLUNK";

    private SplunkHecMetrics() {}

    /** Registers against the JVM's active Logback context. */
    public static void register(MeterRegistry registry) {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            register(registry, context);
        }
    }

    static void register(MeterRegistry registry, LoggerContext context) {
        SplunkHecAppender appender = findAppender(context);
        if (appender == null) {
            return; // No Splunk appender configured — nothing to publish.
        }

        gauge(registry, "splunk_hec_events_sent_total", appender,
                a -> a.snapshot().sent(),
                "Log events confirmed accepted by Splunk (2xx).");
        gauge(registry, "splunk_hec_events_dropped_total", appender,
                a -> a.snapshot().dropped(),
                "Log events discarded because the bounded queue was full.");
        gauge(registry, "splunk_hec_events_failed_total", appender,
                a -> a.snapshot().failed(),
                "Log events Splunk definitively refused, or that never left the host.");
        gauge(registry, "splunk_hec_events_indeterminate_total", appender,
                a -> a.snapshot().indeterminate(),
                "Log events sent but never acknowledged: possibly delivered, possibly lost.");
        gauge(registry, "splunk_hec_queue_depth", appender,
                a -> a.snapshot().queued(),
                "Log events currently waiting in the appender's bounded queue.");
    }

    private static void gauge(MeterRegistry registry, String name, SplunkHecAppender appender,
                              java.util.function.ToDoubleFunction<SplunkHecAppender> value,
                              String description) {
        Gauge.builder(name, appender, value)
                .description(description)
                .register(registry);
    }

    private static SplunkHecAppender findAppender(LoggerContext context) {
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        for (Iterator<Appender<ILoggingEvent>> it = root.iteratorForAppenders(); it.hasNext(); ) {
            Appender<ILoggingEvent> appender = it.next();
            if (APPENDER_NAME.equals(appender.getName()) && appender instanceof SplunkHecAppender s) {
                return s;
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=SplunkHecMetricsTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/metrics/SplunkHecMetrics.java \
        src/test/java/com/recsys/metrics/SplunkHecMetricsTest.java
git commit -m "feat: publish Splunk appender delivery counters as metrics

Phase 1 computes sent/dropped/failed/indeterminate and exposes them to
nothing outside its own package, so log-shipping loss is visible only as
Logback status lines on stdout. These gauges make it alertable.

Gauges over a monotonic source rather than counters: the appender already
owns the AtomicLongs, so a Counter would need a second increment site and
double-count. rate()/increase() work fine over a monotonic gauge.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Wire the bridge into all four services

**Files:**
- Modify: `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java` (near line 128)
- Modify: `src/main/java/com/recsys/api/online/OnlinePredictionServer.java` (near line 225)
- Modify: `src/main/java/com/recsys/api/serving/RecSysServer.java` (where it builds its registry)
- Create: `src/main/java/com/recsys/config/SplunkHecMetricsConfig.java`

**Interfaces:**
- Consumes: `SplunkHecMetrics.register(MeterRegistry)` from Task 2.
- Produces: nothing for later tasks.

- [ ] **Step 1: Wire the three Armeria mains**

Each builds a `PrometheusMeterRegistry` and serves `/metrics` from it. The gateway is the model — `MicroserviceGatewayServer.java:128-129`:

```java
PrometheusMeterRegistry meterRegistry = PrometheusMeterRegistries.defaultRegistry();
sb.service("/metrics", PrometheusExpositionService.of(meterRegistry.getPrometheusRegistry()));
```

Immediately after the registry is created in each of the three files, add:

```java
// The Splunk appender was built by Logback long before this registry existed, so it
// cannot register itself. No-op when SPLUNK_HEC_TOKEN is unset.
SplunkHecMetrics.register(meterRegistry);
```

Use whatever the local registry variable is called in each file — read the surrounding lines rather than assuming it is `meterRegistry` in all three. Add the import `com.recsys.metrics.SplunkHecMetrics`.

- [ ] **Step 2: Add the Spring configuration**

The model service has **no** metrics configuration class; it uses Actuator's auto-configured registry. Create `src/main/java/com/recsys/config/SplunkHecMetricsConfig.java`:

```java
package com.recsys.config;

import com.recsys.metrics.SplunkHecMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the Splunk appender's delivery counters against Spring Boot's auto-configured
 * {@link MeterRegistry}, which is what backs {@code /actuator/prometheus} on the model
 * service.
 *
 * <p>Its own class rather than a method on an existing config: appender wiring has nothing
 * to do with the inference pipeline that {@code ModelRecommendationPipelineConfig} owns.
 */
@Configuration
public class SplunkHecMetricsConfig implements InitializingBean {

    private final MeterRegistry registry;

    public SplunkHecMetricsConfig(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void afterPropertiesSet() {
        SplunkHecMetrics.register(registry);
    }
}
```

- [ ] **Step 3: Verify everything still compiles and passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test`
Expected: PASS. The registration is a no-op without a token, so no test behaviour changes.

- [ ] **Step 4: Confirm the metrics actually appear on a running service**

```bash
RECOMMENDATION_CURSOR_SIGNING_KEY="$(openssl rand -hex 32)" \
  SPLUNK_HEC_TOKEN=metrics-wiring-check \
  sh scripts/run-microservices-local.sh
```

Wait for startup, then in another shell:

```bash
curl -s localhost:8010/metrics | grep splunk_hec
```

Expected: the five `splunk_hec_*` series. Stop the services afterwards.

If nothing appears, the appender is disabled — check the token reached the JVM. Do **not** "fix" it by removing the token check.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/api/ src/main/java/com/recsys/config/SplunkHecMetricsConfig.java
git commit -m "feat: register Splunk delivery metrics in all four services

Three Armeria mains register after building their Prometheus registry; the
Spring model service gets a small dedicated @Configuration, since it has no
metrics config class and appender wiring does not belong on the inference
pipeline's config.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: The alert rules

**Files:**
- Create: `k8s/base/prometheus-rules.yaml`
- Modify: `k8s/base/kustomization.yaml`

**Interfaces:**
- Consumes: the metrics from Tasks 1-3.
- Produces: alert names used by Task 5's tests — `RecsysTargetDown`, `SplunkHecDroppingEvents`, `SplunkHecIndeterminateDelivery`, `GatewayRegistryStale`, `OnlineServingShedding`, `RedisCacheUnavailable`, `RedisReplicaLagHigh`, `OutboxBacklogGrowing`.

- [ ] **Step 1: Create the rule file**

Create `k8s/base/prometheus-rules.yaml`:

```yaml
# Alerts for the recsys services.
#
# Every expression here uses a metric name that exists in src/main/java — verified with
# `grep -rn '"<name>"' src/main/java` before this file was written. An alert on a metric
# that is never emitted is worse than no alert: it looks like coverage and can never fire.
#
# Thresholds are deliberately loose starting points. An alert that cries wolf gets muted,
# and a muted alert is worse than none. Tighten them with production data, not by guessing.
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: recsys-alerts
  namespace: recsys
  labels:
    app: recsys
    release: kube-prometheus-stack
spec:
  groups:
    - name: recsys.availability
      rules:
        # The meta-alert. This is the one that would have caught online-serving and the
        # gateway publishing metrics nobody collected. Note its blind spot: `up` only
        # exists for targets Prometheus already knows about, so a ServiceMonitor that was
        # never created produces no series to be zero. ScrapeTargetManifestTest covers
        # that side; this covers a target that existed and stopped answering.
        - alert: RecsysTargetDown
          expr: up{namespace="recsys"} == 0
          for: 5m
          labels:
            severity: critical
          annotations:
            summary: "{{ $labels.job }} has not been scraped for 5 minutes"
            description: >-
              Prometheus cannot scrape {{ $labels.job }} ({{ $labels.instance }}). Either
              the service is down or its metrics endpoint stopped answering. Its dashboards
              and every other alert in this file are blind for that service until resolved.

    - name: recsys.logging
      rules:
        - alert: SplunkHecDroppingEvents
          expr: increase(splunk_hec_events_dropped_total[10m]) > 0
          for: 10m
          labels:
            severity: warning
          annotations:
            summary: "Splunk log shipping is dropping events on {{ $labels.job }}"
            description: >-
              The appender's bounded queue filled and events were discarded. Log delivery is
              at-most-once by design, so these are gone from Splunk — stdout remains the
              authoritative copy. Consider raising SPLUNK_HEC_QUEUE_CAPACITY, or investigate
              why the drain thread fell behind.

        - alert: SplunkHecIndeterminateDelivery
          expr: increase(splunk_hec_events_indeterminate_total[10m]) > 0
          for: 10m
          labels:
            severity: warning
          annotations:
            summary: "Splunk delivery outcome unknown on {{ $labels.job }}"
            description: >-
              Batches were sent but never acknowledged, so Splunk may or may not hold them.
              Unlike dropped events these carry duplicate risk as well as loss risk. Usually
              a read timeout or a shutdown that outran its budget.

    - name: recsys.serving
      rules:
        - alert: GatewayRegistryStale
          expr: gateway_registry_snapshot_age_seconds > 120
          for: 5m
          labels:
            severity: warning
          annotations:
            summary: "Gateway service-registry snapshot is stale"
            description: >-
              The gateway is resolving upstreams from a registry snapshot older than two
              minutes, so it may route to addresses that have moved. Check Redis
              availability and gateway_registry_refresh_failures_total.

        - alert: OnlineServingShedding
          expr: online_serving_rejected_rate > 0.1
          for: 10m
          labels:
            severity: warning
          annotations:
            summary: "Online serving is shedding more than 10% of requests"
            description: >-
              Admission control has been rejecting a sustained share of traffic. Either
              genuine overload or too tight a concurrency limit — check
              recsys_load_shedder_utilization and the pod's CPU.

    - name: recsys.data
      rules:
        - alert: RedisCacheUnavailable
          expr: redis_cache_available == 0
          for: 5m
          labels:
            severity: critical
          annotations:
            summary: "Redis is unreachable from {{ $labels.job }}"
            description: >-
              The cache probe cannot reach Redis. Serving degrades to stale or empty results
              depending on the path. Check the Redis pods and the NetworkPolicy egress rules.

        - alert: RedisReplicaLagHigh
          expr: redis_replica_lag_seconds > 10
          for: 10m
          labels:
            severity: warning
          annotations:
            summary: "Redis replica lag above 10s"
            description: >-
              AZ-aware replica reads are returning data more than ten seconds old. Reads
              routed to a replica may contradict a write that already succeeded.

        - alert: OutboxBacklogGrowing
          # delta(), not increase(): this is a true gauge that rises AND falls, and increase()
          # would read every drain as a counter reset. increase() is correct for the
          # splunk_hec_* gauges above only because those are monotonic.
          expr: outbox_pending_events > 1000 and delta(outbox_pending_events[15m]) > 0
          for: 15m
          labels:
            severity: warning
          annotations:
            summary: "Transactional outbox backlog is growing"
            description: >-
              More than 1000 events are pending and the backlog is still increasing, so the
              relay is not keeping up. Eventual consistency windows widen until it drains.
              Check outbox_delivery_failures_total and outbox_delivery_lag_seconds.
```

- [ ] **Step 2: Add it to the kustomization resource list**

In `k8s/base/kustomization.yaml`, add to `resources:`, after `servicemonitor.yaml`:

```yaml
  - prometheus-rules.yaml
```

**This is not optional.** The list is explicit — a manifest file that exists but is not listed renders as nothing, with no error.

- [ ] **Step 3: Verify it renders**

```bash
kubectl kustomize k8s/base | grep -c "kind: PrometheusRule"
kubectl kustomize k8s/eks > /dev/null && kubectl kustomize k8s/eks-us-west-2 > /dev/null && echo "overlays build"
```
Expected: the count is **1**, and both overlays build. If the count is 0 you skipped Step 2.

- [ ] **Step 4: Commit**

```bash
git add k8s/base/prometheus-rules.yaml k8s/base/kustomization.yaml
git commit -m "feat: add alert rules for serving, data and log shipping

Eight alerts, every expression using a metric name verified present in
src/main/java. RecsysTargetDown is the meta-alert: it catches a service that
stops being scraped, which is this repo's recurring failure mode.

Thresholds are deliberately loose. An alert that cries wolf gets muted, and
a muted alert is worse than none.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Prove the alerts fire

**Files:**
- Create: `k8s/base/prometheus-rules.test.yaml`
- Create: `.github/workflows/prometheus-rules.yml`

**Interfaces:**
- Consumes: the alert names from Task 4.
- Produces: nothing.

**Why this task exists:** two artifacts shipped this week could not be verified where they were written, and one CI workflow was dead for five days because a pin was wrong. Alert expressions are executable — `promtool` runs them against synthetic series — so there is no excuse for shipping ones nobody has watched fire.

- [ ] **Step 1: Write the rule tests**

Create `k8s/base/prometheus-rules.test.yaml`:

```yaml
# promtool unit tests for prometheus-rules.yaml.
#
# Run: promtool test rules k8s/base/prometheus-rules.test.yaml
#
# Every alert gets a case that MUST fire and a near-miss that MUST NOT. The near-miss
# matters more than it looks: an expression that fires on everything passes a
# fire-only test suite while being useless.
rule_files:
  - prometheus-rules.yaml

evaluation_interval: 1m

tests:
  # --- RecsysTargetDown ---
  - interval: 1m
    input_series:
      - series: 'up{namespace="recsys", job="recsys-api-gateway", instance="10.0.0.1:8010"}'
        values: '1x5 0x10'
    alert_rule_test:
      - eval_time: 4m
        alertname: RecsysTargetDown
        exp_alerts: []          # still up at 4m
      - eval_time: 12m
        alertname: RecsysTargetDown
        exp_alerts:
          - exp_labels:
              severity: critical
              namespace: recsys
              job: recsys-api-gateway
              instance: 10.0.0.1:8010
            exp_annotations:
              summary: "recsys-api-gateway has not been scraped for 5 minutes"
              description: >-
                Prometheus cannot scrape recsys-api-gateway (10.0.0.1:8010). Either the
                service is down or its metrics endpoint stopped answering. Its dashboards
                and every other alert in this file are blind for that service until resolved.

  # --- SplunkHecDroppingEvents ---
  - interval: 1m
    input_series:
      - series: 'splunk_hec_events_dropped_total{job="recsys-api-gateway"}'
        values: '0+5x30'        # rising: events being dropped
      - series: 'splunk_hec_events_dropped_total{job="recsys-online-serving"}'
        values: '7+0x30'        # flat: some dropped historically, none now
    alert_rule_test:
      - eval_time: 20m
        alertname: SplunkHecDroppingEvents
        exp_alerts:
          - exp_labels:
              severity: warning
              job: recsys-api-gateway
            exp_annotations:
              summary: "Splunk log shipping is dropping events on recsys-api-gateway"
              description: >-
                The appender's bounded queue filled and events were discarded. Log delivery
                is at-most-once by design, so these are gone from Splunk — stdout remains
                the authoritative copy. Consider raising SPLUNK_HEC_QUEUE_CAPACITY, or
                investigate why the drain thread fell behind.

  # --- SplunkHecIndeterminateDelivery ---
  - interval: 1m
    input_series:
      - series: 'splunk_hec_events_indeterminate_total{job="recsys-online-serving"}'
        values: '0+3x30'
    alert_rule_test:
      - eval_time: 20m
        alertname: SplunkHecIndeterminateDelivery
        exp_alerts:
          - exp_labels:
              severity: warning
              job: recsys-online-serving
            exp_annotations:
              summary: "Splunk delivery outcome unknown on recsys-online-serving"
              description: >-
                Batches were sent but never acknowledged, so Splunk may or may not hold
                them. Unlike dropped events these carry duplicate risk as well as loss risk.
                Usually a read timeout or a shutdown that outran its budget.

  # --- GatewayRegistryStale: fires above 120s, not below ---
  - interval: 1m
    input_series:
      - series: 'gateway_registry_snapshot_age_seconds{job="recsys-api-gateway"}'
        values: '200x20'
      - series: 'gateway_registry_snapshot_age_seconds{job="recsys-catalog-serving"}'
        values: '110x20'        # near miss: under the threshold
    alert_rule_test:
      - eval_time: 10m
        alertname: GatewayRegistryStale
        exp_alerts:
          - exp_labels:
              severity: warning
              job: recsys-api-gateway
            exp_annotations:
              summary: "Gateway service-registry snapshot is stale"
              description: >-
                The gateway is resolving upstreams from a registry snapshot older than two
                minutes, so it may route to addresses that have moved. Check Redis
                availability and gateway_registry_refresh_failures_total.

  # --- OnlineServingShedding: fires above 0.1, not at 0.05 ---
  - interval: 1m
    input_series:
      - series: 'online_serving_rejected_rate{job="recsys-online-serving"}'
        values: '0.3x20'
      - series: 'online_serving_rejected_rate{job="recsys-catalog-serving"}'
        values: '0.05x20'
    alert_rule_test:
      - eval_time: 15m
        alertname: OnlineServingShedding
        exp_alerts:
          - exp_labels:
              severity: warning
              job: recsys-online-serving
            exp_annotations:
              summary: "Online serving is shedding more than 10% of requests"
              description: >-
                Admission control has been rejecting a sustained share of traffic. Either
                genuine overload or too tight a concurrency limit — check
                recsys_load_shedder_utilization and the pod's CPU.

  # --- RedisCacheUnavailable ---
  - interval: 1m
    input_series:
      - series: 'redis_cache_available{job="recsys-catalog-serving"}'
        values: '1x5 0x10'
    alert_rule_test:
      - eval_time: 4m
        alertname: RedisCacheUnavailable
        exp_alerts: []
      - eval_time: 12m
        alertname: RedisCacheUnavailable
        exp_alerts:
          - exp_labels:
              severity: critical
              job: recsys-catalog-serving
            exp_annotations:
              summary: "Redis is unreachable from recsys-catalog-serving"
              description: >-
                The cache probe cannot reach Redis. Serving degrades to stale or empty
                results depending on the path. Check the Redis pods and the NetworkPolicy
                egress rules.

  # --- RedisReplicaLagHigh ---
  - interval: 1m
    input_series:
      - series: 'redis_replica_lag_seconds{job="recsys-catalog-serving"}'
        values: '25x20'
      - series: 'redis_replica_lag_seconds{job="recsys-online-serving"}'
        values: '2x20'
    alert_rule_test:
      - eval_time: 15m
        alertname: RedisReplicaLagHigh
        exp_alerts:
          - exp_labels:
              severity: warning
              job: recsys-catalog-serving
            exp_annotations:
              summary: "Redis replica lag above 10s"
              description: >-
                AZ-aware replica reads are returning data more than ten seconds old. Reads
                routed to a replica may contradict a write that already succeeded.

  # --- OutboxBacklogGrowing: needs BOTH size and growth ---
  - interval: 1m
    input_series:
      - series: 'outbox_pending_events{job="recsys-model-serving"}'
        values: '1500+50x30'    # large AND growing -> fires
      - series: 'outbox_pending_events{job="recsys-catalog-serving"}'
        values: '2000+0x30'     # large but FLAT -> must not fire
    alert_rule_test:
      - eval_time: 20m
        alertname: OutboxBacklogGrowing
        exp_alerts:
          - exp_labels:
              severity: warning
              job: recsys-model-serving
            exp_annotations:
              summary: "Transactional outbox backlog is growing"
              description: >-
                More than 1000 events are pending and the backlog is still increasing, so
                the relay is not keeping up. Eventual consistency windows widen until it
                drains. Check outbox_delivery_failures_total and
                outbox_delivery_lag_seconds.
```

**On annotation matching:** promtool compares rendered annotations exactly. YAML block scalars (`>-`) fold newlines into single spaces, so the expected text must fold to the same string as the rule's. If a test fails only on annotation text, diff the two strings before changing either — it is usually whitespace, not logic. If matching proves brittle, dropping `exp_annotations` and asserting only `exp_labels` is acceptable; the labels are what routing depends on.

- [ ] **Step 2: Run the tests locally and confirm they pass**

Install promtool if needed (macOS: `brew install prometheus`), then:

```bash
promtool check rules k8s/base/prometheus-rules.yaml
promtool test rules k8s/base/prometheus-rules.test.yaml
```

Expected: `SUCCESS` from both.

`promtool check rules` on the `PrometheusRule` CRD may object to the Kubernetes envelope (`apiVersion`/`kind`/`metadata`), since it expects a bare Prometheus rule file. If it does, extract `spec` to a temp file for the check:

```bash
yq '.spec' k8s/base/prometheus-rules.yaml > /tmp/rules.yaml && promtool check rules /tmp/rules.yaml
```

Whichever form works, use the same one in the workflow — and say in your report which it was.

- [ ] **Step 3: Deliberately break an alert and confirm the tests catch it**

Change `RecsysTargetDown`'s expression to `up{namespace="recsys"} == 1` and re-run `promtool test rules`. It must FAIL. Revert.

**Do not skip this.** A test suite that passes against a broken rule is exactly the failure this task exists to prevent, and it has already happened twice in this repo.

- [ ] **Step 4: Add the CI workflow**

Create `.github/workflows/prometheus-rules.yml`:

```yaml
# Executes the alert rules against synthetic series.
#
# Alert expressions are the one part of observability that can be unit-tested, and an
# untested alert is indistinguishable from a working one until the incident it was
# supposed to catch. Path-filtered so it costs nothing on unrelated PRs.
name: prometheus-rules

on:
  pull_request:
    paths:
      - "k8s/base/prometheus-rules.yaml"
      - "k8s/base/prometheus-rules.test.yaml"
      - ".github/workflows/prometheus-rules.yml"
  push:
    branches: [main]
    paths:
      - "k8s/base/prometheus-rules.yaml"
      - "k8s/base/prometheus-rules.test.yaml"
  workflow_dispatch:

permissions:
  contents: read

env:
  # Pinned deliberately. An unpinned `latest` makes the check that validates the alerts
  # itself unreproducible — and a pin copied without verifying is how the setup-java SHA
  # broke the weekly workflow for five days. Resolve this from a real release before
  # changing it: https://github.com/prometheus/prometheus/releases
  PROMETHEUS_VERSION: "3.1.0"

jobs:
  rules:
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4.2.2

      - name: Install promtool
        run: |
          set -euo pipefail
          url="https://github.com/prometheus/prometheus/releases/download/v${PROMETHEUS_VERSION}/prometheus-${PROMETHEUS_VERSION}.linux-amd64.tar.gz"
          curl -fsSL "$url" -o /tmp/prometheus.tar.gz
          tar -xzf /tmp/prometheus.tar.gz -C /tmp
          sudo mv "/tmp/prometheus-${PROMETHEUS_VERSION}.linux-amd64/promtool" /usr/local/bin/
          promtool --version

      - name: Check rule syntax
        run: promtool check rules k8s/base/prometheus-rules.yaml

      - name: Run rule unit tests
        working-directory: k8s/base
        run: promtool test rules prometheus-rules.test.yaml
```

`working-directory: k8s/base` matters: `rule_files:` in the test file is resolved relative to the test file's directory.

If Step 2 showed that `promtool check rules` cannot read the CRD envelope, replace the "Check rule syntax" step with the `yq` extraction form — `yq` is preinstalled on `ubuntu-latest`.

- [ ] **Step 5: Commit and confirm the workflow passes**

```bash
git add k8s/base/prometheus-rules.test.yaml .github/workflows/prometheus-rules.yml
git commit -m "test: execute the alert rules with promtool in CI

Alert expressions are executable, so there is no excuse for shipping ones
nobody has watched fire. Each alert has a case that must fire and a near-miss
that must not — a rule that fires on everything passes a fire-only suite
while being useless.

promtool is pinned by version: an unpinned latest makes the check that
validates the alerts unreproducible, and a pin copied without verifying is
how the setup-java SHA broke the weekly workflow for five days.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
git push
```

Then watch the run: `gh run list --workflow prometheus-rules.yml --limit 1`. It must be green before moving on. Report the actual result.

---

### Task 6: The consolidated document

**Files:**
- Create: `docs/system_design/21_Observability.md`
- Modify: `README.md` (documentation map)
- Modify: `.claude/CLAUDE.md`
- Modify: `pom.xml` (`resilience` profile)

**Interfaces:**
- Consumes: everything from Tasks 1-5.
- Produces: nothing.

- [ ] **Step 1: Add the new tests to the PR gate**

In `pom.xml`, in the `resilience` profile's `<includes>`, after the existing `**/metrics/RedisCacheMetricsTest.java` line:

```xml
                <include>**/metrics/SplunkHecMetricsTest.java</include>
                <include>**/metrics/ScrapeTargetManifestTest.java</include>
```

Both are pure unit-level — no Redis, no Docker, no timing — which is that profile's documented bar.

- [ ] **Step 2: Write the investigation**

Create `docs/system_design/21_Observability.md`. Read `docs/system_design/02_Caching.md` and `18_Fault_Tolerance.md` first and match their voice: direct, specific, willing to name what does not work.

Cover, in this order:

1. **The two questions.** Splunk answers "what happened in this request?" — a specific `traceId`, an exception, high cardinality, per-event, at-most-once. Prometheus answers "is the system healthy?" — rates, saturation, latency, low cardinality, scraped. Neither is derived from the other: no metric is computed by parsing logs, no log line exists to feed a metric. Alerting reads Prometheus; investigation reads Splunk.
2. **Phase 1: logs.** What ships, the at-most-once contract, and the five `splunk_hec_*` gauges that now bridge it into Phase 2. Link `docs/runbooks/splunk-hec-logging.md` rather than duplicating it.
3. **Phase 2: metrics.** A table of all four services: what they expose, on which path and port, and which `ServiceMonitor` collects them. Then the metric inventory grouped by subsystem — serving, gateway, Redis, outbox, inference, load shedding, Splunk shipping — **naming the source file where each is registered**, so a reader can verify rather than trust. Note the two naming conventions: Prometheus-native snake_case (`gateway_registry_*`, `outbox_*`) and dotted Micrometer names (`recsys.inference.*`) that the registry converts to `recsys_inference_*` on exposition.
4. **Alerts.** Each of the eight: what it means, likely cause, first response. An alert without a documented response is a pager that teaches people to ignore it.
5. **What is deliberately absent.** No Grafana (nothing here to render or validate dashboards, and they drift fastest). No log-derived metrics. **No tracing backend** — `traceId` exists in MDC but only the Spring model service populates it, and no collector exists; say so plainly so nobody assumes distributed tracing works. **And no Prometheus**: these manifests assume a Prometheus Operator is already running in the cluster, exactly as the two pre-existing `ServiceMonitor`s always have. If none is, the `ServiceMonitor`s and `PrometheusRule` are inert custom resources. State this explicitly — a committed alert file is evidence that alerts are *written*, not that anything is evaluating them, and that is precisely the confusion this investigation exists to prevent.
6. **How this stays honest.** `RecsysTargetDown` and its blind spot; `ScrapeTargetManifestTest` covering the three silent layers; promtool executing the alerts; `DocumentationIndexTest` keeping the index complete. Include the three-layer table from this plan — it is the clearest example in the repo of instrumentation that looked present and was not.

- [ ] **Step 3: Index it**

In `README.md`'s system-design table, after the row for `20`:

```markdown
| 21 | [Observability](docs/system_design/21_Observability.md) | The two-phase split — logs to Splunk, system health to Prometheus — the metric inventory, and the alerts that keep it honest |
```

- [ ] **Step 4: Update CLAUDE.md**

Add a short paragraph beside the existing `SPLUNK_*` one, covering: all four services expose Prometheus metrics (three on `/metrics`, the model service on `/actuator/prometheus`); all four are scraped via `ServiceMonitor`s in `k8s/base/servicemonitor.yaml`; alerts live in `k8s/base/prometheus-rules.yaml` and are unit-tested by `promtool` in CI; and the Splunk appender's delivery counters surface as `splunk_hec_*` gauges. Point at `docs/system_design/21_Observability.md`.

- [ ] **Step 5: Verify**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=DocumentationIndexTest
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
```
Expected: both PASS, with `SplunkHecMetricsTest` and `ScrapeTargetManifestTest` in the resilience output. A `DocumentationIndexTest` failure means the README link and the file path disagree — fix the link, not the test.

- [ ] **Step 6: Commit**

```bash
git add docs/system_design/21_Observability.md README.md .claude/CLAUDE.md pom.xml
git commit -m "docs: consolidate the two-phase observability story

New investigation 21 — a new number, not a renumbering — because none of the
existing twenty covers logging or metrics, and the Splunk design anticipated
this one.

Records the Splunk/Prometheus boundary, the metric inventory with the source
file each metric is registered in, each alert's meaning and first response,
and what is deliberately absent (no Grafana, no tracing backend despite
traceId existing in MDC).

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Final Verification

- [ ] `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test` — PASS
- [ ] `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience` — PASS, both new tests present
- [ ] `kubectl kustomize k8s/base | grep -c "kind: ServiceMonitor"` — **4**
- [ ] `kubectl kustomize k8s/base | grep -c "kind: PrometheusRule"` — **1**
- [ ] `kubectl kustomize k8s/eks && kubectl kustomize k8s/eks-us-west-2` — both build
- [ ] `prometheus-rules` workflow green on the PR
- [ ] With `SPLUNK_HEC_TOKEN` set, `curl -s localhost:8010/metrics | grep splunk_hec` returns five series
- [ ] With no token, the services start clean and no `splunk_hec_*` series appear
- [ ] Open a PR (never merge to `main` directly)
