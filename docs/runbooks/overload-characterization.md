# Runbook: Overload-Protection Characterization

`@Tag("load")` harnesses that characterize the in-memory overload gates and lock in
their invariants. They are **excluded from the default `mvn test`** and opt-in:

> These harnesses prove the gate knees described in the
> [Fault Tolerance investigation](../../18_Fault_Tolerance.md).


```bash
mvn test -DexcludedGroups="" -Dgroups=load -Dtest=OnlineLoadShedderCharacterizationTest,WorkerBulkheadCharacterizationTest,OverloadGateOrderingCharacterizationTest
```

> These validate the overload **mechanism** and give a repeatable way to find each knee.
> Absolute latency/throughput numbers are **hardware-dependent** — an in-repo run measures
> the dev/CI box, not production. Real tuning needs a prod-like load environment. The Redis
> rate limiter is characterized separately by the `@Tag("docker")` sliding-window test.

## What each harness measures

| Harness | Locks in (invariant) | Profile emitted |
|---|---|---|
| `OnlineLoadShedderCharacterizationTest` | in-flight never exceeds `max` (64); drain knee exactly at inFlight 61 (`⌈0.95×64⌉`); `suggestedWeight` monotonic 100→0 | offered/admitted/inflight/util/drain/weight per level |
| `WorkerBulkheadCharacterizationTest` | accepts up to `poolSize+queueCapacity`; work beyond is rejected **immediately** (bounded tail latency, no unbounded queue); recovers after drain | rejected/active/queued at the ceiling |
| `OverloadGateOrderingCharacterizationTest` | the smaller of {64 gate, `cores×10` bulkhead ceiling} trips first; the other still has room | cores / gate / bulkhead ceiling / winner |

## The gate-vs-bulkhead ordering (sharp-edge #1)

The catalog recall path has a 64-concurrency shedder AND a recall bulkhead sized
`pool=cores×2, queue=pool×4` (ceiling `cores×10`). Which trips first is **machine-dependent**:

- **≤6 cores** (`cores×10 < 64`): the bulkhead saturates first → non-primary recall channels
  drop to empty results on HTTP 200 **before** any 429 (silent recall degradation). This is
  the empirical basis for sharp-edge #1; it is now observable via `GET /health/load`
  (`recall.degradedRatio`) from PR #202.
- **≥7 cores** (`cores×10 > 64`): the concurrency gate trips first → overload surfaces as 429.

Observed on the machine that last ran this harness:

```
[ordering] cores=8 gate=64 bulkheadCeiling=80 => gate trips first
[ordering] confirmed: gate trips first on this host
```

## Observed profiles (last local run)

`OnlineLoadShedderCharacterizationTest`:

```
[shedder] max=64 drain=0.95
offered  admitted  inflight  util     drain   weight
16       16        16        0.250    false   75
32       32        32        0.500    false   50
48       48        48        0.750    false   25
61       61        61        0.953    true    5
64       64        64        1.000    true    0
80       64        64        1.000    true    0
128      64        64        1.000    true    0
```

`WorkerBulkheadCharacterizationTest`:

```
[bulkhead] pool=4 queue=8 ceiling=12 submitted=17
rejected=5 active=4 queued=8 snap.rejected=5
```
