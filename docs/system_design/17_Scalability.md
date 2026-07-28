# Scalability in Recsys-Backend-Service

An investigation of how the system scales: the compute tier (stateless services
behind HPA), the data tier (consistent-hash shards, read replicas, Kafka
partitions), and the overload-protection layers that keep each instance from
collapsing before autoscaling catches up.

## The big picture

Scalability here is **multi-dimensional and layered**:

- the **compute tier** scales *out* — stateless services behind Kubernetes HPA;
- the **data tier** scales *horizontally* — consistent-hash record shards,
  Redis read replicas, and Kafka partitions;
- a stack of **overload-protection layers** keeps each instance responsive while
  HPA reacts.

The recurring philosophy: keep the request path lock-free and stateless, push
state into shardable/replicable stores, and **reject fast rather than queue
unbounded** when saturated.

## 1. Compute-tier scaling — HPA is the real autoscaler

Four `autoscaling/v2` HPAs ([k8s/base/hpa.yaml](../../k8s/base/hpa.yaml)), all on
CPU/memory Resource metrics — no custom/external metrics, no KEDA, no
Prometheus-adapter.

| Service | min→max | CPU target | Mem target | Behavior |
|---|---|---|---|---|
| api-gateway | 2→6 | 70% | 80% | scaleDown stabilize 120s |
| catalog-serving | 2→8 | 70% | 80% | scaleDown stabilize 120s |
| **model-serving** | 3→6 | **60%** | 80% | scale-up 2 pods/60s (stabilize 120s); scale-in 1 pod/120s (stabilize 300s) |
| online-serving | 2→8 | 70% | 80% | scaleDown stabilize 120s |

**model-serving is the sensitive one** — heaviest pod (requests 500m/2Gi, limits
2/3Gi; longest 180s startup budget for ONNX model load), lowest CPU target, most
aggressive scale-out with deliberately conservative scale-in.

Supporting mechanisms:

- **Zonal spread** — identical `topologySpreadConstraints` on all four
  deployments (`maxSkew:1`, `topology.kubernetes.io/zone`, `DoNotSchedule`,
  `nodeTaintsPolicy: Honor`) force even AZ distribution.
- **Topology-aware routing** — `PreferClose` (`trafficDistribution`) on the three
  internal ClusterIP backends prefers same-AZ endpoints to cut inter-AZ traffic;
  the gateway is deliberately excluded (external WAF ALB edge).
- **PodDisruptionBudgets** — `minAvailable:1` for gateway/catalog/online;
  model-serving uniquely uses `maxUnavailable:1` (keeps 2 of 3 up during
  voluntary disruption).
- **DR warm standby (us-west-2)** — a strategic-merge patch drops `minReplicas`
  to ~50% of primary (gateway/catalog/online 2→1, model 3→2) while inheriting
  `maxReplicas` so HPA + cluster-autoscaler can surge to full capacity on
  failover. On cutover an operator **pre-scales** the standby back to the primary
  baseline via `scripts/dr-standby-capacity.sh promote`, which applies the
  `k8s/eks-us-west-2-active` overlay **HPA-documents-only** (`kubectl kustomize |
  filter HorizontalPodAutoscaler docs | apply -f -`, deliberately *not* `apply -k`,
  so it never rolls Deployments to the out-of-band placeholder image digest);
  `demote` restores the warm floor and `verify` is an offline drift guard against
  `k8s/base`.

**Two things labeled "autoscaling" that are NOT controllers:**

- `infrastructure/autoscaling/AutoScalingGroup` + `InstanceProvisioner` — an
  AWS-ASG-style node-fleet abstraction with **clamp-only bounds and AZ balancing,
  but no metric-driven desired-capacity computation and no cooldowns**.
  `setDesiredCapacity(int)` takes the target as a caller-supplied argument; the
  only guardrails are `ScalingConfig.clamp` (min/max) and least-loaded-AZ
  placement. Externally triggered. The missing signal→capacity "brain" now exists
  as a **tested reference** — `application/autoscaling/CapacityController` does
  target-tracking (`desired = ⌈running × util ÷ 0.7⌉`) with a surge step and
  **asymmetric cooldowns** (60s scale-out / 300s scale-in); `AsgCapacityActuator`
  drives this `AutoScalingGroup` and `OnlineCapacitySignalSource` maps an
  `OnlineCapacityService` snapshot to a `(qpsUtilization, overloaded)` signal — but
  **no server schedules it**, so HPA is still the only autoscaler in production.
- `health/OnlineCapacityService` — **observability of static sizing
  assumptions**, not a controller. It surfaces constants (`ONLINE_TARGET_DAU`
  2,000,000 / `ONLINE_PEAK_QPS` 8,000 / `ONLINE_PEAK_TPS` 20,000) plus live
  `qpsUtilization` / `headroomQps` / `overloaded` on the operator-gated
  `/online/ops` surface. Peak TPS ≫ QPS because Kafka absorbs bursty writes and
  Flink compacts them into Redis aggregates, while stateless (HPA-scalable)
  instances serve peak read QPS.

## 2. Overload protection — the layers that let it scale without collapsing

Documented in [docs/runbooks/overload-protection.md](../runbooks/overload-protection.md).
All gates are **per-instance** (aggregate cluster capacity = per-instance ×
replicas) and fail fast with `Retry-After`.

### Admission control (concurrency gate)

`loadshed/OnlineLoadShedder` — lock-free CAS loop on an `AtomicInteger` in-flight
counter. Defaults: **64 concurrent** (`ONLINE_MAX_CONCURRENT_REQUESTS`), drain at
the **0.95 application default** (`ONLINE_DRAIN_UTILIZATION`); the base
Kubernetes ConfigMap overrides online serving to **0.90**, and catalog 6010
uses **0.90** via `CATALOG_*`. `OnlineAdmissionControl` (Armeria decorator)
returns **429** +
`Retry-After` past the ceiling. `suggestedWeight = round((1−util)×100)` feeds ALB
target weights so a saturated node bleeds off traffic before it hard-rejects.
SIGTERM sets a one-way `shuttingDown` flag → `/health/ready` flips to 503 for
graceful drain (Armeria 1s quiet / 30s timeout, under k8s
`terminationGracePeriodSeconds: 60`).

### Bulkheads + bounded queues

`resilience/WorkerBulkhead` — a fixed `ThreadPoolExecutor` (`cores×2`) backed by a
**bounded `ArrayBlockingQueue`** (`RECALL_BULKHEAD_QUEUE_CAPACITY`, default
`pool×4`) with **no rejection handler**: overflow throws
`RejectedExecutionException` immediately, bounding memory and tail latency.
Per-channel `RECALL_CHANNEL_TIMEOUT_MS` (default **200ms**) via
`CompletableFuture.orTimeout` bounds each recall channel's tail contribution
independently; timed-out non-primary channels degrade to empty results. That
degradation is **observable** on catalog 6010: `GET /health/load` (`CatalogLoadService`)
reports `recall.degradedRatio` (fraction of non-primary recall *requests* that
degraded), a per-channel `recall.channelDegraded` breakdown
(`REJECTED`/`TIMEOUT`/`ERROR`), and live bulkhead `active`/`queued`; degraded
responses also carry an `X-Recall-Degraded` header on `/getrecommendation`,
`/recommendation`, and `/v2/recommend` (observe-only, request outcomes unchanged),
alongside the bounded `X-Recall-Degradation-Reason`
(`partial` / `all_channels` / `fallback`) that separates a healthy empty result
from an empty one caused by unavailable channels — see
[Fault Tolerance](18_Fault_Tolerance.md#channel-health-and-per-channel-timeouts).

### Rate limiting (`ratelimit/TokenBucket` primitive)

| Limiter | Scope | Notes |
|---|---|---|
| `GatewayRateLimiter` | per-(route, principal), per instance | bounded Caffeine cache (`GATEWAY_RL_MAX_PRINCIPALS` 100k); 429 on deny |
| `ModelRateLimiter` | per-user, per instance | access-ordered LRU capped at max-users (10k); fairness between users |
| `LlmTokenRateLimiter` | token-count-aware | consumes `max_tokens` per call so large-context requests can't drain the quota |
| `RedisRateLimiter` | **global / cluster-wide** | distributed **weighted sliding-window** (rolling rate ≈1× the limit, not the ~2× a fixed window admits across a boundary); **no local fast-path** — every request consults Redis; Redis-outage admission is bounded by a per-replica emergency token bucket only when all construction gates below are active |

Rule of thumb: **rate limiters fail open** (disabled at 0; on Redis error the online
limiter uses its per-replica emergency ceiling only when a Redis executor exists,
Redis QPS is positive, the emergency flag is enabled, and emergency rate and burst
are both positive). If the Redis limiter is active but the emergency flag, rate, or
burst disables that rollback bucket, Redis errors, malformed decisions, and circuit
rejections use **unlimited fail-open**. Without an executor or positive Redis QPS,
the Redis limiter itself is disabled and admits all requests. **Load shedders are
always on**. The online Redis QPS limit is the only *global* ceiling; everything
else is per-instance, so effective limits move with replica count.

### Circuit breakers

`resilience/CircuitBreaker` (CLOSED/OPEN/HALF_OPEN, CAS-based). `RouteCircuitBreaker`
defaults: **5 consecutive failures → open 10s → single half-open probe**; the
gateway returns **503** while open, shedding load off a failing upstream for the
cooldown. The `RedisRateLimiter` embeds its own breaker (5 failures / 30s reset)
so a Redis outage can't cascade into latency on every rate-limit check.

### Graceful degradation

The model 8080 path is uniquely graceful: on a full shedder it first tries
**degrade-to-cache** (`tryServeFromCache` → HTTP 200 + `X-Served-From:
degraded-cache`) and only throws `ServiceOverloadedException` (latency-derived
`Retry-After`) on a cache miss.

## 3. Data-tier scaling — horizontal everywhere

> Store-level companion:
> [03_DB_Scaling_Sharding §6](03_DB_Scaling_Sharding.md#6-scaling--the-levers-and-what-each-actually-buys)
> lists the levers per store, what each one actually buys, and what breaks first. Note the
> qualifier on "horizontal everywhere": the Redis shards are logical prefixes on one
> primary, so levers 1–2 there spread contention rather than adding nodes.

### Record sharding

`infrastructure/redis/sharding/ConsistentHashRing` — 150 virtual nodes per shard,
FNV1a-64 hashing. Adding a shard remaps only ~1/N of keys, and a runtime-swappable
**versioned topology** lets an operator **reshard live (e.g. 4→8)** with a bounded
dual-read window. Per-shard `INCR` counters avoid global write contention.
Resharding is operator-triggered, not automatic/elastic.

Mechanics, key schema, and the reshard procedure:
[03_DB_Scaling_Sharding §1 and §3](03_DB_Scaling_Sharding.md#1-shardedrecordstore--the-sharded-record-database).

### Hot trending keys

`infrastructure/redis/ShardedTopKStore` — the few trending windows
(`topk:last_hour`, `topk:last_day`) are read on every request across all JVMs. Each
window is a single canonical snapshot key; a short JVM cache and inline single-flight
absorb the bulk, so an instance issues at most one Redis read per window per cache TTL
regardless of request volume. (This store used to replicate each window across 4
identical shard keys; those were never written and were removed on 2026-07-28.)

Cache TTLs, the guards that stop a miss storm undoing this, and why the cache — not
the sharding — is the primary mechanism:
[03_DB_Scaling_Sharding §6](03_DB_Scaling_Sharding.md#6-scaling--the-levers-and-what-each-actually-buys).

### Kafka / Flink

`online/flink/OnlineFeatureStreamingJob` — **24 topic partitions**, 24-way
source/operator parallelism, `max-parallelism 128` (fixed key-group ceiling for
later growth via savepoint restore), targeting **50,000 events/s**. Two-stage
bucketed Top-K removes the single-task `windowAll` bottleneck (partials keyed by
movie-bucket, final merge keyed by window-end). Partition resizes create a **new
topic generation** (`movie_events_v3`) rather than an in-place change, to preserve
per-user ordering; a startup validator fails fast if the partition count drifts.

### Read scaling

`infrastructure/redis/RedisReadReplicaRouter` + `RoutingRedisExecutor` — writes to
primary, reads to the **same-AZ replica** (lowest latency, survives primary-AZ
loss), each replica backed by its own connection pool. Anti-stampede guards
(`SingleFlight`, `HotKeyDetector`, `BloomFilterGuard`) keep cache misses from
capping the gain.

Routing config, pool sizing, and guard thresholds:
[03_DB_Scaling_Sharding §6](03_DB_Scaling_Sharding.md#6-scaling--the-levers-and-what-each-actually-buys).
Consistency and lag: [04_Replication](04_Replication.md).

### Pagination at scale

`application/pagination/MillionScalePaginationSql` — keyset seek / covering-index
/ delayed-join with `FORCE INDEX` and a **1000-row page cap** avoids the OFFSET
deep-scan cliff, keeping page latency flat to 10M+ rows. `/v2/recommend` uses an
HMAC-signed, query-bound `(score, itemId)` live-keyset cursor over a recomputed
bounded ranking window. Catalog, Spring model, and online serving share
validation-before-source-work, deterministic tuple seek, and exact
`hasMore`/`nextCursor` semantics; MySQL catalog cursors remain separately
HMAC-signed and filter-bound. Current behavior, live-feed limitations, and
rotation operations are documented in
[Pagination](19_Pagination.md).

### Caching / CDN offload

Multi-tier embedding caches (`MultiLevelEmbeddingCache` L1 heap 10k → L2 Redis →
L3; `LocalEmbeddingCache` 100k; `LogicalExpiryEmbeddingCache` soft-expiry to
prevent TTL stampede) plus `RecommendationCache` (RW-lock TTL-LRU with in-flight
dedup) absorb load and offload the origin. CloudFront offloads the two shared
catalog reads at the edge (`/api/catalog/item` s-maxage 3600, `/api/catalog/similar`
s-maxage 300); personalized `/api/recommend` stays `no-store` (0% hit ratio, by
design).

## 4. How adding capacity raises throughput (summary)

| Lever | Effect |
|---|---|
| More service replicas (HPA) | Linear read/serve throughput; stateless instances, per-instance gates scale with them |
| More record shards (reshard) | Spreads keys across more **logical shards on the same primary** — not hash slots or nodes; ~1/N remap; per-shard counters avoid write contention. Raises the contention ceiling, not node capacity ([03 §6](03_DB_Scaling_Sharding.md#6-scaling--the-levers-and-what-each-actually-buys)) |
| More top-K shards | N× per-key read QPS for hot trending windows |
| More Kafka partitions + Flink parallelism | More parallel ingest up to key cardinality; 50k events/s target |
| More Redis read replicas | Spreads read load off primary, each +50 connections, cuts cross-AZ cost |
| Caching / CDN | Flattens origin load under spikes; edge-offloads shared catalog reads |
| Keyset pagination | Page latency stays flat as tables grow (no OFFSET cliff) |

## 5. Sharp edges — status after the 2026-07-20 hardening pass

The original investigation flagged five sharp edges; four have since been
instrumented or fixed (each as its own spec→plan→build cycle), leaving two
genuinely deferred assumptions. Current status:

1. **Silent recall-quality degradation now has a signal — fixed (PR #202).** On
   catalog 6010 the recall bulkhead (~`cores×10` task ceiling) can still saturate
   *before* the 64-concurrency gate rejects — on **≤6-core** boxes non-primary
   channels drop to empty results (HTTP 200, fewer candidates) before any 429. That
   ordering is now **observable** via `GET /health/load` (`recall.degradedRatio`,
   per-channel `recall.channelDegraded`, live bulkhead `active`/`queued`) plus the
   `X-Recall-Degraded` response header — watch those, not just 429 rates. The
   ordering itself is pinned by a characterization harness (edge #3 below).
2. **`AutoScalingGroup` still isn't a live autoscaler, but the loop is now closed
   by a tested reference — partially addressed.** `application/autoscaling/CapacityController`
   supplies the missing signal→capacity brain (target-tracking `⌈running × util ÷
   0.7⌉`, surge step, asymmetric 60s/300s cooldowns) over the existing ASG via
   `AsgCapacityActuator`. **It is a reference — no server schedules it**, so HPA is
   still the only production autoscaler and anything relying on the ASG to *react*
   needs an external trigger.
3. **Overload defaults are now characterized, not yet prod-tuned — mechanism
   addressed (PR #206).** Three opt-in `@Tag("load")` harnesses lock in each gate's
   invariants and give a repeatable way to find its knee (shedder never exceeds
   `max` and drains at `⌈0.95×64⌉ = 61`; bulkhead accepts exactly `pool+queue` then
   rejects immediately; the smaller of {64 gate, `cores×10` ceiling} trips first).
   See [docs/runbooks/overload-characterization.md](../runbooks/overload-characterization.md).
   They characterize the *mechanism* on the box that runs them — tuning
   64-concurrency / 0.95-drain / 200ms-timeout against real latency curves in a
   prod-like environment remains deferred.
4. **`RedisRateLimiter` no longer admits ~2× at the window boundary — fixed
   (PR #203).** It is now a weighted **sliding-window** counter (rolling rate ≈1×
   the limit) and the per-instance local fast-path was **removed** (it leaked
   `N × fraction × limit` before the global limit engaged), so every request
   consults Redis. Fail-open + embedded circuit breaker kept. Caveat: the window
   bucket is stamped from each instance's wall clock, so the ≈1× bound assumes
   NTP-synced clocks (proven by a `@Tag("docker")` boundary test with an injected
   clock). It's still the only *global* ceiling — every other limit is per-instance
   and moves with replica count. Its Redis-outage path is bounded only while the
   executor, positive-QPS, emergency-enable, positive-rate, and positive-burst gates
   all hold; disabling the emergency bucket for rollback restores unlimited
   fail-open.
5. **DR warm standby can now be pre-scaled on failover — fixed (PR #204).** The
   standby still *sits* at ~50% minReplicas to save cost, but an operator promotes
   it to the primary baseline in one step (`dr-standby-capacity.sh promote`, HPA-docs
   only). It remains a **manual** step, so expect a brief scale-out window if a
   cutover lands before promote runs.
