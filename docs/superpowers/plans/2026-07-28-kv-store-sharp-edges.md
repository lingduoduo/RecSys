# KV-Store Sharp Edges Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair four defects in the Redis key-value layer where the code and its documented intent have drifted apart.

**Architecture:** Four independent, stacked pull requests, each branched off the previous. PR 1 deletes unreachable top-K sharding code. PR 2 bounds four unbounded in-memory maps with Caffeine. PR 3 makes the sequence-counter guard generation-aware and actually wires it up, off the boot thread. PR 4 corrects misleading javadoc. Every PR that adds a test also adds it to the `resilience` Maven profile, because that profile is the PR gate.

**Tech Stack:** Java 17, Maven, Lettuce (Redis), Caffeine, JUnit 5, AssertJ, Mockito, Armeria.

**Spec:** `docs/superpowers/specs/2026-07-28-kv-store-sharp-edges-design.md`

## Global Constraints

- **JDK 17 is required.** Build with `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`. On JDK 25 a clean compile fails on two pre-existing files unrelated to this work.
- **Never merge to `main` directly.** Every change lands through a pull request.
- **The PR gate runs only the `resilience` Maven profile** (`.github/workflows/resilience-pr.yml`), which is an allow-list in `pom.xml:330-345` — roughly 149 of 1380 tests. A test not listed there does not block a merge.
- **The `resilience` profile sets `<excludedGroups>load,docker</excludedGroups>`.** A test annotated `@Tag("docker")` is excluded even if its path matches an `<include>`. **Every test written by this plan must be a plain unit test with mocked collaborators — never `@Tag("docker")`, never Testcontainers.**
- **Do not renumber `##` headings in `docs/system_design/`.** Other documents deep-link to them.
- **Every document under `docs/system_design/` and `docs/runbooks/` must be linked from `README.md`** or `DocumentationIndexTest` fails the build. This plan modifies existing documents only, so no new README entries are needed. `docs/superpowers/` is exempt from that test.
- **Commit message trailer**, on every commit:
  `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`

## File Structure

**PR 1 — `fix/topk-remove-vestigial-sharding`**

| File | Responsibility |
|---|---|
| `src/main/java/com/recsys/infrastructure/redis/ShardedTopKStore.java` | Modify: delete the shard fan-out write path, the shard read branch, and the `shardCount` constructor parameters |
| `src/test/java/com/recsys/infrastructure/redis/ShardedTopKStoreTest.java` | Modify: drop shard-specific tests, retarget the rest at the canonical and legacy paths |
| `pom.xml` | Modify: add the two top-K test classes to the `resilience` allow-list |
| `docs/system_design/03_DB_Scaling_Sharding.md` | Modify: rewrite §2 to describe the canonical-snapshot read |
| `docs/system_design/14_Partitioning.md` | Modify: correct the top-K paragraphs |
| `docs/superpowers/specs/2026-07-28-kv-store-sharp-edges-design.md` | Modify: record the one deviation (keeping `legacyFallbackFetches`) |

**PR 2 — `fix/cache-bound-unbounded-maps`**

| File | Responsibility |
|---|---|
| `src/main/java/com/recsys/infrastructure/cache/LogicalExpiryEmbeddingCache.java` | Modify: bound `cache`, `nullSentinels`, `refreshing` |
| `src/main/java/com/recsys/infrastructure/cache/MultiLevelEmbeddingCache.java` | Modify: bound `nullSentinels` |
| `src/test/java/com/recsys/infrastructure/cache/LogicalExpiryEmbeddingCacheTest.java` | Modify: add bounding regression tests |
| `src/test/java/com/recsys/infrastructure/cache/MultiLevelEmbeddingCacheTest.java` | Modify: add a bounding regression test |
| `pom.xml` | Modify: add the two cache test classes to the allow-list |
| `docs/system_design/02_Caching.md` | Modify: note that every embedding-cache tier is now bounded |

**PR 3 — `fix/sharding-generation-aware-seq-guard`**

| File | Responsibility |
|---|---|
| `src/main/java/com/recsys/infrastructure/redis/sharding/SequenceGenerator.java` | Modify: generation-aware keys, wall-clock budget, drop the unused parameter |
| `src/test/java/com/recsys/infrastructure/redis/sharding/SequenceGeneratorGenerationTest.java` | Create: non-docker unit tests for generation keying and budget behavior |
| `src/test/java/com/recsys/infrastructure/redis/sharding/SequenceGeneratorTest.java` | Modify: update the one call site to the new signature |
| `src/main/java/com/recsys/api/online/OnlinePredictionServer.java` | Modify: run the repair in the background at startup |
| `pom.xml` | Modify: add the new test class to the allow-list |
| `docs/system_design/14_Partitioning.md` | Modify: document the startup repair and its env knobs |
| `.claude/CLAUDE.md` | Modify: document the two new env vars |

**PR 4 — `docs/replica-selection-javadoc`**

| File | Responsibility |
|---|---|
| `src/main/java/com/recsys/infrastructure/redis/RedisReadReplicaRouter.java` | Modify: comments only |

---

## PR 1 — Remove the vestigial top-K shard fan-out

### Task 1: Delete the unreachable shard machinery from ShardedTopKStore

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/redis/ShardedTopKStore.java`
- Test: `src/test/java/com/recsys/infrastructure/redis/ShardedTopKStoreTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: the trimmed constructor set that Task 2's documentation describes.
  - `public ShardedTopKStore(RedisExecutor exec, String keyPrefix)`
  - `public ShardedTopKStore(RedisExecutor writeExec, RedisExecutor readExec, String keyPrefix)`
  - `ShardedTopKStore(RedisExecutor writeExec, RedisExecutor readExec, String keyPrefix, long cacheTtlMs, HotKeyDetector hotKeyDetector)` — package-private
  - `ShardedTopKStore(RedisExecutor writeExec, RedisExecutor readExec, String keyPrefix, long cacheTtlMs, long staleTtlMs, HotKeyDetector hotKeyDetector)` — package-private

  The `int shardCount` parameter is gone from both package-private constructors. The public constructors are unchanged in signature, so the three production call sites (`RecSysServer:92`, `OnlinePredictionServer:115`, `ModelRuntimeProvider:162`) need no edit.

  Removed members: `seedAllShards`, `shardKey`, `DEFAULT_SHARD_COUNT`, `shardCount`.
  Retained member: `legacyFallbackFetches()` — see Step 1 note.

**Background — why this code is unreachable.** `seedAllShards` is the only writer of `topk:<window>:s0..sN`, and its only caller in the repository is this test class. Since commit `01870d2` the read path evaluates `READ_CANONICAL_SNAPSHOT` against `topk:{window}:value` first; the Flink sink writes that key, so the canonical branch always wins in production and the shard branch is dead.

- [ ] **Step 1: Record the one deviation from the spec**

The spec listed `legacyFallbackFetches` for removal. Keep it instead. The legacy `topk:<window>` fallback survives this change (it is reachable on a cold Redis before Flink's first snapshot lands), so the counter that reports how often we serve from it is still meaningful — arguably more so, since it now signals "the canonical snapshot is missing" with no shard layer in between.

Append this to the "PR 1" section of `docs/superpowers/specs/2026-07-28-kv-store-sharp-edges-design.md`, immediately after the paragraph beginning "**Also kept — the legacy**":

```markdown
**Deviation from the original scoping — `legacyFallbackFetches` is kept.** It was
listed for removal, but since the legacy fallback branch survives, the counter that
reports how often it serves traffic remains meaningful. With no shard layer between
the canonical read and the fallback, a non-zero value now means exactly one thing:
the canonical snapshot is absent.
```

- [ ] **Step 2: Write the failing tests**

Replace the three shard-oriented tests in `ShardedTopKStoreTest.java`. Delete `shardKey_producesExpectedPattern` (lines 130-135), `getTopKIds_readsFromOneOfNShards` (lines 202-214), and the whole "Write path: fan-out to all shards" section — `seedAllShards_writesToEveryShardKey`, `seedAllShards_invalidatesLocalCache`, `seedAllShards_noopsForNullOrEmptyScores` (lines 228-271).

Then replace `canonicalMarkerBeatsPopulatedStaleShard`, `absentCanonicalMarkerFallsBackToShardBeforeLegacy`, and `getTopKIds_fallsBackToLegacyKeyWhenShardIsEmpty` with these three, and add the new no-shard-key assertion:

```java
    @Test
    void canonicalMarkerBeatsPopulatedLegacyKey() {
        when(cmd.eval(any(String.class), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class)))
                .thenReturn(List.of("canonical", "fresh"));
        when(cmd.zrevrange(eq("topk:last_hour"), anyLong(), anyLong()))
                .thenReturn(List.of("legacy-stale"));
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 0L, new HotKeyDetector());

        assertThat(store.getTopKIds("last_hour", 1)).containsExactly("fresh");
        verify(cmd, never()).zrevrange(eq("topk:last_hour"), anyLong(), anyLong());
    }

    @Test
    void absentCanonicalMarkerFallsBackToLegacyKey() {
        when(cmd.eval(any(String.class), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class)))
                .thenReturn(List.of("absent"));
        when(cmd.zrevrange("topk:last_hour", 0, 99)).thenReturn(List.of("legacy"));
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 0L, new HotKeyDetector());

        assertThat(store.getTopKIds("last_hour", 1)).containsExactly("legacy");
        assertThat(store.legacyFallbackFetches()).isEqualTo(1L);
    }

    @Test
    void readPathNeverTouchesAShardKey() {
        when(cmd.eval(any(String.class), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class)))
                .thenReturn(List.of("absent"));
        when(cmd.zrevrange("topk:last_hour", 0, 99)).thenReturn(List.of("legacy"));
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 0L, new HotKeyDetector());

        for (int i = 0; i < 20; i++) store.getTopKIds("last_hour", 1);

        // The sharded keyspace is gone: no read may address topk:<window>:sN.
        verify(cmd, never()).zrevrange(
                argThat((String key) -> key.matches("topk:last_hour:s\\d+")), anyLong(), anyLong());
    }
```

Now update every remaining `new ShardedTopKStore(...)` call in the file to drop the shard-count argument. The five-argument form `(exec, exec, "topk:", N, ttl, detector)` becomes `(exec, exec, "topk:", ttl, detector)`, and the six-argument form `(exec, exec, "topk:", N, ttl, staleTtl, detector)` becomes `(exec, exec, "topk:", ttl, staleTtl, detector)`. The affected tests are `primaryReadBypassesHotCacheAndReplicaReadPath`, `canonicalMarkerMakesEmptySnapshotAuthoritativeAndCacheable`, `primaryReadHonorsEmptyCanonicalSnapshotWithoutLegacyFallback`, `getTopKIds_servesFromLocalCacheWithinTtl`, `getTopKIds_refetchesAfterCacheTtlExpires`, `getTopKIds_servesBoundedStaleValueWhenRedisFails`, `getTopKIds_slicesResultToRequestedK`, `getTopKIds_returnsEmptyForNonPositiveK`, `localHitRate_isZeroOnColdStart`, and `localHitRate_improvesAfterFirstCacheFill`.

Those tests stub `cmd.zrevrange(any(String.class), ...)` and leave `cmd.eval(...)` unstubbed. An unstubbed Mockito mock returns `null`, `readCanonicalSnapshot` treats `null` as absent, and the read falls through to the legacy key — which `any(String.class)` still matches. They keep passing unchanged apart from the constructor arity.

Finally, remove the now-unused imports: `org.mockito.ArgumentCaptor`, `java.util.Map`, and `io.lettuce.core.ScoredValue` if no remaining test references them. Leave `atLeast` imported only if still used; otherwise remove it too.

- [ ] **Step 3: Run the tests to verify they fail**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=ShardedTopKStoreTest
```

Expected: compilation failure — the constructors still take `int shardCount`, and `seedAllShards`/`shardKey` no longer have callers in the test but still exist in main. This is the correct failing state; the compiler is the assertion.

- [ ] **Step 4: Implement — trim ShardedTopKStore**

Rewrite the class javadoc. It currently claims sharding that does not happen:

```java
/**
 * Trending top-K reads, served from a short-lived JVM cache in front of Redis.
 *
 * Problem: a handful of trending-window keys ({@code topk:last_hour}, {@code topk:last_day},
 * etc.) are read on every recommendation request across all JVM instances.
 *
 * What protects them, in order:
 *   1. Local JVM cache — ConcurrentHashMap keyed by window, 2-second fresh TTL, which
 *      absorbs the vast majority of reads.
 *   2. Per-window singleflight — on a cache miss only the first thread in this JVM
 *      fetches; the rest wait on its result.
 *   3. Serve-stale — a Redis failure within the 60-second stale window returns the last
 *      known value rather than propagating the error.
 *
 * Read path on a cache miss: evaluate {@link #READ_CANONICAL_SNAPSHOT} against the
 * canonical snapshot written atomically by the Flink job ({@code topk:{window}:value},
 * guarded by {@code topk:{window}:version}). If no canonical snapshot exists — a cold
 * Redis before Flink's first write — fall back to the unversioned {@code topk:<window>}
 * key and count it in {@link #legacyFallbackFetches()}.
 *
 * <p>This class previously fanned each window out to N identical replica keys
 * ({@code topk:<window>:s0..sN}) to spread hot-key read QPS. That machinery was removed
 * on 2026-07-28: nothing had written those keys since the canonical snapshot path landed
 * in {@code 01870d2}, so every read already resolved via the canonical key. See
 * {@code docs/superpowers/specs/2026-07-28-kv-store-sharp-edges-design.md}.
 *
 * Hit-rate metrics and {@link HotKeyDetector} integration expose which windows are
 * hottest and how effectively the local cache is absorbing load.
 */
```

Delete the `shardCount` field and `DEFAULT_SHARD_COUNT` constant. The remaining constructors:

```java
    /**
     * Single-executor constructor — reads and writes use the same Redis connection.
     * This is what all three production call sites use.
     */
    public ShardedTopKStore(RedisExecutor exec, String keyPrefix) {
        this(exec, exec, keyPrefix,
                readLongEnv("ONLINE_TOPK_CACHE_TTL_MS", DEFAULT_CACHE_TTL_MS),
                readLongEnv("ONLINE_TOPK_STALE_TTL_MS", DEFAULT_STALE_TTL_MS), new HotKeyDetector());
    }

    /**
     * AZ-aware constructor — primary-only reads ({@link #getTopKIdsPrimary}) go to
     * {@code writeExec}, cached reads go to {@code readExec} (an AZ-local replica).
     */
    public ShardedTopKStore(RedisExecutor writeExec, RedisExecutor readExec, String keyPrefix) {
        this(writeExec, readExec, keyPrefix,
                readLongEnv("ONLINE_TOPK_CACHE_TTL_MS", DEFAULT_CACHE_TTL_MS),
                readLongEnv("ONLINE_TOPK_STALE_TTL_MS", DEFAULT_STALE_TTL_MS), new HotKeyDetector());
    }

    ShardedTopKStore(RedisExecutor writeExec, RedisExecutor readExec, String keyPrefix,
                     long cacheTtlMs, HotKeyDetector hotKeyDetector) {
        this(writeExec, readExec, keyPrefix, cacheTtlMs, DEFAULT_STALE_TTL_MS, hotKeyDetector);
    }

    ShardedTopKStore(RedisExecutor writeExec, RedisExecutor readExec, String keyPrefix,
                     long cacheTtlMs, long staleTtlMs, HotKeyDetector hotKeyDetector) {
        this.writeExec      = writeExec;
        this.readExec       = readExec;
        this.keyPrefix      = keyPrefix;
        this.cacheTtlMs     = Math.max(0L, cacheTtlMs);
        this.staleTtlMs     = Math.max(this.cacheTtlMs, staleTtlMs);
        this.hotKeyDetector = hotKeyDetector;
    }
```

Rename `fetchFromRandomShard` to `fetchFromRedis` and drop the shard branch. Update both call sites in `getTopKIds` (the singleflight leader path and the fail-open path):

```java
    private CachedIds fetchFromRedis(String window, int k, long now) {
        int fetchSize = Math.max(k, MAX_FULL_CACHE_SIZE);

        return readExec.executeRead(c -> {
            CanonicalSnapshot snapshot = readCanonicalSnapshot(c, window, fetchSize);
            List<String> ids = snapshot.ids;
            if (!snapshot.present) {
                // Cold Redis, before the Flink job's first canonical write.
                List<String> oldIds = c.zrevrange(legacyKey(window), 0, fetchSize - 1);
                ids = oldIds == null ? List.of() : List.copyOf(oldIds);
                if (!ids.isEmpty()) legacyFallbackFetches.incrementAndGet();
            }
            redisFetches.incrementAndGet();
            CachedIds result = new CachedIds(ids, now + cacheTtlMs, now + staleTtlMs);
            if (staleTtlMs > 0L) hotCache.put(window, result);
            return result;
        });
    }
```

Delete `seedAllShards` entirely, along with `shardKey`. With `seedAllShards` gone, `writeExec` is used only by `getTopKIdsPrimary` — keep the field.

Remove the imports that only the deleted code used: `io.lettuce.core.LettuceFutures`, `io.lettuce.core.RedisFuture`, `java.time.Duration`, `java.util.ArrayList`, `java.util.Map`. Keep `java.util.List`. `org.slf4j.Logger`/`LoggerFactory` and the `log` field were used only by `seedAllShards`'s catch block — remove them too.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='ShardedTopKStoreTest,ShardedTopKStoreTtlConfigTest'
```

Expected: PASS. `ShardedTopKStoreTtlConfigTest` touches only `readLongEnv` and `DEFAULT_CACHE_TTL_MS`, both retained, so it should pass untouched.

- [ ] **Step 6: Verify nothing else referenced the deleted members**

```bash
grep -rn "seedAllShards\|shardKey\|DEFAULT_SHARD_COUNT" src/ streaming/ scripts/
```

Expected: no output. If `streaming/` or `scripts/` produces a hit, stop and report it — the premise that nothing writes the shard keys would be wrong.

- [ ] **Step 7: Compile the whole project**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q package -DskipTests
```

Expected: BUILD SUCCESS. This proves the three production call sites still compile against the unchanged public constructors.

- [ ] **Step 8: Commit**

```bash
git checkout -b fix/topk-remove-vestigial-sharding
git add src/main/java/com/recsys/infrastructure/redis/ShardedTopKStore.java \
        src/test/java/com/recsys/infrastructure/redis/ShardedTopKStoreTest.java \
        docs/superpowers/specs/2026-07-28-kv-store-sharp-edges-design.md
git commit -m "$(cat <<'EOF'
fix(topk): remove the vestigial shard fan-out

ShardedTopKStore documented itself as spreading trending-key read QPS across
N replica keys. Nothing wrote those keys: seedAllShards had exactly one caller
in the repository, a test. Since 01870d2 the read path evaluates the canonical
snapshot written by the Flink sink and returns, so the shard branch was
unreachable in production.

Delete seedAllShards, shardKey, DEFAULT_SHARD_COUNT and the shard read branch.
Keep what actually protects the key: the 2s/60s JVM cache, singleflight,
serve-stale, the canonical Lua read, and the legacy fallback for a cold Redis.

legacyFallbackFetches is retained rather than removed as originally scoped —
with no shard layer in between, a non-zero value now means precisely that the
canonical snapshot is absent.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 2: Gate the top-K tests and correct the system-design docs

**Files:**
- Modify: `pom.xml:330-345`
- Modify: `docs/system_design/03_DB_Scaling_Sharding.md`
- Modify: `docs/system_design/14_Partitioning.md`

**Interfaces:**
- Consumes: the trimmed `ShardedTopKStore` API from Task 1.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Add the top-K tests to the PR gate**

In `pom.xml`, inside the `resilience` profile's `<includes>` block, add these two lines immediately before the `<!-- Not a resilience test. -->` comment:

```xml
                <include>**/redis/ShardedTopKStoreTest.java</include>
                <include>**/redis/ShardedTopKStoreTtlConfigTest.java</include>
```

Then extend the existing explanatory comment so the next reader understands why non-resilience tests keep appearing here. Replace the comment block with:

```xml
                <!-- Not resilience tests. This profile is what the PR gate runs
                     (.github/workflows/resilience-pr.yml), so it is the only place a
                     deterministic check can block a merge. Everything listed below is
                     pure unit-level — no Redis, no Docker, no timing — so it is safe
                     here. Note that @Tag("docker") tests are excluded by
                     <excludedGroups> regardless of any <include> above. -->
```

- [ ] **Step 2: Verify the gate actually runs them**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience 2>&1 | grep -E "ShardedTopKStore|Tests run:.*Failures" | tail -5
```

Expected: both `ShardedTopKStoreTest` and `ShardedTopKStoreTtlConfigTest` appear in the output and pass. If they do not appear, the `<include>` pattern is wrong — check it against the test's path relative to the test-source root.

- [ ] **Step 3: Rewrite the top-K section of 03_DB_Scaling_Sharding.md**

Read the file first and locate `## 2. \`ShardedTopKStore\` — sharded trending` (near line 68) and the summary-table row near line 24.

The table row at line 24 currently claims the store keeps "**replica** — N identical copies" to "spread hot-key *read* QPS". Replace that row's last two cells so it reads: `single canonical snapshot per window` and `absorbed by a 2 s JVM cache + singleflight`.

Under the `## 2.` heading — **keep the heading text and number exactly as they are**, since other documents deep-link to it — replace the body with a description of the real path: the Flink sink writes `topk:{window}:value` and `topk:{window}:version` atomically; readers evaluate a Lua snapshot read against those keys; a cold Redis falls back to the unversioned `topk:<window>`; the hot key is absorbed by the 2 s fresh / 60 s stale JVM cache and per-window singleflight, not by key replication. Delete the bullet at line 79 describing `seedAllShards` fanning a pipelined `ZADD` to every shard.

Add a short note recording the change, so a reader who remembers the old design is not confused:

```markdown
> **Changed 2026-07-28 — the shard fan-out was removed.** This section previously
> described N identical replica keys per window. Nothing had written them since the
> canonical snapshot path landed, so every read already resolved through
> `topk:{window}:value`. The dead machinery was deleted; see
> `docs/superpowers/specs/2026-07-28-kv-store-sharp-edges-design.md`.
```

At line 149 the test list names `ShardedTopKStoreTest` and `ShardedTopKStoreTtlConfigTest` — leave it, both still exist.

- [ ] **Step 4: Correct 14_Partitioning.md**

Read the file and fix three places:

- Line ~45: the sentence grouping `ShardedRecordStore` / `ShardedTopKStore` as each holding a single shard dimension. `ShardedTopKStore` no longer partitions anything — reword so only `ShardedRecordStore` is described as sharded, and mention `ShardedTopKStore` as a cached single-key read.
- Line ~139 and ~153: the paragraph describing writes via `seedAllShards` "from the Flink/sync jobs". Replace with the canonical-snapshot description. Keep the fail-open sentence about the timeout, which is still accurate.
- Line ~248: the test list entry is still correct — leave it.

- [ ] **Step 5: Check the remaining references for invalidated claims**

```bash
grep -n "ShardedTopKStore" docs/system_design/02_Caching.md docs/system_design/10_MicroServices.md \
    docs/system_design/15_Eventual_Consistency.md docs/system_design/17_Scalability.md
```

Read each hit in context. `02_Caching.md:95,128` describe the 2 s / 60 s fresh+stale+singleflight lifecycle — still accurate, leave them. `15_Eventual_Consistency.md:100,149` describe serve-stale and `getTopKIdsPrimary` — still accurate. `10_MicroServices.md:111` lists the class as shared infrastructure — accurate. `17_Scalability.md:178` mentions "the few trending windows" — read it and correct only if it asserts sharding.

- [ ] **Step 6: Run the documentation index test**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=DocumentationIndexTest
```

Expected: PASS. No documents were added or renamed, so the README map is unaffected — this confirms no link was broken.

- [ ] **Step 7: Commit and open the PR**

```bash
git add pom.xml docs/system_design/03_DB_Scaling_Sharding.md docs/system_design/14_Partitioning.md
git commit -m "$(cat <<'EOF'
docs(topk): describe the canonical read path, and gate its tests

The system-design docs told an operator that hot-key sharding protected the
trending keys. It did not — nothing had written the shard keys since the
canonical snapshot path landed. Rewrite 03 section 2 and the 14 top-K
paragraphs to describe what actually runs.

Also add both top-K test classes to the resilience profile. That profile is
what the PR gate executes, so a test outside it cannot block a merge.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
git push -u origin fix/topk-remove-vestigial-sharding
gh pr create --base main --title "fix(topk): remove the vestigial shard fan-out" --body "$(cat <<'EOF'
## Summary

`ShardedTopKStore` documented itself as spreading trending-key read QPS across N
replica keys (`topk:<window>:s0..sN`). **Nothing wrote those keys.** `seedAllShards`
had exactly one caller in the repository — a test.

Since `01870d2` ("honor canonical top-k snapshots") the read path evaluates a Lua
snapshot read against `topk:{window}:value`, which the Flink sink writes atomically.
That branch always hits, so the shard read and its legacy sibling were unreachable.

## What changed

- Deleted `seedAllShards`, `shardKey`, `DEFAULT_SHARD_COUNT`, the `shardCount` field
  and constructor parameters, and the shard read branch.
- Kept what actually protects the key: the 2 s fresh / 60 s stale JVM cache,
  per-window singleflight, serve-stale-on-error, the canonical Lua read, and the
  legacy `topk:<window>` fallback for a cold Redis before Flink's first write.
- Kept `legacyFallbackFetches` — with no shard layer in between, a non-zero value
  now means precisely that the canonical snapshot is absent.
- Rewrote `03_DB_Scaling_Sharding.md` §2 and the `14_Partitioning.md` top-K
  paragraphs, which described the fan-out as live.
- Added both top-K test classes to the `resilience` profile, which is what the PR
  gate runs.

The three production call sites use the unchanged public constructors and needed
no edit.

## Not in this PR

The class keeps the name `ShardedTopKStore`, which is now a slight misnomer.
Renaming touches three servers, five documents, and two test classes for no
behavioral gain, and would bury this diff. Filed as a follow-up.

## Test plan

- `mvn test -Dtest='ShardedTopKStoreTest,ShardedTopKStoreTtlConfigTest'` — passes
- `mvn test -Presilience` — both classes now appear in the gate's output
- `mvn package -DskipTests` — the three production call sites still compile
- New test `readPathNeverTouchesAShardKey` asserts no read addresses `topk:<w>:sN`

Spec: `docs/superpowers/specs/2026-07-28-kv-store-sharp-edges-design.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## PR 2 — Bound the unbounded cache maps

### Task 3: Bound the three maps in LogicalExpiryEmbeddingCache

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/cache/LogicalExpiryEmbeddingCache.java`
- Test: `src/test/java/com/recsys/infrastructure/cache/LogicalExpiryEmbeddingCacheTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks. Branch from PR 1's head.
- Produces: the env-var name `LOGICAL_EXPIRY_CACHE_MAX_ENTRIES`, which Task 4's documentation references. All existing package-private test accessors keep their signatures: `int cacheSize()`, `boolean isRefreshing(int)`, `int inflightColdMisses()`, `boolean hasNullSentinel(int)`.

**Background.** `cache` holds full `float[]` embeddings and nothing evicts them — the largest heap exposure of the four. `nullSentinels` and `refreshing` are smaller but equally unbounded. `LocalEmbeddingCache` in the same package already models the target shape exactly (`LocalEmbeddingCache.java:66-71`): a Caffeine cache with `maximumSize`, `expireAfterWrite`, and `.executor(Runnable::run)` for deterministic size in tests.

- [ ] **Step 1: Write the failing tests**

Add these two tests to `LogicalExpiryEmbeddingCacheTest.java`, in the negative-cache section near the other `hasNullSentinel` tests. They use the file's existing `SYNC_EXECUTOR` constant and its private `TrackingStore` fixture (defined at line 330), whose `data` map returns `null` for any id never `put`.

```java
    @Test
    void cache_staysBoundedUnderFarMoreDistinctIdsThanTheCap() {
        // Every id resolves, so nothing limits growth except the cap itself.
        var backing = new TrackingStore();
        for (int id = 0; id < 10_000; id++) backing.put(id, new float[]{id});
        var cache = new LogicalExpiryEmbeddingCache(backing, 60_000L, 60_000L, SYNC_EXECUTOR, 100);

        for (int id = 0; id < 10_000; id++) cache.getEmbedding(id);

        assertThat(cache.cacheSize()).isLessThanOrEqualTo(100);
    }

    @Test
    void nullSentinels_stayBoundedUnderFarMoreAbsentIdsThanTheCap() {
        // Nothing is put into the store, so every lookup records a negative-cache entry.
        var backing = new TrackingStore();
        var cache = new LogicalExpiryEmbeddingCache(backing, 60_000L, 60_000L, SYNC_EXECUTOR, 100);

        for (int id = 0; id < 10_000; id++) cache.getEmbedding(id);

        assertThat(cache.nullSentinelSize()).isLessThanOrEqualTo(100);
    }
```

Both need two things that do not exist yet: the five-argument constructor taking an explicit max-entries value, and a `nullSentinelSize()` accessor. Step 3 adds them.

`TrackingStore.getEmbedding` is `synchronized` and increments a counter, which is fine here — these tests are single-threaded.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=LogicalExpiryEmbeddingCacheTest
```

Expected: compilation failure — no five-argument constructor, no `nullSentinelSize()`.

- [ ] **Step 3: Implement — convert the three maps to Caffeine**

Add the imports:

```java
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
```

Replace the three field declarations:

```java
    static final int DEFAULT_MAX_ENTRIES = 10_000;

    // Bounded so a sweep over many distinct or absent IDs cannot grow the heap without limit.
    // Caffeine's expireAfterWrite governs *eviction*; the softExpiresAtMs stamped into each
    // LogicalEntry governs the *fresh-vs-stale* decision. Those are different questions —
    // the hard TTL here is deliberately longer than the soft TTL so an entry is still
    // present, and servable as stale, after its soft expiry passes.
    private final Cache<Integer, LogicalEntry> cache;
    private final Cache<Integer, Boolean> nullSentinels;
    private final Cache<Integer, Boolean> refreshing;
```

Note the type change on `nullSentinels`: with `expireAfterWrite` doing the expiry, membership alone means "recently confirmed absent", so the stored value becomes `Boolean` instead of an expiry timestamp. This mirrors `LocalEmbeddingCache`.

Replace the constructor chain. The public constructor and the two existing package-private ones delegate to a new one that takes `maxEntries`:

```java
    public LogicalExpiryEmbeddingCache(EmbeddingStore backingStore, long softTtlSeconds) {
        this(backingStore, softTtlSeconds * 1_000L, DEFAULT_NULL_SENTINEL_TTL_MS,
                ForkJoinPool.commonPool(), readIntEnv("LOGICAL_EXPIRY_CACHE_MAX_ENTRIES", DEFAULT_MAX_ENTRIES));
    }

    // softTtlMs is in milliseconds — allows sub-second TTLs for testing.
    LogicalExpiryEmbeddingCache(EmbeddingStore backingStore, long softTtlMs, Executor refreshExecutor) {
        this(backingStore, softTtlMs, DEFAULT_NULL_SENTINEL_TTL_MS, refreshExecutor, DEFAULT_MAX_ENTRIES);
    }

    LogicalExpiryEmbeddingCache(EmbeddingStore backingStore, long softTtlMs,
                                long nullSentinelTtlMs, Executor refreshExecutor) {
        this(backingStore, softTtlMs, nullSentinelTtlMs, refreshExecutor, DEFAULT_MAX_ENTRIES);
    }

    LogicalExpiryEmbeddingCache(EmbeddingStore backingStore, long softTtlMs, long nullSentinelTtlMs,
                                Executor refreshExecutor, int maxEntries) {
        this.backingStore = backingStore;
        this.softTtlMs = Math.max(1L, softTtlMs);
        this.nullSentinelTtlMs = Math.max(1L, nullSentinelTtlMs);
        this.refreshExecutor = refreshExecutor;
        int cap = Math.max(1, maxEntries);
        // Hard TTL = 2x soft TTL: an entry must outlive its soft expiry to be servable as stale.
        this.cache = Caffeine.newBuilder()
                .maximumSize(cap)
                .expireAfterWrite(Duration.ofMillis(Math.max(1L, this.softTtlMs * 2L)))
                .executor(Runnable::run)
                .build();
        this.nullSentinels = Caffeine.newBuilder()
                .maximumSize(cap)
                .expireAfterWrite(Duration.ofMillis(this.nullSentinelTtlMs))
                .executor(Runnable::run)
                .build();
        // Bounded by the same cap — it can never hold more distinct IDs than the cache it
        // refreshes. The short hard TTL doubles as a backstop: a refresh task that dies
        // without reaching its finally block cannot wedge an ID out of refresh forever.
        this.refreshing = Caffeine.newBuilder()
                .maximumSize(cap)
                .expireAfterWrite(Duration.ofMinutes(1))
                .executor(Runnable::run)
                .build();
    }

    private static int readIntEnv(String envName, int defaultValue) {
        String raw = System.getenv(envName);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
```

Now update every call site of the three maps. `Cache` is not a `Map`, so reads become `getIfPresent` and the compound operations go through `asMap()`:

In `getEmbedding`:

```java
        LogicalEntry entry = cache.getIfPresent(id);
        ...
        if (nullSentinels.getIfPresent(id) != null) {
            return null;
        }
```

The `long now = System.currentTimeMillis();` at the top of the method is still needed for the soft-expiry comparison, but the sentinel branch no longer compares timestamps — Caffeine's expiry already did.

In `getEmbeddings`, the same two substitutions: `cache.getIfPresent(id)`, and the sentinel check becomes `if (nullSentinels.getIfPresent(id) != null) continue;`.

Writes: `cache.put(...)` and `nullSentinels.put(id, Boolean.TRUE)` — `put` exists on `Cache`, so only the sentinel's value changes. `nullSentinels.remove(id)` becomes `nullSentinels.invalidate(id)`.

In `scheduleRefresh`, the singleflight guard and the conditional eviction both need `asMap()`:

```java
    private void scheduleRefresh(int id) {
        // putIfAbsent is the singleflight guard: only one refresh task per ID.
        if (refreshing.asMap().putIfAbsent(id, Boolean.TRUE) != null) return;
        refreshExecutor.execute(() -> {
            try {
                LogicalEntry stale = cache.getIfPresent(id);        // capture before refresh
                float[] fresh = backingStore.getEmbedding(id);
                if (fresh != null) {
                    cache.put(id, new LogicalEntry(fresh, System.currentTimeMillis() + softTtlMs));
                } else if (stale != null && cache.asMap().remove(id, stale)) {
                    // Only evict when no concurrent write replaced the stale entry.
                    nullSentinels.put(id, Boolean.TRUE);
                }
            } catch (Exception e) {
                log.warn("Background refresh failed for embedding {}: {}", id, e.toString());
            } finally {
                refreshing.invalidate(id);
            }
        });
    }
```

`cache.asMap().remove(id, stale)` preserves the compare-and-remove semantics, because `LogicalEntry` is a record and its `equals` compares the `float[]` field by reference — the same identity check the old code relied on.

In `loadColdMiss`: `cache.getIfPresent(id)`, and `nullSentinels.put(id, Boolean.TRUE)`.

Finally the test accessors, plus the new one:

```java
    int cacheSize() { return (int) cache.estimatedSize(); }
    int nullSentinelSize() { return (int) nullSentinels.estimatedSize(); }
    boolean isRefreshing(int id) { return refreshing.getIfPresent(id) != null; }
    int inflightColdMisses() { return coldMissSingleFlight.inflightCount(); }
    boolean hasNullSentinel(int id) { return nullSentinels.getIfPresent(id) != null; }
```

`.executor(Runnable::run)` makes `estimatedSize()` exact after synchronous maintenance, which is what the existing `cacheSize()` assertions depend on.

Update the class javadoc's closing paragraph to record the bound:

```java
 * All three internal maps are bounded Caffeine caches: an unbounded sweep over distinct
 * or absent IDs cannot grow the heap without limit. {@code maximumSize} is configurable
 * via {@code LOGICAL_EXPIRY_CACHE_MAX_ENTRIES} (default 10 000).
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=LogicalExpiryEmbeddingCacheTest
```

Expected: PASS, including every pre-existing test. Pay particular attention to the serve-stale tests around lines 96-130 and the negative-cache tests at 166-260 — they prove the timestamp semantics survived the migration. If a stale-serving test now fails, the hard TTL is too short relative to the soft TTL; confirm the `softTtlMs * 2L` expiry.

- [ ] **Step 5: Commit**

```bash
git checkout -b fix/cache-bound-unbounded-maps
git add src/main/java/com/recsys/infrastructure/cache/LogicalExpiryEmbeddingCache.java \
        src/test/java/com/recsys/infrastructure/cache/LogicalExpiryEmbeddingCacheTest.java
git commit -m "$(cat <<'EOF'
fix(cache): bound the three maps in LogicalExpiryEmbeddingCache

cache, nullSentinels and refreshing were plain ConcurrentHashMaps with no size
cap and no eviction sweep. Entries expired logically — a timestamp compared on
read — but were removed only on a write or a completed refresh, so traffic over
many distinct or absent IDs grew them without limit. cache holds full float[]
embeddings, making it the largest exposure.

Convert all three to size-capped Caffeine caches with expireAfterWrite, matching
the LocalEmbeddingCache and OnlineFeatureStore precedents in this package.

Caffeine's expireAfterWrite governs eviction; the softExpiresAtMs stamped into
each entry still governs the fresh-vs-stale decision. The hard TTL is 2x the
soft TTL so an entry outlives its soft expiry and remains servable as stale.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 4: Bound MultiLevelEmbeddingCache, gate both tests, document

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/cache/MultiLevelEmbeddingCache.java`
- Test: `src/test/java/com/recsys/infrastructure/cache/MultiLevelEmbeddingCacheTest.java`
- Modify: `pom.xml`
- Modify: `docs/system_design/02_Caching.md`

**Interfaces:**
- Consumes: nothing from Task 3 at the code level; same branch.
- Produces: the env-var name `EMBEDDING_NULL_SENTINEL_MAX_ENTRIES`.

**Scope guard.** Only `nullSentinels` changes. `l1` is already capped at `l1Capacity`, and its arbitrary-entry eviction is deliberate — the javadoc says "this L1 is purpose-built for hot keys". Changing it would alter promotion semantics rather than fix unboundedness, and is explicitly out of scope.

- [ ] **Step 1: Write the failing test**

Add to `MultiLevelEmbeddingCacheTest.java`. The class has a `storeWith(Map)` helper — reuse it. An empty map makes every lookup a miss, which is what records a sentinel.

```java
    @Test
    void nullSentinels_stayBoundedUnderFarMoreAbsentIdsThanTheCap() {
        MultiLevelEmbeddingCache cache = new MultiLevelEmbeddingCache.Builder(storeWith(Map.of()))
                .nullSentinelCapacity(100)
                .build();

        for (int id = 0; id < 10_000; id++) cache.getEmbedding(id);

        assertThat(cache.nullSentinelSize()).isLessThanOrEqualTo(100);
        assertThat(cache.tierStats().misses()).isEqualTo(10_000L);
    }
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=MultiLevelEmbeddingCacheTest
```

Expected: compilation failure — no `nullSentinelCapacity` builder method, no `nullSentinelSize()`.

- [ ] **Step 3: Implement**

Add imports for `Cache`, `Caffeine`, and `java.time.Duration`.

Replace the field:

```java
    static final int DEFAULT_NULL_SENTINEL_CAPACITY = 10_000;

    // Bounded negative cache. expireAfterWrite supplies the TTL semantics that the stored
    // expiry timestamp used to carry, so membership alone means "recently confirmed absent".
    private final Cache<Integer, Boolean> nullSentinels;
```

Extend the full constructor:

```java
    MultiLevelEmbeddingCache(EmbeddingStore l2, EmbeddingStore l3,
                              int l1Capacity, HotKeyDetector hotKeyDetector,
                              int nullSentinelCapacity) {
        this.l2             = l2;
        this.l3             = l3;
        this.l1Capacity     = Math.max(1, l1Capacity);
        this.hotKeyDetector = hotKeyDetector;
        this.l1             = new ConcurrentHashMap<>(Math.min(l1Capacity, 1 << 14));
        this.nullSentinels  = Caffeine.newBuilder()
                .maximumSize(Math.max(1, nullSentinelCapacity))
                .expireAfterWrite(Duration.ofMillis(NULL_SENTINEL_TTL_MS))
                .executor(Runnable::run)
                .build();
    }
```

Update every `nullSentinels` call site. In `getEmbedding`, the opening timestamp check collapses:

```java
    @Override
    public float[] getEmbedding(int id) {
        if (nullSentinels.getIfPresent(id) != null) {
            misses.incrementAndGet();
            return null;
        }
        ...
        misses.incrementAndGet();
        nullSentinels.put(id, Boolean.TRUE);
        return null;
    }
```

The `long now = System.currentTimeMillis();` line at the top of `getEmbedding` becomes unused — remove it.

In `getEmbeddings`, the same substitution in the L1 batch-check loop, `nullSentinels.invalidate(id)` in place of `remove`, and `nullSentinels.put(id, Boolean.TRUE)` in both miss branches. `now` is unused there too once the checks collapse — remove it.

In `setEmbedding` and `setEmbeddings`, `nullSentinels.remove(id)` becomes `nullSentinels.invalidate(id)`.

Add the accessor next to `l1Size()`:

```java
    /** Number of entries currently in the bounded negative cache. */
    public int nullSentinelSize() { return (int) nullSentinels.estimatedSize(); }
```

Extend the `Builder`:

```java
        private int nullSentinelCapacity = DEFAULT_NULL_SENTINEL_CAPACITY;

        public Builder nullSentinelCapacity(int cap) { this.nullSentinelCapacity = cap; return this; }

        public MultiLevelEmbeddingCache build() {
            return new MultiLevelEmbeddingCache(l2, l3, l1Capacity, detector, nullSentinelCapacity);
        }
```

Have the default flow from the environment, so operators can tune it without a redeploy of calling code. In the `Builder` field initializer:

```java
        private int nullSentinelCapacity =
                readIntEnv("EMBEDDING_NULL_SENTINEL_MAX_ENTRIES", DEFAULT_NULL_SENTINEL_CAPACITY);
```

and add the same `readIntEnv` private static helper shown in Task 3 Step 3 to `MultiLevelEmbeddingCache`.

Update the tier-layout javadoc block: the L1 box says "No TTL; entries live until the cache fills up or the JVM restarts" — still true. Add a line below the box:

```java
 * The negative cache of confirmed-absent IDs is a bounded Caffeine cache
 * ({@code EMBEDDING_NULL_SENTINEL_MAX_ENTRIES}, default 10 000, 30 s TTL), so a sweep
 * over many absent IDs cannot grow the heap without limit.
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='MultiLevelEmbeddingCacheTest,LogicalExpiryEmbeddingCacheTest,LocalEmbeddingCacheTest'
```

Expected: PASS. `LocalEmbeddingCacheTest` is included as a regression check — `LocalEmbeddingCache` composes with these classes in the L2/L3 chain.

- [ ] **Step 5: Add both cache test classes to the PR gate**

In `pom.xml`, inside the `resilience` profile's `<includes>`, add:

```xml
                <include>**/cache/LogicalExpiryEmbeddingCacheTest.java</include>
                <include>**/cache/MultiLevelEmbeddingCacheTest.java</include>
```

- [ ] **Step 6: Verify the gate runs them**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience 2>&1 | grep -E "EmbeddingCacheTest|BUILD" | tail -5
```

Expected: both classes appear and pass, and the build succeeds.

- [ ] **Step 7: Document the bound**

In `docs/system_design/02_Caching.md`, find the section covering the embedding cache tiers (near the existing `ShardedTopKStore` mentions at lines 95 and 128). Add a sentence stating that every embedding-cache tier is now bounded, naming the two knobs:

```markdown
Every embedding-cache tier is size-bounded. `LocalEmbeddingCache`
(`LOCAL_EMBEDDING_CACHE_MAX_ENTRIES`, default 100 000),
`LogicalExpiryEmbeddingCache` (`LOGICAL_EXPIRY_CACHE_MAX_ENTRIES`, default 10 000)
and the shared negative cache of confirmed-absent IDs
(`EMBEDDING_NULL_SENTINEL_MAX_ENTRIES`, default 10 000) all use Caffeine
`maximumSize` + `expireAfterWrite`, so a sweep over many distinct or absent IDs
cannot grow the heap without limit.
```

Do not renumber any heading.

- [ ] **Step 8: Document the env vars in CLAUDE.md**

In `.claude/CLAUDE.md`, in the "Key env vars" paragraph of the **Services & Ports** section, append after the existing `CATALOG_MAX_CONCURRENT_REQUESTS` sentence:

```markdown
`LOGICAL_EXPIRY_CACHE_MAX_ENTRIES` / `EMBEDDING_NULL_SENTINEL_MAX_ENTRIES` (both default
10000) cap the embedding caches' bounded Caffeine maps.
```

- [ ] **Step 9: Run the documentation index test and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=DocumentationIndexTest
git add src/main/java/com/recsys/infrastructure/cache/MultiLevelEmbeddingCache.java \
        src/test/java/com/recsys/infrastructure/cache/MultiLevelEmbeddingCacheTest.java \
        pom.xml docs/system_design/02_Caching.md .claude/CLAUDE.md
git commit -m "$(cat <<'EOF'
fix(cache): bound MultiLevelEmbeddingCache's negative cache, gate both tests

nullSentinels was a plain ConcurrentHashMap with no cap: a sweep over many
absent IDs grew it without limit. Convert it to a bounded Caffeine cache with
expireAfterWrite, which supplies the TTL semantics the stored expiry timestamp
used to carry.

l1 is deliberately untouched — it is already capped, and its arbitrary-entry
eviction is purpose-built for hot keys.

Add both embedding-cache test classes to the resilience profile so the new
bounding regressions actually gate a merge.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
git push -u origin fix/cache-bound-unbounded-maps
gh pr create --base fix/topk-remove-vestigial-sharding --title "fix(cache): bound the unbounded embedding-cache maps" --body "$(cat <<'EOF'
## Summary

Four plain `ConcurrentHashMap`s in the embedding caches had no size cap and no
eviction sweep. Entries expired *logically* — a timestamp compared on read — but
were removed only on a write or a completed refresh. Traffic touching many distinct
or absent IDs grew them without limit.

`LogicalExpiryEmbeddingCache.cache` is the largest exposure: it holds full `float[]`
embeddings and nothing evicted them.

## What changed

| Map | Bound |
|---|---|
| `LogicalExpiry.cache` | `maximumSize` + `expireAfterWrite(softTtl × 2)` |
| `LogicalExpiry.nullSentinels` | `maximumSize` + `expireAfterWrite(nullSentinelTtl)` |
| `LogicalExpiry.refreshing` | `maximumSize` + 1 min `expireAfterWrite`, doubling as a stuck-refresh backstop |
| `MultiLevel.nullSentinels` | `maximumSize` + `expireAfterWrite(30 s)` |

New knobs `LOGICAL_EXPIRY_CACHE_MAX_ENTRIES` and `EMBEDDING_NULL_SENTINEL_MAX_ENTRIES`,
both defaulting to 10 000 to match the existing `ONLINE_FEATURE_CACHE_MAX_USERS`
precedent.

This follows `LocalEmbeddingCache` and `OnlineFeatureStore`, which already solved
exactly this problem in the same package.

## Semantics preserved

Caffeine's `expireAfterWrite` governs **eviction**; the `softExpiresAtMs` stamped into
each entry still governs the **fresh-vs-stale** decision. Those are different
questions, so the timestamps stay. The hard TTL is deliberately 2× the soft TTL, so
an entry outlives its soft expiry and remains servable as stale.

`MultiLevel.l1` is untouched: already capped, and its arbitrary-entry eviction is
purpose-built for hot keys.

## Test plan

- Two new bounding regressions: insert 10 000 distinct IDs against a cap of 100,
  assert the cache stays bounded
- All pre-existing serve-stale and negative-cache tests pass unchanged, proving the
  timestamp semantics survived the migration
- Both classes added to the `resilience` profile — verified they appear in
  `mvn test -Presilience` output

Stacked on #<PR 1>. Spec: `docs/superpowers/specs/2026-07-28-kv-store-sharp-edges-design.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## PR 3 — Make the sequence-counter guard real

### Task 5: Generation-aware keys and a wall-clock budget

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/redis/sharding/SequenceGenerator.java`
- Create: `src/test/java/com/recsys/infrastructure/redis/sharding/SequenceGeneratorGenerationTest.java`
- Modify: `src/test/java/com/recsys/infrastructure/redis/sharding/SequenceGeneratorTest.java:61`

**Interfaces:**
- Consumes: `Generations.keyPrefix(int version)` — returns `""` for version ≤ 1, `"g{version}:"` for version ≥ 2.
- Produces: `public boolean ensureCounterValid(int version, int shardIndex, long budgetMs)` — returns `true` if the scan completed within budget, `false` if it was truncated. Task 6 calls this.
  The old `ensureCounterValid(int shardIndex, int shardCount)` is removed.

**Background — the hazard.** After a Redis partial flush the counter can lag the highest sequence number still present in the device ZSets. `next()` then reissues an existing number, `ShardedRecordStore`'s `ZADD NX` on the device index becomes a no-op, and the record is silently dropped. The guard scans for the true maximum and raises the counter past it.

**Why the current code cannot help.** It hardcodes `seqKey(1, shardIndex)` and scans the unversioned `prefix + "dev:"` pattern, so after a reshard to generation ≥ 2 it inspects a keyspace new writes no longer use. It also has no caller at all.

**Test constraint.** `SequenceGeneratorTest` is `@Tag("docker")` and the `resilience` profile excludes that tag, so tests added there can never gate a merge. The new test class must use a mocked `RedisExecutor` — follow `ShardedRecordStoreGenerationKeyTest`, which is in the same package and does exactly this.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/recsys/infrastructure/redis/sharding/SequenceGeneratorGenerationTest.java`:

```java
package com.recsys.infrastructure.redis.sharding;

import com.recsys.infrastructure.redis.RedisExecutor;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Non-docker unit tests for the generation-aware sequence-counter guard.
 *
 * SequenceGeneratorTest is @Tag("docker") and the resilience profile — the PR gate —
 * excludes that tag, so behaviour that must block a merge is asserted here against a
 * mocked executor instead.
 */
class SequenceGeneratorGenerationTest {

    @SuppressWarnings("unchecked")
    private RedisCommands<String, String> wire(RedisExecutor exec) {
        RedisCommands<String, String> cmd = mock(RedisCommands.class);
        when(exec.execute(any())).thenAnswer(i -> i.getArgument(0, Function.class).apply(cmd));
        return cmd;
    }

    @SuppressWarnings("unchecked")
    private KeyScanCursor<String> finishedCursor(List<String> keys) {
        KeyScanCursor<String> cursor = mock(KeyScanCursor.class);
        when(cursor.getKeys()).thenReturn(keys);
        when(cursor.isFinished()).thenReturn(true);
        return cursor;
    }

    @Test
    void generation2_scansAndWritesGenerationPrefixedKeys() {
        RedisExecutor exec = mock(RedisExecutor.class);
        RedisCommands<String, String> cmd = wire(exec);

        when(cmd.scan(any(ScanArgs.class)))
                .thenReturn(finishedCursor(List.of("sr:g2:dev:0:device-1")));
        when(cmd.zrevrangebyscoreWithScores(eq("sr:g2:dev:0:device-1"), any(Range.class), any(Limit.class)))
                .thenReturn(List.of(ScoredValue.just(100.0, "event-1")));
        when(cmd.get("sr:g2:seq:0")).thenReturn("5");

        boolean completed = new SequenceGenerator(exec, "sr:").ensureCounterValid(2, 0, 30_000L);

        assertThat(completed).isTrue();
        // Counter was behind the max score, so it is raised past it — on the g2 key.
        verify(cmd).set("sr:g2:seq:0", "101");
        verify(cmd, never()).set(eq("sr:seq:0"), anyString());
    }

    @Test
    void generation1_keepsTheUnversionedKeyspace() {
        RedisExecutor exec = mock(RedisExecutor.class);
        RedisCommands<String, String> cmd = wire(exec);

        when(cmd.scan(any(ScanArgs.class)))
                .thenReturn(finishedCursor(List.of("sr:dev:0:device-1")));
        when(cmd.zrevrangebyscoreWithScores(eq("sr:dev:0:device-1"), any(Range.class), any(Limit.class)))
                .thenReturn(List.of(ScoredValue.just(100.0, "event-1")));
        when(cmd.get("sr:seq:0")).thenReturn("5");

        new SequenceGenerator(exec, "sr:").ensureCounterValid(1, 0, 30_000L);

        verify(cmd).set("sr:seq:0", "101");
    }

    @Test
    void counterAlreadyAheadIsLeftAlone() {
        RedisExecutor exec = mock(RedisExecutor.class);
        RedisCommands<String, String> cmd = wire(exec);

        when(cmd.scan(any(ScanArgs.class)))
                .thenReturn(finishedCursor(List.of("sr:dev:0:device-1")));
        when(cmd.zrevrangebyscoreWithScores(anyString(), any(Range.class), any(Limit.class)))
                .thenReturn(List.of(ScoredValue.just(100.0, "event-1")));
        when(cmd.get("sr:seq:0")).thenReturn("500");

        new SequenceGenerator(exec, "sr:").ensureCounterValid(1, 0, 30_000L);

        // The guard only ever raises the counter, never lowers it.
        verify(cmd, never()).set(anyString(), anyString());
    }

    @Test
    void emptyShardIsANoOp() {
        RedisExecutor exec = mock(RedisExecutor.class);
        RedisCommands<String, String> cmd = wire(exec);
        when(cmd.scan(any(ScanArgs.class))).thenReturn(finishedCursor(List.of()));

        assertThat(new SequenceGenerator(exec, "sr:").ensureCounterValid(1, 0, 30_000L)).isTrue();

        verify(cmd, never()).get(anyString());
        verify(cmd, never()).set(anyString(), anyString());
    }

    @Test
    void exhaustedBudgetTruncatesTheScanAndReportsIncomplete() {
        RedisExecutor exec = mock(RedisExecutor.class);
        RedisCommands<String, String> cmd = wire(exec);

        // A cursor that never finishes: only the budget can stop the loop.
        @SuppressWarnings("unchecked")
        KeyScanCursor<String> endless = mock(KeyScanCursor.class);
        when(endless.getKeys()).thenReturn(List.of("sr:dev:0:device-1"));
        when(endless.isFinished()).thenReturn(false);
        when(cmd.scan(any(ScanArgs.class))).thenReturn(endless);
        when(cmd.scan(any(KeyScanCursor.class), any(ScanArgs.class))).thenReturn(endless);
        when(cmd.zrevrangebyscoreWithScores(anyString(), any(Range.class), any(Limit.class)))
                .thenReturn(List.of(ScoredValue.just(100.0, "event-1")));
        when(cmd.get("sr:seq:0")).thenReturn("5");

        // A clock that advances 10ms per read exhausts a 50ms budget in a handful of pages.
        AtomicLong ticks = new AtomicLong();
        SequenceGenerator gen = new SequenceGenerator(exec, "sr:", () -> ticks.addAndGet(10L));

        assertThat(gen.ensureCounterValid(1, 0, 50L)).isFalse();

        // A truncated scan still repairs with what it found: under-estimating maxSeq is safe,
        // because the guard only raises the counter.
        verify(cmd).set("sr:seq:0", "101");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=SequenceGeneratorGenerationTest
```

Expected: compilation failure — `ensureCounterValid(int, int, long)` and the three-argument constructor do not exist.

- [ ] **Step 3: Implement**

Rewrite `SequenceGenerator.java`. Add a `LongSupplier` clock so the budget is testable, defaulting to `System::currentTimeMillis`:

```java
package com.recsys.infrastructure.redis.sharding;

import com.recsys.infrastructure.redis.RedisExecutor;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScoredValue;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * Assigns shard-scoped monotonic sequence numbers via Redis INCR.
 *
 * Each shard has its own counter at {prefix}{generation}seq:{shardIndex}.
 * Sequence numbers are shard-scoped (not globally unique across shards).
 */
public final class SequenceGenerator {

    private final RedisExecutor exec;
    private final String prefix;
    private final LongSupplier clockMs;

    public SequenceGenerator(RedisExecutor exec, String prefix) {
        this(exec, prefix, System::currentTimeMillis);
    }

    SequenceGenerator(RedisExecutor exec, String prefix, LongSupplier clockMs) {
        this.exec    = exec;
        this.prefix  = prefix;
        this.clockMs = clockMs;
    }

    /** Next sequence for (version, shard). Always >= 1. */
    public long next(int version, int shardIndex) {
        return exec.execute(c -> c.incr(seqKey(version, shardIndex)));
    }

    /** Back-compat: version-1 (unversioned) sequence. */
    public long next(int shardIndex) {
        return next(1, shardIndex);
    }

    /**
     * Guards against a stale counter after a Redis partial flush: a counter behind the
     * highest sequence number still present reissues that number, the device index's
     * {@code ZADD NX} becomes a no-op, and the record is silently dropped.
     *
     * <p>Scans the device ZSets of one shard <em>in the given topology generation</em> and
     * raises the counter to {@code max(score) + 1} when it is behind. The counter is only
     * ever raised, never lowered.
     *
     * <p>The scan is bounded by {@code budgetMs} of wall-clock time. A truncated scan can
     * only <em>under</em>-estimate the true maximum, so a partial run degrades to less
     * repair rather than to a wrong counter — which is why exceeding the budget is a
     * warning, not a failure.
     *
     * <p>Expensive: one SCAN pass plus a ZREVRANGEBYSCORE per device key. Call it off the
     * request path and off the startup thread.
     *
     * @return {@code true} if the scan completed, {@code false} if the budget truncated it
     */
    public boolean ensureCounterValid(int version, int shardIndex, long budgetMs) {
        ScanResult scan = findMaxSeqInShard(version, shardIndex, budgetMs);
        if (scan.maxSeq() <= 0) return scan.completed();

        String key = seqKey(version, shardIndex);
        String current = exec.execute(c -> c.get(key));
        long currentVal = current == null ? 0L : Long.parseLong(current);
        if (currentVal < scan.maxSeq()) {
            exec.execute(c -> c.set(key, String.valueOf(scan.maxSeq() + 1)));
        }
        return scan.completed();
    }

    private ScanResult findMaxSeqInShard(int version, int shardIndex, long budgetMs) {
        String pattern = prefix + Generations.keyPrefix(version) + "dev:" + shardIndex + ":*";
        ScanArgs params = ScanArgs.Builder.matches(pattern).limit(200);
        long deadline = clockMs.getAsLong() + Math.max(1L, budgetMs);

        return exec.execute(c -> {
            long maxSeq = 0L;
            KeyScanCursor<String> cursor = c.scan(params);
            while (true) {
                for (String devKey : cursor.getKeys()) {
                    List<ScoredValue<String>> top = c.zrevrangebyscoreWithScores(
                            devKey, Range.unbounded(), Limit.create(0, 1));
                    if (!top.isEmpty()) {
                        maxSeq = Math.max(maxSeq, (long) top.get(0).getScore());
                    }
                }
                if (cursor.isFinished()) return new ScanResult(maxSeq, true);
                if (clockMs.getAsLong() >= deadline) return new ScanResult(maxSeq, false);
                cursor = c.scan(cursor, params);
            }
        });
    }

    private String seqKey(int version, int shardIndex) {
        return prefix + Generations.keyPrefix(version) + "seq:" + shardIndex;
    }

    /** Outcome of one bounded scan: the highest score seen, and whether the scan finished. */
    private record ScanResult(long maxSeq, boolean completed) {}
}
```

Note that `maxSeq` is captured inside the lambda and mutated — Java forbids that for a captured local. It is declared *inside* the lambda body here, which is why this compiles. Verify that when you build.

- [ ] **Step 4: Update the one existing call site**

In `SequenceGeneratorTest.java` line 61, replace:

```java
        gen.ensureCounterValid(0, 1); // shardIndex=0, shardCount=1
```

with:

```java
        gen.ensureCounterValid(1, 0, 30_000L); // version=1, shardIndex=0, 30s budget
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=SequenceGeneratorGenerationTest
```

Expected: PASS, all five tests.

- [ ] **Step 6: Commit**

```bash
git checkout -b fix/sharding-generation-aware-seq-guard
git add src/main/java/com/recsys/infrastructure/redis/sharding/SequenceGenerator.java \
        src/test/java/com/recsys/infrastructure/redis/sharding/SequenceGeneratorGenerationTest.java \
        src/test/java/com/recsys/infrastructure/redis/sharding/SequenceGeneratorTest.java
git commit -m "$(cat <<'EOF'
fix(sharding): make the sequence-counter guard generation-aware and bounded

ensureCounterValid hardcoded seqKey(1, shardIndex) and scanned the unversioned
dev: pattern, so after a reshard to generation >= 2 it inspected a keyspace that
new writes no longer use. Its shardCount parameter was unused.

Route both the counter key and the scan pattern through Generations.keyPrefix,
and bound the scan with a wall-clock budget — it issues one ZREVRANGEBYSCORE per
device key, which is unbounded work against a large keyspace. A truncated scan
can only under-estimate the maximum, and the guard only ever raises the counter,
so a partial run degrades to less repair rather than a wrong counter.

Tests are a new non-docker class: SequenceGeneratorTest is @Tag("docker") and the
resilience profile excludes that tag, so assertions there cannot gate a merge.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 6: Run the repair at startup, off the boot thread

**Files:**
- Modify: `src/main/java/com/recsys/api/online/OnlinePredictionServer.java:176-179`

**Interfaces:**
- Consumes: `SequenceGenerator.ensureCounterValid(int version, int shardIndex, long budgetMs)` from Task 5, and `ShardTopologyProvider.current()` which returns a `ShardTopology` with `version()` and `shardCount()`.
- Produces: nothing consumed by later tasks.

**Why not on the boot thread.** The guard SCANs every device ZSet in a shard and issues a `ZREVRANGEBYSCORE` per key. Running it synchronously for every shard would block server startup in proportion to keyspace size. It runs on a daemon thread, budgeted, and fails soft — matching the codebase's existing rule that topology I/O never breaks the request path.

- [ ] **Step 1: Implement the background repair**

In `OnlinePredictionServer.java`, the current block reads:

```java
            ShardedRecordStore shardedRecordStore = new ShardedRecordStore(
                    jedisPool, jedisPool, topologyProvider,
                    new SequenceGenerator(jedisPool, "sr:"), "sr:");
```

Replace it with:

```java
            SequenceGenerator seqGen = new SequenceGenerator(jedisPool, "sr:");
            ShardedRecordStore shardedRecordStore = new ShardedRecordStore(
                    jedisPool, jedisPool, topologyProvider, seqGen, "sr:");
            startSequenceCounterRepair(seqGen, topologyProvider);
```

Add this private static method to the same class, next to the other helpers:

```java
    /**
     * Repairs shard sequence counters that a Redis partial flush left behind the highest
     * sequence number still present — a stale counter reissues that number, the device
     * index's ZADD NX becomes a no-op, and the record is silently dropped.
     *
     * <p>Runs on a daemon thread, not the boot thread: the guard SCANs every device ZSet
     * and issues a ZREVRANGEBYSCORE per key, so doing it synchronously would block startup
     * in proportion to keyspace size. Fails soft — a Redis outage here must not stop the
     * server from serving.
     */
    private static void startSequenceCounterRepair(SequenceGenerator seqGen,
                                                   ShardTopologyProvider provider) {
        if (!Boolean.parseBoolean(
                System.getenv().getOrDefault("SHARDED_RECORD_SEQ_REPAIR_ENABLED", "true"))) {
            log.info("Shard sequence-counter repair disabled (SHARDED_RECORD_SEQ_REPAIR_ENABLED=false)");
            return;
        }
        long budgetMs = readIntEnv("SHARDED_RECORD_SEQ_REPAIR_TIMEOUT_MS", 30_000);
        Thread t = new Thread(() -> {
            try {
                ShardTopology topo = provider.current();
                for (int shard = 0; shard < topo.shardCount(); shard++) {
                    if (!seqGen.ensureCounterValid(topo.version(), shard, budgetMs)) {
                        log.warn("Sequence-counter repair for generation {} shard {} exceeded its {}ms "
                                + "budget; counter raised only as far as the partial scan reached",
                                topo.version(), shard, budgetMs);
                    }
                }
                log.info("Sequence-counter repair complete for generation {} ({} shards)",
                        topo.version(), topo.shardCount());
            } catch (Exception e) {
                log.warn("Sequence-counter repair failed; continuing without it: {}", e.toString());
            }
        }, "shard-seq-repair");
        t.setDaemon(true);
        t.start();
    }
```

Add the import for `ShardTopology` if the class does not already have it:

```java
import com.recsys.infrastructure.redis.sharding.ShardTopology;
```

Confirm the class has a `log` field and a `readIntEnv` helper — both are used elsewhere in the file (`readIntEnv` appears at lines 162 and 170). If `log` is absent, use the logger name the file already uses for its other messages.

- [ ] **Step 2: Verify it compiles**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q package -DskipTests
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Verify the server still starts and the repair logs**

```bash
docker run -d --rm --name seq-repair-redis -p 6379:6379 redis:7-alpine
JAVA_HOME=$(/usr/libexec/java_home -v 17) timeout 45 mvn exec:java \
  -Dexec.mainClass=com.recsys.api.online.OnlinePredictionServer 2>&1 | tee /tmp/seq-repair.log | tail -30
grep -E "Sequence-counter repair|Server started|Started.*7010" /tmp/seq-repair.log
docker rm -f seq-repair-redis
```

Expected: the log shows "Sequence-counter repair complete for generation 1 (2 shards)" and the server reaches its listening state. On an empty Redis the scan finds nothing and returns immediately, so the two messages may appear in either order — that ordering is the point: startup is not gated on the repair.

- [ ] **Step 4: Verify the kill switch**

```bash
docker run -d --rm --name seq-repair-redis -p 6379:6379 redis:7-alpine
SHARDED_RECORD_SEQ_REPAIR_ENABLED=false JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  timeout 45 mvn exec:java -Dexec.mainClass=com.recsys.api.online.OnlinePredictionServer 2>&1 \
  | grep -E "repair disabled|Sequence-counter repair complete"
docker rm -f seq-repair-redis
```

Expected: "Shard sequence-counter repair disabled" appears and "repair complete" does not.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/api/online/OnlinePredictionServer.java
git commit -m "$(cat <<'EOF'
fix(sharding): run the sequence-counter repair at startup

The guard's javadoc said "call once at startup per shard before accepting
writes". Nothing called it — its only caller was a test — so the hazard it
describes went unguarded.

Wire it into OnlinePredictionServer on a daemon thread, budgeted and fail-soft.
Not on the boot thread: it SCANs every device ZSet and issues one
ZREVRANGEBYSCORE per key, so a synchronous run would block startup in proportion
to keyspace size.

SHARDED_RECORD_SEQ_REPAIR_ENABLED (default true) and
SHARDED_RECORD_SEQ_REPAIR_TIMEOUT_MS (default 30000) tune it.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 7: Gate the new test and document the repair

**Files:**
- Modify: `pom.xml`
- Modify: `docs/system_design/14_Partitioning.md`
- Modify: `.claude/CLAUDE.md`

**Interfaces:**
- Consumes: the env-var names from Task 6.
- Produces: nothing.

- [ ] **Step 1: Add the new test to the PR gate**

In `pom.xml`, inside the `resilience` profile's `<includes>`:

```xml
                <include>**/sharding/SequenceGeneratorGenerationTest.java</include>
```

Do **not** add `SequenceGeneratorTest.java` — it is `@Tag("docker")` and `<excludedGroups>` would drop it anyway, so listing it would imply coverage the gate does not provide.

- [ ] **Step 2: Verify the gate runs it**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience 2>&1 | grep -E "SequenceGeneratorGeneration|BUILD"
```

Expected: the class appears, five tests pass, build succeeds.

- [ ] **Step 3: Document the repair**

In `docs/system_design/14_Partitioning.md`, in the section covering `ShardedRecordStore` sequence numbers, add:

```markdown
**Sequence-counter repair at startup.** A Redis partial flush can leave a shard's
`{prefix}{generation}seq:{shard}` counter behind the highest sequence number still
present in that shard's device ZSets. The next write then reissues an existing
number, the device index's `ZADD NX` becomes a no-op, and the record is silently
dropped. `SequenceGenerator.ensureCounterValid` scans the shard's device ZSets for
the true maximum and raises the counter past it — only ever raising, never lowering.

`OnlinePredictionServer` runs it for every shard of the current generation at
startup, on a daemon thread. It is deliberately not on the boot thread: the scan
issues one `ZREVRANGEBYSCORE` per device key, so a synchronous run would block
startup in proportion to keyspace size. It is bounded by
`SHARDED_RECORD_SEQ_REPAIR_TIMEOUT_MS` (default 30000) and can be disabled with
`SHARDED_RECORD_SEQ_REPAIR_ENABLED=false`. Exceeding the budget logs a warning
rather than failing: a truncated scan can only under-estimate the maximum, so it
repairs less, never wrongly.
```

Do not renumber any heading.

- [ ] **Step 4: Document the env vars in CLAUDE.md**

In `.claude/CLAUDE.md`, in the same "Key env vars" paragraph edited in Task 4:

```markdown
`SHARDED_RECORD_SEQ_REPAIR_ENABLED` (default true) and
`SHARDED_RECORD_SEQ_REPAIR_TIMEOUT_MS` (default 30000) control the background
shard sequence-counter repair that `OnlinePredictionServer` runs at startup.
```

Also extend the **Redis Conventions** section's `sr:g{version}:rec:…` bullet to mention that the repair follows the active generation.

- [ ] **Step 5: Run the doc test, commit, open the PR**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=DocumentationIndexTest
git add pom.xml docs/system_design/14_Partitioning.md .claude/CLAUDE.md
git commit -m "$(cat <<'EOF'
docs(sharding): document the sequence-counter repair, and gate its test

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
git push -u origin fix/sharding-generation-aware-seq-guard
gh pr create --base fix/cache-bound-unbounded-maps --title "fix(sharding): make the sequence-counter guard real" --body "$(cat <<'EOF'
## Summary

`SequenceGenerator.ensureCounterValid` guards a real hazard: after a Redis partial
flush, a stale counter reissues an existing sequence number, the device index's
`ZADD NX` becomes a no-op, and **the record is silently dropped**.

Two problems. It was **never called** — its only caller was a test, despite a javadoc
saying "call once at startup per shard before accepting writes". And it was wrong for
any topology generation ≥ 2: it hardcoded `seqKey(1, shardIndex)` and scanned the
unversioned `dev:` pattern, so after a reshard it inspected a keyspace new writes no
longer use. Its `shardCount` parameter was unused.

## What changed

- Signature is now `ensureCounterValid(int version, int shardIndex, long budgetMs)`.
  Both the counter key and the scan pattern route through `Generations.keyPrefix`.
- Bounded by a wall-clock budget, returning `false` when truncated. The scan issues
  one `ZREVRANGEBYSCORE` per device key — unbounded work against a large keyspace.
- Wired into `OnlinePredictionServer` on a **daemon thread**, not the boot thread, so
  startup is never blocked in proportion to keyspace size. Fails soft.
- `SHARDED_RECORD_SEQ_REPAIR_ENABLED` (default `true`) and
  `SHARDED_RECORD_SEQ_REPAIR_TIMEOUT_MS` (default `30000`).

## Why a truncated scan is safe

A partial scan can only *under*-estimate the true maximum, and the guard only ever
raises the counter, never lowers it. A budget-exhausted run therefore degrades to
today's behavior — less repair — rather than to a wrong counter. That is why
exceeding the budget logs a warning instead of failing.

## Test plan

New **non-docker** `SequenceGeneratorGenerationTest` with five cases: generation-2
keying, generation-1 keying, counter-already-ahead, empty shard, and budget
exhaustion. Non-docker matters — `SequenceGeneratorTest` is `@Tag("docker")` and the
`resilience` profile sets `<excludedGroups>load,docker</excludedGroups>`, so
assertions there can never gate a merge.

Manually verified the server starts with the repair logging, and that
`SHARDED_RECORD_SEQ_REPAIR_ENABLED=false` suppresses it.

Stacked on #<PR 2>. Spec: `docs/superpowers/specs/2026-07-28-kv-store-sharp-edges-design.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## PR 4 — Correct the replica-selection javadoc

### Task 8: Document why replica selection is stable

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/redis/RedisReadReplicaRouter.java:11-20,48-56`

**Interfaces:**
- Consumes: nothing. Comment-only change; no signature moves.
- Produces: nothing.

**Background.** `readable()` returns `replicas.get(0)`, a stable choice, while both the class javadoc and the method javadoc promise "a randomly selected replica" for load balancing. The stable behavior is correct and deliberate: it arrived in `fbe54cd` ("wire consistency health signals end-to-end"), which added a per-process replica-lag probe with a correlated sequence check. `readable()` and `probeReadable()` must resolve to the same node or the lag measurement is meaningless. Only the documentation is wrong — and a future reader could easily "fix" the code back to random and silently break the probe.

- [ ] **Step 1: Correct the class javadoc**

Replace the `<b>Read path</b>` paragraph (lines 13-17):

```java
 * <p><b>Read path</b>: prefers the replica in the same AZ as this service
 * instance (set via the {@code AWS_AZ} environment variable).  Otherwise falls back
 * to the first configured replica — a <em>stable</em> choice, not a random one — or
 * to the primary when no replicas are configured.  This keeps reads available even
 * if the primary AZ becomes unreachable, and avoids cross-AZ data-transfer costs on
 * the hot read path.
 *
 * <p><b>Why the fallback is stable and not randomised.</b> {@link #readable()} and
 * {@link #probeReadable()} must resolve to the same node. The replica-lag probe
 * issues a correlated sequence check against {@code probeReadable()} and reports the
 * lag of the replica that real reads are actually served from; spreading reads
 * randomly across replicas would make that measurement describe a node no particular
 * read used. Do not "fix" this to random selection without also reworking
 * {@code RedisReplicaLagProbe}.
```

- [ ] **Step 2: Correct the method javadoc**

Replace the `readable()` javadoc (lines 48-56):

```java
    /**
     * Returns the read executor for the current AZ.
     *
     * <ol>
     *   <li>Same-AZ replica — lowest latency, survives primary-AZ failure.</li>
     *   <li>First configured replica — a stable choice, so that reads and the
     *       replica-lag probe observe the same node.</li>
     *   <li>Primary — safe fallback when no replicas are configured.</li>
     * </ol>
     */
```

Leave the inline comment at the `return replicas.get(0)` line as it is; it already says "Stable fallback".

- [ ] **Step 3: Verify nothing but comments changed**

```bash
git diff -U0 src/main/java/com/recsys/infrastructure/redis/RedisReadReplicaRouter.java \
  | grep -E "^[+-]" | grep -vE "^[+-]{3}" | grep -vE "^[+-]\s*(\*|//|/\*)" 
```

Expected: no output. Any line printed is a code change, which this task must not make.

- [ ] **Step 4: Run the router's tests**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='RedisReadReplicaRouterTest,RedisReplicaLagProbeTest'
```

Expected: PASS, unchanged. No test is added — there is no behavior to assert that these do not already cover.

- [ ] **Step 5: Commit and open the PR**

```bash
git checkout -b docs/replica-selection-javadoc
git add src/main/java/com/recsys/infrastructure/redis/RedisReadReplicaRouter.java
git commit -m "$(cat <<'EOF'
docs(redis): say why replica selection is stable, not random

readable() returns replicas.get(0), but the class and method javadoc both
promised "a randomly selected replica" for load balancing.

The stable behaviour is correct and deliberate — it arrived with the replica-lag
probe in fbe54cd. readable() and probeReadable() must resolve to the same node or
the correlated sequence check measures the lag of a replica no read used. The
docs were the only thing wrong, and left the code one plausible "fix" away from
silently breaking the probe. Record the constraint where a future reader will
find it.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
git push -u origin docs/replica-selection-javadoc
gh pr create --base fix/sharding-generation-aware-seq-guard --title "docs(redis): say why replica selection is stable, not random" --body "$(cat <<'EOF'
## Summary

`RedisReadReplicaRouter.readable()` returns `replicas.get(0)` — a stable choice.
Both the class javadoc and the method javadoc promised "a randomly selected replica"
for load balancing.

The code is right; the documentation is wrong. Stable selection arrived in `fbe54cd`
("wire consistency health signals end-to-end") along with the per-process replica-lag
probe: `readable()` and `probeReadable()` must resolve to the same node, or the
correlated sequence check reports the lag of a replica that no particular read was
served from.

## What changed

Comments only — no signature or behavior change. The javadoc now describes stable
first-configured-replica selection **and states why**, so the next reader does not
"fix" it back to random and silently break the lag probe.

## Test plan

- `mvn test -Dtest='RedisReadReplicaRouterTest,RedisReplicaLagProbeTest'` — passes,
  unchanged
- Verified the diff contains no non-comment lines

No test added: there is no new behavior, and the existing suites already cover
selection order.

Stacked on #<PR 3>. Spec: `docs/superpowers/specs/2026-07-28-kv-store-sharp-edges-design.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Final verification

After all four PRs are open, run the full gate and a complete build from the tip of the stack:

- [ ] **Step 1: Run the PR gate**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
```

Expected: BUILD SUCCESS, with `ShardedTopKStoreTest`, `ShardedTopKStoreTtlConfigTest`, `LogicalExpiryEmbeddingCacheTest`, `MultiLevelEmbeddingCacheTest`, and `SequenceGeneratorGenerationTest` all present in the output.

- [ ] **Step 2: Run the full non-docker suite**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -DexcludedGroups="load,docker"
```

Expected: BUILD SUCCESS. This is broader than the gate and catches any collateral damage the allow-list would miss. If a failure appears in a class this plan never touched, investigate before merging — do not assume it is pre-existing without checking `git stash`.

- [ ] **Step 3: Confirm the stack order**

```bash
gh pr list --state open --json number,title,baseRefName,headRefName \
  --jq '.[] | "\(.number) \(.headRefName) -> \(.baseRefName)"'
```

Expected chain: `fix/topk-remove-vestigial-sharding -> main`, `fix/cache-bound-unbounded-maps -> fix/topk-remove-vestigial-sharding`, `fix/sharding-generation-aware-seq-guard -> fix/cache-bound-unbounded-maps`, `docs/replica-selection-javadoc -> fix/sharding-generation-aware-seq-guard`. As each merges, retarget the next to `main`.
