# Versioned Runtime Shard Topology Design

This design captures the spec implemented by PR 151. It is archived here because `SPEC.md`
now tracks the newer ops-layer split work from `main`.

## Objective

`ConsistentHashRing(shardCount, 150)` used to be built once from `SHARDED_RECORD_SHARD_COUNT`
on each instance. Changing the shard count required a redeploy and could strand records whose
device IDs remapped to different shards.

The feature makes Redis shard topology a versioned, shared, runtime-swappable snapshot so that:

1. All instances agree on the current topology by reading one authoritative Redis snapshot.
2. Operators can change shard count at runtime.
3. Resharding is safe for TTL data through generation-scoped keys and a bounded dual-read window.
4. The per-generation mapping stays the existing consistent-hash ring, backed by one shared FNV-1a
   primitive also reused by `StableBucketer`.

## Decisions

- Keep the consistent-hash ring: FNV-1a, virtual nodes, default 150 vnodes, TreeMap ceiling lookup.
- Store topology in Redis under `shard:topology`.
- Refresh topology every 30 seconds into an immutable in-memory `ShardTopology`.
- Use lock-free request-path reads via a volatile current snapshot and last-good fallback on Redis
  failure.
- Trigger resharding through a guarded admin endpoint: `POST /shards/topology {shardCount}`.
- Keep generation 1 behavior-compatible with existing unversioned keys; generation 2 and later use
  `sr:g{version}:...` prefixes.
- Dual-read current and previous generations until `prevExpiresAtMs`, with the current generation
  winning duplicates.
- Keep reshard decisions operator-triggered only.

## Components

New and modified components under `com.recsys.infrastructure.redis.sharding`:

```text
Fnv1a.java
ConsistentHashRing.java
ShardTopology.java
ShardTopologyStore.java
ShardTopologyProvider.java
ShardedRecordStore.java
SequenceGenerator.java
```

Other integration points:

- `application/experiment/StableBucketer.java` reuses the shared FNV-1a accumulation while keeping
  its existing fmix64 finalizer.
- `api/online/OnlinePredictionServer.java` wires the topology provider instead of a deploy-fixed
  ring.
- `infrastructure/store/ShardedRecordService.java` exposes the guarded reshard endpoint.

## Topology Snapshot

Redis key: `shard:topology`.

Shape:

```json
{
  "version": 2,
  "shardCount": 8,
  "vnodes": 150,
  "createdAtMs": 1710000000000,
  "prevVersion": 1,
  "prevShardCount": 4,
  "prevExpiresAtMs": 1710000600000
}
```

`ShardTopology` is immutable and builds its `ConsistentHashRing` once. `ShardTopologyProvider`
holds the current snapshot plus an optional previous snapshot while the dual-read window is open.
If `shard:topology` is absent, the provider initializes version 1 from
`SHARDED_RECORD_SHARD_COUNT` with idempotent bootstrap behavior.

## Key Scheme

Generation 1 uses legacy unversioned keys:

```text
sr:rec:{shard}:{seq}
sr:dev:{shard}:{deviceId}
sr:stream:{shard}
sr:seq:{shard}
```

Generation 2 and later use generation-prefixed keys:

```text
sr:g{version}:rec:{shard}:{seq}
sr:g{version}:dev:{shard}:{deviceId}
sr:g{version}:stream:{shard}
sr:g{version}:seq:{shard}
```

## Read And Write Semantics

- Writes always target the current generation.
- Device reads query the current generation and, while the migration window is open, also query the
  previous generation.
- Results are merged and deduped by `deviceId:seq`, preferring current-generation data.
- After `prevExpiresAtMs`, previous-generation reads are skipped because the old TTL data should
  have expired.

## Verification

Expected verification for this feature:

```bash
mvn package -DskipTests
mvn test
mvn test -Dtest='Fnv1a*,ConsistentHashRing*,StableBucketer*,ShardTopology*,Sharded*'
mvn test -Dgroups=docker -Dtest='ShardTopology*,ShardedRecordStore*'
grep -rn "0xcbf29ce484222325L\|0x100000001b3L" src/main --include="*.java"
```

Docker-backed sharding tests require a Docker/Redis test environment and may be unavailable in
minimal local or CI sandboxes.

## Boundaries

- Do not change FNV-1a constants, UTF-8 byte handling, vnode key format, default vnode count,
  TreeMap lookup, or `StableBucketer.KEYSPACE`.
- Do not swap hashing algorithms.
- Do not add request-to-service-instance affinity routing.
- Do not change `ShardedTopKStore` random-shard logic or `RedisReadReplicaRouter` AZ routing.
- Do not make resharding automatic or elastic.
