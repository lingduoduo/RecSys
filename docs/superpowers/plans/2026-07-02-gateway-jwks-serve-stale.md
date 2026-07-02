# Gateway JWKS Serve-Stale Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** During a transient JWKS/Cognito outage, keep authenticating tokens whose signing key is already cached (serving the stale key with a 30s retry backoff) instead of failing every JWT with a 401.

**Architecture:** One production file (`CognitoJwtVerifier.java`): extract a `KeyFetcher` seam + injectable millis clock into the `HttpJwkProvider` cache so `key()` can serve the last-good key when a fetch fails, and so the behavior is unit-testable without a mock library. One new test.

**Tech Stack:** Java 17, JDK crypto, Jackson, slf4j, JUnit 5 + Jupiter Assertions. No new Maven dependencies.

## Global Constraints

- Edit only `src/main/java/com/recsys/application/gateway/CognitoJwtVerifier.java` + add one test. No new Maven dependencies.
- Serve-stale policy: on a JWKS fetch failure, if the requested `kid` is in the last-good cache, return it and set `expiresAtMillis = now + STALE_RETRY_BACKOFF` (30s); if the `kid` is not cached, rethrow the fetch `JwtAuthException` (→ 401). Unbounded during the outage (no grace-window cap); self-heals on the next successful fetch.
- Token `exp`/claims validation in `verify()`/`validateClaims` is unchanged — serve-stale affects only signing-key freshness.
- Public verifier API unchanged: `CognitoJwtVerifier(CognitoConfig, HttpClient, Clock)`, `CognitoJwtVerifier(CognitoConfig, JwkProvider, Clock)`, `verify(String)`, `StaticJwkProvider`. `StaticJwkProvider` is untouched.
- Preserve the single-flight `AtomicBoolean refetchInProgress` guard with its `finally` release.
- One commit. Never commit to `main`; work stays on branch `feat/gateway-jwks-serve-stale`.
- Verify with `mvn test -Dtest=CognitoJwtVerifierJwksTest,CognitoJwtVerifierTest` and `mvn test`.

---

### Task 1: Serve-stale in `HttpJwkProvider` + `KeyFetcher` testability seam

**Files:**
- Modify: `src/main/java/com/recsys/application/gateway/CognitoJwtVerifier.java` (imports, constant, logger; replace the `HttpJwkProvider` class; add nested `KeyFetcher` interface)
- Test: `src/test/java/com/recsys/application/gateway/CognitoJwtVerifierJwksTest.java`

**Interfaces:**
- Consumes: existing outer-class statics `MAPPER`, `URL_DECODER`, `JWKS_CACHE_TTL`, `UNKNOWN_KID_REFETCH_INTERVAL`, `JWKS_REQUEST_TIMEOUT`, `text(JsonNode,String)`, and the nested `JwtAuthException`.
- Produces (used by the test): nested `@FunctionalInterface CognitoJwtVerifier.KeyFetcher { Map<String,PublicKey> fetch(); }`; package-visible `CognitoJwtVerifier.HttpJwkProvider` with constructor `HttpJwkProvider(KeyFetcher fetcher, java.util.function.LongSupplier nowMillis)` and `PublicKey key(String kid)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/application/gateway/CognitoJwtVerifierJwksTest.java`:

```java
package com.recsys.application.gateway;

import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CognitoJwtVerifierJwksTest {

    private static PublicKey rsaKey() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair().getPublic();
    }

    @Test
    void servesStaleKeyWhenFetchFailsAfterExpiry() throws Exception {
        PublicKey k = rsaKey();
        AtomicLong now = new AtomicLong(0L);
        AtomicBoolean down = new AtomicBoolean(false);
        AtomicInteger fetches = new AtomicInteger(0);
        CognitoJwtVerifier.KeyFetcher fetcher = () -> {
            fetches.incrementAndGet();
            if (down.get()) throw new CognitoJwtVerifier.JwtAuthException(503, "JWKS down");
            return Map.of("kid-1", k);
        };
        CognitoJwtVerifier.HttpJwkProvider p =
                new CognitoJwtVerifier.HttpJwkProvider(fetcher, now::get);

        assertSame(k, p.key("kid-1"));                       // fetch #1 populates cache
        assertEquals(1, fetches.get());

        now.addAndGet(Duration.ofMinutes(6).toMillis());     // past the 5-min TTL
        down.set(true);                                      // JWKS outage begins

        assertSame(k, p.key("kid-1"));                       // SERVE STALE: still returns cached key
        assertEquals(2, fetches.get());                      // it attempted one (failed) refetch
    }

    @Test
    void rejectsUnknownKidWhenFetchFailsAndNothingCached() {
        AtomicLong now = new AtomicLong(0L);
        CognitoJwtVerifier.KeyFetcher fetcher = () -> {
            throw new CognitoJwtVerifier.JwtAuthException(503, "JWKS down");
        };
        CognitoJwtVerifier.HttpJwkProvider p =
                new CognitoJwtVerifier.HttpJwkProvider(fetcher, now::get);

        assertThrows(CognitoJwtVerifier.JwtAuthException.class, () -> p.key("kid-x"));
    }

    @Test
    void backoffLimitsRefetchDuringOutage() throws Exception {
        PublicKey k = rsaKey();
        AtomicLong now = new AtomicLong(0L);
        AtomicBoolean down = new AtomicBoolean(false);
        AtomicInteger fetches = new AtomicInteger(0);
        CognitoJwtVerifier.KeyFetcher fetcher = () -> {
            fetches.incrementAndGet();
            if (down.get()) throw new CognitoJwtVerifier.JwtAuthException(503, "down");
            return Map.of("kid-1", k);
        };
        CognitoJwtVerifier.HttpJwkProvider p =
                new CognitoJwtVerifier.HttpJwkProvider(fetcher, now::get);

        assertSame(k, p.key("kid-1"));                       // fetch #1
        now.addAndGet(Duration.ofMinutes(6).toMillis());
        down.set(true);
        assertSame(k, p.key("kid-1"));                       // fetch #2 (fails) -> stale + 30s backoff
        assertEquals(2, fetches.get());

        now.addAndGet(Duration.ofSeconds(10).toMillis());    // within the 30s backoff window
        assertSame(k, p.key("kid-1"));                       // served stale WITHOUT refetch
        assertEquals(2, fetches.get());

        now.addAndGet(Duration.ofSeconds(25).toMillis());    // now past the 30s backoff
        assertSame(k, p.key("kid-1"));                       // refetch attempted again
        assertEquals(3, fetches.get());
    }

    @Test
    void servesFromCacheWithinTtlWithoutRefetch() throws Exception {
        PublicKey k = rsaKey();
        AtomicLong now = new AtomicLong(0L);
        AtomicInteger fetches = new AtomicInteger(0);
        CognitoJwtVerifier.KeyFetcher fetcher = () -> {
            fetches.incrementAndGet();
            return Map.of("kid-1", k);
        };
        CognitoJwtVerifier.HttpJwkProvider p =
                new CognitoJwtVerifier.HttpJwkProvider(fetcher, now::get);

        assertSame(k, p.key("kid-1"));                       // fetch #1
        now.addAndGet(Duration.ofMinutes(1).toMillis());     // within the 5-min TTL
        assertSame(k, p.key("kid-1"));                       // served from cache
        assertEquals(1, fetches.get());                      // no refetch
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -Dtest=CognitoJwtVerifierJwksTest`
Expected: FAIL — compilation error: `CognitoJwtVerifier.KeyFetcher` and the `HttpJwkProvider(KeyFetcher, LongSupplier)` constructor do not exist yet.

- [ ] **Step 3: Add imports, a constant, and a logger**

In `src/main/java/com/recsys/application/gateway/CognitoJwtVerifier.java`:

(a) Add these imports (with the existing imports). Add `java.util.function.LongSupplier` after the `java.util.Objects` import, and the slf4j imports after the Jackson imports:

```java
import java.util.function.LongSupplier;
```
```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

(b) In the class body, after the existing constants block, add the logger and the backoff constant. Change:

```java
    private static final Duration JWKS_REQUEST_TIMEOUT = Duration.ofSeconds(2);
    private static final long ALLOWED_CLOCK_SKEW_SECONDS = 60L;
```
to:

```java
    private static final Duration JWKS_REQUEST_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration STALE_RETRY_BACKOFF = Duration.ofSeconds(30);
    private static final long ALLOWED_CLOCK_SKEW_SECONDS = 60L;
    private static final Logger log = LoggerFactory.getLogger(CognitoJwtVerifier.class);
```

- [ ] **Step 4: Add the `KeyFetcher` interface and replace `HttpJwkProvider`**

Replace the entire existing `HttpJwkProvider` class — from `private static final class HttpJwkProvider implements JwkProvider {` through its closing brace (the block ending just before `static final class JwtAuthException`) — with the following (a `KeyFetcher` interface plus the refactored, serve-stale `HttpJwkProvider`):

```java
    @FunctionalInterface
    interface KeyFetcher {
        Map<String, PublicKey> fetch();
    }

    static final class HttpJwkProvider implements JwkProvider {
        private final KeyFetcher fetcher;
        private final LongSupplier nowMillis;
        private volatile Map<String, PublicKey> cachedKeys = Map.of();
        private volatile long expiresAtMillis = 0L;
        private volatile long nextUnknownKidRefetchAtMillis = 0L;
        private final AtomicBoolean refetchInProgress = new AtomicBoolean(false);

        HttpJwkProvider(KeyFetcher fetcher, LongSupplier nowMillis) {
            this.fetcher = Objects.requireNonNull(fetcher, "fetcher is required");
            this.nowMillis = Objects.requireNonNull(nowMillis, "nowMillis is required");
        }

        HttpJwkProvider(String issuer, HttpClient httpClient) {
            this(httpFetcher(issuer, httpClient), System::currentTimeMillis);
        }

        private static KeyFetcher httpFetcher(String issuer, HttpClient httpClient) {
            Objects.requireNonNull(httpClient, "httpClient is required");
            String jwksUri = issuer + "/.well-known/jwks.json";
            return () -> httpFetch(jwksUri, httpClient);
        }

        @Override
        public PublicKey key(String kid) {
            Map<String, PublicKey> keys = cachedKeys;
            PublicKey key = keys.get(kid);
            long now = nowMillis.getAsLong();
            boolean expired = now >= expiresAtMillis;
            // Refetch when the cache has expired, or the kid is unknown and we have not
            // refetched for an unknown kid recently. The rate limit bounds refetches driven
            // by a flood of tokens carrying random kids; a single-flight guard (CAS) runs the
            // fetch WITHOUT holding a lock across the I/O.
            boolean needsRefetch = expired || (key == null && now >= nextUnknownKidRefetchAtMillis);
            if (needsRefetch && refetchInProgress.compareAndSet(false, true)) {
                try {
                    nextUnknownKidRefetchAtMillis = now + UNKNOWN_KID_REFETCH_INTERVAL.toMillis();
                    Map<String, PublicKey> fresh = fetcher.fetch();
                    cachedKeys = fresh;
                    expiresAtMillis = now + JWKS_CACHE_TTL.toMillis();
                    key = fresh.get(kid);
                } catch (JwtAuthException fetchError) {
                    // Serve-stale: a transient JWKS fetch failure must not reject tokens whose
                    // signing key we already hold. Keep the last-good cache and serve the stale
                    // key; retry at most once per STALE_RETRY_BACKOFF so a down endpoint is not
                    // hammered. Token exp/claims are validated independently in verify().
                    if (key == null) {
                        throw fetchError;
                    }
                    expiresAtMillis = now + STALE_RETRY_BACKOFF.toMillis();
                    log.warn("Cognito JWKS fetch failed; serving cached signing keys (stale), "
                            + "retrying in {}s: {}", STALE_RETRY_BACKOFF.toSeconds(), fetchError.getMessage());
                } finally {
                    refetchInProgress.set(false);
                }
            }
            if (key == null) {
                throw new JwtAuthException(401, "unknown JWT kid");
            }
            return key;
        }

        private static Map<String, PublicKey> httpFetch(String jwksUri, HttpClient httpClient) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(jwksUri))
                        .timeout(JWKS_REQUEST_TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    throw new JwtAuthException(503, "failed to fetch Cognito JWKS");
                }
                return parseJwks(response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JwtAuthException(503, "interrupted fetching Cognito JWKS", e);
            } catch (IOException | IllegalArgumentException e) {
                throw new JwtAuthException(503, "failed to fetch Cognito JWKS", e);
            }
        }

        private static Map<String, PublicKey> parseJwks(String body) throws IOException {
            JsonNode keysNode = MAPPER.readTree(body).get("keys");
            if (keysNode == null || !keysNode.isArray()) {
                throw new JwtAuthException(503, "Cognito JWKS response has no keys");
            }
            Map<String, PublicKey> parsed = new HashMap<>();
            Iterator<JsonNode> elements = keysNode.elements();
            while (elements.hasNext()) {
                JsonNode key = elements.next();
                if (!"RSA".equals(text(key, "kty"))) {
                    continue;
                }
                parsed.put(text(key, "kid"), rsaKey(text(key, "n"), text(key, "e")));
            }
            return parsed;
        }

        private static RSAPublicKey rsaKey(String modulus, String exponent) {
            try {
                BigInteger n = new BigInteger(1, URL_DECODER.decode(modulus));
                BigInteger e = new BigInteger(1, URL_DECODER.decode(exponent));
                return (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new RSAPublicKeySpec(n, e));
            } catch (GeneralSecurityException | IllegalArgumentException ex) {
                throw new JwtAuthException(503, "invalid RSA key in Cognito JWKS", ex);
            }
        }
    }
```

Notes: `parseJwks`/`rsaKey`/`httpFetch` are now `private static` and call the outer-class statics (`MAPPER`, `URL_DECODER`, `text`, `JWKS_REQUEST_TIMEOUT`) — all legal within the same top-level class. `text(JsonNode,String)` is the existing outer-class private static helper. The `key` local holds the stale value on the catch path (it is only reassigned after a successful fetch), so serving stale simply returns it.

- [ ] **Step 5: Run the new test, then the full suite**

Run: `mvn test -Dtest=CognitoJwtVerifierJwksTest,CognitoJwtVerifierTest`
Expected: PASS — `CognitoJwtVerifierJwksTest` 4/4 and the existing `CognitoJwtVerifierTest` 4/4 (the StaticJwkProvider path is unaffected).

Run: `mvn test`
Expected: full suite `BUILD SUCCESS` (a known pre-existing `OnlineAdmissionControlTest` timing flake, if it appears, passes in isolation — re-run `mvn test -Dtest=OnlineAdmissionControlTest` to confirm; not a regression).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/application/gateway/CognitoJwtVerifier.java \
        src/test/java/com/recsys/application/gateway/CognitoJwtVerifierJwksTest.java
git commit -m "feat(gateway): serve stale JWKS keys during a fetch outage

HttpJwkProvider now serves the last-good cached signing key when a JWKS fetch
fails (retrying at most once per 30s) instead of 401-ing every token; unknown
kids with no cached key still reject. Extracts a KeyFetcher + clock seam so the
cache/stale policy is unit-tested without a mock HttpClient.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Serve-stale on fetch failure (cached kid → serve + 30s backoff + WARN; uncached kid → rethrow → 401; unbounded, self-healing) → Task 1 Step 4. ✓
- `STALE_RETRY_BACKOFF = 30s` + slf4j logger → Task 1 Step 3. ✓
- `KeyFetcher` + injectable `LongSupplier nowMillis` testability seam; `HttpJwkProvider` package-visible; HTTP fetch moved to static helpers; public API + `StaticJwkProvider` unchanged → Task 1 Step 4. ✓
- Single-flight `AtomicBoolean` + `finally` preserved → Task 1 Step 4 (retained in the replacement). ✓
- Tests: serve-stale, unknown-kid-during-outage rethrow, backoff limits refetch, within-TTL no refetch → Task 1 Step 1. ✓
- Out of scope (no grace-window cap, no verify() change, no new dependency) → none added. ✓

**Placeholder scan:** the full `HttpJwkProvider`/`KeyFetcher` replacement and the full test file are shown; every verify step is a concrete `mvn` command with expected counts. No TBD/TODO. ✓

**Type consistency:** `CognitoJwtVerifier.KeyFetcher.fetch(): Map<String,PublicKey>`, `HttpJwkProvider(KeyFetcher, LongSupplier)`, `key(String): PublicKey`, `STALE_RETRY_BACKOFF`, `JwtAuthException(int,String)` — used identically in the production replacement and the test. The `(issuer, httpClient)` constructor delegates to the `(fetcher, nowMillis)` one, preserving the existing `CognitoJwtVerifier(config, HttpClient, Clock)` wiring. ✓
