# Online Serving

This streaming path is intentionally separate from the main Jetty movie API and the Spring Boot model-artifact service.
It shows an online or near-real-time recommendation path where:

```text
Kafka -> Flink -> Redis -> online prediction server
```

The streaming path uses:

- Kafka as the event ingress layer
- Flink containers as the stream-processing runtime placeholder
- Redis as the online feature and Top-K store
- `com.recsys.streaming.OnlinePredictionServer` as the serving layer

Today the repo includes both:

- a real Java Flink job in `com.recsys.streaming.flink`
- replay scripts for loading the same Redis shape without running Flink

That keeps the streaming path runnable without coupling it to the current model-artifact flow.

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

## Try It

Inspect the online feature view:

```bash
curl "http://localhost:7010/online/features?userId=123&window=last_hour&k=5"
```

Request near-real-time recommendations:

```bash
curl "http://localhost:7010/online/recommendation?userId=123&window=last_hour&k=5"
```

The serving logic blends:

- recent per-user movies from Redis
- trending movie IDs from Redis Top-K
- offline similarity from `DataManager`

This is meant to model a common production split:

- offline jobs build durable similarity or embedding assets
- online stream processing updates short-lived behavioral features
- serving combines both at request time

## Stop Infra

```bash
docker compose -f streaming/online-serving/docker-compose.yml down
```
