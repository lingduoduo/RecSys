# RecSys

RecSys is a compact Maven workspace for experimenting with recommendation-system serving, retrieval, ranking, and offline embedding pipelines.

| Area | What it shows |
|---|---|
| Movie API | Jetty, Redis, local movie data, multi-strategy retrieval, and runtime embedding updates |
| Two-tower demo | PyTorch training, ONNX export, and Spring Boot retrieval serving |
| Rule-based offline embeddings | Spark Word2Vec item embeddings trained from user interaction sequences |
| Model-based offline embeddings | Two-tower user inference plus precomputed item embeddings |

The movie API includes:

- Movie and user lookup
- Single-strategy recall from seed-movie genres
- Multi-way recall from user history, global top-rated movies, and latest releases
- Embedding-based retrieval over classpath user and item embeddings with selectable vector backends
- Batched pair scoring through a TensorFlow Serving style `POST /v1/models/recmodel:predict` endpoint
- Redis sorted-set Top-K trending recommendations
- Redis item-embedding similarity search
- Runtime embedding updates

------

## Recommendation Flow

The movie API models a common recommendation-serving pipeline: recall first narrows the catalog to a candidate set, then ranking scores and orders those candidates.

Recall examples:

- Single-strategy recall: `CandidateGenerator.byGenre` expands from the genres of a seed movie.
- Multi-way recall: `CandidateGenerator.byUserHistory` merges candidates from user-history genres, global top-rated movies, and latest releases.
- Embedding recall: `CandidateGenerator.byEmbedding` retrieves items by comparing user and item embeddings with the configured vector index.

Ranking example:

- Embedding-similarity ranking: `SimilarMovieService` builds a candidate set, scores each candidate with inner-product similarity, and returns the top-K results.

Future ranking demos can replace embedding similarity with model-based rankers such as LR, GBDT, DNN, Wide & Deep, DIN, or other recommendation models.

------

## Quick Start: Movie API

Use this path to run the Jetty movie API on port `6010` with Redis-backed embeddings and Top-K state.

Requires:

- Java 17, matching `pom.xml`
- Maven
- Docker with Docker Compose, for Redis/Kafka/Flink services

Start infrastructure:

```bash
colima start # if you use Colima
docker compose -f docker-compose.streaming.yml up -d
```

If needed, replace `docker compose` with `docker-compose`.

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
RECSYS_VECTOR_BACKEND=lsh mvn exec:java -Dexec.mainClass="com.recsys.serving.RecSysServer"
RECSYS_VECTOR_BACKEND=exact mvn exec:java -Dexec.mainClass="com.recsys.serving.RecSysServer"
```

`lsh` is the default approximate backend. `exact` is useful for deterministic recall checks.

Stop infrastructure:

```bash
docker compose -f docker-compose.streaming.yml down
```

------

## Quick Start: Two-Tower Demo

The two-tower demo is a separate Spring Boot service on port `8080`. It serves model-based retrieval through `TwoTowerApplication` and the `/recommend` endpoint.

It demonstrates:

- ONNX user-tower inference in Java
- Precomputed item embeddings
- Model-based retrieval with inner-product similarity

1. `python-training/data.py` reads `src/main/java/com/recsys/data/ratings.txt`, keeps ratings `>= 3.5` as positive interactions, and builds `user_vocab` / `item_vocab` mappings with an `__UNK__` fallback.
2. `python-training/train_and_export.py` trains the PyTorch `TwoTower` model from `python-training/model.py`: the user tower and item tower learn normalized vectors using in-batch cross-entropy over user-item pairs.
3. The exporter writes `user_tower.onnx`, `feature_config.json`, `item_embeddings.json`, and `metadata.json` to `python-training/artifacts/`, then copies them into `src/main/resources/model/` for Java serving.
4. Item embeddings are precomputed offline by running the trained item tower over every known `item_id`; when `faiss-cpu` is installed, the script also exports optional `item_embeddings.faiss` and `item_ids.json` files.
5. Start the Spring Boot service. `ModelArtifactLocator` resolves all artifacts and exposes two typed groups: **model** (feature configs, ONNX models, pre-computed embeddings at `classpath:model/`) and **spark** (PySpark model files at `classpath:artifacts/pyspark/`). `UserTowerInferenceService` loads `user_tower.onnx` and `ModelArtifactService` loads `feature_config.json` plus `item_embeddings.json` through the model group.
6. At request time, `/recommend` calls `FeatureEncoder` to map `userId` into the training vocab, runs ONNX inference to produce a user embedding, uses `CandidateSelectionService` and `RetrievalService` to recall candidates, and uses `RankingService` to rerank the final top-K by inner-product similarity.

If your modeling pipeline writes artifacts to its own output directory, point each artifact group to it independently:

```bash
RECSYS_MODEL_ARTIFACTS_DIR=/path/to/model/artifacts   # overrides classpath:model/
RECSYS_SPARK_ARTIFACTS_DIR=/path/to/spark/artifacts   # overrides classpath:artifacts/pyspark/
```

When unset, the service uses the bundled classpath artifacts.

The demo stays small so the training-serving feature contract is easy to inspect.

### Feature Contract

User tower inputs:

- `user_id`

Item tower inputs:

- `item_id`

`ratings.txt` provides `userId`, `movieId`, `rating`, and `timestamp`. The
Python trainer treats ratings `>= 3.5` as positive user-item interactions.

### Python Training

Requires:

- Python 3.10+
- pip

Create a local virtualenv and install dependencies:

```bash
python3 -m venv .venv
.venv/bin/pip install -r python-training/requirements.txt
```

Train and export artifacts:

```bash
.venv/bin/python python-training/train_and_export.py
```

This writes artifacts to both `python-training/artifacts/` and
`src/main/resources/model/`:

```text
feature_config.json
item_embeddings.json
item_embeddings.faiss  # optional, when faiss-cpu is installed
item_ids.json          # optional, FAISS row-to-item-id mapping
metadata.json
user_tower.onnx
```

The exporter writes directly into `src/main/resources/model/`, so the Java service can use the artifacts immediately after training.

Verify the optional FAISS artifact:

```bash
.venv/bin/python -c "import faiss; idx=faiss.read_index('src/main/resources/model/item_embeddings.faiss'); print(idx.ntotal, idx.d)"
```

### Spring Boot Serving

Start the two-tower service from the repo root:

```bash
mvn spring-boot:run
```

Load artifacts directly from a modeling pipeline output directory:

```bash
RECSYS_MODEL_ARTIFACTS_DIR=python-training/artifacts mvn spring-boot:run
```

Health:

```bash
curl http://localhost:8080/health
```

Expected response:

```text
ok
```

Recommend:

```bash
curl -X POST http://localhost:8080/recommend \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": "123",
    "k": 5,
    "excludeItemIds": ["2"]
  }'
```

Example response:

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

The Spring Boot entry point is `com.recsys.modelbased.twotower.TwoTowerApplication`.
The Jetty API remains available through `com.recsys.serving.RecSysServer`.

Notes:

- Training data comes from `src/main/java/com/recsys/data/ratings.txt`.
- Retrieval uses inner-product similarity in the portable Java path. Python also exports a FAISS `IndexFlatIP` index when `faiss-cpu` is available.
- For production-scale Java serving, use a Linux native FAISS binding such as `com.criteo.jfaiss:jfaiss-cpu`, or use a managed ANN service such as OpenSearch kNN, Vespa, or Milvus.
- In production, item embeddings are usually generated offline and reloaded
  periodically by the serving layer.

------

## Testing

Run all unit and integration tests from the repo root:

```bash
mvn test
```

The test suite covers the two-tower serving layer:

| Test class | What it covers |
|---|---|
| `ModelArtifactLocatorTest` | Classpath and external-dir resolution for model and spark artifact groups; whitespace-only override falls back to classpath |
| `ModelArtifactServiceTest` | Loads bundled `feature_config.json` and `item_embeddings.json`; asserts model version, vocab contents, embedding dimension, and immutability |
| `FeatureEncoderTest` | Known user IDs map to their vocab indices; unknown IDs fall back to `__UNK__` (index 0) |
| `RankingServiceTest` | Items are re-ordered by inner-product score descending; k-truncation, duplicate deduplication, and missing-embedding skip |
| `RetrievalServiceTest` | Embedding recall returns highest inner-product candidates up to recall size; null embedding, empty candidates, and unknown items |
| `RecommendationServiceTest` | Validates `userId` and `k`; wires mocked sub-services and asserts the full response shape |
| `RecommendationControllerTest` | `GET /health`, `POST /recommend` happy path and `IllegalArgumentException` → HTTP 400 via `GlobalExceptionHandler` |

------

## Configuration

These environment variables control the Jetty movie API runtime and its retrieval backend.

| Env var | Default | Purpose |
|---|---:|---|
| `PORT` | `6010` | API server port |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `RECSYS_VECTOR_BACKEND` | `lsh` | Movie API embedding backend: `lsh` or `exact`; `faiss` currently falls back to `lsh` in the portable build |
| `RECSYS_MODEL_ARTIFACTS_DIR` | empty | Two-tower model artifact directory; overrides `classpath:model/` for `user_tower.onnx`, `feature_config.json`, and `item_embeddings.json` |
| `RECSYS_SPARK_ARTIFACTS_DIR` | empty | PySpark artifact directory; overrides `classpath:artifacts/pyspark/` for ALS, Item2Vec, and other Spark model files |

Example:

```bash
PORT=7010 REDIS_HOST=localhost REDIS_PORT=6379 \
  mvn exec:java -Dexec.mainClass="com.recsys.serving.RecSysServer"
```

On startup, the server seeds Redis with bundled movie and user embeddings if the Redis keys are empty.

------

## Project Layout

The repo keeps serving code, training code, bundled data, model artifacts, and local infrastructure definitions in one workspace.

**Java**

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

src/main/resources/model/   Exported two-tower artifacts loaded by Spring Boot
├── feature_config.json
├── item_embeddings.json
├── item_embeddings.faiss
├── item_ids.json
├── metadata.json
└── user_tower.onnx
```

**Python**

```text
python-training/
├── model.py                UserTower, ItemTower, TwoTower definitions
├── data.py                 Ratings loading, vocab building, batch construction
├── train_and_export.py     Training loop and ONNX/embedding artifact export
└── artifacts/              Generated local artifacts, ignored by Git
```

**Infrastructure**

```text
docker-compose.streaming.yml Redis, Kafka, Zookeeper, Flink
```

------

## API

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

**Default with `seedMovieId` — genre-based from seed:**

```bash
curl "http://localhost:6010/getrecommendation?userId=123&seedMovieId=2"
```

Uses `CandidateGenerator.byGenre`: for each genre tag on the seed movie, retrieves the top-100 by average rating, deduplicates, and removes the seed itself.

**`mode=embedding` — embedding-based retrieval:**

```bash
curl "http://localhost:6010/getrecommendation?userId=123&mode=embedding&k=20"
```

Uses `CandidateGenerator.byEmbedding`: searches movies with classpath embeddings against the user's embedding. The active backend is controlled by `RECSYS_VECTOR_BACKEND` or `-Drecsys.vector.backend`. Returns 404 if no user embedding is found. The `k` parameter is capped at 200 (default: 20).

Supported portable backends:

- `lsh`: approximate SimHash random-projection candidate generation with inner-product reranking.
- `exact`: full-scan inner-product top-k with a bounded min-heap.

`faiss` is reserved for Linux native FAISS deployments and falls back to `lsh`
in the portable build.

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

Scores explicit `(userId, movieId)` pairs with a batched JSON `POST`, shaped like a model-serving inference API:

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

Example response:

```json
{
  "predictions": [
    [0.9231],
    [0.7412]
  ]
}
```

Notes:

- This endpoint scores each request pair independently; it does not do candidate generation or top-K recommendation assembly.
- The current Java implementation uses the bundled classpath user and movie embeddings plus inner-product scoring, which is the local equivalent of `model(user_ids, movie_ids)` in a compact serving demo.
- Requests return `400` when `instances` is empty, when IDs are non-positive, or when a user/movie embedding is missing.

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

------

## Redis Test Data

Use these commands to seed or inspect Redis state for trending recommendations and item embeddings.

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

------

## Kafka/Flink

The compose file also starts Kafka and Flink for streaming Top-K experiments.

Flink UI:

```text
http://localhost:8081
```

Create and populate the sample topic:

```bash
docker exec -it recsys-kafka-1 \
  kafka-topics --bootstrap-server localhost:9092 \
  --create --topic movie_events --partitions 1 --replication-factor 1

docker exec -it recsys-kafka-1 \
  kafka-console-producer --bootstrap-server kafka:9092 --topic movie_events
```

Sample events, matching `src/main/java/com/recsys/data/events.txt`:

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

------

## Offline Item Embeddings

This repo's default path is online prediction:

```text
Kafka -> Flink -> online feature / event store -> retrieval / prediction service
```

The bundled `events.txt` rows model the Kafka payloads. The
`online_features.txt` rows model the low-latency aggregates a Flink job would
write into Redis or another online store, such as `user:<id>:last_3_movies`,
`movie:<id>:views_1h`, and `topk:last_hour`.

Item embedding training is a separate offline preparation path:

```text
Kafka / HDFS -> Spark -> embedding training / batch generation -> model registry / vector store / feature store -> service
```

The bundled `ratings.txt` rows model the batch/HDFS-style positive feedback
used by Spark Word2Vec. The `movie_embeddings.txt` and `user_embeddings.txt`
files model the generated artifacts that get loaded into Redis for retrieval.
Spark dependencies are isolated behind the `offline-embedding` Maven profile and
declared `provided` scope — the cluster supplies Spark at runtime so it is not
bundled into the JAR.

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

The output CSV uses the same `movieId,vector` shape as
`src/main/java/com/recsys/data/movie_embeddings.txt`, so generated vectors can
be copied into the bundled seed data or loaded into Redis as `i2vEmb:<movieId>`
for retrieval.

To write embeddings directly to Redis after training, add the `--save-to-redis` flag:

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
--redis-key-prefix=i2vEmb  Key prefix — keys are written as {prefix}:{movieId} (default: i2vEmb)
--redis-ttl=86400           TTL in seconds; 0 = no expiry (default: 86400)
```

All writes are pipelined in a single round-trip. Key and value formats are compatible with
`RedisEmbeddingStore` and `VectorMath.parseVector`, so the Jetty API can serve them immediately.

------

## Embedding Storage Paths

The two offline strategies write to different stores.

### Rule-based → Redis

`ItemEmbeddingJob` writes directly to Redis after training (when `--save-to-redis=true`).
At serving time, `SimilarMovieService` first builds a metadata candidate set, then calls
`RedisEmbeddingStore.getEmbeddings(candidateIds)` to fetch only those candidate vectors for inner-product ranking.

```
Spark Word2Vec
  └─ Jedis pipeline ──► Redis (i2vEmb:{movieId} → "0.169 0.296 -0.130 ...")
                                │
                         SimilarMovieService
                           metadata candidate set
                                │
                           MGET candidate embeddings
                                │
                         inner-product similarity ──► top-k results
```

Key format: `{prefix}:{id}`, e.g. `i2vEmb:1`  
Value format: space-separated floats, e.g. `0.16938460 0.29643180 -0.13044095 ...`  
TTL: 86400 s by default (configurable via `--redis-ttl`).  
To update: re-run the job with `--save-to-redis=true`.

### Model-based → classpath, not Redis

`train_and_export.py` writes `item_embeddings.json` into `src/main/resources/model/`.
At Spring Boot startup, `ModelArtifactService` loads it into a `ConcurrentHashMap` in the JVM heap.
Redis is not involved in the two-tower path.

```
PyTorch item tower
  └─ train_and_export.py ──► item_embeddings.json (classpath)
                                      │
                             ModelArtifactService (@PostConstruct)
                               ConcurrentHashMap<String, float[]>
                                      │
                             CandidateSelectionService
                               history-genre + top-rated + latest eligible item IDs
                                      │
                              RetrievalService
                                embedding recall + metadata recall, merged by item ID
                                      │
                              RankingService
                                recompute inner-product similarity ──► final top-k results
```

TTL: none — embeddings reload on service restart.  
To update: re-run `train_and_export.py` and restart the Spring Boot service.

### Movie API → classpath (CandidateGenerator)

`CandidateGenerator` also loads `movie_embeddings.txt` and `user_embeddings.txt` from the classpath at startup for the `mode=embedding` retrieval path. This is the same bundled seed data loaded into Redis on first start — the classpath copy is used for direct heap-based scoring without a Redis round-trip.

```
movie_embeddings.txt / user_embeddings.txt (classpath)
  └─ DataLoader.loadMovieEmbeddings / loadUserEmbeddings
       └─ CandidateGenerator (JVM heap)
            └─ byEmbedding(userId, k)
                 VectorIndex backend ──► inner-product rerank ──► top-k results
```

### Comparison

| | Rule-based (Redis) | Model-based (two-tower) | Movie API (classpath) |
|---|---|---|---|
| Written by | Spark job → Jedis pipeline | Python → JSON file | Bundled text resources |
| Stored in | Redis (`i2vEmb:{id}`) | Classpath + JVM heap | Classpath + JVM heap |
| Loaded by | `RedisEmbeddingStore.getEmbeddings(candidateIds)` | `ModelArtifactService` `@PostConstruct` | `CandidateGenerator` constructor |
| User vector | Not produced | Live via ONNX at request time | Preloaded from `user_embeddings.txt` |
| Retrieval backend | Metadata candidates → Redis MGET → exact inner-product rank | Candidate set → embedding recall + metadata recall → inner-product rank; optional FAISS artifact for external/native use | `VectorIndex`: `lsh` or `exact` |
| TTL | 86400 s default | N/A — reloads on restart | N/A — reloads on restart |

------

## Developer Notes

These notes summarize the main implementation boundaries and where each retrieval or ranking responsibility lives.

Data loading and access:

- `DataLoader` loads bundled text resources from `com/recsys/data`.
- `DataManager` is a read-only data-access singleton. It owns immutable maps, precomputed sorted lists such as `topRatedMovies` and `latestMovies`, genre indexes such as `moviesByGenre`, and fast lookup helpers. Retrieval strategy logic stays outside this class.

Movie API retrieval:

- `CandidateGenerator` owns the Jetty movie API recall strategies and classpath movie/user embeddings. It is created once in `RecSysServer` and injected into `RecommendationService`.
- `CandidateGenerator.byGenre` implements seed-movie genre recall.
- `CandidateGenerator.byUserHistory` implements multi-way recall from user-history genres, global top-rated movies, and latest releases.
- `CandidateGenerator.byEmbedding` implements embedding recall through the `VectorIndex` interface. Use `RECSYS_VECTOR_BACKEND=lsh` for approximate retrieval or `RECSYS_VECTOR_BACKEND=exact` for deterministic full-scan evaluation.

Redis-backed embeddings:

- `RedisEmbeddingStore` is a generic key-prefix store for `getEmbedding`, `setEmbedding`, `setEmbeddings`, and `scanIds`.
- The same store supports both `i2vEmb:` item embeddings and `u2vEmb:` user embeddings.
- Bulk writes use Redis pipelines. Bulk reads use `SCAN` plus `MGET`, which avoids blocking Redis with large keyspace operations.

Servlet and ranking flow:

- `BaseApiServlet` centralizes JSON headers, Jackson serialization, error responses, and request parameter parsing.
- `SimilarMovieService` demonstrates candidate recall plus embedding ranking for Redis item embeddings: build metadata candidates, fetch only those vectors with Redis `MGET`, then rank by target-vs-candidate inner product.

Two-tower serving:

- `ModelArtifactLocator` is the single artifact resolver for the service. It exposes a **model** group (`classpath:model/`, overridden by `RECSYS_MODEL_ARTIFACTS_DIR`) for feature configs, ONNX models, and pre-computed embeddings, and a **spark** group (`classpath:artifacts/pyspark/`, overridden by `RECSYS_SPARK_ARTIFACTS_DIR`) for PySpark model files.
- `CandidateSelectionService` chooses eligible item IDs from user-history genres plus global pools.
- `RetrievalService` performs multi-route recall by combining embedding recall and metadata recall.
- `RankingService` recomputes inner-product similarity and sorts the final top-K response.
- `GlobalExceptionHandler` maps `IllegalArgumentException` to HTTP 400 with a JSON `{"error": "..."}` body.

------

## LLM Integration Ideas

These are possible follow-up directions for combining the existing retrieval stack with LLM-based features.

- Use text embeddings as item/user features for retrieval.
- Use an LLM as a zero-shot ranker or reranker for diversity, freshness, and domain-specific constraints.
- Fine-tune for direct item generation when supervised recommendation data is available.
- Add conversational recommendation on top of the existing serving layer.
