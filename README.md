# RecSys

Movie recommendation API built with Jetty, Redis, and a small local dataset. It supports:

- movie and user lookup
- co-rating based recommendations
- Redis sorted-set Top-K trending recommendations
- Redis item-embedding similarity search
- runtime embedding updates

## Quick Start

Prerequisites:

- Java 17, matching `pom.xml`
- Maven
- Docker with Docker Compose, for Redis/Kafka/Flink services

Start infrastructure from the repo root:

```bash
colima start # if you use Colima
docker compose -f docker-compose.streaming.yml up -d
```

If your Docker install uses legacy Compose, replace `docker compose` with
`docker-compose`. If `docker compose version` and `docker-compose version` both
fail, install Docker Compose first.

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
```

Stop infrastructure:

```bash
docker compose -f docker-compose.streaming.yml down
```

## Configuration

| Env var | Default | Purpose |
|---|---:|---|
| `PORT` | `6010` | API server port |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |

Example:

```bash
PORT=7010 REDIS_HOST=localhost REDIS_PORT=6379 \
  mvn exec:java -Dexec.mainClass="com.recsys.serving.RecSysServer"
```

On startup, the server seeds Redis with bundled movie and user embeddings if the Redis keys are empty.

## Project Layout

| Path | Purpose |
|---|---|
| `src/main/java/com/recsys/models/` | Immutable API/domain records |
| `src/main/java/com/recsys/features/` | Data loading, vector math, Redis stores |
| `src/main/java/com/recsys/serving/` | Jetty server and servlet endpoints |
| `src/main/java/com/recsys/data/` | Bundled sample data and embeddings |
| `docker-compose.streaming.yml` | Redis, Kafka, Zookeeper, Flink |

Key data files:

- `movies.txt`
- `users.txt`
- `ratings.txt`
- `events.txt`
- `online_features.txt`
- `movie_embeddings.txt`
- `user_embeddings.txt`

## API

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

Default mode uses the in-memory co-rating similarity lists:

```bash
curl "http://localhost:6010/getrecommendation?userId=123"
curl "http://localhost:6010/getrecommendation?userId=123&seedMovieId=2"
```

Trending mode reads Redis sorted sets such as `topk:last_hour`:

```bash
curl "http://localhost:6010/getrecommendation?userId=123&mode=topk&window=last_hour&k=5"
```

Supported windows: `last_hour`, `last_day`, `last_month`.

### Similar Movies

Computes cosine similarity against Redis item embeddings:

```bash
curl "http://localhost:6010/getsimilarmovie?movieId=1&k=5"
# {"movieId":1,"similar":[{"movieId":4,"score":0.99}, ...]}
```

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
Spark dependencies are isolated behind the `offline-embedding` Maven profile.

Train Word2Vec item embeddings from bundled ratings:

```bash
mvn -Poffline-embedding exec:java \
  -Dexec.mainClass="com.recsys.offline.ItemEmbeddingJob" \
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
--synonym-movie-id=1
```

The output CSV uses the same `movieId,vector` shape as
`src/main/java/com/recsys/data/movie_embeddings.txt`, so generated vectors can
be copied into the bundled seed data or loaded into Redis as `i2vEmb:<movieId>`
for retrieval.

## Developer Notes

- `DataLoader` loads bundled text resources from `com/recsys/data`.
- `DataManager` keeps immutable maps and precomputed indexes for request-time reads.
- `RedisEmbeddingStore` uses Redis pipelines for bulk writes and `SCAN` + `MGET` for safe bulk reads.
- `BaseApiServlet` centralizes JSON headers, serialization, and request parameter parsing.

## LLM Integration Ideas

- Use text embeddings as item/user features for retrieval.
- Use an LLM as a zero-shot ranker or reranker for diversity, freshness, and domain-specific constraints.
- Fine-tune for direct item generation when supervised recommendation data is available.
- Add conversational recommendation on top of the existing serving layer.
