# Online Prediction Server Optimization Plan

**Goal:** Make the port `7010` online prediction path maintain predictable latency during Redis degradation and traffic spikes, shed load before work queues grow, and expose metrics suitable for alerts and autoscaling.

**Scope:** `OnlinePredictionServer`, `/online/recommendation`, `/online/features`, online Redis reads, health/ops endpoints, tests, docs, and Kubernetes configuration. Existing Redis Sentinel/ElastiCache HA work remains unchanged.

**Target SLOs**

- Normal load: p95 response latency <= `100 ms`, p99 <= `250 ms`, error rate < `1%`.
- At configured capacity: no unbounded executor or Redis-pool queue growth.
- Overload: reject quickly with `429` and `Retry-After`; rejected-request p95 <= `25 ms`.
- Redis interruption: serve bounded-stale features/top-K where available; keep dependency-induced p99 <= the configured request deadline.
- Operations: expose route/outcome latency, rejection reason, Redis health/latency, cache behavior, executor pressure, and event drops.

## Current-State Risks

1. `OnlinePredictionService` and `OnlineFeaturesService` submit work to `ctx.blockingTaskExecutor()` before calling `OnlineLoadShedder.tryAcquire()`. The executor can queue requests before admission control sees them.
2. The default in-flight limit is `512`, but the Redis pool defaults to `50` connections and waits up to `2 seconds` when exhausted.
3. Redis-backed feature/top-K stores use local caches, but do not serve expired known-good values when Redis is slow or unavailable.
4. `OnlineServingMetricsService` keeps one object per request in a synchronized rolling deque and exposes averages rather than percentiles.
5. Kubernetes uses the same overload-sensitive `/health` endpoint for startup, readiness, and liveness.
6. The HPA scales only on CPU/memory, and there is no port-7010 load-test gate.

## Phase 1: Establish a Performance Baseline

**Files**

- Create `src/test/java/com/recsys/streaming/OnlinePredictionLoadTest.java`
- Create `scripts/run-online-serving-load-test.sh`
- Modify `streaming/online-serving/README.md`

**Work**

- Add an opt-in `@Tag("load")` test modeled after `InferenceLoadTest`.
- Cover warm-cache steady state, cold-cache Redis reads, mixed `/online/recommendation` and `/online/features`, burst overload, and injected Redis delay/failure.
- Report throughput, p50/p95/p99, success/429/5xx rates, max in-flight work, Redis calls, and cache hit rate.
- Record a baseline before changing defaults. Do not claim the repository's `8k QPS` target until the test environment demonstrates it.

**Acceptance**

- `mvn test -Dgroups=load -Dtest=OnlinePredictionLoadTest`
- Baseline report is reproducible and saved in CI artifacts or test output.

## Phase 2: Move Admission Control Ahead of Queues

**Files**

- Create `src/main/java/com/recsys/streaming/OnlineAdmissionControl.java`
- Create `src/main/java/com/recsys/streaming/OnlineRequestDecorator.java`
- Modify `src/main/java/com/recsys/streaming/OnlinePredictionServer.java`
- Modify `src/main/java/com/recsys/streaming/OnlinePredictionService.java`
- Modify `src/main/java/com/recsys/streaming/OnlineFeaturesService.java`
- Modify `src/main/java/com/recsys/streaming/OnlineLoadShedder.java`
- Add/update tests under `src/test/java/com/recsys/streaming/`

**Work**

- Apply admission control as an Armeria decorator on expensive routes before blocking-task submission.
- Use separate budgets for recommendation, feature, and shard routes; exempt live/ready/metrics endpoints.
- Return `429` immediately with a nonzero `Retry-After` and a machine-readable rejection reason.
- Release permits from response completion, including cancellation and timeout paths, so permits cannot leak.
- Configure bounded request deadlines with `ONLINE_REQUEST_TIMEOUT_MS`; return `503` or `504` consistently when the deadline expires.
- Bound the blocking executor or use a dedicated bounded executor for port 7010. Export active-thread and queue-depth metrics.
- Align `ONLINE_MAX_CONCURRENT_REQUESTS` with measured CPU capacity and Redis pool capacity rather than keeping `512` as an assumed safe default.

**Acceptance**

- A saturated executor does not accumulate an unbounded queue.
- Over-capacity requests are rejected before recommendation or Redis work starts.
- Permit counts return to zero after success, failure, cancellation, and timeout.
- Existing endpoint behavior remains compatible.

## Phase 3: Bound Redis Latency and Degrade Gracefully

**Files**

- Modify `src/main/java/com/recsys/infrastructure/redis/RedisConnectionFactory.java`
- Modify `src/main/java/com/recsys/streaming/OnlineFeatureStore.java`
- Modify `src/main/java/com/recsys/infrastructure/redis/ShardedTopKStore.java`
- Modify `src/main/java/com/recsys/streaming/OnlineRecommendationService.java`
- Modify `src/main/java/com/recsys/streaming/RedisRateLimiter.java`
- Add/update Redis and streaming tests

**Work**

- Make Redis pool size, max wait, connect timeout, and socket timeout configurable. Choose fail-fast defaults validated by Phase 1.
- Ensure request deadline > individual Redis timeout but remains within the p99 SLO budget.
- Add stale-if-error caches:
  - Fresh TTL controls normal reads.
  - A separate bounded stale TTL permits known-good feature/top-K values during Redis errors.
  - Never cache malformed values as valid stale data.
- On recent-history failure, degrade to trending plus model candidates; on top-K failure, degrade to recent-history plus model candidates.
- Tag responses or internal results with the degradation strategy for metrics and debugging.
- Add Redis dependency circuit state and latency metrics for feature reads, top-K reads, pool acquisition, and rate limiting.
- Make Redis rate-limiter fail-open behavior visible. Add an optional local emergency rate cap while the Redis limiter circuit is open.
- Document that the current local-pass optimization makes the distributed QPS limit approximate; either account sampled/local passes globally or rename/configure it explicitly as an approximate safety rail.

**Acceptance**

- Redis timeout/failure tests return a degraded `200` when stale or alternate sources exist.
- Redis pool exhaustion fails within the configured deadline instead of waiting two seconds.
- No request performs a second independent Redis fetch after a singleflight timeout unless its deadline budget permits it.

## Phase 4: Replace Hot-Path Metrics and Split Health Semantics

**Files**

- Modify `pom.xml`
- Replace or refactor `src/main/java/com/recsys/streaming/OnlineServingMetricsService.java`
- Create `src/main/java/com/recsys/streaming/OnlineMetricsService.java`
- Create `src/main/java/com/recsys/streaming/OnlineLiveService.java`
- Create `src/main/java/com/recsys/streaming/OnlineReadyService.java`
- Modify `src/main/java/com/recsys/streaming/OnlineOpsService.java`
- Modify `src/main/java/com/recsys/streaming/OnlinePredictionServer.java`
- Update tests

**Work**

- Use Armeria/Micrometer metrics with bounded histograms instead of a synchronized per-request rolling deque.
- Expose `/metrics` in Prometheus/OpenMetrics format.
- Keep `/online/ops` as a human-readable JSON snapshot sourced from the same metrics/state.
- Record:
  - request count/latency by low-cardinality route and outcome;
  - 429 rejection reason and request timeout count;
  - in-flight permits, executor active threads, and queue depth;
  - Redis pool active/idle/waiters, operation latency, timeout/error count, and circuit state;
  - fresh/stale/miss cache outcomes and singleflight wait/timeout;
  - recommendation strategy/degradation mode;
  - async event queue depth and dropped events.
- Add `/health/live` that only reports process/event-loop viability.
- Add `/health/ready` that drains on admission pressure, executor saturation, sustained latency/error threshold, or loss of all usable Redis/fallback paths.
- Preserve `/health` temporarily as a readiness-compatible alias and document its deprecation.

**Acceptance**

- Metrics have bounded label cardinality and include p50/p95/p99-capable histograms.
- Load shedding changes readiness but never liveness.
- Metrics collection does not allocate one retained object per request.

## Phase 5: Kubernetes, Autoscaling, and Graceful Shutdown

**Files**

- Modify `k8s/base/online-serving.yaml`
- Modify `k8s/base/configmap.yaml`
- Modify `k8s/base/hpa.yaml`
- Modify `k8s/base/network-policy.yaml` if metrics scraping requires it
- Modify `README.md` and `streaming/online-serving/README.md`

**Work**

- Point startup/readiness probes to `/health/ready` and liveness to `/health/live`.
- On shutdown, mark readiness false first, stop accepting expensive work, wait for in-flight requests up to a configured grace period, flush the event publisher, then close Redis.
- Add explicit env values for request timeout, route concurrency, Redis pool/timeouts, stale TTL, and readiness thresholds.
- Add Prometheus scrape configuration/annotations consistent with the deployment environment.
- Add autoscaling on a custom signal such as in-flight utilization or rejected-request rate when the metrics adapter is available; retain CPU as a fallback.
- Set HPA scale-up behavior to react quickly to sustained pressure and scale down conservatively.

**Acceptance**

- Overloaded pods drain without restart loops.
- Rolling deployment completes without dropping accepted requests beyond the documented shutdown deadline.
- HPA behavior is validated with the burst load scenario.

## Phase 6: Verification and Rollout

**Verification commands**

```bash
mvn test -Dtest="OnlinePredictionServerIntegrationTest,OnlineLoadShedderTest,OnlineServingMetricsServiceTest,OnlineFeatureStoreTest,OnlineRecommendationServiceTest,RedisRateLimiterTest,ShardedTopKStoreTest"
mvn test -Dgroups=load -Dtest=OnlinePredictionLoadTest
kubectl kustomize k8s/base >/dev/null
git diff --check
```

**Rollout**

1. Deploy metrics and split health endpoints without changing admission defaults.
2. Enable pre-queue admission control with conservative per-route limits.
3. Enable fail-fast Redis timeouts and stale-if-error behavior.
4. Tune concurrency, Redis pool size, stale TTL, and HPA thresholds from load-test and production canary data.
5. Remove the old `/health` alias only after gateway and probe consumers migrate.

**Exit Criteria**

- Meets the stated latency/error SLOs in the reproducible load test.
- During Redis fault injection, recommendations degrade instead of cascading into queue growth and 5xx spikes.
- During burst overload, 429s are immediate, readiness drains the pod, liveness remains healthy, and recovery is automatic.
- Operators can explain any latency or rejection spike from `/metrics` and `/online/ops`.
