# Online Prediction Server Optimization Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden the port-7010 online serving path with latency percentiles, stale-fallback for batch Redis reads, Micrometer gauge exposure, and a load-test baseline.

**Architecture:** The Online Prediction Server (Armeria on port 7010) already has `OnlineAdmissionControl`, `OnlineLoadShedder`, `RedisRateLimiter` (with circuit breaker), and `OnlineFeatureStore` (L3 JVM cache with single-key stale fallback). Four gaps remain: (1) metrics only expose mean latency, not percentiles; (2) the batch `getFeatures()` path throws on Redis error with no stale fallback; (3) custom metrics are not wired into the Prometheus registry; (4) no load-test baseline exists.

**Tech Stack:** Java 21, Armeria 1.28.4, Micrometer 1.12.4 (`micrometer-registry-prometheus` already in `pom.xml`), JUnit 5 + AssertJ + Mockito, Maven.

---

## File Map

| File | Change |
|---|---|
| `src/main/java/com/recsys/streaming/OnlineServingMetricsService.java` | Add P50/P95/P99 reservoir estimator; add `registerGauges(MeterRegistry)` |
| `src/main/java/com/recsys/streaming/OnlineFeatureStore.java` | Stale fallback in `getFeatures()` batch path |
| `src/main/java/com/recsys/streaming/OnlinePredictionServer.java` | Call `metricsService.registerGauges(registry)` after registry construction |
| `src/test/java/com/recsys/streaming/OnlineServingMetricsServiceTest.java` | Add percentile and gauge tests |
| `src/test/java/com/recsys/streaming/OnlineFeatureStoreTest.java` | Add batch stale-fallback test |
| `src/test/java/com/recsys/streaming/OnlinePredictionLoadTest.java` | New `@Tag("load")` baseline test |

---

## Task 1: Add Latency Percentiles to OnlineServingMetricsService

**Goal:** Replace mean-only latency with a fixed-size reservoir that enables P50/P95/P99 queries without allocating a retained object per request.

**Files:**
- Modify: `src/main/java/com/recsys/streaming/OnlineServingMetricsService.java`
- Modify: `src/test/java/com/recsys/streaming/OnlineServingMetricsServiceTest.java`

- [ ] **Step 1: Write the failing test**

Add to `OnlineServingMetricsServiceTest.java`:

```java
@Test
void percentiles_computedFromRecordedLatencies() {
    var service = new OnlineServingMetricsService(60);

    // Record 100 samples: 1 ms, 2 ms, …, 100 ms
    for (int i = 1; i <= 100; i++) {
        service.recordSuccess(i, "online");
    }

    var snap = service.snapshot();
    // p50 ≈ 50, p95 ≈ 95, p99 ≈ 99 — allow ±5 ms for reservoir rounding
    assertThat(snap.p50Ms()).isBetween(45L, 55L);
    assertThat(snap.p95Ms()).isBetween(90L, 100L);
    assertThat(snap.p99Ms()).isBetween(94L, 100L);
}

@Test
void percentiles_zeroWhenNoRequests() {
    var service = new OnlineServingMetricsService(60);
    var snap = service.snapshot();
    assertThat(snap.p50Ms()).isZero();
    assertThat(snap.p95Ms()).isZero();
    assertThat(snap.p99Ms()).isZero();
}
```

- [ ] **Step 2: Run to verify tests fail**

```bash
mvn test -pl . -Dtest=OnlineServingMetricsServiceTest#percentiles_computedFromRecordedLatencies+percentiles_zeroWhenNoRequests -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20
```

Expected: FAIL — `Snapshot` has no `p50Ms()` method.

- [ ] **Step 3: Add a fixed-size reservoir and percentile fields to OnlineServingMetricsService**

In `OnlineServingMetricsService.java`, add after the existing `Deque<RequestRecord> window` field:

```java
private static final int RESERVOIR_SIZE = 512;

// Uniform random reservoir for percentile estimation — sampled, not retained per request.
private final long[] reservoir = new long[RESERVOIR_SIZE];
private final AtomicLong reservoirCount = new AtomicLong(0L);
private final Object reservoirLock = new Object();
```

Replace the existing `record` method body. **Full new `record` method** (replace lines 103–124 entirely):

```java
private void record(long latencyMs, boolean failed, boolean rejected) {
    totalRequests.incrementAndGet();
    if (failed) {
        failureCount.incrementAndGet();
    } else {
        successCount.incrementAndGet();
    }
    if (rejected) {
        rejectedCount.incrementAndGet();
    }
    totalLatencyMs.addAndGet(latencyMs);

    // Reservoir: replace a random slot once full (Vitter's Algorithm R, simplified).
    synchronized (reservoirLock) {
        long n = reservoirCount.incrementAndGet();
        int slot = (int) (n <= RESERVOIR_SIZE ? n - 1
                : (long) (Math.random() * n));
        if (slot < RESERVOIR_SIZE) {
            reservoir[slot] = latencyMs;
        }
    }

    long now = System.currentTimeMillis() / 1000L;
    synchronized (lock) {
        evict(now);
        window.addLast(new RequestRecord(now, latencyMs, failed, rejected));
        windowTotal++;
        if (failed) windowFailures++;
        if (rejected) windowRejected++;
        windowLatencyMs += latencyMs;
    }
}
```

Add a private helper method after `record`:

```java
private long[] percentiles(int... ranks) {
    long[] result = new long[ranks.length];
    synchronized (reservoirLock) {
        int count = (int) Math.min(reservoirCount.get(), RESERVOIR_SIZE);
        if (count == 0) return result;
        long[] sorted = java.util.Arrays.copyOf(reservoir, count);
        java.util.Arrays.sort(sorted);
        for (int i = 0; i < ranks.length; i++) {
            int idx = Math.min(count - 1, (int) Math.ceil(ranks[i] / 100.0 * count) - 1);
            result[i] = sorted[Math.max(0, idx)];
        }
    }
    return result;
}
```

In the `snapshot()` method, replace the `return new Snapshot(...)` block:

```java
long[] pctls = percentiles(50, 95, 99);
return new Snapshot(
        total,
        successCount.get(),
        failureCount.get(),
        rejectedCount.get(),
        allTimeAvgLatencyMs,
        recentTotal,
        recentFailures,
        recentRejected,
        recentAvgLatencyMs,
        recentFailureRate,
        recentRejectedRate,
        qps,
        pctls[0],
        pctls[1],
        pctls[2],
        strategies
);
```

Replace the `Snapshot` record declaration (add three new fields before `strategies`):

```java
public record Snapshot(
        long totalRequests,
        long successCount,
        long failureCount,
        long rejectedCount,
        double allTimeAvgLatencyMs,
        long recentRequests,
        long recentFailures,
        long recentRejected,
        double recentAvgLatencyMs,
        double recentFailureRate,
        double recentRejectedRate,
        double qps,
        long p50Ms,
        long p95Ms,
        long p99Ms,
        Map<String, StrategySnapshot> strategies
) {}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -pl . -Dtest=OnlineServingMetricsServiceTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20
```

Expected: BUILD SUCCESS, all tests pass (including the existing 4 tests + 2 new ones).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/streaming/OnlineServingMetricsService.java \
        src/test/java/com/recsys/streaming/OnlineServingMetricsServiceTest.java
git commit -m "feat: add P50/P95/P99 reservoir percentiles to OnlineServingMetricsService"
```

---

## Task 2: Stale Fallback in OnlineFeatureStore.getFeatures() Batch Path

**Goal:** `getFeatures()` currently throws if Redis fails during `fetchFeaturesFromRedis`. Serve stale cached values on error (matching the single-key behavior in `getCachedOrLoad`).

**Files:**
- Modify: `src/main/java/com/recsys/streaming/OnlineFeatureStore.java`
- Modify: `src/test/java/com/recsys/streaming/OnlineFeatureStoreTest.java`

- [ ] **Step 1: Write the failing test**

Add to `OnlineFeatureStoreTest.java` inside the class (after the existing `getFeatures_cachesMissingFeatureKeys` test):

```java
@Test
void getFeatures_servesStaleValueWhenRedisFailsOnBatchPath() throws Exception {
    var stub = new RedisPoolStub();
    when(stub.pool.getResource()).thenReturn(stub.jedis);
    // First call succeeds and populates cache
    when(stub.jedis.mget("user:1:embedding")).thenReturn(List.of("0.1 0.2"));
    var store = new OnlineFeatureStore(stub.pool, 1L, 5_000L, 100, 10);

    // Warm the cache
    Map<String, String> first = store.getFeatures(List.of("user:1:embedding"));
    assertThat(first).containsEntry("user:1:embedding", "0.1 0.2");

    // Redis goes down after TTL expires
    Thread.sleep(5);
    when(stub.jedis.mget("user:1:embedding"))
            .thenThrow(new RuntimeException("redis down"));

    // Should serve stale value within staleTtlMs (5000 ms), not throw
    Map<String, String> stale = store.getFeatures(List.of("user:1:embedding"));
    assertThat(stale).containsEntry("user:1:embedding", "0.1 0.2");
}
```

- [ ] **Step 2: Run to verify test fails**

```bash
mvn test -pl . -Dtest=OnlineFeatureStoreTest#getFeatures_servesStaleValueWhenRedisFailsOnBatchPath -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20
```

Expected: FAIL with `RuntimeException: redis down` propagating to the test.

- [ ] **Step 3: Add stale fallback to getFeatures() in OnlineFeatureStore**

In `OnlineFeatureStore.java`, replace the body of `getFeatures()` (the `if (misses.isEmpty())` block and everything below it through the closing `}`) with:

```java
        if (misses.isEmpty()) {
            return result;
        }

        evictIfNeeded(now);

        // Snapshot existing stale values for all misses before hitting Redis — used
        // as fallback if the Redis batch call fails, matching single-key stale behavior.
        Map<String, String> staleByKey = new LinkedHashMap<>();
        for (String key : misses) {
            CachedFeature cached = featureCache.get(key);
            if (cached != null && cached.staleExpiresAtMs() > now && cached.value() != null) {
                staleByKey.put(key, cached.value());
            }
        }

        try {
            Map<String, CachedFeature> fetched = fetchFeaturesFromRedis(misses, now);
            fetched.forEach((key, feature) -> {
                featureCache.put(key, feature);
                if (feature.value() != null) {
                    result.put(key, feature.value());
                }
            });
        } catch (RuntimeException e) {
            log.warn("getFeatures Redis batch failed; serving {} stale values: {}", staleByKey.size(), e.toString());
            result.putAll(staleByKey);
        }
        return result;
```

Note: `CachedFeature` is a private record with accessor methods `value()`, `expiresAtMs()`, `staleExpiresAtMs()`. Verify existing record field names match (they are `value`, `expiresAtMs`, `staleExpiresAtMs` as defined at line 255 of the current file).

Also add the `log` import at the top of `OnlineFeatureStore.java` if not present:

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

And the field inside the class:

```java
private static final Logger log = LoggerFactory.getLogger(OnlineFeatureStore.class);
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -pl . -Dtest=OnlineFeatureStoreTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20
```

Expected: BUILD SUCCESS, all tests pass (existing 8 + 1 new stale-batch test).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/streaming/OnlineFeatureStore.java \
        src/test/java/com/recsys/streaming/OnlineFeatureStoreTest.java
git commit -m "feat: serve stale values on Redis batch failure in OnlineFeatureStore.getFeatures()"
```

---

## Task 3: Wire OnlineServingMetricsService into Prometheus via Micrometer Gauges

**Goal:** Custom ops metrics (QPS, failure rate, P95, P99, in-flight) appear in `/metrics` so alerting rules and dashboards can use them without polling `/online/ops`.

**Files:**
- Modify: `src/main/java/com/recsys/streaming/OnlineServingMetricsService.java`
- Modify: `src/main/java/com/recsys/streaming/OnlinePredictionServer.java`
- Modify: `src/test/java/com/recsys/streaming/OnlineServingMetricsServiceTest.java`

- [ ] **Step 1: Write the failing test**

Add to `OnlineServingMetricsServiceTest.java`:

```java
@Test
void registerGauges_exposesQpsAndPercentilesToMeterRegistry() {
    var service = new OnlineServingMetricsService(60);
    for (int i = 1; i <= 10; i++) {
        service.recordSuccess(i * 10L, "online"); // 10, 20, …, 100 ms
    }

    var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    service.registerGauges(registry);

    // Gauges exist — values are computed lazily on first scrape
    assertThat(registry.find("online_serving_qps").gauge()).isNotNull();
    assertThat(registry.find("online_serving_p95_ms").gauge()).isNotNull();
    assertThat(registry.find("online_serving_p99_ms").gauge()).isNotNull();
    assertThat(registry.find("online_serving_failure_rate").gauge()).isNotNull();
    assertThat(registry.find("online_serving_rejected_rate").gauge()).isNotNull();

    // QPS > 0 after recording 10 requests in a 60-second window
    double qps = registry.find("online_serving_qps").gauge().value();
    assertThat(qps).isGreaterThan(0.0);
}
```

- [ ] **Step 2: Run to verify test fails**

```bash
mvn test -pl . -Dtest=OnlineServingMetricsServiceTest#registerGauges_exposesQpsAndPercentilesToMeterRegistry -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20
```

Expected: FAIL — `registerGauges` method does not exist.

- [ ] **Step 3: Add registerGauges to OnlineServingMetricsService**

Add the following import at the top of `OnlineServingMetricsService.java`:

```java
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
```

Add the following method to `OnlineServingMetricsService` (after the `snapshot()` method):

```java
public void registerGauges(MeterRegistry registry) {
    Gauge.builder("online_serving_qps", this, s -> s.snapshot().qps())
            .description("Observed QPS in the recent window")
            .register(registry);
    Gauge.builder("online_serving_failure_rate", this, s -> s.snapshot().recentFailureRate())
            .description("Recent request failure rate (0.0–1.0)")
            .register(registry);
    Gauge.builder("online_serving_rejected_rate", this, s -> s.snapshot().recentRejectedRate())
            .description("Recent request rejection rate (0.0–1.0)")
            .register(registry);
    Gauge.builder("online_serving_p50_ms", this, s -> (double) s.snapshot().p50Ms())
            .description("P50 request latency in milliseconds (reservoir estimate)")
            .baseUnit("ms")
            .register(registry);
    Gauge.builder("online_serving_p95_ms", this, s -> (double) s.snapshot().p95Ms())
            .description("P95 request latency in milliseconds (reservoir estimate)")
            .baseUnit("ms")
            .register(registry);
    Gauge.builder("online_serving_p99_ms", this, s -> (double) s.snapshot().p99Ms())
            .description("P99 request latency in milliseconds (reservoir estimate)")
            .baseUnit("ms")
            .register(registry);
}
```

- [ ] **Step 4: Call registerGauges in OnlinePredictionServer.main()**

In `OnlinePredictionServer.java`, after the line:

```java
OnlineServingMetricsService metricsService = new OnlineServingMetricsService();
```

Add:

```java
metricsService.registerGauges(PrometheusMeterRegistries.defaultRegistry());
```

The import for `PrometheusMeterRegistries` is already present (line 8 of `OnlinePredictionServer.java`).

- [ ] **Step 5: Run tests to verify they pass**

```bash
mvn test -pl . -Dtest=OnlineServingMetricsServiceTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20
```

Expected: BUILD SUCCESS — all 7 tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/streaming/OnlineServingMetricsService.java \
        src/main/java/com/recsys/streaming/OnlinePredictionServer.java \
        src/test/java/com/recsys/streaming/OnlineServingMetricsServiceTest.java
git commit -m "feat: expose custom ops metrics as Micrometer gauges in Prometheus registry"
```

---

## Task 4: Load Test Baseline

**Goal:** Establish a reproducible load-test baseline for the online serving path (steady state, overload, Redis-fail degradation) so future changes can be verified against it. Tests are tagged `@Tag("load")` and excluded by default from CI.

**Files:**
- Create: `src/test/java/com/recsys/streaming/OnlinePredictionLoadTest.java`

- [ ] **Step 1: Write the load test**

```java
package com.recsys.streaming;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.infrastructure.redis.sharding.Page;
import com.recsys.infrastructure.redis.sharding.ShardedRecordStore;
import com.recsys.model.User;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("load")
class OnlinePredictionLoadTest {

    static final OnlineRecommendationService mockRec = mock(OnlineRecommendationService.class);

    static {
        OnlineRecommendationResult result = new OnlineRecommendationResult(
                new User(1, "Alice"), "last_hour", "online",
                List.of(), List.of(), List.of());
        when(mockRec.recommend(any())).thenReturn(result);
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            OnlineServingMetricsService metrics = new OnlineServingMetricsService(60);
            // Tight concurrency limit (16) to exercise load shedding under burst.
            OnlineLoadShedder shedder = new OnlineLoadShedder(16, 0.90);
            ShardedRecordStore mockStore = mock(ShardedRecordStore.class);
            when(mockStore.readDevice(any(), any(), anyInt())).thenReturn(new Page<>(List.of(), null));

            sb.requestTimeoutMillis(500)
              .service("/online/recommendation", new OnlineAdmissionControl(
                      new OnlinePredictionService(mockRec, metrics, shedder,
                              RedisRateLimiter.disabled(), null, true),
                      shedder, metrics))
              .service("/online/ops", new OnlineOpsService(metrics, shedder, new OnlineCapacityService()))
              .service(Route.builder().pathPrefix("/shards/").build(),
                      new ShardedRecordService(mockStore));
        }
    };

    @Test
    void steadyState_200RPS_allSucceed() throws InterruptedException {
        int requests = 200;
        int threads = 8;
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger err = new AtomicInteger();
        AtomicLong totalMs = new AtomicLong();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(requests);

        for (int i = 0; i < requests; i++) {
            pool.submit(() -> {
                try {
                    long start = System.currentTimeMillis();
                    AggregatedHttpResponse r = server.blockingWebClient()
                            .get("/online/recommendation?userId=1&k=5");
                    long elapsed = System.currentTimeMillis() - start;
                    totalMs.addAndGet(elapsed);
                    if (r.status().equals(HttpStatus.OK)) ok.incrementAndGet();
                    else err.incrementAndGet();
                } catch (Exception e) {
                    err.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();

        double avgMs = (double) totalMs.get() / requests;
        System.out.printf("[LOAD] steady-state: ok=%d err=%d avgMs=%.1f%n", ok.get(), err.get(), avgMs);

        // Under steady load with a mock backend, every request should succeed.
        assertThat(ok.get()).isEqualTo(requests);
        assertThat(err.get()).isZero();
        assertThat(avgMs).isLessThan(200.0);
    }

    @Test
    void burstOverload_shedsBeyondConcurrencyLimit() throws InterruptedException {
        int requests = 200;
        int threads = 64; // burst: far exceeds the 16-slot concurrency limit
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger shed = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(requests);

        for (int i = 0; i < requests; i++) {
            pool.submit(() -> {
                try {
                    AggregatedHttpResponse r = server.blockingWebClient()
                            .get("/online/recommendation?userId=1&k=5");
                    if (r.status().equals(HttpStatus.OK)) ok.incrementAndGet();
                    else if (r.status().equals(HttpStatus.TOO_MANY_REQUESTS)) shed.incrementAndGet();
                    else other.incrementAndGet();
                } catch (Exception e) {
                    other.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();

        System.out.printf("[LOAD] burst: ok=%d shed(429)=%d other=%d%n",
                ok.get(), shed.get(), other.get());

        // Some requests succeed; burst traffic above the concurrency limit is shed as 429.
        assertThat(ok.get()).isGreaterThan(0);
        assertThat(shed.get()).isGreaterThan(0);
        // No 5xx or unexpected status codes.
        assertThat(other.get()).isZero();
    }

    @Test
    void ops_endpoint_reflectsLoadAfterBurst() throws InterruptedException {
        // Fire 50 rapid requests to ensure the ops snapshot reflects observed traffic.
        int requests = 50;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(requests);

        for (int i = 0; i < requests; i++) {
            pool.submit(() -> {
                try {
                    server.blockingWebClient().get("/online/recommendation?userId=1&k=5");
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();

        AggregatedHttpResponse ops = server.blockingWebClient().get("/online/ops");
        assertThat(ops.status()).isEqualTo(HttpStatus.OK);
        String body = ops.contentUtf8();
        System.out.println("[LOAD] ops snapshot: " + body);

        // Ensure key fields are present in the ops JSON.
        assertThat(body).contains("\"totalRequests\"");
        assertThat(body).contains("\"qps\"");
        assertThat(body).contains("\"inFlightRequests\"");
    }
}
```

- [ ] **Step 2: Run the load test to verify it passes (and baseline is printed)**

```bash
mvn test -pl . -DexcludedGroups="" -Dgroups=load -Dtest=OnlinePredictionLoadTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -40
```

Expected: BUILD SUCCESS. Review printed `[LOAD]` lines for the baseline numbers.

- [ ] **Step 3: Run the full non-load test suite to verify no regressions**

```bash
mvn test -pl . -Dtest="OnlinePredictionServerIntegrationTest,OnlineLoadShedderTest,OnlineServingMetricsServiceTest,OnlineFeatureStoreTest,OnlineRecommendationServiceTest,RedisRateLimiterTest" -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/recsys/streaming/OnlinePredictionLoadTest.java
git commit -m "test: add @Tag(load) OnlinePredictionLoadTest with steady-state and burst scenarios"
```

---

## Self-Review Checklist

**Spec coverage:**
- [x] Real-time Redis-backed recommendations — Task 2 adds stale fallback for batch reads
- [x] Load shedding — Tasks 1 & 4 provide percentile latency data needed to tune `OnlineLoadShedder`; the concurrency gate itself is already implemented
- [x] Ops metrics — Task 1 adds P50/P95/P99; Task 3 wires them to Prometheus; Task 4 confirms ops endpoint health after load

**No placeholders:** Every step has exact code or commands.

**Type consistency:**
- Task 1 adds `p50Ms`, `p95Ms`, `p99Ms` to `Snapshot` and all four references (constructor call, gauge lambda, test assertions) use the same names.
- Task 3 calls `s.snapshot().p50Ms()` / `p95Ms()` / `p99Ms()` which matches the record accessor names added in Task 1.
- Task 4 imports reference types present in `OnlinePredictionServerIntegrationTest` (same mock pattern).

**Execution order:** Task 1 must complete before Task 3 (Task 3 calls `snap.p50Ms()` which Task 1 adds). Tasks 2 and 4 are independent.
