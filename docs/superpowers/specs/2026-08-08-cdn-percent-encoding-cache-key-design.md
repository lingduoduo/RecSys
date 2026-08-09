# CDN percent-encoding cache key — design

Make one CloudFront cache key mean one response body on the cached catalog routes, by rejecting
percent-encoded query strings at the gateway.

## The gap

`cacheKeyIntParam` (`BaseApiService.java:250`) validates the **decoded** value: it reads
`ctx.queryParams(name)`, and Armeria percent-decodes both parameter names and values before that
returns. The CloudFront cache key is built from the **raw** query string — the `recsys-item` policy
whitelists `id`, the `recsys-similar` policy whitelists `movieId` and `k`, matched as literal names.

Three channels follow from that asymmetry, in increasing severity.

**1. Encoded value — key fragmentation.** `?id=%37` decodes to a valid `7` and is accepted, but is a
second cache key over the `id=7` body. On a public, unauthenticated route where every miss costs a
full candidate scan, that is the same unbounded cache-buster the `k` ceiling was added to close in
the 2026-07-29 hardening.

**2. Encoded name — collision.** `?%69d=7` decodes its parameter *name* to `id` and is likewise
accepted by the origin, but the edge whitelist matches on the raw name, so the request presents no
whitelisted parameter at all. It collides with `?%69%64=8` and with a bare parameterless request:
many bodies under one key.

**3. Encoded name for a defaulted parameter — wrong body served.** Not recorded in sharp edge 9 of
`docs/system_design/12_CDNS.md`, and worse than either above. `k` has a default, so
`GET /api/catalog/similar?movieId=1&%6b=200` is a complete, valid request whose body reflects
`k=200`, while the edge sees only `movieId=1`. `&%6b=200` and `&%6b=5` are one cache key over two
different bodies, and both collide with the plain `?movieId=1` response. It also bypasses the `k`
ceiling entirely — the parameter the ceiling bounds is not the parameter the edge is keying on.

Channel 3 is a defect in the origin's own contract: it honours a parameter the edge cannot see. That
holds regardless of whether CloudFront percent-decodes before whitelist matching — a question this
repo has never been able to answer and still cannot. The two cache policies (`recsys-item`,
`recsys-similar`) exist in the AWS account with the whitelists above, but **no distribution exists**,
so there is no live edge to measure. The local nginx stand-in resolves `$arg_id` against the raw
literal name, which remains the only evidence either way.

Recorded as sharp edge 9 in `12_CDNS.md` since 2026-07-29 as known and open. It is pre-existing:
`requiredIntParam` accepted the same spellings before `cacheKeyIntParam` was introduced.

## The guard

In `GatewayProxyService.serve`, immediately after `rejectNonCanonicalPath` and before
authentication: when the normalized path is one of the cached catalog paths and `ctx.query()`
contains a `%`, return 400.

**One `indexOf`, no query parsing.** The guard does not locate parameters, split pairs, or decode
anything, so it cannot disagree with Armeria's decoder about what a query string means. That
matters more than concision: a guard that re-implements the parser it guards inherits that parser's
blind spots. Four fail-opens survived independent reviews of `MySqlConnectionSettings` and
`MySqlTlsManifestTest` because both carried byte-identical URL parsing, and
`IrsaPermissionSourceFactsTest` initially missed the exact omission it was written to catch by
matching at the wrong grain. A rule with nothing to get subtly wrong is the point.

**Placement is by right, not convenience.** `rejectNonCanonicalPath` exists because Armeria and
Tomcat disagree about `.` segments, so every gateway control keyed on the path — routing,
`GATEWAY_PUBLIC_PATHS`, the `BackendRoutePolicy` lookup, rate-limit keying, and, in its own words,
"the CloudFront cache key" — sees a spelling the backend handler does not. This is the query-string
half of that same sentence, and it takes the same three positions: reject rather than normalize
(canonicalizing here would fix the origin while leaving the edge key on whatever the client chose),
run before authorization (it is a malformed-request rejection, not an authorization decision), and
bind service-tier callers too.

**Rejecting `%` wholesale is correct here** because the only parameters these two routes accept are
three integers. No legitimate request needs an encoded character; the family is simply not accepted.
Non-cached routes are untouched — they have no cache key to protect, and `POST /api/recommend` and
friends may carry legitimately encoded values.

The 400 is safe at the edge without further work: `gatewayError` already sets `Cache-Control:
no-store`, and its comment documents why that is load-bearing on precisely these four cached
behaviors.

## Which paths

Two, not four. `ApiVersion.parse` strips `/api/v1/...` to `/api/...` before routing, authorization,
and rate-limit keying ever see the path, so `/api/catalog/item` and `/api/catalog/similar` cover all
four public spellings. This is the same reason `GATEWAY_PUBLIC_PATHS` carries no versioned twins,
and the guard must run after that stripping for it to hold.

The list is a constant in the gateway. A conformance test pins it against the two places the edge
configuration actually lives:

- the `PathPatterns` and cache-policy query whitelists in `scripts/create-cdn-distribution.sh`
- the `location =` blocks carrying `proxy_cache_key` in `docker/cdn/default.conf.template`

A fifth cached behavior added to either without a corresponding guard entry fails the build. This
follows the derive-from-source pattern of the existing `**/k8s/*ManifestTest` conformance tests
rather than restating the list a third time.

## Testing

All non-docker, all in the `resilience` profile the PR gate runs:

- each of the three channels above rejected, on both cached paths
- all four public spellings (`/api/catalog/item`, `/api/v1/catalog/item`, and the two `similar`
  twins) reach the guard, proving the guard runs after version stripping
- a percent-encoded query accepted on a non-cached route
- the clean spellings still pass, including `?movieId=1&k=5` and a bare `?movieId=1`
- conformance: the guarded-path constant equals the set of cached behaviors derived from the script
  and the nginx template

`LocalCdnCacheTest` is not extended. It is `@Tag("docker")` and has never been executed on any
machine used for this work, so a case added there would ship unverified — the failure mode the
Splunk HEC integration test already demonstrates. The collision it would demonstrate is documented
in `12_CDNS.md` and prevented by a guard that is itself merge-blocking.

## What this does not close

- **Direct-to-6010 requests.** They bypass the gateway, so the guard never runs. No CDN fronts
  those, so no cache key is at stake, but `cacheKeyIntParam` keeps accepting `?id=%37` there. This
  design does not change `cacheKeyIntParam`.
- **Whether CloudFront decodes before whitelist matching.** Still unverified, and unverifiable
  without a distribution. The guard makes the question moot rather than answering it: no encoded
  query reaches a cached behavior at all.
- **Overlay or out-of-band edge configuration.** The conformance test reads the committed script and
  template. A behavior added directly in the AWS console is invisible to it, as it is to every other
  conformance test in this repo.

## Documentation

Sharp edge 9 in `docs/system_design/12_CDNS.md` is rewritten: channels 1 and 2 become closed rather
than open, channel 3 is recorded for the first time, and the two residues above are stated. The
sharp-edge numbering is not changed and no `##` heading is renumbered.
