# Zonal (single-AZ) Failure Hardening — Design

**Date:** 2026-07-08
**Status:** Approved (design)
**Author:** brainstormed with Claude Code

## Problem

An investigation of how the us-east-1 deployment survives a single Availability Zone
(AZ) failure found the traffic and application layers already resilient (cross-zone
ALB, `PreferClose` fallback, and a mature timeout / circuit-breaker / load-shed /
stale-cache stack), but four concrete gaps remain:

1. **Pod spread is best-effort, not enforced.** All four service Deployments use
   `topologySpreadConstraints` with `whenUnsatisfiable: ScheduleAnyway` and no hard
   anti-affinity, so replicas *can* co-locate in one AZ — losing that AZ can drop a
   service harder than expected.
2. **The AZ-aware Redis reader is dead code.** `RedisReadReplicaRouter` exists and is
   bean-registered, but no production read path calls `readable()`/`writable()` — every
   read path injects the primary `RedisExecutor` directly, and `OnlinePredictionServer`
   builds its client via `LettuceClientFactory.fromEnv()` (primary only). Setting
   `REDIS_REPLICA_NODES` alone builds replica executors nothing uses; reads still stall
   for the ~30s of a primary-AZ Multi-AZ DNS failover. Activating the router requires a
   behavioral code change (wiring `router.readable()` into the read paths), not just
   config — out of scope for a manifests-and-config-only branch.
3. **A PDB can block recovery.** model-serving's PDB is `minAvailable: 2` (of 3
   replicas) and its Deployment sets `maxUnavailable: 0`; once an AZ loss leaves 2 pods,
   node drains / rollouts are blocked while degraded.
4. **AZ infra assumptions are undocumented.** The ALB spanning ≥2 AZ subnets and node
   groups spanning all AZs are assumed but out-of-band and unverifiable from the repo.

## Goals

- A single-AZ loss in us-east-1 leaves every service with running replicas in the
  surviving AZs, and recovery scheduling is not blocked by our own constraints.
- The operational assumptions that make zonal survival possible are written down.

- Reads survive a primary-AZ ElastiCache failover instead of stalling ~30s (via the
  reader endpoint — gap #2).

## Non-Goals

- Cross-region DR (covered separately by the multi-region DR branch / PR #176).
- Raising replica floors or changing HPA min/max (the chosen approach enforces spread
  at the current replica counts rather than adding capacity).
- Provisioning AWS infra (subnets, node groups, ElastiCache) — that stays out-of-band
  and is documented, not coded.

## Decisions (from brainstorming)

| Question | Decision |
|---|---|
| Spread guarantee (gap #1) | **Hard spread** (`DoNotSchedule`) **+ `nodeTaintsPolicy: Honor`** so a dead AZ's tainted nodes are excluded from the skew calc and don't block recovery scheduling |
| AZ-aware Redis reads (gap #2) | **Delivered.** Add a `RoutingRedisExecutor` (execute→primary, executeRead→replica) wrapping `RedisReadReplicaRouter`; swap the entry points to it; point `REDIS_REPLICA_NODES` at the ElastiCache **reader endpoint** (no `AWS_AZ` needed) |
| Gap #2 read target | **Single reader endpoint** (auto-ejects failed replicas). Same-AZ locality via per-node endpoints + `AWS_AZ` is an optional later optimization |
| Gap #2 region scope | Code wiring benefits both regions; `k8s/eks` (us-east-1) gets the reader-endpoint config here, `k8s/eks-us-west-2/` needs its own value set (one-line follow-up) |
| PDB fix (gap #3) | model-serving PDB `minAvailable: 2` → **`maxUnavailable: 1`** |
| Infra assumptions (gap #4) | New **`docs/runbooks/zonal-resilience.md`** |
| Branch | `feat/zonal-hardening` off `main`, independent of PR #176 |

## Architecture / Changes

Gaps #1, #3, #4 are Kubernetes manifests + config + docs. Gap #2 adds a small,
well-bounded Java change (a new `RoutingRedisExecutor` adapter, four entry-point swaps,
and two read-site conversions) to route reads to replicas.

### Gap #1 — enforce AZ spread (hard, outage-safe)

For each of the four service Deployments — `k8s/base/api-gateway.yaml`,
`k8s/base/catalog-serving.yaml`, `k8s/base/model-serving.yaml`,
`k8s/base/online-serving.yaml` — modify the existing `topologySpreadConstraints` entry:

- `whenUnsatisfiable: ScheduleAnyway` → `whenUnsatisfiable: DoNotSchedule`
- add `nodeTaintsPolicy: Honor`

`maxSkew: 1` and the existing `topologyKey: topology.kubernetes.io/zone` /
`labelSelector` are unchanged.

**Why `nodeTaintsPolicy: Honor` is essential (the footgun):** with a naive hard
`DoNotSchedule` + `maxSkew: 1`, a dead AZ counts as a 0-pod domain, so the scheduler
would refuse to place a replacement pod in a surviving AZ (doing so would push skew past
1 relative to the empty AZ). `Honor` (GA in k8s 1.31, already required by the
`PreferClose` routing the cluster uses) tells the scheduler to ignore nodes carrying the
AZ's `NoSchedule`/`NoExecute` failure taint when computing skew, so the dead AZ drops out
of the calculation and recovery scheduling proceeds. Result: spread is enforced in
steady state, without wedging recovery during the outage.

**Low-replica note:** 2-replica services across 3 AZs occupy only 2 of 3 AZs by
construction; hard spread guarantees those 2 replicas are in *different* AZs, so a
single-AZ loss leaves at least 1 replica. This is the intended behavior at current
replica counts (raising floors is a non-goal).

### Gap #2 — AZ-aware Redis reads (delivered via the ElastiCache reader endpoint)

An earlier iteration only added `REDIS_REPLICA_NODES` config, which was **inert**: no
production read path called `RedisReadReplicaRouter`. Every entry point built its client
via `LettuceClientFactory.fromEnv()`, which returns a primary-only executor whose
`executeRead()` delegates to `execute()` — so even the stores that already call
`executeRead()` (`OnlineFeatureStore`, `ShardedTopKStore`, `ShardedRecordStore`, …) hit
the primary. That config was reverted. This iteration wires it up for real.

The change is small because the read/write split already exists in the `RedisExecutor`
interface (`execute` vs `executeRead`):

1. **New adapter** `RoutingRedisExecutor implements RedisExecutor`, wrapping the existing
   `RedisReadReplicaRouter`: `execute`/`executePipelined` → `router.writable()`,
   `executeRead` → `router.readable()`, `close()` → `router.close()`.
2. **Factory** `LettuceClientFactory.routingFromEnv()` (+ a timeout variant for the
   recall path's `RECALL_REDIS_TIMEOUT_MS`) builds the router and wraps it.
3. **Swap four entry points** from `fromEnv()` to `routingFromEnv()`: `RecSysServer`,
   `OnlinePredictionServer`, and `ModelRuntimeProvider` (recall + item-embedding pools).
4. **Convert the embedding reads** in `RedisEmbeddingStore` (`get`, `mget`) from
   `execute()` to `executeRead()` — the recall hot path. The topk/feature/record stores
   already use `executeRead()`, so they route to replicas automatically.
5. **Config**: `REDIS_REPLICA_NODES` in `k8s/eks/redis-elasticache-patch.yaml` points at
   the single ElastiCache **reader endpoint** (`<reader>.cache.amazonaws.com:6379`).

**Why the reader endpoint (not per-node + AWS_AZ):** the single reader endpoint
auto-ejects failed replicas on failover, so a primary-AZ loss leaves reads flowing to a
surviving replica without any AZ knowledge in the app. Same-AZ locality (via per-node
endpoints + injecting the pod's `AWS_AZ`) is a later cross-AZ-cost optimization, not
required for survival.

**Safe by construction:** when `REDIS_REPLICA_NODES` is unset (every non-EKS profile),
the router has no replicas and `readable()` returns the primary — behavior is identical
to today. Only the EKS overlay, with the reader endpoint configured, routes reads to a
replica.

**Consistency:** routing embeddings/topk/features to replicas makes those reads
eventually consistent (replica lag), already acceptable per the online path's stale-TTL
caches. Consistency-sensitive reads (`SequenceGenerator`, `ShardTopologyStore`,
`LoginTokenService`/`SubmitTokenService`) deliberately stay on `execute()` → primary for
read-your-writes.

### Gap #3 — fix the recovery-blocking PDB

In `k8s/base/pdb.yaml`, change the model-serving PDB from `minAvailable: 2` to
`maxUnavailable: 1`. `maxUnavailable`-based budgets scale with the current replica count,
so they always permit exactly one voluntary disruption regardless of how many replicas
are currently up — draining a node or rolling out during a degraded (2-pod) state is no
longer blocked. The other three PDBs (`minAvailable: 1`) are unchanged; at 2-replica
floors they already permit one disruption.

### Gap #4 — document the out-of-band AZ assumptions

New `docs/runbooks/zonal-resilience.md` covering:

- **Required infra:** ALB spans ≥2 AZ subnets; node groups span all 3 AZs; ElastiCache
  Multi-AZ + automatic failover enabled, with a reader endpoint configured via
  `REDIS_REPLICA_NODES` (gap #2).
- **What survives a single-AZ loss automatically:** cross-zone ALB, `PreferClose`
  cluster-wide fallback, enforced pod spread (post gap #1), replica reads via the
  ElastiCache reader endpoint (post gap #2).
- **Expected degradation profile:** dead-AZ endpoints are removed after Kubernetes
  node-not-ready detection (~40s) plus taint-based eviction, absorbed by `PreferClose`
  fallback and app-layer retry/timeouts; reads route to the reader endpoint and survive a
  primary-AZ loss, while **writes** still ride the ~30s ElastiCache primary DNS flip when
  the primary's AZ is lost, with the online path's stale-TTL caches absorbing residual
  impact; HPA re-scales survivors on remaining capacity.
- **What still needs an operator:** confirming ALB/node-group AZ coverage (out-of-band).
- **Follow-up (optional):** same-AZ read locality via per-node endpoints + `AWS_AZ`
  injection — a cross-AZ-cost optimization, not required for survival.

Cross-link from `.claude/CLAUDE.md` (Kubernetes section).

## Testing

Live AZ-failure testing is not feasible (no CI, no provisioned infra). Layered strategy:

- **Render assertions** (local `kubectl kustomize`):
  - `kubectl kustomize k8s/base` — each of the four Deployments renders
    `whenUnsatisfiable: DoNotSchedule` and `nodeTaintsPolicy: Honor`; the model-serving
    PDB renders `maxUnavailable: 1` (and no longer `minAvailable`).
  - `kubectl kustomize k8s/eks` — the merged `recsys-config` ConfigMap carries
    `REDIS_REPLICA_NODES` (the reader endpoint).
- **New unit test** — `RoutingRedisExecutor`: `execute`/`executePipelined` delegate to
  the router's `writable()` executor and `executeRead` to `readable()`; `close()` closes
  the router. The router's own routing logic is already covered by
  `RedisReadReplicaRouterTest`.
- **Existing suite** — `mvn test` must stay green (JDK 17 per the repo build note); the
  four entry-point swaps and the two `RedisEmbeddingStore` read conversions must not
  regress any existing test.

## Risks & open items

- **Hard spread at rollout time.** `DoNotSchedule` + `maxSkew: 1` interacts with rolling
  updates; with `nodeTaintsPolicy: Honor` and healthy AZs this is fine, but if node
  groups are ever misconfigured to a single AZ, pods would go `Pending`. The runbook
  (gap #4) calls out the multi-AZ node-group requirement.
- **Replica read consistency.** Embeddings/topk/features now read from replicas and are
  eventually consistent (replica lag). Accepted: the online path tolerates staleness via
  its stale-TTL caches, and read-your-writes-sensitive paths stay on the primary.
- **us-west-2 mirror.** Gap #2's config (`REDIS_REPLICA_NODES` → reader endpoint) is
  applied to `k8s/eks` (us-east-1) here. `k8s/eks-us-west-2/` inherits the code wiring
  automatically (same binaries) but needs its own reader-endpoint value set — a one-line
  follow-up on that overlay.
- **Same-AZ locality deferred.** Reads survive a primary-AZ loss via the reader endpoint,
  but do not yet prefer a same-AZ replica; per-node endpoints + `AWS_AZ` injection remain
  an optional cross-AZ-cost optimization.
