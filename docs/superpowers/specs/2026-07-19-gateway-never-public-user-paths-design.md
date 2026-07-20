# Gateway "never-public" path guard — design

**Date:** 2026-07-19
**Status:** Approved (design)
**Branch:** `fix/gateway-never-public-user-paths` (stacked on `fix/gateway-auth-fail-closed`)

## Problem

An AuthN/AuthZ audit flagged `/api/catalog/user` and `/api/users/*` as an IDOR
(any caller reads any `userId`). After clarifying the trust model, these endpoints are a
**trusted client/service lookup**: authenticated backends (e.g. the recommendation pipeline)
legitimately read any `userId` to assemble features, and the returned data is MovieLens
reference data (`record User(int userId, String name)`), not the caller's own account/PII.

Under that model there is no per-user ownership to enforce. The requirement collapses to:

1. **Must be authenticated** — already satisfied: these paths are not in `GATEWAY_PUBLIC_PATHS`,
   and `fix/gateway-auth-fail-closed` guarantees the gateway is authenticated in prod.
2. **Must never become anonymous by accident** — currently only enforced by a comment.

The residual risk is (2): `GatewayAuthenticator.isPublic()` matches public paths by
prefix-with-boundary (`path.equals(p) || path.startsWith(p + "/")`). A single misconfiguration —
setting `GATEWAY_PUBLIC_PATHS` to the bare prefix `/api/catalog` instead of the exact read
paths — silently exposes `/api/catalog/user` anonymously. The existing test
`check_prefixPublicPath_dangerouslyExposesUserRoute` *documents* this trap rather than
preventing it.

## Goal

Make the trap unreachable: a hardcoded set of protected path prefixes that can **never** be
treated as public, regardless of `GATEWAY_PUBLIC_PATHS`. This is defense-in-depth so a
configmap typo cannot re-open user-data reads to anonymous callers.

## Non-goals

- No per-user ownership check / `sub → userId` identity map (wrong model per the clarified
  trust model; no mapping source exists).
- No change to which routes exist or where they proxy.
- No k8s change — the shipped `GATEWAY_PUBLIC_PATHS` already uses correct exact paths; this
  guarantees it stays safe.

## Design

In `GatewayAuthenticator` (`src/main/java/com/recsys/application/gateway/GatewayAuthenticator.java`):

- Add `private static final Set<String> PROTECTED_PREFIXES = Set.of("/api/catalog/user", "/api/users")`.
  These are the user-data read paths; matching uses the same boundary rule as `isPublic`
  (exact match or `prefix + "/"`), so `/api/users/user?userId=1` and `/api/catalog/user?...`
  are both covered but unrelated paths like `/api/usersettings` are not.
- `isPublic(path)` returns `false` whenever the path matches a protected prefix, evaluated
  **before** the configured-public-paths check. A protected path therefore always falls through
  to credential verification (401 without a valid credential), even if it appears in
  `GATEWAY_PUBLIC_PATHS`.
- At construction (in the `fromEnvironment` factory, where `publicPaths` is known), log one
  `WARN` if any configured public path would have exposed a protected prefix — i.e. the
  configured value equals or is a bare-prefix parent of a protected prefix. This surfaces the
  misconfiguration to operators instead of silently overriding it. The override still applies
  regardless of the log.

### Why request-time override, not startup failure

Failing startup on overlap was considered but rejected: it couples correctness to exact string
forms and a gap in the startup check would re-open the hole. Request-time enforcement in
`isPublic` is the robust core (fails safe by default); the startup WARN adds visibility without
being load-bearing.

## Testing

- Flip `check_prefixPublicPath_dangerouslyExposesUserRoute`: with `GATEWAY_PUBLIC_PATHS=/api/catalog`,
  `/api/catalog/user` is now **rejected** (rename to reflect it's a guard, not a trap).
- Add: `/api/catalog/user` and `/api/users/profile` rejected even when listed **explicitly** in
  the public set.
- Regression: the legitimate public reads (`/api/catalog/item`, `/api/catalog/similar`) and
  `/health` still pass; an authenticated caller still reaches `/api/catalog/user`.

## Scope

`GatewayAuthenticator.java` + `GatewayAuthenticatorTest.java` only. One focused PR, stacked on
`fix/gateway-auth-fail-closed`.
