# Model Serving: Deployment, Load Test & Monitoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire Prometheus metrics from existing custom tracking services, add HTTP-level k6 load testing, and harden model-serving deployment manifests.

**Architecture:** Three orthogonal additions: (1) bridge `InferenceMetricsService` and `LoadShedder` into Spring Actuator's `MeterRegistry` so Prometheus can scrape them; (2) a k6 script that drives `POST /api/v1/recommend` with multi-stage traffic patterns; (3) tighter PDB/HPA settings aligned with ONNX startup costs and production capacity requirements.

**Tech Stack:** Spring Boot 3.3.4, Micrometer 1.12 (`micrometer-registry-prometheus` already in pom.xml), k6, Kubernetes `autoscaling/v2` HPA, Prometheus Operator `ServiceMonitor`

---

## File Map

| File | Change |
|------|--------|
| `pom.xml` | Add `spring-boot-starter-actuator` |
| `src/main/resources/application.yml` | Add `management.*` config to expose `/actuator/prometheus` |
| `k8s/base/configmap.yaml` | Add `MANAGEMENT_ENDPOINTS_EXPOSURE` env var |
| `src/main/java/com/recsys/modelbased/service/InferenceMetricsService.java` | Inject `MeterRegistry`; register counters and gauges |
| `src/test/java/com/recsys/modelbased/service/InferenceMetricsServiceTest.java` | Update constructor call sites; add Micrometer assertion test |
| `src/main/java/com/recsys/modelbased/service/LoadShedder.java` | Inject `MeterRegistry`; register in-flight gauge and accept/reject counters |
| `src/test/java/com/recsys/modelbased/service/LoadShedderTest.java` | Update constructor call sites; add Micrometer assertion test |
| `k8s/base/servicemonitor.yaml` | **Create** — Prometheus `ServiceMonitor` for scraping `/actuator/prometheus` |
| `k8s/base/model-serving.yaml` | Add `prometheus.io/*` annotations to the Service |
| `k8s/base/kustomization.yaml` | Add `servicemonitor.yaml` to resources list |
| `scripts/load-test/model-serving.js` | **Create** — k6 multi-stage load test (ramp → 50 rps → hold → ramp-down) |
| `k8s/base/pdb.yaml` | Tighten model-serving PDB to `minAvailable: 2` |
| `k8s/base/hpa.yaml` | Add `scaleUp`/`scaleDown` behavior tuning to model-serving HPA |

---

### Task 1: Add Spring Boot Actuator and expose the Prometheus scrape endpoint

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Modify: `k8s/base/configmap.yaml`

- [ ] **Step 1: Add `spring-boot-starter-actuator` to pom.xml**

`micrometer-registry-prometheus` is already declared (line 94) but there is no `spring-boot-starter-actuator`. Add it after the `spring-boot-starter-validation` block (around line 109):

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
  <version>${spring-boot.version}</version>
</dependency>
```

- [ ] **Step 2: Configure management endpoints in application.yml**

Append to `src/main/resources/application.yml` (after the last `recsys.submit-token` block):

```yaml
management:
  endpoints:
    web:
      exposure:
        include: ${MANAGEMENT_ENDPOINTS_EXPOSURE:health,info,prometheus}
      base-path: /actuator
  endpoint:
    health:
      show-details: always
    prometheus:
      enabled: true
  metrics:
    tags:
      application: ${spring.application.name}
    distribution:
      percentiles-histogram:
        http.server.requests: true
```

- [ ] **Step 3: Add env var to k8s configmap**

In `k8s/base/configmap.yaml`, add to the `data:` block:

```yaml
  MANAGEMENT_ENDPOINTS_EXPOSURE: "health,info,prometheus"
```

- [ ] **Step 4: Build and spot-check the endpoint resolves**

```bash
mvn package -DskipTests -q
```

Expected: `BUILD SUCCESS`. (Full HTTP check requires the service to be running; a build-only pass confirms the wiring compiles.)

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/resources/application.yml k8s/base/configmap.yaml
git commit -m "feat: expose Spring Actuator prometheus endpoint for model-serving"
```

---

### Task 2: Wire MeterRegistry into InferenceMetricsService

**Files:**
- Modify: `src/main/java/com/recsys/modelbased/service/InferenceMetricsService.java`
- Modify: `src/test/java/com/recsys/modelbased/service/InferenceMetricsServiceTest.java`

**Context:** `InferenceMetricsService` already tracks success/failure counters and rolling-window latency via `AtomicLong`s and a `Deque`. These steps bridge those same semantics into Micrometer `Counter`s and `Gauge`s without changing any existing logic. The constructor currently takes only `HealthProperties` (see line 49). Tests at lines 18 and 87 call the single-arg form; both must be updated.

- [ ] **Step 1: Write the failing test**

In `src/test/java/com/recsys/modelbased/service/InferenceMetricsServiceTest.java`, add this import at the top:

```java
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
```

Add this test method to the class:

```java
@Test
void recordSuccess_and_recordFailure_incrementMicrometerCounters() {
    var props = new HealthProperties();
    props.setWindowSeconds(60);
    var registry = new SimpleMeterRegistry();
    var svc = new InferenceMetricsService(props, registry);

    svc.recordSuccess(42L);
    svc.recordSuccess(10L);
    svc.recordFailure(5L);

    double successCount = registry.counter("recsys.inference.requests",
            "result", "success").count();
    double failureCount = registry.counter("recsys.inference.requests",
            "result", "failure").count();

    assertThat(successCount).isEqualTo(2.0);
    assertThat(failureCount).isEqualTo(1.0);
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
mvn test -Dtest=InferenceMetricsServiceTest#recordSuccess_and_recordFailure_incrementMicrometerCounters -pl . 2>&1 | tail -10
```

Expected: FAIL — `InferenceMetricsService` has no two-arg constructor yet.

- [ ] **Step 3: Update InferenceMetricsService to accept MeterRegistry**

Add imports at the top of `InferenceMetricsService.java`:

```java
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
```

Add two new fields immediately after the existing `AtomicLong` field declarations:

```java
private final Counter successCounter;
private final Counter failureCounter;
```

Replace the existing constructor (line 49) with a two-arg version:

```java
public InferenceMetricsService(HealthProperties props, MeterRegistry registry) {
    this.windowSeconds = props.getWindowSeconds();
    this.successCounter = registry.counter("recsys.inference.requests", "result", "success");
    this.failureCounter = registry.counter("recsys.inference.requests", "result", "failure");
    Gauge.builder("recsys.inference.recent_failure_rate", this,
            s -> s.snapshot().recentFailureRate())
            .description("Rolling-window failure rate (0–1)")
            .register(registry);
    Gauge.builder("recsys.inference.throughput_per_second", this,
            s -> s.snapshot().throughputPerSecond())
            .description("Rolling-window requests per second")
            .register(registry);
}
```

In the private `record(long latencyMs, boolean failed)` method, add counter increments **after** the existing `totalRequests.incrementAndGet()` block (outside the synchronized block, as counters are thread-safe):

```java
if (failed) failureCounter.increment();
else         successCounter.increment();
```

- [ ] **Step 4: Fix the two existing constructor call sites in InferenceMetricsServiceTest**

Line 18 (`@BeforeEach`):
```java
metrics = new InferenceMetricsService(props, new SimpleMeterRegistry());
```

Line 87 (inside a test method):
```java
var svc = new InferenceMetricsService(props, new SimpleMeterRegistry());
```

- [ ] **Step 5: Run the new test to verify it passes**

```bash
mvn test -Dtest=InferenceMetricsServiceTest#recordSuccess_and_recordFailure_incrementMicrometerCounters -pl . 2>&1 | tail -5
```

Expected: PASS

- [ ] **Step 6: Run the full InferenceMetricsServiceTest suite**

```bash
mvn test -Dtest=InferenceMetricsServiceTest -pl . 2>&1 | tail -10
```

Expected: all tests in that class pass. If any `@SpringBootTest` wired test fails because the bean now needs `MeterRegistry`, Spring will auto-inject `SimpleMeterRegistry` (backed by Actuator) — no change needed there.

- [ ] **Step 7: Run full test suite to catch any other broken call sites**

```bash
mvn test -pl . 2>&1 | grep -E "FAIL|ERROR|BUILD" | head -20
```

Expected: `BUILD SUCCESS`. Fix any additional `new InferenceMetricsService(props)` usages by passing `new SimpleMeterRegistry()`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/recsys/modelbased/service/InferenceMetricsService.java \
        src/test/java/com/recsys/modelbased/service/InferenceMetricsServiceTest.java
git commit -m "feat: wire MeterRegistry into InferenceMetricsService — success/failure counters and rolling gauges"
```

---

### Task 3: Wire MeterRegistry into LoadShedder

**Files:**
- Modify: `src/main/java/com/recsys/modelbased/service/LoadShedder.java`
- Modify: `src/test/java/com/recsys/modelbased/service/LoadShedderTest.java`

**Context:** `LoadShedder` (semaphore-based concurrency guard) tracks `inFlightRequests`, `acceptedRequests`, and `rejectedRequests` via `AtomicInteger`/`AtomicLong`. The constructor takes only `HealthProperties` (line 22). Five tests at lines 14, 31, 47, 62, 81 all call the single-arg form; all must be updated.

- [ ] **Step 1: Write the failing test**

In `src/test/java/com/recsys/modelbased/service/LoadShedderTest.java`, add the import:

```java
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
```

Add this test method:

```java
@Test
void tryAcquire_registersInFlightGaugeAndAcceptedCounter() {
    var props = new HealthProperties();
    props.setMaxConcurrentRequests(4);
    var registry = new SimpleMeterRegistry();
    var shedder = new LoadShedder(props, registry);

    shedder.tryAcquire();
    shedder.tryAcquire();

    double inFlight = registry.get("recsys.load_shedder.in_flight_requests").gauge().value();
    assertThat(inFlight).isEqualTo(2.0);

    double accepted = registry.counter("recsys.load_shedder.requests", "result", "accepted").count();
    assertThat(accepted).isEqualTo(2.0);

    shedder.release();
    shedder.release();

    inFlight = registry.get("recsys.load_shedder.in_flight_requests").gauge().value();
    assertThat(inFlight).isEqualTo(0.0);
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
mvn test -Dtest=LoadShedderTest#tryAcquire_registersInFlightGaugeAndAcceptedCounter -pl . 2>&1 | tail -10
```

Expected: FAIL — `LoadShedder` has no two-arg constructor yet.

- [ ] **Step 3: Update LoadShedder to accept MeterRegistry**

Add imports at the top of `LoadShedder.java`:

```java
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
```

Add two fields after the existing `AtomicLong` declarations:

```java
private final Counter acceptedCounter;
private final Counter rejectedCounter;
```

Replace the existing constructor (line 22) with a two-arg version:

```java
public LoadShedder(HealthProperties props, MeterRegistry registry) {
    this.maxConcurrentRequests = props.getMaxConcurrentRequests();
    this.maxReadinessUtilization = props.getMaxInFlightUtilization();
    this.requestSlots = new Semaphore(maxConcurrentRequests);
    this.acceptedCounter = registry.counter("recsys.load_shedder.requests", "result", "accepted");
    this.rejectedCounter = registry.counter("recsys.load_shedder.requests", "result", "rejected");
    Gauge.builder("recsys.load_shedder.in_flight_requests", inFlightRequests, AtomicInteger::get)
            .description("Number of in-flight ONNX inference requests")
            .register(registry);
    Gauge.builder("recsys.load_shedder.utilization", this, s -> s.snapshot().utilization())
            .description("In-flight / max-concurrent ratio (0–1)")
            .register(registry);
}
```

In `tryAcquire()`, add `rejectedCounter.increment()` next to each `rejectedRequests.incrementAndGet()` call. After `acceptedRequests.incrementAndGet()`, add `acceptedCounter.increment()`:

```java
public boolean tryAcquire() {
    if (shuttingDown) {
        rejectedRequests.incrementAndGet();
        rejectedCounter.increment();
        return false;
    }
    if (!requestSlots.tryAcquire()) {
        rejectedRequests.incrementAndGet();
        rejectedCounter.increment();
        return false;
    }
    inFlightRequests.incrementAndGet();
    acceptedRequests.incrementAndGet();
    acceptedCounter.increment();
    return true;
}
```

- [ ] **Step 4: Update the five existing constructor call sites in LoadShedderTest**

Replace each `new LoadShedder(props)` (at lines 14, 31, 47, 62, 81) with:

```java
new LoadShedder(props, new SimpleMeterRegistry())
```

- [ ] **Step 5: Run the new test to verify it passes**

```bash
mvn test -Dtest=LoadShedderTest#tryAcquire_registersInFlightGaugeAndAcceptedCounter -pl . 2>&1 | tail -5
```

Expected: PASS

- [ ] **Step 6: Run the full LoadShedderTest suite**

```bash
mvn test -Dtest=LoadShedderTest -pl . 2>&1 | tail -10
```

Expected: all tests in that class pass.

- [ ] **Step 7: Run the full test suite**

```bash
mvn test -pl . 2>&1 | grep -E "FAIL|ERROR|BUILD" | head -20
```

Expected: `BUILD SUCCESS`. If `RecommendationControllerTest` or any `@SpringBootTest` test fails because it constructs `LoadShedder` directly, add `new SimpleMeterRegistry()` at those sites.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/recsys/modelbased/service/LoadShedder.java \
        src/test/java/com/recsys/modelbased/service/LoadShedderTest.java
git commit -m "feat: wire MeterRegistry into LoadShedder — in-flight gauge and accept/reject counters"
```

---

### Task 4: Add Prometheus ServiceMonitor and scrape annotations

**Files:**
- Create: `k8s/base/servicemonitor.yaml`
- Modify: `k8s/base/model-serving.yaml`
- Modify: `k8s/base/kustomization.yaml`

**Context:** The Prometheus Operator (installed in most EKS setups via `kube-prometheus-stack`) discovers scrape targets via `ServiceMonitor` CRDs. The `prometheus.io/*` annotations serve as a fallback for plain Prometheus installs without the Operator.

- [ ] **Step 1: Baseline dry-run of existing manifests**

```bash
kubectl apply --dry-run=client -f k8s/base/model-serving.yaml 2>&1
```

Expected: `service/recsys-model-serving configured (dry run)` — clean baseline before edits.

- [ ] **Step 2: Create k8s/base/servicemonitor.yaml**

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: recsys-model-serving
  namespace: recsys
  labels:
    app: recsys-model-serving
spec:
  selector:
    matchLabels:
      app: recsys-model-serving
  endpoints:
    - port: http
      path: /actuator/prometheus
      interval: 15s
      scheme: http
```

- [ ] **Step 3: Add prometheus.io annotations to the Service in model-serving.yaml**

Locate the `Service` section (starts at line 88) and update its `metadata`:

```yaml
metadata:
  name: recsys-model-serving
  namespace: recsys
  annotations:
    prometheus.io/scrape: "true"
    prometheus.io/path: "/actuator/prometheus"
    prometheus.io/port: "8080"
```

- [ ] **Step 4: Add servicemonitor.yaml to kustomization.yaml**

In `k8s/base/kustomization.yaml`, append to the `resources:` list:

```yaml
  - servicemonitor.yaml
```

- [ ] **Step 5: Dry-run the full overlay**

```bash
kubectl kustomize k8s/base | kubectl apply --dry-run=client -f - 2>&1 | grep -v "^$"
```

Expected: all resources report `... configured (dry run)`. The `ServiceMonitor` may print `no matches for kind "ServiceMonitor"` if the Prometheus Operator CRD is not installed locally — that is acceptable for a local check.

- [ ] **Step 6: Commit**

```bash
git add k8s/base/servicemonitor.yaml k8s/base/model-serving.yaml k8s/base/kustomization.yaml
git commit -m "feat: add ServiceMonitor and prometheus.io annotations for model-serving metrics scraping"
```

---

### Task 5: k6 HTTP load test for model-serving

**Files:**
- Create: `scripts/load-test/model-serving.js`

**Context:** The existing `InferenceLoadTest.java` drives the service layer in-process. This k6 script tests the actual HTTP endpoint (`POST /api/v1/recommend`) under realistic multi-stage traffic, exercising load shedding, rate limiting, caching, and the full request path. Run with `k6 run --env BASE_URL=http://localhost:8080 scripts/load-test/model-serving.js`.

- [ ] **Step 1: Verify k6 is installed**

```bash
which k6 && k6 version
```

If missing:
```bash
brew install k6
```

Expected: `k6 v0.x.x (...)` — k6 available.

- [ ] **Step 2: Create scripts/load-test/model-serving.js**

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const errorRate  = new Rate('errors');
const reqLatency = new Trend('req_latency_ms', true);

// Run: k6 run --env BASE_URL=http://localhost:8080 scripts/load-test/model-serving.js
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    staged_load: {
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      preAllocatedVUs: 30,
      maxVUs: 150,
      stages: [
        { duration: '30s', target: 10 },   // warm up to 10 rps
        { duration: '60s', target: 50 },   // ramp to steady-state 50 rps
        { duration: '120s', target: 50 },  // hold at 50 rps (2 min)
        { duration: '30s', target: 100 },  // spike to 100 rps
        { duration: '30s', target: 50 },   // return to steady-state
        { duration: '30s', target: 0 },    // ramp down
      ],
    },
  },
  thresholds: {
    // P95 under 500 ms at steady-state
    'http_req_duration{scenario:staged_load}': ['p(95)<500'],
    // Fewer than 1% failed requests
    'errors': ['rate<0.01'],
    'http_req_failed': ['rate<0.01'],
  },
};

const USER_IDS = ['1', '10', '50', '100', '123', '200', '300', '400', '500', '600'];

export default function () {
  const userId  = USER_IDS[Math.floor(Math.random() * USER_IDS.length)];
  const payload = JSON.stringify({ userId, k: 10 });

  const res = http.post(`${BASE_URL}/api/v1/recommend`, payload, {
    headers: { 'Content-Type': 'application/json' },
    timeout: '5s',
  });

  const ok = check(res, {
    'status 200':         (r) => r.status === 200,
    'has recommendations': (r) => {
      try {
        const body = r.json();
        return Array.isArray(body.recommendations) && body.recommendations.length > 0;
      } catch (_) {
        return false;
      }
    },
  });

  errorRate.add(ok ? 0 : 1);
  if (ok) reqLatency.add(res.timings.duration);
}

export function handleSummary(data) {
  const d   = data.metrics.http_req_duration;
  const p95 = d ? d.values['p(95)'] : 'N/A';
  const err = data.metrics.errors ? (data.metrics.errors.values.rate * 100).toFixed(2) : '0.00';
  console.log('\n=== model-serving load test summary ===');
  console.log(`  P95 latency : ${typeof p95 === 'number' ? p95.toFixed(0) : p95} ms  (threshold: 500 ms)`);
  console.log(`  Error rate  : ${err}%  (threshold: 1%)`);
  return {};
}
```

- [ ] **Step 3: Smoke-test with minimal load against a locally-running service**

Start the model-serving service:
```bash
mvn exec:java -Dexec.mainClass=com.recsys.modelbased.model.ModelApplication -q &
sleep 15  # wait for ONNX model to load
```

Smoke run:
```bash
k6 run --env BASE_URL=http://localhost:8080 \
  --duration 15s --vus 2 \
  scripts/load-test/model-serving.js 2>&1 | tail -20
```

Expected: k6 reports `✓ status 200` and `✓ has recommendations` checks passing. Error rate = 0%.

- [ ] **Step 4: Run the full staged scenario**

```bash
k6 run --env BASE_URL=http://localhost:8080 scripts/load-test/model-serving.js
```

Expected: all thresholds pass (P95 < 500 ms, error rate < 1%). Note the actual P95 and throughput numbers — they inform HPA threshold tuning in Task 6. If the spike to 100 rps causes load-shedding 503s, that is expected behavior; check error rate stays below 1%.

- [ ] **Step 5: Commit**

```bash
git add scripts/load-test/model-serving.js
git commit -m "feat: add k6 HTTP load test for model-serving — 50 rps steady-state with spike to 100 rps"
```

---

### Task 6: Harden deployment — PDB and HPA tuning

**Files:**
- Modify: `k8s/base/pdb.yaml`
- Modify: `k8s/base/hpa.yaml`

**Context:**
- **PDB:** Current `minAvailable: 1` lets Kubernetes drain 5 of 6 replicas simultaneously during a node rotation. For ONNX inference (slow startup, stateful ONNX session), this risks sustained overload. `minAvailable: 2` keeps at least two replicas serving.
- **HPA:** Current config has no `behavior` block, so Kubernetes uses the defaults (5-minute scale-down stabilization, immediate scale-up). The defaults are too aggressive for scale-up (ONNX takes ~10s to load) and too slow for scale-down. The new policy adds at most 2 pods/minute on scale-up to avoid over-provisioning during a spike, and removes at most 1 pod/2 min on scale-down to let cache entries warm up before the next pod leaves.

- [ ] **Step 1: Dry-run current state**

```bash
kubectl apply --dry-run=client -f k8s/base/pdb.yaml && \
kubectl apply --dry-run=client -f k8s/base/hpa.yaml
```

Expected: both apply cleanly — clean baseline before edits.

- [ ] **Step 2: Update model-serving PDB to minAvailable: 2**

In `k8s/base/pdb.yaml`, locate the `recsys-model-serving-pdb` block (lines 22–30) and change `minAvailable`:

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: recsys-model-serving-pdb
  namespace: recsys
spec:
  minAvailable: 2
  selector:
    matchLabels:
      app: recsys-model-serving
```

- [ ] **Step 3: Add scaleUp and scaleDown behavior to model-serving HPA**

In `k8s/base/hpa.yaml`, locate the `recsys-model-serving` HPA block. It currently ends with:
```yaml
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300
```

Replace that entire `behavior:` block with:

```yaml
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 30
      policies:
        - type: Pods
          value: 2
          periodSeconds: 60
      selectPolicy: Max
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
        - type: Pods
          value: 1
          periodSeconds: 120
      selectPolicy: Min
```

- [ ] **Step 4: Dry-run the updated manifests**

```bash
kubectl apply --dry-run=client -f k8s/base/pdb.yaml && \
kubectl apply --dry-run=client -f k8s/base/hpa.yaml
```

Expected: both apply cleanly.

- [ ] **Step 5: Validate the full kustomize overlay**

```bash
kubectl kustomize k8s/base | kubectl apply --dry-run=client -f - 2>&1 | grep -v "^$"
```

Expected: all resources apply cleanly.

- [ ] **Step 6: Commit**

```bash
git add k8s/base/pdb.yaml k8s/base/hpa.yaml
git commit -m "feat: tighten model-serving PDB to minAvailable:2 and tune HPA scaleUp/scaleDown policies"
```

---

## Self-Review

**1. Spec coverage**

| Requirement | Task |
|---|---|
| Prometheus metrics exposure | Tasks 1, 2, 3, 4 |
| Inference counters/gauges (success, failure, in-flight) | Tasks 2, 3 |
| Prometheus scrape endpoint wired | Task 1 |
| k8s ServiceMonitor | Task 4 |
| HTTP-level load test (multi-stage, ramp, spike) | Task 5 |
| PDB hardening | Task 6 |
| HPA scale behavior tuning | Task 6 |

**2. Placeholder scan** — None found. All code blocks are complete and runnable.

**3. Type consistency**

- Counter name `"recsys.inference.requests"` with tags `"result","success"/"failure"` — consistent between Task 2 step 1 (test) and step 3 (implementation).
- Counter name `"recsys.load_shedder.requests"` with tags `"result","accepted"/"rejected"` — consistent between Task 3 step 1 (test) and step 3 (implementation).
- Gauge name `"recsys.load_shedder.in_flight_requests"` — consistent between Task 3 step 1 (test assert) and step 3 (registration).
- `InferenceMetricsService(HealthProperties, MeterRegistry)` — two-arg constructor defined in Task 2 step 3; all call sites updated in Task 2 step 4.
- `LoadShedder(HealthProperties, MeterRegistry)` — two-arg constructor defined in Task 3 step 3; all five call sites updated in Task 3 step 4.
