# Dynamic Shard Topology Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Redis shard topology a versioned, cross-instance-consistent snapshot that can be resharded at runtime without redeploy or data loss, keeping the consistent-hash ring as the per-generation mapping and deduping the FNV-1a hash.

**Architecture:** A single authoritative `shard:topology` JSON in Redis is the consistent snapshot; each instance refreshes it on a 30 s timer into an immutable in-memory `ShardTopology` (lock-free `volatile` swap, last-good on failure). Records are keyed per generation (version 1 = today's unversioned keys; v≥2 = `g{version}:` prefix); a reshard publishes version+1 and reads dual-read the previous generation for one max-TTL window so in-flight TTL data isn't lost.

**Tech Stack:** Java 17+, Maven/JUnit5/AssertJ/Mockito, Jackson (`ObjectMapper`), Jedis (`Pool<Jedis>`), Armeria (`BaseApiService`). Redis-backed tests use the existing `RedisShardingTestBase` (`@Tag("docker")`).

## Global Constraints

- **Within a version, mapping is byte-identical** — FNV-1a constants `0xcbf29ce484222325L`/`0x100000001b3L`, UTF-8 bytes, vnode key `"v" + v + ":" + shard`, default 150 vnodes, TreeMap ceiling lookup. Only crossing a version may remap.
- **No data loss** for records still within their TTL across a reshard (dual-read window = configured max record TTL).
- **Version 1 uses the existing unversioned key scheme** (`sr:rec:{shard}:{seq}`, `sr:dev:…`, `sr:stream:…`, `sr:seq:…`); only **v≥2** prepends `g{version}:`. Helper: `Generations.keyPrefix(v) = v <= 1 ? "" : "g" + v + ":"`.
- **Topology I/O never blocks the request hot path**; on Redis failure serve the last-good snapshot.
- `StableBucketer.KEYSPACE` stays 10000; its `fmix64` finalizer stays inline.
- No new dependencies. Reuse `EnvConfig`, the project `ObjectMapper`, the existing write/read `Pool<Jedis>` split.
- `mvn test` green at the end of every task. Feature branch; open a **PR**, never merge to `main` directly.
- Reshard is operator-triggered via a token-guarded `POST /shards/topology`; never automatic.

---

## Phase 1 — FNV-1a dedup

### Task 1: Shared `Fnv1a` primitive

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/redis/sharding/Fnv1a.java`
- Create: `src/test/java/com/recsys/infrastructure/redis/sharding/Fnv1aTest.java`
- Modify: `src/main/java/com/recsys/infrastructure/redis/sharding/ConsistentHashRing.java`
- Modify: `src/main/java/com/recsys/application/experiment/StableBucketer.java`

**Interfaces:**
- Produces: `Fnv1a.hash(byte[]) -> long`, `Fnv1a.hash(String) -> long` (UTF-8). Used by `ConsistentHashRing` (Tasks 2+) and `StableBucketer`.

- [ ] **Step 1: Capture today's golden hash values**

Run a throwaway main or `jshell` against the current `ConsistentHashRing.fnv1a` logic to record the exact longs (these are deterministic constants of FNV-1a, independent of the refactor):

```
fnv1a("v0:0")     = -3750763034362895579   (0xcbf29ce484222325 ^… ; capture the actual long)
fnv1a("v0:1")     = <capture>
fnv1a("device-123") = <capture>
```

Capture the real values by adding a temporary `System.out.println(ConsistentHashRing.fnv1a("v0:0"));` (the method is package-private) in a scratch test, run it, record the three longs, then delete the scratch. These literals go into Step 4.

- [ ] **Step 2: Write the failing golden test** — `Fnv1aTest.java`

```java
package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class Fnv1aTest {

    // Golden values captured from the pre-refactor ConsistentHashRing.fnv1a (Step 1).
    @Test
    void hash_matchesGoldenValues() {
        assertThat(Fnv1a.hash("v0:0")).isEqualTo(/* GOLDEN_V0_0 */);
        assertThat(Fnv1a.hash("v0:1")).isEqualTo(/* GOLDEN_V0_1 */);
        assertThat(Fnv1a.hash("device-123")).isEqualTo(/* GOLDEN_DEVICE_123 */);
    }

    @Test
    void stringAndByteOverloadsAgree() {
        assertThat(Fnv1a.hash("hello"))
                .isEqualTo(Fnv1a.hash("hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void deterministicAndCollisionFreeForDistinctShortKeys() {
        assertThat(Fnv1a.hash("a")).isEqualTo(Fnv1a.hash("a"));
        assertThat(Fnv1a.hash("a")).isNotEqualTo(Fnv1a.hash("b"));
    }
}
```

Replace the three `/* GOLDEN_* */` placeholders with the literal longs from Step 1 before running.

- [ ] **Step 3: Run it — expect FAIL (class missing)**

Run: `mvn -q test -Dtest=Fnv1aTest`
Expected: compile error / FAIL — `Fnv1a` does not exist.

- [ ] **Step 4: Create `Fnv1a.java`**

```java
package com.recsys.infrastructure.redis.sharding;

import java.nio.charset.StandardCharsets;

/**
 * 64-bit FNV-1a hash. The single source of FNV-1a in the codebase — used by
 * {@link ConsistentHashRing} (shard placement) and by StableBucketer (A/B bucketing,
 * which then applies its own murmur3 fmix64 finalizer).
 *
 * Do not change the constants or byte handling: shard placement and A/B bucketing
 * both depend on the exact output.
 */
public final class Fnv1a {

    private Fnv1a() {}

    public static long hash(byte[] data) {
        long h = 0xcbf29ce484222325L;          // FNV-1a 64-bit offset basis
        for (byte b : data) {
            h ^= (b & 0xffL);
            h *= 0x100000001b3L;               // FNV-1a 64-bit prime
        }
        return h;
    }

    public static long hash(String s) {
        return hash(s.getBytes(StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 5: Run the golden test — expect PASS**

Run: `mvn -q test -Dtest=Fnv1aTest`
Expected: PASS.

- [ ] **Step 6: Wire `ConsistentHashRing` to `Fnv1a`**

In `ConsistentHashRing.java`: delete the private `static long fnv1a(String s)` method (lines ~55-62) and its now-unused `import java.nio.charset.StandardCharsets;`. Replace the two call sites:
- line ~32: `long hash = fnv1a("v" + v + ":" + shard);` → `long hash = Fnv1a.hash("v" + v + ":" + shard);`
- line ~40: `long hash = fnv1a(deviceId);` → `long hash = Fnv1a.hash(deviceId);`

(No test references the package-private `fnv1a`, confirmed — `ConsistentHashRingTest` does not use it.)

- [ ] **Step 7: Wire `StableBucketer` to `Fnv1a`**

In `StableBucketer.java`, replace the FNV-1a accumulation loop inside `hash64(byte[] data)` with a call to `Fnv1a.hash`, keeping the murmur3 finalizer:

```java
    // FNV-1a accumulation (shared Fnv1a) followed by the murmur3 fmix64 finalizer.
    private static long hash64(byte[] data) {
        long h = com.recsys.infrastructure.redis.sharding.Fnv1a.hash(data);
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return h;
    }
```

Add `import com.recsys.infrastructure.redis.sharding.Fnv1a;` at the top and use `Fnv1a.hash(data)` (preferred over the fully-qualified inline). Remove the now-unused `import java.nio.charset.StandardCharsets;` **only if** `slot(...)` no longer references it — it does (`key.getBytes(StandardCharsets.UTF_8)`), so **keep** that import.

- [ ] **Step 8: Run the full suite + sanity grep**

Run: `mvn -q test`
Expected: BUILD SUCCESS (existing `ConsistentHashRing*`, `StableBucketer*`, `Sharded*` all green).

Run: `grep -rn "0xcbf29ce484222325L\|0x100000001b3L" src/main --include="*.java"`
Expected: matches in **only** `Fnv1a.java` (StableBucketer's fmix64 constants `0xff51…`/`0xc4ce…` are different and may remain).

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "refactor: extract shared Fnv1a primitive (dedup ring + StableBucketer)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Phase 2 — Versioned topology snapshot (read path)

### Task 2: `ShardTopology` immutable snapshot + `Generations` helper

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/redis/sharding/ShardTopology.java`
- Create: `src/main/java/com/recsys/infrastructure/redis/sharding/Generations.java`
- Create: `src/test/java/com/recsys/infrastructure/redis/sharding/ShardTopologyTest.java`

**Interfaces:**
- Produces: `ShardTopology(int version, int shardCount, int vnodes, long createdAtMs)` (ctor builds the ring) with `version()`, `shardCount()`, `vnodes()`, `createdAtMs()`, `ring()`, `shardFor(String)`.
- Produces: `Generations.keyPrefix(int version) -> String` (`"" ` for v≤1, `"g{version}:"` for v≥2).

- [ ] **Step 1: Write the failing test** — `ShardTopologyTest.java`

```java
package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShardTopologyTest {

    @Test
    void buildsRingAndExposesFields() {
        ShardTopology t = new ShardTopology(1, 4, 150, 1000L);
        assertThat(t.version()).isEqualTo(1);
        assertThat(t.shardCount()).isEqualTo(4);
        assertThat(t.vnodes()).isEqualTo(150);
        assertThat(t.createdAtMs()).isEqualTo(1000L);
        assertThat(t.shardFor("device-1")).isBetween(0, 3);
    }

    @Test
    void shardForMatchesUnderlyingRing() {
        ShardTopology t = new ShardTopology(2, 8, 150, 0L);
        assertThat(t.shardFor("user:123")).isEqualTo(t.ring().shardFor("user:123"));
    }

    @Test
    void rejectsInvalidShardCount() {
        assertThatThrownBy(() -> new ShardTopology(1, 0, 150, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keyPrefix_v1IsEmpty_v2Plus_isPrefixed() {
        assertThat(Generations.keyPrefix(1)).isEmpty();
        assertThat(Generations.keyPrefix(0)).isEmpty();
        assertThat(Generations.keyPrefix(2)).isEqualTo("g2:");
        assertThat(Generations.keyPrefix(7)).isEqualTo("g7:");
    }
}
```

- [ ] **Step 2: Run — expect FAIL**

Run: `mvn -q test -Dtest=ShardTopologyTest`
Expected: FAIL (classes missing).

- [ ] **Step 3: Create `Generations.java`**

```java
package com.recsys.infrastructure.redis.sharding;

/** Key-prefix scheme per topology generation. v1 reuses the original unversioned keys. */
public final class Generations {
    private Generations() {}

    /** "" for version <= 1 (legacy/original keyspace); "g{version}:" for version >= 2. */
    public static String keyPrefix(int version) {
        return version <= 1 ? "" : "g" + version + ":";
    }
}
```

- [ ] **Step 4: Create `ShardTopology.java`**

```java
package com.recsys.infrastructure.redis.sharding;

/**
 * Immutable snapshot of one shard-topology generation: a version, a shard count, and the
 * consistent-hash ring built from them. Thread-safe (all fields final, ring immutable).
 */
public final class ShardTopology {

    private final int version;
    private final int shardCount;
    private final int vnodes;
    private final long createdAtMs;
    private final ConsistentHashRing ring;

    public ShardTopology(int version, int shardCount, int vnodes, long createdAtMs) {
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
        this.version = version;
        this.shardCount = shardCount;          // ConsistentHashRing validates >= 1
        this.vnodes = vnodes;                  // ConsistentHashRing validates >= 1
        this.createdAtMs = createdAtMs;
        this.ring = new ConsistentHashRing(shardCount, vnodes);
    }

    public int version()      { return version; }
    public int shardCount()   { return shardCount; }
    public int vnodes()       { return vnodes; }
    public long createdAtMs() { return createdAtMs; }
    public ConsistentHashRing ring() { return ring; }

    public int shardFor(String deviceId) { return ring.shardFor(deviceId); }
}
```

- [ ] **Step 5: Run — expect PASS**

Run: `mvn -q test -Dtest=ShardTopologyTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add immutable ShardTopology snapshot + Generations key-prefix helper

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `ShardTopologyStore` (Redis load / bootstrap / publish)

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/redis/sharding/ShardTopologyStore.java`
- Create: `src/test/java/com/recsys/infrastructure/redis/sharding/ShardTopologyStoreTest.java`

**Interfaces:**
- Produces: `ShardTopologyStore.Snapshot(int version, int shardCount, int vnodes, long createdAtMs, Integer prevVersion, Integer prevShardCount, Long prevExpiresAtMs)` (Jackson-serializable record).
- Produces: `Snapshot load()` (null if absent); `Snapshot bootstrap(int shardCount, int vnodes, long nowMs)` (SETNX then load); `Snapshot publishReshard(int newShardCount, long nowMs, long dualReadWindowMs)` (atomic version bump via Lua).

- [ ] **Step 1: Write the failing test** — `ShardTopologyStoreTest.java` (extends the docker base)

```java
package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("docker")
class ShardTopologyStoreTest extends RedisShardingTestBase {

    @Test
    void bootstrap_writesVersion1_andIsIdempotent() {
        ShardTopologyStore store = new ShardTopologyStore(pool, "shard:topology:test1");

        ShardTopologyStore.Snapshot s1 = store.bootstrap(2, 150, 1000L);
        assertThat(s1.version()).isEqualTo(1);
        assertThat(s1.shardCount()).isEqualTo(2);
        assertThat(s1.prevVersion()).isNull();

        // Second bootstrap must NOT overwrite (SETNX) — even with a different shardCount.
        ShardTopologyStore.Snapshot s2 = store.bootstrap(9, 150, 2000L);
        assertThat(s2.version()).isEqualTo(1);
        assertThat(s2.shardCount()).isEqualTo(2);
    }

    @Test
    void load_returnsNullWhenAbsent() {
        ShardTopologyStore store = new ShardTopologyStore(pool, "shard:topology:absent");
        assertThat(store.load()).isNull();
    }

    @Test
    void publishReshard_bumpsVersionAndRecordsPreviousWithExpiry() {
        ShardTopologyStore store = new ShardTopologyStore(pool, "shard:topology:test2");
        store.bootstrap(2, 150, 1000L);

        ShardTopologyStore.Snapshot v2 = store.publishReshard(4, 5000L, 60_000L);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.shardCount()).isEqualTo(4);
        assertThat(v2.prevVersion()).isEqualTo(1);
        assertThat(v2.prevShardCount()).isEqualTo(2);
        assertThat(v2.prevExpiresAtMs()).isEqualTo(65_000L);

        // A subsequent load reflects the published version.
        assertThat(store.load().version()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run — expect FAIL**

Run: `mvn -q test -Dtest=ShardTopologyStoreTest -Dgroups=docker`
Expected: FAIL (class missing).

- [ ] **Step 3: Create `ShardTopologyStore.java`**

```java
package com.recsys.infrastructure.redis.sharding;

import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.util.Pool;

import java.util.List;

/**
 * Authoritative shard-topology snapshot in Redis (key {@code shard:topology}).
 * One small JSON document; bootstrap is SETNX (first writer wins), reshard is an atomic
 * Lua read-modify-write that bumps the version and records the previous generation +
 * its dual-read expiry.
 */
public final class ShardTopologyStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Reshard: read JSON at KEYS[1], bump version, set prev pointer, write back, return new JSON. */
    private static final String PUBLISH_LUA = """
            local cur = redis.call('GET', KEYS[1])
            if not cur then return redis.error_reply('topology-absent') end
            local t = cjson.decode(cur)
            local newShard = tonumber(ARGV[1])
            local nowMs = tonumber(ARGV[2])
            local windowMs = tonumber(ARGV[3])
            local next = {
              version = t.version + 1,
              shardCount = newShard,
              vnodes = t.vnodes,
              createdAtMs = nowMs,
              prevVersion = t.version,
              prevShardCount = t.shardCount,
              prevExpiresAtMs = nowMs + windowMs
            }
            local encoded = cjson.encode(next)
            redis.call('SET', KEYS[1], encoded)
            return encoded
            """;

    private final Pool<Jedis> pool;
    private final String key;

    public ShardTopologyStore(Pool<Jedis> pool, String key) {
        this.pool = pool;
        this.key = key;
    }

    public Snapshot load() {
        try (Jedis jedis = pool.getResource()) {
            String json = jedis.get(key);
            return json == null ? null : parse(json);
        }
    }

    /** First-writer-wins create of version 1; returns the effective snapshot (existing or new). */
    public Snapshot bootstrap(int shardCount, int vnodes, long nowMs) {
        Snapshot v1 = new Snapshot(1, shardCount, vnodes, nowMs, null, null, null);
        try (Jedis jedis = pool.getResource()) {
            jedis.set(key, write(v1), SetParams.setParams().nx());
        }
        return load();
    }

    /** Atomically bump to version+1 with the new shard count and a prev pointer + expiry. */
    public Snapshot publishReshard(int newShardCount, long nowMs, long dualReadWindowMs) {
        Object raw;
        try (Jedis jedis = pool.getResource()) {
            raw = jedis.eval(PUBLISH_LUA, List.of(key),
                    List.of(Integer.toString(newShardCount),
                            Long.toString(nowMs),
                            Long.toString(dualReadWindowMs)));
        }
        return parse((String) raw);
    }

    private static Snapshot parse(String json) {
        try {
            return MAPPER.readValue(json, Snapshot.class);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt shard topology JSON: " + json, e);
        }
    }

    private static String write(Snapshot s) {
        try {
            return MAPPER.writeValueAsString(s);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize shard topology", e);
        }
    }

    public record Snapshot(
            int version,
            int shardCount,
            int vnodes,
            long createdAtMs,
            Integer prevVersion,
            Integer prevShardCount,
            Long prevExpiresAtMs
    ) {}
}
```

> Jackson serializes a record by component name, matching the Lua `cjson` field names exactly — round-trip safe. `cjson` encodes integers without decimals, so `tonumber` and Jackson `int`/`long` align.

- [ ] **Step 4: Run — expect PASS**

Run: `mvn -q test -Dtest=ShardTopologyStoreTest -Dgroups=docker`
Expected: PASS (all three cases).

- [ ] **Step 5: Run full suite + commit**

Run: `mvn -q test`
Expected: BUILD SUCCESS.

```bash
git add -A
git commit -m "feat: ShardTopologyStore — Redis snapshot bootstrap + atomic reshard publish

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: `ShardTopologyProvider` (in-memory current/previous + refresh)

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/redis/sharding/ShardTopologyProvider.java`
- Create: `src/test/java/com/recsys/infrastructure/redis/sharding/ShardTopologyProviderTest.java`

**Interfaces:**
- Consumes: `ShardTopologyStore` (Task 3), `ShardTopology` (Task 2).
- Produces:
  - ctor `ShardTopologyProvider(ShardTopologyStore store, int vnodes, int initialShardCount, long refreshMs, java.util.function.LongSupplier clockMs)`
  - `void start()` — bootstrap (if absent) + initial `refresh()` + schedule periodic `refresh()`.
  - `void refresh()` — load snapshot, rebuild `current` (+ `previous` if `prevExpiresAtMs` in the future), atomically swap; on any failure keep the last-good snapshot.
  - `ShardTopology current()`.
  - `ShardTopology previousIfActive()` — the previous-generation snapshot if `now < prevExpiresAtMs`, else `null`.
  - `void stop()`.
  - static `ShardTopologyProvider fixed(ConsistentHashRing ring)` — constant version-1 provider (no Redis, no refresh) for tests / non-dynamic wiring.

- [ ] **Step 1: Write the failing test** — `ShardTopologyProviderTest.java` (pure unit; mock the store)

```java
package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShardTopologyProviderTest {

    private static ShardTopologyStore.Snapshot snap(int v, int shards, long created,
            Integer pv, Integer ps, Long pExp) {
        return new ShardTopologyStore.Snapshot(v, shards, 150, created, pv, ps, pExp);
    }

    @Test
    void refresh_adoptsCurrentVersionFromStore() {
        ShardTopologyStore store = mock(ShardTopologyStore.class);
        when(store.load()).thenReturn(snap(3, 8, 100L, null, null, null));
        AtomicLong clock = new AtomicLong(500L);
        ShardTopologyProvider p = new ShardTopologyProvider(store, 150, 2, 30_000L, clock::get);

        p.refresh();

        assertThat(p.current().version()).isEqualTo(3);
        assertThat(p.current().shardCount()).isEqualTo(8);
        assertThat(p.previousIfActive()).isNull();
    }

    @Test
    void previousIsActiveOnlyInsideWindow() {
        ShardTopologyStore store = mock(ShardTopologyStore.class);
        when(store.load()).thenReturn(snap(2, 4, 1000L, 1, 2, 5000L)); // prev expires at 5000
        AtomicLong clock = new AtomicLong(3000L);                        // inside window
        ShardTopologyProvider p = new ShardTopologyProvider(store, 150, 2, 30_000L, clock::get);

        p.refresh();
        assertThat(p.previousIfActive()).isNotNull();
        assertThat(p.previousIfActive().version()).isEqualTo(1);
        assertThat(p.previousIfActive().shardCount()).isEqualTo(2);

        clock.set(5000L); // window closed (now >= expiry)
        assertThat(p.previousIfActive()).isNull();
    }

    @Test
    void refresh_keepsLastGoodSnapshotOnStoreFailure() {
        ShardTopologyStore store = mock(ShardTopologyStore.class);
        when(store.load()).thenReturn(snap(1, 2, 0L, null, null, null));
        ShardTopologyProvider p = new ShardTopologyProvider(store, 150, 2, 30_000L, () -> 0L);
        p.refresh();
        assertThat(p.current().version()).isEqualTo(1);

        when(store.load()).thenThrow(new RuntimeException("redis down"));
        p.refresh(); // must not throw, must not null out current

        assertThat(p.current().version()).isEqualTo(1);
    }

    @Test
    void fixed_providesConstantVersionOneTopology() {
        ShardTopologyProvider p = ShardTopologyProvider.fixed(new ConsistentHashRing(2, 150));
        assertThat(p.current().version()).isEqualTo(1);
        assertThat(p.current().shardCount()).isEqualTo(2);
        assertThat(p.previousIfActive()).isNull();
    }
}
```

- [ ] **Step 2: Run — expect FAIL**

Run: `mvn -q test -Dtest=ShardTopologyProviderTest`
Expected: FAIL (class missing).

- [ ] **Step 3: Create `ShardTopologyProvider.java`**

```java
package com.recsys.infrastructure.redis.sharding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Holds the in-memory, periodically-refreshed view of the shard topology. Reads are lock-free
 * (a single volatile reference to an immutable {@link Snapshot}); a refresh atomically swaps it.
 * On any refresh failure the last-good snapshot is retained — topology I/O never breaks the
 * request path.
 */
public final class ShardTopologyProvider {

    private static final Logger log = LoggerFactory.getLogger(ShardTopologyProvider.class);

    private final ShardTopologyStore store;
    private final int vnodes;
    private final int initialShardCount;
    private final long refreshMs;
    private final LongSupplier clockMs;

    private volatile Snapshot snapshot;          // null until first successful refresh / fixed()
    private ScheduledExecutorService scheduler;

    public ShardTopologyProvider(ShardTopologyStore store, int vnodes, int initialShardCount,
                                 long refreshMs, LongSupplier clockMs) {
        this.store = store;
        this.vnodes = vnodes;
        this.initialShardCount = initialShardCount;
        this.refreshMs = refreshMs;
        this.clockMs = clockMs;
    }

    private ShardTopologyProvider(ShardTopology fixedCurrent) {
        this.store = null; this.vnodes = fixedCurrent.vnodes();
        this.initialShardCount = fixedCurrent.shardCount();
        this.refreshMs = 0L; this.clockMs = () -> 0L;
        this.snapshot = new Snapshot(fixedCurrent, null, Long.MIN_VALUE);
    }

    /** Constant version-1 provider with no Redis/refresh — for tests and non-dynamic wiring. */
    public static ShardTopologyProvider fixed(ConsistentHashRing ring) {
        return new ShardTopologyProvider(
                new ShardTopology(1, ring.shardCount(), ConsistentHashRing.DEFAULT_VIRTUAL_NODES, 0L));
    }

    public void start() {
        if (store != null) {
            try { store.bootstrap(initialShardCount, vnodes, clockMs.getAsLong()); }
            catch (Exception e) { log.warn("topology bootstrap failed (will retry on refresh): {}", e.toString()); }
        }
        refresh();
        if (refreshMs > 0 && store != null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "shard-topology-refresh");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(this::refresh, refreshMs, refreshMs, TimeUnit.MILLISECONDS);
        }
    }

    public void refresh() {
        if (store == null) return; // fixed provider
        try {
            ShardTopologyStore.Snapshot s = store.load();
            if (s == null) return;  // not yet bootstrapped — keep last-good
            ShardTopology current = new ShardTopology(s.version(), s.shardCount(), s.vnodes(), s.createdAtMs());
            ShardTopology previous = null;
            long prevExpiresAtMs = Long.MIN_VALUE;
            if (s.prevVersion() != null && s.prevShardCount() != null && s.prevExpiresAtMs() != null) {
                previous = new ShardTopology(s.prevVersion(), s.prevShardCount(), s.vnodes(), s.createdAtMs());
                prevExpiresAtMs = s.prevExpiresAtMs();
            }
            this.snapshot = new Snapshot(current, previous, prevExpiresAtMs);
        } catch (Exception e) {
            log.warn("topology refresh failed — keeping last-good snapshot: {}", e.toString());
        }
    }

    public ShardTopology current() {
        Snapshot s = snapshot;
        if (s == null) throw new IllegalStateException("topology not initialized — call start() first");
        return s.current;
    }

    public ShardTopology previousIfActive() {
        Snapshot s = snapshot;
        if (s == null || s.previous == null) return null;
        return clockMs.getAsLong() < s.prevExpiresAtMs ? s.previous : null;
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    /** Immutable triple swapped atomically on refresh. */
    private record Snapshot(ShardTopology current, ShardTopology previous, long prevExpiresAtMs) {}
}
```

> `ConsistentHashRing.DEFAULT_VIRTUAL_NODES` is currently package-private (`static final int = 150`) in the same package — accessible here. Leave its visibility unchanged.

- [ ] **Step 4: Run — expect PASS**

Run: `mvn -q test -Dtest=ShardTopologyProviderTest`
Expected: PASS (all four cases).

- [ ] **Step 5: Full suite + commit**

Run: `mvn -q test`
Expected: BUILD SUCCESS.

```bash
git add -A
git commit -m "feat: ShardTopologyProvider — lock-free refreshable current/previous snapshot

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Generation-aware keys in the store (single-version, behavior-identical)

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/redis/sharding/SequenceGenerator.java`
- Modify: `src/main/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStore.java`
- Modify: `src/main/java/com/recsys/api/online/OnlinePredictionServer.java`
- Test: existing `ShardedRecordStore*Test`, `SequenceGeneratorTest` must stay green; add `src/test/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStoreGenerationKeyTest.java`

**Interfaces:**
- `SequenceGenerator`: add `long next(int version, int shardIndex)`; keep `long next(int shardIndex)` delegating to `next(1, shardIndex)`. Seq key = `prefix + Generations.keyPrefix(version) + "seq:" + shard`.
- `ShardedRecordStore`: new primary ctor `ShardedRecordStore(Pool<Jedis> writePool, Pool<Jedis> readPool, ShardTopologyProvider provider, SequenceGenerator seqGen, String prefix)`. Keep the two ring-based ctors as adapters via `ShardTopologyProvider.fixed(ring)`. Keys gain a `version` arg.

- [ ] **Step 1: Write the failing key-format test** — `ShardedRecordStoreGenerationKeyTest.java`

```java
package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.Pool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ShardedRecordStoreGenerationKeyTest {

    // Key helpers don't touch Redis; mock pools satisfy the constructor's non-null checks.
    @SuppressWarnings("unchecked")
    private ShardedRecordStore storeAtVersion(int version, int shardCount) {
        Pool<Jedis> pool = mock(Pool.class);
        ShardTopologyProvider provider = ShardTopologyProvider.fixedAtVersion(version, shardCount, 150);
        return new ShardedRecordStore(pool, pool, provider,
                new SequenceGenerator(pool, "sr:"), "sr:");
    }

    @Test
    void version1_usesUnversionedKeys() {
        ShardedRecordStore store = storeAtVersion(1, 2);
        assertThat(store.recKey(1, 0, 5L)).isEqualTo("sr:rec:0:5");
        assertThat(store.devKey(1, 0, "dev-1")).isEqualTo("sr:dev:0:dev-1");
        assertThat(store.streamKey(1, 0)).isEqualTo("sr:stream:0");
    }

    @Test
    void version2_prependsGenerationPrefix() {
        ShardedRecordStore store = storeAtVersion(2, 4);
        assertThat(store.recKey(2, 1, 7L)).isEqualTo("sr:g2:rec:1:7");
        assertThat(store.devKey(2, 1, "dev-1")).isEqualTo("sr:g2:dev:1:dev-1");
        assertThat(store.streamKey(2, 1)).isEqualTo("sr:g2:stream:1");
    }
}
```

> This test needs a `ShardTopologyProvider.fixedAtVersion(int version, int shardCount, int vnodes)` test factory. Add it alongside `fixed(...)` in Task 4's class (it's a thin variant): returns a constant provider whose `current()` is `new ShardTopology(version, shardCount, vnodes, 0L)`. Add it now as part of this task.

- [ ] **Step 2: Add `fixedAtVersion` to `ShardTopologyProvider`**

In `ShardTopologyProvider.java`, add next to `fixed(...)`:

```java
    /** Constant provider pinned at an explicit version — test/helper use. */
    public static ShardTopologyProvider fixedAtVersion(int version, int shardCount, int vnodes) {
        return new ShardTopologyProvider(new ShardTopology(version, shardCount, vnodes, 0L));
    }
```

- [ ] **Step 3: Update `SequenceGenerator`** for generation-scoped keys

Replace the seq-key method and `next` in `SequenceGenerator.java`:

```java
    /** Next sequence for (version, shard). Always >= 1. */
    public long next(int version, int shardIndex) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.incr(seqKey(version, shardIndex));
        }
    }

    /** Back-compat: version-1 (unversioned) sequence. */
    public long next(int shardIndex) {
        return next(1, shardIndex);
    }

    private String seqKey(int version, int shardIndex) {
        return prefix + Generations.keyPrefix(version) + "seq:" + shardIndex;
    }
```

`ensureCounterValid`/`findMaxSeqInShard` operate on version 1 only (the only version with pre-existing data at rollout); leave them keyed to the unversioned `prefix + "dev:" + shard + ":*"` and `prefix + "seq:" + shard` (i.e., `Generations.keyPrefix(1)` == "" — unchanged). No signature change there.

- [ ] **Step 4: Update `ShardedRecordStore`** to be provider- and generation-aware

In `ShardedRecordStore.java`:

1. Replace the `ConsistentHashRing ring` field with `ShardTopologyProvider provider`.
2. New primary constructor + keep ring adapters:

```java
    public ShardedRecordStore(Pool<Jedis> writePool, Pool<Jedis> readPool,
                              ShardTopologyProvider provider,
                              SequenceGenerator seqGen, String prefix) {
        this.writePool = Objects.requireNonNull(writePool, "writePool");
        this.readPool  = Objects.requireNonNull(readPool,  "readPool");
        this.provider  = Objects.requireNonNull(provider,  "provider");
        this.seqGen    = Objects.requireNonNull(seqGen,    "seqGen");
        this.prefix    = Objects.requireNonNull(prefix,    "prefix");
    }

    // Back-compat: fixed single-version topology from a ring.
    public ShardedRecordStore(Pool<Jedis> pool, ConsistentHashRing ring,
                              SequenceGenerator seqGen, String prefix) {
        this(pool, pool, ShardTopologyProvider.fixed(ring), seqGen, prefix);
    }

    public ShardedRecordStore(Pool<Jedis> writePool, Pool<Jedis> readPool,
                              ConsistentHashRing ring, SequenceGenerator seqGen, String prefix) {
        this(writePool, readPool, ShardTopologyProvider.fixed(ring), seqGen, prefix);
    }
```

3. `doWrite` uses the current topology and version-scoped keys/seq:

```java
    private WriteResult doWrite(ShardedRecord record, boolean isUpdate, int ttlSeconds) {
        ShardTopology topo = provider.current();
        int version    = topo.version();
        int shardIndex = topo.shardFor(record.deviceId());
        long seqNum    = seqGen.next(version, shardIndex);

        String recKey    = recKey(version, shardIndex, seqNum);
        String devKey    = devKey(version, shardIndex, record.deviceId());
        String streamKey = streamKey(version, shardIndex);
        // ... pipeline body UNCHANGED (hset/expire/zadd/xadd/sync) ...
    }
```

4. Reads resolve the shard from the current topology and use version-scoped keys. **In this task keep them single-generation** (current only); Task 6 adds the previous-generation fallback. Update `readDevice`, `readShard`, `readAllShards`, and `fetchRecords` to thread `version`:

```java
    public Page<ShardedRecord> readDevice(String deviceId, ShardCursor cursor, int limit) {
        ShardTopology topo = provider.current();
        int version = topo.version();
        int shardIndex = topo.shardFor(deviceId);
        String devKey = devKey(version, shardIndex, deviceId);
        // ... unchanged zrangeByScore logic, but fetchRecords(version, shardIndex, seqNums) ...
    }

    public Page<ShardedRecord> readShard(int shardIndex, ShardCursor cursor, int limit) {
        int version = provider.current().version();
        String streamKey = streamKey(version, shardIndex);
        // ... unchanged xread logic, but fetchRecords(version, shardIndex, seqNums) ...
    }

    public List<Page<ShardedRecord>> readAllShards(ShardCursor cursor, int limitPerShard) {
        int shardCount = provider.current().shardCount();
        // ... unchanged loop over [0, shardCount) ...
    }

    private List<ShardedRecord> fetchRecords(int version, int shardIndex, List<Long> seqNums) {
        // ... unchanged, but recKey(version, shardIndex, seq) ...
    }
```

5. Key helpers become version-scoped (keep them package-private for the key test):

```java
    String recKey(int version, int shardIndex, long seqNum) {
        return prefix + Generations.keyPrefix(version) + "rec:" + shardIndex + ":" + seqNum;
    }
    String devKey(int version, int shardIndex, String deviceId) {
        return prefix + Generations.keyPrefix(version) + "dev:" + shardIndex + ":" + deviceId;
    }
    String streamKey(int version, int shardIndex) {
        return prefix + Generations.keyPrefix(version) + "stream:" + shardIndex;
    }
```

> Because version 1 → `Generations.keyPrefix(1)` == "", every key is byte-identical to today for the bootstrapped v1 topology. Existing `ShardedRecordStore*Test` (which build the store via the ring ctor → `fixed(ring)` → version 1) keep passing unchanged.

- [ ] **Step 5: Wire `OnlinePredictionServer`** to construct the provider

Replace the sharding wiring block (current lines ~110-118) with:

```java
            int shardCount = readIntEnv("SHARDED_RECORD_SHARD_COUNT", 2);
            long refreshMs = readIntEnv("SHARD_TOPOLOGY_REFRESH_SECONDS", 30) * 1000L;
            ShardTopologyStore topologyStore = new ShardTopologyStore(jedisPool, "shard:topology");
            ShardTopologyProvider topologyProvider = new ShardTopologyProvider(
                    topologyStore, ConsistentHashRing.DEFAULT_VIRTUAL_NODES, shardCount, refreshMs,
                    System::currentTimeMillis);
            topologyProvider.start();
            ShardedRecordStore shardedRecordStore = new ShardedRecordStore(
                    jedisPool, jedisPool, topologyProvider,
                    new SequenceGenerator(jedisPool, "sr:"), "sr:");
```

Add imports for `ShardTopologyStore`, `ShardTopologyProvider`. `ConsistentHashRing.DEFAULT_VIRTUAL_NODES` is package-private — to use it from `api.online`, either (a) make it `public` (one-word visibility change, acceptable), or (b) pass the literal `150`. **Choose (b): pass `150`** to avoid widening visibility. (The provider stores it as `vnodes`.) Register the service exactly as before (Task 7 changes its constructor).

- [ ] **Step 6: Run the generation-key test + the existing sharding suite**

Run: `mvn -q test -Dtest='ShardedRecordStoreGenerationKeyTest,SequenceGeneratorTest,ShardedRecordStoreWriteTest,ShardedRecordStoreReadTest'`
Expected: PASS. The docker-tagged ones need `-Dgroups=docker`:
Run: `mvn -q test -Dgroups=docker -Dtest='ShardedRecordStore*Test'`
Expected: PASS (keys are byte-identical at v1).

- [ ] **Step 7: Full suite + commit**

Run: `mvn -q test`
Expected: BUILD SUCCESS.

```bash
git add -A
git commit -m "feat: generation-scoped shard keys via ShardTopologyProvider (v1 == legacy keys)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Phase 3 — Dual-read migration + reshard endpoint

### Task 6: Dual-read across current + previous generation

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStore.java`
- Test: `src/test/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStoreDualReadTest.java` (`@Tag("docker")`)

**Interfaces:**
- Consumes: `ShardTopologyProvider.previousIfActive()` (Task 4).
- Behavior: `readDevice` returns records from the **current** generation merged with the **previous** generation (when active), deduped by `(deviceId, seqNum)` preferring current; `readShard(index, …)` reads the current generation only (stream scan is per concrete shard index and version) — document that shard-level reads are generation-current. Writes remain current-only (Task 5).

- [ ] **Step 1: Write the failing dual-read test** — `ShardedRecordStoreDualReadTest.java`

```java
package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("docker")
class ShardedRecordStoreDualReadTest extends RedisShardingTestBase {

    private ShardedRecordStore storeOn(ShardTopologyProvider provider) {
        return new ShardedRecordStore(pool, pool, provider, new SequenceGenerator(pool, "sr:"), "sr:");
    }

    @Test
    void readDevice_findsRecordWrittenUnderPreviousGeneration_whileWindowOpen() {
        // Write under generation 1 (unversioned keys).
        ShardTopologyProvider v1 = ShardTopologyProvider.fixedAtVersion(1, 2, 150);
        storeOn(v1).write(new ShardedRecord("dev-A", 0, RecordType.EVENT, "e1", "p", 1L));

        // Reader on generation 2, with generation 1 still active as previous.
        ShardTopologyProvider migrating = TestProviders.withPrevious(
                /*current*/ new ShardTopology(2, 4, 150, 0L),
                /*previous*/ new ShardTopology(1, 2, 150, 0L),
                /*prevExpiresAtMs*/ Long.MAX_VALUE);

        var page = storeOn(migrating).readDevice("dev-A", ShardCursor.start(), 10);
        assertThat(page.records()).extracting(ShardedRecord::eventId).contains("e1");
    }

    @Test
    void readDevice_skipsPreviousGeneration_afterWindowCloses() {
        ShardTopologyProvider v1 = ShardTopologyProvider.fixedAtVersion(1, 2, 150);
        storeOn(v1).write(new ShardedRecord("dev-B", 0, RecordType.EVENT, "e2", "p", 1L));

        ShardTopologyProvider expired = TestProviders.withPrevious(
                new ShardTopology(2, 4, 150, 0L),
                new ShardTopology(1, 2, 150, 0L),
                /*prevExpiresAtMs*/ Long.MIN_VALUE); // already closed

        var page = storeOn(expired).readDevice("dev-B", ShardCursor.start(), 10);
        assertThat(page.records()).extracting(ShardedRecord::eventId).doesNotContain("e2");
    }
}
```

> Add a tiny test helper `TestProviders.withPrevious(current, previous, prevExpiresAtMs)` in `src/test/java/com/recsys/infrastructure/redis/sharding/TestProviders.java` that builds a constant provider exposing those exact current/previous/expiry values (mirrors the private `Snapshot` triple via a package-private factory). To enable it, add to `ShardTopologyProvider` a package-private static factory:
> ```java
> static ShardTopologyProvider fixedWithPrevious(ShardTopology current, ShardTopology previous, long prevExpiresAtMs) {
>     ShardTopologyProvider p = new ShardTopologyProvider(current);
>     p.snapshot = new Snapshot(current, previous, prevExpiresAtMs);
>     p.clockFixedForTest(); // see below
>     return p;
> }
> ```
> Since `previousIfActive()` compares `clockMs.getAsLong()` against `prevExpiresAtMs`, the fixed ctor's clock returns `0L`; `Long.MAX_VALUE` (open) and `Long.MIN_VALUE` (closed) bracket it correctly, so no extra clock plumbing is needed — drop the `clockFixedForTest()` line. `TestProviders.withPrevious` just calls `ShardTopologyProvider.fixedWithPrevious(...)`.

- [ ] **Step 2: Run — expect FAIL**

Run: `mvn -q test -Dtest=ShardedRecordStoreDualReadTest -Dgroups=docker`
Expected: FAIL — `e1` not found (no dual-read yet) / missing factory.

- [ ] **Step 3: Add the package-private `fixedWithPrevious` factory to `ShardTopologyProvider`**

```java
    static ShardTopologyProvider fixedWithPrevious(ShardTopology current, ShardTopology previous,
                                                   long prevExpiresAtMs) {
        ShardTopologyProvider p = new ShardTopologyProvider(current);
        p.snapshot = new Snapshot(current, previous, prevExpiresAtMs);
        return p;
    }
```

(The private `Snapshot` record and `snapshot` field already exist from Task 4.)

- [ ] **Step 4: Implement dual-read in `readDevice`**

Replace `readDevice` so it reads the current generation and, when a previous generation is active, merges its records (deduped by `deviceId:seqNum`, current wins):

```java
    public Page<ShardedRecord> readDevice(String deviceId, ShardCursor cursor, int limit) {
        ShardTopology cur = provider.current();
        Page<ShardedRecord> currentPage = readDeviceAt(cur.version(), cur.shardFor(deviceId),
                deviceId, cursor, limit);

        ShardTopology prev = provider.previousIfActive();
        if (prev == null) return currentPage;

        Page<ShardedRecord> prevPage = readDeviceAt(prev.version(), prev.shardFor(deviceId),
                deviceId, cursor, limit);
        return mergeDevicePages(currentPage, prevPage, limit);
    }

    // Single-generation device read (the former readDevice body, now version-scoped).
    private Page<ShardedRecord> readDeviceAt(int version, int shardIndex, String deviceId,
                                             ShardCursor cursor, int limit) {
        String devKey = devKey(version, shardIndex, deviceId);
        double minScore = cursor.isStart() ? Double.NEGATIVE_INFINITY
                                           : Double.parseDouble(cursor.value()) + 1;
        List<Tuple> tuples;
        try (Jedis jedis = readPool.getResource()) {
            tuples = jedis.zrangeByScoreWithScores(devKey, minScore, Double.POSITIVE_INFINITY, 0, limit);
        }
        if (tuples.isEmpty()) return Page.empty();
        List<Long> seqNums = tuples.stream().map(t -> (long) t.getScore()).toList();
        List<ShardedRecord> records = fetchRecords(version, shardIndex, seqNums);
        long lastSeq = (long) tuples.get(tuples.size() - 1).getScore();
        ShardCursor next = tuples.size() < limit ? null : ShardCursor.of(String.valueOf(lastSeq));
        return new Page<>(records, next);
    }

    // Merge current + previous device records, dedupe by (deviceId, seqNum) preferring current,
    // sort by seqNum ascending, cap at `limit`. Cursor: current's cursor drives pagination
    // (previous-generation data is finite and TTLs out within the window).
    private Page<ShardedRecord> mergeDevicePages(Page<ShardedRecord> current,
                                                 Page<ShardedRecord> previous, int limit) {
        java.util.LinkedHashMap<String, ShardedRecord> byKey = new java.util.LinkedHashMap<>();
        for (ShardedRecord r : current.records()) byKey.put(r.deviceId() + ":" + r.seqNum(), r);
        for (ShardedRecord r : previous.records()) byKey.putIfAbsent(r.deviceId() + ":" + r.seqNum(), r);
        List<ShardedRecord> merged = new ArrayList<>(byKey.values());
        merged.sort(java.util.Comparator.comparingLong(ShardedRecord::seqNum));
        if (merged.size() > limit) merged = new ArrayList<>(merged.subList(0, limit));
        return new Page<>(merged, current.next());
    }
```

Leave `readShard`/`readAllShards` as the current-generation versions from Task 5 (document: stream/shard-level reads are generation-current; device reads are the dual-read surface that matters for migration).

- [ ] **Step 5: Run the dual-read test — expect PASS**

Run: `mvn -q test -Dtest=ShardedRecordStoreDualReadTest -Dgroups=docker`
Expected: PASS (found within window; absent after window).

- [ ] **Step 6: Full suite + commit**

Run: `mvn -q test` (and `-Dgroups=docker` for the sharding integration tests)
Expected: BUILD SUCCESS.

```bash
git add -A
git commit -m "feat: dual-read device records across current+previous generation within window

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Token-guarded reshard endpoint `POST /shards/topology`

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/store/ShardedRecordService.java`
- Modify: `src/main/java/com/recsys/api/online/OnlinePredictionServer.java`
- Test: `src/test/java/com/recsys/infrastructure/store/ShardedRecordServiceReshardTest.java`

**Interfaces:**
- `ShardedRecordService` new ctor: `ShardedRecordService(ShardedRecordStore store, ShardTopologyStore topologyStore, String adminToken, long dualReadWindowMs, java.util.function.LongSupplier clockMs)`. Keep the single-arg ctor (`ShardedRecordService(store)`) delegating with a `null` topologyStore + empty token (reshard disabled) for existing tests.
- New route handled in `doPost`: path `/shards/topology` → require header `X-Admin-Token` == `adminToken` (non-blank); body `{ "shardCount": N }`; calls `topologyStore.publishReshard(N, clockMs, dualReadWindowMs)`; returns the new `{version, shardCount, prevVersion, prevExpiresAtMs}`.

- [ ] **Step 1: Write the failing test** — `ShardedRecordServiceReshardTest.java`

Use Armeria's `ServerExtension` if the existing service tests do; otherwise unit-test the handler via a constructed `ShardedRecordService` and a mock `ShardTopologyStore`. Pattern after `ShardedRecordServiceIntegrationTest` (read it for the established harness). Cases:

```java
// 1. POST /shards/topology with correct X-Admin-Token + {"shardCount":4}
//    → 200, body.version == 2, body.shardCount == 4; topologyStore.publishReshard(4, ...) called once.
// 2. Missing/incorrect token → 403, publishReshard never called.
// 3. adminToken blank/null (reshard disabled) → 403 regardless of header.
// 4. Body missing shardCount or shardCount < 1 → 400, publishReshard never called.
```

Write each case with real assertions (status code + Mockito `verify`), mirroring the existing service test's request-construction style.

- [ ] **Step 2: Run — expect FAIL**

Run: `mvn -q test -Dtest=ShardedRecordServiceReshardTest`
Expected: FAIL.

- [ ] **Step 3: Add reshard handling to `ShardedRecordService`**

Add fields + constructors:

```java
    private final ShardedRecordStore store;
    private final ShardTopologyStore topologyStore;   // null => reshard disabled
    private final String adminToken;                  // blank/null => reshard disabled
    private final long dualReadWindowMs;
    private final java.util.function.LongSupplier clockMs;

    public ShardedRecordService(ShardedRecordStore store) {
        this(store, null, null, 0L, System::currentTimeMillis);
    }

    public ShardedRecordService(ShardedRecordStore store, ShardTopologyStore topologyStore,
                                String adminToken, long dualReadWindowMs,
                                java.util.function.LongSupplier clockMs) {
        this.store = store;
        this.topologyStore = topologyStore;
        this.adminToken = adminToken;
        this.dualReadWindowMs = dualReadWindowMs;
        this.clockMs = clockMs;
    }
```

In `doPost`, branch on the topology path **before** the records branch:

```java
        if (path.equals("/shards/topology")) {
            return handleReshard(ctx, req);
        }
```

Add the handler:

```java
    private HttpResponse handleReshard(ServiceRequestContext ctx, HttpRequest req) {
        return HttpResponse.of(req.aggregate().thenApplyAsync(agg -> {
            if (topologyStore == null || adminToken == null || adminToken.isBlank()) {
                return writeError(HttpStatus.FORBIDDEN, "reshard disabled");
            }
            String token = agg.headers().get("X-Admin-Token");
            if (token == null || !token.equals(adminToken)) {
                return writeError(HttpStatus.FORBIDDEN, "invalid admin token");
            }
            try {
                JsonNode body = MAPPER.readTree(agg.content().toInputStream());
                if (!body.has("shardCount") || body.get("shardCount").asInt(0) < 1) {
                    return writeError(HttpStatus.BAD_REQUEST, "shardCount must be an integer >= 1");
                }
                int newShardCount = body.get("shardCount").asInt();
                ShardTopologyStore.Snapshot s =
                        topologyStore.publishReshard(newShardCount, clockMs.getAsLong(), dualReadWindowMs);
                return writeJson(HttpStatus.OK, Map.of(
                        "version", s.version(),
                        "shardCount", s.shardCount(),
                        "prevVersion", s.prevVersion(),
                        "prevExpiresAtMs", s.prevExpiresAtMs()
                ));
            } catch (Exception e) {
                log.error("Unexpected error in reshard", e);
                return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
            }
        }, ctx.blockingTaskExecutor()));
    }
```

Add the import for `com.recsys.infrastructure.redis.sharding.ShardTopologyStore`.

- [ ] **Step 4: Wire `OnlinePredictionServer`** to pass the reshard deps

Update the service registration:

```java
            String adminToken = System.getenv("SHARD_ADMIN_TOKEN");
            long dualReadWindowMs = readIntEnv("SHARDED_RECORD_MAX_TTL_SECONDS", 86_400) * 1000L;
            // ...
            .service(Route.builder().pathPrefix("/shards/").build(),
                    new ShardedRecordService(shardedRecordStore, topologyStore, adminToken,
                            dualReadWindowMs, System::currentTimeMillis));
```

> Auth note: the admin guard is a shared-secret header (`X-Admin-Token` == `SHARD_ADMIN_TOKEN`); when the env var is unset the endpoint is disabled (403). This is the minimal guard that fits the online server (which has no existing auth filter on `/shards/`). If the reviewer/operator wants a stronger mechanism, surface it — do not silently expand scope.

- [ ] **Step 5: Run the reshard test + full suite**

Run: `mvn -q test -Dtest=ShardedRecordServiceReshardTest`
Expected: PASS.
Run: `mvn -q test`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: token-guarded POST /shards/topology reshard endpoint

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Docs + end-to-end verification

**Files:**
- Modify: `.claude/CLAUDE.md` (Redis Conventions + a short "Shard topology" note)

- [ ] **Step 1: Document the new conventions in CLAUDE.md**

Under "Redis Conventions", add:

```
- `shard:topology` — authoritative versioned shard-topology snapshot (JSON); instances refresh every 30s
- `sr:rec:{shard}:{seq}` / `sr:dev:{shard}:{id}` / `sr:stream:{shard}` / `sr:seq:{shard}` — generation 1 (unversioned)
- `sr:g{version}:rec:…` etc. — generation ≥2 keys after a reshard; reads dual-read the previous generation for one max-TTL window
```

Add one line near the online-path description noting reshard is operator-triggered via `POST /shards/topology` (header `X-Admin-Token`).

- [ ] **Step 2: Final verification**

```bash
grep -rn "0xcbf29ce484222325L\|0x100000001b3L" src/main --include="*.java"   # only Fnv1a.java
mvn -q package -DskipTests                                                    # BUILD SUCCESS
mvn -q test                                                                   # BUILD SUCCESS
mvn -q test -Dgroups=docker -Dtest='ShardTopology*,ShardedRecordStore*,ShardedRecordService*'  # if Docker available
```

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "docs: document versioned shard topology + reshard endpoint

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**1. Spec coverage:**
- §1 dedup FNV-1a → Task 1. §3 `Fnv1a` in `infrastructure/redis/sharding` → Task 1. ✓
- §3 `ShardTopology` immutable snapshot → Task 2; `Generations` prefix scheme (v1 unversioned, v≥2 `g{}:`) → Task 2 (refinement of the spec's "g1 + legacy fallback": v1 *is* the legacy keyspace, eliminating a separate fallback path — call out at PR). ✓
- §3 `ShardTopologyStore` (`shard:topology` JSON, SETNX bootstrap, atomic reshard) → Task 3. ✓
- §3 `ShardTopologyProvider` (volatile current+previous, 30s refresh, last-good on failure) → Task 4. ✓
- §3 generation keys + provider wiring → Task 5. §3 dual-read window = max TTL → Tasks 6 (read) + 7 (window plumbed from `SHARDED_RECORD_MAX_TTL_SECONDS`). ✓
- §3 reshard via guarded `POST /shards/topology` + reusable `publishReshard` → Task 7. ✓
- §5 golden FNV-1a, within-version stability, topology versioning, dual-read migration, last-good → Tasks 1,3,4,6. ✓
- §6 never-change constants/vnode format/KEYSPACE → enforced by Task 1 golden test + v1-unversioned keys. ✓
- §4 refresh interval 30s, window=max TTL, gen scheme, first-window-only legacy → Tasks 4,5,6,7. ✓ (Legacy handled by "v1 == legacy keys," so no separate first-window legacy reader is needed; noted.)

**2. Placeholder scan:** the only intentional fill-ins are the three golden FNV-1a literals in Task 1 (captured in Step 1 before use) — every other step has complete code/commands. No TBD/TODO.

**3. Type consistency:** `Fnv1a.hash` (Tasks 1,2 via ring). `ShardTopology(int,int,int,long)` ctor + `version()/shardCount()/vnodes()/createdAtMs()/ring()/shardFor()` used identically in Tasks 2,4,5,6. `Generations.keyPrefix(int)` used in Tasks 2,3(via store? no — store doesn't key records),5. `ShardTopologyStore.Snapshot(version,shardCount,vnodes,createdAtMs,prevVersion,prevShardCount,prevExpiresAtMs)` used in Tasks 3,4,7. `ShardTopologyProvider` ctor + `start/refresh/current/previousIfActive/stop/fixed/fixedAtVersion/fixedWithPrevious` consistent across Tasks 4,5,6. `ShardedRecordStore` version-scoped `recKey/devKey/streamKey(int,int,long|String)` consistent Tasks 5,6. `SequenceGenerator.next(int,int)`/`next(int)` consistent Tasks 5. `publishReshard(int,long,long)` consistent Tasks 3,7.

## Deferred (unchanged from spec §"Deferred")
- Algorithm swap (jump/rendezvous), instance-affinity routing, automatic/elastic resharding — out of scope; would need their own spec + benchmark.
