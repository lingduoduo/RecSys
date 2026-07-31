# Cross-Shard Atomicity — Design

**Date:** 2026-07-31
**Status:** Approved, not yet implemented
**Plan:** `docs/superpowers/plans/2026-07-31-cross-shard-atomicity.md`

## Problem

The starting question: *to deduct inventory and create an order record atomically, you wrap
both operations in a database transaction — either both succeed or both fail, and the
database handles the consistency guarantees.* What happens to that guarantee when the
database shards?

An investigation of this codebase produced two results, and this spec covers both.

**The conceptual result.** The premise is exactly right, and it holds in precisely one
place here: `MySqlSagaStateStore.saveWithEvent` mutates `saga_instance` and `event_outbox`
inside a single `TransactionalMySql.inTransaction`. That is legal only because MySQL is
deliberately un-sharded. The system's answer to cross-boundary work is the outbox, the
compensation saga, and TCC — none of which is documented as a *consequence of sharding*
anywhere in the system-design docs. Sharding (03) and eventual consistency (15) each
describe their own half; nothing joins them.

**The code result.** Two real gaps in the sharded write path, both directly on the topic:

1. `ShardedRecordStore.doWrite` pipelines `HSET` + `ZADD` + `XADD`. Pipelining is
   batching, not a transaction. A partial failure leaves a record hash with no device-index
   entry (invisible to `readDevice`) or an index entry pointing at no record.
2. Record-store keys carry no hash tags (`sr:rec:0:123`, not `sr:rec:{0}:123`). No
   multi-key operation over them can be atomic under Redis Cluster, which also means the
   "cluster-ready without a data migration" claim in 03 §6 and 14 is true only for
   single-key operations.

## Scope

Two stages, two PRs.

**Stage 1 — documentation.** Join the sharding and eventual-consistency investigations,
and record gaps 1 and 2 as sharp edges describing today's code.

**Stage 2 — code.** Close gaps 1 and 2.

### Explicitly out of scope

Three further findings from the investigation, deferred by decision:

- **Saga observability.** There is no counter or metric anywhere in `application/saga` or
  `infrastructure/saga`. A failed compensation — the inconsistent state ACID could never
  produce — surfaces only as `failure_reason` text in `saga_instance`.
- **`TransactionalMySql` commit reporting.** A `close()` `SQLException` raised *after* a
  successful `commit()` is caught by the outer handler and wrapped as
  `MySqlPoolUnavailableException`, so a committed transaction can be reported to the caller
  as a failure. Low impact: every current caller is idempotent on retry.
- **`readShard` does not dual-read.** Pre-existing, already documented as sharp edge 1 in 03.

Also out of scope: any production wiring of the saga orchestrators. `SagaOrchestrators` is
reference machinery — only tests construct it — and this work does not change that.

## Stage 1 — Documentation

### `docs/system_design/03_DB_Scaling_Sharding.md`

A new `## 7. Cross-shard atomicity — where the transaction stops`, placed after §6 and
before `## Sharp edges — notes`. No existing `##` is renumbered. It covers:

- **The premise and where it holds.** `saveWithEvent` as the repo's one two-table atomic
  commit, legal because MySQL is un-sharded.
- **Three distinct ways sharding breaks it.** Separate shards are separate transaction
  domains (no XA, no 2PC, and `TransactionalMySql` holds a single JDBC URL); multi-key
  writes within one shard are not atomic today (gap 1); and the shard map is itself
  eventually consistent (30 s topology propagation, 24 h dual-read window), so a
  transaction cannot span a boundary that is still moving.
- **What the codebase uses instead.** Local transaction + outbox, compensation saga, TCC —
  a table mapping each to the inventory/order example, with the argument that TCC fits
  inventory better than the plain saga: compensation is not rollback, so between
  `ReserveInventory` committing and `ReleaseInventory` running, every reader sees stock
  that was never sold. TCC's Try holds a reservation that is not externally final and
  closes that window, at the cost of modelling `available` vs `reserved`.
- **The obligations that replace ACID.** Idempotency keys (`sagaId + stepName`, plus phase
  for TCC) because the event path is at-least-once; `WHERE saga_id = ? AND version = ?`
  optimistic concurrency in place of `SELECT … FOR UPDATE`; and a best-effort compensation
  path that can leave genuine inconsistency needing an operator — a state ACID never had.
- **The structural conclusion.** The saga coordinator itself depends on a local
  transaction, so the design bottoms out at exactly one un-sharded database. The practical
  rule: shard the high-volume aggregates, keep coordinator state and its outbox together
  and un-sharded — which is the arrangement the repo already has.

**Two new sharp edges**, describing today's code:

- The single-shard write is pipelined, not atomic (gap 1).
- Keys carry no hash tags, so no multi-key operation over them is Cluster-safe (gap 2).

**One correction.** 03 §6 ("What sharding here does *not* buy") claims the ring makes the
store ready to map logical shards onto separate nodes without a data migration. Scope that
to single-key operations, and point at the new sharp edge for the rest.

### `docs/system_design/14_Partitioning.md`

The same correction under "Where the shards physically live — Redis and MySQL", where the
identical cluster-readiness claim appears. One sentence; no restructuring.

### `docs/system_design/15_Eventual_Consistency.md`

A pointer from §1a to 03 §7, and one entry in §4's sharp-edge list for the non-atomic
write. 15 already owns the saga/TCC mechanism description and must not restate it — the
new material in 03 is the *sharding consequence*, and each doc keeps its own half.

### `README.md`

Extend row 03's description to mention cross-shard atomicity. Both docs are already
indexed, so `DocumentationIndexTest` is not at risk.

## Stage 2 — Code

### Component 1 — one Lua script replaces the pipeline

`ShardedRecordStore.doWrite` evaluates a single script via
`writeExec.execute(commands -> commands.eval(...))`, the pattern `ShardedTopKStore`
already uses. No new `RedisExecutor` method is needed.

The script performs `INCR seq` → `ZADD` → `HSET` + `EXPIRE` → `XADD`, and returns
`{seqNum, zaddResult}`. `WriteResult(seqNum, shardIndex, status)` keeps its current shape,
and `DUPLICATE` keeps its current meaning: `!isUpdate && zadd == 0`.

Folding the `INCR` into the script removes a second failure mode alongside the partial
write: today a successful `INCR` followed by a failed pipeline burns a sequence number
*and* drops the record.

**Deliberate behavior change, insert path only.** Evaluating `ZADD` first lets the script
return immediately on a duplicate, writing nothing else. Today a retried duplicate write
still burns a sequence, writes an orphan `rec:` hash under it, and appends a second stream
entry — so retries silently inflate the stream. The new behavior is strictly better, but it
is a change to observable semantics and is the only one in stage 2.

This early return applies **only when `isUpdate` is false**. On the update path
(`ZADD XX GT`), a `0` return means the element exists but its score did not advance — which
is not a duplicate, and today still refreshes the hash and appends to the stream. The
script must preserve that: for `isUpdate`, proceed regardless of the `ZADD` result, and
keep returning `OK`. `DUPLICATE` remains reachable only via `!isUpdate && zadd == 0`.

**Key construction inside the script.** The `rec:` key cannot be a declared `KEYS` entry
because its sequence number does not exist until the `INCR` runs. It is built inside the
script from a prefix passed in `ARGV`. Under format 1 this is safe because every shard key
lives on the single Sentinel primary. Under Redis Cluster it is safe only because the hash
tag places the constructed key in the same slot as the declared ones — so Component 2 is
what makes the script *Cluster*-safe, not what makes it work today.

### Component 2 — key format travels with the generation

`ShardTopology` gains a `keyFormat` field: `1` = untagged (`sr:rec:0:123`), `2` = tagged
(`sr:g{v}:rec:{0}:123`).

- `ShardTopologyStore` serializes the field. **An absent field reads as 1**, so existing
  deployments are unaffected by the deploy itself.
- `bootstrap` writes format 2 — a fresh Redis has no legacy data to preserve. Because
  bootstrap is `SETNX`, an existing generation 1 is never rewritten.
- `publishReshard` writes format 2.
- Key builders take the format as a parameter; `ShardTopologyProvider` carries it on both
  the current and previous generations.

That last point is what makes the migration free: `readDevice`'s existing dual-read already
merges current and previous generations, so it will merge a format-1 previous generation
with a format-2 current one using machinery that is already there and already tested. The
operator procedure to migrate is "publish a reshard" — no new tooling, no data migration,
no read gap.

### Component 3 — `SequenceGenerator` follows the format

`next()` folds into the script. The startup repair path (`ensureCounterValid`) must build
both its `seq:` key and its `dev:` SCAN pattern through the format-aware builder. Braces
are literal in Redis glob patterns (only `*`, `?`, `[…]`, and `\` are special), so a
tagged pattern still matches.

### Component 4 — docs close the loop

CLAUDE.md's Redis Conventions key schema gains the tagged spelling, and 03's two new sharp
edges are rewritten from "here is the gap" to "here is the guarantee, and the generation it
starts at."

## Testing

| Test | Kind | Covers |
|---|---|---|
| Key builders per format, golden strings | unit | Component 2 |
| Topology JSON round-trip, incl. absent `keyFormat` → 1 | unit | Component 2 backward compatibility |
| Duplicate insert returns `DUPLICATE` and writes nothing | unit (fake Redis) or docker | Component 1 behavior change |
| Non-advancing `update` still refreshes hash and stream, returns `OK` | unit (fake Redis) or docker | Component 1 update-path preservation |
| Atomic write against live Redis | docker | Component 1 |
| Dual-read merging a format-1 previous with a format-2 current generation | docker | Component 2 migration |
| `ensureCounterValid` repair against tagged keys | docker | Component 3 |

**The CI trap.** The PR gate runs only the `-Presilience` profile — an allow-list of
roughly 220 of ~1400 tests — and separately excludes `@Tag("docker")`. A test intended to
block a merge must therefore be **non-docker *and* explicitly added to that profile**.
Otherwise stage 2 ships with tests that never ran on a PR. At minimum the key-builder and
topology round-trip tests must be non-docker and in the profile; the docker tests are
verification, not gating, and the plan must say which is which.

## Sequencing

Two PRs off `main`; never a direct merge to `main`.

1. **PR1 — stage 1 docs.** Self-contained.
2. **PR2 — stage 2 code plus its doc updates.** Stacked on PR1, then rebased onto `main`
   once PR1 merges, so it is not left stranded off a merged branch.

## Risks

- **Lua on the hot write path.** Every record write becomes a script evaluation. The script
  is small and replaces four pipelined commands with one round-trip, so latency should
  improve, but this is the highest-traffic write in the system and the change is not
  behavior-neutral (see the duplicate-handling change above).
- **Topology schema change.** `shard:topology` is read by every instance every 30 s. The
  absent-field default is the compatibility guarantee and must be tested directly, not
  assumed.
- **Migration requires an operator action.** Format 2 only takes effect on a fresh
  bootstrap or a published reshard. Existing deployments keep format 1 — and therefore keep
  a non-Cluster-safe keyspace — until an operator reshards. The docs must say so plainly
  rather than implying the deploy alone fixes gap 2.
