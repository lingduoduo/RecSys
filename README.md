# RecSys

A compact Maven workspace demonstrating recommendation-system serving, retrieval, ranking, and offline embedding pipelines.

| Area | What it shows |
|---|---|
| Recommendation Serving API | Jetty, Redis, multi-strategy retrieval, runtime embedding updates |
| Model Serving | Spring Boot ONNX scoring with variant-aware model artifacts |
| Online Serving | Real-time Redis-backed recommendations with load shedding |
| API Gateway | Microservice edge: circuit breakers, rate limiting, LLM proxy |
| Offline Embeddings | Spark Word2Vec item embeddings + PyTorch/ONNX two-tower model |

![Architecture](recsys-architecture.png)
[Architecture Diagram (interactive)](https://htmlpreview.github.io/?https://github.com/lingduoduo/Recsys-Backend-Service/blob/main/recsys-architecture.html)

---

## Quick Start

**Requirements:** Java 17, Maven, Docker + Colima (Mac) or Docker Desktop.

```bash
# 1. Start infrastructure (Zookeeper, Kafka, Redis Sentinel, Flink)
colima start
docker compose -f docker-compose.streaming.yml up -d

# 2. Build
mvn package -DskipTests

# 3. Start all four services
sh scripts/run-microservices-local.sh
```

All four services start in the background with logs under `logs/`. The gateway at `:8010` becomes the single entry point.

Verify everything is up:

```bash
curl http://localhost:8010/health
```

To start services individually, see [Services & Ports](#services--ports).

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
- [JVM Tuning](#jvm-tuning)
- [Capacity Planning](#capacity-planning)
- [Developer Notes](#developer-notes)
- [Pipeline Optimizations](#pipeline-optimizations)
- [LLM Gateway](#llm-gateway)
- [Model Rate Limiting](#model-rate-limiting)
- [AWS Saga Orchestration](#aws-saga-orchestration)
- [LLM Integration Ideas](#llm-integration-ideas)

---

## Services & Ports

| Service | Port | Start command | Entrypoint |
|---|---:|---|---|
| Catalog / Recommendation Serving | `6010` | `mvn exec:java -Dexec.mainClass=com.recsys.serving.RecSysServer` | `RecSysServer` |
| Online Prediction Server | `7010` | `mvn exec:java -Dexec.mainClass=com.recsys.streaming.OnlinePredictionServer` | `OnlinePredictionServer` |
| Model Serving (Spring Boot) | `8080` | `mvn spring-boot:run` | `ModelApplication` |
| API Gateway | `8010` | `mvn exec:java -Dexec.mainClass=com.recsys.microservice.MicroserviceGatewayServer` | `MicroserviceGatewayServer` |

With JVM tuning:

```bash
env PORT=6010        sh scripts/run-with-jvm-tuning.sh recsys-serving  -- mvn exec:java -Dexec.mainClass=com.recsys.serving.RecSysServer
env ONLINE_DEMO_PORT=7010 sh scripts/run-with-jvm-tuning.sh online-serving  -- mvn exec:java -Dexec.mainClass=com.recsys.streaming.OnlinePredictionServer
env SERVER_PORT=8080 sh scripts/run-with-jvm-tuning.sh model-serving   -- mvn spring-boot:run
env GATEWAY_PORT=8010 sh scripts/run-with-jvm-tuning.sh api-gateway    -- mvn exec:java -Dexec.mainClass=com.recsys.microservice.MicroserviceGatewayServer
```

Key env vars: `REDIS_HOST`, `REDIS_PORT`, `PORT` / `ONLINE_DEMO_PORT` / `SERVER_PORT` / `GATEWAY_PORT`.

---

## Recommendation Flow

The project demonstrates two recommendation paths that can run independently or together.

**Offline / batch path (port 6010)**

Recall narrows the catalog to a candidate set; ranking scores and orders those candidates.

- **Single-strategy recall:** `CandidateGenerator.byGenre` expands from the genres of a seed movie.
- **Multi-way recall:** `CandidateGenerator.byUserHistory` merges candidates from user-history genres, global top-rated movies, and latest releases.
- **Embedding recall:** `CandidateGenerator.byEmbedding` retrieves items by ANN search on user and item embeddings.
- **Ranking:** `SimilarMovieService` scores each candidate with inner-product similarity and returns the top-K results.

**Online / real-time path (port 7010)**

`OnlineRecommendationService` blends two live signals on every request:

- **Behavioral signals** (`OnlineRecommendationEngine`): recent per-user watch history + windowed trending Top-K written by the Flink job into Redis.
- **Embedding recall** (`CandidateGenerator.byEmbedding`): ANN search on offline-trained user-tower embeddings.

A normalized rank score fuses the two lists. Cold-start users fall back to behavioral signals only. The response `strategy` field (`"online+model"` or `"online"`) shows which signals fired.

---

## API Reference

### Port 6010 — Catalog & Recommendation Serving

Direct access to the Jetty serving API. All endpoints are also reachable through the gateway at `:8010` — see [Port 8010 — API Gateway](#port-8010--api-gateway).

#### Health

```bash
curl http://localhost:6010/health
# {"ok":true}
```

#### User Lookup

```bash
curl "http://localhost:6010/getuser?userId=123"
# or REST alias:
curl "http://localhost:6010/user?userId=123"
# {"userId":123,"name":"Alice"}
```

#### Item (Movie) Lookup

```bash
curl "http://localhost:6010/item?id=1"
# or REST alias:
curl "http://localhost:6010/movie?id=1"
# {"id":1,"title":"Inception","year":2010,"genres":["Sci-Fi","Thriller"]}
```

#### Recommendations

Four retrieval modes. All require `userId`.

**Default — multi-strategy by user history:**

```bash
curl "http://localhost:6010/getrecommendation?userId=123"
# or REST alias:
curl "http://localhost:6010/recommendation?userId=123"
```

Merges three candidate pools via `CandidateGenerator.byUserHistory`: genre-based from the user's rating history (top 20 per genre), global top-100 by average rating, and latest 100 by release year.

**Seed movie — genre-based from seed:**

```bash
curl "http://localhost:6010/getrecommendation?userId=123&seedMovieId=2"
```

**Embedding-based recall:**

```bash
curl "http://localhost:6010/getrecommendation?userId=123&mode=embedding&k=20"
```

Uses `CandidateGenerator.byEmbedding`. Backend controlled by `RECSYS_VECTOR_BACKEND` (`lsh` or `exact`). Returns `404` if no user embedding is found. `k` capped at 200 (default 20).

**Trending (Redis sorted set):**

```bash
curl "http://localhost:6010/getrecommendation?userId=123&mode=topk&window=last_hour&k=5"
```

Windows: `last_hour`, `last_day`, `last_month`.

#### Similar Items

```bash
curl "http://localhost:6010/similar?movieId=1&k=5"
# {"movieId":1,"similar":[{"movieId":4,"score":0.99}, ...]}
```

#### Pair Prediction

```bash
curl -X POST "http://localhost:6010/v1/models/recmodel:predict" \
  -H "Content-Type: application/json" \
  -d '{"instances":[{"userId":123,"movieId":1},{"userId":123,"movieId":2}]}'
# {"predictions":[[0.9231],[0.7412]]}
```

#### Set Embedding

```bash
# Raw body
curl -X POST "http://localhost:6010/setembedding?movieId=4" \
  -H "Content-Type: text/plain" --data-binary "0.2 0.2 0.6"

# Form body
curl -X POST "http://localhost:6010/setembedding?movieId=5" \
  --data-urlencode "vec=0.1 0.3 0.6"

# Custom TTL (seconds; 0 = no expiry)
curl -X POST "http://localhost:6010/setembedding?movieId=6&ttl=3600&vec=0.5+0.5+0.0"
```

#### Embedding backend

```bash
RECSYS_VECTOR_BACKEND=lsh   sh scripts/run-with-jvm-tuning.sh recsys-serving -- mvn exec:java -Dexec.mainClass=com.recsys.serving.RecSysServer
RECSYS_VECTOR_BACKEND=exact sh scripts/run-with-jvm-tuning.sh recsys-serving -- mvn exec:java -Dexec.mainClass=com.recsys.serving.RecSysServer
```

`lsh` is the default approximate backend. `exact` is useful for deterministic recall checks.

---

### Port 7010 — Online Prediction Server

Real-time recommendations driven by Redis behavioral features and offline embeddings.

#### Real-time recommendations

```bash
curl "http://localhost:7010/online/recommendation?userId=123"
curl "http://localhost:7010/online/recommendation?userId=123&window=last_day&k=10"
```

| Param | Required | Default | Values |
|---|---|---|---|
| `userId` | yes | — | any int |
| `k` | no | 5 | 1–20 |
| `window` | no | `last_hour` | `last_hour`, `last_day`, `last_month` |

Example response:

```json
{
  "user": {"userId": 123, "name": "Alice"},
  "window": "last_hour",
  "strategy": "online+model",
  "recentMovies": [],
  "trendingMovies": [],
  "recommendations": []
}
```

`recommendations` is populated once the Flink job writes user history and trending data to Redis.

#### Feature snapshot

```bash
curl "http://localhost:7010/online/features?userId=123"
curl "http://localhost:7010/online/features?userId=123&window=last_hour"
```

Returns the raw feature view (recent history + trending movies) without running the recommendation blending.

#### Ops & metrics

```bash
curl "http://localhost:7010/online/ops"
```

Returns QPS, latency, failure rate, load-shedder state, capacity targets, and a `servedAt` timestamp in one payload.

---

### Port 8080 — Model Serving (Spring Boot)

ONNX two-tower DSSM model serving with A/B testing and health probes.

#### Recommend

```bash
curl -X POST http://localhost:8080/api/v1/recommend \
  -H "Content-Type: application/json" \
  -d '{"userId": "123", "k": 5, "excludeItemIds": ["2"]}'
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
| `excludeItemIds` | string[] | no | max 500 entries, each max 50 chars |

#### Model version management

```bash
curl http://localhost:8080/api/v1/model/versions

curl -X POST http://localhost:8080/api/v1/model/versions/preload \
  -H "Content-Type: application/json" -d '{"variant": "candidate-v2"}'

curl -X POST http://localhost:8080/api/v1/model/versions/activate \
  -H "Content-Type: application/json" -d '{"variant": "candidate-v2"}'

curl -X POST http://localhost:8080/api/v1/model/versions/rollback
```

#### Health probes

| Endpoint | Returns |
|---|---|
| `GET /health/live` | `200` if JVM responds — restart trigger |
| `GET /health/ready` | `200` if fit for traffic; `503` when overloaded or model not ready |
| `GET /health/load` | Concurrency snapshot + `suggestedWeight` |
| `GET /health/metrics` | Rolling-window request counters and latency |
| `GET /health/ab-tests` | Per-variant success rate and latency vs control |
| `GET /health/jvm` | Heap / non-heap / metaspace / thread snapshot |
| `GET /health/gc` | GC event histogram, STW pause stats, evacuation failures |

```bash
curl http://localhost:8080/health/ready
curl http://localhost:8080/health/gc
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

The gateway strips the route prefix and proxies to the appropriate backend. Use this as the primary entry point in local and K8s deployments.

| Gateway prefix | Backend port | Example |
|---|---|---|
| `/api/users` | `6010` | `/api/users/user?userId=123` |
| `/api/movies` | `6010` | `/api/movies/movie?id=1` |
| `/api/catalog` | `6010` | `/api/catalog/item?id=1` |
| `/api/features` | `7010` | `/api/features/online/features?userId=123` |
| `/api/online` | `7010` | `/api/online/online/recommendation?userId=123` |
| `/api/retrieval` | `8080` | `/api/retrieval/api/v1/recommend` |
| `/api/ranking` | `8080` | `/api/ranking/api/v1/recommend` |
| `/api/model` | `8080` | `/api/model/api/v1/recommend` |
| `/api/agents` | `8080` | `/api/agents/...` |
| `/api/observability` | `8080` | `/api/observability/health/ready` |
| `/api/llm` | `11434` | opt-in — set `LLM_SERVICE_URL` |
| `/api/explanations` | `11434` | opt-in — set `LLM_EXPLANATION_SERVICE_URL` |

**LLM routes** (`/api/llm`, `/api/explanations`) are only registered when `LLM_SERVICE_URL` or `LLM_EXPLANATION_SERVICE_URL` are explicitly set (requires Ollama or a compatible endpoint). Without them, the gateway health reports `UP` with no LLM entries.

#### Smoke tests

```bash
# Health (aggregates all downstream services)
curl http://localhost:8010/health

# User lookup
curl "http://localhost:8010/api/users/user?userId=123"

# Movie lookup
curl "http://localhost:8010/api/movies/movie?id=1"
curl "http://localhost:8010/api/catalog/item?id=1"

# Offline recommendations
curl "http://localhost:8010/api/catalog/getrecommendation?userId=123&mode=embedding&k=5"

# Online recommendations (real-time)
curl "http://localhost:8010/api/online/online/recommendation?userId=123&window=last_hour&k=5"

# Model-based recommendations (ONNX)
curl -X POST "http://localhost:8010/api/model/api/v1/recommend" \
  -H "Content-Type: application/json" \
  -d '{"userId":"123","k":5}'

# LLM (requires Ollama running + LLM_SERVICE_URL set)
curl -X POST "http://localhost:8010/api/llm/api/generate" \
  -H "Content-Type: application/json" \
  -d '{"model":"llama3","prompt":"Summarize: Inception","max_tokens":200}'
```

> **Hostname note:** `localhost:8010` is for local development. Inside a Kubernetes cluster use the ClusterIP service name (e.g. `recsys-api-gateway:8010`); on EKS with Cloud Map use the external DNS name (e.g. `api-gateway.recsys.internal:8010`).

---

## Microservice Gateway

The gateway is the public edge for the microservice topology.

| Service | Port | Gateway prefix | Entrypoint |
|---|---:|---|---|
| User Profile Service | `6010` | `/api/users` | `com.recsys.serving.RecSysServer` |
| Movie Metadata Service | `6010` | `/api/movies` | `com.recsys.serving.RecSysServer` |
| Feature Service | `7010` | `/api/features` | `com.recsys.streaming.OnlinePredictionServer` |
| Recommendation Retrieval | `8080` | `/api/retrieval` | `com.recsys.modelbased.ModelApplication` |
| Ranking Service | `8080` | `/api/ranking` | `com.recsys.modelbased.ModelApplication` |
| Agent Workflow | `8080` | `/api/agents` | model-serving placeholder |
| Observability | `8080` | `/api/observability` | model-serving health/metrics |
| Catalog (backward compat) | `6010` | `/api/catalog` | `com.recsys.serving.RecSysServer` |
| Model recommendation | `8080` | `/api/model` | `com.recsys.modelbased.ModelApplication` |
| Online recommendation | `7010` | `/api/online` | `com.recsys.streaming.OnlinePredictionServer` |
| LLM proxy _(opt-in)_ | `11434` | `/api/llm` | set `LLM_SERVICE_URL` |
| LLM explanation _(opt-in)_ | `11434` | `/api/explanations` | set `LLM_EXPLANATION_SERVICE_URL` |
| API gateway | `8010` | `/` | `com.recsys.microservice.MicroserviceGatewayServer` |

Start all services:

```bash
docker compose -f docker-compose.streaming.yml up -d
sh scripts/run-microservices-local.sh
```

Start only the gateway (when downstream services are already running):

```bash
sh scripts/run-with-jvm-tuning.sh api-gateway -- \
  mvn exec:java -Dexec.mainClass=com.recsys.microservice.MicroserviceGatewayServer
```

Enable LLM routes (requires Ollama):

```bash
brew install ollama && ollama serve
export LLM_SERVICE_URL=http://localhost:11434
sh scripts/run-microservices-local.sh
```

`GET /health` aggregates downstream health checks and returns `503` with `status: DEGRADED` when any registered service is unavailable. See `docs/api-gateway-service-topology.md` for the route ownership map.

---

## Configuration

### Recommendation Serving API (port 6010)

| Env var | Default | Purpose |
|---|---:|---|
| `PORT` | `6010` | Server port |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `LOCAL_EMBEDDING_CACHE_MAX_ENTRIES` | `100000` | Max embeddings in JVM read-through cache |
| `RECSYS_VECTOR_BACKEND` | `lsh` | `lsh` or `exact` |

On startup the server seeds Redis with bundled embeddings if the Redis keys are empty.

### Online Prediction Server (port 7010)

| Env var | Default | Purpose |
|---|---:|---|
| `ONLINE_DEMO_PORT` | `7010` | Server port |
| `ONLINE_MAX_CONCURRENT_REQUESTS` | `512` | In-flight cap before `429` |
| `ONLINE_DRAIN_UTILIZATION` | `0.90` | `/health` → `503` threshold for load-balancer drain |
| `ONLINE_REDIS_RATE_LIMIT_QPS` | `0` | Cross-instance Redis rate limit; `0` = disabled |
| `ONLINE_FEATURE_CACHE_MAX_USERS` | `10000` | Max Redis online feature keys in JVM cache |
| `ONLINE_FEATURE_REDIS_MGET_BATCH_SIZE` | `500` | Redis `MGET` batch size for feature reads |
| `REDIS_EMBEDDING_MGET_BATCH_SIZE` | `500` | Redis `MGET` batch size for embedding reads |
| `ONLINE_METRICS_WINDOW_SECONDS` | `60` | Rolling metrics window |
| `ONLINE_TARGET_DAU` | `2000000` | Capacity sizing assumption |
| `ONLINE_PEAK_QPS` | `8000` | Peak read-QPS target |
| `ONLINE_PEAK_TPS` | `20000` | Peak event-TPS target |

### API Gateway (port 8010)

| Env var | Default | Purpose |
|---|---:|---|
| `GATEWAY_PORT` | `8010` | Gateway port |
| `GATEWAY_TIMEOUT_MS` | `3000` | Upstream connect/request timeout |
| `CATALOG_SERVICE_URL` | `http://localhost:6010` | Base URL for `/api/catalog` |
| `MODEL_SERVICE_URL` | `http://localhost:8080` | Base URL for `/api/model` |
| `ONLINE_SERVICE_URL` | `http://localhost:7010` | Base URL for `/api/online` |
| `LLM_SERVICE_URL` | _(unset)_ | Enables `/api/llm` — default Ollama `11434` |
| `LLM_EXPLANATION_SERVICE_URL` | _(unset)_ | Enables `/api/explanations` |
| `GATEWAY_API_KEYS` | _(unset)_ | Comma-separated API keys; enables `X-API-Key` / `Authorization: Bearer` auth |
| `GATEWAY_PUBLIC_PATHS` | `/health` | Paths that bypass API-key auth |
| `GATEWAY_RATE_LIMIT_RPS` | `0` | Global token-bucket rate; `0` = disabled |
| `GATEWAY_RATE_LIMIT_BURST` | `0` | Global burst capacity |
| `GATEWAY_RATE_LIMIT_<ROUTE>_RPS` | _(unset)_ | Per-route override, e.g. `GATEWAY_RATE_LIMIT_MODEL_RPS` |
| `GATEWAY_RATE_LIMIT_<ROUTE>_BURST` | _(unset)_ | Per-route burst override |

### Model Serving (Spring Boot, port 8080)

| Env var / property | Default | Purpose |
|---|---:|---|
| `SERVER_PORT` | `8080` | Server port |
| `RECSYS_MODEL_ARTIFACTS_DIR` | _(classpath)_ | Model artifact directory; resolves `<dir>/<variant>/...` |
| `RECSYS_MODEL_ITEM_EMBEDDINGS_SOURCE` | `classpath` | `classpath` or `redis` |
| `RECSYS_MODEL_REDIS_ITEM_EMBEDDING_PREFIX` | `i2vEmb` | Redis key prefix for item embeddings |
| `RECSYS_SPARK_ARTIFACTS_DIR` | _(classpath)_ | PySpark artifact directory |
| `recsys.health.window-seconds` | `60` | Rolling window for metrics |
| `recsys.health.min-sample-size` | `5` | Min requests before readiness thresholds apply |
| `recsys.health.max-failure-rate` | `0.5` | Failure rate above which `/health/ready` → `503` |
| `recsys.health.max-avg-latency-ms` | `2000` | Avg latency above which `/health/ready` → `503` |
| `recsys.health.max-concurrent-requests` | `64` | Per-instance in-flight cap |
| `recsys.health.max-in-flight-utilization` | `0.95` | Utilization above which `/health/ready` → `503` |
| `MYSQL_ENABLED` | `false` | Optional MySQL switch |
| `MYSQL_URL` | `jdbc:mysql://localhost:3306/recsys?...` | JDBC URL |
| `MYSQL_USER` | `recsys` | MySQL username |
| `MYSQL_PASSWORD` | _(empty)_ | MySQL password |

### A/B test configuration (Model Serving)

| Property | Default | Purpose |
|---|---:|---|
| `recsys.ab-test.enabled` | `false` | Enable bucketing |
| `recsys.ab-test.layer-name` | `default` | Experiment name mixed into the hash key |
| `recsys.ab-test.traffic-split-number` | `5` | Modulus for the hash bucket (20% A, 20% B, 60% control) |
| `recsys.ab-test.bucket-a-variant` | `test` | Variant for bucket 0 |
| `recsys.ab-test.bucket-b-variant` | `training` | Variant for bucket 1 |
| `recsys.ab-test.default-variant` | `training` | Control variant |

---

## Project Layout

```text
src/main/java/com/recsys/
├── models/                 Immutable API/domain records
├── features/               Data loading, indexed access, retrieval, vector math, Redis stores
├── microservice/           API gateway, domain route map, LLM proxy, route health aggregation
├── serving/                Jetty server and servlet endpoints (port 6010)
├── streaming/              Online serving layer (port 7010)
│   ├── flink/              Flink streaming job — writes online features to Redis
│   ├── OnlineRecommendationService.java   Blends behavioral + embedding signals
│   ├── OnlineRecommendationEngine.java    Real-time scoring: recent history + trending
│   ├── OnlinePredictionServer.java        Jetty entry point
│   └── ...
├── mysql/                  Optional JDBC helper and connection settings
├── pagination/             SQL templates for million-row pagination
├── saga/                   AWS saga orchestration (SagaOrchestrator, TCC variant, Step Functions ASL)
├── training/
│   ├── rulebased/          Spark Word2Vec offline item embeddings
│   └── modelbased/
│       └── model/          Spring Boot ONNX model serving
│           ├── ModelApplication.java
│           ├── config/     Model artifact + A/B test configuration
│           ├── controller/ Recommendation and health APIs
│           ├── dto/        Request / response payloads
│           └── service/    Candidate selection, recall, ranking, ONNX inference, A/B bucketing
└── data/                   Bundled sample data and seed embeddings

src/main/resources/artifacts/model/
├── training/               Bundled sample artifacts (feature_config.json, dssm_model.onnx)
└── test/

docker-compose.streaming.yml              Local Redis/Kafka/Flink infrastructure
streaming/online-serving/                 Canonical Kafka + Flink + Redis online-serving path
k8s/base/                                 Kustomize manifests for all four services
k8s/eks/                                  EKS-specific patches (IRSA, Cloud Map, ECR)
```

---

## Model Serving Demo

A Spring Boot service on port `8080` serving ONNX-based retrieval and ranking.

**Demonstrates:**

- DSSM ONNX inference in Java
- Offline model artifacts from a PyTorch/ONNX training pipeline
- A/B variant-aware runtime pre-warming — all variants loaded before the first request
- Per-variant latency and success-rate metrics via `GET /health/ab-tests`
- Rolling-window inference metrics and config-driven probe thresholds

### Artifact contract

```text
feature_config.json        User vocab and feature metadata
item_embeddings.json       Optional pretrained item embeddings (item_id → float[])
item_embeddings.faiss      Optional FAISS IndexFlatIP index
item_ids.json              Optional FAISS row-to-item-id mapping
metadata.json              Model version and training metadata
dssm_model.onnx            Exported DSSM model for runtime pair scoring
```

Organize variants as `<artifacts-dir>/<variant>/feature_config.json`. Leave the model file on the classpath for local demos. `RECSYS_MODEL_FILE` defaults to `dssm_model.onnx`.

### Start

```bash
# Bundled classpath artifacts
sh scripts/run-with-jvm-tuning.sh model-serving -- mvn spring-boot:run

# External modeling pipeline artifacts
RECSYS_MODEL_ARTIFACTS_DIR=/path/to/model/artifacts \
  sh scripts/run-with-jvm-tuning.sh model-serving -- mvn spring-boot:run
```

### Generate item embeddings offline

```bash
# Train and write to file
sh scripts/run-with-jvm-tuning.sh offline-embedding -- \
  mvn -Poffline-embedding exec:java \
  -Dexec.mainClass=com.recsys.training.rulebased.ItemEmbeddingJob \
  -Dexec.args="--output=output/item_embeddings"

# Train and write directly to Redis
sh scripts/run-with-jvm-tuning.sh offline-embedding -- \
  mvn -Poffline-embedding exec:java \
  -Dexec.mainClass=com.recsys.training.rulebased.ItemEmbeddingJob \
  -Dexec.args="--output=output/item_embeddings --save-to-redis=true --redis-host=localhost --redis-port=6379"
```

Then run model serving with Redis-backed item embeddings:

```bash
RECSYS_MODEL_ITEM_EMBEDDINGS_SOURCE=redis \
RECSYS_MODEL_REDIS_ITEM_EMBEDDING_PREFIX=i2vEmb \
RECSYS_MODEL_ARTIFACTS_DIR=/path/to/model/artifacts \
  sh scripts/run-with-jvm-tuning.sh model-serving -- mvn spring-boot:run
```

---

## A/B Testing

`ABTestService` assigns each user to a variant deterministically by hashing `userId:layerName` modulo `trafficSplitNumber`.

### Bucketing logic

```
bucket = (userId + ":" + layerName).hashCode() & Integer.MAX_VALUE
         % trafficSplitNumber

bucket == 0  →  bucketAVariant  (treatment A)
bucket == 1  →  bucketBVariant  (treatment B)
otherwise    →  defaultVariant  (control)
```

With `trafficSplitNumber = 5`: 20% A, 20% B, 60% control.

### Layer isolation

**Within the same layer** — users are mutually exclusive across buckets.  
**Across different layers** — bucket indices are independent; same user can be in bucket 0 of two independent experiments simultaneously.

### Enable

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

Or via env vars:

```bash
RECSYS_AB_TEST_ENABLED=true \
RECSYS_AB_TEST_LAYER_NAME=model-arch-test-2024q2 \
  sh scripts/run-with-jvm-tuning.sh model-serving -- mvn spring-boot:run
```

### Compare variants

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

---

## Testing

```bash
# Unit and integration tests
mvn test

# Load tests only
mvn test -DexcludedGroups="" -Dgroups=load

# Single test class
mvn test -Dtest=RecommendationServiceTest
```

| Test class | What it covers |
|---|---|
| `ModelArtifactLocatorTest` | Classpath and external-dir resolution for model and spark artifact groups |
| `ModelArtifactServiceTest` | Loads bundled `feature_config.json`; asserts model version, vocab, item vocab |
| `ModelRuntimeProviderTest` | Loads independent `training` and `test` runtimes; asserts distinct model versions |
| `FeatureEncoderTest` | Known user IDs map to vocab indices; unknown IDs fall back to `__UNK__` |
| `RankingServiceTest` | Items re-ordered by inner-product score; k-truncation and missing-embedding skip |
| `RetrievalServiceTest` | Embedding recall returns top-K ordered descending; null embedding and unknown items handled |
| `ABTestServiceTest` | Disabled flag, null userId, per-bucket assignment, determinism, same-layer disjoint buckets, cross-layer independence |
| `RecommendationServiceTest` | Service-level guards reject blank `userId` and out-of-range `k`; full response shape including `abTestVariant` |
| `RecommendationControllerTest` | Bean-validation rejections, malformed JSON, wrong content-type → stable `ApiError` shape |
| `PredictionIntegrationTest` | End-to-end service pipeline against bundled artifacts: ranked results, score ordering, excludeItemIds |
| `RecommendationEndToEndTest` | Full HTTP chain (`@SpringBootTest`): controller → inference → metrics; verifies counters and `/health/ready` |
| `InferenceLoadTest` _(tag: load)_ | 100 concurrent requests, 10 threads; asserts P95 ≤ 2000 ms, success rate ≥ 99% |
| `OnlineRecommendationEngineTest` | Blends recent-history and trending; rejects unknown window values |
| `OnlineRecommendationServiceTest` | Blended scoring, online-only fallback, recently-watched exclusion, unknown user 404 |
| `JvmMemoryMonitorTest` | Heap/non-heap positive used bytes, usedFraction in [0,1], metaspace pool presence |
| `GcEventTrackerTest` | Zero initial counters, histogram key presence, `GcType.stw` flag, `avgPauseMs`, destroy idempotence |
| `RedisConnectionFactoryTest` | Standalone pool creation, sentinel code path, `parseSentinelNodes`, `parsePort` edge cases |

---

## Redis Test Data

Seed trending data manually:

```bash
docker exec -it redis-primary redis-cli DEL topk:last_hour
docker exec -it redis-primary redis-cli ZADD topk:last_hour \
  2 11 1 1 1 2 1 3 1 4 1 5 1 7 1 8 1 9 1 12
docker exec -it redis-primary redis-cli ZREVRANGE topk:last_hour 0 9 WITHSCORES
```

Inspect seeded embeddings:

```bash
docker exec -it redis-primary redis-cli SCAN 0 MATCH 'i2vEmb:*' COUNT 20
docker exec -it redis-primary redis-cli GET i2vEmb:1
```

Redis key conventions:

| Key pattern | Purpose |
|---|---|
| `i2vEmb:<id>` | Item (movie) embedding |
| `u2vEmb:<id>` | User embedding |
| `topk:<window>` | Sharded trending top-K sorted set (`last_hour`, `last_day`, `last_month`) |
| `user:<id>:recent_movies` | Per-user recent watch history (written by Flink) |
| `feature:user:<id>:embedding` | User embedding from Flink (online path) |

---

## Online Serving

The Kafka/Flink/Redis streaming path blends real-time behavioral signals with offline embedding recall.

See [streaming/online-serving/README.md](streaming/online-serving/README.md) for full setup. Quick reference:

```bash
# 1. Start infra
docker compose -f streaming/online-serving/docker-compose.yml up -d
# 2. Load sample features (no Flink required)
sh streaming/online-serving/scripts/load_online_features.sh
# 3. Start the server
sh scripts/run-with-jvm-tuning.sh online-serving -- \
  mvn exec:java -Dexec.mainClass=com.recsys.streaming.OnlinePredictionServer
# 4. Try it
curl "http://localhost:7010/online/recommendation?userId=123&window=last_hour&k=5"
curl "http://localhost:7010/online/ops"
```

| Component | What it does |
|---|---|
| `LogCollector` | Validates and emits Kafka-ready behavior logs (exposure, click, watch, like, rating, dwell, search, order) |
| `OnlineJoiner` | Joins behavior logs with user/item/context features; produces labeled samples |
| `ExperienceCollector` | Groups samples by request into ranked list experiences for listwise training |
| `OnlineLearner` | Consumes list experiences; updates lightweight item-bias parameters in Redis |
| `OnlineFeatureStreamingJob` | Flink job: reads Kafka events, deduplicates by `eventId`, writes history + embeddings + trending to Redis |
| `OnlineRecommendationEngine` | Scores candidates from per-user history + trending Top-K |
| `OnlineRecommendationService` | Blends behavioral + embedding signals; falls back to behavioral-only for cold-start |
| `OnlineServingMetricsService` | Rolling QPS, latency, failures, per-strategy `failureRate` and `share` |
| `OnlineLoadShedder` | Caps in-flight requests; sheds overload with `429` + `Retry-After` |
| `OnlineCapacityService` | DAU/QPS/TPS sizing assumptions, remaining `headroomQps`, `overloaded` flag |
| `OnlinePredictionServer` | Jetty entry point on port `7010`: `/health`, `/online/features`, `/online/recommendation`, `/online/ops` |

---

## Offline Item Embeddings

**Online prediction pipeline:**

```text
LogCollector → Kafka → OnlineJoiner → ExperienceCollector ──► OnlineLearner ──► serving parameters
                                             │
                                             └───────────────► training streams / HDFS
                         │
                         └─► Flink → Redis (behavioral features) ─┐
                                                                  ├─> OnlineRecommendationService
user embeddings (ANN recall) ─────────────────────────────────────┘
```

**Offline embedding pipeline:**

```text
Kafka / HDFS → Spark → Word2Vec → model registry / vector store → RecSysServer
```

Train Word2Vec from bundled ratings:

```bash
mvn -Poffline-embedding exec:java \
  -Dexec.mainClass=com.recsys.training.rulebased.ItemEmbeddingJob \
  -Dexec.args="--output=output/item_embeddings"
```

Options: `--ratings`, `--vector-size`, `--window-size`, `--min-count`, `--max-iter`, `--step-size`, `--min-rating`, `--synonym-movie-id`.

Write embeddings to Redis:

```bash
mvn -Poffline-embedding exec:java \
  -Dexec.mainClass=com.recsys.training.rulebased.ItemEmbeddingJob \
  -Dexec.args="--output=output/item_embeddings --save-to-redis=true --redis-host=localhost --redis-port=6379"
```

Redis options: `--redis-key-prefix=i2vEmb` · `--redis-ttl=86400`.

### Redis Feature Store keys (written by Flink)

| Feature family | Redis key | Value shape |
|---|---|---|
| Recent user history | `user:<userId>:recent_movies` | Space-delimited movie IDs, newest last |
| User embedding | `feature:user:<userId>:embedding` | L2-normalized hashed embedding CSV |
| Session feature | `feature:user:<userId>:session:<sessionId>` | Counts and last event fields |
| Movie CTR feature | `feature:movie:<movieId>:ctr:<window>` | impressions, clicks, ctr, watches, dwells, engagement score |
| Hot movies | `topk:<window>` | Redis ZSET movie ID → engagement score |
| Trend feature | `feature:trend:<window>` | Compact `movieId:score` list |

---

## Embedding Storage Paths

### Rule-based → Redis

`ItemEmbeddingJob` → Jedis pipeline → `i2vEmb:{movieId}` → `SimilarMovieService` (MGET → inner-product top-k)

### Model-based → offline artifacts + Redis

PyTorch/ONNX pipeline exports compact ONNX + `feature_config.json`; item embeddings go to Redis `i2vEmb:{movieId}`. `ModelRuntimeProvider` pre-loads all configured variants at startup.

```bash
RECSYS_MODEL_ITEM_EMBEDDINGS_SOURCE=redis \
RECSYS_MODEL_REDIS_ITEM_EMBEDDING_PREFIX=i2vEmb \
  sh scripts/run-with-jvm-tuning.sh model-serving -- mvn spring-boot:run
```

### Serving API → classpath

`DataLoader` reads `movie_embeddings.txt` / `user_embeddings.txt` from the classpath at startup for the `mode=embedding` path.

### Comparison

| | Rule-based (Redis) | Model-based (ONNX) | Serving API (classpath) |
|---|---|---|---|
| Written by | Spark → Jedis pipeline | External PyTorch/ONNX pipeline | Bundled text resources |
| Stored in | Redis (`i2vEmb:{id}`) | ONNX + config artifacts; item embeddings in Redis | Classpath + JVM heap |
| Retrieval | Redis MGET → exact inner-product | DSSM ONNX pair scoring | `VectorIndex`: `lsh` or `exact` |
| TTL | 86400 s default | Redis-configurable | Reloads on restart |

---

## Kubernetes & EKS

The same image runs every service by setting `RECSYS_MAIN_CLASS`.

```bash
# Build
docker build -t recsys-backend-service:local .

# Deploy base manifests
kubectl apply -k k8s/base
kubectl -n recsys rollout status deployment/recsys-api-gateway
```

Inside the cluster, the gateway receives service URLs from `k8s/base/configmap.yaml`:

```text
CATALOG_SERVICE_URL=http://recsys-catalog-serving:6010
MODEL_SERVICE_URL=http://recsys-model-serving:8080
ONLINE_SERVICE_URL=http://recsys-online-serving:7010
```

For EKS with Cloud Map, service URLs follow the pattern `http://<service>.recsys.internal:<port>`.

```bash
# Deploy EKS overlay
kubectl apply -k k8s/eks
kubectl -n recsys get svc recsys-api-gateway
```

See [docs/aws/eks-deployment.md](docs/aws/eks-deployment.md) for ECR and EKS commands.

---

## JVM Tuning

Three GC profiles at the repo root:

| File | Collector | Pause target | When to use |
|---|---|---|---|
| `jvm.options` | G1GC | 100 ms | Default — balanced throughput and latency |
| `jvm-g1.options` | G1GC (enhanced) | 100 ms | Adaptive IHOP, reserve percent, mixed-GC count, phase logging |
| `jvm-zgc.options` | ZGC (generational) | < 1 ms | Latency-critical inference; requires Java 21+ |

```bash
java $(cat jvm-g1.options) -jar recsys-api-*.jar
java $(cat jvm-zgc.options) -jar recsys-api-*.jar   # Java 21+
```

Per-service profiles under `config/jvm/`:

| Profile | JVM file | Target |
|---|---|---|
| `recsys-serving` | `config/jvm/recsys-serving.jvmopts` | Jetty port `6010` |
| `model-serving` | `config/jvm/model-serving.jvmopts` | Spring Boot port `8080` |
| `online-serving` | `config/jvm/online-serving.jvmopts` | Jetty port `7010` |
| `offline-embedding` | `config/jvm/offline-embedding.jvmopts` | Spark driver runs |
| `*-zgc` variants | `config/jvm/*-zgc.jvmopts` | ZGC low-pause alternatives |

Heap profiles:

| Profile | `-Xms` | `-Xmx` | GC pause target |
|---|---:|---:|---:|
| `recsys-serving` | `1g` | `2g` | `100 ms` |
| `model-serving` | `2g` | `2g` | `100 ms` |
| `online-serving` | `1g` | `2g` | `100 ms` |
| `offline-embedding` | `4g` | `8g` | `200 ms` |

Serving profiles use fixed heaps (`-Xms == -Xmx`) to remove heap-resize pauses during traffic ramps.

### GC observability (model serving)

```bash
curl http://localhost:8080/health/gc
```

```json
{
  "byType": {
    "MINOR_GC": {"events": 42, "totalPauseMs": 630, "avgPauseMs": 15.0},
    "FULL_GC":  {"events": 0,  "totalPauseMs": 0,   "avgPauseMs": 0.0}
  },
  "stwLongestPauseMs": 28,
  "stwPauseHistogram": {"<1ms":0,"1-10ms":5,"10-50ms":37,"50-200ms":0},
  "evacuationFailures": 0,
  "allocationStalls": 0
}
```

Alarms to set: `evacuationFailures > 0`, `allocationStalls > 0`, `stwLongestPauseMs` above request SLO, any `FULL_GC` events.

Summarize GC logs:

```bash
sh scripts/summarize-gc-logs.sh logs/gc-online-serving-*.log
```

### Arthas runtime diagnostics

```bash
mkdir -p tools/arthas
curl -L -o tools/arthas/arthas-boot.jar https://arthas.aliyun.com/arthas-boot.jar

jps -lv
sh scripts/arthas-diagnostics.sh <pid> thread
sh scripts/arthas-diagnostics.sh <pid> cpu 60
sh scripts/arthas-diagnostics.sh <pid> watch com.recsys.modelbased.model.service.RankingService rank
sh scripts/arthas-diagnostics.sh <pid> trace com.recsys.modelbased.model.service.RecommendationService recommend
```

### MAT heap analysis

```bash
sh scripts/mat-heap-analysis.sh dump <pid>
sh scripts/mat-heap-analysis.sh histogram <pid>
MAT_PARSE_HEAP_DUMP=/path/to/mat/ParseHeapDump \
  sh scripts/mat-heap-analysis.sh report logs/heap-dumps/heap-<pid>-<timestamp>.hprof
```

---

## Capacity Planning

Sizing for 200w+ DAU and 8k peak QPS:

| Dimension | Target | Design implication |
|---|---:|---|
| DAU | `200w+` | Compact per-user online state: history lists, counters, small learned params |
| Peak read QPS | `8k` | JVM local cache first, Redis second; bound candidate count |
| Event TPS | > read QPS during bursts | Write to Kafka first; Flink aggregates asynchronously |
| Machine scale | Stateless API + partitioned Flink + Redis Sentinel | Scale API on QPS/CPU; Flink on lag; Redis on memory/ops |

Production concerns:

- **Latency SLO:** track p50/p95/p99 for recall, Redis reads, ranking, and serialization separately.
- **Cache hit rate:** watch JVM local-cache hit rate and Redis MGET latency.
- **Backpressure:** monitor Kafka consumer lag, Flink checkpoint duration, Redis write latency.
- **Degradation:** fall back to cached Top-K/trending when Redis or inference is slow.
- **Consistency:** at-least-once MQ delivery, `eventId` idempotency, Redis last-write-wins timestamps.

---

## Developer Notes

**Data loading:**
- `DataLoader` loads bundled text resources from `com/recsys/data`.
- `DataManager` is a read-only singleton with immutable maps and precomputed sorted lists.

**Retrieval strategies (`CandidateGenerator`):**
- `byGenre` — seed-movie genre recall.
- `byUserHistory` — multi-way recall from user-history genres, global top-rated, and latest.
- `byEmbedding` — ANN search through the `VectorIndex` interface (`lsh` or `exact`).

**Redis-backed embeddings:**
- `RedisEmbeddingStore` — generic key-prefix store for item and user embeddings.
- `LocalEmbeddingCache` — bounded LRU JVM read-through layer in front of Redis; batch reads deduplicate misses.

**Hot-key and multi-level cache controls:**
- `HotKeyDetector` — sliding-window hot-key detection; two-bucket alpha-weighted blending; lock-free per-key counters.
- `ShardedTopKStore` — key sharding + local cache for Top-K windows; reduces per-key Redis QPS by N.
- `MultiLevelEmbeddingCache` — L1 (JVM hot-key) → L2 (Redis) → L3 (fallback snapshot); per-tier hit rates exposed.

**Model serving:**
- `ModelRuntimeProvider` — owns lifecycle of every per-variant runtime; `@PostConstruct warmUp()` pre-loads all configured variants.
- `ModelArtifactLocator` — resolves model and spark artifact groups from classpath or external directory.
- `ABTestService` — deterministic hash-based bucketing; same layer → mutually exclusive; different layers → independent.
- `InferenceMetricsService` — per-variant rolling counters; `abTestSnapshot(controlVariant)` computes deltas vs control.
- `GcEventTracker` — JMX notification listener per `GarbageCollectorMXBean`; fires on every GC event.

**Servlet base:**
- `BaseApiServlet` — centralizes JSON headers, Jackson serialization, error responses, and parameter parsing.

---

## Pipeline Optimizations

| Component | Problem | Fix |
|---|---|---|
| `OnlineFeatureStore` | `ConcurrentHashMap.compute()` held a bin lock during Redis network call | `CompletableFuture` inflight map; Redis fetch runs outside any lock |
| `RecommendationCache.TtlLruCache` | `synchronized` + access-order `LinkedHashMap` serialised every read | `ReentrantReadWriteLock` + insertion-order map |
| `RedisEmbeddingStore.loadAll()` | One unbounded `MGET` on large stores → OOM / Full GC | Batch-MGET per SCAN page (≤500 keys) |
| `RedisEmbeddingStore.getEmbeddings()` | Oversized MGET; duplicate keys in same request | Deduplicate IDs and chunk with `REDIS_EMBEDDING_MGET_BATCH_SIZE` |
| `LocalEmbeddingCache` | FIFO eviction could evict hot embeddings; duplicate misses forwarded | Access-order LRU; batch misses deduplicated before backing-store fetch |
| `HotKeyDetector` | Fixed-window counters reset abruptly | Two-bucket alpha-weighted sliding window; lock-free per-key counters |
| `ShardedTopKStore` | Single `topk:{window}` key → Redis hot key at scale | N shard replicas; random shard read on TTL refresh; 2 s local cache + singleflight |
| `MultiLevelEmbeddingCache` | Redis hiccups → repeated network calls | L1→L2→L3 promotion; null sentinel for missing hot IDs |
| `ModelArtifactService` | `Arrays.copyOf()` doubled live heap during startup | Removed defensive copy; read-only after load |
| `OnlineFeatureStore.evictIfNeeded()` | O(N) `removeIf` on every cache-miss request | Rate-limited to once per 5 s |
| `OnlineFeatureStore.getFeatures()` | Feature reads one Redis key at a time | Bulk path: dedup + bounded local cache + null-miss caching + chunked MGET |
| `OnlineLearner.evictIfNeeded()` | O(N log N) heap allocation on every `learn()` call past limit | Rate-limited to once per 5 s |
| `UserTowerInferenceService.close()` | Closed `OrtEnvironment` (JVM-wide singleton), invalidating other variant sessions | Now closes only the per-variant `OrtSession` |
| `OnlineServingMetricsService` | `Instant.now()` allocation on hot path | `System.currentTimeMillis() / 1000L` — no allocation |

---

## LLM Gateway

The gateway includes an LLM-optimized reverse proxy at `/api/llm/*` (opt-in — set `LLM_SERVICE_URL`).

| Feature | Behaviour |
|---|---|
| Streaming passthrough | Detects `"stream":true` and pipes SSE/chunked response byte-by-byte |
| Retry-on-429 | Reads upstream `Retry-After` and retries once (buffered mode only) |
| Token-based rate limiting | Pre-checks `max_tokens` against a local token-bucket |
| Response caching | Non-streaming `200` responses cached by SHA-256 of request body |
| Circuit breaker | Shared with health endpoint; fast-fails `503` during cooldown |

Default target: Ollama (`http://localhost:11434`). Override via `LLM_SERVICE_URL` for any OpenAI-compatible endpoint.

### LLM environment variables

| Env var | Default | Purpose |
|---|---:|---|
| `LLM_SERVICE_URL` | _(unset)_ | Enables LLM routes; base URL for the LLM backend |
| `LLM_TIMEOUT_MS` | `120000` | Per-request timeout in ms |
| `LLM_MAX_RETRY_WAIT_MS` | `30000` | Max `Retry-After` wait before abandoning 429 retry |
| `LLM_DEFAULT_TOKEN_ESTIMATE` | `1000` | Token estimate when `max_tokens` is absent |
| `LLM_TOKEN_RATE_LIMIT_TPS` | `0` | Refill rate in LLM-tokens/second (`0` = disabled) |
| `LLM_TOKEN_RATE_LIMIT_BURST` | `0` | Burst capacity in LLM-tokens (`0` = disabled) |
| `LLM_CACHE_MAX_SIZE` | `500` | Max cached responses (`0` = disabled) |
| `LLM_CACHE_TTL_SECONDS` | `300` | Cache entry TTL (`0` = disabled) |

---

## Model Rate Limiting

`ModelRateLimiter` applies a per-user token-bucket rate limit to `POST /api/v1/recommend`. The check runs before the global concurrency semaphore.

| Property | Default | Purpose |
|---|---:|---|
| `recsys.model.rate-limit.rps` | `0.0` | Per-user requests/second (`0` = disabled) |
| `recsys.model.rate-limit.burst` | `0` | Burst capacity per user |
| `recsys.model.rate-limit.max-users` | `10000` | Max tracked users (LRU eviction) |

Example — 5 req/s per user, burst 10:

```bash
RECSYS_MODEL_RATE_LIMIT_RPS=5.0 \
RECSYS_MODEL_RATE_LIMIT_BURST=10 \
  sh scripts/run-with-jvm-tuning.sh model-serving -- mvn spring-boot:run
```

`429` response:

```json
{"error": "request rate limit exceeded — retry after 1s", "violations": []}
```

---

## AWS Saga Orchestration

`com.recsys.saga` provides durable multi-step orchestration backed by AWS Step Functions.

| Class | Pattern | When to use |
|---|---|---|
| `SagaOrchestrator` | Compensating transaction | Sequential steps with best-effort rollback |
| `TccSagaOrchestrator` | Try / Confirm / Cancel | Stronger consistency — Try reserves, Confirm commits, Cancel releases |

Both use full-jitter exponential backoff (`MaxDelaySeconds: 30`, `JitterStrategy: FULL`).

`AwsStepFunctionsSagaDefinition.render()` produces ready-to-deploy Step Functions JSON with per-step retry policies, catch routing to compensating states, and terminal `SagaCompleted` / `SagaCancelled` states.

```java
SagaInstance result = orchestrator.execute(
    sagaId, correlationId, payloadJson, definition,
    Map.of("charge-payment", (saga, step) -> paymentService.charge(...)),
    Map.of("charge-payment", (saga, step) -> paymentService.refund(...))
);
```

Use `sagaId + stepName` as the idempotency key for participant commands.

---

## LLM Integration Ideas

- Use text embeddings as item/user features for retrieval.
- Use an LLM as a zero-shot ranker or reranker for diversity, freshness, and domain-specific constraints.
- Fine-tune for direct item generation when supervised recommendation data is available.
- Add conversational recommendation on top of the existing serving layer.
