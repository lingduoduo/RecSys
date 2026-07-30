# CDN Cache-Key and Edge-Config Hardening Design

Date: 2026-07-29
Status: Proposed

## Summary

A gap-hunt over the CDN read path found that the query-string whitelist — the
mechanism `docs/superpowers/specs/2026-07-14-cdn-edge-acceleration-design.md`
introduced specifically to stop cache-key fragmentation — is defeated by a
parameter that is itself on the whitelist. Three smaller edge-config defects came
out of the same pass: the four cache behaviors match a wider path set than any
other layer, the cache policies are frozen after their first creation, and the
one metric that reports whether the CDN earns anything is not enabled.

Scope: the origin's parameter handling, `scripts/create-cdn-distribution.sh`, the
local nginx harness, and the claims in `docs/system_design/12_CDNS.md` /
`docs/runbooks/cdn-operations.md`. No new AWS resources, no IaC toolchain, no
change to what is cached or for how long.

## Findings

Each was verified against code and against the AWS reference; the two claims that
turned out to be non-issues are recorded so the next pass does not re-derive them.

### F1 — `k` is a whitelisted cache-buster, and numeric spellings alias

`ensure_cache_policy` whitelists `movieId` and `k` for `/similar` and `id` for
`/item`, with this rationale in the script:

> Cache keys whitelist ONLY the meaningful params. Forwarding all query strings
> would let `?id=1&cachebuster=N` fragment the cache arbitrarily and act as an
> origin-DoS amplifier.

The origin clamps instead of rejecting. `BaseApiService.optionalIntParam` returns
`Math.min(parsed, max)` above the range and `defaultValue` below it, and
`RecommendationService.Similar` calls it as `optionalIntParam(ctx, "k", 10, 1, 200)`.
So every one of `k=201, 202, … 2147483647` is a distinct CloudFront cache key
whose response body is byte-identical to `k=200`, and every `k≤0` is a distinct
key identical to `k=10`. The whitelist bounds *which* parameters can fragment the
cache; it does not bound the values, and `k` is unbounded.

Three further aliasing channels apply to `id`, `movieId`, and `k` alike, because
`requiredIntParam`/`optionalIntParam` both end in `Integer.parseInt(value.trim())`:

- **Leading zeros** — `id=7`, `id=07`, `id=007`, … all parse to `7`. Unbounded.
- **Explicit plus and whitespace** — `+7` and `%207` parse to `7`.
- **Repeated parameters** — `?id=1&id=<n>`: CloudFront's cache key includes the
  whole whitelisted set, while `ctx.queryParam("id")` reads only the first
  occurrence. So `?id=1&id=<anything>` returns `id=1`'s body under an unbounded
  family of keys. This channel is independent of value canonicalization and has to
  be closed separately.

Impact is origin CPU, not edge storage. A miss on `/api/catalog/similar` runs
`selectCandidates` for `k × 5` candidates, a bulk embedding fetch, and an exact
cosine scan — up to 1000 candidates at `k=200`. It is the only route in the system
that is simultaneously public (`GATEWAY_PUBLIC_PATHS`), unauthenticated (the
cached behaviors set `HeaderBehavior: none`, so `Authorization` never reaches the
origin), and compute-heavy. The two backstops one would reach for are not
available: gateway rate limiting defaults to off (`GATEWAY_RATE_LIMIT_RPS`
defaults to `0.0`, and `fromEnvironment` returns `disabled()` when no route has a
limit), and if it were enabled it would key every edge-forwarded request to the
single shared `anonymous` principal, because the cached behaviors strip the
credential that would distinguish callers.

The codebase already contains the correctly-shaped helper. `optionalBoundedIntParam`
is documented as *"Parses an optional bounded integer and rejects, rather than
clamps, invalid values"* — the cacheable route simply does not use it.

### F2 — the path patterns are globs; every other layer is exact

The four behaviors use `PathPattern: "/api/catalog/item*"` and
`"/api/catalog/similar*"` (plus versioned twins). The AWS reference is explicit
that the wildcard buys nothing for query strings:

> CloudFront does not consider query strings or cookies when evaluating the path
> pattern.

So `PathPattern: "/api/catalog/item"` already matches `GET /api/catalog/item?id=1`.
The `*` only widens the match, and it widens toward the hazard: on any
glob-matched path the edge drops `Authorization`, while the gateway's
`GATEWAY_PUBLIC_PATHS` check is exact (`matchesPrefix` requires `equals(prefix)`
or `startsWith(prefix + "/")`). A future `/api/catalog/similar-users` would
inherit a cached, credential-stripped behavior without anyone choosing that. The
same AWS page warns about precisely this class of mistake: *"Define path patterns
and their sequence carefully or you may give users undesired access to your
content."*

The over-match is reachable today, not only latent. `/api/catalog` is a *prefix*
route in `MicroserviceRoute`, and `rewrite` boundary-matches it, so
`GET /api/catalog/itemX?id=<n>` forwards to `http://…:6010/itemX`, where no route
exists. What comes back is Armeria's framework 404 — which, unlike every 404 the
origin writes itself, carries no `Cache-Control`, and a 404 is on CloudFront's
unconditionally-cached list. So under the glob that response is edge-cached for
10 s per key, unbounded across `<n>`. Whether a caller reaches it depends on
gateway auth: with `GATEWAY_ALLOW_ANONYMOUS=true` (`k8s/base`, local) it lands as
described; with auth enabled (both EKS overlays) the gateway returns a 401 first,
which is not edge-cacheable. Narrowing the pattern removes the case regardless of
which auth posture is deployed.

The local harness does not reproduce the glob — `docker/cdn/default.conf.template`
uses `location = /api/catalog/item`, an exact match. So `12_CDNS.md` §5's claim
that the template "mirrors the five CloudFront behaviors one-for-one" is untrue on
this axis, and the harness structurally cannot exercise the over-match. Narrowing
CloudFront is what makes the claim true, rather than widening nginx.

### F3 — the cache policies are created once and then frozen

`ensure_cache_policy` looks a policy up by name, returns the existing id if found,
and otherwise creates it. There is no `update-cache-policy` call anywhere. The
TTLs and the query-string whitelist live in the *policy*, not in the distribution
config, so **editing either in the script is a silent no-op from the second run
onward** — no error, no warning, no diff.

`docs/runbooks/cdn-operations.md` states the opposite as doctrine:

> **The `create-cdn-distribution.sh` payload is the sole source of truth for the
> distribution config.** `aws cloudfront update-distribution` REPLACES the entire
> configuration — any field not present in the payload is silently reset to its
> default.

That is accurate for the distribution, which is genuinely create-or-update with
`--if-match`. It is false for the cache policies in both directions: a script edit
never applies, and a console edit is never reverted. The runbook's model tells an
operator that the risk is the script clobbering the console, when for the cache key
and TTLs the risk is the exact inverse.

This compounds a coupling nothing in the repo records. AWS caps stale serving at
`MaxTTL`:

> CloudFront will serve the stale content up to the value of the
> `stale-if-error` directive or the value of the CloudFront maximum TTL, whichever
> is less. After the maximum TTL duration, the stale object won't be available
> from the edge cache, regardless of the `stale-if-error` value.

Today `MaxTTL` equals each stale window by coincidence — `recsys-item` is
`MaxTTL 86400` against `stale-*=86400`, `recsys-similar` is `MaxTTL 3600` against
`stale-*=3600` — so the documented 24 h / 1 h outage tolerance works out. But a
future change to `HttpCaching.publicCache`'s stale window has no effect unless
`MaxTTL` moves with it, and per this finding `MaxTTL` cannot be moved by the
script at all.

### F4 — `CacheHitRate` is an additional metric that nothing enables

`cdn-operations.md`'s "Monitoring" section documents hit-ratio checking as a
`get-metric-statistics` call on `AWS/CloudFront` / `CacheHitRate`. That metric is
not a default metric. Per the AWS reference, the default set is Requests, Bytes
downloaded, Bytes uploaded, 4xx/5xx/Total error rate; **Cache hit rate, Origin
latency, and Error rate by status code require additional metrics to be turned on
per distribution** via `create-monitoring-subscription`. Nothing in the repo
enables it and no runbook mentions it, so the documented command returns
`Datapoints: []` and exit status 0 — a silent empty answer for the one number that
says whether the cache is earning anything, and the only signal that would show F1
being exercised.

The monitoring subscription is a separate API from the distribution config, so
unlike `Logging` it is not at risk from the `update-distribution` replace
semantics: adding it to the script is safe and survives re-runs.

### F5 — the error-caching model in the comments is inverted

Three sites justify `Cache-Control: no-store` on error branches by citing a 400 or
a 403 being pinned by CloudFront's 10 s Error Caching Minimum TTL
(`CatalogService.Movies`, `RecommendationService.Similar`,
`GatewayProxyService.gatewayError`). The AWS reference splits the status codes:

- Cached **unconditionally**: 404, 414, 500, 501, 502, 503, 504.
- Cached **only if the origin returns `Cache-Control: max-age` or `s-maxage`**:
  400, 403, 405, 412, 415.
- Not cached at all under the `CachingDisabled` managed policy — *"If you're using
  the CachingDisabled managed cache policy, CloudFront won't cache these status
  codes or custom error pages."*

So the `no-store` on the 400 and 403 branches is defensive, not load-bearing (those
responses carry no `max-age`, so they were never cacheable), while the genuinely
load-bearing ones are the 404s — `CatalogService.Movies`' "movie may be added
later" and `Similar`'s "embedding not found" — and the gateway's 502/503. The
comments get the important case backwards, which is the direction that matters: a
future reader "simplifying" a 404 branch is the failure this documentation is
supposed to prevent.

`MinTTL: 0` is load-bearing for all of it and equally unrecorded — above zero,
*"CloudFront uses the cache policy's minimum TTL, even if the `Cache-Control:
no-cache`, `no-store`, and/or `private` directives are present in the origin
headers."* Every `no-store` in the read path depends on both policies keeping
`MinTTL: 0`.

### Checked and not defects

- **The 429 and 401 omit `Cache-Control`.** `GatewayRequestForwarder`'s rate-limit
  429 and `GatewayAuthenticator`'s 401 build headers by hand and skip
  `Cache-Control`, unlike every other gateway error path. CloudFront caches
  neither status (neither appears in either list in F5), and neither is
  heuristically cacheable under RFC 9111, so there is no edge-pinning bug. Left
  unchanged deliberately; recorded here so the asymmetry does not read as an
  oversight next time.
- **`OnlineAdmissionControl` sheds `/similar` with a 429**, which for the same
  reason is not edge-cacheable. The shed path needs no cache header.
- **Origin Shield remains out.** The 2026-07-14 spec rejected it on the grounds
  that a mid-tier cache only pays off with meaningful cacheable volume. Nothing in
  this pass changes the volume argument.
- **`stale-while-revalidate` / `stale-if-error` are genuinely honored** by
  CloudFront. The availability story in `12_CDNS.md` rests entirely on this, so it
  was verified rather than assumed.

## Goals

- Make the set of cache keys reaching the origin exactly the set of canonical,
  in-range parameter values — one key per distinct response body.
- Make the edge's path scope no wider than the gateway's public-path scope.
- Make a cache-policy change in the script either apply or fail loudly.
- Make the hit ratio observable by default.
- Correct the error-caching and `MinTTL` claims in the code comments and docs.

## Non-goals

- Changing what is cached, or the TTLs and stale windows themselves.
- Widening the cached surface, or making `POST /api/recommend` cacheable.
- Origin Shield, real-time logs, access logging, custom error responses.
- Enabling gateway rate limiting, or making it CloudFront-aware. Both are real
  gaps for high read traffic, both are out of scope here: the shared-`anonymous`
  keying under a credential-stripping edge is a rate-limiting design question, not
  a cache-key one.

## Design

### PR1 — canonicalize the cache-key surface at the origin

Add one helper to `BaseApiService`, used only by the two cacheable routes:

```java
/**
 * Parses a query parameter that is part of a CDN cache key. Accepts exactly one
 * canonical decimal spelling per value and rejects everything else, so the set of
 * edge cache keys is the set of distinct responses. See
 * docs/superpowers/specs/2026-07-29-cdn-cache-key-and-edge-config-hardening-design.md.
 */
protected static int cacheKeyIntParam(ServiceRequestContext ctx, String name,
                                      Integer defaultValue, int min, int max)
```

Rules, all of which reject with `BadRequestException` (surfaced as a `no-store`
400 by the existing `writeNoStoreError` branches):

1. **At most one occurrence.** `ctx.queryParams(name).size() > 1` → reject. Closes
   the repeated-parameter channel, which value canonicalization cannot.
2. **Canonical spelling only.** The raw value must match `^(0|-?[1-9][0-9]*)$`.
   This rejects `007`, `+7`, `" 7"`, `7 `, and `-0`, while still accepting `-5`
   so a negative id keeps its current 404 rather than changing status.
3. **In range, not clamped.** Outside `[min, max]` → reject.
4. `defaultValue == null` means required; absent or blank → reject.

Call sites: `CatalogService.Movies` (`id`), `RecommendationService.Similar`
(`movieId`, `k`). `RecommendationService.V1`'s `k` and every non-cacheable route
keep `optionalIntParam` — clamping is harmless where no cache key is derived from
the value, and narrowing the change to the cached routes keeps the compatibility
cost as small as the fix allows.

**Compatibility.** This is a breaking change by the contract in
`09_API_Gateway.md` § "The compatibility contract", which lists *"tightening
validation on an existing request field"* and *"changing the status code returned
for an unchanged condition"* as breaking and requiring a new version with twelve
months' notice. Two ways to land it, and the reviewer should choose explicitly:

- **As specified (chosen):** ship the 400 now as a consciously-reviewed exception
  on abuse-mitigation grounds, and add a row to the contract recording it. The
  contract's own removal rule already establishes that these decisions are made by
  explicit reviewed PR rather than by automation, so an exception is expressible —
  it is just not free.
- **Staged fallback if the exception is refused:** serve the clamped response with
  `Cache-Control: no-store` when the raw parameter is non-canonical or
  out-of-range. That creates no cache entries, so the fragmentation half is closed
  immediately with no client-visible change; the 400 then ships after notice. The
  cost is that origin CPU stays exposed, since every bogus request still runs the
  full candidate scan.

### PR2 — narrow the edge, and unfreeze the cache policies

- Drop the trailing `*` from all four `PathPattern`s, with a comment recording why
  the wildcard is unnecessary (path patterns ignore query strings) and why it is
  harmful (it is wider than `GATEWAY_PUBLIC_PATHS`).
- Make `ensure_cache_policy` compare the existing policy's config against the
  desired config and either `update-cache-policy --if-match` or fail with a
  diagnostic naming the drifted field. Failing loudly is acceptable and better
  than today's silence; updating is preferable.
- Record the `MaxTTL`-caps-stale coupling next to the two policy definitions, so a
  future stale-window change moves `MaxTTL` with it.
- Correct the runbook's source-of-truth paragraph to scope its claim to the
  distribution config and state the opposite behavior for cache policies.
- Add a comment to `default.conf.template` tying `location =` to the now-exact
  CloudFront patterns, so the two cannot drift apart silently.

### PR3 — enable the hit-ratio metric, and fix the error-caching model

- Add an idempotent `create-monitoring-subscription` step to the script, guarded
  by `get-monitoring-subscription`, with a comment noting it is a separate API and
  therefore not subject to the `update-distribution` replace semantics.
- Add a verification step to the runbook's Monitoring section: check the
  subscription first, since an empty `Datapoints` array is otherwise
  indistinguishable from zero traffic.
- Correct the three `no-store` comments to name the codes that are actually cached
  unconditionally, and state the `MinTTL: 0` dependency where the policies are
  defined.
- Weave all of it into `12_CDNS.md` — the corrected error-caching model, the
  cache-key canonicalization rule, the exact path patterns, and the frozen-policy
  hazard as a new recorded sharp edge.

## Testing

The PR gate runs only the `-Presilience` profile, which is an explicit include
list and excludes `@Tag("docker")`. So the merge-blocking tests must be
unit-level, and each new test class needs its own `<include>` entry.

**PR1 — merge-blocking.** Extend the existing `CatalogCacheHeadersTest` and
`SimilarCacheHeadersTest`, plus a new `BaseApiService` parameter test:

- `k=201`, `k=0`, `k=-1` → 400 with `Cache-Control: no-store` (was 200).
- `id=007`, `id=+7`, `id=%207`, `id=-0` → 400; `id=7` and `id=-5` unchanged
  (200 and 404 respectively).
- `?id=1&id=2` → 400.
- `movieId`/`k` canonical values → responses byte-identical to today, with the
  same `Cache-Control` and `ETag`, so the cache semantics are provably unchanged
  for valid callers.

**PR1 — harness-level, not merge-blocking.** A `LocalCdnCacheTest` case proving
`similar?movieId=1&k=201` no longer produces a second cache entry alongside
`k=200`. This is the test that exercises the actual fragmentation, and it needs
Testcontainers, so it documents rather than gates.

**PR2.** A `LocalCdnCacheTest` case pinning the exactness: a path under the old
glob (`/api/catalog/items?id=1`) must report `X-Cache: BYPASS`, i.e. land in the
default-deny behavior. Docker-tagged, so not merge-blocking; its value is
preventing a future widening of the nginx `location` from silently diverging from
the narrowed CloudFront config. `DocumentedMechanismTest` already gates that the
docs' source links resolve, which covers the doc edits.

**PR3.** No new automated coverage — the changes are a shell step against a live
AWS API and prose. The runbook verification step is the check.

## Risks

- **Client breakage on `k` and non-canonical ids** — the compatibility cost above.
  Bounded to the two cacheable public routes and to spellings that already
  returned something other than what was asked for.
- **`update-cache-policy` on a policy attached to a live distribution** propagates
  to the edge and changes cache behavior globally. The fail-loudly variant avoids
  the risk entirely at the cost of manual intervention; whichever ships, the
  runbook needs the propagation caveat.
- **The narrowed path patterns are a live routing change.** Requests that today
  match a glob behavior move to the default-deny behavior. Nothing legitimate is
  served under those paths — the gateway's only catalog entry is the `/api/catalog`
  prefix route, and the sole cacheable leaves beneath it are `item` and `similar`,
  both matched exactly by the narrowed patterns. What changes is that
  `/api/catalog/item<suffix>` stops being edge-cached, which is the point of the
  fix. Adding a genuinely cacheable sibling route later now requires adding a
  behavior for it, deliberately — the gateway-first deploy order in `12_CDNS.md`
  sharp edge 6 applies unchanged.
