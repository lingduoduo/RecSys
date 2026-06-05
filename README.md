# RecSys

A compact Maven workspace demonstrating recommendation-system serving, retrieval, ranking, and offline embedding pipelines across four independently runnable services.

| Service | Port | What it shows |
|---|---:|---|
| Catalog / Recommendation Serving | `6010` | Jetty, Redis embeddings, multi-strategy recall, runtime embedding updates |
| Online Prediction Server | `7010` | Real-time Redis-backed recommendations, load shedding, ops metrics |
| Model Serving (Spring Boot) | `8080` | ONNX two-tower DSSM inference, A/B testing, variant-aware artifacts |
| API Gateway | `8010` | Microservice edge: circuit breakers, rate limiting, LLM proxy |

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
{"status":"UP","checkedAt":"...","services":{"user-profile":{"status":"UP"},...}}
```

---

## Contents

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
- [Model Serving Demo](#model-serving-demo)
- [A/B Testing](#ab-testing)
- [Testing](#testing)
- [Redis Test Data](#redis-test-data)
- [Online Serving](#online-serving)
- [Offline Item Embeddings](#offline-item-embeddings)
- [Embedding Storage Paths](#embedding-storage-paths)
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

# Online Prediction Server — port 7010
env ONLINE_DEMO_PORT=7010 sh scripts/run-with-jvm-tuning.sh online-serving -- \
  mvn exec:java -Dexec.mainClass=com.recsys.streaming.OnlinePredictionServer

# Model Serving (Spring Boot / ONNX) — port 8080
env SERVER_PORT=8080 sh scripts/run-with-jvm-tuning.sh model-serving -- \
  mvn spring-boot:run

# API Gateway — port 8010
env GATEWAY_PORT=8010 sh scripts/run-with-jvm-tuning.sh api-gateway -- \
  mvn exec:java -Dexec.mainClass=com.recsys.microservice.MicroserviceGatewayServer
```

Key env vars: `REDIS_HOST` (default `localhost`), `REDIS_PORT` (default `6379`).

---

## Recommendation Flow

Two independent recommendation paths — run one or both.

**Offline / batch path (port 6010)** — pre-trained embeddings in Redis, no streaming required.

1. `CandidateGenerator.byUserHistory` — merges candidates from the user's genre history, global top-rated, and latest releases.
2. `CandidateGenerator.byGenre` — expands from a seed movie's genres.
3. `CandidateGenerator.byEmbedding` — ANN search on user/item embedding vectors.
4. `SimilarMovieService` — ranks candidates by inner-product similarity and returns top-K.

**Online / real-time path (port 7010)** — live signals from Redis updated by the Flink job.

1. `OnlineRecommendationEngine` — scores candidates from per-user recent watch history and windowed trending Top-K.
2. `CandidateGenerator.byEmbedding` — ANN recall on offline user-tower embeddings.
3. `OnlineRecommendationService` — normalizes and blends both lists; cold-start users fall back to behavioral signals. The response `strategy` field (`"online+model"` or `"online"`) shows which sources fired.

---

## API Reference

### Port 6010 — Catalog & Recommendation Serving

Jetty API backed by Redis embeddings and bundled movie/user data. Embeddings are seeded from classpath files at startup if Redis is empty.

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
# {"userId":123,"name":"Alice"}
```

#### Movie (item) lookup

Fetch a movie by `id`:

```bash
curl "http://localhost:6010/item?id=1"
curl "http://localhost:6010/movie?id=1"               # REST alias
# {"id":1,"title":"Inception","year":2010,"genres":["Sci-Fi","Thriller"]}
```

#### Recommendations — multi-strategy (default)

Blends genre history, global top-rated, and latest releases for the user:

```bash
curl "http://localhost:6010/getrecommendation?userId=123"
curl "http://localhost:6010/recommendation?userId=123"   # REST alias

# Limit results
curl "http://localhost:6010/getrecommendation?userId=123&k=10"
```

#### Recommendations — seed movie genre expansion

Expands candidates from the genres of a specific seed movie:

```bash
curl "http://localhost:6010/getrecommendation?userId=123&seedMovieId=2"
curl "http://localhost:6010/getrecommendation?userId=123&seedMovieId=2&k=5"
```

#### Recommendations — embedding-based ANN recall

Retrieves candidates by approximate nearest-neighbor search on user/item embeddings:

```bash
curl "http://localhost:6010/getrecommendation?userId=123&mode=embedding&k=20"
```

Returns `404` if no user embedding is found. `k` is capped at 200 (default 20). Backend controlled by `RECSYS_VECTOR_BACKEND`:

```bash
# Approximate (default) — SimHash random-projection + inner-product reranking
RECSYS_VECTOR_BACKEND=lsh mvn exec:java -Dexec.mainClass=com.recsys.serving.RecSysServer

# Exact — full-scan inner-product top-k (deterministic, slower)
RECSYS_VECTOR_BACKEND=exact mvn exec:java -Dexec.mainClass=com.recsys.serving.RecSysServer
```

#### Recommendations — trending (Redis sorted set)

Returns pre-scored trending movies from a Redis sorted set written by the Flink job:

```bash
curl "http://localhost:6010/getrecommendation?userId=123&mode=topk&window=last_hour&k=5"
curl "http://localhost:6010/getrecommendation?userId=123&mode=trending&window=last_day&k=10"
```

Windows: `last_hour`, `last_day`, `last_month`.

#### Similar movies

Computes inner-product similarity against Redis item embeddings and returns the closest movies:

```bash
curl "http://localhost:6010/similar?movieId=1&k=5"
# {"movieId":1,"similar":[{"movieId":4,"score":0.99},{"movieId":7,"score":0.97},...]}

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

#### Set / update an embedding

Stores or updates a movie embedding in Redis (default TTL 24 h; `ttl=0` for no expiry):

```bash
# Raw body
curl -X POST "http://localhost:6010/setembedding?movieId=4" \
  -H "Content-Type: text/plain" --data-binary "0.2 0.2 0.6"

# Form body
curl -X POST "http://localhost:6010/setembedding?movieId=5" \
  --data-urlencode "vec=0.1 0.3 0.6"

# Query param with custom TTL (seconds)
curl -X POST "http://localhost:6010/setembedding?movieId=6&ttl=3600&vec=0.5+0.5+0.0"
```

---

### Port 7010 — Online Prediction Server

Real-time recommendations blending per-user Redis history with offline embedding recall. Responses populate once the Flink job is writing to Redis; without it the lists are empty but the API is healthy.

#### Real-time recommendations

Blends behavioral signals (recent watch history + trending) with embedding-based recall:

```bash
curl "http://localhost:7010/online/recommendation?userId=123"
curl "http://localhost:7010/online/recommendation?userId=123&window=last_day&k=10"
curl "http://localhost:7010/online/recommendation?userId=456&window=last_month&k=5"
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
  "strategy": "online+model",
  "recentMovies": [4, 7],
  "trendingMovies": [11, 1, 2],
  "recommendations": [{"movieId": 4, "score": 0.91}, ...]
}
```

The `strategy` field is `"online+model"` when embedding recall fires, `"online"` for cold-start users.

#### Feature snapshot

Returns the raw Redis feature view for a user — useful for debugging what signals are available:

```bash
curl "http://localhost:7010/online/features?userId=123"
curl "http://localhost:7010/online/features?userId=123&window=last_hour"
# {"user":{"userId":123,"name":"Alice"},"window":"last_hour","recentMovies":[4,7],"trendingMovies":[11,1,2]}
```

#### Ops and metrics

Returns QPS, latency, failure rate, load-shedder state, and capacity targets in one payload:

```bash
curl "http://localhost:7010/online/ops"
```

```json
{
  "servedAt": "2026-06-03T12:00:00Z",
  "metrics": {"totalRequests": 42, "recentAvgLatencyMs": 22.5, "recentFailureRate": 0.0},
  "load": {"inFlightRequests": 0, "utilization": 0.0, "suggestedWeight": 100},
  "capacity": {"targetDau": 2000000, "peakQps": 8000, "headroomQps": 7999.9, "overloaded": false}
}
```

---

### Port 8080 — Model Serving (Spring Boot)

Spring Boot service running a PyTorch-exported DSSM ONNX model with A/B variant support.

#### Recommend

Runs ONNX inference to rank candidates for a user; returns `abTestVariant` so impressions can be attributed to the correct experiment bucket:

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

| Field | Type | Required | Constraints |
|---|---|---|---|
| `userId` | string | yes | non-blank, max 50 chars |
| `k` | integer | no | 1–100, default `5` |
| `excludeItemIds` | string[] | no | max 500 entries |

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
    "training": {"totalRequests":120,"successRate":0.9833,"avgLatencyMs":11.4,"successRateDeltaVsControl":0.0},
    "test":     {"totalRequests":113,"successRate":0.9823,"avgLatencyMs":12.1,"successRateDeltaVsControl":-0.001}
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
| `GET /health/jvm` | Heap / non-heap / metaspace / thread snapshot | Memory pressure investigation |
| `GET /health/gc` | GC event histogram, STW pause stats | GC tuning and incident response |

```bash
curl http://localhost:8080/health/ready
# 200: {"status":"UP","recentRequests":42,"recentFailureRate":0.02,"inFlightRequests":7,"suggestedWeight":89}
# 503: {"status":"DOWN","reason":"high failure rate","recentFailureRate":0.6,"threshold":0.5}

curl http://localhost:8080/health/load
# {"inFlightRequests":7,"maxConcurrentRequests":64,"utilization":0.109,"suggestedWeight":89}

curl http://localhost:8080/health/metrics
# {"totalRequests":1042,"successCount":1038,"recentAvgLatencyMs":55.7,"throughputPerSecond":0.3}

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

The gateway strips its route prefix and proxies to the backend. Use it as the single entry point — it handles circuit breaking, rate limiting, and auth.

| Gateway prefix | Backend | Direct equivalent |
|---|---|---|
| `/api/users` | `:6010` | `GET /user?userId=123` |
| `/api/movies` | `:6010` | `GET /movie?id=1` |
| `/api/catalog` | `:6010` | `GET /item?id=1`, `GET /getrecommendation?...` |
| `/api/features` | `:7010` | `GET /online/features?userId=123` |
| `/api/online` | `:7010` | `GET /online/recommendation?userId=123` |
| `/api/retrieval` | `:8080` | `POST /api/v1/recommend` |
| `/api/ranking` | `:8080` | `POST /api/v1/recommend` |
| `/api/model` | `:8080` | `POST /api/v1/recommend` |
| `/api/observability` | `:8080` | `GET /health/ready` |
| `/api/llm` | `:11434` | opt-in — set `LLM_SERVICE_URL` |
| `/api/explanations` | `:11434` | opt-in — set `LLM_EXPLANATION_SERVICE_URL` |

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

# Offline recommendations via gateway
curl "http://localhost:8010/api/catalog/getrecommendation?userId=123&mode=embedding&k=5"
curl "http://localhost:8010/api/catalog/getrecommendation?userId=123&mode=topk&window=last_hour&k=5"

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

| Gateway prefix | Backend port | Backing class | Notes |
|---|---:|---|---|
| `/api/users` | `6010` | `RecSysServer` | User profile lookup |
| `/api/movies` | `6010` | `RecSysServer` | Movie metadata lookup |
| `/api/catalog` | `6010` | `RecSysServer` | Recommendations, similar, pair prediction |
| `/api/features` | `7010` | `OnlinePredictionServer` | Online feature snapshot |
| `/api/online` | `7010` | `OnlinePredictionServer` | Real-time recommendations + ops |
| `/api/retrieval` | `8080` | `ModelApplication` | ONNX-based retrieval |
| `/api/ranking` | `8080` | `ModelApplication` | ONNX-based ranking |
| `/api/model` | `8080` | `ModelApplication` | Full recommend endpoint |
| `/api/agents` | `8080` | `ModelApplication` | Agent workflow placeholder |
| `/api/observability` | `8080` | `ModelApplication` | Model health and metrics |
| `/api/llm` | `11434` | Ollama / OpenAI-compat | Opt-in — set `LLM_SERVICE_URL` |
| `/api/explanations` | `11434` | Ollama / OpenAI-compat | Opt-in — set `LLM_EXPLANATION_SERVICE_URL` |

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
curl http://localhost:8010/health | jq '{status, services: (.services | to_entries | map({(.key): .value.status}) | add)}'
# {"status":"UP","services":{"user-profile":"UP","movie-metadata":"UP",...}}
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

```bash
# Enable global rate limit (5 req/s, burst 10)
GATEWAY_RATE_LIMIT_RPS=5 GATEWAY_RATE_LIMIT_BURST=10 \
  mvn exec:java -Dexec.mainClass=com.recsys.microservice.MicroserviceGatewayServer

# Per-route override — limit model endpoint more aggressively
GATEWAY_RATE_LIMIT_MODEL_RPS=2 GATEWAY_RATE_LIMIT_MODEL_BURST=3 \
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

### Online Prediction Server (port 7010)

| Env var | Default | Purpose |
|---|---:|---|
| `ONLINE_DEMO_PORT` | `7010` | Server port |
| `ONLINE_MAX_CONCURRENT_REQUESTS` | `512` | In-flight cap before `429` |
| `ONLINE_DRAIN_UTILIZATION` | `0.90` | Utilization where `/health` → `503` for drain |
| `ONLINE_REDIS_RATE_LIMIT_QPS` | `0` | Cross-instance Redis rate limit; `0` = disabled |
| `ONLINE_FEATURE_CACHE_MAX_USERS` | `10000` | Max Redis feature keys in JVM cache |
| `ONLINE_FEATURE_REDIS_MGET_BATCH_SIZE` | `500` | Redis `MGET` batch size |
| `ONLINE_METRICS_WINDOW_SECONDS` | `60` | Rolling metrics window |
| `ONLINE_TARGET_DAU` | `2000000` | Capacity sizing assumption |
| `ONLINE_PEAK_QPS` | `8000` | Peak read-QPS target |

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

---

## Project Layout

```text
src/main/java/com/recsys/
├── models/         Immutable API/domain records (Movie, User, Rating)
├── features/       Data loading, vector math, Redis stores, LSH/exact index, candidate generation
├── microservice/   API gateway: routing, circuit breakers, rate limiting, LLM proxy
├── serving/        Jetty servlets for port 6010 (RecSysServer)
├── streaming/      Online serving layer for port 7010 (OnlinePredictionServer)
│   └── flink/      Flink job — writes history + embeddings + trending to Redis
├── training/
│   ├── rulebased/  Spark Word2Vec item embedding job
│   └── modelbased/ Spring Boot ONNX model serving (port 8080)
├── mysql/          Thin JDBC wrapper (opt-in)
├── pagination/     SQL helpers for million-row cursor pagination
└── saga/           AWS Step Functions saga orchestration

src/main/resources/
├── dssm_model.onnx                    Bundled DSSM demo model
└── artifacts/model/training/          Bundled feature_config.json + model artifacts

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

Or write directly to Redis (`i2vEmb:{movieId}`):

```bash
mvn -Poffline-embedding exec:java \
  -Dexec.mainClass=com.recsys.training.rulebased.ItemEmbeddingJob \
  -Dexec.args="--output=output/item_embeddings --save-to-redis=true --redis-host=localhost --redis-port=6379"
```

Options: `--vector-size=16`, `--window-size=5`, `--min-count=1`, `--max-iter=10`, `--min-rating=3.5`, `--redis-key-prefix=i2vEmb`, `--redis-ttl=86400`.

---

## A/B Testing

`ABTestService` assigns users to variants deterministically by hashing `userId:layerName` modulo `trafficSplitNumber`. The assigned variant is returned in every response so impressions can be attributed to the right bucket.

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

## Testing

Run all unit and integration tests (load tests excluded by default):

```bash
mvn test

# Run a single test class
mvn test -Dtest=RecommendationServiceTest

# Load tests only (100 concurrent requests, asserts P95 ≤ 2000 ms)
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
| `InferenceLoadTest` _(tag: load)_ | P95 ≤ 2000 ms, success rate ≥ 99% under 100 concurrent requests |
| `OnlineRecommendationEngineTest` | Blends history + trending; rejects unknown window values |
| `OnlineRecommendationServiceTest` | Blended scoring, online-only fallback, recently-watched exclusion |
| `JvmMemoryMonitorTest` | Heap/non-heap positive bytes, usedFraction in [0,1], metaspace pool |
| `GcEventTrackerTest` | Zero initial counters, histogram keys, `GcType.stw`, `avgPauseMs`, destroy idempotence |
| `RedisConnectionFactoryTest` | Standalone pool, sentinel code path, `parseSentinelNodes`, `parsePort` |

---

## Redis Test Data

Seed trending data so `mode=topk` and online recommendations return results:

```bash
# Seed last_hour trending
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
curl "http://localhost:6010/getrecommendation?userId=123&mode=topk&window=last_hour&k=5"
curl "http://localhost:7010/online/recommendation?userId=123&window=last_hour"
```

Redis key conventions:

| Key | Purpose |
|---|---|
| `i2vEmb:<id>` | Item (movie) embedding |
| `u2vEmb:<id>` | User embedding |
| `topk:<window>` | Trending sorted set (`last_hour`, `last_day`, `last_month`) |
| `user:<id>:recent_movies` | Per-user recent watch history (written by Flink) |
| `feature:user:<id>:embedding` | User embedding from online Flink job |
| `feature:movie:<id>:ctr:<window>` | Movie CTR and engagement metrics |

---

## Online Serving

The Kafka/Flink/Redis pipeline provides the real-time signals consumed by port `7010`. See [streaming/online-serving/README.md](streaming/online-serving/README.md) for full setup.

Quick start (loads sample features without Flink):

```bash
docker compose -f streaming/online-serving/docker-compose.yml up -d
sh streaming/online-serving/scripts/load_online_features.sh

sh scripts/run-with-jvm-tuning.sh online-serving -- \
  mvn exec:java -Dexec.mainClass=com.recsys.streaming.OnlinePredictionServer

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
| `LogCollector` | Validates and emits Kafka-ready behavior logs (exposure, click, watch, like, rating, dwell, search, order) |
| `OnlineJoiner` | Joins behavior logs with user/item/context features; produces labeled samples |
| `ExperienceCollector` | Groups samples by request into ranked list experiences for listwise training |
| `OnlineLearner` | Updates per-item bias parameters from list experiences; persists to Redis |
| `OnlineFeatureStreamingJob` | Flink job: reads Kafka, writes history + embeddings + trending + CTR to Redis |
| `OnlineRecommendationEngine` | Scores candidates from per-user history + windowed trending |
| `OnlineRecommendationService` | Blends behavioral + embedding signals; cold-start fallback |
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

# FEATURE (Flink-written behavioral features: CTR, session data)
curl -X POST http://localhost:7010/shards/records \
  -H "Content-Type: application/json" \
  -d '{"deviceId":"user:123","type":"FEATURE","eventId":"ctr-001","payload":"{\"ctr\":0.42}"}'

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

# Try embedding recall
curl "http://localhost:6010/getrecommendation?userId=123&mode=embedding&k=5"
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
| Stored in | Redis `i2vEmb:{id}` | ONNX + config artifacts; item embeddings in Redis | Classpath + JVM heap |
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
| `FULL_GC events > 0` | `GET /health/gc` | Treat as an incident |
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
| `recsys-serving` | `1–2 g` | `100 ms` | Jetty port 6010 |
| `model-serving` | `2 g` (fixed) | `100 ms` | Spring Boot + ONNX port 8080 |
| `online-serving` | `1–2 g` | `100 ms` | Jetty port 7010 |
| `offline-embedding` | `4–8 g` | `200 ms` | Spark driver |

Serving profiles use fixed heaps (`-Xms == -Xmx`) to eliminate heap-resize pauses during traffic ramps.

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
  com.recsys.modelbased.model.service.RankingService rank   # inspect params/return/cost
sh scripts/arthas-diagnostics.sh <pid> trace \
  com.recsys.modelbased.model.service.RecommendationService recommend  # call path cost
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
