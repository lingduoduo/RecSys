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
([`Hashing`](../../src/main/java/com/recsys/infrastructure/redis/sharding/Hashing.java)),
its constants are a **frozen contract** (§5): one edit breaks sharding and A/B
simultaneously.

## 1. The FNV-1a primitive

[`Hashing`](../../src/main/java/com/recsys/infrastructure/redis/sharding/Hashing.java) is a
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

[`ConsistentHashRing`](../../src/main/java/com/recsys/infrastructure/redis/sharding/ConsistentHashRing.java)
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
  spotting hot-shard imbalance. It is a library helper only — **no endpoint, metric or
  log exposes it today**, so an operator cannot currently read per-shard load without
  writing code. Wiring it to a surface is unclaimed work, not an existing capability.

**The minimal-remap property is structural, not a resize method.** The ring has no
in-place resize — it is immutable, so a resize *rebuilds* a new ring inside a new
topology generation (`new ConsistentHashRing(shardCount, vnodes)` in
[`ShardTopology`](../../src/main/java/com/recsys/infrastructure/redis/sharding/ShardTopology.java)).
The 150-vnode design means the rebuilt ring reassigns only ~1/N of keys. That ring is
wrapped in a versioned, 30 s-refreshed
[`ShardTopologyProvider`](../../src/main/java/com/recsys/infrastructure/redis/sharding/ShardTopologyProvider.java)
with a bounded dual-read window so a live reshard is *safe* — the mechanics of the
versioned topology, generation-scoped keys, and the record write/read paths are the
subject of the [Partitioning investigation](14_Partitioning.md#1-consistent-hash-record-sharding)
and [Database Sharding](03_DB_Scaling_Sharding.md).

## 3. The second consumer — `StableBucketer` (A/B bucketing)

[`StableBucketer`](../../src/main/java/com/recsys/application/experiment/StableBucketer.java)
reuses the *same* FNV-1a primitive for a different shape of problem: deterministically
assigning a user to an experiment slot.

- It hashes the key `"{userId}:{layerName}"` (the `:layerName` suffix is the salt that
  makes different experiment layers assign independently), then applies the `fmix64`
  finalizer and an **unsigned modulo** into a flat keyspace of `KEYSPACE = 10_000`
  slots — `slot ∈ [0, 10000)`, i.e. 0.01% granularity.
- [`ABTestService`](../../src/main/java/com/recsys/application/experiment/ABTestService.java)
  turns that slot into a variant by **contiguous range comparison**:
  `aEnd = bucketAPercent × (KEYSPACE/100)`, `bEnd = aEnd + bucketBPercent × …`, then
  A / B / control by which range the slot falls in.

The payoff is what the Javadoc advertises: because allocations are *contiguous ranges
over a stable keyspace*, growing bucket A from 10% to 20% only pulls in the users whose
slot lands in the newly-covered range — **no user is reshuffled**. This replaced
`String.hashCode() % trafficSplitNumber`, which both clustered poorly and changed with
JVM/value specifics — the requirement is spelled out in the
[A/B test reliability design](../superpowers/specs/2026-06-19-abtest-reliability-design.md).

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
[consistent-hashing consolidation design](../superpowers/specs/2026-06-24-consistent-hashing-consolidation-design.md):
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

## Hashing elsewhere in the system — and why it isn't consistent hashing

IDs are hashed all over the stack, but *consistent hashing* is a narrow thing: the
FNV-1a ring for shard placement (and its A/B twin). The other hashing serves entirely
different jobs — integrity, cache-keying, credential comparison, membership, or
approximate similarity — and is documented in the subsystem that owns it:

| Where | Algorithm | Job | Covered in |
|---|---|---|---|
| Record shards, A/B buckets | **FNV-1a ring / keyspace** | even distribution + minimal remap | **this doc** |
| Catalog keyset cursor (`CatalogCursorCodec`) | HMAC-SHA256 | tamper-proof, filter-bound pagination cursor | [13_DB_Indexing](13_DB_Indexing.md), [14_Partitioning](14_Partitioning.md#4-keyset--cursor-pagination--partitioning-a-result-set) |
| Consistency token (`ConsistencyTokenCodec`) | HMAC-SHA256 | signed read-your-writes token | [15_Eventual_Consistency](15_Eventual_Consistency.md) |
| LLM response cache (`LlmResponseCache`) | SHA-256 of the request body | cache key | [API Gateway](09_API_Gateway.md), [SSE Streaming](16_SSE_Streaming.md) |
| API-key identity (`GatewayPrincipal`) | SHA-256 prefix | a non-reversible principal id for rate-limit keys / logs | [09_API_Gateway §3](09_API_Gateway.md#3-authentication) |
| API keys / origin secret / admin token | constant-time compare (`MessageDigest.isEqual`) | credential authentication | [09](09_API_Gateway.md#3-authentication), [12_CDNS §3](12_CDNS.md#3-origin-lockdown--proving-a-request-came-from-our-distribution) |
| Cache-penetration guard (`BloomFilterGuard`) | Bloom filter | membership — skip a Redis lookup for a known-absent id | [18_Fault_Tolerance](18_Fault_Tolerance.md#redis-resilience) |
| Embedding recall (`EmbeddingLSH`) | LSH random-hyperplane | approximate nearest neighbor | [13_DB_Indexing §5](13_DB_Indexing.md#5-other-index-types-for-context) |
| Kafka partitioning | `userId` key → Kafka's internal murmur2 partitioner | per-user ordering across partitions | [14_Partitioning §3](14_Partitioning.md#3-kafka-topic-partitioning--flink-keyed-pipeline) |
| Trending top-K reads | *no hash, and no sharding* — one canonical snapshot key per window | hot-key load absorbed by a 2 s JVM cache + single-flight | [14_Partitioning §2](14_Partitioning.md#2-windowed-top-k-replica-sharding) |

The distinction that matters: **consistent hashing answers "which node/shard owns this
key, and how little moves when the set of shards changes?"** The others answer "is this
value authentic?" (HMAC), "have I computed this before?" (cache-key SHA-256), "does this
credential match?" (constant-time compare), "might this id exist?" (Bloom), or "what is
this vector near?" (LSH). Only the first is consistent hashing — which is why only the
FNV-1a ring and `StableBucketer` share the frozen `Hashing` primitive, and none of the
others do.

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

## 7. Case one — Amazon DynamoDB, the system that moved *away* from the ring

DynamoDB is the example everyone reaches for when defending a hash ring, and it is the
wrong one — because "DynamoDB" names two systems, and only the older one has a ring.
Everything below separates what a paper published, what AWS documents, and what is merely
widely repeated, because this repo has been burned before by confident claims nobody
checked.

**The 2007 Dynamo paper is the direct ancestor of `ConsistentHashRing`** (DeCandia et al.,
*Dynamo: Amazon's Highly Available Key-value Store*, SOSP '07 —
[PDF](https://www.allthingsdistributed.com/files/amazon-dynamo-sosp2007.pdf)). Its §4.2
describes exactly the structure in §2 of this document: a fixed circular hash space, nodes
at random positions, a clockwise walk to find the owner. It also states the reason for
virtual nodes in terms this document should have quoted from the start — *"the random
position assignment of each node on the ring leads to non-uniform data and load
distribution"* — and adds a use `ConsistentHashRing` has no equivalent of: **the number of
virtual nodes per node is tuned to that node's capacity**, which is how a ring expresses
heterogeneous hardware. Here every shard gets the same 150 and the shards are key prefixes
on one server, so there is no heterogeneity to express.

**The same paper then reports that Amazon abandoned that scheme in production.** §6.2,
*Ensuring Uniform Load Distribution*, evaluates three partitioning strategies:

| | Strategy 1 | Strategy 2 | Strategy 3 |
|---|---|---|---|
| Scheme | T random tokens per node, **partition boundaries are the tokens** | T random tokens, but the hash space is pre-split into Q equal partitions | Q equal partitions, each node holds exactly **Q/S tokens** |
| Status in the paper | "the initial strategy deployed in production (and described in Section 4.2)" | an interim step during migration | **the one they migrated to** |

Strategy 1 is the design this repo runs. The paper's objections to it are operational, not
theoretical: a joining node has to "steal" ranges, which forces a scan of the local
persistence store — *"during busy shopping season… the bootstrapping has taken almost a day
to complete"*; Merkle trees must be recomputed on every join or leave; and *"there was no
easy way to take a snapshot of the entire key space due to the randomness in key ranges"*,
so archival is inefficient. The root cause is stated in one sentence: **"the schemes for
data partitioning and data placement are intertwined."** Strategy 3 won on measured
load-balancing efficiency *and* **"reduces the size of membership information maintained at
each node by three orders of magnitude"**, because a fixed partition can be stored as a
file and relocated as a unit. Its one stated cost: *"changing the node membership requires
coordination."*

That trade — give up derive-placement-from-the-node-list, gain coordinated, controllable
partitions — is the same trade this repo made when it put `shard:topology` in Redis (§10).

**Today's DynamoDB service is not that system, and publishes no ring at all.** The
authoritative public description is Elhemali et al., *Amazon DynamoDB: A Scalable,
Predictably Performant, and Fully Managed NoSQL Database Service*,
[USENIX ATC '22](https://www.usenix.org/conference/atc22/presentation/elhemali). Its own §2
says the service shares the name but little of the architecture. What it publishes:

- *"Each partition of the table hosts a disjoint and contiguous part of the table's
  key-range"* — **range partitioning over hash output**, with movable boundaries, not
  ring tokens. Replication groups run **Multi-Paxos**, not sloppy quorums.
- Splitting is traffic-aware: *"The split point in the key range is chosen based on key
  distribution the partition has observed… more effective than splitting the key range in
  the middle"*, and *"partition splits usually complete in the order of minutes."*
- Splitting is deliberately *suppressed* where it cannot help: *"a partition receiving high
  traffic to a single item or a partition where the key range is accessed sequentially will
  not benefit from split. DynamoDB detects such access patterns and avoids splitting."*
- Adaptive capacity was **replaced** by global admission control.

The phrase "consistent hashing" does not appear in that paper. The word "hash" appears
twice: once for the unnamed internal partition-key hash, and once for *"All the GAC servers
are part of an independent hash ring"* — which is the **admission-control fleet**, not the
data plane. Citing it as evidence that DynamoDB partitions on a ring is a misreading.

What **AWS itself documents** is narrower still: the partition key is fed to *"an internal
hash function"* whose output *"determines the partition in which the item will be stored"*
([Partitions and data distribution](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.Partitions.html));
3,000 RCU / 1,000 WCU per partition and 10 GB as the maximum partition size
([Constraints](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Constraints.html));
and an *"automatic split-for-heat mechanism"*
([hot-partition mitigation](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/throttling-key-range-limit-exceeded-mitigation.html)).
The hash **algorithm is never named** — the frequently repeated "DynamoDB uses MD5" has no
AWS source. Neither does "DynamoDB uses consistent hashing," which appears in no current
DynamoDB documentation page.

**What this repo can and cannot take from it.** The `(hash key, sort key)` shape is already
noted as the analogue of `ConsistentHashRing` + a Redis ZSET
([13_DB_Indexing §5](13_DB_Indexing.md#5-other-index-types-for-context)), and that parallel
holds. The rest does not:

| DynamoDB (published) | This repo |
|---|---|
| Splits the *one* partition that is hot, at a point chosen from observed traffic | Cannot express "split shard 3". A reshard changes `shardCount` and re-derives **every** shard from the ring |
| Detects that a split will not help and declines it | No hot-shard detection at all — `distribution()` exists but nothing calls it (§2) |
| Partition boundaries live in control-plane metadata | Placement is derived from `(shardCount, vnodes)`, so it *cannot* carry per-partition decisions |
| Splits complete in minutes, managed | A reshard is an operator `POST` plus a 24 h dual-read window ([03](03_DB_Scaling_Sharding.md#3-versioned-topology--online-reshard)) |

The one hot-key defence this repo *does* have is not a hashing mechanism at all: the
trending key is a single canonical snapshot fronted by a 2 s JVM cache and single-flight
([14_Partitioning §2](14_Partitioning.md#2-windowed-top-k-replica-sharding)). Which is the
honest summary — for hot keys, the ring contributes nothing and caching contributes
everything.

## 8. Case two — the CDN edge, where two different questions get the same name

Consistent hashing was invented for CDNs, so it is worth being exact about *which* CDN
problem it solved. Karger et al., *Consistent Hashing and Random Trees: Distributed Caching
Protocols for Relieving Hot Spots on the World Wide Web*, STOC '97
([PDF](https://www.cs.princeton.edu/courses/archive/fall09/cos518/papers/chash.pdf)), says
it plainly: *"Our work was originally motivated by the problem of hot spots on the World
Wide Web,"* and the problem is **clients disagreeing about the cache set** — *"If the
distribution was done with a classical hash function… almost every item would be hashed to
a new location. Suddenly, all cached data is useless because clients are looking for it in
a different location."* (The paper also already contains virtual nodes, as replication of
each bucket; Dynamo contributed the *term* and the capacity-weighting use, not the idea.)
Two of its authors founded Akamai the following year.

**There are two selection questions at a CDN edge, and only the second one is this.**

1. **Which POP serves this viewer?** Industry practice is split, and both halves are
   documented. Akamai uses hierarchical DNS with sub-minute map updates (*The Akamai
   Network*, ACM SIGOPS OSR 44(3), 2010, §7.2), reserving anycast for its top-level name
   servers. Cloudflare uses **anycast** ([traffic
   flow](https://developers.cloudflare.com/fundamentals/concepts/traffic-flow-cloudflare/)).
   **CloudFront — this repo's CDN — is documented as DNS-based**: *"DNS routes the request
   to the CloudFront POP (edge location) that can best serve the request"*
   ([How CloudFront delivers content](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/HowCloudFrontWorks.html)).
   AWS documents anycast for CloudFront only as an **opt-in static-IP feature**
   ([Anycast static IPs](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/request-static-ips.html)),
   and separates the two paths explicitly in its own walkthrough. So "CloudFront is
   anycast" is a widely repeated claim that AWS has not made about the default path. No
   hashing is involved in this layer either way.
2. **Which cache server *inside* the chosen POP?** This is Karger's original question, and
   it is where consistent hashing genuinely lives. Akamai states it in print — *"Consistent
   hashing is used by the CDN to balance the load within a single cluster of servers…
   the first algorithmic innovation in Akamai's CDN"* (Maggs & Sitaraman, *Algorithmic
   Nuggets in Content Delivery*, ACM SIGCOMM CCR 45(3), 2015,
   [PDF](https://web.mit.edu/6.829/www/currentsemester/papers/cdnalg.pdf)). So does
   Cloudflare — *"chooses a cache server based on the request's cache key… We even use the
   same type of data structure used by our HTTP cache to choose servers: a consistent hash
   ring"* ([Eliminating cold starts 2: shard and
   conquer](https://blog.cloudflare.com/eliminating-cold-starts-2-shard-and-conquer/), 2025).

**For CloudFront specifically, AWS confirms the layer exists and publishes no mechanism.**
The only AWS statement on it is a 2025 networking-blog line that a POP hands a request to a
*"request router"* which *"load-balances client connections across multiple cache servers"*
([Charting the life of an Amazon CloudFront request](https://aws.amazon.com/blogs/networking-and-content-delivery/charting-the-life-of-an-amazon-cloudfront-request)).
No algorithm is named; "consistent hashing" appears nowhere in the CloudFront
documentation. **Do not assert that CloudFront consistent-hashes anything.** The regional
edge cache tier, by contrast, *is* documented in detail on the same page — including that
proxy methods and request-time-dynamic requests bypass it.

**And the cache key is a different object entirely.** AWS defines it as *"the unique
identifier for an object in the cache"*
([Understand the cache key](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/understanding-the-cache-key.html)) —
composed by default of the distribution domain and the URL path, with query strings,
headers and cookies excluded until a cache policy adds them. The documented order of
operations is: DNS picks the POP **first**, then the already-chosen POP looks up the cache
key. The key identifies an object; it selects no location. Blurring the two is the single
most common error in this area.

**What this repo's CDN actually does with a key.** The distribution has four cached
behaviors, and each one's cache key is *path + a whitelist of query parameters*
([12_CDNS §1](12_CDNS.md#1-what-is-cached-and-what-isnt)). The local stand-in makes the
shape literal — `proxy_cache_key "$uri|$arg_id"` and `"$uri|$arg_movieId|$arg_k"`
(`docker/cdn/default.conf.template:64,89,109,129`), which nginx digests to index its
on-disk cache. That digest is a **lookup identity**, and it has none of the properties
§1–§4 are about:

- there is no set of owners to choose between, so there is nothing for a ring to minimise;
- a key that changes does not "move" anywhere, it simply misses and the origin refills it;
- correctness never depends on two parties computing the same *placement*, only on one
  party computing the same *identity* for two requests that deserve the same body.

Which is why every hard problem this repo has had at the edge is the **inverse** of the one
consistent hashing solves. Consistent hashing asks: given a fixed key, pick an owner that
survives the owner set changing. The CDN work asked: given a fixed owner, make the many
spellings of one request collapse onto one key — `cacheKeyIntParam` rejecting non-canonical
integers, `scripts/cdn/normalize-catalog-query.js` rebuilding the query string in
declaration order ([12_CDNS sharp edges 7 and 9](12_CDNS.md#sharp-edges--notes)).
Canonicalisation, not placement.

The only placement-shaped decision the repo makes at the edge is
`PriceClass: "PriceClass_All"` (`scripts/create-cdn-distribution.sh:247`) — which edge
locations may serve — and Origin Shield is deliberately not enabled
([cdn-edge-acceleration design](../superpowers/specs/2026-07-14-cdn-edge-acceleration-design.md)).
Everything downstream of that is AWS's, and unobservable from here.

**A footnote on the family, because §5's non-goals name it.** The classic within-POP
alternative, CARP
([IETF draft-vinod-carp-v1-03](https://www.ietf.org/archive/id/draft-vinod-carp-v1-03.txt),
expired, never standardised), is *not* a ring: it scores every proxy for a URL and *"routes
the URL query to the proxy with the highest score"* — that is rendezvous hashing / Highest
Random Weight (Thaler & Ravishankar, IEEE/ACM ToN 6(1), 1998). §5's frozen contract forbids
swapping the ring for rendezvous hashing by name; that alternative is this one, and it is
the *other* algorithm the CDN world converged on for the same problem.

## 9. What this repo's ring actually does — a measurement

§2 claims the 150 virtual nodes give "even" load and a "~1/N" remap. That claim had never
been measured at the shard counts and vnode count production actually uses. It does not
hold, and the reason is in the vnode label.

**The label is the problem.** `ConsistentHashRing:31` places virtual node `v` of shard `s`
at `fnv1a64("v" + v + ":" + s)`. For a single-digit shard index the shard is the **last
byte** of the string, and FNV-1a's last step is `h ^= b; h *= PRIME` — so ten shards'
`v0` positions are ten values that differ only by `(digit ⊕ digit') × PRIME`:

```
v0:0  = ac0be40007072439      v0:5  = ac0be70007072952
v0:1  = ac0be30007072286      v0:6  = ac0be6000707279f
v0:2  = ac0be200070720d3      v0:7  = ac0be500070725ec
v0:3  = ac0be10007071f20      v0:8  = ac0bec00070731d1
v0:4  = ac0be80007072b05      v0:9  = ac0beb000707301e
                              v0:10 = 5f556f0bf11ffb42   ← two digits, unrelated position
```

All ten agree in their top 20 bits. Their total span is 12,094,627,910,321 ≈ 2⁴³·⁵ — about
**1/1,530,000 of the 64-bit ring**. So for any shard count ≤ 10 the ring is not 150×N
scattered points; it is **150 tight micro-clusters**, and within each cluster the
lowest-sorting point absorbs the entire arc that leads up to it while the other N−1 points
own slivers no wider than 1/1,530,000 of the ring. Effectively **one shard owns each of the
150 arcs outright** — the ring degenerates to 150 buckets dealt out by the low bits of the
shard index, not 150 virtual nodes per shard. (Index 10 and up carry a second digit, which
moves the whole tail of the string and puts them in unrelated positions — which is why the
16 → 17 row below behaves differently from 4 → 5 and 8 → 9.)

Everything else follows from that. Measured against the real constants — an independent
reimplementation of `Hashing` / `ConsistentHashRing` that reproduces every golden value in
`HashingTest`, `StableBucketerTest` and `ConsistentHashRingTest` exactly — over 200,000
device IDs:

| Shards (150 vnodes) | busiest shard | quietest shard | plain `hash % N`, same keys |
|---|---:|---:|---|
| 2 | 1.10× even | 0.90× even | 1.00× / 1.00× |
| 4 | 1.11× | 0.81× | 1.00× / 1.00× |
| 8 | 1.31× | 0.72× | 1.00× / 1.00× |
| 16 | 2.38× | 0.27× | 0.98× / 1.02× |
| 32 | 4.75× | 0.004× — **23 devices out of 200,000** | 0.98× / 1.03× |

The imbalance is a property of **the ring**, not of the key population: computing each
shard's share of the ring's *arc mass* — which does not look at keys at all — reproduces
the same figures, and sequential `device-N` IDs, random UUIDs and random hex strings all
land within a few tenths of a percent of it.

**The remap property fails in the same place.** Adding one shard should move ~1/N of keys.
Measured:

| Resize | keys moved (ring) | minimum for a balanced result | plain `hash % N` |
|---|---:|---:|---:|
| 2 → 3 | 47.5% | 33.3% | 66.7% |
| 4 → 5 | **47.1%** | 20.0% | 80.0% |
| 8 → 9 | **46.4%** | 11.1% | 88.9% |
| 16 → 17 | 12.1% | 5.9% | 94.1% |
| 2 → 4 | 47.5% | 50.0% | 50.0% |
| 4 → 8 | 47.1% | 50.0% | 50.0% |
| 8 → 16 | **73.2%** | 50.0% | 50.0% |
| 16 → 32 | 33.9% | 50.0% | 50.0% |

4 → 5 hands shard 4 **46.9% of the ring**; 8 → 9 hands shard 8 **49.2%**. This is not
noise, it is arithmetic: shard index 4 differs from 0–3 in bit 2, so for roughly half the
150 clusters flipping that bit produces the lowest-sorting point, and shard 4 takes those
clusters whole (measured: 75 of 150 clusters at 5 shards, 74 of 150 at 9). Index 8 does the
same with bit 3. A resize that crosses a new power of two in the shard index therefore
moves about half the keyspace onto the single newest shard — the opposite of both "minimal
remap" and "even load". The rows that move *less* than the balanced minimum do so precisely
because the result is not balanced.

Doublings are the ring's best case and still not reliably better than modulo: 4 → 8 moves
47.1% against modulo's 50%, but **8 → 16 moves 73.2% — worse than modulo**. The ring's only
consistent win is a +1 resize (12–47% against modulo's 67–94%), and that win is bought by
dumping the moved keys onto one shard.

**Why the tests did not catch it.** `ConsistentHashRingTest`'s uniformity test
(`multipleShards_distributionIsUniformWithin20Percent`) constructs
`new ConsistentHashRing(4, 250)` — **250 virtual nodes, a value production never uses**.
With production's `DEFAULT_VIRTUAL_NODES = 150` and that test's own 10,000 `device-N` IDs
the shards receive `[2600, 2700, 1700, 3000]` against an assertion window of
`[2000, 3000]` — the test fails. The golden-assignment tests do use 150, but goldens pin
placement *stability*, never *balance*, so they pass either way. This is the familiar shape:
a property asserted at parameters the system does not run at.

**The cause is one line, and the cure is already in the codebase.** The ring hashes with
`fnv1a64` alone; `StableBucketer` hashes with `fmix64(fnv1a64(...))`. §4's table records
that difference and sharp edge 5 calls it deliberate — but neither says what it costs.
Re-running the identical measurement with the finalizer applied to both the vnode labels
and the lookup key:

| Shards | ring today | ring with `fmix64` |
|---|---|---|
| 2 | 0.90×–1.10× | 0.93×–1.07× |
| 4 | 0.81×–1.11× | 0.87×–1.15× |
| 8 | 0.72×–1.31× | 0.94×–1.08× |
| 16 | 0.27×–2.38× | 0.86×–1.17× |
| 32 | 0.004×–4.75× | 0.91×–1.11× |

That is textbook 150-vnode behaviour. The defect is not the ring, the vnode count, or
FNV-1a; it is placing ring positions with an **unfinalized accumulation hash**, whose high
bits — the only bits `TreeMap` ordering effectively looks at — barely move when the last
byte of a short structured label changes. It is the same weakness Dynamo's §6.2 named in
2007 ("the random position assignment… leads to non-uniform data and load distribution"),
except here the positions are not even random.

None of this is a correctness bug. Placement is deterministic, the goldens hold, and at the
bootstrap default of **2 shards** (`OnlinePredictionServer:195`,
`SHARDED_RECORD_SHARD_COUNT`) the spread is 0.90×–1.10×, which is fine. It is a bug in the
*justification*: the ring is not buying what §2 says it buys, and the further the shard
count is raised the less it buys.

## 10. Ring or topology? — the question the three cases answer

| | 2007 Dynamo (Strategy 1) | DynamoDB today | CloudFront edge | **this repo** |
|---|---|---|---|---|
| Who joins/leaves | storage nodes, continuously, without coordination | AWS-managed, invisible to the caller | POPs and cache servers, continuously | nothing — shards are key prefixes on one Redis primary |
| Placement decided by | consistent-hash ring + vnodes | contiguous key-ranges in control-plane metadata | not published for CloudFront; a ring within a POP for Akamai/Cloudflare | ring, rebuilt per generation |
| Reconfiguration is | uncoordinated, gossiped | an automatic, traffic-informed split | continuous and invisible | an explicit, versioned, operator-triggered publish |
| Cost of getting placement wrong | a day-long bootstrap scan (their words) | throttling, then an automatic split | a cache miss | a dual-read window and stranded keys |

The variable that decides ring-vs-topology is **who changes membership, and what it costs
to move a key**:

- **A ring earns its keep when membership changes constantly and nobody is coordinating
  it.** That is the Karger/Akamai case and the 2007 Dynamo case: servers come and go, there
  is no window in which to publish a new map to every client, and the map must be derivable
  from the member list alone. The ring's real product is not balance — vnodes are a patch
  for balance — it is **not needing a shared, versioned placement document**.
- **An explicit topology earns its keep when reconfiguration is rare, coordinated, and you
  want to control placement.** Once you can publish a versioned document and wait for every
  reader to pick it up, you can split exactly the partition that is hot, keep partition
  sizes equal, relocate one partition as a file, or change the key format. A ring can
  express none of that, because it derives placement from the member set and nothing else.
  Dynamo paid *"coordination [on] changing the node membership"* for exactly this and
  called it a win; DynamoDB today is built entirely on that side.

**This repo has already chosen the second, and kept the first as its placement function.**
The evidence is in what does the work during a reshard. `shard:topology` is an authoritative
versioned document
([`ShardTopologyStore`](../../src/main/java/com/recsys/infrastructure/redis/sharding/ShardTopologyStore.java)),
`publishReshard` is an operator-triggered atomic bump (`ShardTopologyStore:23-44`),
`ShardTopologyProvider` polls it every 30 s into a `volatile` snapshot
(`ShardTopologyProvider:112-121`), keys are generation-prefixed so a new generation writes
into a disjoint keyspace, the key *format* travels with the generation
([`ShardKeys`](../../src/main/java/com/recsys/infrastructure/redis/sharding/ShardKeys.java)),
and `readDevice` dual-reads both generations for a bounded window
(`ShardedRecordStore:165-179`). Every one of those is a mechanism a hash ring exists to
avoid needing — and the last two are things a ring **cannot** express at all.

And the ring's own payoff is unavailable here, because **a "shard" is a key prefix on a
single Redis primary**, not a node
([03_DB_Scaling_Sharding](03_DB_Scaling_Sharding.md#the-big-picture)). "Only ~1/N of keys
move" is a claim about network copies between machines; that is what made Strategy 1's
bootstrap take a day and what made Strategy 3's file-per-partition relocation valuable.
Here nothing crosses a machine on a reshard — records are rewritten under a new prefix by
whatever writes them next, and the old generation TTLs out. The ring is paying the costs of
consistent hashing — a frozen primitive (§5), measurably lumpy load (§9) — for a benefit
this deployment cannot collect.

Two options therefore exist on paper, and both are **migrations, not edits**. This document
proposes neither; it records what the measurement implies:

- **Keep the ring, add the finalizer** (§9). Restores even load at every shard count and
  keeps whatever remap advantage a ring has on a +1 resize. Smallest conceptual change, and
  the code is already in `Hashing`.
- **Drop to `Long.remainderUnsigned(fnv1a64(deviceId), shardCount)`.** Measured at
  0.98×–1.03× at every shard count against the ring's 0.004×–4.75×, and it pays for that
  with a much larger remap on a non-doubling resize (80–94% against the ring's 12–47%). That
  is a price this deployment can afford precisely because moving a key is a rewrite on the
  same server, and the dual-read window already exists to cover a reshard that is not free.

The reason to do neither today is not technical merit; it is §5. The frozen contract names
modulo hashing as a forbidden replacement, any placement change remaps every device, the
same primitive also decides every A/B bucket, and the last format-level reshard already
proved that rolling the fleet back is one-way
([03_DB_Scaling_Sharding](03_DB_Scaling_Sharding.md)). The mechanism to do it safely does
exist — one more generation with a dual-read window — which is the closing irony of the
comparison: the **topology** machinery is what would make changing the **ring** survivable.

## Design specs & plans

The design requirements behind this are captured as paired design specs and
implementation plans under `docs/superpowers/`:

- **[Consistent-hashing consolidation](../superpowers/specs/2026-06-24-consistent-hashing-consolidation-design.md)**
  ([plan](../superpowers/plans/2026-06-24-consistent-hashing-consolidation.md)) — the
  requirement to consolidate onto one shared FNV-1a `Hashing` primitive and the frozen
  compatibility contract (§5).
- **[A/B test reliability](../superpowers/specs/2026-06-19-abtest-reliability-design.md)**
  ([plan](../superpowers/plans/2026-06-19-abtest-reliability.md)) — the requirement
  that produced `StableBucketer`: stable, JVM-independent bucketing to replace
  `String.hashCode() % trafficSplitNumber` (§3).
- **[Dynamic shard topology](../superpowers/specs/2026-06-24-dynamic-shard-topology-design.md)**
  ([plan](../superpowers/plans/2026-06-24-dynamic-shard-topology.md)) — making the ring
  a versioned, runtime-swappable topology so a reshard needs no redeploy (§2; detailed in
  [14_Partitioning](14_Partitioning.md#versioned-topology-and-online-reshard)).

Tangentially, [Redis round-trip batching](../superpowers/specs/2026-06-23-redis-batching-design.md)
optimizes the *sharded* write/seed paths (pipelined `ZADD`) but does not touch the
hashing algorithm.

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
