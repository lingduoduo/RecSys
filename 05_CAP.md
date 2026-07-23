# CAP in Recsys-Backend-Service

An investigation of the system through the CAP lens: during a network partition you
can keep **consistency** or **availability**, not both, and every distributed store
here makes that choice deliberately. This doc is the *decision map* — which subsystem
lands in which corner and why. The *mechanics* of each choice live in the
[Eventual Consistency investigation](15_Eventual_Consistency.md); this reframes them
as C-vs-A-under-P decisions.

## The big picture

The system's posture is **AP by default, with opt-in CP escape hatches — and CP
writes**:

- **Writes are CP.** A single Redis primary (leader-elected by Sentinel) and atomic
  read-modify-write / `INCR` operations mean writes are linearizable or they don't
  happen; a partitioned minority simply can't write.
- **Reads are AP by default.** The hot read path prefers an AZ-local replica and
  *fails open* to stale data (serve-stale caches, replica fallback, in-memory
  fallbacks) rather than erroring when the primary or a dependency is unreachable.
- **CP is available on request.** A caller who cannot tolerate staleness presents a
  consistency token; that read fails *closed* — it reads the primary and returns
  `503` rather than serve stale. It is off by default, so you pay for consistency
  only where you ask for it.

The one-line summary: **choose availability everywhere the data can be a little
stale; choose consistency exactly where a user must see their own write or a
counter must be exact.**

## The CAP decision table

| Subsystem / operation | Corner | Behavior during a partition | Why |
|---|---|---|---|
| Redis **writes** (records, topology, sequences) | **CP** | minority can't reach the primary → write fails | single leader + atomic RMW/`INCR`; a lost write is worse than a failed one |
| Shard-topology commit (`ShardTopologyStore` Lua/SETNX) | **CP** | RMW on primary; no split-brain generations | one authoritative topology version |
| Sequence assignment (`SequenceGenerator` INCR) | **CP** | monotonic per `(gen, shard)` or unavailable | duplicate seq nums would corrupt device order |
| **Read-your-writes** read (token present) | **CP** | primary unreachable → **`503`, never stale** | the caller explicitly demanded to see their write |
| Default online reads (no token) | **AP** | serve from AZ-local replica, possibly lagging | recommendations tolerate seconds of staleness |
| Serve-stale caches (feature store, top-K, CDN) | **AP** | serve last-good within stale-if-error window | a slightly-old list beats an error |
| Fail-open guards (rate limiter, bloom, popularity) | **AP** | Redis down → admit / skip / in-memory fallback | availability of the request path wins |
| Fire-and-forget events (`AsyncEventPublisher`) | **AP** | broker down → drop (counted), never block | at-most-once by design; use the outbox for CP |
| Durable outbox (`OutboxRelay`) | **CP-ish** | committed to MySQL before ack; at-least-once | events that must not be lost |
| Multi-region DR reads / writes | **AP reads / CP writes** | reads fail over in ~30 s; writes need manual promotion | accept an RPO rather than block globally |

## 1. Writes are CP

The write path is deliberately linearizable. Redis runs a **single primary**
(Sentinel handles leader election; the router only fans out *reads*), so every record
write is a pipelined `HSET`+`ZADD`+`XADD` against that one leader. A reshard is an
atomic Lua read-modify-write with a `SETNX` first-writer-wins bootstrap
(`ShardTopologyStore`), and sequence numbers come from an atomic `INCR`
(`SequenceGenerator`). During a partition the side that can't reach the primary simply
can't write — the system chooses "no write" over "conflicting writes," because a
duplicate sequence number or a split-brain topology would corrupt the sharded record
order. The mechanics are in [14_Partitioning](14_Partitioning.md#versioned-topology-and-online-reshard)
and [15_Eventual_Consistency §3a](15_Eventual_Consistency.md).

## 2. Reads are AP by default

The read path optimizes for availability. `RoutingRedisExecutor` sends reads to the
AZ-local replica, then a random replica, then the primary — so a briefly-unreachable
primary AZ degrades read latency, not availability (see the README
[Redis Read Replicas](README.md#redis-read-replicas)). On top of that, nearly every
view **fails open / serves stale**: the feature store and top-K store serve their
last-good snapshot within a 60 s stale-if-error window, the CDN serves cached catalog
reads for up to 24 h on origin error, the Redis rate limiter admits when its breaker
is open, the bloom/hot-key guards degrade to a Redis round-trip or an in-memory
fallback, and every periodically-refreshed view (topology, registry, DNS) keeps its
last-good snapshot on refresh error (**fail-static**). None of these turn a partition
into an outage; they turn it into bounded staleness. The full stale-window catalog is
[15_Eventual_Consistency §2](15_Eventual_Consistency.md); the fail-open resilience is
[18_Fault_Tolerance](18_Fault_Tolerance.md#redis-resilience).

## 3. The CP escape hatch — bounded read-your-writes

Availability-by-default would let a user *not* see their own just-written event, so
the system offers an opt-in CP path (`ONLINE_DURABLE_EVENTS_ENABLED`, off by default).
On a write the server durably commits to a MySQL outbox before acking and returns an
HMAC-signed `X-Consistency-Token`. On the next read, if the caller presents the token,
the server polls the **primary** for the event's lineage marker (up to 2 s) and:

- materialized → serves from the **primary** (the caller sees their write) — **C**;
- not yet after 2 s → **`202` + `Retry-After`** (surfaces the staleness, doesn't lie);
- primary unavailable → **`503`** — **fails closed, never served from stale cache**.

That last line is the CAP choice made explicit: this specific read prefers
*unavailable* over *inconsistent*, the opposite of the default path. The full flow is
[15_Eventual_Consistency §1](15_Eventual_Consistency.md) and the README
[Durable Eventual Consistency](README.md#durable-eventual-consistency).

## 4. The tunable dial

CAP here isn't a single global setting — it's a **per-read dial**. The same query can
be:

- **AP** — no token, AZ-local replica read (fast, possibly stale); the default.
- **CP** — token present, primary read with a bounded wait (consistent, slower, can
  `503`).

So `RoutingRedisExecutor` + the consistency token together let each caller pick its
own corner per request, rather than forcing one consistency level on the whole system.
Writes have no such dial — they are always CP.

## 5. Partition tolerance — the P is real, and bounded

Partitions are handled, not wished away:

- **Reshard windows** — during a topology change the fleet is briefly split across
  generations; per-device reads dual-read both generations for a bounded 24 h window
  so no record is lost (shard-level scans do *not* — a known sharp edge). See
  [14_Partitioning §1](14_Partitioning.md#1-consistent-hash-record-sharding).
- **Redis failover** — Sentinel re-elects a primary; the read router keeps serving
  from replicas throughout ([18_Fault_Tolerance](18_Fault_Tolerance.md#redis-resilience)).
- **Regional partition (DR)** — active-passive us-east-1 → us-west-2, Route53
  health-check failover (~30 s for reads), manual data-tier promotion for writes, and
  an **accepted RPO** (Aurora/ElastiCache Global async lag ~seconds; in-flight
  streaming events are accepted loss). This is a deliberate AP-for-reads / manual-CP-
  for-writes stance at the region boundary — detailed in
  [15_Eventual_Consistency §3e](15_Eventual_Consistency.md).

## 6. PACELC — the "else, latency" half

CAP only describes behavior *during* a partition; PACELC adds: **else** (normal
operation), you still trade **L**atency against **C**onsistency. This system leans **L**
there too:

- AZ-local replica reads trade consistency for latency (and cross-AZ cost) even with
  no partition — the data may be replica-lag stale by design.
- The read-your-writes token trades latency the other way: a caller accepts up to 2 s
  of added wait to get consistency.
- Serve-stale caches trade freshness for a fast local hit on every request, not just
  during failures.

So the honest label is **PA/EL**: partition → available, else → low-latency, with
consistency as the opt-in exception on both sides.

## Sharp edges — notes

1. **AP-by-default means silent staleness is normal.** Without a token, a read can
   miss a just-written event; that's not a bug, it's the chosen corner. Callers that
   can't tolerate it must opt into the token path.
2. **The CP path is off by default.** `ONLINE_DURABLE_EVENTS_ENABLED=false` ships,
   so out of the box *everything* is AP — read-your-writes exists but isn't engaged
   until enabled and until clients present tokens.
3. **Write-availability is sacrificed on purpose.** A partitioned pod that can't reach
   the primary cannot write; there is no multi-primary / last-write-wins write path,
   because record ordering and sequence uniqueness require a single leader.
4. **Region-boundary consistency is manual.** DR write failover is an operator step
   with an accepted RPO — the system does not auto-promote a standby primary, trading
   automation for avoiding split-brain across regions.
5. **Two consistency domains can disagree.** `OnlineLearner` biases are per-pod (30 s
   flush), so during a partition two pods can rank the same user differently — an
   availability choice with a visible consistency cost. See
   [15_Eventual_Consistency §4](15_Eventual_Consistency.md).
