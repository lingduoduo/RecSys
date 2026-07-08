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
- **ElastiCache Multi-AZ + automatic failover enabled**, with a **reader
  endpoint** configured via `REDIS_REPLICA_NODES` (reads route there via
  `RoutingRedisExecutor`).

## What survives a single-AZ loss automatically

- **Edge ingress** — cross-zone ALB health-checks around the dead AZ's pods.
- **In-cluster routing** — `trafficDistribution: PreferClose` falls back
  cluster-wide when the local AZ has no ready endpoints (no black-holing).
- **Pod placement** — enforced spread (`topologySpreadConstraints` with
  `maxSkew: 1`, `whenUnsatisfiable: DoNotSchedule`, `nodeTaintsPolicy: Honor`)
  guarantees replicas sit in different AZs, so a single-AZ loss always leaves ≥1
  replica per service. `nodeTaintsPolicy: Honor` excludes the dead AZ's tainted
  nodes from the skew calc, so replacement pods still schedule in surviving AZs.
- **Redis reads** — routed to the ElastiCache reader endpoint via
  `RoutingRedisExecutor`; a primary-AZ loss no longer stalls reads (writes
  still ride the ~30s primary DNS failover).

## Expected degradation profile

- **Endpoint-not-ready blip:** dead-AZ endpoints are removed after Kubernetes
  node-not-ready detection (~40s, `node-monitor-grace-period`) plus
  taint-based eviction; `PreferClose` cluster-wide fallback and app-layer
  retry/timeouts (recall 200ms fail-open, route circuit breakers, load
  shedding, stale-TTL caches) absorb the interim. (Readiness probes only
  govern a pod-level, not node-level, outage.)
- **Reads** route to the ElastiCache reader endpoint and survive a
  primary-AZ loss. **Writes** ride the ~30s ElastiCache primary DNS flip
  when the primary's AZ is lost; the online path's 60s stale-TTL caches
  absorb residual read impact.
- HPA re-scales survivors onto remaining capacity (node headroom permitting).

## Operator checklist during a suspected AZ event

1. Confirm the AZ impact (AWS Health Dashboard).
2. Verify each service still has Ready pods in the surviving AZs:
   `kubectl -n recsys get pods -o wide` (check the NODE/zone spread).
3. Confirm no service is stuck with `Pending` pods (would indicate node groups
   are not multi-AZ — see Required infrastructure).
4. If the ElastiCache primary was in the lost AZ, expect a ~30s **write** blip
   during the ElastiCache DNS failover; reads continue via the reader
   endpoint.
5. After AZ recovery, confirm pods rebalance and PDBs are satisfied.

## Follow-up: AZ-aware reads

Same-AZ read locality (per-node endpoints + `AWS_AZ` injection) is an
**optional** cross-AZ-cost optimization; reads already survive via the
reader endpoint.
