# Signal-Driven Capacity Controller (Reference) Design

## Objective

`AutoScalingGroup` (in `infrastructure/autoscaling`) is a self-contained in-memory
EC2-fleet **simulation** — `setDesiredCapacity(int)` clamps to `ScalingConfig`
bounds and scale-out/in with AZ balancing, backed by an `InstanceProvisioner` SPI
(`launch`/`terminate`). It has **no caller anywhere in production** and no AWS SDK
behind it; real scaling in EKS is HPA + cluster-autoscaler. The scaling-loop is
therefore dangling — there is no controller that turns observed load into a
desired capacity.

Close that loop with a **reference** `CapacityController`: it reads the real
capacity signals the system already computes (`OnlineCapacityService` QPS
utilization / overloaded), applies a target-tracking policy with cooldowns, and
drives `AutoScalingGroup.setDesiredCapacity`. This is a well-tested reference
implementation of app-signal-driven fleet scaling over the simulation.

## Scope

In scope (new package `com.recsys.application.autoscaling`):

- `CapacityScalingPolicy` — pure target-tracking + surge policy.
- `CapacitySignal` / `CapacitySignalSource` — the utilization+surge reading.
- `CapacityActuator` + `AsgCapacityActuator` — the actuation seam over
  `AutoScalingGroup`.
- `CapacityController` — the tick loop, cooldowns, `ScalingDecision`, optional
  scheduler hook.
- `OnlineCapacitySignalSource` — an adapter mapping an `OnlineCapacityService.Snapshot`
  to a `CapacitySignal` (demonstrates real integration; not scheduled).
- Unit tests + one end-to-end adapter test over a real `AutoScalingGroup`.

Out of scope (explicit non-goals):

- **NOT wired into any live server** (`OnlinePredictionServer` or others). This is a
  reference/library; production scaling stays HPA + cluster-autoscaler. Class docs
  state this plainly.
- **No AWS SDK / real fleet.** `InstanceProvisioner` stays the in-memory SPI.
- **No change to `AutoScalingGroup`, `ScalingConfig`, or HPA manifests.** The
  controller only *decides* desired capacity; the ASG owns clamping and placement.

## Background

- `AutoScalingGroup`: `int runningCount()`, `void setDesiredCapacity(int)` (clamps
  via `scalingConfig().clamp()`), `ScalingConfig scalingConfig()`. Built via
  `AutoScalingGroup.builder(name).launchTemplate(..).networkConfig(..).scalingConfig(..).provisioner(..).targetGroup(..).build()`.
- `ScalingConfig(minSize, maxSize, desiredCapacity)` with `clamp(desired)`.
- `OnlineCapacityService.Snapshot` exposes `qpsUtilization` (observedQps/peakQps) and
  `overloaded` (QPS saturation OR load-shedder concurrency drain — it already ORs
  both, so it is a sufficient surge signal on its own).
- `AutoScalingGroupTest` construction pattern (reused for the e2e test):
  `LaunchTemplate.builder("lt")…`, `NetworkConfig.builder(vpc).availabilityZone…`,
  `ScalingConfig.of(1, 6, 2)`, a `FakeProvisioner`.

## Components

### 1. `CapacitySignal` (record) + `CapacitySignalSource` (functional)

```
public record CapacitySignal(double utilization, boolean surge) {}         // utilization >= 0
@FunctionalInterface public interface CapacitySignalSource { CapacitySignal read(); }
```

### 2. `CapacityScalingPolicy` (pure)

```
public int desiredReplicas(int currentRunning, double utilization, boolean surge)
```

- `base = max(1, currentRunning)`.
- **Target-tracking** (HPA/ASG algorithm): `raw = ceil(base * utilization / targetUtilization)`.
- **Surge:** if `surge`, `raw = max(raw, currentRunning + surgeStep)`.
- Return `max(0, raw)` — clamping to `[min,max]` is the actuator's job.
- Config (constructor): `targetUtilization` (default **0.7**, must be `> 0`),
  `surgeStep` (default **2**, `>= 1`). Non-finite / negative `utilization` is treated
  as `0.0`.
- Examples: `(5, 1.40, false) → ceil(5*1.4/0.7)=10`; `(6, 0.35, false) → ceil(6*0.5)=3`;
  `(4, 0.70, false) → 4` (steady); `(3, 0.10, true) → max(ceil(3*0.1/0.7)=1, 3+2)=5`.

### 3. `CapacityActuator` + `AsgCapacityActuator`

```
public interface CapacityActuator {
    int runningCount();
    int minSize();
    int maxSize();
    void setDesiredCapacity(int desired);
}
```

`AsgCapacityActuator` adapts an `AutoScalingGroup`: `runningCount()` → `asg.runningCount()`,
`minSize()/maxSize()` → `asg.scalingConfig().minSize()/maxSize()`, `setDesiredCapacity(n)`
→ `asg.setDesiredCapacity(n)`. Keeps `AutoScalingGroup` (infrastructure) unchanged and
lets the controller (application) depend only on the seam. Tests use a trivial fake.

### 4. `CapacityController`

Constructor deps: `CapacityActuator actuator`, `CapacitySignalSource signals`,
`CapacityScalingPolicy policy`, `long scaleOutCooldownMs`, `long scaleInCooldownMs`,
`LongSupplier clockMs`.

```
public ScalingDecision tick();
public void start(ScheduledExecutorService scheduler, java.time.Duration interval); // optional hook
public record ScalingDecision(int running, int desired, boolean applied, String reason) {}
```

`tick()`:

1. `CapacitySignal s = signals.read()`; `running = actuator.runningCount()`.
2. `desired = clamp(policy.desiredReplicas(running, s.utilization(), s.surge()),
   actuator.minSize(), actuator.maxSize())`.
3. If `desired == running` → `ScalingDecision(running, desired, false, "steady")`.
4. **Cooldown gate** (asymmetric, mirroring the model-serving HPA — fast out, slow in):
   scaling out uses `scaleOutCooldownMs`, scaling in uses `scaleInCooldownMs`. If
   `now - lastScaleAtMs(direction) < cooldown` → `ScalingDecision(running, desired,
   false, "cooldown")` (no actuation).
5. Else `actuator.setDesiredCapacity(desired)`; stamp the direction's last-scale time;
   `ScalingDecision(running, desired, true, "scaled-out"|"scaled-in")`.

Cooldowns are tracked as two `long` timestamps (`lastScaleOutMs`, `lastScaleInMs`),
initialised so the first tick can always act. `tick()` is single-threaded per
controller (a scheduled loop calls it serially); a `synchronized` tick keeps it safe
if `start()` is used.

Config defaults (mirroring model-serving HPA behavior): `scaleOutCooldownMs`
**60_000**, `scaleInCooldownMs` **300_000**.

### 5. `OnlineCapacitySignalSource` (production-style adapter, not scheduled)

```
public final class OnlineCapacitySignalSource implements CapacitySignalSource {
    // ctor: Supplier<OnlineCapacityService.Snapshot> snapshot
    public CapacitySignal read() {
        var s = snapshot.get();
        return new CapacitySignal(s.qpsUtilization(), s.overloaded());
    }
}
```

Demonstrates wiring the controller to the live capacity signal without pulling in any
request-path scheduling. Provided for completeness; **not** registered in any server.

## Testing

**`CapacityScalingPolicyTest`** (pure): target-tracking math (the four examples
above), surge override, and edges — `currentRunning == 0` (uses base 1), `utilization
== 0` (→ 0, actuator floors to min), negative/NaN utilization → treated as 0.

**`CapacityControllerTest`** (fake actuator + fake signal source + fake clock):
- computes and applies the policy's desired value when the cooldown allows;
- `desired == running` → not applied, reason "steady";
- **scale-out cooldown:** two ticks inside `scaleOutCooldownMs` → only the first scales
  out; after the cooldown elapses (advance the fake clock) → the second applies;
- **scale-in cooldown** is longer: a scale-in tick right after a scale-out is gated by
  `scaleInCooldownMs`;
- **surge** raises desired by `surgeStep` and is gated by the *out* cooldown;
- the returned `ScalingDecision` fields are correct in each case.

**`AsgCapacityActuatorTest`** (end-to-end over a real `AutoScalingGroup`, reusing the
`AutoScalingGroupTest` construction with a `FakeProvisioner`, `ScalingConfig.of(1,6,2)`):
a `CapacityController` with `AsgCapacityActuator` drives `asg.runningCount()` from 2 →
the policy's desired value on tick, and a desired above `maxSize` is **clamped by the
ASG** (e.g. surge pushing toward 20 lands at 6). Proves the loop closes over the
simulation, clamping and all.

## Acceptance Criteria

1. `CapacityScalingPolicy` implements target-tracking (`ceil(base*util/target)`) with a
   surge override, correct on the documented examples and edge inputs.
2. `CapacityController.tick()` reads the signal, computes+clamps desired, applies via the
   actuator only when it differs from running AND the direction's cooldown has elapsed,
   and returns an accurate `ScalingDecision`; scale-out and scale-in cooldowns are
   independent and asymmetric.
3. `AsgCapacityActuator` drives a real `AutoScalingGroup`'s `runningCount`, with the ASG
   clamping to `[min,max]` — proven by an end-to-end test.
4. `OnlineCapacitySignalSource` maps an `OnlineCapacityService.Snapshot` to a
   `CapacitySignal` (`qpsUtilization` → utilization, `overloaded` → surge).
5. Nothing is wired into a live server; `AutoScalingGroup`/`ScalingConfig`/HPA manifests
   are unchanged; class docs state the reference/not-production-wired scope. Full suite
   green.
6. Unit tests cover policy math+edges, controller decision+cooldown logic, and the
   end-to-end ASG drive.
