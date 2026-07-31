# Eventual Consistency in Recsys-Backend-Service

An investigation of how eventual consistency manifests — and is deliberately
bounded — across the system's storage, caching, sharding, streaming, and
multi-region layers.

## The big picture

This codebase treats eventual consistency as an **explicit, tunable design
decision**, not an accident. The pattern repeats everywhere: an authoritative
store (Redis primary / MySQL / Kafka) converges asynchronously, and every layer
in front of it either

- (a) tolerates bounded staleness with a documented window, or
- (b) offers a **strong-read escape hatch** to the primary when a caller cannot
  tolerate it.

Almost every "periodically-refreshed view" retains its last-good snapshot on
error — *fail-static, so the consistency machinery never breaks the request
path.*

## 1. The deliberate consistency machinery (read-your-writes)

The `application/consistency` + `application/outbox` packages implement
**opt-in, bounded read-your-writes** over eventually-consistent Redis replicas.
Gated by `ONLINE_DURABLE_EVENTS_ENABLED` (default **off**; when off, the service
uses "legacy in-memory, replica-and-cache eventual-consistency behavior" —
[DurableConsistencyConfiguration.java:8-12](../../src/main/java/com/recsys/application/outbox/DurableConsistencyConfiguration.java#L8-L12)).

**Write side (transactional outbox).** `DurableEventPublisher.publishOnline`
synchronously commits an online event to a MySQL outbox *before* acking the API
call ([DurableEventPublisher.java:25](../../src/main/java/com/recsys/application/outbox/DurableEventPublisher.java#L25)).
`OutboxRelay` then delivers to Kafka asynchronously with leases, retries, and a
delivery deadline — classic at-least-once.

**The token.** On a write, the server mints an HMAC-signed `ConsistencyToken`
(24h lifetime, subject-bound to `userId`, carrying the `eventId`) and returns it
in the `X-Consistency-Token` header
([OnlineServices.java:401](../../src/main/java/com/recsys/application/online/OnlineServices.java#L401),
[ConsistencyTokenCodec.java](../../src/main/java/com/recsys/application/consistency/ConsistencyTokenCodec.java)).

**Read side (bounded read-your-writes).** On the next read, if the client
presents its token, the server calls `ConsistencyWaiter.await(eventId, userId,
2s)` ([OnlineServices.java:147](../../src/main/java/com/recsys/application/online/OnlineServices.java#L147)),
which polls the **primary** every 50 ms for the `lineage:event:<id>` marker the
Flink sink writes when the event materializes
([RedisLineageReader.java:17-21](../../src/main/java/com/recsys/application/consistency/RedisLineageReader.java#L17-L21)).
Three outcomes:

- materialized → serve from **primary** (`recommendPrimary`), guaranteeing the
  caller sees their own write;
- not yet after 2 s → **HTTP 202 "event materialization pending"** with
  `Retry-After` ([OnlineServices.java:154-158](../../src/main/java/com/recsys/application/online/OnlineServices.java#L154-L158))
  — it surfaces the staleness rather than lying;
- primary unavailable → 503.
- **No token** → the fast, stale-tolerant path (reads may hit lagging replicas).

**Staleness is measured, not assumed.** `RedisReplicaLagProbe` writes a sequence
marker to the primary and reads it back through replica routing to continuously
sample replica lag in seconds
([RedisReplicaLagProbe.java](../../src/main/java/com/recsys/infrastructure/redis/RedisReplicaLagProbe.java)),
and `ConsistencyMetrics` records every token validation and wait outcome
(APPLIED / TIMEOUT / UNAVAILABLE).

**Convergent, not exactly-once.** The Flink Redis sink makes at-least-once
delivery *convergent* rather than exactly-once: idempotent `SET_IF_NEWER` Lua
scripts do **last-writer-by-`(eventTimeMillis, eventId)`-wins**, and top-K
updates run in one atomic multi-key script ("every related update or none").
Sagas apply the same philosophy to multi-step workflows — compensation instead
of distributed transactions.

### 1a. Durable saga coordination

[`SagaOrchestrators`](../../src/main/java/com/recsys/application/saga/SagaOrchestrators.java)
offers two durable orchestration cores. `Standard` executes steps forward and
best-effort compensates completed steps in reverse on failure; `Tcc` reserves every
step, confirms all reservations, and cancels unconfirmed reservations in reverse on
failure. Both persist each transition before publishing its event, so participants must
use `sagaId + step name` (plus the TCC phase) as their idempotency key.

The Step Functions renderers are definition generators, not a deployment mechanism:
[`AwsStepFunctionsSagaDefinition`](../../src/main/java/com/recsys/application/saga/AwsStepFunctionsSagaDefinition.java)
emits forward/compensation states, and
[`AwsTccStepFunctionsSagaDefinition`](../../src/main/java/com/recsys/application/saga/AwsTccStepFunctionsSagaDefinition.java)
emits Try/Confirm/Cancel states. Both render retry policies with exponential backoff,
`MaxDelaySeconds: 30`, and `JitterStrategy: FULL`; callers remain responsible for
deploying the generated Amazon States Language definition.

Why this machinery exists at all — which guarantee sharding takes away, and what has to
be rebuilt by hand once a transaction can no longer span the write — is
[03 §7](03_DB_Scaling_Sharding.md#7-cross-shard-atomicity--where-the-transaction-stops).
That section owns the sharding consequence; this one owns the mechanism.

## 2. Every staleness window in the system

| Layer | Window (default) | Escape hatch / notes |
|---|---|---|
| **Read-your-writes token wait** | ≤ **2 s** then 202 | primary reads, cache-bypass; off by default |
| Shard topology fleet convergence | 0–**30 s** | last-good snapshot on refresh error |
| **Reshard dual-read window** | **24 h** (= max TTL) | device reads merge prev-gen; **shard reads do NOT dual-read** (silently miss prev-gen) |
| Reshard propagation gap | up to 30 s | fleet split-brained across generations; cross-instance RYW not guaranteed |
| Service registry: backend-down detection | **~20–40 s** (30 s TTL + 10 s poll) | TTL = 3×heartbeat tolerates 2 missed beats; graceful shutdown DELs instantly |
| Registry resolution / DNS | 10 s poll / **30 s** Cloud Map DNS | fail-static to configured route address |
| OnlineFeatureStore (recent history) | 5 s fresh / **60 s** serve-stale-on-error | "5 s stale imperceptible to rec quality" |
| ShardedTopKStore (trending) | 2 s fresh / 60 s stale | `getTopKIdsPrimary` reads primary |
| LogicalExpiryEmbeddingCache (`u2vEmb`) | **30 s** soft TTL | write-through on `setEmbedding`; Flink rewrites land "within ~1 soft TTL" |
| RecommendationCache (recs / cold-start) | **300 s** / **3600 s** | keyed by variant+modelVersion → deploy sidesteps stale |
| LlmResponseCache | 300 s | justified by temperature=0 determinism |
| CDN `/api/catalog/item` | 1 h fresh / **24 h** stale-if-error | operator wildcard invalidation only |
| CDN `/api/catalog/similar` | 5 min / 1 h stale-if-error | invalidate after bulk `setembedding` |
| OnlineLearner bias | immediate local / **30 s** Redis flush | **per-JVM**, not shared; cross-pod converges via flush |
| **DR failover RPO** | Redis/MySQL ~seconds; **streaming = in-flight loss** | reads fail over in ~30 s; writes need manual promotion |

## 3. Subsystem detail

### 3a. Redis-backed sharding / shard topology

- **Strongly consistent:** the topology read-modify-write (`ShardTopologyStore`
  atomic Lua, SETNX first-writer-wins bootstrap), sequence assignment
  (`SequenceGenerator` atomic INCR), and a single record write (pipelined
  HSET+ZADD+XADD to the primary).
- **Eventually consistent:** the topology *view* across instances — each JVM
  holds a `volatile Snapshot` refreshed on a fixed 30 s delay
  (`SHARD_TOPOLOGY_REFRESH_SECONDS`, [OnlinePredictionServer.java:163](../../src/main/java/com/recsys/api/online/OnlinePredictionServer.java#L163)).
  After a reshard commit at T0, the fleet converges within ~30 s.
- **Reshard dual-read window** = `SHARDED_RECORD_MAX_TTL_SECONDS`, default
  **24 h** ([OnlinePredictionServer.java:214](../../src/main/java/com/recsys/api/online/OnlinePredictionServer.java#L214)).
  During it, `readDevice` reads current **and** `previousIfActive()` and merges
  (dedupe by `(deviceId, seqNum)`); new writes land only in the new generation.
  `readShard` is **current-generation only** and silently miss
  previous-generation records.
- **Read-your-writes** holds for same-instance/same-generation primary-only
  deployments, but **not** for AZ-aware replica reads or across the 30 s
  propagation gap.

### 3b. Service registry (`SERVICE_REGISTRY_ENABLED`, default off)

- Backend heartbeat writes `svc:registry:<name>` with a PX TTL; defaults:
  heartbeat **10 s**, TTL **30 s** (3× heartbeat → tolerates 2 missed beats),
  gateway poll **10 s**.
- **Backend-down detection ≈ 20–40 s** (remaining TTL 20–30 s + one poll ≤10 s).
  Graceful shutdown DELs the key instantly.
- Fallback: unregistered/expired service resolves to the static route address;
  Redis unavailable → keep last-good snapshot (fail-static). Flag off → no Redis
  connection at all.
- Independent of, and slower than, the Armeria upstream health-check path
  (`GATEWAY_UPSTREAM_HEALTHCHECK_INTERVAL_MS`, ~10 s).

### 3c. Caching & DNS

- **No cache invalidates on the write path** except `LogicalExpiryEmbeddingCache`
  / `OnlineFeatureStore` (write-through) and the manual CDN invalidation scripts.
  Everything else serves stale up to its TTL after any same-version data change.
- Infra serve-stale caches (`TtlSingleFlightCache`, `ShardedTopKStore`,
  `OnlineFeatureStore`) implement explicit fresh + 60 s stale-on-error windows
  with single-flight refresh.
- CDN: only `GET /api/catalog/item` (1 h / 24 h SIE) and
  `GET /api/catalog/similar` (5 min / 1 h SIE) are cached; everything else is
  `CachingDisabled` / `no-store`. Invalidation is operator-triggered, not wired
  into writes.
- Cloud Map JVM DNS cache capped at 30 s for blue/green cutover — but **only if
  `networkaddress.cache.ttl` is not already set** (else unbounded).

### 3d. Streaming feature pipeline

- Path: user event → Kafka `movie_events_v2` → Flink `OnlineFeatureStreamingJob`
  → Redis → served. **At-least-once**, made convergent by event-ID dedup +
  `(eventTimeMillis, eventId)` monotonic `SET_IF_NEWER` Redis writes.
- Watermarks: `forBoundedOutOfOrderness(5 s)` + 30 s idle timeout. Top-K uses
  10 s event-time windows + 5 s allowed lateness; movie metrics use 10 s
  processing-time windows. Checkpoint interval 10 s, RocksDB incremental,
  durable checkpoint storage enforced for production.
- Practical propagation lag: recent history ~single-digit s (5 s cache);
  trending ~15–17 s; user embedding ~30 s; movie metrics ~10 s.
- `OnlineLearner` biases are **per-JVM**, applied immediately on the processing
  pod, flushed to Redis every 30 s; cross-pod convergence and crash-recovery go
  through that flush.
- Upstream Spark job (separate repo) uses at-least-once with a **non-idempotent**
  `ZINCRBY` popularity sink — a weaker guarantee than the Flink path.

### 3e. Multi-region DR / failover

- Active-passive: primary `us-east-1`, warm standby `us-west-2` (reduced
  replicas). Route53 health-check failover, 30 s DNS TTL.
- **RTO:** reads in seconds (DNS + health check); writes in minutes (manual data
  -tier promotion). **RPO:** ~seconds for MySQL (Aurora Global) and Redis
  (ElastiCache Global Datastore async); streaming RPO = in-flight events on the
  failed region's queue (**accepted data loss**).
- Model artifacts: ECR cross-region digest replication — no loss.
- Streaming has **no cross-region broker replication**; standby Flink runs its
  own offsets, so post-failover online features are only as fresh as the
  standby's own consumer — convergence is stream-replay, not Redis replication.

## 4. Sharp edges worth flagging

Non-obvious gaps in the eventual-consistency model — candidates for a correctness
audit:

1. **Shard-level reads silently drop previous-generation data during a 24 h
   reshard window.** `GET /shards/shard` (`readShard`) does not dual-read; only
   per-device reads merge generations.
2. **`OnlineLearner` state is per-pod.** Learned biases apply immediately only on
   the pod that saw the feedback; others converge via the 30 s Redis flush, and
   up to 30 s of learning is lost on crash. Different pods can rank the same user
   differently.
3. **No cache invalidates on the write path** (except the two write-through
   caches and manual CDN scripts). Any same-version data change is served stale
   up to the TTL.
4. **DR standby trending/history isn't Redis-replicated for un-produced data.**
   The standby Flink runs a separate offset stream — after failover, online
   features are only as fresh as the standby's own consumer, with the in-flight
   window as accepted loss.
5. **Two independent down-backend detection paths** with different latencies:
   Armeria health-checks (~10 s, drops from LB) vs. registry TTL (~20–40 s). If
   the registry static fallback points at the same down host, only the
   health-check path restores availability.
6. **Cloud Map DNS staleness is unbounded if `networkaddress.cache.ttl` is
   pre-set** — the gateway caps it to 30 s only when not already configured.
7. **A single-shard record write is not atomic.** `ShardedRecordStore` pipelines its
   HSET + ZADD + XADD rather than committing them together, so a partial failure can
   leave a record with no device-index entry or an index entry with no record. Recovery
   is idempotent retry, not rollback — see
   [03 sharp edge 6](03_DB_Scaling_Sharding.md#sharp-edges--notes).
