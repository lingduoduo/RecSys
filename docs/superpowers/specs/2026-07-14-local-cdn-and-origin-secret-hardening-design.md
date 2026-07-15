# Local CDN and Origin-Secret Hardening Design

Date: 2026-07-14
Status: Proposed

Follows `docs/superpowers/specs/2026-07-14-cdn-edge-acceleration-design.md` (PR #187).
That design put CloudFront in front of the gateway but left three origin-secret gaps
open as follow-ups, and left the whole thing undemonstrable without an AWS account.
This design closes both.

## Summary

Two related pieces:

1. **Origin-secret hardening** — accept a *set* of secrets so rotation has no 403
   window, make rejections observable, and stop defaulting the origin hop to
   cleartext.
2. **Local CDN environment** — an nginx stand-in in front of the gateway that
   mirrors each CloudFront behavior one-for-one, so the caching semantics can be
   run, observed, and tested with no AWS account.

The AWS scripts remain the documented deployment logic. Nothing here removes them.

## Motivation

The prior design's follow-ups were real:

- `GatewayOriginSecret` validates exactly **one** value, so rotating the secret
  requires two unsynchronized writes (the k8s Secret and a distribution update).
  Between them, **100% of non-exempt traffic 403s**. Rotation is also the primary
  compensating control for a secret the design admits is replayable-if-observed —
  so the control that matters most is the one that is operationally painful.
- The reject path emits no log and no metric. A botched rotation therefore shows up
  only as a CloudFront-side 4xx spike with nothing origin-side to confirm the cause.
- `OriginProtocolPolicy: http-only` sends that secret across the CloudFront→ALB hop
  in cleartext, where it is observable and replayable — by exactly the attacker the
  header exists to stop. The `HTTPSPort: 443` and `OriginSslProtocols: TLSv1.2`
  fields already in the payload are inert under `http-only`, which is a tell that
  `https-only` was the latent intent.

And separately: the CDN could not be exercised at all without provisioning real AWS
infrastructure. For a system whose CDN layer is meant to be understood and reviewed,
that is a gap worth closing.

## Goals

- Zero-downtime origin-secret rotation.
- A Prometheus counter and a first-occurrence log on the reject path.
- `https-only` as the default origin protocol, with `http-only` available but loud.
- A runnable local CDN that honours the same `Cache-Control` directives the origin
  emits, with an automated test proving it.

## Non-goals

- Replacing the AWS scripts. They stay as the deployment logic.
- Emulating WAF, Shield, edge TLS, or geographic distribution locally. See
  "Divergences" — the local environment demonstrates **caching semantics only**.
- LocalStack. Its CloudFront support is a paid feature and does not actually cache,
  so it would validate API call shapes rather than the behaviour that matters here.
- Path-scoped local invalidation. nginx OSS cannot do it; see "Divergences".

---

## Part 1 — Origin-secret hardening

### 1.1 Accept a set of secrets

`GATEWAY_ORIGIN_SECRET` becomes comma-separated, parsed with the same CSV idiom
`GatewayAuthenticator.parseCsv` already uses. Empty or unset still means disabled.
A single value keeps working exactly as today, so this is backward compatible.

`isAllowed` returns true when the provided header constant-time-matches **any**
configured secret.

**Constant-time iteration is load-bearing.** The current single-value compare puts
`expected` first in `MessageDigest.isEqual`, which is what makes the loop count
independent of attacker input. Iterating a set must not `break` on first match — an
early return leaks how many secrets are configured and which one matched.
`GatewayAuthenticator` already solved this exact problem and the fix mirrors it:

```java
boolean matched = false;
for (String secret : secrets) {
    matched |= constantTimeEquals(secret, provided);
}
return matched;
```

Rotation then becomes, with no 403 window:

```
1. GATEWAY_ORIGIN_SECRET="old,new"   -> roll pods   (both accepted)
2. update the distribution to send   "new"          (CloudFront propagates, minutes)
3. GATEWAY_ORIGIN_SECRET="new"       -> roll pods   (old retired)
```

### 1.2 Observability on the reject path

- **Counter** `gateway_origin_secret_rejected_total`, registered on the
  `PrometheusMeterRegistry` the gateway already builds
  (`MicroserviceGatewayServer.java:124`), following the existing
  `GatewayRegistryMetrics.register` pattern. This is the real signal — it is
  scrapeable, alertable, and already exposed at `/metrics`.
- **One WARN log on the first rejection only**, guarded by a flag flip rather than
  logged per request. Under a scan or a botched rotation, per-request logging would
  flood; a single breadcrumb explaining the counter is enough.

The counter must be injectable for tests. `GatewayOriginSecret` takes an optional
`MeterRegistry`; when null it uses a no-op, so no existing call site breaks and the
class stays unit-testable without a registry.

### 1.3 Origin protocol becomes a decision

`scripts/create-cdn-distribution.sh` gains `ORIGIN_PROTOCOL_POLICY`, defaulting to
**`https-only`**. This makes the already-present `HTTPSPort: 443` and
`OriginSslProtocols: TLSv1.2` fields live rather than inert.

`http-only` remains accepted but prints a prominent warning that the origin secret
will cross the public hop in cleartext and is replayable if observed. Any other
value is rejected.

`https-only` requires an ALB `:443` listener and a **regional** ACM certificate — a
prerequisite the runbook must state, because the ALB today listens on `:80` only
(`k8s/eks/waf-api-gateway-ingress.yaml`). Since the distribution is documented logic
rather than a live deployment, the default is set to the correct posture and the
prerequisite is documented, rather than defaulting to the convenient one.

---

## Part 2 — Local CDN environment

### 2.1 Components

| File | Responsibility |
|---|---|
| `docker-compose.cdn.yml` | nginx 1.27-alpine on `:8090`, origin `host.docker.internal:8010` |
| `docker/cdn/nginx.conf` | the behaviour mirror |
| `scripts/invalidate-local-cdn.sh` | local counterpart to `invalidate-cdn.sh` |
| `src/test/java/.../LocalCdnCacheTest.java` | `@Tag("docker")` testcontainers proof |

nginx sits in front of the gateway started by `scripts/run-microservices-local.sh`,
which runs on the host at `:8010`. Port `8090` avoids the model service on `:8080`.
The origin is overridable via an env var for anyone not on Docker Desktop.

### 2.2 The behaviour mirror

Each row is a deliberate mirror of a CloudFront decision from the prior design:

| CloudFront | nginx equivalent |
|---|---|
| Default behaviour `CachingDisabled` | default `location /`, `proxy_cache off` → `X-Cache: BYPASS` |
| `/api/catalog/item*` cache key whitelists `id` | `proxy_cache_key "$uri\|$arg_id"` |
| `/api/catalog/similar*` whitelists `movieId`,`k` | `proxy_cache_key "$uri\|$arg_movieId\|$arg_k"` |
| `CustomHeaders` inject the origin secret | `proxy_set_header x-origin-secret` |
| `X-Cache: Hit from cloudfront` | `add_header X-Cache $upstream_cache_status` |
| Honours `s-maxage` / SWR / `stale-if-error` | same directives, natively |

nginx supports `stale-if-error` since **1.7.7** and `stale-while-revalidate` since
**1.11.10**, so pinning `1.27-alpine` covers both. SWR additionally requires
`proxy_cache_background_update on` plus `proxy_cache_use_stale updating`.
`proxy_cache_revalidate on` makes nginx send `If-None-Match` upstream, exercising the
origin's 304 path.

**The cache-key line is the most important config in the file.** nginx's default key
is the full `$request_uri`, every query argument included — which is precisely the
origin-DoS amplifier the prior design calls out. Restricting the key to whitelisted
arguments reproduces CloudFront's protection, and makes it testable.

### 2.3 The test

`LocalCdnCacheTest`, `@Tag("docker")` — excluded from `mvn test` by default per
`pom.xml:22`, run with `-DexcludedGroups=load -Dgroups=docker`, matching the existing
convention in `src/test/java/com/recsys/infrastructure/redis/sharding/`.

A stub Armeria origin emits real `HttpCaching.publicCache(...)` headers. nginx runs
in a container against it via `Testcontainers.exposeHostPorts(...)` /
`host.testcontainers.internal`. Assertions:

1. First `GET /api/catalog/item?id=1` → `X-Cache: MISS`; second → `HIT`.
2. `s-maxage` actually drives the cache lifetime. **This settles a fact the nginx
   documentation does not state.** nginx's source checks `s-maxage=` before falling
   back to `max-age=`, which is correct shared-cache behaviour, but the docs only
   mention `X-Accel-Expires`, `Expires`, and `Cache-Control` generically. The origin
   emits `s-maxage` and no `max-age`, so if nginx ignored it the object would be
   cached by `proxy_cache_valid` defaults instead and the whole demo would be
   misleading. The test asserts a response with only `s-maxage` is cached — evidence
   over documentation.
3. `?id=1&cachebuster=99` still returns `HIT` — the whitelist holds and the cache
   cannot be fragmented.
4. `POST /api/recommend` → `X-Cache: BYPASS` — default-deny holds.
5. `If-None-Match` with the stub's ETag → `304`.
6. The stub origin records `x-origin-secret` on every forwarded request.
7. With two secrets configured, both are accepted — rotation proven with no AWS.

### 2.4 Divergences from CloudFront

These go in the runbook. The local environment is a semantics harness, not a
CloudFront emulator, and must not be mistaken for one.

- **Invalidation is coarser.** nginx OSS has no path-scoped purge (`proxy_cache_purge`
  is nginx Plus or a third-party module). `invalidate-local-cdn.sh` purges the whole
  cache; CloudFront invalidates by path pattern.
- **No WAF, no Shield, no edge TLS, no geographic distribution.** These are most of
  the CDN's actual value in the prior design. None of them are exercised locally.
- **One nginx, not 400+ POPs.** No POP-to-POP behaviour, no Origin Shield tiering.
- **nginx and CloudFront are not bit-identical.** Matching the three directives we
  emit is what is claimed here; nothing broader.

---

## Testing

| Test | Tag | Proves |
|---|---|---|
| `GatewayOriginSecretTest` (extended) | none | multi-secret accept; single-value unchanged; unset still disabled; no early-break |
| `GatewayOriginSecretMetricsTest` | none | counter increments on reject, not on allow or exempt |
| `LocalCdnCacheTest` | `docker` | the seven assertions in 2.3 |

The existing suite is 954 tests and must stay green.

## Risks

| Risk | Mitigation |
|---|---|
| Set iteration short-circuits, leaking timing | Accumulate with `\|=`, never `break`; mirrors `GatewayAuthenticator` |
| nginx ignores `s-maxage`, making the demo lie | Asserted directly by `LocalCdnCacheTest` rather than assumed |
| `https-only` default breaks the script against today's `:80`-only ALB | Prerequisite documented in the runbook; `http-only` still available with a warning |
| Local env mistaken for a CloudFront emulator | Divergences documented in the runbook and this spec |
| `host.docker.internal` is Docker-Desktop-specific | Origin overridable via env var |
