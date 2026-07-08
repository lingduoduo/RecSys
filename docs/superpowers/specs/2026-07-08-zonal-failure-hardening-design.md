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

Deferred (not achieved by this branch): reads surviving a primary-AZ ElastiCache
failover instead of stalling ~30s. That requires wiring `RedisReadReplicaRouter` into
production read paths, which is a behavioral code change — see gap #2 below.

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
| AZ-aware Redis reads (gap #2) | **Descoped to a follow-up.** `REDIS_REPLICA_NODES` config alone is inert — no read path calls the router. Activating it requires wiring `router.readable()` into the read paths plus injecting the pod's AZ (`AWS_AZ`), a behavioral code change out of scope for this branch |
| Gap #2 scope | **Descoped for both regions** (`k8s/eks` and `k8s/eks-us-west-2`) pending the router-wiring follow-up; not a per-region rollout question |
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

### Gap #2 — AZ-aware Redis reads (deferred follow-up, not done on this branch)

Earlier drafts of this design planned to add `REDIS_REPLICA_NODES` to
`k8s/eks/redis-elasticache-patch.yaml` to activate `RedisReadReplicaRouter`. That plan
was wrong: setting the config alone does nothing, because **no production read path
calls the router**. Every read path (`RecSysServer`'s recall/serving code, the online
prediction server, etc.) injects the primary `RedisExecutor` directly, and
`OnlinePredictionServer` builds its Redis client via `LettuceClientFactory.fromEnv()`,
which only ever points at the primary. `application.yml:92` does bind
`replica-nodes: ${REDIS_REPLICA_NODES:}` into the router bean, and the router's own
`readable()`/`writable()` logic is correct and unit-tested — but nothing in the
call graph invokes it. So `REDIS_REPLICA_NODES` would build replica `RedisExecutor`
instances that sit unused, and reads would still stall for ~30s on a primary-AZ
ElastiCache DNS failover exactly as they do today.

This branch reverts the inert config (commit 80d6e72) rather than ship a setting that
implies protection it doesn't provide. Making reads actually survive a primary-AZ loss
is a behavioral code change: route reads through `router.readable()` at each call site
and inject the pod's AZ (`AWS_AZ`, from the Kubernetes Downward API) so the router can
prefer a same-AZ replica. Tracked as follow-up work, not part of this branch's scope.

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
  Multi-AZ + automatic failover enabled (AZ-aware reads are a documented follow-up, not
  yet wired).
- **What survives a single-AZ loss automatically:** cross-zone ALB, `PreferClose`
  cluster-wide fallback, enforced pod spread (post gap #1).
- **Expected degradation profile:** dead-AZ endpoints are removed after Kubernetes
  node-not-ready detection (~40s) plus taint-based eviction, absorbed by `PreferClose`
  fallback and app-layer retry/timeouts; reads and writes both still ride the ~30s
  ElastiCache primary DNS flip when the primary's AZ is lost, with the online path's
  stale-TTL caches absorbing most read impact; HPA re-scales survivors on remaining
  capacity.
- **What still needs an operator:** confirming ALB/node-group AZ coverage (out-of-band).
- **Follow-up:** wiring `RedisReadReplicaRouter` into production read paths (gap #2).

Cross-link from `.claude/CLAUDE.md` (Kubernetes section).

## Testing

Live AZ-failure testing is not feasible (no CI, no provisioned infra). Layered strategy:

- **Render assertions** (local `kubectl kustomize`):
  - `kubectl kustomize k8s/base` — each of the four Deployments renders
    `whenUnsatisfiable: DoNotSchedule` and `nodeTaintsPolicy: Honor`; the model-serving
    PDB renders `maxUnavailable: 1` (and no longer `minAvailable`).
- **One unit test** — `RedisReadReplicaRouter`: `readable()` returns a replica when the
  replica-nodes list is non-empty, and falls back to the primary when empty. This locks
  in that the router's own logic is correct; it has no manifest deliverable on this
  branch since gap #2 is descoped (no read path calls the router). This is the only
  Java change and it is test-only.
- **Existing suite** — `mvn test` must stay green (JDK 17 per the repo build note).

## Risks & open items

- **Hard spread at rollout time.** `DoNotSchedule` + `maxSkew: 1` interacts with rolling
  updates; with `nodeTaintsPolicy: Honor` and healthy AZs this is fine, but if node
  groups are ever misconfigured to a single AZ, pods would go `Pending`. The runbook
  (gap #4) calls out the multi-AZ node-group requirement.
- **Gap #2 descoped for both regions.** `RedisReadReplicaRouter` is not wired into any
  production read path in either region. PR #176 (multi-region DR) is merged and
  `k8s/eks-us-west-2/` is in-tree, but that does not change gap #2's status — activating
  AZ-aware reads requires the same router-wiring + `AWS_AZ` injection follow-up in
  us-east-1 first, then mirroring to us-west-2. Neither region has AZ-aware reads today.
