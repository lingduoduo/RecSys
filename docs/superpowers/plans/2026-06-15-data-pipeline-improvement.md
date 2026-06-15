# Data Pipeline Improvement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the broken Kafka → Flink → Redis → Serving pipeline by unifying the event topic/schema, fixing embedding key/format mismatches, enabling durable Flink checkpoints, and closing the training loop.

**Architecture:** One unified Kafka topic `recsys_events` fans out to Flink (online features → Redis) and Spark (training samples → HDFS). `MovieEvent` gains `@JsonSetter` methods to parse both old camelCase and new snake_case unified schemas. `OnlinePredictionServer` is wired with a Redis user embedding store so blended recommendations become possible.

**Tech Stack:** Java 21, Flink 1.18 (RocksDB state backend), Spark 3.5 (Scala 2.12, sbt), Python 3 (kafka-python), Redis 7, Jackson 2 for JSON parsing.

**Repos involved:**
- `Recsys-Backend-Service` — Flink job (`OnlineFeatureStreamingJob`), serving layer (`OnlinePredictionServer`), event model (`MovieEvent`)
- `Recsys-Streaming-Pipeline` — Spark streaming jobs, `producer.py`, `run-retrain.sh`

---

## File Map

| File | Repo | Change |
|---|---|---|
| `src/main/java/com/recsys/online/flink/MovieEvent.java` | Backend | Add `@JsonSetter` for `user_id`, `item_id`, `timestamp_ms`, `session_id`; `@JsonAlias` for `event_id` |
| `src/test/java/com/recsys/online/flink/MovieEventTest.java` | Backend | New test class |
| `src/main/java/com/recsys/online/flink/OnlineFeatureStreamingJob.java` | Backend | Checkpoint storage, topic/group, user embedding key, vector separator |
| `src/test/java/com/recsys/online/flink/OnlineFeatureStreamingJobTest.java` | Backend | New test class |
| `src/main/java/com/recsys/online/serving/OnlinePredictionServer.java` | Backend | Wire `RedisEmbeddingStore("u2vEmb")` into `CandidateGenerator` |
| `streaming/online-serving/data/movie_events.ndjson` | Backend | Update 19 events to unified schema |
| `docker-compose.streaming.yml` | Backend | Add `FLINK_CHECKPOINT_DIR` to Flink services |
| `recsys-pipeline/services/spark-streaming-job/src/main/scala/com/demo/task/UserEventStreamingJob.scala` | Streaming | Unified schema, remove Redis write, topic + group |
| `recsys-pipeline/services/spark-streaming-job/src/test/scala/com/demo/task/UserEventStreamingJobSpec.scala` | Streaming | New test class |
| `recsys-pipeline/services/spark-streaming-job/src/main/scala/com/demo/process/OnlineJoinerStreamingJob.scala` | Streaming | Unified schema, `"earliest"` offset, topic + group |
| `recsys-pipeline/services/spark-streaming-job/src/main/scala/com/demo/process/ExperienceCollectorStreamingJob.scala` | Streaming | `"earliest"` offset, consumer group |
| `recsys-pipeline/services/python-modeling/producer.py` | Streaming | Unified schema, `recsys_events` topic, `movie_*` item IDs |
| `recsys-pipeline/run-retrain.sh` | Streaming | Add sample-count trigger guard |

---

## Task 1: `MovieEvent` — unified schema parsing

**Files:**
- Modify: `src/main/java/com/recsys/online/flink/MovieEvent.java`
- Create: `src/test/java/com/recsys/online/flink/MovieEventTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/online/flink/MovieEventTest.java`:

```java
package com.recsys.online.flink;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MovieEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void parsesLegacyIntegerFields() throws Exception {
        String json = """
                {"eventId":"evt-001","userId":42,"movieId":7,
                 "eventType":"click","eventTimeMillis":1718400000000}
                """;
        MovieEvent e = MAPPER.readValue(json, MovieEvent.class);
        assertThat(e.userId).isEqualTo(42);
        assertThat(e.movieId).isEqualTo(7);
        assertThat(e.eventId).isEqualTo("evt-001");
        assertThat(e.eventTimeMillis).isEqualTo(1718400000000L);
    }

    @Test
    void parsesUnifiedStringFields() throws Exception {
        String json = """
                {"event_id":"uuid-abc","user_id":"user_42","item_id":"movie_7",
                 "event_type":"click","timestamp_ms":1718400001000,
                 "session_id":"sess_x"}
                """;
        MovieEvent e = MAPPER.readValue(json, MovieEvent.class);
        assertThat(e.userId).isEqualTo(42);
        assertThat(e.movieId).isEqualTo(7);
        assertThat(e.eventId).isEqualTo("uuid-abc");
        assertThat(e.eventTimeMillis).isEqualTo(1718400001000L);
        assertThat(e.sessionId()).isEqualTo("sess_x");
        assertThat(e.isClick()).isTrue();
    }

    @Test
    void parsesNonNumericItemIdViaHash() throws Exception {
        String json = """
                {"event_id":"uuid-xyz","user_id":"alice","item_id":"scarface",
                 "event_type":"view","timestamp_ms":1718400002000}
                """;
        MovieEvent e = MAPPER.readValue(json, MovieEvent.class);
        assertThat(e.userId).isGreaterThanOrEqualTo(0);
        assertThat(e.movieId).isGreaterThanOrEqualTo(0);
    }

    @Test
    void sessionIdFallsBackToFeaturesMap() throws Exception {
        String json = """
                {"eventId":"e1","userId":1,"movieId":1,"eventType":"click",
                 "eventTimeMillis":1000,"features":{"session_id":"sess_legacy"}}
                """;
        MovieEvent e = MAPPER.readValue(json, MovieEvent.class);
        assertThat(e.sessionId()).isEqualTo("sess_legacy");
    }
}
```

- [ ] **Step 2: Run test to see it fail**

```bash
cd /Users/linghuang/Git/Recsys-Backend-Service
mvn test -Dtest=MovieEventTest -pl . 2>&1 | tail -20
```

Expected: compilation error (new test references `MovieEvent.sessionId()` with string field that doesn't exist yet) or FAIL on `parsesUnifiedStringFields`.

- [ ] **Step 3: Update `MovieEvent.java`**

Add `@JsonSetter` methods and update `sessionId()`. Replace the full file content:

```java
package com.recsys.online.flink;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MovieEvent {
    private static final long MIN_VIEW_WATCH_MS = 30_000L;

    @JsonAlias("event_id")
    public String eventId;
    public int userId;
    public int movieId;
    @JsonAlias("event_type")
    public String eventType;
    public long watchMs;
    public long dwellMs;
    public Integer rating;
    public long eventTimeMillis;
    public String source;
    public Map<String, String> features;
    // Unified schema: session_id as a top-level field
    private String sessionIdField;

    public MovieEvent() {}

    // Unified schema setters — Jackson calls these when it sees snake_case keys.
    // The existing camelCase fields (userId, movieId, eventTimeMillis) handle the old format.

    @JsonSetter("user_id")
    public void setUserIdStr(String raw) {
        this.userId = parseIdSuffix(raw);
    }

    @JsonSetter("item_id")
    public void setItemIdStr(String raw) {
        this.movieId = parseIdSuffix(raw);
    }

    @JsonSetter("timestamp_ms")
    public void setTimestampMs(long ms) {
        this.eventTimeMillis = ms;
    }

    @JsonSetter("session_id")
    public void setSessionIdField(String sessionId) {
        this.sessionIdField = sessionId;
    }

    // Parses "user_42" → 42, "movie_7" → 7, falls back to abs(hashCode) for non-numeric.
    private static int parseIdSuffix(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        int lastUnderscore = raw.lastIndexOf('_');
        String suffix = lastUnderscore >= 0 ? raw.substring(lastUnderscore + 1) : raw;
        try {
            return Math.abs(Integer.parseInt(suffix));
        } catch (NumberFormatException e) {
            return Math.abs(raw.hashCode()) % Integer.MAX_VALUE;
        }
    }

    public boolean hasEventIdentity() {
        return eventId != null && !eventId.isBlank();
    }

    public String idempotencyKey() {
        if (hasEventIdentity()) {
            return eventId.trim();
        }
        return userId + ":" + movieId + ":" + eventType + ":" + eventTimeMillis;
    }

    public boolean isView() {
        return matches("view") || matches("watch");
    }

    public boolean isClick() {
        return matches("click");
    }

    public boolean isLike() {
        return matches("like");
    }

    public boolean isRating() {
        return matches("rating");
    }

    public boolean isDwell() {
        return matches("dwell");
    }

    public boolean isSearch() {
        return matches("search");
    }

    public boolean isOrder() {
        return matches("order") || matches("purchase");
    }

    public boolean hasSessionIdentity() {
        return sessionId() != null && !sessionId().isBlank();
    }

    public String sessionId() {
        // Prefer the top-level session_id field from unified schema.
        if (sessionIdField != null && !sessionIdField.isBlank()) {
            return sessionIdField.trim();
        }
        // Fall back to features map for legacy events.
        if (features == null || features.isEmpty()) {
            return "";
        }
        String sid = firstNonBlank(features.get("sessionId"), features.get("session_id"));
        return sid != null ? sid : "";
    }

    public boolean updatesRecentHistory() {
        if (movieId <= 0 || isSearch()) {
            return false;
        }
        return isClick()
                || isLike()
                || isOrder()
                || isRating()
                || (isView() && watchMs >= MIN_VIEW_WATCH_MS)
                || (isDwell() && dwellMs >= 10_000L);
    }

    public int trainingLabel() {
        if (isOrder()) return 3;
        if (isLike() || isRating() && rating != null && rating >= 4) return 2;
        if (isClick()
                || isSearch()
                || isView() && watchMs >= MIN_VIEW_WATCH_MS
                || isDwell() && dwellMs >= 10_000L) return 1;
        return 0;
    }

    public long engagementWeight() {
        if (isOrder()) return 8L;
        if (isLike()) return 3L;
        if (isRating()) return rating != null && rating >= 4 ? 4L : 0L;
        if (isClick()) return 2L;
        if (isView() && watchMs >= MIN_VIEW_WATCH_MS) return 1L;
        if (isDwell() && dwellMs >= 10_000L) return 1L;
        return 0L;
    }

    private boolean matches(String expected) {
        return expected.equalsIgnoreCase(eventType);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first.trim();
        if (second != null && !second.isBlank()) return second.trim();
        return null;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn test -Dtest=MovieEventTest 2>&1 | tail -10
```

Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/online/flink/MovieEvent.java \
        src/test/java/com/recsys/online/flink/MovieEventTest.java
git commit -m "feat: MovieEvent parses unified snake_case schema alongside legacy camelCase"
```

---

## Task 2: Flink — fix vector format and user embedding Redis key

**Files:**
- Modify: `src/main/java/com/recsys/online/flink/OnlineFeatureStreamingJob.java:314` (`encodeVector`)
- Modify: `src/main/java/com/recsys/online/flink/OnlineFeatureStreamingJob.java:287` (`UserEmbeddingFunction.processElement`)
- Create: `src/test/java/com/recsys/online/flink/OnlineFeatureStreamingJobTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/online/flink/OnlineFeatureStreamingJobTest.java`:

```java
package com.recsys.online.flink;

import com.recsys.infrastructure.vectordb.VectorMath;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OnlineFeatureStreamingJobTest {

    @Test
    void encodeVectorUsesSpaceSeparator() throws Exception {
        // Access package-private encodeVector via reflection for unit testing.
        var method = OnlineFeatureStreamingJob.UserEmbeddingFunction.class
                .getDeclaredMethod("encodeVector", double[].class);
        method.setAccessible(true);
        // Dummy instance needed for non-static inner class; dimensions/ttl don't matter.
        var fn = new OnlineFeatureStreamingJob.UserEmbeddingFunction(4, 3600);
        String encoded = (String) method.invoke(fn, new double[]{1.0, 0.0, 0.0, 0.0});
        assertThat(encoded).doesNotContain(",");
        assertThat(encoded).contains(" ");
    }

    @Test
    void encodedVectorIsParsableByVectorMath() throws Exception {
        var method = OnlineFeatureStreamingJob.UserEmbeddingFunction.class
                .getDeclaredMethod("encodeVector", double[].class);
        method.setAccessible(true);
        var fn = new OnlineFeatureStreamingJob.UserEmbeddingFunction(4, 3600);
        String encoded = (String) method.invoke(fn, new double[]{3.0, 4.0, 0.0, 0.0});
        float[] parsed = VectorMath.parseVector(encoded);
        assertThat(parsed).hasSize(4);
        // After L2-normalisation: 3/5=0.6, 4/5=0.8
        assertThat(parsed[0]).isCloseTo(0.6f, org.assertj.core.data.Offset.offset(0.001f));
        assertThat(parsed[1]).isCloseTo(0.8f, org.assertj.core.data.Offset.offset(0.001f));
    }
}
```

- [ ] **Step 2: Run test to see it fail**

```bash
mvn test -Dtest=OnlineFeatureStreamingJobTest 2>&1 | tail -15
```

Expected: FAIL on `encodeVectorUsesSpaceSeparator` — encoded string contains `,`.

- [ ] **Step 3: Fix `encodeVector` separator in `OnlineFeatureStreamingJob.java`**

In `UserEmbeddingFunction.encodeVector()` at line ~313, change:

```java
// Before (line ~317):
if (i > 0) builder.append(',');

// After:
if (i > 0) builder.append(' ');
```

- [ ] **Step 4: Fix user embedding Redis key in `UserEmbeddingFunction.processElement()`**

At line ~287, change the `StringFeatureUpdate` key argument:

```java
// Before:
out.collect(new StringFeatureUpdate(
        "feature:user:" + event.userId + ":embedding",
        encoded,
        event.eventTimeMillis,
        ttlSeconds
));

// After:
out.collect(new StringFeatureUpdate(
        "u2vEmb:" + event.userId,
        encoded,
        event.eventTimeMillis,
        ttlSeconds
));
```

- [ ] **Step 5: Run tests to verify both pass**

```bash
mvn test -Dtest=OnlineFeatureStreamingJobTest 2>&1 | tail -10
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 6: Run full test suite to verify no regressions**

```bash
mvn test 2>&1 | tail -15
```

Expected: `BUILD SUCCESS`

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/online/flink/OnlineFeatureStreamingJob.java \
        src/test/java/com/recsys/online/flink/OnlineFeatureStreamingJobTest.java
git commit -m "fix: Flink user embedding writes to u2vEmb:{id} with space-separated vector format"
```

---

## Task 3: Flink — configurable checkpoint storage + unified Kafka topic

**Files:**
- Modify: `src/main/java/com/recsys/online/flink/OnlineFeatureStreamingJob.java` (main method)
- Modify: `docker-compose.streaming.yml`

- [ ] **Step 1: Add checkpoint storage configuration**

In `OnlineFeatureStreamingJob.main()`, directly after `env.enableCheckpointing(...)` at line ~70, add:

```java
String checkpointDir = System.getenv("FLINK_CHECKPOINT_DIR");
if (checkpointDir != null && !checkpointDir.isBlank()) {
    env.getCheckpointConfig().setCheckpointStorage(checkpointDir);
    env.setStateBackend(new org.apache.flink.contrib.streaming.state.EmbeddedRocksDBStateBackend(true));
}
```

Add the import at the top of the file:

```java
import org.apache.flink.contrib.streaming.state.EmbeddedRocksDBStateBackend;
```

- [ ] **Step 2: Change Kafka topic and consumer group**

In `buildEventStream()`, update the `KafkaSource` builder (lines ~148–151):

```java
// Before:
KafkaSource<String> source = KafkaSource.<String>builder()
        .setBootstrapServers(bootstrapServers)
        .setTopics(topic)
        .setGroupId(params.get("group.id", "recsys-online-feature-job"))

// After:
KafkaSource<String> source = KafkaSource.<String>builder()
        .setBootstrapServers(bootstrapServers)
        .setTopics(topic)
        .setGroupId(params.get("group.id", "online-features"))
```

Update the default topic parameter at line ~61:

```java
// Before:
String topic = params.get("topic", "movie_events");

// After:
String topic = params.get("topic", "recsys_events");
```

- [ ] **Step 3: Add `FLINK_CHECKPOINT_DIR` to docker-compose**

In `docker-compose.streaming.yml`, add the env var to both `jobmanager` and `taskmanager` services:

```yaml
  jobmanager:
    image: flink:1.18
    ports:
      - "8081:8081"
    command: jobmanager
    environment:
      - JOB_MANAGER_RPC_ADDRESS=jobmanager
      - FLINK_CHECKPOINT_DIR=/tmp/flink-checkpoints   # add this line

  taskmanager:
    image: flink:1.18
    depends_on:
      jobmanager:
        condition: service_healthy
    command: taskmanager
    environment:
      - JOB_MANAGER_RPC_ADDRESS=jobmanager
      - TASK_MANAGER_NUMBER_OF_TASK_SLOTS=4
      - FLINK_CHECKPOINT_DIR=/tmp/flink-checkpoints   # add this line
```

- [ ] **Step 4: Verify build compiles**

```bash
mvn package -DskipTests 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/online/flink/OnlineFeatureStreamingJob.java \
        docker-compose.streaming.yml
git commit -m "feat: Flink checkpoint storage via FLINK_CHECKPOINT_DIR env var; topic recsys_events"
```

---

## Task 4: `OnlinePredictionServer` — wire user embedding store

**Files:**
- Modify: `src/main/java/com/recsys/online/serving/OnlinePredictionServer.java:49`

- [ ] **Step 1: Add the import and change `CandidateGenerator` construction**

At the top of `OnlinePredictionServer.java`, add the import (after existing Redis imports):

```java
import com.recsys.infrastructure.redis.RedisEmbeddingStore;
```

At line ~49, replace:

```java
// Before:
CandidateGenerator candidateGenerator = new CandidateGenerator(dataManager);

// After:
RedisEmbeddingStore userEmbeddingStore = new RedisEmbeddingStore(jedisPool, "u2vEmb");
CandidateGenerator candidateGenerator = new CandidateGenerator(dataManager, userEmbeddingStore);
```

- [ ] **Step 2: Verify build compiles and existing tests pass**

```bash
mvn test -Dtest=OnlinePredictionServerIntegrationTest,OnlinePredictionRegressionTest 2>&1 | tail -15
```

Expected: `BUILD SUCCESS` — existing integration tests pass because `CandidateGenerator.byEmbedding()` returns empty when Redis has no `u2vEmb:*` keys (same behavior as before, just now checks Redis first).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/recsys/online/serving/OnlinePredictionServer.java
git commit -m "fix: wire RedisEmbeddingStore(u2vEmb) into CandidateGenerator in OnlinePredictionServer"
```

---

## Task 5: Update `movie_events.ndjson` fixture to unified schema

**Files:**
- Modify: `streaming/online-serving/data/movie_events.ndjson`

- [ ] **Step 1: Rewrite the fixture**

Replace the entire file with 19 events in unified schema format. Item IDs use `"movie_1"` through `"movie_7"` to stay within the default catalog range. Timestamps use `timestamp_ms` (millis):

```
{"event_id":"evt-001","user_id":"user_123","item_id":"movie_1","event_type":"view","timestamp_ms":1713503000000,"watch_ms":720000}
{"event_id":"evt-002","user_id":"user_123","item_id":"movie_2","event_type":"view","timestamp_ms":1713503060000,"watch_ms":680000}
{"event_id":"evt-003","user_id":"user_124","item_id":"movie_5","event_type":"view","timestamp_ms":1713503120000,"watch_ms":540000}
{"event_id":"evt-004","user_id":"user_124","item_id":"movie_6","event_type":"like","timestamp_ms":1713503180000}
{"event_id":"evt-005","user_id":"user_125","item_id":"movie_7","event_type":"view","timestamp_ms":1713503240000,"watch_ms":760000}
{"event_id":"evt-006","user_id":"user_126","item_id":"movie_3","event_type":"click","timestamp_ms":1713503300000,"session_id":"sess_a"}
{"event_id":"evt-007","user_id":"user_126","item_id":"movie_4","event_type":"order","timestamp_ms":1713503360000,"session_id":"sess_a"}
{"event_id":"evt-008","user_id":"user_127","item_id":"movie_2","event_type":"click","timestamp_ms":1713503420000}
{"event_id":"evt-009","user_id":"user_127","item_id":"movie_1","event_type":"view","timestamp_ms":1713503480000,"watch_ms":900000}
{"event_id":"evt-010","user_id":"user_128","item_id":"movie_5","event_type":"click","timestamp_ms":1713503540000,"session_id":"sess_b"}
{"event_id":"evt-011","user_id":"user_128","item_id":"movie_6","event_type":"like","timestamp_ms":1713503600000,"session_id":"sess_b"}
{"event_id":"evt-012","user_id":"user_129","item_id":"movie_7","event_type":"order","timestamp_ms":1713503660000}
{"event_id":"evt-013","user_id":"user_129","item_id":"movie_3","event_type":"click","timestamp_ms":1713503720000}
{"event_id":"evt-014","user_id":"user_130","item_id":"movie_1","event_type":"view","timestamp_ms":1713503780000,"watch_ms":1200000}
{"event_id":"evt-015","user_id":"user_130","item_id":"movie_4","event_type":"like","timestamp_ms":1713503840000}
{"event_id":"evt-016","user_id":"user_123","item_id":"movie_3","event_type":"click","timestamp_ms":1713503900000,"session_id":"sess_c"}
{"event_id":"evt-017","user_id":"user_124","item_id":"movie_2","event_type":"order","timestamp_ms":1713503960000}
{"event_id":"evt-018","user_id":"user_125","item_id":"movie_5","event_type":"click","timestamp_ms":1713504020000}
{"event_id":"evt-019","user_id":"user_126","item_id":"movie_7","event_type":"view","timestamp_ms":1713504080000,"watch_ms":480000}
```

Note: `watchMs` field in `MovieEvent` remains camelCase for backward compat; `watch_ms` in the ndjson is treated as unknown and ignored by Jackson (`@JsonIgnoreProperties(ignoreUnknown = true)`). The Flink job uses `watchMs` for dwell/view qualification — set existing events with `watchMs` via `features` map or leave watchMs defaulting to 0. Because the view events above have `watch_ms` ignored, views will have `watchMs=0` which is under `MIN_VIEW_WATCH_MS=30000ms`. Add `watchMs` to view events:

Revise lines with `event_type: "view"` to add the `watchMs` field (camelCase, already on the `MovieEvent`):

```
{"event_id":"evt-001","user_id":"user_123","item_id":"movie_1","event_type":"view","timestamp_ms":1713503000000,"watchMs":720000}
{"event_id":"evt-002","user_id":"user_123","item_id":"movie_2","event_type":"view","timestamp_ms":1713503060000,"watchMs":680000}
{"event_id":"evt-003","user_id":"user_124","item_id":"movie_5","event_type":"view","timestamp_ms":1713503120000,"watchMs":540000}
{"event_id":"evt-004","user_id":"user_124","item_id":"movie_6","event_type":"like","timestamp_ms":1713503180000}
{"event_id":"evt-005","user_id":"user_125","item_id":"movie_7","event_type":"view","timestamp_ms":1713503240000,"watchMs":760000}
{"event_id":"evt-006","user_id":"user_126","item_id":"movie_3","event_type":"click","timestamp_ms":1713503300000,"session_id":"sess_a"}
{"event_id":"evt-007","user_id":"user_126","item_id":"movie_4","event_type":"order","timestamp_ms":1713503360000,"session_id":"sess_a"}
{"event_id":"evt-008","user_id":"user_127","item_id":"movie_2","event_type":"click","timestamp_ms":1713503420000}
{"event_id":"evt-009","user_id":"user_127","item_id":"movie_1","event_type":"view","timestamp_ms":1713503480000,"watchMs":900000}
{"event_id":"evt-010","user_id":"user_128","item_id":"movie_5","event_type":"click","timestamp_ms":1713503540000,"session_id":"sess_b"}
{"event_id":"evt-011","user_id":"user_128","item_id":"movie_6","event_type":"like","timestamp_ms":1713503600000,"session_id":"sess_b"}
{"event_id":"evt-012","user_id":"user_129","item_id":"movie_7","event_type":"order","timestamp_ms":1713503660000}
{"event_id":"evt-013","user_id":"user_129","item_id":"movie_3","event_type":"click","timestamp_ms":1713503720000}
{"event_id":"evt-014","user_id":"user_130","item_id":"movie_1","event_type":"view","timestamp_ms":1713503780000,"watchMs":1200000}
{"event_id":"evt-015","user_id":"user_130","item_id":"movie_4","event_type":"like","timestamp_ms":1713503840000}
{"event_id":"evt-016","user_id":"user_123","item_id":"movie_3","event_type":"click","timestamp_ms":1713503900000,"session_id":"sess_c"}
{"event_id":"evt-017","user_id":"user_124","item_id":"movie_2","event_type":"order","timestamp_ms":1713503960000}
{"event_id":"evt-018","user_id":"user_125","item_id":"movie_5","event_type":"click","timestamp_ms":1713504020000}
{"event_id":"evt-019","user_id":"user_126","item_id":"movie_7","event_type":"view","timestamp_ms":1713504080000,"watchMs":480000}
```

- [ ] **Step 2: Smoke-test with the Flink job in file mode**

```bash
# Start Redis (if not already running)
docker run -d --name redis-test -p 6379:6379 redis:7-alpine

# Run Flink job against the ndjson file (no Kafka needed)
mvn exec:java \
  -Dexec.mainClass=com.recsys.online.flink.OnlineFeatureStreamingJob \
  -Dexec.args="--window-seconds 5 --window-label last_hour --input-file streaming/online-serving/data/movie_events.ndjson" \
  2>&1 | tail -20

# Verify Redis keys populated
docker exec redis-test redis-cli keys "user:*:recent_movies"
docker exec redis-test redis-cli keys "u2vEmb:*"
docker exec redis-test redis-cli zrange "topk:last_hour" 0 -1 WITHSCORES
```

Expected: non-empty output for all three redis-cli commands.

- [ ] **Step 3: Commit**

```bash
git add streaming/online-serving/data/movie_events.ndjson
git commit -m "fix: update movie_events.ndjson fixture to unified schema with string IDs and timestamp_ms"
```

---

## Task 6: Spark — `UserEventStreamingJob` unified schema + remove duplicate Redis write

**Repo:** `Recsys-Streaming-Pipeline`

**Files:**
- Modify: `recsys-pipeline/services/spark-streaming-job/src/main/scala/com/demo/task/UserEventStreamingJob.scala`
- Create: `recsys-pipeline/services/spark-streaming-job/src/test/scala/com/demo/task/UserEventStreamingJobSpec.scala`

- [ ] **Step 1: Write the failing test**

Create `recsys-pipeline/services/spark-streaming-job/src/test/scala/com/demo/task/UserEventStreamingJobSpec.scala`:

```scala
package com.demo.task

import com.demo.SparkTestSupport
import org.apache.spark.sql.functions._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class UserEventStreamingJobSpec extends AnyFlatSpec with Matchers with SparkTestSupport {

  "parseUnifiedEvents" should "parse unified schema with string IDs and timestamp_ms" in {
    import spark.implicits._
    val json = Seq(
      """{"event_id":"e1","user_id":"user_5","item_id":"movie_3","event_type":"click","timestamp_ms":1718400000000}""",
      """{"event_id":"e2","user_id":"user_5","item_id":"movie_4","event_type":"impression","timestamp_ms":1718400001000}"""
    ).toDF("value")

    val parsed = UserEventStreamingJob.parseEvents(json)
    val clicks = parsed.filter($"event_type" === "click").collect()
    clicks should have length 1
    clicks.head.getAs[String]("user_id") shouldBe "user_5"
    clicks.head.getAs[String]("item_id") shouldBe "movie_3"
    clicks.head.getAs[Long]("timestamp_ms") shouldBe 1718400000000L
  }

  it should "parse legacy schema with integer timestamp field" in {
    import spark.implicits._
    val json = Seq(
      """{"user_id":"user_1","item_id":"item_1","event_type":"click","timestamp":1718400000}"""
    ).toDF("value")

    val parsed = UserEventStreamingJob.parseEvents(json)
    parsed.filter($"event_type" === "click").count() shouldBe 1
  }
}
```

- [ ] **Step 2: Add a `parseEvents` helper to `UserEventStreamingJob` and update the schema**

In `UserEventStreamingJob.scala`, update the schema and add a `parseEvents` method. Replace the `schema` val and parsing code:

```scala
// Replace existing schema val:
val schema = StructType(Seq(
  StructField("user_id", StringType, nullable = false),
  StructField("item_id", StringType, nullable = false),
  StructField("event_type", StringType, nullable = false),
  StructField("timestamp_ms", LongType, nullable = true),   // unified schema (millis)
  StructField("timestamp", LongType, nullable = true)        // legacy compat (seconds)
))

// Add parseEvents method (called from writeStream.foreachBatch too):
def parseEvents(rawKafka: org.apache.spark.sql.DataFrame): org.apache.spark.sql.DataFrame = {
  import rawKafka.sparkSession.implicits._
  rawKafka.selectExpr("CAST(value AS STRING) as json")
    .select(from_json(col("json"), schema).as("data"))
    .select("data.*")
    .filter(
      col("user_id").isNotNull &&
        col("item_id").isNotNull &&
        col("event_type") === "click"
    )
    // Normalise timestamp to millis: prefer timestamp_ms, fall back to timestamp*1000
    .withColumn("timestamp_ms",
      coalesce(col("timestamp_ms"), col("timestamp") * 1000L))
}
```

- [ ] **Step 3: Remove LPUSH/LTRIM block and update topic/group**

In `main()`, change:

```scala
// Before:
val kafkaTopic = sys.env.getOrElse("KAFKA_TOPIC", "user_events")

// After:
val kafkaTopic = sys.env.getOrElse("KAFKA_TOPIC", "recsys_events")
```

In `readStream`:

```scala
// Add consumer group option:
.option("kafka.group.id", sys.env.getOrElse("KAFKA_GROUP_ID", "training-user-history"))
.option("startingOffsets", "earliest")
```

In `foreachBatch`, remove the entire `userItems.foreach` block (LPUSH/LTRIM/EXPIRE) and keep only the `itemCounts.foreach` block:

```scala
parsed.writeStream.foreachBatch { (batch: DataFrame, _: Long) =>
  batch.foreachPartition { rows: Iterator[Row] =>
    val itemCounts = scala.collection.mutable.Map.empty[String, Int]

    rows.foreach { row =>
      try {
        val item = row.getAs[String]("item_id")
        itemCounts(item) = itemCounts.getOrElse(item, 0) + 1
      } catch {
        case e: Exception =>
          log.warn("Skipping malformed row: {}", e.getMessage)
      }
    }

    val pool = RedisPool.get(poolHost, poolPort, poolMax)
    val jedis = pool.getResource
    try {
      val pipeline = jedis.pipelined()
      var pendingCommands = 0

      def flushIfNeeded(): Unit =
        if (pendingCommands >= redisPipelineSize) { pipeline.sync(); pendingCommands = 0 }

      itemCounts.foreach { case (item, count) =>
        pipeline.zincrby("global:item_popularity", count.toDouble, item)
        pendingCommands += 1
        flushIfNeeded()
      }

      if (pendingCommands > 0) pipeline.sync()
    } finally {
      jedis.close()
    }
  }
}
```

- [ ] **Step 4: Run tests**

```bash
cd /Users/linghuang/Git/Recsys-Streaming-Pipeline/recsys-pipeline/services/spark-streaming-job
sbt "testOnly com.demo.task.UserEventStreamingJobSpec" 2>&1 | tail -15
```

Expected: `All tests passed`

- [ ] **Step 5: Commit**

```bash
cd /Users/linghuang/Git/Recsys-Streaming-Pipeline
git add recsys-pipeline/services/spark-streaming-job/src/main/scala/com/demo/task/UserEventStreamingJob.scala \
        recsys-pipeline/services/spark-streaming-job/src/test/scala/com/demo/task/UserEventStreamingJobSpec.scala
git commit -m "feat: UserEventStreamingJob reads recsys_events; removes user history write (Flink owns it)"
```

---

## Task 7: Spark — `OnlineJoinerStreamingJob` unified schema + offset fix

**Repo:** `Recsys-Streaming-Pipeline`

**Files:**
- Modify: `recsys-pipeline/services/spark-streaming-job/src/main/scala/com/demo/process/OnlineJoinerStreamingJob.scala`
- Modify: `recsys-pipeline/services/spark-streaming-job/src/test/scala/com/demo/process/OnlineJoinerStreamingJobSpec.scala`

- [ ] **Step 1: Add a test for `timestamp_ms` parsing in the existing spec**

Add to the existing `OnlineJoinerStreamingJobSpec.scala` (append after the last `it should` block):

```scala
  it should "parse unified schema with timestamp_ms field" in {
    val sparkSession = spark
    import sparkSession.implicits._

    val events = Seq(
      ("req_3", "user_5", "movie_3", "impression", 1718400000000L, 0,
        Map("tier" -> "vip"), Map("genre" -> "action"), Map("device" -> "web")),
      ("req_3", "user_5", "movie_3", "click", 1718400005000L, 0,
        Map.empty[String, String], Map.empty[String, String], Map.empty[String, String])
    ).toDF("request_id", "user_id", "item_id", "event_type", "timestamp_ms",
           "position", "user_features", "item_features", "context_features")

    val rows = OnlineJoinerStreamingJob.buildTrainingSamples(
      events.withColumnRenamed("timestamp_ms", "timestamp")
    ).collect()
    rows should have length 1
    rows.head.getAs[Int]("clicked") shouldBe 1
  }
```

- [ ] **Step 2: Update `EventSchema` and `startingOffsets` in `OnlineJoinerStreamingJob`**

In `OnlineJoinerStreamingJob.scala`, update:

```scala
// Replace EventSchema timestamp field:
val EventSchema: StructType = StructType(Seq(
  StructField("request_id", StringType, nullable = false),
  StructField("user_id", StringType, nullable = false),
  StructField("item_id", StringType, nullable = false),
  StructField("event_type", StringType, nullable = false),
  StructField("timestamp_ms", LongType, nullable = true),    // unified (millis)
  StructField("timestamp", LongType, nullable = true),        // legacy compat
  StructField("position", IntegerType, nullable = true),
  StructField("user_features", MapType(StringType, StringType), nullable = true),
  StructField("item_features", MapType(StringType, StringType), nullable = true),
  StructField("context_features", MapType(StringType, StringType), nullable = true)
))
```

Change `startingOffsets` and add consumer group:

```scala
// Before:
.option("startingOffsets", "latest")

// After:
.option("startingOffsets", sys.env.getOrElse("KAFKA_STARTING_OFFSETS", "earliest"))
.option("kafka.group.id", sys.env.getOrElse("KAFKA_GROUP_ID", "training-joiner"))
```

Change default topic:

```scala
// Before:
val inputTopic = sys.env.getOrElse("ONLINE_JOINER_INPUT_TOPIC", "behavior_logs")

// After:
val inputTopic = sys.env.getOrElse("ONLINE_JOINER_INPUT_TOPIC", "recsys_events")
```

In `parseEvents`, normalise timestamp:

```scala
def parseEvents(rawKafka: DataFrame): DataFrame =
  rawKafka.selectExpr("CAST(value AS STRING) AS json")
    .select(from_json(col("json"), EventSchema).as("data"))
    .select("data.*")
    .filter(
      col("request_id").isNotNull &&
        col("user_id").isNotNull &&
        col("item_id").isNotNull &&
        col("event_type").isNotNull
    )
    // Normalise to a single timestamp column used by buildTrainingSamples
    .withColumn("timestamp",
      coalesce(col("timestamp_ms"), col("timestamp") * 1000L))
    .drop("timestamp_ms")
```

- [ ] **Step 3: Run tests**

```bash
cd /Users/linghuang/Git/Recsys-Streaming-Pipeline/recsys-pipeline/services/spark-streaming-job
sbt "testOnly com.demo.process.OnlineJoinerStreamingJobSpec" 2>&1 | tail -15
```

Expected: `All tests passed`

- [ ] **Step 4: Commit**

```bash
cd /Users/linghuang/Git/Recsys-Streaming-Pipeline
git add recsys-pipeline/services/spark-streaming-job/src/main/scala/com/demo/process/OnlineJoinerStreamingJob.scala \
        recsys-pipeline/services/spark-streaming-job/src/test/scala/com/demo/process/OnlineJoinerStreamingJobSpec.scala
git commit -m "fix: OnlineJoinerStreamingJob reads recsys_events from earliest; handles timestamp_ms"
```

---

## Task 8: Spark — `ExperienceCollectorStreamingJob` offset fix

**Repo:** `Recsys-Streaming-Pipeline`

**Files:**
- Modify: `recsys-pipeline/services/spark-streaming-job/src/main/scala/com/demo/process/ExperienceCollectorStreamingJob.scala`

- [ ] **Step 1: Change `startingOffsets` and add consumer group**

In `ExperienceCollectorStreamingJob.scala`, in the `readStream` section:

```scala
// Before:
.option("startingOffsets", "latest")

// After:
.option("startingOffsets", sys.env.getOrElse("KAFKA_STARTING_OFFSETS", "earliest"))
.option("kafka.group.id", sys.env.getOrElse("KAFKA_GROUP_ID", "training-experience"))
```

- [ ] **Step 2: Run existing spec**

```bash
cd /Users/linghuang/Git/Recsys-Streaming-Pipeline/recsys-pipeline/services/spark-streaming-job
sbt "testOnly com.demo.process.ExperienceCollectorStreamingJobSpec" 2>&1 | tail -10
```

Expected: `All tests passed`

- [ ] **Step 3: Commit**

```bash
cd /Users/linghuang/Git/Recsys-Streaming-Pipeline
git add recsys-pipeline/services/spark-streaming-job/src/main/scala/com/demo/process/ExperienceCollectorStreamingJob.scala
git commit -m "fix: ExperienceCollectorStreamingJob reads from earliest; adds consumer group"
```

---

## Task 9: `producer.py` — unified schema, `recsys_events` topic, catalog-aligned item IDs

**Repo:** `Recsys-Streaming-Pipeline`

**Files:**
- Modify: `recsys-pipeline/services/python-modeling/producer.py`

- [ ] **Step 1: Write a test for the new event shape**

Add a test in `recsys-pipeline/integration-tests/python_modeling/test_producer.py`. Open the file and add after existing tests:

```python
import uuid
from services.python_modeling import producer  # adjust import path if needed

def test_click_event_has_unified_schema():
    users = ["user_1", "user_2"]
    items = ["movie_1", "movie_2", "movie_3"]
    event = producer.make_click_event(users, items)
    assert "event_id" in event
    assert event["user_id"].startswith("user_")
    assert event["item_id"].startswith("movie_")
    assert "timestamp_ms" in event
    assert isinstance(event["timestamp_ms"], int)
    assert event["timestamp_ms"] > 1_000_000_000_000  # millis, not seconds

def test_behavior_slate_has_unified_schema():
    users = ["user_1"]
    items = ["movie_1", "movie_2", "movie_3", "movie_4", "movie_5"]
    events = producer.make_behavior_slate(users, items)
    impressions = [e for e in events if e["event_type"] == "impression"]
    assert len(impressions) >= 1
    for e in events:
        assert "event_id" in e
        assert "timestamp_ms" in e
        assert e["item_id"].startswith("movie_")
```

- [ ] **Step 2: Run test to see it fail**

```bash
cd /Users/linghuang/Git/Recsys-Streaming-Pipeline/recsys-pipeline
python -m pytest integration-tests/python_modeling/test_producer.py -v 2>&1 | tail -20
```

Expected: FAIL — `event_id` and `timestamp_ms` not present yet.

- [ ] **Step 3: Update `producer.py`**

Apply these changes to `recsys-pipeline/services/python-modeling/producer.py`:

```python
# Line 1: add uuid import (already imported at top)
import uuid

# Change TOPIC default:
TOPIC = os.getenv("KAFKA_TOPIC", "recsys_events")

# Change NUM_ITEMS ceiling and item ID format in make_click_event:
def make_click_event(users, items):
    return {
        "event_id": str(uuid.uuid4()),
        "user_id": random.choice(users),
        "item_id": random.choice(items),
        "event_type": "click",
        "timestamp_ms": int(time.time() * 1000),
    }

# Change make_behavior_slate:
def make_behavior_slate(users, items):
    now_ms = int(time.time() * 1000)
    user = random.choice(users)
    request_id = f"req_{uuid.uuid4().hex[:12]}"
    slate_items = random.sample(items, min(SLATE_SIZE, len(items)))
    device = random.choice(["ios", "android", "web"])
    country = random.choice(["US", "CA", "GB"])
    user_tier = random.choice(["new", "standard", "vip"])
    session_id = f"sess_{uuid.uuid4().hex[:8]}"

    events = []
    for position, item in enumerate(slate_items):
        events.append({
            "event_id": str(uuid.uuid4()),
            "request_id": request_id,
            "user_id": user,
            "item_id": item,
            "event_type": "impression",
            "timestamp_ms": now_ms,
            "position": position,
            "session_id": session_id,
            "user_features": {"tier": user_tier},
            "item_features": {"bucket": f"b{int(item.split('_')[-1]) % 4}"},
            "context_features": {"device": device, "country": country},
        })

    clicked_item = random.choice(slate_items) if random.random() < 0.35 else None
    if clicked_item:
        events.append({
            "event_id": str(uuid.uuid4()),
            "request_id": request_id,
            "user_id": user,
            "item_id": clicked_item,
            "event_type": "click",
            "timestamp_ms": now_ms + random.randint(1000, 20000),
            "position": slate_items.index(clicked_item),
            "session_id": session_id,
            "user_features": {},
            "item_features": {},
            "context_features": {},
        })

        if random.random() < 0.12:
            events.append({
                "event_id": str(uuid.uuid4()),
                "request_id": request_id,
                "user_id": user,
                "item_id": clicked_item,
                "event_type": "order",
                "timestamp_ms": now_ms + random.randint(21000, 120000),
                "position": slate_items.index(clicked_item),
                "session_id": session_id,
                "user_features": {},
                "item_features": {},
                "context_features": {},
            })

    return events

# In main(), change item ID generation to use "movie_" prefix:
# Before:
#   items = [f"item_{i}" for i in range(1, NUM_ITEMS + 1)]
# After:
    items = [f"movie_{i}" for i in range(1, NUM_ITEMS + 1)]
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd /Users/linghuang/Git/Recsys-Streaming-Pipeline/recsys-pipeline
python -m pytest integration-tests/python_modeling/test_producer.py -v 2>&1 | tail -15
```

Expected: `passed`

- [ ] **Step 5: Commit**

```bash
cd /Users/linghuang/Git/Recsys-Streaming-Pipeline
git add recsys-pipeline/services/python-modeling/producer.py \
        recsys-pipeline/integration-tests/python_modeling/test_producer.py
git commit -m "feat: producer emits unified schema to recsys_events; movie_ item IDs; event_id + timestamp_ms"
```

---

## Task 10: `run-retrain.sh` — add HDFS sample-count trigger guard

**Repo:** `Recsys-Streaming-Pipeline`

**Files:**
- Modify: `recsys-pipeline/run-retrain.sh`

- [ ] **Step 1: Add sample-count check before Spark steps**

After the `DRY_RUN` / variable block and before `echo "=== Recsys Retraining Pipeline ==="`, add:

```bash
TRAINING_PATH="${TRAINING_PATH:-/tmp/spark-recsys/training-samples}"
RETRAIN_THRESHOLD="${RETRAIN_THRESHOLD:-50}"

SAMPLE_COUNT=0
if [[ -d "${TRAINING_PATH}" ]]; then
  SAMPLE_COUNT=$(find "${TRAINING_PATH}" -name "*.parquet" | wc -l | tr -d ' ')
fi

if [[ "${SAMPLE_COUNT}" -lt "${RETRAIN_THRESHOLD}" && "${DRY_RUN}" != "1" ]]; then
  echo "Skipping retrain: only ${SAMPLE_COUNT} Parquet files found in ${TRAINING_PATH} (threshold: ${RETRAIN_THRESHOLD})."
  echo "Run with DRY_RUN=1 to preview, or RETRAIN_THRESHOLD=0 to force."
  exit 0
fi
```

- [ ] **Step 2: Verify dry-run skips correctly**

```bash
cd /Users/linghuang/Git/Recsys-Streaming-Pipeline/recsys-pipeline
TRAINING_PATH=/nonexistent RETRAIN_THRESHOLD=50 DRY_RUN=0 bash run-retrain.sh 2>&1 | head -5
```

Expected output: `Skipping retrain: only 0 Parquet files found ...`

- [ ] **Step 3: Verify force mode bypasses guard**

```bash
TRAINING_PATH=/nonexistent RETRAIN_THRESHOLD=0 DRY_RUN=1 bash run-retrain.sh 2>&1 | head -10
```

Expected: prints `DRY RUN:` lines for all steps, no early exit.

- [ ] **Step 4: Commit**

```bash
cd /Users/linghuang/Git/Recsys-Streaming-Pipeline
git add recsys-pipeline/run-retrain.sh
git commit -m "feat: run-retrain.sh skips unless HDFS sample count exceeds RETRAIN_THRESHOLD"
```

---

## Task 11: Full regression pass

- [ ] **Step 1: Run all Backend tests**

```bash
cd /Users/linghuang/Git/Recsys-Backend-Service
mvn test 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`

- [ ] **Step 2: Run all Streaming Pipeline tests**

```bash
cd /Users/linghuang/Git/Recsys-Streaming-Pipeline/recsys-pipeline/services/spark-streaming-job
sbt test 2>&1 | tail -20
```

Expected: `All tests passed`

- [ ] **Step 3: Smoke test end-to-end (optional, requires Docker)**

```bash
cd /Users/linghuang/Git/Recsys-Backend-Service

# 1. Start infra
docker-compose -f docker-compose.streaming.yml up -d

# 2. Wait for Kafka + Flink + Redis to be healthy
sleep 30

# 3. Seed events via producer (200 events in behavior mode)
cd /Users/linghuang/Git/Recsys-Streaming-Pipeline/recsys-pipeline
NUM_ITEMS=7 PRODUCER_MODE=behavior MAX_EVENTS=200 python services/python-modeling/producer.py

# 4. Wait for Flink window (10 s default)
sleep 15

# 5. Verify Redis keys
docker exec redis-primary redis-cli keys "user:*:recent_movies"
docker exec redis-primary redis-cli keys "u2vEmb:*"
docker exec redis-primary redis-cli zrange "topk:last_hour" 0 -1 WITHSCORES

# 6. Start OnlinePredictionServer and query
cd /Users/linghuang/Git/Recsys-Backend-Service
mvn exec:java -Dexec.mainClass=com.recsys.online.serving.OnlinePredictionServer &
sleep 5
curl "http://localhost:7010/v1/recommend?userId=123&window=last_hour"
# Expected: JSON with non-empty "recommendations" array
```

---

## Self-Review Checklist

**Spec coverage:**

| Spec section | Task |
|---|---|
| Unified topic `recsys_events` | Tasks 3, 6, 7, 9 |
| Unified event schema (C) | Tasks 1, 5, 6, 7, 9 |
| Flink persistent checkpoint storage | Task 3 |
| Flink consumer group `online-features` | Task 3 |
| Redis key consolidation (`u2vEmb` wins) | Task 2 |
| Vector format fix (comma → space) | Task 2 |
| `OnlinePredictionServer` embedding wire | Task 4 |
| `movie_events.ndjson` fixture update | Task 5 |
| `UserEventStreamingJob` remove Redis write | Task 6 |
| `OnlineJoinerStreamingJob` `"earliest"` + schema | Task 7 |
| `ExperienceCollectorStreamingJob` `"earliest"` | Task 8 |
| Producer unified schema + item IDs | Task 9 |
| Training loop `run-retrain.sh` guard | Task 10 |
| `docker-compose.streaming.yml` FLINK_CHECKPOINT_DIR | Task 3 |
| Catalog constraint (item IDs ∈ `movie_1..7`) | Tasks 5, 9 |
