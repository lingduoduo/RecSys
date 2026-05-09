# RecSys

RecSys is a compact Maven workspace for experimenting with recommendation-system serving, retrieval, ranking, and offline embedding pipelines.

| Area | What it shows |
|---|---|
| Movie API | Jetty, Redis, local movie data, multi-strategy retrieval, and runtime embedding updates |
| Model serving demo | Spring Boot ONNX retrieval serving with variant-aware model artifact loading |
| Rule-based offline embeddings | Spark Word2Vec item embeddings trained from user interaction sequences |
| Model-based offline embeddings | User-tower inference plus precomputed item embeddings |

![Architecture](architecture.png)

---

## Contents

- [Recommendation Flow](#recommendation-flow)
- [Movie API](#movie-api)
- [Configuration](#configuration)
- [Project Layout](#project-layout)
- [API Reference](#api-reference)
- [Model Serving Demo](#model-serving-demo)
- [A/B Testing](#ab-testing)
- [Testing](#testing)
- [Redis Test Data](#redis-test-data)
- [Online Serving](#online-serving)
- [Offline Item Embeddings](#offline-item-embeddings)
- [Embedding Storage Paths](#embedding-storage-paths)
- [Developer Notes](#developer-notes)
- [LLM Integration Ideas](#llm-integration-ideas)

---

## Recommendation Flow

The project demonstrates two recommendation paths that can be run independently or together:

**Offline / batch path (Movie API, port 6010)**

Recall narrows the catalog to a candidate set; ranking scores and orders those candidates.

- **Single-strategy recall:** `CandidateGenerator.byGenre` expands from the genres of a seed movie.
- **Multi-way recall:** `CandidateGenerator.byUserHistory` merges candidates from user-history genres, global top-rated movies, and latest releases.
- **Embedding recall:** `CandidateGenerator.byEmbedding` retrieves items by ANN search on user and item embeddings.
- **Ranking:** `SimilarMovieService` scores each candidate with inner-product similarity and returns the top-K results.

**Online / real-time path (Online Prediction Server, port 7010)**

`OnlineRecommendationService` blends two live signals on every request:

- **Behavioral signals** (`OnlineRecommendationEngine`): recent per-user watch history + windowed trending Top-K, both written by the Flink job into Redis.
- **Embedding recall** (`CandidateGenerator.byEmbedding`): ANN search on offline-trained user-tower embeddings.

A normalized rank score fuses the two lists. Cold-start users with no embedding fall back to behavioral signals only. The response `strategy` field (`"online+model"` or `"online"`) shows which signals fired.

---

## Movie API

Runs the Jetty movie API on port `6010` with Redis-backed embeddings and Top-K state.

**Requirements:** Java 17, Maven, Docker with Docker Compose.

Start infrastructure:

```bash
colima start  # if you use Colima
docker compose -f docker-compose.streaming.yml up -d
```

Run the API:

```bash
mvn clean compile
mvn exec:java -Dexec.mainClass="com.recsys.serving.RecSysServer"
```

Smoke test:

```bash
curl "http://localhost:6010/health"
curl "http://localhost:6010/getmovie?id=1"
curl "http://localhost:6010/getsimilarmovie?movieId=1&k=5"
curl "http://localhost:6010/getrecommendation?userId=123&mode=embedding&k=5"
curl -X POST "http://localhost:6010/v1/models/recmodel:predict" \
  -H "Content-Type: application/json" \
  -d '{"instances":[{"userId":123,"movieId":1},{"userId":123,"movieId":2}]}'
```

Select a classpath embedding backend:

```bash
RECSYS_VECTOR_BACKEND=lsh   mvn exec:java -Dexec.mainClass="com.recsys.serving.RecSysServer"
RECSYS_VECTOR_BACKEND=exact mvn exec:java -Dexec.mainClass="com.recsys.serving.RecSysServer"
```

`lsh` is the default approximate backend. `exact` is useful for deterministic recall checks.

Stop infrastructure:

```bash
docker compose -f docker-compose.streaming.yml down
```

---

## Configuration

### Movie API (Jetty, port 6010)

| Env var | Default | Purpose |
|---|---:|---|
| `PORT` | `6010` | API server port |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `RECSYS_VECTOR_BACKEND` | `lsh` | Embedding backend: `lsh` or `exact`; `faiss` falls back to `lsh` in the portable build |

Example:

```bash
PORT=7010 REDIS_HOST=localhost REDIS_PORT=6379 \
  mvn exec:java -Dexec.mainClass="com.recsys.serving.RecSysServer"
```

On startup the server seeds Redis with bundled movie and user embeddings if the Redis keys are empty.

### Model serving service (Spring Boot, port 8080)

| Env var / property | Default | Purpose |
|---|---:|---|
| `RECSYS_MODEL_ARTIFACTS_DIR` | _(empty)_ | Model artifact directory; resolves `artifacts/model/<variant>/...`; defaults to the bundled `classpath:artifacts/model/training/` |
| `RECSYS_SPARK_ARTIFACTS_DIR` | _(empty)_ | PySpark artifact directory; overrides `classpath:artifacts/pyspark/` |
| `recsys.health.window-seconds` | `60` | Rolling window width (s) for recent failure rate, latency, and throughput metrics |
| `recsys.health.min-sample-size` | `5` | Minimum requests in the window before readiness thresholds are enforced |
| `recsys.health.max-failure-rate` | `0.5` | Failure rate `[0.0, 1.0]` above which `/health/ready` returns 503 |
| `recsys.health.max-avg-latency-ms` | `2000` | Average latency (ms) above which `/health/ready` returns 503 |

All `recsys.health.*` values are validated at startup — misconfiguration fails fast. Override via `application.yml` or environment variables (e.g. `RECSYS_HEALTH_MAX_FAILURE_RATE=0.3`).

### A/B test configuration (Model serving service)

| Property | Default | Purpose |
|---|---:|---|
| `recsys.ab-test.enabled` | `false` | Enable or disable bucketing; when `false` every user gets `default-variant` |
| `recsys.ab-test.layer-name` | `default` | Experiment name mixed into the hash key — change this to run an independent parallel experiment |
| `recsys.ab-test.traffic-split-number` | `5` | Modulus for the hash bucket; 20 % of users land in A, 20 % in B, 60 % in control |
| `recsys.ab-test.bucket-a-variant` | `test` | Variant served to users in bucket 0 |
| `recsys.ab-test.bucket-b-variant` | `training` | Variant served to users in bucket 1 |
| `recsys.ab-test.default-variant` | `training` | Variant served to all other users (control group) |

All `recsys.ab-test.*` values are validated at startup. Override via `application.yml` or environment variables (e.g. `RECSYS_AB_TEST_ENABLED=true`).

---

## Project Layout

```text
src/main/java/com/recsys/
├── models/                 Immutable API/domain records
├── features/               Data loading, indexed access, retrieval, vector math, Redis stores
├── serving/                Jetty server and servlet endpoints (port 6010)
├── streaming/              Online serving layer (port 7010)
│   ├── flink/              Flink streaming job — writes online features to Redis
│   ├── OnlineRecommendationService.java   Blends behavioral + embedding signals
│   ├── OnlineRecommendationEngine.java    Real-time scoring: recent history + trending
│   ├── OnlinePredictionServer.java        Jetty entry point
│   └── ...                (request/result records, feature stores, servlets)
├── training/
│   ├── rulebased/          Spark Word2Vec offline item embeddings
│   └── modelbased/
│       └── model/          Spring Boot ONNX model serving
│           ├── ModelApplication.java
│           ├── config/     Model artifact + A/B test configuration
│           ├── controller/ Recommendation and health APIs
│           ├── dto/        Request / response payloads
│           └── service/    Candidate selection, recall, ranking, ONNX inference, A/B bucketing
│                           ModelArtifactLocator — unified locator for model + spark artifact groups
└── data/                   Bundled sample data and seed embeddings
    ├── movies.txt
    ├── users.txt
    ├── ratings.txt
    ├── events.txt
    ├── online_features.txt
    ├── movie_embeddings.txt
    └── user_embeddings.txt

src/main/resources/artifacts/model/      Variant-aware model artifact root
├── training/
└── test/

src/main/resources/artifacts/model/training/   Bundled sample artifacts for the default variant
├── feature_config.json
├── item_embeddings.json
├── item_embeddings.faiss
├── item_ids.json
├── metadata.json
└── user_tower.onnx

docker-compose.streaming.yml              Legacy root compose for local Redis/Kafka/Flink experiments
streaming/online-serving/                 Canonical Kafka + Flink + Redis online-serving path
├── README.md
├── docker-compose.yml
├── data/movie_events.ndjson
└── scripts/
    ├── load_online_features.sh
    └── produce_movie_events.sh
```

---

## API Reference

The Jetty movie API exposes lookup, recommendation, similarity, pair-scoring, and embedding-update endpoints on port `6010`.

### Health

```bash
curl "http://localhost:6010/health"
# {"ok":true}
```

### Movie Lookup

```bash
curl "http://localhost:6010/getmovie?id=1"
# {"id":1,"title":"Inception","year":2010,"genres":["Sci-Fi","Thriller"]}
```

### User Lookup

```bash
curl "http://localhost:6010/getuser?userId=123"
# {"userId":123,"name":"Alice"}
```

### Recommendations

Four retrieval modes are supported. All require `userId`.

**Default (no `mode`) — multi-strategy by user history:**

```bash
curl "http://localhost:6010/getrecommendation?userId=123"
```

Merges three candidate pools via `CandidateGenerator.byUserHistory`: genre-based from the user's rating history (top 20 per genre), global top-100 by average rating, and latest 100 by release year. Already-watched movies are excluded.

**With `seedMovieId` — genre-based from seed:**

```bash
curl "http://localhost:6010/getrecommendation?userId=123&seedMovieId=2"
```

Uses `CandidateGenerator.byGenre`: for each genre on the seed movie, retrieves the top-100 by average rating, deduplicates, and removes the seed itself.

**`mode=embedding` — embedding-based retrieval:**

```bash
curl "http://localhost:6010/getrecommendation?userId=123&mode=embedding&k=20"
```

Uses `CandidateGenerator.byEmbedding` against classpath embeddings. Backend is controlled by `RECSYS_VECTOR_BACKEND`. Returns 404 if no user embedding is found. `k` is capped at 200 (default: 20).

Supported portable backends:

- `lsh` — approximate SimHash random-projection with inner-product reranking.
- `exact` — full-scan inner-product top-k with a bounded min-heap.

`faiss` is reserved for Linux native FAISS deployments and falls back to `lsh` in the portable build.

**`mode=topk` / `mode=trending` — Redis sorted-set trending:**

```bash
curl "http://localhost:6010/getrecommendation?userId=123&mode=topk&window=last_hour&k=5"
```

Reads pre-scored movie IDs from a Redis sorted set. Supported windows: `last_hour`, `last_day`, `last_month`.

For a separate online or near-real-time serving path backed by Kafka, Flink, and Redis, see [streaming/online-serving/README.md](streaming/online-serving/README.md).

### Similar Movies

Computes inner-product similarity against Redis item embeddings:

```bash
curl "http://localhost:6010/getsimilarmovie?movieId=1&k=5"
# {"movieId":1,"similar":[{"movieId":4,"score":0.99}, ...]}
```

### Pair Prediction

Scores explicit `(userId, movieId)` pairs with a batched JSON `POST`:

```bash
curl -X POST "http://localhost:6010/v1/models/recmodel:predict" \
  -H "Content-Type: application/json" \
  -d '{
    "instances": [
      {"userId": 123, "movieId": 1},
      {"userId": 123, "movieId": 2}
    ]
  }'
```

```json
{
  "predictions": [
    [0.9231],
    [0.7412]
  ]
}
```

Notes:

- Scores each pair independently; does not do candidate generation or top-K assembly.
- Uses bundled classpath user and movie embeddings with inner-product scoring.
- Returns `400` when `instances` is empty, IDs are non-positive, or a user/movie embedding is missing.

### Set Embedding

Stores or updates a movie embedding in Redis. Default TTL is 24 hours; use `ttl=0` for no expiry.

```bash
# Raw body
curl -X POST "http://localhost:6010/setembedding?movieId=4" \
  -H "Content-Type: text/plain" \
  --data-binary "0.2 0.2 0.6"

# Form body
curl -X POST "http://localhost:6010/setembedding?movieId=5" \
  --data-urlencode "vec=0.1 0.3 0.6"

# Query parameter with custom TTL
curl -X POST "http://localhost:6010/setembedding?movieId=6&ttl=3600&vec=0.5+0.5+0.0"
```

---

## Model Serving Demo

A separate Spring Boot service on port `8080` that serves model-based retrieval through `ModelApplication`.

**Demonstrates:**

- ONNX user-tower inference in Java
- Precomputed item embeddings
- Model-based retrieval with inner-product similarity
- A/B variant-aware runtime pre-warming at startup — all configured model variants are loaded before the first request so no user pays cold-start cost
- Per-variant latency and success-rate metrics via `GET /health/ab-tests`, with deltas vs the control
- Readiness / liveness probes that check every pre-warmed variant, not just the default
- Rolling-window inference metrics (latency, failure rate, throughput)
- Config-driven probe thresholds with startup validation

At request time, `POST /api/v1/recommend` calls `ABTestService` to deterministically assign the user to a variant, fetches the pre-warmed `ModelRuntime` from `ModelRuntimeProvider`, runs `FeatureEncoder` → ONNX inference → `RetrievalService` → `RankingService`, and records per-variant metrics in `InferenceMetricsService`. `ModelRuntimeProvider` owns the full lifecycle of every `ModelArtifactService` and `UserTowerInferenceService` instance — they are plain Java objects, not Spring beans.

`ModelArtifactLocator` resolves artifacts into two groups: **model** (`classpath:artifacts/model/<variant>/...`, overridden by `RECSYS_MODEL_ARTIFACTS_DIR`) and **spark** (`classpath:artifacts/pyspark/`, overridden by `RECSYS_SPARK_ARTIFACTS_DIR`). When no variant is specified the locator defaults to the `training` variant.

### Artifact Contract

The service expects the following files exported by your modeling pipeline:

```text
feature_config.json        User vocab and feature metadata
item_embeddings.json       Precomputed item embeddings (item_id → float[])
item_embeddings.faiss      Optional FAISS IndexFlatIP index
item_ids.json              Optional FAISS row-to-item-id mapping
metadata.json              Model version and training metadata
user_tower.onnx            Exported user tower for runtime inference
```

Point the service at your pipeline's output directory via `RECSYS_MODEL_ARTIFACTS_DIR` (see [Configuration](#configuration)). Organize variants as `<artifacts-dir>/<variant>/feature_config.json`, `<artifacts-dir>/<variant>/item_embeddings.json`, and `<artifacts-dir>/<variant>/user_tower.onnx`. When unset, the bundled sample artifacts under `classpath:artifacts/model/training/` are used.

### Feature Contract

| Input | Field |
|---|---|
| User tower | `user_id` |
| Item tower | `item_id` |

### Spring Boot Serving

```bash
# Use bundled classpath artifacts
mvn spring-boot:run

# Load artifacts from your modeling pipeline's output directory
RECSYS_MODEL_ARTIFACTS_DIR=/path/to/model/artifacts mvn spring-boot:run
```

### Recommend

```bash
curl -X POST http://localhost:8080/api/v1/recommend \
  -H 'Content-Type: application/json' \
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

`abTestVariant` is the name of the experiment variant the user was assigned to. Log this field alongside impressions and conversions to compare variants offline.

### A/B comparison metrics

Once requests have flowed through the service, compare variants directly:

```bash
curl http://localhost:8080/health/ab-tests
```

Example response:

```json
{
  "controlVariant": "training",
  "variants": {
    "training": {
      "variant": "training",
      "modelVersion": "demo-model-ratings-v1",
      "totalRequests": 120,
      "successCount": 118,
      "failureCount": 2,
      "successRate": 0.9833,
      "avgLatencyMs": 11.4,
      "successRateDeltaVsControl": 0.0,
      "avgLatencyDeltaVsControlMs": 0.0
    },
    "test": {
      "variant": "test",
      "modelVersion": "demo-model-ratings-test-v1",
      "totalRequests": 113,
      "successCount": 111,
      "failureCount": 2,
      "successRate": 0.9823,
      "avgLatencyMs": 12.1,
      "successRateDeltaVsControl": -0.001,
      "avgLatencyDeltaVsControlMs": 0.7
    }
  }
}
```

This endpoint gives you online operational comparison by variant: request volume, failure rate, average latency, and deltas versus the configured control. Pair it with offline business metrics such as CTR, watch time, or conversion for full experiment evaluation.

#### Request fields

| Field | Type | Required | Constraints |
|---|---|---|---|
| `userId` | string | yes | non-blank, max 50 chars |
| `k` | integer | no | 1–100, default `5` |
| `excludeItemIds` | string[] | no | max 500 entries, each max 50 chars |

#### Error response shape

All errors return a consistent JSON body regardless of failure type:

```json
{
  "error": "validation failed",
  "violations": [
    {"field": "k", "message": "k must be at most 100"}
  ]
}
```

Non-validation errors (`violations` is an empty array):

| Status | Cause |
|---|---|
| `400` | Bean-validation failure or service-level guard |
| `415` | Missing or wrong `Content-Type` (must be `application/json`) |
| `500` | Unhandled inference or runtime error |

### Health Probes

#### Liveness — `GET /health/live`

Always returns `200 OK` as long as the JVM's HTTP thread pool responds. Configure your orchestrator to restart the container when this endpoint times out or becomes unreachable.

```bash
curl http://localhost:8080/health/live
# {"status":"UP"}
```

#### Readiness — `GET /health/ready`

Returns `200` when the instance is fit to receive load-balancer traffic; `503` otherwise. The instance is pulled from rotation (without restart) when:

- Any pre-warmed model variant does not have a live ONNX session (checked via `ModelRuntimeProvider.areVariantsReady()`).
- The recent failure rate exceeds `recsys.health.max-failure-rate` (default 50 %).
- The average inference latency exceeds `recsys.health.max-avg-latency-ms` (default 2000 ms).

Threshold checks are skipped until `recsys.health.min-sample-size` requests are in the window, preventing false draining on cold start.

```bash
curl http://localhost:8080/health/ready
# 200: {"status":"UP","recentRequests":42,"recentFailureRate":0.02,"recentAvgLatencyMs":38.5,"throughputPerSecond":0.7}
# 503: {"status":"DOWN","reason":"high failure rate","recentFailureRate":0.6,"threshold":0.5}
```

#### Metrics — `GET /health/metrics`

Exposes both all-time counters (lock-free atomic) and rolling-window stats:

```bash
curl http://localhost:8080/health/metrics
```

```json
{
  "totalRequests": 1042,
  "successCount": 1038,
  "failureCount": 4,
  "allTimeAvgLatencyMs": 41.2,
  "recentRequests": 18,
  "recentFailures": 1,
  "recentAvgLatencyMs": 55.7,
  "recentFailureRate": 0.055,
  "throughputPerSecond": 0.3
}
```

#### Kubernetes probe config example

```yaml
livenessProbe:
  httpGet:
    path: /health/live
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10

readinessProbe:
  httpGet:
    path: /health/ready
    port: 8080
  initialDelaySeconds: 15
  periodSeconds: 5
```

Notes:

- Retrieval uses inner-product similarity in the portable Java path. If your pipeline exports a FAISS `IndexFlatIP` index (`item_embeddings.faiss` + `item_ids.json`), it is picked up automatically when `RECSYS_MODEL_ARTIFACTS_DIR` is set.
- For production-scale Java serving, use a Linux native FAISS binding (`com.criteo.jfaiss:jfaiss-cpu`) or a managed ANN service (OpenSearch kNN, Vespa, Milvus).
- Item embeddings reload on service restart; re-point `RECSYS_MODEL_ARTIFACTS_DIR` and restart to pick up a new model version.

---

## A/B Testing

`ABTestService` assigns each user to a variant deterministically by hashing `userId:layerName` modulo `trafficSplitNumber`. The result is returned in the `abTestVariant` response field so downstream logging can attribute impressions and conversions to the correct bucket.

### Bucketing logic

```
bucket = (userId + ":" + layerName).hashCode() & Integer.MAX_VALUE
         % trafficSplitNumber

bucket == 0  →  bucketAVariant  (treatment A)
bucket == 1  →  bucketBVariant  (treatment B)
otherwise    →  defaultVariant  (control)
```

With the default `trafficSplitNumber = 5`, 20 % of users land in A, 20 % in B, and 60 % in control.

### Layer isolation

The `layerName` salt is the key property for running multiple independent experiments simultaneously:

**Within the same layer — users are mutually exclusive across buckets.** A user assigned to variant A is never also assigned to variant B in the same layer. The A-population and B-population are always disjoint.

**Across different layers — bucket indices are independent.** A user can be in bucket 0 of `model-arch-test` *and* bucket 0 of `recall-strategy-test` at the same time. The two layers do not interfere.

```
Layer "model-arch-test":      user-7 → bucket 0 (test)
Layer "recall-strategy-test": user-7 → bucket 0 (test)          ← same bucket, independent layer
Layer "model-arch-test":      user-7 → bucket 0 (test)
Layer "recall-strategy-test": user-9 → bucket 2 (training)      ← different bucket, different layer
```

To run a second experiment in parallel, deploy a second instance with a different `recsys.ab-test.layer-name`; the user assignments will be orthogonal to the first experiment.

### Enable A/B testing

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

Or via environment variables:

```bash
RECSYS_AB_TEST_ENABLED=true \
RECSYS_AB_TEST_LAYER_NAME=model-arch-test-2024q2 \
  mvn spring-boot:run
```

---

## Testing

```bash
# Unit and integration tests (load tests excluded)
mvn test

# Load tests only
mvn test -DexcludedGroups="" -Dgroups=load
```

| Test class | What it covers |
|---|---|
| `ModelArtifactLocatorTest` | Classpath and external-dir resolution for model and spark artifact groups; whitespace-only override falls back to classpath |
| `ModelArtifactServiceTest` | Loads bundled `feature_config.json` and `item_embeddings.json`; asserts model version, vocab contents, embedding dimension, and immutability |
| `ModelRuntimeProviderTest` | Loads independent `training` and `test` runtimes from a temp directory; asserts each has a distinct model version and `ModelRuntime` instance |
| `FeatureEncoderTest` | Known user IDs map to their vocab indices; unknown IDs fall back to `__UNK__` (index 0) |
| `RankingServiceTest` | Items re-ordered by inner-product score descending; k-truncation, duplicate deduplication, and missing-embedding skip |
| `RetrievalServiceTest` | Embedding recall returns the top-K candidates by inner-product, ordered descending by score; null embedding, empty candidates, and unknown items are handled |
| `ABTestServiceTest` | Disabled flag, null/blank userId, per-bucket variant assignment, determinism; **same-layer** bucket-A and bucket-B populations are disjoint; **cross-layer** same bucket index is reachable and different layers diverge |
| `RecommendationServiceTest` | Service-level guards reject blank `userId` and out-of-range `k` before any downstream call; wires mocked sub-services and asserts the full response shape including `abTestVariant` |
| `RecommendationControllerTest` | Bean-validation rejections (blank userId, k out of range), malformed JSON, wrong content-type, and `IllegalArgumentException` → stable `ApiError` shape |
| `PredictionIntegrationTest` | End-to-end service pipeline against bundled classpath artifacts: ranked results, score ordering, excludeItemIds, unknown users |
| `RecommendationEndToEndTest` | Full HTTP chain (`@SpringBootTest`): controller → inference → metrics tracking; verifies `InferenceMetricsService` counters, `/health/ready`, and `/health/metrics` reflect real state |
| `InferenceLoadTest` _(tag: load)_ | 100 concurrent requests across 10 threads; reports avg latency, P95 latency, throughput (req/s), and success rate; asserts P95 ≤ 2000 ms and success rate ≥ 99 % |
| `OnlineRecommendationEngineTest` | Blends recent-history similarity with trending and excludes recently-watched movies; rejects unknown window values |
| `OnlineRecommendationServiceTest` | Blended scoring (movie in both lists ranks first), online-only fallback when no embedding, recently-watched exclusion, unknown user 404, bad window propagation |

---

## Redis Test Data

Seed trending data manually:

```bash
docker exec -it redis-dev redis-cli DEL topk:last_hour
docker exec -it redis-dev redis-cli ZADD topk:last_hour \
  2 11 1 1 1 2 1 3 1 4 1 5 1 7 1 8 1 9 1 12
docker exec -it redis-dev redis-cli ZREVRANGE topk:last_hour 0 9 WITHSCORES
```

Inspect seeded embeddings:

```bash
docker exec -it redis-dev redis-cli SCAN 0 MATCH 'i2vEmb:*' COUNT 20
docker exec -it redis-dev redis-cli GET i2vEmb:1
```

---

## Online Serving

The Kafka/Flink/Redis streaming path lives separately from the main Jetty movie API and the Spring Boot model-artifact service. At request time `OnlineRecommendationService` fuses real-time behavioral signals from Redis with offline embedding-based recall, returning a `strategy` field that shows which sources contributed.

See [streaming/online-serving/README.md](streaming/online-serving/README.md) for full setup instructions. Quick reference:

| Component | What it does |
|---|---|
| `OnlineFeatureStreamingJob` | Flink job: consumes Kafka events, writes `user:<id>:recent_movies`, `movie:<id>:views_1h`, `topk:<window>` to Redis |
| `OnlineRecommendationEngine` | Scores candidates using per-user recent history + trending rank |
| `CandidateGenerator.byEmbedding` | ANN recall on offline user-tower embeddings |
| `OnlineRecommendationService` | Blends the two sources, excludes recently watched, falls back gracefully for cold-start users |
| `OnlinePredictionServer` | Jetty HTTP server on port `7010` wiring all of the above |

Recommended entrypoint:

```bash
# 1. Start infra
docker compose -f streaming/online-serving/docker-compose.yml up -d
# 2. Load sample features into Redis (no Flink required)
sh streaming/online-serving/scripts/load_online_features.sh
# 3. Start the server
mvn exec:java -Dexec.mainClass="com.recsys.streaming.OnlinePredictionServer"
# 4. Try it
curl "http://localhost:7010/online/recommendation?userId=123&window=last_hour&k=5"
```

Legacy note: `docker-compose.streaming.yml` is still available for the older root-level setup, but `streaming/online-serving` is the maintained path.

---

## Offline Item Embeddings

**Online prediction path:**

```text
Kafka → Flink → Redis (behavioral features) ─┐
                                              ├─> OnlineRecommendationService
user embeddings (ANN recall) ─────────────────┘
```

The bundled `events.txt` rows model the Kafka payloads. The `online_features.txt` rows model the low-latency aggregates the Flink job writes into Redis (`user:<id>:recent_movies`, `movie:<id>:views_1h`, `topk:last_hour`). `OnlineRecommendationService` blends these real-time signals with offline embedding-based recall at request time.

**Offline embedding path:**

```text
Kafka / HDFS → Spark → embedding training → model registry / vector store → service
```

The bundled `ratings.txt` rows model the batch/HDFS-style positive feedback used by Spark Word2Vec. Spark dependencies are isolated behind the `offline-embedding` Maven profile and declared `provided` scope — the cluster supplies Spark at runtime.

Train Word2Vec item embeddings from bundled ratings:

```bash
mvn -Poffline-embedding exec:java \
  -Dexec.mainClass="com.recsys.training.rulebased.ItemEmbeddingJob" \
  -Dexec.args="--output=output/item_embeddings"
```

Useful options:

```text
--ratings=/path/to/ratings.csv
--output=output/item_embeddings
--vector-size=16
--window-size=5
--min-count=1
--max-iter=10
--step-size=0.025
--min-rating=3.5
--synonym-movie-id=1
```

The output CSV uses the same `movieId,vector` shape as `movie_embeddings.txt`, so generated vectors can be copied into the bundled seed data or loaded into Redis as `i2vEmb:<movieId>`.

Write embeddings directly to Redis after training:

```bash
mvn -Poffline-embedding exec:java \
  -Dexec.mainClass="com.recsys.training.rulebased.ItemEmbeddingJob" \
  -Dexec.args="--output=output/item_embeddings --save-to-redis=true --redis-host=localhost --redis-port=6379"
```

Redis options:

```text
--save-to-redis=true        Enable Redis output (default: false)
--redis-host=localhost      Redis host (default: localhost)
--redis-port=6379           Redis port (default: 6379)
--redis-key-prefix=i2vEmb  Key prefix — written as {prefix}:{movieId} (default: i2vEmb)
--redis-ttl=86400           TTL in seconds; 0 = no expiry (default: 86400)
```

All writes are pipelined in a single round-trip. Key and value formats are compatible with `RedisEmbeddingStore` and `VectorMath.parseVector`.

---

## Embedding Storage Paths

### Rule-based → Redis

`ItemEmbeddingJob` writes to Redis when `--save-to-redis=true`. `SimilarMovieService` builds a metadata candidate set, fetches only those vectors via `RedisEmbeddingStore.getEmbeddings(candidateIds)`, then ranks by inner-product.

```
Spark Word2Vec
  └─ Jedis pipeline ──► Redis (i2vEmb:{movieId} → "0.169 0.296 -0.130 ...")
                                │
                         SimilarMovieService
                           metadata candidate set → MGET embeddings → inner-product top-k
```

Key: `{prefix}:{id}` (e.g. `i2vEmb:1`) · Value: space-separated floats · TTL: 86400 s (configurable)

### Model-based → classpath

Your modeling pipeline exports `item_embeddings.json` (and optionally `user_tower.onnx`, `feature_config.json`, `metadata.json`). Point `RECSYS_MODEL_ARTIFACTS_DIR` at a directory organised as `<dir>/training/` and `<dir>/test/` for variant-aware serving, or leave it unset to use the bundled classpath artifacts under `artifacts/model/training/`. At startup, `ModelArtifactService` loads item embeddings into a `ConcurrentHashMap` in the JVM heap. Redis is not involved.

```
Modeling pipeline (any framework)
  └─ artifact export ──► artifacts/model/<variant>/   (feature_config.json, item_embeddings.json, user_tower.onnx)
                                      │
                         ModelRuntimeProvider (@PostConstruct warmUp)
                           └─ per variant: ModelArtifactService → ConcurrentHashMap
                                           UserTowerInferenceService → OrtSession
                                      │
                         CandidateSelectionService → RetrievalService → RankingService → top-k
```

TTL: none — reloads on service restart.

### Movie API → classpath

`CandidateGenerator` loads `movie_embeddings.txt` and `user_embeddings.txt` from the classpath at startup for the `mode=embedding` path. This is the same bundled seed data seeded into Redis on first start, used here for direct heap-based scoring without a Redis round-trip.

```
movie_embeddings.txt / user_embeddings.txt (classpath)
  └─ DataLoader → CandidateGenerator (JVM heap)
       └─ byEmbedding(userId, k) → VectorIndex backend → inner-product rerank → top-k
```

### Comparison

| | Rule-based (Redis) | Model-based (ONNX service) | Movie API (classpath) |
|---|---|---|---|
| Written by | Spark job → Jedis pipeline | External modeling pipeline | Bundled text resources |
| Stored in | Redis (`i2vEmb:{id}`) | Classpath + JVM heap | Classpath + JVM heap |
| Loaded by | `RedisEmbeddingStore.getEmbeddings` | `ModelRuntimeProvider.warmUp()` per variant | `CandidateGenerator` constructor |
| User vector | Not produced | Live via ONNX at request time | Preloaded from `user_embeddings.txt` |
| Retrieval backend | Metadata candidates → Redis MGET → exact inner-product | Candidate set → embedding + metadata recall → inner-product; optional FAISS | `VectorIndex`: `lsh` or `exact` |
| TTL | 86400 s default | N/A — reloads on restart | N/A — reloads on restart |

---

## Developer Notes

**Data loading:**

- `DataLoader` loads bundled text resources from `com/recsys/data`.
- `DataManager` is a read-only singleton owning immutable maps, precomputed sorted lists (`topRatedMovies`, `latestMovies`), genre indexes (`moviesByGenre`), and fast lookup helpers. Retrieval logic stays outside this class.

**Movie API retrieval:**

- `CandidateGenerator` owns Jetty recall strategies and classpath embeddings. Created once in `RecSysServer` and injected into `RecommendationService`.
- `byGenre` — seed-movie genre recall.
- `byUserHistory` — multi-way recall from user-history genres, global top-rated, and latest releases.
- `byEmbedding` — embedding recall through the `VectorIndex` interface (`lsh` or `exact`).

**Redis-backed embeddings:**

- `RedisEmbeddingStore` is a generic key-prefix store for `getEmbedding`, `setEmbedding`, `setEmbeddings`, and `scanIds`.
- Supports both `i2vEmb:` item and `u2vEmb:` user embeddings.
- Bulk writes use Redis pipelines; bulk reads use `SCAN` + `MGET` to avoid blocking large keyspace operations.

**Servlet and ranking:**

- `BaseApiServlet` centralizes JSON headers, Jackson serialization, error responses, and request parameter parsing.
- `SimilarMovieService` demonstrates candidate recall + embedding ranking: build metadata candidates, fetch vectors via Redis `MGET`, rank by inner product.

**Online serving (`com.recsys.streaming`):**

- `OnlineRecommendationEngine` — scores candidates from per-user recent-watch history (Redis) and trending Top-K (Redis sorted set). Accepts `window` (`last_hour`, `last_day`, `last_month`).
- `OnlineRecommendationService` — orchestrates `OnlineRecommendationEngine` + `CandidateGenerator.byEmbedding`. Blends normalized rank scores (`ONLINE_WEIGHT=1.0`, `MODEL_WEIGHT=0.5`), excludes recently-watched movies, and falls back to online-only for cold-start users. Returns a `strategy` field in the result.
- `OnlineFeatureStreamingJob` (profile `streaming-flink`) — Flink 1.18 job that reads `MovieEvent` records from Kafka or a local file, then writes per-user recent-movie lists, per-movie engagement metrics, and global top-K to Redis.
- `OnlinePredictionServer` — Jetty entry point on port `7010`; wires `OnlineRecommendationService` and exposes `/health`, `/online/features`, and `/online/recommendation`.

**Model serving:**

- `ModelArtifactLocator` — single artifact resolver exposing **model** (`classpath:artifacts/model/<variant>/`, overridden by `RECSYS_MODEL_ARTIFACTS_DIR`) and **spark** groups. Blank variant defaults to `training`.
- `ModelRuntimeProvider` — Spring `@Service` that owns the full lifecycle of every per-variant runtime. `@PostConstruct warmUp()` pre-loads the default variant and, when A/B testing is enabled, the A and B variants. `areVariantsReady()` checks whether all loaded runtimes have live ONNX sessions.
- `ModelArtifactService` — plain Java class (not a Spring bean); loads `feature_config.json` and `item_embeddings.json` for one variant into a `ConcurrentHashMap`. Created and called by `ModelRuntimeProvider`.
- `UserTowerInferenceService` — plain Java class (not a Spring bean); manages a single `OrtSession` for one variant. Created and initialized by `ModelRuntimeProvider`; closed on `@PreDestroy`.
- `CandidateSelectionService` — plain Java class (not a Spring bean); builds the candidate pool from user-history genres, global top-rated, and latest releases. Excluded item IDs are filtered eagerly inside `addIfAvailable` (before insertion) rather than removed in bulk at the end.
- `RetrievalService` — plain Java class (not a Spring bean); merges embedding-based and metadata-based recall. Embedding recall returns candidates sorted descending by inner-product score. Metadata recall exits as soon as `recallSize` is reached to avoid wasted iteration.
- `RankingService` — plain Java class (not a Spring bean); scores recalled candidates and returns the top-k.
- `ABTestConfig` — `@ConfigurationProperties(prefix = "recsys.ab-test")` with `@Validated` startup checks; holds `layerName`, `trafficSplitNumber`, variant names, and the `enabled` flag.
- `ABTestService` — hashes `userId:layerName` to a bucket index; returns a typed `Assignment` record (variant, bucket, layerName, inExperiment). Same layer → mutually exclusive buckets; different layers → independent assignments.
- `InferenceMetricsService` — global rolling-window metrics plus per-variant counters. `abTestSnapshot(controlVariant)` computes success-rate and latency deltas vs control, exposed at `GET /health/ab-tests`.
- `HealthProperties` — `@ConfigurationProperties(prefix = "recsys.health")` with `@Validated` startup checks; all probe thresholds in one place, overridable via env vars.
- `HealthController` — `/health/live` (liveness), `/health/ready` (readiness gated on `ModelRuntimeProvider.areVariantsReady()` and rolling metrics), `/health/metrics` (global snapshot), `/health/ab-tests` (per-variant comparison snapshot).
- `GlobalExceptionHandler` — maps bean-validation failures, malformed JSON, wrong content-type, and unexpected errors to a consistent `ApiError` shape.

---

## LLM Integration Ideas

- Use text embeddings as item/user features for retrieval.
- Use an LLM as a zero-shot ranker or reranker for diversity, freshness, and domain-specific constraints.
- Fine-tune for direct item generation when supervised recommendation data is available.
- Add conversational recommendation on top of the existing serving layer.
