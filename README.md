# RecSys

A movie recommendation API built on Jetty + Redis, with a Kafka/Flink streaming pipeline for real-time Top-K trending.

## Package Structure

| Package | Contents |
|---|---|
| `models/` | DTOs: `Movie`, `User`, `RecommendationResponse` |
| `features/` | `DataManager`, `RedisEmbeddingStore`, `RedisTopKStore`, `VectorMath` |
| `serving/` | Jetty server and HTTP servlet endpoints |

## Infrastructure

```bash
docker compose -f docker-compose.streaming.yml up -d
```

Starts: Zookeeper, Kafka, Redis, Flink JobManager, Flink TaskManager.

Flink UI: http://localhost:8081

## Kafka Topic Setup

```bash
# Create topic
docker exec -it recsys-kafka-1 \
  kafka-topics --bootstrap-server localhost:9092 \
  --create --topic video_views --partitions 1 --replication-factor 1

# List topics
docker exec -it recsys-kafka-1 \
  kafka-topics --bootstrap-server kafka:9092 --list

# Produce sample events
docker exec -it recsys-kafka-1 \
  kafka-console-producer --bootstrap-server kafka:9092 --topic video_views
```

Sample events:
```json
{"videoId":"1","eventTimeMillis":1700000000000}
{"videoId":"2","eventTimeMillis":1700000001000}
{"videoId":"2","eventTimeMillis":1700000002000}
{"videoId":"2","eventTimeMillis":1700000003000}
{"videoId":"2","eventTimeMillis":1700000004000}
{"videoId":"3","eventTimeMillis":1700000005000}
```

Delete topic:
```bash
docker exec -it recsys-kafka-1 \
  kafka-topics --bootstrap-server localhost:9092 --delete --topic video_views
```

## Redis

### Top-K (trending)

```bash
docker exec -it redis-dev redis-cli DEL topk:last_hour
docker exec -it redis-dev redis-cli ZADD topk:last_hour 50 2 20 1 10 3
docker exec -it redis-dev redis-cli ZREVRANGE topk:last_hour 0 9 WITHSCORES
```

### Embeddings (item-to-vec)

```bash
docker exec -it redis-dev redis-cli SET i2vEmb:1 "1 0 0"
docker exec -it redis-dev redis-cli SET i2vEmb:2 "0.9 0.1 0"
docker exec -it redis-dev redis-cli SET i2vEmb:3 "0 1 0"
```

## Build & Run

```bash
mvn clean compile
mvn exec:java -Dexec.mainClass="com.recsys.serving.RecSysServer"
```

Server and Redis settings can be overridden via env vars:

| Env var | Default |
|---|---|
| `PORT` | `6010` |
| `REDIS_HOST` | `localhost` |
| `REDIS_PORT` | `6379` |

## API Endpoints

### GET `/health`

```bash
curl "http://localhost:6010/health"
# {"ok":true}
```

### GET `/getmovie?id={int}`

```bash
curl "http://localhost:6010/getmovie?id=1"
# {"id":1,"title":"Inception","year":2010}
```

### GET `/getuser?userId={int}`

```bash
curl "http://localhost:6010/getuser?userId=123"
# {"userId":123,"name":"Alice"}
```

### GET `/getrecommendation?userId={int}`

Default mode (similar items from seed movie):
```bash
curl "http://localhost:6010/getrecommendation?userId=123"
curl "http://localhost:6010/getrecommendation?userId=123&seedMovieId=2"
```

Trending mode (Top-K from Redis ZSET):
```bash
curl "http://localhost:6010/getrecommendation?userId=123&mode=topk&window=last_hour&k=5"
# window: last_hour | last_day | last_month  (default: last_hour)
# k: 1–200 (default: 20)
```

### GET `/getsimilarmovie?movieId={int}`

Cosine similarity search over Redis embeddings:
```bash
curl "http://localhost:6010/getsimilarmovie?movieId=1&k=5"
# {"movieId":1,"similar":[{"movieId":2,"score":0.994},{"movieId":3,"score":0.0}]}
```

### POST `/setembedding?movieId={int}`

Store a float vector embedding (space-separated values in body):
```bash
curl -X POST "http://localhost:6010/setembedding?movieId=4" \
  -H "Content-Type: text/plain" \
  --data-binary "0.2 0.2 0.6"
# {"ok":true,"movieId":4,"dim":3}

curl -X POST "http://localhost:6010/setembedding?movieId=5" \
  --data-urlencode "vec=0.1 0.3 0.6"
# {"ok":true,"movieId":5,"dim":3}
```

---

## LLM Integration Notes

### Feature Encoding

- **LLM embeddings** — GPT-style embeddings for item retrieval; text embeddings encoded as sparse features
- **LLM augmented features** — derive structured features from item textual data

### Ranking / Re-ranking

- **Zero-shot LLM ranker** — prompt engineering; note: sensitive to item order and popularity bias in prompts
- **Fine-tuned LLM** — SFT to directly generate target items; instruction tuning for alignment
- **Goal-directed reranking** — diversity, relevance, freshness; domain specialist use cases (health, finance, legal)

### User Interaction

- Conversational recommendation

### Architecture

- Generative sequence modeling
- Transformer variants: HSTU
- Mixture of Experts (MoE)
