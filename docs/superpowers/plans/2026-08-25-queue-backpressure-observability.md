# Queue Backpressure Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the two bounded message queues that already shed under pressure — `AsyncEventPublisher` and `WorkerBulkhead` — visible in Prometheus *while they fill*, instead of only after they have overflowed.

**Architecture:** One registrar, `com.recsys.metrics.QueueMetrics`, publishes four metrics per queue from a three-method `Source` interface that `WorkerBulkhead` and `AsyncEventPublisher` implement. A registration map keyed by `(registry, queueName)` rejects duplicate names *and*, as a consequence, holds every `Source` strongly — which is what closes the Micrometer weak-reference failure mode for the one meter that cannot opt out of it. Two alerts follow: utilization as early warning, reasoned rejections as evidence of loss.

**Tech Stack:** Java 17, Micrometer 1.13.x (`micrometer-core`, `micrometer-registry-prometheus-simpleclient`), Armeria 1.28.4, JUnit 5 + AssertJ, `promtool` + `yq`.

**Spec:** [`docs/superpowers/specs/2026-08-25-queue-backpressure-observability-design.md`](../specs/2026-08-25-queue-backpressure-observability-design.md)

## Global Constraints

- **JDK 17 is required.** Prefix every Maven command: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`. Newer JDKs fail a clean compile of two pre-existing files.
- **Every new test must be added to the `resilience` profile `<includes>` in `pom.xml`** with a comment explaining what breaks if the test is absent. That profile is an allow-list and the only thing the PR gate runs; a test outside it gates nothing.
- **The `resilience` profile is documented as timing-free** and was burned once by a racy test (issue #261). A test needing `System.gc()` or `Thread.sleep` goes in a separate class that stays *out* of the profile — the split already applied to `GcEventTrackerTest` / `GcEventTrackerLifecycleTest` and to `JvmMetricsBinderTest` / `JvmMetricsBinderGcObservationTest`.
- **Every assertion must be shown to fail before it is trusted.** Break the implementation, run the test, confirm it goes red *on the intended assertion*, restore. In the PR #293 workstream six prescribed tests turned out unable to fail under their own mutation. A green mutation run is a finding to report, not a step to tick.
- **`pom.xml` hardcodes Surefire's `<argLine>`**, not `${argLine}`, so `mvn test -DargLine="..."` is **silently ignored**. Do not use it to pass JVM flags.
- **Maven's incremental compiler is unreliable here.** Delete the relevant `target/classes/...` and `target/test-classes/...` outputs before any mutation run.
- **Never `git add -A` or `git add .`.** Stage explicit paths and verify with `git diff --cached --name-only` before every commit.
- **`PrometheusMeterRegistries.defaultRegistry()` is a JVM-wide singleton.** Anything registered against it must tolerate the whole test suite sharing one registry.
- Metric contract, verbatim from the spec — an implementation matching the prose but not this table is wrong:

| Metric | Type | Contract |
|---|---|---|
| `recsys_queue_depth{queue}` | gauge | entries currently enqueued |
| `recsys_queue_capacity{queue}` | gauge | **effective** bound, strictly positive, constant per process |
| `recsys_queue_utilization{queue}` | gauge | `depth/capacity`, computed in-process from one paired read, never derived in PromQL, never clamped |
| `recsys_queue_rejected_total{queue,reason}` | counter | `reason` ∈ `full` \| `shutdown` \| `invalid_key` |

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `src/main/java/com/recsys/metrics/QueueMetrics.java` | The registrar, the `Source` interface, duplicate-name rejection, and the strong-reference map |
| `src/test/java/com/recsys/metrics/QueueMetricsTest.java` | Registration, validation, utilization math, duplicate rejection — all timing-free, **in** the profile |
| `src/test/java/com/recsys/metrics/QueueMetricsGcObservationTest.java` | Forced-GC liveness of all four meters — timing-dependent, **out** of the profile |

**Modified:**

| File | Change |
|---|---|
| `src/main/java/com/recsys/resilience/WorkerBulkhead.java` | Store capacity; classify rejections; implement `Source` |
| `src/main/java/com/recsys/infrastructure/messaging/AsyncEventPublisher.java` | Store capacity; reason-taking `recordRejectedEvent` overload; implement `Source` |
| `src/main/java/com/recsys/infrastructure/messaging/KafkaAsyncEventPublisher.java` | Pass `INVALID_KEY` at its own reject site |
| `src/main/java/com/recsys/api/serving/CatalogLoadService.java` | Report `queueCapacity` in its JSON |
| `src/main/java/com/recsys/api/serving/RecSysServer.java`, `.../online/OnlinePredictionServer.java` | Register the queues |
| `k8s/base/prometheus-rules.yaml`, `k8s/base/prometheus-rules.test.yaml` | Two alerts + cases |
| `pom.xml` | `resilience` includes |
| `docs/system_design/18_Fault_Tolerance.md`, `docs/runbooks/overload-protection.md` | Documentation |

**Dependency order:** Task 1 (registrar) → Task 2 (`WorkerBulkhead`) and Task 3 (`AsyncEventPublisher`) in either order → Task 4 (wiring) → Task 5 (alerts) → Task 6 (docs).

---

### Task 1: `QueueMetrics` — the registrar

**Files:**
- Create: `src/main/java/com/recsys/metrics/QueueMetrics.java`
- Create: `src/test/java/com/recsys/metrics/QueueMetricsTest.java`
- Create: `src/test/java/com/recsys/metrics/QueueMetricsGcObservationTest.java`
- Modify: `pom.xml`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `com.recsys.metrics.QueueMetrics.Source` — public interface with `int depth()`, `int capacity()`, `long rejected(QueueMetrics.RejectionReason reason)`.
  - `com.recsys.metrics.QueueMetrics.RejectionReason` — public enum `FULL`, `SHUTDOWN`, `INVALID_KEY`, each with a `tag()` returning `"full"`, `"shutdown"`, `"invalid_key"`.
  - `QueueMetrics.register(MeterRegistry registry, String queueName, Source source)` — `public static void`, throws `IllegalArgumentException` on a non-positive capacity, `IllegalStateException` on a duplicate `(registry, queueName)`.
  - Package-private `static int registeredCount()` for tests.

Tasks 2 and 3 implement `Source`; Task 4 calls `register`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/metrics/QueueMetricsTest.java`:

```java
package com.recsys.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueueMetricsTest {

    /** Minimal Source whose values the test controls directly. */
    private static final class FakeQueue implements QueueMetrics.Source {
        private final AtomicInteger depth = new AtomicInteger();
        private final int capacity;
        private final Map<QueueMetrics.RejectionReason, AtomicInteger> rejects = Map.of(
                QueueMetrics.RejectionReason.FULL, new AtomicInteger(),
                QueueMetrics.RejectionReason.SHUTDOWN, new AtomicInteger(),
                QueueMetrics.RejectionReason.INVALID_KEY, new AtomicInteger());

        FakeQueue(int capacity) { this.capacity = capacity; }

        @Override public int depth() { return depth.get(); }
        @Override public int capacity() { return capacity; }
        @Override public long rejected(QueueMetrics.RejectionReason reason) {
            return rejects.get(reason).get();
        }
        void setDepth(int d) { depth.set(d); }
        void reject(QueueMetrics.RejectionReason reason) { rejects.get(reason).incrementAndGet(); }
    }

    private static Double gauge(SimpleMeterRegistry registry, String name, String queue) {
        return registry.find(name).tag("queue", queue).gauge().value();
    }

    @Test
    void registersTheFourMeterFamiliesForAQueue() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueMetrics.register(registry, "alpha", new FakeQueue(100));

        assertThat(gauge(registry, "recsys.queue.depth", "alpha")).isZero();
        assertThat(gauge(registry, "recsys.queue.capacity", "alpha")).isEqualTo(100.0);
        assertThat(gauge(registry, "recsys.queue.utilization", "alpha")).isZero();
        assertThat(registry.find("recsys.queue.rejected")
                .tag("queue", "alpha").tag("reason", "full").functionCounter()).isNotNull();
        assertThat(registry.find("recsys.queue.rejected")
                .tag("queue", "alpha").tag("reason", "shutdown").functionCounter()).isNotNull();
        assertThat(registry.find("recsys.queue.rejected")
                .tag("queue", "alpha").tag("reason", "invalid_key").functionCounter()).isNotNull();
    }

    @Test
    void gaugesTrackTheLiveSource() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FakeQueue q = new FakeQueue(200);
        QueueMetrics.register(registry, "beta", q);

        q.setDepth(50);
        assertThat(gauge(registry, "recsys.queue.depth", "beta")).isEqualTo(50.0);
        assertThat(gauge(registry, "recsys.queue.utilization", "beta")).isEqualTo(0.25);

        q.setDepth(200);
        assertThat(gauge(registry, "recsys.queue.utilization", "beta")).isEqualTo(1.0);
    }

    /**
     * Not clamped on purpose: a Source whose depth exceeds its own capacity is a bug worth
     * seeing, and smoothing it to 1.0 would hide it.
     */
    @Test
    void utilizationIsNotClampedAboveOne() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FakeQueue q = new FakeQueue(10);
        QueueMetrics.register(registry, "gamma", q);
        q.setDepth(15);
        assertThat(gauge(registry, "recsys.queue.utilization", "gamma")).isEqualTo(1.5);
    }

    @Test
    void rejectionCountersAreSeparatedByReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FakeQueue q = new FakeQueue(10);
        QueueMetrics.register(registry, "delta", q);

        q.reject(QueueMetrics.RejectionReason.FULL);
        q.reject(QueueMetrics.RejectionReason.FULL);
        q.reject(QueueMetrics.RejectionReason.SHUTDOWN);

        assertThat(registry.find("recsys.queue.rejected")
                .tag("queue", "delta").tag("reason", "full").functionCounter().count()).isEqualTo(2.0);
        assertThat(registry.find("recsys.queue.rejected")
                .tag("queue", "delta").tag("reason", "shutdown").functionCounter().count()).isEqualTo(1.0);
        assertThat(registry.find("recsys.queue.rejected")
                .tag("queue", "delta").tag("reason", "invalid_key").functionCounter().count()).isZero();
    }

    /**
     * Capacity is a positive invariant, not a runtime state. A non-positive value is a
     * programming error in a Source, so registration fails at startup where it is attributable —
     * and must register NOTHING, or it would publish exactly the misleading series the throw
     * exists to prevent.
     */
    @Test
    void nonPositiveCapacityIsRejectedAndRegistersNothing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        assertThatThrownBy(() -> QueueMetrics.register(registry, "zero", new FakeQueue(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero");
        assertThat(registry.getMeters()).isEmpty();

        assertThatThrownBy(() -> QueueMetrics.register(registry, "neg", new FakeQueue(-5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(registry.getMeters()).isEmpty();
    }

    /**
     * Measured against micrometer-core 1.13.6: registering a duplicate name+tags returns the
     * FIRST meter and discards the second's state object. Without this guard the second queue
     * would report the first queue's depth forever, with no error anywhere.
     */
    @Test
    void duplicateQueueNameOnTheSameRegistryIsRejected() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueMetrics.register(registry, "dup", new FakeQueue(10));

        assertThatThrownBy(() -> QueueMetrics.register(registry, "dup", new FakeQueue(20)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dup");
    }

    @Test
    void sameQueueNameOnDifferentRegistriesIsAllowed() {
        SimpleMeterRegistry a = new SimpleMeterRegistry();
        SimpleMeterRegistry b = new SimpleMeterRegistry();
        QueueMetrics.register(a, "shared", new FakeQueue(10));
        QueueMetrics.register(b, "shared", new FakeQueue(20));

        assertThat(gauge(a, "recsys.queue.capacity", "shared")).isEqualTo(10.0);
        assertThat(gauge(b, "recsys.queue.capacity", "shared")).isEqualTo(20.0);
    }

    @Test
    void twoQueuesOnOneRegistryDoNotAlias() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FakeQueue first = new FakeQueue(10);
        FakeQueue second = new FakeQueue(20);
        QueueMetrics.register(registry, "first", first);
        QueueMetrics.register(registry, "second", second);

        first.setDepth(3);
        second.setDepth(7);

        assertThat(gauge(registry, "recsys.queue.depth", "first")).isEqualTo(3.0);
        assertThat(gauge(registry, "recsys.queue.depth", "second")).isEqualTo(7.0);
    }

    @Test
    void nullArgumentsAreRejected() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        assertThatThrownBy(() -> QueueMetrics.register(null, "x", new FakeQueue(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> QueueMetrics.register(registry, null, new FakeQueue(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> QueueMetrics.register(registry, "x", null))
                .isInstanceOf(NullPointerException.class);
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=QueueMetricsTest
```

Expected: compilation failure — `QueueMetrics` does not exist.

- [ ] **Step 3: Implement `QueueMetrics`**

Create `src/main/java/com/recsys/metrics/QueueMetrics.java`. Match the javadoc register of `RequestDurationHistogram` and `JvmMetricsBinder` — read one first; they explain *why* a thing is the way it is and what breaks otherwise.

```java
package com.recsys.metrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Publishes depth, capacity, utilization and reasoned rejection counts for a bounded queue.
 *
 * <p>Exists because the two bounded queues on the message path — {@code AsyncEventPublisher} and
 * {@code WorkerBulkhead} — each compute their own depth and publish it nowhere. Their drops were
 * visible only after the fact, so a queue filling up was invisible until it overflowed: the
 * "instrumentation that looks present and is observable by nothing" failure recorded in
 * 18_Fault_Tolerance §8.2.
 *
 * <p><b>Two Micrometer behaviours drive this class's shape, both measured against
 * micrometer-core 1.13.6 rather than assumed.</b>
 *
 * <p>First, registering a meter whose name and tags match an existing one <em>silently returns
 * the existing meter and discards the new state object</em> — two gauges built over different
 * objects under the same tag yield one meter, and both read the first object. A second queue
 * registering under a name already taken would therefore report the first queue's depth forever,
 * with no error anywhere. {@link #REGISTERED} exists to make that impossible: a duplicate
 * {@code (registry, queueName)} throws instead.
 *
 * <p>Second, {@link Gauge.Builder} offers {@code strongReference(boolean)} but
 * {@link FunctionCounter.Builder} <b>does not</b>. Micrometer holds meter state weakly by
 * default, and per §8.3 the two meter types then fail <em>differently</em>: a collected gauge
 * reports {@code NaN} — visibly wrong — while a {@code FunctionCounter} freezes at its last value
 * and reports no error, which is indistinguishable from a healthy quiet queue. The rejection
 * counter is therefore the one meter that cannot protect itself. It does not need to:
 * {@link #REGISTERED} holds every {@link Source} strongly for the JVM's life, so the retention
 * falls out of the duplicate-name guard rather than being a separate field whose rationale can
 * rot. <b>Do not "clean up" that map.</b>
 *
 * <p>Deliberately <em>not</em> relied on: that a live {@code WorkerBulkhead} or
 * {@code AsyncEventPublisher} is reachable through the Armeria service graph or its own drain
 * thread. Both are true today and neither is a mechanism — the drain-thread path evaporates at
 * {@code close()}, and the service-graph path is an argument a refactor could invalidate with no
 * test failing. That is the mistake {@code JvmMetricsBinder.RETAINED}'s javadoc made before it
 * was disproved.
 */
public final class QueueMetrics {

    private static final Logger log = LoggerFactory.getLogger(QueueMetrics.class);

    /** Why a queue refused work. Separated because they demand different responses. */
    public enum RejectionReason {
        /** The bound was reached. This is the backpressure signal, and the only alerted reason. */
        FULL("full"),
        /** The queue was closed and refused late work. Routine during a drain; never a page. */
        SHUTDOWN("shutdown"),
        /** The event carried no usable partition key. A data or configuration fault, not capacity. */
        INVALID_KEY("invalid_key");

        private final String tag;

        RejectionReason(String tag) { this.tag = tag; }

        public String tag() { return tag; }
    }

    /**
     * A bounded queue, as the metrics need to see it.
     *
     * <p>{@link #capacity()} must return a strictly positive value — see
     * {@link #register(MeterRegistry, String, Source)}. Implementations must also maintain
     * {@code 0 <= depth() <= capacity()}; that invariant is theirs, not this class's, and is not
     * clamped here because a violation is a bug worth seeing.
     */
    public interface Source {
        int depth();

        /** The <em>effective</em> bound of the queue that was actually constructed. */
        int capacity();

        long rejected(RejectionReason reason);
    }

    /**
     * Registered sources, keyed by registry identity then queue name. Serves two purposes at once:
     * it rejects duplicate queue names, and by holding each {@link Source} strongly it keeps every
     * meter's state alive. Identity-keyed because {@code MeterRegistry} does not define equality.
     */
    private static final Map<MeterRegistry, Map<String, Source>> REGISTERED = new IdentityHashMap<>();

    private QueueMetrics() {}

    /**
     * @throws IllegalArgumentException if {@code source.capacity()} is not strictly positive
     * @throws IllegalStateException    if {@code queueName} is already registered on {@code registry}
     */
    public static synchronized void register(MeterRegistry registry, String queueName, Source source) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(queueName, "queueName");
        Objects.requireNonNull(source, "source");

        int capacity = source.capacity();
        if (capacity <= 0) {
            throw new IllegalArgumentException(
                    "Queue '" + queueName + "' reported a non-positive capacity (" + capacity
                            + "). Capacity is a positive invariant: an unbounded queue needs its own "
                            + "metric shape, not a sentinel capacity threaded through this one.");
        }

        Map<String, Source> byName = REGISTERED.computeIfAbsent(registry, r -> new HashMap<>());
        if (byName.containsKey(queueName)) {
            throw new IllegalStateException(
                    "Queue '" + queueName + "' is already registered on this MeterRegistry. "
                            + "Micrometer would silently return the first meter and discard this "
                            + "source, so the second queue would report the first one's depth.");
        }

        Gauge.builder("recsys.queue.depth", source, Source::depth)
                .tag("queue", queueName)
                .description("Entries currently enqueued")
                .strongReference(true)
                .register(registry);

        Gauge.builder("recsys.queue.capacity", source, s -> s.capacity())
                .tag("queue", queueName)
                .description("Effective bound of the queue")
                .strongReference(true)
                .register(registry);

        // depth and capacity are read together here, in one call, so the two can never be
        // sampled at different instants the way a PromQL division of the two series would.
        Gauge.builder("recsys.queue.utilization", source, s -> (double) s.depth() / s.capacity())
                .tag("queue", queueName)
                .description("depth / capacity")
                .strongReference(true)
                .register(registry);

        for (RejectionReason reason : RejectionReason.values()) {
            FunctionCounter.builder("recsys.queue.rejected", source, s -> s.rejected(reason))
                    .tag("queue", queueName)
                    .tag("reason", reason.tag())
                    .description("Work refused by this queue")
                    .register(registry);
        }

        byName.put(queueName, source);
        log.info("Registered queue metrics for '{}' (capacity {})", queueName, capacity);
    }

    /** Package-private for tests. */
    static synchronized int registeredCount(MeterRegistry registry) {
        Map<String, Source> byName = REGISTERED.get(registry);
        return byName == null ? 0 : byName.size();
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=QueueMetricsTest
```

Expected: PASS, all nine tests.

- [ ] **Step 5: Prove the two guards can fail**

Force-clean first — the incremental compiler is not trustworthy for mutation runs:

```bash
rm -f target/classes/com/recsys/metrics/QueueMetrics*.class \
      target/test-classes/com/recsys/metrics/QueueMetricsTest*.class
```

Mutation A — delete the `capacity <= 0` throw. Re-run: `nonPositiveCapacityIsRejectedAndRegistersNothing` must go red. Restore.

Mutation B — delete the `byName.containsKey(queueName)` throw. Re-run: `duplicateQueueNameOnTheSameRegistryIsRejected` must go red, **and** confirm from the failure output that the aliasing is real. Restore.

If either stays green, say so rather than proceeding — that is the finding, not a formality.

- [ ] **Step 6: Write the GC-liveness test, kept out of the merge gate**

Create `src/test/java/com/recsys/metrics/QueueMetricsGcObservationTest.java`:

```java
package com.recsys.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deliberately NOT in the resilience profile: it forces a real collection with System.gc() and an
 * allocation loop, and that profile is documented timing-free (issue #261). Same split as
 * GcEventTrackerTest / GcEventTrackerLifecycleTest and JvmMetricsBinderTest /
 * JvmMetricsBinderGcObservationTest.
 *
 * <p>The FunctionCounter is the meter this test exists for. Gauges pass strongReference(true) and
 * a collected one reports NaN, which is visibly wrong; FunctionCounter.Builder has no such option
 * and a collected one FREEZES at its last value reporting no error — indistinguishable from a
 * quiet queue. A liveness test covering only the gauges would pass while the one unprotected
 * meter was broken.
 */
class QueueMetricsGcObservationTest {

    private static final class CountingSource implements QueueMetrics.Source {
        private int depth = 7;
        private long full = 3;
        @Override public int depth() { return depth; }
        @Override public int capacity() { return 100; }
        @Override public long rejected(QueueMetrics.RejectionReason reason) {
            return reason == QueueMetrics.RejectionReason.FULL ? full : 0L;
        }
    }

    @Test
    void allMetersStillReportAfterGarbageCollection() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueMetrics.register(registry, "gc-probe", new CountingSource());

        WeakReference<Object> canary = new WeakReference<>(new Object());
        for (int i = 0; i < 20 && canary.get() != null; i++) {
            System.gc();
            byte[] pressure = new byte[8 * 1024 * 1024];
            assertThat(pressure).isNotNull();
        }
        assertThat(canary.get())
                .as("a real GC must have run for this test to mean anything")
                .isNull();

        assertThat(registry.find("recsys.queue.depth").tag("queue", "gc-probe").gauge().value())
                .as("gauge state must survive GC").isEqualTo(7.0);
        assertThat(registry.find("recsys.queue.rejected")
                .tag("queue", "gc-probe").tag("reason", "full").functionCounter().count())
                .as("FunctionCounter state must survive GC — it freezes silently if collected")
                .isEqualTo(3.0);
    }
}
```

- [ ] **Step 7: Run it and prove it can fail**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=QueueMetricsGcObservationTest
```

Expected: PASS.

Then mutate: comment out `byName.put(queueName, source);` in `QueueMetrics.register` so nothing retains the `Source`, force-clean the class outputs, and re-run.

**Report exactly what happens.** If the test goes red, the retention is proven load-bearing. If it stays green, the `Source` is being kept alive by something else in the test — say so plainly rather than claiming proof, exactly as the `JvmMetricsBinder.RETAINED` investigation had to. Restore afterwards either way.

- [ ] **Step 8: Add the timing-free test to the resilience profile**

In `pom.xml`, inside the `resilience` profile `<includes>`, next to `**/metrics/RequestDurationHistogramTest.java`:

```xml
                <!-- Pins two Micrometer behaviours that fail silently. A duplicate queue name
                     would otherwise return the first meter and discard the second source, so one
                     queue would report another's depth with no error; and a non-positive capacity
                     would publish a utilization series that reads as healthy-and-empty for a
                     broken queue. Both are registration-time throws, so the failure is a boot
                     failure rather than a wrong number at 3am. Pure unit-level, no timing --
                     the GC-liveness half lives in QueueMetricsGcObservationTest, out of this
                     profile on purpose. -->
                <include>**/metrics/QueueMetricsTest.java</include>
```

- [ ] **Step 9: Run the gate and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
git add src/main/java/com/recsys/metrics/QueueMetrics.java \
        src/test/java/com/recsys/metrics/QueueMetricsTest.java \
        src/test/java/com/recsys/metrics/QueueMetricsGcObservationTest.java \
        pom.xml
git commit -m "feat(metrics): QueueMetrics registrar for bounded queues

Publishes depth, capacity, utilization and reasoned rejections per queue.

Two measured Micrometer behaviours shape it. A duplicate name+tags
registration silently returns the first meter and discards the second's
state, so a second queue would report the first's depth forever -- rejected
at registration instead. And FunctionCounter.Builder has no strongReference
option, unlike Gauge.Builder, so the rejection counter is the one meter that
cannot protect itself; the duplicate-name map holds every Source strongly,
which closes that as a consequence rather than as a separate field.

Capacity is validated positive at registration and registers nothing on
failure: a non-positive capacity is a programming error in a Source, not a
runtime state to render."
```

---

### Task 2: `WorkerBulkhead` — capacity and reasoned rejections

**Files:**
- Modify: `src/main/java/com/recsys/resilience/WorkerBulkhead.java`
- Modify: `src/main/java/com/recsys/api/serving/CatalogLoadService.java`
- Test: `src/test/java/com/recsys/resilience/WorkerBulkheadTest.java` (exists; append to it)
- **No `pom.xml` change needed** — the profile's `**/resilience/*Test.java` include already gates this class. Verified.

**Interfaces:**
- Consumes: `QueueMetrics.Source`, `QueueMetrics.RejectionReason` from Task 1.
- Produces: `WorkerBulkhead implements QueueMetrics.Source`; `Snapshot` gains a `queueCapacity` component, becoming `Snapshot(String name, int active, int queued, int poolSize, int queueCapacity, long rejected)`.

- [ ] **Step 1: Write the failing test**

Append to the existing `src/test/java/com/recsys/resilience/WorkerBulkheadTest.java`:

```java
    @Test
    void reportsTheEffectiveCapacityItWasConstructedWith() {
        WorkerBulkhead bulkhead = new WorkerBulkhead("cap", 1, 5);
        try {
            assertThat(bulkhead.capacity()).isEqualTo(5);
            assertThat(bulkhead.snapshot().queueCapacity()).isEqualTo(5);
        } finally {
            bulkhead.close();
        }
    }

    /**
     * Both constructors clamp with Math.max(1, n), so a requested 0 yields a one-entry queue.
     * The metric must report the queue that exists, not the one that was asked for, or
     * utilization would be arithmetically wrong.
     */
    @Test
    void aNonPositiveRequestedCapacityIsClampedToOneAndReportedAsSuch() {
        WorkerBulkhead bulkhead = new WorkerBulkhead("clamped", 1, 0);
        try {
            assertThat(bulkhead.capacity()).isEqualTo(1);
        } finally {
            bulkhead.close();
        }
    }

    /**
     * ThreadPoolExecutor throws RejectedExecutionException for a full queue OR a shut-down
     * executor. Counting both as saturation would fire the queue alert on every rolling deploy.
     */
    @Test
    void rejectionsAreClassifiedFullVersusShutdown() throws Exception {
        WorkerBulkhead bulkhead = new WorkerBulkhead("reasons", 1, 1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            // Occupy the single worker, then fill the single queue slot.
            bulkhead.submit(() -> { release.await(); return "held"; });
            bulkhead.submit(() -> "queued");

            // Everything after this has nowhere to go: the queue is full.
            for (int i = 0; i < 5; i++) {
                bulkhead.submit(() -> "overflow");
            }

            assertThat(bulkhead.rejected(QueueMetrics.RejectionReason.FULL)).isEqualTo(5L);
            assertThat(bulkhead.rejected(QueueMetrics.RejectionReason.SHUTDOWN)).isZero();
        } finally {
            release.countDown();
            bulkhead.close();
        }

        // After close, the executor rejects for a different reason entirely.
        bulkhead.submit(() -> "after-close");
        assertThat(bulkhead.rejected(QueueMetrics.RejectionReason.SHUTDOWN)).isEqualTo(1L);
        assertThat(bulkhead.rejected(QueueMetrics.RejectionReason.FULL))
                .as("a shutdown rejection must not inflate the saturation counter")
                .isEqualTo(5L);
    }

    @Test
    void invalidKeyIsNeverUsedByABulkhead() {
        WorkerBulkhead bulkhead = new WorkerBulkhead("nokey", 1, 1);
        try {
            assertThat(bulkhead.rejected(QueueMetrics.RejectionReason.INVALID_KEY)).isZero();
        } finally {
            bulkhead.close();
        }
    }
```

Add imports: `com.recsys.metrics.QueueMetrics`, `java.util.concurrent.CountDownLatch`, `org.junit.jupiter.api.Test`, and `static org.assertj.core.api.Assertions.assertThat`.

- [ ] **Step 2: Run it and confirm it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=WorkerBulkheadTest
```

Expected: compilation failure — `capacity()`, `rejected(...)` and `queueCapacity()` do not exist.

- [ ] **Step 3: Implement**

In `WorkerBulkhead.java`:

Change the class declaration to `public final class WorkerBulkhead implements QueueMetrics.Source` and add `import com.recsys.metrics.QueueMetrics;`.

Add fields beside `rejectedCount`:

```java
    /**
     * The effective bound, captured here rather than read back from the executor's queue:
     * ArrayBlockingQueue.remainingCapacity() + size() is racy under concurrent access, and
     * this value is immutable for the object's life anyway.
     */
    private final int queueCapacity;

    private final AtomicLong shutdownRejectedCount = new AtomicLong();
```

In the constructor, before building the executor:

```java
        int effectiveCapacity = Math.max(1, queueCapacity);
        if (effectiveCapacity != queueCapacity) {
            log.warn("WorkerBulkhead '{}' requested queue capacity {} but the effective bound is {};"
                            + " metrics report the effective value.",
                    name, queueCapacity, effectiveCapacity);
        }
        this.queueCapacity = effectiveCapacity;
```

and use `new ArrayBlockingQueue<>(effectiveCapacity)` in the `ThreadPoolExecutor` arguments. Add a logger field if the class has none:

```java
    private static final Logger log = LoggerFactory.getLogger(WorkerBulkhead.class);
```

with `import org.slf4j.Logger;` and `import org.slf4j.LoggerFactory;`.

Replace the catch block in `submit`:

```java
        } catch (RejectedExecutionException e) {
            // ThreadPoolExecutor throws this for a full queue OR a shut-down executor. Counting
            // both as saturation would fire the queue alert on every rolling deploy.
            if (executor.isShutdown()) {
                shutdownRejectedCount.incrementAndGet();
            } else {
                rejectedCount.incrementAndGet();
            }
            future.completeExceptionally(e);
        }
```

Add the `Source` methods and widen the snapshot:

```java
    @Override
    public int depth() {
        return executor.getQueue().size();
    }

    @Override
    public int capacity() {
        return queueCapacity;
    }

    @Override
    public long rejected(QueueMetrics.RejectionReason reason) {
        return switch (reason) {
            case FULL -> rejectedCount.get();
            case SHUTDOWN -> shutdownRejectedCount.get();
            case INVALID_KEY -> 0L;   // a bulkhead has no keys to be invalid
        };
    }

    public Snapshot snapshot() {
        return new Snapshot(name, executor.getActiveCount(), executor.getQueue().size(),
                executor.getCorePoolSize(), queueCapacity, rejectedCount.get());
    }

    public record Snapshot(String name, int active, int queued, int poolSize, int queueCapacity,
                           long rejected) {}
```

- [ ] **Step 4: Update the JSON endpoint**

In `CatalogLoadService.java`, after `bulkhead.put("queued", b.queued());`:

```java
        bulkhead.put("queueCapacity", b.queueCapacity());
```

- [ ] **Step 5: Run the test and confirm it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=WorkerBulkheadTest
```

Expected: PASS.

- [ ] **Step 6: Prove the classification can fail**

Force-clean, then replace the catch body's `if (executor.isShutdown())` with a bare `rejectedCount.incrementAndGet();` — the pre-change behaviour. Re-run: `rejectionsAreClassifiedFullVersusShutdown` must go red on the shutdown assertion. Restore.

This is the mutation that matters in this task: without it, the alert pages on every deploy.

- [ ] **Step 7: Commit**

`pom.xml` is deliberately absent from the staged paths: this class is already gated by
`**/resilience/*Test.java`.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
git add src/main/java/com/recsys/resilience/WorkerBulkhead.java \
        src/main/java/com/recsys/api/serving/CatalogLoadService.java \
        src/test/java/com/recsys/resilience/WorkerBulkheadTest.java
git commit -m "feat(resilience): WorkerBulkhead reports capacity and classifies rejections

ThreadPoolExecutor throws RejectedExecutionException for a full queue OR a
shut-down executor, and the counter treated both alike -- so a queue-full
alert would have fired on every rolling deploy. Split into saturation and
shutdown counters behind QueueMetrics.Source.

Capacity is captured in the constructor rather than read back from the
executor's queue, since remainingCapacity() + size() is racy. The
Math.max(1, n) clamp now logs a WARN when it engages: metrics report the
effective bound, so the requested-vs-effective divergence needs to surface
somewhere."
```

---

### Task 3: `AsyncEventPublisher` — capacity and reasoned rejections

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/messaging/AsyncEventPublisher.java`
- Modify: `src/main/java/com/recsys/infrastructure/messaging/KafkaAsyncEventPublisher.java`
- Test: `src/test/java/com/recsys/infrastructure/messaging/AsyncEventPublisherTest.java` (exists; append to it)
- Modify: `pom.xml` — **this class is NOT currently gated.** No include matches `infrastructure/messaging/`, unlike `**/resilience/*Test.java` which already covers `WorkerBulkheadTest`. Verified by running the profile against both class names.

**Interfaces:**
- Consumes: `QueueMetrics.Source`, `QueueMetrics.RejectionReason` from Task 1.
- Produces: `AsyncEventPublisher implements QueueMetrics.Source`; `Snapshot` gains `queueCapacity`, becoming `Snapshot(int queueSize, int queueCapacity, long published, long dropped, long drained, long deliveryFailures)`; a new `protected boolean recordRejectedEvent(QueueMetrics.RejectionReason reason)` alongside the existing no-arg form.

**Compatibility note:** `recordRejectedEvent()` is `protected` and is called by `KafkaAsyncEventPublisher`. Keep the no-arg overload delegating to `recordRejectedEvent(RejectionReason.FULL)` so no subclass breaks; the Kafka subclass is then updated to name its own reason explicitly. `OnlineOpsService` constructs `new AsyncEventPublisher.Snapshot(0, 0L, 0L, 0L)` via a convenience constructor — keep a convenience overload so that call site still compiles, or update it; check which is cleaner once you see it.

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/com/recsys/infrastructure/messaging/AsyncEventPublisherTest.java`:

```java
    @Test
    void reportsTheEffectiveCapacityItWasConstructedWith() {
        AsyncEventPublisher publisher = new AsyncEventPublisher(4, 1);
        try {
            assertThat(publisher.capacity()).isEqualTo(4);
            assertThat(publisher.snapshot().queueCapacity()).isEqualTo(4);
        } finally {
            publisher.close();
        }
    }

    @Test
    void aNonPositiveRequestedCapacityIsClampedToOneAndReportedAsSuch() {
        AsyncEventPublisher publisher = new AsyncEventPublisher(0, 1);
        try {
            assertThat(publisher.capacity()).isEqualTo(1);
        } finally {
            publisher.close();
        }
    }

    /**
     * publish() opens with `if (!running) return recordRejectedEvent()`, so a closed publisher
     * counted refusals identically to a full queue. Counting both as saturation would fire the
     * queue alert on every rolling deploy.
     */
    @Test
    void rejectionsAfterCloseAreShutdownNotFull() {
        AsyncEventPublisher publisher = new AsyncEventPublisher(1000, 1000) {
            @Override protected void sendBatch(java.util.List<String> events) { /* swallow */ }
        };
        publisher.close();

        publisher.publish("late-event");

        assertThat(publisher.rejected(QueueMetrics.RejectionReason.SHUTDOWN)).isEqualTo(1L);
        assertThat(publisher.rejected(QueueMetrics.RejectionReason.FULL))
                .as("a shutdown refusal must not inflate the saturation counter")
                .isZero();
    }
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=AsyncEventPublisherTest
```

Expected: compilation failure — `capacity()`, `rejected(...)`, `queueCapacity()` do not exist.

- [ ] **Step 3: Implement in `AsyncEventPublisher`**

Declare `implements AutoCloseable, QueueMetrics.Source` and add `import com.recsys.metrics.QueueMetrics;`.

Add fields:

```java
    private final int queueCapacity;
    private final AtomicLong shutdownRejectedCount = new AtomicLong();
    private final AtomicLong invalidKeyRejectedCount = new AtomicLong();
```

In the main constructor, replace the queue construction:

```java
        int effectiveCapacity = Math.max(1, queueCapacity);
        if (effectiveCapacity != queueCapacity) {
            log.warn("AsyncEventPublisher requested queue capacity {} but the effective bound is {};"
                    + " metrics report the effective value.", queueCapacity, effectiveCapacity);
        }
        this.queueCapacity = effectiveCapacity;
        this.queue = new ArrayBlockingQueue<>(effectiveCapacity);
```

Name the reason at each existing call site in `publish`:

```java
        if (!running) return recordRejectedEvent(QueueMetrics.RejectionReason.SHUTDOWN);
        if (queue.offer(new EventEnvelope(key, event))) {
            publishedCount.incrementAndGet();
            return true;
        }
        return recordRejectedEvent(QueueMetrics.RejectionReason.FULL);
```

Replace `recordRejectedEvent` with the pair:

```java
    /**
     * Retained so subclasses compiled against the old shape keep working; FULL is the historical
     * meaning of a bare rejection here.
     */
    protected boolean recordRejectedEvent() {
        return recordRejectedEvent(QueueMetrics.RejectionReason.FULL);
    }

    protected boolean recordRejectedEvent(QueueMetrics.RejectionReason reason) {
        long dropped = droppedCount.incrementAndGet();
        switch (reason) {
            case SHUTDOWN -> shutdownRejectedCount.incrementAndGet();
            case INVALID_KEY -> invalidKeyRejectedCount.incrementAndGet();
            case FULL -> { /* droppedCount is the FULL counter; see rejected(...) */ }
        }
        if (consistencyMetrics != null) consistencyMetrics.recordAsyncDrop(metricEventType);
        log.warn("AsyncEventPublisher event rejected, reason={} (total dropped: {})",
                reason.tag(), dropped);
        return false;
    }
```

Add the `Source` methods and widen the snapshot:

```java
    @Override
    public int depth() {
        return queue.size();
    }

    @Override
    public int capacity() {
        return queueCapacity;
    }

    @Override
    public long rejected(QueueMetrics.RejectionReason reason) {
        return switch (reason) {
            // droppedCount is the total; FULL is what remains after the other two are removed.
            case FULL -> droppedCount.get() - shutdownRejectedCount.get() - invalidKeyRejectedCount.get();
            case SHUTDOWN -> shutdownRejectedCount.get();
            case INVALID_KEY -> invalidKeyRejectedCount.get();
        };
    }

    public Snapshot snapshot() {
        return new Snapshot(queue.size(), queueCapacity, publishedCount.get(), droppedCount.get(),
                drainedCount.get(), deliveryFailureCount.get());
    }
```

and widen the record, keeping a convenience constructor for existing call sites:

```java
    public record Snapshot(int queueSize, int queueCapacity, long published, long dropped,
                           long drained, long deliveryFailures) {
        public Snapshot(int queueSize, long published, long dropped, long drained) {
            this(queueSize, 1, published, dropped, drained, 0L);
        }
    }
```

**Note on the `FULL` arithmetic:** `droppedCount` is pre-existing and is the total across all reasons, so `FULL` is derived by subtraction rather than counted separately. That keeps `async_events_dropped_total` (which reads `droppedCount`) unchanged in meaning. Verify by test that the three reasons sum to `droppedCount`.

- [ ] **Step 4: Name the Kafka subclass's own reason**

In `KafkaAsyncEventPublisher.java`:

```java
    private boolean rejectInvalidKey() {
        return recordRejectedEvent(QueueMetrics.RejectionReason.INVALID_KEY);
    }
```

Add `import com.recsys.metrics.QueueMetrics;`.

- [ ] **Step 5: Fix any broken call sites**

`OnlineOpsService` constructs `new AsyncEventPublisher.Snapshot(0, 0L, 0L, 0L)`. Compile and repair whatever the widened record breaks:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q compile
```

- [ ] **Step 6: Run the test and confirm it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=AsyncEventPublisherTest
```

Expected: PASS.

- [ ] **Step 7: Prove the classification can fail**

Force-clean, then change the `!running` branch back to the bare `recordRejectedEvent()`. Re-run: `rejectionsAfterCloseAreShutdownNotFull` must go red. Restore.

- [ ] **Step 8: Add the test to the resilience profile**

Unlike `WorkerBulkheadTest`, this class gates nothing today — no include matches
`infrastructure/messaging/`. Add it beside the other metrics entries:

```xml
                <!-- Three distinct causes flowed through one drop counter here: a full queue, a
                     closed publisher, and (in the Kafka subclass) an event with no usable
                     partition key. Un-separated, a malformed-key bug reads as saturation and the
                     queue-full alert fires on every rolling deploy. This pins the split. No
                     Redis, no timing -- the publisher is constructed directly with a stub
                     sendBatch. -->
                <include>**/messaging/AsyncEventPublisherTest.java</include>
```

- [ ] **Step 9: Commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
git add src/main/java/com/recsys/infrastructure/messaging/AsyncEventPublisher.java \
        src/main/java/com/recsys/infrastructure/messaging/KafkaAsyncEventPublisher.java \
        src/test/java/com/recsys/infrastructure/messaging/AsyncEventPublisherTest.java \
        src/main/java/com/recsys/health/OnlineOpsService.java pom.xml
git commit -m "feat(messaging): AsyncEventPublisher reports capacity and classifies rejections

Three distinct causes flowed through one counter: a full queue, a closed
publisher (publish() opens with `if (!running) return recordRejectedEvent()`)
and -- in the Kafka subclass -- an event with no usable partition key. Folded
together, a malformed-key bug would have presented as queue saturation, and
a queue-full alert would have fired on every rolling deploy.

recordRejectedEvent() keeps its no-arg form delegating to FULL, since it is
protected and called by KafkaAsyncEventPublisher.

droppedCount stays the all-reasons total so async_events_dropped_total is
unchanged in meaning; FULL is derived by subtraction."
```

---

### Task 4: Register the queues on the two Armeria services

**Files:**
- Modify: `src/main/java/com/recsys/api/serving/RecSysServer.java`
- Modify: `src/main/java/com/recsys/api/online/OnlinePredictionServer.java`

**Interfaces:**
- Consumes: `QueueMetrics.register` (Task 1); `WorkerBulkhead`/`AsyncEventPublisher` as `Source` (Tasks 2, 3).
- Produces: the live series Task 5's alerts query.

**Queue names are the metric's identity — use exactly these**, since a duplicate name now throws at boot and a renamed one silently breaks an alert: `recall-catalog` (6010), `recall-online` (7010), `async-events` (7010).

- [ ] **Step 1: Register on catalog serving**

In `RecSysServer.java`, immediately after the existing `JvmMetricsBinder.bindTo(registry);` line:

```java
            QueueMetrics.register(registry, "recall-catalog", recallBulkhead);
```

The bulkhead is constructed a few lines above as `WorkerBulkhead recallBulkhead = new WorkerBulkhead("recall-catalog", ...)`. If `QueueMetrics.register` appears before that construction, move the registration below it rather than moving the construction. Add `import com.recsys.metrics.QueueMetrics;`.

- [ ] **Step 2: Register on online serving**

In `OnlinePredictionServer.java`, after its `JvmMetricsBinder.bindTo(registry);`:

```java
            QueueMetrics.register(registry, "recall-online", recallBulkhead);
            if (asyncEventPublisher != null) {
                QueueMetrics.register(registry, "async-events", asyncEventPublisher);
            }
```

Both locals exist by that point in `main` — `recallBulkhead` around line 136 and `asyncEventPublisher` around line 113. The null guard matters: `asyncEventPublisher` is initialised to `null` at line 96 and only assigned conditionally.

Add `import com.recsys.metrics.QueueMetrics;`.

- [ ] **Step 3: Verify on the wire**

Static wiring is what §8.2 calls "instrumentation that looked present and was observable by nothing". Confirm the series actually reach the exposition:

```bash
REDIS_ALLOW_NO_AUTH=true RECOMMENDATION_CURSOR_SIGNING_KEY=dummy-key-for-local-run \
  JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  mvn exec:java -Dexec.mainClass=com.recsys.api.serving.RecSysServer &
sleep 25
curl -s localhost:6010/metrics | grep '^recsys_queue_'
kill %1
```

Expected: `recsys_queue_depth`, `_capacity`, `_utilization` and three `recsys_queue_rejected_total` lines, all tagged `queue="recall-catalog"`. **Record the exact `capacity` value** — Task 5's alert thresholds assume a plausible bound and the promtool cases should use realistic numbers.

Repeat for online serving:

```bash
REDIS_ALLOW_NO_AUTH=true JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  mvn exec:java -Dexec.mainClass=com.recsys.api.online.OnlinePredictionServer &
sleep 25
curl -s localhost:7010/metrics | grep '^recsys_queue_' | sort
kill %1
```

Expected: both `recall-online` and `async-events`. If `async-events` is missing, the publisher was null — report that rather than removing the guard.

- [ ] **Step 4: Confirm a duplicate name fails loudly**

Temporarily change the online-serving registration to reuse `"recall-catalog"` for both queues, boot it, and confirm the process **fails at startup** with the `IllegalStateException` rather than booting with aliased metrics. Restore.

This is the check that proves the guard protects a real wiring mistake, not just a unit test.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/api/serving/RecSysServer.java \
        src/main/java/com/recsys/api/online/OnlinePredictionServer.java
git commit -m "feat(metrics): register the three bounded queues for metrics

recall-catalog on 6010; recall-online and async-events on 7010. The
async-events registration is guarded because asyncEventPublisher is
initialised to null and assigned conditionally.

Verified on the wire rather than by inspection: both /metrics endpoints
serve the recsys_queue_* family, and a deliberately duplicated queue name
fails the process at boot instead of silently aliasing two queues onto one
set of meters."
```

---

### Task 5: The two alerts

**Files:**
- Modify: `k8s/base/prometheus-rules.yaml`
- Modify: `k8s/base/prometheus-rules.test.yaml`

**Interfaces:**
- Consumes: the metric names Task 4 put on the wire.
- Produces: alerts `RecsysQueueFillingUp`, `RecsysQueueRejecting`.

- [ ] **Step 1: Confirm every metric name against the live exposition**

Do not skip this. The file's own header states that an alert on a metric that is never emitted looks like coverage and can never fire, and two alerts in this repo have already shipped against a wrong metric shape.

Boot online serving as in Task 4 Step 3 and record the exact exposed names — in particular whether the counter appears as `recsys_queue_rejected_total` (Micrometer appends `_total` to a `FunctionCounter` on the Prometheus wire). **Write the alert against the name that actually appears.**

- [ ] **Step 2: Add the alerts**

Append to the `recsys.runtime` group in `k8s/base/prometheus-rules.yaml`:

```yaml
        - alert: RecsysQueueFillingUp
          # Early warning, deliberately distinct from RecsysQueueRejecting below: this fires while
          # a queue is under pressure but has lost nothing. A queue can sit at 90% indefinitely and
          # be fine; what this catches is one that has stopped draining as fast as it fills, which
          # is the state that precedes loss and is invisible without this metric.
          #
          # for: 10m is the substance, not decoration -- at a 15s scrape that is ~40 consecutive
          # samples above the line. A queue that touches 0.8 for one scrape and drains is working
          # as designed and must not page.
          expr: recsys_queue_utilization > 0.7
          for: 10m
          labels:
            severity: warning
          annotations:
            summary: "Queue {{ $labels.queue }} on {{ $labels.job }} is sustained above 70% full"
            description: >-
              Sustained pressure with nothing lost yet. Check whether the consumer has slowed
              (the drain thread for async-events, the recall workers for a bulkhead) before
              raising the bound -- a larger queue buys latency, not throughput. See
              docs/runbooks/overload-protection.md.

        - alert: RecsysQueueRejecting
          # Evidence of actual loss, not early warning. Scoped to reason="full" ON PURPOSE:
          # both implementations also count shutdown refusals, so an unscoped expression fires on
          # every rolling deploy. Dropping this matcher while "simplifying" the expression would
          # reintroduce deploy-time paging silently -- there is a promtool case pinning it.
          #
          # for: 3m sits well below the 10m increase() range per 18_Fault_Tolerance section 8.4:
          # an isolated burst ages out of the range and flips the expression false before a longer
          # for: is satisfied, which would defeat alerting on exactly the burst this is for.
          expr: increase(recsys_queue_rejected_total{reason="full"}[10m]) > 0
          for: 3m
          labels:
            severity: warning
          annotations:
            summary: "Queue {{ $labels.queue }} on {{ $labels.job }} is rejecting work"
            description: >-
              This queue is full and discarding work now. For async-events that is lost
              interaction events; for a recall bulkhead it is degraded recommendations. Check
              recsys_queue_utilization for how long pressure had been building, and note that
              reason="shutdown" and reason="invalid_key" are deliberately excluded -- the latter
              is a data fault, not saturation.
```

- [ ] **Step 3: Write the promtool cases**

Append to `k8s/base/prometheus-rules.test.yaml`. Two of these matter more than the others and must be written deliberately.

```yaml
  # --- RecsysQueueFillingUp ---
  - interval: 1m
    input_series:
      # 90% full and staying there: must fire.
      - series: 'recsys_queue_utilization{queue="async-events", job="recsys-online-serving"}'
        values: '0.9x20'
      # 60% and staying there: near-miss, must NOT fire.
      - series: 'recsys_queue_utilization{queue="recall-online", job="recsys-online-serving"}'
        values: '0.6x20'
    alert_rule_test:
      - eval_time: 15m
        alertname: RecsysQueueFillingUp
        exp_alerts:
          - exp_labels:
              severity: warning
              queue: async-events
              job: recsys-online-serving
            exp_annotations:
              summary: "Queue async-events on recsys-online-serving is sustained above 70% full"
              description: >-
                Sustained pressure with nothing lost yet. Check whether the consumer has slowed
                (the drain thread for async-events, the recall workers for a bulkhead) before
                raising the bound -- a larger queue buys latency, not throughput. See
                docs/runbooks/overload-protection.md.

  # --- RecsysQueueFillingUp: a brief excursion must NOT fire ---
  # This is what separates an early-warning signal from noise. A queue that spikes above the
  # line for two scrapes and drains is working as designed.
  - interval: 1m
    input_series:
      - series: 'recsys_queue_utilization{queue="recall-catalog", job="recsys-catalog-serving"}'
        values: '0.1 0.1 0.9 0.9 0.1 0.1 0.1 0.1 0.1 0.1 0.1 0.1 0.1 0.1 0.1'
    alert_rule_test:
      - eval_time: 14m
        alertname: RecsysQueueFillingUp
        exp_alerts: []

  # --- RecsysQueueRejecting ---
  - interval: 1m
    input_series:
      - series: 'recsys_queue_rejected_total{queue="async-events", job="recsys-online-serving", reason="full"}'
        values: '0+5x25'
    alert_rule_test:
      - eval_time: 20m
        alertname: RecsysQueueRejecting
        exp_alerts:
          - exp_labels:
              severity: warning
              queue: async-events
              job: recsys-online-serving
              reason: full
            exp_annotations:
              summary: "Queue async-events on recsys-online-serving is rejecting work"
              description: >-
                This queue is full and discarding work now. For async-events that is lost
                interaction events; for a recall bulkhead it is degraded recommendations. Check
                recsys_queue_utilization for how long pressure had been building, and note that
                reason="shutdown" and reason="invalid_key" are deliberately excluded -- the latter
                is a data fault, not saturation.

  # --- RecsysQueueRejecting: shutdown and invalid_key must NOT fire ---
  # THE case that protects against paging on every rolling deploy. Both implementations count
  # shutdown refusals; without the reason="full" matcher this input fires.
  - interval: 1m
    input_series:
      - series: 'recsys_queue_rejected_total{queue="async-events", job="recsys-online-serving", reason="shutdown"}'
        values: '0+20x25'
      - series: 'recsys_queue_rejected_total{queue="async-events", job="recsys-online-serving", reason="invalid_key"}'
        values: '0+20x25'
    alert_rule_test:
      - eval_time: 20m
        alertname: RecsysQueueRejecting
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

`prometheus-rules.rules.yaml` is generated and gitignored. Do not commit it.

- [ ] **Step 5: Prove each case can fail**

Four mutations, each with a specific expected red:

1. Raise `RecsysQueueFillingUp`'s threshold to `> 0.95` → the fire case goes red.
2. Lower it to `> 0.05` → the near-miss and the brief-excursion cases go red.
3. Shorten `for:` on `RecsysQueueFillingUp` to `1m` → the brief-excursion case goes red.
4. **Remove `{reason="full"}` from `RecsysQueueRejecting`** → the shutdown/invalid_key case goes red.

Mutation 4 is the important one: it is the only thing standing between this alert and a page on every deploy. Restore after each.

- [ ] **Step 6: Commit**

```bash
cd /Users/linghuang/Git/Recsys-Backend-Service
rm -f k8s/base/prometheus-rules.rules.yaml
git add k8s/base/prometheus-rules.yaml k8s/base/prometheus-rules.test.yaml
git commit -m "feat(alerts): queue utilization and reasoned rejection alerts

Two signals, different in kind rather than two thresholds on one thing:
utilization is early warning (pressure, nothing lost), rejections are
evidence of actual loss.

RecsysQueueRejecting is scoped to reason=full because both queue
implementations also count shutdown refusals -- unscoped it fires on every
rolling deploy. A promtool case pins that: shutdown and invalid_key series at
a firing rate must produce no alert.

A second case pins that a brief excursion above 0.7 does not fire, which is
what separates an early-warning signal from noise."
```

---

### Task 6: Documentation

**Files:**
- Modify: `docs/system_design/18_Fault_Tolerance.md`
- Modify: `docs/runbooks/overload-protection.md`

- [ ] **Step 1: Add a Queues subsection to the §8.3 inventory**

After the Runtime table, matching the surrounding style — dense, referencing the registering file, explaining what breaks rather than narrating what the code does:

```markdown
**Queues** — the bounded queues on the message path. Before 2026-08-25 neither published
anything: `AsyncEventPublisher` computed `queue.size()` into a `Snapshot` that reached no
registry, and `WorkerBulkhead` had no metrics at all, its depth served as JSON by
`CatalogLoadService` and read by no collector. Drops were visible only after the fact, so a queue
filling up was invisible until it overflowed.

| Metric | Registered in |
|---|---|
| `recsys_queue_depth`, `_capacity`, `_utilization` (all tagged `queue`), `recsys_queue_rejected_total` (tagged `queue`, `reason`) | [`metrics/QueueMetrics.java`](../../src/main/java/com/recsys/metrics/QueueMetrics.java) |

Registered queues: `recall-catalog` (6010), `recall-online` and `async-events` (7010).
```

Then add the three facts a future maintainer needs and cannot re-derive cheaply:

- `capacity` is the **effective** bound, not the configured one — both constructors clamp `Math.max(1, n)`, so a requested `0` yields a one-entry queue and the metric says `1`.
- `reason` separates `full` from `shutdown` and `invalid_key`; only `full` is a saturation signal, and `async_events_dropped_total` remains the all-reasons total and is therefore **not** a pure saturation signal.
- Registration throws on a duplicate queue name because Micrometer would otherwise return the first meter and silently discard the second source — measured, not assumed.

- [ ] **Step 2: Add the two alerts to the §8.4 table**

Two rows in the established four-column format (Alert / Means / Likely cause / First response). `RecsysQueueFillingUp`'s first response must say to check the consumer before raising the bound. `RecsysQueueRejecting`'s must say that `reason="shutdown"` is excluded deliberately and that seeing it during a deploy is expected.

- [ ] **Step 3: State the scrape-interval limitation where an operator would act on it**

In §8.4, near the new rows:

```markdown
**A 15 s scrape cannot see a queue that fills and drains between samples.** `recsys_queue_depth`
and `_utilization` catch sustained pressure; `recsys_queue_rejected_total` catches the bursty case,
because a counter records an event a sampled gauge can miss entirely. A flat depth graph is
therefore half the picture, and the two alerts are complementary rather than redundant. The
remaining gap is a queue that repeatedly reaches ~95% and drains without ever rejecting: invisible
to both. Closing it needs a peak-depth metric, which was deliberately deferred — see the design
doc for why, including the hot-path cost and the decaying-max sharp edge already documented for
`OutboxDeliveryLatencyHigh`.
```

- [ ] **Step 4: Update the overload-protection runbook**

`docs/runbooks/overload-protection.md` already owns this subject. Add: how to read the four metrics when diagnosing an overload; that `reason="full"` is the saturation signal and the other two are not; and the drop-rather-than-block reasoning — `publish()` drops instead of blocking so a serving thread is never stalled inside a 500 ms request budget, which is why the throttle question belongs at admission and not at the queue.

- [ ] **Step 5: Verify the documentation tests still pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='DocumentationIndexTest,DocumentedMechanismTest'
```

Expected: PASS. No new file is added under `docs/system_design/` or `docs/runbooks/`, so the index test should be unaffected; if `DocumentedMechanismTest` objects to a cited class name, read what it wants rather than removing the citation.

- [ ] **Step 6: Full gate, then commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
git add docs/system_design/18_Fault_Tolerance.md docs/runbooks/overload-protection.md
git commit -m "docs: record the queue backpressure metrics and alerts

Adds a Queues subsection to the section 8.3 inventory and the two alerts to
8.4, plus the three facts that are expensive to re-derive: capacity is the
effective bound not the configured one, only reason=full is a saturation
signal (so async_events_dropped_total is not one), and duplicate queue names
throw because Micrometer would otherwise alias two queues onto one meter.

States the scrape-interval limitation where an operator would act on it,
including the gap neither alert covers -- a queue that repeatedly nears full
and drains without rejecting."
```

---

### Task 7: Open the pull request

- [ ] **Step 1: Clean-build the gate**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn clean test -Presilience
```

- [ ] **Step 2: Review the whole diff**

```bash
git diff origin/main --stat
git diff origin/main
```

Confirm specifically: no `prometheus-rules.rules.yaml`, no probe or scratch test class left behind, and **no mutation from a "prove it fails" step left in place**. Those steps deliberately break things; each has a restore instruction, and a missed restore is the likeliest way this lands broken.

- [ ] **Step 3: Push and open the PR**

```bash
git push -u origin feat/queue-backpressure-observability
gh pr create --title "Queue backpressure observability: see the queues fill, not just overflow" --body "$(cat <<'EOF'
Implements `docs/superpowers/specs/2026-08-25-queue-backpressure-observability-design.md`.

## What this adds

Depth, capacity, utilization and reasoned rejection metrics for the three bounded queues on the message path — `recall-catalog` (6010), `recall-online` and `async-events` (7010) — plus two alerts.

## The finding

Backpressure already existed here in three shapes; `AsyncEventPublisher` drops and `WorkerBulkhead` rejects, both deliberately. What did not exist was the ability to see it coming. Both queues **computed their own depth and published it nowhere**: `AsyncEventPublisher.Snapshot` carried a `queueSize` registered on no registry, and `WorkerBulkhead` had no metrics at all — its depth served as JSON from a health endpoint and read by no collector, the same pull-only shape `JvmMemoryMonitor` had before #293. Neither snapshot carried capacity, so utilization could not be expressed at all.

## Three things measurement changed

- **Rejection counters conflated saturation with shutdown**, on both queues. `publish()` opens with `if (!running) return recordRejectedEvent()`, and `WorkerBulkhead` counted every `RejectedExecutionException`, which `ThreadPoolExecutor` throws for a full queue *or a shut-down executor*. As originally designed the alert would have fired on **every rolling deploy**. A third cause turned up in the Kafka subclass — a missing partition key, a data fault that would have presented as saturation.
- **Micrometer aliases duplicate registrations silently.** Probed: two gauges over different objects under the same name+tags yield one meter, `g1 == g2`, both reading the first object. A second queue under a taken name would report the first one's depth forever. Registration now throws.
- **`FunctionCounter.Builder` has no `strongReference` option** where `Gauge.Builder` does — so the rejection counter is the one meter that cannot protect itself, and per §8.3 its failure is to freeze silently rather than report `NaN`. The duplicate-name map holds every source strongly, closing that as a consequence rather than as a separate field whose rationale can rot.

## Capacity semantics

`capacity` is the **effective** bound, not the configured one: both constructors clamp `Math.max(1, n)`, so a requested `0` yields a one-entry queue and the metric truthfully says `1`. A non-positive capacity is therefore unreachable today and would be a programming error in a future source — registration throws rather than publishing a series that reads as healthy-and-empty for a broken queue. Capacity `0` is explicitly never a sentinel for "unbounded".

## Not in this change

No throttle and no control loop — that decision is deliberately deferred until these numbers exist, and any throttle belongs at admission rather than at `publish()`, which would stall a serving thread inside a 500 ms request budget. No high-water-mark metric; the design records why, including its hot-path cost.

## Verification

`mvn clean test -Presilience` green. `promtool check rules` + `test rules` green, with fire and near-miss cases per alert plus two that matter more than the rest: shutdown-reason rejections must **not** fire, and a brief excursion above 0.7 must **not** fire.

Every guard was mutation-proven — including the `reason="full"` matcher, which is the only thing standing between this alert and a page on every deploy.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review

**Spec coverage.** Metrics contract → Task 1 (all four families, the enum, the validation). Capacity-is-a-positive-invariant → Task 1 Steps 1/3 plus Tasks 2/3 for the clamp WARN and effective-value reporting. Rejection semantics including `invalid_key` → Tasks 2, 3. Ownership/lifetime → Task 1's `REGISTERED` map, `strongReference(true)` on gauges, and the GC test in Steps 6–7. Utilization computed in-process, unclamped → Task 1 Step 3 and the `utilizationIsNotClampedAboveOne` test. Alerts and their two-signal semantics → Task 5. Scrape-interval limitation and the deferred high-water mark → Task 6 Step 3. Non-goals are carried in the PR body.

**Placeholder scan.** No TBD/TODO. Two steps deliberately defer to what the implementer finds rather than guessing: Task 3 Step 5 (repair whatever the widened record breaks — the exact call sites are compiler output, not something to invent) and Task 5 Step 1 (write the alert against the metric name the exposition actually shows, since Micrometer's `_total` suffixing must be observed rather than assumed). Both name the command that produces the answer.

**Type consistency.** `QueueMetrics.Source` — `depth()`, `capacity()`, `rejected(RejectionReason)` — is defined in Task 1 and implemented unchanged in Tasks 2 and 3. `RejectionReason.FULL/SHUTDOWN/INVALID_KEY` with `tag()` is used identically in Tasks 1, 2, 3 and in Task 5's label matcher (`reason="full"`). Queue names `recall-catalog`, `recall-online`, `async-events` are identical in Task 4's registration, Task 5's promtool series, and Task 6's docs. Both `Snapshot` records gain `queueCapacity` and both are read back in their tasks' tests.

**Corrected during self-review.** The plan originally said "add to the profile if not already
there" for both existing test classes. Verified instead: `**/resilience/*Test.java` already gates
`WorkerBulkheadTest`, while nothing matches `infrastructure/messaging/`, so
`AsyncEventPublisherTest` gates nothing today and needs an explicit include. Left as a conditional
it would most likely have been resolved the wrong way in both directions.

**Known soft spots, flagged rather than hidden.** Task 3's `FULL` count is derived by subtraction from the pre-existing `droppedCount` so `async_events_dropped_total` keeps its meaning; that arithmetic is the one place a future fourth reason would silently corrupt the `full` figure, and the task requires a test that the three reasons sum to `droppedCount`. Task 1 Step 7's mutation may not go red if the `Source` is reachable through the test's own stack — the step requires reporting that honestly rather than claiming proof, which is exactly how the `JvmMetricsBinder.RETAINED` claim went wrong before.
