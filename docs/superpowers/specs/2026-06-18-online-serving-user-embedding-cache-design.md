# Online Serving User-Embedding Cache Design

_Date: 2026-06-18_
_Scope: Port 7010 (`OnlinePredictionServer`) — user-embedding read path_
_Related: [2026-06-18-feature-store-optimization-design.md](2026-06-18-feature-store-optimization-design.md) (sibling feature-store optimization)_

---

## 1. Problem Statement

`OnlinePredictionServer` (port 7010) reads each request's user embedding through a **bare** `RedisEmbeddingStore` with no JVM cache:

```java
// OnlinePredictionServer.java:50-51
RedisEmbeddingStore userEmbeddingStore = new RedisEmbeddingStore(jedisPool, "u2vEmb");
CandidateGenerator candidateGenerator = new CandidateGenerator(dataManager, userEmbeddingStore);
```

On every `/online/recommendation` and `/online/features` request, `CandidateGenerator.byEmbedding(userId)` issues one `GET u2vEmb:<userId>` plus a fresh pool lease ([CandidateGenerator.java:94-96](../../src/main/java/com/recsys/infrastructure/vectordb/CandidateGenerator.java)). There is no heap cache, no request-coalescing, and no negative caching. At 500 rps this is ~500 avoidable Redis round-trips/second per process for slowly-changing vectors.

`RecSysServer` (port 6010) already wraps the identical `u2vEmb` store in a JVM cache; 7010 simply never received the same treatment.

**Why `LocalEmbeddingCache` (the 6010 cache) is the wrong fit for 7010:** 7010's `u2vEmb:*` keyspace is **continuously written and updated by Flink at runtime** ([OnlineFeatureStreamingJob.java:302](../../src/main/java/com/recsys/online/flink/OnlineFeatureStreamingJob.java)). `LocalEmbeddingCache` has two properties that are safe for 6010's static/batch-seeded embeddings but harmful here:

1. **Bloom penetration guard** — once activated via `preload`/`warmUp`, `getEmbedding` returns `null` for any ID absent at startup *without consulting Redis* ([LocalEmbeddingCache.java:117](../../src/main/java/com/recsys/infrastructure/cache/LocalEmbeddingCache.java)). Users Flink embeds *after* process start would be black-holed until restart, because Flink writes straight to Redis, not through this JVM's cache.
2. **No positive TTL** — cached vectors live until LRU eviction (100k entries). As Flink updates a user's embedding, 7010 would serve the stale vector indefinitely.

---

## 2. Chosen Approach

Wrap 7010's user-embedding store in `LogicalExpiryEmbeddingCache` (currently dead code — only used in tests) with a **30 s soft TTL**, and add a short **null-sentinel negative cache** to that class.

`LogicalExpiryEmbeddingCache` is the correct tool for a continuously-updated keyspace:
- **No Bloom guard** — cold misses always consult Redis, so users Flink embeds post-startup are found.
- **Soft (logical) TTL with serve-stale + single background refresh** — past the soft TTL a request returns the stale-but-valid vector immediately and schedules exactly one async refresh, so Flink's updates land within ~one TTL and no request blocks ([LogicalExpiryEmbeddingCache.java:63-78](../../src/main/java/com/recsys/infrastructure/cache/LogicalExpiryEmbeddingCache.java)).
- **Batched MGET** cold-miss path already present.

The one gap — no negative caching — is closed by this spec so brand-new users (common on 7010, before Flink has embedded them) don't re-hit Redis on every request.

**Out of scope:** item embeddings on 7010 (classpath-loaded into a JVM `ConcurrentHashMap` by `CandidateGenerator`, no per-request Redis); `RecSysServer` (separate path, already cached); all other audit findings (ModelApplication profile re-walk, `LocalEmbeddingCache` lock contention, dead-code removal of `MultiLevelEmbeddingCache`).

---

## 3. Components

### 3.1 `OnlinePredictionServer` wiring (`OnlinePredictionServer.java:50-51`)

```java
RedisEmbeddingStore userEmbeddingStore = new RedisEmbeddingStore(jedisPool, "u2vEmb");
EmbeddingStore userEmbCache = new LogicalExpiryEmbeddingCache(userEmbeddingStore, USER_EMB_SOFT_TTL_SECONDS);
CandidateGenerator candidateGenerator = new CandidateGenerator(dataManager, userEmbCache);
```

`USER_EMB_SOFT_TTL_SECONDS` defaults to `30`, overridable via env `ONLINE_USER_EMB_SOFT_TTL_SECONDS` (mirrors the existing `readIntEnv` pattern in this class). No `preload`/`warmUp` — the cache fills on demand, which is correct for a runtime-written keyspace.

`CandidateGenerator` already accepts the `EmbeddingStore` interface, so no change there.

### 3.2 `LogicalExpiryEmbeddingCache` null-sentinel (`LogicalExpiryEmbeddingCache.java`)

Add a negative cache mirroring `LocalEmbeddingCache`'s existing pattern:

- New field: `ConcurrentHashMap<Integer, Long> nullSentinels` and constant `NULL_SENTINEL_TTL_MS = 30_000L`.
- `getEmbedding(id)`: before the cold-miss fetch, if an unexpired sentinel exists for `id`, return `null` without touching the backing store. On a cold-miss fetch that resolves to `null`, record `nullSentinels.put(id, now + NULL_SENTINEL_TTL_MS)`.
- `getEmbeddings(ids)`: filter out IDs with unexpired sentinels before the batch MGET; for batch-miss IDs that resolve absent, record sentinels.
- Background refresh (`scheduleRefresh` → its loader) that resolves to `null`: record a sentinel (the previously-cached entry is removed/allowed to lapse so the user stops being served a vanished vector).
- `setEmbedding`/`setEmbeddings`: clear the sentinel for written IDs (`nullSentinels.remove(id)`).
- Sentinels are recorded only on a **confirmed `null`** from a successful backing-store call — never on a thrown exception — so a transient Redis failure cannot mark a user absent.

---

## 4. Data Flow & Freshness

| Case | Behavior | Redis cost |
|---|---|---|
| Warm user, within soft TTL | Heap hit | 0 |
| Warm user, past soft TTL | Serve stale vector immediately + schedule one background refresh (deduped) | ≤1 per user per 30 s (async) |
| Cold miss (never cached) | Synchronous GET, deduped across concurrent callers via `coldMissSingleFlight`; cache with 30 s soft expiry | 1 (coalesced) |
| Absent user (no `u2vEmb` yet) | First request GETs `null`, records 30 s sentinel; subsequent requests skip Redis and return `null` (→ empty embedding recall, engine blends trending + recent history) | 1 per 30 s |

Flink's updated embedding for a warm user becomes visible within ~30 s (one soft-TTL refresh cycle). A newly-embedded user becomes visible within ~30 s (sentinel expiry).

**Operational requirement:** the Flink `u2vEmb` write should use a hard TTL ≥ ~2× the soft TTL (≥ 60 s) or no expiry, so a soft-expired entry's background refresh still finds the key. If the key has expired in Redis, the refresh resolves to `null`, the entry lapses, and the null-sentinel path handles it gracefully (empty recall until the user is re-embedded) — no crash.

---

## 5. Error Handling

| Condition | Behavior |
|---|---|
| Redis error on cold miss | `coldMissSingleFlight` propagates; `CandidateGenerator.byEmbedding` treats a missing user vector as "no embedding recall"; `OnlineRecommendationEngine` blends trending + recent-history instead |
| Redis error on background refresh | Stale cached value continues to be served; failure logged; entry untouched (best-effort refresh) |
| Confirmed `null` from Redis | Record null-sentinel (30 s) |
| Redis *exception* (not null) | No sentinel recorded — transient failure cannot poison a user into looking absent |

---

## 6. Testing Strategy

### Unit tests — `LogicalExpiryEmbeddingCacheTest` (extend)

| Case | Assertion |
|---|---|
| Absent ID, repeated reads within sentinel TTL | Backing store consulted once; subsequent reads return `null` without a backing call |
| Sentinel expiry | After TTL, an absent ID re-queries the backing store |
| `setEmbedding` clears sentinel | After writing a previously-absent ID, the next read returns the value (no stale sentinel) |
| Redis exception vs confirmed null | A thrown exception on cold miss does NOT create a sentinel; a `null` return does |
| Background refresh → null | A soft-expired entry whose refresh resolves `null` stops being served and records a sentinel |
| Existing behavior | Soft-expiry serve-stale, single background refresh dedup, batch MGET cold-miss tests still pass unmodified |

### Regression (pass unmodified)

`OnlinePredictionServerIntegrationTest`, `OnlinePredictionRegressionTest` — recommendation output is unchanged for warm users; the wrap only adds caching.

### Integration smoke test

```bash
# Warm user — repeated reads collapse to ≤1 Redis GET per 30s
redis-cli set u2vEmb:1 "0.1 0.2 0.3 ..."
for i in $(seq 1 50); do curl -s "http://localhost:7010/online/recommendation?userId=1" >/dev/null; done
redis-cli --stat   # ~1 GET u2vEmb:1 per 30s window, not 1 per request

# Absent user — does not hammer Redis
for i in $(seq 1 50); do curl -s "http://localhost:7010/online/recommendation?userId=999999" >/dev/null; done
# MONITOR shows ~1 GET u2vEmb:999999 per 30s, not 50
```

---

## 7. Out of Scope (with rationale)

- **Item embeddings on 7010** — `CandidateGenerator` loads movie embeddings from the classpath into a JVM `ConcurrentHashMap` at startup; no per-request Redis read exists to cache.
- **`RecSysServer` (6010)** — already wraps `u2vEmb` in `LocalEmbeddingCache`; its static/batch keyspace makes the Bloom guard + no-TTL behavior safe there.
- **Removing dead code (`MultiLevelEmbeddingCache`)** — separate cleanup; not required for this fix.
- **`LocalEmbeddingCache` synchronized-map read contention** — a distinct optimization (audit finding #3); not part of this scope.
- **ModelApplication per-user profile re-walk** — in-memory only (no Redis); separate concern.

---

## 8. Files Changed

| File | Change |
|---|---|
| `online/serving/OnlinePredictionServer.java` | Wrap `userEmbeddingStore` in `LogicalExpiryEmbeddingCache` (env-configurable soft TTL, default 30 s) before passing to `CandidateGenerator` |
| `infrastructure/cache/LogicalExpiryEmbeddingCache.java` | Add null-sentinel negative cache (30 s); clear on write; record only on confirmed null |
| `src/test/.../cache/LogicalExpiryEmbeddingCacheTest.java` | Extend with null-sentinel cases |
