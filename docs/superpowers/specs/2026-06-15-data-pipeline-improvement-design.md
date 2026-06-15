# Data Pipeline Improvement Design

_Date: 2026-06-15_
_Branch: feat/api-service-consolidation_

---

## 1. Problem Statement

The three serving paths (RecSysServer, ModelApplication, OnlinePredictionServer) all read from a shared Redis store populated by two streaming jobs:

- **Flink** `OnlineFeatureStreamingJob` — writes recent history, user embeddings, topK trending, movie metrics
- **Spark** `UserEventStreamingJob` — writes user recent history and global item popularity

The pipeline is broken end-to-end for the following reasons:

1. **Topic mismatch (root cause):** Flink reads from `movie_events`; the only event producer writes to `user_events`. The Flink job receives no events and Redis stays empty. All three serving paths return empty results.
2. **No persistent Flink checkpoint storage:** `env.enableCheckpointing()` is called but no state backend or checkpoint directory is configured. Job restarts lose all accumulated state and reprocess from Kafka's earliest offset.
3. **Duplicate Redis key namespace:** Spark writes `user:{id}:recent` (LPUSH list); Flink writes `user:{id}:recent_movies` (space-delimited string). All three serving paths read the Flink key via `OnlineFeatureStore.getRecentMovieIds()`. Spark's write is redundant and uses a conflicting format.
4. **Orphaned training output:** `OnlineJoinerStreamingJob` writes Parquet to `/tmp/spark-recsys/training-samples/` and training samples to Kafka `training_samples`. Nothing triggers retraining from this data.
5. **Embedding alignment bugs:** Flink writes user embeddings to the wrong Redis key, with a format that serving cannot parse, and `OnlinePredictionServer` does not wire the Redis store into `CandidateGenerator` at all.

---

## 2. Chosen Approach

**Unified topic + training loop closure (Approach 2):**

- One Kafka topic (`recsys_events`) replaces both `user_events` and `movie_events`
- Consumer groups fan out independently to Flink and Spark
- Spark's duplicate Redis write is removed; Flink owns the user history key
- The orphaned HDFS training samples are connected to the existing retrain script
- Flink checkpoint storage is made configurable via env var
- All embedding key/format mismatches are fixed

---

## 3. Unified Event Schema

All producers write to `recsys_events`. All consumers parse this schema.

```json
{
  "event_id":         "uuid-v4-string",
  "user_id":          "user_42",
  "item_id":          "movie_123",
  "event_type":       "click",
  "timestamp_ms":     1718400000000,
  "session_id":       "sess_abc",
  "position":         2,
  "user_features":    {"tier": "vip"},
  "item_features":    {"bucket": "b2"},
  "context_features": {"device": "ios", "country": "US"}
}
```

**Field contract:**

| Field | Required | Flink usage | Spark usage |
|---|---|---|---|
| `event_id` | yes | dedup key (24 h TTL state) | ignored |
| `user_id` | yes | strip prefix → integer key | groupBy key |
| `item_id` | yes | strip prefix → integer | groupBy key |
| `event_type` | yes | engagement weight mapping | label generation |
| `timestamp_ms` | yes | event-time watermark | `/ 1000` for Spark timestamps |
| `session_id` | no | session feature state | ignored |
| `position` | no | ignored | impression position |
| `*_features` | no | ignored | pass-through to Parquet |

**ID parsing rule (Flink):** `"user_42"` → strip non-numeric prefix → integer `42`. Applied consistently in both `user_id` and `item_id` parsing. Falls back to `Math.abs(string.hashCode()) % MAX_INT` for non-numeric IDs.

**Catalog constraint:** The producer must generate `item_id` values whose integer suffix exists in the serving layer's movie catalog (default: `movie_1` through `movie_7`). This keeps topK results resolvable via `DataManager.getMovieById()` without requiring catalog externalization (tracked separately in the streaming pipeline SPEC.md as G7).

---

## 4. Architecture After Changes

```
producer.py ──► Kafka: recsys_events
                      │
          ┌───────────┴──────────────┐
          │  group: online-features  │  group: training-user-history
          │                          │  group: training-joiner
          ▼                          ▼
Flink OnlineFeatureStreamingJob   Spark UserEventStreamingJob
  → Redis: u2vEmb:{id}              → Redis: global:item_popularity
           user:{id}:recent_movies  Spark OnlineJoinerStreamingJob
           topk:{window}              → HDFS: training-samples/
           feature:user:{id}:*        → Kafka: training_samples
           movie:{id}:{metric}      Spark ExperienceCollectorStreamingJob
                                      → Kafka: training_experiences

HDFS training-samples/
  └─► run-retrain.sh (cron)
        → Item2VecTrainingJob → Redis: i2vEmb:{id}
        → UserEmbeddingTrainingJob → Redis: u2vEmb:{id}

Serving:
  RecSysServer       reads i2vEmb:*, u2vEmb:*
  ModelApplication   reads i2vEmb:* (configurable prefix)
  OnlinePredictionServer reads user:{id}:recent_movies, topk:*, u2vEmb:*
```

---

## 5. Flink Job Changes (`OnlineFeatureStreamingJob.java`)

### 5.1 Persistent Checkpoint Storage

```java
// After env.enableCheckpointing(...)
String checkpointDir = System.getenv("FLINK_CHECKPOINT_DIR");
if (checkpointDir != null && !checkpointDir.isBlank()) {
    env.getCheckpointConfig().setCheckpointStorage(checkpointDir);
    env.setStateBackend(new EmbeddedRocksDBStateBackend(true));
}
```

`docker-compose.streaming.yml` Flink services gain `FLINK_CHECKPOINT_DIR=/tmp/flink-checkpoints`.

Default (env var absent): in-memory state backend, current behavior for local dev without Docker volume.

Production: set `FLINK_CHECKPOINT_DIR=s3://bucket/flink-checkpoints` or `hdfs://namenode/flink-checkpoints`.

### 5.2 Unified Schema Parser (`MovieEvent`)

Updated `parseEvent()` reads unified schema fields:

| Old field | New field | Notes |
|---|---|---|
| `userId` (int) | `user_id` (string) | Strip prefix, parse integer |
| `movieId` (int) | `item_id` (string) | Strip prefix, parse integer |
| `eventTimeMillis` (long) | `timestamp_ms` (long) | Direct mapping |
| `eventId` (string) | `event_id` (string) | Direct mapping |
| `sessionId` (string) | `session_id` (string) | Optional |
| `eventType` (string) | `event_type` (string) | Direct mapping |

### 5.3 Consumer Group and Topic

`KafkaSource` group ID: `online-features`. Topic: `recsys_events`. Starting offset: `earliest` (unchanged).

### 5.4 User Embedding Key Fix

`UserEmbeddingFunction` output key changes from:
```
"feature:user:" + userId + ":embedding"
```
to:
```
"u2vEmb:" + userId
```

This aligns with `RedisEmbeddingStore("u2vEmb")` used by both `RecSysServer` and the fixed `OnlinePredictionServer`.

### 5.5 Vector Format Fix

`encodeVector()` separator changes from `,` to `' '` (space):

```java
// Before:
if (i > 0) builder.append(',');
// After:
if (i > 0) builder.append(' ');
```

`VectorMath.parseVector()` splits on `\\s+` — this makes Flink-written vectors parseable with zero changes to serving.

---

## 6. Spark Job Changes

### 6.1 `UserEventStreamingJob.scala` — Schema update + Redis write removal

- Parse `timestamp_ms` (millis) instead of `timestamp` (seconds). Divide by 1000 for TTL calculations.
- **Remove** the `LPUSH`/`LTRIM`/`EXPIRE` block for `user:{id}:recent`. Flink owns this key in the `user:{id}:recent_movies` format. Spark retains only the `ZINCRBY global:item_popularity` write.
- Topic: `user_events` → `recsys_events`. Consumer group: `training-user-history`.

### 6.2 `OnlineJoinerStreamingJob.scala` — Schema update + offset fix

- Parse `timestamp_ms` instead of `timestamp`; divide by 1000 in `impression_time` timestamp cast.
- `startingOffsets`: `"latest"` → `"earliest"`. Events produced before the job starts are currently silently lost.
- Topic: `user_events` → `recsys_events`. Consumer group: `training-joiner`.

### 6.3 `ExperienceCollectorStreamingJob.scala` — Offset fix only

- `startingOffsets`: `"latest"` → `"earliest"`. Reads from `training_samples` (not raw events), so no schema change.
- Consumer group: `training-experience`.

### 6.4 Training Loop Closure (`run-retrain.sh`)

```bash
SAMPLE_COUNT=$(find "${TRAINING_PATH:-/tmp/spark-recsys/training-samples}" -name "*.parquet" | wc -l)
if [ "$SAMPLE_COUNT" -gt "${RETRAIN_THRESHOLD:-50}" ]; then
  ./run-offline-pipeline.sh          # Item2Vec → Redis i2vEmb:*
  ./run-user-embedding-pipeline.sh   # UserEmbedding → Redis u2vEmb:* (script to be created per streaming SPEC.md P1.1)
fi
```

The loop: `recsys_events` → Spark OnlineJoiner → HDFS Parquet → `run-retrain.sh` cron → Redis embeddings → RecSysServer + OnlinePredictionServer.

---

## 7. Serving Layer Fix (`OnlinePredictionServer.java`)

Wire user embedding store into `CandidateGenerator`:

```java
// Before:
CandidateGenerator candidateGenerator = new CandidateGenerator(dataManager);

// After:
RedisEmbeddingStore userEmbeddingStore = new RedisEmbeddingStore(jedisPool, "u2vEmb");
CandidateGenerator candidateGenerator = new CandidateGenerator(dataManager, userEmbeddingStore);
```

Once the Flink key fix (§5.4) and format fix (§5.5) are in place, `CandidateGenerator.byEmbedding()` will find user vectors in Redis and return non-empty candidates, enabling the `online+model` blend strategy instead of always falling back to `online`.

---

## 8. Producer Changes (`producer.py`)

- Output topic: `recsys_events` (was `user_events`)
- Schema updated to unified format: rename `user_id`/`item_id` (unchanged), add `event_id` (UUID v4), rename `timestamp` → `timestamp_ms` (multiply existing value by 1000), add optional `session_id`, `position`, `*_features`
- Item IDs generated as `"movie_1"` through `"movie_{NUM_ITEMS}"` so integer suffix falls within the default catalog range
- `behavior` mode already produces most optional fields — just rename keys and add `event_id`

---

## 9. Embedding Alignment Summary

| Component | Before | After |
|---|---|---|
| Flink user embedding key | `feature:user:{id}:embedding` | `u2vEmb:{id}` |
| Flink user embedding format | comma-separated (`0.12,0.34`) | space-separated (`0.12 0.34`) |
| `OnlinePredictionServer` `CandidateGenerator` | no Redis store (always empty) | `RedisEmbeddingStore("u2vEmb")` wired in |
| `VectorMath.parseVector()` | splits on `\\s+` | unchanged (now compatible) |
| Item IDs in topK → catalog lookup | any string suffix (may miss) | constrained to catalog range |

---

## 10. `movie_events.ndjson` Local Fallback

The Flink job falls back to reading `streaming/online-serving/data/movie_events.ndjson` when no Kafka `bootstrap.servers` is set. Update this file to use the unified schema so `mvn exec:java -Dexec.mainClass=com.recsys.online.flink.OnlineFeatureStreamingJob` can seed Redis without any streaming infra.

---

## 11. Testing Strategy

### Local Smoke Test (no Kafka)

```bash
# 1. Start Redis
docker run -d -p 6379:6379 redis:7-alpine

# 2. Run Flink job in local mode against ndjson fixture
mvn exec:java -Dexec.mainClass=com.recsys.online.flink.OnlineFeatureStreamingJob

# 3. Verify Redis populated
redis-cli keys "user:*:recent_movies"      # expect non-empty
redis-cli zrange "topk:last_hour" 0 -1 WITHSCORES

# 4. Start OnlinePredictionServer and hit it
curl "http://localhost:7010/v1/recommend?userId=1&window=last_hour"
```

### Integration Test (full pipeline)

```bash
docker-compose -f docker-compose.streaming.yml up -d
python producer.py --mode behavior --events 200          # seeds recsys_events
# wait ~10 s for Flink window to fire
redis-cli keys "user:*:recent_movies"
redis-cli keys "u2vEmb:*"
redis-cli zrange "topk:last_hour" 0 -1 WITHSCORES
curl "http://localhost:7010/v1/recommend?userId=user_1&window=last_hour"

# Spark training path
ls /tmp/spark-recsys/training-samples/                   # expect Parquet files
RETRAIN_THRESHOLD=0 ./run-retrain.sh                     # force retrain
redis-cli keys "i2vEmb:*"                                # expect embeddings
```

### Checkpoint Recovery Test

```bash
FLINK_CHECKPOINT_DIR=/tmp/flink-checkpoints ./run-flink-job.sh
# send 50 events via producer
kill <flink-pid>
FLINK_CHECKPOINT_DIR=/tmp/flink-checkpoints ./run-flink-job.sh   # restart
# send 10 more events
# verify topK scores reflect all 60 events, not reprocessed-from-earliest duplicates
```

### Regression Guard

Existing `OnlinePredictionRegressionTest` and `OnlinePredictionServerIntegrationTest` cover the serving layer. Update the `movie_events.ndjson` fixture to unified schema to keep those tests passing without changes to test code.

---

## 12. Out of Scope

- Catalog externalization (G7 from streaming pipeline SPEC.md) — item IDs constrained to demo catalog range instead
- Model hot-reload endpoint — tracked in streaming pipeline SPEC.md Phase 4.2
- Distributed Flink cluster deployment — `docker-compose.streaming.yml` Flink setup is sufficient for demo
- Replacing Spark streaming with Flink (Approach 3) — deferred until this loop is proven end-to-end

---

## 13. Files Changed

| File | Repo | Change |
|---|---|---|
| `OnlineFeatureStreamingJob.java` | Backend | Checkpoint storage, unified schema parser, user embedding key + format fix |
| `MovieEvent.java` | Backend | New string `userId`/`itemId` fields + prefix-strip parser |
| `OnlinePredictionServer.java` | Backend | Wire `RedisEmbeddingStore("u2vEmb")` into `CandidateGenerator` |
| `streaming/online-serving/data/movie_events.ndjson` | Backend | Update to unified schema |
| `docker-compose.streaming.yml` | Backend | Add `FLINK_CHECKPOINT_DIR` to Flink services |
| `UserEventStreamingJob.scala` | Streaming | Schema update, remove `user:{id}:recent` write, new topic + group |
| `OnlineJoinerStreamingJob.scala` | Streaming | Schema update, `earliest` offset, new topic + group |
| `ExperienceCollectorStreamingJob.scala` | Streaming | `earliest` offset, new consumer group |
| `producer.py` | Streaming | Unified schema output, `recsys_events` topic, item IDs in catalog range |
| `run-retrain.sh` | Streaming | Wire HDFS sample count → trigger retrain |
