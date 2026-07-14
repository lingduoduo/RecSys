# CDN Rollback

Reverse of `docs/runbooks/cdn-operations.md`. Roll back in this order — it is the rollout order
reversed, and skipping ahead strands traffic.

## 1. Restore direct ALB reachability (do this FIRST)

If the SG was already narrowed to the CloudFront prefix list, re-open it before touching DNS,
or the reverted DNS will point at an origin nothing can reach:

```bash
aws ec2 authorize-security-group-ingress --group-id <alb-sg> \
  --protocol tcp --port 80 --cidr 0.0.0.0/0
```

If the REGIONAL WebACL was retired, re-attach it — see `docs/runbooks/waf-webacl.md`.

## 2. Revert DNS

Point `app.*` back at the Route53 failover record set (primary us-east-1 ALB / secondary
us-west-2 ALB). Propagation is bounded by the 30 s TTL.

## 3. Stop enforcing the origin secret

```bash
kubectl -n recsys delete secret recsys-gateway-origin-secret
kubectl -n recsys rollout restart deployment/recsys-api-gateway
```

The env var is `optional: true`, so the pod starts without it and `GatewayOriginSecret` falls
back to disabled. Nothing else changes.

## 4. Disable the distribution (optional)

Leaving it enabled but unreferenced is harmless and makes re-rollout a DNS flip. To disable,
set `Enabled: false` via `update-distribution`.

## What you do NOT need to roll back

The cache headers, ETag/304 support, and `no-store` markers are correct HTTP semantics
independent of any CDN, and cost nothing with no cache in front. Leave them.

`GATEWAY_PUBLIC_PATHS` may be reverted to `/health` if you want the catalog reads
re-authenticated, but this is not required for rollback.
