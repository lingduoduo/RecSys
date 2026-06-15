# Data Lineage — Event ID Propagation Design

_Date: 2026-06-15_
_Branch: feat/api-service-consolidation_

---

## 1. Problem Statement

After the data pipeline improvement (see `2026-06-15-data-pipeline-improvement-design.md`), feature keys in Redis (`u2vEmb:{id}`, `user:{id}:recent_movies`, session features) are written by Flink from `recsys_events`. There is currently no way to answer:

- **A** — Which source event last updated `u2vEmb:42`?
- **B** — What were the last 5 events that shaped this embedding?
- **C** — Which feature keys were touched by event `uuid-abc`?

---

## 2. Approach

**Extend existing Lua scripts atomically.** The `SET_IF_NEWER_SCRIPT` already writes two keys per feature update (`{key}` and `{key}:updated_at`). Adding three companion lineage keys in the same Lua call ensures lineage is written if and only if the feature value is written — the existing timestamp guard covers all five writes together.

This is the only viable approach for correctness: a separate lineage sink would fire even when the timestamp guard skips the embedding write, producing misleading lineage for stale events.

---

## 3. Scope

### In scope — per-event feature sinks

| Flink operator | Redis key written | Lineage written |
|---|---|---|
| `UserEmbeddingFunction` | `u2vEmb:{userId}` | yes |
| `UserRecentHistoryFunction` | `user:{userId}:recent_movies` | yes |
| `SessionFeatureFunction` | `feature:session:{sessionId}:*` | yes |

### Out of scope — windowed aggregate sinks

| Flink operator | Redis key written | Reason skipped |
|---|---|---|
| `TopKWindowFunction` | `topk:{window}` | N events → 1 write; no single event_id |
| `MovieMetricFunction` | `movie:{id}:{metric}` | Window aggregate |

The serving layer (`OnlinePredictionServer`, `RecSysServer`, `ModelApplication`) requires **zero changes** — lineage keys are companions that existing read paths never touch.

---

## 4. Redis Key Schema

Three companion keys are written alongside every qualifying per-event feature write:

| Key pattern | Redis type | Content | Lookup |
|---|---|---|---|
| `{key}:last_event` | String | `event_id` of the write that last won the timestamp guard | A |
| `{key}:event_history` | List | Last 5 `event_id`s, newest-first (RPUSH + LTRIM -5 -1) | B |
| `lineage:event:{eventId}` | Set | All feature keys touched by this event in this Flink invocation | C |

**TTL:** all three companion keys use the same TTL as the feature key they accompany, set in the same Lua call. When the embedding evicts, lineage evicts with it.

**Examples:**

```
GET u2vEmb:42:last_event               → "uuid-abc"
LRANGE u2vEmb:42:event_history 0 -1   → ["uuid-abc", "uuid-xyz", "uuid-001", "uuid-002", "uuid-003"]
SMEMBERS lineage:event:uuid-abc        → {"u2vEmb:42", "user:42:recent_movies"}
```

---

## 5. Implementation

### 5.1 Type Changes

Add `String eventId` to `StringFeatureUpdate` and `UserRecentMoviesUpdate` in `OnlineFeatureStreamingJob.java`.

```java
static final class StringFeatureUpdate {
    final String redisKey;
    final String value;
    final long updatedAtMillis;
    final int ttlSeconds;
    final String eventId;          // NEW

    StringFeatureUpdate(String redisKey, String value, long updatedAtMillis,
                        int ttlSeconds, String eventId) { ... }
}
```

Same addition to `UserRecentMoviesUpdate`.

### 5.2 Operator Changes

`UserEmbeddingFunction`, `SessionFeatureFunction`, and `UserRecentHistoryFunction` each already have `event.eventId` in scope. Pass it as the new constructor argument when emitting update objects:

```java
out.collect(new StringFeatureUpdate(
        "u2vEmb:" + event.userId,
        encodeVector(vector),
        event.eventTimeMillis,
        ttlSeconds,
        event.eventId          // NEW
));
```

### 5.3 Lua Script Extension

`SET_IF_NEWER_SCRIPT` gains two new keys and one new arg:

```
Before:
  KEYS[1] = redisKey
  KEYS[2] = redisKey + ":updated_at"
  ARGV[1] = updatedAtMillis
  ARGV[2] = ttlSeconds
  ARGV[3] = value

After:
  KEYS[1] = redisKey
  KEYS[2] = redisKey + ":updated_at"
  KEYS[3] = redisKey + ":last_event"
  KEYS[4] = redisKey + ":event_history"
  KEYS[5] = "lineage:event:" + eventId
  ARGV[1] = updatedAtMillis
  ARGV[2] = ttlSeconds
  ARGV[3] = value
  ARGV[4] = eventId
```

Full updated script:

```lua
local current = redis.call('GET', KEYS[2])
if current and tonumber(current) > tonumber(ARGV[1]) then
  return 0
end
redis.call('SETEX', KEYS[1], tonumber(ARGV[2]), ARGV[3])
redis.call('SETEX', KEYS[2], tonumber(ARGV[2]), ARGV[1])
redis.call('SETEX', KEYS[3], tonumber(ARGV[2]), ARGV[4])
redis.call('RPUSH', KEYS[4], ARGV[4])
redis.call('LTRIM', KEYS[4], -5, -1)
redis.call('EXPIRE', KEYS[4], tonumber(ARGV[2]))
redis.call('SADD', KEYS[5], KEYS[1])
redis.call('EXPIRE', KEYS[5], tonumber(ARGV[2]))
return 1
```

`LTRIM -5 -1` keeps the 5 newest entries (RPUSH appends to the right; trim from the right end).

`ZSET_IF_NEWER_SCRIPT` (used by TopK) is **not modified**.

### 5.4 Sink Call-Site Changes

`RedisStringFeatureSink.invoke` and `RedisRecentMoviesSink.invoke` pass the additional KEYS and ARGV:

```java
jedis.eval(
    SET_IF_NEWER_SCRIPT,
    List.of(value.redisKey,
            value.redisKey + ":updated_at",
            value.redisKey + ":last_event",
            value.redisKey + ":event_history",
            "lineage:event:" + value.eventId),
    List.of(Long.toString(value.updatedAtMillis),
            Integer.toString(value.ttlSeconds),
            value.value,
            value.eventId)
);
```

---

## 6. Testing

All tests added to the existing `OnlineFeatureStreamingJobTest.java`. Requires a live Redis instance; use Testcontainers `redis:7-alpine` or a local Redis started in `@BeforeAll`.

### 6.1 `lineageKeysWrittenOnEmbeddingUpdate`

Invoke `UserEmbeddingFunction` via reflection with a `MovieEvent` carrying `eventId="evt-test"`. Assert the emitted `StringFeatureUpdate.eventId` equals `"evt-test"`.

### 6.2 `luaScriptWritesCompanionKeys`

Call `setStringIfNewer(jedis, "u2vEmb:1", "0.6 0.8", now, 3600, "evt-test")`. Assert:
- `GET u2vEmb:1:last_event` → `"evt-test"`
- `LRANGE u2vEmb:1:event_history 0 -1` → `["evt-test"]`
- `SMEMBERS lineage:event:evt-test` → `{"u2vEmb:1"}`

### 6.3 `luaScriptSkipsLineageWhenNewerExists`

Write with timestamp T₁, then with T₀ < T₁. Assert all lineage keys still reflect the T₁ event (the second write is silently rejected).

### 6.4 `eventHistoryCapAtFive`

Call `setStringIfNewer` six times with distinct event IDs (timestamps T₁…T₆, all increasing). Assert:
- `LLEN u2vEmb:1:event_history` → `5`
- `LINDEX u2vEmb:1:event_history 0` → `"evt-002"` (oldest of the 5 retained)
- `LINDEX u2vEmb:1:event_history 4` → `"evt-006"` (newest)

---

## 7. Files Changed

| File | Change |
|---|---|
| `src/main/java/com/recsys/online/flink/OnlineFeatureStreamingJob.java` | Add `eventId` to `StringFeatureUpdate` + `UserRecentMoviesUpdate`; extend `SET_IF_NEWER_SCRIPT`; update three operators + two sinks |
| `src/test/java/com/recsys/online/flink/OnlineFeatureStreamingJobTest.java` | Add 4 new tests (sections 6.1–6.4) |

---

## 8. Out of Scope

- No API endpoint for lineage queries — use `redis-cli` or the existing Jedis pool directly
- No Spark-side lineage — `event_id` is already present in Parquet training samples from the unified schema
- No lineage for windowed aggregates (`topk:*`, `movie:*:*`)
- No lineage persistence beyond Redis TTL (no HDFS archive)
