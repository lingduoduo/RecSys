# RecSys

A compact Maven workspace demonstrating recommendation-system serving, retrieval, ranking, and offline embedding pipelines across four independently runnable services.

| Service | Port | Function |
|---|---:|---|
| Catalog / Recommendation Serving | `6010` | Kafka → Flink → Redis pipeline; cold-start fallback and multi-channel recall (embedding + trending + genre + popularity) |
| Online Prediction Server | `7010` | Real-time feature store — serves per-user history and windowed trending written by the Flink job |
| Model Serving (Spring Boot) | `8080` | Load ONNX model → encode user tower → score candidates; A/B variant management, result caching, load shedding |
| API Gateway | `8010` | Single entry point for all three services: per-route circuit breakers, token-bucket rate limiting, LLM proxy |

## Architecture Layers

| Layer | Key Components |
|---|---|
| API Gateway | `MicroserviceGatewayServer`, `RouteCircuitBreaker`, `GatewayRateLimiter`, `LlmProxyService` |
| Model Serving | `ModelApplication`, `RecommendationController`, `ModelRuntimeProvider`, A/B testing, feature flags |
| Online Serving | `OnlinePredictionServer`, `OnlineFeatureStore`, `ShardedTopKStore`, `OnlineLearner` |
| Catalog Serving | `RecSysServer` (Armeria), embedding-based recall, `SimilarMovieService` |
| Recommendation Service | Shared recall → rank → paginate → hydrate pipeline, `MultiChannelRecallService` |
| Data Infrastructure | Redis embedding store, multi-level cache (L1/L2/L3), LSH vector index, sharded top-K, MySQL |
| Domain Model | Shared value objects: `Movie`, `User`, `MovieCandidate`, `RecommendationQuery` |
| Configuration | Spring `@ConfigurationProperties`, feature flag providers, JVM tuning options |
| Infrastructure | Kubernetes base + EKS overlay, Docker, Flink streaming topology |
| Documentation | README, architecture docs, design specs, implementation plans |

![Architecture](recsys-architecture.png)
[Architecture Diagram (interactive)](https://htmlpreview.github.io/?https://github.com/lingduoduo/Recsys-Backend-Service/blob/main/recsys-architecture.html)

---

## Quick Start

**Requirements:** Java 17, Maven, Docker + Colima (Mac) or Docker Desktop.

```bash
# 1. Start infrastructure (Redis Sentinel, Kafka, Flink)
colima start
docker compose -f docker-compose.streaming.yml up -d

# 2. Build
mvn package -DskipTests

# 3. Start all four services (logs → logs/<service>.log)
sh scripts/run-microservices-local.sh
```

Check the full stack is healthy:

```bash
curl http://localhost:8010/health
```

```json
{
  "status": "UP",
  "checkedAt": "...",
  "services": {
    "recommendation-retrieval": {"status": "UP", "healthUrl": "http://localhost:8080/health/ready", "statusCode": 200, "latencyMs": 4},
    "catalog":  {"status": "UP", "healthUrl": "http://localhost:6010/health", "statusCode": 200, "latencyMs": 2},
    "model":    {"status": "UP", "healthUrl": "http://localhost:8080/health/ready", "statusCode": 200, "latencyMs": 3},
    "online":   {"status": "UP", "healthUrl": "http://localhost:7010/health", "statusCode": 200, "latencyMs": 2}
  }
}
```

### Start individual services

Run each service in its own terminal. Redis must be running first (step 1 above).

```bash
# Port 6010 — Catalog & Recommendation Serving
mvn exec:java -Dexec.mainClass=com.recsys.serving.RecSysServer
curl http://localhost:6010/health
# {"ok":true}

# Port 7010 — Online Prediction Server
mvn exec:java -Dexec.mainClass=com.recsys.online.serving.OnlinePredictionServer
curl http://localhost:7010/online/ops
# {"servedAt":"...","metrics":{...},"load":{...},"capacity":{...}}

# Port 8080 — Model Serving (Spring Boot / ONNX)
mvn spring-boot:run
curl http://localhost:8080/health/ready
# {"status":"UP",...}

# Port 8010 — API Gateway
mvn exec:java -Dexec.mainClass=com.recsys.microservice.MicroserviceGatewayServer
curl http://localhost:8010/health
# {"status":"UP","services":{...}}
```

> The gateway (8010) proxies the other three services — start 6010, 7010, and 8080 first if you want all gateway routes healthy.

---

## Contents

- [Architecture Layers](#architecture-layers)
- [Quick Start](#quick-start)
- [Services & Ports](#services--ports)
- [Recommendation Flow](#recommendation-flow)
- [API Reference](#api-reference)
  - [Port 6010 — Catalog & Recommendation Serving](#port-6010--catalog--recommendation-serving)
  - [Port 7010 — Online Prediction Server](#port-7010--online-prediction-server)
  - [Port 8080 — Model Serving (Spring Boot)](#port-8080--model-serving-spring-boot)
  - [Port 8010 — API Gateway](#port-8010--api-gateway)
- [Microservice Gateway](#microservice-gateway)
- [Configuration](#configuration)
- [Project Layout](#project-layout)
- [Model Serving Demo](#model-serving-demo)
- [A/B Testing](#ab-testing)
- [Feature Flags](#feature-flags)
- [Testing](#testing)
- [Redis Test Data](#redis-test-data)
- [Online Serving](#online-serving)
- [Sharded Record Store](#sharded-record-store)
- [Offline Item Embeddings](#offline-item-embeddings)
- [Kubernetes & EKS](#kubernetes--eks)
- [Capacity Planning](#capacity-planning)
- [JVM Tuning](#jvm-tuning)
- [Pipeline Optimizations](#pipeline-optimizations)
- [LLM Gateway](#llm-gateway)
- [Model Rate Limiting](#model-rate-limiting)
- [AWS Saga Orchestration](#aws-saga-orchestration)
- [Developer Notes](#developer-notes)

---

## Services & Ports

Start each service individually with JVM tuning:

```bash
# Catalog / Recommendation Serving — port 6010
env PORT=6010 sh scripts/run-with-jvm-tuning.sh recsys-serving -- \
  mvn exec:java -Dexec.mainClass=com.recsys.serving.RecSysServer
curl http://localhost:6010/health
# {"ok":true}

# Online Prediction Server — port 7010
env ONLINE_DEMO_PORT=7010 sh scripts/run-with-jvm-tuning.sh online-serving -- \
  mvn exec:java -Dexec.mainClass=com.recsys.online.serving.OnlinePredictionServer
curl http://localhost:7010/online/ops
# {"servedAt":"...","metrics":{...},"load":{...},"capacity":{...}}

# Model Serving (Spring Boot / ONNX) — port 8080
env SERVER_PORT=8080 sh scripts/run-with-jvm-tuning.sh model-serving -- \
  mvn spring-boot:run
curl http://localhost:8080/health/ready
# {"status":"UP",...}

# API Gateway — port 8010
env GATEWAY_PORT=8010 sh scripts/run-with-jvm-tuning.sh api-gateway -- \
  mvn exec:java -Dexec.mainClass=com.recsys.microservice.MicroserviceGatewayServer
curl http://localhost:8010/health
# {"status":"UP","services":{...}}
```

Key env vars: `REDIS_HOST` (default `localhost`), `REDIS_PORT` (default `6379`).

---

## Recommendation Flow

Three independent recommendation paths share a common Redis-backed data layer written by the Flink streaming job.

**Data pipeline (shared)** — Kafka → Flink → Redis

- The Flink job `OnlineFeatureStreamingJob` consumes user events from Kafka and writes per-user recent history (`user:<id>:recent_movies`), user-tower embeddings (`feature:user:<id>:embedding`), and windowed trending counters (`topk:<window>:s0..s3`) into Redis.
- All three serving paths read from this shared Redis store; without the Flink job the lists are empty but the APIs remain healthy.

**Port 6010 — Cold-start & multi-channel recall**

1. On startup, `RecSysServer` seeds item and user embeddings from classpath text files if Redis is empty — providing a usable baseline before the Flink job writes live data.
2. `MultiChannelRecallService` runs all **five** recall channels in parallel — embedding ANN, windowed trending Top-K, genre history, global popularity, and a dedicated cold-start channel — merged under the `QuotaPolicy.defaultMovie()` quota.
3. Results are merged by the quota-aware two-phase merge, already-watched items are excluded, and top-K are returned.

**Port 7010 — Shared multi-channel recall (real-time)**

1. `OnlineFeatureStore` reads per-user recent watch history from Redis (`user:<id>:recent_movies`); `ShardedTopKStore` serves windowed trending items from sharded Redis keys (`topk:<window>:s0..s3`).
2. `OnlineRecommendationService` runs the **same `MultiChannelRecallService`** as 6010 — five channels in parallel (embedding ANN, `online_recent_history`, trending, popularity, cold_start) under the 7010-specific `QuotaPolicy.defaultOnline()` quota, with warm/cold classification from the cache-backed user embedding.
3. `OnlineLearner` re-ranks the merged candidates (adds a learned per-item score adjustment), recent watches are excluded, and the top-`k` are returned with `strategy: "multichannel"`. If every channel returns empty, the trending snapshot is served as a fallback. The `/online/features` endpoint still exposes the raw Redis view for debugging.

**Port 8080 — ONNX model serving**

1. `ModelRuntimeProvider` loads the PyTorch-exported DSSM `dssm_model.onnx` (or an A/B variant artifact) and warms it at startup.
2. `RetrievalService` runs the user tower through ONNX to produce a user embedding.
3. `RankingService` inner-product scores the user embedding against pre-loaded item embeddings and returns top-K.
4. `ABTestService` deterministically assigns each user to a variant bucket (`(userId + ":" + layerName).hashCode() % split`); the active variant's artifacts are used and `abTestVariant` is returned in every response.
5. Cold-start users (not in the model's user vocabulary) are served from a shared pre-scored pool gated by a PostHog feature flag.

---

## API Reference

### Port 6010 — Catalog & Recommendation Serving

Armeria service at the tail of the Kafka → Flink → Redis pipeline. At startup it seeds item and user embeddings from classpath files if Redis is empty, ensuring cold-start recommendations are available before the Flink job delivers live data. Every `/getrecommendation` call fans out across **five recall channels** — embedding ANN, trending Top-K (multi-window), genre history, global popularity, and a dedicated cold-start channel — running each in parallel on a bounded thread pool with per-channel timeouts and circuit-breaker backoff.

Per request, the service probes the user's embedding (`u2vEmb:<userId>`, one heap lookup → Redis fallthrough) to classify the user as **warm** or **cold**, then merges channels with a **quota-aware two-phase merge**:

| Channel | Warm quota | Cold quota |
|---|---:|---:|
| embedding | 60% | 0% |
| trending | 20% | 20% |
| genre_history | 15% | 10% |
| popularity | 5% | 20% |
| cold_start | 0% | 50% |

Phase 1 fills each channel's quota by score; phase 2 gap-fills any shortfall (e.g. a backed-off channel's slots) from the remaining pool. A malformed/blank `userId` defaults to the cold quota.

#### Health check

Verify the service is running:

```bash
curl http://localhost:6010/health
# {"ok":true}
```

#### User lookup

Fetch a user profile by `userId`:

```bash
curl "http://localhost:6010/getuser?userId=123"
curl "http://localhost:6010/user?userId=123"          # REST alias (gateway-friendly)
# 200: {"userId":123,"name":"Alice"}
# 404: {"error":"user not found","userId":123}
```

#### Movie (item) lookup

Fetch a movie by `id`:

```bash
curl "http://localhost:6010/item?id=1"
curl "http://localhost:6010/movie?id=1"               # REST alias
# 200: {"id":1,"title":"Inception","year":2010,"genres":["Sci-Fi","Thriller"]}
# 404: {"error":"movie not found","id":1}
```

#### Recommendations

Runs all five recall channels in parallel, classifies the user as warm/cold from their embedding, and merges with the quota-aware two-phase merge described above. Already-watched movies are automatically excluded.

```bash
curl "http://localhost:6010/getrecommendation?userId=123"
curl "http://localhost:6010/recommendation?userId=123"   # REST alias

# Limit results (default 20, max 100)
curl "http://localhost:6010/getrecommendation?userId=123&k=10"
```

> **The user must exist in the catalog.** `/getrecommendation` looks up the user first and returns `404 {"error":"user not found"}` for an unknown id. The bundled seed data has users **123–127** (warm — all have embeddings) plus **`200` ("New User")**, a built-in **cold** user with no embedding. `userId=999` is not a cold user, it's a missing user (404).

**Cold-start vs. warm — same endpoint, different recall mix.** A *cold* user **exists in the catalog but has no `u2vEmb:<id>` embedding**: its results come from the cold-start, trending, and popularity channels (embedding contributes nothing). A *warm* user has an embedding, so embedding ANN takes 60% of the slots.

```bash
# Warm seeded user — embedding-led (60% of slots from embedding ANN)
curl "http://localhost:6010/getrecommendation?userId=123&k=10"

# Built-in COLD user (200 = "New User", no embedding) — cold_start / trending / popularity dominate
curl "http://localhost:6010/getrecommendation?userId=200&k=10"

# Flip the cold user warm by seeding an embedding — embedding ANN now contributes.
# The vector must match the seed embedding dimension (6); a mismatch returns 400.
curl -X POST "http://localhost:6010/setuserembedding?userId=200&vec=0.1+0.5+0.4+0.0+0.0+0.0"
curl "http://localhost:6010/getrecommendation?userId=200&k=10"
```

```json
{
  "user": {"userId": 123, "name": "Alice"},
  "recommendations": [
    {"id": 4, "title": "The Matrix", "year": 1999, "genres": ["Sci-Fi", "Action"]},
    {"id": 7, "title": "Interstellar", "year": 2014, "genres": ["Sci-Fi", "Drama"]}
  ]
}
```

Returns `404` if `userId` is not found; `400` if `k` is out of range.

The vector backend for the embedding channel is controlled by `RECSYS_VECTOR_BACKEND`:

```bash
# Approximate (default) — SimHash random-projection + inner-product reranking
RECSYS_VECTOR_BACKEND=lsh mvn exec:java -Dexec.mainClass=com.recsys.serving.RecSysServer

# Exact — full-scan inner-product top-k (deterministic, slower)
RECSYS_VECTOR_BACKEND=exact mvn exec:java -Dexec.mainClass=com.recsys.serving.RecSysServer
```

#### Recommendations v2 — cursor pagination (`/v2/recommend`)

`POST /v2/recommend` drives the same multi-channel recall through the recall → rank → hydrate → paginate pipeline (`RecommendationOrchestrator`). It takes a JSON `RecommendationQuery` and returns a cursor for million-scale, stable pagination. Cold/warm channel selection is identical to `/getrecommendation`.

```bash
# First page
curl -X POST "http://localhost:6010/v2/recommend" \
  -H "Content-Type: application/json" \
  -d '{"userId":"123","limit":10,"excludedItemIds":["1","2"]}'

# Next page — pass back the nextCursor from the previous response
curl -X POST "http://localhost:6010/v2/recommend" \
  -H "Content-Type: application/json" \
  -d '{"userId":"123","limit":10,"cursor":"<nextCursor>"}'
```

```json
{
  "userId": "123",
  "items": [
    {"itemId": "4", "score": 0.93, "rank": 1, "features": {"title": "The Matrix", "year": 1999}}
  ],
  "nextCursor": "eyJvZmZzZXQiOjEwfQ==",
  "trace": {"candidateCount": "50", "rankedCount": "50"}
}
```

`limit` must be 1–100; a blank `userId` returns `400`. Unlike `/getrecommendation`, this pipeline does **not** look up the user in the catalog, so an unknown (non-blank) `userId` does not 404 — it runs recall directly (cold quota if it has no embedding). When there are no more results `nextCursor` is `null`. The `trace` map reports `candidateCount` (recalled) and `rankedCount` for debugging.

#### Similar movies

Computes inner-product similarity against Redis item embeddings and returns the closest movies (default `k=10`, max 200):

```bash
curl "http://localhost:6010/similar?movieId=1&k=5"
# 200: {"movieId":1,"similar":[{"movieId":4,"score":0.99},{"movieId":7,"score":0.97},...]}
# 404: {"error":"embedding not found for movieId","movieId":1}

curl "http://localhost:6010/similar?movieId=3&k=10"
```

#### Pair prediction

Scores explicit (userId, movieId) pairs using bundled user/movie embeddings and inner-product scoring:

```bash
curl -X POST "http://localhost:6010/v1/models/recmodel:predict" \
  -H "Content-Type: application/json" \
  -d '{"instances":[{"userId":123,"movieId":1},{"userId":123,"movieId":2}]}'
# {"predictions":[[0.9231],[0.7412]]}
```

Returns `400` when `instances` is empty, IDs are non-positive, or an embedding is missing.

#### Set / update an item embedding

Stores or updates a movie embedding in Redis (default TTL 24 h; `ttl=0` for no expiry).

> The vector **must be 6-dimensional** — it has to match the seed embedding dimension so the ANN index can hash it. A mismatched dimension returns `400 {"error":"vector dimension mismatch: expected 6, got 3"}`. Pass it either as the request body (space-separated) or as the `vec` query param (`+` = space). Do **not** use `--data-urlencode "vec=..."` — that sends a `vec=` prefix and percent-encodes the spaces, which the parser can't read.

```bash
# Raw plain-text body (just the numbers)
curl -X POST "http://localhost:6010/setembedding?movieId=4" \
  -H "Content-Type: text/plain" --data-binary "0.2 0.2 0.6 0.0 0.0 0.0"

# Vector as a query param (+ = space)
curl -X POST "http://localhost:6010/setembedding?movieId=5&vec=0.1+0.3+0.6+0.0+0.0+0.0"

# Query param with custom TTL (seconds)
curl -X POST "http://localhost:6010/setembedding?movieId=6&ttl=3600&vec=0.5+0.5+0.0+0.0+0.0+0.0"
# {"ok":true,"movieId":6,"dim":6,"ttl":3600}
```

#### Set / update a user embedding

Stores or updates a user embedding in Redis (`u2vEmb:<userId>`). Same calling conventions as the item endpoint; the vector must also be **6-dimensional** to match the seed embeddings:

```bash
# Raw plain-text body (just the numbers)
curl -X POST "http://localhost:6010/setuserembedding?userId=123" \
  -H "Content-Type: text/plain" --data-binary "0.1 0.5 0.4 0.0 0.0 0.0"

# Query param with custom TTL (seconds)
curl -X POST "http://localhost:6010/setuserembedding?userId=123&ttl=3600&vec=0.1+0.5+0.4+0.0+0.0+0.0"
# {"ok":true,"userId":123,"dim":6,"ttl":3600}
```

---

### Shared recall core

Both serving ports run the **same** `MultiChannelRecallService` — only their per-port wiring differs. That wiring is bundled in a `RecallConfig` built once at startup and handed to `MultiChannelRecallService.from(config)`:

| `RecallConfig` field | Port 6010 (`RecSysServer`) | Port 7010 (`OnlinePredictionServer`) |
|---|---|---|
| `channels` | embedding, trending, **genre_history**, popularity, cold_start | embedding, **online_recent_history**, trending, popularity, cold_start |
| `quotaPolicy` | `QuotaPolicy.defaultMovie()` | `QuotaPolicy.defaultOnline()` |
| `channelTimeoutMs` | `RECALL_CHANNEL_TIMEOUT_MS` (default `200`) | `RECALL_CHANNEL_TIMEOUT_MS` (default `200`) |
| `healthMonitor` | `ChannelHealthMonitor` (per-channel backoff) | `ChannelHealthMonitor` |
| `userEmbeddingStore` | `u2vEmb` Redis store + heap cache | `u2vEmb` store wrapped in `LogicalExpiryEmbeddingCache` |

**How a request flows through it:**

1. **Classify** — probe the user embedding (cache → Redis) to label the user **warm** (has an embedding) or **cold** (none). A malformed/blank `userId` defaults to the cold quota.
2. **Fan out** — run every channel in parallel on a bounded thread pool. Each channel has `RECALL_CHANNEL_TIMEOUT_MS` to respond; a timeout/error returns empty and trips `ChannelHealthMonitor` backoff so a sick channel is skipped on subsequent requests until it recovers.
3. **Quota merge (two-phase)** — phase 1 fills each channel's quota by score; phase 2 gap-fills any shortfall (a backed-off channel's unused slots) from the remaining pool. Already-excluded items (`excludedItemIds`) are dropped.

The per-channel timeout is the one knob shared across both ports — set `RECALL_CHANNEL_TIMEOUT_MS=<ms>` once to tune 6010 and 7010 together (unset → 200 ms, unchanged).

`QuotaPolicy` encodes each port's warm/cold quota as ordered fraction maps plus a *residual* channel that absorbs leftover slots; the [6010](#port-6010--catalog--recommendation-serving) and [7010](#port-7010--online-prediction-server-feature-store) tables above show the resolved percentages.

### Port 7010 — Online Prediction Server (Feature Store)

Real-time serving built on the **same shared `MultiChannelRecallService`** as port 6010, plus a feature store for the per-user signals written by the Flink job. `OnlineFeatureStore` reads recent watch history (`user:<id>:recent_movies`) and `ShardedTopKStore` reads windowed trending items (`topk:<window>:s0..s3`) directly from Redis. The recommendation endpoint runs **five recall channels in parallel** — embedding ANN, recent-history similarity, trending, popularity, and cold-start — each on a bounded thread pool with a per-channel timeout (`RECALL_CHANNEL_TIMEOUT_MS`, default 200 ms) and `ChannelHealthMonitor` backoff, then re-ranks the merge with `OnlineLearner`. The feature snapshot endpoint exposes the raw Redis view for debugging. Responses populate once the Flink job is writing to Redis; without it the lists are empty but the API is healthy.

Per request the user is classified **warm/cold** from their (cache-backed) embedding, then channels are merged with the **quota-aware two-phase merge** under `QuotaPolicy.defaultOnline()`:

| Channel | Warm quota | Cold quota |
|---|---:|---:|
| embedding | 50% | 0% |
| online_recent_history | 25% | 10% |
| trending | 15% | 20% |
| popularity | 10% | 20% |
| cold_start | 0% | 50% |

(The percentages that aren't a fixed fraction come from a *residual* channel that absorbs the remaining slots — `popularity` for warm, `online_recent_history` for cold.) This is the same merge engine as 6010, just a different channel set and quota — see [Shared recall core](#shared-recall-core).

#### Real-time recommendations

Runs the five-channel recall, re-ranks with `OnlineLearner`, excludes recent watches, and returns the top-`k`. The response also carries the recent-history and per-`window` trending snapshots the UI renders alongside the recommendations.

```bash
curl "http://localhost:7010/online/recommendation?userId=123"
curl "http://localhost:7010/online/recommendation?userId=123&window=last_day&k=10"
curl "http://localhost:7010/online/recommendation?userId=124&window=last_month&k=5"
```

| Param | Required | Default | Values |
|---|---|---|---|
| `userId` | yes | — | any int |
| `k` | no | 5 | 1–20 |
| `window` | no | `last_hour` | `last_hour`, `last_day`, `last_month` |

```json
{
  "user": {"userId": 123, "name": "Alice"},
  "window": "last_hour",
  "strategy": "multichannel",
  "recentMovies": [
    {"id": 4, "title": "The Matrix", "year": 1999, "genres": ["Sci-Fi", "Action"]}
  ],
  "trendingMovies": [
    {"id": 11, "title": "Interstellar", "year": 2014, "genres": ["Sci-Fi", "Drama"]}
  ],
  "recommendations": [
    {"id": 4, "title": "The Matrix", "year": 1999, "genres": ["Sci-Fi", "Action"]}
  ]
}
```

`strategy` is always `"multichannel"`. The `window` parameter only affects the `trendingMovies` snapshot in the response — the recall channels themselves use fixed windows (`last_hour`, `last_day`). Returns `404` if `userId` is not found; `429` (with a `Retry-After` header) when the load shedder is active.

> Like 6010, this endpoint 404s on unknown users, and warm vs. cold changes the **recall mix**, not the `strategy` string. Seeded users **123–127** have embeddings, so embedding ANN leads (50% of warm slots); the built-in cold user **200 ("New User")** has no embedding, so `cold_start` / `trending` / `popularity` lead:

```bash
# Warm user — embedding-led
curl "http://localhost:7010/online/recommendation?userId=123&k=10"

# Built-in COLD user (200) — cold_start / trending / popularity lead
curl "http://localhost:7010/online/recommendation?userId=200&window=last_hour&k=5"
# {... "strategy":"multichannel", "recommendations":[ ... ]}
```

#### Recommendations v2 — cursor pagination (`/v2/recommend`)

`POST /v2/recommend` drives the same 7010 multichannel recall through the shared recall → rank → hydrate → paginate pipeline (`OnlineBlendingPipeline`), returning a cursor for stable, million-scale pagination. Same JSON `RecommendationQuery` body and cursor semantics as 6010's `/v2/recommend`.

```bash
curl -X POST "http://localhost:7010/v2/recommend" \
  -H "Content-Type: application/json" \
  -d '{"userId":"123","limit":10,"excludedItemIds":["1","2"]}'
```

#### Feature snapshot

Returns the raw Redis feature view for a user — useful for debugging what signals are available:

```bash
curl "http://localhost:7010/online/features?userId=123"
curl "http://localhost:7010/online/features?userId=123&window=last_hour"
```

```json
{
  "user": {"userId": 123, "name": "Alice"},
  "window": "last_hour",
  "recentMovies": [
    {"id": 4, "title": "The Matrix", "year": 1999, "genres": ["Sci-Fi", "Action"]}
  ],
  "trendingMovies": [
    {"id": 11, "title": "Interstellar", "year": 2014, "genres": ["Sci-Fi", "Drama"]}
  ]
}
```

#### Ops and metrics

Returns latency percentiles, per-strategy counters, load-shedder state, rate-limiter state, capacity targets, and async-event queue stats in one payload:

```bash
curl "http://localhost:7010/online/ops"
```

```json
{
  "servedAt": "2026-06-03T12:00:00Z",
  "metrics": {
    "totalRequests": 42, "successCount": 41, "failureCount": 1, "rejectedCount": 0,
    "recentAvgLatencyMs": 22.5, "recentFailureRate": 0.0, "recentRejectedRate": 0.0, "qps": 0.7,
    "p50Ms": 20, "p95Ms": 45, "p99Ms": 80, "strategies": {"multichannel": {...}}
  },
  "load": {
    "inFlightRequests": 0, "maxConcurrentRequests": 64, "utilization": 0.0, "drainUtilization": 0.9,
    "acceptedRequests": 42, "rejectedRequests": 0, "suggestedWeight": 100, "retryAfterSeconds": 0
  },
  "rateLimit": {"enabled": false, "limit": 0, "windowSeconds": 1, "circuitState": "CLOSED"},
  "capacity": {
    "targetDau": 2000000, "peakQps": 8000, "peakTps": 12000,
    "observedQps": 0.7, "qpsUtilization": 0.0001, "headroomQps": 7999.3,
    "overloaded": false, "peakShaving": "none"
  },
  "events": {"queueSize": 0, "published": 0, "dropped": 0, "drained": 0}
}
```

---

### Port 8080 — Model Serving (Spring Boot)

Spring Boot service that loads a PyTorch-exported DSSM ONNX model at startup and serves real-time inference. `ModelRuntimeProvider` manages one runtime per A/B variant; `RetrievalService` encodes the user tower via ONNX, and `RankingService` inner-product scores the user embedding against pre-loaded item embeddings to return top-K. Cold-start users (unknown to the model) are served from a pre-scored pool gated by a PostHog feature flag.

#### Submit token (optional CSRF protection)

When the submit-token service is configured, obtain a single-use token before each recommend call:

```bash
curl http://localhost:8080/api/v1/token
# {"token":"a3f9...","expiresInSeconds":30}
```

Pass it as `X-Submit-Token: <token>` on the subsequent `POST /api/v1/recommend`. The header is optional — if the token service is disabled (default) requests proceed without it.

#### Recommend

Runs ONNX inference to rank candidates for a user; returns `abTestVariant` so outcomes can be attributed to the correct experiment bucket:

```bash
curl -X POST http://localhost:8080/api/v1/recommend \
  -H "Content-Type: application/json" \
  -d '{"userId": "123", "k": 5}'

# Exclude already-seen items
curl -X POST http://localhost:8080/api/v1/recommend \
  -H "Content-Type: application/json" \
  -d '{"userId": "123", "k": 10, "excludeItemIds": ["2", "3"]}'
```

```json
{
  "userId": "123",
  "modelVersion": "demo-model-ratings-v1",
  "abTestVariant": "training",
  "recommendations": [
    {"itemId": "1", "score": 0.9997},
    {"itemId": "3", "score": 0.7100}
  ]
}
```

The response includes `X-Capacity-Weight: <0–100>` so load balancers can adjust routing weight in real time.

| Field | Type | Required | Constraints |
|---|---|---|---|
| `userId` | string | yes | non-blank, max 50 chars |
| `k` | integer | no | 1–100, default `5` |
| `excludeItemIds` | string[] | no | max 500 entries; each ID non-blank, max 50 chars |

**Cold-start (users not in the model vocabulary).** When `coldStartEnabled` is on (default) and the `userId` is unknown to the runtime, the request is served from a shared, pre-scored cold-start pool (per A/B variant + model version) instead of per-user inference — capped at `coldStartMaxK` (default 100) and cached for `coldStartTtlSeconds` (default 3600). The call shape is identical; only the candidate source differs:

```bash
# Unknown user → served from the shared cold-start pool, same response shape
curl -X POST http://localhost:8080/api/v1/recommend \
  -H "Content-Type: application/json" \
  -d '{"userId": "brand-new-user", "k": 5}'
```

Cold-start hit/miss counters are exposed at `GET /health/cache`; tune the pool via `recsys.recommendation-cache.cold-start-*` in `application.yml`.

#### Model version management

Preload a new model variant, activate it without downtime, or roll back:

```bash
# List all loaded variants
curl http://localhost:8080/api/v1/model/versions

# Warm a new variant before sending it traffic
curl -X POST http://localhost:8080/api/v1/model/versions/preload \
  -H "Content-Type: application/json" -d '{"variant": "candidate-v2"}'

# Promote the warmed variant to default traffic
curl -X POST http://localhost:8080/api/v1/model/versions/activate \
  -H "Content-Type: application/json" -d '{"variant": "candidate-v2"}'

# Roll back to the previous active variant
curl -X POST http://localhost:8080/api/v1/model/versions/rollback
```

#### A/B comparison

Compare per-variant request volume, failure rate, and latency against the control:

```bash
curl http://localhost:8080/health/ab-tests
```

```json
{
  "controlVariant": "training",
  "variants": {
    "training": {
      "variant": "training", "modelVersion": "demo-model-ratings-v1",
      "totalRequests": 120, "successCount": 118, "failureCount": 2,
      "successRate": 0.9833, "avgLatencyMs": 11.4,
      "successRateDeltaVsControl": 0.0, "avgLatencyDeltaVsControlMs": 0.0
    },
    "test": {
      "variant": "test", "modelVersion": "demo-model-ratings-v1",
      "totalRequests": 113, "successCount": 111, "failureCount": 2,
      "successRate": 0.9823, "avgLatencyMs": 12.1,
      "successRateDeltaVsControl": -0.001, "avgLatencyDeltaVsControlMs": 0.7
    }
  }
}
```

#### Health probes

| Endpoint | Returns | When to use |
|---|---|---|
| `GET /health/live` | `200` if JVM responds | Liveness — restart trigger |
| `GET /health/ready` | `200` fit for traffic; `503` overloaded | Readiness — load-balancer drain |
| `GET /health/load` | Concurrency snapshot + `suggestedWeight` | Dynamic load-balancer weight |
| `GET /health/metrics` | Rolling-window request counters and latency | Dashboards |
| `GET /health/ab-tests` | Per-variant stats vs control | A/B experiment monitoring |
| `GET /health/cache` | Result-cache hit/miss rates | Cache effectiveness |
| `GET /health/jvm` | Heap / non-heap / metaspace / thread snapshot | Memory pressure investigation |
| `GET /health/gc` | GC event histogram, STW pause stats | GC tuning and incident response |

```bash
curl http://localhost:8080/health/ready
```

```json
// 200 — healthy
{
  "status": "UP",
  "recentRequests": 42, "recentFailureRate": 0.02, "recentAvgLatencyMs": 11.4,
  "throughputPerSecond": 0.7, "inFlightRequests": 7,
  "maxConcurrentRequests": 64, "utilization": 0.109, "suggestedWeight": 89
}
// 503 — model not yet loaded
{"status": "DOWN", "reason": "model not loaded"}
// 503 — SIGTERM received, draining
{"status": "DOWN", "reason": "shutting down", "inFlightRequests": 3}
// 503 — concurrency cap reached
{"status": "DOWN", "reason": "overloaded", "inFlightRequests": 64, "maxConcurrentRequests": 64, "utilization": 1.0, "threshold": 0.9, "suggestedWeight": 0}
// 503 — rolling failure rate too high
{"status": "DOWN", "reason": "high failure rate", "recentFailureRate": 0.6, "threshold": 0.5}
// 503 — rolling latency too high
{"status": "DOWN", "reason": "high inference latency", "recentAvgLatencyMs": 2100.0, "thresholdMs": 2000.0}
```

```bash
curl http://localhost:8080/health/load
```

```json
{
  "inFlightRequests": 7, "maxConcurrentRequests": 64,
  "utilization": 0.109, "maxReadinessUtilization": 0.9,
  "acceptedRequests": 1042, "rejectedRequests": 0,
  "suggestedWeight": 89, "shuttingDown": false
}
```

```bash
curl http://localhost:8080/health/metrics
```

```json
{
  "totalRequests": 1042, "successCount": 1038, "failureCount": 4,
  "allTimeAvgLatencyMs": 55.7,
  "recentRequests": 20, "recentFailures": 0,
  "recentAvgLatencyMs": 52.3, "recentFailureRate": 0.0,
  "throughputPerSecond": 0.3
}
```

```bash
curl http://localhost:8080/health/cache
# {"enabled":true,"coldStartEnabled":true,
#  "recommendations":{"hits":820,"misses":222,"hitRate":0.787},
#  "coldStart":{"hits":5,"misses":17,"hitRate":0.227}}

curl http://localhost:8080/health/jvm
# heap/non-heap pools, thread counts, GC collector breakdown

curl http://localhost:8080/health/gc
```

```json
{
  "byType": {
    "MINOR_GC": {"events": 42, "totalPauseMs": 630, "avgPauseMs": 15.0},
    "FULL_GC":  {"events": 0,  "totalPauseMs": 0,   "avgPauseMs": 0.0}
  },
  "stwLongestPauseMs": 28,
  "stwPauseHistogram": {"<1ms":0,"1-10ms":5,"10-50ms":37,"50-200ms":0,">500ms":0},
  "evacuationFailures": 0,
  "allocationStalls": 0
}
```

Kubernetes probe config:

```yaml
livenessProbe:
  httpGet: { path: /health/live, port: 8080 }
  initialDelaySeconds: 30
  periodSeconds: 10
readinessProbe:
  httpGet: { path: /health/ready, port: 8080 }
  initialDelaySeconds: 15
  periodSeconds: 5
```

---

### Port 8010 — API Gateway

Overall API gateway and single entry point for all three upstream services (6010, 7010, 8080). Strips the route prefix, proxies to the correct backend, and enforces per-route circuit breakers (`RouteCircuitBreaker`), token-bucket rate limiting (`GatewayRateLimiter`), and optional API-key auth. Also includes a dedicated LLM proxy with token budgets, SSE streaming passthrough, and SHA-256 response caching.

| Gateway prefix | Backend | Direct equivalent |
|---|---|---|
| `/api/users` | `:6010` | `GET /user?userId=123` |
| `/api/movies` | `:6010` | `GET /movie?id=1` |
| `/api/features` | `:7010` | `GET /online/features?userId=123` |
| `/api/retrieval` | `:8080` | `POST /api/v1/recommend` |
| `/api/ranking` | `:8080` | `POST /api/v1/recommend` |
| `/api/agents` | `:8080` | agent workflow (placeholder) |
| `/api/observability` | `:8080` | `GET /health/ready` |
| `/api/catalog` † | `:6010` | `GET /item?id=1`, `GET /getrecommendation?...` |
| `/api/online` † | `:7010` | `GET /online/recommendation?userId=123` |
| `/api/model` † | `:8080` | `POST /api/v1/recommend` |
| `/api/llm` | `:11434` | opt-in — set `LLM_SERVICE_URL` |
| `/api/explanations` | `:11434` | opt-in — set `LLM_EXPLANATION_SERVICE_URL` |

† Backward-compatible routes kept for existing clients. See [Microservice Gateway](#microservice-gateway) for full route details, env var overrides, and circuit-breaker configuration.

#### Smoke tests

```bash
# Stack health — shows status of all registered services
curl http://localhost:8010/health

# User lookup via gateway
curl "http://localhost:8010/api/users/user?userId=123"
# {"userId":123,"name":"Alice"}

# Movie lookup via gateway
curl "http://localhost:8010/api/movies/movie?id=1"
curl "http://localhost:8010/api/catalog/item?id=1"
# {"id":1,"title":"Inception","year":2010,"genres":["Sci-Fi","Thriller"]}

# Offline recommendations via gateway (all five recall channels run in parallel)
curl "http://localhost:8010/api/catalog/getrecommendation?userId=123&k=5"
curl "http://localhost:8010/api/catalog/getrecommendation?userId=123&k=10"

# Similar movies via gateway
curl "http://localhost:8010/api/catalog/similar?movieId=1&k=5"

# Online (real-time) recommendations via gateway
curl "http://localhost:8010/api/online/online/recommendation?userId=123&window=last_hour&k=5"
curl "http://localhost:8010/api/online/online/features?userId=123"

# ONNX model recommendations via gateway
curl -X POST "http://localhost:8010/api/model/api/v1/recommend" \
  -H "Content-Type: application/json" \
  -d '{"userId":"123","k":5}'

# LLM (requires Ollama + LLM_SERVICE_URL set)
curl -X POST "http://localhost:8010/api/llm/api/generate" \
  -H "Content-Type: application/json" \
  -d '{"model":"llama3","prompt":"Summarize this movie: Inception","max_tokens":200}'
```

> **Hostname note:** `localhost:8010` is for local dev. In Kubernetes use the ClusterIP name (e.g. `recsys-api-gateway:8010`); on EKS with Cloud Map use `api-gateway.recsys.internal:8010`.

---

## Microservice Gateway

`MicroserviceGatewayServer` is the single public edge for the local microservice topology. It strips the route prefix and proxies to the right backend, while adding circuit breaking, token-bucket rate limiting, API-key auth, and a dedicated LLM proxy with token budgets and response caching. All four services sit behind it — clients only need to know one hostname and port.

### Route table

Domain-facing routes (preferred — each has its own env var and circuit breaker):

| Route name | Gateway prefix | Backend port | Notes |
|---|---|---:|---|
| `user-profile` | `/api/users` | `6010` | User profile lookup; override with `USER_PROFILE_SERVICE_URL` |
| `movie-metadata` | `/api/movies` | `6010` | Movie metadata lookup; override with `MOVIE_METADATA_SERVICE_URL` |
| `feature` | `/api/features` | `7010` | Online feature snapshot; override with `FEATURE_SERVICE_URL` |
| `recommendation-retrieval` | `/api/retrieval` | `8080` | ONNX-based retrieval; override with `RECOMMENDATION_RETRIEVAL_SERVICE_URL` |
| `ranking` | `/api/ranking` | `8080` | ONNX-based ranking; override with `RANKING_SERVICE_URL` |
| `agent-workflow` | `/api/agents` | `8080` | Agent workflow placeholder; override with `AGENT_WORKFLOW_SERVICE_URL` |
| `observability` | `/api/observability` | `8080` | Model health and metrics; override with `OBSERVABILITY_SERVICE_URL` |

Backward-compatible routes (kept for existing clients — same backends, different prefix):

| Route name | Gateway prefix | Backend port | Notes |
|---|---|---:|---|
| `catalog` | `/api/catalog` | `6010` | Recommendations, similar, pair prediction; override with `CATALOG_SERVICE_URL` |
| `model` | `/api/model` | `8080` | Full recommend endpoint; override with `MODEL_SERVICE_URL` |
| `online` | `/api/online` | `7010` | Real-time recommendations + ops; override with `ONLINE_SERVICE_URL` |

Opt-in routes (registered only when the env var is set):

| Route name | Gateway prefix | Env var |
|---|---|---|
| `llm` | `/api/llm` | `LLM_SERVICE_URL` |
| `llm-explanation` | `/api/explanations` | `LLM_EXPLANATION_SERVICE_URL` |

### Start

```bash
# Start all services + gateway
docker compose -f docker-compose.streaming.yml up -d
sh scripts/run-microservices-local.sh

# Start only the gateway (when backends are already running)
sh scripts/run-with-jvm-tuning.sh api-gateway -- \
  mvn exec:java -Dexec.mainClass=com.recsys.microservice.MicroserviceGatewayServer

# Enable LLM routes (requires Ollama)
brew install ollama && ollama serve &
export LLM_SERVICE_URL=http://localhost:11434
sh scripts/run-microservices-local.sh
```

### Health aggregation

`GET /health` pings every registered downstream service and returns an aggregated status. `DEGRADED` means at least one service is down; individual `status` fields show which:

```bash
curl http://localhost:8010/health
```

```json
{
  "status": "UP",
  "checkedAt": "2026-06-12T00:00:00Z",
  "services": {
    "user-profile":  {"status":"UP","prefix":"/api/users","baseUrl":"http://localhost:6010","healthUrl":"http://localhost:6010/health","statusCode":200,"latencyMs":2,"circuitState":"CLOSED"},
    "catalog":       {"status":"UP","prefix":"/api/catalog","baseUrl":"http://localhost:6010","healthUrl":"http://localhost:6010/health","statusCode":200,"latencyMs":1,"circuitState":"CLOSED"},
    "model":         {"status":"UP","prefix":"/api/model","baseUrl":"http://localhost:8080","healthUrl":"http://localhost:8080/health/ready","statusCode":200,"latencyMs":3,"circuitState":"CLOSED"},
    "online":        {"status":"DOWN","prefix":"/api/online","baseUrl":"http://localhost:7010","healthUrl":"http://localhost:7010/health","statusCode":0,"latencyMs":500,"circuitState":"OPEN","error":"Connection refused"}
  }
}
```

```bash
# Status-only summary
curl http://localhost:8010/health | jq '{status, services: (.services | to_entries | map({(.key): .value.status}) | add)}'
# {"status":"DEGRADED","services":{"user-profile":"UP","catalog":"UP","model":"UP","online":"DOWN",...}}
```

### Circuit breaker (`RouteCircuitBreaker`)

Each route has an independent circuit breaker. After `GATEWAY_CB_FAILURE_THRESHOLD` consecutive failures the circuit opens and fast-fails with `503` during the cooldown window — protecting downstream services from traffic during an outage.

```bash
# Circuit state is visible in each service's health entry
curl http://localhost:8010/health | jq '.services["model"].circuitState'
# "CLOSED"   ← healthy
# "OPEN"     ← tripped; fast-failing
# "HALF_OPEN"← testing recovery
```

### Rate limiting (`GatewayRateLimiter`)

Token-bucket rate limiting per route. Each bucket refills at `GATEWAY_RATE_LIMIT_RPS` tokens/second with a `GATEWAY_RATE_LIMIT_BURST` burst. Excess requests get `429 Too Many Requests`.

Per-route overrides use the route name uppercased with hyphens replaced by underscores: e.g., route `recommendation-retrieval` → `GATEWAY_RATE_LIMIT_RECOMMENDATION_RETRIEVAL_RPS`.

```bash
# Enable global rate limit (5 req/s, burst 10)
GATEWAY_RATE_LIMIT_RPS=5 GATEWAY_RATE_LIMIT_BURST=10 \
  mvn exec:java -Dexec.mainClass=com.recsys.microservice.MicroserviceGatewayServer

# Per-route overrides (route name → UPPER_SNAKE suffix)
GATEWAY_RATE_LIMIT_MODEL_RPS=2 GATEWAY_RATE_LIMIT_MODEL_BURST=3 \
  mvn exec:java -Dexec.mainClass=com.recsys.microservice.MicroserviceGatewayServer
GATEWAY_RATE_LIMIT_RECOMMENDATION_RETRIEVAL_RPS=10 GATEWAY_RATE_LIMIT_RECOMMENDATION_RETRIEVAL_BURST=20 \
  mvn exec:java -Dexec.mainClass=com.recsys.microservice.MicroserviceGatewayServer
```

### API-key authentication (`GatewayAuthenticator`)

When `GATEWAY_API_KEYS` is set, requests must send a valid key via `X-API-Key` or `Authorization: Bearer`. Public paths (default: `/health`) bypass auth.

```bash
GATEWAY_API_KEYS=secret-key-1,secret-key-2 \
  mvn exec:java -Dexec.mainClass=com.recsys.microservice.MicroserviceGatewayServer

# Authenticated request
curl -H "X-API-Key: secret-key-1" http://localhost:8010/api/catalog/item?id=1
curl -H "Authorization: Bearer secret-key-1" http://localhost:8010/api/catalog/item?id=1

# Health always works without auth
curl http://localhost:8010/health
```

### LLM proxy (`LlmProxyServlet`)

LLM routes use a dedicated `HttpClient` with a longer timeout (default 120 s). The proxy handles SSE streaming passthrough, retry-on-429, token-count-aware rate limiting, and SHA-256 response caching.

```bash
# Non-streaming
curl -X POST http://localhost:8010/api/llm/api/generate \
  -H "Content-Type: application/json" \
  -d '{"model":"llama3","prompt":"Summarize: Inception","max_tokens":100}'

# Streaming (SSE)
curl -X POST http://localhost:8010/api/llm/api/generate \
  -H "Content-Type: application/json" \
  -d '{"model":"llama3","prompt":"Summarize: Inception","stream":true}'

# Cache hit on repeated request returns X-Cache: HIT
curl -v -X POST http://localhost:8010/api/llm/api/generate \
  -H "Content-Type: application/json" \
  -d '{"model":"llama3","prompt":"Summarize: Inception","max_tokens":100}' 2>&1 | grep X-Cache
```

---

## Configuration

### Catalog / Recommendation Serving (port 6010)

| Env var | Default | Purpose |
|---|---:|---|
| `PORT` | `6010` | Server port |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `LOCAL_EMBEDDING_CACHE_MAX_ENTRIES` | `100000` | Max embeddings in JVM LRU cache |
| `RECSYS_VECTOR_BACKEND` | `lsh` | `lsh` (approximate) or `exact` |
| `RECALL_CHANNEL_TIMEOUT_MS` | `200` | Per-channel recall timeout (shared by both serving ports) |

### Online Prediction Server (port 7010)

| Env var | Default | Purpose |
|---|---:|---|
| `ONLINE_DEMO_PORT` | `7010` | Server port |
| `ONLINE_REQUEST_TIMEOUT_MS` | `500` | End-to-end Armeria request deadline |
| `RECALL_CHANNEL_TIMEOUT_MS` | `200` | Per-channel recall timeout for the shared `MultiChannelRecallService` (also tunes 6010) |
| `ONLINE_USER_EMB_SOFT_TTL_SECONDS` | `30` | Soft-TTL of the `LogicalExpiryEmbeddingCache` for user embeddings (cold/warm classification) |
| `ONLINE_TOPK_CACHE_TTL_MS` | `2000` | Local hot-key cache TTL for the sharded trending store |
| `ONLINE_MAX_CONCURRENT_REQUESTS` | `64` | Pre-queue in-flight cap before `429`; tune against Redis pool and load tests |
| `ONLINE_DRAIN_UTILIZATION` | `0.90` | Utilization where `/health/ready` → `503` for drain |
| `ONLINE_REDIS_RATE_LIMIT_QPS` | `0` | Cross-instance Redis rate limit; `0` = disabled |
| `ONLINE_FEATURE_CACHE_MAX_USERS` | `10000` | Max Redis feature keys in JVM cache |
| `ONLINE_FEATURE_STALE_TTL_MS` | `60000` | Maximum stale recent-history age served during Redis errors |
| `ONLINE_TOPK_STALE_TTL_MS` | `60000` | Maximum stale Top-K age served during Redis errors |
| `ONLINE_FEATURE_REDIS_MGET_BATCH_SIZE` | `500` | Redis `MGET` batch size |
| `ONLINE_METRICS_WINDOW_SECONDS` | `60` | Rolling metrics window |
| `ONLINE_TARGET_DAU` | `2000000` | Capacity sizing assumption |
| `ONLINE_PEAK_QPS` | `8000` | Peak read-QPS target |
| `REDIS_POOL_MAX_TOTAL` | `50` | Maximum Redis connections per process |
| `REDIS_POOL_MAX_WAIT_MS` | `250` | Fail-fast wait when the Redis pool is exhausted |
| `REDIS_TIMEOUT_MS` | `2000` | Redis connect/socket timeout; set below the request deadline for online serving |

### API Gateway (port 8010)

| Env var | Default | Purpose |
|---|---:|---|
| `GATEWAY_PORT` | `8010` | Gateway port |
| `GATEWAY_TIMEOUT_MS` | `3000` | Upstream connect/request timeout |
| `LLM_SERVICE_URL` | _(unset)_ | Enables `/api/llm` — set to Ollama URL to activate |
| `LLM_EXPLANATION_SERVICE_URL` | _(unset)_ | Enables `/api/explanations` |
| `GATEWAY_API_KEYS` | _(unset)_ | Comma-separated API keys; enables `X-API-Key` / `Authorization: Bearer` auth |
| `GATEWAY_PUBLIC_PATHS` | `/health` | Paths that bypass API-key auth |
| `GATEWAY_RATE_LIMIT_RPS` | `0` | Global token-bucket rate; `0` = disabled |
| `GATEWAY_RATE_LIMIT_<ROUTE>_RPS` | _(unset)_ | Per-route override, e.g. `GATEWAY_RATE_LIMIT_MODEL_RPS` |

### Model Serving — Spring Boot (port 8080)

| Env var / property | Default | Purpose |
|---|---:|---|
| `SERVER_PORT` | `8080` | Server port |
| `RECSYS_MODEL_ARTIFACTS_DIR` | _(classpath)_ | External artifact directory; resolves `<dir>/<variant>/...` |
| `RECSYS_MODEL_ITEM_EMBEDDINGS_SOURCE` | `classpath` | `classpath` or `redis` |
| `RECSYS_MODEL_REDIS_ITEM_EMBEDDING_PREFIX` | `i2vEmb` | Redis key prefix for item embeddings |
| `recsys.health.max-failure-rate` | `0.5` | Failure rate above which `/health/ready` → `503` |
| `recsys.health.max-avg-latency-ms` | `2000` | Avg latency above which `/health/ready` → `503` |
| `recsys.health.max-concurrent-requests` | `64` | Per-instance in-flight cap |
| `MYSQL_ENABLED` | `false` | Optional MySQL switch |
| `FEATURE_FLAG_ENVIRONMENT_PREFIX` | `FEATURE_FLAG_` | Prefix for environment-backed feature flags |
| `POSTHOG_FEATURE_FLAGS_ENABLED` | `false` | Enables PostHog feature-flag evaluation |
| `POSTHOG_PROJECT_API_KEY` | _(unset)_ | PostHog project API key used by `/decide` |
| `POSTHOG_HOST` | `https://us.i.posthog.com` | PostHog host |
| `POSTHOG_FEATURE_FLAGS_TIMEOUT` | `2s` | PostHog request timeout |

---

## Project Layout

```text
src/main/java/com/recsys/
├── domain/         Shared value objects: Movie, User, Rating, RecommendationQuery, MovieCandidate
├── data/           DataLoader / DataManager — bundled classpath movie+user+rating data
├── infrastructure/
│   ├── redis/      RedisConnectionFactory, RedisEmbeddingStore, ShardedTopKStore
│   ├── vectordb/   CandidateGenerator, VectorIndex (LSH + exact), EmbeddingStore
│   ├── cache/      LocalEmbeddingCache, MultiLevelEmbeddingCache, HotKeyDetector
│   ├── alb/        ALB integration helpers
│   └── autoscaling/ Auto-scaling signal publishers
├── service/
│   ├── retrieval/  MultiChannelRecallService, RecallConfig, QuotaPolicy, recall channels (Embedding, Trending, GenreHistory, OnlineRecentHistory, Popularity, ColdStart)
│   ├── ranking/    Ranking pipeline
│   ├── recommendation/ Shared recall → rank → paginate → hydrate pipeline
│   ├── hydrator/   Item/user hydration
│   ├── feedback/   Feedback processing
│   └── pagination/ Cursor-based result pagination
├── serving/        Armeria servlets for port 6010 (RecSysServer)
├── online/         Online serving layer for port 7010
│   ├── serving/    OnlinePredictionServer, OnlinePredictionService, OnlineFeaturesService
│   ├── flink/      Flink job — writes history + embeddings + trending to Redis
│   ├── learner/    OnlineLearner, OnlineJoiner, ExperienceCollector, LogCollector
│   ├── ops/        OnlineLoadShedder, OnlineCapacityService, OnlineServingMetricsService, OnlineOpsService
│   ├── redis/      RedisRateLimiter, WatchdogLock
│   ├── store/      OnlineFeatureStore, ShardedRecordStore, TrendingStore
│   └── event/      AsyncEventPublisher
├── model/          Spring Boot ONNX model serving for port 8080
│   ├── controller/ RecommendationController, HealthController, VersionController
│   ├── service/    RecommendationService, ModelRuntimeProvider, ABTestService, LoadShedder, GcEventTracker
│   ├── request/    RecommendRequest, ModelVersionRequest
│   ├── response/   RecommendResponse, ModelVersionResponse, SubmitTokenResponse
│   ├── dto/        ScoredItem
│   ├── exception/  RateLimitExceededException, ServiceOverloadedException
│   └── vo/         Value objects
├── microservice/   API gateway: routing, circuit breakers, rate limiting, LLM proxy
├── config/         Spring @ConfigurationProperties (ABTestConfig, HealthProperties, etc.)
├── featureflags/   FeatureFlagService, PostHog integration, environment-backed flags
├── annotation/     Custom annotations
├── mysql/          Thin JDBC wrapper (opt-in)
├── saga/           AWS Step Functions saga orchestration (SagaOrchestrator, TccSagaOrchestrator)
└── training/
    └── rulebased/  Spark Word2Vec item embedding job (ItemEmbeddingJob)

src/main/resources/
├── dssm_model.onnx           Bundled DSSM demo model
├── dssm_metrics.json         Bundled model metrics
├── artifacts/model/          Bundled feature_config.json + model artifacts (training/, test/ variants)
├── artifacts/pyspark/        PySpark job resources
├── application.yml           Spring Boot config (A/B test, health thresholds, Redis, feature flags)
└── logback-spring.xml        Logging config

k8s/base/     Kustomize base manifests for all four services
k8s/eks/      EKS overlays (IRSA, Cloud Map, ECR image)
```

---

## Model Serving Demo

The Spring Boot service on port `8080` runs a PyTorch-exported DSSM ONNX model. All variants are pre-warmed at startup so no user pays cold-start cost.

Start with bundled classpath artifacts:

```bash
sh scripts/run-with-jvm-tuning.sh model-serving -- mvn spring-boot:run
```

Start with external artifacts from your modeling pipeline:

```bash
RECSYS_MODEL_ARTIFACTS_DIR=/path/to/model/artifacts \
  sh scripts/run-with-jvm-tuning.sh model-serving -- mvn spring-boot:run
```

Start with Redis-backed item embeddings (stripped-embedding deployment):

```bash
RECSYS_MODEL_ITEM_EMBEDDINGS_SOURCE=redis \
RECSYS_MODEL_REDIS_ITEM_EMBEDDING_PREFIX=i2vEmb \
RECSYS_MODEL_ARTIFACTS_DIR=/path/to/model/artifacts \
  sh scripts/run-with-jvm-tuning.sh model-serving -- mvn spring-boot:run
```

### Expected artifact layout

```text
<artifacts-dir>/
├── training/
│   ├── feature_config.json   user vocab, feature metadata
│   ├── dssm_model.onnx       exported DSSM model
│   └── item_embeddings.json  optional pretrained item embeddings
└── test/
    ├── feature_config.json
    └── dssm_model.onnx
```

### Generate item embeddings with Spark Word2Vec

Train from bundled ratings and write to file:

```bash
mvn -Poffline-embedding exec:java \
  -Dexec.mainClass=com.recsys.training.rulebased.ItemEmbeddingJob \
  -Dexec.args="--output=output/item_embeddings"
```

Or write directly to Redis (`i2vEmb:<movieId>`):

```bash
mvn -Poffline-embedding exec:java \
  -Dexec.mainClass=com.recsys.training.rulebased.ItemEmbeddingJob \
  -Dexec.args="--output=output/item_embeddings --save-to-redis=true --redis-host=localhost --redis-port=6379"
```

Options: `--vector-size=16`, `--window-size=5`, `--min-count=1`, `--max-iter=10`, `--step-size=0.025`, `--min-rating=3.5`, `--redis-key-prefix=i2vEmb`, `--redis-ttl=86400`.

---

## A/B Testing

`ABTestService` assigns users to variants deterministically by hashing `userId:layerName` modulo `trafficSplitNumber`. The assigned variant is returned in every response so experiment outcomes can be attributed to the right bucket.

**Bucketing:**

```
bucket = hash(userId + ":" + layerName) % trafficSplitNumber
bucket == 0  →  bucketAVariant  (20%)
bucket == 1  →  bucketBVariant  (20%)
otherwise    →  defaultVariant  (60% control)
```

**Layer isolation:** within the same layer, a user is in exactly one bucket. Across different layers, assignments are independent — useful for running multiple experiments simultaneously.

Enable via `application.yml`:

```yaml
recsys:
  ab-test:
    enabled: true
    layer-name: model-arch-test-2024q2
    traffic-split-number: 5
    bucket-a-variant: test
    bucket-b-variant: training
    default-variant: training
```

Or env vars:

```bash
RECSYS_AB_TEST_ENABLED=true \
RECSYS_AB_TEST_LAYER_NAME=model-arch-test-2024q2 \
  sh scripts/run-with-jvm-tuning.sh model-serving -- mvn spring-boot:run
```

Check which variant a specific user gets:

```bash
curl -X POST http://localhost:8080/api/v1/recommend \
  -H "Content-Type: application/json" \
  -d '{"userId":"42","k":1}'
# "abTestVariant": "test"   ← bucket assignment is in every response
```

Compare live variant performance:

```bash
curl http://localhost:8080/health/ab-tests
```

---

## Feature Flags

`FeatureFlagService` provides boolean feature flags with safe per-flag defaults. Providers are evaluated in order:

1. Environment overrides.
2. PostHog, when enabled and configured.
3. The flag's default value.

Environment flags normalize the flag key to uppercase snake case and prepend `FEATURE_FLAG_` by default:

```bash
# Enables FeatureFlag.disabledByDefault("new-ranking")
FEATURE_FLAG_NEW_RANKING=true mvn spring-boot:run

# Disables FeatureFlag.enabledByDefault("new-ranking")
FEATURE_FLAG_NEW_RANKING=false mvn spring-boot:run
```

Accepted truthy values are `true`, `1`, `yes`, `on`, and `enabled`; accepted falsey values are `false`, `0`, `no`, `off`, and `disabled`.

Enable PostHog evaluation:

```bash
RECSYS_FEATURE_FLAGS_POST_HOG_ENABLED=true \
RECSYS_FEATURE_FLAGS_POST_HOG_API_KEY=phc_your_project_key \
RECSYS_FEATURE_FLAGS_POST_HOG_HOST=https://us.i.posthog.com \
  mvn spring-boot:run
```

PostHog evaluation requires a non-blank distinct ID:

```java
FeatureFlag flag = FeatureFlag.disabledByDefault("new-ranking");
boolean enabled = featureFlagService.isEnabled(
    flag,
    "user-123",
    Map.of("plan", "pro")
);
```

If PostHog or an environment value cannot resolve a flag, callers get the default declared on the `FeatureFlag`, so failure mode stays explicit at the call site.

---

## Testing

Run all unit and integration tests (load tests excluded by default):

```bash
mvn test

# Run a single test class
mvn test -Dtest=RecommendationServiceTest

# Load tests only (100 requests, 10 concurrent threads, asserts P95 ≤ 2000 ms)
mvn test -DexcludedGroups="" -Dgroups=load
```

| Test class | What it covers |
|---|---|
| `ModelArtifactLocatorTest` | Classpath and external-dir resolution for model and spark artifact groups |
| `ModelArtifactServiceTest` | Loads bundled `feature_config.json`; asserts model version, vocab, item vocab |
| `ModelRuntimeProviderTest` | Loads `training` and `test` runtimes; asserts distinct model versions |
| `FeatureEncoderTest` | Known user IDs → vocab indices; unknown IDs → `__UNK__` |
| `RankingServiceTest` | Re-ordering by score, k-truncation, missing-embedding skip |
| `RetrievalServiceTest` | Top-K by inner-product, null embedding, empty candidates |
| `ABTestServiceTest` | Bucketing determinism, same-layer exclusivity, cross-layer independence |
| `RecommendationServiceTest` | Guards reject blank `userId` and out-of-range `k`; full response shape |
| `RecommendationControllerTest` | Bean-validation rejections, malformed JSON, wrong content-type → stable `ApiError` |
| `PredictionIntegrationTest` | End-to-end pipeline: ranked results, score ordering, excludeItemIds |
| `RecommendationEndToEndTest` | Full HTTP chain; verifies metrics counters and `/health/ready` state |
| `InferenceLoadTest` _(tag: load)_ | P95 ≤ 2000 ms, success rate ≥ 99% — 100 total requests, 10 concurrent threads |
| `OnlineRecentHistoryChannelTest` | Recency-boosted similar-movie recall; empty on no history / non-numeric `userId`; rank-based scores |
| `OnlineRecommendationServiceTest` | Multichannel recall + `OnlineLearner` re-rank, `strategy:"multichannel"`, empty-recall → trending fallback, recently-watched exclusion |
| `JvmMemoryMonitorTest` | Heap/non-heap positive bytes, usedFraction in [0,1], metaspace pool |
| `GcEventTrackerTest` | Zero initial counters, histogram keys, `GcType.stw`, `avgPauseMs`, destroy idempotence |
| `RedisConnectionFactoryTest` | Standalone pool, sentinel code path, `parseSentinelNodes`, `parsePort` |

---

## Redis Test Data

Seed trending data so the trending channel (port 6010) and online recommendations (port 7010) return results:

```bash
# Seed last_hour trending (legacy key — ShardedTopKStore falls back to this when shards are empty)
docker exec -it redis-primary redis-cli DEL topk:last_hour
docker exec -it redis-primary redis-cli ZADD topk:last_hour \
  2 11 1 1 1 2 1 3 1 4 1 5 1 7 1 8 1 9 1 12

# Verify
docker exec -it redis-primary redis-cli ZREVRANGE topk:last_hour 0 9 WITHSCORES
```

Inspect seeded embeddings:

```bash
docker exec -it redis-primary redis-cli SCAN 0 MATCH 'i2vEmb:*' COUNT 20
docker exec -it redis-primary redis-cli GET i2vEmb:1
```

Then try a trending recommendation:

```bash
curl "http://localhost:6010/getrecommendation?userId=123&k=5"
curl "http://localhost:7010/online/recommendation?userId=123&window=last_hour"
```

Redis key conventions:

| Key | Purpose |
|---|---|
| `i2vEmb:<id>` | Item (movie) embedding |
| `u2vEmb:<id>` | User embedding |
| `topk:<window>:s{0-3}` | Trending sorted set shards (physical keys written by Flink; `topk:<window>` is the legacy fallback) |
| `user:<id>:recent_movies` | Per-user recent watch history (written by Flink) |
| `feature:user:<id>:embedding` | User embedding from online Flink job |

---

## Online Serving

The Kafka/Flink/Redis pipeline provides the real-time signals consumed by port `7010`. See [streaming/online-serving/README.md](streaming/online-serving/README.md) for full setup.

Quick start (loads sample features without Flink):

```bash
docker compose -f streaming/online-serving/docker-compose.yml up -d
sh streaming/online-serving/scripts/load_online_features.sh

sh scripts/run-with-jvm-tuning.sh online-serving -- \
  mvn exec:java -Dexec.mainClass=com.recsys.online.serving.OnlinePredictionServer

# Verify
curl "http://localhost:7010/online/recommendation?userId=123&window=last_hour&k=5"
curl "http://localhost:7010/online/ops"
```

With full Flink pipeline (produces live events to Kafka):

```bash
sh streaming/online-serving/scripts/produce_movie_events.sh
# Flink job writes to Redis → online serving sees live history and trending
```

| Component | Responsibility |
|---|---|
| `LogCollector` | Validates and emits Kafka-ready behavior logs (view, watch, click, like, rating, dwell, search, order, purchase) |
| `OnlineJoiner` | Joins behavior logs with user/item/context features; produces labeled samples |
| `ExperienceCollector` | Groups samples by request into ranked list experiences for listwise training |
| `OnlineLearner` | Updates per-item bias parameters from list experiences; persists to Redis |
| `OnlineFeatureStreamingJob` | Flink job: reads Kafka, writes history + embeddings + trending to Redis |
| `OnlineRecentHistoryChannel` | Recall channel: movies similar to the user's recent watches, recency-boosted (7010's behavioral signal) |
| `OnlineRecommendationService` | Recall (shared `MultiChannelRecallService`) → `OnlineLearner` re-rank → recent/trending snapshot; `strategy:"multichannel"` |
| `OnlineLoadShedder` | Caps in-flight requests; returns `429` + `Retry-After` when overloaded |
| `OnlineCapacityService` | Exposes DAU/QPS/TPS targets, `headroomQps`, and `overloaded` flag |

---

## Sharded Record Store

`ShardedRecordStore` distributes event, feature, and log records across N Redis shards using consistent hashing. Each write fans out to an HSET (full record) + ZADD (device index for per-device reads) + XADD (shard stream for ordered replay). The number of shards is controlled by `SHARDED_RECORD_SHARD_COUNT` (default `2`).

The HTTP façade is mounted at `/shards/` on port `7010`.

#### Write a record

```bash
# EVENT (click, watch, rating, dwell, search)
curl -X POST http://localhost:7010/shards/records \
  -H "Content-Type: application/json" \
  -d '{"deviceId":"user:123","type":"EVENT","eventId":"click-001","payload":"{\"movieId\":7}"}'
# {"seqNum":1,"shardIndex":0,"status":"OK"}

# FEATURE (Flink-written behavioral features: engagement, session data)
curl -X POST http://localhost:7010/shards/records \
  -H "Content-Type: application/json" \
  -d '{"deviceId":"user:123","type":"FEATURE","eventId":"engagement-001","payload":"{\"engagement\":0.42}"}'

# LOG (audit / debug entries)
curl -X POST http://localhost:7010/shards/records \
  -H "Content-Type: application/json" \
  -d '{"deviceId":"user:123","type":"LOG","eventId":"log-001","payload":"startup"}'
```

Duplicate `eventId` for the same device returns `"status":"DUPLICATE"` — idempotent writes are safe to retry.

#### Read records by device (cursor-paginated)

```bash
# First page (default limit 10)
curl "http://localhost:7010/shards/device?deviceId=user:123"
# {"deviceId":"user:123","cursor":"","hasMore":false,"count":3,"records":[...]}

# With explicit limit and cursor
curl "http://localhost:7010/shards/device?deviceId=user:123&limit=2"
# {"deviceId":"user:123","cursor":"3","hasMore":true,"count":2,"records":[...]}

# Next page — pass cursor value from previous response
curl "http://localhost:7010/shards/device?deviceId=user:123&limit=2&cursor=3"
```

#### Read all records on a shard (stream scan)

```bash
# Shard 0, first 20 records
curl "http://localhost:7010/shards/shard?index=0&limit=20"
# {"shardIndex":0,"cursor":"","hasMore":false,"count":2,"records":[...]}

# Shard 1 with cursor for incremental replay
curl "http://localhost:7010/shards/shard?index=1&limit=10"
```

| Param | Endpoint | Default | Notes |
|---|---|---|---|
| `deviceId` | `/shards/device` | required | Any string device/user ID |
| `limit` | both | `10` | 1–100 |
| `cursor` | both | start | Opaque string from previous response; empty string = start |
| `index` | `/shards/shard` | `0` | Shard index (0 to `SHARDED_RECORD_SHARD_COUNT - 1`) |

---

## Offline Item Embeddings

**Rule-based path (Spark Word2Vec → Redis):**

```bash
# Train and push to Redis
mvn -Poffline-embedding exec:java \
  -Dexec.mainClass=com.recsys.training.rulebased.ItemEmbeddingJob \
  -Dexec.args="--output=output/item_embeddings --save-to-redis=true"

# Verify
docker exec -it redis-primary redis-cli GET i2vEmb:1

# Try a recommendation (all recall channels run; embedding channel uses the Redis vectors)
curl "http://localhost:6010/getrecommendation?userId=123&k=5"
```

**Model-based path (PyTorch/ONNX → Redis item embeddings):**

```bash
# Start model serving with Redis-backed item embeddings
RECSYS_MODEL_ITEM_EMBEDDINGS_SOURCE=redis \
RECSYS_MODEL_REDIS_ITEM_EMBEDDING_PREFIX=i2vEmb \
  sh scripts/run-with-jvm-tuning.sh model-serving -- mvn spring-boot:run

# Verify the model serves correctly
curl -X POST http://localhost:8080/api/v1/recommend \
  -H "Content-Type: application/json" -d '{"userId":"123","k":5}'
```

| | Rule-based (Spark) | Model-based (ONNX) | Serving API (classpath) |
|---|---|---|---|
| Written by | Spark → Jedis pipeline | External PyTorch/ONNX pipeline | Bundled text resources |
| Stored in | Redis `i2vEmb:<id>` | ONNX + config artifacts; item embeddings in Redis | Classpath + JVM heap |
| Retrieval | Redis MGET → exact inner-product | DSSM ONNX pair scoring | `VectorIndex`: `lsh` or `exact` |
| TTL | 86400 s default | Redis-configurable | Reloads on restart |

---

## Kubernetes & EKS

The same image runs every service by setting `RECSYS_MAIN_CLASS`.

```bash
# Build image
docker build -t recsys-backend-service:local .

# Deploy base manifests
kubectl apply -k k8s/base
kubectl -n recsys rollout status deployment/recsys-api-gateway

# Check gateway service
kubectl -n recsys get svc recsys-api-gateway

# Port-forward for local testing
kubectl -n recsys port-forward svc/recsys-api-gateway 8010:8010
curl http://localhost:8010/health
```

Inside the cluster, service URLs come from `k8s/base/configmap.yaml`:

```
# Domain-facing routes
USER_PROFILE_SERVICE_URL=http://recsys-catalog-serving:6010
MOVIE_METADATA_SERVICE_URL=http://recsys-catalog-serving:6010
FEATURE_SERVICE_URL=http://recsys-online-serving:7010
RECOMMENDATION_RETRIEVAL_SERVICE_URL=http://recsys-model-serving:8080
RANKING_SERVICE_URL=http://recsys-model-serving:8080
AGENT_WORKFLOW_SERVICE_URL=http://recsys-model-serving:8080
OBSERVABILITY_SERVICE_URL=http://recsys-model-serving:8080
# Backward-compat routes
CATALOG_SERVICE_URL=http://recsys-catalog-serving:6010
MODEL_SERVICE_URL=http://recsys-model-serving:8080
ONLINE_SERVICE_URL=http://recsys-online-serving:7010
```

On EKS with Cloud Map, DNS names follow the pattern `http://<service>.recsys.internal:<port>`.

```bash
# Deploy EKS overlay (ECR image, IRSA, Cloud Map)
kubectl apply -k k8s/eks
```

See [docs/aws/eks-deployment.md](docs/aws/eks-deployment.md) for ECR push and EKS commands.

---

## Capacity Planning

Each architectural decision in this repo was made with a specific production scale in mind. The table below maps that target to the design choice it drives — useful context when adapting the system to a different load profile.

| Dimension | Target | Design decision |
|---|---:|---|
| DAU | `200w+` | Compact per-user Redis state: history list + counters + small learned params rather than large mutable profiles |
| Peak read QPS | `8k` | JVM local cache first, Redis second; keep request-time ranking bounded by candidate count |
| Event TPS | > read QPS during bursts | Write to Kafka first; Flink aggregates asynchronously so bursty TPS don't stall serving reads |
| Machine scale | Stateless API + partitioned Flink + Redis Sentinel | Scale API on QPS/CPU; Flink on consumer lag; Redis on memory, ops/s, and hot-key pressure |

Check live capacity headroom:

```bash
curl http://localhost:7010/online/ops | jq '.capacity'
# {"targetDau":2000000,"peakQps":8000,"headroomQps":7999.9,"overloaded":false}
```

Alarms to set in production:

| Signal | Source | Meaning |
|---|---|---|
| `evacuationFailures > 0` | `GET /health/gc` | G1 heap fragmentation — cap caches or increase heap |
| `.byType.FULL_GC.events > 0` | `GET /health/gc` | Treat as an incident |
| `allocationStalls > 0` | `GET /health/gc` | ZGC needs more heap or more GC threads |
| `stwLongestPauseMs` > SLO | `GET /health/gc` | GC pauses exceeding request latency budget |
| `overloaded: true` | `GET /online/ops` | Online serving load-shedder is active |
| Kafka consumer lag rising | Flink metrics | Flink falling behind; online features will go stale |

```bash
# Quick alarm check
curl -s http://localhost:8080/health/gc | jq '{evacuationFailures, allocationStalls, stwLongestPauseMs}'
curl -s http://localhost:7010/online/ops | jq '{overloaded: .capacity.overloaded, headroomQps: .capacity.headroomQps}'
```

---

## JVM Tuning

Three GC profiles at the repo root:

```bash
# G1 (default — balanced throughput and latency)
java $(cat jvm-g1.options) -jar recsys-api-*.jar

# ZGC (sub-ms pauses — Java 21+)
java $(cat jvm-zgc.options) -jar recsys-api-*.jar
```

Per-service JVM profiles under `config/jvm/`:

| Profile | Heap | GC target | Use case |
|---|---:|---:|---|
| `api-gateway` | `256 m–512 m` | `100 ms` | Armeria API gateway port 8010 |
| `recsys-serving` | `1–2 g` | `100 ms` | Armeria port 6010 |
| `model-serving` | `2 g` (fixed) | `100 ms` | Spring Boot + ONNX port 8080 |
| `online-serving` | `1–2 g` | `100 ms` | Armeria port 7010 |
| `offline-embedding` | `4–8 g` | `200 ms` | Spark driver |

`model-serving` uses a fixed heap (`-Xms2g -Xmx2g`) to eliminate heap-resize pauses under ONNX load. Other serving profiles use a minimum/maximum range (`-Xms1g -Xmx2g`).

Summarize GC logs:

```bash
sh scripts/summarize-gc-logs.sh logs/gc-online-serving-*.log
```

Arthas for live JVM diagnostics:

```bash
mkdir -p tools/arthas
curl -L -o tools/arthas/arthas-boot.jar https://arthas.aliyun.com/arthas-boot.jar

jps -lv                                                     # find the PID
sh scripts/arthas-diagnostics.sh <pid> thread               # CPU threads + deadlock
sh scripts/arthas-diagnostics.sh <pid> cpu 60               # flame graph (60 s)
sh scripts/arthas-diagnostics.sh <pid> watch \
  com.recsys.model.service.RankingService rank              # inspect params/return/cost
sh scripts/arthas-diagnostics.sh <pid> trace \
  com.recsys.model.service.RecommendationService recommend  # call path cost
```

MAT heap analysis:

```bash
sh scripts/mat-heap-analysis.sh dump <pid>              # live heap dump
sh scripts/mat-heap-analysis.sh histogram <pid>         # top classes
MAT_PARSE_HEAP_DUMP=/path/to/ParseHeapDump \
  sh scripts/mat-heap-analysis.sh report logs/heap-dumps/heap-<pid>-<ts>.hprof
```

## Pipeline Optimizations

A log of specific fixes applied to the serving path, targeting OOM, Full GC, thread blocking, and CPU spikes.

| Component | Problem | Fix |
|---|---|---|
| `OnlineFeatureStore` | `ConcurrentHashMap.compute()` held a bin lock during Redis network call | `CompletableFuture` inflight map; Redis fetch runs outside any lock |
| `RecommendationCache` | `synchronized` + access-order map serialised every cache read | `ReentrantReadWriteLock` + insertion-order map |
| `RedisEmbeddingStore.loadAll()` | One unbounded `MGET` → OOM on large stores | Batch-MGET per SCAN page (≤ 500 keys) |
| `RedisEmbeddingStore.getEmbeddings()` | Oversized or duplicate-key MGET | Deduplicate IDs and chunk with `REDIS_EMBEDDING_MGET_BATCH_SIZE` |
| `LocalEmbeddingCache` | FIFO eviction could evict hot embeddings; duplicate batch misses | Access-order LRU; batch misses deduplicated before backing-store fetch |
| `HotKeyDetector` | Fixed-window counters reset abruptly at boundaries | Two-bucket alpha-weighted sliding window; lock-free per-key counters |
| `ShardedTopKStore` | Single `topk:{window}` → Redis hot key under load | N shard replicas; random shard read on TTL refresh; local 2 s cache + singleflight |
| `MultiLevelEmbeddingCache` | Redis hiccups → repeated network calls for popular IDs | L1→L2→L3 promotion; null sentinel for missing hot IDs |
| `ModelArtifactService` | `Arrays.copyOf()` doubled live heap at startup | Removed defensive copy; read-only after load |
| `OnlineFeatureStore.evictIfNeeded()` | O(N) `removeIf` on every cache-miss request | Rate-limited to once per 5 s |
| `OnlineLearner.evictIfNeeded()` | O(N log N) heap allocation on every `learn()` call | Rate-limited to once per 5 s |
| `UserTowerInferenceService.close()` | Closed `OrtEnvironment` (JVM-wide singleton) → invalidated all variant sessions | Now closes only the per-variant `OrtSession` |
| `OnlineServingMetricsService` | `Instant.now()` allocation on hot request path | `System.currentTimeMillis() / 1000L` — zero allocation |

---

## LLM Gateway

The gateway includes an LLM-optimized proxy at `/api/llm/*`. Enable it by setting `LLM_SERVICE_URL`:

```bash
export LLM_SERVICE_URL=http://localhost:11434   # Ollama
sh scripts/run-microservices-local.sh
```

Features: SSE streaming passthrough, retry-on-429, token-count-aware rate limiting, SHA-256 response caching, circuit breaker.

```bash
# Non-streaming generation
curl -X POST "http://localhost:8010/api/llm/api/generate" \
  -H "Content-Type: application/json" \
  -d '{"model":"llama3","prompt":"Summarize this movie: Inception","max_tokens":200}'

# Streaming generation (SSE)
curl -X POST "http://localhost:8010/api/llm/api/generate" \
  -H "Content-Type: application/json" \
  -d '{"model":"llama3","prompt":"Recommend 3 movies similar to Inception","stream":true}'

# Check if LLM routes are registered
curl http://localhost:8010/health | jq '.services | with_entries(select(.key | test("llm")))'
```

| Env var | Default | Purpose |
|---|---:|---|
| `LLM_SERVICE_URL` | _(unset)_ | Enables LLM routes; set to Ollama or any OpenAI-compatible URL |
| `LLM_TIMEOUT_MS` | `120000` | Per-request timeout |
| `LLM_TOKEN_RATE_LIMIT_TPS` | `0` | Tokens/second refill rate; `0` = disabled |
| `LLM_TOKEN_RATE_LIMIT_BURST` | `0` | Burst token capacity |
| `LLM_CACHE_MAX_SIZE` | `500` | Max cached non-streaming responses |
| `LLM_CACHE_TTL_SECONDS` | `300` | Cache TTL |

---

## Model Rate Limiting

`ModelRateLimiter` applies a per-user token-bucket limit to `POST /api/v1/recommend` before the global concurrency semaphore — preventing one high-traffic user from monopolising ONNX inference slots.

```bash
# Enable: 5 req/s per user, burst 10
RECSYS_MODEL_RATE_LIMIT_RPS=5.0 \
RECSYS_MODEL_RATE_LIMIT_BURST=10 \
  sh scripts/run-with-jvm-tuning.sh model-serving -- mvn spring-boot:run

# Trigger the limit (run rapidly)
for i in $(seq 1 15); do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -X POST http://localhost:8080/api/v1/recommend \
    -H "Content-Type: application/json" \
    -d '{"userId":"1","k":1}'
done
# 200 200 200 ... 429 429 429
```

`429` response:

```json
{"error": "request rate limit exceeded — retry after 1s", "violations": []}
```

| Property | Default | Purpose |
|---|---:|---|
| `recsys.model.rate-limit.rps` | `0.0` | Per-user req/s (`0` = disabled) |
| `recsys.model.rate-limit.burst` | `0` | Burst capacity |
| `recsys.model.rate-limit.max-users` | `10000` | Max tracked users (LRU eviction) |

---

## AWS Saga Orchestration

`com.recsys.saga` provides durable multi-step orchestration for eventual-consistency workflows, backed by AWS Step Functions.

| Class | Pattern | Use when |
|---|---|---|
| `SagaOrchestrator` | Compensating transaction | Sequential steps with best-effort rollback |
| `TccSagaOrchestrator` | Try / Confirm / Cancel | Stronger consistency — Try reserves, Confirm commits, Cancel releases |

Both use full-jitter exponential backoff (matching `MaxDelaySeconds: 30`, `JitterStrategy: FULL` in generated ASL).

```java
// Run a saga with charge + reserve steps
SagaInstance result = orchestrator.execute(
    sagaId, correlationId, payloadJson, definition,
    Map.of(
        "charge-payment", (saga, step) -> paymentService.charge(...),
        "reserve-model",  (saga, step) -> modelSlotService.reserve(...)
    ),
    Map.of(
        "charge-payment", (saga, step) -> paymentService.refund(...),
        "reserve-model",  (saga, step) -> modelSlotService.release(...)
    )
);
// result.status() == SagaStatus.COMPLETED or FAILED
```

`AwsStepFunctionsSagaDefinition.render(definition)` produces ready-to-deploy Step Functions JSON with per-step retry policies and compensating-state routing. Use `sagaId + stepName` as the idempotency key.

---

## Developer Notes

The sections above cover the system from the outside in — capacity targets that set scale requirements, JVM profiles that bound GC pauses, pipeline fixes that removed lock contention and OOM paths, LLM and model rate limits that protect shared inference slots, and saga orchestration for durable multi-step workflows. This section maps those operational constraints to the specific classes that implement them, as a guide for contributors reading or modifying the code.

The key design thread running through all of it: **keep the request path allocation-free and lock-free wherever possible**. Hot-key detection, sharded Top-K, multi-level caches, batched Redis reads, and concurrency-limited inference slots all exist because the capacity targets (8 k QPS, 200 w+ DAU) leave no room for per-request blocking, unbounded allocations, or single-key Redis hotspots.

### Data loading

`DataLoader` loads bundled text resources from `com/recsys/data`. `DataManager` is a read-only singleton with immutable maps, precomputed sorted lists (`topRatedMovies`, `latestMovies`), and genre indexes.

### Retrieval strategies

- `CandidateGenerator.byGenre` — expands from seed movie's genres; top-100 per genre by average rating.
- `CandidateGenerator.byUserHistory` — multi-way: user's genre history + global top-100 + latest 100.
- `CandidateGenerator.byEmbedding` — ANN search through `VectorIndex` (`lsh` or `exact`).

### Hot-key and cache controls

`HotKeyDetector` — sliding two-bucket window with alpha-weighted blending; lock-free per-key counters. Detects which movie/user keys are hot and gates eviction.

```bash
# Observe hot-key detection indirectly via cache hit rates in online ops
curl http://localhost:7010/online/ops | jq '.metrics'
```

`ShardedTopKStore` — replicates each `topk:{window}` sorted set across N Redis shard keys. On local-cache TTL refresh, reads a random shard — reducing per-key Redis QPS by N. `seedAllShards()` fan-out keeps shards consistent.

`MultiLevelEmbeddingCache` — L1 (JVM hot-key) → L2 (Redis) → L3 (fallback snapshot). L2/L3 hits promote to L1; null sentinels absorb repeated misses for missing IDs.

### Online learner

`OnlineLearner` updates per-item bias parameters from `ExperienceCollector` output without retraining the ONNX model. Biases are bounded by `maxItemCount` (default 10,000) with LRU eviction. State is persisted to Redis between restarts:

```bash
# Observe learning indirectly: recommendations shift as biases update
curl "http://localhost:7010/online/recommendation?userId=123"
```

### Model runtime provider

`ModelRuntimeProvider` owns the lifecycle of every per-variant ONNX runtime. `@PostConstruct warmUp()` pre-loads all configured variants so no user pays cold-start cost. `areVariantsReady()` gates `/health/ready`.
