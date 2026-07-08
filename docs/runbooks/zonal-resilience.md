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
- **ElastiCache Multi-AZ + automatic failover enabled**, with **read replicas in
  the other AZs** exposed via `REDIS_REPLICA_NODES` (host:port@az) in
  `k8s/eks/redis-elasticache-patch.yaml`.

## What survives a single-AZ loss automatically

- **Edge ingress** — cross-zone ALB health-checks around the dead AZ's pods.
- **In-cluster routing** — `trafficDistribution: PreferClose` falls back
  cluster-wide when the local AZ has no ready endpoints (no black-holing).
- **Pod placement** — enforced spread (`topologySpreadConstraints` with
  `maxSkew: 1`, `whenUnsatisfiable: DoNotSchedule`, `nodeTaintsPolicy: Honor`)
  guarantees replicas sit in different AZs, so a single-AZ loss always leaves ≥1
  replica per service. `nodeTaintsPolicy: Honor` excludes the dead AZ's tainted
  nodes from the skew calc, so replacement pods still schedule in surviving AZs.
- **Redis reads** — `RedisReadReplicaRouter` prefers a same-AZ replica; a
  primary-AZ loss no longer stalls reads (they route to a surviving replica).

## Expected degradation profile

- Brief endpoint-not-ready blip until readiness probes mark the dead-AZ pods out
  (~15s: `periodSeconds: 5` × `failureThreshold: 3`); app-layer retry/timeouts
  (recall 200ms fail-open, route circuit breakers, load shedding, stale-TTL
  caches) absorb it.
- **Writes** still ride the ElastiCache primary's Multi-AZ DNS failover (~30s) if
  the primary's AZ is the one lost. Reads are covered by the replica router.
- HPA re-scales survivors onto remaining capacity (node headroom permitting).

## Operator checklist during a suspected AZ event

1. Confirm the AZ impact (AWS Health Dashboard).
2. Verify each service still has Ready pods in the surviving AZs:
   `kubectl -n recsys get pods -o wide` (check the NODE/zone spread).
3. Confirm no service is stuck with `Pending` pods (would indicate node groups
   are not multi-AZ — see Required infrastructure).
4. If the ElastiCache primary was in the lost AZ, expect a ~30s write blip during
   its DNS failover; reads should stay up via `REDIS_REPLICA_NODES`.
5. After AZ recovery, confirm pods rebalance and PDBs are satisfied.
