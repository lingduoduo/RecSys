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
| Redis (the L2 everything shares) | embeddings, top-K, features | per-key TTL **+ jitter**; LRU eviction at `maxmemory` (§8) | `maxmemory 200mb` |
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

Everything above treats Redis as "the backing store", but Redis is **configured as a
cache, not a store**: both the primary and the replica StatefulSets
([k8s/base/redis-cluster.yaml](../../k8s/base/redis-cluster.yaml), mirrored in
`docker-compose.streaming.yml`) run with

```
--maxmemory 200mb --maxmemory-policy allkeys-lru
```

RDB snapshots (`--save`) are on, so the data survives a restart — but under memory
pressure **any** key is evictable, including keys written with no TTL. LRU protects what
the hot path reads; the exposure is cold data (see sharp edge 6).

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
serve-stale paths in §1–§2; the recall path additionally caps the command timeout via
`LettuceClientFactory.fromEnv(maxTimeoutMs)`.

**Connections.** Each executor keeps one **lazily opened** shared multiplexed connection
for sync commands plus a commons-pool2 pool (`REDIS_POOL_MAX_TOTAL` 50, maxIdle 10,
minIdle 2, `REDIS_POOL_MAX_WAIT_MS` 250) for pipelines — pipelines need a dedicated
connection because auto-flush is a per-connection setting. Lazy connect is what lets a
service construct its stores at boot against a down Redis without failing startup; a
pipeline whose lifecycle was interrupted is **destroyed rather than returned**, so a
half-flushed batch can never replay into an unrelated request.

**What actually carries a TTL in Redis:**

| Key | Redis TTL | Written by |
|---|---|---|
| `i2vEmb:*` / `u2vEmb:*` | **none** as seeded (`setEmbeddings(…, 0)`); jittered TTL only when a caller passes one | `RecSysServer.seedEmbeddings`, Flink |
| `topk:<window>` | **none** | the Flink streaming job (serving is read-only here) |
| `shard:topology` | **none** (`SET … NX`) | `ShardTopologyStore` |
| `sr:rec:*` / `sr:dev:*` | `EXPIRE ttlSeconds` when the writer passes one | `ShardedRecordStore` |
| `svc:registry:*` | `SET … PX` renewed by heartbeat (`SERVICE_REGISTRY_TTL_MS`, 30 s) | `ServiceRegistrar` |

The pattern: **liveness data expires, cached data doesn't** — it is bounded by
`maxmemory` + LRU instead.

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
5. **Null sentinels are a 30 s bet.** Absent-ID sentinels expire after 30 s, so a newly
   *added* embedding for a previously-missing ID isn't visible until the sentinel lapses
   (or a write-through happens).
6. **Redis evicts, and the seed is one-shot.** Embeddings are seeded with **no TTL**, but
   `allkeys-lru` can still evict them — and `RecSysServer.seedEmbeddings` re-seeds only
   when `scanIds(1)` comes back *empty*. After a partial eviction Redis is non-empty, so a
   restart does **not** restore the evicted subset. Hot IDs are safe (LRU keeps what's
   read) and classpath IDs are still served from `LocalEmbeddingCache.preload()`; the real
   exposure is cold long-tail IDs and Flink-written vectors that have no classpath copy.
7. **The cache metrics stop at the JVM boundary.** The per-tier counters (§1) and
   `GET /health/cache` are in-process; nothing scrapes Redis `INFO` for `evicted_keys`,
   `used_memory`, or `keyspace_misses`. Eviction pressure is therefore only visible
   *indirectly*, as a rising L3/miss rate — which is the same signal a Redis outage
   produces.
