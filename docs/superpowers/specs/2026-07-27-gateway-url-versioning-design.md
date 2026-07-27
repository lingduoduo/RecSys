# Gateway-owned URL versioning and API compatibility policy — design

**Date:** 2026-07-27
**Status:** Approved (design)
**Branch:** `feat/gateway-url-versioning`

## Problem

The public surface is unversioned. Every prefix in `MicroserviceRoute.defaults()` is
`/api/<resource>` with no version segment, and there is no header-based scheme either.
Version numbers exist only on the internal hop after prefix-strip, where three
conventions coexist — root `/v1/…` and `/v2/…` on 6010 and 7010, Spring `/api/v1/…`
and root `/v2/…` on 8080, and unversioned legacy routes on both Armeria servers.

Three consequences, documented in
[09_API_Gateway §1](../../system_design/09_API_Gateway.md#api-versioning-and-deprecation):

- Clients have no way to pin a version, so any breaking change to a backend is a
  breaking change to every caller at once.
- A version segment lands mid-path whenever it is exposed: the model service's
  version-management API is reachable only as `/api/model/api/v1/model/versions`.
- The back-compat aliases `/api/catalog`, `/api/model`, and `/api/online` are marked
  deprecated in documentation only. No `Deprecation` or `Sunset` header is emitted
  anywhere, so no client can discover the deprecation programmatically.

The last point was a deliberate deferral. The
[canonical entry-point design](2026-07-10-canonical-recommendation-gateway-entry-point-design.md)
declined to add a deprecation header because doing it consistently "would require a
separate compatibility policy and removal schedule". This design writes that policy.

## Goal

Make the version a routing dimension the **gateway owns**, so that:

- `/api/v1/<resource>` is the canonical public spelling for every `/api` route.
- Backends keep their current internal paths unchanged — four services do not each
  reimplement versioning.
- Every unversioned request keeps working, treated as implicit v1, and carries a
  machine-readable deprecation signal.
- A written compatibility policy states what a breaking change is, how long a
  deprecated spelling survives, and who decides to remove it.

## Non-goals

- **Header-based versioning.** Rejected. `HeaderBehavior: none` on both CloudFront
  cache behaviors means no request header can vary a cached response, so a v1 and a
  v2 client would collide on one cached object. A distinct URL is a distinct cache
  key for free.
- **Renaming the internal `/v2/…` routes.** `/v2/recommend` is the shared pipeline
  contract all three backends implement — it is what `CrossPathConsistencyTest` pins,
  and no client sees it. Renaming costs three servers plus the integration suite for
  zero external benefit. It is documented as a pipeline variant instead (§7).
- **The `/v2/recommend` protection gap.** On 7010 `/v2/recommend` is not wrapped in
  `OnlineAdmissionControl` although `/online/recommendation` beside it is; on 8080 it
  has no rate limiter, submit token, load shedder, A/B exposure logging, or metrics,
  unlike `/api/v1/recommend`. This is a live correctness issue but an independent one,
  scoped to a follow-up spec.
- **Automatic removal of deprecated routes.** See §5.

## Decisions

Taken during brainstorming:

| Question | Decision |
|---|---|
| Unversioned prefixes | Keep working as implicit v1, deprecated with `Deprecation` + `Sunset` |
| Where the version is stripped | At each gateway service entry, before authorization |
| Internal `/v2/…` | Document as a pipeline variant; do not rename |
| Edge configuration | In scope — CloudFront behaviors and the local nginx stand-in |
| Policy strength | Advisory `Sunset`; removal is always an explicit PR |

## 1. `ApiVersion` — parsing and normalization

New value type `com.recsys.application.gateway.ApiVersion`:

```java
public record ApiVersion(int version, String path, boolean explicit) {
    public static ApiVersion parse(String requestPath);
    public boolean supported();
}
```

`parse` strips a leading segment only when it is exactly `/api/` followed by `v`, then
one to four digits, then `/` or end-of-path. The narrow rule keeps the function total —
no exceptions, no integer overflow — and avoids mistaking a resource named `version`
for a version segment.

| Request path | `version` | `path` | `explicit` | Outcome |
|---|---:|---|---|---|
| `/api/v1/users/profile` | 1 | `/api/users/profile` | true | proxied |
| `/api/users/profile` | 1 | `/api/users/profile` | false | proxied, deprecated |
| `/api/v2/users` | 2 | `/api/users` | true | `400` — unsupported |
| `/api/version/x` | 1 | `/api/version/x` | false | not a version segment |
| `/api/v/x` | 1 | `/api/v/x` | false | no digits — not a version |
| `/api/v99999/x` | 1 | `/api/v99999/x` | false | over four digits — not a version |
| `/api/v1` | 1 | `/api` | true | `404` on route match |
| `/health` | 1 | `/health` | false | untouched, exempt |

`SUPPORTED_VERSIONS = Set.of(1)`. Adding v2 later is a one-line change plus the route
work that a real v2 would need.

An explicit unsupported version returns `400` through the existing
`GatewayProxyService.gatewayError`:

```json
{"error":"unsupported API version: v2; supported: v1"}
```

`400` rather than `404` matches the precedent already set by
`RecommendationGatewayService`, which returns `400` listing the valid `strategy`
values. It also lets a client distinguish "wrong version" from "no such route".
`gatewayError` already sets `Cache-Control: no-store`, so CloudFront cannot pin the
rejection.

## 2. Wiring — three call sites

All three already read `ctx.path()`; each normalizes on entry and uses the normalized
path from then on.

**`GatewayProxyService.serve`** — the catch-all, and the whole of the route table:

```java
ApiVersion apiVersion = ApiVersion.parse(ctx.path());
if (!apiVersion.supported()) {
    return gatewayError(HttpStatus.BAD_REQUEST, "unsupported API version: v"
            + apiVersion.version() + "; supported: v1");
}
String path = apiVersion.path();
// auth, routeTable.match, route.rewrite all take `path` from here
```

**`RecommendationGatewayService.serve`** — same normalization. Because the canonical
endpoint is registered as an exact Armeria route rather than through the route table,
`MicroserviceGatewayServer` also registers the versioned twin:

```java
sb.service("/api/recommend", recommendationService);
sb.service("/api/v1/recommend", recommendationService);
```

**`registerLlmRoutes`** — a versioned twin prefix per LLM route. This is required, not
cosmetic: LLM routes are filtered out of `proxyRoutes`, so `/api/v1/llm/…` would
otherwise reach the catch-all, fail to match any proxy route, and `404`.

Nothing in `MicroserviceRoute` or `MicroserviceRouteTable` changes.

## 3. Normalization precedes authorization

This is the load-bearing ordering property.

`/api/v1/users` is normalized to `/api/users` **before** `authenticator.check` runs.
Therefore:

- `PROTECTED_PREFIXES` (`/api/catalog/user`, `/api/users`) keeps working with **no new
  entries**. A version segment cannot be used to slip past the never-public guard.
- `GATEWAY_PUBLIC_PATHS` keeps working with no new entries, so
  `/api/v1/catalog/item` is public exactly because `/api/catalog/item` is. This
  matters because CloudFront drops `Authorization` on the versioned behavior too — if
  the gateway did not treat it as public, every edge-cached versioned read would
  `401`.
- Rate-limit keys (`route|principal`) and circuit-breaker names are unchanged, because
  route matching still sees `/api/users`.

Had the version instead been registered as route-table prefixes, every one of those
lists would need a versioned twin, and a missed entry would be a silent auth bypass —
the exact failure mode `CLAUDE.md` already warns about for `/api/catalog`.

## 4. `ApiDeprecationDecorator`

One server-wide Armeria decorator, registered in `MicroserviceGatewayServer`. It reads
the **original** `ctx.path()` — not the normalized one — and adds response headers when
either deprecation class applies:

- **Unversioned spelling** — the path is under `/api` with no explicit version segment.
- **Backend-oriented alias route** — the path is under `/api/catalog`, `/api/model`, or
  `/api/online`. These stay deprecated even when spelled `/api/v1/catalog/…`, because
  the reason is different: they duplicate `/api/movies`, `/api/recommend`, and friends.

`/health` and `/metrics` are exempt, reusing the same exemption rule as
`GatewayOriginSecret` — a probe or a scrape should not carry a deprecation contract.

Headers added:

```
Deprecation: true
Sunset: Tue, 27 Jul 2027 00:00:00 GMT
Link: </api/v1/catalog/item>; rel="successor-version"
```

`Sunset` is RFC 8594 (HTTP-date). `Deprecation: true` is preferred over RFC 9745's
structured-field date form: it needs one configuration knob instead of two, and the
date a client must act on is `Sunset`.

**`Link` is emitted only for the unversioned-spelling class**, where the successor is
mechanically derivable: the same path with `/api/v1` substituted for `/api`. Request
and response bodies are identical between the two spellings, so
`rel="successor-version"` (RFC 5829) is exactly true.

It is **not** emitted for the alias-route class. `/api/catalog` and `/api/movies` are
not equivalent mappings — they strip to different backend paths — so there is no
mechanical successor for `/api/v1/catalog/item`, and advertising one would send
clients to a route that behaves differently. An already-versioned alias such as
`/api/v1/catalog/item` therefore carries `Deprecation` and `Sunset` only; the
alias-to-canonical mapping is explained in prose in the policy document, because it
requires judgment rather than a rewrite rule.

The decorator never alters status or body — only adds headers — so it cannot break an
existing response contract.

**Configuration.** A single `GATEWAY_DEPRECATION_SUNSET` (ISO-8601 date). When unset,
the decorator is a no-op and emits nothing. That is deliberate: the policy in §5 says a
`Sunset` date is set when deprecation is announced, so emitting `Deprecation` without a
date would be exactly the half-promise the 2026-07-10 spec refused to make.
`k8s/base/configmap.yaml` sets `2027-07-27` — twelve months' notice.

## 5. Compatibility policy

New client-facing document `docs/api-compatibility-policy.md`, alongside
`CONFIG_GUIDE.md`, linked from `README.md` and `09_API_Gateway.md`.

**Breaking vs additive.** Additive: a new optional request field, a new response field,
a new route, a new enum value in a field documented as open. Breaking: removing or
renaming a response field, tightening validation on an existing field, changing a
status code for an unchanged condition, changing default behavior, or removing a route.

**Support window.** Two versions are supported concurrently — N and N−1. A third is
never promised.

**Notice.** At least twelve months between announcing a deprecation and removing the
deprecated spelling. `Sunset` is set when the deprecation is announced, never later.

**Removal.** Always an explicit pull request. Nothing in the gateway auto-expires a
route, and `Sunset` passing does not by itself change behavior. The reason is stated
plainly in the document: this repository has no client inventory, so it cannot safely
`410` callers it cannot enumerate. An enforcing sunset would be a scheduled outage for
whoever ignored the header.

**Client guidance.** How to detect deprecation (the three headers above), what
`successor-version` points at, and that migrating is a path change only — request and
response bodies are identical between `/api/foo` and `/api/v1/foo`.

## 6. Edge and configuration changes

| File | Change |
|---|---|
| `scripts/create-cdn-distribution.sh` | Cache behaviors for `/api/v1/catalog/item*` and `/api/v1/catalog/similar*`, reusing the existing `recsys-item` / `recsys-similar` cache policies verbatim |
| `docker-compose.cdn.yml` | Mirror the two locations in the local nginx stand-in |
| `k8s/base/configmap.yaml` | `GATEWAY_DEPRECATION_SUNSET: "2027-07-27"` |
| `CONFIG_GUIDE.md` | Document the new variable |

`GATEWAY_PUBLIC_PATHS` is unchanged — the payoff from §3.

**Rollout order.** Gateway first, distribution second:

1. Ship the gateway. Both spellings work; unversioned traffic still matches the
   existing cache behaviors, so nothing regresses.
2. Add the two distribution behaviors. Versioned catalog reads become cacheable.
3. Announce the policy. Clients migrate at their own pace.
4. Removal, if ever, is a separate PR after the sunset date.

The reverse order is dangerous: CloudFront would drop `Authorization` on a versioned
path the gateway had not yet learned to normalize, producing `401` on a cached
behavior. This mirrors the ordering rule already recorded in
[12_CDNS sharp edge 6](../../system_design/12_CDNS.md#sharp-edges--notes).

## 7. Documentation changes

- `09_API_Gateway.md` — rewrite the "API versioning and deprecation" subsection to
  describe the shipped design, and state the rule explicitly: **edge paths carry API
  versions; internal paths carry pipeline names.** `/v2/recommend` is the v2 *pipeline*,
  not API v2.
- Route-registration comments at the three internal `/v2/…` sites (6010, 7010, 8080)
  naming them pipeline routes, so the next reader does not have to infer it.
- `12_CDNS.md` — add the versioned paths to the cache-behavior table and update sharp
  edge 6, which currently predicts this work.
- `20_AuthN_AuthZ.md` — record that normalization precedes authorization and why
  `PROTECTED_PREFIXES` needs no versioned entries.
- `README.md` — link the compatibility policy from the documentation map.

## 8. Testing

**`ApiVersionTest`** (new, pure unit) — the full parse table in §1, including
`/api/version/x`, `/api/v/x`, `/api/v99999/x`, `/api/v1`, bare `/api`, non-`/api`
paths, and the unsupported-version case.

**`ApiDeprecationDecoratorTest`** (new) — headers present on an unversioned path,
absent on a versioned one, present on `/api/v1/catalog` via the alias-route class,
absent on `/health` and `/metrics`, no-op when `GATEWAY_DEPRECATION_SUNSET` is unset,
correct `successor-version` computation for the unversioned class, and **no `Link` at
all** for the alias-route class.

**`GatewayServerIntegrationTest`** (extend) — `/api/v1/users` proxies identically to
`/api/users`; `/api/v2/users` returns `400`; `/api/v1/recommend` behaves like
`/api/recommend` including strategy dispatch; deprecation headers appear on the
unversioned spelling and not the versioned one.

**The security regression test.** `/api/v1/catalog/user` and `/api/v1/users` must
return `401` without credentials. If normalization is ever moved after the auth check,
this is the test that fails. It belongs in `GatewayAuthenticatorTest` alongside the
existing never-public cases.

## Risks

- **A route added later that bypasses `GatewayProxyService`** would not normalize, so
  its versioned spelling would 404 and its unversioned spelling would skip the
  deprecation headers. Mitigated by the decorator being server-wide and by the
  integration test covering all three entry points; not fully preventable without an
  ArchUnit-style guard, which this repository does not have.
- **Clients that hardcode the unversioned path** are unaffected on day one and for at
  least twelve months. That is the point of the policy.
- **CloudFront drift.** The distribution is script-managed with no IaC, so the two new
  behaviors can be lost in a rebuild. The runbook and the script are the source of
  truth; this risk is pre-existing and unchanged by this design.
