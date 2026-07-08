# Multi-Region DR Failover — Design

**Date:** 2026-07-08
**Status:** Approved (design)
**Author:** brainstormed with Claude Code

## Problem

The system runs as a single, multi-AZ EKS cluster in `us-east-1`. There is exactly
one production kustomize overlay (`k8s/eks`), a single hardcoded ECR region, a single
regional WAF, one cluster's Cloud Map namespace, and a single-cluster digest-based
blue/green deploy flow. A regional outage (us-east-1) takes the entire recommendation
service down with no recovery path.

We want a **disaster-recovery (DR) failover** posture: a second region that can take
over when the primary region fails.

## Goals

- Survive a full `us-east-1` regional outage.
- **Read path continuity is automatic** (serving is read-dominant): recommendations
  keep serving from replicas in the standby region within seconds of DNS failover.
- **Write path recovers within minutes** after a documented (or managed) data-tier
  promotion step.
- Match the repo's existing convention: Kubernetes described as kustomize overlays;
  AWS infrastructure provisioned out-of-band and documented in runbooks. **No new IaC
  toolchain** is introduced.

## Non-Goals

- Active-active / latency-based geo-serving. This is active-passive DR only.
- Data-residency isolation (regions are replicas, not compliance silos).
- Introducing Terraform or any IaC framework (out of scope; infra stays out-of-band).
- Restructuring the existing `k8s/eks` overlay beyond the minimal shared-component
  extraction needed to avoid duplication.

## Decisions (from brainstorming)

| Question | Decision |
|---|---|
| Primary driver | DR / failover (active-passive) |
| Standby posture | **Warm standby** — services running at reduced replica counts |
| Data to replicate | MySQL, ElastiCache Redis, streaming (SQS/Kafka), model artifacts |
| Failover trigger | **Route53 automatic health-check failover** |
| Repo deliverable | Per-region overlay + DR runbooks (no IaC) |
| Secondary region | `us-west-2` (natural DR pair for `us-east-1`) |

## Architecture

### Topology

- **Primary:** `us-east-1` — the existing `k8s/eks` overlay, behavior unchanged.
- **Secondary:** `us-west-2` — **warm standby**. All four services
  (RecSys Serving 6010, Model Serving 8080, Online Serving 7010, API Gateway 8010)
  run continuously at reduced replica counts: HPA `minReplicas` ≈ 50% of primary, with
  cluster-autoscaler headroom so a traffic surge on failover can scale up quickly.
- Both regions are otherwise identical deployments.

### Overlay structure

A new overlay `k8s/eks-us-west-2/` composes `../base` plus a small set of
**shared EKS components** (the region-agnostic patches currently inline in `k8s/eks`,
extracted into a `k8s/eks-shared/` component or kustomize component so both region
overlays reference them instead of duplicating). Each region overlay overrides only
region-specific values:

- ECR registry region (`…dkr.ecr.<region>.amazonaws.com/recsys-backend-service`)
- ElastiCache primary/replica endpoint
- Aurora writer/reader endpoint
- WAF ACLv2 ARN (regional)
- Cloud Map namespace (`recsys.internal` in each region)
- `AWS_REGION` env (drives the SQS client — plumbing already exists)
- Replica counts / HPA `minReplicas` (reduced in the standby)

The existing `k8s/eks` overlay is retargeted to reference the same shared component so
the two overlays stay in lockstep. This is the *minimal* extraction — no broader
restructure.

### The read/write split (central design tradeoff)

RecSys serving is **read-dominant**. This shapes the whole recovery story:

- **Read path** (embedding recall, top-K trending, ONNX ranking) reads from
  ElastiCache and Aurora **replicas** that are already live in us-west-2. When Route53
  fails traffic over, the standby serves reads **immediately** — no promotion needed.
- **Write path** (feedback ingestion, online-learning parameter updates, saga event
  publishing) requires a **writable** data tier. Until the standby's Aurora/ElastiCache
  is promoted to primary, writes are unavailable or degraded (Aurora promotion applies
  only when MySQL is enabled; the app's live write stores are ElastiCache Redis and SQS).

So: automatic DNS failover restores *serving* in seconds; full *write* capability
returns after data-tier promotion (managed or runbook), targeted at minutes.

## Data replication

All mechanisms are AWS-managed and provisioned out-of-band; the repo documents them in
runbooks. Replication scope was chosen to be comprehensive (all four stores).

| Store | Mechanism | On failover |
|---|---|---|
| **MySQL** (only when `MYSQL_ENABLED=true`; default deployment runs with MySQL disabled) | Aurora Global Database — writer in us-east-1, read-replica cluster in us-west-2 (RPO ~1s) | Promote us-west-2 cluster (Aurora Global managed failover, or runbook) |
| **ElastiCache Redis** | Global Datastore — secondary replica in us-west-2, readable | Promote secondary. Much Redis data is also re-seedable (embeddings from classpath, top-K from streaming) as a fallback |
| **Model artifacts** | ECR cross-region replication — pinned image digests replicate to us-west-2 ECR (extends the existing digest-pinning work) | No action; the pinned digest is identical in both regions |
| **Streaming (SQS/Kafka)** | Standby region runs its own queues/topics with warm Flink consumers | Producers repoint to the us-west-2 endpoint (runbook); no cross-region broker replication assumed |

Notes:
- Because model artifacts are baked into the container image, DR for artifacts is
  **ECR registry replication**, not S3 CRR. This keeps digest-pinned deploys valid in
  both regions.
- Streaming has no managed cross-region replication in this design. The standby's
  online path stays warm on its own event stream; on failover, event producers cut over
  to the standby endpoint. Any in-flight events on the failed region's queue are the
  accepted RPO gap for the streaming path.

## Failover & DNS

- **Route53 health check** targets the primary API Gateway ALB health path
  (`/health`). A **failover routing policy** on the public hostname has a primary
  record (us-east-1 ALB) and a secondary record (us-west-2 ALB).
- When the primary health check fails, Route53 serves the secondary record
  automatically. The gateway's IRSA role already carries the Route53 permissions
  (`route53:ChangeResourceRecordSets`), so no new IAM is required for the DNS mechanism.
- **App tier: automatic.** DNS failover + read-from-replica restores serving without
  human action.
- **Data tier: promotion step.** Aurora/ElastiCache promotion is a documented runbook
  step (optionally Aurora Global *managed* failover for hands-off write recovery in a
  later iteration).
- **DNS TTL:** the public failover records use a short TTL (≈30s, consistent with the
  existing Cloud Map DNS TTL convention) to bound propagation.

### Realistic RTO / RPO

- **RTO (reads):** seconds — DNS TTL + health-check interval.
- **RTO (writes):** minutes — data-tier promotion time.
- **RPO:** ~seconds for MySQL/Redis (async replication lag); streaming RPO = in-flight
  events on the failed region's queue at cutover.

## Repo deliverables

1. **`k8s/eks-us-west-2/` overlay** + minimal shared-component extraction
   (`k8s/eks-shared/` or a kustomize `component`) so the two region overlays don't
   duplicate the EKS patches. The existing `k8s/eks` overlay is retargeted to the shared
   component.
2. **DR runbooks** under `docs/runbooks/`:
   - `dr-regional-failover.md` — detect outage, verify Route53 failover, verify standby
     serving.
   - `dr-data-tier-promotion.md` — promote Aurora Global + ElastiCache Global Datastore,
     repoint streaming producers.
   - `dr-failback.md` — return to us-east-1 as primary after recovery.
   - `dr-game-day.md` — scheduled drill to exercise the above.
3. **Overlay render check** — a local/build validation that
   `kustomize build k8s/eks-us-west-2` renders and produces the expected region-specific
   values. The repo has no CI, so this is a local/build step, matching convention.

## Testing strategy

Live regional-failover testing is not feasible (no CI, no provisioned DR infra). The
strategy is layered:

- **Overlay render tests** — assert `kustomize build k8s/eks-us-west-2` succeeds and the
  built manifests carry the correct region-specific values (ECR region, ElastiCache/Aurora
  endpoints, WAF ARN, Cloud Map namespace, `AWS_REGION`, reduced replica minimums).
- **Existing `AWS_REGION` plumbing tests** — already validate that the SQS client honors
  the region env var; these continue to cover the app-side region awareness.
- **Game-day runbook** — the real validation vehicle: a documented, periodically executed
  drill that simulates a primary outage and exercises DNS failover, standby serving, and
  data-tier promotion end-to-end against real infra when it exists.

## Risks & open items

- **Warm-standby sizing vs. cost.** `minReplicas ≈ 50%` is a starting point; the real
  number depends on how fast HPA + cluster autoscaler can absorb a full-traffic surge.
  Revisit with load data.
  - At warm-standby replica counts (1/1/2/1), the base PodDisruptionBudgets
    (`minAvailable: 1`, model `2`) leave zero voluntary-disruption headroom, so node
    drains block in the standby region until the deployment scales up — revisit
    alongside sizing.
- **Data-tier promotion is not automatic in v1.** Reads fail over automatically; writes
  wait on a runbook. Aurora Global managed failover can automate this later.
- **Streaming RPO gap.** In-flight events on the failed region's queue are lost/delayed.
  Acceptable for the online path (reconstructable), but called out explicitly.
- **Shared-component extraction blast radius.** Retargeting `k8s/eks` to a shared
  component touches the live single-region deploy; the render check must prove the
  primary overlay is byte-for-byte equivalent (aside from intended changes) before merge.
