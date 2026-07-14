# CDN Edge Acceleration Design

Date: 2026-07-14
Status: Proposed

## Summary

Put a CloudFront distribution in front of the API gateway to terminate TLS at the
edge, drop attack traffic before it reaches the region, accelerate the
uncacheable-but-dominant POST path over the AWS backbone, and cache the two
genuinely shared read routes (`/api/catalog/item`, `/api/catalog/similar`).

The distribution is created out-of-band by an idempotent script plus a runbook,
matching how the WAF WebACL and Route53 records are already managed
(`docs/runbooks/waf-webacl.md`). No IaC toolchain is introduced.

## Motivation, stated honestly

This system's primary route is `POST /api/recommend`
(`application/gateway/RecommendationGatewayService.java`) — POST-only and
personalized per `userId`. **CloudFront will forward 100% of those to the origin.
The cache hit ratio on the primary route will be zero, by design.**

No service in this repo serves static content over HTTP. There is no HTML, JS,
CSS, or image surface; `dssm_model.onnx` and the embedding text files are
classpath-only and never HTTP-reachable. Before this design, `src/main` contained
zero `Cache-Control`, `ETag`, `Last-Modified`, or `Vary` headers.

A CDN is still worth having here, but the value must be ordered correctly:

1. **Transport acceleration — applies to all traffic, including uncacheable
   POSTs.** The viewer TLS handshake terminates at a nearby POP rather than
   crossing an ocean to us-east-1. For a viewer in Singapore that collapses a
   ~3-RTT handshake from roughly 240 ms to roughly 10 ms. The POP-to-origin leg
   reuses warm connections over the AWS backbone instead of the public internet.
   This is the largest available latency win and it is independent of caching.
2. **Security edge.** The WebACL moves to `CLOUDFRONT` scope, so attack traffic is
   dropped at the POP and consumes no origin bandwidth or ALB capacity. Shield
   Standard is included. The ALB stops being directly reachable.
3. **TLS.** The front door is plaintext HTTP:80 with no certificate today
   (`k8s/eks/waf-api-gateway-ingress.yaml` listens on `[{"HTTP":80}]`). ACM plus
   CloudFront closes the gap that
   `docs/superpowers/specs/2026-07-02-gateway-waf-ingress-design.md` listed as
   future work, without rewriting the ALB listener.
4. **Caching — narrow but real.** Two routes only. This is where cache
   population, freshness, and bandwidth optimization legitimately apply, and it
   is a minority of the surface.

## Goals

- Terminate viewer TLS at the edge with an ACM certificate.
- Move WAF enforcement to `CLOUDFRONT` scope; retire the `REGIONAL` WebACL.
- Make the ALB unreachable except via this distribution.
- Cache `/api/catalog/item` and `/api/catalog/similar` at the edge with explicit
  freshness controls.
- Add `Cache-Control` / `ETag` / `304` support to those two origin routes.
- Preserve the existing active-passive DR behaviour unchanged.

## Non-goals

- Caching any personalized or POST route.
- Active-active or latency-based geo-serving. This remains a non-goal of
  `docs/superpowers/specs/2026-07-08-multi-region-dr-failover-design.md`.
- Origin Shield. A mid-tier cache only pays off with meaningful cacheable volume;
  two catalog routes do not justify the cost or the extra hop.
- Lambda@Edge or CloudFront Functions. Nothing in this design needs edge compute.
- Introducing Terraform or CloudFormation. See "Codification" below.
- Serving static assets. There are none.

## Architecture

```
Viewer ──HTTPS (ACM, us-east-1)──▶ CloudFront POP
                                     │  WebACL scope=CLOUDFRONT, Shield Standard
                     ┌───────────────┴───────────────┐
                     │ cache: /api/catalog/item*     │──HIT──▶ served at edge
                     │        /api/catalog/similar*  │
                     └───────────────┬───────────────┘
                                     │ MISS + ALL POSTs
                                     │ AWS backbone, keep-alive
                                     │ + X-Origin-Secret custom header
                                     ▼
                       ALB :80   SG allows ONLY
                                 com.amazonaws.global.cloudfront.origin-facing
                                     ▼
                            gateway :8010 ──▶ 6010 / 7010 / 8080
```

### Origin and failover

CloudFront uses a single origin pointing at the existing Route53 failover
hostname. **CloudFront Origin Groups were considered and rejected**: the system
already has a failover mechanism, and two independent failover brains can
disagree, producing split-brain behaviour and invalidating every DR runbook. One
mechanism, one runbook.

The cost of this choice is that origin failover speed remains bounded by the 30 s
DNS TTL rather than being per-request. That is accepted.

DNS restructures as follows. The failover logic is unchanged; it moves behind a
new name.

```
app.recsys.example.com     ──alias────▶ CloudFront distribution   (new)
origin.recsys.example.com  ──failover─▶ PRIMARY   us-east-1 ALB   (was app.*)
                                     └▶ SECONDARY us-west-2 ALB
                                        Route53 health check on /health
```

`dr-regional-failover.md`, `dr-failback.md`, and `dr-game-day.md` need hostname
updates but no logic changes. RTO and RPO characteristics are unchanged.

## Cacheable surface

The gateway rewrite is a pure prefix-strip with no rewrite field
(`MicroserviceRoute.rewrite`, `MicroserviceRoute.java:75-87`). This makes several
otherwise-cacheable routes reachable only at awkward paths — `/api/v1/model/versions`
is only reachable as `/api/model/api/v1/model/versions`. Those paths are not worth
enshrining in a cache behavior, and knowledge-bases is mutable via POST/DELETE/PATCH
in any case. Both are excluded.

`GET /getuser` / `/user` returns user data and is never cached.

### Cache behaviors (ordered, first match wins)

| Path pattern | Policy | Edge TTL | Cache key |
|---|---|---|---|
| `/api/catalog/item*` | Cache | `s-maxage=3600`, SWR 86400, SIE 86400 | `id` |
| `/api/catalog/similar*` | Cache | `s-maxage=300`, SWR 3600, SIE 3600 | `movieId`, `k` |
| `/health` | CachingDisabled | — | — |
| `*` (default) | **CachingDisabled** | — | — |

(SWR = `stale-while-revalidate`, SIE = `stale-if-error`; see "Freshness" below. CloudFront has
supported both directives since May 2023.)

**Default-deny is load-bearing.** Every POST, every personalized route, and any
route added in future is uncacheable unless someone deliberately opts it in. This
prevents accidental edge caching of `/api/recommend` or `/api/catalog/user`.

Cache keys whitelist only the listed query parameters. Forwarding all query
strings would let `?id=1&cachebuster=<n>` fragment the cache arbitrarily and act
as an origin-DoS amplifier; the whitelist makes that impossible.

Cookies are not forwarded and are not part of any cache key. The system sets and
reads no cookies anywhere.

### Auth and the public-path trap

For the edge to cache these routes at a useful hit ratio, they must not vary on
`Authorization`. With Cognito JWTs in use, an auth-keyed cache fragments per user
and the hit ratio collapses to zero. Therefore the two cached routes are marked
public via `GATEWAY_PUBLIC_PATHS`, and CloudFront does not forward `Authorization`
or `x-api-key` on those two behaviors.

This is an explicit trust decision: **movie catalog metadata and item-to-item
similarity are treated as non-sensitive and world-readable.** Everything else
keeps its current auth posture.

**The value MUST be the two exact paths, never the prefix.** Public-path matching
is prefix-with-boundary (`GatewayAuthenticator.java:114-117`), so
`GATEWAY_PUBLIC_PATHS=/api/catalog` would also expose
`/api/catalog/user?userId=1`, which returns user data. The correct value is:

```
GATEWAY_PUBLIC_PATHS=/health,/api/catalog/item,/api/catalog/similar
```

Note that gateway auth is fail-open: when neither `GATEWAY_API_KEYS` nor Cognito
is configured, every path is anonymous-allowed (`GatewayAuthenticator.java:54-56`).
This design does not change that, but the origin-secret check below is enforced
independently of it.

## Origin changes

Small, and independent of any CDN vendor.

- `CatalogService` `/item`: add
  `Cache-Control: public, s-maxage=3600, stale-while-revalidate=86400, stale-if-error=86400`
  and a strong `ETag` over the serialized response body.
- `RecommendationService` `/similar`: add
  `Cache-Control: public, s-maxage=300, stale-while-revalidate=3600, stale-if-error=3600`
  and a strong `ETag`.
- Both: honour `If-None-Match` and return `304 Not Modified` on match. This is
  what delivers the bandwidth-optimization goal — revalidations become ~200-byte
  304s rather than full payloads, and CloudFront revalidates instead of refetching.
- Add explicit `Cache-Control: no-store` to `/api/v1/token`, `/getuser` / `/user`,
  and the auth routes, so that even a misconfigured edge cannot retain them.
- Gateway: validate a secret origin header when `GATEWAY_ORIGIN_SECRET` is set;
  reject mismatches with 403. **Defaults to disabled when the env var is unset**,
  so `scripts/run-microservices-local.sh` and local dev are unaffected. `/health`
  and `/metrics` are exempt from this check — see "Risks" below for what that
  costs.

**Accepted limitation: the origin secret rides in cleartext.** The origin is
`http-only` (no regional TLS cert on the ALB), so `x-origin-secret` travels the
POP-to-origin hop unencrypted — observable on-path and replayable once captured,
which partially undercuts the origin-lockdown control the header exists to
provide. This was weighed against standing up a second, regional ACM certificate
(with its own renewal lifecycle) on the ALB, and the user chose to accept the
cleartext exposure rather than take that on. Revisit if/when ACM-on-ALB happens
for another reason — `scripts/create-cdn-distribution.sh` already sets
`HTTPSPort: 443` and `OriginSslProtocols: ["TLSv1.2"]` on the custom origin
config; both are inert under `http-only` today and exist as the hook for
switching later. Details in `docs/runbooks/cdn-operations.md`.

## Freshness

Three layered mechanisms.

1. **`stale-while-revalidate` and `stale-if-error`** split hit ratio from
   availability, and both are needed. `stale-while-revalidate` covers the
   background-refresh case: once the object passes `s-maxage`, the edge serves
   the stale copy immediately while it revalidates against a *healthy* origin in
   the background. It says nothing about an unhealthy origin. `stale-if-error` is
   the directive that actually does that: it lets the edge keep serving the
   cached object when the origin is unreachable or returns a 5xx, for the same
   window. Both are emitted with the same value by `HttpCaching.publicCache` so
   the two windows never drift apart. This mirrors the idiom already used
   internally by `LogicalExpiryEmbeddingCache` on port 7010 — the edge applies
   the repo's existing serve-stale pattern one layer further out.
2. **`ETag` / `304`** bounds how stale an object can be, cheaply.
3. **Explicit invalidation** via `scripts/invalidate-cdn.sh` for the
   correctness-critical case. `POST /setembedding` mutates the vectors behind
   `/similar`, so a bulk embedding refresh must invalidate `/api/catalog/similar*`.

Invalidation is **operator-triggered, not wired into the write path**. Per-write
invalidation during a bulk embedding load would issue thousands of API calls and
exhaust CloudFront's 1,000-free-invalidation-path quota. The runbook rule is: one
bulk reload, one wildcard invalidation.

## Availability

`stale-if-error` means a total origin outage still serves cached `/item` for up
to 24 h and `/similar` for up to 1 h.

CloudFront bounds served-stale duration to the *lesser* of the directive value
and the cache policy's `MaxTTL` (`scripts/create-cdn-distribution.sh`:
`recsys-item` MaxTTL 86400, `recsys-similar` MaxTTL 3600). Today those MaxTTLs
exactly equal the `stale-if-error` windows above, so nothing is truncated — but
this is a coupling to watch: if either MaxTTL is ever lowered independently of
`HttpCaching.publicCache`'s `staleSeconds` argument, the effective outage
tolerance silently shrinks to the new, lower MaxTTL without any code or test
signaling it.

This is a real but **narrow** benefit. `/api/recommend` — the primary route — still
hard-fails during an origin outage. The CDN is not an availability blanket for
this system, and should not be described as one.

## Scalability and load reduction

- TLS handshake termination moves off the origin entirely; the ALB sees a small
  number of warm, reused backbone connections instead of per-viewer handshakes.
- WAF-blocked traffic never reaches the region, so it consumes no ALB capacity and
  no origin egress.
- Cached catalog reads bypass the 6010 admission-control path
  (`CATALOG_MAX_CONCURRENT_REQUESTS`) and its Redis lookups entirely on a hit.

These compose with, and do not replace, the existing overload-protection layers in
`docs/runbooks/overload-protection.md`.

## Codification

An idempotent AWS CLI script plus runbooks, matching the existing convention for
the WAF WebACL and Route53 records. The repo contains no IaC today
(`find . -name "*.tf"` is empty) and this design does not introduce any.

```
scripts/create-cdn-distribution.sh   idempotent create/update
scripts/invalidate-cdn.sh            freshness
docs/runbooks/cdn-operations.md      deploy, validate, invalidate
docs/runbooks/cdn-rollback.md        revert
```

The accepted tradeoff is no state file and no drift detection. This is already the
repo's accepted position for the ALB, WebACL, and Route53 records; this design does
not make it worse, and does not attempt to fix it either. Migrating all edge infra
to IaC is a reasonable follow-up project, deliberately out of scope here.

## Rollout

Order matters; steps 5 and 6 are reversed at the risk of locking yourself out.

1. Ship origin code — cache headers, ETag/304, origin-secret validation
   **disabled**. A safe no-op.
2. Create the ACM certificate (us-east-1), the `CLOUDFRONT`-scope WebACL, and the
   distribution with origin `origin.*`.
3. Validate against the raw `dXXXXXXXXXXXXX.cloudfront.net` domain. Real traffic is
   still on the old path.
4. Set `GATEWAY_ORIGIN_SECRET`; configure CloudFront to send the matching custom
   header. The gateway now enforces it.
5. Flip `app.*` to the CloudFront alias.
6. **Only now** narrow the ALB security group to the CloudFront origin-facing
   prefix list, and retire the `REGIONAL` WebACL.

Steps 1-4 are invisible to users. Rollback at any point is a DNS revert plus a
security-group revert.

### Interaction with the existing WAF design

`docs/superpowers/specs/2026-07-02-gateway-waf-ingress-design.md:103` and
`docs/runbooks/waf-webacl.md:14` state the WebACL scope MUST be `REGIONAL`, not
`CLOUDFRONT`. That was correct while the ALB was the front door. This design
supersedes it: once CloudFront is the front door, a `CLOUDFRONT`-scope WebACL is
required, and the `REGIONAL` one is retired at step 6. Both documents must be
updated to reference this design rather than being left to contradict it.

Keeping both WebACLs was considered and rejected: it doubles cost and invites rule
drift between two sets that must stay in sync, and the origin lockdown already
prevents the bypass that a second WebACL would defend against.

## Testing

There is no CI in this repo, and `@Tag("docker")` tests run locally only. All tests
below are plain unit tests requiring no AWS account.

- `/item` returns the expected `Cache-Control` and a non-blank `ETag`.
- `/similar` returns the expected `Cache-Control` and a non-blank `ETag`.
- ETag is stable across two identical requests and differs when the body differs.
- `If-None-Match` with a matching ETag returns 304 and an empty body.
- `If-None-Match` with a stale ETag returns 200 and the full body.
- `/api/v1/token` and `/getuser` return `Cache-Control: no-store`.
- Gateway rejects a request with a missing or wrong origin secret with 403 when
  `GATEWAY_ORIGIN_SECRET` is set.
- Gateway allows the request when `GATEWAY_ORIGIN_SECRET` is unset (local-dev
  default).

The CloudFront configuration itself cannot be unit-tested. It is validated by the
curl checks in `docs/runbooks/cdn-operations.md` at rollout step 3, which assert
`X-Cache: Hit from cloudfront` on a repeated `/api/catalog/item?id=1` and
`X-Cache: Miss from cloudfront` on `POST /api/recommend`.

## Risks

| Risk | Mitigation |
|---|---|
| `GATEWAY_PUBLIC_PATHS` set to `/api/catalog` exposes user data | Exact paths only; asserted by test and called out in the runbook |
| Locking the SG before DNS flip cuts off all traffic | Rollout order; documented in the runbook |
| Cache serves stale `/similar` after an embedding reload | Operator invalidation step in the reload runbook; 300 s TTL bounds exposure |
| Someone opts a personalized route into a cache behavior | Default-deny; opt-in requires an explicit new behavior |
| Another AWS account's CloudFront reaches the origin via the shared prefix list | Secret origin header validated at the gateway |
| Origin is `http-only`, so `x-origin-secret` crosses the POP-to-origin hop in cleartext and is replayable if observed | **Accepted.** Avoids a second regional ACM cert/renewal lifecycle; revisit if ACM-on-ALB happens for another reason. The `HTTPSPort`/`OriginSslProtocols` fields already in `scripts/create-cdn-distribution.sh` are the hook for switching later |
| `/health` and `/metrics` must be exempt from the origin-secret check (probes/scrapes reach the pod directly, with no secret), so they stay reachable by any AWS account's CloudFront distribution once the SG opens to the shared prefix list; `/health` discloses per-route circuit-breaker state, upstream reachability, and registry topology | Not a new exposure — the ALB is already internet-facing today. If tighter isolation is wanted later: stop routing `/health`/`/metrics` through the distribution, or move them to a separate management port |
