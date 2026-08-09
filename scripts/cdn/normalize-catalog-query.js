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
// The 400 carries no-store, so a rejection is never cached at the edge.
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
