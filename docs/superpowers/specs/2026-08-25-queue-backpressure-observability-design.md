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

## The metrics

One family, tagged by queue, registered through a single registrar in `com.recsys.metrics`:

| Metric | Type | Notes |
|---|---|---|
| `recsys_queue_depth{queue}` | gauge | current entries |
| `recsys_queue_capacity{queue}` | gauge | configured bound; constant, but present so utilization is checkable and a capacity change is visible |
| `recsys_queue_utilization{queue}` | gauge | `depth / capacity`, 0–1 |
| `recsys_queue_rejected_total{queue}` | counter | work refused because the bound was hit |

**One counter deliberately spans two mechanisms.** The async publisher *drops* an event and the
bulkhead *rejects* a task with an exception — different consequences for the caller, but the same
fact from a capacity standpoint: the bound was reached and work was refused. Keeping them one
metric is what lets a single alert cover every queue. Where the distinction matters, the queue tag
already carries it, and the differing consequence belongs in the runbook rather than in a second
metric name.

**`async_events_dropped_total` stays.** `recsys_queue_rejected_total{queue="async-events"}` will
overlap it, and that is accepted rather than resolved: the existing counter is tagged by
`event_type` and answers a different question (*which kind* of event was lost), while the new one
is tagged by queue and answers *which bound* was hit. Removing either would lose a dimension, and
removing the older one would break anything already pointing at it.

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

Both have already bitten this repo, and both are recorded in §8.3 and §8.4.

**The NaN trap.** `utilization = depth / capacity` divides by zero if capacity is ever 0, and
`NaN > 0.7` is **false** — the alert would go silent in precisely the broken state it exists to
catch. This is the same shape that once defeated `RedisReplicaLagHigh` and `GatewayRegistryStale`.
The gauge reports `0` when capacity is not positive, and a promtool case must prove the guard is
load-bearing by going red when it is removed.

**The Micrometer WeakReference trap.** `Gauge.builder`/`FunctionCounter.builder` hold their state
object weakly by design. A collected gauge reports `NaN` — visibly wrong — while a
`FunctionCounter` **freezes at its last value and reports no error**, which is indistinguishable
from a quiet system. `QueueMetrics` retains every registered `Source` for the JVM's life, the same
deliberate retention as `SplunkHecMetrics.RETAINED`, proven by a test that forces a real GC
verified through a canary `WeakReference`.

## The alerts

Two rules, covering every registered queue through the shared tag.

| Alert | Expression shape | Threshold | `for:` |
|---|---|---:|---:|
| `RecsysQueueFillingUp` | `recsys_queue_utilization` | `> 0.7` | 10 m |
| `RecsysQueueRejecting` | `increase(recsys_queue_rejected_total[10m])` | `> 0` | 3 m |

`RecsysQueueFillingUp` is the signal that does not exist today: sustained pressure *before*
anything is discarded. `RecsysQueueRejecting` catches the case the depth gauge structurally
cannot — see the limitation below.

`for:` sits well below the `increase()` range on `RecsysQueueRejecting`, per §8.4's first trap: an
isolated burst ages out of the range and flips the expression false before a longer `for:` is
satisfied, so `for:` equal to the range can only fire on a continuously regenerating condition.

No `namespace="recsys"` selector is needed here, unlike the JVM alerts added in PR #295: the
`recsys_queue_` prefix is repo-unique, so there is no foreign-workload series to match. Recorded
because the opposite decision was correct three times recently and the difference is the metric
name's uniqueness, not a general rule.

## The limitation, stated rather than discovered

**A gauge scraped every 15 s cannot see a queue that fills and drains between scrapes.** A
recall bulkhead with a 64-entry bound can saturate and recover inside a single scrape interval
and leave `recsys_queue_depth` looking calm throughout.

This is why both alerts ship and why neither is sufficient alone: `recsys_queue_utilization`
catches sustained pressure, `recsys_queue_rejected_total` catches the bursty case, because a
counter records an event that a sampled gauge can miss entirely. Anyone reading a flat depth graph
and concluding the queues are healthy is reading half the picture.

## Testing

Unit, non-docker, and added to the `resilience` profile `<includes>` — that profile is an
allow-list and the only thing the PR gate runs, so a test outside it gates nothing.

- `QueueMetrics` registers the four expected meter names, tagged by queue, for two distinct queues
  on one registry.
- Utilization math, including the capacity-0 guard reporting `0` and never `NaN`.
- Meters still report after a forced GC — the same proof shape as
  `SplunkHecMetricsTest#metersSurviveGarbageCollectionOfTheirBackingState`, with a canary
  `WeakReference` confirming a real collection occurred.
- `WorkerBulkhead` and `AsyncEventPublisher` report their configured capacity, and their rejection
  counters advance when the bound is exceeded.

`promtool test rules` gains a fire case **and** a near-miss for each new alert, plus the
capacity-0 case for `RecsysQueueFillingUp`.

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
