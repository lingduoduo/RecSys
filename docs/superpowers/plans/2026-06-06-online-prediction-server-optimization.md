# Online Prediction Server (Port 7010) Optimization — Planning Record

> Migrated from the Manus-style `.planning/2026-06-06-online-prediction-server-optimization/`
> (task_plan + findings + progress) into the superpowers archive. **Status: complete** — this was a
> planning/analysis turn that produced an implementation-ready optimization plan; preserved here as the
> record of that analysis.

## Goal
Make port 7010 predictable under Redis latency and traffic spikes, expose actionable operations
metrics, and validate against explicit latency and overload SLOs — without re-doing existing Redis HA.

## Target SLOs
- p95 ≤ 100 ms, p99 ≤ 250 ms under normal load.
- Bounded `429`/`503` (no unbounded queueing) under overload.

## Key decisions
| Decision | Rationale |
|----------|-----------|
| Fix admission control before deeper recommendation optimizations | The shedder ran *inside* the blocking executor, so the executor queue could grow before rejection. |
| Admission gate must execute **before** blocking-task submission | Prevents hidden executor queue growth. |
| Keep Redis HA out of scope | Sentinel/ElastiCache connection work already exists; focus on request-path resilience + observability. |
| Route-specific budgets and rejection reasons | Recommendation, feature, ops, health, and shard traffic have different costs/priorities. |
| Serve stale online features/top-K for a bounded period on Redis errors | Slight staleness beats total recommendation failure during short dependency incidents. |
| Replace per-request rolling deque with bounded histograms/counters | Avoids allocation + synchronized-lock cost at peak QPS. |
| Retain `/online/ops`; add `/metrics` and split `/health/live` vs `/health/ready` | Preserves compatibility while enabling production ops; avoids liveness restarts during intentional drain. |
| Make load-test measurements the tuning authority | Defaults (concurrency 512, Redis pool max 50) were not demonstrably aligned. |

## Research findings (state at 2026-06-06)
- Port 7010 (`OnlinePredictionServer`) exposes `/online/recommendation`, `/online/features`, `/online/ops`, `/health`, `/shards/*`.
- Existing controls: `OnlineLoadShedder`, `RedisRateLimiter`, `OnlineServingMetricsService`, `OnlineCapacityService`, local caches, singleflight reads, sharded top-K, bounded async publishing, Redis Sentinel, an HPA, and a PDB.
- Both main endpoints call `CompletableFuture.supplyAsync(..., ctx.blockingTaskExecutor())` with `loadShedder.tryAcquire()` **inside** the task → requests queue in the blocking executor before admission rejects them.
- Online concurrency default `512` vs Redis pool `50` (`blockWhenExhausted=true`, `maxWait=2s`) → large queueing/timeout amplification on cache miss / Redis degradation.
- `OnlineFeatureStore`/`RedisTopKStore`/`ShardedTopKStore` use 2 s waits and re-issue after singleflight timeout; they do **not** serve expired-but-known-good values on Redis failure.
- `OnlineServingMetricsService` keeps one `RequestRecord` per request in a synchronized rolling deque, reports averages but no p50/p95/p99 (~480k objects + lock contention at 8k QPS / 60 s window).
- No Prometheus/OpenMetrics endpoint for scraping/alerting/custom-metric HPA.
- K8s startup/readiness/liveness all use `/health`, which returns 503 on drain → intentional overload draining can trigger liveness restarts.
- Readiness doesn't check Redis state, stale-cache age, blocking-executor saturation, or async-queue pressure.
- Redis rate limiter fails open on outage (protects availability, but then only the local gate applies — needs explicit metrics + a configurable local emergency limit); its local fast path doesn't count allowed requests in Redis, so the cross-instance limit can overshoot by `localPassThreshold * replicaCount`.
- HPA scales on CPU/memory only, ignoring better overload signals (in-flight utilization, rejections, latency, Redis pool pressure).
- No port-7010 load test comparable to `InferenceLoadTest`.

## Referenced source / deployment files
`OnlinePredictionServer`, `OnlinePredictionService`, `OnlineFeaturesService`, `OnlineLoadShedder`,
`OnlineServingMetricsService`, `OnlineFeatureStore`, `infrastructure/redis/ShardedTopKStore`,
`infrastructure/redis/RedisConnectionFactory`, `k8s/base/online-serving.yaml`, `k8s/base/hpa.yaml`.
(Paths reflect the 2026-06-06 layout, before the later package reorg into `api/online`, `loadshed/`, `metrics/`, etc.)

## Outcome
- Inspected the full port-7010 path (bootstrap, HTTP services, Redis stores, shedder, limiter, metrics, capacity, async publisher, tests, K8s manifests).
- Identified: executor-before-admission queueing, Redis-pool/concurrency mismatch, missing stale-if-error, high-overhead rolling metrics, shared liveness/readiness probe, absent online-serving load gate.
- Produced an implementation-ready optimization plan; cross-checked every proposed task against existing code to avoid duplicating HA work. Verification: plan cross-check **pass**.
- Note from the original record: an initial search referenced a missing root `docker-compose.yml`; resolved by using the online-serving compose file + K8s manifests instead.
