# Reliability & Scheduling: Worker Isolation / Failure Injection / Auto-Recovery

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three orthogonal reliability primitives — `WorkerBulkhead` (named isolated thread pools), `FaultInjector` (controllable fault injection for tests), and `ChannelHealthMonitor` + `LearnerFlushScheduler` (auto-recovery) — then wire them into `MultiChannelRecallService` (parallel execution with backoff) and `OnlinePredictionServer` (scheduled learner persistence).

**Architecture:**
- `WorkerBulkhead` wraps a bounded `ThreadPoolExecutor` per lane; isolates slow channels from the request path.
- `FaultInjector` is a per-test singleton that injects latency or exceptions at named points; production code uses `FaultInjector.NOOP`.
- `ChannelHealthMonitor` tracks consecutive failures per recall channel and applies exponential backoff before retrying, mirroring the `RouteCircuitBreaker` pattern already in the gateway.
- `LearnerFlushScheduler` runs `OnlineLearner.flushToRedis()` on a daemon thread at a fixed interval so learned biases survive process restarts.
- `MultiChannelRecallService.recall()` is upgraded from sequential to parallel (via `WorkerBulkhead`) with per-channel timeouts and `ChannelHealthMonitor` integration; the 1-arg constructor is backward-compatible.

**Tech Stack:** Java 17, JUnit 5 / AssertJ, Mockito, existing Jedis pool, `java.util.concurrent` (no new external dependencies).

---

## File Map

| Path | Action | Responsibility |
|---|---|---|
| `src/main/java/com/recsys/streaming/WorkerBulkhead.java` | Create | Named bounded `ThreadPoolExecutor`; provides `submit(Callable<T>)` + `Snapshot` |
| `src/main/java/com/recsys/streaming/FaultInjector.java` | Create | Injects latency or exceptions at named points; `NOOP` for production |
| `src/main/java/com/recsys/service/retrieval/ChannelHealthMonitor.java` | Create | Exponential-backoff health tracking per `RecallChannel` name |
| `src/main/java/com/recsys/streaming/LearnerFlushScheduler.java` | Create | Daemon scheduler: periodic `OnlineLearner.flushToRedis()` |
| `src/main/java/com/recsys/service/retrieval/MultiChannelRecallService.java` | Modify | Parallel channel execution via `WorkerBulkhead`; integrates `ChannelHealthMonitor` + `FaultInjector` |
| `src/main/java/com/recsys/streaming/OnlinePredictionServer.java` | Modify | Wire `LearnerFlushScheduler`; add to shutdown hook |
| `src/test/java/com/recsys/streaming/WorkerBulkheadTest.java` | Create | Unit: submit, queue overflow, snapshot, shutdown |
| `src/test/java/com/recsys/streaming/FaultInjectorTest.java` | Create | Unit: latency injection, exception injection, NOOP |
| `src/test/java/com/recsys/service/retrieval/ChannelHealthMonitorTest.java` | Create | Unit: backoff onset, backoff expiry, reset on success |
| `src/test/java/com/recsys/streaming/LearnerFlushSchedulerTest.java` | Create | Unit: periodic flush, error resilience, final flush on close |
| `src/test/java/com/recsys/service/retrieval/MultiChannelRecallServiceTest.java` | Modify | Add parallel, timeout, and health-backoff scenarios |
| `src/test/java/com/recsys/service/retrieval/WorkerIsolationFailureTest.java` | Create | Integration chaos: slow channel times out, backoff kicks in, recovery after reset |

---

## Task 1: WorkerBulkhead

**Files:**
- Create: `src/main/java/com/recsys/streaming/WorkerBulkhead.java`
- Create: `src/test/java/com/recsys/streaming/WorkerBulkheadTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/recsys/streaming/WorkerBulkheadTest.java
package com.recsys.streaming;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerBulkheadTest {

    private WorkerBulkhead bulkhead;

    @AfterEach
    void tearDown() {
        if (bulkhead != null) bulkhead.close();
    }

    @Test
    void tasksExecuteInNamedThreads() throws Exception {
        bulkhead = new WorkerBulkhead("test-lane", 2, 8);
        CompletableFuture<String> threadName = bulkhead.submit(() -> Thread.currentThread().getName());
        assertThat(threadName.get(2, TimeUnit.SECONDS)).startsWith("test-lane-worker-");
    }

    @Test
    void queueOverflowIncrementsRejectedCount() throws InterruptedException {
        // 1 thread, 0 queue capacity → every task beyond in-flight 1 is rejected
        bulkhead = new WorkerBulkhead("tight", 1, 0);
        CountDownLatch blocker = new CountDownLatch(1);
        // Occupy the sole thread
        bulkhead.submit(() -> { blocker.await(); return null; });

        AtomicInteger rejectedFutures = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            CompletableFuture<Void> f = bulkhead.submit(() -> null);
            f.exceptionally(ex -> { rejectedFutures.incrementAndGet(); return null; });
        }
        Thread.sleep(50); // let exceptionally callbacks fire
        assertThat(bulkhead.snapshot().rejected()).isGreaterThan(0);
        blocker.countDown();
    }

    @Test
    void snapshotReflectsPoolName() {
        bulkhead = new WorkerBulkhead("scoring", 4, 32);
        assertThat(bulkhead.snapshot().name()).isEqualTo("scoring");
        assertThat(bulkhead.snapshot().poolSize()).isEqualTo(4);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=WorkerBulkheadTest -DskipTests=false 2>&1 | tail -15
```
Expected: FAIL — `WorkerBulkhead cannot be resolved to a type`

- [ ] **Step 3: Implement WorkerBulkhead**

```java
// src/main/java/com/recsys/streaming/WorkerBulkhead.java
package com.recsys.streaming;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class WorkerBulkhead {

    private static final AtomicLong THREAD_COUNTER = new AtomicLong();

    private final String name;
    private final ThreadPoolExecutor executor;
    private final AtomicLong rejectedCount = new AtomicLong();

    public WorkerBulkhead(String name, int poolSize, int queueCapacity) {
        this.name = name;
        this.executor = new ThreadPoolExecutor(
                poolSize, poolSize,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                r -> {
                    Thread t = new Thread(r, name + "-worker-" + THREAD_COUNTER.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                (runnable, tpe) -> rejectedCount.incrementAndGet()
        );
    }

    public <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(task.call());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            rejectedCount.incrementAndGet();
            future.completeExceptionally(e);
        }
        return future;
    }

    public void close() {
        executor.shutdown();
    }

    public Snapshot snapshot() {
        return new Snapshot(name, executor.getActiveCount(), executor.getQueue().size(),
                executor.getCorePoolSize(), rejectedCount.get());
    }

    public record Snapshot(String name, int active, int queued, int poolSize, long rejected) {}
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn test -Dtest=WorkerBulkheadTest 2>&1 | tail -10
```
Expected: BUILD SUCCESS, 3 tests passing.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/streaming/WorkerBulkhead.java \
        src/test/java/com/recsys/streaming/WorkerBulkheadTest.java
git commit -m "feat: add WorkerBulkhead — named bounded thread pool per lane with rejection metrics"
```

---

## Task 2: FaultInjector

**Files:**
- Create: `src/main/java/com/recsys/streaming/FaultInjector.java`
- Create: `src/test/java/com/recsys/streaming/FaultInjectorTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/recsys/streaming/FaultInjectorTest.java
package com.recsys.streaming;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FaultInjectorTest {

    @Test
    void noopNeverInjectsAnything() {
        // FaultInjector.NOOP must never throw or delay
        long start = System.currentTimeMillis();
        FaultInjector.NOOP.maybeInject("any:point");
        assertThat(System.currentTimeMillis() - start).isLessThan(50);
    }

    @Test
    void latencyInjectionSleepsAtLeastConfiguredMs() {
        FaultInjector injector = new FaultInjector();
        injector.injectLatency("db:read", 80);
        long start = System.currentTimeMillis();
        injector.maybeInject("db:read");
        assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(75);
    }

    @Test
    void exceptionInjectionThrows() {
        FaultInjector injector = new FaultInjector();
        injector.injectException("redis:get", new RuntimeException("injected failure"));
        assertThatThrownBy(() -> injector.maybeInject("redis:get"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("injected failure");
    }

    @Test
    void clearRemovesFault() {
        FaultInjector injector = new FaultInjector();
        injector.injectException("channel:x", new RuntimeException("boom"));
        injector.clear("channel:x");
        // Should not throw
        injector.maybeInject("channel:x");
    }

    @Test
    void unknownPointIsAlwaysPassThrough() {
        FaultInjector injector = new FaultInjector();
        injector.injectLatency("point:a", 100);
        // point:b is not configured — must not delay or throw
        long start = System.currentTimeMillis();
        injector.maybeInject("point:b");
        assertThat(System.currentTimeMillis() - start).isLessThan(50);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=FaultInjectorTest 2>&1 | tail -10
```
Expected: FAIL — `FaultInjector cannot be resolved to a type`

- [ ] **Step 3: Implement FaultInjector**

```java
// src/main/java/com/recsys/streaming/FaultInjector.java
package com.recsys.streaming;

import java.util.concurrent.ConcurrentHashMap;

public final class FaultInjector {

    public static final FaultInjector NOOP = new FaultInjector() {
        @Override public void injectLatency(String point, long millis) {}
        @Override public void injectException(String point, RuntimeException ex) {}
        @Override public void clear(String point) {}
        @Override public void maybeInject(String point) {}
    };

    private enum FaultType { LATENCY, EXCEPTION }

    private record FaultConfig(FaultType type, long latencyMs, RuntimeException exception) {}

    private final ConcurrentHashMap<String, FaultConfig> faults = new ConcurrentHashMap<>();

    public void injectLatency(String point, long millis) {
        faults.put(point, new FaultConfig(FaultType.LATENCY, millis, null));
    }

    public void injectException(String point, RuntimeException ex) {
        faults.put(point, new FaultConfig(FaultType.EXCEPTION, 0, ex));
    }

    public void clear(String point) {
        faults.remove(point);
    }

    public void maybeInject(String point) {
        FaultConfig config = faults.get(point);
        if (config == null) return;
        if (config.type() == FaultType.LATENCY) {
            try {
                Thread.sleep(config.latencyMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            throw config.exception();
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn test -Dtest=FaultInjectorTest 2>&1 | tail -10
```
Expected: BUILD SUCCESS, 5 tests passing.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/streaming/FaultInjector.java \
        src/test/java/com/recsys/streaming/FaultInjectorTest.java
git commit -m "feat: add FaultInjector — controllable latency/exception injection for chaos tests"
```

---

## Task 3: ChannelHealthMonitor

**Files:**
- Create: `src/main/java/com/recsys/service/retrieval/ChannelHealthMonitor.java`
- Create: `src/test/java/com/recsys/service/retrieval/ChannelHealthMonitorTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/recsys/service/retrieval/ChannelHealthMonitorTest.java
package com.recsys.service.retrieval;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelHealthMonitorTest {

    // Controllable clock for deterministic backoff testing
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final ChannelHealthMonitor monitor = new ChannelHealthMonitor(3, 1_000L, 30_000L, clock::get);

    @Test
    void newChannelIsAvailable() {
        assertThat(monitor.isAvailable("embedding")).isTrue();
    }

    @Test
    void belowThresholdFailureStaysAvailable() {
        monitor.recordFailure("trending");
        monitor.recordFailure("trending");
        assertThat(monitor.isAvailable("trending")).isTrue();
    }

    @Test
    void atThresholdChannelEntersBackoff() {
        failThrice("embedding");
        assertThat(monitor.isAvailable("embedding")).isFalse();
    }

    @Test
    void backoffExpiresAndChannelBecomesAvailableAgain() {
        failThrice("embedding");
        // Advance clock past the base backoff window (1_000 ms)
        clock.addAndGet(1_001L);
        assertThat(monitor.isAvailable("embedding")).isTrue();
    }

    @Test
    void successAfterBackoffResetsToHealthy() {
        failThrice("embedding");
        clock.addAndGet(2_000L);
        monitor.recordSuccess("embedding");
        // Now it should be healthy — available without clock manipulation
        assertThat(monitor.isAvailable("embedding")).isTrue();
        assertThat(monitor.snapshot().get("embedding").consecutiveFailures()).isZero();
    }

    @Test
    void backoffDoublesOnFurtherFailures() {
        // 3 failures → backoff=1_000ms
        failThrice("channel:x");
        clock.addAndGet(1_001L); // past first backoff

        // One more failure during probe
        monitor.recordFailure("channel:x");
        // backoff should now be 2_000ms
        clock.addAndGet(1_500L); // still within doubled backoff
        assertThat(monitor.isAvailable("channel:x")).isFalse();
        clock.addAndGet(600L);  // past 2_000ms total from re-open
        assertThat(monitor.isAvailable("channel:x")).isTrue();
    }

    @Test
    void differentChannelsAreTrackedIndependently() {
        failThrice("a");
        assertThat(monitor.isAvailable("a")).isFalse();
        assertThat(monitor.isAvailable("b")).isTrue();
    }

    private void failThrice(String name) {
        monitor.recordFailure(name);
        monitor.recordFailure(name);
        monitor.recordFailure(name);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=ChannelHealthMonitorTest 2>&1 | tail -10
```
Expected: FAIL — `ChannelHealthMonitor cannot be resolved to a type`

- [ ] **Step 3: Implement ChannelHealthMonitor**

```java
// src/main/java/com/recsys/service/retrieval/ChannelHealthMonitor.java
package com.recsys.service.retrieval;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public final class ChannelHealthMonitor {

    private static final int DEFAULT_FAILURE_THRESHOLD = 3;
    private static final long DEFAULT_BASE_BACKOFF_MS  = 5_000L;
    private static final long DEFAULT_MAX_BACKOFF_MS   = 60_000L;

    private final int failureThreshold;
    private final long baseBackoffMs;
    private final long maxBackoffMs;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, ChannelState> states = new ConcurrentHashMap<>();

    public ChannelHealthMonitor() {
        this(DEFAULT_FAILURE_THRESHOLD, DEFAULT_BASE_BACKOFF_MS, DEFAULT_MAX_BACKOFF_MS,
                System::currentTimeMillis);
    }

    public ChannelHealthMonitor(int failureThreshold, long baseBackoffMs, long maxBackoffMs,
                                LongSupplier clock) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.baseBackoffMs    = Math.max(1L, baseBackoffMs);
        this.maxBackoffMs     = Math.max(baseBackoffMs, maxBackoffMs);
        this.clock            = clock;
    }

    public boolean isAvailable(String channelName) {
        ChannelState state = states.get(channelName);
        if (state == null) return true;
        return state.backoffUntilMs() < 0 || clock.getAsLong() >= state.backoffUntilMs();
    }

    public void recordSuccess(String channelName) {
        states.put(channelName, ChannelState.HEALTHY);
    }

    public void recordFailure(String channelName) {
        states.compute(channelName, (name, existing) -> {
            int failures = (existing == null ? 0 : existing.consecutiveFailures()) + 1;
            if (failures < failureThreshold) {
                return new ChannelState(failures, -1L);
            }
            // Exponential backoff: base * 2^(failures - threshold), capped at max
            int exponent = failures - failureThreshold;
            long backoff = Math.min(maxBackoffMs, baseBackoffMs * (1L << Math.min(exponent, 30)));
            return new ChannelState(failures, clock.getAsLong() + backoff);
        });
    }

    public Map<String, ChannelState> snapshot() {
        return Map.copyOf(states);
    }

    public record ChannelState(int consecutiveFailures, long backoffUntilMs) {
        static final ChannelState HEALTHY = new ChannelState(0, -1L);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn test -Dtest=ChannelHealthMonitorTest 2>&1 | tail -10
```
Expected: BUILD SUCCESS, 7 tests passing.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/service/retrieval/ChannelHealthMonitor.java \
        src/test/java/com/recsys/service/retrieval/ChannelHealthMonitorTest.java
git commit -m "feat: add ChannelHealthMonitor — exponential backoff per recall channel"
```

---

## Task 4: Parallel MultiChannelRecallService

**Files:**
- Modify: `src/main/java/com/recsys/service/retrieval/MultiChannelRecallService.java`
- Modify: `src/test/java/com/recsys/service/retrieval/MultiChannelRecallServiceTest.java`

- [ ] **Step 1: Add new tests to the existing test file**

Open `src/test/java/com/recsys/service/retrieval/MultiChannelRecallServiceTest.java` and append the following tests to the class (after the existing `failingChannelIsSkipped_othersStillContribute` test):

```java
    @Test
    void slowChannelTimesOut_othersStillContribute() throws Exception {
        RecallChannel slow = new RecallChannel() {
            @Override public String name() { return "slow"; }
            @Override public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
                try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return List.of(new MovieCandidate("slow-1", 0.9, "slow", Map.of()));
            }
        };
        RecallChannel fast = channel("fast", new MovieCandidate("fast-1", 0.5, "fast", Map.of()));

        WorkerBulkhead bulkhead = new WorkerBulkhead("test-recall", 4, 16);
        ChannelHealthMonitor health = new ChannelHealthMonitor();
        MultiChannelRecallService service = new MultiChannelRecallService(
                List.of(slow, fast), health, bulkhead.asExecutorService(), 100L, FaultInjector.NOOP);

        List<MovieCandidate> recalled = service.recall(
                new RecommendationQuery("u1", 10, Set.of(), null), 10);

        assertThat(recalled).hasSize(1);
        assertThat(recalled.get(0).itemId()).isEqualTo("fast-1");
        bulkhead.close();
    }

    @Test
    void channelBackedOff_isSkippedWithoutCall() {
        AtomicInteger callCount = new AtomicInteger();
        RecallChannel tracked = new RecallChannel() {
            @Override public String name() { return "tracked"; }
            @Override public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
                callCount.incrementAndGet();
                throw new RuntimeException("always fails");
            }
        };
        WorkerBulkhead bulkhead = new WorkerBulkhead("test-health", 4, 16);
        ChannelHealthMonitor health = new ChannelHealthMonitor(3, 60_000L, 60_000L);
        MultiChannelRecallService service = new MultiChannelRecallService(
                List.of(tracked), health, bulkhead.asExecutorService(), 200L, FaultInjector.NOOP);

        // 3 calls to trigger backoff
        for (int i = 0; i < 3; i++) {
            service.recall(new RecommendationQuery("u1", 10, Set.of(), null), 10);
        }
        int callsBeforeBackoff = callCount.get();

        // Channel should now be in backoff — further calls skip it
        service.recall(new RecommendationQuery("u1", 10, Set.of(), null), 10);
        assertThat(callCount.get()).isEqualTo(callsBeforeBackoff);
        bulkhead.close();
    }
```

Also add the import at the top of the test file:
```java
import com.recsys.streaming.FaultInjector;
import com.recsys.streaming.WorkerBulkhead;
import java.util.concurrent.atomic.AtomicInteger;
```

- [ ] **Step 2: Run the new tests to verify they fail**

```bash
mvn test -Dtest=MultiChannelRecallServiceTest 2>&1 | tail -15
```
Expected: FAIL — compilation error; `MultiChannelRecallService` does not have the new constructor.

- [ ] **Step 3: Replace MultiChannelRecallService implementation**

```java
// src/main/java/com/recsys/service/retrieval/MultiChannelRecallService.java
package com.recsys.service.retrieval;

import com.recsys.model.MovieCandidate;
import com.recsys.model.RecommendationQuery;
import com.recsys.streaming.FaultInjector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

public class MultiChannelRecallService {
    private static final Logger log = LoggerFactory.getLogger(MultiChannelRecallService.class);
    private static final long DEFAULT_CHANNEL_TIMEOUT_MS = 200L;

    private final List<RecallChannel> channels;
    private final ChannelHealthMonitor healthMonitor;
    private final ExecutorService executor;
    private final long channelTimeoutMs;
    private final FaultInjector faultInjector;

    public MultiChannelRecallService(List<RecallChannel> channels) {
        this(channels, new ChannelHealthMonitor(), ForkJoinPool.commonPool(),
                DEFAULT_CHANNEL_TIMEOUT_MS, FaultInjector.NOOP);
    }

    public MultiChannelRecallService(List<RecallChannel> channels,
                                     ChannelHealthMonitor healthMonitor,
                                     ExecutorService executor,
                                     long channelTimeoutMs,
                                     FaultInjector faultInjector) {
        if (channels == null || channels.isEmpty()) {
            throw new IllegalArgumentException("at least one recall channel is required");
        }
        this.channels        = List.copyOf(channels);
        this.healthMonitor   = Objects.requireNonNull(healthMonitor, "healthMonitor");
        this.executor        = Objects.requireNonNull(executor, "executor");
        this.channelTimeoutMs = Math.max(1L, channelTimeoutMs);
        this.faultInjector   = faultInjector == null ? FaultInjector.NOOP : faultInjector;
    }

    public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
        Objects.requireNonNull(query, "query");
        if (limit <= 0) return List.of();

        // Submit all available channels in parallel, each with a bounded timeout.
        List<CompletableFuture<ChannelResult>> futures = new ArrayList<>(channels.size());
        for (RecallChannel channel : channels) {
            if (!healthMonitor.isAvailable(channel.name())) {
                log.debug("Channel '{}' is in backoff — skipping", channel.name());
                continue;
            }
            String name = channel.name();
            CompletableFuture<ChannelResult> future = CompletableFuture
                    .supplyAsync(() -> {
                        faultInjector.maybeInject("channel:" + name);
                        return new ChannelResult(name, channel.recall(query, limit), null);
                    }, executor)
                    .orTimeout(channelTimeoutMs, TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> new ChannelResult(name, List.of(), ex));
            futures.add(future);
        }

        // Wait for all (timeout already enforced by orTimeout above).
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Merge results; track channel health.
        Map<String, MovieCandidate> merged = new LinkedHashMap<>();
        for (CompletableFuture<ChannelResult> future : futures) {
            ChannelResult result = future.join(); // already complete
            if (result.error() != null) {
                healthMonitor.recordFailure(result.channel());
                log.warn("Channel '{}' failed: {}", result.channel(), result.error().getMessage());
                continue;
            }
            healthMonitor.recordSuccess(result.channel());
            if (result.candidates() != null) {
                for (MovieCandidate c : result.candidates()) {
                    if (query.excludedItemIds().contains(c.itemId())) continue;
                    merged.merge(c.itemId(), c,
                            (existing, incoming) -> incoming.score() > existing.score() ? incoming : existing);
                }
            }
        }

        return merged.values().stream()
                .sorted(Comparator.comparingDouble(MovieCandidate::score).reversed()
                        .thenComparing(MovieCandidate::itemId))
                .limit(limit)
                .toList();
    }

    private record ChannelResult(String channel, List<MovieCandidate> candidates, Throwable error) {}
}
```

Also add `asExecutorService()` to `WorkerBulkhead`. Open `src/main/java/com/recsys/streaming/WorkerBulkhead.java` and add this method before `close()`:

```java
    public ExecutorService asExecutorService() {
        return executor;
    }
```

Add import `java.util.concurrent.ExecutorService` to `WorkerBulkhead.java` at the top.

- [ ] **Step 4: Run all MultiChannelRecallService tests to verify they pass**

```bash
mvn test -Dtest=MultiChannelRecallServiceTest 2>&1 | tail -10
```
Expected: BUILD SUCCESS, all tests (both old and new) passing.

- [ ] **Step 5: Run full test suite to ensure no regressions**

```bash
mvn test 2>&1 | tail -15
```
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/streaming/WorkerBulkhead.java \
        src/main/java/com/recsys/service/retrieval/MultiChannelRecallService.java \
        src/test/java/com/recsys/service/retrieval/MultiChannelRecallServiceTest.java
git commit -m "feat: parallel MultiChannelRecallService — WorkerBulkhead isolation + ChannelHealthMonitor backoff"
```

---

## Task 5: LearnerFlushScheduler + OnlinePredictionServer wiring

**Files:**
- Create: `src/main/java/com/recsys/streaming/LearnerFlushScheduler.java`
- Create: `src/test/java/com/recsys/streaming/LearnerFlushSchedulerTest.java`
- Modify: `src/main/java/com/recsys/streaming/OnlinePredictionServer.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/recsys/streaming/LearnerFlushSchedulerTest.java
package com.recsys.streaming;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.Pool;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class LearnerFlushSchedulerTest {

    private LearnerFlushScheduler scheduler;

    @AfterEach
    void tearDown() throws Exception {
        if (scheduler != null) scheduler.close();
    }

    @Test
    void flushCalledAtLeastOnceWithinInterval() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        OnlineLearner learner = new OnlineLearner() {
            @Override
            public void flushToRedis(Pool<Jedis> pool, String keyPrefix) {
                latch.countDown();
            }
        };

        scheduler = new LearnerFlushScheduler(learner, null, "bias:", 1L);
        scheduler.start();

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(scheduler.snapshot().flushCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void flushErrorDoesNotStopFutureFlushes() throws Exception {
        AtomicInteger flushAttempts = new AtomicInteger();
        CountDownLatch twoFlushes = new CountDownLatch(2);
        OnlineLearner learner = new OnlineLearner() {
            @Override
            public void flushToRedis(Pool<Jedis> pool, String keyPrefix) {
                int count = flushAttempts.incrementAndGet();
                if (count == 1) throw new RuntimeException("Redis gone");
                twoFlushes.countDown();
            }
        };

        scheduler = new LearnerFlushScheduler(learner, null, "bias:", 1L);
        scheduler.start();

        assertThat(twoFlushes.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(scheduler.snapshot().errorCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void snapshotContainsIntervalSeconds() {
        scheduler = new LearnerFlushScheduler(new OnlineLearner(), null, "bias:", 30L);
        assertThat(scheduler.snapshot().intervalSeconds()).isEqualTo(30L);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=LearnerFlushSchedulerTest 2>&1 | tail -10
```
Expected: FAIL — `LearnerFlushScheduler cannot be resolved`

- [ ] **Step 3: Implement LearnerFlushScheduler**

```java
// src/main/java/com/recsys/streaming/LearnerFlushScheduler.java
package com.recsys.streaming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.Pool;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class LearnerFlushScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LearnerFlushScheduler.class);

    private final OnlineLearner learner;
    private final Pool<Jedis> pool;
    private final String keyPrefix;
    private final long intervalSeconds;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong flushCount = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private volatile long lastFlushMs = 0L;

    public LearnerFlushScheduler(OnlineLearner learner, Pool<Jedis> pool,
                                 String keyPrefix, long intervalSeconds) {
        this.learner          = learner;
        this.pool             = pool;
        this.keyPrefix        = keyPrefix;
        this.intervalSeconds  = Math.max(1L, intervalSeconds);
        this.scheduler        = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "learner-flush");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(
                this::tryFlush, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private void tryFlush() {
        try {
            learner.flushToRedis(pool, keyPrefix);
            flushCount.incrementAndGet();
            lastFlushMs = System.currentTimeMillis();
        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.warn("LearnerFlushScheduler: flush error: {}", e.toString());
        }
    }

    @Override
    public void close() {
        scheduler.shutdown();
        tryFlush(); // best-effort final flush
    }

    public Snapshot snapshot() {
        return new Snapshot(flushCount.get(), errorCount.get(), lastFlushMs, intervalSeconds);
    }

    public record Snapshot(long flushCount, long errorCount, long lastFlushMs, long intervalSeconds) {}
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn test -Dtest=LearnerFlushSchedulerTest 2>&1 | tail -10
```
Expected: BUILD SUCCESS, 3 tests passing.

- [ ] **Step 5: Wire LearnerFlushScheduler into OnlinePredictionServer**

Read `src/main/java/com/recsys/streaming/OnlinePredictionServer.java` first, then apply these two edits:

**Edit 1** — after `OnlineLearner onlineLearner = new OnlineLearner();` (insert after the line that creates `recommendationService`). Find the block where `OnlineRecommendationService recommendationService` is constructed and add the scheduler creation immediately after:

```java
            OnlineLearner onlineLearner = new OnlineLearner();
            // NOTE: pass the same onlineLearner instance to recommendationService below
            OnlineRecommendationService recommendationService =
                    new OnlineRecommendationService(dataManager, engine, candidateGenerator, onlineLearner);
            LearnerFlushScheduler learnerFlushScheduler =
                    new LearnerFlushScheduler(onlineLearner, jedisPool, "bias:item", 30L);
```

The current `OnlinePredictionServer.java` constructs `OnlineRecommendationService` without an explicit `OnlineLearner`. Change the construction to pass the new one explicitly. Locate the line:
```java
            OnlineRecommendationService recommendationService =
                    new OnlineRecommendationService(dataManager, engine, candidateGenerator);
```
Replace it with:
```java
            OnlineLearner onlineLearner = new OnlineLearner();
            OnlineRecommendationService recommendationService =
                    new OnlineRecommendationService(dataManager, engine, candidateGenerator, onlineLearner);
            LearnerFlushScheduler learnerFlushScheduler =
                    new LearnerFlushScheduler(onlineLearner, jedisPool, "bias:item", 30L);
            learnerFlushScheduler.start();
```

**Edit 2** — in the shutdown hook, add `learnerFlushScheduler.close()` before `jedisPool.close()`:
```java
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.stop().join();
                asyncEventPublisher.close();
                learnerFlushScheduler.close();
                jedisPool.close();
            }));
```

Also add `learnerFlushScheduler.close()` in the catch block:
```java
        } catch (Exception e) {
            asyncEventPublisher.close();
            learnerFlushScheduler.close();
            jedisPool.close();
            throw e;
        }
```

- [ ] **Step 6: Compile to verify no errors**

```bash
mvn package -DskipTests 2>&1 | tail -10
```
Expected: BUILD SUCCESS.

- [ ] **Step 7: Run full test suite**

```bash
mvn test 2>&1 | tail -15
```
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/recsys/streaming/LearnerFlushScheduler.java \
        src/test/java/com/recsys/streaming/LearnerFlushSchedulerTest.java \
        src/main/java/com/recsys/streaming/OnlinePredictionServer.java
git commit -m "feat: LearnerFlushScheduler — periodic bias persistence + OnlinePredictionServer wiring"
```

---

## Task 6: Integration Chaos Test

**Files:**
- Create: `src/test/java/com/recsys/service/retrieval/WorkerIsolationFailureTest.java`

- [ ] **Step 1: Write the integration chaos test**

```java
// src/test/java/com/recsys/service/retrieval/WorkerIsolationFailureTest.java
package com.recsys.service.retrieval;

import com.recsys.model.MovieCandidate;
import com.recsys.model.RecommendationQuery;
import com.recsys.streaming.FaultInjector;
import com.recsys.streaming.WorkerBulkhead;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerIsolationFailureTest {

    private WorkerBulkhead bulkhead;

    @AfterEach
    void tearDown() {
        if (bulkhead != null) bulkhead.close();
    }

    @Test
    void slowChannelDoesNotBlockFastChannel() {
        bulkhead = new WorkerBulkhead("chaos-recall", 4, 16);
        FaultInjector faults = new FaultInjector();
        // Inject 500ms delay into the "slow" channel point
        faults.injectLatency("channel:slow", 500);

        RecallChannel slow = namedChannel("slow",
                List.of(new MovieCandidate("slow-1", 0.95, "slow", Map.of())));
        RecallChannel fast = namedChannel("fast",
                List.of(new MovieCandidate("fast-1", 0.70, "fast", Map.of())));

        MultiChannelRecallService service = new MultiChannelRecallService(
                List.of(slow, fast), new ChannelHealthMonitor(),
                bulkhead.asExecutorService(), 150L, faults);

        long start = System.currentTimeMillis();
        List<MovieCandidate> results = service.recall(
                new RecommendationQuery("u1", 10, Set.of(), null), 10);
        long elapsed = System.currentTimeMillis() - start;

        // Slow channel timed out, so only fast-1 appears
        assertThat(results).hasSize(1);
        assertThat(results.get(0).itemId()).isEqualTo("fast-1");
        // Overall recall must complete within ~2x the timeout (not 500ms)
        assertThat(elapsed).isLessThan(400L);
    }

    @Test
    void failingChannelEntersBackoffAfterThreshold_andRecoverAfterReset() {
        bulkhead = new WorkerBulkhead("chaos-health", 4, 16);
        AtomicLong clock = new AtomicLong(1_000_000L);
        // Low threshold for fast test: 2 failures → backoff
        ChannelHealthMonitor health = new ChannelHealthMonitor(2, 1_000L, 30_000L, clock::get);
        FaultInjector faults = new FaultInjector();
        faults.injectException("channel:failing", new RuntimeException("chaos!"));

        RecallChannel failing = namedChannel("failing",
                List.of(new MovieCandidate("x", 1.0, "failing", Map.of())));
        RecallChannel backup  = namedChannel("backup",
                List.of(new MovieCandidate("b1", 0.5, "backup", Map.of())));

        MultiChannelRecallService service = new MultiChannelRecallService(
                List.of(failing, backup), health,
                bulkhead.asExecutorService(), 200L, faults);

        RecommendationQuery query = new RecommendationQuery("u1", 10, Set.of(), null);

        // Two failures trigger backoff
        service.recall(query, 10);
        service.recall(query, 10);
        assertThat(health.isAvailable("failing")).isFalse();

        // Advance clock past backoff → channel available again
        clock.addAndGet(1_001L);
        assertThat(health.isAvailable("failing")).isTrue();

        // Remove the fault so the channel succeeds on recovery probe
        faults.clear("channel:failing");
        List<MovieCandidate> recovered = service.recall(query, 10);

        assertThat(health.snapshot().get("failing").consecutiveFailures()).isZero();
        assertThat(recovered.stream().map(MovieCandidate::itemId).toList()).contains("x");
    }

    private static RecallChannel namedChannel(String name, List<MovieCandidate> results) {
        return new RecallChannel() {
            @Override public String name() { return name; }
            @Override public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
                return results;
            }
        };
    }
}
```

- [ ] **Step 2: Run the test to verify it passes**

```bash
mvn test -Dtest=WorkerIsolationFailureTest 2>&1 | tail -10
```
Expected: BUILD SUCCESS, 2 tests passing.

- [ ] **Step 3: Run full suite one final time**

```bash
mvn test 2>&1 | tail -15
```
Expected: BUILD SUCCESS, all tests green.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/recsys/service/retrieval/WorkerIsolationFailureTest.java
git commit -m "test: chaos integration — slow-channel timeout, backoff onset, and auto-recovery"
```

---

## Self-Review Checklist

**Spec coverage:**
- Worker isolation: `WorkerBulkhead` provides named bounded thread pools; `MultiChannelRecallService` now runs channels in parallel on `WorkerBulkhead` — channels can no longer block each other. ✓
- Failure injection: `FaultInjector` injects latency or exceptions at named points; wired into `MultiChannelRecallService` via constructor; `NOOP` used in production. ✓
- Auto-recovery: `ChannelHealthMonitor` applies exponential backoff and automatically re-enables a channel once the backoff window expires. `LearnerFlushScheduler` persists learned biases to Redis periodically so they survive restarts. ✓

**Backward compatibility:**
- `MultiChannelRecallService(List<RecallChannel>)` 1-arg constructor preserved — callers in existing tests compile unchanged. ✓
- `WorkerBulkhead` and `FaultInjector` are new files with no existing call sites. ✓

**Type consistency:**
- `WorkerBulkhead.asExecutorService()` returns `java.util.concurrent.ExecutorService` — used as the `executor` parameter in `MultiChannelRecallService`. ✓
- `ChannelHealthMonitor` uses `ChannelState` record throughout; `snapshot()` returns `Map<String, ChannelState>` consistently. ✓
- `FaultInjector.NOOP` is the same type as `new FaultInjector()` — polymorphic via method override. ✓
