# Consistent Hashing Consolidation Design

## Objective

Consolidate the repo's consistent hashing primitives without changing any existing shard
assignment or A/B bucketing output. This is a compatibility-preserving refactor: existing device
IDs must continue to map to the same Redis shard for a given shard count and virtual-node count.

## Current State

`ConsistentHashRing` owns shard placement and already uses a shared `Fnv1a` helper. `StableBucketer`
also uses `Fnv1a` for the accumulation step, then applies its own fmix64 avalanche before taking an
unsigned remainder over a fixed keyspace. The behavior is mostly consolidated already, but the
hashing surface is narrow and the bucketer's fmix64 logic remains private to the bucketer.

The separate Bloom-filter hash in `infrastructure/resilience/BloomFilterGuard` is intentionally
domain-specific and is not part of shard placement. It should remain separate.

## Compatibility Contract

The refactor must preserve:

- FNV-1a offset basis: `0xcbf29ce484222325L`.
- FNV-1a prime: `0x100000001b3L`.
- UTF-8 byte handling for string hashes.
- Virtual-node label format: `"v" + vnodeIndex + ":" + shardIndex`.
- `TreeMap.ceilingEntry(hash)` lookup with wraparound to `firstEntry()`.
- Existing `ConsistentHashRing.DEFAULT_VIRTUAL_NODES`.
- Existing `StableBucketer.KEYSPACE` and slot outputs.

No device remapping is allowed for the same `(shardCount, virtualNodesPerShard, deviceId)` inputs.

## Proposed Shape

Replace or evolve `Fnv1a` into a small shared hashing utility in
`com.recsys.infrastructure.redis.sharding`, for example `Hashing`.

The utility should expose:

```java
static long fnv1a64(byte[] data)
static long fnv1a64(String value)
static long fmix64(long value)
```

`ConsistentHashRing` uses `Hashing.fnv1a64(...)` for vnode labels and device IDs.

`StableBucketer` composes the shared helpers:

```text
UTF-8 bytes of "userId:layerName"
-> Hashing.fnv1a64(bytes)
-> Hashing.fmix64(hash)
-> Long.remainderUnsigned(..., StableBucketer.KEYSPACE)
```

Keep names short and explicit. Do not introduce a placement strategy interface unless a second
placement strategy is actually needed.

## Tests

Use TDD for the refactor:

1. Add FNV-1a golden tests for representative strings and byte/string overload agreement.
2. Add shard-placement golden tests for several device IDs across 2, 4, and 8 shards.
3. Keep or expand `StableBucketer` golden slot coverage so bucketing output cannot drift.
4. Run the targeted suite:

```bash
mvn test -Dtest='Fnv1a*,Hashing*,ConsistentHashRing*,StableBucketer*'
```

5. Run the full suite:

```bash
mvn test
```

The golden placement tests are the main guardrail. They should fail if the refactor changes vnode
labels, byte handling, lookup behavior, or hash constants.

## Non-Goals

- Do not replace the virtual-node ring with jump hashing, rendezvous hashing, modulo hashing, or any
  other algorithm.
- Do not change Redis topology versioning, generation-key behavior, dual-read behavior, or record
  storage semantics.
- Do not merge unrelated hash-like code such as Bloom-filter probe hashing.
- Do not change A/B assignment behavior.
