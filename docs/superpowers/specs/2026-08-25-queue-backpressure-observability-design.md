# Queue backpressure observability — design

Make the bounded queues that already shed under pressure visible *while they fill*, instead of
only after they have overflowed. Metrics and alerts only; no throttle, no control loop.

## What backpressure already exists

The premise worth correcting first: this repo already applies backpressure, in three shapes, and
has done since well before this work.

| Mechanism | Bound | Behaviour when full |
|---|---|---|
| [`AsyncEventPublisher`](../../../src/main/java/com/recsys/infrastructure/messaging/AsyncEventPublisher.java) (serving → MQ) | `ArrayBlockingQueue`, `ASYNC_EVENT_QUEUE_CAPACITY` (default 10 000) | **drops** |
| [`WorkerBulkhead`](../../../src/main/java/com/recsys/resilience/WorkerBulkhead.java) (recall, 6010 and 7010) | `ThreadPoolExecutor` + `ArrayBlockingQueue`, `RECALL_BULKHEAD_QUEUE_CAPACITY` | **rejects** (`RejectedExecutionException`) |
| `SplunkHecAppender`, `LoadShedder`, `OnlineAdmissionControl`, the rate limiters | various | shed / drop |

Dropping rather than blocking is a **deliberate decision, not an oversight**.
`AsyncEventPublisher`'s own javadoc states the reason: peak shaving exists so "the serving thread
is never stalled by MQ back-pressure." Blocking `publish()` would stall a serving thread inside a
request whose Armeria deadline is 500 ms. Any future throttle therefore belongs at admission —
the front door, rejecting fast — and not at `publish()`. This document does not change that, and
does not add a throttle at all.

## What is actually missing

**You can see that a queue already overflowed. You cannot see one filling.**

| Queue | Depth computed? | Depth in Prometheus? | Rejections in Prometheus? |
|---|---|---|---|
| `AsyncEventPublisher` | yes — `queue.size()`, in `Snapshot.queueSize` | **no — registered nowhere** | yes, `async_events_dropped_total{event_type}` |
| `WorkerBulkhead` | yes — `Snapshot.queued`, `Snapshot.active` | **no — no metrics at all** | **no** — `rejectedCount` is counted and never registered |
| `SplunkHecAppender` | yes | yes, `splunk_hec_queue_depth` | yes |
| `LoadShedder` (8080) | yes | yes, `recsys_load_shedder_in_flight_requests` / `_utilization` | yes |

The two **message** queues are exactly the two that reach nothing. This is the repo's signature
failure mode, recorded in [18_Fault_Tolerance §8.2](../../system_design/18_Fault_Tolerance.md):
instrumentation that looks present and is observable by nothing. `WorkerBulkhead`'s depth is
served as JSON by [`CatalogLoadService`](../../../src/main/java/com/recsys/api/serving/CatalogLoadService.java)
and read by no collector — the same pull-only shape `JvmMemoryMonitor` had before PR #293.

The consequence is specific to backpressure: **the signal a throttle would need is the one signal
that does not exist.** `OnlineAdmissionControl` makes shedding decisions today without reference
to recall-queue depth at all. Deciding on a control loop before this lands would mean tuning
against numbers nobody can see.

## Neither snapshot carries capacity

Utilization is the useful signal — a depth of 45 means nothing without knowing whether the bound
is 64 or 10 000 — and **neither record can express it today**:

- `WorkerBulkhead.Snapshot(String name, int active, int queued, int poolSize, long rejected)` —
  `poolSize` is the thread count, not the queue bound.
- `AsyncEventPublisher.Snapshot(int queueSize, long published, long dropped, long drained, long deliveryFailures)`.

Both gain a queue-capacity component. For `WorkerBulkhead` the value must be captured in the
constructor: `ThreadPoolExecutor` exposes `getQueue()`, and `ArrayBlockingQueue.remainingCapacity()
+ size()` is racy under concurrent access, so the configured bound is stored rather than
recomputed. `CatalogLoadService`'s JSON gains the field too, which is a small improvement to an
endpoint that already reports depth without context.

## The metrics contract

One family, tagged by queue, registered through a single registrar in `com.recsys.metrics`. This
section is the contract, not an illustration: an implementation that satisfies the prose but not
the table is wrong.

| Metric | Type | Unit | Contract |
|---|---|---|---|
| `recsys_queue_depth{queue}` | gauge | entries | Entries currently enqueued and not yet taken. Always ≥ 0 and ≤ `capacity`. Sampled at scrape time — see the limitation section. |
| `recsys_queue_capacity{queue}` | gauge | entries | The configured bound. **Strictly positive** — see below. Constant for a process's life; published so utilization is independently checkable and a capacity change is visible across a deploy. |
| `recsys_queue_utilization{queue}` | gauge | ratio 0–1 | `depth / capacity`, **defined only when `capacity > 0`**. Never negative, never > 1, never `NaN`. |
| `recsys_queue_rejected_total{queue,reason}` | counter | events | Monotonically increasing count of work refused. `reason` is `full` or `shutdown` — see below. Resets only on process restart. |

### Capacity is a positive invariant, not a runtime state

The first draft of this design said the utilization gauge should report `0` when capacity is `0`,
to dodge a `NaN`. That was wrong twice over, and the review that caught it was right: reporting
0 % utilization for a broken queue is as misleading as `NaN`, just less obviously so — one reads
as "healthy and empty", the other at least reads as "wrong".

**More importantly, the premise was false.** Both current implementations clamp their bound:
`AsyncEventPublisher` and `WorkerBulkhead` each construct
`new ArrayBlockingQueue<>(Math.max(1, queueCapacity))`. Capacity is therefore **≥ 1 by
construction** and a capacity of 0 is unreachable, whatever the operator sets
`ASYNC_EVENT_QUEUE_CAPACITY` or `RECALL_BULKHEAD_QUEUE_CAPACITY` to.

So a non-positive capacity is not a runtime condition to render — it is a **programming error in a
future `Source` implementation**. The contract follows from that:

- `Source.capacity()` **must** return a strictly positive value. This is stated on the interface.
- `QueueMetrics.register` **validates at registration time** and throws
  `IllegalArgumentException` naming the queue, rather than registering meters that would publish a
  misleading series.
- Registration happens during service startup, so a violation fails the process at boot, loudly,
  where it is attributable — not silently at 3 a.m. in a gauge nobody is looking at.

This makes the question moot by construction rather than answering it with a magic value. Neither
`0` nor `NaN` is ever published, because a queue that could produce either never gets registered.

An unbounded queue is deliberately **out of scope**: this design describes *bounded* queues, and
an unbounded one has no meaningful utilization. If one is ever instrumented, it needs its own
metric shape (depth and growth rate, no utilization), not a sentinel capacity smuggled through
this one.

### The rejection counter separates saturation from shutdown

The counter must answer "is this queue losing work because it is full?" — and as the two existing
implementations stand, a single counter **cannot**, because both conflate a second cause:

- `AsyncEventPublisher.publish` begins `if (!running) return recordRejectedEvent();`, so
  `droppedCount` advances when the publisher has been **closed**, not only when the queue is full.
- `WorkerBulkhead.submit` catches `RejectedExecutionException` and increments unconditionally.
  `ThreadPoolExecutor` throws that exception for a full queue **or a shut-down executor**.

The consequence is concrete and would have shipped: during a graceful shutdown or a rolling
deploy, late submissions on both paths increment the rejection counter, and an alert on
`increase(...) > 0` would fire on **every routine deploy**. A page that fires on normal operations
is how an alert gets muted and then stops being trusted for the case it was written for.

So the counter is tagged by reason:

- `reason="full"` — the bound was reached; work was lost to saturation. **This is the backpressure
  signal**, and the alert scopes to it.
- `reason="shutdown"` — the queue was closed and refused late work. Expected during drain; useful
  for confirming a clean shutdown, never a page.

Both classes need a small change to distinguish the branches they already take: `publish` knows
which of its two `recordRejectedEvent()` call sites it is on, and `WorkerBulkhead` can consult
`executor.isShutdown()` in its catch block. No new failure mode is introduced — the information
exists and is currently discarded.

**This also means `async_events_dropped_total` is, today, quietly inaccurate** for the same
reason: it counts shutdown refusals as drops. That counter is not changed here — it is tagged by
`event_type` and answers a different question, and renaming or re-tagging it would break anything
pointing at it — but the inaccuracy is recorded in §8.3 so nobody reads it as a pure saturation
signal.

### Overlap with `async_events_dropped_total` is accepted

`recsys_queue_rejected_total{queue="async-events", reason="full"}` overlaps the existing counter.
That is accepted rather than resolved: the existing one is tagged by `event_type` and answers
*which kind of event* was lost; the new one is tagged by queue and reason and answers *which bound
was hit and why*. Removing either loses a dimension; removing the older one breaks existing
consumers.

### Gauge lifetime and ownership

The weak-reference failure mode is not merely documented against — it is made **impossible by
construction**, which the review correctly asked for.

Micrometer's `Gauge.builder(name, state, fn)` holds `state` weakly. The failure only matters if
the state object's *only* strong reference is the registration itself. So the gauges read directly
from the live queue object — the `WorkerBulkhead` or `AsyncEventPublisher` instance — and those
objects are strongly reachable for the process's life through paths that have nothing to do with
metrics:

- A `WorkerBulkhead` is held by the Armeria `Server`'s service graph: `RecSysServer` passes it to
  `CatalogLoadService`, which is registered as a service on the built `Server`.
- An `AsyncEventPublisher` is held by **its own running drain thread**, whose `Runnable` is a
  method reference on the instance. A live thread is a GC root, so the publisher cannot be
  collected while it is draining — the same argument that makes `JvmGcMetrics` reachable through
  the JMX listener chain.

Therefore no `RETAINED`-style holder list is needed, and the plan should **not** add one.

**But this reasoning must be verified, not trusted.** An identical-shaped argument about
`JvmMetricsBinder.RETAINED` was asserted in PR #293 and later *disproved* — the javadoc claimed a
protection that did not hold, and correcting it needed its own PR. So the implementation carries a
test that forces a real GC (canary `WeakReference` confirming collection actually occurred) and
asserts every gauge still reports. If any source turns out not to be reachable as argued here,
the fix is an explicit strong reference in `QueueMetrics` — and the argument above gets corrected
rather than left standing.

`QueueMetrics.register(MeterRegistry, String queueName, QueueMetrics.Source)` where `Source` is a
three-method interface — `int depth()`, `int capacity()`, `long rejected()`. `WorkerBulkhead` and
`AsyncEventPublisher` satisfy it without either learning about Micrometer, keeping `resilience/`
and `messaging/` as free of the metrics dependency as they are now.

**One family rather than per-queue names is the point.** A single alert expression then covers
every queue, and a future bounded queue gets coverage by registering rather than by someone
remembering to write another alert. The cost is accepted deliberately: `splunk_hec_queue_depth`
and the load-shedder gauges keep their existing names, so three conventions coexist for a while.
Renaming them was considered and rejected — they shipped recently, and per §8.5 there is no
Grafana here to catch a silently-renamed series.

Registered instances: `recall-catalog` (6010), `recall-online` (7010), and the
`AsyncEventPublisher` family on 7010 and the Spring model service — including the
`KafkaAsyncEventPublisher` and `SqsAsyncEventPublisher` subclasses, which inherit the same bounded
queue and so are covered by registering the base class's source.

## Two traps this design is built against

Both have already bitten this repo, and both are resolved above by construction rather than by
convention. Restated here only as an index, because the detail belongs with the contract:

- **The NaN trap** — `NaN > 0.7` is false, so a divide-by-zero utilization would silence the alert
  in exactly the state it exists to catch, the same shape that once defeated `RedisReplicaLagHigh`
  and `GatewayRegistryStale` (§8.4). Resolved by making capacity a validated positive invariant, so
  the division cannot occur: see *Capacity is a positive invariant*.
- **The Micrometer WeakReference trap** — a collected gauge reports `NaN` while a
  `FunctionCounter` freezes at its last value and reports nothing, which is indistinguishable from
  a quiet system (§8.3). Resolved by having the gauges read from objects that are already strongly
  reachable for the process's life: see *Gauge lifetime and ownership*, including why that argument
  must be tested rather than trusted.

## The alerts, and what each one means

The two signals are **not two thresholds on the same thing**. They answer different questions and
have different severities, and the numbers below follow from that rather than the other way round.

| Signal | Question it answers | Nature | Action |
|---|---|---|---|
| `recsys_queue_utilization` | Is this queue heading for trouble? | **Early warning.** Pressure exists; nothing has been lost yet. | Investigate before it saturates. |
| `recsys_queue_rejected_total{reason="full"}` | Is this queue losing work *now*? | **Evidence of saturation.** Work has already been discarded. | Treat as active loss. |

A queue can be at 90 % utilization indefinitely and lose nothing; that is a system running hot but
correctly. A queue can also reject while its sampled utilization looks calm, if it saturates
between scrapes. Neither signal implies the other, which is why both exist.

| Alert | Expression shape | Threshold | `for:` | Severity |
|---|---|---:|---:|---|
| `RecsysQueueFillingUp` | `recsys_queue_utilization` | `> 0.7` | 10 m | warning |
| `RecsysQueueRejecting` | `increase(recsys_queue_rejected_total{reason="full"}[10m])` | `> 0` | 3 m | warning |

**`RecsysQueueFillingUp` fires on sustained pressure, not on a single scrape above the line.** The
`for: 10m` is the substance of the alert, not decoration: with a 15 s scrape interval that is
roughly 40 consecutive samples above 0.7. A queue that touches 0.8 for one scrape and drains is
working exactly as intended and must not page. What this catches is a queue that has stopped
draining as fast as it fills — the state that precedes loss, and the one that is invisible today.

**`RecsysQueueRejecting` is scoped to `reason="full"`.** Without that label matcher it would fire
on every rolling deploy, because both implementations count shutdown refusals in the same
counter (see the rejection-semantics section). This is the single most important detail in the
alert and the easiest to drop while "simplifying" the expression.

`for: 3m` sits well below the `increase()` range of 10 m, per §8.4's first trap: an isolated burst
ages out of the range and flips the expression false before a longer `for:` is satisfied, so
`for:` equal to the range can only fire on a continuously regenerating condition — which defeats
alerting on a burst, the very thing this alert is for.

No `namespace="recsys"` selector is needed here, unlike the JVM alerts added in PR #295: the
`recsys_queue_` prefix is repo-unique, so there is no foreign-workload series to match. Recorded
because the opposite decision was correct three times recently, and the difference is the metric
name's uniqueness rather than a general rule.

## The limitation, stated rather than discovered

**A gauge scraped every 15 s cannot see a queue that fills and drains between scrapes.** A
recall bulkhead with a 64-entry bound can saturate and recover inside a single scrape interval
and leave `recsys_queue_depth` looking calm throughout.

This is why both alerts ship and why neither is sufficient alone: `recsys_queue_utilization`
catches sustained pressure, `recsys_queue_rejected_total` catches the bursty case, because a
counter records an event that a sampled gauge can miss entirely. Anyone reading a flat depth graph
and concluding the queues are healthy is reading half the picture.

**There is still a gap between them, and it is worth naming.** The rejection counter only fires
once work is actually lost. A queue that repeatedly reaches 95 % and drains without ever rejecting
is invisible to *both* signals: the sampled gauge misses the peak, and nothing was refused. That
is precisely the state in which a modest traffic increase turns into loss with no prior warning.

### High-water mark — optional, deliberately not in the first change

The gap above closes with a peak-depth metric, and this repo already has the pattern for it:
`outbox_delivery_lag_seconds_max` is a Micrometer `Timer`'s decaying-window max. The equivalent
here is a `DistributionSummary` recording depth at each enqueue, yielding
`recsys_queue_depth_max{queue}` for free.

It is **explicitly optional and not a blocker**, for three reasons worth writing down rather than
rediscovering:

1. **It is a hot-path cost.** Recording a `DistributionSummary` on every enqueue means every
   published event and every submitted recall task, versus a gauge that costs nothing until
   scraped. The cost is small but it is on the request path, and it should be measured rather
   than assumed — this repo has been wrong about "small" framework costs before.
2. **A decaying max has its own sharp edge, already documented here.** §8.4 records it for
   `OutboxDeliveryLatencyHigh`: the value falls back toward 0 when the underlying activity stops
   entirely, so a queue that stops being written to reports a *falling* max that reads like
   recovery. Anyone adding this needs to know that before alerting on it.
3. **The first change is worth having without it.** Depth, capacity, utilization and reasoned
   rejections take these two queues from invisible to observable. Peak-tracking refines a picture
   that would then exist; it does not create it.

If added later it should reuse the same `Source` interface — a `peakDepth()` default method
returning "not supported" keeps existing sources valid — so the addition is a new metric, not a
reshaping of this contract.

## Testing

Unit, non-docker, and added to the `resilience` profile `<includes>` — that profile is an
allow-list and the only thing the PR gate runs, so a test outside it gates nothing.

Each item below maps to a clause of the contract above; a contract clause with no test is a gap.

- **Registration.** `QueueMetrics` registers the four expected meter names, tagged by queue, for
  two distinct queues on one registry, and the two queues' series do not collide.
- **Capacity validation.** A `Source` returning `0` or a negative capacity causes
  `register` to throw, naming the queue — and **registers no meters at all**. Assert both: a
  version that throws after registering a partial set would still publish a misleading series.
- **Utilization bounds.** `depth/capacity` for representative values; 0 when empty, 1 when full,
  never negative, never above 1, never `NaN` for any input a valid `Source` can produce.
- **Rejection reasons are distinguished.** Fill a `WorkerBulkhead` past its bound and assert
  `reason="full"` advances while `reason="shutdown"` does not; then `close()` it, submit again,
  and assert the opposite. Same for `AsyncEventPublisher` — fill the queue, then `close()` and
  publish. **This is the test that protects against paging on every rolling deploy**, and the
  behaviour it pins does not exist in either class today.
- **Gauge liveness after GC.** Meters still report after a forced collection, with a canary
  `WeakReference` confirming a real GC occurred — the proof shape from
  `SplunkHecMetricsTest#metersSurviveGarbageCollectionOfTheirBackingState`. This is what verifies
  the reachability argument in the ownership section rather than trusting it.
- **Capacity is reported.** `WorkerBulkhead` and `AsyncEventPublisher` each report the bound they
  were constructed with, including that the `Math.max(1, …)` clamp is reflected — a caller passing
  `0` gets a reported capacity of `1`, matching the queue that was actually built.

`promtool test rules` gains a fire case **and** a near-miss for each new alert. Two cases matter
more than the rest and must be written deliberately:

- **`RecsysQueueRejecting` must not fire on `reason="shutdown"` alone.** A series with only
  shutdown-reason rejections at a firing rate must assert `exp_alerts: []`. Without this the label
  matcher is untested, and dropping it while tidying the expression would reintroduce
  deploy-time paging silently.
- **`RecsysQueueFillingUp` must not fire on a single scrape above the line.** A series that
  crosses 0.7 briefly and drains must not fire; only sustained pressure should. This is what
  distinguishes an early-warning signal from noise.

**Every assertion must be shown to fail before it is trusted.** Break the implementation, run the
test, confirm it goes red on the intended assertion, restore. In the PR #293 workstream, six
prescribed tests turned out unable to fail under their own mutation — including the sentinel case
that was the single most important test in that work. A green mutation run is a finding, not a
formality.

## What this deliberately does not do

- **No throttle and no control loop.** Whether queue pressure should feed `OnlineAdmissionControl`
  is a separate decision, deliberately deferred until these numbers exist.
- **No change to drop-vs-block semantics.** `publish()` still drops immediately; `submit()` still
  rejects immediately.
- **No renaming** of `splunk_hec_queue_depth` or the load-shedder gauges.
- **No high-water-mark metric in this change** — see the optional section above.
- **No new environment variables.** The bounds are already configurable
  (`ASYNC_EVENT_QUEUE_CAPACITY`, `RECALL_BULKHEAD_QUEUE_CAPACITY`); this only reports them.

## Documentation

No new numbered document, per the repo's consolidation convention.

- **`18_Fault_Tolerance.md` §8.3** — a Queues subsection in the metric inventory, naming the
  registrar as the source of truth.
- **§8.4** — the two new alerts in the alert table, and the scrape-interval limitation stated
  where an operator would act on it.
- **`docs/runbooks/overload-protection.md`** — how to read the new metrics when diagnosing an
  overload, and the drop-vs-block reasoning, since that runbook already owns this subject.
