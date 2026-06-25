# Consistent Hashing Consolidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Consolidate consistent hashing helpers while preserving every current shard assignment and A/B bucketing slot.

**Architecture:** Add golden compatibility tests first, then replace the narrow `Fnv1a` helper with a small `Hashing` utility that owns FNV-1a and fmix64. Keep `ConsistentHashRing`'s virtual-node labels and TreeMap lookup unchanged, and update `StableBucketer` to compose the shared helpers.

**Tech Stack:** Java 17, Maven, JUnit 5, AssertJ.

## Global Constraints

- No device remapping for the same `(shardCount, virtualNodesPerShard, deviceId)` inputs.
- Preserve FNV-1a offset basis `0xcbf29ce484222325L` and prime `0x100000001b3L`.
- Preserve UTF-8 byte handling for string hashes.
- Preserve virtual-node label format `"v" + vnodeIndex + ":" + shardIndex`.
- Preserve `TreeMap.ceilingEntry(hash)` lookup with wraparound to `firstEntry()`.
- Preserve `ConsistentHashRing.DEFAULT_VIRTUAL_NODES`.
- Preserve `StableBucketer.KEYSPACE` and existing slot outputs.
- Do not change Redis topology versioning, generation keys, dual-read behavior, or record storage.
- Do not merge Bloom-filter hashing from `infrastructure/resilience/BloomFilterGuard`.

---

## File Structure

- `src/main/java/com/recsys/infrastructure/redis/sharding/Hashing.java`
  - New shared non-instantiable hashing helper.
  - Owns `fnv1a64(byte[])`, `fnv1a64(String)`, and `fmix64(long)`.
- `src/main/java/com/recsys/infrastructure/redis/sharding/Fnv1a.java`
  - Removed after tests prove compatibility and call sites migrate.
- `src/main/java/com/recsys/infrastructure/redis/sharding/ConsistentHashRing.java`
  - Uses `Hashing.fnv1a64(...)`.
  - Placement behavior remains unchanged.
- `src/main/java/com/recsys/application/experiment/StableBucketer.java`
  - Uses `Hashing.fnv1a64(...)` plus `Hashing.fmix64(...)`.
  - Slot behavior remains unchanged.
- `src/test/java/com/recsys/infrastructure/redis/sharding/HashingTest.java`
  - Golden FNV and fmix tests.
- `src/test/java/com/recsys/infrastructure/redis/sharding/ConsistentHashRingTest.java`
  - Add golden no-remap placement coverage.
- `src/test/java/com/recsys/application/experiment/StableBucketerTest.java`
  - Expand golden slot coverage.

---

### Task 1: Pin Current Hashing Behavior

**Files:**
- Create: `src/test/java/com/recsys/infrastructure/redis/sharding/HashingTest.java`
- Modify: `src/test/java/com/recsys/infrastructure/redis/sharding/ConsistentHashRingTest.java`
- Modify: `src/test/java/com/recsys/application/experiment/StableBucketerTest.java`

**Interfaces:**
- Consumes: existing `Fnv1a.hash(byte[])`, `Fnv1a.hash(String)`, `ConsistentHashRing.shardFor(String)`, `StableBucketer.slot(String, String)`.
- Produces: golden tests that fail until `Hashing` exists but pin exact existing values.

- [ ] **Step 1: Write failing `HashingTest`**

Create `src/test/java/com/recsys/infrastructure/redis/sharding/HashingTest.java`:

```java
package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HashingTest {

    @Test
    void fnv1a64_matchesGoldenValuesFromCurrentImplementation() {
        assertThat(Hashing.fnv1a64("v0:0")).isEqualTo(-6049490985673087943L);
        assertThat(Hashing.fnv1a64("v0:1")).isEqualTo(-6049492085184716154L);
        assertThat(Hashing.fnv1a64("device-123")).isEqualTo(8014270626680959582L);
        assertThat(Hashing.fnv1a64("user:12345")).isEqualTo(5900300057503531651L);
        assertThat(Hashing.fnv1a64("")).isEqualTo(-3750763034362895579L);
    }

    @Test
    void fnv1a64_stringAndByteOverloadsAgree() {
        assertThat(Hashing.fnv1a64("device-123"))
                .isEqualTo(Hashing.fnv1a64("device-123".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void fmix64_matchesBucketerAvalancheGoldenValues() {
        assertThat(Hashing.fmix64(Hashing.fnv1a64("123:default"))).isEqualTo(-5946069669134103219L);
        assertThat(Hashing.fmix64(Hashing.fnv1a64(":"))).isEqualTo(8049451291064401709L);
    }
}
```

- [ ] **Step 2: Run `HashingTest` and verify RED**

Run:

```bash
mvn test -Dtest=HashingTest
```

Expected: compile failure because `Hashing` does not exist.

- [ ] **Step 3: Add shard-placement golden tests**

Append this test to `src/test/java/com/recsys/infrastructure/redis/sharding/ConsistentHashRingTest.java`:

```java
    @Test
    void shardFor_matchesGoldenAssignmentsAcrossShardCounts() {
        assertThatAssignments(2, Map.of(
                "device-1", 0,
                "device-123", 0,
                "device-abc", 0,
                "user:999", 1,
                "user:12345", 1));

        assertThatAssignments(4, Map.of(
                "device-1", 0,
                "device-123", 0,
                "device-abc", 0,
                "user:999", 1,
                "user:12345", 1));

        assertThatAssignments(8, Map.of(
                "device-1", 4,
                "device-123", 4,
                "device-abc", 0,
                "user:999", 1,
                "user:12345", 5));
    }

    private static void assertThatAssignments(int shardCount, Map<String, Integer> expected) {
        var ring = new ConsistentHashRing(shardCount, 150);
        expected.forEach((deviceId, shard) -> assertThat(ring.shardFor(deviceId)).isEqualTo(shard));
    }
```

- [ ] **Step 4: Expand bucketer golden tests**

In `src/test/java/com/recsys/application/experiment/StableBucketerTest.java`, replace the single
`GOLDEN_123_DEFAULT` constant with:

```java
    private static final int GOLDEN_123_DEFAULT = 8397;
    private static final int GOLDEN_123_LAYER_A = 7123;
    private static final int GOLDEN_123_LAYER_B = 5243;
    private static final int GOLDEN_EMPTY = 1709;
    private static final int GOLDEN_DEVICE_RECSYS_AB = 8238;
```

Then replace `slotIsStableForKnownInput()` with:

```java
    @Test
    void slotIsStableForKnownInputs() {
        // Golden values: pin the algorithm so a future refactor that changes bucketing is caught.
        assertThat(StableBucketer.slot("123", "default")).isEqualTo(GOLDEN_123_DEFAULT);
        assertThat(StableBucketer.slot("123", "a")).isEqualTo(GOLDEN_123_LAYER_A);
        assertThat(StableBucketer.slot("123", "b")).isEqualTo(GOLDEN_123_LAYER_B);
        assertThat(StableBucketer.slot("", "")).isEqualTo(GOLDEN_EMPTY);
        assertThat(StableBucketer.slot("device-123", "recsys.ab")).isEqualTo(GOLDEN_DEVICE_RECSYS_AB);
    }
```

- [ ] **Step 5: Run targeted tests and verify only `Hashing` is missing**

Run:

```bash
mvn test -Dtest='HashingTest,ConsistentHashRingTest,StableBucketerTest'
```

Expected: compile failure because `Hashing` does not exist. The new ring and bucketer tests cannot
run until compilation succeeds; this is the RED state for the shared utility.

- [ ] **Step 6: Commit behavior-pinning tests after Task 2 turns them green**

Do not commit this task while tests are red. Commit it together with Task 2 after the implementation
passes:

```bash
git add src/test/java/com/recsys/infrastructure/redis/sharding/HashingTest.java \
        src/test/java/com/recsys/infrastructure/redis/sharding/ConsistentHashRingTest.java \
        src/test/java/com/recsys/application/experiment/StableBucketerTest.java
```

---

### Task 2: Consolidate Hashing Utility

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/redis/sharding/Hashing.java`
- Delete: `src/main/java/com/recsys/infrastructure/redis/sharding/Fnv1a.java`
- Modify: `src/main/java/com/recsys/infrastructure/redis/sharding/ConsistentHashRing.java`
- Modify: `src/main/java/com/recsys/application/experiment/StableBucketer.java`
- Modify if needed: docs or comments that mention `Fnv1a`

**Interfaces:**
- Consumes: red tests from Task 1.
- Produces:
  - `Hashing.fnv1a64(byte[] data) -> long`
  - `Hashing.fnv1a64(String value) -> long`
  - `Hashing.fmix64(long value) -> long`

- [ ] **Step 1: Create `Hashing.java`**

Create `src/main/java/com/recsys/infrastructure/redis/sharding/Hashing.java`:

```java
package com.recsys.infrastructure.redis.sharding;

import java.nio.charset.StandardCharsets;

/**
 * Shared deterministic hashing primitives for shard placement and stable bucketing.
 *
 * Do not change these constants, byte handling, or fmix64 operations without an explicit
 * remapping/bucketing migration plan.
 */
public final class Hashing {

    private static final long FNV_64_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_64_PRIME = 0x100000001b3L;

    private Hashing() {}

    public static long fnv1a64(byte[] data) {
        long h = FNV_64_OFFSET_BASIS;
        for (byte b : data) {
            h ^= (b & 0xffL);
            h *= FNV_64_PRIME;
        }
        return h;
    }

    public static long fnv1a64(String value) {
        return fnv1a64(value.getBytes(StandardCharsets.UTF_8));
    }

    public static long fmix64(long value) {
        long h = value;
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return h;
    }
}
```

- [ ] **Step 2: Update `ConsistentHashRing`**

In `src/main/java/com/recsys/infrastructure/redis/sharding/ConsistentHashRing.java`, replace:

```java
long hash = Fnv1a.hash("v" + v + ":" + shard);
```

with:

```java
long hash = Hashing.fnv1a64("v" + v + ":" + shard);
```

Replace:

```java
long hash = Fnv1a.hash(deviceId);
```

with:

```java
long hash = Hashing.fnv1a64(deviceId);
```

Update the class comment line:

```java
 * Uses FNV-1a (64-bit) for hashing and virtual nodes for uniform distribution.
```

to:

```java
 * Uses {@link Hashing#fnv1a64(String)} and virtual nodes for uniform distribution.
```

- [ ] **Step 3: Update `StableBucketer`**

In `src/main/java/com/recsys/application/experiment/StableBucketer.java`, replace:

```java
import com.recsys.infrastructure.redis.sharding.Fnv1a;
```

with:

```java
import com.recsys.infrastructure.redis.sharding.Hashing;
```

Replace the private `hash64(byte[] data)` method with:

```java
    private static long hash64(byte[] data) {
        return Hashing.fmix64(Hashing.fnv1a64(data));
    }
```

- [ ] **Step 4: Delete `Fnv1a.java` and update references**

Delete `src/main/java/com/recsys/infrastructure/redis/sharding/Fnv1a.java`.

Run:

```bash
rg -n "Fnv1a|FNV-1a constants" src/main/java src/test/java docs/superpowers/specs docs/superpowers/plans
```

Expected: no production/test references to `Fnv1a`. Historical docs may still mention `Fnv1a`; update only current plan/spec references if they would confuse future implementation. Do not edit unrelated archived design docs unless necessary.

- [ ] **Step 5: Run targeted tests and verify GREEN**

Run:

```bash
mvn test -Dtest='HashingTest,ConsistentHashRingTest,StableBucketerTest'
```

Expected: all tests pass.

- [ ] **Step 6: Run sharding and bucketing compatibility suite**

Run:

```bash
mvn test -Dtest='Hashing*,ConsistentHashRing*,StableBucketer*,ShardTopology*,Sharded*'
```

Expected: all non-docker selected tests pass. If docker-tagged tests are selected and fail because
Docker/Redis is unavailable, rerun the non-docker subset and report the environment limitation.

- [ ] **Step 7: Run full verification**

Run:

```bash
mvn test
```

Expected: build success with 0 failures.

- [ ] **Step 8: Commit implementation**

Run:

```bash
git add src/main/java/com/recsys/infrastructure/redis/sharding/Hashing.java \
        src/main/java/com/recsys/infrastructure/redis/sharding/ConsistentHashRing.java \
        src/main/java/com/recsys/application/experiment/StableBucketer.java \
        src/test/java/com/recsys/infrastructure/redis/sharding/HashingTest.java \
        src/test/java/com/recsys/infrastructure/redis/sharding/ConsistentHashRingTest.java \
        src/test/java/com/recsys/application/experiment/StableBucketerTest.java
git rm src/main/java/com/recsys/infrastructure/redis/sharding/Fnv1a.java
git commit -m "refactor: consolidate consistent hashing helpers"
```

---

## Final Verification

After Task 2, confirm:

```bash
git status -sb
mvn test
```

Expected:

- Working tree clean except any intentional branch-ahead state.
- `mvn test` reports build success and 0 failures.
