# Signal-Driven Capacity Controller (Reference) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the dangling `AutoScalingGroup` scaling loop with a reference `CapacityController` — a target-tracking policy + surge override + asymmetric cooldowns driving `setDesiredCapacity` via an actuator seam, fed by the existing `OnlineCapacityService` signals. Not wired into production.

**Architecture:** New package `com.recsys.application.autoscaling`. A pure `CapacityScalingPolicy`, a `CapacityController` (tick loop + cooldowns), a `CapacityActuator` seam with an `AsgCapacityActuator` adapter over the untouched `AutoScalingGroup`, and an `OnlineCapacitySignalSource` adapter over the real capacity snapshot. Unit-tested throughout, plus one end-to-end drive of a real `AutoScalingGroup`.

**Tech Stack:** Java 17, JUnit 5 + AssertJ, Maven. No new dependencies.

## Global Constraints

- **Build/test with JDK 17:** `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`.
- **New package:** `com.recsys.application.autoscaling` (main + test).
- **Not production-wired:** no registration in `OnlinePredictionServer` or any server; class docs state it's a reference over the in-memory ASG simulation (real scaling = HPA + cluster-autoscaler).
- **Do not modify** `AutoScalingGroup`, `ScalingConfig`, `InstanceProvisioner`, `OnlineCapacityService`, or any k8s/HPA manifest.
- **Policy defaults:** `targetUtilization` **0.7**, `surgeStep` **2**. **Controller cooldown defaults:** scale-out **60_000 ms**, scale-in **300_000 ms**.
- **`OnlineCapacityService.Snapshot`** is `(long targetDau, long peakQps, long peakTps, double observedQps, double qpsUtilization, double headroomQps, boolean overloaded, OnlineLoadShedder.Snapshot load, String peakShaving)` — accessors `qpsUtilization()` and `overloaded()`.

---

## File Structure

- Create `src/main/java/com/recsys/application/autoscaling/CapacitySignal.java`
- Create `.../CapacitySignalSource.java`
- Create `.../CapacityScalingPolicy.java`  (+ test)
- Create `.../CapacityActuator.java`
- Create `.../AsgCapacityActuator.java`  (+ test, end-to-end over real ASG)
- Create `.../CapacityController.java`  (+ tests: fake actuator + e2e)
- Create `.../OnlineCapacitySignalSource.java`  (+ test)

---

## Task 1: Policy + signal types

**Files:**
- Create: `src/main/java/com/recsys/application/autoscaling/CapacitySignal.java`
- Create: `src/main/java/com/recsys/application/autoscaling/CapacitySignalSource.java`
- Create: `src/main/java/com/recsys/application/autoscaling/CapacityScalingPolicy.java`
- Test: `src/test/java/com/recsys/application/autoscaling/CapacityScalingPolicyTest.java`

**Interfaces:**
- Produces: `record CapacitySignal(double utilization, boolean surge)`; `@FunctionalInterface CapacitySignalSource { CapacitySignal read(); }`; `CapacityScalingPolicy` with `int desiredReplicas(int currentRunning, double utilization, boolean surge)`, ctors `()` and `(double targetUtilization, int surgeStep)`, accessors `targetUtilization()`/`surgeStep()`.

- [ ] **Step 1: Write the failing policy test**

```java
package com.recsys.application.autoscaling;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapacityScalingPolicyTest {

    private final CapacityScalingPolicy policy = new CapacityScalingPolicy(); // target 0.7, surge 2

    @Test void targetTrackingScalesOut() {
        assertThat(policy.desiredReplicas(5, 1.40, false)).isEqualTo(10); // ceil(5*1.4/0.7)
    }

    @Test void targetTrackingScalesIn() {
        assertThat(policy.desiredReplicas(6, 0.35, false)).isEqualTo(3);  // ceil(6*0.35/0.7)
    }

    @Test void steadyAtTarget() {
        assertThat(policy.desiredReplicas(4, 0.70, false)).isEqualTo(4);
    }

    @Test void surgeOverridesLowUtilization() {
        assertThat(policy.desiredReplicas(3, 0.10, true)).isEqualTo(5);   // max(1, 3+2)
    }

    @Test void zeroRunningUsesBaseOne() {
        assertThat(policy.desiredReplicas(0, 0.70, false)).isEqualTo(1);  // ceil(1*0.7/0.7)
    }

    @Test void zeroUtilizationScalesToZero() {
        assertThat(policy.desiredReplicas(5, 0.0, false)).isEqualTo(0);
    }

    @Test void negativeOrNanUtilizationTreatedAsZero() {
        assertThat(policy.desiredReplicas(5, -3.0, false)).isEqualTo(0);
        assertThat(policy.desiredReplicas(5, Double.NaN, false)).isEqualTo(0);
    }

    @Test void invalidConfigRejected() {
        assertThatThrownBy(() -> new CapacityScalingPolicy(0.0, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapacityScalingPolicy(0.7, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run — expect FAIL (compile error, classes missing)**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=CapacityScalingPolicyTest`
Expected: FAIL — `CapacityScalingPolicy` etc. don't exist.

- [ ] **Step 3: Create the three classes**

`CapacitySignal.java`:
```java
package com.recsys.application.autoscaling;

/** A capacity reading: current utilization (>= 0) and whether the system is overloaded (surge). */
public record CapacitySignal(double utilization, boolean surge) {}
```

`CapacitySignalSource.java`:
```java
package com.recsys.application.autoscaling;

/** Supplies the current {@link CapacitySignal} for the controller to act on. */
@FunctionalInterface
public interface CapacitySignalSource {
    CapacitySignal read();
}
```

`CapacityScalingPolicy.java`:
```java
package com.recsys.application.autoscaling;

/**
 * Pure target-tracking scaling policy (the algorithm AWS ASG target-tracking and Kubernetes HPA
 * both use): desired = ceil(running * utilization / targetUtilization). A surge override raises the
 * desired by {@code surgeStep} above the current count when the system reports overload, so a sudden
 * spike does not wait for utilization to be observed. Clamping to [min,max] is the actuator's job.
 */
public final class CapacityScalingPolicy {
    public static final double DEFAULT_TARGET_UTILIZATION = 0.7;
    public static final int    DEFAULT_SURGE_STEP         = 2;

    private final double targetUtilization;
    private final int surgeStep;

    public CapacityScalingPolicy() {
        this(DEFAULT_TARGET_UTILIZATION, DEFAULT_SURGE_STEP);
    }

    public CapacityScalingPolicy(double targetUtilization, int surgeStep) {
        if (!(targetUtilization > 0.0) || Double.isInfinite(targetUtilization)) {
            throw new IllegalArgumentException("targetUtilization must be finite and > 0");
        }
        if (surgeStep < 1) {
            throw new IllegalArgumentException("surgeStep must be >= 1");
        }
        this.targetUtilization = targetUtilization;
        this.surgeStep = surgeStep;
    }

    public int desiredReplicas(int currentRunning, double utilization, boolean surge) {
        double util = (Double.isNaN(utilization) || Double.isInfinite(utilization) || utilization < 0.0)
                ? 0.0 : utilization;
        int base = Math.max(1, currentRunning);
        int raw = (int) Math.ceil(base * util / targetUtilization);
        if (surge) {
            raw = Math.max(raw, currentRunning + surgeStep);
        }
        return Math.max(0, raw);
    }

    public double targetUtilization() { return targetUtilization; }
    public int surgeStep() { return surgeStep; }
}
```

- [ ] **Step 4: Run — expect PASS**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=CapacityScalingPolicyTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/application/autoscaling/CapacitySignal.java \
        src/main/java/com/recsys/application/autoscaling/CapacitySignalSource.java \
        src/main/java/com/recsys/application/autoscaling/CapacityScalingPolicy.java \
        src/test/java/com/recsys/application/autoscaling/CapacityScalingPolicyTest.java
git commit -m "feat: CapacityScalingPolicy target-tracking + surge, and signal types"
```

---

## Task 2: `CapacityActuator` seam + `AsgCapacityActuator`

**Files:**
- Create: `src/main/java/com/recsys/application/autoscaling/CapacityActuator.java`
- Create: `src/main/java/com/recsys/application/autoscaling/AsgCapacityActuator.java`
- Test: `src/test/java/com/recsys/application/autoscaling/AsgCapacityActuatorTest.java`

**Interfaces:**
- Produces: `interface CapacityActuator { int runningCount(); int minSize(); int maxSize(); void setDesiredCapacity(int); }`; `AsgCapacityActuator(AutoScalingGroup)` implementing it.

- [ ] **Step 1: Write the failing adapter test**

```java
package com.recsys.application.autoscaling;

import com.recsys.infrastructure.autoscaling.AutoScalingGroup;
import com.recsys.infrastructure.autoscaling.Ec2Instance;
import com.recsys.infrastructure.autoscaling.InstanceProvisioner;
import com.recsys.infrastructure.autoscaling.InstanceState;
import com.recsys.infrastructure.autoscaling.LaunchTemplate;
import com.recsys.infrastructure.autoscaling.NetworkConfig;
import com.recsys.infrastructure.autoscaling.ScalingConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AsgCapacityActuatorTest {

    /** Local fake — the one in AutoScalingGroupTest is package-private in a different package. */
    static final class FakeProvisioner implements InstanceProvisioner {
        private final AtomicInteger id = new AtomicInteger();
        @Override public Ec2Instance launch(LaunchTemplate t, String az) {
            int n = id.incrementAndGet();
            return new Ec2Instance("i-" + String.format("%017d", n),
                    "10.0.0." + n, t.instanceType(), az, InstanceState.RUNNING);
        }
        @Override public void terminate(String instanceId) { }
    }

    static AutoScalingGroup newAsg() {
        LaunchTemplate template = LaunchTemplate.builder("lt-recsys")
                .imageId("ami-0abcdef1234567890").instanceType("t3.medium")
                .servicePort(8080).securityGroup("sg-web").build();
        NetworkConfig network = NetworkConfig.builder("vpc-12345678")
                .availabilityZone("us-east-1a").availabilityZone("us-east-1b").availabilityZone("us-east-1c")
                .subnet("subnet-1a").subnet("subnet-1b").subnet("subnet-1c")
                .build();
        return AutoScalingGroup.builder("recsys-asg")
                .launchTemplate(template).networkConfig(network)
                .scalingConfig(ScalingConfig.of(1, 6, 2))
                .provisioner(new FakeProvisioner())
                .build();
    }

    @Test void exposesRunningCountAndBounds() {
        AutoScalingGroup asg = newAsg();
        asg.setDesiredCapacity(2);
        AsgCapacityActuator actuator = new AsgCapacityActuator(asg);
        assertThat(actuator.runningCount()).isEqualTo(2);
        assertThat(actuator.minSize()).isEqualTo(1);
        assertThat(actuator.maxSize()).isEqualTo(6);
    }

    @Test void setDesiredCapacityDrivesAsgAndClampsToMax() {
        AutoScalingGroup asg = newAsg();
        AsgCapacityActuator actuator = new AsgCapacityActuator(asg);
        actuator.setDesiredCapacity(20);                 // above max 6
        assertThat(actuator.runningCount()).isEqualTo(6); // ASG clamps
    }
}
```

> Before running, confirm against the real sources: `LaunchTemplate.builder(...).imageId/instanceType/servicePort/securityGroup`, `NetworkConfig.builder(vpc).availabilityZone/subnet`, `Ec2Instance(id, ip, instanceType, az, InstanceState)`, and `AutoScalingGroup.builder(name).launchTemplate/networkConfig/scalingConfig/provisioner/build`. This construction is copied from `AutoScalingGroupTest`; match it exactly (drop `.targetGroup(...)` — it's optional). If any builder method name differs, fix the TEST to match; do not change production.

- [ ] **Step 2: Run — expect FAIL**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=AsgCapacityActuatorTest`
Expected: FAIL — `CapacityActuator` / `AsgCapacityActuator` missing.

- [ ] **Step 3: Create the interface + adapter**

`CapacityActuator.java`:
```java
package com.recsys.application.autoscaling;

/** Actuation seam the controller drives — decouples it from the concrete AutoScalingGroup. */
public interface CapacityActuator {
    int runningCount();
    int minSize();
    int maxSize();
    void setDesiredCapacity(int desired);
}
```

`AsgCapacityActuator.java`:
```java
package com.recsys.application.autoscaling;

import com.recsys.infrastructure.autoscaling.AutoScalingGroup;

import java.util.Objects;

/** Adapts the in-memory {@link AutoScalingGroup} simulation to {@link CapacityActuator}. */
public final class AsgCapacityActuator implements CapacityActuator {
    private final AutoScalingGroup asg;

    public AsgCapacityActuator(AutoScalingGroup asg) {
        this.asg = Objects.requireNonNull(asg, "asg");
    }

    @Override public int runningCount() { return asg.runningCount(); }
    @Override public int minSize() { return asg.scalingConfig().minSize(); }
    @Override public int maxSize() { return asg.scalingConfig().maxSize(); }
    @Override public void setDesiredCapacity(int desired) { asg.setDesiredCapacity(desired); }
}
```

- [ ] **Step 4: Run — expect PASS**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=AsgCapacityActuatorTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/application/autoscaling/CapacityActuator.java \
        src/main/java/com/recsys/application/autoscaling/AsgCapacityActuator.java \
        src/test/java/com/recsys/application/autoscaling/AsgCapacityActuatorTest.java
git commit -m "feat: CapacityActuator seam + AsgCapacityActuator over AutoScalingGroup"
```

---

## Task 3: `CapacityController` (tick + cooldowns) + end-to-end drive

**Files:**
- Create: `src/main/java/com/recsys/application/autoscaling/CapacityController.java`
- Test: `src/test/java/com/recsys/application/autoscaling/CapacityControllerTest.java`
- Test: `src/test/java/com/recsys/application/autoscaling/CapacityControllerAsgTest.java`

**Interfaces:**
- Consumes: `CapacityActuator` (T2), `CapacitySignalSource`/`CapacitySignal`/`CapacityScalingPolicy` (T1), `AsgCapacityActuator` (T2).
- Produces: `CapacityController` with `ScalingDecision tick()`, `start(ScheduledExecutorService, Duration)`, ctors `(actuator, signals, policy)` and `(actuator, signals, policy, long outCooldownMs, long inCooldownMs, LongSupplier clockMs)`; `record ScalingDecision(int running, int desired, boolean applied, String reason)`.

- [ ] **Step 1: Write the failing controller unit tests (fake actuator)**

```java
package com.recsys.application.autoscaling;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class CapacityControllerTest {

    /** Fake actuator: mutable runningCount, clamps setDesiredCapacity into [min,max]. */
    static final class FakeActuator implements CapacityActuator {
        int running; final int min; final int max; int lastDesired = -1; int applyCount = 0;
        FakeActuator(int running, int min, int max) { this.running = running; this.min = min; this.max = max; }
        @Override public int runningCount() { return running; }
        @Override public int minSize() { return min; }
        @Override public int maxSize() { return max; }
        @Override public void setDesiredCapacity(int d) {
            lastDesired = d; applyCount++; running = Math.max(min, Math.min(max, d));
        }
    }

    private static CapacitySignalSource signal(double util, boolean surge) {
        return () -> new CapacitySignal(util, surge);
    }

    @Test void scalesOutWhenOverTarget() {
        FakeActuator a = new FakeActuator(5, 1, 20);
        CapacityController c = new CapacityController(a, signal(1.40, false),
                new CapacityScalingPolicy(), 60_000, 300_000, () -> 0L);
        CapacityController.ScalingDecision d = c.tick();
        assertThat(d.applied()).isTrue();
        assertThat(d.desired()).isEqualTo(10);
        assertThat(d.reason()).isEqualTo("scaled-out");
        assertThat(a.lastDesired).isEqualTo(10);
    }

    @Test void steadyDoesNotApply() {
        FakeActuator a = new FakeActuator(4, 1, 20);
        CapacityController c = new CapacityController(a, signal(0.70, false),
                new CapacityScalingPolicy(), 60_000, 300_000, () -> 0L);
        CapacityController.ScalingDecision d = c.tick();
        assertThat(d.applied()).isFalse();
        assertThat(d.reason()).isEqualTo("steady");
        assertThat(a.applyCount).isZero();
    }

    @Test void scaleOutCooldownGatesUntilElapsed() {
        FakeActuator a = new FakeActuator(5, 1, 50);
        AtomicLong clock = new AtomicLong(0);
        CapacityController c = new CapacityController(a, signal(1.40, false),
                new CapacityScalingPolicy(), 60_000, 300_000, clock::get);
        assertThat(c.tick().applied()).isTrue();          // t=0: 5 -> 10
        clock.set(30_000);
        CapacityController.ScalingDecision gated = c.tick(); // within out-cooldown
        assertThat(gated.applied()).isFalse();
        assertThat(gated.reason()).isEqualTo("cooldown");
        clock.set(70_000);
        assertThat(c.tick().applied()).isTrue();          // past out-cooldown
    }

    @Test void scaleInUsesLongerCooldown() {
        FakeActuator a = new FakeActuator(10, 1, 50);
        AtomicLong clock = new AtomicLong(0);
        CapacityController c = new CapacityController(a, signal(0.10, false),
                new CapacityScalingPolicy(), 60_000, 300_000, clock::get);
        CapacityController.ScalingDecision first = c.tick(); // t=0: 10 -> ceil(10*.1/.7)=2
        assertThat(first.applied()).isTrue();
        assertThat(first.reason()).isEqualTo("scaled-in");
        clock.set(120_000);                                  // past OUT cooldown, within IN cooldown
        CapacityController.ScalingDecision gated = c.tick();  // 2 -> ceil(2*.1/.7)=1, gated
        assertThat(gated.applied()).isFalse();
        assertThat(gated.reason()).isEqualTo("cooldown");
    }

    @Test void surgeScalesOutByStep() {
        FakeActuator a = new FakeActuator(3, 1, 50);
        CapacityController c = new CapacityController(a, signal(0.10, true),
                new CapacityScalingPolicy(), 60_000, 300_000, () -> 0L);
        CapacityController.ScalingDecision d = c.tick();
        assertThat(d.applied()).isTrue();
        assertThat(d.desired()).isEqualTo(5);               // max(1, 3+2)
    }
}
```

- [ ] **Step 2: Run — expect FAIL**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=CapacityControllerTest`
Expected: FAIL — `CapacityController` missing.

- [ ] **Step 3: Create `CapacityController`**

```java
package com.recsys.application.autoscaling;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Reference signal-driven capacity controller over the in-memory {@link AsgCapacityActuator}
 * simulation. Each {@link #tick()} reads a {@link CapacitySignal}, computes a target-tracking
 * desired capacity, clamps it to the actuator's [min,max], and applies it via the actuator only
 * when it differs from the running count AND the direction's cooldown has elapsed (asymmetric:
 * fast scale-out, slow scale-in — mirroring the model-serving HPA).
 *
 * <p><b>Not wired into production.</b> Real scaling in EKS is HPA + cluster-autoscaler; this exists
 * to close the otherwise-dangling {@code AutoScalingGroup} loop as a tested reference.
 */
public final class CapacityController {
    public static final long DEFAULT_SCALE_OUT_COOLDOWN_MS = 60_000L;
    public static final long DEFAULT_SCALE_IN_COOLDOWN_MS   = 300_000L;

    private final CapacityActuator actuator;
    private final CapacitySignalSource signals;
    private final CapacityScalingPolicy policy;
    private final long scaleOutCooldownMs;
    private final long scaleInCooldownMs;
    private final LongSupplier clockMs;

    // Initialised far in the past (without underflow) so the first tick can always act.
    private long lastScaleOutMs = Long.MIN_VALUE / 2;
    private long lastScaleInMs  = Long.MIN_VALUE / 2;

    public CapacityController(CapacityActuator actuator, CapacitySignalSource signals,
                              CapacityScalingPolicy policy) {
        this(actuator, signals, policy, DEFAULT_SCALE_OUT_COOLDOWN_MS, DEFAULT_SCALE_IN_COOLDOWN_MS,
                System::currentTimeMillis);
    }

    public CapacityController(CapacityActuator actuator, CapacitySignalSource signals,
                              CapacityScalingPolicy policy, long scaleOutCooldownMs,
                              long scaleInCooldownMs, LongSupplier clockMs) {
        this.actuator = Objects.requireNonNull(actuator, "actuator");
        this.signals = Objects.requireNonNull(signals, "signals");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.scaleOutCooldownMs = Math.max(0L, scaleOutCooldownMs);
        this.scaleInCooldownMs = Math.max(0L, scaleInCooldownMs);
        this.clockMs = Objects.requireNonNull(clockMs, "clockMs");
    }

    public synchronized ScalingDecision tick() {
        CapacitySignal s = signals.read();
        int running = actuator.runningCount();
        int desired = clamp(policy.desiredReplicas(running, s.utilization(), s.surge()),
                actuator.minSize(), actuator.maxSize());
        if (desired == running) {
            return new ScalingDecision(running, desired, false, "steady");
        }
        boolean out = desired > running;
        long now = clockMs.getAsLong();
        long sinceLast = out ? now - lastScaleOutMs : now - lastScaleInMs;
        long cooldown = out ? scaleOutCooldownMs : scaleInCooldownMs;
        if (sinceLast < cooldown) {
            return new ScalingDecision(running, desired, false, "cooldown");
        }
        actuator.setDesiredCapacity(desired);
        if (out) { lastScaleOutMs = now; } else { lastScaleInMs = now; }
        return new ScalingDecision(running, desired, true, out ? "scaled-out" : "scaled-in");
    }

    /** Optional convenience: run {@link #tick()} on a fixed-delay schedule. Not used in production. */
    public void start(ScheduledExecutorService scheduler, Duration interval) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(interval, "interval");
        long ms = Math.max(1L, interval.toMillis());
        scheduler.scheduleWithFixedDelay(this::tick, ms, ms, TimeUnit.MILLISECONDS);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public record ScalingDecision(int running, int desired, boolean applied, String reason) {}
}
```

- [ ] **Step 4: Run — expect PASS**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=CapacityControllerTest`
Expected: PASS.

- [ ] **Step 5: Write the end-to-end test (controller + AsgCapacityActuator over a real ASG)**

```java
package com.recsys.application.autoscaling;

import com.recsys.infrastructure.autoscaling.AutoScalingGroup;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CapacityControllerAsgTest {

    @Test void controllerDrivesRealAsgAndAsgClampsToMax() {
        AutoScalingGroup asg = AsgCapacityActuatorTest.newAsg(); // reuse the same construction
        asg.setDesiredCapacity(2);
        AsgCapacityActuator actuator = new AsgCapacityActuator(asg);

        // High utilization pushes desired well above max (6); the ASG must clamp.
        CapacitySignalSource hot = () -> new CapacitySignal(5.0, false);
        CapacityController controller = new CapacityController(
                actuator, hot, new CapacityScalingPolicy(), 0L, 0L, () -> 0L);

        CapacityController.ScalingDecision d = controller.tick();
        assertThat(d.applied()).isTrue();
        assertThat(d.desired()).isEqualTo(6);            // controller clamped to actuator.maxSize()
        assertThat(asg.runningCount()).isEqualTo(6);     // real ASG scaled out and clamped
    }
}
```

> `AsgCapacityActuatorTest.newAsg()` and its `FakeProvisioner` are `static`/package-private in the
> same test package, so this reuse compiles. If you prefer not to cross-reference, inline the same
> `newAsg()` here — but do not duplicate the provisioner class publicly.

- [ ] **Step 6: Run both controller tests — expect PASS**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=CapacityControllerTest,CapacityControllerAsgTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/application/autoscaling/CapacityController.java \
        src/test/java/com/recsys/application/autoscaling/CapacityControllerTest.java \
        src/test/java/com/recsys/application/autoscaling/CapacityControllerAsgTest.java
git commit -m "feat: CapacityController tick loop with asymmetric cooldowns + e2e ASG drive"
```

---

## Task 4: `OnlineCapacitySignalSource` adapter

**Files:**
- Create: `src/main/java/com/recsys/application/autoscaling/OnlineCapacitySignalSource.java`
- Test: `src/test/java/com/recsys/application/autoscaling/OnlineCapacitySignalSourceTest.java`

**Interfaces:**
- Consumes: `CapacitySignal`/`CapacitySignalSource` (T1), `com.recsys.health.OnlineCapacityService.Snapshot`.
- Produces: `OnlineCapacitySignalSource(Supplier<OnlineCapacityService.Snapshot>)` implementing `CapacitySignalSource`.

- [ ] **Step 1: Write the failing test**

```java
package com.recsys.application.autoscaling;

import com.recsys.health.OnlineCapacityService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OnlineCapacitySignalSourceTest {

    private static OnlineCapacityService.Snapshot snap(double qpsUtilization, boolean overloaded) {
        // Snapshot(targetDau, peakQps, peakTps, observedQps, qpsUtilization, headroomQps, overloaded, load, peakShaving)
        return new OnlineCapacityService.Snapshot(
                2_000_000L, 8_000L, 20_000L, 6_000.0, qpsUtilization, 2_000.0, overloaded, null, "x");
    }

    @Test void mapsUtilizationAndOverloaded() {
        OnlineCapacitySignalSource src = new OnlineCapacitySignalSource(() -> snap(0.75, true));
        CapacitySignal signal = src.read();
        assertThat(signal.utilization()).isEqualTo(0.75);
        assertThat(signal.surge()).isTrue();
    }

    @Test void notOverloadedIsNoSurge() {
        OnlineCapacitySignalSource src = new OnlineCapacitySignalSource(() -> snap(0.20, false));
        CapacitySignal signal = src.read();
        assertThat(signal.utilization()).isEqualTo(0.20);
        assertThat(signal.surge()).isFalse();
    }
}
```

> Confirm the `OnlineCapacityService.Snapshot` canonical constructor arg order matches the comment
> (targetDau, peakQps, peakTps, observedQps, qpsUtilization, headroomQps, overloaded, load,
> peakShaving). If it differs, fix the TEST's constructor call to match the real record.

- [ ] **Step 2: Run — expect FAIL**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=OnlineCapacitySignalSourceTest`
Expected: FAIL — `OnlineCapacitySignalSource` missing.

- [ ] **Step 3: Create the adapter**

```java
package com.recsys.application.autoscaling;

import com.recsys.health.OnlineCapacityService;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Maps the live {@link OnlineCapacityService.Snapshot} to a {@link CapacitySignal} — QPS utilization
 * as the tracked metric, {@code overloaded} (QPS saturation OR load-shedder drain) as the surge flag.
 * Demonstrates wiring the reference {@link CapacityController} to real signals; not scheduled by any
 * server.
 */
public final class OnlineCapacitySignalSource implements CapacitySignalSource {
    private final Supplier<OnlineCapacityService.Snapshot> snapshot;

    public OnlineCapacitySignalSource(Supplier<OnlineCapacityService.Snapshot> snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    @Override
    public CapacitySignal read() {
        OnlineCapacityService.Snapshot s = snapshot.get();
        return new CapacitySignal(s.qpsUtilization(), s.overloaded());
    }
}
```

- [ ] **Step 4: Run — expect PASS**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=OnlineCapacitySignalSourceTest`
Expected: PASS.

- [ ] **Step 5: Full suite + commit**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test`
Expected: BUILD SUCCESS (new package green; nothing else touched, so no regressions).

```bash
git add src/main/java/com/recsys/application/autoscaling/OnlineCapacitySignalSource.java \
        src/test/java/com/recsys/application/autoscaling/OnlineCapacitySignalSourceTest.java
git commit -m "feat: OnlineCapacitySignalSource mapping capacity snapshot to CapacitySignal"
```

---

## Self-Review Notes (author)

- **Spec coverage:** policy target-tracking+surge+edges (T1) ✓; actuator seam + ASG adapter + clamp (T2) ✓; controller tick/clamp/cooldown decisions + e2e real-ASG drive (T3) ✓; OnlineCapacitySignalSource mapping (T4) ✓; not-production-wired (no server registration anywhere; class docs state it) ✓; AutoScalingGroup/ScalingConfig/OnlineCapacityService/HPA unchanged ✓. Acceptance criteria 1–6 mapped.
- **Cooldown init** `Long.MIN_VALUE / 2` avoids the `now - Long.MIN_VALUE` overflow while guaranteeing the first tick acts, for both real and small test clocks.
- **Type consistency:** `desiredReplicas(int,double,boolean)`, `CapacitySignal(utilization,surge)`, `CapacityActuator{runningCount,minSize,maxSize,setDesiredCapacity}`, `ScalingDecision(running,desired,applied,reason)`, controller ctors — all used identically across tasks/tests.
- **Pre-write checks (flagged inline):** ASG/LaunchTemplate/NetworkConfig/Ec2Instance builder+ctor shapes (T2 note); `OnlineCapacityService.Snapshot` canonical-constructor arg order (T4 note). Fix TESTS to match real APIs if they differ; never change the untouched production classes.
