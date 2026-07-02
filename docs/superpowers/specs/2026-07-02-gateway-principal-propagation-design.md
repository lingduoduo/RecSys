# Gateway Principal Propagation & Per-Principal Rate Limiting — Design

**Date:** 2026-07-02
**Status:** Approved (pending spec review)
**Scope:** `com.recsys.application.gateway` (`GatewayAuthenticator` seam change,
new `GatewayPrincipal` + `GatewayAuthResult`, both proxy services) and
`com.recsys.ratelimit.GatewayRateLimiter`. No new Maven dependencies (Caffeine
is already present). Phase 2 of the gateway Cognito JWT auth work.

## Goal

Turn the gateway's authentication decision into a usable identity: propagate the
authenticated principal downstream as `X-Authenticated-*` headers, and rate-limit
per principal (per user) instead of only per route. Builds directly on phase 1
(`CognitoJwtVerifier` + accept-either `GatewayAuthenticator`).

## Context

- Phase 1 left `GatewayAuthenticator.check(RequestHeaders, path) → HttpResponse`
  (null = allow, 401 = reject). It already knows whether an API key matched or a
  JWT verified — and `CognitoJwtVerifier.verify()` already extracts
  `subject`/`clientId`/`tokenUse` — but that identity is discarded. Phase 1
  deliberately deferred the seam change to here.
- `GatewayRateLimiter` keys one `TokenBucket` per route
  (`tryAcquire(String routeName)`); it has no notion of caller identity, so one
  abusive caller consumes a route's whole budget.
- Integration points are inline in each proxy service's `serve(...)`:
  - `GatewayProxyService.java`: `authenticator.check(...)` (line ~82),
    `rateLimiter.tryAcquire(route.name())` (line ~90), and a static
    `buildUpstreamHeaders(incoming, targetPath, ctx)` (line ~137) that copies
    request headers to the upstream.
  - `LlmProxyService.java`: the same `check(...)` + upstream-header pattern; its
    rate limiting is a separate token-budget limiter (`LlmTokenRateLimiter`).
- **Caffeine is already a dependency** (used on the embedding hot path), so
  bounded per-principal bucket caches are free.

## Design (Approach A: result-object seam + per-(route,principal) buckets)

### Component 1 — `GatewayPrincipal` (new value type)

`record GatewayPrincipal(String subject, String clientId, String tokenUse, String rateLimitKey)`
in `com.recsys.application.gateway`. Factories:

- `ofJwt(CognitoJwtVerifier.VerifiedClaims claims)` — `subject`/`clientId`/
  `tokenUse` from the claims; `rateLimitKey` = `"user:" + subject`, or
  `"client:" + clientId` when `subject` is blank.
- `ofApiKey(String matchedKey)` — `clientId = "service"`, `subject`/`tokenUse`
  blank; `rateLimitKey = "apikey:" + sha256Hex(matchedKey).substring(0, 12)`
  (never the raw key).
- `anonymous()` — `rateLimitKey = "anonymous"`, all identity fields blank; used
  for public paths and when auth is disabled.

Method `java.util.Map<String,String> identityHeaders()` returns the
`X-Authenticated-*` headers to inject: for a JWT principal
`X-Authenticated-Subject`, `X-Authenticated-Client-Id`, `X-Authenticated-Token-Use`
(only non-blank values); for an API-key principal `X-Authenticated-Client-Id: service`;
for anonymous, an empty map.

### Component 2 — `GatewayAuthResult` (new)

`{ HttpResponse rejection; GatewayPrincipal principal }` with
`static allowed(GatewayPrincipal)`, `static rejected(HttpResponse)`, and
`boolean rejected()`. When `rejected()` is false, `principal()` is the
authenticated principal (possibly `anonymous()`).

### Component 3 — `GatewayAuthenticator.check()` seam change

Change the return type from `HttpResponse` to `GatewayAuthResult`:

- Not enabled or public path → `allowed(anonymous())`.
- API key matches (existing constant-time check, still accepting `x-api-key` or
  `Authorization: Bearer <key>`) → `allowed(ofApiKey(matchedKey))`.
- Else a bearer JWT verifies → `allowed(ofJwt(claims))`. The internal helper
  changes from `boolean jwtAccepts(token)` to one returning the
  `VerifiedClaims` (or null), so the claims reach the principal.
- Otherwise → `rejected(<the existing 401 HttpResponse>)`.

The 401 body/`WWW-Authenticate` shape is unchanged. The strict-superset
backward-compat property is preserved (any credential accepted in phase 1 is
still accepted).

### Component 4 — `GatewayRateLimiter` per-(route, principal)

Add `TokenBucket.Decision tryAcquire(String routeName, String principalKey)`.
Buckets live in a bounded Caffeine `Cache<String, TokenBucket>` keyed
`routeName + "|" + principalKey`; each bucket uses **that route's existing
configured rate/burst** (so operator config is unchanged — the same limit now
applies *per principal* on that route). The cache is bounded by `maximumSize`
(default 100_000, overridable via env `GATEWAY_RL_MAX_PRINCIPALS`) and
`expireAfterAccess` (default 1 hour) to bound memory. Routes with no configured
limit return `TokenBucket.Decision.unlimited()` as today. Anonymous requests all
share the `anonymous` bucket per route.

The existing `tryAcquire(String routeName)` is removed; its sole caller
(`GatewayProxyService`) switches to the two-arg form. (If any test references the
one-arg method, it is updated to pass a principal key.)

### Component 5 — Identity header handling in both proxies

In each proxy's upstream-header builder:

1. **Strip** any inbound header whose name starts with `x-authenticated-`
   (case-insensitive) — a client must never be able to spoof identity.
2. **Inject** `principal.identityHeaders()`.

`buildUpstreamHeaders` gains a `GatewayPrincipal` parameter. This applies to both
`GatewayProxyService` and `LlmProxyService`.

### Data flow (`GatewayProxyService.serve`)

`GatewayAuthResult r = authenticator.check(req.headers(), path)` → if
`r.rejected()` return `r.rejection()`; else `GatewayPrincipal p = r.principal()`
→ match route → `rateLimiter.tryAcquire(route.name(), p.rateLimitKey())` → build
upstream headers with `p` (strip + inject) → proxy. `LlmProxyService.serve`
applies the same seam change and header handling; its `LlmTokenRateLimiter` is
unchanged.

## Error Handling

- Rejected auth → unchanged 401. Rate-limited → unchanged 429 with
  `Retry-After` / `x-ratelimit-*`.
- A blank `subject` and blank `clientId` on a verified JWT (should not happen for
  Cognito) → `rateLimitKey` falls back to `"anonymous"`; the request is still
  authorized (it verified), just not individually keyed.
- Caffeine cache eviction only drops idle buckets; an evicted principal simply
  starts with a full bucket on its next request (fail-open on eviction is
  acceptable — eviction requires `expireAfterAccess` idleness).

## Testing

- `GatewayPrincipalTest` — `rateLimitKey` derivation (jwt `sub`; jwt fallback to
  `client:` when sub blank; `apikey:` hash is stable and not the raw key);
  `identityHeaders()` content per principal kind (jwt full set, api-key
  `service` only, anonymous empty).
- `GatewayAuthenticatorTest` (extend) — `check()` returns `allowed` with the
  right principal for api-key and jwt, `rejected` (401) for neither, `anonymous`
  for public path and for disabled.
- `GatewayRateLimiterTest` — per-(route,principal) isolation: principal A
  exhausts its bucket on route R while principal B on R is still allowed; the same
  principal on routes R1 and R2 has independent budgets; unconfigured route →
  unlimited.
- Header handling — inbound `X-Authenticated-Subject` (spoof) is stripped; a JWT
  principal's headers are injected. Exercised at the header-builder level (made
  package-visible if needed) with a `GatewayPrincipal`.

## Out of Scope (YAGNI / later specs)

- Per-principal **LLM token** budgets (`LlmTokenRateLimiter` stays route/global).
- Redis-distributed per-principal buckets (in-process Caffeine only, matching the
  existing in-process limiter).
- serve-stale-JWKS refinement and WAF ingress — their own follow-up specs.
- `GatewayPrincipal` authorization/roles (scopes/groups) — this phase carries
  identity only, not authorization decisions.

## Cross-cutting

- No new Maven dependencies. All Java in `com.recsys.application.gateway` +
  `com.recsys.ratelimit`.
- Backward-compatible auth behavior: with Cognito unconfigured and API keys as
  before, requests authorize exactly as in phase 1; identity headers for API-key
  callers are `X-Authenticated-Client-Id: service`.
- One commit per implementation task; feature branch, PR to `main` (never commit
  to `main` directly).
