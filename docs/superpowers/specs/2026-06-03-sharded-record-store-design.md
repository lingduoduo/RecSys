# Sharded Record Store — Design Spec

**Date:** 2026-06-03  
**Status:** Approved  
**Scope:** `com.recsys.infrastructure.redis.sharding`

---

## Problem

The existing `ShardedTopKStore` does *read-replica* sharding — all N shards hold identical data and a random shard is picked on cache miss. There is no *data-partitioning* layer: event records, behavioral feature records, and general log records have no shared structure, no sequence numbers, and no stable device → shard mapping. This makes incremental consumption (Flink, batch export) and per-device targeted reads impossible without a full Redis scan.

---

## Boundary: What This Design Does NOT Cover

Several existing components handle adjacent concerns and are **unchanged**:

| Component | What it handles | Why excluded |
|---|---|---|
| `RedisEmbeddingStore` | Item/user embeddings (`float[]`) as `{prefix}:{id}` → float string | Write-once per training cycle; no ordering, dedup, or cursor reads needed |
| `OnlineFeatureStore` | Pure reader — loads arbitrary Redis keys (history, CTR, session, embeddings) with JVM cache | No write path; delegates to Flink-written keys |
| `ShardedTopKStore` | Trending Top-K sorted sets as read replicas | Read-replica pattern, not data partitioning |
| `EmbeddingLSH` | SimHash of `float[]` vectors for ANN similarity search | Similarity hashing — orthogonal to consistent-hash partitioning |

The `ConsistentHashRing` maps a **device/user ID string → shard index** for data partitioning. This is completely separate from `EmbeddingLSH`, which maps a **float[] vector → similarity bucket**. The two hashing schemes solve different problems and coexist without conflict.

---

## Goals

- Map any device/user ID to a stable shard via consistent hashing
- Assign a shard-scoped monotonic sequence number to every record
- Use that sequence number for: deduplication, ordering, optimistic versioning, and cursor-based reads
- Cover three record types with clear boundaries:
  - **EVENT** — click, watch, rating, dwell, search events (new; no existing handler)
  - **FEATURE** — Flink-written behavioral features only: recent history updates, CTR events, session data (streaming writes that need ordering and dedup; raw embeddings remain in `RedisEmbeddingStore`)
  - **LOG** — general audit/debug log entries (new; no existing handler)
- Expose two read modes: per-device cursor and shard-level stream scan

---

## Architecture

```
                ┌─────────────────────┐
  deviceId ───► │ ConsistentHashRing  │──► shardIndex (0..N-1)
                └─────────────────────┘
                           │
                           ▼
                ┌─────────────────────┐     INCR seq:{shardIndex}
  record ──────►│ ShardedRecordStore  │────────────────────────────► SequenceGenerator
                └─────────────────────┘
                    │            │
          ZADD NX   │            │  XADD
                    ▼            ▼
         dev:{N}:{deviceId}   stream:{N}        Redis
           (Sorted Set)       (Stream)
           score = seqNum     entry = seq + payload
```

Four units, each with one responsibility:

| Class | Responsibility |
|---|---|
| `ConsistentHashRing` | Maps `deviceId` → `shardIndex` via virtual-node ring |
| `SequenceGenerator` | Assigns shard-scoped monotonic seq numbers via Redis `INCR` |
| `ShardedRecord` | Immutable record with `deviceId`, `seqNum`, `type`, `eventId`, `payload`, `timestamp` |
| `ShardedRecordStore` | Orchestrates write (Sorted Set + Stream) and both read paths |

---

## Component Designs

### ConsistentHashRing

Virtual-node ring backed by `TreeMap<Long, Integer>`. Each physical shard gets `V` virtual nodes (default 150) spread by hashing `"{shardIndex}:v{i}"` with MurmurHash3.

**Lookup:** `TreeMap.ceilingKey(hash(deviceId))` — O(log V·N), effectively O(1) for fixed N. Wraps to `firstKey()` when hash exceeds all nodes.

**Immutable after construction** — no lock needed on the read path.

```java
class ConsistentHashRing {
    ConsistentHashRing(int shardCount, int virtualNodesPerShard)
    int shardFor(String deviceId)                                  // hot path, lock-free
    int shardCount()
    Map<Integer, Integer> distribution(Collection<String> deviceIds) // diagnostic
}
```

Adding/removing shards remaps ~1/N of devices. Increase `virtualNodesPerShard` (→ 300) if per-shard imbalance exceeds 20% as measured by `distribution()`.

---

### SequenceGenerator

Thin wrapper around Redis `INCR`. Each shard has its own counter at `sr:seq:{shardIndex}`. On startup, verifies the counter is not behind the highest seq number seen in any device Sorted Set; if so, resets it unconditionally with `SET sr:seq:{N} {max+1}` (not NX — the counter may exist at a stale low value after a partial flush).

```java
class SequenceGenerator {
    long next(int shardIndex)          // INCR sr:seq:{shardIndex}
    void ensureCounterValid(int shardIndex, Pool<Jedis> pool)
}
```

---

### Data Model

**`ShardedRecord`:**

```java
record ShardedRecord(
    String     deviceId,     // originating device / user
    long       seqNum,       // shard-scoped monotonic sequence number
    RecordType type,         // EVENT | FEATURE | LOG
    String     eventId,      // caller-supplied dedup key (Kafka offset, UUID, etc.)
    String     payload,      // JSON or opaque string
    long       timestamp     // epoch-ms at write time
)

enum RecordType {
    EVENT,    // click, watch, rating, dwell, search — from LogCollector / Kafka
    FEATURE,  // Flink-written behavioral features: recent history, CTR, session data
              // NOT raw embeddings (float[]) — those stay in RedisEmbeddingStore
    LOG       // general audit / debug log entries
}
```

**`ShardCursor`:**

```java
record ShardCursor(String value) {
    static ShardCursor start()           // "0-0" for streams, "-1" for ZSet
    static ShardCursor of(String value)
    boolean isStart()
}
```

---

### Redis Key Schema

All keys use a configurable prefix (default `"sr:"`):

| Key | Type | Purpose |
|---|---|---|
| `sr:seq:{shardIndex}` | String | Monotonic counter — `INCR` assigns next seq |
| `sr:dev:{shardIndex}:{deviceId}` | Sorted Set | Per-device index: member=`eventId`, score=`seqNum` |
| `sr:rec:{shardIndex}:{seqNum}` | Hash | Full record: `deviceId`, `type`, `eventId`, `payload`, `timestamp` |
| `sr:stream:{shardIndex}` | Stream | Shard-level ordered log: fields = `deviceId`, `seq`, `type`, `eventId` |

`eventId` is the Sorted Set *member* (not `seqNum`) so `ZADD NX` deduplicates by business key, not by seq. `ZADD XX GT` updates the score only when the new `seqNum` is greater, preventing stale writers from overwriting newer records.

---

## Write Path

```
write(record):
  1. shardIndex = ring.shardFor(record.deviceId)
  2. seqNum     = INCR sr:seq:{shardIndex}
  3. pipeline:
       HSET sr:rec:{shardIndex}:{seqNum}
         deviceId {deviceId} type {type} eventId {eventId}
         payload {payload} timestamp {timestamp}
       ZADD NX sr:dev:{shardIndex}:{deviceId} {seqNum} {eventId}
       XADD sr:stream:{shardIndex} MAXLEN ~ 1_000_000 *
         deviceId {deviceId} seq {seqNum} type {type} eventId {eventId}
  4. if ZADD result == 0: status = DUPLICATE (stream entry still written — append-only)
  returns: WriteResult(seqNum, shardIndex, status)
```

**Versioned update** (for FEATURE records):

```
update(record):
  1-2. same (INCR + assign seq)
  3. pipeline:
       HSET sr:rec:{shardIndex}:{seqNum} ...
       ZADD XX GT sr:dev:{shardIndex}:{deviceId} {seqNum} {eventId}
       XADD sr:stream:{shardIndex} MAXLEN ~ 1_000_000 * ...
```

`INCR` is a separate round-trip (return value needed before pipeline). Steps 3–5 execute in a single pipeline — 1 round-trip for the bulk of the work.

**`WriteResult`:**

```java
record WriteResult(long seqNum, int shardIndex, WriteStatus status)
enum WriteStatus { OK, DUPLICATE }
```

---

## Read Paths

### Per-Device Cursor Read

```
readDevice(deviceId, cursor, limit):
  1. shardIndex = ring.shardFor(deviceId)
  2. afterSeq   = cursor.isStart() ? -1 : Long.parseLong(cursor.value)
  3. scores     = ZRANGEBYSCORE sr:dev:{shardIndex}:{deviceId}
                    (afterSeq +inf WITHSCORES LIMIT 0 {limit}
  4. pipeline: HGETALL sr:rec:{shardIndex}:{seqNum} for each result
  5. nextCursor = ShardCursor.of(String.valueOf(lastSeqNum))  // null if empty
  returns: Page<ShardedRecord>(records, nextCursor)
```

Step 4 is a single pipelined multi-HGETALL — one round-trip regardless of `limit`.

### Shard-Level Scan

```
readShard(shardIndex, cursor, limit):
  1. streamKey = "sr:stream:{shardIndex}"
  2. entries   = XREAD COUNT {limit} STREAMS {streamKey} {cursor.value}
  3. pipeline: HGETALL sr:rec:{shardIndex}:{seq} for each entry's seq field
  4. nextCursor = ShardCursor.of(lastStreamEntryId)  // null if no entries
  returns: Page<ShardedRecord>(records, nextCursor)
```

```java
// Consume all shards independently (Flink source / batch export)
List<Page<ShardedRecord>> readAllShards(ShardCursor cursor, int limitPerShard)
```

Each shard cursor advances independently — one slow shard does not block others.

---

## Error Handling

| Scenario | Behaviour |
|---|---|
| Pipeline fails after `INCR` (seq consumed, no record written) | Seq gap — harmless; readers use range scans, not contiguous seq assumption |
| `write` throws | Exception propagates; caller retries with backoff |
| `HGETALL` returns empty (record TTL expired) | Reader skips entry and advances cursor |
| Hot shard imbalance > 20% | Increase `virtualNodesPerShard`; observable via `ring.distribution()` |
| Redis counter reset (flush) | `SequenceGenerator.ensureCounterValid()` on startup: reads max score from all device ZSets in shard, issues `SET sr:seq:{N} {max+1}` unconditionally if counter < max |

**Stream trimming** — `XADD MAXLEN ~ 1_000_000` keeps each shard stream bounded at ~1 M entries (approximate trim to avoid O(N) on every write).

**TTL per RecordType** — callers pass optional `ttlSeconds`; store sets `EXPIRE sr:rec:{N}:{seq}` after `HSET`. ZSet member and stream entry remain (lightweight); expired `HGETALL` returns empty map — reader skips.

---

## Public Interface Summary

```java
// com.recsys.infrastructure.redis.sharding

class ConsistentHashRing {
    ConsistentHashRing(int shardCount, int virtualNodesPerShard)
    int shardFor(String deviceId)
    int shardCount()
    Map<Integer, Integer> distribution(Collection<String> deviceIds)
}

class ShardedRecordStore {
    ShardedRecordStore(Pool<Jedis> pool, ConsistentHashRing ring,
                       SequenceGenerator seqGen, String keyPrefix)

    WriteResult write(ShardedRecord record)
    WriteResult write(ShardedRecord record, int ttlSeconds)
    WriteResult update(ShardedRecord record)                         // ZADD XX GT

    Page<ShardedRecord> readDevice(String deviceId, ShardCursor cursor, int limit)
    Page<ShardedRecord> readShard(int shardIndex, ShardCursor cursor, int limit)
    List<Page<ShardedRecord>> readAllShards(ShardCursor cursor, int limitPerShard)
}

record ShardedRecord(String deviceId, long seqNum, RecordType type,
                     String eventId, String payload, long timestamp)
record WriteResult(long seqNum, int shardIndex, WriteStatus status)
record Page<T>(List<T> records, ShardCursor next) { boolean hasMore() }
record ShardCursor(String value) { static ShardCursor start(); boolean isStart() }
enum RecordType { EVENT, FEATURE, LOG }  // FEATURE = behavioral only; embeddings → RedisEmbeddingStore
enum WriteStatus { OK, DUPLICATE }
```

---

## Testing Plan

| Test class | What it covers |
|---|---|
| `ConsistentHashRingTest` | Single shard → all devices map to 0; N shards → < 20% imbalance on large device set; ring wrap at `Long.MAX_VALUE`; deterministic across JVM restarts |
| `SequenceGeneratorTest` | Monotonic increment; concurrent writers never produce duplicate seq; counter-reset guard on startup |
| `ShardedRecordStoreWriteTest` | `write` returns `OK` and correct `shardIndex`; duplicate `eventId` returns `DUPLICATE`; `update` with stale seq loses to newer (`ZADD XX GT`); pipeline executes in one round-trip |
| `ShardedRecordStoreReadTest` | `readDevice` respects cursor and limit; empty device returns empty page; cursor advances across pages; `readShard` returns all records in seq order; `readAllShards` advances each shard independently |
| `ShardedRecordStoreTtlTest` | Expired `sr:rec` entries skipped during reads; ZSet member and stream entry remain |
| `ShardedRecordStoreIntegrationTest` | Full write → read round-trip for EVENT, FEATURE, LOG; 10 concurrent writers produce no duplicates and no seq gaps; shard-level scan returns all records written by per-device writes |

All tests use Testcontainers `redis:7-alpine` — no mocks, consistent with existing project test style.

---

## Package Layout

```
src/main/java/com/recsys/infrastructure/redis/sharding/
├── ConsistentHashRing.java
├── SequenceGenerator.java
├── ShardedRecord.java
├── ShardedRecordStore.java
├── ShardCursor.java
├── WriteResult.java
├── WriteStatus.java
└── RecordType.java

src/test/java/com/recsys/infrastructure/redis/sharding/
├── ConsistentHashRingTest.java
├── SequenceGeneratorTest.java
├── ShardedRecordStoreWriteTest.java
├── ShardedRecordStoreReadTest.java
├── ShardedRecordStoreTtlTest.java
└── ShardedRecordStoreIntegrationTest.java
```
