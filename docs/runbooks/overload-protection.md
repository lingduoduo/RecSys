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

## Tuning

All values above are starting points, not load-validated. To tune: run a load test to find the
knee (latency/error inflection) per service, set the concurrency gate just below it, and set the
online global QPS to the aggregate sustainable throughput. Raise `RECALL_BULKHEAD_QUEUE_CAPACITY`
if normal bursts cause premature per-channel shedding; lower it to fail faster under sustained
overload.
