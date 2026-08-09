# CDN percent-encoding cache key — design

Close the one real percent-encoding cache-key channel at the CloudFront edge, and correct the two
claims in `12_CDNS.md` that turned out to be false.

## What was actually established

This design replaced an earlier one on the same branch. Two foundational claims were disproved
along the way, and recording them is half the value here — both had already cost a wrong turn.

### The origin cannot see the raw query string

The first design put a guard in `GatewayProxyService` rejecting any `%` in the query string of a
cached route. It cannot work. Armeria's Netty codecs overwrite `:path` with the **normalized**
request target before any service, decorator, or `ApiVersion.parse` runs — `Http1RequestDecoder:177`
into `ArmeriaHttpUtil:679` for HTTP/1, `Http2RequestDecoder:128` into `ArmeriaHttpUtil:614` for
HTTP/2, both `.path(reqTarget.toString())`. `DefaultRequestTarget.toString()` composes the
normalized path with `encodeQueryToPercents(query)`, and `QUERY_MUST_PRESERVE_ENCODING` excludes
alphanumerics. So percent-encoded letters and digits are decoded away in the decoder, and in
production `req.path()` is exactly `ctx.path() + "?" + ctx.query()`.

Measured on a real Armeria 1.28.4 server driven over a raw socket:

```
wire=[/api/catalog/item?id=%37]                -> req.path()=[/api/catalog/item?id=7]
wire=[/api/catalog/item?%69d=7]                -> req.path()=[/api/catalog/item?id=7]
wire=[/api/catalog/similar?movieId=1&%6b=200]  -> req.path()=[/api/catalog/similar?movieId=1&k=200]
```

`ServiceRequestContext.of(request)` is the one place that behaves differently — its builder passes
the caller's `HttpRequest` through verbatim — so a unit test can show a guard working that is inert
in production. That is how the broken design reached a green test suite.

### Two of the three channels are not real

`12_CDNS.md` sharp edge 9 has claimed since 2026-07-29 that `?%69d=7` "collides with `?%69%64=8`
and with a bare parameterless request — many bodies under one key". It does not, and neither does
the variant this branch originally added about `k`'s default.

The four cached behaviors carry **no** `OriginRequestPolicyId`
(`scripts/create-cdn-distribution.sh:187-200`); only the `DefaultCacheBehavior` sets `$all_viewer`
(line 175). AWS is explicit: *"Other information from the viewer request, such as URL query strings,
HTTP headers, and cookies, is not included in the origin request by default"*, and *"All URL query
strings … that you include in the cache key are automatically included in origin requests."* So on
a cached behavior CloudFront forwards exactly the whitelisted parameters and nothing else.

A non-whitelisted `%69d` or `%6b` therefore never reaches the origin:

| Request | Cache key | Origin sees | Verdict |
|---|---|---|---|
| `?%69d=7` | no `id` | no `id` → 400, `no-store`, uncached | no bodies collide |
| `?movieId=1&%6b=200` | `movieId=1` | `movieId=1`, default `k=10` | body matches its key |
| `?id=%37` | `id=%37` | `id=%37` → decodes to `7` | **a second key over one body** |

Both false channels failed the same way: they assumed the origin sees parameters the edge left out
of the cache key.

## The one real channel

`?id=%37` is a distinct CloudFront cache key for byte-identical content, and so are `?id=%3037`,
`?id=%25%33%37` and every other spelling of the same integer. `id` is whitelisted, so the encoded
value is both keyed and forwarded; the origin percent-decodes it, `cacheKeyIntParam` sees a
canonical `7`, and serves the `id=7` body.

This is the same unbounded cache-buster that the `k` ceiling closed in the 2026-07-29 hardening,
reached by a different spelling: the whitelist bounds *which* parameters fragment the cache, never
*which spellings* of a value do. Every miss costs a full candidate scan on a public, unauthenticated
route, and the fragmentation is attacker-controlled and unbounded.

It is a cost problem, not a correctness one. Nothing is served wrongly.

## The fix

A CloudFront **viewer-request Function** associated with the four cached behaviors, which rebuilds
the query string from the whitelisted parameters alone:

```js
var ALLOWED = {
    '/api/catalog/item':       ['id'],
    '/api/v1/catalog/item':    ['id'],
    '/api/catalog/similar':    ['movieId', 'k'],
    '/api/v1/catalog/similar': ['movieId', 'k']
};

function handler(event) {
    var request = event.request;
    var allowed = ALLOWED[request.uri];
    if (!allowed) {
        return request;
    }
    var qs = [];
    for (var i = 0; i < allowed.length; i++) {
        var name = allowed[i];
        var param = request.querystring[name];
        if (!param) {
            continue;
        }
        if (param.multiValue || param.value.indexOf('%') >= 0) {
            return {
                statusCode: 400,
                statusDescription: 'Bad Request',
                headers: { 'cache-control': { value: 'no-store' } }
            };
        }
        qs.push(name + '=' + param.value);
    }
    request.querystring = qs.join('&');
    return request;
}
```

The cache key is computed from the function's **output** — that is what AWS's own
`normalize-query-string-parameters` example exists to exploit — so whatever the function emits is
what fragments the cache.

Three properties, in order of importance:

**Rejecting `%` in a whitelisted value closes the real channel.** Rejecting rather than decoding is
deliberate and matches the origin: `cacheKeyIntParam` already refuses every non-canonical spelling
of an integer, so decoding `%37` to `7` at the edge would *create* a second working spelling rather
than remove one. One spelling, one identity, at both layers. The 400 carries `no-store` and so is
not cached.

**Rebuilding in a fixed order closes an ordering channel.** `?movieId=1&k=5` and `?k=5&movieId=1`
are two spellings of one request; AWS documents that parameter order fragments the cache. Emitting
the allowed names in declaration order makes that moot without anyone having to reason about
whether the whitelist sorts.

**Dropping unlisted parameters is defensive, not corrective.** They are already excluded from the
key and not forwarded. Dropping them makes the forwarded query deterministic and removes the class
of reasoning that produced the two false channels above.

**Robust to the question nobody can answer here.** Whether CloudFront percent-decodes parameter
*names* before whitelist matching is undocumented and unmeasurable without a distribution. The
function is correct either way: if `%69d` stays raw it is unlisted and dropped; if it decodes to
`id` it is whitelisted and passes through as `id=7`, which was already right. Nothing in this design
rests on the answer.

## Verification

Two layers, and it matters which is which.

**Executable now.** `aws cloudfront create-function` places a function in the `DEVELOPMENT` stage
without any distribution, and `aws cloudfront test-function` runs it in the real CloudFront runtime
against a supplied event object. So the function's *logic* is verified against AWS itself, not a
local JS shim: encoded value rejected, repeated parameter rejected, order normalized, unlisted
parameter dropped, non-cached URI untouched. `scripts/test-cdn-function.sh` creates the function,
runs the cases, and deletes it.

**Not executable, and stated as such.** How CloudFront parses a raw wire query string into the event
object is not observable through `test-function`, which takes an already-parsed event. Nor is the
association wiring, since no distribution exists in this account. The nginx stand-in mirror is
`@Tag("docker")` territory and Docker has never run on any machine used for this work.

The Java-side gate gets a conformance test instead — `CdnQueryNormalizationConformanceTest`, non-docker,
in the `resilience` profile — pinning the function's `ALLOWED` map against the cache-policy
whitelists and `PathPattern` entries in `scripts/create-cdn-distribution.sh`, and asserting every
cached behavior carries a `FunctionAssociations` entry. A fifth cached behavior, or a whitelist
edit, fails the build until the function agrees.

## The nginx stand-in

`docker/cdn/default.conf.template` mirrors the distribution's cache behaviors for local development.
Each cached `location =` block gets a rejection matching the function's, so the harness keeps
demonstrating the same semantics as the real edge. This ships unexecuted — Docker is unavailable —
and the design says so rather than implying coverage.

## Documentation

`docs/system_design/12_CDNS.md` sharp edge 9 is rewritten. The two false claims are removed and
replaced with what was established: the origin provably cannot see the raw query string (with the
Armeria citations and probe output), the origin-request-policy reasoning that kills the two
channels, the one real channel and where it is now closed, and the two things that remain
unverified. The `QueryStringBehavior` bullet around line 68 loses its "more serious direction"
sentence for the same reason. Sharp-edge numbering is unchanged and no `##` heading is renumbered.

This correction is the more valuable half of the branch: a false claim in a design document sent
two separate implementation attempts at the wrong layer inside one session.

## What this does not close

- **Nothing is deployed.** No CloudFront distribution exists in this account — only the two cache
  policies. The function and its association are instructions for the same future provisioning step
  the rest of `create-cdn-distribution.sh` describes.
- **`cacheKeyIntParam` is unchanged.** It cannot see raw spellings and this design does not pretend
  otherwise. Direct-to-6010 requests keep accepting `?id=%37`; nothing caches those responses.
- **Whether CloudFront decodes parameter names stays unknown.** The design is built not to care.
