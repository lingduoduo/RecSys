# CDN Operations

CloudFront fronts the API gateway. Design:
`docs/superpowers/specs/2026-07-14-cdn-edge-acceleration-design.md`.

Like the WAF WebACL and the Route53 records, the distribution is created out-of-band — this
repo has no IaC. There is no state file and no drift detection.

> **The `create-cdn-distribution.sh` payload is the sole source of truth for the distribution
> config.** `aws cloudfront update-distribution` REPLACES the entire configuration — any field
> not present in the payload is silently reset to its default. The script's JSON only sets
> `Comment`, `Enabled`, `HttpVersion`, `Aliases`, `Origins`, `DefaultCacheBehavior`,
> `CacheBehaviors`, `ViewerCertificate`, `WebACLId`, and `PriceClass` — it omits `Logging`,
> `CustomErrorResponses`, `Restrictions`, `DefaultRootObject`, `IsIPV6Enabled`, and
> `OriginGroups`. If an operator turns on access logging or adds a geo restriction via the
> console, the **next routine re-run of the script** (e.g. to rotate the origin secret) silently
> reverts it, with no error and no warning. Any console change that isn't also added to the
> script's `jq` payload does not survive the next run.

## What is and is not cached

Cached: `GET /api/catalog/item` (1 h fresh, 24 h stale-while-revalidate) and
`GET /api/catalog/similar` (5 min fresh, 1 h stale-while-revalidate).

Everything else, including `POST /api/recommend`, is `CachingDisabled` by default and always
reaches the origin. **The hit ratio on the primary recommendation route is zero by design** —
that route is POST-only and personalized. The CDN earns its keep here through edge TLS
termination, WAF, and backbone acceleration, not caching.

## Rollout

Order matters. Reversing steps 5 and 6 locks all traffic out of the origin.

1. Deploy the app (cache headers, ETag/304, origin-secret validation **disabled**). Safe no-op.
2. Create the ACM cert (**us-east-1** — CloudFront ignores certs anywhere else), the
   `CLOUDFRONT`-scope WebACL, and the distribution:
   ```bash
   ORIGIN_DOMAIN=origin.recsys.example.com \
   ALIAS_DOMAIN=app.recsys.example.com \
   ACM_CERT_ARN=arn:aws:acm:us-east-1:<acct>:certificate/<id> \
   WEB_ACL_ARN=arn:aws:wafv2:us-east-1:<acct>:global/webacl/recsys-edge/<id> \
   ORIGIN_SECRET="$(openssl rand -hex 32)" \
   ./scripts/create-cdn-distribution.sh
   ```
   Save the `ORIGIN_SECRET` value — step 4 needs it.
3. Validate against the raw distribution domain. Real traffic is still on the old path:
   ```bash
   D=dXXXXXXXXXXXXX.cloudfront.net
   curl -sI "https://$D/api/catalog/item?id=1" | grep -i 'x-cache\|cache-control\|etag'
   curl -sI "https://$D/api/catalog/item?id=1" | grep -i x-cache   # 2nd call: expect Hit
   curl -sI -X POST "https://$D/api/recommend" | grep -i x-cache   # expect Miss, always
   ```
   Expected: `X-Cache: Hit from cloudfront` on the repeated catalog GET;
   `X-Cache: Miss from cloudfront` on the POST.
4. Create the Secret so the gateway enforces the origin check:
   ```bash
   kubectl -n recsys create secret generic recsys-gateway-origin-secret \
     --from-literal=secret='<the ORIGIN_SECRET from step 2>'
   kubectl -n recsys rollout restart deployment/recsys-api-gateway
   kubectl -n recsys rollout status deployment/recsys-api-gateway
   ```
   Verify probes still pass — `/health` and `/metrics` are exempt from the secret check. If the
   pods go NotReady here, that exemption is broken; roll back the Secret immediately.

   That exemption is deliberate, not incidental: the ALB health check, all three kubelet probes,
   and the Prometheus scrape all reach the pod directly, bypassing CloudFront entirely, and none
   of them can carry the origin secret. The consequence is that `/health` and `/metrics` remain
   reachable by **any** AWS account's CloudFront distribution once the SG is opened to the shared
   prefix list in step 6 — the prefix list, unlike the secret header, does not prove the request
   came from *our* distribution. This is not a new exposure (the ALB is already internet-facing
   today), but `/health` returns per-route circuit-breaker state, upstream reachability, and (when
   `SERVICE_REGISTRY_ENABLED`) registry resolution/topology detail, which is more than a bare
   liveness check would leak. If that becomes a concern, the options are: stop routing `/health`
   and `/metrics` through the distribution (serve them only from the ALB's own DNS name, never
   `app.*`), or move them to a separate management port that the WebACL/SG never expose publicly.
5. Point `app.*` at the distribution (Route53 alias A record). The failover records move to
   `origin.*` and keep working unchanged.
6. **Only now** narrow the ALB security group to the CloudFront prefix list, and retire the
   REGIONAL WebACL:
   ```bash
   aws ec2 authorize-security-group-ingress --group-id <alb-sg> --ip-permissions \
     'IpProtocol=tcp,FromPort=80,ToPort=80,PrefixListIds=[{PrefixListId=<pl-id>}]'
   ```
   Find the prefix list id with:
   ```bash
   aws ec2 describe-managed-prefix-lists --region <region> \
     --filters Name=prefix-list-name,Values=com.amazonaws.global.cloudfront.origin-facing \
     --query 'PrefixLists[0].PrefixListId' --output text
   ```
   Then remove the old 0.0.0.0/0 rule.

Steps 1-4 are invisible to users.

## Accepted limitation: the origin secret travels in cleartext

The origin is `http-only` (`OriginProtocolPolicy: "http-only"` in
`scripts/create-cdn-distribution.sh`) — there is no regional TLS certificate on the ALB. That
means the `x-origin-secret` header CloudFront injects on the POP-to-origin leg travels in
cleartext over that hop. It is observable to anything positioned on that path and, if observed,
replayable — the header check in `GatewayOriginSecret` is a constant-value comparison, not a
nonce or timestamped signature, so a captured value keeps working until rotated. This partially
weakens the origin-lockdown control the header is supposed to provide.

This was a conscious tradeoff, not an oversight: the alternative is a second, regional ACM
certificate (plus its own renewal lifecycle) on the ALB, per region, and that ongoing operational
cost wasn't judged worth it for this system. Revisit if/when ACM-on-ALB happens for another
reason. `scripts/create-cdn-distribution.sh` already sets `HTTPSPort: 443` and
`OriginSslProtocols: ["TLSv1.2"]` in the `CustomOriginConfig` — both are inert while
`OriginProtocolPolicy` is `http-only`, but they're left in place as the hook: switching to
`https-only` later needs no new fields, just that one policy flip plus the ALB listener and cert.

## Freshness after a bulk embedding reload

`POST /setembedding` rewrites the vectors behind `/similar`. After a **bulk** reload, invalidate
once:

```bash
./scripts/invalidate-cdn.sh '/api/catalog/similar*'
```

Do not invalidate per write. A bulk load would issue thousands of calls and exhaust the
1,000-free-path monthly quota.

Single-item edits need no action: the 300 s fresh window bounds the staleness.

## Monitoring

```bash
# Hit ratio (expect high on catalog, ~0 overall — most traffic is uncacheable POSTs)
aws cloudwatch get-metric-statistics --namespace AWS/CloudFront \
  --metric-name CacheHitRate --dimensions Name=DistributionId,Value=<id> \
  Name=Region,Value=Global --start-time "$(date -u -v-1H +%Y-%m-%dT%H:%M:%SZ)" \
  --end-time "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --period 300 --statistics Average
```

A sudden 403 spike at the origin after step 4 means the origin secret does not match — compare
the Secret value against the distribution's `CustomHeaders`.

### If "no distribution found" appears unexpectedly

`create-cdn-distribution.sh` and `invalidate-cdn.sh` both look up the distribution by
`Comment=='recsys-edge'` with `aws cloudfront list-distributions ... 2>/dev/null || true` — they
swallow the AWS CLI's stderr and treat *any* failure (including an auth failure) the same as "the
distribution doesn't exist yet." An expired SSO session or missing/misconfigured credentials
therefore surfaces as:

```
ERROR: no distribution found with Comment='recsys-edge'.
Run ./scripts/create-cdn-distribution.sh first.
```

which reads like an infrastructure gap and points the operator toward *creating* a distribution
that already exists. If you see this message and you're confident the distribution was already
created, do not re-run the create script — run `aws sts get-caller-identity` first. If that
fails or returns unexpectedly, the real problem is authentication (refresh SSO / credentials),
not missing infrastructure.
