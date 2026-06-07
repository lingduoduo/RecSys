# Findings & Decisions

## Requirements
- Optimize `OnlinePredictionServer` on port `7010`.
- Preserve real-time Redis-backed recommendation behavior.
- Improve load shedding and operations metrics.
- Deliver a plan, not implementation, in this turn.

## Research Findings
- Port 7010 is implemented by `com.recsys.streaming.OnlinePredictionServer` and exposes `/online/recommendation`, `/online/features`, `/online/ops`, `/health`, and `/shards/*`.
- Existing controls include `OnlineLoadShedder`, `RedisRateLimiter`, `OnlineServingMetricsService`, `OnlineCapacityService`, local caches, singleflight reads, sharded top-K keys, bounded async event publishing, Redis Sentinel support, an HPA, and a PDB.
- Both main online endpoints call `CompletableFuture.supplyAsync(..., ctx.blockingTaskExecutor())`; `loadShedder.tryAcquire()` is inside that task. Requests can therefore queue in the blocking executor before admission control rejects them.
- Default online concurrency is `512`, while `RedisConnectionFactory` defaults to a Redis pool of `50` connections with `blockWhenExhausted=true` and `maxWait=2s`. This creates a large queueing/timeout amplification risk during cache misses or Redis degradation.
- `OnlineFeatureStore`, `RedisTopKStore`, and `ShardedTopKStore` use 2-second waits and issue a fresh Redis request after singleflight timeout. They do not serve expired-but-known-good values when Redis fails.
- `OnlineServingMetricsService` stores one `RequestRecord` per request in a synchronized rolling deque and reports averages but no p50/p95/p99. At the stated 8k peak QPS, the 60-second window can retain roughly 480k objects and contend on a lock.
- `/online/ops` is a useful JSON snapshot, but there is no Prometheus/OpenMetrics endpoint for scraping, alerting, or custom-metric HPA.
- Kubernetes startup, readiness, and liveness probes all use `/health`. `/health` returns 503 when load shedding says drain, so intentional overload draining can trigger liveness restarts.
- Readiness does not check Redis dependency state, stale-cache age, blocking executor saturation, or async event queue pressure.
- The Redis rate limiter fails open on Redis outage. This protects availability but leaves the service with only the local concurrency gate; that mode needs explicit metrics and a configurable local emergency limit.
- The rate limiter's local fast path does not count allowed requests in Redis, so the configured cross-instance limit is approximate and can overshoot by `localPassThreshold * replicaCount`.
- The HPA scales on CPU and memory only, while the service already has more relevant overload signals: in-flight utilization, rejections, latency, and Redis pool pressure.
- There is no port-7010 load test comparable to `InferenceLoadTest`.

## Technical Decisions
| Decision | Rationale |
|----------|-----------|
| Target p95 ≤ 100 ms and p99 ≤ 250 ms under normal load; bounded 429/503 under overload | Matches the repository's stated online-serving 100 ms GC/latency profile while making overload behavior explicit. |
| Admission gate must execute before blocking-task submission | Prevents hidden executor queue growth. |
| Use route-specific budgets and rejection reasons | Recommendation, feature, ops, health, and shard traffic have different costs and priorities. |
| Serve stale online features/top-K for a bounded period on Redis errors | Slight staleness is preferable to total recommendation failure during short dependency incidents. |
| Replace per-request rolling deque with bounded histograms/counters | Avoids allocation and synchronized-lock cost at peak QPS. |
| Retain `/online/ops`; add `/metrics` and separate `/health/live`, `/health/ready` | Preserves compatibility while enabling production operations. |

## Issues Encountered
| Issue | Resolution |
|-------|------------|
| Existing system already contains many requested capabilities | Planned incremental hardening instead of recreating load shedding and metrics. |

## Resources
- `src/main/java/com/recsys/streaming/OnlinePredictionServer.java`
- `src/main/java/com/recsys/streaming/OnlinePredictionService.java`
- `src/main/java/com/recsys/streaming/OnlineFeaturesService.java`
- `src/main/java/com/recsys/streaming/OnlineLoadShedder.java`
- `src/main/java/com/recsys/streaming/OnlineServingMetricsService.java`
- `src/main/java/com/recsys/streaming/OnlineFeatureStore.java`
- `src/main/java/com/recsys/infrastructure/redis/ShardedTopKStore.java`
- `src/main/java/com/recsys/infrastructure/redis/RedisConnectionFactory.java`
- `k8s/base/online-serving.yaml`
- `k8s/base/hpa.yaml`
