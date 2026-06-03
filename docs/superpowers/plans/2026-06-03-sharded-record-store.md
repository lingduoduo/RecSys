# Sharded Record Store Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a Redis-backed sharded record store with consistent-hash device→shard routing, shard-scoped sequence numbers, and dual write path (Sorted Set per device + Stream per shard) supporting per-device cursor reads and shard-level stream consumption.

**Architecture:** `ConsistentHashRing` maps `deviceId → shardIndex` via FNV-1a virtual-node ring. `SequenceGenerator` assigns monotonic seq numbers via Redis `INCR`. `ShardedRecordStore` writes each record to both `sr:dev:{N}:{deviceId}` (ZSet, `ZADD NX` for dedup) and `sr:stream:{N}` (Stream, for shard-level consumption), using a single Jedis pipeline after the INCR round-trip.

**Tech Stack:** Java 17 records, Jedis 5.1.3, JUnit 5 + AssertJ + Testcontainers (`redis:7-alpine`).

---

## File Map

**New production files** — all under `src/main/java/com/recsys/infrastructure/redis/sharding/`:

| File | Responsibility |
|---|---|
| `RecordType.java` | Enum: EVENT, FEATURE, LOG |
| `WriteStatus.java` | Enum: OK, DUPLICATE |
| `ShardedRecord.java` | Immutable record: deviceId, seqNum, type, eventId, payload, timestamp |
| `WriteResult.java` | Immutable record: seqNum, shardIndex, status |
| `ShardCursor.java` | Opaque cursor token for incremental reads |
| `Page.java` | Generic paged result: records + next cursor |
| `ConsistentHashRing.java` | FNV-1a virtual-node ring: deviceId → shardIndex |
| `SequenceGenerator.java` | Redis INCR wrapper + startup counter-reset guard |
| `ShardedRecordStore.java` | Write (pipeline) + readDevice + readShard + readAllShards |

**New test files** — all under `src/test/java/com/recsys/infrastructure/redis/sharding/`:

| File | Covers |
|---|---|
| `RedisShardingTestBase.java` | Testcontainers `redis:7-alpine` lifecycle, shared pool |
| `ConsistentHashRingTest.java` | Distribution, determinism, ring wrap |
| `SequenceGeneratorTest.java` | Monotonic increment, concurrent safety, counter-reset guard |
| `ShardedRecordStoreWriteTest.java` | write OK/DUPLICATE, update versioning |
| `ShardedRecordStoreReadTest.java` | readDevice cursor, readShard XREAD, readAllShards |
| `ShardedRecordStoreTtlTest.java` | Expired records skipped; ZSet member + stream entry survive |
| `ShardedRecordStoreIntegrationTest.java` | Full round-trips, concurrent writes, cross-device shard scan |

**Modified:**
- `pom.xml` — add Testcontainers BOM + `testcontainers-redis` dependency

---

## Task 1: Add Testcontainers to pom.xml

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add Testcontainers BOM and redis module**

In `pom.xml`, add inside `<dependencyManagement><dependencies>`:

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-bom</artifactId>
    <version>1.19.8</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

And add inside `<dependencies>`:

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Verify compile**

```bash
mvn compile -q
```

Expected: BUILD SUCCESS (no output)

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: add Testcontainers for Redis integration tests"
```

---

## Task 2: Data Types

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/redis/sharding/RecordType.java`
- Create: `src/main/java/com/recsys/infrastructure/redis/sharding/WriteStatus.java`
- Create: `src/main/java/com/recsys/infrastructure/redis/sharding/ShardedRecord.java`
- Create: `src/main/java/com/recsys/infrastructure/redis/sharding/WriteResult.java`
- Create: `src/main/java/com/recsys/infrastructure/redis/sharding/ShardCursor.java`
- Create: `src/main/java/com/recsys/infrastructure/redis/sharding/Page.java`

- [ ] **Step 1: Create RecordType.java**

```java
package com.recsys.infrastructure.redis.sharding;

public enum RecordType {
    /** Click, watch, rating, dwell, search — from LogCollector / Kafka. */
    EVENT,
    /**
     * Flink-written behavioral features: recent history updates, CTR events, session data.
     * Raw float[] embeddings are NOT stored here — use RedisEmbeddingStore for those.
     */
    FEATURE,
    /** General audit / debug log entries. */
    LOG
}
```

- [ ] **Step 2: Create WriteStatus.java**

```java
package com.recsys.infrastructure.redis.sharding;

public enum WriteStatus { OK, DUPLICATE }
```

- [ ] **Step 3: Create ShardedRecord.java**

```java
package com.recsys.infrastructure.redis.sharding;

import java.util.Objects;

public record ShardedRecord(
        String     deviceId,
        long       seqNum,
        RecordType type,
        String     eventId,
        String     payload,
        long       timestamp
) {
    public ShardedRecord {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(type,     "type");
        Objects.requireNonNull(eventId,  "eventId");
        if (deviceId.isBlank()) throw new IllegalArgumentException("deviceId must not be blank");
        if (eventId.isBlank())  throw new IllegalArgumentException("eventId must not be blank");
    }

    /** Convenience factory used by callers that set seqNum after assignment. */
    public ShardedRecord withSeqNum(long seq) {
        return new ShardedRecord(deviceId, seq, type, eventId, payload, timestamp);
    }
}
```

- [ ] **Step 4: Create WriteResult.java**

```java
package com.recsys.infrastructure.redis.sharding;

public record WriteResult(long seqNum, int shardIndex, WriteStatus status) {
    public boolean isDuplicate() { return status == WriteStatus.DUPLICATE; }
}
```

- [ ] **Step 5: Create ShardCursor.java**

```java
package com.recsys.infrastructure.redis.sharding;

import java.util.Objects;

public record ShardCursor(String value) {

    private static final ShardCursor START = new ShardCursor("0-0");

    public ShardCursor {
        Objects.requireNonNull(value, "value");
    }

    /** Initial cursor for both device ZSet reads and shard Stream reads. */
    public static ShardCursor start() { return START; }

    public static ShardCursor of(String value) { return new ShardCursor(value); }

    public boolean isStart() { return "0-0".equals(value); }
}
```

- [ ] **Step 6: Create Page.java**

```java
package com.recsys.infrastructure.redis.sharding;

import java.util.List;
import java.util.Objects;

public record Page<T>(List<T> records, ShardCursor next) {

    public Page {
        Objects.requireNonNull(records, "records");
        records = List.copyOf(records);
    }

    public boolean hasMore() { return next != null; }

    public static <T> Page<T> empty() { return new Page<>(List.of(), null); }
}
```

- [ ] **Step 7: Verify compile**

```bash
mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/redis/sharding/
git commit -m "feat: add ShardedRecord data types (RecordType, WriteStatus, ShardCursor, Page)"
```

---

## Task 3: ConsistentHashRing

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/redis/sharding/ConsistentHashRing.java`
- Create: `src/test/java/com/recsys/infrastructure/redis/sharding/ConsistentHashRingTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsistentHashRingTest {

    @Test
    void singleShard_allDevicesMapToShardZero() {
        var ring = new ConsistentHashRing(1, 150);
        assertThat(ring.shardFor("device-1")).isZero();
        assertThat(ring.shardFor("device-abc")).isZero();
        assertThat(ring.shardFor("user:999")).isZero();
    }

    @Test
    void multipleShards_distributionIsUniformWithin20Percent() {
        var ring = new ConsistentHashRing(4, 150);
        int deviceCount = 10_000;
        List<String> devices = IntStream.range(0, deviceCount)
                .mapToObj(i -> "device-" + i)
                .toList();

        Map<Integer, Integer> dist = ring.distribution(devices);

        int expected = deviceCount / 4;
        for (int count : dist.values()) {
            assertThat(count).isBetween((int)(expected * 0.8), (int)(expected * 1.2));
        }
    }

    @Test
    void shardFor_isDeterministicAcrossCallsAndInstances() {
        var ring1 = new ConsistentHashRing(4, 150);
        var ring2 = new ConsistentHashRing(4, 150);
        String deviceId = "user:12345";

        int shard1 = ring1.shardFor(deviceId);
        int shard2 = ring2.shardFor(deviceId);
        int shard3 = ring1.shardFor(deviceId);

        assertThat(shard1).isEqualTo(shard2).isEqualTo(shard3);
    }

    @Test
    void shardFor_returnsValueInRange() {
        var ring = new ConsistentHashRing(8, 150);
        for (int i = 0; i < 1000; i++) {
            int shard = ring.shardFor("device-" + i);
            assertThat(shard).isBetween(0, 7);
        }
    }

    @Test
    void invalidShardCount_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new ConsistentHashRing(0, 150))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shardCount_returnsConfiguredValue() {
        assertThat(new ConsistentHashRing(3, 150).shardCount()).isEqualTo(3);
    }
}
```

- [ ] **Step 2: Run tests — expect failure**

```bash
mvn test -Dtest=ConsistentHashRingTest -pl . 2>&1 | grep -E "FAIL|ERROR|Cannot find"
```

Expected: compilation error — `ConsistentHashRing` does not exist yet.

- [ ] **Step 3: Implement ConsistentHashRing.java**

```java
package com.recsys.infrastructure.redis.sharding;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable consistent-hash ring for mapping device/user IDs to shard indices.
 *
 * Uses FNV-1a (64-bit) for hashing and virtual nodes to achieve uniform distribution.
 * Each physical shard gets {@code virtualNodesPerShard} virtual nodes spread across
 * the hash space by hashing "{shardIndex}:v{i}" strings.
 *
 * Thread-safe after construction — all state is final.
 */
public final class ConsistentHashRing {

    static final int DEFAULT_VIRTUAL_NODES = 150;

    private final TreeMap<Long, Integer> ring = new TreeMap<>();
    private final int shardCount;

    public ConsistentHashRing(int shardCount, int virtualNodesPerShard) {
        if (shardCount < 1) throw new IllegalArgumentException("shardCount must be >= 1");
        if (virtualNodesPerShard < 1) throw new IllegalArgumentException("virtualNodesPerShard must be >= 1");
        this.shardCount = shardCount;

        for (int shard = 0; shard < shardCount; shard++) {
            for (int v = 0; v < virtualNodesPerShard; v++) {
                long hash = fnv1a(shard + ":v" + v);
                ring.put(hash, shard);
            }
        }
    }

    /**
     * Returns the shard index for the given device/user ID.
     * Lock-free — safe to call concurrently after construction.
     */
    public int shardFor(String deviceId) {
        long hash = fnv1a(deviceId);
        Map.Entry<Long, Integer> entry = ring.ceilingEntry(hash);
        return (entry != null ? entry : ring.firstEntry()).getValue();
    }

    public int shardCount() { return shardCount; }

    /**
     * Returns the number of device IDs that map to each shard.
     * Useful for diagnosing hot-shard imbalance. O(deviceIds.size()).
     */
    public Map<Integer, Integer> distribution(Collection<String> deviceIds) {
        Map<Integer, Integer> dist = new HashMap<>();
        for (int i = 0; i < shardCount; i++) dist.put(i, 0);
        for (String id : deviceIds) {
            dist.merge(shardFor(id), 1, Integer::sum);
        }
        return dist;
    }

    // FNV-1a 64-bit — fast, uniform, no external dependency.
    static long fnv1a(String s) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xFFL);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
```

- [ ] **Step 4: Run tests — expect pass**

```bash
mvn test -Dtest=ConsistentHashRingTest -pl . 2>&1 | grep -E "Tests run|BUILD"
```

Expected:
```
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/redis/sharding/ConsistentHashRing.java \
        src/test/java/com/recsys/infrastructure/redis/sharding/ConsistentHashRingTest.java
git commit -m "feat: add ConsistentHashRing with FNV-1a virtual-node ring"
```

---

## Task 4: Redis Test Base + SequenceGenerator

**Files:**
- Create: `src/test/java/com/recsys/infrastructure/redis/sharding/RedisShardingTestBase.java`
- Create: `src/main/java/com/recsys/infrastructure/redis/sharding/SequenceGenerator.java`
- Create: `src/test/java/com/recsys/infrastructure/redis/sharding/SequenceGeneratorTest.java`

- [ ] **Step 1: Create shared Redis test base**

```java
package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.util.Pool;

@Testcontainers
public abstract class RedisShardingTestBase {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    protected static Pool<Jedis> pool;

    @BeforeAll
    static void startRedis() {
        pool = new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379));
    }

    @AfterEach
    void flushRedis() {
        try (Jedis jedis = pool.getResource()) {
            jedis.flushAll();
        }
    }
}
```

- [ ] **Step 2: Write failing SequenceGenerator tests**

```java
package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class SequenceGeneratorTest extends RedisShardingTestBase {

    @Test
    void next_returnsMonotonicallyIncreasingValues() {
        var gen = new SequenceGenerator(pool, "sr:");
        long s1 = gen.next(0);
        long s2 = gen.next(0);
        long s3 = gen.next(0);
        assertThat(s1).isLessThan(s2).isLessThan(s3);
        assertThat(s1).isEqualTo(1L);
    }

    @Test
    void next_differentShardsHaveIndependentCounters() {
        var gen = new SequenceGenerator(pool, "sr:");
        assertThat(gen.next(0)).isEqualTo(1L);
        assertThat(gen.next(1)).isEqualTo(1L);
        assertThat(gen.next(0)).isEqualTo(2L);
    }

    @Test
    void next_concurrentWritersProduceNoDuplicates() throws InterruptedException {
        var gen = new SequenceGenerator(pool, "sr:");
        int threads = 10, callsPerThread = 100;
        Set<Long> seqNums = new ConcurrentSkipListSet<>();
        CountDownLatch latch = new CountDownLatch(threads);

        ExecutorService ex = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            ex.submit(() -> {
                for (int i = 0; i < callsPerThread; i++) seqNums.add(gen.next(0));
                latch.countDown();
            });
        }
        latch.await();
        ex.shutdown();

        assertThat(seqNums).hasSize(threads * callsPerThread);
    }

    @Test
    void ensureCounterValid_resetsCounterWhenBehindMaxZSetScore() {
        var gen = new SequenceGenerator(pool, "sr:");
        // Manually write a ZSet member with score=100 simulating records after a flush.
        try (Jedis jedis = pool.getResource()) {
            jedis.zadd("sr:dev:0:device-1", 100.0, "event-1");
        }
        // Counter is at 0 (never incremented after flush) — should be reset.
        gen.ensureCounterValid(0, 1); // shardIndex=0, shardCount=1

        long next = gen.next(0);
        assertThat(next).isGreaterThan(100L);
    }
}
```

- [ ] **Step 3: Run — expect failure**

```bash
mvn test -Dtest=SequenceGeneratorTest -pl . 2>&1 | grep -E "FAIL|ERROR|Cannot find" | head -5
```

Expected: compilation error — `SequenceGenerator` not found.

- [ ] **Step 4: Implement SequenceGenerator.java**

```java
package com.recsys.infrastructure.redis.sharding;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import redis.clients.jedis.util.Pool;

import java.util.List;

/**
 * Assigns shard-scoped monotonic sequence numbers via Redis INCR.
 *
 * Each shard has its own counter at {prefix}seq:{shardIndex}.
 * Sequence numbers are shard-scoped (not globally unique across shards).
 */
public final class SequenceGenerator {

    private final Pool<Jedis> pool;
    private final String prefix;

    public SequenceGenerator(Pool<Jedis> pool, String prefix) {
        this.pool   = pool;
        this.prefix = prefix;
    }

    /** Returns the next sequence number for the given shard. Always >= 1. */
    public long next(int shardIndex) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.incr(seqKey(shardIndex));
        }
    }

    /**
     * Guards against a stale counter after a Redis partial flush.
     * Scans all device ZSets for the shard and resets the counter to max(score)+1
     * if the current counter is lower.
     *
     * Call once at startup per shard before accepting writes.
     */
    public void ensureCounterValid(int shardIndex, int shardCount) {
        long maxSeq = findMaxSeqInShard(shardIndex);
        if (maxSeq <= 0) return;

        try (Jedis jedis = pool.getResource()) {
            String key = seqKey(shardIndex);
            String current = jedis.get(key);
            long currentVal = current == null ? 0L : Long.parseLong(current);
            if (currentVal < maxSeq) {
                jedis.set(key, String.valueOf(maxSeq + 1));
            }
        }
    }

    private long findMaxSeqInShard(int shardIndex) {
        String pattern = prefix + "dev:" + shardIndex + ":*";
        ScanParams params = new ScanParams().match(pattern).count(200);
        long maxSeq = 0L;

        try (Jedis jedis = pool.getResource()) {
            String cursor = "0";
            do {
                ScanResult<String> result = jedis.scan(cursor, params);
                for (String devKey : result.getResult()) {
                    List<String> top = jedis.zrevrangeByScore(devKey, "+inf", "-inf",
                            0, 1);
                    if (!top.isEmpty()) {
                        Double score = jedis.zscore(devKey, top.get(0));
                        if (score != null) maxSeq = Math.max(maxSeq, score.longValue());
                    }
                }
                cursor = result.getCursor();
            } while (!"0".equals(cursor));
        }
        return maxSeq;
    }

    private String seqKey(int shardIndex) {
        return prefix + "seq:" + shardIndex;
    }
}
```

- [ ] **Step 5: Run tests — expect pass**

```bash
mvn test -Dtest=SequenceGeneratorTest -pl . 2>&1 | grep -E "Tests run|BUILD"
```

Expected:
```
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/redis/sharding/SequenceGenerator.java \
        src/test/java/com/recsys/infrastructure/redis/sharding/RedisShardingTestBase.java \
        src/test/java/com/recsys/infrastructure/redis/sharding/SequenceGeneratorTest.java
git commit -m "feat: add SequenceGenerator with Redis INCR and counter-reset guard"
```

---

## Task 5: ShardedRecordStore — Write Path

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStore.java`
- Create: `src/test/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStoreWriteTest.java`

- [ ] **Step 1: Write failing write tests**

```java
package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ShardedRecordStoreWriteTest extends RedisShardingTestBase {

    private ShardedRecordStore store;
    private ConsistentHashRing ring;

    @BeforeEach
    void setUp() {
        ring  = new ConsistentHashRing(2, 150);
        store = new ShardedRecordStore(pool, ring, new SequenceGenerator(pool, "sr:"), "sr:");
    }

    @Test
    void write_returnsOkWithCorrectShardIndex() {
        ShardedRecord record = new ShardedRecord("device-1", 0, RecordType.EVENT,
                "evt-001", "{\"action\":\"click\"}", System.currentTimeMillis());

        WriteResult result = store.write(record);

        assertThat(result.status()).isEqualTo(WriteStatus.OK);
        assertThat(result.seqNum()).isGreaterThan(0);
        assertThat(result.shardIndex()).isEqualTo(ring.shardFor("device-1"));
    }

    @Test
    void write_storesFullRecordInHashKey() {
        ShardedRecord record = new ShardedRecord("device-2", 0, RecordType.FEATURE,
                "feat-001", "{\"ctr\":0.12}", System.currentTimeMillis());

        WriteResult result = store.write(record);

        try (Jedis jedis = pool.getResource()) {
            Map<String, String> hash = jedis.hgetAll(
                    "sr:rec:" + result.shardIndex() + ":" + result.seqNum());
            assertThat(hash)
                    .containsEntry("deviceId", "device-2")
                    .containsEntry("type", "FEATURE")
                    .containsEntry("eventId", "feat-001")
                    .containsEntry("payload", "{\"ctr\":0.12}");
        }
    }

    @Test
    void write_addsMemberToDeviceSortedSet() {
        ShardedRecord record = new ShardedRecord("device-3", 0, RecordType.EVENT,
                "evt-002", "{}", System.currentTimeMillis());

        WriteResult result = store.write(record);

        try (Jedis jedis = pool.getResource()) {
            Double score = jedis.zscore(
                    "sr:dev:" + result.shardIndex() + ":device-3", "evt-002");
            assertThat(score).isEqualTo((double) result.seqNum());
        }
    }

    @Test
    void write_appendsEntryToShardStream() {
        ShardedRecord record = new ShardedRecord("device-4", 0, RecordType.LOG,
                "log-001", "startup", System.currentTimeMillis());

        WriteResult result = store.write(record);

        try (Jedis jedis = pool.getResource()) {
            long streamLen = jedis.xlen("sr:stream:" + result.shardIndex());
            assertThat(streamLen).isGreaterThan(0);
        }
    }

    @Test
    void write_duplicateEventIdReturnsDuplicate() {
        ShardedRecord r1 = new ShardedRecord("device-5", 0, RecordType.EVENT,
                "evt-dup", "{}", System.currentTimeMillis());
        ShardedRecord r2 = new ShardedRecord("device-5", 0, RecordType.EVENT,
                "evt-dup", "{\"retry\":true}", System.currentTimeMillis());

        WriteResult first  = store.write(r1);
        WriteResult second = store.write(r2);

        assertThat(first.status()).isEqualTo(WriteStatus.OK);
        assertThat(second.status()).isEqualTo(WriteStatus.DUPLICATE);
    }

    @Test
    void update_staleSeqDoesNotOverwriteNewerRecord() {
        // First write — seq assigned by store, score set high.
        ShardedRecord r1 = new ShardedRecord("device-6", 0, RecordType.FEATURE,
                "feat-ver", "{\"v\":1}", System.currentTimeMillis());
        WriteResult first = store.write(r1);

        // Manually force a high score for same eventId.
        try (Jedis jedis = pool.getResource()) {
            jedis.zadd("sr:dev:" + first.shardIndex() + ":device-6",
                    9999.0, "feat-ver");
        }

        // update() with a new record — ZADD XX GT should lose to score 9999.
        ShardedRecord r2 = new ShardedRecord("device-6", 0, RecordType.FEATURE,
                "feat-ver", "{\"v\":2}", System.currentTimeMillis());
        store.update(r2);

        try (Jedis jedis = pool.getResource()) {
            Double score = jedis.zscore(
                    "sr:dev:" + first.shardIndex() + ":device-6", "feat-ver");
            assertThat(score).isEqualTo(9999.0); // stale writer did not win
        }
    }
}
```

- [ ] **Step 2: Run — expect compilation failure**

```bash
mvn test -Dtest=ShardedRecordStoreWriteTest -pl . 2>&1 | grep -E "FAIL|ERROR|Cannot find" | head -5
```

Expected: compilation error — `ShardedRecordStore` not found.

- [ ] **Step 3: Implement ShardedRecordStore.java (write path only)**

```java
package com.recsys.infrastructure.redis.sharding;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.params.ZAddParams;
import redis.clients.jedis.util.Pool;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Redis-backed sharded record store.
 *
 * Write path:
 *   1. INCR seq counter (separate round-trip — return value needed).
 *   2. Pipeline: HSET full record + ZADD NX/GT device index + XADD shard stream.
 *
 * Read paths:
 *   - readDevice: ZRANGEBYSCORE on device ZSet + pipelined HGETALL per seq.
 *   - readShard:  XREAD on shard Stream + pipelined HGETALL per entry.
 */
public final class ShardedRecordStore {

    private static final long STREAM_MAXLEN = 1_000_000L;

    private final Pool<Jedis> pool;
    private final ConsistentHashRing ring;
    private final SequenceGenerator seqGen;
    private final String prefix;

    public ShardedRecordStore(Pool<Jedis> pool, ConsistentHashRing ring,
                               SequenceGenerator seqGen, String prefix) {
        this.pool   = Objects.requireNonNull(pool,   "pool");
        this.ring   = Objects.requireNonNull(ring,   "ring");
        this.seqGen = Objects.requireNonNull(seqGen, "seqGen");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
    }

    // ── Write ────────────────────────────────────────────────────────────────────

    /** Writes a record. Deduplicates by eventId via ZADD NX. */
    public WriteResult write(ShardedRecord record) {
        return doWrite(record, false, 0);
    }

    /** Writes a record with TTL on the hash key. */
    public WriteResult write(ShardedRecord record, int ttlSeconds) {
        return doWrite(record, false, ttlSeconds);
    }

    /** Updates a record. Uses ZADD XX GT — stale writes lose to newer seq numbers. */
    public WriteResult update(ShardedRecord record) {
        return doWrite(record, true, 0);
    }

    private WriteResult doWrite(ShardedRecord record, boolean isUpdate, int ttlSeconds) {
        int shardIndex = ring.shardFor(record.deviceId());
        long seqNum    = seqGen.next(shardIndex);

        String recKey    = recKey(shardIndex, seqNum);
        String devKey    = devKey(shardIndex, record.deviceId());
        String streamKey = streamKey(shardIndex);

        long zaddResult;
        try (Jedis jedis = pool.getResource(); Pipeline pipe = jedis.pipelined()) {
            pipe.hset(recKey, Map.of(
                    "deviceId",  record.deviceId(),
                    "type",      record.type().name(),
                    "eventId",   record.eventId(),
                    "payload",   record.payload() != null ? record.payload() : "",
                    "timestamp", String.valueOf(record.timestamp())
            ));
            if (ttlSeconds > 0) pipe.expire(recKey, ttlSeconds);

            var zaddFuture = isUpdate
                    ? pipe.zadd(devKey, seqNum, record.eventId(),
                            ZAddParams.zAddParams().xx().gt())
                    : pipe.zadd(devKey, seqNum, record.eventId(),
                            ZAddParams.zAddParams().nx());

            pipe.xadd(streamKey,
                    redis.clients.jedis.StreamEntryID.NEW_ENTRY,
                    Map.of(
                            "deviceId", record.deviceId(),
                            "seq",      String.valueOf(seqNum),
                            "type",     record.type().name(),
                            "eventId",  record.eventId()
                    ),
                    STREAM_MAXLEN, true);
            pipe.sync();

            zaddResult = (Long) zaddFuture.get();
        }

        WriteStatus status = (!isUpdate && zaddResult == 0L)
                ? WriteStatus.DUPLICATE : WriteStatus.OK;
        return new WriteResult(seqNum, shardIndex, status);
    }

    // ── Read — implemented in Task 6 ────────────────────────────────────────────

    public Page<ShardedRecord> readDevice(String deviceId, ShardCursor cursor, int limit) {
        throw new UnsupportedOperationException("implemented in Task 6");
    }

    public Page<ShardedRecord> readShard(int shardIndex, ShardCursor cursor, int limit) {
        throw new UnsupportedOperationException("implemented in Task 6");
    }

    public List<Page<ShardedRecord>> readAllShards(ShardCursor cursor, int limitPerShard) {
        throw new UnsupportedOperationException("implemented in Task 6");
    }

    // ── Key helpers ──────────────────────────────────────────────────────────────

    String recKey(int shardIndex, long seqNum) {
        return prefix + "rec:" + shardIndex + ":" + seqNum;
    }

    String devKey(int shardIndex, String deviceId) {
        return prefix + "dev:" + shardIndex + ":" + deviceId;
    }

    String streamKey(int shardIndex) {
        return prefix + "stream:" + shardIndex;
    }
}
```

- [ ] **Step 4: Run write tests — expect pass**

```bash
mvn test -Dtest=ShardedRecordStoreWriteTest -pl . 2>&1 | grep -E "Tests run|BUILD"
```

Expected:
```
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStore.java \
        src/test/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStoreWriteTest.java
git commit -m "feat: add ShardedRecordStore write path (INCR + pipeline HSET/ZADD/XADD)"
```

---

## Task 6: ShardedRecordStore — Read Paths

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStore.java`
- Create: `src/test/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStoreReadTest.java`

- [ ] **Step 1: Write failing read tests**

```java
package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShardedRecordStoreReadTest extends RedisShardingTestBase {

    private ShardedRecordStore store;

    @BeforeEach
    void setUp() {
        var ring = new ConsistentHashRing(2, 150);
        store = new ShardedRecordStore(pool, ring, new SequenceGenerator(pool, "sr:"), "sr:");
    }

    private ShardedRecord event(String deviceId, String eventId) {
        return new ShardedRecord(deviceId, 0, RecordType.EVENT, eventId, "{}", System.currentTimeMillis());
    }

    @Test
    void readDevice_emptyDevice_returnsEmptyPage() {
        Page<ShardedRecord> page = store.readDevice("ghost-device", ShardCursor.start(), 10);
        assertThat(page.records()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void readDevice_returnsRecordsInSeqOrder() {
        store.write(event("dev-A", "e1"));
        store.write(event("dev-A", "e2"));
        store.write(event("dev-A", "e3"));

        Page<ShardedRecord> page = store.readDevice("dev-A", ShardCursor.start(), 10);

        assertThat(page.records()).hasSize(3);
        List<Long> seqs = page.records().stream().map(ShardedRecord::seqNum).toList();
        assertThat(seqs).isSorted();
    }

    @Test
    void readDevice_respectsLimitAndCursorAdvances() {
        for (int i = 1; i <= 5; i++) store.write(event("dev-B", "e" + i));

        Page<ShardedRecord> page1 = store.readDevice("dev-B", ShardCursor.start(), 2);
        assertThat(page1.records()).hasSize(2);
        assertThat(page1.hasMore()).isTrue();

        Page<ShardedRecord> page2 = store.readDevice("dev-B", page1.next(), 2);
        assertThat(page2.records()).hasSize(2);

        Page<ShardedRecord> page3 = store.readDevice("dev-B", page2.next(), 2);
        assertThat(page3.records()).hasSize(1);
        assertThat(page3.hasMore()).isFalse();
    }

    @Test
    void readShard_returnsAllRecordsAcrossDevices() {
        // Both devices must land on the same shard for this test to work.
        // We use a fixed ring and find two devices on shard 0.
        var ring = new ConsistentHashRing(1, 150); // 1 shard → everything on shard 0
        var s = new ShardedRecordStore(pool, ring, new SequenceGenerator(pool, "sr:"), "sr:");

        s.write(event("dev-X", "ex1"));
        s.write(event("dev-Y", "ey1"));
        s.write(event("dev-X", "ex2"));

        Page<ShardedRecord> page = s.readShard(0, ShardCursor.start(), 10);
        assertThat(page.records()).hasSize(3);
    }

    @Test
    void readShard_cursorAdvancesIncrementally() {
        var ring = new ConsistentHashRing(1, 150);
        var s = new ShardedRecordStore(pool, ring, new SequenceGenerator(pool, "sr:"), "sr:");

        for (int i = 1; i <= 4; i++) s.write(event("dev-C", "ec" + i));

        Page<ShardedRecord> p1 = s.readShard(0, ShardCursor.start(), 2);
        assertThat(p1.records()).hasSize(2);
        assertThat(p1.hasMore()).isTrue();

        Page<ShardedRecord> p2 = s.readShard(0, p1.next(), 2);
        assertThat(p2.records()).hasSize(2);
        assertThat(p2.hasMore()).isFalse();
    }

    @Test
    void readAllShards_advancesEachShardIndependently() {
        var ring = new ConsistentHashRing(2, 150);
        var s = new ShardedRecordStore(pool, ring, new SequenceGenerator(pool, "sr:"), "sr:");

        // Write enough records to populate both shards.
        for (int i = 0; i < 20; i++) s.write(event("device-" + i, "e" + i));

        List<Page<ShardedRecord>> pages = s.readAllShards(ShardCursor.start(), 5);
        assertThat(pages).hasSize(2);

        int total = pages.stream().mapToInt(p -> p.records().size()).sum();
        assertThat(total).isEqualTo(20);
    }
}
```

- [ ] **Step 2: Run — expect UnsupportedOperationException**

```bash
mvn test -Dtest=ShardedRecordStoreReadTest -pl . 2>&1 | grep -E "UnsupportedOperation|Tests run" | head -5
```

Expected: tests fail with `UnsupportedOperationException`.

- [ ] **Step 3: Replace stub read methods in ShardedRecordStore.java**

Replace the three `throw new UnsupportedOperationException` stubs with these implementations:

```java
// ── Read ────────────────────────────────────────────────────────────────────

public Page<ShardedRecord> readDevice(String deviceId, ShardCursor cursor, int limit) {
    int shardIndex = ring.shardFor(deviceId);
    String devKey  = devKey(shardIndex, deviceId);
    double minScore = cursor.isStart() ? Double.NEGATIVE_INFINITY
                                       : Double.parseDouble(cursor.value()) + 1;

    List<Map.Entry<String, Double>> entries;
    try (Jedis jedis = pool.getResource()) {
        entries = jedis.zrangeByScoreWithScores(devKey,
                minScore, Double.POSITIVE_INFINITY, 0, limit);
    }
    if (entries.isEmpty()) return Page.empty();

    List<ShardedRecord> records = fetchRecords(shardIndex, entries.stream()
            .map(e -> (long) e.getValue().doubleValue()).toList());

    long lastSeq = (long) entries.getLast().getValue().doubleValue();
    ShardCursor next = records.size() < limit ? null : ShardCursor.of(String.valueOf(lastSeq));
    return new Page<>(records, next);
}

public Page<ShardedRecord> readShard(int shardIndex, ShardCursor cursor, int limit) {
    String streamKey = streamKey(shardIndex);

    List<redis.clients.jedis.resps.StreamEntry> entries;
    try (Jedis jedis = pool.getResource()) {
        var result = jedis.xread(
                redis.clients.jedis.params.XReadParams.xReadParams().count(limit),
                Map.of(streamKey, new redis.clients.jedis.StreamEntryID(cursor.value())));
        entries = result == null || result.isEmpty() ? List.of()
                : result.get(0).getValue();
    }
    if (entries.isEmpty()) return Page.empty();

    List<Long> seqNums = entries.stream()
            .map(e -> Long.parseLong(e.getFields().get("seq")))
            .toList();
    List<ShardedRecord> records = fetchRecords(shardIndex, seqNums);

    ShardCursor next = records.size() < limit ? null
            : ShardCursor.of(entries.getLast().getID().toString());
    return new Page<>(records, next);
}

public List<Page<ShardedRecord>> readAllShards(ShardCursor cursor, int limitPerShard) {
    List<Page<ShardedRecord>> pages = new ArrayList<>();
    for (int i = 0; i < ring.shardCount(); i++) {
        pages.add(readShard(i, cursor, limitPerShard));
    }
    return pages;
}

// Pipeline multi-HGETALL for a list of seq numbers — one Redis round-trip.
private List<ShardedRecord> fetchRecords(int shardIndex, List<Long> seqNums) {
    List<redis.clients.jedis.Response<Map<String, String>>> responses = new ArrayList<>();
    try (Jedis jedis = pool.getResource(); Pipeline pipe = jedis.pipelined()) {
        for (long seq : seqNums) responses.add(pipe.hgetAll(recKey(shardIndex, seq)));
        pipe.sync();
    }

    List<ShardedRecord> records = new ArrayList<>();
    for (int i = 0; i < seqNums.size(); i++) {
        Map<String, String> fields = responses.get(i).get();
        if (fields == null || fields.isEmpty()) continue; // TTL expired — skip
        records.add(new ShardedRecord(
                fields.get("deviceId"),
                seqNums.get(i),
                RecordType.valueOf(fields.get("type")),
                fields.get("eventId"),
                fields.get("payload"),
                Long.parseLong(fields.get("timestamp"))
        ));
    }
    return records;
}
```

Also add these imports at the top of `ShardedRecordStore.java`:

```java
import redis.clients.jedis.Response;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XReadParams;
import redis.clients.jedis.resps.StreamEntry;
import java.util.ArrayList;
```

- [ ] **Step 4: Run read tests — expect pass**

```bash
mvn test -Dtest=ShardedRecordStoreReadTest -pl . 2>&1 | grep -E "Tests run|BUILD"
```

Expected:
```
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStore.java \
        src/test/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStoreReadTest.java
git commit -m "feat: add ShardedRecordStore read paths (readDevice, readShard, readAllShards)"
```

---

## Task 7: TTL Support

**Files:**
- Create: `src/test/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStoreTtlTest.java`

The write path already supports TTL via `write(record, ttlSeconds)` — this task only adds tests.

- [ ] **Step 1: Write TTL tests**

```java
package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import static org.assertj.core.api.Assertions.assertThat;

class ShardedRecordStoreTtlTest extends RedisShardingTestBase {

    private ShardedRecordStore store;

    @BeforeEach
    void setUp() {
        var ring = new ConsistentHashRing(1, 150);
        store = new ShardedRecordStore(pool, ring, new SequenceGenerator(pool, "sr:"), "sr:");
    }

    @Test
    void write_withTtl_setsExpireOnHashKey() {
        ShardedRecord record = new ShardedRecord("dev-ttl", 0, RecordType.LOG,
                "log-ttl", "data", System.currentTimeMillis());

        WriteResult result = store.write(record, 3600);

        try (Jedis jedis = pool.getResource()) {
            long ttl = jedis.ttl("sr:rec:0:" + result.seqNum());
            assertThat(ttl).isBetween(3598L, 3600L);
        }
    }

    @Test
    void write_withTtl_zsetMemberSurvivesExpiry() throws InterruptedException {
        ShardedRecord record = new ShardedRecord("dev-ttl2", 0, RecordType.EVENT,
                "evt-short", "data", System.currentTimeMillis());

        WriteResult result = store.write(record, 1); // 1 second TTL

        Thread.sleep(1100); // wait for hash to expire

        try (Jedis jedis = pool.getResource()) {
            // Hash key expired
            assertThat(jedis.exists("sr:rec:0:" + result.seqNum())).isFalse();
            // ZSet member still present (lightweight — no TTL set on ZSet)
            Double score = jedis.zscore("sr:dev:0:dev-ttl2", "evt-short");
            assertThat(score).isNotNull();
        }
    }

    @Test
    void readDevice_skipsExpiredRecords() throws InterruptedException {
        ShardedRecord r1 = new ShardedRecord("dev-ttl3", 0, RecordType.EVENT,
                "short", "v1", System.currentTimeMillis());
        ShardedRecord r2 = new ShardedRecord("dev-ttl3", 0, RecordType.EVENT,
                "long", "v2", System.currentTimeMillis());

        store.write(r1, 1);  // expires in 1s
        store.write(r2, 0);  // no expiry

        Thread.sleep(1100);

        Page<ShardedRecord> page = store.readDevice("dev-ttl3", ShardCursor.start(), 10);
        assertThat(page.records()).hasSize(1);
        assertThat(page.records().get(0).eventId()).isEqualTo("long");
    }
}
```

- [ ] **Step 2: Run TTL tests — expect pass**

```bash
mvn test -Dtest=ShardedRecordStoreTtlTest -pl . 2>&1 | grep -E "Tests run|BUILD"
```

Expected:
```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStoreTtlTest.java
git commit -m "test: add TTL tests for ShardedRecordStore hash expiry and read skip"
```

---

## Task 8: Integration Test

**Files:**
- Create: `src/test/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStoreIntegrationTest.java`

- [ ] **Step 1: Write integration tests**

```java
package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class ShardedRecordStoreIntegrationTest extends RedisShardingTestBase {

    private ShardedRecordStore store;
    private ConsistentHashRing ring;

    @BeforeEach
    void setUp() {
        ring  = new ConsistentHashRing(1, 150); // single shard for easy assertions
        store = new ShardedRecordStore(pool, ring, new SequenceGenerator(pool, "sr:"), "sr:");
    }

    @Test
    void fullRoundTrip_eventRecord() {
        ShardedRecord input = new ShardedRecord("user:42", 0, RecordType.EVENT,
                "click-001", "{\"movieId\":7}", System.currentTimeMillis());

        WriteResult wr = store.write(input);
        Page<ShardedRecord> page = store.readDevice("user:42", ShardCursor.start(), 10);

        assertThat(page.records()).hasSize(1);
        ShardedRecord out = page.records().get(0);
        assertThat(out.deviceId()).isEqualTo("user:42");
        assertThat(out.type()).isEqualTo(RecordType.EVENT);
        assertThat(out.eventId()).isEqualTo("click-001");
        assertThat(out.payload()).isEqualTo("{\"movieId\":7}");
        assertThat(out.seqNum()).isEqualTo(wr.seqNum());
    }

    @Test
    void fullRoundTrip_featureAndLogTypes() {
        store.write(new ShardedRecord("user:1", 0, RecordType.FEATURE,
                "ctr-001", "{\"ctr\":0.2}", System.currentTimeMillis()));
        store.write(new ShardedRecord("user:1", 0, RecordType.LOG,
                "log-001", "startup", System.currentTimeMillis()));

        Page<ShardedRecord> page = store.readDevice("user:1", ShardCursor.start(), 10);
        List<RecordType> types = page.records().stream().map(ShardedRecord::type).toList();
        assertThat(types).containsExactlyInAnyOrder(RecordType.FEATURE, RecordType.LOG);
    }

    @Test
    void concurrentWrites_noDuplicateSeqNums() throws InterruptedException {
        int threads = 10, writesPerThread = 100;
        Set<Long> seqNums = new ConcurrentSkipListSet<>();
        CountDownLatch latch = new CountDownLatch(threads);
        ExecutorService ex = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            int tid = t;
            ex.submit(() -> {
                for (int i = 0; i < writesPerThread; i++) {
                    WriteResult wr = store.write(new ShardedRecord(
                            "dev-" + tid, 0, RecordType.EVENT,
                            "e-" + tid + "-" + i, "{}", System.currentTimeMillis()));
                    seqNums.add(wr.seqNum());
                }
                latch.countDown();
            });
        }
        latch.await();
        ex.shutdown();

        assertThat(seqNums).hasSize(threads * writesPerThread);
    }

    @Test
    void shardLevelScan_returnsAllRecordsWrittenByPerDeviceWrites() {
        store.write(new ShardedRecord("dev-P", 0, RecordType.EVENT, "p1", "{}", System.currentTimeMillis()));
        store.write(new ShardedRecord("dev-Q", 0, RecordType.EVENT, "q1", "{}", System.currentTimeMillis()));
        store.write(new ShardedRecord("dev-P", 0, RecordType.EVENT, "p2", "{}", System.currentTimeMillis()));

        List<ShardedRecord> allFromShard = new ArrayList<>();
        ShardCursor cursor = ShardCursor.start();
        while (true) {
            Page<ShardedRecord> page = store.readShard(0, cursor, 10);
            allFromShard.addAll(page.records());
            if (!page.hasMore()) break;
            cursor = page.next();
        }

        assertThat(allFromShard).hasSize(3);
        List<String> eventIds = allFromShard.stream().map(ShardedRecord::eventId).toList();
        assertThat(eventIds).containsExactlyInAnyOrder("p1", "q1", "p2");
    }

    @Test
    void readDevice_onlySeesOwnRecordsNotOtherDevices() {
        store.write(new ShardedRecord("user:A", 0, RecordType.EVENT, "a1", "{}", System.currentTimeMillis()));
        store.write(new ShardedRecord("user:B", 0, RecordType.EVENT, "b1", "{}", System.currentTimeMillis()));
        store.write(new ShardedRecord("user:A", 0, RecordType.EVENT, "a2", "{}", System.currentTimeMillis()));

        Page<ShardedRecord> page = store.readDevice("user:A", ShardCursor.start(), 10);

        assertThat(page.records()).hasSize(2);
        assertThat(page.records()).allMatch(r -> r.deviceId().equals("user:A"));
    }
}
```

- [ ] **Step 2: Run integration tests — expect pass**

```bash
mvn test -Dtest=ShardedRecordStoreIntegrationTest -pl . 2>&1 | grep -E "Tests run|BUILD"
```

Expected:
```
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

- [ ] **Step 3: Run full test suite to check for regressions**

```bash
mvn test -pl . 2>&1 | grep -E "Tests run|BUILD|FAIL" | tail -5
```

Expected: BUILD SUCCESS, no failures.

- [ ] **Step 4: Final commit**

```bash
git add src/test/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStoreIntegrationTest.java
git commit -m "test: add ShardedRecordStore integration tests (round-trips, concurrency, shard scan)"
```

---

## Self-Review Checklist

- [x] **Spec coverage**
  - ConsistentHashRing: Task 3 ✓
  - SequenceGenerator + counter-reset guard: Task 4 ✓
  - Write (dedup ZADD NX): Task 5 ✓
  - Update (versioned ZADD XX GT): Task 5 ✓
  - readDevice cursor: Task 6 ✓
  - readShard XREAD cursor: Task 6 ✓
  - readAllShards: Task 6 ✓
  - TTL + expired record skipping: Task 7 ✓
  - Integration (round-trips, concurrency, shard scan): Task 8 ✓
- [x] **No placeholders** — all steps contain complete code
- [x] **Type consistency** — `Page<T>`, `ShardCursor`, `WriteResult`, `WriteStatus` defined in Task 2, used consistently through Tasks 5–8
- [x] **Method names consistent** — `readDevice`, `readShard`, `readAllShards`, `write`, `update`, `shardFor` match spec and are used identically across plan and tests
