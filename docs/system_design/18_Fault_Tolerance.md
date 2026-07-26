# Fault Tolerance in Recsys-Backend-Service

An investigation of how the system stays up when things break: how a single
request is isolated from a sick dependency, how each instance sheds load rather
than collapsing, how a degraded answer is served in place of no answer, and how
the deployment survives the loss of a zone or an entire region.

## The big picture

Fault tolerance here is **layered by failure domain** and governed by four rules:

- **Fail fast, never queue unbounded** — a request that cannot be served
  promptly is rejected (`429`/`503` with `Retry-After`) or fast-failed, never
  parked on an unbounded queue that turns a latency blip into an OOM.
- **Degrade, don't collapse** — when a recall channel, a cache, or Redis is
  unavailable, the request path falls back to a non-personalized floor (trending,
  popularity, cold-start, or a cached result) and returns `200` with a partial
  answer instead of erroring.
- **Isolate the blast radius** — circuit breakers, bulkheads, per-channel
  timeouts, and per-`(route, principal)` rate limits keep one bad upstream, one
  slow channel, or one noisy caller from taking down the whole path.
- **Survive zone and region loss** — pod anti-affinity spread, AZ-aware Redis
  reads, PodDisruptionBudgets, and a warm-standby second region keep a zonal or
  regional failure from becoming an outage.

The layers, from the request inward:

| Layer | Mechanism | Failure it absorbs |
|---|---|---|
| Edge / route | `RouteCircuitBreaker`, gateway upstream health-check fast-fail | A dead or failing backend |
| Admission | `OnlineAdmissionControl`, `LoadShedder` (semaphore), rate limiters | Overload / thundering herd |
| Fan-out | `WorkerBulkhead` + bounded queue, per-channel timeout, `ChannelHealthMonitor` | A slow/failing recall channel |
| Data | Redis replica routing + Sentinel, single-flight, bloom/hot-key, MySQL retry | A sick or lagging store |
| Fallback | Cold-start / trending / popularity floors, degrade-to-cache | Empty or unavailable candidates |
| Lifecycle | Graceful drain on SIGTERM, readiness → `503` | Rolling deploys / scale-in |
| Topology | Pod spread, PDB, AZ-aware reads, multi-region warm standby | Zone / region loss |

The recurring philosophy mirrors the [scalability investigation](17_Scalability.md):
keep the request path lock-free, push state into replicable stores, and reject
or degrade fast rather than let a single fault propagate.

## 1. Request-tier resilience — circuit breakers, bulkheads, fault injection

### Circuit breakers

A single CAS-based state machine backs every breaker in the system:
[`CircuitBreaker`](../../src/main/java/com/recsys/resilience/CircuitBreaker.java) is
lock-free with an injectable clock. It is **CLOSED** while consecutive failures
stay below the threshold; once tripped it is **OPEN** until `cooldownMs` elapses,
then **HALF_OPEN**, where `tryAcquirePermit()` admits exactly one probe. Every
admission returns a permit bound to the breaker generation and completion must
present that same permit. Opening or successfully recovering advances the
generation, so a late completion from an older request cannot mutate the
current recovery attempt. State and probe ownership move together in one
immutable CAS snapshot.

[`RouteCircuitBreaker`](../../src/main/java/com/recsys/resilience/RouteCircuitBreaker.java)
wraps one breaker per gateway route (default threshold **5**, cooldown **10 s**;
`GATEWAY_CB_FAILURE_THRESHOLD` / `GATEWAY_CB_COOLDOWN_MS`). In
[`GatewayRequestForwarder`](../../src/main/java/com/recsys/application/gateway/GatewayRequestForwarder.java)
an open circuit fast-fails with `503 "circuit open"`; a `5xx` upstream response
records a failure, anything else records success. The same primitive is embedded
in the LLM proxy and the Redis rate limiter (§4).

Circuit state is visible per route in the gateway health surface:

```bash
curl http://localhost:8010/health | jq '.services["model"].circuitState'
# "CLOSED"    ← healthy
# "OPEN"      ← tripped; fast-failing during the cooldown window
# "HALF_OPEN" ← testing recovery with a single probe
```

### Bulkheads and bounded queues

Recall fan-out is blocking I/O (Redis, DB), so it runs on an isolated pool:
[`WorkerBulkhead`](../../src/main/java/com/recsys/resilience/WorkerBulkhead.java) is a
fixed `ThreadPoolExecutor` over an `ArrayBlockingQueue` with daemon threads and
**no rejection handler** — a full queue throws `RejectedExecutionException`
rather than growing memory. Both serving paths wire one
(`recall-catalog` on 6010, `recall-online` on 7010; pool `= cores × 2`, queue
`RECALL_BULKHEAD_QUEUE_CAPACITY`, default `pool × 4`).

Rejection is a **degrade signal**, not an error: in
[`MultiChannelRecallService`](../../src/main/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallService.java)
a rejected non-primary channel returns an empty result (the merge gap-fills from
the others); only a rejected *primary* channel raises
`PrimaryRecallUnavailableException`. Because the bulkhead saturates at roughly
`cores × 10` tasks — before the 64-slot concurrency gate — 6010 tends to degrade
to partial/empty results (`200`) rather than shed with `429` under recall
pressure. See [overload-protection.md](../runbooks/overload-protection.md).

### Fault injection

[`FaultInjector`](../../src/main/java/com/recsys/resilience/FaultInjector.java) defines
named injection points that can add latency or throw. The production default is
the `NOOP` singleton (wired into `RecallConfig` at both servers), and
`MultiChannelRecallService` calls `maybeInject("channel:" + name)` before each
channel runs — so the degradation and bulkhead tests (§7) can deterministically
make a channel slow or fail without touching production behavior.

## 2. Overload protection — shed fast, never queue unbounded

Layered admission gates keep the serving paths responsive under load instead of
collapsing: a request that can't be served promptly is rejected fast
(`429`/`503` with `Retry-After`), trading a few rejected requests for bounded
latency and protected capacity for the rest.

### Admission control (concurrency gates)

[`OnlineLoadShedder`](../../src/main/java/com/recsys/loadshed/OnlineLoadShedder.java) is
a lock-free concurrency gate (CAS on an in-flight counter). `tryAcquire()`
rejects when full or shutting down; `shouldDrain()` trips at the drain
utilization or on SIGTERM; `retryAfterSeconds()` is `1` while draining and
`suggestedWeight` = `(1 − utilization) × 100` feeds the load balancer.
[`OnlineAdmissionControl`](../../src/main/java/com/recsys/loadshed/OnlineAdmissionControl.java)
is the Armeria decorator that calls the gate before entering the blocking
executor and returns `429` + `Retry-After` + `{"reason":"concurrency_limit"}`
on rejection. The catalog path (6010) reuses both; there is no separate
`CatalogAdmissionControl` class.

The model server (8080) uses a Spring
[`LoadShedder`](../../src/main/java/com/recsys/loadshed/LoadShedder.java) built on a
`Semaphore` with Micrometer counters. On rejection it first tries
`recommendationService.tryServeFromCache` (degrade-to-cache) and only then throws
`ServiceOverloadedException`, and it emits an `X-Capacity-Weight` header.

| Env var | Default | Purpose |
|---|---:|---|
| `CATALOG_MAX_CONCURRENT_REQUESTS` | `64` | In-flight cap on 6010 `/getrecommendation` before shedding |
| `CATALOG_DRAIN_UTILIZATION` | `0.90` | Utilization where 6010 reports drain to the load balancer |
| `ONLINE_MAX_CONCURRENT_REQUESTS` | `64` | In-flight cap on 7010 |
| `ONLINE_DRAIN_UTILIZATION` | `0.95` | Utilization where 7010 reports drain |
| `RECSYS_HEALTH_MAX_CONCURRENT_REQUESTS` | `64` | Model-serving (8080) semaphore cap (`recsys.health.max-concurrent-requests`) |
| `RECALL_BULKHEAD_QUEUE_CAPACITY` | `4 × recall pool` | Bounded recall queue on 6010 + 7010 before overflow is rejected |

### Gate ordering

The gates compose in a deliberate order — cheapest and most global first — so a
request is stopped as early as possible: **rate limit → admission (concurrency)
→ bulkhead**. This ordering is pinned by
[`OverloadGateOrderingCharacterizationTest`](../../src/test/java/com/recsys/loadshed/OverloadGateOrderingCharacterizationTest.java);
the layered table (gateway token bucket/CB, online admission + global Redis QPS,
model per-user + semaphore, recall bulkhead, timeouts) lives in
[overload-protection.md](../runbooks/overload-protection.md). The gate *knees*
are characterized by the `@Tag("load")` harnesses (§7); tuning them against
real latency curves in a prod-like environment remains deferred.

## 3. Graceful degradation — a degraded answer beats no answer

### Channel health and per-channel timeouts

Every recall channel runs with `RECALL_CHANNEL_TIMEOUT_MS` (default **200 ms**,
applied as `.orTimeout(...)`) and is guarded by
[`ChannelHealthMonitor`](../../src/main/java/com/recsys/application/retrieval/multichannel/ChannelHealthMonitor.java):
after **3** consecutive failures a channel enters exponential backoff
(`base 5 s × 2^(failures−3)`, capped at **60 s**) and is skipped until it
recovers, so one slow/failing channel doesn't stall every request. A timeout,
error, or bulkhead rejection returns an empty channel result — the two-phase
quota merge gap-fills the shortfall from the remaining channels.

[`RecallDegradationMetrics`](../../src/main/java/com/recsys/application/retrieval/multichannel/RecallDegradationMetrics.java)
classifies failures (REJECTED / TIMEOUT / ERROR), tracks
`degradedRatio = degradedRecalls / totalRecalls`, and records four bounded
outcomes. `GET /health/load` retains per-channel operational detail;
Prometheus exposes `recsys_recall_degradation_outcomes_total` with only the
bounded `outcome` tag:

| Outcome | `X-Recall-Degradation-Reason` | Meaning |
|---|---|---|
| healthy | absent | All attempted channels succeeded, including a naturally empty result. |
| partial | `partial` | At least one channel degraded and at least one attempted channel succeeded. |
| all channels | `all_channels` | Every attempted channel degraded; candidate count is not used to infer this. |
| fallback | `fallback` | The Spring overload path recovered the response from cache. |

For partial/all-channel outcomes, `X-Recall-Degraded` remains the sorted,
comma-separated degraded-channel list. Healthy status codes and response
bodies are unchanged; non-healthy recommendation responses retain their
successful status and add the bounded reason header.

### Non-personalized floors and degrade-to-cache

When personalization is unavailable the path still returns something useful:

- **Cold-start** —
  [`ColdStartChannel`](../../src/main/java/com/recsys/application/retrieval/coldstart/ColdStartChannel.java)
  blends trending windows (last_day 0.7, last_month 0.5) with global popularity
  (0.4) for users with no embedding.
  [`QuotaPolicy`](../../src/main/java/com/recsys/application/retrieval/coldstart/QuotaPolicy.java)
  picks a cold quota (weighted toward `cold_start` / `trending` / `popularity`)
  when the embedding is absent or the `userId` is unparseable.
- **Trending fallback** — if every channel returns empty, the trending snapshot
  is served so the response is never blank.
- **Fail-open stores** —
  [`GlobalPopularityStore`](../../src/main/java/com/recsys/infrastructure/redis/GlobalPopularityStore.java)
  catches a Redis outage and returns an empty list, letting the popularity
  channel fall back to its in-memory `DataManager`.
- **Degrade-to-cache** — the model server tries the result cache before it sheds
  (§2), so an overloaded 8080 can still answer a repeat query from cache.

## 4. Dependency resilience — surviving a sick downstream

### Gateway upstream health-check fast-fail

By default the gateway data path wraps every upstream in a health-checked
Armeria endpoint group
([`UpstreamEndpointGroups`](../../src/main/java/com/recsys/application/gateway/UpstreamEndpointGroups.java)):
each backend is probed on an interval and a down backend is dropped from
selection, so a request to a dead upstream **fast-fails with `503`** instead of
hanging until the timeout. `allowEmptyEndpoints(false)` means an all-unhealthy
group fails selection immediately (`EmptyEndpointGroupException` →
`GatewayRequestForwarder.isNoHealthyEndpoint` → `503`). A single IOException is
retried once after 50 ms (max 2 attempts, never on socket timeout). Host
resolution and the 30 s Cloud Map DNS cache are unchanged.

| Env var | Default | Purpose |
|---|---:|---|
| `GATEWAY_UPSTREAM_HEALTHCHECK_ENABLED` | `true` | Wrap each upstream in a health-checked endpoint group (set `false` for local dev without all backends) |
| `GATEWAY_UPSTREAM_HEALTHCHECK_INTERVAL_MS` | `10000` | Probe interval |

### Redis resilience

Redis is the hot dependency, so its failure paths are the most developed:

- **Replica routing + Sentinel** —
  [`RedisReadReplicaRouter`](../../src/main/java/com/recsys/infrastructure/redis/RedisReadReplicaRouter.java)
  and [`RoutingRedisExecutor`](../../src/main/java/com/recsys/infrastructure/redis/RoutingRedisExecutor.java)
  keep writes on the single primary while reads prefer the same-AZ replica
  (`AWS_AZ`), fall back to another replica, then fall back to the primary when
  none are configured — so a briefly-unreachable replica AZ degrades read
  latency, not availability. Sentinel
  ([`LettuceClientFactory`](../../src/main/java/com/recsys/infrastructure/redis/LettuceClientFactory.java))
  handles primary leader election; the router handles read fan-out. See
  [Redis Read Replicas](../../README.md#redis-read-replicas).
- **Cache-stampede protection** —
  [`SingleFlight`](../../src/main/java/com/recsys/infrastructure/resilience/SingleFlight.java)
  dedupes concurrent same-key recomputations and **fails open** (independent
  compute) on wait timeout, so a hot expired key can't stampede Redis.
- **Cache-penetration / hot-key guards** —
  [`BloomFilterGuard`](../../src/main/java/com/recsys/infrastructure/resilience/BloomFilterGuard.java)
  skips the Redis round-trip for known-absent IDs;
  [`HotKeyDetector`](../../src/main/java/com/recsys/infrastructure/resilience/HotKeyDetector.java)
  flags keys exceeding a per-second threshold for local caching.

### Rate limiters — bounded fail-open with an embedded breaker

[`RedisRateLimiter`](../../src/main/java/com/recsys/ratelimit/RedisRateLimiter.java) is
the only *global* ceiling while Redis is healthy (a weighted sliding-window
counter in Lua). It embeds a `CircuitBreaker` (threshold 5, reset 30 s). A valid
Redis decision is authoritative. On an exception, malformed Redis reply, OPEN
circuit, or lost HALF_OPEN probe race, the request consults one conservative
local emergency token bucket. An emergency allowance is flagged
`failOpen=true`; emergency exhaustion uses the existing `429` with a positive
`Retry-After`.

The emergency budget is per online-serving replica and is inactive on healthy
Redis decisions. Kubernetes defaults it to 50 requests/s with burst 50; absent
values derive one quarter of global QPS (minimum one). Invalid boolean,
negative/non-finite rate, or invalid/negative burst values fail startup.
`ONLINE_REDIS_EMERGENCY_LIMIT_ENABLED=false` or a zero rate/burst restores the
former unlimited fail-open behavior for rollback. Metrics expose only bounded
`source=redis|emergency` and `result=allowed|rejected` tags; `/online/ops`
reports circuit state, emergency configuration, and cumulative counts without
bucket/principal dimensions. The per-`(route, principal)`
[`GatewayRateLimiter`](../../src/main/java/com/recsys/ratelimit/GatewayRateLimiter.java),
per-user [`ModelRateLimiter`](../../src/main/java/com/recsys/ratelimit/ModelRateLimiter.java),
and token-budget [`LlmTokenRateLimiter`](../../src/main/java/com/recsys/ratelimit/LlmTokenRateLimiter.java)
all disable (fail open) when their limit is `0`.

### MySQL read retry and status contract

The read-only catalog pool retries narrowly:
[`MySqlClient.withReadRetry`](../../src/main/java/com/recsys/infrastructure/persistence/MySqlClient.java)
retries **only** transient connection failures
([`MySqlExceptionClassifier`](../../src/main/java/com/recsys/infrastructure/persistence/MySqlExceptionClassifier.java):
`SQLTransientConnectionException` or SQLState class `08`) up to
`MYSQL_READ_MAX_ATTEMPTS` (default **2**), sleeping `MYSQL_READ_RETRY_BACKOFF_MS`
(default **50 ms**) between attempts. Timeouts, auth, and syntax errors — and
row-mapping errors — are propagated without retry. Failures map to a fixed status
contract rather than leaking SQL internals:

| Condition | Status |
|---|---:|
| Invalid `limit`/`genre`, malformed / tampered cursor, cursor–filter mismatch | `400` |
| MySQL disabled, or HikariCP pool unavailable/exhausted (`MySqlPoolUnavailableException`) | `503` |
| Query exceeds `MYSQL_QUERY_TIMEOUT_SECONDS` (`SQLTimeoutException`) | `504` |
| Unexpected SQL or mapping failure | `500` |

### LLM proxy timeouts and retry

The LLM proxy
([`LlmProxyService`](../../src/main/java/com/recsys/application/gateway/LlmProxyService.java))
uses a longer 120 s timeout and a non-blocking retry-once on an upstream `429`
that honors `Retry-After` — but only in buffered mode; streaming errors are
surfaced immediately (the SSE path skips retry and caching).

## 5. Graceful shutdown and drain

All four services share a graceful-drain baseline so a rolling deploy or scale-in
never drops in-flight requests. On `SIGTERM`:

- The Armeria servers (6010, 7010) and the Spring model server (8080) flip
  readiness to `503` **before** draining — `markShuttingDown()` on the load
  shedder / [`GracefulShutdownSupport`](../../src/main/java/com/recsys/loadshed/GracefulShutdownSupport.java)
  (a `HIGHEST_PRECEDENCE` `ContextClosedEvent` listener) — so Kubernetes stops
  routing new work while in-flight requests finish.
- [`GracefulServers`](../../src/main/java/com/recsys/loadshed/GracefulServers.java)
  drains with a 1 s quiet period and a 30 s timeout, comfortably under the k8s
  `terminationGracePeriodSeconds: 60`; recall executors drain via
  [`GracefulExecutors`](../../src/main/java/com/recsys/loadshed/GracefulExecutors.java)
  (`RECSYS_EXECUTOR_SHUTDOWN_TIMEOUT_MS`, default 5 s).
- The catalog (6010) and gateway (8010) additionally rely on Kubernetes Endpoint
  removal to stop new routing and simply serve out the drain window.

The model-serving `/health/ready` surface makes each drain / shed reason explicit
(`"shutting down"`, `"overloaded"`, `"high failure rate"`, `"high inference
latency"`), each returning `503` — see the
[Port 8080 API reference](../../README.md#port-8080--model-serving-spring-boot).

## 6. Multi-AZ and multi-region survival

### Zonal resilience

`topologySpreadConstraints` (`maxSkew:1`, `DoNotSchedule`) force even AZ
distribution; PodDisruptionBudgets ([k8s/base/pdb.yaml](../../k8s/base/pdb.yaml)) keep
`minAvailable:1` for gateway/catalog/online and `maxUnavailable:1` for
model-serving (2 of 3 up during voluntary disruption); AZ-aware Redis reads (§4)
keep a briefly-unreachable zone from stalling the hot read path;
`trafficDistribution: PreferClose` keeps service-to-service traffic same-AZ
best-effort with a cluster-wide fallback. See
[zonal-resilience.md](../runbooks/zonal-resilience.md).

### Multi-region DR

`k8s/eks-us-west-2/` is a warm-standby overlay for a second region (reduced HPA
`minReplicas` — gateway/catalog 1, model 2, online 1 — while inheriting
`maxReplicas` so HPA + cluster-autoscaler can surge on failover). Each region
overlay composes `../base` + the `k8s/eks-shared/` component and overrides only
region-specific values (us-west-2 ECR/ElastiCache/WAF). On failover an operator
**pre-scales** the standby to the primary baseline with
`scripts/dr-standby-capacity.sh promote`, which validates every rendered
workload but applies one canonical payload containing **only the four HPAs**.
It refuses placeholder/inconsistent images, a wrong context/region/endpoint,
ambiguous HPA state, unhealthy rollout/PDB/topology/services, or missing fresh
dependency evidence. `demote` restores only the warm HPA floor; `verify` is an
offline drift guard; `cutover-check` and `failback-check` are read-only
evidence gates; `manifest-digest --target <command>` is the offline helper that
prints the canonical HPA digest those evidence files must carry, so operators
derive it instead of transcribing it. Reports are schema-v1, locked, atomic,
and never overwrite an existing path. Data roles, DNS, and traffic remain explicit operator actions.
The design is
[2026-07-08-multi-region-dr-failover-design.md](../superpowers/specs/2026-07-08-multi-region-dr-failover-design.md);
operations are in [dr-regional-failover.md](../runbooks/dr-regional-failover.md),
[dr-failback.md](../runbooks/dr-failback.md),
[dr-data-tier-promotion.md](../runbooks/dr-data-tier-promotion.md), and
[dr-game-day.md](../runbooks/dr-game-day.md).

## 7. Proving the failure paths

The failure paths are exercised, not just asserted in prose.

The pull-request gate runs:

```bash
mvn --batch-mode validate
mvn --batch-mode -Presilience test
```

Validation enforces Java 17 and dependency convergence. The deterministic
profile excludes `load,docker` and covers breakers, admission, bulkheads,
limiting, degradation, drain, outbox, Saga, and TCC behavior.

**Chaos / fault-exercising unit + integration tests** (run in the default suite):
`CircuitBreakerTest`, `RouteCircuitBreakerTest`, `FaultInjectorTest`,
`WorkerBulkheadTest`, `LoadShedderTest`, `OnlineLoadShedderTest`,
`OnlineAdmissionControlTest`, `GracefulExecutorsTest`, `GracefulServersTest`,
`MultiChannelRecallDegradationTest`, `MultiChannelRecallServiceBulkheadTest`,
`WorkerIsolationFailureTest`, `RedisRateLimiterTest` (fail-open + embedded
breaker), and `GatewayUpstreamHealthCheckIntegrationTest`.

**Scheduled/manual environmental suites** use isolated report directories/jobs
and the exact selectors:

```bash
mvn --batch-mode test -DexcludedGroups=docker -Dgroups=load \
  -Dresilience.evidence.suite=load \
  -Dresilience.evidence.output=target/resilience-measurements-load.json
mvn --batch-mode test -DexcludedGroups=load -Dgroups=docker \
  -Dresilience.evidence.suite=docker \
  -Dresilience.evidence.output=target/resilience-measurements-docker.json
```

The load sidecar proves serving admission, bulkhead rejection, bounded recall
degradation/recovery, timeout recovery, and graceful drain. The Docker sidecar
proves the real Testcontainers Redis/Lua boundary. The schema-v1 summarizer
requires matching suite applicability and fresh Surefire XML and fails on
malformed/empty evidence or false invariants. Shared-runner latency and
throughput remain report-only.

The load / characterization harnesses pin the gate knees:
`OnlineLoadShedderCharacterizationTest`,
`OverloadGateOrderingCharacterizationTest`,
`WorkerBulkheadCharacterizationTest`, plus the per-path load tests
(`EmbeddingRecallLoadTest`, `OnlinePredictionLoadTest`, `V2CrossPathLoadTest`,
`InferenceLoadTest`, `KafkaFlinkPartitionLoadTest`). How to run them is in
[overload-characterization.md](../runbooks/overload-characterization.md).

## Sharp edges — status

1. **Bulkhead saturates before the concurrency gate (by design).** On 6010 the
   recall bulkhead (~`cores × 10` tasks) fills before the 64-slot admission gate,
   so heavy recall pressure degrades to partial/empty results (`200`) rather than
   a clean `429`. Documented in
   [overload-protection.md](../runbooks/overload-protection.md); intentional,
   not a bug.
2. **Gate knees are characterized, not yet prod-tuned.** The `@Tag("load")`
   harnesses pin the mechanism (64-concurrency / 0.95-drain / 200 ms-timeout)
   against the box that runs them; tuning those constants against real prod-like
   latency curves remains deferred.
3. **DR authority remains manual.** The script can mutate only the four HPAs
   and can prove cutover/failback prerequisites. Operators own data-tier roles,
   DNS, and traffic. A checked-in placeholder image intentionally blocks real
   cluster commands until all overlays use one approved immutable digest.
4. **`RedisRateLimiter` global bound assumes NTP-synced clocks.** The weighted
   sliding window is stamped from each instance's wall clock, so the ≈1×-the-limit
   bound holds only under synced clocks (proven by the Docker Redis/Lua boundary
   probe). It is the only global ceiling while Redis is healthy; the emergency
   fail-open ceiling is per-instance and moves with replica count.
5. **`CapacityController` is a reference, not a production controller.**
   [`application/autoscaling/CapacityController`](../../src/main/java/com/recsys/application/autoscaling/CapacityController.java)
   and the `infrastructure/autoscaling` ASG model are tested simulations — real
   scaling and failover capacity come from EKS HPA + cluster-autoscaler and the
   manual DR promote above.
