# Findings — Replication / Sharding Investigation

## ShardedRecordStore

### Write path (doWrite)
1. INCR seq counter — separate Redis call (return value needed for key construction)
2. Pipeline: HSET full record + ZADD (NX new / GT update) on device ZSet + XADD to shard stream

Atomicity gap: if the pipeline fails after INCR, the seq number is burned. No record is written but the counter advances. Gaps in sequence are silent. Non-critical in practice but no test covers this.

### Read path
- `readDevice`: queries device ZSet (ZRANGEBYSCORE), then pipelined HGETALL for each seq in results
- `readShard`: XREAD from shard stream (limit+1 to detect hasMore)
- `readAllShards`: loops over all shards, drains each fully

### Bug: readDevice hasMore off-by-one with TTL-expired records
File: ShardedRecordStore.java line 127
```java
ShardCursor next = records.size() < limit ? null : ShardCursor.of(String.valueOf(lastSeq));
```
`records` is post-TTL-filter. `tuples` (raw ZSet result) has all limit entries. When some expired:
- tuples.size() == limit → more may exist in Redis
- but some HGETALL return empty → records.size() < limit
- → hasMore = false (WRONG), pagination stops early

Fix: check `tuples.size() < limit` not `records.size() < limit`.
(Same pattern readShard uses: fetch limit+1, check entries.size() > limit)

### Duplicate detection
ZADD NX on device ZSet: if eventId already exists for the device, ZADD returns 0 → WriteStatus.DUPLICATE.
Works correctly. Only deduplicates within a device (by design).

### ConsistentHashRing
FNV-1a 64-bit with 150 virtual nodes per shard. Thread-safe after construction. Correct.

### SequenceGenerator
Per-shard INCR. `ensureCounterValid` scans device ZSets at startup to repair a stale counter after a partial Redis flush. Correct.

---

## ShardedTopKStore

### Purpose
Read-replica sharding for hot trending-window keys (topk:last_hour etc.).
N physical shard keys per window. On cache miss, a random shard is read → N-fold QPS reduction.

### Write (seedAllShards)
Sequential per-shard zadd (one Redis call per shard). Partial write failure is caught and logged; other shards still written. Stale data risk: a read hitting the failed shard gets old data until the next seed. Acceptable tradeoff given the log + fallback to legacy key.

### Read (getTopKIds)
1. Check JVM hot cache (2s TTL)
2. Singleflight deduplication for concurrent cache misses in same JVM
3. Random shard selection from Redis
4. Fallback to unsharded legacy key if selected shard is empty (backwards compat)

Fail-open: singleflight failures cause independent fetch, not empty result. Correct.

### Cache invalidation race
seedAllShards calls hotCache.remove(window) but not inflight.remove(window).
If a seed occurs while an inflight fetch is in progress, the fetch completes with stale data
and re-populates hotCache. Stale data persists for up to 2s. Acceptable.

---

## Test coverage gaps found
1. readDevice with TTL-expired records affecting hasMore (BUG — needs test + fix)
2. readDevice cursor pagination through a set where some records have expired mid-page
