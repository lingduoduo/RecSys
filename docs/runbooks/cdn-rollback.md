# CDN Rollback

Reverse of `docs/runbooks/cdn-operations.md`. Roll back in this order — it is the rollout order
reversed, and skipping ahead strands traffic.

## 1. Restore direct ALB reachability (do this FIRST)

If the SG was already narrowed to the CloudFront prefix list, re-open it before touching DNS,
or the reverted DNS will point at an origin nothing can reach.

**The port MUST match the origin protocol the distribution was created with** (`ORIGIN_PROTOCOL_POLICY`
when `scripts/create-cdn-distribution.sh` ran) — the ALB only listens on the port corresponding to
that protocol, and opening the wrong port leaves it unreachable:

- `ORIGIN_PROTOCOL_POLICY=https-only` (the script's default) → re-open **443**:
  ```bash
  aws ec2 authorize-security-group-ingress --group-id <alb-sg> \
    --protocol tcp --port 443 --cidr 0.0.0.0/0
  ```
- `ORIGIN_PROTOCOL_POLICY=http-only` → re-open **80**:
  ```bash
  aws ec2 authorize-security-group-ingress --group-id <alb-sg> \
    --protocol tcp --port 80 --cidr 0.0.0.0/0
  ```

If the REGIONAL WebACL was retired, re-attach it — see `docs/runbooks/waf-webacl.md`.

## 2. Revert DNS

Point `app.*` back at the Route53 failover record set (primary us-east-1 ALB / secondary
us-west-2 ALB). Propagation is bounded by the 30 s TTL.

## 3. Stop enforcing the origin secret

Delete in **both** contexts. `GATEWAY_ORIGIN_SECRET` lives in `k8s/base` and is deployed to both
the us-east-1 primary and the us-west-2 standby. Deleting it in only one context leaves the
standby holding the secret; on failover, traffic would reach a gateway that still enforces the
origin check against a distribution no longer sending the header — 100% 403 in DR. Delete in
both contexts before moving to the next step:

```bash
kubectl --context <us-east-1-ctx> -n recsys delete secret recsys-gateway-origin-secret
kubectl --context <us-east-1-ctx> -n recsys rollout restart deployment/recsys-api-gateway
kubectl --context <us-east-1-ctx> -n recsys rollout status deployment/recsys-api-gateway

kubectl --context <us-west-2-ctx> -n recsys delete secret recsys-gateway-origin-secret
kubectl --context <us-west-2-ctx> -n recsys rollout restart deployment/recsys-api-gateway
kubectl --context <us-west-2-ctx> -n recsys rollout status deployment/recsys-api-gateway
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
