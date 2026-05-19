# RecSys

RecSys is a compact Maven workspace for experimenting with recommendation-system serving, retrieval, ranking, and offline embedding pipelines.

| Area | What it shows |
|---|---|
| Recommendation Serving API | Jetty, Redis, local item data, multi-strategy retrieval, and runtime embedding updates |
| Model serving demo | Spring Boot ONNX scoring with variant-aware model artifact loading |
| Rule-based offline embeddings | Spark Word2Vec item embeddings trained from user interaction sequences |
| Model-based offline training | PyTorch-exported ONNX models plus vocab/config artifacts generated offline |
| Online learning | Streaming feedback updates lightweight serving parameters outside the PyTorch model |

![Architecture](architecture.png)

---

## Contents

- [Recommendation Flow](#recommendation-flow)
- [Recommendation Serving API](#recommendation-serving-api)
- [Configuration](#configuration)
- [Capacity Planning](#capacity-planning)
- [JVM Tuning](#jvm-tuning)
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
- [Pipeline Optimizations](#pipeline-optimizations)
- [LLM Integration Ideas](#llm-integration-ideas)

---

## Recommendation Flow

The project demonstrates two recommendation paths that can be run independently or together:

**Offline / batch path (Recommendation Serving API, port 6010)**

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

## Capacity Planning

This demo is sized for local development, but the production shape should be planned around the online serving path:

| Dimension | Production target / assumption | Design implication |
|---|---:|---|
| DAU / system daily active users | `200w+` users | Keep per-user online state compact: recent history lists, counters, and small learned parameters rather than large mutable profiles |
| Peak read QPS | `8k` recommendation requests/s | Serve hot features from local JVM cache first, Redis second; keep request-time ranking bounded by candidate count |
| Event TPS | Higher than read QPS during traffic bursts | Write behavior logs to MQ/Kafka first, then let Flink consume and aggregate asynchronously |
| Data scale | User history, item embeddings, engagement counters, Top-K windows | Store durable model artifacts offline; store online features in Redis with key prefixes, TTLs, and bounded Top-K/list sizes |
| Machine scale | Horizontally scaled stateless API + partitioned stream workers + Redis cluster/sentinel | Add serving instances behind a load balancer; scale Flink/Kafka by partitions; shard or cluster Redis by feature family |

For `200w+` DAU and `8k` peak QPS, the serving API should avoid synchronous heavy feature construction. Redis stores the latest online features (`user:<id>:recent_movies`, `movie:<id>:metrics`, `topk:<window>`) and model/vector side data that must be read with low latency. MQ/Kafka absorbs write spikes from exposure/click/view/order logs, and Flink smooths that bursty TPS into incremental Redis updates. This Redis + MQ peak-shaving pattern keeps recommendation reads predictable even when event traffic temporarily exceeds steady-state processing capacity.

The online-serving code includes runtime support for these assumptions:

- `OnlineServingMetricsService` tracks rolling QPS, latency, failures, rejected requests, and per-strategy failure rate and traffic mix (`share`).
- `OnlineLoadShedder` limits concurrent online requests; returns `429` with a `Retry-After` header when the instance is draining.
- `OnlineCapacityService` exposes DAU/QPS/TPS targets, remaining QPS `headroomQps`, and an `overloaded` flag alongside observed traffic.
- `/health` reports readiness, current QPS, in-flight requests, and suggested load-balancer weight.
- `/online/ops` returns metrics, load-shedder state, and capacity targets in one JSON payload with a `servedAt` ISO-8601 timestamp; also sets `Retry-After` when the shedder is draining.

Related production concerns:

- **Latency SLO:** track p50/p95/p99 end-to-end latency separately for recall, Redis reads, ranking, and response serialization.
- **Cache hit rate:** watch JVM local-cache hit rate and Redis MGET latency; hot embeddings and Top-K windows should avoid repeated cold reads.
- **Backpressure:** monitor Kafka consumer lag, Flink checkpoint duration, and Redis write latency so bursty TPS does not silently stale online features.
- **Degradation:** when Redis or model inference is slow, fall back to cached Top-K/trending recommendations and cap candidate counts.
- **Capacity triggers:** scale API replicas on QPS/CPU/p99 latency, Kafka/Flink on lag and processing time, and Redis on memory, ops/s, network, and hot-key pressure.
- **Consistency:** avoid cross-system distributed transactions across Kafka/Flink/Redis; use at-least-once MQ delivery, event-id idempotency, and Redis last-write-wins timestamps for eventual consistency.

---

## JVM Tuning

The runnable JVM workloads use explicit option profiles under `config/jvm/` and a shared launcher:

```bash
sh scripts/run-with-jvm-tuning.sh <profile> -- <maven command...>
```

Profiles:

| Profile | JVM options file | Run target |
|---|---|---|
| `recsys-serving` | `config/jvm/recsys-serving.jvmopts` | Jetty Recommendation Serving API, port `6010` |
| `model-serving` | `config/jvm/model-serving.jvmopts` | Spring Boot ONNX model service, port `8080` |
| `online-serving` | `config/jvm/online-serving.jvmopts` | Jetty Online Prediction Server, port `7010` |
| `offline-embedding` | `config/jvm/offline-embedding.jvmopts` | Local offline embedding / Spark driver runs |

Tuning starts from the JVM memory model:

| Area | What this service uses it for | Tuning control |
|---|---|---|
| Heap (`堆`) | Movie/user data, embeddings, vector indexes, local caches, request/response objects | `-Xms`, `-Xmx`, cache-size env vars such as `ONLINE_FEATURE_CACHE_MAX_USERS` and `RECSYS_RECOMMENDATION_CACHE_MAX_ENTRIES` |
| Thread stack (`栈`) | Jetty/Tomcat request threads, Redis calls, Spark helper threads | `-Xss`; serving profiles use `512k`, offline embedding uses `1m` |
| Method area / Metaspace (`方法区` / `元空间`) | Spring Boot, Jetty, Flink/Spark, ONNX/Jedis/Jackson class metadata | `-XX:MaxMetaspaceSize`; larger for Spring Boot and offline Spark runs |
| Direct/native memory | ONNX Runtime native buffers, NIO/direct buffers, JVM internals | `-XX:MaxDirectMemorySize`; larger in `model-serving` because ONNX uses native memory outside the Java heap |
| Code cache | JIT-compiled hot paths for ranking, vector math, cache, metrics, Spark/Scala code | `-XX:ReservedCodeCacheSize` |

All profiles use G1 GC, bounded pause targets, string deduplication, heap dumps on OOM, and rotating GC/safepoint logs under `logs/`. For production containers, set the process/container memory limit above `Xmx + MaxMetaspaceSize + MaxDirectMemorySize + thread_count * Xss + JVM/native overhead`; a practical first pass is 25-35% headroom above those explicit caps.

---

## Recommendation Serving API

Runs the Jetty recommendation serving API on port `6010` with Redis-backed embeddings and Top-K state.

**Requirements:** Java 17, Maven, Docker with Docker Compose.

Start infrastructure:

```bash
colima start  
docker compose -f docker-compose.streaming.yml up -d
```

Run the API:

```bash
mvn clean compile
sh scripts/run-with-jvm-tuning.sh recsys-serving -- \
  mvn exec:java -Dexec.mainClass="com.recsys.serving.RecSysServer"
```

Smoke test:

```bash
curl "http://localhost:6010/health"
curl "http://localhost:6010/item?id=1"
curl "http://localhost:6010/similar?movieId=1&k=5"
curl "http://localhost:6010/getrecommendation?userId=123&mode=embedding&k=5"
curl -X POST "http://localhost:6010/v1/models/recmodel:predict" \
  -H "Content-Type: application/json" \
  -d '{"instances":[{"userId":123,"movieId":1},{"userId":123,"movieId":2}]}'
```

Select a classpath embedding backend:

```bash
RECSYS_VECTOR_BACKEND=lsh sh scripts/run-with-jvm-tuning.sh recsys-serving -- \
  mvn exec:java -Dexec.mainClass="com.recsys.serving.RecSysServer"
RECSYS_VECTOR_BACKEND=exact sh scripts/run-with-jvm-tuning.sh recsys-serving -- \
  mvn exec:java -Dexec.mainClass="com.recsys.serving.RecSysServer"
```

`lsh` is the default approximate backend. `exact` is useful for deterministic recall checks.

Stop infrastructure:

```bash
docker compose -f docker-compose.streaming.yml down
```

---

## Configuration

### Recommendation Serving API (Jetty, port 6010)

| Env var | Default | Purpose |
|---|---:|---|
| `PORT` | `6010` | API server port |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `RECSYS_VECTOR_BACKEND` | `lsh` | Embedding backend: `lsh` or `exact`; `faiss` falls back to `lsh` in the portable build |

Example:

```bash
PORT=7010 REDIS_HOST=localhost REDIS_PORT=6379 \
  sh scripts/run-with-jvm-tuning.sh recsys-serving -- \
  mvn exec:java -Dexec.mainClass="com.recsys.serving.RecSysServer"
```

On startup the server seeds Redis with bundled movie and user embeddings if the Redis keys are empty.

### Model serving service (Spring Boot, port 8080)

| Env var / property | Default | Purpose |
|---|---:|---|
| `RECSYS_MODEL_ARTIFACTS_DIR` | _(empty)_ | Model artifact directory; resolves `artifacts/model/<variant>/...`; defaults to the bundled `classpath:artifacts/model/training/` |
| `RECSYS_MODEL_ITEM_EMBEDDINGS_SOURCE` | `classpath` | Model-serving item embedding source: `classpath` for `item_embeddings.json`, or `redis` for preloaded Redis embeddings |
| `RECSYS_MODEL_REDIS_ITEM_EMBEDDING_PREFIX` | `i2vEmb` | Redis key prefix used when model-serving item embeddings are loaded from Redis |
| `RECSYS_SPARK_ARTIFACTS_DIR` | _(empty)_ | PySpark artifact directory; overrides `classpath:artifacts/pyspark/` |
| `recsys.health.window-seconds` | `60` | Rolling window width (s) for recent failure rate, latency, and throughput metrics |
| `recsys.health.min-sample-size` | `5` | Minimum requests in the window before readiness thresholds are enforced |
| `recsys.health.max-failure-rate` | `0.5` | Failure rate `[0.0, 1.0]` above which `/health/ready` returns 503 |
| `recsys.health.max-avg-latency-ms` | `2000` | Average latency (ms) above which `/health/ready` returns 503 |
| `recsys.health.max-concurrent-requests` | `64` | Per-instance in-flight recommendation cap; excess requests fail fast with `503` |
| `recsys.health.max-in-flight-utilization` | `0.95` | In-flight utilization above which `/health/ready` returns `503` so load balancers drain the node |
| `MYSQL_ENABLED` | `false` | Optional MySQL access switch; disabled by default so normal serving paths do not open DB connections |
| `MYSQL_URL` | `jdbc:mysql://localhost:3306/recsys?...` | JDBC URL used only by explicit `MySqlClient` callers |
| `MYSQL_USER` | `recsys` | MySQL username |
| `MYSQL_PASSWORD` | _(empty)_ | MySQL password |

All `recsys.health.*` values are validated at startup — misconfiguration fails fast. Override via `application.yml` or environment variables (e.g. `RECSYS_HEALTH_MAX_FAILURE_RATE=0.3`).

MySQL support is intentionally minimal: the repo includes the runtime JDBC driver plus `com.recsys.mysql.MySqlClient`, but no JPA, no connection pool, and no startup connection. Use it only in repository code that explicitly needs SQL-backed reads, such as the million-scale pagination plans in `com.recsys.pagination`.

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
├── mysql/                  Optional JDBC helper and connection settings (MySQL opt-in)
├── pagination/             SQL templates for million-row pagination (covering index, cursor, delayed join)
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
└── dssm_model.onnx

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

### Item Lookup

```bash
curl "http://localhost:6010/item?id=1"
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

### Similar Items

Computes inner-product similarity against Redis item embeddings:

```bash
curl "http://localhost:6010/similar?movieId=1&k=5"
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

- DSSM ONNX inference in Java
- Offline model artifacts generated by a PyTorch/ONNX training pipeline
- Candidate pair scoring with user/item vocab lookup
- A/B variant-aware runtime pre-warming at startup — all configured model variants are loaded before the first request so no user pays cold-start cost
- Per-variant latency and success-rate metrics via `GET /health/ab-tests`, with deltas vs the control
- Readiness / liveness probes that check every pre-warmed variant, not just the default
- Rolling-window inference metrics (latency, failure rate, throughput)
- Config-driven probe thresholds with startup validation

At request time, `POST /api/v1/recommend` calls `ABTestService` to deterministically assign the user to a variant, fetches the pre-warmed `ModelRuntime` from `ModelRuntimeProvider`, runs `FeatureEncoder` → DSSM ONNX pair scoring, and records per-variant metrics in `InferenceMetricsService`. `ModelRuntimeProvider` owns the full lifecycle of every `ModelArtifactService` and `UserTowerInferenceService` instance — they are plain Java objects, not Spring beans.

The model artifact itself is offline-trained: PyTorch exports the ONNX file, and the same pipeline emits stable serving artifacts such as vocab/config metadata and pretrained item embeddings. To keep the deployable model small, the production export can strip the item-embedding table out of the ONNX artifact and publish those vectors into Redis as key-value records. Real-time streaming data is not used to mutate the ONNX weights in-process. Online learning lives in a separate layer for fast-changing function parameters, blending weights, recency/trending coefficients, thresholds, or other lightweight serving knobs learned from Kafka/Flink feedback streams and published to the online serving layer.

`ModelArtifactLocator` resolves artifacts into two groups: **model** (`classpath:artifacts/model/<variant>/...`, overridden by `RECSYS_MODEL_ARTIFACTS_DIR`) and **spark** (`classpath:artifacts/pyspark/`, overridden by `RECSYS_SPARK_ARTIFACTS_DIR`). When no variant is specified the locator defaults to the `training` variant.

### Artifact Contract

The service expects the following files exported by your modeling pipeline:

```text
feature_config.json        User vocab and feature metadata
item_embeddings.json       Optional serialized pretrained item embeddings (item_id → float[])
item_embeddings.faiss      Optional FAISS IndexFlatIP index
item_ids.json              Optional FAISS row-to-item-id mapping
metadata.json              Model version and training metadata
dssm_model.onnx            Exported DSSM model for runtime pair scoring
```

Point the service at your pipeline's output directory via `RECSYS_MODEL_ARTIFACTS_DIR` (see [Configuration](#configuration)). Organize variants as `<artifacts-dir>/<variant>/feature_config.json` plus the configured ONNX model file, or leave the model file on the classpath root for local demos. `RECSYS_MODEL_FILE` defaults to `dssm_model.onnx`. When `RECSYS_MODEL_ARTIFACTS_DIR` is unset, the bundled sample artifacts under `classpath:artifacts/model/training/` are used.

The full production artifact set is expected to come from an external PyTorch/ONNX training export pipeline. The bundled DSSM ONNX file and config are small demo artifacts.

The repo can generate sample offline item embeddings with Spark Word2Vec:

```bash
sh scripts/run-with-jvm-tuning.sh offline-embedding -- \
  mvn -Poffline-embedding exec:java \
  -Dexec.mainClass="com.recsys.training.rulebased.ItemEmbeddingJob" \
  -Dexec.args="--output=output/item_embeddings"
```

To preload those item embeddings into Redis for stripped-embedding model serving:

```bash
sh scripts/run-with-jvm-tuning.sh offline-embedding -- \
  mvn -Poffline-embedding exec:java \
  -Dexec.mainClass="com.recsys.training.rulebased.ItemEmbeddingJob" \
  -Dexec.args="--output=output/item_embeddings --save-to-redis=true --redis-host=localhost --redis-port=6379"
```

Then run the Spring Boot model service with Redis-backed item embeddings:

```bash
RECSYS_MODEL_ITEM_EMBEDDINGS_SOURCE=redis \
RECSYS_MODEL_REDIS_ITEM_EMBEDDING_PREFIX=i2vEmb \
RECSYS_MODEL_ARTIFACTS_DIR=/path/to/model/artifacts \
sh scripts/run-with-jvm-tuning.sh model-serving -- mvn spring-boot:run
```

### Feature Contract

| Input | Field |
|---|---|
| User tower | `user_id` |
| Item tower | `item_id` |

### Spring Boot Serving

```bash
# Use bundled classpath artifacts
sh scripts/run-with-jvm-tuning.sh model-serving -- mvn spring-boot:run

# Load artifacts from your modeling pipeline's output directory
RECSYS_MODEL_ARTIFACTS_DIR=/path/to/model/artifacts \
  sh scripts/run-with-jvm-tuning.sh model-serving -- mvn spring-boot:run
```

### Version Controller

`VersionController` manages the model update path after offline training finishes. A new PyTorch/ONNX export is first written under a new artifact variant, such as `<artifacts-dir>/candidate-v2/feature_config.json` plus the configured ONNX model file. The serving process can then preload the candidate, verify that its ONNX session is ready, and promote it to default traffic without directly overwriting the active model files.

```bash
# See the active model and all loaded variants
curl http://localhost:8080/api/v1/model/versions

# Warm a newly exported model variant before sending traffic to it
curl -X POST http://localhost:8080/api/v1/model/versions/preload \
  -H 'Content-Type: application/json' \
  -d '{"variant": "candidate-v2"}'

# Promote the warmed variant to default traffic
curl -X POST http://localhost:8080/api/v1/model/versions/activate \
  -H 'Content-Type: application/json' \
  -d '{"variant": "candidate-v2"}'

# Roll back to the previous active variant
curl -X POST http://localhost:8080/api/v1/model/versions/rollback
```

Activation updates the in-memory default variant used by `ABTestService`; it does not retrain the model or mutate ONNX weights. In production, persist the promoted version in your deployment/config system after validation so a restart comes back on the intended active version.

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
- In-flight recommendation utilization exceeds `recsys.health.max-in-flight-utilization`.
- The recent failure rate exceeds `recsys.health.max-failure-rate` (default 50 %).
- The average inference latency exceeds `recsys.health.max-avg-latency-ms` (default 2000 ms).

Threshold checks are skipped until `recsys.health.min-sample-size` requests are in the window, preventing false draining on cold start.

```bash
curl http://localhost:8080/health/ready
# 200: {"status":"UP","recentRequests":42,"recentFailureRate":0.02,"recentAvgLatencyMs":38.5,"throughputPerSecond":0.7,"inFlightRequests":7,"maxConcurrentRequests":64,"utilization":0.109,"suggestedWeight":89}
# 503: {"status":"DOWN","reason":"high failure rate","recentFailureRate":0.6,"threshold":0.5}
```

#### Load signal — `GET /health/load`

Returns the node-local concurrency snapshot used by readiness. External balancers that support dynamic weights can use `suggestedWeight` as a simple capacity signal; orchestrators that only understand healthy/unhealthy should keep using `/health/ready`.

```bash
curl http://localhost:8080/health/load
```

```json
{
  "inFlightRequests": 7,
  "maxConcurrentRequests": 64,
  "utilization": 0.109375,
  "maxReadinessUtilization": 0.95,
  "acceptedRequests": 1042,
  "rejectedRequests": 3,
  "suggestedWeight": 89
}
```

#### Overload protection

`POST /api/v1/recommend` is guarded by a per-instance concurrency limiter before model inference runs. When all request slots are occupied, the service returns `503 Service Unavailable` with `Retry-After: 1` instead of queueing indefinitely. This protects tail latency and lets upstream load balancers retry another healthy replica.

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

For Nginx, Envoy, ALB, or Kubernetes Service routing, deploy multiple identical model-serving pods and point the balancer at `/api/v1/recommend`; use `/health/ready` as the upstream health check. Liveness should only restart dead processes, while readiness and overload shedding handle normal traffic spikes without killing warm model runtimes.

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
  sh scripts/run-with-jvm-tuning.sh model-serving -- mvn spring-boot:run
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
| `ModelArtifactServiceTest` | Loads bundled `feature_config.json`; asserts model version, vocab contents, item vocab, and immutable fallback collections |
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

This is also where online learning belongs. The streaming samples can update function-level serving parameters independently of the PyTorch/ONNX model: for example source-blending weights, recency decay, trending boosts, exploration rates, business-rule coefficients, or calibration thresholds. Those parameters should be stored in a fast serving store such as Redis or a config service, then read by the online serving layer without rebuilding or re-exporting the ONNX artifact.

See [streaming/online-serving/README.md](streaming/online-serving/README.md) for full setup instructions. Quick reference:

| Component | What it does |
|---|---|
| `LogCollector` | App/API boundary for exposure, click, view, like, and order logs; validates and emits Kafka-ready JSON lines |
| `OnlineJoiner` | Joins behavior logs with user/item/context features and emits labeled samples for training streams |
| `ExperienceCollector` | Groups joined point samples by request/list and emits ranked recommendation experiences for listwise training |
| `OnlineLearner` | Consumes listwise experiences and updates lightweight serving parameters without retraining PyTorch/ONNX artifacts |
| `OnlineFeatureStreamingJob` | Flink job: consumes Kafka events, deduplicates by `eventId`, writes `user:<id>:recent_movies`, engagement metrics, and `topk:<window>` to Redis |
| `OnlineRecommendationEngine` | Scores candidates using per-user recent history + trending rank |
| `CandidateGenerator.byEmbedding` | ANN recall on offline user-tower embeddings |
| `OnlineRecommendationService` | Blends the two sources, excludes recently watched, falls back gracefully for cold-start users |
| `OnlineServingMetricsService` | Tracks rolling QPS, latency, failures, rejected requests, and per-strategy failure rate and traffic mix (`share`) |
| `OnlineLoadShedder` | Caps per-instance in-flight requests; sheds overload with HTTP `429` + `Retry-After` header |
| `OnlineCapacityService` | Exposes DAU/QPS/TPS sizing assumptions, remaining QPS headroom (`headroomQps`), and an `overloaded` flag alongside observed traffic |
| `OnlineOpsServlet` | Returns combined metrics/load/capacity snapshot at `GET /online/ops` with a `servedAt` timestamp; sets `Retry-After` when draining |
| `OnlinePredictionServer` | Jetty HTTP server on port `7010` exposing `/health`, `/online/features`, `/online/recommendation`, and `/online/ops` |

Recommended entrypoint:

```bash
# 1. Start infra
docker compose -f streaming/online-serving/docker-compose.yml up -d
# 2. Load sample features into Redis (no Flink required)
sh streaming/online-serving/scripts/load_online_features.sh
# 3. Start the server
sh scripts/run-with-jvm-tuning.sh online-serving -- \
  mvn exec:java -Dexec.mainClass="com.recsys.streaming.OnlinePredictionServer"
# 4. Try it
curl "http://localhost:7010/online/recommendation?userId=123&window=last_hour&k=5"
curl "http://localhost:7010/online/ops"
```

Online-serving environment knobs:

| Env var | Default | Purpose |
|---|---:|---|
| `ONLINE_DEMO_PORT` | `7010` | Online Jetty server port |
| `ONLINE_MAX_CONCURRENT_REQUESTS` | `512` | Per-instance in-flight request cap before returning `429` |
| `ONLINE_DRAIN_UTILIZATION` | `0.90` | Utilization threshold where `/health` returns `503` for load-balancer drain |
| `ONLINE_REDIS_RATE_LIMIT_QPS` | `0` | Optional Redis-backed cross-instance request limit; `0` disables distributed rate limiting |
| `ONLINE_REDIS_RATE_LIMIT_WINDOW_SECONDS` | `1` | Redis rate-limit window size |
| `ONLINE_FEATURE_CACHE_MAX_USERS` | `10000` | Max users kept in the short-TTL recent-history JVM cache |
| `ONLINE_METRICS_WINDOW_SECONDS` | `60` | Rolling metrics window for QPS, latency, failures, and rejected requests |
| `ONLINE_TARGET_DAU` | `2000000` | Runtime capacity assumption for daily active users |
| `ONLINE_PEAK_QPS` | `8000` | Runtime peak read-QPS target |
| `ONLINE_PEAK_TPS` | `20000` | Runtime peak event-TPS target used for sizing notes |

Legacy note: `docker-compose.streaming.yml` is still available for the older root-level setup, but `streaming/online-serving` is the maintained path.

---

## Offline Item Embeddings

**Online prediction path:**

```text
LogCollector → Kafka → OnlineJoiner → ExperienceCollector ──► OnlineLearner ──► serving parameters
                                             │
                                             └───────────────► training streams / HDFS
                         │
                         └─► Flink → Redis (behavioral features) ─┐
                                                                  ├─> OnlineRecommendationService
user embeddings (ANN recall) ─────────────────────────────────────┘
```

The bundled `events.txt` rows model the Kafka payloads produced by `LogCollector`. `OnlineJoiner` models the step that joins those logs with user, item, and context features to produce labeled samples. `ExperienceCollector` groups those point samples back into ranked recommendation-list experiences keyed by user and request/list ID, which is the shape used by listwise online and offline training. `OnlineLearner` is the online-training counterpart: it consumes those list experiences, updates lightweight item-bias parameters in the serving process, and lets `OnlineRecommendationService` apply those adjustments at ranking time. This is intentionally not PyTorch/ONNX retraining; it is real-time parameter learning from the stream. The `online_features.txt` rows model the low-latency aggregates the Flink job writes into Redis (`user:<id>:recent_movies`, engagement counters, `topk:last_hour`). `OnlineRecommendationService` blends these real-time signals with offline embedding-based recall at request time.

**Data processing contracts:**

- `EventSemantics` is the shared normalization and label policy for `LogCollector` and `OnlineJoiner`: impressions/exposures label `0`, clicks or meaningful views label `1`, likes/high ratings label `2`, and orders/purchases label `3`.
- `LogCollector` sanitizes feature maps before emitting Kafka-ready JSON: blank keys and null values are dropped, keys/values are trimmed, and output order is deterministic.
- `OnlineJoiner` namespaces features as `user.*`, `item.*`, `context.*`, and `event.*`, then produces immutable joined samples.
- `ExperienceCollector` groups by `userId + event.requestId`, sorts by `event.rank`, and compacts duplicate movie feedback within the same request by keeping the strongest label.
- `OnlineLearner` performs bounded online updates over list experiences and exposes item-level score adjustments to serving.
- `OnlineFeatureStreamingJob` treats Kafka/Flink/Redis as an eventually consistent pipeline rather than a distributed transaction. It deduplicates by `eventId` with Flink state TTL and writes Redis feature keys with `:updated_at` companion keys so stale window snapshots cannot overwrite newer state.

**Offline embedding path:**

```text
Kafka / HDFS → Spark → embedding training → model registry / vector store → service
```

The bundled `ratings.txt` rows model the batch/HDFS-style positive feedback used by Spark Word2Vec. Spark dependencies are isolated behind the `offline-embedding` Maven profile and declared `provided` scope — the cluster supplies Spark at runtime.

Training is split into two loops:

- Offline training generates durable artifacts such as item embeddings and user-tower/model files. Those artifacts are exported, loaded by the serving layer, and changed on a release/reload cadence.
- Online learning consumes `ExperienceCollector` output from the real-time stream and updates small serving parameters continuously. In this demo `OnlineLearner` maintains bounded per-item bias terms, so fresh feedback can influence online recommendations without rebuilding offline embeddings or retraining an ONNX/PyTorch model.

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

### Model-based → offline artifacts + Redis embeddings

The model-serving path treats PyTorch/ONNX artifacts as offline-trained assets. The model's embedding layer is trained offline in PyTorch; item embeddings are exported as pretrained vectors and preloaded into Redis (`i2vEmb:{movieId}` by default). Keeping the item-embedding table in Redis instead of packaging it inside the ONNX file reduces the online model size and makes deployment cheaper.

The bundled DSSM demo loads a configured ONNX file, `feature_config.json`, user vocab, and item vocab, then scores candidate `(user_id, item_id)` pairs in Java through ONNX Runtime. In a production stripped-embedding setup, the ONNX artifact should consume compact IDs or features while item vectors are fetched from Redis for retrieval/ranking paths that need them.

For local demos, your modeling pipeline can export `feature_config.json`, `metadata.json`, the configured ONNX file (`RECSYS_MODEL_FILE`, default `dssm_model.onnx`), and optional `item_embeddings.json`. Point `RECSYS_MODEL_ARTIFACTS_DIR` at a directory organised as `<dir>/training/` and `<dir>/test/` for variant-aware serving, or leave it unset to use the bundled classpath artifacts under `artifacts/model/training/`.

For Redis-backed serving, preload the PyTorch-trained item vectors as Redis key-values and start the service with:

```bash
RECSYS_MODEL_ITEM_EMBEDDINGS_SOURCE=redis \
RECSYS_MODEL_REDIS_ITEM_EMBEDDING_PREFIX=i2vEmb \
sh scripts/run-with-jvm-tuning.sh model-serving -- mvn spring-boot:run
```

`ModelArtifactService` still loads `feature_config.json` from the artifact bundle so it can validate metadata and vocab mappings. Item vectors come from the offline export, either as `item_embeddings.json` for local demos or `RedisEmbeddingStore.loadAll()` for production-style serving.

```
Modeling pipeline (any framework)
  ├─ compact model export ──► artifacts/model/<variant>/   (feature_config.json, dssm_model.onnx)
  └─ pretrained item embedding export ──► Redis i2vEmb:{movieId} → "0.169 0.296 -0.130 ..."
                                      │
                         ModelRuntimeProvider (@PostConstruct warmUp)
                           └─ per variant: ModelArtifactService → vocab/config from artifacts
                                           RedisEmbeddingStore → item vectors from Redis
                                           UserTowerInferenceService → OrtSession
                                      │
                         CandidateSelectionService → DSSM ONNX pair scoring → top-k
```

TTL: Redis-configurable for key-value embeddings; classpath artifacts reload on service restart.

### Recommendation Serving API → classpath

`CandidateGenerator` loads `movie_embeddings.txt` and `user_embeddings.txt` from the classpath at startup for the `mode=embedding` path. This is the same bundled seed data seeded into Redis on first start, used here for direct heap-based scoring without a Redis round-trip.

```
movie_embeddings.txt / user_embeddings.txt (classpath)
  └─ DataLoader → CandidateGenerator (JVM heap)
       └─ byEmbedding(userId, k) → VectorIndex backend → inner-product rerank → top-k
```

### Comparison

| | Rule-based (Redis) | Model-based (ONNX service) | Serving API (classpath) |
|---|---|---|---|
| Written by | Spark job → Jedis pipeline | External PyTorch/ONNX pipeline; pretrained item embeddings preloaded to Redis | Bundled text resources |
| Stored in | Redis (`i2vEmb:{id}`) | Compact ONNX + config/vocab artifacts; item embeddings in Redis key-value records | Classpath + JVM heap |
| Loaded by | `RedisEmbeddingStore.getEmbeddings` | `ModelRuntimeProvider.warmUp()` per variant; `ModelArtifactService` loads config/vocab and Redis item vectors | `CandidateGenerator` constructor |
| User vector | Not produced | Encoded user/item IDs scored live by ONNX | Preloaded from `user_embeddings.txt` |
| Retrieval backend | Metadata candidates → Redis MGET → exact inner-product | Candidate set → DSSM ONNX pair scoring | `VectorIndex`: `lsh` or `exact` |
| TTL | 86400 s default | Redis-configurable for key-value item embeddings; classpath artifacts reload on restart | N/A — reloads on restart |

---

## Developer Notes

**Data loading:**

- `DataLoader` loads bundled text resources from `com/recsys/data`.
- `DataManager` is a read-only singleton owning immutable maps, precomputed sorted lists (`topRatedMovies`, `latestMovies`), genre indexes (`moviesByGenre`), and fast lookup helpers. Retrieval logic stays outside this class.

**Serving API retrieval:**

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

- `LogCollector` — validates app behavior logs, sanitizes feature maps, and normalizes them into the JSON shape consumed by Kafka/Flink.
- `OnlineJoiner` — joins behavior logs with user/item/context features, applies shared label semantics, and produces immutable labeled samples for online/offline model updates.
- `ExperienceCollector` — groups joined samples by `userId + event.requestId`, orders items by displayed rank, compacts duplicate item feedback, and emits list-shaped recommendation experiences.
- `OnlineLearner` — consumes recommendation experiences and updates per-item bias parameters used by `OnlineRecommendationService`. Biases are bounded by `maxItemCount` (default 10,000) with LRU-style eviction of the lowest-magnitude entries. `flushToRedis` / `loadFromRedis` persist the learned state across restarts.
- `OnlineFeatureStore` — reads per-user recent history from Redis and keeps a bounded short-TTL JVM cache for hot users (`ONLINE_FEATURE_CACHE_MAX_USERS`).
- `OnlineRecommendationEngine` — scores candidates from per-user recent-watch history (Redis) and trending Top-K (Redis sorted set). Accepts `window` (`last_hour`, `last_day`, `last_month`).
- `OnlineRecommendationService` — orchestrates `OnlineRecommendationEngine` + `CandidateGenerator.byEmbedding`. Blends normalized rank scores (`ONLINE_WEIGHT=1.0`, `MODEL_WEIGHT=0.5`), excludes recently-watched movies, and falls back to online-only for cold-start users. Returns a `strategy` field in the result.
- `OnlineFeatureStreamingJob` (profile `streaming-flink`) — Flink 1.18 job that reads `MovieEvent` records from Kafka or a local file, deduplicates by `eventId`, then writes per-user recent-movie lists, per-movie engagement metrics, and global top-K to Redis. Redis writes use companion `:updated_at` keys to keep old retries from overwriting newer feature snapshots.
- `RedisTopKStore` — reads trending sorted sets from Redis and keeps a short local cache for hot Top-K windows.
- `RedisRateLimiter` — optional Redis-backed fixed-window limiter for cross-instance online request protection. It fails open if Redis is unavailable.
- `OnlineServingMetricsService` — node-local rolling-window metrics for online serving: QPS, average latency, failures, rejected requests, and per-strategy `failureRate` and `share` (traffic mix). The strategy map is capped at 50 entries.
- `OnlineLoadShedder` — node-local concurrency limiter for online requests. Excess traffic returns HTTP `429`; when draining, `retryAfterSeconds()` returns `1` and callers can set a `Retry-After` header.
- `OnlineCapacityService` — exposes runtime sizing assumptions (`ONLINE_TARGET_DAU`, `ONLINE_PEAK_QPS`, `ONLINE_PEAK_TPS`) alongside observed QPS, remaining `headroomQps`, and an `overloaded` flag.
- `OnlineOpsServlet` — returns the combined metrics/load/capacity snapshot at `GET /online/ops` with a `servedAt` ISO-8601 timestamp; sets `Retry-After` on the response when the shedder is draining.
- `OnlinePredictionServer` — Jetty entry point on port `7010`; wires `OnlineRecommendationService` and exposes `/health`, `/online/features`, `/online/recommendation`, and `/online/ops`.

**Model serving:**

- `ModelArtifactLocator` — single artifact resolver exposing **model** (`classpath:artifacts/model/<variant>/`, overridden by `RECSYS_MODEL_ARTIFACTS_DIR`) and **spark** groups. Blank variant defaults to `training`.
- `ModelRuntimeProvider` — Spring `@Service` that owns the full lifecycle of every per-variant runtime. `@PostConstruct warmUp()` pre-loads the default variant and, when A/B testing is enabled, the A and B variants. `areVariantsReady()` checks whether all loaded runtimes have live ONNX sessions.
- `VersionController` — Spring REST controller for model version operations: list loaded versions, preload a candidate variant, activate it as the default, and roll back to the previous active variant.
- `ModelVersionService` — coordinates `VersionController`, `ModelRuntimeProvider`, and `ABTestConfig` so promotion happens only after the candidate runtime can be loaded.
- `ModelArtifactService` — plain Java class (not a Spring bean); loads `feature_config.json`, user vocab, item vocab, and optional item embeddings for one variant. Created and called by `ModelRuntimeProvider`.
- `UserTowerInferenceService` — plain Java class (not a Spring bean); manages a single `OrtSession` for one variant and scores DSSM `(user_id, item_id)` pairs. Created and initialized by `ModelRuntimeProvider`; closed on `@PreDestroy`.
- `CandidateSelectionService` — plain Java class (not a Spring bean); builds the candidate pool from user-history genres, global top-rated, and latest releases. Excluded item IDs are filtered eagerly inside `addIfAvailable` (before insertion) rather than removed in bulk at the end.
- `RetrievalService` — plain Java class (not a Spring bean); merges embedding-based and metadata-based recall. Embedding recall returns candidates sorted descending by inner-product score. Metadata recall exits as soon as `recallSize` is reached to avoid wasted iteration.
- `RankingService` — plain Java class (not a Spring bean); scores recalled candidates and returns the top-k.
- `ABTestConfig` — `@ConfigurationProperties(prefix = "recsys.ab-test")` with `@Validated` startup checks; holds `layerName`, `trafficSplitNumber`, variant names, and the `enabled` flag.
- `ABTestService` — hashes `userId:layerName` to a bucket index; returns a typed `Assignment` record (variant, bucket, layerName, inExperiment). Same layer → mutually exclusive buckets; different layers → independent assignments.
- `InferenceMetricsService` — global rolling-window metrics plus per-variant counters. `abTestSnapshot(controlVariant)` computes success-rate and latency deltas vs control, exposed at `GET /health/ab-tests`.
- `LoadShedder` — per-instance concurrency limiter and load snapshot used for overload protection and load-balancer readiness decisions.
- `HealthProperties` — `@ConfigurationProperties(prefix = "recsys.health")` with `@Validated` startup checks; all probe thresholds in one place, overridable via env vars.
- `HealthController` — `/health/live` (liveness), `/health/ready` (readiness gated on `ModelRuntimeProvider.areVariantsReady()`, load, and rolling metrics), `/health/load` (node-local concurrency snapshot), `/health/metrics` (global snapshot), `/health/ab-tests` (per-variant comparison snapshot).
- `GlobalExceptionHandler` — maps bean-validation failures, malformed JSON, wrong content-type, and unexpected errors to a consistent `ApiError` shape.

**MySQL and pagination (`com.recsys.mysql`, `com.recsys.pagination`):**

- `MySqlConnectionSettings` — immutable settings record read from `MYSQL_ENABLED`, `MYSQL_URL`, `MYSQL_USER`, `MYSQL_PASSWORD`. Disabled by default so no serving path opens a DB connection at startup.
- `MySqlClient` — thin JDBC wrapper with no connection pool. Callers pass an explicit `Connection` for scoped queries, or use the single-plan overload for one-shot reads. `query(..., queryTimeoutSeconds)` bounds slow scans. `queryPage()` executes a cursor-page plan and extracts the next-page token from the last row automatically, returning `PageResult<T>` (`rows` + `nextCursor`); `nextCursor` is `null` on the last page.
- `MillionScalePaginationSql` — SQL template builder for three million-row pagination strategies, all using `FORCE INDEX` against a composite covering index:
  - `coveringIndexDdl()` — generates a `CREATE INDEX` statement with equality-filter columns first, then sort column and id, then any extra projected columns.
  - `countWithCoveringIndex()` — `SELECT COUNT(*) FORCE INDEX` forces MySQL to use the narrow index tree instead of the clustered primary key scan (5–20× faster on large tables).
  - `cursorPage()` / `cursorPageBefore()` — keyset/seek pagination using a `SeekCursor(sortValue, id)` opaque token. Zero `OFFSET` at any depth; O(1) per page regardless of position. `cursorPageBefore` reverses `ORDER BY` and uses `beforeOperator`; callers reverse the returned list for display order.
  - `delayedJoinPage()` — deferred-join pagination: inner subquery walks only the covering index for `(id, sortCol)` page keys; outer join fetches full rows only for those keys, avoiding reading skipped rows entirely.

---

## Pipeline Optimizations

Optimizations applied to the serving path targeting OOM, Full GC, thread blocking, and CPU spikes:

| Component | Problem | Fix |
|---|---|---|
| `OnlineFeatureStore` | `ConcurrentHashMap.compute()` held a CHM bin lock during the Redis network call, stalling all threads hashing to the same segment | Replaced with `CompletableFuture` inflight map; Redis fetch runs entirely outside any lock |
| `RecommendationCache.TtlLruCache` | `synchronized` + access-order `LinkedHashMap` serialised every cache read through an exclusive write lock | `ReentrantReadWriteLock` + insertion-order `LinkedHashMap`; concurrent reads now share a read lock |
| `RedisEmbeddingStore.loadAll()` | Accumulated all key names then issued one unbounded `MGET` — OOM / Full GC risk on large stores | Batch-`MGET` per SCAN page (≤500 keys); peak heap is now O(page) not O(all embeddings) |
| `ModelArtifactService` | `Arrays.copyOf()` doubled live heap (two full copies of all embedding vectors) during startup | Removed defensive copy; vectors are read-only after load |
| `OnlineFeatureStore.evictIfNeeded()` | O(N) `removeIf` over 10K entries ran on every cache-miss request at capacity | Rate-limited to once per 5 s; `Enumeration.nextElement()` replaced with `Iterator` (safe under concurrent modification) |
| `OnlineLearner.evictIfNeeded()` | O(N log N) heap allocation ran on every `learn()` call past the item limit | Rate-limited to once per 5 s |
| `UserTowerInferenceService.close()` | Closed `OrtEnvironment` (JVM-wide singleton), invalidating all other A/B-test variant sessions | Now only closes the per-variant `OrtSession`; environment is process-global |
| `OnlineServingMetricsService` | `Instant.now()` allocation on every request's hot path | `System.currentTimeMillis() / 1000L` — no allocation |

---

## LLM Integration Ideas

- Use text embeddings as item/user features for retrieval.
- Use an LLM as a zero-shot ranker or reranker for diversity, freshness, and domain-specific constraints.
- Fine-tune for direct item generation when supervised recommendation data is available.
- Add conversational recommendation on top of the existing serving layer.
