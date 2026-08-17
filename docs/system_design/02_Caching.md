# Caching in Recsys-Backend-Service

An investigation of the caching layers that keep the recommendation hot path fast: a
three-tier embedding cache, a soft-TTL cache that serves stale while it refreshes, a
generic single-flight snapshot cache, and result caches for recommendations and LLM
responses. The recurring discipline is the same everywhere — **bounded, single-flight,
serve-stale-on-error, and keyed so a deploy sidesteps stale data**.

## The big picture

Every cache here follows four rules, because they run on the request path where a slow
or unbounded cache is worse than no cache:

- **Bounded** — a fixed capacity with LRU/sentinel eviction, so no cache can grow the
  heap without limit.
- **Single-flight** — when an entry expires, exactly one caller recomputes it while the
  rest wait or serve stale; a hot key never triggers a thundering herd on the backing
  store.
- **Serve-stale-on-error** — a backing-store failure serves the last-good value within a
  bounded stale window rather than erroring (fail-open — the availability choice from
  [05_CAP](05_CAP.md#2-reads-are-ap-by-default)).
- **Keyed by version** — result caches key on the A/B variant + model version, so a
  deploy naturally invalidates without an explicit purge.

The caches:

| Cache | Caches | Expiry strategy | Bound |
|---|---|---|---|
| `MultiLevelEmbeddingCache` | item/user embeddings | L1 no-TTL + null sentinel; L2/L3 fallthrough | L1 = 10,000 entries; sentinels = `EMBEDDING_NULL_SENTINEL_MAX_ENTRIES` |
| `LocalEmbeddingCache` | embeddings (L2 local) | access-order LRU | `LOCAL_EMBEDDING_CACHE_MAX_ENTRIES` (100,000) |
| `LogicalExpiryEmbeddingCache` | user embeddings (`u2vEmb`) | **soft** TTL (~30 s) + serve-stale + 1 refresh | `LOGICAL_EXPIRY_CACHE_MAX_ENTRIES` (10,000) |
| `TtlSingleFlightCache<V>` | any snapshot | fresh 1 s / stale 60 s + single-flight | per-key |
| `RecommendationCache` | rec results + cold-start pools | 300 s / 3600 s, keyed by variant+version | bounded map |
| `LlmResponseCache` | LLM responses | 300 s TTL | 500 entries |
| Redis (the L2 everything shares) | embeddings, top-K, features | per-key TTL **+ jitter**; `volatile-lru` at `maxmemory`, so only TTL'd keys evict (§8) | `maxmemory 200mb` |
| CloudFront edge | catalog reads | 1 h / 5 min (see [12_CDNS](12_CDNS.md)) | edge |

## 1. Embedding caches — the hot path

Embeddings are read on every recall, so they get the most cache machinery.

**`MultiLevelEmbeddingCache`** ([infrastructure/cache/MultiLevelEmbeddingCache.java](../../src/main/java/com/recsys/infrastructure/cache/MultiLevelEmbeddingCache.java))
is an explicit three-tier cache:

- **L1** — a JVM `ConcurrentHashMap` bounded at `DEFAULT_L1_CAPACITY = 10_000`, **no TTL**
  (entries live until eviction or restart). An L2 hit is **promoted** to L1 only if the
  `HotKeyDetector` says the key is hot — so L1 holds the genuinely hot IDs, not everything.
- **L2** — any `EmbeddingStore` (typically Redis / `LocalEmbeddingCache` → Redis). L2
  exceptions are caught and logged, and execution **falls through to L3** (graceful
  degradation, not an error).
- **L3** — an optional file-system snapshot fallback (may be `null`), used only when L2 is
  unavailable — so a Redis outage still serves embeddings from the snapshot.

`setEmbedding` is **write-through** to L1 + L2 (L3 writes are attempted and swallowed).
A **null sentinel** (30 s TTL) absorbs repeated misses for genuinely-absent IDs so they
don't re-hit L2/L3 every request, and per-tier hit counters (`l1Hits`/`l2Hits`/`l3Hits`/
`misses`) make abnormal L3 traffic (a Redis problem) observable.

**`LocalEmbeddingCache`** ([infrastructure/cache/LocalEmbeddingCache.java](../../src/main/java/com/recsys/infrastructure/cache/LocalEmbeddingCache.java))
is the cache actually wired in production — a Caffeine JVM-heap cache in front of Redis
(`maximumSize = LOCAL_EMBEDDING_CACHE_MAX_ENTRIES`, default 100,000). It layers three
cache-penetration defenses: a **Bloom filter** guard (after warm-up, `mightContain==false`
short-circuits the Redis round-trip for known-absent IDs), a bounded **null-sentinel**
cache (100k, 30 s), and **single-flight** miss loading (2 s wait). `warmUp()` bulk-loads
from Redis and `preload()` seeds from the classpath. (`MultiLevelEmbeddingCache` above is
the *explicit-tier* design; in production the "multi-level" shape is realized by
`LocalEmbeddingCache` → Redis.)

**`LogicalExpiryEmbeddingCache`** ([infrastructure/cache/LogicalExpiryEmbeddingCache.java](../../src/main/java/com/recsys/infrastructure/cache/LogicalExpiryEmbeddingCache.java))
solves the hot-key TTL stampede a different way: it embeds a **soft (logical) expiry**
inside each entry and gives the backing-store key a much longer **hard** TTL (2× soft).
On a read *before* soft expiry it's a plain hit; *past* soft expiry it **returns the
stale-but-valid value immediately and schedules exactly one background refresh** — so
the herd never forms. Refreshes (and cold misses) are deduped per ID via a `refreshing`
map. Used for user embeddings (`u2vEmb`, ~30 s soft TTL — see the staleness table in
[15_Eventual_Consistency](15_Eventual_Consistency.md)).

**Every embedding-cache tier is size-bounded** (since 2026-07-28). `LocalEmbeddingCache`
(`LOCAL_EMBEDDING_CACHE_MAX_ENTRIES`, default 100,000), `LogicalExpiryEmbeddingCache`
(`LOGICAL_EXPIRY_CACHE_MAX_ENTRIES`, default 10,000) and both negative caches of
confirmed-absent IDs (`EMBEDDING_NULL_SENTINEL_MAX_ENTRIES`, default 10,000) use Caffeine
`maximumSize`, so a sweep over many distinct or absent IDs cannot grow the heap without
limit.

One deliberate asymmetry: the sentinel and refresh-guard maps also carry
`expireAfterWrite`, but `LogicalExpiryEmbeddingCache`'s *value* map is bounded by **size
only**. A time-based eviction there would defeat the pattern — an entry past its soft
expiry must stay servable until a refresh replaces it, or a backing-store outage would
evaporate every entry and turn each read into a cold miss, which is precisely the herd
this cache exists to prevent.

## 2. `TtlSingleFlightCache` — the generic serve-stale primitive

[`TtlSingleFlightCache<V>`](../../src/main/java/com/recsys/infrastructure/cache/TtlSingleFlightCache.java)
generalizes the fresh/stale lifecycle the infra stores use inline. Each key has a
**fresh** window (`DEFAULT_FRESH_TTL_MS = 1_000`) and a **stale** window
(`DEFAULT_STALE_TTL_MS = 60_000`), and reads take one of three paths:

1. **Fresh hit** (`now < freshUntil`) — return cached, loader never called.
2. **Stale window** (`freshUntil ≤ now < staleUntil`) — **one** caller refreshes
   asynchronously; everyone else serves the stale value, and if the refresh loader
   throws, the stale value is kept and served.
3. **Cold miss** (beyond stale) — block-and-load, coalescing concurrent callers via a
   `refreshing` key set.

Its concrete user is
[`GlobalPopularityStore`](../../src/main/java/com/recsys/infrastructure/redis/GlobalPopularityStore.java)
(a 100-item popularity snapshot that fails open to an empty list when Redis is down and
no snapshot exists). It is the reusable form of the same pattern `OnlineFeatureStore`
(5 s / 60 s) and `ShardedTopKStore` (2 s / 60 s) implement inline.

## 3. `RecommendationCache` — result and cold-start caching

[`RecommendationCache`](../../src/main/java/com/recsys/application/recommendation/RecommendationCache.java)
caches finished recommendation results and the pre-scored cold-start pools on the model
service. It is **keyed by A/B variant + model version**, so a new model or variant deploy
sidesteps stale results with no explicit invalidation. TTLs are ~**300 s** for
recommendations and ~**3600 s** for cold-start pools (`RecommendationCacheProperties`),
and its hit/miss rates are exposed at `GET /health/cache`. Its concurrency was tuned from
a `synchronized` + access-order map (which serialized every read) to a
`ReentrantReadWriteLock` + insertion-order map so reads run in parallel.

## 4. `LlmResponseCache` — buffered LLM responses

[`LlmResponseCache`](../../src/main/java/com/recsys/infrastructure/cache/LlmResponseCache.java)
caches non-streaming LLM proxy responses keyed by a **SHA-256 of the request body**
(bounded at `LLM_CACHE_MAX_SIZE`, default **500**; TTL `LLM_CACHE_TTL_SECONDS`, default
**300 s**), returning `X-Cache: HIT/MISS`. It applies **only to the buffered path** — the
SSE streaming path skips caching entirely (a stream can't be replayed from a cache). The
justification for caching a nondeterministic model is that the demo runs at
temperature 0. It sits inside the [API Gateway](09_API_Gateway.md) LLM proxy; its
streaming-vs-buffered behavior is owned by [SSE Streaming](16_SSE_Streaming.md).

## 5. Supporting machinery

- **`HotKeyDetector`** — a lock-free two-bucket, alpha-weighted sliding window that
  decides which IDs are hot enough to promote into L1 (and gates eviction). It's the
  promotion policy for `MultiLevelEmbeddingCache`.
- **`SingleFlight`** ([infrastructure/resilience/SingleFlight.java](../../src/main/java/com/recsys/infrastructure/resilience/SingleFlight.java))
  — the general dedup primitive behind the caches; on a wait timeout it fails open to an
  independent compute rather than blocking ([18_Fault_Tolerance](18_Fault_Tolerance.md#redis-resilience)).
- **Infra serve-stale caches** — `OnlineFeatureStore` (5 s fresh / 60 s stale) and
  `ShardedTopKStore` (2 s / 60 s) implement the same fresh+stale+single-flight lifecycle
  inline; see [03_DB_Scaling_Sharding §2](03_DB_Scaling_Sharding.md#2-shardedtopkstore--sharded-trending).
- **CloudFront edge cache** — the outermost cache tier, for the two catalog reads, is the
  [CDN Edge investigation](12_CDNS.md#1-what-is-cached-and-what-isnt).

## 6. Where each cache invalidates

The one meaningful axis of difference is **write-through vs TTL-only**:

- **Write-through (invalidate on write)** — `MultiLevelEmbeddingCache.setEmbedding` and
  `LogicalExpiryEmbeddingCache` update on the write path, and the CDN has manual operator
  invalidation. These are the caches where a same-version data change is reflected
  promptly.
- **TTL-only** — everything else (recommendation results, LLM responses, feature/top-K
  snapshots) is *not* invalidated on writes; a same-version change is served stale up to
  the TTL. Result caches sidestep this with **version keying** (a deploy changes the key),
  which is why they can afford a longer TTL. The full staleness-window catalog is in
  [15_Eventual_Consistency §2](15_Eventual_Consistency.md).

## 7. Testing

- **Embedding caches** — `MultiLevelEmbeddingCacheTest` (tier promotion, L2→L3
  fallthrough, null sentinel), `LocalEmbeddingCacheTest` (LRU, batch dedup),
  `LogicalExpiryEmbeddingCacheTest` (soft-expiry serve-stale + single refresh).
- **Generic** — `TtlSingleFlightCacheTest` (fresh/stale/cold paths, serve-stale-on-error,
  coalescing).
- **Result caches** — `RecommendationCacheTest` (version keying, hit rates),
  `LlmResponseCacheTest` (SHA-256 key, TTL, bound).

## 8. Redis itself as a cache tier

Everything above treats Redis as "the backing store", but Redis is really **half cache,
half state**: both the primary and the replica StatefulSets
([k8s/base/redis-cluster.yaml](../../k8s/base/redis-cluster.yaml), mirrored in
`docker-compose.streaming.yml`) run with

```
--maxmemory 200mb --maxmemory-policy volatile-lru
```

RDB snapshots (`--save`) are on, so data survives a restart, and **`volatile-lru` confines
eviction to keys that carry a TTL**. That split is the whole design: everything cache-like
sets an explicit TTL, so the keys *without* one are exactly the authoritative ones and are
structurally protected from eviction.

The policy is not a tuning knob — it is a correctness invariant, pinned by
`RedisEvictionPolicyManifestTest` and reported at runtime as
`redis_cache_evicts_only_volatile_keys` (§8, "observability"). Under the previous
`allkeys-lru`, an evicted `shard:topology` would be silently recreated at **version 1** by
`ShardTopologyStore.bootstrap`, resetting a resharded cluster's generation and addressing
data under the wrong key prefix. The trade is deliberate: when memory fills with
non-evictable keys, writes fail loudly with an OOM error instead of quietly dropping state.

### The same invariant, two different mechanisms

**In EKS none of the above applies.** `k8s/eks-shared` scales `redis-primary`,
`redis-replica`, and `redis-sentinel` to **0** in every region — ElastiCache serves
instead — so `k8s/base/redis-cluster.yaml` is inert in production. ElastiCache is
**ElastiCache for Redis**, not a different engine (the overlay requires "Engine mode: Redis,
not Cluster Mode"), so the semantics are the same; what differs is that its config lives in
an AWS **parameter group**, out of reach of the manifests.

That leaves the invariant enforced by two mechanisms and verified by a third:

| Where | Mechanism | Checked by |
|---|---|---|
| Local / `k8s/base` | `--maxmemory-policy volatile-lru` in the manifests | `RedisEvictionPolicyManifestTest` |
| EKS (both regions) | `maxmemory-policy` on a **custom** ElastiCache parameter group | [`scripts/set-elasticache-parameters.sh`](../../scripts/set-elasticache-parameters.sh) (`apply` / `verify`) |
| Any | the running policy as Redis reports it | `redis_cache_evicts_only_volatile_keys` |

ElastiCache is provisioned out-of-band (this repo has no IaC), following the same
convention as CloudFront and the WAF, so the repo can only *state* the requirement — the
two `redis-elasticache-patch.yaml` headers list it alongside Multi-AZ and the reader
endpoint, and the manifest test asserts they keep saying so. Two operational details the
script encodes:

- **A `default.*` parameter group cannot carry it.** AWS rejects edits to its managed
  default groups, so the cluster needs a custom group; `apply` fails fast with that
  instruction rather than surfacing an opaque API error.
- **Run it once per region.** Global Datastore replicates *data*, not parameter groups, and
  the us-west-2 secondary is promoted to primary on failover — a secondary that evicted
  untl'd keys is promoted already missing them.

Only the metric closes the loop. The manifest test cannot see a live cluster, and the
script only sees the group it is pointed at; `redis_cache_evicts_only_volatile_keys`
reports what the serving path is actually talking to, which is what catches a manual
`CONFIG SET`, an unmanaged instance, or a region nobody ran the script against.

### Running the claim instead of asserting it

ElastiCache is out-of-band, so the argument above would otherwise never execute.
[`scripts/simulate-elasticache-eviction.sh`](../../scripts/simulate-elasticache-eviction.sh)
starts a throwaway local `redis-server` (no Docker, no AWS) and applies real memory pressure
under each policy — see [the runbook](../runbooks/elasticache-local.md) for the full output.
At `maxmemory=8mb` with 51 authoritative keys and ~3000 filler writes:

| Scenario | Authoritative kept | Note |
|---|---|---|
| `volatile-lru`, TTL'd pressure | **51/51** | 1579 keys evicted, 0 writes refused |
| `allkeys-lru`, TTL'd pressure | **19/51** | `shard:topology` evicted in **4 of 5** trials |
| `volatile-lru`, un-TTL'd pressure | 51/51 | 1565 writes refused with OOM — sharp edge 6, made concrete |

Two things this measured that the prose had wrong or missing:

- **The old policy's damage is probabilistic, not certain.** Redis samples for approximate
  LRU, so survival moves run to run (0–24 of 50 embeddings across trials). The fix removes a
  coin flip rather than improving odds — worth stating precisely, because "it might be fine"
  is exactly the reasoning that leaves it unfixed.
- **`maxmemory` is enforced per dispatched command, not per `redis.call` inside a script.**
  A single Lua script runs to completion and overshoots the limit — measured at 15.8 MB
  against 8 MB, near 2×. No eviction policy changes this, and the mechanism is real: the
  Flink sinks write through Lua (`SET_IF_NEWER_WITH_LINEAGE_SCRIPT`, `ATOMIC_TOPK_SCRIPT`).
  But the 2× magnitude is not — those sinks write far fewer keys per invocation than the
  simulation's synthetic script does. In this system the per-invocation writes are small,
  so the practical overshoot is
  kilobytes, not the 2× the simulation shows: `SET_IF_NEWER_WITH_LINEAGE_SCRIPT` touches 5
  keys and `ATOMIC_TOPK_SCRIPT` writes `top-k` members (default **10**) into 2 ZSets. The
  simulation reaches 15.8 MB only because it writes 3000 keys in one `EVAL`, which no sink
  does. The mechanism is worth knowing before someone adds a batching writer; it does not
  justify resizing `maxmemory` today.

**TTL jitter — the cache-avalanche defense.** Redis-side TTLs are never used raw:
[`RedisEmbeddingStore.jitteredTtlMillis`](../../src/main/java/com/recsys/infrastructure/redis/RedisEmbeddingStore.java)
adds uniform **positive** jitter in `[0, jitterFraction]` of the base TTL (default
`jitterFraction = 0.1`, clamped to `[0, 0.5]`), and `setEmbeddings` draws a **fresh
jitter per key inside the pipeline** — so a bulk write of N embeddings does not create a
synchronized expiry cliff N keys wide. The jitter is one-sided on purpose: adding time
never shortens the caller's intended freshness window, it only spreads the tail.

**Reads leave the primary.** `getEmbedding`/`getEmbeddings` use `executeRead`, which
[`RoutingRedisExecutor`](../../src/main/java/com/recsys/infrastructure/redis/RoutingRedisExecutor.java)
routes to the AZ-local replica; only writes and pipelines go to the primary. So a cached
read's staleness is *replication lag on top of* the JVM-tier TTL. `executePrimaryRead`
is the read-your-writes escape hatch (used by `getTopKIdsPrimary` and the correlated lag
probe) — see [04_Replication §1](04_Replication.md#1-redis-read-replicas--az-aware-read-routing).

**Batching keeps the round-trip count flat.** Multi-key reads go out as `MGET` in
batches of `REDIS_EMBEDDING_MGET_BATCH_SIZE` (default **500**), and `loadAll` SCANs
pages of 500 and MGETs **each page immediately** rather than accumulating every key name
and issuing one unbounded MGET (a Full-GC/OOM risk on a large store). `loadAll` also
carries a wall-clock budget, `REDIS_LOADALL_TIMEOUT_MS` (default **30 s**), after which
it logs and returns a *partial* result — a slow or oversized Redis degrades startup
warm-up, it doesn't block it.

**A down Redis fails fast, so the JVM tier can serve stale.**
`LettuceClientFactory.failFastOptions()` sets `TimeoutOptions.enabled()` (the per-command
deadline applies even to commands queued while disconnected) plus
`DisconnectedBehavior.REJECT_COMMANDS` (commands error immediately instead of buffering).
Without this, a dead Redis would stall callers past their budget instead of tripping the
serve-stale paths in §1–§2.

The command timeout itself is capped per service, and not in one place. Model serving passes
`RECALL_REDIS_TIMEOUT_MS` (150 ms) to `LettuceClientFactory.routingFromEnv(int)`; catalog 6010
and online 7010 call the **uncapped** overload and depend on `REDIS_TIMEOUT_MS` in
`k8s/base` (200 ms each) instead, so an unset env var leaves them at the 2000 ms default —
10× the recall channel budget layered above them. Why that gap is a capacity problem rather
than a latency one, and the measurement behind it, is
[23_Online_Serving_Latency §2](23_Online_Serving_Latency.md#2-async-apis--where-the-asynchrony-actually-stops).

**Connections.** Each executor keeps one **lazily opened** shared multiplexed connection
for sync commands plus a commons-pool2 pool (`REDIS_POOL_MAX_TOTAL` 50, maxIdle 10,
minIdle 2, `REDIS_POOL_MAX_WAIT_MS` 250) for pipelines — pipelines need a dedicated
connection because auto-flush is a per-connection setting. Lazy connect is what lets a
service construct its stores at boot against a down Redis without failing startup; a
pipeline whose lifecycle was interrupted is **destroyed rather than returned**, so a
half-flushed batch can never replay into an unrelated request.

**What actually carries a TTL in Redis:**

| Key | Redis TTL | Evictable | Written by |
|---|---|---|---|
| `u2vEmb:*` (streaming) | `SETEX ttlSeconds` | yes | Flink `OnlineFeatureStreamingJob` |
| `topk:<window>` | `EXPIRE ttlSeconds` (all 5 keys, in the Lua) | yes | the Flink TopK sink |
| `sr:rec:*` / `sr:dev:*` | `EXPIRE ttlSeconds` when the writer passes one | if TTL'd | `ShardedRecordStore` |
| `svc:registry:*` | `SET … PX` renewed by heartbeat (`SERVICE_REGISTRY_TTL_MS`, 30 s) | yes | `ServiceRegistrar` |
| `i2vEmb:*` / `u2vEmb:*` (seeded) | **none** (`writeMissing(…, 0)`) | **no** | `RecSysServer.seedEmbeddings` |
| `shard:topology` | **none** (`SET … NX`) | **no** | `ShardTopologyStore` |

The pattern: **derived and liveness data expires; authoritative data has no TTL** — and
`volatile-lru` turns that convention into the eviction boundary. Note the two `u2vEmb`
rows: the same key namespace is durable when seeded from the classpath and ephemeral when
written by the streaming job, which is why the TTL, not the prefix, is what decides.

**Streaming-written values are derived, not durable.** The Flink sink writes through a
Lua script that `SETEX`s the value, its `:updated_at`, and its `:last_event` together, and
the authoritative accumulation lives in Flink keyed state (itself TTL'd). Losing a
`u2vEmb:<user>` to eviction or expiry is therefore equivalent to early expiry: the user's
next event rewrites it from Flink state.

**Seeding repairs eviction per id.** `volatile-lru` protects seeded embeddings today, but
the repair path stays because eviction is not the only way to lose them (a flush, a
restored-from-empty Redis, a policy revert).
[`RedisEmbeddingStore.writeMissing`](../../src/main/java/com/recsys/infrastructure/redis/RedisEmbeddingStore.java)
MGETs the classpath ids (batched, **on the primary** — a lagging replica would report a
live key as absent) and pipelines back **only the absent subset**, so a healthy restart
issues zero writes and a depleted one is repaired. The earlier guard re-seeded only when
the store scanned *completely empty*, which meant a partial loss left Redis non-empty and
the missing ids missing until the whole keyspace was cleared.

**Observability.** [`RedisCacheStatsProbe`](../../src/main/java/com/recsys/infrastructure/redis/RedisCacheStatsProbe.java)
samples `INFO` every `REDIS_CACHE_STATS_PROBE_SECONDS` (default **30 s**) on the online
server and publishes via
[`RedisCacheMetrics`](../../src/main/java/com/recsys/metrics/RedisCacheMetrics.java):

| Metric | Answers |
|---|---|
| `redis_cache_evicted_keys` | is Redis evicting at all? |
| `redis_cache_used_memory_bytes` / `_max_memory_bytes` | how close is it to `maxmemory`? |
| `redis_cache_keyspace_hits` / `_misses` | Redis-side hit rate, independent of the JVM tiers |
| `redis_cache_evicts_only_volatile_keys` | is the **running** policy still `volatile-*`? |
| `redis_cache_available` | was the last sample even taken? |
| `redis_unexpected_persistent_keys` | is someone writing keys that can never be evicted? |

The policy gauge and the availability flag matter most. The policy gauge catches drift the
manifest test cannot see — a manual `CONFIG SET`, an unmanaged instance, a hand-rolled
compose file. And because the cumulative counters are **retained** across an unavailable
sample (only `_available` drops to 0), a Redis gap can't masquerade as a counter reset and
corrupt `rate()`.

### Surveying the Redis tier: by consumer, not by store

A recurring mistake when reasoning about this tier — three separate attempts made it during the
2026-08-09 ACL work — is to enumerate the Redis *stores* (`RedisEmbeddingStore`,
`ShardedTopKStore`, `RecommendationCache`, …) and treat that as the keyspace. It is not. Around
**29 consumers sit behind the 35 references to `RedisExecutor`**, and the ones a store-first sweep
misses are exactly the interesting ones: classes that build keys inline, and classes that span
several stores. Each of the three store-first passes missed a *different* set.

Two consequences are worth knowing before changing anything in this tier.

**Failures here are usually silent.** `GlobalPopularityStore` catches `RuntimeException` and returns
an empty result so the caller falls back (`GlobalPopularityStore.java:40`), which is correct for a
Redis outage and indistinguishable from a permission error. So a denied `ZREVRANGE
global:item_popularity` makes Popularity and ColdStart recall quietly return nothing on all three
serving services. `SCAN` compounds this: it takes no key argument, so under a restricted ACL the
scan itself succeeds and only the per-key follow-up is refused — six of the seven access failures
found in that audit were invisible for this reason.

**Two Lua scripts touch keys they do not declare in `KEYS`** — the online rate limiter reaches
`rate:online:<bucket>:<windowId>` while declaring only `rate:online:<bucket>`, and the
sharded-record script constructs `sr:rec:…` internally. Today's key patterns cover both, but the
declared `KEYS` list will not warn anyone who narrows them. Note also that Redis requires *full
read-write* permission on every key passed to `EVAL`, even for a read-only script — so no key
reached through a script can be granted read-only access.

**Three consumers are test-only:** `RedisTopKStore`, `RedisDistributedLock` and `RedisMutex` are
constructed nowhere in `src/main/java`, so `dlock:` and `mutex:` are dead prefixes. `WatchdogLock`
is *not* in that group — it has a live construction, and an earlier survey that grouped it with the
other three was wrong.

## Sharp edges — notes

1. **Most caches don't invalidate on writes.** Only the two write-through embedding
   caches (and manual CDN purges) reflect a same-version change promptly; everything else
   serves stale up to its TTL. Version keying, not invalidation, is what keeps result
   caches correct across deploys.
2. **L1 has no TTL.** `MultiLevelEmbeddingCache` L1 evicts by capacity/hotness, not time —
   a hot embedding that changed in place (same ID, new vector) stays until evicted unless
   written through.
3. **Serve-stale is an availability trade.** Under a backing-store outage caches serve
   old data rather than error; that's deliberate (AP), but it means a Redis incident can
   silently extend staleness to the stale-window bound.
4. **LLM caching assumes determinism.** Caching a model's output is only sound because the
   demo runs at temperature 0; a nonzero-temperature deployment would serve one sampled
   answer for all identical prompts.
5. **The caches' wait budgets exceed the request deadline.** The cold-miss `SingleFlight`
   (2000 ms), `ShardedTopKStore.FETCH_WAIT_TIMEOUT_MS` (2000 ms),
   `OnlineFeatureStore.REDIS_FETCH_TIMEOUT_MS` (2000 ms) and the recommendation cache's
   compute-wait (2000 ms) were each chosen independently of online serving's 500 ms
   `ONLINE_REQUEST_TIMEOUT_MS`. None is reachable as client-visible latency there; all remain
   load-bearing as thread occupancy — see
   [23_Online_Serving_Latency §4](23_Online_Serving_Latency.md#4-caching--the-wait-budgets-are-larger-than-the-request),
   which also covers the pool knobs not bounding the serving path (§5).
5. **Null sentinels are a 30 s bet.** Absent-ID sentinels expire after 30 s, so a newly
   *added* embedding for a previously-missing ID isn't visible until the sentinel lapses
   (or a write-through happens).
6. **`volatile-lru` trades silent eviction for a loud OOM.** Keys without a TTL are no
   longer eviction candidates, so once `maxmemory` is reached and the evictable set is
   exhausted, Redis rejects **writes** with an OOM error rather than dropping state. That
   is the intended failure mode for authoritative data, but it makes `maxmemory` headroom
   something to watch (`redis_cache_used_memory_bytes` vs `_max_memory_bytes`) rather than
   something the policy silently absorbs. The durable set is small and bounded — the
   catalog's embeddings plus one topology document.
7. **The eviction boundary is a writer convention, now sampled rather than assumed.**
   `volatile-lru` is only correct while *every* cache-like writer sets a TTL. Nothing at
   write time enforces that, so
   [`RedisPersistentKeyProbe`](../../src/main/java/com/recsys/infrastructure/redis/RedisPersistentKeyProbe.java)
   walks one bounded `SCAN` page per tick and publishes `redis_unexpected_persistent_keys`
   for keys with no TTL outside the declared durable prefixes (`shard:topology`, `i2vEmb:`,
   `u2vEmb:`, `sr:`, `bias:item:`). It watches the keyspace rather than the code because
   the Flink sinks — the highest-volume writer — are excluded from the Maven compile and
   write through Lua. Two residual gaps: detection is **probabilistic**, so a rarely-written
   key may take many ticks to surface; and the allow-list is itself a declaration that can
   go stale if a new durable namespace is added without updating it.
