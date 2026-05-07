# Online Serving

This streaming path is intentionally separate from the main Jetty movie API and the Spring Boot model-artifact service.
It shows an online or near-real-time recommendation path where:

```text
Kafka -> Flink -> Redis ─┐
                         ├─> OnlineRecommendationService -> online prediction server
user embeddings (ANN) ───┘
```

The streaming path uses:

- Kafka as the event ingress layer
- Flink (`OnlineFeatureStreamingJob`) to write per-user history and Top-K trending into Redis
- Redis as the online feature store
- `OnlineRecommendationEngine` for real-time behavioral scoring
- `CandidateGenerator.byEmbedding` for embedding-based ANN recall
- `OnlineRecommendationService` to blend both signals before serving
- `OnlinePredictionServer` (Jetty, port `7010`) as the HTTP layer

Today the repo includes both:

- a real Java Flink job in `com.recsys.streaming.flink`
- replay scripts for loading the same Redis shape without running Flink

That keeps the streaming path runnable without coupling it to the model-artifact service.

## Files

- `streaming/online-serving/docker-compose.yml`
- `streaming/online-serving/data/movie_events.ndjson`
- `streaming/online-serving/scripts/load_online_features.sh`
- `streaming/online-serving/scripts/produce_movie_events.sh`
- `src/main/java/com/recsys/streaming/*`
- `src/main/java/com/recsys/streaming/flink/*`

## Start Infra

```bash
docker compose -f streaming/online-serving/docker-compose.yml up -d
```

Services:

- Kafka on `localhost:9092`
- Redis on `localhost:6379`
- Flink UI on `http://localhost:8081`

## Produce Kafka Events

```bash
sh streaming/online-serving/scripts/produce_movie_events.sh
```

That publishes the bundled event stream from `movie_events.ndjson` into Kafka topic `movie_events`.

## Run The Flink Job

The Flink job is implemented in:

- `com.recsys.streaming.flink.OnlineFeatureStreamingJob`

It supports:

- Kafka source via `--bootstrap.servers ... --topic movie_events`
- local file replay via `--input-file streaming/online-serving/data/movie_events.ndjson`

Build the Flink profile:

```bash
mvn -Pstreaming-flink -DskipTests compile
```

Run from the bundled file:

```bash
mvn -Pstreaming-flink exec:java \
  -Dexec.mainClass="com.recsys.streaming.flink.OnlineFeatureStreamingJob" \
  -Dexec.args="--input-file streaming/online-serving/data/movie_events.ndjson --redis.host localhost --redis.port 6379 --window-seconds 10 --window-label last_hour --top-k 10"
```

Run from Kafka:

```bash
mvn -Pstreaming-flink exec:java \
  -Dexec.mainClass="com.recsys.streaming.flink.OnlineFeatureStreamingJob" \
  -Dexec.args="--bootstrap.servers localhost:9092 --topic movie_events --redis.host localhost --redis.port 6379 --window-seconds 10 --window-label last_hour --top-k 10"
```

What the job writes to Redis:

- `user:<id>:recent_movies`
- `movie:<id>:views_1h`
- `movie:<id>:likes_1h`
- `topk:last_hour`

## Load Online Features Into Redis

```bash
sh streaming/online-serving/scripts/load_online_features.sh
```

That script replays the sample Redis state from `src/main/java/com/recsys/data/online_features.txt`.
Use it as a fallback when you want the serving side without running the Flink job.

Redis keys written by the script:

- `user:<id>:recent_movies`
- `movie:<id>:views_1h`
- `movie:<id>:likes_1h`
- `topk:last_hour`

## Run The Separate Online Server

```bash
mvn clean compile
mvn exec:java -Dexec.mainClass="com.recsys.streaming.OnlinePredictionServer"
```

Optional env vars:

```bash
ONLINE_DEMO_PORT=7010 REDIS_HOST=localhost REDIS_PORT=6379 \
  mvn exec:java -Dexec.mainClass="com.recsys.streaming.OnlinePredictionServer"
```

## Recommendation Strategy

`OnlineRecommendationService` blends two recall sources on every request:

| Source | Signal | Weight |
|---|---|---|
| `OnlineRecommendationEngine` | Real-time: recent-history similarity + trending rank | 1.0 |
| `CandidateGenerator.byEmbedding` | Offline: ANN search on user-tower embeddings | 0.5 |

Each source contributes a normalized rank score `weight × (n − rank) / n`. Movies that appear in both lists accumulate scores from both and surface at the top. Recently-watched movies are excluded from the final output.

When no embedding exists for the user (cold-start), the service falls back to the online path only. The response includes a `strategy` field (`"online+model"` or `"online"`) so callers can observe which signals fired.

## Try It

Inspect the online feature snapshot:

```bash
curl "http://localhost:7010/online/features?userId=123&window=last_hour&k=5"
```

Request blended recommendations:

```bash
curl "http://localhost:7010/online/recommendation?userId=123&window=last_hour&k=5"
```

Example response shape:

```json
{
  "user": { "userId": 123, "name": "Alice" },
  "window": "last_hour",
  "strategy": "online+model",
  "recentMovies": [...],
  "trendingMovies": [...],
  "recommendations": [...]
}
```

This models the production split where:

- offline jobs build durable embedding assets (user tower, item embeddings)
- Flink keeps short-lived behavioral features fresh in Redis
- `OnlineRecommendationService` fuses both at request time

## Stop Infra

```bash
docker compose -f streaming/online-serving/docker-compose.yml down
```
