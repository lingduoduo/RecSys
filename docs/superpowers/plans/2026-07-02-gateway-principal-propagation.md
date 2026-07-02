# Gateway Principal Propagation & Per-Principal Rate Limiting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Carry the authenticated caller identity through the gateway — forward it downstream as `X-Authenticated-*` headers (stripping any spoofed inbound ones) and rate-limit per principal instead of only per route.

**Architecture:** Task 1 adds two pure value types (`GatewayPrincipal`, `GatewayAuthResult`). Task 2 changes `GatewayAuthenticator.check()` to return the principal and wires identity-header strip+inject into both proxy services. Task 3 makes `GatewayRateLimiter` per-(route,principal) via a bounded Caffeine cache. Phase 2 of the gateway Cognito JWT auth work.

**Tech Stack:** Java 17, Armeria, Caffeine (already a dependency), JDK crypto (`MessageDigest`), JUnit 5 + Jupiter Assertions.

## Global Constraints

- All new/changed Java in `com.recsys.application.gateway` and `com.recsys.ratelimit`. **No new Maven dependencies** (Caffeine `com.github.ben-manes.caffeine:caffeine`, package `com.github.benmanes.caffeine.cache`, is already present).
- Backward-compatible auth: any credential accepted before (API key via `x-api-key` or `Authorization: Bearer <key>`; valid JWT) is still accepted; the 401 body/`WWW-Authenticate` shape is unchanged.
- **Anti-spoof is non-negotiable:** every inbound header whose name starts with `x-authenticated-` (case-insensitive) MUST be stripped before forwarding upstream.
- Rate-limit key: JWT → `user:<sub>` (or `client:<clientId>` if sub blank); API key → `apikey:<sha256(key)[:12 hex]>` (never the raw key); public/disabled → `anonymous`.
- Per-(route,principal) buckets reuse each route's existing configured rate/burst; cache bounded by `GATEWAY_RL_MAX_PRINCIPALS` (default 100000) + `expireAfterAccess` 60 min.
- Per-principal rate limiting applies to `GatewayRateLimiter` (standard proxy routes) only; `LlmTokenRateLimiter` is unchanged. Identity forwarding applies to both proxies.
- Test framework: JUnit 5 with `org.junit.jupiter.api.Assertions`.
- One commit per task. Never commit to `main`; work stays on branch `feat/gateway-principal-propagation`.
- Verify per task with `mvn test -Dtest=...` and `mvn test` for the full suite.

---

### Task 1: `GatewayPrincipal` + `GatewayAuthResult` value types

**Files:**
- Create: `src/main/java/com/recsys/application/gateway/GatewayPrincipal.java`
- Create: `src/main/java/com/recsys/application/gateway/GatewayAuthResult.java`
- Test: `src/test/java/com/recsys/application/gateway/GatewayPrincipalTest.java`

**Interfaces:**
- Consumes: `CognitoJwtVerifier.VerifiedClaims` (record `(String subject, String clientId, String tokenUse)`, same package) from phase 1.
- Produces (used by Tasks 2 & 3):
  - `GatewayPrincipal` — record `(String subject, String clientId, String tokenUse, String rateLimitKey)`; statics `anonymous()`, `ofJwt(CognitoJwtVerifier.VerifiedClaims)`, `ofApiKey(String matchedKey)`; instance `Map<String,String> identityHeaders()`.
  - `GatewayAuthResult` — statics `allowed(GatewayPrincipal)`, `rejected(HttpResponse)`; instance `boolean rejected()`, `HttpResponse rejection()`, `GatewayPrincipal principal()`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/application/gateway/GatewayPrincipalTest.java`:

```java
package com.recsys.application.gateway;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayPrincipalTest {

    @Test
    void ofJwt_keysOnSubjectAndEmitsIdentityHeaders() {
        GatewayPrincipal p = GatewayPrincipal.ofJwt(
                new CognitoJwtVerifier.VerifiedClaims("user-1", "app-client", "access"));
        assertEquals("user:user-1", p.rateLimitKey());
        assertEquals(Map.of(
                "x-authenticated-subject", "user-1",
                "x-authenticated-client-id", "app-client",
                "x-authenticated-token-use", "access"), p.identityHeaders());
    }

    @Test
    void ofJwt_fallsBackToClientWhenSubjectBlank() {
        GatewayPrincipal p = GatewayPrincipal.ofJwt(
                new CognitoJwtVerifier.VerifiedClaims("", "app-client", "access"));
        assertEquals("client:app-client", p.rateLimitKey());
    }

    @Test
    void ofApiKey_hashesKeyNeverLeaksIt_andIsStable() {
        GatewayPrincipal p = GatewayPrincipal.ofApiKey("super-secret-key");
        assertTrue(p.rateLimitKey().startsWith("apikey:"));
        assertFalse(p.rateLimitKey().contains("super-secret-key"));
        assertEquals(Map.of("x-authenticated-client-id", "service"), p.identityHeaders());
        assertEquals(p.rateLimitKey(), GatewayPrincipal.ofApiKey("super-secret-key").rateLimitKey());
    }

    @Test
    void anonymous_hasNoIdentityHeaders() {
        GatewayPrincipal p = GatewayPrincipal.anonymous();
        assertEquals("anonymous", p.rateLimitKey());
        assertTrue(p.identityHeaders().isEmpty());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=GatewayPrincipalTest`
Expected: FAIL — compilation error, `GatewayPrincipal` cannot be resolved.

- [ ] **Step 3: Create `GatewayPrincipal.java`**

```java
package com.recsys.application.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The authenticated caller identity carried through the gateway: used to key
 * per-principal rate limiting and to forward identity headers to backends.
 */
public record GatewayPrincipal(String subject, String clientId, String tokenUse, String rateLimitKey) {

    private static final GatewayPrincipal ANONYMOUS = new GatewayPrincipal("", "", "", "anonymous");

    public static GatewayPrincipal anonymous() {
        return ANONYMOUS;
    }

    public static GatewayPrincipal ofJwt(CognitoJwtVerifier.VerifiedClaims claims) {
        String subject = claims.subject() == null ? "" : claims.subject();
        String clientId = claims.clientId() == null ? "" : claims.clientId();
        String tokenUse = claims.tokenUse() == null ? "" : claims.tokenUse();
        String key = !subject.isBlank() ? "user:" + subject
                : !clientId.isBlank() ? "client:" + clientId
                : "anonymous";
        return new GatewayPrincipal(subject, clientId, tokenUse, key);
    }

    public static GatewayPrincipal ofApiKey(String matchedKey) {
        return new GatewayPrincipal("", "service", "", "apikey:" + sha256Prefix(matchedKey));
    }

    /** Identity headers to forward upstream (lowercase names; never the raw credential). */
    public Map<String, String> identityHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        if (!subject.isBlank()) headers.put("x-authenticated-subject", subject);
        if (!clientId.isBlank()) headers.put("x-authenticated-client-id", clientId);
        if (!tokenUse.isBlank()) headers.put("x-authenticated-token-use", tokenUse);
        return headers;
    }

    private static String sha256Prefix(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
```

- [ ] **Step 4: Create `GatewayAuthResult.java`**

```java
package com.recsys.application.gateway;

import com.linecorp.armeria.common.HttpResponse;

/** Outcome of a gateway auth check: either a rejection response, or an allowed principal. */
public final class GatewayAuthResult {
    private final HttpResponse rejection;
    private final GatewayPrincipal principal;

    private GatewayAuthResult(HttpResponse rejection, GatewayPrincipal principal) {
        this.rejection = rejection;
        this.principal = principal;
    }

    public static GatewayAuthResult allowed(GatewayPrincipal principal) {
        return new GatewayAuthResult(null, principal);
    }

    public static GatewayAuthResult rejected(HttpResponse rejection) {
        return new GatewayAuthResult(rejection, null);
    }

    public boolean rejected() {
        return rejection != null;
    }

    public HttpResponse rejection() {
        return rejection;
    }

    public GatewayPrincipal principal() {
        return principal;
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn test -Dtest=GatewayPrincipalTest`
Expected: PASS — `Tests run: 4, Failures: 0, Errors: 0`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/application/gateway/GatewayPrincipal.java \
        src/main/java/com/recsys/application/gateway/GatewayAuthResult.java \
        src/test/java/com/recsys/application/gateway/GatewayPrincipalTest.java
git commit -m "feat(gateway): add GatewayPrincipal + GatewayAuthResult identity types

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Auth seam change + identity-header propagation

**Files:**
- Modify: `src/main/java/com/recsys/application/gateway/GatewayAuthenticator.java` (`check()` returns `GatewayAuthResult`; `jwtAccepts`→`jwtVerify`)
- Modify: `src/main/java/com/recsys/application/gateway/GatewayProxyService.java` (unwrap result; strip+inject in `buildUpstreamHeaders`)
- Modify: `src/main/java/com/recsys/application/gateway/LlmProxyService.java` (same)
- Modify: `src/test/java/com/recsys/application/gateway/GatewayAuthenticatorTest.java` (new return type)

**Interfaces:**
- Consumes: `GatewayPrincipal`, `GatewayAuthResult` (Task 1).
- Produces: `GatewayAuthenticator.check(RequestHeaders, path) → GatewayAuthResult`. The principal reaches `buildUpstreamHeaders(incoming, targetPath, ctx, GatewayPrincipal)` in both proxies. Task 3 uses `principal.rateLimitKey()`.

- [ ] **Step 1: Update the authenticator test for the new return type (failing)**

In `src/test/java/com/recsys/application/gateway/GatewayAuthenticatorTest.java`:

Add these static imports (keep existing ones):
```java
import static org.junit.jupiter.api.Assertions.assertTrue;
```
(`assertFalse`, `assertEquals`, `assertNull`, `assertNotNull`, `assertThrows` are already imported.)

Replace each `assertNull(auth.check(headers, "/model/predict"));` / `assertNull(auth.check(headers, "/health"));` / `assertNull(GatewayAuthenticator.disabled().check(headers, "/model/predict"));` / `assertNull(auth.check(RequestHeaders.of(HttpMethod.GET, "/model/predict"), "/model/predict"));` assertion so it asserts the result is not rejected. Concretely:

- `check_allowsValidApiKeyViaHeader`: change the last line to
  ```java
        assertFalse(auth.check(headers, "/model/predict").rejected());
  ```
- `check_allowsValidCognitoJwt`: change the last line to
  ```java
        GatewayAuthResult r = auth.check(headers, "/model/predict");
        assertFalse(r.rejected());
        assertEquals("user:user-1", r.principal().rateLimitKey());
  ```
- `check_rejectsWhenNeitherCredentialPresent`: replace its body's check lines with
  ```java
        GatewayAuthResult r = auth.check(headers, "/model/predict");
        assertTrue(r.rejected());
        assertEquals(401, r.rejection().aggregate().join().status().code());
  ```
  (remove the old `HttpResponse rejection = ...; assertNotNull(rejection); assertEquals(401, rejection.aggregate()...)` lines.)
- `check_bypassesPublicPath`: change to `assertFalse(auth.check(headers, "/health").rejected());`
- `check_disabledPassesThrough`: change to `assertFalse(GatewayAuthenticator.disabled().check(headers, "/model/predict").rejected());`
- `check_allowsBearerTokenAsApiKey`: change to `assertFalse(auth.check(headers, "/model/predict").rejected());`
- `fromEnvironment_disabledWhenNothingConfigured`: change the check line to
  ```java
        assertFalse(auth.check(RequestHeaders.of(HttpMethod.GET, "/model/predict"), "/model/predict").rejected());
  ```

The unused `import ...assertNull;` / `import ...assertNotNull;` may remain (harmless) or be removed.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -Dtest=GatewayAuthenticatorTest`
Expected: FAIL — compilation error: `check(...)` returns `HttpResponse`, no `.rejected()` method (the production change is not in yet).

- [ ] **Step 3: Change `GatewayAuthenticator.check()` to return `GatewayAuthResult`**

In `src/main/java/com/recsys/application/gateway/GatewayAuthenticator.java`, replace the entire `check(...)` method:

```java
    public HttpResponse check(RequestHeaders headers, String path) {
        if (!isEnabled() || isPublic(path)) return null;

        String bearer = bearerToken(headers.get(HttpHeaderNames.AUTHORIZATION));
        String provided = firstNonBlank(headers.get(HttpHeaderNames.of("x-api-key")), bearer);

        if (provided != null) {
            boolean matched = false;
            for (String key : apiKeys) {
                matched |= constantTimeEquals(key, provided);
            }
            if (matched) return null;
        }

        if (jwtVerifier != null && bearer != null && jwtAccepts(bearer)) {
            return null;
        }

        return HttpResponse.of(
                ResponseHeaders.builder(HttpStatus.UNAUTHORIZED)
                        .set(HttpHeaderNames.WWW_AUTHENTICATE, "Bearer")
                        .contentType(MediaType.JSON_UTF_8)
                        .build(),
                HttpData.ofUtf8("{\"error\":\"missing or invalid gateway API key\"}"));
    }
```

with:

```java
    public GatewayAuthResult check(RequestHeaders headers, String path) {
        if (!isEnabled() || isPublic(path)) {
            return GatewayAuthResult.allowed(GatewayPrincipal.anonymous());
        }

        String bearer = bearerToken(headers.get(HttpHeaderNames.AUTHORIZATION));
        String provided = firstNonBlank(headers.get(HttpHeaderNames.of("x-api-key")), bearer);

        if (provided != null) {
            boolean matched = false;
            for (String key : apiKeys) {
                matched |= constantTimeEquals(key, provided);
            }
            if (matched) {
                return GatewayAuthResult.allowed(GatewayPrincipal.ofApiKey(provided));
            }
        }

        if (jwtVerifier != null && bearer != null) {
            CognitoJwtVerifier.VerifiedClaims claims = jwtVerify(bearer);
            if (claims != null) {
                return GatewayAuthResult.allowed(GatewayPrincipal.ofJwt(claims));
            }
        }

        return GatewayAuthResult.rejected(HttpResponse.of(
                ResponseHeaders.builder(HttpStatus.UNAUTHORIZED)
                        .set(HttpHeaderNames.WWW_AUTHENTICATE, "Bearer")
                        .contentType(MediaType.JSON_UTF_8)
                        .build(),
                HttpData.ofUtf8("{\"error\":\"missing or invalid gateway API key\"}")));
    }
```

Then replace the private helper:

```java
    private boolean jwtAccepts(String token) {
        try {
            jwtVerifier.verify(token);
            return true;
        } catch (CognitoJwtVerifier.JwtAuthException e) {
            return false;
        }
    }
```

with:

```java
    private CognitoJwtVerifier.VerifiedClaims jwtVerify(String token) {
        try {
            return jwtVerifier.verify(token);
        } catch (CognitoJwtVerifier.JwtAuthException e) {
            return null;
        }
    }
```

- [ ] **Step 4: Update `GatewayProxyService` — unwrap the result and strip+inject headers**

In `src/main/java/com/recsys/application/gateway/GatewayProxyService.java`:

(a) Replace the auth-check lines in `serve(...)`:
```java
        HttpResponse authRejection = authenticator.check(req.headers(), path);
        if (authRejection != null) return authRejection;
```
with:
```java
        GatewayAuthResult auth = authenticator.check(req.headers(), path);
        if (auth.rejected()) return auth.rejection();
        GatewayPrincipal principal = auth.principal();
```

(b) Pass the principal to the header builder — change:
```java
                    RequestHeaders upstreamHeaders = buildUpstreamHeaders(aggReq.headers(), targetPath, ctx);
```
to:
```java
                    RequestHeaders upstreamHeaders = buildUpstreamHeaders(aggReq.headers(), targetPath, ctx, principal);
```

(c) Change `buildUpstreamHeaders` to strip inbound `x-authenticated-*` and inject the principal's headers. Replace:
```java
    private static RequestHeaders buildUpstreamHeaders(RequestHeaders incoming, String targetPath,
                                                       ServiceRequestContext ctx) {
        RequestHeadersBuilder b = RequestHeaders.builder(incoming.method(), targetPath);
        incoming.forEach((name, value) -> {
            if (!isHopByHop(name.toString())) b.add(name, value);
        });
        b.set(HttpHeaderNames.of("x-gateway-service"), "recsys-api-gateway");
```
with:
```java
    private static RequestHeaders buildUpstreamHeaders(RequestHeaders incoming, String targetPath,
                                                       ServiceRequestContext ctx, GatewayPrincipal principal) {
        RequestHeadersBuilder b = RequestHeaders.builder(incoming.method(), targetPath);
        incoming.forEach((name, value) -> {
            String n = name.toString();
            // Strip any client-supplied identity header — the gateway is the sole authority.
            if (!isHopByHop(n) && !n.regionMatches(true, 0, "x-authenticated-", 0, 16)) {
                b.add(name, value);
            }
        });
        principal.identityHeaders().forEach((hn, hv) -> b.set(HttpHeaderNames.of(hn), hv));
        b.set(HttpHeaderNames.of("x-gateway-service"), "recsys-api-gateway");
```
(Leave the remainder of the method — `x-forwarded-*` handling and `return b.build();` — unchanged.)

- [ ] **Step 5: Update `LlmProxyService` — same unwrap and strip+inject**

In `src/main/java/com/recsys/application/gateway/LlmProxyService.java`:

(a) Replace the auth-check lines in `serve(...)`:
```java
        HttpResponse authRejection = authenticator.check(req.headers(), path);
        if (authRejection != null) return authRejection;
```
with:
```java
        GatewayAuthResult auth = authenticator.check(req.headers(), path);
        if (auth.rejected()) return auth.rejection();
        GatewayPrincipal principal = auth.principal();
```

(b) Pass the principal to the header builder — change:
```java
                    RequestHeaders upstreamHeaders = buildUpstreamHeaders(
                            aggReq.headers(), targetPath, ctx);
```
to:
```java
                    RequestHeaders upstreamHeaders = buildUpstreamHeaders(
                            aggReq.headers(), targetPath, ctx, principal);
```

(c) Change `buildUpstreamHeaders` (the one near line 287). Replace:
```java
    private static RequestHeaders buildUpstreamHeaders(RequestHeaders incoming, String targetPath,
                                                       ServiceRequestContext ctx) {
        RequestHeadersBuilder b = RequestHeaders.builder(incoming.method(), targetPath);
        incoming.forEach((name, value) -> {
            if (!isHopByHop(name.toString())) b.add(name, value);
        });
        b.set(HttpHeaderNames.of("x-gateway-service"), "recsys-llm-gateway");
```
with:
```java
    private static RequestHeaders buildUpstreamHeaders(RequestHeaders incoming, String targetPath,
                                                       ServiceRequestContext ctx, GatewayPrincipal principal) {
        RequestHeadersBuilder b = RequestHeaders.builder(incoming.method(), targetPath);
        incoming.forEach((name, value) -> {
            String n = name.toString();
            // Strip any client-supplied identity header — the gateway is the sole authority.
            if (!isHopByHop(n) && !n.regionMatches(true, 0, "x-authenticated-", 0, 16)) {
                b.add(name, value);
            }
        });
        principal.identityHeaders().forEach((hn, hv) -> b.set(HttpHeaderNames.of(hn), hv));
        b.set(HttpHeaderNames.of("x-gateway-service"), "recsys-llm-gateway");
```
(Leave the remainder of the method unchanged.)

- [ ] **Step 6: Run the authenticator test, then the full suite**

Run: `mvn test -Dtest=GatewayAuthenticatorTest`
Expected: PASS — `Tests run: 8, Failures: 0, Errors: 0`.

Run: `mvn test`
Expected: full suite `BUILD SUCCESS` (both proxy services compile with the new `buildUpstreamHeaders` signature and the `check()` return type).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/application/gateway/GatewayAuthenticator.java \
        src/main/java/com/recsys/application/gateway/GatewayProxyService.java \
        src/main/java/com/recsys/application/gateway/LlmProxyService.java \
        src/test/java/com/recsys/application/gateway/GatewayAuthenticatorTest.java
git commit -m "feat(gateway): propagate authenticated principal + strip spoofed identity headers

check() now returns GatewayAuthResult carrying the principal; both proxies strip
inbound x-authenticated-* and inject the verified identity (X-Authenticated-Subject/
-Client-Id/-Token-Use) upstream.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Per-(route, principal) rate limiting

**Files:**
- Modify: `src/main/java/com/recsys/ratelimit/GatewayRateLimiter.java` (per-(route,principal) Caffeine buckets)
- Modify: `src/main/java/com/recsys/application/gateway/GatewayProxyService.java` (pass `principal.rateLimitKey()`)
- Modify: `src/test/java/com/recsys/ratelimit/GatewayRateLimiterTest.java` (two-arg `tryAcquire` + isolation tests)

**Interfaces:**
- Consumes: `GatewayPrincipal.rateLimitKey()` (Task 1) via the `principal` local in `GatewayProxyService.serve()` (Task 2).
- Produces: `GatewayRateLimiter.tryAcquire(String routeName, String principalKey) → TokenBucket.Decision`. The one-arg `tryAcquire(routeName)` is removed.

- [ ] **Step 1: Update the rate-limiter test to the two-arg API + add isolation tests (failing)**

In `src/test/java/com/recsys/ratelimit/GatewayRateLimiterTest.java`:

Change every existing `tryAcquire("<route>")` call to `tryAcquire("<route>", "p1")` — specifically all occurrences on lines currently reading `.tryAcquire("catalog")`, `.tryAcquire("model")`, `.tryAcquire("online")` become `.tryAcquire("catalog", "p1")`, `.tryAcquire("model", "p1")`, `.tryAcquire("online", "p1")`.

Then add these two tests inside the class (before the private `route` helper):

```java
    @Test
    void isolatesBucketsPerPrincipalOnSameRoute() {
        AtomicLong now = new AtomicLong(0L);
        GatewayRateLimiter limiter = GatewayRateLimiter.fromEnvironment(
                List.of(route("model")),
                Map.of("GATEWAY_RATE_LIMIT_RPS", "1", "GATEWAY_RATE_LIMIT_BURST", "1")::get,
                now::get);

        assertTrue(limiter.tryAcquire("model", "user:a").allowed());
        assertFalse(limiter.tryAcquire("model", "user:a").allowed());   // A exhausted
        assertTrue(limiter.tryAcquire("model", "user:b").allowed());    // B independent
    }

    @Test
    void samePrincipalIndependentAcrossRoutes() {
        AtomicLong now = new AtomicLong(0L);
        GatewayRateLimiter limiter = GatewayRateLimiter.fromEnvironment(
                List.of(route("model"), route("catalog")),
                Map.of("GATEWAY_RATE_LIMIT_RPS", "1", "GATEWAY_RATE_LIMIT_BURST", "1")::get,
                now::get);

        assertTrue(limiter.tryAcquire("model", "user:a").allowed());
        assertFalse(limiter.tryAcquire("model", "user:a").allowed());    // model exhausted for A
        assertTrue(limiter.tryAcquire("catalog", "user:a").allowed());   // catalog independent for A
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -Dtest=GatewayRateLimiterTest`
Expected: FAIL — compilation error: `tryAcquire(String, String)` does not exist (only the one-arg form).

- [ ] **Step 3: Rewrite `GatewayRateLimiter` for per-(route, principal) buckets**

Replace the entire contents of `src/main/java/com/recsys/ratelimit/GatewayRateLimiter.java` with:

```java
package com.recsys.ratelimit;
import com.recsys.config.EnvVars;
import com.recsys.application.gateway.MicroserviceRoute;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Per-(route, principal) token-bucket rate limiter. Each principal gets the route's
 * configured rate/burst independently; buckets live in a bounded Caffeine cache so a
 * flood of distinct principals cannot exhaust memory.
 */
public final class GatewayRateLimiter {
    private static final GatewayRateLimiter DISABLED =
            new GatewayRateLimiter(Map.of(), () -> 0L, 100_000);

    private record RouteLimit(double rate, int burst) {
    }

    private final Map<String, RouteLimit> routeLimits;
    private final LongSupplier tickerNanos;
    private final Cache<String, TokenBucket> buckets;

    private GatewayRateLimiter(Map<String, RouteLimit> routeLimits, LongSupplier tickerNanos, long maxPrincipals) {
        this.routeLimits = Map.copyOf(routeLimits);
        this.tickerNanos = tickerNanos;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(maxPrincipals)
                .expireAfterAccess(60, TimeUnit.MINUTES)
                .build();
    }

    public static GatewayRateLimiter disabled() {
        return DISABLED;
    }

    public static GatewayRateLimiter fromEnvironment(List<MicroserviceRoute> routes) {
        return fromEnvironment(routes, System::getenv, System::nanoTime);
    }

    public static GatewayRateLimiter fromEnvironment(List<MicroserviceRoute> routes,
                                              EnvVars.EnvReader env,
                                              LongSupplier tickerNanos) {
        Objects.requireNonNull(routes, "routes");
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(tickerNanos, "tickerNanos");

        double defaultRate = EnvVars.readDouble(env, "GATEWAY_RATE_LIMIT_RPS", 0.0);
        int defaultBurst = EnvVars.readInt(env, "GATEWAY_RATE_LIMIT_BURST", 0);
        Map<String, RouteLimit> routeLimits = new HashMap<>();

        for (MicroserviceRoute route : routes) {
            String suffix = route.name().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
            double rate = EnvVars.readDouble(env, "GATEWAY_RATE_LIMIT_" + suffix + "_RPS", defaultRate);
            int burst = EnvVars.readInt(env, "GATEWAY_RATE_LIMIT_" + suffix + "_BURST", defaultBurst);
            if (rate > 0.0 && burst > 0) {
                routeLimits.put(route.name(), new RouteLimit(rate, burst));
            }
        }

        if (routeLimits.isEmpty()) {
            return disabled();
        }
        long maxPrincipals = EnvVars.readInt(env, "GATEWAY_RL_MAX_PRINCIPALS", 100_000);
        return new GatewayRateLimiter(routeLimits, tickerNanos, maxPrincipals);
    }

    /**
     * Consume one token for (routeName, principalKey). Unconfigured routes are unlimited.
     * Each (route, principal) pair gets its own bucket using the route's configured rate/burst.
     */
    public TokenBucket.Decision tryAcquire(String routeName, String principalKey) {
        RouteLimit limit = routeLimits.get(routeName);
        if (limit == null) {
            return TokenBucket.Decision.unlimited();
        }
        TokenBucket bucket = buckets.get(routeName + "|" + principalKey,
                k -> new TokenBucket(limit.rate(), limit.burst(), tickerNanos));
        return bucket.tryAcquire();
    }

    public boolean isEnabled() {
        return !routeLimits.isEmpty();
    }
}
```

- [ ] **Step 4: Update the `GatewayProxyService` rate-limit call site**

In `src/main/java/com/recsys/application/gateway/GatewayProxyService.java`, change:
```java
        TokenBucket.Decision rateDecision = rateLimiter.tryAcquire(route.name());
```
to:
```java
        TokenBucket.Decision rateDecision = rateLimiter.tryAcquire(route.name(), principal.rateLimitKey());
```
(`principal` is the local introduced in Task 2, in scope here.)

- [ ] **Step 5: Run the rate-limiter test, then the full suite**

Run: `mvn test -Dtest=GatewayRateLimiterTest`
Expected: PASS — `Tests run: 5, Failures: 0, Errors: 0` (3 updated + 2 new).

Run: `mvn test`
Expected: full suite `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/ratelimit/GatewayRateLimiter.java \
        src/main/java/com/recsys/application/gateway/GatewayProxyService.java \
        src/test/java/com/recsys/ratelimit/GatewayRateLimiterTest.java
git commit -m "feat(gateway): per-(route,principal) rate limiting via bounded Caffeine cache

GatewayRateLimiter keys a TokenBucket per (route, principal), each using the route's
configured rate/burst, in a size- and TTL-bounded Caffeine cache. Proxy passes the
authenticated principal's rate-limit key.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Component 1 `GatewayPrincipal` (record + factories `ofJwt`/`ofApiKey`/`anonymous`, `rateLimitKey` derivation, `identityHeaders`, sha256 hash) → Task 1. ✓
- Component 2 `GatewayAuthResult` (allowed/rejected/rejected()/rejection()/principal()) → Task 1. ✓
- Component 3 `check()` seam change (returns result; consumes VerifiedClaims via `jwtVerify`; anonymous for public/disabled; strict-superset) → Task 2 Step 3. ✓
- Component 4 `GatewayRateLimiter` per-(route,principal) Caffeine (`tryAcquire(route,principalKey)`, `GATEWAY_RL_MAX_PRINCIPALS` default 100000, expireAfterAccess 60m, unconfigured→unlimited) → Task 3 Step 3. ✓
- Component 5 identity header strip (`x-authenticated-*`) + inject in both proxies → Task 2 Steps 4-5. ✓
- Data flow (unwrap → rate-limit by principal key → strip+inject) → Task 2 Steps 4-5 + Task 3 Step 4. ✓
- Out of scope (LlmTokenRateLimiter unchanged; no Redis; no roles) → not added. ✓
- Testing (principal derivation/headers; check() principal/rejection; rate-limit isolation; header strip via principal) → Task 1 Step 1, Task 2 Step 1, Task 3 Step 1. ✓

**Placeholder scan:** every code step shows full file content or exact before/after; every verify step is a concrete `mvn` command with expected counts. No TBD/TODO. ✓

**Type consistency:** `GatewayPrincipal(subject, clientId, tokenUse, rateLimitKey)`, `ofJwt(CognitoJwtVerifier.VerifiedClaims)`, `ofApiKey(String)`, `anonymous()`, `identityHeaders(): Map<String,String>`; `GatewayAuthResult.allowed/rejected/rejected()/rejection()/principal()`; `GatewayAuthenticator.check(...) → GatewayAuthResult`; `jwtVerify(String) → VerifiedClaims`; `GatewayRateLimiter.tryAcquire(String,String)`; `buildUpstreamHeaders(RequestHeaders, String, ServiceRequestContext, GatewayPrincipal)` — used identically across tasks and both proxies. The anti-spoof strip uses `regionMatches(true, 0, "x-authenticated-", 0, 16)` in both proxies (16 = length of `"x-authenticated-"`). ✓
