# Gateway JWKS Serve-Stale Hardening — Design

**Date:** 2026-07-02
**Status:** Approved (pending spec review)
**Scope:** `com.recsys.application.gateway.CognitoJwtVerifier` (the `HttpJwkProvider`
inner class) + a new unit test. No new Maven dependencies. Follow-up hardening
to the gateway Cognito JWT auth work (phases 1–2).

## Goal

Keep authenticating valid tokens during a transient Cognito/JWKS outage. Today,
when the JWKS cache is expired and the fetch fails, the gateway rejects every
token — even tokens whose signing key it still holds in the (now-stale) cache.
Serve the last-good cached key during an outage instead of failing closed on
availability.

## Context

- `HttpJwkProvider.key(kid)` in
  [`CognitoJwtVerifier.java`](../../../src/main/java/com/recsys/application/gateway/CognitoJwtVerifier.java)
  refetches the JWKS when the cache is expired (5-min TTL) or on an unknown kid
  (rate-limited to once per 30s). On a successful fetch it replaces `cachedKeys`;
  on a **failed** fetch it throws `JwtAuthException(503)`, which propagates out of
  `verify()` → the authenticator's `jwtVerify` maps it to a 401. So a JWKS outage
  rejects all JWT-authed requests, including ones whose signing key is already
  cached (just past its TTL).
- The JWT's own `exp`/`nbf`/`iss`/`aud`/`token_use` are validated separately in
  `validateClaims`. Serving a stale signing key therefore only extends
  *signing-key* freshness, never token lifetime — a small, contained surface.
  Cognito rotates signing keys infrequently and overlaps old+new, so a cached key
  is valid well beyond the 5-min JWKS TTL.
- Phase-1's JWKS hardening (`AtomicBoolean` single-flight + unknown-kid backoff)
  shipped *inspection-only*: `HttpJwkProvider` builds its own `HttpClient` call and
  the project has no mock library (and adds none), so the fetch/cache logic had no
  unit test. This design adds a testability seam so the new behavior is covered.

## Design

### Behavior change — serve-stale on fetch failure (unbounded during outage + retry backoff)

In `HttpJwkProvider.key(kid)`, wrap the fetch in a `try/catch (JwtAuthException)`:

- **Success (unchanged):** replace `cachedKeys` with the fresh map, set
  `expiresAtMillis = now + JWKS_CACHE_TTL`, take the key from the fresh map.
- **Fetch failure:**
  - If the requested `kid` **is** in the stale `cachedKeys` → keep serving it: do
    not clear the cache, set `expiresAtMillis = now + STALE_RETRY_BACKOFF` (30s) so
    at most one fetch is attempted per 30s during the outage (stale served in
    between), and log one WARN. Return the stale key.
  - If the `kid` is **not** cached → rethrow the fetch `JwtAuthException` (an
    unknown kid with no cached key is genuinely unverifiable → 401).

Add `private static final Duration STALE_RETRY_BACKOFF = Duration.ofSeconds(30);`
and an slf4j logger (`LoggerFactory.getLogger(CognitoJwtVerifier.class)`; the class
has none today). The WARN fires at most ~once per 30s (bounded by the backoff),
so no log spam during a long outage.

There is **no** grace-window cap: stale keys are served for as long as the outage
lasts, and the cache self-heals on the next successful fetch. (Token `exp` bounds
actual access; Cognito key rotation is slow/overlapping.)

### Testability seam — `KeyFetcher` + injectable clock

To unit-test the cache/stale policy without a mock library or an `HttpClient` stub,
separate *how to fetch* from *cache/stale policy*:

- Add a nested `@FunctionalInterface KeyFetcher { Map<String,PublicKey> fetch(); }`
  (package-visible) — `fetch()` returns the parsed JWKS map or throws
  `JwtAuthException` on failure.
- Make `HttpJwkProvider` package-visible with a constructor
  `HttpJwkProvider(KeyFetcher fetcher, java.util.function.LongSupplier nowMillis)`.
  The existing `HttpJwkProvider(String issuer, HttpClient httpClient)` constructor
  delegates to it with the real HTTP fetch (the current `fetchKeys`/`parseJwks`/
  `rsaKey` logic, moved to static helpers keyed on `jwksUri`/`httpClient`) and
  `System::currentTimeMillis`.
- `key(kid)` uses `nowMillis.getAsLong()` for expiry/backoff timing and
  `fetcher.fetch()` for the fetch.

This is a refactor of internals only; the verifier's public API
(`CognitoJwtVerifier(config, HttpClient, Clock)`, `verify(String)`,
`StaticJwkProvider`) is unchanged. `StaticJwkProvider` is untouched (it never
fetches, so serve-stale does not apply to it).

## Error Handling

- Fetch failure with a cached kid → serve stale + WARN (not an error to the caller).
- Fetch failure with an uncached kid → `JwtAuthException` propagates → 401 (unchanged).
- The single-flight `AtomicBoolean` and the `finally { refetchInProgress.set(false); }`
  are preserved, so the guard is always released even when the fetch throws.
- Setting `expiresAtMillis` forward on failure bounds the retry rate to once per
  `STALE_RETRY_BACKOFF`, so a down JWKS endpoint is not hammered.

## Testing

New `CognitoJwtVerifierJwksTest` drives `HttpJwkProvider` directly via the
`KeyFetcher` + `LongSupplier nowMillis` seam (a controllable fetcher that can
return a map or throw, and an `AtomicLong` clock):

- **Serve-stale:** fetch #1 populates the cache; advance the clock past the 5-min
  TTL; flip the fetcher to throw → a known `kid` is still returned (no throw).
- **Unknown kid during outage:** failing fetcher + uncached kid → throws
  `JwtAuthException`.
- **Retry backoff:** after a stale-serve at time T, a call at T+10s does not invoke
  the fetcher again (assert the fetch-count is unchanged); a call after the 30s
  backoff does refetch.
- **Happy path unchanged:** a call within the 5-min TTL after a successful fetch
  serves from cache without refetching.

The existing `CognitoJwtVerifierTest` (StaticJwkProvider-based) is unaffected.

## Out of Scope (YAGNI)

- No grace-window cap on stale-serving (unbounded during outage was chosen).
- No change to `verify()` or claim validation.
- No new Maven dependency; no change to the authenticator, proxies, or rate limiter.
- WAF ingress remains a separate later spec.

## Cross-cutting

- Single production file (`CognitoJwtVerifier.java`) + one new test.
- Backward-compatible: the public verifier API and the success path are unchanged;
  behavior differs only when a JWKS fetch fails while a usable key is cached.
- One commit for the implementation; feature branch, PR to `main` (never commit to
  `main` directly).
