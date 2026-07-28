# Local CDN

An nginx stand-in for the CloudFront distribution, so the caching semantics can be run and
observed with no AWS account. For the full edge design see the
[CDN Edge investigation](../system_design/12_CDNS.md#5-local-cdn-stand-in).

Design: `docs/superpowers/specs/2026-07-14-local-cdn-and-origin-secret-hardening-design.md`

## Run it

```bash
# 1. Gateway + backends on the host (gateway listens on :8010)
sh scripts/run-microservices-local.sh

# 2. The CDN in front of it, on :8090
docker compose -f docker-compose.cdn.yml up
```

If the gateway has `GATEWAY_ORIGIN_SECRET` set, `CDN_ORIGIN_SECRET` must match it or every
request 403s:

```bash
CDN_ORIGIN_SECRET=my-secret docker compose -f docker-compose.cdn.yml up
```

## See it work

```bash
curl -sI 'localhost:8090/api/catalog/item?id=1' | grep -i x-cache   # X-Cache: MISS
curl -sI 'localhost:8090/api/catalog/item?id=1' | grep -i x-cache   # X-Cache: HIT

# The cache key whitelists `id`, so a cachebuster cannot fragment the cache
curl -sI 'localhost:8090/api/catalog/item?id=1&cachebuster=99' | grep -i x-cache   # HIT

# Default-deny: the personalized route is never cached
curl -sI -X POST localhost:8090/api/recommend | grep -i x-cache   # X-Cache: BYPASS
```

## Invalidate

```bash
./scripts/invalidate-local-cdn.sh
```

## What this DOES mirror

Each nginx block is a deliberate mirror of a CloudFront decision:

| CloudFront | nginx |
|---|---|
| `DefaultCacheBehavior` = CachingDisabled | default `location /` with bypass → `X-Cache: BYPASS` |
| `/api/catalog/item*` key whitelists `id` | `proxy_cache_key "$uri\|$arg_id"` |
| `/api/catalog/similar*` whitelists `movieId`,`k` | `proxy_cache_key "$uri\|$arg_movieId\|$arg_k"` |
| `/api/v1/catalog/item*`, identical to the unversioned twin | second `location = /api/v1/catalog/item` block, same `proxy_cache_key "$uri\|$arg_id"` |
| `/api/v1/catalog/similar*`, identical to the unversioned twin | second `location = /api/v1/catalog/similar` block, same `proxy_cache_key "$uri\|$arg_movieId\|$arg_k"` |
| `CustomHeaders` inject the origin secret | `proxy_set_header x-origin-secret` |
| `X-Cache: Hit from cloudfront` | `add_header X-Cache $upstream_cache_status` (or the `$cdn_cache_status` map in the default block — see Config note) |
| Honours `stale-while-revalidate` / `stale-if-error` | same directives, natively (`proxy_cache_background_update on`, `proxy_cache_use_stale ...`) |
| Honours a **bare `s-maxage`** with no `max-age`/`Expires` fallback | same, natively — **not documented by nginx itself**, confirmed here by experiment (see Config note) |

`LocalCdnCacheTest` (`@Tag("docker")`) proves these:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  mvn test -DexcludedGroups=load -Dgroups=docker -Dtest=LocalCdnCacheTest
```

Notably, `sMaxAgeAloneIsEnoughToCache_missThenHit` is the test that establishes the bare-`s-maxage`
fact above: the fixture's stub origin emits `Cache-Control` with only `s-maxage` (no `max-age`, no
`Expires`), and the template carries no `proxy_cache_valid` (see Config note), so the second
request can only come back `HIT` if nginx itself chose to honour `s-maxage`. It does.

Also observed (not assumed): a conditional request — `GET` with `If-None-Match` matching the
cached ETag — comes back `X-Cache: HIT` with a `304`, per `ifNoneMatchReturns304ThroughTheCdn`.
`proxy_cache_revalidate on` lets nginx answer the 304 straight from its own cached copy, with no
round trip to the origin at all.

## What this does NOT mirror

Read this before drawing any conclusion from the local environment.

- **Invalidation is coarser.** `invalidate-local-cdn.sh` purges the WHOLE cache. nginx OSS has
  no path-scoped purge (`proxy_cache_purge` is nginx Plus or a third-party module), whereas
  CloudFront invalidates by path pattern.
- **No WAF, no Shield, no edge TLS, no geographic distribution.** These are most of the CDN's
  actual value in the CloudFront design. None are exercised here.
- **One nginx, not 400+ POPs.** No POP-to-POP behaviour, no Origin Shield tiering.
- **nginx and CloudFront are not bit-identical.** The claim is that the three `Cache-Control`
  directives we emit behave the same. Nothing broader.
- **`$arg_` lookups are case-insensitive in nginx; the production cache keys are not.** nginx's
  `$arg_movieId` (and `$arg_id`) matches the query-string argument name case-insensitively and
  returns only the *first* match, while CloudFront's query-string whitelist and Armeria's
  `ctx.queryParam` are both case-sensitive. So locally, `?MOVIEID=1&movieId=2` keys the local
  cache on `1` (whichever case nginx happens to pick up first) while the real origin reads
  `movieId=2` — silently caching movie 2's neighbours under the key for movie 1. This is a
  local-only artifact: CloudFront's whitelist simply drops `MOVIEID` since it isn't `movieId`,
  so the ambiguity nginx exhibits here cannot happen in production.
- **The local locations are exact matches; CloudFront's are prefix globs.** `location =
  /api/catalog/item`, `location = /api/catalog/similar`, `location = /api/v1/catalog/item`,
  and `location = /api/v1/catalog/similar` in `docker/cdn/default.conf.template` each match
  only that one literal path, whereas the CloudFront path patterns are `/api/catalog/item*` /
  `/api/catalog/similar*` / `/api/v1/catalog/item*` / `/api/v1/catalog/similar*` — prefix globs
  that also match, e.g., `/api/catalog/item/5` or `/api/v1/catalog/item/5`. The local
  environment therefore **under-caches** relative to production, for both the versioned and
  unversioned spellings: a sub-path request falls through to the local default (uncached) block
  instead of the cached location. Nothing personalized is reachable at those sub-paths, so this
  is a false-conclusion risk for anyone using the local env to reason about hit ratio — not a
  security hole.

This is a semantics harness, not a CloudFront emulator.

## Config note

`docker/cdn/default.conf.template` is rendered by the nginx image's envsubst.
`NGINX_ENVSUBST_FILTER=CDN_` is **required**: without it envsubst also substitutes nginx's own
`$uri`, `$arg_id`, and `$upstream_cache_status`, silently producing a config that caches
everything under one key.

**How the default block reports `BYPASS`.** `$upstream_cache_status` is empty whenever the cache
module never ran at all — which is exactly what happens for `POST /api/recommend`, since POST is
not a cacheable method and nginx skips the cache module for it entirely. nginx omits a header
whose value is empty, so without intervention `X-Cache` would simply vanish on that route instead
of reporting `BYPASS`. The template does **not** fix this by widening `proxy_cache_methods` to
include POST — that was tried and reverted, because it would make POST cacheable-by-method and
leave it protected only by the cache-key/behavior configuration rather than being structurally
uncacheable. Instead there is an http-context `map`:

```
map $upstream_cache_status $cdn_cache_status { '' BYPASS; default $upstream_cache_status; }
```

`location /` uses `add_header X-Cache $cdn_cache_status` — the map only translates the reporting
of the empty case to `BYPASS`; it changes nothing about whether nginx actually attempts to cache
the response. POST stays uncacheable for the structural reason (wrong method), not merely because
a directive says so.

The two CACHED locations (`= /api/catalog/item`, `= /api/catalog/similar`) deliberately do **not**
use the map — they emit the raw `$upstream_cache_status` via `add_header X-Cache
$upstream_cache_status`. There, an empty status would mean the cache module didn't run on a
GET route that is supposed to be cached — a real bug worth seeing as a missing header, not
something to paper over as `BYPASS`.

The template deliberately has **no `proxy_cache_valid`**. Cache lifetime comes only from the
origin's `s-maxage`, and nobody should add one: doing so would make `LocalCdnCacheTest` pass even
if nginx ignored `s-maxage` entirely — i.e. it would prove nothing. This is also how we know nginx
honours a bare `s-maxage` with no `max-age`/`Expires` present, a fact nginx's own documentation
does not spell out: `sMaxAgeAloneIsEnoughToCache_missThenHit` only works as proof *because* there
is no `proxy_cache_valid` to cache the object independently of what `s-maxage` says.
