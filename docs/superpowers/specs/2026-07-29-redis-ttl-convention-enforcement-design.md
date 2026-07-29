# Redis TTL Convention Enforcement Design

Date: 2026-07-29
Status: Proposed

## Summary

`volatile-lru` (PR #251) made "has a TTL" the eviction boundary: cache-like keys carry an
explicit TTL and are evictable, keys without one are authoritative and structurally
protected. That is only correct while *every* writer honours it, and nothing checks. A
writer that forgets a TTL silently converts its key into permanently-resident,
unevictable state — recorded as sharp edge 7 in
[02_Caching](../../system_design/02_Caching.md#sharp-edges--notes) and left unenforced.

This adds a bounded runtime sampler that reports keys with no TTL outside a declared
allow-list, and fixes the one violation already in the tree.

## Motivation, stated honestly

The gap is not hypothetical. `RedisReplicaLagProbe` already violates the convention:

- The marker key is `recsys:replica-lag-probe:<random UUID>` — **a new key per process
  instance** (`DEFAULT_KEY_PREFIX + UUID.randomUUID()`).
- It is written with a bare `SET` and no TTL
  (`redis.execute(commands -> commands.set(key, writtenSequence + ":" + now))`).

Under `volatile-lru` that key can never be evicted, and every process start — deploy, HPA
scale-up, crash-loop — mints another one. A liveness marker is being stored as if it were
authoritative state. The keys are tiny, so this is a slow leak rather than an outage, but
it also *dilutes the invariant itself*: "no TTL means authoritative" stops being true, and
the next reader of that rule is misled.

An audit of the other eleven Redis writers found no further violations. Auth tokens
(`LoginTokenService`, `SubmitTokenService`) and all three locks (`RedisMutex`,
`RedisDistributedLock`, `WatchdogLock`) set TTLs correctly. `SequenceGenerator`
(`sr:seq:<shard>`) and `OnlineLearner` write without a TTL and are *legitimately* durable
and bounded.

That audit was itself incomplete: the final review found that `ShardedRecordStore` also
writes without a TTL for records (`sr:rec:<shard>:<seq>`), device indexes
(`sr:dev:<shard>:<id>`), and streams (`sr:stream:<shard>`) — `doWrite` only issues `EXPIRE`
when `ttlSeconds > 0`, and normal writes pass 0. These are durable-by-design, not
violations, but they belong on the allow-list, which now covers the bare `sr:` namespace
instead of just `sr:seq:` so generation-prefixed keys (`sr:g2:seq:…`, written after a
reshard) stay covered too.

So: one real bug, one allow-list gap caught late, and no way to notice the next one.

## Why a runtime sampler and not a static test

A static architecture test over the Java writers was considered and rejected. It would be
structurally blind to the highest-volume writer in the system: the Flink sinks in
`online/flink/OnlineFeatureStreamingJob` are **excluded from the Maven compile** and write
through Lua, so no source-level assertion over compiled call sites can see them. It would
also miss anything written out-of-band (`redis-cli`, a future service, a migration script).

The sampler observes the keyspace itself, so it sees every writer regardless of language,
build path, or origin. The cost is that detection is probabilistic rather than at PR time —
accepted, and stated in the sharp edge.

## Design

### 1. Fix the lag-probe marker

`RedisReplicaLagProbe.sample()` writes with `SetArgs.Builder.ex(MARKER_TTL_SECONDS)`
(60 s) instead of a bare `SET`.

The marker is read back inside the same `sample()` call, so the TTL only has to outlive
replication lag — 60 s is generous for that and short enough that a dead instance's marker
disappears within a minute. Per-UUID keying is kept deliberately: two instances sharing one
key would race on the sequence number and break the monotonicity check that makes the lag
measurement meaningful (see the class javadoc on stable replica selection).

### 2. `RedisPersistentKeyProbe`

A new class in `infrastructure/redis`, kept separate from `RedisCacheStatsProbe` so each
has one job — one parses `INFO`, the other walks the keyspace.

- **Bounded work per tick.** One `SCAN` page (`COUNT` ≈ 200), carrying the cursor across
  ticks so successive samples advance through the keyspace and wrap. Full coverage over
  time, fixed cost per tick — never a full keyspace walk in one call.
- **TTL classification.** For the page's keys, keys reporting `TTL == -1` (no expiry) are
  matched against `DURABLE_PREFIXES`; anything unmatched is *unexpected*.
- **The allow-list is the declaration.** `shard:topology`, `i2vEmb:`, `u2vEmb:`, `sr:`,
  and `bias:item:` (the online learner's flush prefix, wired in `OnlinePredictionServer`
  via `new LearnerFlushScheduler(onlineLearner, jedisPool, "bias:item", 30L)`). Note
  `u2vEmb:` is deliberately durable-listed even though the Flink job also writes TTL'd
  `u2vEmb:` keys: a `u2vEmb` key *without* a TTL is a legitimately durable
  classpath-seeded embedding.
- **Failure is non-fatal.** Any exception yields an "unavailable" sample, mirroring
  `RedisCacheStatsProbe`; the scheduler is never cancelled by an observer throwing.

### 3. Metric and operator signal

`RedisCacheMetrics` gains:

- `redis_unexpected_persistent_keys` — count from the most recent sample.
- `redis_keyspace_sampled_keys` — the denominator, so the gauge is interpretable.

Key *names* never become metric labels — that would be unbounded cardinality. Instead the
probe logs a bounded sample (first 3) of offending key names at WARN, which is what an
operator actually needs to act.

Wired on the online server beside the existing Redis probes, on its own interval
(`REDIS_PERSISTENT_KEY_PROBE_SECONDS`, default 60).

### 4. Corrections carried in the same change

Three claims in the current docs are wrong or overstated and are fixed here:

- **Lua overshoot magnitude.** `02_Caching` §8 and `runbooks/elasticache-local.md` imply the
  Flink sinks can push Redis far past `maxmemory`, extrapolated from a synthetic script
  that wrote 3000 keys in one `EVAL`. The real per-invocation writes are small —
  `SET_IF_NEWER_WITH_LINEAGE` touches 5 keys, `ATOMIC_TOPK` writes `top-k` members
  (default 10) into 2 ZSets. The mechanism is real; the magnitude is kilobytes. No
  `maxmemory` resize is warranted.
- **Sharp edge 7** becomes "enforced by sampling", with the residual risk stated: a rarely
  written key may take many ticks to surface.
- **"Production ElastiCache unverified"** is dropped. The account has zero cache clusters
  and zero replication groups in both regions; the EKS overlays are templates with
  placeholder endpoints that were never deployed. This claim lives only in the assistant's
  session memory, not in `docs/`, so correcting it involves no repo change.

## Testing

TDD, per the repo's practice.

- `RedisPersistentKeyProbeTest` — detects an unexpected persistent key; allow-listed
  prefixes are not flagged; TTL'd keys are not flagged; the cursor advances across samples
  and wraps; a Redis failure yields unavailable without propagating.
- `RedisCacheMetricsTest` — the two new gauges publish, and an unavailable sample does not
  report a false zero.
- `RedisReplicaLagProbeTest` — the marker write carries a TTL (this test fails against the
  current code, which is the bug).
- **Empirical validation** against a local `redis-server`, as with the eviction simulation:
  plant an unexpected persistent key, confirm the gauge moves and the WARN names it.

All new tests are mock-only and belong in the `-Presilience` merge gate.

## Scope boundaries

Not included, deliberately:

- A static architecture test over Java writers — blind to the Flink sinks (above).
- Any `maxmemory` resize — the overshoot bound does not justify it.
- Auto-remediation (expiring or deleting offending keys). The probe reports; a human
  decides. Deleting a key the sampler misjudged as unexpected would destroy the exact
  authoritative state this invariant exists to protect.
- Provisioning real ElastiCache. The AWS side stays aspirational for this research repo.
