// Viewer-request function for the four cached catalog behaviors.
//
// CloudFront computes the cache key from THIS function's output, so whatever it emits is what
// fragments the cache. It rebuilds the query string from the whitelisted parameters alone:
//
//   1. Rejects a percent-encoded value on a whitelisted name. `?id=%37` is a distinct cache key
//      for the byte-identical `?id=7` body, and so is every other spelling of the same integer —
//      an unbounded, attacker-controlled cache-buster on a public route where each miss costs a
//      full candidate scan. Rejecting rather than decoding matches the origin: cacheKeyIntParam
//      already refuses every non-canonical spelling, so decoding %37 to 7 here would CREATE a
//      second working spelling rather than remove one. One spelling, one identity, both layers.
//   2. Rejects a repeated parameter, as cacheKeyIntParam does.
//   3. Emits the allowed names in declaration order, so ?movieId=1&k=5 and ?k=5&movieId=1 are one
//      cache key rather than two.
//   4. Drops unlisted parameters. They are already excluded from the key and, with no origin
//      request policy on these behaviors, never forwarded — so this is defensive, not corrective.
//
// It is deliberately robust to a fact nobody can measure without a distribution: whether
// CloudFront percent-decodes parameter NAMES before whitelist matching. If `%69d` stays raw it is
// unlisted and dropped; if it decodes to `id` it is whitelisted and passes through as `id=7`,
// which was already correct. Nothing here rests on the answer.
//
// The same question applies to parameter VALUES, where it is NOT neutral — it changes what this
// function does. `param.value.indexOf('%')` is the entire mechanism, and it assumes CloudFront
// puts the RAW wire value in the event object. AWS's event-structure documentation is silent on
// whether query-string values are percent-decoded first, and `test-function` cannot settle it:
// the harness hands the runtime an event in which the harness itself wrote the literal `%37`.
// That is the exact shape of the failure that killed the first design of this fix — a guard that
// passed its tests because the test supplied an encoding the production path had already decoded
// away (see 12_CDNS.md sharp edge 9 on Armeria's decoders). Both branches, stated plainly:
//   - Cache-key soundness survives either way. If values arrive decoded, `%37` is already `7` by
//     the time the rebuild runs, so `?id=%37` and `?id=7` collapse onto ONE key. The channel this
//     file exists to close is closed on both branches.
//   - The STATED behaviour would be wrong. `?id=%37` would return 200 from the `id=7` cache entry
//     rather than a 400 — that is the edge DECODING the alias, which point 1 above explicitly
//     refuses, because decoding creates a second working spelling instead of removing one. The
//     guard would have degraded from reject-the-alias to normalize-the-alias.
//   - Conditional on that same branch, a decoded `%26` would arrive as a bare `&` that the `%`
//     check cannot see (see the concatenation note below).
// Only a real distribution distinguishes the two, and none exists in this account.
//
// The `%` rejection is load-bearing beyond the cache key. The rebuild below concatenates
// `name + '=' + param.value` with NO re-encoding, which is safe only because a value containing
// `%` has already been rejected: on the raw branch nothing reaching that concatenation can carry
// an encoded `&`, `=` or `#` to split itself into extra parameters. Treat the check as an
// injection guard as well as a cache-key measure — relaxing it into a decode requires adding
// encoding at the join.
//
// The 400 carries no-store, so a rejection is never cached at the edge.
//
// Two things this function does NOT close, left as origin-side responsibility rather than
// edge logic:
//   - `?movieId=1` and `?movieId=1&k=10` are two distinct cache keys over one response body,
//     since `k`'s origin default is 10. Bounded at 2x, not eliminated — "one spelling, one
//     identity" above is aspirational, not literally true, for a parameter with a default.
//   - A non-canonical but UNENCODED value (`007`, `+7`, an empty string, `7é`) has no `%` in
//     it, so it clears the check below and passes through as its own cache key. It relies
//     entirely on the origin's cacheKeyIntParam rejecting it with no-store to avoid being
//     cached under that spelling. The two layers split this responsibility on purpose; this
//     file only owns the percent-encoded half of it.
//
// Pinned to the cache policies by CdnQueryNormalizationConformanceTest — the ALLOWED literal
// below is parsed by that test, so keep it a plain object of string arrays.
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
        // This function is associated ONLY with the four exact-match cached behaviors, so a
        // miss here cannot mean "some other, unprotected route" — every URI that legitimately
        // reaches this handler is one of the four keys above. A miss means the URI CloudFront
        // handed the function differs from the URI CloudFront matched the behavior on. AWS
        // documents exactly that gap: "CloudFront normalizes URI paths consistent with RFC 3986
        // and then matches the path with the correct cache behavior. Once the cache behavior is
        // matched, CloudFront sends the raw URI path to the origin." If viewer-request functions
        // are handed that same raw (pre-normalization) path, a spelling like `//api/catalog/item`
        // or `/api/x/../catalog/item` would match the behavior on the normalized path yet miss
        // this whitelist on the raw one — silently falling through the whole percent-encoding
        // guard this file exists to enforce. Whether Functions actually receive the raw or the
        // normalized path is not measurable from here: `test-function` takes an already-parsed
        // event, never a raw wire path, and there is no distribution in this repo to observe it
        // on. Passing through on a miss would bet the guard's soundness on an unmeasured fact;
        // rejecting removes the dependence on that fact entirely. This mirrors the gateway's
        // existing discipline in rejectNonCanonicalPath: a non-canonical spelling of a public
        // route is refused outright, not served as if it were the canonical one.
        return {
            statusCode: 400,
            statusDescription: 'Bad Request',
            headers: { 'cache-control': { value: 'no-store' } }
        };
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
        // Emitted unencoded on purpose, and safe only because the `%` rejection above ran first.
        qs.push(name + '=' + param.value);
    }
    request.querystring = qs.join('&');
    return request;
}
