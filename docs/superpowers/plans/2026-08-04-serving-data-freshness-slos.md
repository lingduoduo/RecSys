# Serving Data Freshness SLOs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add truthful, tested Prometheus alerts and an operational runbook for online-feature freshness and durable online-event delivery.

**Architecture:** Extend `ConsistencyMetrics` only where current gauges cannot distinguish valid data from missing telemetry. Make the existing outbox relay metrics scrapeable through the repository's Prometheus Operator pattern, then evaluate feature freshness and relay delivery with unit-tested `PrometheusRule` expressions. Keep incident response in one runbook and summarize the objectives in the observability design.

**Tech Stack:** Java 17, Micrometer Prometheus simpleclient 1.13.4, Redis/Lettuce, Kubernetes/Kustomize, Prometheus Operator, PromQL, `promtool` 3.13.2, JUnit 5, AssertJ.

## Global Constraints

- These are internal SLOs, not contractual customer SLAs.
- Do not add BigQuery code, a generic endpoint YAML schema, a dashboard, or a new monitoring service.
- Feature warning: age greater than 60 seconds for 5 minutes.
- Feature critical: age greater than 300 seconds for 5 minutes.
- `kafka_online` delivery warning: greater than 30 seconds for 10 minutes.
- A failed or never-successful probe must never be interpreted as fresh data.
- Labels stay bounded to existing `job`, `instance`, and `destination` dimensions.
- Do not change serving behavior, Redis freshness windows, outbox retries, Flink semantics, relay replicas, or application behavior.
- Kubernetes changes only expose and scrape the relay metrics endpoint and permit Prometheus ingress.
- The Maven baseline has two known `ModelArtifactLocatorTest` errors when `artifacts/pyspark/als_model_metadata.json` is absent; no additional failure is accepted.

---

### Task 1: Make feature sampling observable and pin metric exposition

**Files:**
- Modify: `src/main/java/com/recsys/metrics/ConsistencyMetrics.java`
- Modify: `src/main/java/com/recsys/infrastructure/redis/RedisFeatureVersionSampler.java`
- Modify: `src/test/java/com/recsys/metrics/ConsistencyMetricsTest.java`
- Create: `src/test/java/com/recsys/infrastructure/redis/RedisFeatureVersionSamplerTest.java`

**Interfaces:**
- Consumes: `updateFeatureVersions(long, long, Duration)` and `RedisFeatureVersionSampler.sample()`.
- Produces: gauge `redis_feature_version_sample_available`, method `markFeatureVersionSampleUnavailable()`, and a pinned Prometheus delivery-lag series name.

- [ ] **Step 1: Add failing metric contract tests**

Extend `ConsistencyMetricsTest` with:

```java
@Test
void prometheusExpositionPinsFeatureAvailabilityAndDeliveryLagNames() {
    var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    var metrics = new ConsistencyMetrics(registry);
    assertThat(registry.get("redis_feature_version_sample_available").gauge().value()).isZero();
    metrics.updateFeatureVersions(7, 11, Duration.ofSeconds(8));
    metrics.recordDelivered(ConsistencyMetrics.Destination.KAFKA_ONLINE, Duration.ofSeconds(45));
    assertThat(registry.get("redis_feature_version_sample_available").gauge().value()).isOne();
    assertThat(registry.scrape())
            .contains("redis_feature_version_sample_available 1.0")
            .contains("outbox_delivery_lag_seconds_max")
            .contains("destination=\"kafka_online\"");
}

@Test
void featureSampleCanReturnToUnavailableWithoutErasingLastGoodValues() {
    var registry = new SimpleMeterRegistry();
    var metrics = new ConsistencyMetrics(registry);
    metrics.updateFeatureVersions(7, 11, Duration.ofSeconds(8));
    metrics.markFeatureVersionSampleUnavailable();
    assertThat(registry.get("redis_feature_version_sample_available").gauge().value()).isZero();
    assertThat(registry.get("redis_feature_version_age_seconds").gauge().value()).isEqualTo(8);
}
```

Import `PrometheusConfig` and `PrometheusMeterRegistry`.

- [ ] **Step 2: Confirm RED**

Run:

```bash
mvn --batch-mode test -Dtest=ConsistencyMetricsTest -DexcludedGroups=load,docker
```

Expected: compilation fails because the new method/gauge do not exist.

- [ ] **Step 3: Implement the availability state**

Add a shared `AtomicLong featureSampleAvailable`, register
`redis_feature_version_sample_available`, set it to `1` in `updateFeatureVersions`, and add:

```java
public void markFeatureVersionSampleUnavailable() {
    featureSampleAvailable.set(0);
}
```

Do not reset last-good min, max, or age values.

- [ ] **Step 4: Add sampler tests**

Create `RedisFeatureVersionSamplerTest` with Mockito-backed `RedisExecutor` and
`RedisCommands<String,String>`. Capture and execute the function passed to
`executePrimaryRead`. Cover:

```java
@Test void emptyScanMarksSampleUnavailable() { /* empty keys; false; availability=0 */ }
@Test void redisFailureMarksSampleUnavailableAndRethrows() { /* same exception; availability=0 */ }
@Test void validTimestampMarksSampleAvailable() { /* value=1000, clock=11000; age=10s */ }
```

- [ ] **Step 5: Confirm RED, implement, and verify GREEN**

Refactor `sample()` so an empty sample marks unavailable, while a runtime exception marks
unavailable and rethrows. Keep the scheduler's existing best-effort swallowing behavior.

Run outside the restricted sandbox:

```bash
mvn --batch-mode test -Dtest=RedisFeatureVersionSamplerTest,ConsistencyMetricsTest -DexcludedGroups=load,docker
git diff --check
```

Expected: focused tests pass and the diff check is clean.

- [ ] **Step 6: Commit Task 1**

```bash
git add src/main/java/com/recsys/metrics/ConsistencyMetrics.java \
  src/main/java/com/recsys/infrastructure/redis/RedisFeatureVersionSampler.java \
  src/test/java/com/recsys/metrics/ConsistencyMetricsTest.java \
  src/test/java/com/recsys/infrastructure/redis/RedisFeatureVersionSamplerTest.java
git commit -m "feat: expose online feature sample availability"
```

### Task 2: Make the outbox relay scrapeable

**Files:**
- Modify: `src/test/java/com/recsys/metrics/ScrapeTargetManifestTest.java`
- Modify: `k8s/base/outbox-relay-deployment.yaml`
- Modify: `k8s/base/servicemonitor.yaml`
- Modify: `k8s/base/network-policy.yaml`

**Interfaces:**
- Consumes: relay pod label `app: recsys-outbox-relay`, named port `http`, path `/metrics`, port 7020.
- Produces: Service and ServiceMonitor named `recsys-outbox-relay`; Prometheus-only ingress to 7020.

- [ ] **Step 1: Extend the manifest contract test first**

Add the relay name to `EXPECTED_SCRAPE_TARGETS` and its port to `SCRAPE_PORTS`:

```java
private static final Set<String> EXPECTED_SCRAPE_TARGETS = Set.of(
        "recsys-model-serving",
        "recsys-catalog-serving",
        "recsys-online-serving",
        "recsys-api-gateway",
        "recsys-outbox-relay");

// Add beside the existing Map.ofEntries entries:
entry("recsys-outbox-relay", 7020)
```

The existing three tests must require the Service label, matching ServiceMonitor, and one
NetworkPolicy rule combining the monitoring namespace selector with the Prometheus pod selector.

- [ ] **Step 2: Confirm RED**

```bash
mvn --batch-mode test -Dtest=ScrapeTargetManifestTest -DexcludedGroups=load,docker
```

Expected: failures identify the missing Service, ServiceMonitor, and ingress rule.

- [ ] **Step 3: Add the Operator-native scrape path**

Remove the relay pod's `prometheus.io/*` annotations. Append this Service to
`outbox-relay-deployment.yaml`:

```yaml
---
apiVersion: v1
kind: Service
metadata:
  name: recsys-outbox-relay
  namespace: recsys
  labels:
    app: recsys-outbox-relay
spec:
  selector:
    app: recsys-outbox-relay
  ports:
    - name: http
      port: 7020
      targetPort: http
```

Append a ServiceMonitor to `servicemonitor.yaml` with selector
`app: recsys-outbox-relay`, port `http`, path `/metrics`, interval `15s`, timeout `10s`,
namespace `recsys`, and metadata label `release: kube-prometheus-stack`.

Append an ingress-only NetworkPolicy selecting the relay. Its one ingress rule combines:

```yaml
namespaceSelector:
  matchLabels:
    kubernetes.io/metadata.name: monitoring
podSelector:
  matchLabels:
    app.kubernetes.io/name: prometheus
```

and admits only TCP port `7020`. Do not add egress policy.

- [ ] **Step 4: Verify and commit Task 2**

```bash
mvn --batch-mode test -Dtest=ScrapeTargetManifestTest -DexcludedGroups=load,docker
kubectl kustomize k8s/base > /tmp/recsys-data-slo-rendered.yaml
rg -n 'recsys-outbox-relay|port: 7020|path: /metrics' /tmp/recsys-data-slo-rendered.yaml
git diff --check
git add src/test/java/com/recsys/metrics/ScrapeTargetManifestTest.java \
  k8s/base/outbox-relay-deployment.yaml k8s/base/servicemonitor.yaml k8s/base/network-policy.yaml
git commit -m "feat: scrape outbox relay metrics"
```

Expected: 3/3 manifest tests pass and Kustomize renders the relay scrape path.

### Task 3: Add tested data freshness alert rules

**Files:**
- Modify: `k8s/base/prometheus-rules.yaml`
- Modify: `k8s/base/prometheus-rules.test.yaml`

**Interfaces:**
- Consumes: `redis_feature_version_age_seconds`, `redis_feature_version_sample_available`,
  proven `outbox_delivery_lag_seconds_max`, and
  `outbox_delivery_failures_total{destination="kafka_online"}`.
- Produces: five tested Prometheus alerts.

- [ ] **Step 1: Add RED promtool cases**

Add firing, threshold near-miss, and brief-spike/no-traffic cases for:

```text
OnlineFeatureDataStale                 age 61, for 5m, warning; age 60 does not fire
OnlineFeatureDataCriticallyStale       age 301, for 5m, critical; age 300 does not fire
OnlineFeatureVersionSampleUnavailable  available 0, for 5m, warning; 1 does not fire
OutboxDeliveryLatencyHigh              kafka_online max 31, for 10m; 30 and saga_sns do not fire
OutboxDeliveryFailuresSustained        increase over 10m > 0, for 10m; flat history does not fire
```

Include exact expected labels and annotations for every firing result.

- [ ] **Step 2: Confirm RED**

```bash
cd k8s/base
yq '.spec' prometheus-rules.yaml > prometheus-rules.rules.yaml
promtool test rules prometheus-rules.test.yaml
```

Expected: new cases fail because the alerts do not exist.

- [ ] **Step 3: Add exact alert expressions**

Under `recsys.data`, add:

```promql
redis_feature_version_age_seconds > 60
redis_feature_version_age_seconds > 300
redis_feature_version_sample_available == 0
outbox_delivery_lag_seconds_max{destination="kafka_online"} > 30
increase(outbox_delivery_failures_total{destination="kafka_online"}[10m]) > 0
```

Use the names and `for`/severity values from Step 1. Feature annotations direct operators
to Redis and online-serving logs and explain that unavailable sampling preserves the last
good age. Relay annotations direct operators to relay logs, MySQL backlog, Kafka
reachability, and the companion metric.

- [ ] **Step 4: Verify and commit Task 3**

```bash
cd k8s/base
yq '.spec' prometheus-rules.yaml > prometheus-rules.rules.yaml
promtool check rules prometheus-rules.rules.yaml
promtool test rules prometheus-rules.test.yaml
cd ../..
git diff --check
git add k8s/base/prometheus-rules.yaml k8s/base/prometheus-rules.test.yaml
git commit -m "feat: alert on serving data freshness SLOs"
```

Expected: syntax and every old/new rule test pass.

### Task 4: Add the operational runbook and entry points

**Files:**
- Create: `docs/runbooks/serving-data-freshness.md`
- Modify: `docs/system_design/21_Observability.md`
- Modify: `README.md`
- Modify: `src/test/java/com/recsys/docs/DocumentationIndexTest.java` only if its explicit link set requires it.

**Interfaces:**
- Consumes: five alert names, thresholds, and existing Kafka/Flink, Redis, outbox, Prometheus, and Splunk surfaces.
- Produces: one canonical incident runbook linked from README and summarized in the observability design.

- [ ] **Step 1: Establish the documentation test behavior**

If `DocumentationIndexTest` enumerates runbook links, add
`docs/runbooks/serving-data-freshness.md` first and confirm the test fails until the link/file
exist. If it discovers links generically, do not add a redundant assertion.

```bash
mvn --batch-mode test -Dtest=DocumentationIndexTest -DexcludedGroups=load,docker
```

- [ ] **Step 2: Write the runbook**

Create `docs/runbooks/serving-data-freshness.md` containing:

1. SLO table with every threshold and `for` duration.
2. Telemetry verification: Prometheus targets, `up{namespace="recsys"}`, relay Service,
   ServiceMonitor, and safe port-forward/curl commands.
3. PromQL for feature age/availability/version spread, relay max lag, failure increases,
   backlog, and replica lag.
4. Alert diagnosis through Kafka, Flink state/checkpoints/watermarks, Redis
   `*:updated_at`, MySQL outbox, relay logs, and Kafka connectivity.
5. Mitigation/recovery checks that never delete Kafka, Redis, or MySQL state.
6. Limitations: this repo installs neither Prometheus Operator nor Alertmanager; Splunk
   provides event detail and is at-most-once.

- [ ] **Step 3: Update design documentation and README**

Update `docs/system_design/21_Observability.md` to add the availability metric and five
alerts, replace the obsolete claim that relay annotations are inert, and summarize the
internal SLOs with a runbook link. Add one concise link to the README runbook index.

- [ ] **Step 4: Verify and commit Task 4**

```bash
mvn --batch-mode test -Dtest=DocumentationIndexTest,DocumentedMechanismTest -DexcludedGroups=load,docker
rg -n 'OnlineFeatureDataStale|OutboxDeliveryLatencyHigh|redis_feature_version_sample_available|serving-data-freshness' \
  README.md docs/system_design/21_Observability.md docs/runbooks/serving-data-freshness.md
git diff --check
git add README.md docs/runbooks/serving-data-freshness.md docs/system_design/21_Observability.md
git add src/test/java/com/recsys/docs/DocumentationIndexTest.java 2>/dev/null || true
git commit -m "docs: add serving data freshness runbook"
```

Do not stage `DocumentationIndexTest.java` if it was unchanged.

### Task 5: Run final cross-layer verification

**Files:**
- Verify only; modify prior-task files only to fix a discovered defect.

**Interfaces:**
- Consumes: metrics, rendered scrape resources, rules, tests, and documentation from Tasks 1–4.
- Produces: evidence that no monitoring layer is disconnected.

- [ ] **Step 1: Run focused Java contracts outside the restricted sandbox**

```bash
mvn --batch-mode test \
  -Dtest=ConsistencyMetricsTest,RedisFeatureVersionSamplerTest,ScrapeTargetManifestTest,DocumentationIndexTest,DocumentedMechanismTest \
  -DexcludedGroups=load,docker
```

Expected: zero failures and errors.

- [ ] **Step 2: Verify rules and rendered resources**

```bash
cd k8s/base
yq '.spec' prometheus-rules.yaml > prometheus-rules.rules.yaml
promtool check rules prometheus-rules.rules.yaml
promtool test rules prometheus-rules.test.yaml
cd ../..
kubectl kustomize k8s/base > /tmp/recsys-data-slo-rendered.yaml
rg -n 'recsys-outbox-relay|port: 7020|path: /metrics|OnlineFeatureDataStale|OutboxDeliveryLatencyHigh' \
  /tmp/recsys-data-slo-rendered.yaml
```

Expected: rules pass and rendered output contains the scrape path and alerts.

- [ ] **Step 3: Re-run the known Maven baseline outside the sandbox**

```bash
mvn --batch-mode test -DexcludedGroups=load,docker
```

Expected without generated Spark artifacts: at least 1,546 tests, zero failures, and only
the two known missing-`als_model_metadata.json` errors. If the artifact exists, expect zero
errors. Any other failure blocks completion.

- [ ] **Step 4: Final hygiene**

```bash
git diff --check
git status --short
git log --oneline --decorate -8
```

Do not create an empty verification commit. Record exact test counts and the known baseline
exception in the pull-request handoff.
