# Catalog Recall Degradation Visibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make catalog 6010's silent recall-quality degradation visible via per-channel degradation metrics on a new `GET /health/load` snapshot and an `X-Recall-Degraded` response header — without changing any request outcome.

**Architecture:** Add an additive `RecallResult` return type and a `RecallDegradationMetrics` counter to `MultiChannelRecallService`; the shared private recall method records degradation for every caller, while two new `*Detailed` methods expose the degraded-channel set to the two 6010 HTTP paths that set the header. A new `CatalogLoadService` serves the counters + live bulkhead state.

**Tech Stack:** Java 17, Armeria (server/`HttpService`), JUnit 5 + AssertJ + Mockito, Jackson, Maven.

## Global Constraints

- **Build/test with JDK 17:** `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...` (repo CLAUDE.md).
- **No behavior change to request outcomes** — degraded responses stay HTTP 200; no status code that was 200 becomes anything else.
- **No Prometheus/Micrometer dependency added to 6010** — signals go through the existing health scrape + a response header.
- **Additive recall API** — `recall(query, limit)` / `recallPrimary(query, limit)` keep returning `List<MovieCandidate>` (callers in 7010 `OnlineRecommendationService`, `ModelRetrievalStage`, and ~15 tests must not break).
- **Primary-channel semantics unchanged** — a failed/rejected primary channel still throws `PrimaryRecallUnavailableException` and is never counted as degradation.
- **Package for new recall types:** `com.recsys.application.retrieval.multichannel`.
- **`WorkerBulkhead.rejected` is always 0 on the catalog path** (recall uses `asExecutorService()`+`supplyAsync`, not `submit()`); the authoritative rejection count is the `RecallDegradationMetrics` `REJECTED` reason. `/health/load` bulkhead section exposes only `poolSize`/`active`/`queued`.

---

## File Structure

- Create `src/main/java/com/recsys/application/retrieval/multichannel/RecallResult.java` — return record (candidates + degradedChannels).
- Create `src/main/java/com/recsys/application/retrieval/multichannel/RecallDegradationMetrics.java` — cumulative counters + `Reason` + `Snapshot`.
- Modify `src/main/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallService.java` — private recall returns `RecallResult`; record metrics; add `recallDetailed`/`recallPrimaryDetailed`; keep `List` methods.
- Modify `src/main/java/com/recsys/application/retrieval/multichannel/RecallConfig.java` — carry `RecallDegradationMetrics`.
- Create `src/main/java/com/recsys/api/serving/CatalogLoadService.java` — `GET /health/load` JSON.
- Modify `src/main/java/com/recsys/api/serving/BaseApiService.java` — `writeJsonWithRecallDegraded` helper.
- Modify `src/main/java/com/recsys/api/serving/RecommendationService.java` — V1 + V2 header wiring.
- Modify `src/main/java/com/recsys/application/recommendation/RecommendationOrchestrator.java` — `recallDetailed` + trace entry.
- Modify `src/main/java/com/recsys/api/serving/RecSysServer.java` — construct/share metrics, register `/health/load`.
- Tests alongside each under `src/test/java/...`.

---

## Task 1: `RecallResult` record

**Files:**
- Create: `src/main/java/com/recsys/application/retrieval/multichannel/RecallResult.java`
- Test: `src/test/java/com/recsys/application/retrieval/multichannel/RecallResultTest.java`

**Interfaces:**
- Produces: `RecallResult(List<MovieCandidate> candidates, Set<String> degradedChannels)`; accessors `candidates()`, `degradedChannels()`. Both non-null; `degradedChannels` unmodifiable.

- [ ] **Step 1: Write the failing test**

```java
package com.recsys.application.retrieval.multichannel;

import com.recsys.domain.item.MovieCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecallResultTest {

    @Test
    void nullsAreRejected() {
        assertThatThrownBy(() -> new RecallResult(null, Set.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RecallResult(List.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void degradedChannelsIsUnmodifiable() {
        RecallResult r = new RecallResult(List.of(), Set.of("trending"));
        assertThat(r.degradedChannels()).containsExactly("trending");
        assertThatThrownBy(() -> r.degradedChannels().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=RecallResultTest`
Expected: FAIL — `RecallResult` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

```java
package com.recsys.application.retrieval.multichannel;

import com.recsys.domain.item.MovieCandidate;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Result of a multichannel recall: the ranked candidates plus the set of
 * non-primary channel names that returned empty due to rejection/timeout/error.
 * An empty {@code degradedChannels} means full-quality recall.
 */
public record RecallResult(List<MovieCandidate> candidates, Set<String> degradedChannels) {
    public RecallResult {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(degradedChannels, "degradedChannels");
        candidates = List.copyOf(candidates);
        degradedChannels = Set.copyOf(degradedChannels);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=RecallResultTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/application/retrieval/multichannel/RecallResult.java \
        src/test/java/com/recsys/application/retrieval/multichannel/RecallResultTest.java
git commit -m "feat: add RecallResult return type for recall degradation visibility"
```

---

## Task 2: `RecallDegradationMetrics`

**Files:**
- Create: `src/main/java/com/recsys/application/retrieval/multichannel/RecallDegradationMetrics.java`
- Test: `src/test/java/com/recsys/application/retrieval/multichannel/RecallDegradationMetricsTest.java`

**Interfaces:**
- Produces:
  - `enum Reason { REJECTED, TIMEOUT, ERROR }`
  - `static Reason classify(Throwable t)` — unwraps `CompletionException`; `RejectedExecutionException → REJECTED`, `TimeoutException → TIMEOUT`, else `ERROR`.
  - `void record(String channel, Reason reason)` — increments the `(channel, reason)` counter and `degradedRecalls`.
  - `void recordTotal()` — increments `totalRecalls`.
  - `Snapshot snapshot()` → `Snapshot(Map<String, Map<Reason, Long>> byChannel, long totalRecalls, long degradedRecalls, double degradedRatio)`; `degradedRatio = totalRecalls == 0 ? 0.0 : degradedRecalls / (double) totalRecalls`.

- [ ] **Step 1: Write the failing test**

```java
package com.recsys.application.retrieval.multichannel;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RecallDegradationMetricsTest {

    @Test
    void classifyMapsThrowableTypesAndUnwrapsCompletionException() {
        assertThat(RecallDegradationMetrics.classify(new RejectedExecutionException()))
                .isEqualTo(RecallDegradationMetrics.Reason.REJECTED);
        assertThat(RecallDegradationMetrics.classify(new TimeoutException()))
                .isEqualTo(RecallDegradationMetrics.Reason.TIMEOUT);
        assertThat(RecallDegradationMetrics.classify(new CompletionException(new TimeoutException())))
                .isEqualTo(RecallDegradationMetrics.Reason.TIMEOUT);
        assertThat(RecallDegradationMetrics.classify(new IllegalStateException("boom")))
                .isEqualTo(RecallDegradationMetrics.Reason.ERROR);
    }

    @Test
    void snapshotCountsAndRatio() {
        RecallDegradationMetrics m = new RecallDegradationMetrics();
        m.recordTotal();
        m.recordTotal();
        m.record("trending", RecallDegradationMetrics.Reason.REJECTED);
        m.record("trending", RecallDegradationMetrics.Reason.TIMEOUT);

        RecallDegradationMetrics.Snapshot s = m.snapshot();
        assertThat(s.totalRecalls()).isEqualTo(2);
        assertThat(s.degradedRecalls()).isEqualTo(2);
        assertThat(s.byChannel().get("trending"))
                .containsEntry(RecallDegradationMetrics.Reason.REJECTED, 1L)
                .containsEntry(RecallDegradationMetrics.Reason.TIMEOUT, 1L);
        assertThat(s.degradedRatio()).isEqualTo(1.0, within(1e-9));
    }

    @Test
    void zeroTrafficRatioIsZeroNotNaN() {
        assertThat(new RecallDegradationMetrics().snapshot().degradedRatio())
                .isEqualTo(0.0);
    }

    @Test
    void concurrentRecordsAreCounted() throws InterruptedException {
        RecallDegradationMetrics m = new RecallDegradationMetrics();
        int threads = 8, perThread = 1000;
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            ts[i] = new Thread(() -> {
                for (int j = 0; j < perThread; j++) {
                    m.recordTotal();
                    m.record("c", RecallDegradationMetrics.Reason.REJECTED);
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) t.join();
        RecallDegradationMetrics.Snapshot s = m.snapshot();
        assertThat(s.totalRecalls()).isEqualTo((long) threads * perThread);
        assertThat(s.byChannel().get("c").get(RecallDegradationMetrics.Reason.REJECTED))
                .isEqualTo((long) threads * perThread);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=RecallDegradationMetricsTest`
Expected: FAIL — `RecallDegradationMetrics` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.recsys.application.retrieval.multichannel;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Cumulative, thread-safe counters for silent recall-channel degradation on the
 * catalog path. Recorded inside {@link MultiChannelRecallService} for every caller;
 * surfaced (on 6010) by {@code CatalogLoadService} at {@code GET /health/load}.
 */
public final class RecallDegradationMetrics {

    public enum Reason { REJECTED, TIMEOUT, ERROR }

    private final Map<String, Map<Reason, LongAdder>> byChannel = new ConcurrentHashMap<>();
    private final AtomicLong totalRecalls = new AtomicLong();
    private final AtomicLong degradedRecalls = new AtomicLong();

    public static Reason classify(Throwable t) {
        Throwable c = t;
        while (c instanceof CompletionException && c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        if (c instanceof RejectedExecutionException) return Reason.REJECTED;
        if (c instanceof TimeoutException) return Reason.TIMEOUT;
        return Reason.ERROR;
    }

    /** One non-primary recall invocation (the denominator for degradedRatio). */
    public void recordTotal() {
        totalRecalls.incrementAndGet();
    }

    /** One degraded non-primary channel within a recall. */
    public void record(String channel, Reason reason) {
        byChannel
                .computeIfAbsent(channel, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(reason, k -> new LongAdder())
                .increment();
        degradedRecalls.incrementAndGet();
    }

    public Snapshot snapshot() {
        Map<String, Map<Reason, Long>> out = new LinkedHashMap<>();
        byChannel.forEach((channel, reasons) -> {
            Map<Reason, Long> m = new EnumMap<>(Reason.class);
            reasons.forEach((reason, adder) -> m.put(reason, adder.sum()));
            out.put(channel, m);
        });
        long total = totalRecalls.get();
        long degraded = degradedRecalls.get();
        double ratio = total == 0 ? 0.0 : degraded / (double) total;
        return new Snapshot(out, total, degraded, ratio);
    }

    public record Snapshot(Map<String, Map<Reason, Long>> byChannel,
                           long totalRecalls,
                           long degradedRecalls,
                           double degradedRatio) {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=RecallDegradationMetricsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/application/retrieval/multichannel/RecallDegradationMetrics.java \
        src/test/java/com/recsys/application/retrieval/multichannel/RecallDegradationMetricsTest.java
git commit -m "feat: add RecallDegradationMetrics counters"
```

---

## Task 3: Record degradation in `MultiChannelRecallService` + `*Detailed` methods

**Files:**
- Modify: `src/main/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallService.java`
- Test: `src/test/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallDegradationTest.java`

**Interfaces:**
- Consumes: `RecallResult` (Task 1), `RecallDegradationMetrics` (Task 2).
- Produces:
  - New 8-arg constructor adding a trailing `RecallDegradationMetrics metrics` parameter; existing constructors delegate passing `new RecallDegradationMetrics()`.
  - `RecallResult recallDetailed(RecommendationQuery query, int limit)` (primary=false).
  - `RecallResult recallPrimaryDetailed(RecommendationQuery query, int limit)` (primary=true).
  - `recall(query, limit)` / `recallPrimary(query, limit)` unchanged signatures, now delegating to the detailed variants and returning `.candidates()`.

- [ ] **Step 1: Write the failing test**

```java
package com.recsys.application.retrieval.multichannel;

import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.application.retrieval.RecallChannel;
import com.recsys.resilience.FaultInjector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultiChannelRecallDegradationTest {

    private static RecommendationQuery query() {
        return new RecommendationQuery("1", 10, Set.of(), null);
    }

    /** A channel whose recall always throws — used to force a degraded non-primary channel. */
    private static final class FailingChannel implements RecallChannel {
        private final String name;
        FailingChannel(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public List<MovieCandidate> recall(RecommendationQuery q, int limit) {
            throw new IllegalStateException("boom");
        }
        @Override public List<MovieCandidate> recallPrimary(RecommendationQuery q, int limit) {
            throw new IllegalStateException("boom");
        }
    }

    private static final class OkChannel implements RecallChannel {
        @Override public String name() { return "ok"; }
        @Override public List<MovieCandidate> recall(RecommendationQuery q, int limit) {
            return List.of(new MovieCandidate("100", 0.9, "ok", java.util.Map.of()));
        }
        @Override public List<MovieCandidate> recallPrimary(RecommendationQuery q, int limit) {
            return recall(q, limit);
        }
    }

    @Test
    void nonPrimaryChannelErrorIsRecordedAndReportedButStillServes() {
        RecallDegradationMetrics metrics = new RecallDegradationMetrics();
        MultiChannelRecallService service = new MultiChannelRecallService(
                List.of(new OkChannel(), new FailingChannel("trending")),
                new ChannelHealthMonitor(),
                java.util.concurrent.Executors.newFixedThreadPool(2),
                200L, FaultInjector.NOOP, null,
                com.recsys.application.retrieval.coldstart.QuotaPolicy.defaultMovie(),
                metrics);

        RecallResult result = service.recallDetailed(query(), 10);

        assertThat(result.degradedChannels()).contains("trending");
        assertThat(result.candidates()).isNotEmpty(); // ok channel still served
        RecallDegradationMetrics.Snapshot s = metrics.snapshot();
        assertThat(s.totalRecalls()).isEqualTo(1);
        assertThat(s.degradedRecalls()).isEqualTo(1);
        assertThat(s.byChannel()).containsKey("trending");
    }

    @Test
    void bulkheadRejectionIsClassifiedAsRejected() {
        // A pool with 0 queue slots and one busy thread rejects the second task.
        ThreadPoolExecutor tiny = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        RecallDegradationMetrics metrics = new RecallDegradationMetrics();
        // Two channels both dispatched; one is guaranteed rejected under saturation.
        MultiChannelRecallService service = new MultiChannelRecallService(
                List.of(new SlowChannel(), new SlowChannel2()),
                new ChannelHealthMonitor(), tiny, 5000L, FaultInjector.NOOP, null,
                com.recsys.application.retrieval.coldstart.QuotaPolicy.defaultMovie(),
                metrics);

        RecallResult result = service.recallDetailed(query(), 10);

        assertThat(result.degradedChannels()).isNotEmpty();
        assertThat(metrics.snapshot().byChannel().values().stream()
                .anyMatch(m -> m.containsKey(RecallDegradationMetrics.Reason.REJECTED)))
                .isTrue();
        tiny.shutdownNow();
    }

    @Test
    void primaryChannelFailureThrowsAndIsNotCountedAsDegradation() {
        RecallDegradationMetrics metrics = new RecallDegradationMetrics();
        MultiChannelRecallService service = new MultiChannelRecallService(
                List.of(new FailingChannel("primary")),
                new ChannelHealthMonitor(),
                java.util.concurrent.Executors.newFixedThreadPool(1),
                200L, FaultInjector.NOOP, null,
                com.recsys.application.retrieval.coldstart.QuotaPolicy.defaultMovie(),
                metrics);

        assertThatThrownBy(() -> service.recallPrimaryDetailed(query(), 10))
                .isInstanceOf(MultiChannelRecallService.PrimaryRecallUnavailableException.class);
        // primary path does not touch the non-primary denominator
        assertThat(metrics.snapshot().totalRecalls()).isEqualTo(0);
        assertThat(metrics.snapshot().degradedRecalls()).isEqualTo(0);
    }

    private static final class SlowChannel implements RecallChannel {
        @Override public String name() { return "slow1"; }
        @Override public List<MovieCandidate> recall(RecommendationQuery q, int limit) {
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return List.of(new MovieCandidate("1", 0.5, "slow1", java.util.Map.of()));
        }
        @Override public List<MovieCandidate> recallPrimary(RecommendationQuery q, int limit) { return recall(q, limit); }
    }

    private static final class SlowChannel2 implements RecallChannel {
        @Override public String name() { return "slow2"; }
        @Override public List<MovieCandidate> recall(RecommendationQuery q, int limit) {
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return List.of(new MovieCandidate("2", 0.5, "slow2", java.util.Map.of()));
        }
        @Override public List<MovieCandidate> recallPrimary(RecommendationQuery q, int limit) { return recall(q, limit); }
    }
}
```

> Note: verify the `MovieCandidate` and `RecallChannel` shapes before running — if
> `MovieCandidate`'s canonical constructor differs from `(String itemId, double score,
> String source)`, adjust the test's candidate construction to match. This does not
> change the production code below.

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=MultiChannelRecallDegradationTest`
Expected: FAIL — `recallDetailed` / 8-arg constructor do not exist.

- [ ] **Step 3: Add the metrics field, new constructor, and detailed methods**

In `MultiChannelRecallService.java`:

Add the field after `private final QuotaPolicy quotaPolicy;`:

```java
    private final RecallDegradationMetrics degradationMetrics;
```

Change the 7-arg constructor (currently the widest) to delegate to a new 8-arg one — replace the existing `public MultiChannelRecallService(... QuotaPolicy quotaPolicy) { ... }` body with a delegation, and add the 8-arg constructor:

```java
    public MultiChannelRecallService(List<RecallChannel> channels,
                                     ChannelHealthMonitor healthMonitor,
                                     ExecutorService executor,
                                     long channelTimeoutMs,
                                     FaultInjector faultInjector,
                                     EmbeddingStore userEmbeddingStore,
                                     QuotaPolicy quotaPolicy) {
        this(channels, healthMonitor, executor, channelTimeoutMs, faultInjector,
                userEmbeddingStore, quotaPolicy, new RecallDegradationMetrics());
    }

    public MultiChannelRecallService(List<RecallChannel> channels,
                                     ChannelHealthMonitor healthMonitor,
                                     ExecutorService executor,
                                     long channelTimeoutMs,
                                     FaultInjector faultInjector,
                                     EmbeddingStore userEmbeddingStore,
                                     QuotaPolicy quotaPolicy,
                                     RecallDegradationMetrics degradationMetrics) {
        if (channels == null || channels.isEmpty()) {
            throw new IllegalArgumentException("at least one recall channel is required");
        }
        this.channels            = List.copyOf(channels);
        this.healthMonitor       = Objects.requireNonNull(healthMonitor, "healthMonitor");
        this.executor            = Objects.requireNonNull(executor, "executor");
        this.channelTimeoutMs    = Math.max(1L, channelTimeoutMs);
        this.faultInjector       = faultInjector == null ? FaultInjector.NOOP : faultInjector;
        this.userEmbeddingStore  = userEmbeddingStore;
        this.quotaPolicy         = quotaPolicy == null ? QuotaPolicy.defaultMovie() : quotaPolicy;
        this.degradationMetrics  = degradationMetrics == null
                ? new RecallDegradationMetrics() : degradationMetrics;
    }
```

Update the `from(RecallConfig config)` factory to pass the metrics (Task 4 adds the accessor; use it here):

```java
    public static MultiChannelRecallService from(RecallConfig config) {
        java.util.Objects.requireNonNull(config, "config");
        return new MultiChannelRecallService(
                config.channels(),
                config.healthMonitor(),
                config.executor(),
                config.channelTimeoutMs(),
                config.faultInjector(),
                config.userEmbeddingStore(),
                config.quotaPolicy(),
                config.recallMetrics());
    }
```

Replace the public `recall`/`recallPrimary` methods and add the detailed variants:

```java
    public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
        return recall(query, limit, false).candidates();
    }

    public List<MovieCandidate> recallPrimary(RecommendationQuery query, int limit) {
        return recall(query, limit, true).candidates();
    }

    public RecallResult recallDetailed(RecommendationQuery query, int limit) {
        return recall(query, limit, false);
    }

    public RecallResult recallPrimaryDetailed(RecommendationQuery query, int limit) {
        return recall(query, limit, true);
    }
```

Change the private `recall` signature to return `RecallResult` and record degradation. Replace `private List<MovieCandidate> recall(RecommendationQuery query, int limit, boolean primary) {` with `private RecallResult recall(RecommendationQuery query, int limit, boolean primary) {`, and:

- After `if (limit <= 0) return List.of();` change that line to:

```java
        if (limit <= 0) return new RecallResult(List.of(), Set.of());
        if (!primary) degradationMetrics.recordTotal();
```

- Add a degraded-set accumulator before the result-collection loop (next to `Map<String, List<MovieCandidate>> channelResults = new LinkedHashMap<>();`):

```java
        Set<String> degradedChannels = new LinkedHashSet<>();
```

- Inside the result-collection loop, in the `if (result.error() != null) { ... }` branch, add recording just before `continue;`:

```java
            if (result.error() != null) {
                healthMonitor.recordFailure(result.channel());
                if (!primary) {
                    degradationMetrics.record(result.channel(),
                            RecallDegradationMetrics.classify(result.error()));
                    degradedChannels.add(result.channel());
                }
                Throwable err = result.error();
                log.warn("Channel '{}' failed: {}", result.channel(),
                        err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName());
                continue;
            }
```

- Replace the two `return legacyMerge(...)` / `return quotaMerge(...)` tail statements with:

```java
        List<MovieCandidate> ranked = (quota == null)
                ? legacyMerge(channelResults, query, limit)
                : quotaMerge(channelResults, quota, query, limit);
        return new RecallResult(ranked, degradedChannels);
```

Add imports if not already present: `com.recsys.application.retrieval.multichannel.RecallResult` is same-package (no import needed); ensure `java.util.LinkedHashSet` and `java.util.Set` are imported (both already are).

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=MultiChannelRecallDegradationTest,MultiChannelRecallServiceTest,RecallConfigTest`
Expected: PASS — new degradation test passes and the existing recall tests still pass (List-returning methods unchanged). If `RecallConfigTest` fails to compile because `config.recallMetrics()` does not exist yet, temporarily skip it and run it in Task 4; the `from(...)` change depends on Task 4's accessor. To keep this task self-contained, do Task 4 **Step 3** (add `recallMetrics` to `RecallConfig`) before running.

> Sequencing note: Tasks 3 and 4 are mutually dependent (the `from(...)` factory
> references `config.recallMetrics()`). Apply Task 4's `RecallConfig` change, then
> compile. If you prefer strict TDD isolation, land Task 4 first.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallService.java \
        src/test/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallDegradationTest.java
git commit -m "feat: record recall degradation and expose recallDetailed variants"
```

---

## Task 4: Thread `RecallDegradationMetrics` through `RecallConfig`

**Files:**
- Modify: `src/main/java/com/recsys/application/retrieval/multichannel/RecallConfig.java`
- Test: `src/test/java/com/recsys/application/retrieval/multichannel/RecallConfigMetricsTest.java`

**Interfaces:**
- Produces: `RecallConfig.recallMetrics()` accessor returning a non-null `RecallDegradationMetrics`; `Builder.recallMetrics(RecallDegradationMetrics)`; default is a fresh instance when unset.

- [ ] **Step 1: Write the failing test**

```java
package com.recsys.application.retrieval.multichannel;

import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.application.retrieval.RecallChannel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class RecallConfigMetricsTest {

    private static final class OkChannel implements RecallChannel {
        @Override public String name() { return "ok"; }
        @Override public List<MovieCandidate> recall(RecommendationQuery q, int limit) {
            return List.of(new MovieCandidate("1", 0.5, "ok", java.util.Map.of()));
        }
        @Override public List<MovieCandidate> recallPrimary(RecommendationQuery q, int limit) { return recall(q, limit); }
    }

    @Test
    void builderDefaultsToNonNullMetrics() {
        RecallConfig config = RecallConfig.builder()
                .channels(List.of(new OkChannel()))
                .executor(Executors.newSingleThreadExecutor())
                .build();
        assertThat(config.recallMetrics()).isNotNull();
    }

    @Test
    void builderCarriesSuppliedMetricsInstance() {
        RecallDegradationMetrics metrics = new RecallDegradationMetrics();
        RecallConfig config = RecallConfig.builder()
                .channels(List.of(new OkChannel()))
                .executor(Executors.newSingleThreadExecutor())
                .recallMetrics(metrics)
                .build();
        assertThat(config.recallMetrics()).isSameAs(metrics);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=RecallConfigMetricsTest`
Expected: FAIL — `recallMetrics` does not exist.

- [ ] **Step 3: Add the field, accessor, builder method, and default**

In `RecallConfig.java`:

- Add `RecallDegradationMetrics recallMetrics` as the final record component:

```java
public record RecallConfig(
        List<RecallChannel> channels,
        QuotaPolicy quotaPolicy,
        long channelTimeoutMs,
        ExecutorService executor,
        ChannelHealthMonitor healthMonitor,
        FaultInjector faultInjector,
        EmbeddingStore userEmbeddingStore,
        RecallDegradationMetrics recallMetrics) {
```

> Match the record's actual current component order when adding `recallMetrics` as the
> last component; adjust the `build()` constructor call accordingly.

- In `Builder`, add the field + setter:

```java
        private RecallDegradationMetrics recallMetrics = new RecallDegradationMetrics();

        public Builder recallMetrics(RecallDegradationMetrics metrics) {
            this.recallMetrics = metrics == null ? new RecallDegradationMetrics() : metrics;
            return this;
        }
```

- In `build()`, pass `recallMetrics` as the final argument to the `new RecallConfig(...)` call.

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=RecallConfigMetricsTest,RecallConfigTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/application/retrieval/multichannel/RecallConfig.java \
        src/test/java/com/recsys/application/retrieval/multichannel/RecallConfigMetricsTest.java
git commit -m "feat: carry RecallDegradationMetrics through RecallConfig"
```

---

## Task 5: `CatalogLoadService` — `GET /health/load`

**Files:**
- Create: `src/main/java/com/recsys/api/serving/CatalogLoadService.java`
- Test: `src/test/java/com/recsys/api/serving/CatalogLoadServiceTest.java`

**Interfaces:**
- Consumes: `WorkerBulkhead` (`snapshot()` → `active/queued/poolSize`), `RecallDegradationMetrics` (`snapshot()`).
- Produces: an Armeria `HttpService`. `GET` returns `200` JSON `{ "recall": { "bulkhead": {poolSize,active,queued}, "channelDegraded": {channel: {reason: count}}, "degradedRatio": <double> } }`.

- [ ] **Step 1: Write the failing test**

```java
package com.recsys.api.serving;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.application.retrieval.multichannel.RecallDegradationMetrics;
import com.recsys.resilience.WorkerBulkhead;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogLoadServiceTest {

    @Test
    void reportsBulkheadAndDegradationSnapshot() throws Exception {
        WorkerBulkhead bulkhead = new WorkerBulkhead("recall-catalog", 4, 16);
        RecallDegradationMetrics metrics = new RecallDegradationMetrics();
        metrics.recordTotal();
        metrics.recordTotal();
        metrics.record("trending", RecallDegradationMetrics.Reason.REJECTED);

        CatalogLoadService service = new CatalogLoadService(bulkhead, metrics);

        HttpRequest req = HttpRequest.of(HttpMethod.GET, "/health/load");
        ServiceRequestContext ctx = ServiceRequestContext.of(req);
        AggregatedHttpResponse res = service.serve(ctx, req).aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        JsonNode root = new ObjectMapper().readTree(res.contentUtf8());
        JsonNode recall = root.get("recall");
        assertThat(recall.get("bulkhead").get("poolSize").asInt()).isEqualTo(4);
        assertThat(recall.get("bulkhead").has("rejected")).isFalse();
        assertThat(recall.get("channelDegraded").get("trending").get("REJECTED").asLong())
                .isEqualTo(1L);
        assertThat(recall.get("degradedRatio").asDouble()).isEqualTo(0.5);

        bulkhead.close();
    }

    @Test
    void zeroTrafficRatioIsZero() throws Exception {
        CatalogLoadService service = new CatalogLoadService(
                new WorkerBulkhead("recall-catalog", 2, 4), new RecallDegradationMetrics());
        HttpRequest req = HttpRequest.of(HttpMethod.GET, "/health/load");
        AggregatedHttpResponse res = service.serve(ServiceRequestContext.of(req), req).aggregate().join();
        JsonNode root = new ObjectMapper().readTree(res.contentUtf8());
        assertThat(root.get("recall").get("degradedRatio").asDouble()).isEqualTo(0.0);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=CatalogLoadServiceTest`
Expected: FAIL — `CatalogLoadService` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.recsys.api.serving;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.application.retrieval.multichannel.RecallDegradationMetrics;
import com.recsys.resilience.WorkerBulkhead;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * GET /health/load — catalog serving (6010) load snapshot: live recall-bulkhead
 * pressure plus cumulative silent-degradation counters. Read-only; normal gateway
 * auth. Note: the bulkhead's own {@code rejected} counter is always 0 on this path
 * (recall runs via asExecutorService()+supplyAsync, not submit()), so it is omitted;
 * the authoritative rejection count is channelDegraded[*].rejected.
 */
public final class CatalogLoadService extends BaseApiService {

    private final WorkerBulkhead recallBulkhead;
    private final RecallDegradationMetrics degradationMetrics;

    public CatalogLoadService(WorkerBulkhead recallBulkhead,
                              RecallDegradationMetrics degradationMetrics) {
        this.recallBulkhead = Objects.requireNonNull(recallBulkhead, "recallBulkhead");
        this.degradationMetrics = Objects.requireNonNull(degradationMetrics, "degradationMetrics");
    }

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
        WorkerBulkhead.Snapshot b = recallBulkhead.snapshot();
        RecallDegradationMetrics.Snapshot d = degradationMetrics.snapshot();

        Map<String, Object> bulkhead = new LinkedHashMap<>();
        bulkhead.put("poolSize", b.poolSize());
        bulkhead.put("active", b.active());
        bulkhead.put("queued", b.queued());

        Map<String, Object> channelDegraded = new LinkedHashMap<>();
        d.byChannel().forEach((channel, reasons) -> {
            Map<String, Long> byReason = new LinkedHashMap<>();
            reasons.forEach((reason, count) -> byReason.put(reason.name(), count));
            channelDegraded.put(channel, byReason);
        });

        Map<String, Object> recall = new LinkedHashMap<>();
        recall.put("bulkhead", bulkhead);
        recall.put("channelDegraded", channelDegraded);
        recall.put("degradedRatio", d.degradedRatio());

        return writeJson(HttpStatus.OK, Map.of("recall", recall));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=CatalogLoadServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/api/serving/CatalogLoadService.java \
        src/test/java/com/recsys/api/serving/CatalogLoadServiceTest.java
git commit -m "feat: add CatalogLoadService for GET /health/load"
```

---

## Task 6: `X-Recall-Degraded` header helper + V1 wiring

**Files:**
- Modify: `src/main/java/com/recsys/api/serving/BaseApiService.java`
- Modify: `src/main/java/com/recsys/api/serving/RecommendationService.java`
- Test: `src/test/java/com/recsys/api/serving/RecommendationV1DegradedHeaderTest.java`

**Interfaces:**
- Produces: `BaseApiService.writeJsonWithRecallDegraded(HttpStatus, Object, Set<String>)` — sets `X-Recall-Degraded: <comma-joined>` when the set is non-empty, else identical to `writeJson`.
- Consumes: `MultiChannelRecallService.recallDetailed(...)` (Task 3), `RecallResult` (Task 1).

- [ ] **Step 1: Write the failing test**

```java
package com.recsys.api.serving;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.application.retrieval.multichannel.MultiChannelRecallService;
import com.recsys.application.retrieval.multichannel.RecallResult;
import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.user.User;
import com.recsys.infrastructure.dataloading.DataManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecommendationV1DegradedHeaderTest {

    @Test
    void setsHeaderWhenChannelsDegraded() {
        DataManager dm = mock(DataManager.class);
        when(dm.getUserById(1)).thenReturn(mock(User.class));
        when(dm.getWatchedMovieIds(1)).thenReturn(Set.of());
        MultiChannelRecallService recall = mock(MultiChannelRecallService.class);
        when(recall.recallDetailed(any(), anyInt()))
                .thenReturn(new RecallResult(List.<MovieCandidate>of(), Set.of("trending", "momentum")));

        RecommendationService.V1 v1 = new RecommendationService.V1(dm, recall);
        HttpRequest req = HttpRequest.of(HttpMethod.GET, "/recommendation?userId=1&k=5");
        AggregatedHttpResponse res = v1.serve(ServiceRequestContext.of(req), req).aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get("x-recall-degraded")).isEqualTo("trending,momentum");
    }

    @Test
    void noHeaderWhenNotDegraded() {
        DataManager dm = mock(DataManager.class);
        when(dm.getUserById(1)).thenReturn(mock(User.class));
        when(dm.getWatchedMovieIds(1)).thenReturn(Set.of());
        MultiChannelRecallService recall = mock(MultiChannelRecallService.class);
        when(recall.recallDetailed(any(), anyInt()))
                .thenReturn(new RecallResult(List.<MovieCandidate>of(), Set.of()));

        RecommendationService.V1 v1 = new RecommendationService.V1(dm, recall);
        HttpRequest req = HttpRequest.of(HttpMethod.GET, "/recommendation?userId=1&k=5");
        AggregatedHttpResponse res = v1.serve(ServiceRequestContext.of(req), req).aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get("x-recall-degraded")).isNull();
    }
}
```

> Note: confirm `RecommendationResponse`/`User`/`Movie` construction paths tolerate the
> mocked `DataManager` returning empty lists; if `getWatchedMovieIds` or `getMovieById`
> require specific stubbing to avoid NPEs on the mock, add the minimal `when(...)` stubs.
> This does not change the production code.

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=RecommendationV1DegradedHeaderTest`
Expected: FAIL — `recallDetailed` not used yet / no header set.

- [ ] **Step 3: Add the helper and wire V1**

In `BaseApiService.java`, add imports if missing (`java.util.Set`) and the helper:

```java
    /**
     * Like {@link #writeJson} but adds {@code X-Recall-Degraded: <comma-joined>} when
     * {@code degradedChannels} is non-empty. Signals silent recall-quality degradation
     * without changing the response status or body.
     */
    protected static HttpResponse writeJsonWithRecallDegraded(HttpStatus status, Object payload,
                                                              java.util.Set<String> degradedChannels) {
        if (degradedChannels == null || degradedChannels.isEmpty()) {
            return writeJson(status, payload);
        }
        try {
            byte[] body = MAPPER.writeValueAsBytes(payload);
            ResponseHeaders headers = ResponseHeaders.builder(status)
                    .contentType(MediaType.JSON_UTF_8)
                    .set(HttpHeaderNames.of("x-recall-degraded"), String.join(",", degradedChannels))
                    .build();
            return HttpResponse.of(headers, HttpData.wrap(body));
        } catch (Exception e) {
            return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "serialization error");
        }
    }
```

In `RecommendationService.V1.doGet`, replace the recall call + return:

```java
                    RecallResult recall = recallService.recallDetailed(query, k * RECALL_MULTIPLIER);
                    List<MovieCandidate> candidates = recall.candidates();
                    List<Movie> movies = candidates.stream()
                            .map(c -> {
                                try { return dataManager.getMovieById(Integer.parseInt(c.itemId())); }
                                catch (NumberFormatException e) { return null; }
                            })
                            .filter(Objects::nonNull)
                            .toList();

                    return writeJsonWithRecallDegraded(HttpStatus.OK,
                            new RecommendationResponse(user, movies), recall.degradedChannels());
```

Add the import at the top of `RecommendationService.java`:

```java
import com.recsys.application.retrieval.multichannel.RecallResult;
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=RecommendationV1DegradedHeaderTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/api/serving/BaseApiService.java \
        src/main/java/com/recsys/api/serving/RecommendationService.java \
        src/test/java/com/recsys/api/serving/RecommendationV1DegradedHeaderTest.java
git commit -m "feat: X-Recall-Degraded header on V1 recommendation path"
```

---

## Task 7: V2 path — trace entry + header

**Files:**
- Modify: `src/main/java/com/recsys/application/recommendation/RecommendationOrchestrator.java`
- Modify: `src/main/java/com/recsys/api/serving/RecommendationService.java` (V2)
- Test: `src/test/java/com/recsys/application/recommendation/RecommendationOrchestratorDegradedTest.java`

**Interfaces:**
- Consumes: `MultiChannelRecallService.recallDetailed(...)`, `RecallResult`, `RecommendationResult.trace()`.
- Produces: `RecommendationResult.trace()` contains `degradedChannels` (comma-joined) when non-empty; V2 handler sets `X-Recall-Degraded` from it.

- [ ] **Step 1: Write the failing test**

```java
package com.recsys.application.recommendation;

import com.recsys.application.pagination.CursorPaginationService;
import com.recsys.application.ranking.CandidateRanker;
import com.recsys.application.retrieval.multichannel.MultiChannelRecallService;
import com.recsys.application.retrieval.multichannel.RecallResult;
import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.item.RankedMovie;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.recommendation.RecommendationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecommendationOrchestratorDegradedTest {

    @Test
    void degradedChannelsAppearInTrace() {
        MultiChannelRecallService recall = mock(MultiChannelRecallService.class);
        when(recall.recallDetailed(any(), anyInt()))
                .thenReturn(new RecallResult(List.<MovieCandidate>of(), Set.of("trending")));
        CandidateRanker ranker = mock(CandidateRanker.class);
        when(ranker.rank(any(), any(), anyInt())).thenReturn(List.<RankedMovie>of());

        RecommendationOrchestrator orch = new RecommendationOrchestrator(
                recall, ranker, null, new CursorPaginationService());

        RecommendationResult result = orch.recommend(
                new RecommendationQuery("1", 10, Set.of(), null));

        assertThat(result.trace()).containsEntry("degradedChannels", "trending");
    }

    @Test
    void noDegradedChannelsKeyWhenFullQuality() {
        MultiChannelRecallService recall = mock(MultiChannelRecallService.class);
        when(recall.recallDetailed(any(), anyInt()))
                .thenReturn(new RecallResult(List.<MovieCandidate>of(), Set.of()));
        CandidateRanker ranker = mock(CandidateRanker.class);
        when(ranker.rank(any(), any(), anyInt())).thenReturn(List.<RankedMovie>of());

        RecommendationOrchestrator orch = new RecommendationOrchestrator(
                recall, ranker, null, new CursorPaginationService());

        RecommendationResult result = orch.recommend(
                new RecommendationQuery("1", 10, Set.of(), null));

        assertThat(result.trace()).doesNotContainKey("degradedChannels");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=RecommendationOrchestratorDegradedTest`
Expected: FAIL — orchestrator still uses `recall(...)` and does not add the trace key.

- [ ] **Step 3: Wire the orchestrator + V2 handler**

In `RecommendationOrchestrator.recommend`, replace the recall call and trace map:

```java
    @Override
    public RecommendationResult recommend(RecommendationQuery query) {
        Objects.requireNonNull(query, "query");
        int windowLimit = query.limit() * recallMultiplier;
        com.recsys.application.retrieval.multichannel.RecallResult recall =
                recallService.recallDetailed(query, windowLimit);
        List<MovieCandidate> candidates = recall.candidates();
        List<RankedMovie> ranked = ranker.rank(query, candidates, windowLimit);
        Page<RankedMovie> page = paginationService.page(
                ranked, query.cursor(), query.limit(), RankedMovie::score, RankedMovie::itemId);
        List<RankedMovie> hydrated = hydrator.hydrate(query, page.items());

        Map<String, String> trace = new java.util.LinkedHashMap<>();
        trace.put("candidateCount", Integer.toString(candidates.size()));
        trace.put("rankedCount", Integer.toString(ranked.size()));
        if (!recall.degradedChannels().isEmpty()) {
            trace.put("degradedChannels", String.join(",", recall.degradedChannels()));
        }

        return new RecommendationResult(query.userId(), hydrated, page.nextCursor(), trace);
    }
```

(Remove the now-unused `Map.of(...)` import only if nothing else uses `java.util.Map` — keep `import java.util.Map;` since the local variable uses it.)

In `RecommendationService.V2.doPost`, replace the success return:

```java
                    RecommendationResult result = pipeline.recommend(query);
                    String degraded = result.trace().get("degradedChannels");
                    java.util.Set<String> degradedSet = (degraded == null || degraded.isBlank())
                            ? java.util.Set.of()
                            : new java.util.LinkedHashSet<>(java.util.List.of(degraded.split(",")));
                    return writeJsonWithRecallDegraded(HttpStatus.OK, result, degradedSet);
```

Add the import at the top of `RecommendationService.java` if missing:

```java
import com.recsys.domain.recommendation.RecommendationResult;
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=RecommendationOrchestratorDegradedTest,RecommendationV1DegradedHeaderTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/application/recommendation/RecommendationOrchestrator.java \
        src/main/java/com/recsys/api/serving/RecommendationService.java \
        src/test/java/com/recsys/application/recommendation/RecommendationOrchestratorDegradedTest.java
git commit -m "feat: surface degraded channels on V2 trace + X-Recall-Degraded header"
```

---

## Task 8: Wire shared metrics + register `/health/load` in `RecSysServer`

**Files:**
- Modify: `src/main/java/com/recsys/api/serving/RecSysServer.java`
- Test: `src/test/java/com/recsys/api/serving/RecSysServerHealthLoadRouteTest.java`

**Interfaces:**
- Consumes: `RecallDegradationMetrics`, `CatalogLoadService`, `RecallConfig.builder().recallMetrics(...)`.
- Produces: `GET /health/load` route on the 6010 server backed by the same metrics the recall service records into.

- [ ] **Step 1: Write the failing test**

```java
package com.recsys.api.serving;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.application.retrieval.multichannel.RecallDegradationMetrics;
import com.recsys.resilience.WorkerBulkhead;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards that the same RecallDegradationMetrics instance recorded into by the recall
 * service is the one served by CatalogLoadService (the wiring contract). Full server
 * bootstrap needs Redis, so this asserts the shared-instance contract directly.
 */
class RecSysServerHealthLoadRouteTest {

    @Test
    void loadServiceReflectsMetricsRecordedElsewhere() throws Exception {
        RecallDegradationMetrics shared = new RecallDegradationMetrics();
        CatalogLoadService service = new CatalogLoadService(
                new WorkerBulkhead("recall-catalog", 2, 4), shared);

        // Simulate the recall service recording into the shared instance.
        shared.recordTotal();
        shared.record("trending", RecallDegradationMetrics.Reason.REJECTED);

        HttpRequest req = HttpRequest.of(HttpMethod.GET, "/health/load");
        AggregatedHttpResponse res = service.serve(ServiceRequestContext.of(req), req).aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).contains("\"degradedRatio\":1.0");
        assertThat(res.contentUtf8()).contains("trending");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=RecSysServerHealthLoadRouteTest`
Expected: FAIL until `CatalogLoadService` (Task 5) is present; if Task 5 landed, this compiles and passes — but the **wiring** step below is still required for the real server, verified by re-running the full suite in Step 4.

- [ ] **Step 3: Wire `RecSysServer`**

Locate the recall wiring (around lines 102–130). After constructing `recallBulkhead` and before building the recall service, add:

```java
            RecallDegradationMetrics recallMetrics = new RecallDegradationMetrics();
```

Add `.recallMetrics(recallMetrics)` to the `RecallConfig.builder()` chain (any position before `.build()`):

```java
                            .userEmbeddingStore(userEmbCache)
                            .recallMetrics(recallMetrics)
                            .build());
```

Register the route by adding, alongside the other `.service(...)` calls in the `ServerBuilder` chain:

```java
                    .service("/health/load", new CatalogLoadService(recallBulkhead, recallMetrics))
```

Add imports at the top of `RecSysServer.java`:

```java
import com.recsys.application.retrieval.multichannel.RecallDegradationMetrics;
```

(`CatalogLoadService` is same-package `com.recsys.api.serving` — no import needed.)

- [ ] **Step 4: Run the full serving test suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='RecSysServer*,Recommendation*,MultiChannelRecall*,RecallConfig*,CatalogLoadServiceTest,RecallResultTest,RecallDegradationMetricsTest'`
Expected: PASS — new routes/behavior plus all pre-existing serving/recall tests green (the `List`-returning API is unchanged).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/api/serving/RecSysServer.java \
        src/test/java/com/recsys/api/serving/RecSysServerHealthLoadRouteTest.java
git commit -m "feat: wire shared recall metrics and register GET /health/load on 6010"
```

---

## Task 9: Full build + docs cross-reference

**Files:**
- Modify: `README.md` (Capacity Planning alarms table — add the degradation signal)
- Modify: `docs/runbooks/overload-protection.md` (note the recall-degradation visibility signal)

**Interfaces:**
- Consumes: everything above. No new production interfaces.

- [ ] **Step 1: Run the full test suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test`
Expected: BUILD SUCCESS, no regressions.

- [ ] **Step 2: Add the alarm row to README Capacity Planning**

In `README.md`, in the "Alarms to set in production" table (the `| Signal | Source | Meaning |` table), add:

```markdown
| `recall.degradedRatio` rising | `GET /health/load` (6010) | Recall bulkhead saturating — non-primary channels dropping to empty results (silent quality loss); scale catalog-serving or raise `RECALL_BULKHEAD_QUEUE_CAPACITY` |
```

- [ ] **Step 3: Note the signal in the overload-protection runbook**

In `docs/runbooks/overload-protection.md`, under "Key caveats" (the note about 6010's bulkhead saturating before the concurrency gate), append:

```markdown
- **Visibility:** silent recall degradation is now observable on 6010 via
  `GET /health/load` (`recall.degradedRatio`, per-channel `channelDegraded`
  counters) and the `X-Recall-Degraded` response header on `/recommendation`
  and `/v2/recommend`. `degradedRatio` climbing above ~0 under load is the
  early-warning signal that fires before any 429.
```

- [ ] **Step 4: Verify docs render (no broken tables)**

Run: `git diff --stat`
Expected: `README.md` and `docs/runbooks/overload-protection.md` modified; visually confirm the markdown tables are well-formed.

- [ ] **Step 5: Commit**

```bash
git add README.md docs/runbooks/overload-protection.md
git commit -m "docs: document recall-degradation visibility signals"
```

---

## Self-Review Notes (author)

- **Spec coverage:** RecallResult (T1) ✓, RecallDegradationMetrics + classify + degradedRatio (T2) ✓, additive `*Detailed` methods + recording + primary-unchanged (T3) ✓, RecallConfig threading (T4) ✓, `/health/load` with bulkhead-rejected omitted (T5) ✓, header helper + V1 (T6) ✓, V2 trace + header (T7) ✓, shared-instance wiring (T8) ✓, docs (T9) ✓. Acceptance criteria 1–6 all mapped.
- **No-status-change guarantee:** V1/V2 keep `HttpStatus.OK`; the header helper only adds a header. Non-header paths (`/similar`) untouched.
- **Type consistency:** `recallDetailed`/`recallPrimaryDetailed`, `RecallResult.degradedChannels()`, `RecallDegradationMetrics.Reason`, `Snapshot.degradedRatio()`, `RecallConfig.recallMetrics()` used identically across tasks.
- **Verified during authoring:** `MovieCandidate(String itemId, double score, String channel, Map<String,Object> features)` (4-arg — test candidates use `java.util.Map.of()` as the 4th arg); `RecallChannel` = `name()` + `recall(query,limit)` + `default recallPrimary(query,limit)` (throws when not overridden); `CandidateRanker.rank(query, candidates, limit)` (3-arg, from `RecommendationOrchestrator:54`); `RecommendationResult(userId, items, nextCursor, trace)` with `trace()` accessor.
- **Pre-write check (still confirm):** mocked-`DataManager` NPE stubs in the T6 test — if `getMovieById`/`getWatchedMovieIds` on the mock trip an NPE in the real V1 flow, add the minimal `when(...)` stub. Production edits do not depend on it.
