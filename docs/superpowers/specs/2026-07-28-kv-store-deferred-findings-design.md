# KV-store deferred findings — design

**Date:** 2026-07-28
**Status:** approved, in implementation
**Follows:** `2026-07-28-kv-store-sharp-edges-design.md`, which deferred these three

## Problem

The KV-store audit surfaced seven findings. Four were repaired in PRs #241–#244. The
remaining three were recorded as out of scope. This spec addresses them.

Investigating them properly changed the picture on two of the three. The severity
ordering from the original audit was wrong.

## Findings, re-characterised

### A — `ShardCursor` returns 500 on ordinary user input

Originally filed as a type-hygiene concern. It is a reachable defect.

`ShardCursor` is one record wrapping one opaque `String`, but it is used for two
mutually incompatible cursor spaces:

- `readDeviceAt` treats it as a **ZSET score**: `Double.parseDouble(cursor.value()) + 1`
  (`ShardedRecordStore.java:156`). It emits `ShardCursor.of(String.valueOf(lastSeq))` —
  e.g. `"42"`.
- `readShard` treats it as a **Redis stream ID**, passed to `XREAD`
  (`ShardedRecordStore.java:192`). It emits the message ID — e.g. `"1690000000000-0"`.

Both HTTP handlers accept an opaque `cursor` query parameter and pass it straight
through with no validation (`ShardedRecordService.java:165-166, 189-190`). So:

| Request | Result today |
|---|---|
| `GET /shards/shard?cursor=42` | `XREAD` rejects the malformed stream ID → 500 |
| `GET /shards/device?cursor=1690000000000-0` | `NumberFormatException` → 500 |

A client that pages one endpoint and pastes the cursor into the other gets a 500 from
well-formed input. The shared `START` sentinel `"0-0"` — a *stream* ID that the device
path special-cases via `isStart()` — is itself evidence of the conflation.

### B — a failed pipeline can silently replay its writes later

Originally filed as "connections return to the pool unvalidated". The consequence is
worse than hygiene.

`LettuceRedisExecutor.executePipelined` (`:113-130`) returns the borrowed connection in
a `finally` with no invalidation on any failure path:

1. **Callback throws after queueing, before `flushCommands()`.** The inner `finally`
   calls `setAutoFlushCommands(true)`, which does *not* flush already-buffered commands.
   The connection goes back to the pool with those commands still queued. The next
   borrower's `flushCommands()` flushes them too — so the `HSET`/`ZADD`/`XADD` of a write
   the caller saw fail can execute later, against an unrelated request.
2. **`setAutoFlushCommands(false)` itself throws.** The inner `try` is never entered, so
   auto-flush is never restored. The connection returns to the pool with auto-flush
   *off*, and the next borrower's commands buffer and never flush — a hang until timeout.
3. **`pool.returnObject` throws.** Unguarded, unlike the timed-read path directly above
   it (`:105-107`), which invalidates on trouble.

### C — `readAllShards` is unreachable

Originally filed as an unbounded drain backing the admin `GET /shards/shard`. That is
wrong: the endpoint calls `readShard`, which is properly paginated
(`ShardedRecordService.java:192`).

`readAllShards` has **zero production callers** — its only caller is
`ShardedRecordStoreReadTest`. It is a third piece of unreachable machinery, alongside
the top-K shard fan-out (#241) and the never-invoked sequence guard (#243). Its
unbounded `do/while` drain is real but not reachable.

## Design

Three independent, stacked pull requests, in descending order of value.

### PR A — give `ShardCursor` a kind

Derive the kind at construction from the value's shape, which is unambiguous here: a
Redis stream ID always contains `-`, a sequence number never does.

```java
public enum Kind { SEQ, STREAM, START }
```

- `ShardCursor.start()` → `START`, accepted by both read paths.
- `ShardCursor.seq(long)` / `ShardCursor.stream(String)` — explicit factories used
  internally by the two emit sites.
- `ShardCursor.of(String)` — the parse used at the HTTP boundary. Infers the kind from
  shape and rejects a value that is neither.
- Each read path asserts the kind it needs and throws `IllegalArgumentException`
  otherwise; `ShardedRecordService` maps that to **400** with a message naming the
  expected cursor space.

**The wire format is unchanged.** Cursors remain the bare `"42"` / `"1690000000000-0"`
strings clients already hold, so no in-flight cursor breaks. This is deliberate: the
goal is to make the confusion *unrepresentable inside the store* and *a 400 at the
boundary*, not to re-encode a client-visible value.

`/shards/*` is not routed through the API gateway — it is reachable only on port 7010 —
so there is no public API-compatibility commitment on these cursors either way.

### PR B — invalidate the pooled connection on any pipeline failure

Adopt the discipline the timed-read path on the same class already uses: a connection
whose lifecycle was interrupted is destroyed, not reused.

- Track failure across the whole method; on any exception, `pool.invalidateObject(conn)`
  instead of `returnObject`.
- Guard `returnObject` itself, falling back to `invalidateObject`, matching `:105-107`.
- The cost is one dropped connection per failed pipeline. That is the correct trade
  against silently replaying a failed write.

### PR C — delete `readAllShards`

Consistent with #241: unreachable machinery is deleted rather than bounded. The paged
`readShard` is the supported way to walk a shard, and the git history preserves the
method if a future operator tool wants it back.

Its test moves to asserting the paged path it replaced, so shard-walk coverage is not
lost.

## Testing

Test-driven: a failing test precedes each change. All tests are plain unit tests with
mocked collaborators — never `@Tag("docker")`, because the `resilience` profile that
gates PRs sets `<excludedGroups>load,docker</excludedGroups>`, so a docker-tagged
assertion cannot block a merge. Each PR adds its test classes to that allow-list.

- **A** — a cross-fed device cursor and a cross-fed shard cursor each produce a typed
  rejection rather than a parse failure; `start()` is accepted by both paths; round-trip
  through `of()` preserves each kind.
- **B** — a callback that throws causes `invalidateObject`, not `returnObject`; a
  throwing `setAutoFlushCommands` also invalidates; the success path still returns.
- **C** — no new behaviour; the existing shard-walk test is retargeted at `readShard`.
