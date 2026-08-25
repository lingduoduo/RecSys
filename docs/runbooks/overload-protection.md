# Runbook: Overload Protection & Rate Limits

The system sheds overload in layers. This documents what each layer protects, its shape,
and how to tune it. Design: `docs/superpowers/specs/2026-07-08-overload-protection-design.md`.

> Overload protection is one facet of the system's fault tolerance — see the
> [Fault Tolerance investigation](../system_design/18_Fault_Tolerance.md) for how these gates fit
> alongside circuit breakers, graceful degradation, and multi-region survival.

## Layers

| Layer | Mechanism | Scope | Config | Response |
|---|---|---|---|---|
| Gateway 8010 | per-route × per-caller token bucket | per instance | `GATEWAY_RATE_LIMIT_RPS`=100 / `_BURST`=200 (model 50/100) | 429 + Retry-After |
| Gateway 8010 | per-route circuit breaker | per instance | `GATEWAY_CB_FAILURE_THRESHOLD`=5 / `_COOLDOWN_MS`=10000 | 503 |
| Online 7010 | concurrency admission | per instance | `ONLINE_MAX_CONCURRENT_REQUESTS`=64 / `ONLINE_DRAIN_UTILIZATION`=0.90 | 429 |
| Online 7010 | Redis sliding-window QPS | **cluster-wide (global)** | `ONLINE_REDIS_RATE_LIMIT_QPS`=200 / `_WINDOW_SECONDS`=1 | 429 |
| Model 8080 | per-user token bucket + semaphore | per instance | `recsys.model.rate-limit.*`, `RECSYS_HEALTH_MAX_CONCURRENT_REQUESTS`=64 | 429 / 503 (degrade-to-cache first) |
| RecSys 6010 | concurrency admission | per instance | `CATALOG_MAX_CONCURRENT_REQUESTS`=64 / `CATALOG_DRAIN_UTILIZATION`=0.90 | 429 |
| 6010 & 7010 | recall WorkerBulkhead (bounded queue) | per instance | `RECALL_BULKHEAD_QUEUE_CAPACITY` (default poolSize×4) | per-channel empty result |
| all serving | request/recall timeouts | per request | `ONLINE_REQUEST_TIMEOUT_MS`=500, `RECALL_CHANNEL_TIMEOUT_MS`=200 | bounded work |

> **Changed 2026-07-27 — `/v2/recommend` is now covered on both services.** That is the route
> `POST /api/recommend` reaches, and it previously bypassed every gate in the table above while
> its siblings were protected. The Model 8080 and Online 7010 rows now apply to it too.
>
> If model-serving readiness starts flapping after this change, check whether the instance was
> always overloaded and simply not measuring it: `InferenceMetricsService` previously recorded
> only `/api/v1/recommend`, so the `high failure rate` and `high inference latency` readiness
> reasons excluded all canonical traffic. Compare `/health/load` against the pre-change baseline
> before treating it as a regression.
>
> Note the asymmetry when writing alerts: 7010 sheds with `429` + `Retry-After`, 8080 with `503`.
>
> When triaging a `high failure rate` readiness reason, you can rule out client input as the
> cause: rejected requests (`400`, e.g. a malformed pagination cursor) are deliberately not
> recorded as inference failures, so the rate reflects real inference trouble only.

## Key caveats

- **Online QPS is a single GLOBAL ceiling** (bucket `rate:online:global`), not per-caller —
  200 QPS is the total across all online-serving instances. The gateway limits, by contrast,
  are per authenticated caller per route.
- **Sliding-window rate limiting:** the online Redis QPS limiter uses a weighted sliding-window
  counter that consults Redis on every request, bounding a rolling window to ~1× `limit` (no
  per-instance fast-path). Fail-open and circuit breaker behavior are unchanged. The ~1× bound assumes reasonably NTP-synchronized instance clocks, since the window bucket is derived from each instance's wall clock (`nowMs`); skew approaching a full window loosens the bound.
- **Concurrency gates are per instance** — aggregate cluster concurrency = perInstance × replicas.
- **Rate limiters fail open** (disabled at 0). On Redis error the online limiter falls back
  to a bounded per-replica emergency bucket (`ONLINE_REDIS_EMERGENCY_*`) rather than admitting
  without limit. **Load shedders are always on** and reject when the concurrency counter is full.
- **On RecSys 6010, the recall bulkhead saturates before the concurrency gate returns 429.**
  Each admitted request fans out ~6 channel tasks onto the shared `WorkerBulkhead`
  (poolSize + queue ≈ availableProcessors×2 + availableProcessors×8 ≈ availableProcessors×10
  tasks), which is a much lower ceiling than `CATALOG_MAX_CONCURRENT_REQUESTS`=64 admitted
  requests. So under load 6010 tends to degrade to partial/empty results (per-channel
  shedding, HTTP 200) well before it starts returning 429 — operators should expect silent
  quality degradation as the first symptom, not 429s.
- **Visibility:** silent recall degradation is now observable on 6010 via
  `GET /health/load` (`recall.degradedRatio`, per-channel `channelDegraded`
  counters) and the `X-Recall-Degraded` response header on `/recommendation`
  and `/v2/recommend`. `degradedRatio` climbing above ~0 under load is the
  early-warning signal that fires before any 429.

## Reading the queue metrics during an overload

`recsys_queue_depth`, `_capacity`, `_utilization` (tagged `queue`) and
`recsys_queue_rejected_total` (tagged `queue`, `reason`) — registered by
[`QueueMetrics`](../../src/main/java/com/recsys/metrics/QueueMetrics.java) — cover the
`recall-catalog` (6010), `recall-online`, and `async-events` (both 7010) queues this runbook
already discusses. See [18_Fault_Tolerance §8.3/§8.4](../system_design/18_Fault_Tolerance.md) for
the full metric contract and the two alerts (`RecsysQueueFillingUp`, `RecsysQueueRejecting`).

- **`reason="full"` is the only saturation signal — filter to it first.** `reason="shutdown"` is
  a queue refusing late work during a clean drain, expected on every rolling deploy, never
  evidence of overload. `reason="invalid_key"` (`async-events` only) is a data or configuration
  fault — a malformed event or a key extractor that doesn't match the payload shape — not a
  capacity problem, even though it lands in the same all-reasons `async_events_dropped_total`.
  Reading either of the other two as saturation sends you hunting a capacity problem that isn't
  there.
- **`depth()` — and therefore `recsys_queue_depth`/`_utilization` — is queue-only on both
  implementations, never in-flight work.** A `WorkerBulkhead` with every worker busy and an
  empty queue reports `utilization = 0`, the same as an idle one; `depth()` reads
  `executor.getQueue().size()`, not active-task count. Read `utilization` as "how much
  backlog is waiting to run," not "how loaded is this component" — a bulkhead can be fully
  busy and still show zero utilization here.
- **`recsys_queue_capacity` is the effective bound, not the configured one.** Both constructors
  clamp `Math.max(1, n)`, so `RECALL_BULKHEAD_QUEUE_CAPACITY=0` or
  `ASYNC_EVENT_QUEUE_CAPACITY=0` — plausibly meant as "no queueing" — instead yields a one-entry
  queue, and the gauge truthfully reports `1`. Both now log a WARN naming the requested and
  effective values when the clamp engages; check for it if a configured capacity and the metric
  disagree.
- **`WorkerBulkhead`'s `full`/`shutdown` split can under-report during a deploy under load.**
  `ThreadPoolExecutor` throws the same exception for a full queue and a shut-down executor, and
  `WorkerBulkhead` checks `isShutdown()` only *after* the throw — there's no race-free way to
  check atomically with it. If `close()` lands in that window, a genuine `full` rejection gets
  counted as `shutdown` instead; the error runs only that one direction, never the reverse, so it
  can suppress a page but never manufacture one. This matters specifically during a rolling
  deploy under load — the moment an operator is most likely to be looking at this reason
  breakdown — so cross-check `recsys_queue_utilization` for the same queue rather than trusting
  `reason="full"` alone in that window.
- **Check the consumer before raising a bound.** `recsys_queue_utilization` sustained above 0.7
  (`RecsysQueueFillingUp`) means the queue is filling faster than its consumer drains it — the
  recall workers for a bulkhead, the drain thread for `async-events`. Confirm the consumer has
  actually slowed before reaching for `RECALL_BULKHEAD_QUEUE_CAPACITY` or
  `ASYNC_EVENT_QUEUE_CAPACITY`: a larger queue buys latency headroom, not more throughput, and
  just delays the same rejection if the consumer is the real problem.
- **Why the queue drops instead of blocking.** `AsyncEventPublisher.publish()` never blocks the
  caller, and the recall bulkhead never blocks a caller either — both refuse immediately when
  full. A serving request runs inside a bounded deadline (500 ms for online serving), and
  stalling it on MQ or recall back-pressure would turn a queue problem into a request-latency
  one. This is why any future throttle belongs at admission — the concurrency gates and rate
  limiters in the Layers table above — and not at the queue itself; this metrics work doesn't
  change that.

## Tuning

All values above are starting points, not load-validated. To tune: run a load test to find the
knee (latency/error inflection) per service, set the concurrency gate just below it, and set the
online global QPS to the aggregate sustainable throughput. Raise `RECALL_BULKHEAD_QUEUE_CAPACITY`
if normal bursts cause premature per-channel shedding; lower it to fail faster under sustained
overload.
