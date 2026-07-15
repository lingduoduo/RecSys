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

> **Hostname topology changed once the CDN rollout (`docs/runbooks/cdn-operations.md`) is
> complete.** The public hostname (e.g. `app.recsys.example.com`) is now a CloudFront alias, not
> a Route53 failover record set. The two failover records described below moved to the
> `origin.*` hostname (e.g. `origin.recsys.example.com`), which CloudFront treats as its single
> origin. The failover **logic** is unchanged — only the name it lives on moved. See
> `docs/superpowers/specs/2026-07-14-cdn-edge-acceleration-design.md` ("Origin and failover").
> Before that rollout, or if it's ever rolled back, the failover records live directly on the
> public hostname as described below.

- Health check: HTTPS/HTTP on the **primary** API Gateway ALB, path `/health`,
  interval 30s, failure threshold 3.
- Two failover records on the failover hostname — `origin.recsys.example.com` post-rollout,
  or the public hostname (e.g. `api.recsys.example.com`) pre-rollout — TTL 30s:
  - PRIMARY → us-east-1 gateway ALB, associated with the health check.
  - SECONDARY → us-west-2 gateway ALB.
- When the health check goes unhealthy, Route53 serves the SECONDARY record
  automatically. No human action restores the **read** path.
- Post-rollout, CloudFront still resolves the public hostname's traffic to whichever ALB
  `origin.*` currently points at — the DNS failover this section describes still runs, just one
  hop further from the public hostname, bounded by the same 30s TTL.

## Deploy the standby (keep it current)

Every primary deploy must also deploy the standby so it stays warm and current:
```bash
scripts/set-eks-image-digest.sh --tag <release-tag>   # pins BOTH overlays
kubectl --context <us-east-1-ctx>  apply -k k8s/eks
kubectl --context <us-west-2-ctx>  apply -k k8s/eks-us-west-2
```

### Origin secret is required standby state (post-CDN-rollout)

Once the CloudFront rollout in `docs/runbooks/cdn-operations.md` is complete, the
`recsys-gateway-origin-secret` k8s Secret is part of the standby's required state, not just the
primary's. `GATEWAY_ORIGIN_SECRET` is templated from `k8s/base`, so both regions expect it, and
CloudFront's origin is the `origin.*` failover hostname — it sends the same header value
regardless of which region's ALB answers.

**If the standby is missing the Secret, it does not fail loud.**
`GatewayOriginSecret.fromEnvironment` returns `disabled()` when the env var is absent, so a
us-west-2 gateway with no Secret simply stops enforcing the origin-lockdown check — the origin
secret decorator is never registered. A failover in that state silently drops the origin
lockdown entirely rather than rejecting anything, which is easy to miss because nothing errors
and probes still pass.

Create and rotate the Secret in **both** contexts — see the rotation and step-4 procedures in
`docs/runbooks/cdn-operations.md`, which use the same `--context <us-east-1-ctx>` /
`--context <us-west-2-ctx>` two-context form as this runbook.

## On a us-east-1 outage

1. Confirm the outage (AWS Health Dashboard, primary ALB 5xx / failed health check).
2. Route53 has already cut DNS to us-west-2 — verify:
   - Post-CDN-rollout: `dig +short origin.recsys.example.com` resolves to the us-west-2 ALB.
     The public hostname (`app.recsys.example.com`) keeps resolving to CloudFront throughout —
     it never repoints, since the failover now lives one hop behind it.
   - Pre-rollout: `dig +short api.recsys.example.com` resolves to the us-west-2 ALB directly.
3. Verify the standby serves reads:
   - Post-CDN-rollout: `curl -fsS https://app.recsys.example.com/health` returns healthy (routed
     through CloudFront to whichever ALB `origin.*` now points at).
   - Pre-rollout: `curl -fsS https://api.recsys.example.com/health` returns healthy.
4. **Writes are degraded until the data tier is promoted** → run
   `docs/runbooks/dr-data-tier-promotion.md`.
5. Scale-up is automatic (HPA + cluster autoscaler) as traffic arrives; watch
   `kubectl --context <us-west-2-ctx> -n recsys get hpa`.

## RTO / RPO

- Reads: seconds (DNS TTL + health-check interval).
- Writes: minutes (data-tier promotion).
- RPO: ~seconds for MySQL/Redis; streaming = in-flight events on the failed
  region's queue.
