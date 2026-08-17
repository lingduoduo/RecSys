# Online Serving and Latency in Recsys-Backend-Service

An investigation of the online serving path as a **latency budget**: what each layer is
allowed to spend, whether the layers agree with each other, and what happens to the work
already in flight when one of them gives up. Seven mechanisms are in scope — async APIs,
batching, caching, connection pools, timeouts, retries, and graceful fallbacks.

The individual mechanisms are already documented where they live:
[02_Caching](02_Caching.md) covers the cache tiers,
[17_Scalability](17_Scalability.md) the bulkheads and admission gates,
[18_Fault_Tolerance](18_Fault_Tolerance.md) the breakers and degradation contracts. What
none of them cover is the **composition** — a per-channel budget of 200 ms sitting on a
per-command budget of 2000 ms is a fact about neither the channel nor the command, and it
is invisible from inside either document.

Values below are read from code, from `k8s/base` and the two EKS overlays as rendered, and
from the Armeria 1.28.4 dependency source (`DefaultFlagsProvider`) where a default is
implicit. Where a claim was established by running something, §1 says so and gives the
measurement. Where it was established by reading, it says that instead. Nothing here was
measured against a live cluster or under production load, so no number below is a latency
SLO — this is a budget audit, not a performance benchmark.

## The big picture

```
client → CloudFront → ALB → gateway 8010 ──→ online 7010   (POST /api/recommend)
                                         ├─→ catalog 6010
                                         └─→ model 8080

per request on 7010:  admission gate → [ recent history (Redis) ]
                                     → [ cold-start probe (Redis) ]
                                     → [ 6-channel fan-out ]  ← the only parallelism
                                     → [ trending snapshot (Redis) ]
                                     → rerank → render
```

Three of those four data stages are **serial round trips on the request thread**, and only
the fan-out is parallel. Each stage is individually cheap because a JVM-heap cache absorbs
it; the budget question is what the stages cost when the caches miss at the same moment,
which is exactly the condition a cold pod or a Redis blip produces.

## 1. The budget chain, as deployed

| Layer | catalog 6010 | online 7010 | model 8080 | gateway 8010 |
|---|---|---|---|---|
| Armeria server request timeout | **10 s** (unset → Armeria default) | **500 ms** (`ONLINE_REQUEST_TIMEOUT_MS`) | — (Tomcat) | **10 s** (unset → Armeria default) |
| Upstream client timeout | — | — | — | **3 s** (`GATEWAY_TIMEOUT_MS`) + 1 retry ≈ **6.05 s** |
| Recall channel timeout | **200 ms** | **200 ms** | **200 ms** | — |
| Redis command timeout | **2000 ms** (unset → code default) | **200 ms** (`k8s/base` env) | **150 ms** (code constant) | — |
| Redis pool max-wait | 250 ms (default) | 100 ms (`k8s/base`) | 250 ms | — |
| Admission limit | 64 concurrent | 64 concurrent | semaphore | rate limiter |
| Recall bulkhead | `cores×2` threads, `pool×4` queue | same | same | — |
| Container CPU limit | `1` | `1` | `2` | `750m` |

`RECALL_CHANNEL_TIMEOUT_MS` is set in no manifest, so all three recall paths run at the
200 ms default. `REDIS_TIMEOUT_MS` is set in exactly one manifest,
[`k8s/base/online-serving.yaml`](../../k8s/base/online-serving.yaml). 7010 is the only
Armeria service that sets `requestTimeoutMillis` at all; 6010 and 8010 inherit Armeria's
10 s default (`DefaultFlagsProvider.DEFAULT_REQUEST_TIMEOUT_MILLIS = 10 * 1000`).

**The chain is not monotonically decreasing.** Reading it from the outside in, the gateway
will wait up to ~6 s for a backend that stops itself at 500 ms, and inside 6010 a 200 ms
channel budget sits above a 2000 ms command budget with no server deadline between them.
A budget chain is only meaningful if each layer's allowance is smaller than its caller's;
where it inverts, the inner timeout is decorative for the *client* and load-bearing only
for *thread occupancy* — which §2 is about.

## 2. Async APIs — where the asynchrony actually stops

All three Armeria services are **async at the edge and blocking underneath**. Every
handler follows one shape
([`OnlineServices.java:115`](../../src/main/java/com/recsys/application/online/OnlineServices.java)):

```java
return HttpResponse.of(CompletableFuture.supplyAsync(() -> {
    ...                                   // the entire request, blocking
}, ctx.blockingTaskExecutor()));
```

The event loop never blocks, which is what matters for connection handling. But the request
body holds one of Armeria's 200 common blocking-task threads
(`NUM_COMMON_BLOCKING_TASK_THREADS = 200`, "from Tomcat default maxThreads") for its whole
life, and every Redis call inside it is a **synchronous** Lettuce call —
[`LettuceRedisExecutor.execute`](../../src/main/java/com/recsys/infrastructure/redis/LettuceRedisExecutor.java)
resolves to `shared().sync()`. Lettuce's async API appears only in pipelined *writes*
(`conn.async()` + `flushCommands`). The one genuine intra-request parallelism is the recall
fan-out, and it is collected with a blocking
`CompletableFuture.allOf(...).join()`.

### `orTimeout` bounds the caller's wait, not the work — measured

Each channel is dispatched as
`CompletableFuture.supplyAsync(channel, recallBulkhead).orTimeout(200ms)`
([`MultiChannelRecallService.java:162-170`](../../src/main/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallService.java)).
`orTimeout` completes the *dependent* future exceptionally; it does not cancel or interrupt
the task already running on the bulkhead thread, and a task blocked in a socket read would
not observe an interrupt anyway.

Running that exact shape — a 1-thread pool, a task that blocks for 2000 ms, `.orTimeout(200ms)`:

```
caller saw 'degraded:TimeoutException' at 220ms
pool active=1 queued=0 at 238ms
  [task] completed normally at 2014ms
next task waited 1790ms for a worker (submitted at 2035ms)
```

The caller degraded on schedule at 220 ms. The worker stayed occupied, and **the next
request's channel task waited 1790 ms for a thread**. So:

> The recall bulkhead's drain rate under a stalled backing store is set by the **Redis
> command timeout**, not by `RECALL_CHANNEL_TIMEOUT_MS`.

This is why the inversion in §1 matters, and why it is a capacity property rather than a
latency one. The 200 ms channel timeout protects the *response*; only the command timeout
protects the *thread*. Two of the three recall paths acknowledge this and cap the command
timeout — differently:

| Service | How the command timeout is bounded | Effective cap |
|---|---|---|
| model 8080 | `ModelRuntimeProvider.RECALL_REDIS_TIMEOUT_MS` code constant, passed to `LettuceClientFactory.routingFromEnv(int)` | 150 ms |
| online 7010 | `REDIS_TIMEOUT_MS: "200"` in `k8s/base/online-serving.yaml` | 200 ms |
| catalog 6010 | nothing | 2000 ms |

The same hazard was mitigated twice, in two different layers, and missed on the third. The
mechanism 6010 would need already exists — it calls the *uncapped*
`LettuceClientFactory.routingFromEnv()` overload
([`RecSysServer.java:79`](../../src/main/java/com/recsys/api/serving/RecSysServer.java))
where 8080 calls the capped one. Setting `REDIS_TIMEOUT_MS` for catalog-serving in
`k8s/base` closes it the same way 7010's is closed, and is the one change this
investigation made; see §8.

### Bulkhead width versus admitted concurrency

The recall bulkhead is sized `Runtime.getRuntime().availableProcessors() * 2` with a queue
of `pool × 4`. Under `limits.cpu: "1"` the JVM's container support reports 1 processor, so
a pod gets **2 threads and 8 queue slots** — 10 task slots. Admission control allows **64**
concurrent requests, each fanning out to **6** channels: up to 384 channel tasks against
10 slots. The two gates are dimensioned in different units (requests vs. tasks) from
different inputs (an env default vs. the CPU limit), and nothing ties them together.

That is not a bug — the bounded queue exists precisely so overflow throws
`RejectedExecutionException` and degrades to an empty channel result rather than growing.
But it means **channel rejection is the normal steady state above a few concurrent
requests**, not an overload signal, and `recall.degradedRatio` should be read with that in
mind. What the gap changes is *which* mechanism sheds first: the bulkhead, long before the
admission gate it is nominally behind.

## 3. Batching — present where it counts, absent between stages

Batching is genuinely implemented at every layer where a batch is available:

| Site | Mechanism |
|---|---|
| [`RedisEmbeddingStore.getEmbeddings`](../../src/main/java/com/recsys/infrastructure/redis/RedisEmbeddingStore.java) | MGET in `REDIS_EMBEDDING_MGET_BATCH_SIZE` (500) chunks |
| `RedisEmbeddingStore.setEmbeddings` | one pipelined batch, `flushCommands` + `LettuceFutures.awaitAll(5 s)` |
| `RedisEmbeddingStore.loadAll` | SCAN page → MGET that page immediately (bounded heap) |
| [`OnlineFeatureStore`](../../src/main/java/com/recsys/infrastructure/store/OnlineFeatureStore.java) | MGET in `ONLINE_FEATURE_REDIS_MGET_BATCH_SIZE` (500) chunks |
| [`LogicalExpiryEmbeddingCache.getEmbeddings`](../../src/main/java/com/recsys/infrastructure/cache/LogicalExpiryEmbeddingCache.java) | collapses all cold misses into **one** backing-store call |
| [`UserTowerInferenceService.scoreCandidates`](../../src/main/java/com/recsys/application/retrieval/UserTowerInferenceService.java) | one batched `session.run` for every candidate; single-item `score()` is a 1-element batch |

The gap is not within a stage but **between** stages. The per-request online path issues
its independent reads serially rather than as one pipeline
([`OnlineRecommendationService.recommend`](../../src/main/java/com/recsys/application/online/OnlineRecommendationService.java)):

1. `requireUser` — in-memory `DataManager`.
2. `recentHistoryStore.getRecentMovieIds` — Redis GET.
3. the cold-start probe `userEmbeddingStore.getEmbedding` — Redis GET, issued *inside*
   `MultiChannelRecallService.recall` before any channel is dispatched, on the calling
   thread, and therefore outside both the channel timeout and the fan-out.
4. the 6-channel fan-out — parallel.
5. `topkStore.getTopKIds` — Redis EVAL for the response snapshot.

Steps 2, 3 and 5 are mutually independent and could share one round trip, or overlap with
step 4. They don't. Two of them are also **read twice per request**: recent history by both
`OnlineRecommendationService` and the `OnlineRecentHistory` channel, trending by both the
`Trending` channel and the response snapshot. On a warm pod the duplicates are absorbed by
the 5 s and 2 s JVM caches, costing a map lookup; on a cold one they are genuine extra
round trips, and the cold case is the one the budget has to survive.

There is no **cross-request** batching anywhere: no micro-batching window that would let
concurrent requests share one ONNX `session.run` or one MGET. For a request-per-user
workload with per-user features that is the right call; it is worth stating explicitly
because "batching" in a serving system usually means the cross-request kind, and this
system does not do it.

## 4. Caching — the wait budgets are larger than the request

The tiers themselves are covered in [02_Caching](02_Caching.md). What is relevant here is
that every cache's *wait* budget was chosen independently of the request deadline:

| Budget | Value | vs. 7010's 500 ms request timeout |
|---|---|---|
| `LogicalExpiryEmbeddingCache` cold-miss `SingleFlight` | 2000 ms | 4× |
| `ShardedTopKStore.FETCH_WAIT_TIMEOUT_MS` | 2000 ms | 4× |
| `OnlineFeatureStore.REDIS_FETCH_TIMEOUT_MS` | 2000 ms | 4× |
| `recsys.recommendation-cache.compute-wait-timeout-millis` | 2000 ms | (8080, no equivalent deadline) |

None of these can ever be *reached* as client-visible latency on 7010 — Armeria has already
terminated the response at 500 ms. They remain fully load-bearing as thread occupancy, per
§2: a follower that waits 2000 ms on a single-flight leader holds its blocking-task thread
for 2000 ms, and `ShardedTopKStore` then **fails open by fetching independently**, adding a
round trip after the wait. The serve-stale design means these paths are rare; the point is
that when they are taken, the deadline that bounds them is a thread-pool property rather
than the request's.

One inconsistency in where blocking I/O is allowed to run.
`MultiChannelRecallService`'s own constructor comment states that
`ForkJoinPool.commonPool()` "is unsuitable for blocking I/O" and requires production
callers to pass a `WorkerBulkhead`. `LogicalExpiryEmbeddingCache`'s public two-argument
constructor — the one 7010 uses — schedules its background refreshes, which are blocking
Redis GETs, on exactly that pool. Under `limits.cpu: "1"` the common pool has **one**
worker, so a slow Redis serializes every background refresh behind a single thread shared
with every other common-pool user in the JVM. The serve-stale contract still holds (readers
get the stale value and never block on the refresh), so this degrades refresh *freshness*,
not availability.

## 5. Connection pools — the knobs don't bound the serving path

[02_Caching §8](02_Caching.md#8-redis-itself-as-a-cache-tier) documents the mechanism: one
lazily-opened shared multiplexed connection for sync commands, plus a commons-pool2 pool
used only for pipelines and timed primary reads. The consequence is worth stating plainly
because the configuration reads as though it were otherwise:

> `REDIS_POOL_MAX_TOTAL`, `_MAX_IDLE`, `_MIN_IDLE` and `_MAX_WAIT_MS` do **not** bound
> concurrency on the normal read/write path. Every `execute`/`executeRead` call multiplexes
> over the **single** shared connection.

So 7010's `REDIS_POOL_MAX_TOTAL: "64"` and `REDIS_POOL_MAX_WAIT_MS: "100"` govern neither
serving read. They apply to `executePipelined` (bulk embedding writes) and
`executePrimaryRead` (the consistency and primary-read paths, §7). Request-path
concurrency against Redis is bounded by the admission gate and the bulkhead instead — which
is fine, and is the reason multiplexing was chosen, but it means tuning the pool in
response to a serving-latency incident would change nothing.

Two smaller notes. `testOnBorrow` defaults to `true`, so each pipeline or primary read pays
a validation round trip before its own. And the shared connection is opened lazily on first
use, which is deliberate — it lets a service construct its stores at boot against a down
Redis and fail at request time where callers can fall back, rather than failing to start.

## 6. Retries — deliberately minimal, and correctly so

| Path | Policy |
|---|---|
| gateway → backend | 1 retry, `IOException` **only**, explicitly *not* `SocketTimeoutException`, `Backoff.fixed(50)`, `maxTotalAttempts(2)` |
| gateway → LLM | retry-once on upstream `429`, scheduled non-blocking |
| MySQL reads | retry with 50 ms backoff; not on timeout, auth, or syntax errors |
| **any Redis call** | **none** |

The gateway rule is the load-bearing one and it is right: retrying an `IOException` recovers
the Cloud Map deregistration window, while refusing to retry a timeout is what stops the
gateway from amplifying a slow backend into 2× the load at the moment it can least absorb
it. The cost is bounded and visible — worst-case upstream time becomes
`3 s + 50 ms + 3 s ≈ 6.05 s`, which is the number in §1 that exceeds 7010's own 500 ms
ceiling twelvefold.

Redis has no retry by design.
`ClientOptions.disconnectedBehavior(REJECT_COMMANDS)` plus `TimeoutOptions.enabled()` make
a disconnected Redis fail *immediately* rather than buffer, which is precisely what hands
control to the serve-stale caches. Retrying there would convert a fast, absorbable failure
into a slow one.

## 7. Graceful fallbacks — layered, with one deliberate exception

The default read path degrades at five levels, each independent of the ones above it:

1. **Channel** — timeout, error, or bulkhead rejection becomes an empty candidate list;
   `ChannelHealthMonitor` backs the channel off after 3 consecutive failures;
   `RecallDegradationMetrics` classifies the outcome as `HEALTHY` / `PARTIAL` /
   `ALL_CHANNELS`.
2. **Merge** — the two-phase quota merge gap-fills a degraded channel's slots from the
   channels that answered.
3. **Response** — if reranking yields nothing, the response falls back to the trending
   list.
4. **Store** — trending serves stale for 60 s past its 2 s freshness; embeddings serve
   stale past soft expiry while one background refresh runs; absent users are negatively
   cached so a new userId does not re-hit Redis every request.
5. **Edge** — per-route circuit breakers and health-checked endpoint groups drop a down
   backend from selection, so the gateway fast-fails 503 instead of spending its 3 s.

The **primary-read path is the deliberate exception**. When a caller presents a consistency
token, `recommendPrimary` / `recallPrimary` disable every one of those fallbacks: an
unavailable channel throws `PrimaryRecallUnavailableException` → 503, replica reads are
bypassed for `executePrimaryRead`, and stale values are never served. That is the correct
trade for read-your-writes, but it means the consistency path has a strictly worse
availability profile than the default path — and it is also the path that adds the longest
wait in the system.

`ConsistencyWaiter.await(eventId, userId, Duration.ofSeconds(2))` polls the lineage key
every 50 ms up to a 2 s budget
([`OnlineServices.java:147`](../../src/main/java/com/recsys/application/online/OnlineServices.java)),
on the blocking-task thread, inside a request whose Armeria deadline is **500 ms**. When
materialization is fast the wait returns well inside the deadline and the path works as
designed. When it is not, Armeria terminates the response at 500 ms while the handler keeps
polling for the remaining ~1.5 s and then constructs the `202 Accepted` +
`Retry-After: 1` that the design uses to tell the client to come back. Nobody receives it.
The client sees a generic timeout rather than the backpressure signal, and the thread stays
held for 4× the request budget.

This is **latent, not live**: `ONLINE_DURABLE_EVENTS_ENABLED` is `"false"` in
`k8s/base/configmap.yaml` and is overridden in no overlay, so the token path is unreachable
as deployed. It becomes real on the day that flag is turned on, which is exactly when the
`202` contract is being relied upon. Either the wait must be bounded by the request's
remaining budget or `ONLINE_REQUEST_TIMEOUT_MS` must exceed it; the two numbers cannot both
stay as they are.

## 8. What this investigation changed

One change, the lowest-risk of the findings and the one whose fix already exists elsewhere
in the repo: **`REDIS_TIMEOUT_MS: "200"` for catalog-serving in `k8s/base`**, matching
online-serving. This brings 6010's per-command budget under its 200 ms channel budget, so a
timed-out channel releases its bulkhead thread on roughly the same schedule the caller gave
up on it, instead of ~10× later.

`CatalogRedisTimeoutManifestTest` pins the pairing in both directions: catalog-serving must
set `REDIS_TIMEOUT_MS`, and the value must not exceed the 200 ms
`RECALL_CHANNEL_TIMEOUT_MS` default. The test reads manifest text — it proves a coupling
between two files, not what a cluster receives, the same limitation
[22_Data_Leakage_Posture §5](22_Data_Leakage_Posture.md) records for its own manifest tests.

Everything else in §§1–7 is documented and **not** fixed. Sharp edges below say why.

## Sharp edges — status

1. **6010's command timeout — FIXED** (§2, §8). Was 2000 ms under a 200 ms channel
   budget; now 200 ms via `k8s/base`. Note the fix is configuration: a deployment that does
   not apply `k8s/base` — local `run-microservices-local.sh`, any overlay not composing
   base — still gets the 2000 ms code default. The durable fix would be to cap it in code
   the way 8080 does, and is not done.
2. **Bulkhead width vs. admitted concurrency — OPEN** (§2). 10 task slots against 64
   admitted requests × 6 channels under `limits.cpu: "1"`. Deliberate bounded-queue
   behaviour, but it makes channel rejection the steady state rather than an overload
   signal. Sizing the two gates from one input would need a decision about which is the
   real limit; that decision has not been made.
3. **Consistency wait exceeds the request deadline — OPEN, latent** (§7). 2 s poll inside a
   500 ms deadline makes the designed `202` unreachable. Dormant behind
   `ONLINE_DURABLE_EVENTS_ENABLED=false`. Fixing it means changing one of two numbers, and
   which one depends on whether the token path is meant to be slower than the default path
   — a product question, not a tuning one.
4. **No server deadline on 6010 or 8010 — OPEN** (§1). Both run on Armeria's 10 s default.
   For 8010 this is arguably right (it fronts an LLM path with a 120 s budget); for 6010 it
   means no layer bounds a request end-to-end.
5. **Cache wait budgets exceed the request deadline — OPEN** (§4). Four 2000 ms waits under
   a 500 ms deadline. Unreachable as client latency, load-bearing as thread occupancy.
   Deriving them from a per-request deadline would require threading a budget object
   through the store interfaces, which no store takes today.
6. **Background refresh on the common pool — OPEN** (§4). Blocking Redis I/O on a
   one-worker pool under `limits.cpu: "1"`, contradicting the warning
   `MultiChannelRecallService` gives about the same pool. Degrades refresh freshness, not
   availability.
7. **Serial stages and duplicated reads — OPEN, by omission** (§3). Three independent
   round trips issued serially; recent history and trending each read twice. Invisible on a
   warm pod, real on a cold one. Pipelining them is a code change with a measurable
   benefit only under cold-cache conditions that nothing currently measures.

## What was not investigated

- **No load measurement.** Every number here is a configured budget, not an observed
  latency. There is no p50/p99 for any stage, so this document cannot say which stage
  actually dominates a real request — only which stage is *allowed* to. The
  `@Tag("load")` harnesses ([overload-characterization](../runbooks/overload-characterization.md))
  are the place that would answer it and were not run.
- **The `orTimeout` probe was synthetic.** It reproduced the JDK/executor semantics with
  `Thread.sleep` standing in for a Redis call, in a 1-thread pool. It proves the semantics
  the finding rests on; it is not a measurement of a real pod's bulkhead under a real slow
  Redis.
- **Flink-side latency is out of scope.** How long a feature takes to reach Redis is a
  freshness question, covered by
  [serving-data-freshness](../runbooks/serving-data-freshness.md), not a serving-latency
  one.
- **CDN and ALB contributions are excluded.** The budget chain starts at the gateway
  process. Edge cache-hit ratio and its effect on origin latency are in
  [12_CDNS](12_CDNS.md).
