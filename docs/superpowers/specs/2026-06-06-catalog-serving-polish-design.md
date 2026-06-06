# Catalog / Recommendation Serving Polish

**Date:** 2026-06-06  
**Approach:** A — Thin wrapper, minimal disruption

---

## 1. Goal

Polish `RecSysServer` (Armeria) to:

1. Replace the ad-hoc `?mode=` dispatch in `RecommendationService` with a unified `MultiChannelRecallService` pipeline that always runs all recall channels and blends results by score.
2. Add four concrete `RecallChannel` implementations (Embedding, Trending, GenreHistory, Popularity).
3. Enable hot-reload of item embeddings into the in-memory vector index on every `/setembedding` write.
4. Add `POST /setuserembedding` for runtime user embedding updates.

---

## 2. Architecture & Component Map

```
RecSysServer
  │
  ├─ MultiChannelRecallService
  │    ├─ EmbeddingChannel       → CandidateGenerator.byEmbedding()
  │    ├─ TrendingChannel        → ShardedTopKStore.getTopKIds("last_hour")
  │    ├─ GenreHistoryChannel    → CandidateGenerator.byUserHistory()
  │    └─ PopularityChannel      → DataManager.getTopRatedMovies() + getLatestMovies()
  │
  ├─ RecommendationService       (delegates entirely to MultiChannelRecallService)
  ├─ SimilarMovieService         (unchanged)
  ├─ SetEmbeddingService         (item embed write → LocalEmbeddingCache + hot-swaps VectorIndex)
  └─ SetUserEmbeddingService     (NEW — user embed write → LocalEmbeddingCache for users)

CandidateGenerator
  └─ updateEmbedding(id, vec)    (NEW — mutates VectorIndex under a lock)
```

### What changes vs. today

| Area | Before | After |
|---|---|---|
| `RecommendationService` | 3-branch `if/else` on `?mode=` | Single call to `MultiChannelRecallService.recall()` |
| `RecallChannel` | Interface only | 4 concrete implementations |
| Item embed write | Writes to cache only | Also calls `CandidateGenerator.updateEmbedding()` |
| User embed write | No endpoint | `POST /setuserembedding` writes to user `LocalEmbeddingCache` |
| `RecSysServer` | Builds `CandidateGenerator` standalone | Builds channel list → `MultiChannelRecallService` |

---

## 3. Data Flow

### `GET /getrecommendation?userId=X&k=N`

1. `RecommendationService.doGet()` extracts `userId` and `k` (default 20, max 200).
2. Builds `RecommendationQuery(userId, k, watchedIds, null)` — watched IDs from `DataManager.getWatchedMovieIds()` filter all channels uniformly.
3. Calls `MultiChannelRecallService.recall(query, k * RECALL_MULTIPLIER)` (RECALL_MULTIPLIER = 3).
4. Channels fire sequentially; each is catch-wrapped so failures degrade gracefully:
   - **EmbeddingChannel** — `CandidateGenerator.byEmbedding(userId, limit)`; score = cosine similarity.
   - **TrendingChannel** — `topkStore.getTopKIds("last_hour", limit)`; fixed score weight 0.6.
   - **GenreHistoryChannel** — `CandidateGenerator.byUserHistory(userId, limitPerGenre)`; fixed score weight 0.5.
   - **PopularityChannel** — `getTopRatedMovies(limit)` + `getLatestMovies(limit)`; fixed score weight 0.4 (cold-start fallback).
5. `MultiChannelRecallService` deduplicates by `itemId` (keeps best score), filters `excludedItemIds`, sorts descending, returns top `k`.
6. `RecommendationService` maps `itemId`s → `Movie` objects and returns `RecommendationResponse`.

### `POST /setembedding?movieId=X` (item hot-reload)

After writing to `LocalEmbeddingCache` (which propagates to Redis), `SetEmbeddingService` calls `CandidateGenerator.updateEmbedding(id, vec)`. That method mutates the current `VectorIndex` under a lock (`addOrUpdate` on LSH; direct map update on exact). No `AtomicReference` swap needed — in-place mutation under lock is simpler.

### `POST /setuserembedding?userId=X` (NEW)

Mirrors `SetEmbeddingService` but targets `userEmbCache` (`LocalEmbeddingCache` for users). Same `vec` and `ttl` query params, same JSON response shape `{ok, userId, dim, ttl}`. No vector index involved.

---

## 4. New Files

| File | Purpose |
|---|---|
| `service/retrieval/EmbeddingChannel.java` | `RecallChannel` wrapping `CandidateGenerator.byEmbedding` |
| `service/retrieval/TrendingChannel.java` | `RecallChannel` wrapping `ShardedTopKStore` |
| `service/retrieval/GenreHistoryChannel.java` | `RecallChannel` wrapping `CandidateGenerator.byUserHistory` |
| `service/retrieval/PopularityChannel.java` | `RecallChannel` wrapping `DataManager` top-rated + latest |
| `serving/SetUserEmbeddingService.java` | POST handler for user embedding writes |

---

## 5. Modified Files

| File | Change |
|---|---|
| `serving/RecommendationService.java` | Replace `?mode=` dispatch with single `MultiChannelRecallService.recall()` call; lower HTTP `k` max to 100 (matches `RecommendationQuery.limit` cap) |
| `serving/SetEmbeddingService.java` | Inject `CandidateGenerator`; after cache write, call `candidateGenerator.updateEmbedding(id, vec)` |
| `serving/RecSysServer.java` | Build 4 channels → `MultiChannelRecallService`; register `/setuserembedding`; pass `candidateGenerator` to `SetEmbeddingService` |
| `infrastructure/vectordb/CandidateGenerator.java` | Add `synchronized updateEmbedding(int id, float[] vec)` delegating to `embeddingIndex.addOrUpdate()` |
| `infrastructure/vectordb/LshVectorIndex.java` | Change `allIds` from `Set.copyOf` → `ConcurrentHashMap.newKeySet()`; add `addOrUpdate(int id, float[] vec)` (calls `super.addOrUpdate` + `lsh.add`) |
| `infrastructure/vectordb/ExactVectorIndex.java` | Change `embeddings` from `Map.copyOf` → `new ConcurrentHashMap<>()`; add `addOrUpdate(int id, float[] vec)` |
| `infrastructure/vectordb/EmbeddingLSH.java` | Change `buckets` from `Map.copyOf` → `ConcurrentHashMap` with `CopyOnWriteArrayList` values; add `void add(int id, float[] vec)` |
| `infrastructure/vectordb/VectorIndex.java` | Add `default void addOrUpdate(int id, float[] vec)` (no-op default for backward compat) |

### Type-bridging note

`RecommendationQuery.userId` is `String`. `RecommendationService` parses `int userId` from the HTTP query param and converts to `String` when building the query (`String.valueOf(userId)`). Channels that call `CandidateGenerator` parse it back: `Integer.parseInt(query.userId())`. This is a known wart from `MultiChannelRecallService` being designed for a string-ID catalog; it is not changed in this design.

---

## 6. Error Handling

- Each channel's `recall()` is wrapped in try/catch inside `MultiChannelRecallService`. A throwing channel logs a warning and contributes an empty list.
- If all channels return empty, `RecommendationService` returns HTTP 200 with `movies: []`.
- `?mode=topk`, `?mode=trending`, `?mode=embedding` params are removed. Callers receive blended results. Document in commit message.
- All other routes, paths, and response shapes are unchanged.

---

## 7. Tests

| Test class | New cases |
|---|---|
| `EmbeddingChannelTest` | Returns candidates for known user; empty for unknown user |
| `TrendingChannelTest` | Maps topk IDs to `MovieCandidate` with fixed score |
| `GenreHistoryChannelTest` | Returns genre-based candidates; excludes watched |
| `PopularityChannelTest` | Returns top-rated + latest; deduplicates |
| `MultiChannelRecallServiceTest` | Channel throws → other channels still contribute |
| `RecSysServerIntegrationTest` | `/setuserembedding` happy path; bad body → 400 |

Existing tests are unchanged.

---

## 8. Out of Scope

- Parallel channel execution (sequential is correct and sufficient; add later if latency warrants it).
- Per-channel weight configuration via properties file (Approach C — deferred).
- Deprecation of `CandidateGenerator` (Approach B — future refactor once pipeline is proven).
- `SimilarMovieService` changes.
