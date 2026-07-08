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
2. **The AZ-aware Redis reader is dead code.** `RedisReadReplicaRouter` exists but
   `REDIS_REPLICA_NODES` is never set in any config, so `readable()` returns the single
   ElastiCache primary. Reads stall for the ~30s of a primary-AZ Multi-AZ DNS failover.
3. **A PDB can block recovery.** model-serving's PDB is `minAvailable: 2` (of 3
   replicas) and its Deployment sets `maxUnavailable: 0`; once an AZ loss leaves 2 pods,
   node drains / rollouts are blocked while degraded.
4. **AZ infra assumptions are undocumented.** The ALB spanning ≥2 AZ subnets and node
   groups spanning all AZs are assumed but out-of-band and unverifiable from the repo.

## Goals

- A single-AZ loss in us-east-1 leaves every service with running replicas in the
  surviving AZs, and recovery scheduling is not blocked by our own constraints.
- Reads survive a primary-AZ ElastiCache failover instead of stalling ~30s.
- The operational assumptions that make zonal survival possible are written down.

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
| AZ-aware Redis reads (gap #2) | **Wire `REDIS_REPLICA_NODES`** to the ElastiCache reader endpoint, activating the existing router |
| Gap #2 scope | **us-east-1 (`k8s/eks`) only** on this branch; mirror to `k8s/eks-us-west-2` as a follow-up once DR PR #176 merges (avoids a cross-branch conflict) |
| PDB fix (gap #3) | model-serving PDB `minAvailable: 2` → **`maxUnavailable: 1`** |
| Infra assumptions (gap #4) | New **`docs/runbooks/zonal-resilience.md`** |
| Branch | `feat/zonal-hardening` off `main`, independent of PR #176 |

## Architecture / Changes

All changes are Kubernetes manifests + config + one test-only Java change. No behavioral
application code changes.

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

### Gap #2 — activate the AZ-aware Redis reader (us-east-1)

In `k8s/eks/redis-elasticache-patch.yaml`, add `REDIS_REPLICA_NODES` set to the
ElastiCache **reader** endpoint (placeholder value, matching the existing `REDIS_HOST`
placeholder convention), and update the header comment to document the reader-endpoint
requirement.

Binding path (already present): `application.yml:92` binds
`replica-nodes: ${REDIS_REPLICA_NODES:}`, consumed by `RedisReadReplicaRouter`. With a
non-empty list, `readable()` prefers a same-AZ replica; reads then survive a primary-AZ
loss. With the list empty (every other profile), behavior is unchanged — reads go to the
primary. So this change is additive and scoped to the EKS overlay only.

**Follow-up (out of scope here):** the DR branch's `k8s/eks-us-west-2/redis-elasticache-patch.yaml`
needs the same `REDIS_REPLICA_NODES` line; tracked as a follow-up once PR #176 merges.

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
  Multi-AZ + automatic failover enabled with a reader endpoint provisioned.
- **What survives a single-AZ loss automatically:** cross-zone ALB, `PreferClose`
  cluster-wide fallback, enforced pod spread (post gap #1), AZ-aware Redis reads (post
  gap #2).
- **Expected degradation profile:** brief endpoint-not-ready blip until readiness marks
  dead-AZ pods out; writes still ride the ~30s ElastiCache primary DNS flip (reads now
  covered by the reader endpoint); HPA re-scales survivors on remaining capacity.
- **What still needs an operator:** confirming ALB/node-group AZ coverage (out-of-band).

Cross-link from `.claude/CLAUDE.md` (Kubernetes section).

## Testing

Live AZ-failure testing is not feasible (no CI, no provisioned infra). Layered strategy:

- **Render assertions** (local `kubectl kustomize`):
  - `kubectl kustomize k8s/base` — each of the four Deployments renders
    `whenUnsatisfiable: DoNotSchedule` and `nodeTaintsPolicy: Honor`; the model-serving
    PDB renders `maxUnavailable: 1` (and no longer `minAvailable`).
  - `kubectl kustomize k8s/eks` — the merged `recsys-config` ConfigMap carries
    `REDIS_REPLICA_NODES`.
- **One unit test** — `RedisReadReplicaRouter`: `readable()` returns a replica when the
  replica-nodes list is non-empty, and falls back to the primary when empty. Locks in
  that the newly-wired path behaves and the empty-default is preserved. This is the only
  Java change and it is test-only.
- **Existing suite** — `mvn test` must stay green (JDK 17 per the repo build note).

## Risks & open items

- **Hard spread at rollout time.** `DoNotSchedule` + `maxSkew: 1` interacts with rolling
  updates; with `nodeTaintsPolicy: Honor` and healthy AZs this is fine, but if node
  groups are ever misconfigured to a single AZ, pods would go `Pending`. The runbook
  (gap #4) calls out the multi-AZ node-group requirement.
- **Reader-endpoint staleness.** ElastiCache replica reads are asynchronous; the online
  path already tolerates stale reads (stale-TTL caches), so this is acceptable, but it is
  a read-your-writes relaxation worth noting.
- **us-west-2 mirror deferred.** Until the follow-up lands, the DR region does not have
  AZ-aware reads. Tracked explicitly.
