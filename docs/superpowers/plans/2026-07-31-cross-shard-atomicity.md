# Cross-Shard Atomicity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Document why sharding dissolves the single-database transaction guarantee, then make this repo's sharded write path actually atomic within a shard and safe to co-locate under Redis Cluster.

**Architecture:** Stage 1 adds a new section to the sharding investigation joining it to the eventual-consistency investigation, and records two real gaps as sharp edges. Stage 2 closes them: a single Lua script replaces the pipelined `HSET`+`ZADD`+`XADD` (absorbing the sequence `INCR`), and a per-generation `keyFormat` carried in the topology snapshot introduces hash-tagged keys so all of a shard's keys share one Cluster slot. The existing generation dual-read window absorbs the format migration with no data movement.

**Tech Stack:** Java 17, Maven, Lettuce (Redis), Jackson, JUnit 5, AssertJ, Mockito.

**Spec:** `docs/superpowers/specs/2026-07-31-cross-shard-atomicity-design.md`

## Global Constraints

- **JDK 17 required.** Build with `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`. Newer JDKs fail a clean compile of two pre-existing files.
- **Never merge to `main` directly.** Every stage lands as a PR.
- **Two PRs.** PR1 = Tasks 1–2 (docs). PR2 = Tasks 3–9 (code + doc updates), stacked on PR1 and rebased onto `main` once PR1 merges.
- **The PR gate runs only the `-Presilience` profile** (`pom.xml`, an explicit `<includes>` allow-list) and excludes `@Tag("docker")` and `@Tag("load")` via `<excludedGroups>load,docker</excludedGroups>`. A test blocks a merge only if it is non-docker **and** listed in that profile.
- **`DocumentedMechanismTest` and `DocumentationIndexTest` are in the gate.** Two consequences for every doc edit: relative source links (`../../src/main/java/...`) must resolve to real files, and any method name written as `` `name` `` or `name(` in a doc must still exist in `src/main`. Renaming or deleting a documented method without updating its doc fails the build.
- **Existing behavior to preserve exactly:** `WriteResult(seqNum, shardIndex, status)` shape, and `WriteStatus.DUPLICATE` reachable only via `!isUpdate && zadd == 0`.
- **Redis floor is 6.2+** (the current write path already uses `ZADD ... GT`).
- Run a single test class with `mvn test -Dtest=ClassName`.

---

## File Structure

**Stage 1 — modified only:**

| File | Responsibility for this change |
|---|---|
| `docs/system_design/03_DB_Scaling_Sharding.md` | New `## 7`; two new sharp edges; correct the §6 cluster-readiness claim |
| `docs/system_design/14_Partitioning.md` | Correct the same cluster-readiness claim |
| `docs/system_design/15_Eventual_Consistency.md` | Pointer to 03 §7; one sharp-edge entry |
| `README.md` | Extend the row-03 description |

**Stage 2 — created:**

| File | Responsibility |
|---|---|
| `src/main/java/com/recsys/infrastructure/redis/sharding/ShardKeys.java` | The single owner of the record-store key scheme: prefix + generation + key format → every key for a shard |
| `src/test/java/com/recsys/infrastructure/redis/sharding/ShardKeysTest.java` | Golden key strings for both formats |
| `src/test/java/com/recsys/infrastructure/redis/sharding/ShardTopologySnapshotFormatTest.java` | Topology JSON round-trip incl. absent-field default |
| `src/test/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStoreAtomicWriteTest.java` | Script invocation shape and duplicate/update semantics |
| `src/test/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStoreAtomicWriteIntegrationTest.java` | `@Tag("docker")` — real Redis atomicity and cross-format dual-read |

**Stage 2 — modified:**

| File | Change |
|---|---|
| `.../sharding/ShardTopology.java` | Add `keyFormat` |
| `.../sharding/ShardTopologyStore.java` | `Snapshot.keyFormat`, tolerant parsing, bootstrap + reshard Lua write format 2 |
| `.../sharding/ShardTopologyProvider.java` | Carry `keyFormat` onto current and previous generations |
| `.../sharding/SequenceGenerator.java` | Build keys through `ShardKeys` |
| `.../sharding/ShardedRecordStore.java` | Lua write replaces the pipeline; reads use `ShardKeys` |
| `.../api/online/OnlinePredictionServer.java:340` | `ensureCounterValid` now takes a `ShardTopology` |
| `src/test/.../ShardedRecordStoreGenerationKeyTest.java` | Retarget onto `ShardKeys` |
| `pom.xml` | Add the new non-docker tests to the `resilience` profile |
| `.claude/CLAUDE.md` | Redis Conventions: tagged key spelling |

Why `ShardKeys` exists: the key scheme is currently duplicated between `ShardedRecordStore` (three helpers) and `SequenceGenerator` (`seqKey` plus a SCAN pattern). Adding a second format to two copies invites drift, and the Lua script needs a *key prefix* that neither class exposes. One focused class removes the duplication and is trivially unit-testable without Redis.

---

## Stage 1 — Documentation (PR1)

### Task 1: Cross-shard atomicity section in the sharding investigation

**Files:**
- Modify: `docs/system_design/03_DB_Scaling_Sharding.md` (insert before line 261 `## Sharp edges — notes`; edit §6 at lines 223–234; extend the sharp-edge list)

**Interfaces:**
- Consumes: nothing.
- Produces: an anchor `#7-cross-shard-atomicity--where-the-transaction-stops` that Task 2 links to from `15_Eventual_Consistency.md`.

- [ ] **Step 1: Create the branch**

```bash
git checkout main && git pull
git checkout -b docs/cross-shard-atomicity-investigation
```

(If the branch already exists from the spec commit, `git checkout docs/cross-shard-atomicity-investigation` instead.)

- [ ] **Step 2: Insert the new section**

Insert immediately **before** the `## Sharp edges — notes` heading. Do not renumber any existing `##`.

````markdown
## 7. Cross-shard atomicity — where the transaction stops

The usual way to make two writes all-or-nothing is to wrap them in one database
transaction: deduct inventory and insert the order row, commit, and let the database
own the consistency guarantee. That works, and this repo relies on it — in exactly one
place.

[`MySqlSagaStateStore.saveWithEvent`](../../src/main/java/com/recsys/infrastructure/saga/MySqlSagaStateStore.java)
mutates two different tables — `saga_instance` and `event_outbox` — inside a single
[`TransactionalMySql`](../../src/main/java/com/recsys/infrastructure/persistence/TransactionalMySql.java)
transaction. Both rows land or neither does. That is legal only because MySQL here is
deliberately **un-sharded**
([14_Partitioning](14_Partitioning.md#where-the-shards-physically-live--redis-and-mysql)).
The atomicity is not free; it is bought by not sharding.

### Three ways sharding breaks it

Usually only the first is named. All three are live concerns here.

1. **Separate shards are separate transaction domains.** Inventory hashed by item and
   orders hashed by user land in different places. There is no XA and no two-phase
   commit anywhere in this codebase, and the writable MySQL boundary holds a single
   JDBC URL and one pool.
2. **A multi-key write inside one shard is not atomic either.** The record store
   pipelines its three writes (§1). Pipelining is batching, not a transaction — see
   sharp edge 6.
3. **The shard map is itself eventually consistent.** Topology propagates across the
   fleet over ~30 s and a reshard opens a 24 h dual-read window (§3). A transaction
   cannot span a boundary that is still moving.

### What the system uses instead

| Mechanism | Where | Shape for "deduct inventory, create order" |
|---|---|---|
| Local transaction + outbox | [`DurableEventPublisher`](../../src/main/java/com/recsys/application/outbox/DurableEventPublisher.java), `OutboxRelay` | Order row and the "deduct inventory" outbox row commit together in one database; delivery is asynchronous, leased, and retried |
| Compensation saga | [`SagaOrchestrators`](../../src/main/java/com/recsys/application/saga/SagaOrchestrators.java) `Standard` | Reserve inventory, then create the order; on failure compensate completed steps in reverse |
| TCC | `SagaOrchestrators` `Tcc` | Try reserves without making the reservation externally final; Confirm commits all; Cancel releases every unconfirmed reservation |

For inventory specifically **TCC fits better than the plain saga**, because compensation
is not rollback. Between a reserve step committing and its compensation running, every
other reader sees stock that was never actually sold. TCC's Try holds a reservation that
is not yet externally final, which closes that window — at the cost of modelling
`available` and `reserved` separately.

### What you now owe, that the transaction gave you free

- **Idempotency keys.** Participants key on saga id plus step name, plus the phase for
  TCC, because the event path is at-least-once and replay is expected.
- **Optimistic concurrency instead of row locks.** Saga state advances under
  `WHERE saga_id = ? AND version = ?`, raising a conflict rather than blocking. That is
  the sharded-world substitute for `SELECT … FOR UPDATE`.
- **A failure state ACID never had.** Compensation and cancel are deliberately
  best-effort: every step is attempted, errors accumulate, and the saga still fails. A
  compensation that itself fails leaves real inconsistency for an operator to resolve.

### Where it bottoms out

The saga machinery is not an escape from transactions — it is built on one. The
coordinator needs its state row and its outbox row to commit atomically, or it can lose
track of a workflow it already started. So the design bottoms out at exactly one
un-sharded database.

The practical rule, and the arrangement this repo already has: **shard the high-volume
aggregates, and keep coordinator state and its outbox together and un-sharded.** One
consequence worth planning for — the outbox relay claims work through a single index on
a single table, so per-shard outboxes would need per-shard relays and would give up
global delivery ordering.

Note that the saga orchestrators are reference machinery: no production request path
constructs one today.
````

- [ ] **Step 3: Correct the cluster-readiness claim in §6**

In the `### What sharding here does *not* buy` subsection, replace the paragraph beginning "The payoff is that the ring makes the system **ready**" with:

```markdown
The payoff is that the ring makes the system **ready** to map logical shards onto separate
nodes without a data migration: the placement function and the generation-versioned
reshard already exist, so that move becomes a routing change rather than a rewrite. That
readiness currently covers **single-key operations only** — the keys carry no hash tag, so
a shard's record, device-index, and stream keys are not guaranteed to share a Cluster
slot, and no multi-key operation over them can be atomic (sharp edge 7). Read lever 1 as
an investment that makes future horizontal scaling cheap, and lever 4 as the one that adds
capacity today.
```

- [ ] **Step 4: Add two sharp edges**

Append to the `## Sharp edges — notes` numbered list, continuing from the existing 5:

```markdown
6. **A single-shard write is pipelined, not atomic.** `doWrite` sends HSET + ZADD + XADD
   as one pipeline, which is batching, not a transaction. A partial failure can leave a
   record hash with no device-index entry — invisible to per-device reads — or an index
   entry pointing at a record that was never written. The sequence `INCR` is a separate
   round-trip before it, so a failure between the two burns a sequence number and drops
   the record. Retries are the recovery mechanism: a duplicate `eventId` is caught by
   `ZADD NX` returning 0.
7. **Record-store keys carry no hash tag.** Keys are `sr:rec:0:123`, not
   `sr:rec:{0}:123`, so a shard's four keys are not guaranteed to share a Redis Cluster
   slot. No multi-key operation over them — including any atomic fix for sharp edge 6 —
   is Cluster-safe until they are co-located. See §7.
```

- [ ] **Step 5: Verify the documentation gate passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=DocumentedMechanismTest,DocumentationIndexTest
```

Expected: PASS. A failure here almost always means a relative source link in the new
section does not resolve — check every `../../src/main/java/...` path against the
real file.

- [ ] **Step 6: Commit**

```bash
git add docs/system_design/03_DB_Scaling_Sharding.md
git commit -m "docs: explain where sharding ends the single-transaction guarantee

Adds 03 section 7 joining the sharding investigation to the saga/outbox
machinery, and records the pipelined write and the missing hash tags as
sharp edges 6 and 7."
```

---

### Task 2: Cross-document corrections and index

**Files:**
- Modify: `docs/system_design/14_Partitioning.md:48-52`
- Modify: `docs/system_design/15_Eventual_Consistency.md` (§1a, and the §4 sharp-edge list)
- Modify: `README.md:469`

**Interfaces:**
- Consumes: the `## 7` anchor from Task 1.
- Produces: nothing.

- [ ] **Step 1: Scope the claim in 14_Partitioning**

In "Where the shards physically live — Redis and MySQL", replace the clause "and makes it **ready** to map those logical shards onto separate Redis nodes (or a cluster) without a data migration, but today the win is contention-spreading and reshardability, not multi-node capacity." with:

```markdown
and makes it **ready** to map those logical shards onto separate Redis nodes (or a
cluster) without a data migration — for single-key operations. The keys carry no hash
tag, so a shard's keys are not guaranteed to share a Cluster slot and no multi-key
operation over them can be atomic; see
[03 §7](03_DB_Scaling_Sharding.md#7-cross-shard-atomicity--where-the-transaction-stops).
Today the win is contention-spreading and reshardability, not multi-node capacity.
```

- [ ] **Step 2: Add the pointer in 15_Eventual_Consistency §1a**

Append to the end of the `### 1a. Durable saga coordination` subsection:

```markdown
Why this machinery exists at all — which guarantee sharding takes away, and what has to
be rebuilt by hand once a transaction can no longer span the write — is
[03 §7](03_DB_Scaling_Sharding.md#7-cross-shard-atomicity--where-the-transaction-stops).
That section owns the sharding consequence; this one owns the mechanism.
```

- [ ] **Step 3: Add the sharp edge in 15_Eventual_Consistency §4**

Append to the `## 4. Sharp edges worth flagging` numbered list, continuing from the existing 6:

```markdown
7. **A single-shard record write is not atomic.** `ShardedRecordStore` pipelines its
   HSET + ZADD + XADD rather than committing them together, so a partial failure can
   leave a record with no device-index entry or an index entry with no record. Recovery
   is idempotent retry, not rollback — see
   [03 sharp edge 6](03_DB_Scaling_Sharding.md#sharp-edges--notes).
```

- [ ] **Step 4: Extend the README row**

Replace the row-03 description with:

```markdown
| 03 | [DB Scaling & Sharding](docs/system_design/03_DB_Scaling_Sharding.md) | The two Redis sharded stores, versioned topology and online reshard, which scaling lever buys what, and where sharding ends the single-transaction guarantee |
```

- [ ] **Step 5: Verify the documentation gate passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=DocumentedMechanismTest,DocumentationIndexTest
```

Expected: PASS.

- [ ] **Step 6: Commit and open PR1**

```bash
git add docs/system_design/14_Partitioning.md docs/system_design/15_Eventual_Consistency.md README.md
git commit -m "docs: scope the cluster-readiness claim and cross-link 03 section 7

The ring makes the store cluster-ready for single-key operations only;
without hash tags no multi-key operation can be atomic. Points 14 and 15
at the new cross-shard atomicity section."
gh pr create --base main --title "docs: where sharding ends the single-transaction guarantee" --body "$(cat <<'EOF'
## Summary
Stage 1 of the cross-shard atomicity investigation. Documentation only, no behavior change.

Adds `03 §7`, joining the sharding investigation to the saga/outbox machinery that exists
because of it, and records two real gaps in the sharded write path as sharp edges 6 and 7:
`doWrite` pipelines rather than commits, and record-store keys carry no hash tag.

Also scopes the "cluster-ready without a data migration" claim in 03 §6 and 14 — true for
single-key operations, not for multi-key ones.

## Test plan
- `mvn test -Dtest=DocumentedMechanismTest,DocumentationIndexTest` (both are in the PR gate)

## Follow-up
Stage 2 closes sharp edges 6 and 7. Spec:
`docs/superpowers/specs/2026-07-31-cross-shard-atomicity-design.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Stage 2 — Atomic shard write (PR2)

### Task 3: `ShardKeys` and the topology's key format

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/redis/sharding/ShardKeys.java`
- Modify: `src/main/java/com/recsys/infrastructure/redis/sharding/ShardTopology.java`
- Create: `src/test/java/com/recsys/infrastructure/redis/sharding/ShardKeysTest.java`
- Modify: `src/test/java/com/recsys/infrastructure/redis/sharding/ShardTopologyTest.java`

`ShardKeys` and `ShardTopology.keyFormat` land together because neither is testable
without the other: `ShardKeys.of` reads the format off a topology, and a format on the
topology that nothing consumes is dead code. One commit, compiling and green.

**Interfaces:**
- Consumes: `Generations.keyPrefix(int)`.
- Produces, relied on by Tasks 4–7:
  - `ShardKeys.of(String prefix, ShardTopology topology) -> ShardKeys`
  - `new ShardKeys(String prefix, int version, int keyFormat)`
  - `rec(int shardIndex, long seqNum) -> String`
  - `recPrefix(int shardIndex) -> String`
  - `dev(int shardIndex, String deviceId) -> String`
  - `stream(int shardIndex) -> String`
  - `seq(int shardIndex) -> String`
  - `devScanPattern(int shardIndex) -> String`
  - `version() -> int`, `keyFormat() -> int`
  - Constants `ShardKeys.FORMAT_UNTAGGED = 1`, `ShardKeys.FORMAT_TAGGED = 2`
  - `new ShardTopology(int version, int shardCount, int vnodes, long createdAtMs, int keyFormat)`
  - `new ShardTopology(int version, int shardCount, int vnodes, long createdAtMs)` — retained, defaults to `FORMAT_UNTAGGED`
  - `ShardTopology.keyFormat() -> int`

- [ ] **Step 1: Create the branch off PR1's branch**

```bash
git checkout docs/cross-shard-atomicity-investigation
git checkout -b fix/atomic-shard-write
```

- [ ] **Step 2: Write the failing tests**

Create `src/test/java/com/recsys/infrastructure/redis/sharding/ShardKeysTest.java`:

```java
package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShardKeysTest {

    @Test
    void format1_generation1_usesOriginalUnversionedUntaggedKeys() {
        ShardKeys keys = new ShardKeys("sr:", 1, ShardKeys.FORMAT_UNTAGGED);
        assertThat(keys.rec(0, 5L)).isEqualTo("sr:rec:0:5");
        assertThat(keys.dev(0, "dev-1")).isEqualTo("sr:dev:0:dev-1");
        assertThat(keys.stream(0)).isEqualTo("sr:stream:0");
        assertThat(keys.seq(0)).isEqualTo("sr:seq:0");
    }

    @Test
    void format1_generation2_prependsGenerationPrefixOnly() {
        ShardKeys keys = new ShardKeys("sr:", 2, ShardKeys.FORMAT_UNTAGGED);
        assertThat(keys.rec(1, 7L)).isEqualTo("sr:g2:rec:1:7");
        assertThat(keys.dev(1, "dev-1")).isEqualTo("sr:g2:dev:1:dev-1");
        assertThat(keys.stream(1)).isEqualTo("sr:g2:stream:1");
        assertThat(keys.seq(1)).isEqualTo("sr:g2:seq:1");
    }

    @Test
    void format2_wrapsTheShardIndexInAHashTag() {
        ShardKeys keys = new ShardKeys("sr:", 3, ShardKeys.FORMAT_TAGGED);
        assertThat(keys.rec(1, 7L)).isEqualTo("sr:g3:rec:{1}:7");
        assertThat(keys.dev(1, "dev-1")).isEqualTo("sr:g3:dev:{1}:dev-1");
        assertThat(keys.stream(1)).isEqualTo("sr:g3:stream:{1}");
        assertThat(keys.seq(1)).isEqualTo("sr:g3:seq:{1}");
    }

    @Test
    void allKeysOfOneShardShareTheSameHashTagUnderFormat2() {
        ShardKeys keys = new ShardKeys("sr:", 3, ShardKeys.FORMAT_TAGGED);
        assertThat(tagOf(keys.rec(2, 1L)))
                .isEqualTo(tagOf(keys.dev(2, "d")))
                .isEqualTo(tagOf(keys.stream(2)))
                .isEqualTo(tagOf(keys.seq(2)));
    }

    @Test
    void differentShardsGetDifferentHashTags() {
        ShardKeys keys = new ShardKeys("sr:", 3, ShardKeys.FORMAT_TAGGED);
        assertThat(tagOf(keys.stream(0))).isNotEqualTo(tagOf(keys.stream(1)));
    }

    @Test
    void recPrefixConcatenatedWithASequenceEqualsTheRecordKey() {
        ShardKeys tagged = new ShardKeys("sr:", 3, ShardKeys.FORMAT_TAGGED);
        assertThat(tagged.recPrefix(1) + 7L).isEqualTo(tagged.rec(1, 7L));

        ShardKeys untagged = new ShardKeys("sr:", 1, ShardKeys.FORMAT_UNTAGGED);
        assertThat(untagged.recPrefix(0) + 5L).isEqualTo(untagged.rec(0, 5L));
    }

    @Test
    void devScanPatternMatchesTheDeviceKeyNamespace() {
        assertThat(new ShardKeys("sr:", 1, ShardKeys.FORMAT_UNTAGGED).devScanPattern(0))
                .isEqualTo("sr:dev:0:*");
        assertThat(new ShardKeys("sr:", 3, ShardKeys.FORMAT_TAGGED).devScanPattern(0))
                .isEqualTo("sr:g3:dev:{0}:*");
    }

    @Test
    void ofReadsVersionAndFormatFromTheTopology() {
        ShardKeys keys = ShardKeys.of("sr:", new ShardTopology(4, 2, 150, 0L, ShardKeys.FORMAT_TAGGED));
        assertThat(keys.version()).isEqualTo(4);
        assertThat(keys.keyFormat()).isEqualTo(ShardKeys.FORMAT_TAGGED);
        assertThat(keys.stream(0)).isEqualTo("sr:g4:stream:{0}");
    }

    @Test
    void anUnknownKeyFormatIsRejected() {
        assertThatThrownBy(() -> new ShardKeys("sr:", 1, 99))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // The substring between the first '{' and the following '}' — what Redis Cluster hashes.
    private static String tagOf(String key) {
        int open = key.indexOf('{');
        return key.substring(open + 1, key.indexOf('}', open));
    }
}
```

Add `import static org.assertj.core.api.Assertions.assertThatThrownBy;`.

Append to `src/test/java/com/recsys/infrastructure/redis/sharding/ShardTopologyTest.java`:

```java
    @Test
    void keyFormatDefaultsToUntaggedOnTheFourArgConstructor() {
        assertThat(new ShardTopology(1, 2, 150, 0L).keyFormat())
                .isEqualTo(ShardKeys.FORMAT_UNTAGGED);
    }

    @Test
    void keyFormatIsCarriedWhenSuppliedExplicitly() {
        assertThat(new ShardTopology(2, 4, 150, 0L, ShardKeys.FORMAT_TAGGED).keyFormat())
                .isEqualTo(ShardKeys.FORMAT_TAGGED);
    }
```

If `ShardTopologyTest.java` lacks the AssertJ import, add
`import static org.assertj.core.api.Assertions.assertThat;`.

- [ ] **Step 3: Run the tests to verify they fail**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=ShardKeysTest,ShardTopologyTest
```

Expected: FAIL — compilation error; neither `ShardKeys` nor `ShardTopology.keyFormat()` exists.

- [ ] **Step 4: Write `ShardKeys`**

Create `src/main/java/com/recsys/infrastructure/redis/sharding/ShardKeys.java`:

```java
package com.recsys.infrastructure.redis.sharding;

import java.util.Objects;

/**
 * The single owner of the sharded record store's key scheme.
 *
 * <p>A key is {@code {prefix}{generation}{kind}:{shardToken}:{suffix}}. The shard token is
 * the bare shard index under {@link #FORMAT_UNTAGGED} and a Redis Cluster hash tag —
 * {@code {0}} — under {@link #FORMAT_TAGGED}. The tag co-locates every key belonging to one
 * shard in a single Cluster slot, which is what allows a multi-key script to run over them
 * atomically.
 *
 * <p>The format travels with the topology generation rather than with the deployment, so a
 * generation written under one format keeps that format for its whole life and the existing
 * dual-read window can span a format change.
 */
public final class ShardKeys {

    /** Original scheme: bare shard index, no Cluster slot guarantee. */
    public static final int FORMAT_UNTAGGED = 1;
    /** Shard index wrapped in a Redis Cluster hash tag, co-locating a shard's keys. */
    public static final int FORMAT_TAGGED = 2;

    private final String prefix;
    private final int version;
    private final int keyFormat;

    public ShardKeys(String prefix, int version, int keyFormat) {
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
        if (keyFormat != FORMAT_UNTAGGED && keyFormat != FORMAT_TAGGED) {
            throw new IllegalArgumentException("unknown key format: " + keyFormat);
        }
        this.version = version;
        this.keyFormat = keyFormat;
    }

    public static ShardKeys of(String prefix, ShardTopology topology) {
        Objects.requireNonNull(topology, "topology");
        return new ShardKeys(prefix, topology.version(), topology.keyFormat());
    }

    public int version()   { return version; }
    public int keyFormat() { return keyFormat; }

    public String rec(int shardIndex, long seqNum)     { return recPrefix(shardIndex) + seqNum; }
    public String dev(int shardIndex, String deviceId) { return base("dev", shardIndex) + ":" + deviceId; }
    public String stream(int shardIndex)               { return base("stream", shardIndex); }
    public String seq(int shardIndex)                  { return base("seq", shardIndex); }

    /** Everything before the sequence number, so a Lua script can build the record key itself. */
    public String recPrefix(int shardIndex) { return base("rec", shardIndex) + ":"; }

    /**
     * Glob for every device key in this shard. Braces are literal in Redis glob patterns —
     * only {@code *}, {@code ?}, {@code [...]} and {@code \} are special — so a tagged
     * pattern matches tagged keys exactly.
     */
    public String devScanPattern(int shardIndex) { return base("dev", shardIndex) + ":*"; }

    private String base(String kind, int shardIndex) {
        return prefix + Generations.keyPrefix(version) + kind + ":" + shardToken(shardIndex);
    }

    private String shardToken(int shardIndex) {
        return keyFormat == FORMAT_TAGGED ? "{" + shardIndex + "}" : Integer.toString(shardIndex);
    }
}
```

- [ ] **Step 5: Add `keyFormat` to `ShardTopology`**

Replace the fields and constructor, and add the accessor. Keep every existing accessor
unchanged:

```java
    private final int version;
    private final int shardCount;
    private final int vnodes;
    private final long createdAtMs;
    private final int keyFormat;
    private final ConsistentHashRing ring;

    public ShardTopology(int version, int shardCount, int vnodes, long createdAtMs) {
        this(version, shardCount, vnodes, createdAtMs, ShardKeys.FORMAT_UNTAGGED);
    }

    public ShardTopology(int version, int shardCount, int vnodes, long createdAtMs, int keyFormat) {
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
        this.version = version;
        this.shardCount = shardCount;          // ConsistentHashRing validates >= 1
        this.vnodes = vnodes;                  // ConsistentHashRing validates >= 1
        this.createdAtMs = createdAtMs;
        this.keyFormat = keyFormat;
        this.ring = new ConsistentHashRing(shardCount, vnodes);
    }

    public int keyFormat() { return keyFormat; }
```

The four-argument constructor is retained deliberately: it keeps every existing call site
and test compiling, and defaulting to `FORMAT_UNTAGGED` is the correct reading of a
topology that predates the field.

- [ ] **Step 6: Run the tests to verify they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=ShardKeysTest,ShardTopologyTest
```

Expected: PASS, all tests in both classes.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/redis/sharding/ShardKeys.java \
        src/main/java/com/recsys/infrastructure/redis/sharding/ShardTopology.java \
        src/test/java/com/recsys/infrastructure/redis/sharding/ShardKeysTest.java \
        src/test/java/com/recsys/infrastructure/redis/sharding/ShardTopologyTest.java
git commit -m "feat: add ShardKeys and a per-generation key format

Introduces a key format alongside the existing generation prefix. Format 2
wraps the shard index in a Redis Cluster hash tag so all of a shard's keys
share one slot, which a multi-key script requires. The format lives on the
topology generation, not on the deployment."
```

---

### Task 4: Persist the key format in the topology snapshot

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/redis/sharding/ShardTopologyStore.java`
- Create: `src/test/java/com/recsys/infrastructure/redis/sharding/ShardTopologySnapshotFormatTest.java`

**Interfaces:**
- Consumes: `ShardKeys.FORMAT_UNTAGGED`, `ShardKeys.FORMAT_TAGGED` (Task 3).
- Produces, relied on by Task 5:
  - `Snapshot` gains trailing components `Integer keyFormat, Integer prevKeyFormat`
  - `Snapshot.effectiveKeyFormat() -> int` (null → `FORMAT_UNTAGGED`)
  - `Snapshot.effectivePrevKeyFormat() -> int` (null → `FORMAT_UNTAGGED`)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/infrastructure/redis/sharding/ShardTopologySnapshotFormatTest.java`:

```java
package com.recsys.infrastructure.redis.sharding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Backward compatibility of the shard:topology document. A snapshot written before the
 * keyFormat field existed must still parse, and must read as the untagged format.
 */
class ShardTopologySnapshotFormatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void legacyJsonWithoutKeyFormatReadsAsUntagged() throws Exception {
        String legacy = """
                {"version":1,"shardCount":2,"vnodes":150,"createdAtMs":1000,
                 "prevVersion":null,"prevShardCount":null,"prevExpiresAtMs":null}""";

        ShardTopologyStore.Snapshot snapshot =
                MAPPER.readValue(legacy, ShardTopologyStore.Snapshot.class);

        assertThat(snapshot.version()).isEqualTo(1);
        assertThat(snapshot.effectiveKeyFormat()).isEqualTo(ShardKeys.FORMAT_UNTAGGED);
        assertThat(snapshot.effectivePrevKeyFormat()).isEqualTo(ShardKeys.FORMAT_UNTAGGED);
    }

    @Test
    void keyFormatRoundTrips() throws Exception {
        ShardTopologyStore.Snapshot original = new ShardTopologyStore.Snapshot(
                3, 4, 150, 2000L, 2, 2, 9000L, ShardKeys.FORMAT_TAGGED, ShardKeys.FORMAT_UNTAGGED);

        ShardTopologyStore.Snapshot parsed = MAPPER.readValue(
                MAPPER.writeValueAsString(original), ShardTopologyStore.Snapshot.class);

        assertThat(parsed.effectiveKeyFormat()).isEqualTo(ShardKeys.FORMAT_TAGGED);
        assertThat(parsed.effectivePrevKeyFormat()).isEqualTo(ShardKeys.FORMAT_UNTAGGED);
    }
}
```

The store's own mapper must also tolerate unknown fields, so a pod on an older build can
still read a document a newer build wrote. Assert that on the store's mapper rather than a
local one — add this test to the same class:

```java
    @Test
    void theStoresMapperIgnoresUnknownFields() {
        // A newer writer may add fields this build does not know. The store must keep parsing,
        // or an old pod fails-static on its last-good topology and stops seeing updates.
        String futureJson = """
                {"version":1,"shardCount":2,"vnodes":150,"createdAtMs":1000,
                 "prevVersion":null,"prevShardCount":null,"prevExpiresAtMs":null,
                 "somethingAddedLater":"x"}""";

        assertThat(ShardTopologyStore.parseForTest(futureJson).version()).isEqualTo(1);
    }
```

- [ ] **Step 2: Run to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=ShardTopologySnapshotFormatTest
```

Expected: FAIL — `Snapshot` has seven components, and neither `effectiveKeyFormat()` nor
`parseForTest` exists.

- [ ] **Step 3: Extend `Snapshot` and make parsing tolerant**

Replace the `MAPPER` field, add `import com.fasterxml.jackson.databind.DeserializationFeature;`:

```java
    private static final ObjectMapper MAPPER = new ObjectMapper()
            // A newer writer may add fields this build does not know. Ignoring them keeps a
            // mixed-version fleet readable in both directions rather than fail-static.
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
```

Add a package-private seam so the test can exercise the store's own mapper, next to `parse`:

```java
    /** Package-private seam: parse through the store's configured mapper. */
    static Snapshot parseForTest(String json) {
        return parse(json);
    }
```

Replace the `Snapshot` record:

```java
    /**
     * The stored topology document. {@code keyFormat} and {@code prevKeyFormat} are boxed
     * because documents written before the field existed omit them entirely; absent reads as
     * {@link ShardKeys#FORMAT_UNTAGGED}, which is what those documents' keys actually use.
     */
    public record Snapshot(
            int version,
            int shardCount,
            int vnodes,
            long createdAtMs,
            Integer prevVersion,
            Integer prevShardCount,
            Long prevExpiresAtMs,
            Integer keyFormat,
            Integer prevKeyFormat
    ) {
        public int effectiveKeyFormat() {
            return keyFormat == null ? ShardKeys.FORMAT_UNTAGGED : keyFormat;
        }

        public int effectivePrevKeyFormat() {
            return prevKeyFormat == null ? ShardKeys.FORMAT_UNTAGGED : prevKeyFormat;
        }
    }
```

- [ ] **Step 4: Write format 2 on bootstrap**

```java
    /** First-writer-wins create of version 1; returns the effective snapshot (existing or new). */
    public Snapshot bootstrap(int shardCount, int vnodes, long nowMs) {
        // A fresh keyspace has no legacy records to preserve, so it starts tagged. SETNX means
        // an existing generation 1 — which is untagged — is never rewritten.
        Snapshot v1 = new Snapshot(1, shardCount, vnodes, nowMs, null, null, null,
                ShardKeys.FORMAT_TAGGED, null);
        exec.execute(c -> c.set(key, write(v1), SetArgs.Builder.nx()));
        return load();
    }
```

- [ ] **Step 5: Write format 2 on reshard**

In `PUBLISH_LUA`, extend the `next` table. The previous generation carries forward whatever
format it was written under, defaulting to untagged for a document that predates the field:

```lua
            local next = {
              version = t.version + 1,
              shardCount = newShard,
              vnodes = t.vnodes,
              createdAtMs = nowMs,
              prevVersion = t.version,
              prevShardCount = t.shardCount,
              prevExpiresAtMs = nowMs + windowMs,
              keyFormat = 2,
              prevKeyFormat = t.keyFormat or 1
            }
```

- [ ] **Step 6: Run the tests to verify they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=ShardTopologySnapshotFormatTest,ShardKeysTest,ShardTopologyTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/redis/sharding/ShardTopologyStore.java \
        src/test/java/com/recsys/infrastructure/redis/sharding/ShardTopologySnapshotFormatTest.java
git commit -m "feat: persist keyFormat in the shard topology document

Bootstrap and reshard write format 2; an absent field reads as format 1 so
existing deployments are unaffected until an operator reshards. Parsing now
ignores unknown fields so a mixed-version fleet stays readable."
```

---

### Task 5: Carry the format onto both generations in the provider

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/redis/sharding/ShardTopologyProvider.java:39-67, 85-101`
- Modify: `src/test/java/com/recsys/infrastructure/redis/sharding/ShardTopologyProviderTest.java`

**Interfaces:**
- Consumes: `Snapshot.effectiveKeyFormat()` / `effectivePrevKeyFormat()` (Task 4), the
  five-argument `ShardTopology` constructor (Task 3).
- Produces, relied on by Task 7:
  - `ShardTopologyProvider.fixedAtVersion(int version, int shardCount, int vnodes, int keyFormat)`
  - `current()` and `previousIfActive()` return topologies carrying their own format

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/com/recsys/infrastructure/redis/sharding/ShardTopologyProviderTest.java`,
following that file's existing store-stubbing pattern — read it first and reuse whatever
double it already uses for `ShardTopologyStore`:

```java
    @Test
    void refreshCarriesEachGenerationsOwnKeyFormat() {
        // A reshard from an untagged generation leaves current tagged and previous untagged.
        // Both must keep their own format, or the dual-read builds keys the writer never wrote.
        ShardTopologyStore.Snapshot stored = new ShardTopologyStore.Snapshot(
                2, 4, 150, 1_000L, 1, 2, Long.MAX_VALUE,
                ShardKeys.FORMAT_TAGGED, ShardKeys.FORMAT_UNTAGGED);

        ShardTopologyProvider provider = providerReading(stored);
        provider.refresh();

        assertThat(provider.current().keyFormat()).isEqualTo(ShardKeys.FORMAT_TAGGED);
        assertThat(provider.previousIfActive().keyFormat()).isEqualTo(ShardKeys.FORMAT_UNTAGGED);
    }

    @Test
    void aLegacyDocumentWithoutTheFieldYieldsUntaggedGenerations() {
        ShardTopologyStore.Snapshot legacy = new ShardTopologyStore.Snapshot(
                2, 4, 150, 1_000L, 1, 2, Long.MAX_VALUE, null, null);

        ShardTopologyProvider provider = providerReading(legacy);
        provider.refresh();

        assertThat(provider.current().keyFormat()).isEqualTo(ShardKeys.FORMAT_UNTAGGED);
        assertThat(provider.previousIfActive().keyFormat()).isEqualTo(ShardKeys.FORMAT_UNTAGGED);
    }
```

Write the `providerReading(...)` helper to match the file's existing conventions: a
`ShardTopologyStore` double whose `load()` returns the given snapshot, wrapped in a
provider whose clock is below `prevExpiresAtMs` so `previousIfActive()` is non-null.

- [ ] **Step 2: Run to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=ShardTopologyProviderTest
```

Expected: FAIL — `keyFormat()` is always `FORMAT_UNTAGGED` because `refresh()` builds both
generations with the four-argument constructor.

- [ ] **Step 3: Plumb the format through `refresh()`**

```java
            ShardTopology current = new ShardTopology(s.version(), s.shardCount(), s.vnodes(),
                    s.createdAtMs(), s.effectiveKeyFormat());
            ShardTopology previous = null;
            long prevExpiresAtMs = Long.MIN_VALUE;
            if (s.prevVersion() != null && s.prevShardCount() != null && s.prevExpiresAtMs() != null) {
                previous = new ShardTopology(s.prevVersion(), s.prevShardCount(), s.vnodes(),
                        s.createdAtMs(), s.effectivePrevKeyFormat());
                prevExpiresAtMs = s.prevExpiresAtMs();
            }
```

- [ ] **Step 4: Add the format-aware test factory**

Next to the existing `fixedAtVersion`:

```java
    /** Constant provider pinned at an explicit version and key format — test/helper use. */
    public static ShardTopologyProvider fixedAtVersion(int version, int shardCount, int vnodes,
                                                       int keyFormat) {
        return new ShardTopologyProvider(
                new ShardTopology(version, shardCount, vnodes, 0L, keyFormat));
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=ShardTopologyProviderTest,ShardTopologySnapshotFormatTest,ShardKeysTest,ShardTopologyTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/redis/sharding/ShardTopologyProvider.java \
        src/test/java/com/recsys/infrastructure/redis/sharding/ShardTopologyProviderTest.java
git commit -m "feat: carry each generation's key format through the provider

Current and previous generations keep their own format, so the existing
dual-read window can span a format change without building keys the writer
never wrote."
```

---

### Task 6: Point `SequenceGenerator` at `ShardKeys`

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/redis/sharding/SequenceGenerator.java`
- Modify: `src/main/java/com/recsys/api/online/OnlinePredictionServer.java:340`
- Modify: `src/test/java/com/recsys/infrastructure/redis/sharding/SequenceGeneratorGenerationTest.java`
- Modify: `src/test/java/com/recsys/infrastructure/redis/sharding/SequenceGeneratorTest.java`

**Interfaces:**
- Consumes: `ShardKeys` and `ShardTopology.keyFormat()` (Task 3).
- Produces, relied on by Task 7:
  - `next(ShardTopology topology, int shardIndex) -> long`
  - `ensureCounterValid(ShardTopology topology, int shardIndex, long budgetMs) -> boolean`

- [ ] **Step 1: Write the failing test**

Append to `SequenceGeneratorGenerationTest.java`:

```java
    @Test
    void taggedGenerationIncrementsTheTaggedCounterKey() {
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any())).thenAnswer(invocation -> {
            Function<RedisCommands<String, String>, Object> fn = invocation.getArgument(0);
            return fn.apply(commands);
        });
        when(commands.incr(anyString())).thenReturn(1L);

        new SequenceGenerator(exec, "sr:")
                .next(new ShardTopology(3, 2, 150, 0L, ShardKeys.FORMAT_TAGGED), 1);

        // The counter INCR must target the tagged key, or it will not share a Cluster slot
        // with the record and index keys the write script touches.
        verify(commands).incr("sr:g3:seq:{1}");
    }

    @Test
    void taggedGenerationReadsAndRepairsTheTaggedCounterKey() {
        // ensureCounterValid GETs the counter before deciding whether to raise it. Asserting
        // the key it reads proves the repair path follows the generation's format too.
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any())).thenAnswer(invocation -> {
            Function<RedisCommands<String, String>, Object> fn = invocation.getArgument(0);
            return fn.apply(commands);
        });
        KeyScanCursor<String> cursor = mock(KeyScanCursor.class);
        when(cursor.getKeys()).thenReturn(List.of("sr:g3:dev:{1}:dev-1"));
        when(cursor.isFinished()).thenReturn(true);
        when(commands.scan(any(ScanArgs.class))).thenReturn(cursor);
        when(commands.zrevrangebyscoreWithScores(anyString(), any(Range.class), any(Limit.class)))
                .thenReturn(List.of(ScoredValue.just(9.0, "evt-9")));
        when(commands.get(anyString())).thenReturn("3");

        new SequenceGenerator(exec, "sr:")
                .ensureCounterValid(new ShardTopology(3, 2, 150, 0L, ShardKeys.FORMAT_TAGGED),
                        1, 30_000L);

        verify(commands).get("sr:g3:seq:{1}");
        verify(commands).set("sr:g3:seq:{1}", "10");
    }
```

Add whatever imports the file lacks: `org.mockito.ArgumentMatchers.any`,
`org.mockito.ArgumentMatchers.anyString`, `org.mockito.Mockito.mock`,
`org.mockito.Mockito.verify`, `org.mockito.Mockito.when`,
`io.lettuce.core.api.sync.RedisCommands`, `io.lettuce.core.KeyScanCursor`,
`io.lettuce.core.Limit`, `io.lettuce.core.Range`, `io.lettuce.core.ScanArgs`,
`io.lettuce.core.ScoredValue`, `java.util.List`, `java.util.function.Function`.

Two notes. If the existing tests in this file already stub `RedisExecutor`, reuse that
helper rather than repeating the `thenAnswer` block. And do not assert inside a Mockito
matcher: a matcher that runs verifications can pass without asserting anything. The SCAN
pattern itself is covered by `ShardKeysTest.devScanPatternMatchesTheDeviceKeyNamespace`
and by Task 8's Docker test — `ScanArgs` exposes no getter for its MATCH pattern, so do
not try to assert it here.

- [ ] **Step 2: Run to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=SequenceGeneratorGenerationTest
```

Expected: FAIL — `next(ShardTopology, int)` is undefined.

- [ ] **Step 3: Rewrite the key construction**

In `SequenceGenerator`, replace the `next` overloads, the `seqKey` helper, and the scan
pattern so every key comes from `ShardKeys`:

```java
    /** Next sequence for this generation's shard. Always >= 1. */
    public long next(ShardTopology topology, int shardIndex) {
        String key = ShardKeys.of(prefix, topology).seq(shardIndex);
        return exec.execute(c -> c.incr(key));
    }

    /** Back-compat: version-1, untagged sequence. */
    public long next(int shardIndex) {
        return next(new ShardTopology(1, shardIndex + 1, 150, 0L), shardIndex);
    }
```

Change `ensureCounterValid` and `findMaxSeqInShard` to take a `ShardTopology`:

```java
    public boolean ensureCounterValid(ShardTopology topology, int shardIndex, long budgetMs) {
        ShardKeys keys = ShardKeys.of(prefix, topology);
        ScanResult scan = findMaxSeqInShard(keys, shardIndex, budgetMs);
        if (scan.maxSeq() <= 0) return scan.completed();

        String key = keys.seq(shardIndex);
        String current = exec.execute(c -> c.get(key));
        long currentVal = current == null ? 0L : Long.parseLong(current);
        if (currentVal < scan.maxSeq()) {
            exec.execute(c -> c.set(key, String.valueOf(scan.maxSeq() + 1)));
        }
        return scan.completed();
    }

    private ScanResult findMaxSeqInShard(ShardKeys keys, int shardIndex, long budgetMs) {
        ScanArgs params = ScanArgs.Builder.matches(keys.devScanPattern(shardIndex)).limit(200);
        long deadline = clockMs.getAsLong() + Math.max(1L, budgetMs);
        // ... body unchanged from here down
```

Delete the now-unused `private String seqKey(int, int)` and the `Generations` import if it
becomes unused. Keep the existing Javadoc on `ensureCounterValid` — `DocumentedMechanismTest`
asserts that documented mechanisms still have callers, and the doc text references this
method by name.

- [ ] **Step 4: Update the caller**

`src/main/java/com/recsys/api/online/OnlinePredictionServer.java:340` currently reads
`seqGen.ensureCounterValid(topo.version(), shard, budgetMs)`. The surrounding code already
holds `topo` as a `ShardTopology`, so:

```java
                    if (!seqGen.ensureCounterValid(topo, shard, budgetMs)) {
```

- [ ] **Step 5: Update existing tests to the new signature**

In `SequenceGeneratorGenerationTest.java` and `SequenceGeneratorTest.java`, replace every
`ensureCounterValid(N, shard, budget)` with
`ensureCounterValid(new ShardTopology(N, shard + 1, 150, 0L), shard, budget)`. The
`next(int)` calls need no change.

- [ ] **Step 6: Run the tests to verify they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=SequenceGeneratorTest,SequenceGeneratorGenerationTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/redis/sharding/SequenceGenerator.java \
        src/main/java/com/recsys/api/online/OnlinePredictionServer.java \
        src/test/java/com/recsys/infrastructure/redis/sharding/SequenceGeneratorTest.java \
        src/test/java/com/recsys/infrastructure/redis/sharding/SequenceGeneratorGenerationTest.java
git commit -m "refactor: build sequence-counter keys through ShardKeys

The counter and the device-scan pattern must follow the generation's key
format, or the startup repair scans the wrong keyspace after a reshard."
```

---

### Task 7: Replace the pipelined write with one Lua script

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStore.java:20-32, 75-128, 154-169, 187-242, 256-268`
- Modify: `src/test/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStoreGenerationKeyTest.java`
- Create: `src/test/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStoreAtomicWriteTest.java`

**Interfaces:**
- Consumes: `ShardKeys` and `ShardTopology.keyFormat()` (Task 3), `ShardTopologyProvider.fixedAtVersion(int, int, int, int)` (Task 5), `SequenceGenerator.next(ShardTopology, int)` (Task 6).
- Produces: unchanged public API — `write(ShardedRecord)`, `write(ShardedRecord, int)`, `update(ShardedRecord)` all still return `WriteResult(seqNum, shardIndex, status)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStoreAtomicWriteTest.java`:

```java
package com.recsys.infrastructure.redis.sharding;

import com.recsys.infrastructure.redis.RedisExecutor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The write path issues exactly one Redis command — the script — and derives its status
 * from the script's return value.
 */
class ShardedRecordStoreAtomicWriteTest {

    private final RedisCommands<String, String> commands = mock(RedisCommands.class);
    private final RedisExecutor exec = mock(RedisExecutor.class);

    private ShardedRecordStore storeReturning(long seq, long zadd, int keyFormat) {
        when(exec.execute(any())).thenAnswer(invocation -> {
            Function<RedisCommands<String, String>, Object> fn = invocation.getArgument(0);
            return fn.apply(commands);
        });
        when(commands.eval(anyString(), eq(ScriptOutputType.MULTI), any(String[].class),
                any(String[].class))).thenReturn(List.of(seq, zadd));
        ShardTopologyProvider provider = ShardTopologyProvider.fixedAtVersion(2, 2, 150, keyFormat);
        return new ShardedRecordStore(exec, exec, provider, new SequenceGenerator(exec, "sr:"), "sr:");
    }

    private static ShardedRecord record() {
        return new ShardedRecord("dev-1", 0L, RecordType.EVENT, "evt-1", "{}", 1234L);
    }

    @Test
    void insertReturnsOkAndTheScriptAssignedSequence() {
        ShardedRecordStore store = storeReturning(42L, 1L, ShardKeys.FORMAT_TAGGED);

        WriteResult result = store.write(record());

        assertThat(result.seqNum()).isEqualTo(42L);
        assertThat(result.status()).isEqualTo(WriteStatus.OK);
    }

    @Test
    void insertWithZeroZaddIsADuplicate() {
        ShardedRecordStore store = storeReturning(42L, 0L, ShardKeys.FORMAT_TAGGED);

        assertThat(store.write(record()).status()).isEqualTo(WriteStatus.DUPLICATE);
    }

    @Test
    void updateWithZeroZaddIsNotADuplicate() {
        // ZADD XX GT returns 0 when the element exists but its score did not advance. That is
        // an ordinary non-advancing update, not a duplicate, and must stay OK.
        ShardedRecordStore store = storeReturning(42L, 0L, ShardKeys.FORMAT_TAGGED);

        assertThat(store.update(record()).status()).isEqualTo(WriteStatus.OK);
    }

    @Test
    void theScriptReceivesTaggedKeysAndTheRecordKeyPrefix() {
        ShardedRecordStore store = storeReturning(42L, 1L, ShardKeys.FORMAT_TAGGED);
        int shard = new ConsistentHashRing(2, 150).shardFor("dev-1");

        store.write(record());

        verify(commands).eval(anyString(), eq(ScriptOutputType.MULTI),
                eq(new String[]{
                        "sr:g2:seq:{" + shard + "}",
                        "sr:g2:dev:{" + shard + "}:dev-1",
                        "sr:g2:stream:{" + shard + "}"}),
                any(String[].class));
    }

    @Test
    void anUntaggedGenerationStillUsesUntaggedKeys() {
        ShardedRecordStore store = storeReturning(42L, 1L, ShardKeys.FORMAT_UNTAGGED);
        int shard = new ConsistentHashRing(2, 150).shardFor("dev-1");

        store.write(record());

        verify(commands).eval(anyString(), eq(ScriptOutputType.MULTI),
                eq(new String[]{
                        "sr:g2:seq:" + shard,
                        "sr:g2:dev:" + shard + ":dev-1",
                        "sr:g2:stream:" + shard}),
                any(String[].class));
    }
}
```

If `ShardedRecord`'s constructor signature differs from the one used above, adapt
`record()` to the real one — check `ShardedRecord.java` first.

- [ ] **Step 2: Run to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=ShardedRecordStoreAtomicWriteTest
```

Expected: FAIL — the current write path calls `executePipelined`, not `eval`, so the
verifications on `commands.eval` are never satisfied.

- [ ] **Step 3: Add the script and rewrite `doWrite`**

Add the constant beside `STREAM_MAXLEN`:

```java
    /**
     * One atomic write: assign the sequence, claim the device index, then store the record and
     * append to the stream. Returns {seq, zaddResult}.
     *
     * <p>On an insert whose event ID is already indexed, the script returns before writing
     * anything else — a retry therefore neither burns a record key nor appends a duplicate
     * stream entry. On an update it always proceeds, because ZADD XX GT legitimately returns 0
     * for a non-advancing score.
     *
     * <p>The record key is built inside the script because its sequence number does not exist
     * until the INCR runs. Under the tagged key format every key here shares one hash tag, so
     * the constructed key lands in the same Cluster slot as the declared ones.
     */
    private static final String WRITE_RECORD_LUA = """
            local seq = redis.call('INCR', KEYS[1])
            local isUpdate = ARGV[7] == '1'
            local zadd
            if isUpdate then
              zadd = redis.call('ZADD', KEYS[2], 'XX', 'GT', seq, ARGV[3])
            else
              zadd = redis.call('ZADD', KEYS[2], 'NX', seq, ARGV[3])
              if zadd == 0 then return {seq, 0} end
            end
            local recKey = ARGV[1] .. seq
            redis.call('HSET', recKey,
              'deviceId', ARGV[2], 'type', ARGV[8], 'eventId', ARGV[3],
              'payload', ARGV[4], 'timestamp', ARGV[5])
            local ttl = tonumber(ARGV[6])
            if ttl > 0 then redis.call('EXPIRE', recKey, ttl) end
            redis.call('XADD', KEYS[3], 'MAXLEN', '~', ARGV[9], '*',
              'deviceId', ARGV[2], 'seq', seq, 'type', ARGV[8], 'eventId', ARGV[3])
            return {seq, zadd}
            """;
```

Replace the whole body of `doWrite` (from `ShardTopology topo = provider.current();` through
the `return new WriteResult(...)`):

```java
    private WriteResult doWrite(ShardedRecord record, boolean isUpdate, int ttlSeconds) {
        ShardTopology topo = provider.current();
        int shardIndex = topo.shardFor(record.deviceId());
        ShardKeys keys = ShardKeys.of(prefix, topo);

        List<Long> result = writeExec.execute(c -> c.eval(WRITE_RECORD_LUA, ScriptOutputType.MULTI,
                new String[]{
                        keys.seq(shardIndex),
                        keys.dev(shardIndex, record.deviceId()),
                        keys.stream(shardIndex)},
                keys.recPrefix(shardIndex),
                record.deviceId(),
                record.eventId(),
                record.payload() != null ? record.payload() : "",
                String.valueOf(record.timestamp()),
                Integer.toString(ttlSeconds),
                isUpdate ? "1" : "0",
                record.type().name(),
                Long.toString(STREAM_MAXLEN)));

        long seqNum = result.get(0);
        long zadd   = result.get(1);
        WriteStatus status = (!isUpdate && zadd == 0L) ? WriteStatus.DUPLICATE : WriteStatus.OK;
        return new WriteResult(seqNum, shardIndex, status);
    }
```

Add `import io.lettuce.core.ScriptOutputType;`. Remove the now-unused `ZAddArgs` and
`XAddArgs` imports if nothing else references them.

Update the class Javadoc, which currently documents the two-step pipeline and refers to a
"Task 6" that no longer means anything:

```java
/**
 * Redis-backed sharded record store.
 *
 * Write path: one Lua script per write — INCR the shard's sequence counter, claim the device
 * index, store the record hash, and append to the shard stream, atomically.
 *
 * Read paths: per-device reads dual-read the current and previous generation during a
 * migration window; shard reads are current-generation only.
 */
```

- [ ] **Step 4: Move the read paths onto `ShardKeys`**

Replace the three key helpers at the bottom of the class with nothing — `ShardKeys` owns
them now — and thread a `ShardKeys` through the readers:

- `readDevice`: build `ShardKeys.of(prefix, cur)` and, when `prev != null`,
  `ShardKeys.of(prefix, prev)`; pass each to `readDeviceAt`.
- `readDeviceAt(int version, int shardIndex, ...)` becomes
  `readDeviceAt(ShardKeys keys, int shardIndex, ...)`; its first line becomes
  `String devKey = keys.dev(shardIndex, deviceId);` and its `fetchRecords` call passes `keys`.
- `readShard`: replace `int version = provider.current().version();` and
  `String streamKey = streamKey(version, shardIndex);` with
  `ShardKeys keys = ShardKeys.of(prefix, provider.current());` and
  `String streamKey = keys.stream(shardIndex);`; pass `keys` to `fetchRecords`.
- `fetchRecords(int version, int shardIndex, List<Long> seqNums)` becomes
  `fetchRecords(ShardKeys keys, int shardIndex, List<Long> seqNums)`, and its
  `recKey(version, shardIndex, ...)` call becomes `keys.rec(shardIndex, seqNums.get(i))`.

- [ ] **Step 5: Retarget the old key test**

`ShardedRecordStoreGenerationKeyTest` asserts on the deleted helpers. Its golden strings now
live in `ShardKeysTest`, so replace the whole file with a test of the one thing `ShardKeys`
cannot cover — that the store resolves the format from the topology it is given:

```java
package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The store derives its key scheme from the generation it is serving, not from config. */
class ShardedRecordStoreGenerationKeyTest {

    @Test
    void keysFollowTheGenerationsOwnFormat() {
        assertThat(ShardKeys.of("sr:", new ShardTopology(1, 2, 150, 0L)).stream(0))
                .isEqualTo("sr:stream:0");
        assertThat(ShardKeys.of("sr:", new ShardTopology(2, 4, 150, 0L, ShardKeys.FORMAT_TAGGED))
                .stream(1)).isEqualTo("sr:g2:stream:{1}");
    }
}
```

- [ ] **Step 6: Run the full sharding suite**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='Shard*Test,SequenceGenerator*Test'
```

Expected: PASS, except any `@Tag("docker")` class, which is skipped without Docker. If
`ShardedRecordStoreWriteTest` or `ShardedRecordStoreTtlTest` assert on pipelined calls, they
must be updated to assert on the script — do that now rather than weakening them.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStore.java \
        src/test/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStoreAtomicWriteTest.java \
        src/test/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStoreGenerationKeyTest.java
git commit -m "fix: make the single-shard record write atomic

Replaces the HSET+ZADD+XADD pipeline with one Lua script that also assigns
the sequence number, so a partial failure can no longer leave a record
without its index entry, and a failure after INCR can no longer burn a
sequence and drop the record.

Behavior change: a duplicate insert now returns before writing anything, so
retries no longer append duplicate stream entries or orphan record keys.
Non-advancing updates are unaffected."
```

---

### Task 8: Prove atomicity and the format migration against real Redis

**Files:**
- Create: `src/test/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStoreAtomicWriteIntegrationTest.java`

**Interfaces:**
- Consumes: everything from Tasks 3–7.
- Produces: nothing.

These are `@Tag("docker")` and therefore **do not gate the PR**. They are the verification
that the unit tests' mocked `eval` corresponds to real Redis behavior, and must be run
locally before requesting review.

- [ ] **Step 1: Write the tests**

Follow `RedisShardingTestBase` for container setup — read it first and reuse its executor
and lifecycle rather than standing up a new container.

```java
package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("docker")
class ShardedRecordStoreAtomicWriteIntegrationTest extends RedisShardingTestBase {

    @Test
    void writeStoresRecordIndexAndStreamTogether() {
        // Given a tagged generation, when a record is written, then all three structures exist
        // and agree on the sequence number the script assigned.
    }

    @Test
    void duplicateInsertWritesNothingBeyondTheFirst() {
        // Write the same eventId twice. Assert: status DUPLICATE on the second, the stream has
        // exactly one entry, and no record key exists at the second INCR's sequence number.
    }

    @Test
    void nonAdvancingUpdateStillRefreshesTheRecordAndAppendsToTheStream() {
        // update() with a score that does not advance must leave the hash refreshed and add a
        // stream entry — the behavior ZADD XX GT preserves.
    }

    @Test
    void deviceReadMergesAnUntaggedPreviousWithATaggedCurrentGeneration() {
        // Write under a format-1 generation, publish a reshard (which writes format 2), write
        // again, and assert readDevice returns both records.
    }

    @Test
    void ensureCounterValidRepairsATaggedGenerationsCounter() {
        // Seed a tagged device ZSet with a high score, set the counter behind it, run the
        // repair, and assert the counter was raised.
    }

    @Test
    void everyArgvFieldLandsInTheRightPlace() {
        // Write one record with distinct values in every field, then assert the stored hash's
        // deviceId/type/eventId/payload/timestamp and the stream entry's deviceId/seq/type/
        // eventId each carry the value they were given. Catches a positional ARGV transposition
        // — the failure mode that corrupts every record while still returning OK.
    }

    @Test
    void zeroTtlLeavesTheRecordKeyWithoutAnExpiry() {
        // write(record) with no TTL argument must leave TTL == -1 on the record key, not -2 and
        // not a positive value. Only the positive-TTL case is covered today.
    }

    @Test
    void theSequenceRendersAsAnIntegerNotAFloat() {
        // The script concatenates a Lua number into the record key. Assert the key at
        // "<recPrefix><seq>" exists and that "<recPrefix><seq>.0" does not, so a %.14g
        // rendering regression is caught rather than silently producing unreadable keys.
    }
}
```

Fill in each body against the real store — the comments state the exact assertion each test
owes. Do not leave a body empty; an empty `@Test` passes and proves nothing.

**Use a `FORMAT_TAGGED` topology wherever the test does not specifically need format 1.** Every
existing Docker test in this package builds an untagged topology, so before this task the Lua
script has never executed against tagged keys at all — and tagged keys are the entire point of
the format work. `deviceReadMergesAnUntaggedPreviousWithATaggedCurrentGeneration` is the one
test that deliberately mixes them.

These ten tests exist because a task review found the write path's guarantees asserted nowhere:
the mock-based unit test cannot execute Lua, and every pre-existing Docker test predates the
script. Treat the list as the coverage contract, not as suggestions.

- [ ] **Step 2: Run against Docker**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=ShardedRecordStoreAtomicWriteIntegrationTest -DexcludedGroups=""
```

Expected: PASS. `-DexcludedGroups=""` is required — `docker` is excluded by default.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStoreAtomicWriteIntegrationTest.java
git commit -m "test: verify atomic write and cross-format dual-read against real Redis"
```

---

### Task 10: Fix the dual-read dedup identity

> **Runs before Task 9.** Added mid-execution after Task 8's Docker tests surfaced the defect.
> Task 9 documents the migration as resting on the dual read, so the dual read has to be
> correct first.

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStore.java:191-202`
- Modify: `src/test/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStoreAtomicWriteIntegrationTest.java`

**The defect.** `mergeDevicePages` dedupes the two generations' pages with
`r.deviceId() + ":" + r.seqNum()`. Sequence counters are **per generation** —
`ShardKeys.base()` puts the generation prefix in the counter key, so every new generation
starts counting from 1 again. During a reshard's dual-read window a gen-2 record at seq 1
therefore collides with the gen-1 record at seq 1 for the same device, and the
`putIfAbsent` for the previous generation silently drops the older record. `readDevice`
returns fewer records than exist, with no error.

`seqNum` was never a valid identity across generations. `eventId` is: it is the member of
the device index ZSet, and the write path's `ZADD NX` makes it unique per device. When the
same `eventId` genuinely appears in both generations — a write retried across a reshard —
preferring the current generation is still the right resolution, which the existing
`put` / `putIfAbsent` ordering already gives.

**Interfaces:**
- Consumes: `ShardedRecord.eventId()`, `ShardedRecord.deviceId()`.
- Produces: nothing new — `readDevice`'s signature and return type are unchanged.

- [ ] **Step 1: Write the failing test**

Add to `ShardedRecordStoreAtomicWriteIntegrationTest`. This is the test the existing
migration test's counter-seeding workaround was avoiding, so write it to collide
deliberately:

```java
    @Test
    void deviceReadKeepsBothGenerationsWhenSequenceNumbersCollide() {
        // Sequence counters are per generation, so both generations independently issue seq 1
        // for the same device. Deduping on (deviceId, seqNum) would drop the older record.
        // Deliberately does NOT seed the counters apart — the collision is the point.
    }
```

Fill the body against the real store, following the shape the existing
`deviceReadMergesAnUntaggedPreviousWithATaggedCurrentGeneration` test already uses for
setting up two generations: write one record under generation 1, publish a reshard, write a
second record with a different `eventId` under generation 2 while both counters sit at the
same value, then assert `readDevice` returns **both** eventIds. Assert on eventIds, not on
counts alone, so the failure message names what went missing.

- [ ] **Step 2: Run it and watch it fail for the right reason**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=ShardedRecordStoreAtomicWriteIntegrationTest -DexcludedGroups=""
```

Expected: the new test FAILS, returning one record where two were written. Confirm the
failure is the missing older record and not a setup error — if both records are present,
your two generations are not actually issuing the same sequence number and the test is not
yet exercising the collision.

- [ ] **Step 3: Fix the dedup identity**

```java
    // Merge current + previous device records, dedupe by (deviceId, eventId) preferring current,
    // sort by seqNum ascending, cap at `limit`. eventId — not seqNum — is the identity: sequence
    // counters are per generation, so both generations issue the same numbers and deduping on
    // seqNum would silently drop previous-generation records during a migration window.
    private Page<ShardedRecord> mergeDevicePages(Page<ShardedRecord> current,
                                                 Page<ShardedRecord> previous, int limit) {
        java.util.LinkedHashMap<String, ShardedRecord> byKey = new java.util.LinkedHashMap<>();
        for (ShardedRecord r : current.records()) byKey.put(r.deviceId() + ":" + r.eventId(), r);
        for (ShardedRecord r : previous.records()) byKey.putIfAbsent(r.deviceId() + ":" + r.eventId(), r);
        List<ShardedRecord> merged = new ArrayList<>(byKey.values());
        // Ordering across generations is approximate for the same reason: two generations can
        // issue equal sequence numbers, so a merged page is not a strict chronological sequence.
        // Within one generation it is exact, and previous-generation data TTLs out of the window.
        merged.sort(java.util.Comparator.comparingLong(ShardedRecord::seqNum));
        if (merged.size() > limit) merged = new ArrayList<>(merged.subList(0, limit));
        // Pagination is driven by the current generation's cursor; previous-generation records
        // beyond the first page are not paged — they self-heal when the migration window closes.
        return new Page<>(merged, current.next());
    }
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=ShardedRecordStoreAtomicWriteIntegrationTest -DexcludedGroups=""
```

Expected: PASS, all tests in the class.

- [ ] **Step 5: Remove the workaround the defect forced**

`deviceReadMergesAnUntaggedPreviousWithATaggedCurrentGeneration` seeds the generation-1
counter to 100 so the two generations' sequence ranges stay disjoint, with a comment
explaining that it is working around this defect. Delete the seeding line and its comment —
the defect is gone — and re-run the class to confirm the test still passes on its own merits.

- [ ] **Step 6: Run the whole sharding package**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='Shard*Test,SequenceGenerator*Test,ConsistentHashRingTest' -DexcludedGroups=""
```

Expected: PASS. `ShardedRecordStoreDualReadTest` covers the merge directly and must still
pass — if it asserted the old dedup identity, update it to the new one rather than weakening it.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStore.java \
        src/test/java/com/recsys/infrastructure/redis/sharding/ShardedRecordStoreAtomicWriteIntegrationTest.java
git commit -m "fix: dedupe the dual read by eventId, not by sequence number

Sequence counters are per generation, so both generations independently
issue the same numbers. Deduping a merged device page on (deviceId, seqNum)
made every gen-N record shadow the gen-(N-1) record at the same sequence,
so readDevice silently returned fewer records than existed for the whole
24h migration window. eventId is the device index's own member and is
unique per device, which makes it the correct identity."
```
---

### Task 9: Gate the new tests and update the docs

**Files:**
- Modify: `pom.xml` (the `resilience` profile `<includes>`)
- Modify: `.claude/CLAUDE.md` (Redis Conventions)
- Modify: `docs/system_design/03_DB_Scaling_Sharding.md` (§1 write description; sharp edges 6 and 7)
- Modify: `docs/system_design/15_Eventual_Consistency.md` (sharp edge 7)

**Interfaces:**
- Consumes: the test class names from Tasks 3, 5, and 7.
- Produces: nothing.

- [ ] **Step 1: Add the non-docker tests to the PR gate**

In `pom.xml`, alongside `**/sharding/SequenceGeneratorGenerationTest.java`:

```xml
                <include>**/sharding/ShardKeysTest.java</include>
                <include>**/sharding/ShardTopologySnapshotFormatTest.java</include>
                <include>**/sharding/ShardedRecordStoreAtomicWriteTest.java</include>
```

All three are pure unit tests with mocked Redis, which is what that profile's comment
requires. Do **not** add the Task 8 integration class — `<excludedGroups>` would skip it and
its presence would imply coverage the gate does not provide.

- [ ] **Step 2: Update the Redis key conventions**

In `.claude/CLAUDE.md`, replace the two generation lines under "Redis Conventions":

```markdown
- `sr:rec:{shard}:{seq}` / `sr:dev:{shard}:{id}` / `sr:stream:{shard}` / `sr:seq:{shard}` — generation 1 (unversioned, key format 1; `{shard}` here is a placeholder, not a literal brace)
- `sr:g{version}:rec:…` etc. — generation ≥2. Generations published after the atomic-write change use key format 2, which wraps the shard index in a literal Redis Cluster hash tag — `sr:g3:rec:{0}:42` — so a shard's record, device-index, stream, and sequence keys share one slot and can be written by a single Lua script. The format is recorded per generation in `shard:topology`; an absent field means format 1. Reads dual-read the previous generation for one max-TTL window and honour that generation's own format
```

- [ ] **Step 3: Update 03 §1's write description**

Replace the `**Write fan-out.**` paragraph:

```markdown
**Write fan-out.** `doWrite` resolves `topology.current().shardFor(device)` and evaluates a
single Lua script against that one shard on the **primary**: it INCRs the shard's sequence
counter, claims the device index, writes the record hash (with TTL) and appends to the
shard stream — atomically, in one round-trip. A `ZADD NX` that returns 0 means a duplicate
`eventId`, and the script returns at that point without writing anything else, so writes
are idempotent and safe to retry. Under key format 2 all four keys share a hash tag and
therefore one Cluster slot, which is what makes the multi-key script legal.
```

- [ ] **Step 4: Rewrite sharp edges 6 and 7 in 03**

```markdown
6. **A single-shard write is atomic; a cross-shard one is not.** One Lua script assigns the
   sequence and writes all three structures together, so a partial write is no longer
   possible within a shard. Nothing makes two *different* shards atomic — that is §7's
   subject.
7. **Key format is per generation, and format 1 is not Cluster-safe.** Generations published
   after the atomic-write change tag the shard index (`sr:g3:rec:{0}:42`) so a shard's keys
   share one slot. Generations created before it stay untagged for their whole life. An
   existing deployment therefore keeps a non-Cluster-safe keyspace until an operator
   publishes a reshard — deploying the code alone does not migrate it.
```

- [ ] **Step 5: Rewrite sharp edge 7 in 15**

```markdown
7. **Record-store key format is per generation.** A single-shard write is atomic, but a
   generation created before the atomic-write change keeps the untagged key format for its
   whole life; only a reshard moves a deployment onto the tagged format. During the 24 h
   dual-read window per-device reads span both formats — see
   [03 sharp edge 7](03_DB_Scaling_Sharding.md#sharp-edges--notes).
```

- [ ] **Step 6: Run the full gate profile**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
```

Expected: PASS, including `DocumentedMechanismTest` and `DocumentationIndexTest`. If
`DocumentedMechanismTest` reports a "vanished" method, a doc still names a method this PR
renamed or deleted — most likely `seqKey`, or `recKey`/`devKey`/`streamKey`.

- [ ] **Step 7: Commit and open PR2**

```bash
git add pom.xml .claude/CLAUDE.md docs/system_design/03_DB_Scaling_Sharding.md \
        docs/system_design/15_Eventual_Consistency.md
git commit -m "docs: record the atomic write and the per-generation key format

Rewrites sharp edges 6 and 7 from gaps into guarantees with their
preconditions, and gates the three new unit tests in the resilience profile."
gh pr create --base main --title "fix: make the single-shard record write atomic" --body "$(cat <<'EOF'
## Summary
Stage 2 of the cross-shard atomicity investigation, closing sharp edges 6 and 7 from PR1.

- One Lua script replaces the `HSET`+`ZADD`+`XADD` pipeline and absorbs the sequence `INCR`,
  so a write can no longer half-land and a failure after `INCR` can no longer burn a
  sequence and drop the record.
- A per-generation `keyFormat` in `shard:topology` introduces hash-tagged keys
  (`sr:g3:rec:{0}:42`), co-locating a shard's keys in one Cluster slot. An absent field
  reads as the old format, so existing deployments are untouched until an operator
  reshards — the existing dual-read window spans the format change.
- `ShardKeys` becomes the single owner of the key scheme, which `ShardedRecordStore` and
  `SequenceGenerator` previously duplicated.

## Behavior change
A duplicate insert now returns before writing anything, so a retry no longer appends a
second stream entry or orphans a record key under a burned sequence. Non-advancing
`update` calls (`ZADD XX GT` returning 0) are explicitly unaffected and still return `OK`.

## Rollout
Deploy fleet-wide **before** publishing any reshard. `publishReshard` writes the new field,
and a pod running an older build cannot parse it — it would fail-static on its last-good
topology until restarted.

## Test plan
- `mvn test -Presilience` — the gate, including three new unit tests added to the profile
- `mvn test -Dtest=ShardedRecordStoreAtomicWriteIntegrationTest -DexcludedGroups=""` —
  real-Redis atomicity, duplicate suppression, and cross-format dual-read

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Rollout notes

- **Deploy before resharding.** `publishReshard` and a fresh `bootstrap` write `keyFormat`.
  A pod on an older build parses that document with `FAIL_ON_UNKNOWN_PROPERTIES` enabled,
  throws, and `ShardTopologyProvider.refresh` swallows it to keep the last-good snapshot —
  so an old pod silently stops seeing topology updates. Roll the whole fleet first.
- **A fresh Redis with a mixed fleet is the one hard failure.** A new pod's `bootstrap`
  writes `keyFormat`, and an old pod with no last-good snapshot then throws
  `IllegalStateException("topology not initialized")` from `current()`. Do not run mixed
  builds against an empty keyspace.
- **Existing deployments stay on format 1 until an operator reshards.** The deploy alone
  fixes atomicity, not Cluster-safety.

## Self-Review

**Spec coverage.** Stage 1 §03 → Task 1; §14, §15, README → Task 2. Stage 2 Component 1
(Lua + folded INCR + insert-only early return + `ARGV` record prefix) → Task 7; Component 2
(`keyFormat` on the topology → Task 3, persisted with absent→1, bootstrap and reshard →
Task 4, carried onto both generations → Task 5); Component 3 (`SequenceGenerator`, repair
path, SCAN pattern) → Task 6; Component 4 (CLAUDE.md, sharp-edge rewrites) → Task 9.
Testing table → Tasks 3, 4, 5, 7 (unit) and 8 (docker); the CI trap → Task 9 Step 1.
Sequencing → the two PR steps. The spec's three deferred findings stay deferred; no task
touches them.

**Placeholders.** None. Task 8's bodies are the one place prose stands in for code — that is
deliberate, since the assertions depend on `RedisShardingTestBase`'s fixtures, and each
carries its exact obligation plus an explicit instruction not to leave it empty.

**Every task ends green.** Tasks 3–5 are ordered so each commit compiles and its tests pass:
Task 3 pairs `ShardKeys` with the `ShardTopology` constructor it reads; Task 4 extends
`Snapshot` without touching the provider, which still compiles against the old constructor;
Task 5 switches the provider over. No task commits a known-red test or non-compiling code.

**Type consistency.** `ShardKeys` method names (`rec`, `recPrefix`, `dev`, `stream`, `seq`,
`devScanPattern`) are used identically in Tasks 6–7. The `ShardTopology` five-argument
constructor is defined in Task 3 and used in Tasks 4, 5, 6, 7. `effectiveKeyFormat()` /
`effectivePrevKeyFormat()` are defined in Task 4 and consumed in Task 5.
`fixedAtVersion(int, int, int, int)` is defined in Task 5 and used in Task 7.
`ensureCounterValid(ShardTopology, int, long)` is defined in Task 6 and its only production
caller is updated in the same task.
