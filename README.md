# RecSys

RecSys is a compact Maven workspace for experimenting with recommendation-system serving, retrieval, ranking, and offline embedding pipelines.

| Area | What it shows |
|---|---|
| Movie API | Jetty, Redis, local movie data, multi-strategy retrieval, and runtime embedding updates |
| Two-tower demo | Spring Boot ONNX retrieval serving, loads artifacts from any external modeling pipeline |
| Rule-based offline embeddings | Spark Word2Vec item embeddings trained from user interaction sequences |
| Model-based offline embeddings | Two-tower user inference plus precomputed item embeddings |

![Architecture](architecture.png)

---

## Contents

- [Recommendation Flow](#recommendation-flow)
- [Movie API](#movie-api)
- [Configuration](#configuration)
- [Project Layout](#project-layout)
- [API Reference](#api-reference)
- [Two-Tower Model Demo](#two-tower-model-demo)
- [Testing](#testing)
- [Redis Test Data](#redis-test-data)
- [Kafka / Flink](#kafkaflink)
- [Offline Item Embeddings](#offline-item-embeddings)
- [Embedding Storage Paths](#embedding-storage-paths)
- [Developer Notes](#developer-notes)
- [LLM Integration Ideas](#llm-integration-ideas)

---

## Recommendation Flow

The movie API models a common recommendation-serving pipeline: recall narrows the catalog to a candidate set, then ranking scores and orders those candidates.

Recall examples:

- **Single-strategy:** `CandidateGenerator.byGenre` expands from the genres of a seed movie.
- **Multi-way:** `CandidateGenerator.byUserHistory` merges candidates from user-history genres, global top-rated movies, and latest releases.
- **Embedding:** `CandidateGenerator.byEmbedding` retrieves items by comparing user and item embeddings with the configured vector index.

Ranking example:

- **Embedding-similarity:** `SimilarMovieService` builds a candidate set, scores each candidate with inner-product similarity, and returns the top-K results.

Future ranking demos can replace embedding similarity with model-based rankers such as LR, GBDT, DNN, Wide & Deep, or DIN.

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

Environment variables for the Jetty movie API and its retrieval backend.

| Env var | Default | Purpose |
|---|---:|---|
| `PORT` | `6010` | API server port |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `RECSYS_VECTOR_BACKEND` | `lsh` | Embedding backend: `lsh` or `exact`; `faiss` falls back to `lsh` in the portable build |
| `RECSYS_MODEL_ARTIFACTS_DIR` | _(empty)_ | Two-tower artifact directory; overrides `classpath:artifacts/twotower/` for `user_tower.onnx`, `feature_config.json`, and `item_embeddings.json` |
| `RECSYS_SPARK_ARTIFACTS_DIR` | _(empty)_ | PySpark artifact directory; overrides `classpath:artifacts/pyspark/` |

Example:

```bash
PORT=7010 REDIS_HOST=localhost REDIS_PORT=6379 \
  mvn exec:java -Dexec.mainClass="com.recsys.serving.RecSysServer"
```

On startup the server seeds Redis with bundled movie and user embeddings if the Redis keys are empty.

---

## Project Layout

```text
src/main/java/com/recsys/
├── models/                 Immutable API/domain records
├── features/               Data loading, indexed access, retrieval, vector math, Redis stores
├── serving/                Jetty server and servlet endpoints
├── training/
│   ├── rulebased/          Spark Word2Vec offline item embeddings
│   └── modelbased/
│       └── twotower/       Spring Boot ONNX serving for the two-tower demo
│           ├── TwoTowerApplication.java
│           ├── config/     Model artifact configuration
│           ├── controller/ Recommendation API
│           └── service/    Candidate selection, recall, ranking, ONNX inference
│                           ModelArtifactLocator — unified locator for model + spark artifact groups
└── data/                   Bundled sample data and seed embeddings
    ├── movies.txt
    ├── users.txt
    ├── ratings.txt
    ├── events.txt
    ├── online_features.txt
    ├── movie_embeddings.txt
    └── user_embeddings.txt

src/main/resources/artifacts/twotower/   Sample two-tower artifacts for local dev/testing
├── feature_config.json
├── item_embeddings.json
├── item_embeddings.faiss
├── item_ids.json
├── metadata.json
└── user_tower.onnx

docker-compose.streaming.yml  Redis, Kafka, Zookeeper, Flink
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

## Two-Tower Model Demo

A separate Spring Boot service on port `8080` that serves model-based retrieval through `TwoTowerApplication` and the `/recommend` endpoint.

**Demonstrates:**

- ONNX user-tower inference in Java
- Precomputed item embeddings
- Model-based retrieval with inner-product similarity

At request time, `/recommend` runs `FeatureEncoder` to map `userId` into the training vocab, runs ONNX inference for the user embedding, recalls candidates via `CandidateSelectionService` + `RetrievalService`, and reranks via `RankingService`.

`ModelArtifactLocator` resolves artifacts into two groups: **model** (`classpath:artifacts/twotower/`, overridden by `RECSYS_MODEL_ARTIFACTS_DIR`) and **spark** (`classpath:artifacts/pyspark/`, overridden by `RECSYS_SPARK_ARTIFACTS_DIR`).

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

Point the service at your pipeline's output directory via `RECSYS_MODEL_ARTIFACTS_DIR` (see [Configuration](#configuration)). When unset, the sample classpath artifacts under `src/main/resources/artifacts/twotower/` are used.

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

Health:

```bash
curl http://localhost:8080/health
# ok
```

Recommend:

```bash
curl -X POST http://localhost:8080/recommend \
  -H 'Content-Type: application/json' \
  -d '{"userId": "123", "k": 5, "excludeItemIds": ["2"]}'
```

```json
{
  "userId": "123",
  "modelVersion": "demo-two-tower-ratings-v1",
  "recommendations": [
    {"itemId": "1", "score": 0.9997},
    {"itemId": "3", "score": 0.7100}
  ]
}
```

Notes:

- Retrieval uses inner-product similarity in the portable Java path. If your pipeline exports a FAISS `IndexFlatIP` index (`item_embeddings.faiss` + `item_ids.json`), it is picked up automatically when `RECSYS_MODEL_ARTIFACTS_DIR` is set.
- For production-scale Java serving, use a Linux native FAISS binding (`com.criteo.jfaiss:jfaiss-cpu`) or a managed ANN service (OpenSearch kNN, Vespa, Milvus).
- Item embeddings reload on service restart; re-point `RECSYS_MODEL_ARTIFACTS_DIR` and restart to pick up a new model version.

---

## Testing

```bash
mvn test
```

| Test class | What it covers |
|---|---|
| `ModelArtifactLocatorTest` | Classpath and external-dir resolution for model and spark artifact groups; whitespace-only override falls back to classpath |
| `ModelArtifactServiceTest` | Loads bundled `feature_config.json` and `item_embeddings.json`; asserts model version, vocab contents, embedding dimension, and immutability |
| `FeatureEncoderTest` | Known user IDs map to their vocab indices; unknown IDs fall back to `__UNK__` (index 0) |
| `RankingServiceTest` | Items re-ordered by inner-product score descending; k-truncation, duplicate deduplication, and missing-embedding skip |
| `RetrievalServiceTest` | Embedding recall returns highest inner-product candidates up to recall size; null embedding, empty candidates, and unknown items |
| `RecommendationServiceTest` | Validates `userId` and `k`; wires mocked sub-services and asserts the full response shape |
| `RecommendationControllerTest` | `GET /health`, `POST /recommend` happy path and `IllegalArgumentException` → HTTP 400 via `GlobalExceptionHandler` |

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

## Kafka / Flink

The compose file starts Kafka and Flink for streaming Top-K experiments.

Flink UI: `http://localhost:8081`

Create and populate the sample topic:

```bash
docker exec -it recsys-kafka-1 \
  kafka-topics --bootstrap-server localhost:9092 \
  --create --topic movie_events --partitions 1 --replication-factor 1

docker exec -it recsys-kafka-1 \
  kafka-console-producer --bootstrap-server kafka:9092 --topic movie_events
```

Sample events (matching `src/main/java/com/recsys/data/events.txt`):

```json
{"eventId":"evt-001","userId":123,"movieId":1,"eventType":"view","watchMs":720000,"eventTimeMillis":1713503000000,"source":"web"}
{"eventId":"evt-002","userId":123,"movieId":2,"eventType":"view","watchMs":680000,"eventTimeMillis":1713503060000,"source":"web"}
{"eventId":"evt-003","userId":124,"movieId":5,"eventType":"view","watchMs":540000,"eventTimeMillis":1713503120000,"source":"mobile"}
{"eventId":"evt-004","userId":124,"movieId":6,"eventType":"like","rating":5,"eventTimeMillis":1713503180000,"source":"mobile"}
```

Delete the topic:

```bash
docker exec -it recsys-kafka-1 \
  kafka-topics --bootstrap-server localhost:9092 --delete --topic movie_events
```

---

## Offline Item Embeddings

**Online prediction path:**

```text
Kafka → Flink → online feature/event store → retrieval/prediction service
```

The bundled `events.txt` rows model the Kafka payloads. The `online_features.txt` rows model the low-latency aggregates a Flink job would write into Redis, such as `user:<id>:last_3_movies`, `movie:<id>:views_1h`, and `topk:last_hour`.

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

Your modeling pipeline exports `item_embeddings.json` (and optionally `user_tower.onnx`, `feature_config.json`, `metadata.json`). Point `RECSYS_MODEL_ARTIFACTS_DIR` at the output directory, or copy artifacts into `src/main/resources/artifacts/twotower/` to bundle them in the classpath. At startup, `ModelArtifactService` loads item embeddings into a `ConcurrentHashMap` in the JVM heap. Redis is not involved.

```
Modeling pipeline (any framework)
  └─ artifact export ──► item_embeddings.json  (+ optional ONNX / FAISS files)
                                      │
                             ModelArtifactService (@PostConstruct) → ConcurrentHashMap
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

| | Rule-based (Redis) | Model-based (two-tower) | Movie API (classpath) |
|---|---|---|---|
| Written by | Spark job → Jedis pipeline | External modeling pipeline | Bundled text resources |
| Stored in | Redis (`i2vEmb:{id}`) | Classpath + JVM heap | Classpath + JVM heap |
| Loaded by | `RedisEmbeddingStore.getEmbeddings` | `ModelArtifactService @PostConstruct` | `CandidateGenerator` constructor |
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

**Two-tower serving:**

- `ModelArtifactLocator` — single artifact resolver exposing **model** and **spark** groups.
- `CandidateSelectionService` — eligible item IDs from user-history genres plus global pools.
- `RetrievalService` — multi-route recall combining embedding and metadata recall.
- `RankingService` — inner-product similarity sort for the final top-K response.
- `GlobalExceptionHandler` — maps `IllegalArgumentException` to HTTP 400 with `{"error": "..."}`.

---

## LLM Integration Ideas

- Use text embeddings as item/user features for retrieval.
- Use an LLM as a zero-shot ranker or reranker for diversity, freshness, and domain-specific constraints.
- Fine-tune for direct item generation when supervised recommendation data is available.
- Add conversational recommendation on top of the existing serving layer.
