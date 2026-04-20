# RecSys

Movie recommendation API built with Jetty, Redis, and a small local dataset. It supports:

- movie and user lookup
- co-rating based recommendations
- Redis sorted-set Top-K trending recommendations
- Redis item-embedding similarity search
- runtime embedding updates

## Quick Start

Prerequisites:

- Java 18, matching `pom.xml`
- Maven
- Docker, for Redis/Kafka/Flink services

Start infrastructure from the repo root:

```bash
docker compose -f docker-compose.streaming.yml up -d
```

Run the API:

```bash
cd recsys-api
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
| `recsys-api/src/main/java/com/recsys/models/` | Immutable API/domain records |
| `recsys-api/src/main/java/com/recsys/features/` | Data loading, vector math, Redis stores |
| `recsys-api/src/main/java/com/recsys/serving/` | Jetty server and servlet endpoints |
| `recsys-api/src/main/java/com/recsys/data/` | Bundled sample data and embeddings |
| `docker-compose.streaming.yml` | Redis, Kafka, Zookeeper, Flink |

Key data files:

- `movies.txt`
- `users.txt`
- `ratings.txt`
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
docker exec -it redis-dev redis-cli ZADD topk:last_hour 50 2 20 1 10 3
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
  --create --topic video_views --partitions 1 --replication-factor 1

docker exec -it recsys-kafka-1 \
  kafka-console-producer --bootstrap-server kafka:9092 --topic video_views
```

Sample events:

```json
{"videoId":"1","eventTimeMillis":1700000000000}
{"videoId":"2","eventTimeMillis":1700000001000}
{"videoId":"2","eventTimeMillis":1700000002000}
{"videoId":"3","eventTimeMillis":1700000005000}
```

Delete the topic:

```bash
docker exec -it recsys-kafka-1 \
  kafka-topics --bootstrap-server localhost:9092 --delete --topic video_views
```

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
