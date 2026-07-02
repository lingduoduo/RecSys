# Gateway Cognito JWT Authentication — Design

**Date:** 2026-07-02
**Status:** Approved (pending spec review)
**Scope:** `com.recsys.application.gateway` (extend `GatewayAuthenticator`, add
`CognitoJwtVerifier` + `CognitoConfig`), `k8s/base/configmap.yaml` (env), and
tests. No CI (repo has none). Salvages the dependency-free JWT verifier from the
retired `feature/aws-saga-tcc-orchestration` branch.

## Goal

Add Cognito JWT authentication to the Armeria API gateway so a request is
authorized if it presents **either** a valid static API key **or** a valid
Cognito-issued JWT. Backward-compatible: existing API-key callers keep working,
JWT is added alongside and is off by default until configured.

## Context

- Main's gateway (`com.recsys.api.gateway.MicroserviceGatewayServer`) is Armeria.
  Auth is a single `GatewayAuthenticator`
  ([`application/gateway/GatewayAuthenticator.java`](../../../src/main/java/com/recsys/application/gateway/GatewayAuthenticator.java)):
  a static API-key checker with `check(RequestHeaders, path) → HttpResponse`
  (returns `null` to allow, a 401 `HttpResponse` to reject), public-path bypass,
  and constant-time key comparison. `isEnabled()` is true when API keys are set.
- Both proxy services already invoke it and short-circuit on rejection:
  `GatewayProxyService.java:82` and `LlmProxyService.java:111` call
  `authenticator.check(req.headers(), path)`. **These call sites do not change.**
- The retired branch carried a self-contained Cognito JWT verifier
  (`CognitoJwtAuthenticator`) with **zero external dependencies** — RS256
  verification via JDK crypto (`java.security.Signature`, `RSAPublicKey`) plus
  Jackson (both already in the project), JWKS fetch + 5-minute cache, and claims
  validation. Its `verify(String token)` core is transport-agnostic; only a
  ~10-line `authenticate(HttpServletRequest,…)` shim was Jetty-coupled (dropped
  here). Its 118-line test drives the core via a `StaticJwkProvider` with no
  servlet/network mocking, so it ports almost verbatim.

## Design

### Component 1 — `CognitoJwtVerifier` (new; salvaged core)

A standalone verifier in `com.recsys.application.gateway`, repackaged from the
branch's `CognitoJwtAuthenticator` internals (the `authenticate(HttpServletRequest,…)`
shim is NOT carried over).

- `VerifiedClaims verify(String token)` — splits the JWT, requires `alg = RS256`
  and a `kid`, resolves the public key via a `JwkProvider`, verifies the
  signature over `header.payload`, validates claims, and returns
  `VerifiedClaims(subject, clientId, tokenUse)`. Throws a typed
  `JwtAuthException(int status, String message)` on any failure.
- **Claim validation** (`validateClaims`): issuer equals the configured issuer;
  audience/`client_id` matches the configured audience; `token_use` matches the
  configured value; `exp`/`nbf`/`iat` within a 60-second clock-skew allowance.
- **`JwkProvider` interface** with two implementations:
  - `StaticJwkProvider(Map<String,PublicKey>)` — for tests.
  - `HttpJwkProvider` — fetches JWKS from the configured URI via
    `java.net.http.HttpClient` (2s timeout), parses RSA `n`/`e` into
    `RSAPublicKey`, caches keys for 5 minutes (`volatile` snapshot + expiry).
- **`CognitoConfig`** — value type holding `issuer`, `audience`, `tokenUse`
  (default `access`), and the derived JWKS URI (`<issuer>/.well-known/jwks.json`).
  Built from env; a blank issuer means "Cognito disabled".
- Dependencies: JDK + Jackson only. No new Maven dependencies.

### Component 2 — `GatewayAuthenticator` (modify existing)

Keep the public seam `check(RequestHeaders, path) → HttpResponse` and the
existing 401 body/`WWW-Authenticate: Bearer` shape unchanged. Extend the
internals to an **accept-either** model:

- Add an optional `CognitoJwtVerifier` field (present only when Cognito is
  configured).
- `check(...)` logic:
  1. If not enabled or the path is public → allow (`return null`).
  2. Extract the `x-api-key` header and the bearer token from `Authorization`.
  3. If an API key is present and constant-time-matches a configured key → allow.
  4. Else, if a bearer token is present and a `CognitoJwtVerifier` is configured
     and `verify(token)` succeeds → allow.
  5. Otherwise → return the existing 401 `HttpResponse`.
- `isEnabled()` = API keys configured **OR** Cognito configured.
- `fromEnvironment(EnvReader)` builds the API-key set (as today) and, if a
  Cognito issuer is configured, a `CognitoJwtVerifier` with an `HttpJwkProvider`.
  If neither is configured, returns `disabled()`.

Rejection maps the verifier's `JwtAuthException` to the same 401 JSON response
the authenticator already emits (no new response format).

### Component 3 — Configuration

Add to [`k8s/base/configmap.yaml`](../../../k8s/base/configmap.yaml) with **blank
defaults** (blank ⇒ feature disabled, so behavior is unchanged until set):

- `GATEWAY_COGNITO_ISSUER` (e.g. `https://cognito-idp.<region>.amazonaws.com/<pool-id>`)
- `GATEWAY_COGNITO_AUDIENCE` (expected `aud`/`client_id`)
- `GATEWAY_COGNITO_TOKEN_USE` (default `access`)

Env-var naming follows the existing `GATEWAY_*` convention (alongside
`GATEWAY_API_KEYS`, `GATEWAY_PUBLIC_PATHS`).

### Component 4 — Tests

- `CognitoJwtVerifierTest` — salvaged from the branch's test (adapted to the new
  class name/package). Uses `StaticJwkProvider` and an in-test RSA keypair to mint
  tokens: valid token → allowed claims; expired → 401; wrong audience → 401; bad
  signature → 401; non-RS256 `alg` → 401; missing `kid` → 401.
- `GatewayAuthenticatorTest` — accept-either behavior with a `StaticJwkProvider`
  wired in: valid API key allowed; valid JWT allowed; neither present → 401;
  public path bypassed; fully disabled → passthrough. (Constructed via a
  test-visible factory/constructor that injects a `CognitoJwtVerifier` with a
  static provider, avoiding live JWKS fetches.)

## Scope Boundaries

**Phase 1 (this spec): authentication decision only** — allow/deny. The verifier
returns claims internally, but the authenticator's public result stays
`HttpResponse` (allow=null / 401).

**Deferred to a phase-2 spec:**
- `GatewayPrincipal`/`GatewayAuthResult` value types and forwarding
  `X-Authenticated-Subject`/`-Client-Id`/`-Token-Use` headers downstream.
- Per-principal rate limiting (main's `GatewayRateLimiter` keys by route today).
  Keeping `check() → HttpResponse` now avoids a premature seam change.

**Out of scope:**
- The branch's `waf-api-gateway-ingress.yaml`. WAF is edge/ALB protection (regex
  and rate rules), a different layer from JWT verification. Follow-up if wanted.
- No new Maven dependencies; no changes to the proxy-service call sites.

## Error Handling

- Any verification failure → typed `JwtAuthException(status, message)` → mapped to
  the authenticator's existing 401 JSON response.
- JWKS fetch failure → fail closed (401). The 5-minute key cache bounds fetch
  frequency; a fetch is only attempted on a cache miss/expiry.
- Only `RS256` is accepted; other algorithms are rejected (no `alg`-confusion /
  `none` acceptance).
- Clock-skew allowance of 60 seconds on time-based claims.

## Verification

- `mvn test -Dtest=CognitoJwtVerifierTest,GatewayAuthenticatorTest` — new and
  salvaged unit tests pass.
- `mvn test` — full suite green (no regression; no new dependencies).
- `kubectl kustomize k8s/base` renders with the three new (blank-default)
  `GATEWAY_COGNITO_*` keys; behavior is unchanged when they are blank.

## Cross-cutting

- All Java changes live in `com.recsys.application.gateway`; config in
  `k8s/base/configmap.yaml`. No app call-site or transport changes.
- Backward-compatible: with no `GATEWAY_COGNITO_*` set, the gateway behaves
  exactly as today (API-key or disabled).
- One commit per task in the implementation plan; work on a feature branch, PR to
  `main` (never commit to `main` directly).
