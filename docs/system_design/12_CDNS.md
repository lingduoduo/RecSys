# CDN Edge in Recsys-Backend-Service

An investigation of the optional CloudFront edge that fronts the API Gateway ALB:
what it caches (narrowly) and what it deliberately doesn't, how the two shared
read routes are made cacheable without leaking private data, how the origin is
locked to *our* distribution, how the distribution is provisioned out-of-band, and
a local nginx stand-in that reproduces the cache semantics with no AWS account.

## The big picture

The CDN does four things, only one of which is caching:

- **Edge TLS termination** — viewer TLS terminates at the edge (ACM cert in
  us-east-1, `TLSv1.2_2021`, SNI-only).
- **Attack drop** — a `CLOUDFRONT`-scope WAF WebACL filters traffic before it
  reaches the region.
- **Backbone acceleration** — even uncacheable requests ride the AWS backbone from
  the edge POP to the origin instead of the public internet.
- **Narrow caching** — exactly two catalog read routes are cached; everything else
  is default-deny.

**The primary route earns nothing from caching — by design.** `POST /api/recommend`
is POST-only and personalized per `userId`, so CloudFront forwards 100% of those
requests to the origin (cache hit ratio zero). Its edge value is TLS, WAF, and
backbone acceleration, not caching. The recurring tension the rest of this doc
resolves: caching a route requires *not* varying on `Authorization`, which is only
safe for genuinely world-readable data — so caching is real but deliberately
limited to the two catalog reads.

The whole thing is created **out-of-band by an idempotent script**, matching how
the WAF WebACL and Route53 records are already managed — this repo has no IaC
toolchain. Design specs:
[cdn-edge-acceleration](../superpowers/specs/2026-07-14-cdn-edge-acceleration-design.md)
and
[local-cdn-and-origin-secret-hardening](../superpowers/specs/2026-07-14-local-cdn-and-origin-secret-hardening-design.md).

## 1. What is cached (and what isn't)

The distribution is **default-deny**: `DefaultCacheBehavior` uses the managed
`CachingDisabled` policy, so every route is uncacheable unless it matches one of
two explicit cache behaviors.

| Path pattern | Policy | `DefaultTTL` | `MaxTTL` | Cache key (query whitelist) |
|---|---|---:|---:|---|
| `/api/catalog/item*` | `recsys-item` (Cache) | 3600s (1h) | 86400s (24h) | `id` |
| `/api/catalog/similar*` | `recsys-similar` (Cache) | 300s (5min) | 3600s (1h) | `movieId`, `k` |
| everything else, incl. `POST /api/recommend`, `/api/catalog/user` | `CachingDisabled` | — | — | — |

Both cached behaviors are **GET/HEAD only**, `redirect-to-https`, `Compress: true`
(gzip + brotli), and set three cache-key behaviors that matter:

- **`QueryStringBehavior: whitelist`** — only the listed params form the cache key.
  Forwarding the full query string would let `?id=1&cachebuster=<n>` fragment the
  cache arbitrarily and act as an origin-DoS amplifier.
- **`HeaderBehavior: none`** — `Authorization` is **not** part of the cache key on
  cached routes (a JWT-keyed cache would fragment per user and never hit). This is
  what forces the "these routes must be public" decision in §2.
- **`CookieBehavior: none`.**

TTL comes from the origin's `Cache-Control: s-maxage`
([`HttpCaching.publicCache`](../../src/main/java/com/recsys/api/serving/HttpCaching.java)),
and `stale-while-revalidate` / `stale-if-error` share the same window — so a total
origin outage still serves cached `/item` for up to 24h and `/similar` for up to
1h. Conversely, `GET /getuser`, `GET /api/v1/token`, and not-found responses are
`Cache-Control: no-store`, so a miss or a single-use token can never be pinned at
the edge.

On uncached routes the origin request policy is `AllViewerExceptHostHeader`, so
`Authorization` *is* forwarded to the origin — `POST /api/recommend` earns nothing
from caching both because it is default-deny and because its method isn't cached,
but the gateway still authenticates it normally.

## 2. Making the two reads cacheable — `GATEWAY_PUBLIC_PATHS`

For the edge to hit at a useful ratio, the two catalog reads must not vary on
`Authorization` — so they are marked public and CloudFront drops `Authorization`
on those behaviors. That is an explicit trust decision: movie catalog metadata and
item-to-item similarity are treated as non-sensitive and world-readable.

Public paths are configured via `GATEWAY_PUBLIC_PATHS` (default `/health`) in
[`GatewayAuthenticator`](../../src/main/java/com/recsys/application/gateway/GatewayAuthenticator.java);
the production value is:

```
GATEWAY_PUBLIC_PATHS=/health,/api/catalog/item,/api/catalog/similar
```

Two safety mechanisms make this hard to get wrong:

- **Prefix-with-boundary matching** — `matchesPrefix` accepts a path only if it
  `equals(prefix)` or `startsWith(prefix + "/")`. So `/api/catalog` would match
  `/api/catalog/user?userId=1` (leaking a private route) — which is exactly why the
  two reads **must be listed as exact paths**, never the bare `/api/catalog`
  prefix. The k8s configmap carries a loud DANGER comment to that effect.
- **Never-public guard** — `PROTECTED_PREFIXES = {/api/catalog/user, /api/users}`
  is checked *first*, so even if an operator mistakenly lists a prefix that covers
  a protected route, `isPublic` still returns false and `warnOnProtectedOverlap`
  logs the misconfiguration. Public-path handling is shared with edge auth — see
  the [API Gateway investigation](09_API_Gateway.md#3-authentication).

## 3. Origin lockdown — proving a request came from *our* distribution

The ALB security group is pinned to CloudFront's origin-facing managed prefix list,
but that list covers **every** AWS account's distributions — so it alone does not
prove a request came from *our* distribution.
[`GatewayOriginSecret`](../../src/main/java/com/recsys/application/gateway/GatewayOriginSecret.java)
closes that gap: CloudFront injects a custom `x-origin-secret` header on every
origin request, and the gateway rejects anything missing or mismatched with `403`
"direct origin access is not permitted", counting it in
`gateway_origin_secret_rejected_total`.

```bash
curl http://localhost:8010/metrics | grep gateway_origin_secret_rejected_total
```

Design details that make it safe to operate:

- **Off by default** — `GATEWAY_ORIGIN_SECRET` unset/blank → a `DISABLED` singleton,
  so local dev and the test suite are unaffected. Wired in
  [`MicroserviceGatewayServer`](../../src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java)
  only when enabled, and bound from the `recsys-gateway-origin-secret` Secret
  (`optional: true`, so pods stay schedulable pre-rollout / in no-CDN deploys).
- **Comma-separated secret SET for zero-downtime rotation** — the gateway accepts
  *any* secret in the set, so rotation has no 403 window:

  ```bash
  GATEWAY_ORIGIN_SECRET="old-secret,new-secret"   # 1. pods accept either
  # ... flip the distribution's custom header to new-secret ...
  GATEWAY_ORIGIN_SECRET="new-secret"              # 2. old retired
  ```

- **Constant-time compare** — validation uses `MessageDigest.isEqual` and
  deliberately does **not** early-exit on a match (accumulates with `matched |=`),
  so neither the secret value nor the set size/position leaks via timing.
- **`/health` + `/metrics` exempt** — boundary-matched (`/healthcheck` does *not*
  inherit `/health`'s exemption), since the ALB health check, kubelet probes, and
  the Prometheus scrape reach the pod directly and can't carry the secret. The
  first rejection logs one WARN; subsequent ones are counted, not logged, to avoid
  flooding under a scan or a botched rotation.
- **Encrypted hop** — `create-cdn-distribution.sh` defaults `ORIGIN_PROTOCOL_POLICY`
  to `https-only`, which encrypts the secret across the CloudFront→ALB hop
  (requires an ALB `:443` listener and a regional ACM cert). `http-only` remains a
  loudly-warned opt-out — under it the secret crosses the hop in cleartext and is
  replayable if observed.

## 4. Provisioning the distribution

[`scripts/create-cdn-distribution.sh`](../../scripts/create-cdn-distribution.sh) is an
**idempotent create-or-update** keyed on `Comment="recsys-edge"`: re-running finds
the existing distribution and issues `update-distribution --if-match <etag>`
instead of creating a duplicate. Required env vars all fail-fast:

| Env var | Purpose / validation |
|---|---|
| `ORIGIN_DOMAIN` | Route53 failover hostname (not the ALB directly) |
| `ALIAS_DOMAIN` | Public hostname (CloudFront alias) |
| `ACM_CERT_ARN` | Viewer TLS cert — **hard-validated to be in us-east-1**, else exit 1 |
| `WEB_ACL_ARN` | Must be scope=`CLOUDFRONT` (global) |
| `ORIGIN_SECRET` | Injected as the `x-origin-secret` custom origin header; must equal `GATEWAY_ORIGIN_SECRET` |
| `ORIGIN_PROTOCOL_POLICY` | `https-only` (default) or `http-only` (warned opt-out) |

Fixed settings: `HttpVersion: http2and3`, `PriceClass_All`, origin
`OriginReadTimeout: 30` / `OriginKeepaliveTimeout: 5`, `OriginSslProtocols:
[TLSv1.2]`. The two custom cache policies (`recsys-item`, `recsys-similar`) are
themselves created idempotently by name. Companion scripts: `invalidate-cdn.sh`
(bulk wildcard invalidation, respects the 1,000-free-path quota) and
`invalidate-local-cdn.sh` (local stand-in purge).

The full rollout is out-of-band and **order-sensitive** — validate on the raw
`*.cloudfront.net` name → flip DNS → create the origin Secret → narrow the ALB SG
to the prefix list → retire the REGIONAL WebACL (reversing the last two steps locks
all traffic out of the origin):

```bash
ORIGIN_DOMAIN=origin.recsys.example.com \
ALIAS_DOMAIN=app.recsys.example.com \
ACM_CERT_ARN=arn:aws:acm:us-east-1:<acct>:certificate/<id> \
WEB_ACL_ARN=arn:aws:wafv2:us-east-1:<acct>:global/webacl/recsys-edge/<id> \
ORIGIN_SECRET="$(openssl rand -hex 32)" \
./scripts/create-cdn-distribution.sh
```

Ordered rollout, rollback, and the SG/prefix-list commands are in
[cdn-operations.md](../runbooks/cdn-operations.md) and
[cdn-rollback.md](../runbooks/cdn-rollback.md).

## 5. Local CDN stand-in

[`docker-compose.cdn.yml`](../../docker-compose.cdn.yml) runs an `nginx:1.27-alpine`
container on **`:8090`** that mirrors the three CloudFront behaviors one-for-one, so
the caching semantics can be run and observed with no AWS account.

```bash
# 1. Gateway + backends on the host (gateway listens on :8010)
sh scripts/run-microservices-local.sh
# 2. The CDN in front of it, on :8090
docker compose -f docker-compose.cdn.yml up
# 3. See it work
curl -sI 'localhost:8090/api/catalog/item?id=1' | grep -i x-cache                # MISS
curl -sI 'localhost:8090/api/catalog/item?id=1' | grep -i x-cache                # HIT
curl -sI 'localhost:8090/api/catalog/item?id=1&cachebuster=99' | grep -i x-cache # HIT — key whitelist holds
curl -sI -X POST localhost:8090/api/recommend | grep -i x-cache                  # BYPASS — default-deny holds
```

The [nginx template](../../docker/cdn/default.conf.template) reproduces the CloudFront
semantics deliberately: `location /` is a default-deny mirror
(`proxy_cache_bypass 1; proxy_no_cache 1;` → `X-Cache: BYPASS`); the two catalog
locations set `proxy_cache_key "$uri|$arg_id"` and `"$uri|$arg_movieId|$arg_k"`
(only the whitelisted params) and deliberately omit `proxy_cache_valid` so lifetime
comes only from the origin's `s-maxage`; every location injects the
`x-origin-secret` header (`CDN_ORIGIN_SECRET` must match `GATEWAY_ORIGIN_SECRET` or
every request 403s). Purge with `./scripts/invalidate-local-cdn.sh` (whole cache —
nginx OSS has no path-scoped invalidation).

It is a **semantics harness, not a CloudFront emulator**: caching behavior only —
no WAF, no Shield, no edge TLS, no geographic distribution (one container, not 400+
POPs), and coarser whole-cache invalidation. See
[cdn-local.md](../runbooks/cdn-local.md).

## 6. Testing the edge

- **Origin secret** — `GatewayOriginSecretTest` (14 cases: disabled when unset,
  allow/reject matching/wrong/missing, `/health`+`/metrics` boundary exemption,
  rotation-set accept-either, CSV trimming) and `GatewayOriginSecretMetricsTest`
  (the `gateway_origin_secret_rejected_total` counter; null-registry support).
- **Public paths** — `GatewayAuthenticatorTest` proves the production set allows the
  two catalog reads but rejects `/api/catalog/user` and the bare prefix, and that
  protected prefixes stay auth-required even if explicitly listed public.
- **Cache behavior** — `LocalCdnCacheTest` (Testcontainers nginx on 8090):
  `s-maxage` alone caches (miss→hit), the cache-key whitelist means a cachebuster
  can't fragment the cache, default behavior never caches (BYPASS×2),
  `If-None-Match` 304 passthrough, the origin secret is injected on every forwarded
  request, and a different `similar?k=` is a fresh miss.

## Sharp edges — notes

1. **Caching is narrow on purpose.** Only two GET routes are cacheable; the
   dominant `POST /api/recommend` is uncacheable by design. The CDN's payoff on the
   hot path is TLS/WAF/backbone, not hit ratio.
2. **Public = world-readable, and prefix mistakes are dangerous.** `item`/`similar`
   are cached only because they carry no private data and don't vary on
   `Authorization`; listing the `/api/catalog` *prefix* instead of the exact paths
   would expose `/api/catalog/user`. The never-public guard is a backstop, not a
   license to be sloppy.
3. **The origin secret is the real lock; the prefix list isn't enough.** The
   managed prefix list admits every AWS account's CloudFront, so `x-origin-secret`
   is what authenticates *our* edge — and under the `http-only` opt-out it crosses
   the hop in cleartext.
4. **Rollout order is one-way-dangerous.** Narrowing the ALB SG before DNS/edge are
   verified locks traffic out of the origin; follow the runbook order and use
   `cdn-rollback.md` to back out.
5. **Everything is out-of-band.** No IaC — the distribution, WebACL, prefix-list
   pinning, and DNS are script/console-managed, so drift is possible and the
   runbooks are the source of truth.
