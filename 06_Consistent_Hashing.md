# Consistent Hashing in Recsys-Backend-Service

An investigation of the hashing algorithm at the core of the system's data
distribution: one shared FNV-1a primitive, a virtual-node ring that maps device IDs
to Redis shards, and a second consumer that maps users to A/B buckets. The theme is
**one hash, two uses** — and a frozen compatibility contract, because a single change
to the primitive would remap every device *and* reshuffle every experiment at once.

## The big picture

Consistent hashing here is deliberately consolidated onto a single primitive with two
consumers:

```
                       Hashing.fnv1a64  (the shared FNV-1a primitive)
                         /                        \
     ConsistentHashRing.shardFor            StableBucketer.slot (+ fmix64)
        → Redis record sharding                → A/B experiment bucketing
        (14_Partitioning)                       (ABTestService)
```

Both consumers want the *same* two properties consistent hashing gives you:

- **Determinism across JVMs and restarts** — the same key always lands in the same
  place, with no reliance on `String.hashCode()` (which is JVM- and value-unstable).
- **Minimal disruption on a resize** — growing the shard count moves only ~1/N of
  keys; growing an A/B allocation pulls in only the users at the range boundary.
  Neither reshuffles the whole population.

Because both bottom out in one primitive
([`Hashing`](src/main/java/com/recsys/infrastructure/redis/sharding/Hashing.java)),
its constants are a **frozen contract** (§5): one edit breaks sharding and A/B
simultaneously.

## 1. The FNV-1a primitive

[`Hashing`](src/main/java/com/recsys/infrastructure/redis/sharding/Hashing.java) is a
tiny, dependency-free (pure JDK) hash:

- `fnv1a64(byte[])` seeds with `FNV_64_OFFSET_BASIS = 0xcbf29ce484222325` and, per
  byte, does `h ^= (b & 0xff); h *= FNV_64_PRIME` (`0x100000001b3`) — the XOR-then-
  multiply order that makes it FNV-**1a**. `fnv1a64(String)` is the UTF-8 overload.
- `fmix64(long)` is the MurmurHash3 64-bit finalizer (two `>>> 33` xor-shifts around
  multiplies by `0xff51afd7ed558ccd` and `0xc4ceb9fe1a85ec53`) — used *only* by the
  A/B bucketer (§3), to add avalanche on top of FNV-1a's accumulation.

FNV-1a is chosen because it is **fast** (one XOR + one multiply per byte), **well-
distributed** over the short string keys used here (device IDs, `userId:layer`), and
**dependency-free**. The class Javadoc carries the freeze note in code: *"Do not change
these constants, byte handling, or fmix64 operations without an explicit
remapping/bucketing migration plan."*

## 2. The ring — `ConsistentHashRing`

[`ConsistentHashRing`](src/main/java/com/recsys/infrastructure/redis/sharding/ConsistentHashRing.java)
is an **immutable 64-bit ring** backed by a `TreeMap<Long, Integer>` (hash position →
shard index):

- **Virtual nodes.** Each physical shard is placed at `DEFAULT_VIRTUAL_NODES = 150`
  positions on the ring, hashing the label `"v{i}:{shard}"`. Spreading each shard
  across 150 points is what makes the load even and the remap minimal — with one point
  per shard, distribution would be lumpy and a resize would move large arcs.
- **Lookup.** `shardFor(deviceId)` hashes the id and takes `ring.ceilingEntry(hash)`,
  **wrapping around to `firstEntry()`** when the hash exceeds the largest position —
  the classic clockwise-walk on the ring. It is lock-free after construction.
- **Diagnosis.** `distribution(deviceIds)` returns the device count per shard, for
  spotting hot-shard imbalance.

**The minimal-remap property is structural, not a resize method.** The ring has no
in-place resize — it is immutable, so a resize *rebuilds* a new ring inside a new
topology generation (`new ConsistentHashRing(shardCount, vnodes)` in
[`ShardTopology`](src/main/java/com/recsys/infrastructure/redis/sharding/ShardTopology.java)).
The 150-vnode design means the rebuilt ring reassigns only ~1/N of keys. That ring is
wrapped in a versioned, 30 s-refreshed
[`ShardTopologyProvider`](src/main/java/com/recsys/infrastructure/redis/sharding/ShardTopologyProvider.java)
with a bounded dual-read window so a live reshard is *safe* — the mechanics of the
versioned topology, generation-scoped keys, and the record write/read paths are the
subject of the [Partitioning investigation](14_Partitioning.md#1-consistent-hash-record-sharding)
and the README [Sharded Record Store](README.md#sharded-record-store) section.

## 3. The second consumer — `StableBucketer` (A/B bucketing)

[`StableBucketer`](src/main/java/com/recsys/application/experiment/StableBucketer.java)
reuses the *same* FNV-1a primitive for a different shape of problem: deterministically
assigning a user to an experiment slot.

- It hashes the key `"{userId}:{layerName}"` (the `:layerName` suffix is the salt that
  makes different experiment layers assign independently), then applies the `fmix64`
  finalizer and an **unsigned modulo** into a flat keyspace of `KEYSPACE = 10_000`
  slots — `slot ∈ [0, 10000)`, i.e. 0.01% granularity.
- [`ABTestService`](src/main/java/com/recsys/application/experiment/ABTestService.java)
  turns that slot into a variant by **contiguous range comparison**:
  `aEnd = bucketAPercent × (KEYSPACE/100)`, `bEnd = aEnd + bucketBPercent × …`, then
  A / B / control by which range the slot falls in.

The payoff is what the Javadoc advertises: because allocations are *contiguous ranges
over a stable keyspace*, growing bucket A from 10% to 20% only pulls in the users whose
slot lands in the newly-covered range — **no user is reshuffled**. This replaced
`String.hashCode() % trafficSplitNumber`, which both clustered poorly and changed with
JVM/value specifics.

## 4. Why the ring and the bucket differ

Both consumers share the accumulation hash but arrange the output differently, because
they answer different questions:

| | `ConsistentHashRing` | `StableBucketer` |
|---|---|---|
| Question | "which shard owns this device?" | "which slot in `[0, 10000)`?" |
| Structure | circular 64-bit ring, `ceilingEntry` + wraparound | flat linear keyspace, unsigned modulo |
| Virtual nodes | 150 per shard (even spread, minimal remap) | none |
| Finalizer | FNV-1a only | FNV-1a **+ fmix64** avalanche |
| Resize behavior | ~1/N keys move on rebuild | range boundary shifts, no reshuffle |

The ring's wraparound and vnodes exist so that *adding a shard* is cheap; the bucketer
uses a fixed 10k keyspace so that *resizing an allocation* is cheap. Same hash, two
geometries.

## 5. The frozen compatibility contract

Because one primitive feeds both consumers, its entire surface is frozen by the
[consistent-hashing consolidation design](docs/superpowers/specs/2026-06-24-consistent-hashing-consolidation-design.md):
the FNV-1a offset basis and prime, UTF-8 byte handling, the `"v{i}:{shard}"` vnode
label, `TreeMap.ceilingEntry` + wraparound, `DEFAULT_VIRTUAL_NODES`, and
`StableBucketer.KEYSPACE`. The contract's rule is blunt: *no device remapping is
allowed for the same `(shardCount, virtualNodesPerShard, deviceId)` inputs*, and the
non-goals forbid swapping the vnode ring for jump/rendezvous/modulo hashing.

The reason is the shared primitive itself: any change to a constant, the byte handling,
the vnode label, or the vnode count changes *every* `fnv1a64` output — which would
**remap every device** to a different shard (data stranded behind old keys, a cache-miss
storm) **and** shift **every user's A/B bucket** (contaminating in-flight experiments)
in a single edit. That is why the freeze note lives in the primitive's Javadoc, not
just in a design doc.

## 6. Testing

The frozen behavior is pinned by **golden-value** tests, not just properties:

- `HashingTest` — known-answer vectors for `fnv1a64` (e.g. `"device-123"` →
  `8014270626680959582`) and for the composed `fmix64(fnv1a64(...))`, so any accidental
  change to a constant or the byte handling fails loudly.
- `ConsistentHashRingTest` — distribution uniformity within ±20% over 10k devices,
  determinism across two ring instances, output-in-range, and **golden device→shard
  maps for 2/4/8 shards** (the primary anti-drift guardrail, with vnodes pinned at 150).
- `StableBucketerTest` — golden slot values (`slot("123","default") = 8397`),
  determinism, in-keyspace, independent slots per layer, and a spread test proving 10k
  sequential IDs hit >6000 distinct slots (the `String.hashCode` clustering weakness is
  gone).

## Sharp edges — notes

1. **The hash is a one-way ratchet.** Changing FNV-1a, the vnode label, or the vnode
   count is not a config tweak — it's a data migration for record shards *and* an
   experiment reset for A/B. The golden-value tests exist to make an accidental change
   fail in CI, not to be updated to match it.
2. **Two consumers, one blast radius.** Sharding and A/B look unrelated, but they share
   `Hashing` — so "improving the hash" touches both. Any change needs a plan for both.
3. **The ring is minimal-remap, not zero-remap.** A reshard still moves ~1/N of keys;
   what makes it *safe* is the bounded dual-read window in the versioned topology
   (see [14_Partitioning](14_Partitioning.md#versioned-topology-and-online-reshard)),
   not the ring alone.
4. **A/B stability depends on the layer salt.** Two experiments on the same users get
   independent buckets only because `layerName` is mixed into the key; reusing a layer
   name correlates their assignments.
5. **`fmix64` is bucketer-only.** The ring deliberately does *not* apply the avalanche
   finalizer — so `shardFor` and `slot` are not interchangeable even though both start
   from `fnv1a64`.
