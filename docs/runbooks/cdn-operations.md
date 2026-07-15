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

Cached: `GET /api/catalog/item` (1 h fresh, 24 h `stale-while-revalidate` +
`stale-if-error`) and `GET /api/catalog/similar` (5 min fresh, 1 h
`stale-while-revalidate` + `stale-if-error`). The two directives cover different
failure modes and both matter: `stale-while-revalidate` serves the stale copy
while refreshing it in the background against a *healthy* origin — it says
nothing about an unhealthy one. `stale-if-error` is what covers an origin
outage: it lets the edge keep serving the cached object when the origin is
unreachable or returns a 5xx, for the same window. `HttpCaching.publicCache`
emits both with the same value, so a total origin outage still serves cached
`/item` for up to 24 h and `/similar` for up to 1 h. See "Freshness" /
"Availability" in the design doc for the full breakdown.

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

   **Origin protocol.** `ORIGIN_PROTOCOL_POLICY` defaults to `https-only`, which requires the ALB
   to have a `:443` listener and a **regional** ACM certificate (separate from the us-east-1 cert
   CloudFront uses for viewers). The ALB today listens on `:80` only
   (`k8s/eks/waf-api-gateway-ingress.yaml`), so that listener must exist before this default works.

   `ORIGIN_PROTOCOL_POLICY=http-only` still works and warns. It is a real weakening: the origin
   secret then crosses the public CloudFront→ALB hop in cleartext, where it is observable and
   replayable by exactly the attacker the header exists to stop. Prefer fixing the listener.
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

## Cleartext exposure: only when http-only is an explicit opt-out

By default (`ORIGIN_PROTOCOL_POLICY=https-only`, see the "Origin protocol" note in step 2 above),
`HTTPSPort: 443` and `OriginSslProtocols: ["TLSv1.2"]` in `scripts/create-cdn-distribution.sh`'s
`CustomOriginConfig` are live, not inert: the `x-origin-secret` header CloudFront injects on the
POP-to-origin leg is encrypted over that hop. That default requires the ALB to have a `:443`
listener and a regional ACM certificate, which the ALB does not have today (it listens on `:80`
only — `k8s/eks/waf-api-gateway-ingress.yaml`), so that listener must exist before the default
actually takes effect end to end.

The cleartext exposure only applies when an operator explicitly opts out with
`ORIGIN_PROTOCOL_POLICY=http-only` (the script warns loudly when this is set). Under that opt-out,
the `x-origin-secret` header travels in cleartext over the POP-to-origin hop: observable to
anything positioned on that path and, if observed, replayable — the header check in
`GatewayOriginSecret` is a constant-value comparison, not a nonce or timestamped signature, so a
captured value keeps working until rotated. This partially weakens the origin-lockdown control the
header is supposed to provide.

This is no longer an unconditional accepted limitation — it is a consequence of an explicit
opt-out for the case where the ALB `:443` listener and regional ACM certificate aren't in place
yet. Once that infrastructure exists and the default `https-only` policy is in effect, the
cleartext exposure does not apply; use `http-only` only as a deliberate, temporary fallback.

## Rotating the origin secret

`GATEWAY_ORIGIN_SECRET` accepts a comma-separated **set** of secrets, so rotation has no 403
window. Both the old and the new secret are accepted while the distribution catches up.

```bash
# 1. Accept both. Pods now take either value.
kubectl -n recsys create secret generic recsys-gateway-origin-secret \
  --from-literal=secret='old-secret,new-secret' --dry-run=client -o yaml | kubectl apply -f -
kubectl -n recsys rollout status deployment/recsys-api-gateway

# 2. Flip the distribution to the new secret (CloudFront propagation takes minutes).
ORIGIN_SECRET='new-secret' ... ./scripts/create-cdn-distribution.sh

# 3. Once propagated, retire the old one.
kubectl -n recsys create secret generic recsys-gateway-origin-secret \
  --from-literal=secret='new-secret' --dry-run=client -o yaml | kubectl apply -f -
kubectl -n recsys rollout status deployment/recsys-api-gateway
```

Do not skip step 1. Going straight to step 2 reintroduces the window this ordering exists to
avoid: the distribution sends a secret no pod accepts, and 100% of non-exempt traffic 403s
until step 3 completes.

**Watch the rejections.** `gateway_origin_secret_rejected_total` is exposed on the gateway's
`/metrics`. It should stay flat throughout a correct rotation. A rise means the distribution and
the pods disagree — the most likely cause is step 1 being skipped or not yet rolled out. The
first rejection also emits one WARN log (only the first, to avoid flooding).

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

`invalidate-cdn.sh` looks up the distribution by `Comment=='recsys-edge'` with
`aws cloudfront list-distributions ... 2>/dev/null || true` — it swallows the AWS CLI's stderr and
treats *any* lookup failure (including an auth failure) the same as "the distribution doesn't
exist yet." An expired SSO session or missing/misconfigured credentials therefore surfaces as:

```
ERROR: no distribution found with Comment='recsys-edge'.
Run ./scripts/create-cdn-distribution.sh first.
```

which reads like an infrastructure gap when the real problem is authentication. If you see this
message and you're confident the distribution was already created, do not re-run the create
script — run `aws sts get-caller-identity` first. If that fails or returns unexpectedly, refresh
SSO / credentials.

`create-cdn-distribution.sh` does the same swallowed-stderr lookup (both for the distribution and
for each cache policy) but does **not** print the message above. On a lookup failure it falls
into its "not found" branch and takes the *create* path instead: it echoes "Creating distribution"
and attempts `aws cloudfront create-distribution` (similarly, a swallowed cache-policy lookup
falls into `aws cloudfront create-cache-policy`). So an expired SSO session here does not surface
as "no distribution found" — it surfaces later, as whatever error the first *unsuppressed* AWS
call throws at the create-cache-policy or create-distribution step. Separately, the script
hardcodes `CallerReference: "recsys-edge-1"`, so if the distribution actually already exists (the
lookup just couldn't confirm it), the attempted create is rejected by AWS with
`DistributionAlreadyExists` instead of silently provisioning a duplicate — a loud failure, but one
that names the wrong problem when the underlying cause was an auth error the lookup masked. Same
remediation applies: run `aws sts get-caller-identity` first whenever either script behaves
unexpectedly.
