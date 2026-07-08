# Runbook: Regional DR Failover (us-east-1 → us-west-2)

Active-passive DR. `us-east-1` is primary; `us-west-2` is a warm standby running
the `k8s/eks-us-west-2` overlay. See the design at
`docs/superpowers/specs/2026-07-08-multi-region-dr-failover-design.md`.

## One-time AWS setup (out-of-band, no IaC)

1. **Second EKS cluster** in us-west-2 running the four services:
   `kubectl apply -k k8s/eks-us-west-2` (after pinning the digest — see below).
2. **ECR cross-region replication**: add a registry replication rule copying
   `recsys-backend-service` from us-east-1 to us-west-2 so the pinned digest exists
   in both regions.
3. **Aurora Global Database**: primary writer cluster in us-east-1, secondary
   read-replica cluster in us-west-2.
4. **ElastiCache Global Datastore**: primary in us-east-1, secondary (readable) in
   us-west-2.
5. **Route53 health check + failover records** (see below).

## Route53 automatic failover

- Health check: HTTPS/HTTP on the **primary** API Gateway ALB, path `/health`,
  interval 30s, failure threshold 3.
- Two failover records on the public hostname (e.g. `api.recsys.example.com`),
  TTL 30s:
  - PRIMARY → us-east-1 gateway ALB, associated with the health check.
  - SECONDARY → us-west-2 gateway ALB.
- When the health check goes unhealthy, Route53 serves the SECONDARY record
  automatically. No human action restores the **read** path.

## Deploy the standby (keep it current)

Every primary deploy must also deploy the standby so it stays warm and current:
```bash
scripts/set-eks-image-digest.sh --tag <release-tag>   # pins BOTH overlays
kubectl --context <us-east-1-ctx>  apply -k k8s/eks
kubectl --context <us-west-2-ctx>  apply -k k8s/eks-us-west-2
```

## On a us-east-1 outage

1. Confirm the outage (AWS Health Dashboard, primary ALB 5xx / failed health check).
2. Route53 has already cut DNS to us-west-2 — verify:
   `dig +short api.recsys.example.com` resolves to the us-west-2 ALB.
3. Verify the standby serves reads:
   `curl -fsS https://api.recsys.example.com/health` returns healthy.
4. **Writes are degraded until the data tier is promoted** → run
   `docs/runbooks/dr-data-tier-promotion.md`.
5. Scale-up is automatic (HPA + cluster autoscaler) as traffic arrives; watch
   `kubectl --context <us-west-2-ctx> -n recsys get hpa`.

## RTO / RPO

- Reads: seconds (DNS TTL + health-check interval).
- Writes: minutes (data-tier promotion).
- RPO: ~seconds for MySQL/Redis; streaming = in-flight events on the failed
  region's queue.
