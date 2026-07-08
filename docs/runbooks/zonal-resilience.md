# Runbook: Single-AZ Failure Resilience (us-east-1)

How the us-east-1 deployment survives the loss of one Availability Zone, and the
out-of-band infrastructure it assumes. This is intra-region AZ resilience — for a
full-region outage see the multi-region DR runbooks (`dr-*.md`).

## Required infrastructure (provision out-of-band)

- **ALB** spans **≥2 AZ subnets**. ALBs are always cross-zone; the WAF ALB
  (`k8s/eks/waf-api-gateway-ingress.yaml`) routes around a dead AZ's targets via
  health checks — but only if it was created across multiple AZ subnets.
- **Node groups span all 3 AZs.** Hard pod spread (`DoNotSchedule`, see below)
  requires real nodes in each AZ; if node groups collapse to one AZ, pods go
  `Pending`. Keep the managed node group / Karpenter provisioners multi-AZ.
- **ElastiCache Multi-AZ + automatic failover enabled.** AZ-aware same-AZ reads
  (routing reads through a replica) are a documented follow-up — see below;
  today reads and writes both ride the ElastiCache primary's Multi-AZ DNS
  failover.

## What survives a single-AZ loss automatically

- **Edge ingress** — cross-zone ALB health-checks around the dead AZ's pods.
- **In-cluster routing** — `trafficDistribution: PreferClose` falls back
  cluster-wide when the local AZ has no ready endpoints (no black-holing).
- **Pod placement** — enforced spread (`topologySpreadConstraints` with
  `maxSkew: 1`, `whenUnsatisfiable: DoNotSchedule`, `nodeTaintsPolicy: Honor`)
  guarantees replicas sit in different AZs, so a single-AZ loss always leaves ≥1
  replica per service. `nodeTaintsPolicy: Honor` excludes the dead AZ's tainted
  nodes from the skew calc, so replacement pods still schedule in surviving AZs.

## Expected degradation profile

- **Endpoint-not-ready blip:** dead-AZ endpoints are removed after Kubernetes
  node-not-ready detection (~40s, `node-monitor-grace-period`) plus
  taint-based eviction; `PreferClose` cluster-wide fallback and app-layer
  retry/timeouts (recall 200ms fail-open, route circuit breakers, load
  shedding, stale-TTL caches) absorb the interim. (Readiness probes only
  govern a pod-level, not node-level, outage.)
- **Reads and writes** both ride the ~30s ElastiCache primary DNS failover
  when the primary's AZ is lost. The online path's 60s stale-TTL caches
  absorb most read impact during that window.
- HPA re-scales survivors onto remaining capacity (node headroom permitting).

## Operator checklist during a suspected AZ event

1. Confirm the AZ impact (AWS Health Dashboard).
2. Verify each service still has Ready pods in the surviving AZs:
   `kubectl -n recsys get pods -o wide` (check the NODE/zone spread).
3. Confirm no service is stuck with `Pending` pods (would indicate node groups
   are not multi-AZ — see Required infrastructure).
4. If the ElastiCache primary was in the lost AZ, expect a ~30s read+write blip
   during the ElastiCache DNS failover; the online path serves reads from
   stale caches meanwhile.
5. After AZ recovery, confirm pods rebalance and PDBs are satisfied.

## Follow-up: AZ-aware reads

`RedisReadReplicaRouter` exists (and is bean-registered) but is not yet
invoked by any production read path — every read path injects the primary
`RedisExecutor` directly, and `OnlinePredictionServer` builds its Redis client
via `LettuceClientFactory.fromEnv()` (primary only). Making reads survive a
primary-AZ ElastiCache failover requires a behavioral code change: routing
reads through `router.readable()` and injecting the pod's AZ (`AWS_AZ`) so the
router can prefer a same-AZ replica. Tracked as future work.
