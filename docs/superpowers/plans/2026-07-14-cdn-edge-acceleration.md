# CDN Edge Acceleration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the origin CDN-ready (cache headers, ETag/304, origin lockdown) and codify a CloudFront distribution that terminates TLS, enforces WAF at the edge, and caches the two shared catalog reads.

**Architecture:** CloudFront sits in front of the existing internet-facing ALB. Viewer TLS terminates at the POP via an ACM certificate; a `CLOUDFRONT`-scope WebACL replaces the `REGIONAL` one; the ALB is reachable only from CloudFront's managed prefix list plus a secret header the gateway validates. Only `/api/catalog/item` and `/api/catalog/similar` are cached — every other path, including the POST-only `/api/recommend`, uses a default-deny CachingDisabled behavior.

**Tech Stack:** Java 17, Armeria (ports 6010/7010/8010), Spring Boot (8080), JUnit 5 + AssertJ + Mockito, Kustomize, AWS CLI v2.

Spec: `docs/superpowers/specs/2026-07-14-cdn-edge-acceleration-design.md`

## Global Constraints

- **JDK 17 required.** Every Maven command must be prefixed: `JAVA_HOME=$(/usr/libexec/java_home -v 17)`. On JDK 25 a clean compile of `LlmResponseCache.java` / `RecommendationCache.java` fails.
- **Branch:** `feat/cdn-edge-acceleration` (already exists, spec already committed). Never commit to `main`; this ships as a PR.
- **No IaC toolchain.** No Terraform, no CloudFormation. Edge resources are an idempotent AWS CLI script plus a runbook, matching `docs/runbooks/waf-webacl.md`.
- **`GATEWAY_PUBLIC_PATHS` must list the two exact paths, never the `/api/catalog` prefix.** Public-path matching is prefix-with-boundary (`GatewayAuthenticator.java:114-117`), so `/api/catalog` would also expose `/api/catalog/user?userId=1`, which returns user data. Exact value: `/health,/api/catalog/item,/api/catalog/similar`.
- **`/health` and `/metrics` are exempt from origin-secret enforcement.** The ALB health check, the k8s startup/readiness/liveness probes (`k8s/base/api-gateway.yaml:60-75`), and the Prometheus ServiceMonitor all reach the pod directly, bypassing CloudFront. Enforcing the secret on them breaks every probe.
- **Origin-secret enforcement defaults to disabled** when `GATEWAY_ORIGIN_SECRET` is unset, so `scripts/run-microservices-local.sh` and all existing tests keep working.
- Cache-Control values are exactly: `/item` → `public, s-maxage=3600, stale-while-revalidate=86400`; `/similar` → `public, s-maxage=300, stale-while-revalidate=3600`.
- Errors and PII routes are never cacheable: `no-store`.

---

### Task 1: `HttpCaching` helper

Pure functions for Cache-Control strings, strong ETag generation, and RFC 7232 weak `If-None-Match` comparison. No Armeria, no I/O — fully unit-testable.

**Files:**
- Create: `src/main/java/com/recsys/api/serving/HttpCaching.java`
- Test: `src/test/java/com/recsys/api/serving/HttpCachingTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `public static final String HttpCaching.NO_STORE` = `"no-store"`
  - `public static String HttpCaching.publicCache(long sMaxAgeSeconds, long staleWhileRevalidateSeconds)`
  - `public static String HttpCaching.etagFor(byte[] body)` → quoted 32-hex-char strong ETag
  - `public static boolean HttpCaching.matches(String ifNoneMatch, String etag)`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/api/serving/HttpCachingTest.java`:

```java
package com.recsys.api.serving;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HttpCachingTest {

    @Test
    void publicCache_rendersSMaxAgeAndStaleWhileRevalidate() {
        assertThat(HttpCaching.publicCache(3600, 86400))
                .isEqualTo("public, s-maxage=3600, stale-while-revalidate=86400");
    }

    @Test
    void etagFor_isQuotedAndStableForSameBody() {
        byte[] body = "{\"id\":1}".getBytes(StandardCharsets.UTF_8);
        String first = HttpCaching.etagFor(body);
        String second = HttpCaching.etagFor(body);
        assertThat(first).isEqualTo(second);
        assertThat(first).startsWith("\"").endsWith("\"");
        assertThat(first).hasSize(34); // 32 hex chars + 2 quotes
    }

    @Test
    void etagFor_differsForDifferentBodies() {
        assertThat(HttpCaching.etagFor("{\"id\":1}".getBytes(StandardCharsets.UTF_8)))
                .isNotEqualTo(HttpCaching.etagFor("{\"id\":2}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void matches_returnsTrueForIdenticalTag() {
        assertThat(HttpCaching.matches("\"abc\"", "\"abc\"")).isTrue();
    }

    @Test
    void matches_usesWeakComparisonSoWeakPrefixIsIgnored() {
        assertThat(HttpCaching.matches("W/\"abc\"", "\"abc\"")).isTrue();
    }

    @Test
    void matches_handlesCommaSeparatedList() {
        assertThat(HttpCaching.matches("\"zzz\", \"abc\"", "\"abc\"")).isTrue();
    }

    @Test
    void matches_returnsTrueForWildcard() {
        assertThat(HttpCaching.matches("*", "\"abc\"")).isTrue();
    }

    @Test
    void matches_returnsFalseForDifferentTag() {
        assertThat(HttpCaching.matches("\"zzz\"", "\"abc\"")).isFalse();
    }

    @Test
    void matches_returnsFalseForNullOrBlankHeader() {
        assertThat(HttpCaching.matches(null, "\"abc\"")).isFalse();
        assertThat(HttpCaching.matches("   ", "\"abc\"")).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=HttpCachingTest`
Expected: FAIL — compilation error, `cannot find symbol: class HttpCaching`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/recsys/api/serving/HttpCaching.java`:

```java
package com.recsys.api.serving;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * HTTP cache-header helpers for the CDN edge.
 *
 * <p>See docs/superpowers/specs/2026-07-14-cdn-edge-acceleration-design.md. Only
 * non-personalized, shared responses may use {@link #publicCache}; anything keyed by
 * user identity uses {@link #NO_STORE}.
 */
public final class HttpCaching {

    public static final String NO_STORE = "no-store";

    private static final int ETAG_BYTES = 16; // 16 bytes -> 32 hex chars

    private HttpCaching() {}

    public static String publicCache(long sMaxAgeSeconds, long staleWhileRevalidateSeconds) {
        return "public, s-maxage=" + sMaxAgeSeconds
                + ", stale-while-revalidate=" + staleWhileRevalidateSeconds;
    }

    /** Strong ETag: a quoted 32-hex-character SHA-256 prefix of the serialized body. */
    public static String etagFor(byte[] body) {
        byte[] hash = sha256(body);
        StringBuilder sb = new StringBuilder(ETAG_BYTES * 2 + 2);
        sb.append('"');
        for (int i = 0; i < ETAG_BYTES; i++) {
            sb.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
            sb.append(Character.forDigit(hash[i] & 0xF, 16));
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * RFC 7232 weak comparison of an {@code If-None-Match} header against an ETag.
     * GET revalidation uses weak comparison, so a {@code W/} prefix on either side is ignored.
     */
    public static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank() || etag == null) {
            return false;
        }
        String candidate = normalize(etag);
        for (String raw : ifNoneMatch.split(",")) {
            String value = raw.trim();
            if (value.equals("*") || normalize(value).equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String tag) {
        String value = tag.trim();
        if (value.regionMatches(true, 0, "W/", 0, 2)) {
            value = value.substring(2).trim();
        }
        return value;
    }

    private static byte[] sha256(byte[] body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(body);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=HttpCachingTest`
Expected: PASS — 9 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/api/serving/HttpCaching.java \
        src/test/java/com/recsys/api/serving/HttpCachingTest.java
git commit -m "feat(cdn): add HttpCaching helper for cache-control and etag"
```

---

### Task 2: Cacheable and no-store response writers on `BaseApiService`

Adds the two writers every Armeria serving route will use. `writeCacheableJson` serializes once, derives the ETag from those exact bytes, and short-circuits to 304 when `If-None-Match` matches.

**Files:**
- Modify: `src/main/java/com/recsys/api/serving/BaseApiService.java:1-24` (imports), `:25-32` (insert after `writeJson`)
- Test: `src/test/java/com/recsys/api/serving/BaseApiServiceCachingTest.java`

**Interfaces:**
- Consumes: `HttpCaching.publicCache`, `HttpCaching.etagFor`, `HttpCaching.matches`, `HttpCaching.NO_STORE` (Task 1).
- Produces:
  - `protected static HttpResponse BaseApiService.writeCacheableJson(HttpStatus status, Object payload, String cacheControl, HttpRequest req)`
  - `protected static HttpResponse BaseApiService.writeNoStoreJson(HttpStatus status, Object payload)`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/api/serving/BaseApiServiceCachingTest.java`:

```java
package com.recsys.api.serving;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BaseApiServiceCachingTest {

    /** Fixture route exercising writeCacheableJson through a real server. */
    static final class Cacheable extends BaseApiService {
        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
            return writeCacheableJson(HttpStatus.OK, Map.of("id", 1),
                    HttpCaching.publicCache(3600, 86400), req);
        }
    }

    /** Fixture route exercising writeNoStoreJson. */
    static final class NoStore extends BaseApiService {
        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
            return writeNoStoreJson(HttpStatus.OK, Map.of("token", "secret"));
        }
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/cacheable", new Cacheable());
            sb.service("/nostore", new NoStore());
        }
    };

    private WebClient client() {
        return WebClient.of(server.httpUri());
    }

    @Test
    void cacheableJson_setsCacheControlAndEtag() {
        AggregatedHttpResponse res = client().get("/cacheable").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL))
                .isEqualTo("public, s-maxage=3600, stale-while-revalidate=86400");
        assertThat(res.headers().get(HttpHeaderNames.ETAG)).isNotBlank();
        assertThat(res.contentUtf8()).contains("\"id\":1");
    }

    @Test
    void cacheableJson_returns304WhenIfNoneMatchMatches() {
        String etag = client().get("/cacheable").aggregate().join()
                .headers().get(HttpHeaderNames.ETAG);

        AggregatedHttpResponse res = client().execute(RequestHeaders.of(
                HttpMethod.GET, "/cacheable",
                HttpHeaderNames.IF_NONE_MATCH, etag)).aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(res.content().isEmpty()).isTrue();
        assertThat(res.headers().get(HttpHeaderNames.ETAG)).isEqualTo(etag);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL)).isNotBlank();
    }

    @Test
    void cacheableJson_returns200WhenIfNoneMatchIsStale() {
        AggregatedHttpResponse res = client().execute(RequestHeaders.of(
                HttpMethod.GET, "/cacheable",
                HttpHeaderNames.IF_NONE_MATCH, "\"stale-etag\"")).aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).contains("\"id\":1");
    }

    @Test
    void noStoreJson_setsNoStoreAndOmitsEtag() {
        AggregatedHttpResponse res = client().get("/nostore").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(res.headers().get(HttpHeaderNames.ETAG)).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=BaseApiServiceCachingTest`
Expected: FAIL — compilation error, `cannot find symbol: method writeCacheableJson`.

- [ ] **Step 3: Write minimal implementation**

In `src/main/java/com/recsys/api/serving/BaseApiService.java`, add this import alongside the existing Armeria imports (the file currently imports `AggregatedHttpRequest` but not `HttpRequest`):

```java
import com.linecorp.armeria.common.HttpRequest;
```

Then insert both methods immediately after `writeJson` (which ends at line 32):

```java
    /**
     * Serialize {@code payload} once, derive a strong ETag from those exact bytes, and return
     * either 304 (when the client's If-None-Match matches) or 200 with cache headers.
     *
     * <p>Only for non-personalized, shared responses. Anything keyed by user identity must use
     * {@link #writeNoStoreJson}. See
     * docs/superpowers/specs/2026-07-14-cdn-edge-acceleration-design.md.
     */
    protected static HttpResponse writeCacheableJson(HttpStatus status, Object payload,
                                                     String cacheControl, HttpRequest req) {
        try {
            byte[] body = MAPPER.writeValueAsBytes(payload);
            String etag = HttpCaching.etagFor(body);

            if (HttpCaching.matches(req.headers().get(HttpHeaderNames.IF_NONE_MATCH), etag)) {
                return HttpResponse.of(ResponseHeaders.builder(HttpStatus.NOT_MODIFIED)
                        .set(HttpHeaderNames.CACHE_CONTROL, cacheControl)
                        .set(HttpHeaderNames.ETAG, etag)
                        .build());
            }

            ResponseHeaders headers = ResponseHeaders.builder(status)
                    .contentType(MediaType.JSON_UTF_8)
                    .set(HttpHeaderNames.CACHE_CONTROL, cacheControl)
                    .set(HttpHeaderNames.ETAG, etag)
                    .build();
            return HttpResponse.of(headers, HttpData.wrap(body));
        } catch (Exception e) {
            return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "serialization error");
        }
    }

    /** Serialize {@code payload} with {@code Cache-Control: no-store}. Never cached anywhere. */
    protected static HttpResponse writeNoStoreJson(HttpStatus status, Object payload) {
        try {
            byte[] body = MAPPER.writeValueAsBytes(payload);
            ResponseHeaders headers = ResponseHeaders.builder(status)
                    .contentType(MediaType.JSON_UTF_8)
                    .set(HttpHeaderNames.CACHE_CONTROL, HttpCaching.NO_STORE)
                    .build();
            return HttpResponse.of(headers, HttpData.wrap(body));
        } catch (Exception e) {
            return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "serialization error");
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=BaseApiServiceCachingTest`
Expected: PASS — 4 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/api/serving/BaseApiService.java \
        src/test/java/com/recsys/api/serving/BaseApiServiceCachingTest.java
git commit -m "feat(cdn): add cacheable and no-store json writers to BaseApiService"
```

---

### Task 3: Cache `/item`; mark `/getuser` no-store

`CatalogService.Movies` becomes cacheable. `CatalogService.Users` returns user data and must never be cached. The `movie not found` 404 becomes `no-store` so a miss can't be pinned at the edge before the catalog is populated.

**Files:**
- Modify: `src/main/java/com/recsys/api/serving/CatalogService.java:23-47` (Movies), `:50-74` (Users)
- Test: `src/test/java/com/recsys/api/serving/CatalogCacheHeadersTest.java`

**Interfaces:**
- Consumes: `BaseApiService.writeCacheableJson`, `BaseApiService.writeNoStoreJson` (Task 2); `HttpCaching.publicCache` (Task 1).
- Produces: no new signatures. `GET /item` gains `Cache-Control: public, s-maxage=3600, stale-while-revalidate=86400` + `ETag`; `GET /getuser` gains `Cache-Control: no-store`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/api/serving/CatalogCacheHeadersTest.java`:

```java
package com.recsys.api.serving;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.domain.item.Movie;
import com.recsys.domain.user.User;
import com.recsys.infrastructure.dataloading.DataManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogCacheHeadersTest {

    static final DataManager mockData = mock(DataManager.class);

    static {
        when(mockData.getMovieById(anyInt())).thenReturn(null);
        when(mockData.getMovieById(1)).thenReturn(new Movie(1, "Test Movie", 2020, List.of("Action")));
        when(mockData.getUserById(anyInt())).thenReturn(new User(1, "Alice"));
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/item", new CatalogService.Movies(mockData));
            sb.service("/getuser", new CatalogService.Users(mockData));
        }
    };

    private WebClient client() {
        return WebClient.of(server.httpUri());
    }

    @Test
    void item_isPubliclyCacheableWithEtag() {
        AggregatedHttpResponse res = client().get("/item?id=1").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL))
                .isEqualTo("public, s-maxage=3600, stale-while-revalidate=86400");
        assertThat(res.headers().get(HttpHeaderNames.ETAG)).isNotBlank();
    }

    @Test
    void item_revalidatesTo304() {
        String etag = client().get("/item?id=1").aggregate().join()
                .headers().get(HttpHeaderNames.ETAG);

        AggregatedHttpResponse res = client().execute(RequestHeaders.of(
                HttpMethod.GET, "/item?id=1",
                HttpHeaderNames.IF_NONE_MATCH, etag)).aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(res.content().isEmpty()).isTrue();
    }

    @Test
    void item_notFoundIsNotCacheable() {
        AggregatedHttpResponse res = client().get("/item?id=999").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void getuser_isNeverCacheable() {
        AggregatedHttpResponse res = client().get("/getuser?userId=1").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(res.headers().get(HttpHeaderNames.ETAG)).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=CatalogCacheHeadersTest`
Expected: FAIL — `item_isPubliclyCacheableWithEtag` fails with `expecting actual not to be null` (no Cache-Control header is set today).

- [ ] **Step 3: Write minimal implementation**

In `src/main/java/com/recsys/api/serving/CatalogService.java`, replace the body of `Movies` (lines 23-47) with:

```java
    /** GET /item, /movie — fetch a movie by numeric {@code id}. Shared, non-personalized: cacheable. */
    public static final class Movies extends BaseApiService {

        // Catalog metadata is effectively static; serve stale for a day while revalidating.
        private static final String CACHE_CONTROL = HttpCaching.publicCache(3600, 86400);

        private final DataManager dataManager;

        public Movies(DataManager dataManager) {
            this.dataManager = dataManager;
        }

        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.of(CompletableFuture.supplyAsync(() -> {
                try {
                    int movieId = requiredIntParam(ctx, "id");
                    Movie movie = dataManager.getMovieById(movieId);
                    // Not cacheable: the movie may be added later, and a pinned 404 at the edge
                    // would outlive the gap.
                    if (movie == null) return writeNoStoreJson(HttpStatus.NOT_FOUND,
                            java.util.Map.of("error", "movie not found", "id", movieId));
                    return writeCacheableJson(HttpStatus.OK, movie, CACHE_CONTROL, req);
                } catch (BadRequestException e) {
                    return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
                } catch (Exception e) {
                    log.error("Unexpected error in CatalogService.Movies", e);
                    return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
                }
            }, ctx.blockingTaskExecutor()));
        }
    }
```

Then in `Users`, replace only the success line (currently line 65, `return writeJson(HttpStatus.OK, user);`) with:

```java
                    return writeNoStoreJson(HttpStatus.OK, user);
```

and replace the not-found line (currently line 64) with:

```java
                    if (user == null) return writeNoStoreJson(HttpStatus.NOT_FOUND,
                            java.util.Map.of("error", "user not found", "userId", userId));
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=CatalogCacheHeadersTest`
Expected: PASS — 4 tests, 0 failures.

Then confirm no regression in the existing suite that asserts these routes' bodies:

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='RecSysServerIntegrationTest+RecSysServerRegressionTest'`
Expected: PASS — the response bodies are unchanged; only headers were added.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/api/serving/CatalogService.java \
        src/test/java/com/recsys/api/serving/CatalogCacheHeadersTest.java
git commit -m "feat(cdn): cache /item at the edge, mark /getuser no-store"
```

---

### Task 4: Cache `/similar`

Item-to-item similarity is not personalized, so it is shared across all users. Its TTL is far shorter than `/item` because `POST /setembedding` can rewrite the underlying vectors.

**Files:**
- Modify: `src/main/java/com/recsys/api/serving/RecommendationService.java:110-149` (Similar)
- Test: `src/test/java/com/recsys/api/serving/SimilarCacheHeadersTest.java`

**Interfaces:**
- Consumes: `BaseApiService.writeCacheableJson`, `BaseApiService.writeNoStoreJson` (Task 2); `HttpCaching.publicCache` (Task 1).
- Produces: no new signatures. `GET /similar` gains `Cache-Control: public, s-maxage=300, stale-while-revalidate=3600` + `ETag`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/api/serving/SimilarCacheHeadersTest.java`:

```java
package com.recsys.api.serving;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.infrastructure.dataloading.DataManager;
import com.recsys.infrastructure.vectordb.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimilarCacheHeadersTest {

    static final DataManager mockData = mock(DataManager.class);
    static final EmbeddingStore mockEmb = mock(EmbeddingStore.class);

    static {
        when(mockData.getSimilarMovies(anyInt())).thenReturn(List.of());
        when(mockData.getTopRatedMovies(anyInt())).thenReturn(List.of());
        when(mockData.getMoviesByGenre(any(), anyInt())).thenReturn(List.of());
        when(mockData.getMovieById(anyInt())).thenReturn(null);
        when(mockEmb.getEmbedding(anyInt())).thenReturn(null);
        when(mockEmb.getEmbedding(1)).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        when(mockEmb.getEmbeddings(any())).thenReturn(Map.of());
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/similar", new RecommendationService.Similar(mockEmb, mockData));
        }
    };

    private WebClient client() {
        return WebClient.of(server.httpUri());
    }

    @Test
    void similar_isPubliclyCacheableWithShortTtl() {
        AggregatedHttpResponse res = client().get("/similar?movieId=1&k=5").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL))
                .isEqualTo("public, s-maxage=300, stale-while-revalidate=3600");
        assertThat(res.headers().get(HttpHeaderNames.ETAG)).isNotBlank();
    }

    @Test
    void similar_revalidatesTo304() {
        String etag = client().get("/similar?movieId=1&k=5").aggregate().join()
                .headers().get(HttpHeaderNames.ETAG);

        AggregatedHttpResponse res = client().execute(RequestHeaders.of(
                HttpMethod.GET, "/similar?movieId=1&k=5",
                HttpHeaderNames.IF_NONE_MATCH, etag)).aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(res.content().isEmpty()).isTrue();
    }

    @Test
    void similar_missingEmbeddingIsNotCacheable() {
        AggregatedHttpResponse res = client().get("/similar?movieId=999").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-store");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=SimilarCacheHeadersTest`
Expected: FAIL — `similar_isPubliclyCacheableWithShortTtl` fails, Cache-Control is null.

- [ ] **Step 3: Write minimal implementation**

In `src/main/java/com/recsys/api/serving/RecommendationService.java`, inside `Similar`, add a constant next to the existing `LIMIT_PER_GENRE` / `RECALL_MULTIPLIER` (lines 113-114):

```java
        // Embeddings can be rewritten by POST /setembedding, so the fresh window is short.
        // Bulk reloads additionally require an explicit CDN invalidation — see
        // docs/runbooks/cdn-operations.md.
        private static final String CACHE_CONTROL = HttpCaching.publicCache(300, 3600);
```

Replace the not-found line (currently lines 135-136):

```java
                    if (queryVec == null)
                        return writeNoStoreJson(HttpStatus.NOT_FOUND, Map.of(
                                "error", "embedding not found for movieId", "movieId", movieId));
```

Replace the success line (currently line 141):

```java
                    return writeCacheableJson(HttpStatus.OK,
                            new SimilarMoviesResult(movieId, scored), CACHE_CONTROL, req);
```

`Map` is already imported in this file (used by `Health`); no new imports are needed.

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=SimilarCacheHeadersTest`
Expected: PASS — 3 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/api/serving/RecommendationService.java \
        src/test/java/com/recsys/api/serving/SimilarCacheHeadersTest.java
git commit -m "feat(cdn): cache /similar at the edge with a 300s fresh window"
```

---

### Task 5: `no-store` on the Spring submit-token endpoint

`GET /api/v1/token` mints a one-time CSRF submit token. A cached token would be handed to multiple clients and break single-use semantics. Spring, not Armeria — so this uses `ResponseEntity` with `CacheControl`.

**Files:**
- Modify: `src/main/java/com/recsys/api/rest/RecommendationController.java:50-56`
- Test: `src/test/java/com/recsys/api/rest/SubmitTokenCacheHeaderTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks (different service, different framework).
- Produces: `GET /api/v1/token` returns `ResponseEntity<SubmitTokenResponse>` (was bare `SubmitTokenResponse`) carrying `Cache-Control: no-store`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/api/rest/SubmitTokenCacheHeaderTest.java`:

```java
package com.recsys.api.rest;

import com.recsys.application.auth.SubmitTokenService;
import com.recsys.api.response.SubmitTokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubmitTokenCacheHeaderTest {

    @Test
    void getSubmitToken_isNeverCacheable() {
        SubmitTokenService tokens = mock(SubmitTokenService.class);
        when(tokens.createToken()).thenReturn("tok-1");
        when(tokens.ttlSeconds()).thenReturn(300L);

        RecommendationController controller = new RecommendationController(
                null, null, null, null, null, tokens, null);

        ResponseEntity<SubmitTokenResponse> res = controller.getSubmitToken();

        assertThat(res.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(res.getBody()).isNotNull();
    }
}
```

Note: if `SubmitTokenService.ttlSeconds()` returns `int` rather than `long`, change the stub to `thenReturn(300)`. Check the signature before running.

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=SubmitTokenCacheHeaderTest`
Expected: FAIL — compilation error, `incompatible types: SubmitTokenResponse cannot be converted to ResponseEntity<SubmitTokenResponse>`.

- [ ] **Step 3: Write minimal implementation**

In `src/main/java/com/recsys/api/rest/RecommendationController.java`, add this import next to the existing `org.springframework.http.*` imports (lines 17-19):

```java
import org.springframework.http.CacheControl;
```

Replace the `getSubmitToken` method (lines 50-56) with:

```java
    @GetMapping(
            value = "/token",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<SubmitTokenResponse> getSubmitToken() {
        // Single-use CSRF token: a shared cache handing the same token to two clients would
        // break single-use semantics. Never cache, at any layer.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new SubmitTokenResponse(submitTokenService.createToken(),
                        submitTokenService.ttlSeconds()));
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=SubmitTokenCacheHeaderTest`
Expected: PASS — 1 test, 0 failures.

Then check no caller broke on the changed return type:

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='*RecommendationController*'`
Expected: PASS, or no tests matched. If a test asserts on the old bare return type, update it to call `.getBody()`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/api/rest/RecommendationController.java \
        src/test/java/com/recsys/api/rest/SubmitTokenCacheHeaderTest.java
git commit -m "feat(cdn): mark submit-token endpoint no-store"
```

---

### Task 6: Gateway origin-secret validation

The ALB security group alone is insufficient: the CloudFront origin-facing prefix list permits **any** AWS account's distribution to reach the origin. A secret header, injected by our distribution and validated here, closes that.

**Files:**
- Create: `src/main/java/com/recsys/application/gateway/GatewayOriginSecret.java`
- Modify: `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java:120` (after `ServerBuilder sb = Server.builder().http(port);`)
- Test: `src/test/java/com/recsys/application/gateway/GatewayOriginSecretTest.java`

**Interfaces:**
- Consumes: `EnvVars.EnvReader` (existing), `GatewayProxyService.gatewayError(HttpStatus, String)` (existing, `GatewayProxyService.java:63`).
- Produces:
  - `public static GatewayOriginSecret GatewayOriginSecret.fromEnvironment(EnvVars.EnvReader env)`
  - `public static GatewayOriginSecret GatewayOriginSecret.disabled()`
  - `public boolean isEnabled()`
  - `public boolean isAllowed(RequestHeaders headers, String path)`
  - `public static Function<? super HttpService, ? extends HttpService> newDecorator(GatewayOriginSecret secret)`
  - Header name constant: `public static final String HEADER = "x-origin-secret"`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/application/gateway/GatewayOriginSecretTest.java`:

```java
package com.recsys.application.gateway;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RequestHeaders;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayOriginSecretTest {

    private static GatewayOriginSecret withSecret(String value) {
        Map<String, String> env = Map.of("GATEWAY_ORIGIN_SECRET", value);
        return GatewayOriginSecret.fromEnvironment(env::get);
    }

    private static RequestHeaders headers(String path, String secret) {
        if (secret == null) {
            return RequestHeaders.of(HttpMethod.GET, path);
        }
        return RequestHeaders.of(HttpMethod.GET, path,
                HttpHeaderNames.of(GatewayOriginSecret.HEADER), secret);
    }

    @Test
    void disabledWhenEnvVarUnset() {
        GatewayOriginSecret secret = GatewayOriginSecret.fromEnvironment(name -> null);
        assertThat(secret.isEnabled()).isFalse();
        // Local dev: everything passes.
        assertThat(secret.isAllowed(headers("/api/recommend", null), "/api/recommend")).isTrue();
    }

    @Test
    void disabledWhenEnvVarBlank() {
        assertThat(withSecret("   ").isEnabled()).isFalse();
    }

    @Test
    void allowsMatchingSecret() {
        GatewayOriginSecret secret = withSecret("s3cret");
        assertThat(secret.isEnabled()).isTrue();
        assertThat(secret.isAllowed(headers("/api/recommend", "s3cret"), "/api/recommend")).isTrue();
    }

    @Test
    void rejectsWrongSecret() {
        assertThat(withSecret("s3cret")
                .isAllowed(headers("/api/recommend", "wrong"), "/api/recommend")).isFalse();
    }

    @Test
    void rejectsMissingSecret() {
        assertThat(withSecret("s3cret")
                .isAllowed(headers("/api/recommend", null), "/api/recommend")).isFalse();
    }

    @Test
    void exemptsHealthSoAlbAndKubeletProbesStillPass() {
        GatewayOriginSecret secret = withSecret("s3cret");
        assertThat(secret.isAllowed(headers("/health", null), "/health")).isTrue();
    }

    @Test
    void exemptsMetricsSoPrometheusScrapeStillPasses() {
        GatewayOriginSecret secret = withSecret("s3cret");
        assertThat(secret.isAllowed(headers("/metrics", null), "/metrics")).isTrue();
    }

    @Test
    void exemptionIsBoundaryMatchedNotPrefixMatched() {
        GatewayOriginSecret secret = withSecret("s3cret");
        // /healthcheck must NOT inherit /health's exemption.
        assertThat(secret.isAllowed(headers("/healthcheck", null), "/healthcheck")).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=GatewayOriginSecretTest`
Expected: FAIL — compilation error, `cannot find symbol: class GatewayOriginSecret`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/recsys/application/gateway/GatewayOriginSecret.java`:

```java
package com.recsys.application.gateway;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.HttpService;
import com.recsys.config.EnvVars;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.function.Function;

/**
 * Validates the secret header CloudFront injects on every origin request.
 *
 * <p>The ALB security group is pinned to the CloudFront origin-facing managed prefix list, but
 * that list covers <em>every</em> AWS account's distributions — so the prefix list alone does not
 * prove the request came from <em>our</em> distribution. This header does.
 *
 * <p>Disabled when {@code GATEWAY_ORIGIN_SECRET} is unset, so local dev and the existing test
 * suite are unaffected. See docs/superpowers/specs/2026-07-14-cdn-edge-acceleration-design.md.
 */
public final class GatewayOriginSecret {

    public static final String HEADER = "x-origin-secret";

    /**
     * Paths that reach the pod directly, never through CloudFront: the ALB health check, the
     * kubelet startup/readiness/liveness probes (k8s/base/api-gateway.yaml), and the Prometheus
     * ServiceMonitor scrape. Enforcing the secret on these would fail every probe and the pod
     * would never become ready.
     */
    private static final Set<String> EXEMPT_PATHS = Set.of("/health", "/metrics");

    private static final GatewayOriginSecret DISABLED = new GatewayOriginSecret(null);

    private final String expected;

    private GatewayOriginSecret(String expected) {
        this.expected = expected;
    }

    public static GatewayOriginSecret disabled() {
        return DISABLED;
    }

    public static GatewayOriginSecret fromEnvironment(EnvVars.EnvReader env) {
        String value = env.get("GATEWAY_ORIGIN_SECRET");
        if (value == null || value.isBlank()) {
            return DISABLED;
        }
        return new GatewayOriginSecret(value.trim());
    }

    public boolean isEnabled() {
        return expected != null;
    }

    public boolean isAllowed(RequestHeaders headers, String path) {
        if (!isEnabled() || isExempt(path)) {
            return true;
        }
        String provided = headers.get(HttpHeaderNames.of(HEADER));
        if (provided == null || provided.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.trim().getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isExempt(String path) {
        return EXEMPT_PATHS.stream().anyMatch(exempt ->
                path.equals(exempt) || path.startsWith(exempt + "/"));
    }

    /** Server-wide decorator: rejects any non-exempt request lacking the secret with 403. */
    public static Function<? super HttpService, ? extends HttpService> newDecorator(
            GatewayOriginSecret secret) {
        return delegate -> (ctx, req) -> {
            if (!secret.isAllowed(req.headers(), ctx.path())) {
                return GatewayProxyService.gatewayError(
                        HttpStatus.FORBIDDEN, "direct origin access is not permitted");
            }
            return delegate.serve(ctx, req);
        };
    }
}
```

Then wire it in `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java`. Add the import:

```java
import com.recsys.application.gateway.GatewayOriginSecret;
```

and insert immediately after `ServerBuilder sb = Server.builder().http(port);` (line 120):

```java
        // Origin lockdown: when CloudFront fronts this gateway, reject anything that did not come
        // through our distribution. No-op when GATEWAY_ORIGIN_SECRET is unset (local dev).
        GatewayOriginSecret originSecret = GatewayOriginSecret.fromEnvironment(System::getenv);
        if (originSecret.isEnabled()) {
            sb.decorator(GatewayOriginSecret.newDecorator(originSecret));
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=GatewayOriginSecretTest`
Expected: PASS — 8 tests, 0 failures.

Then verify the gateway suite still passes (the decorator must be inert when unset):

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='com.recsys.application.gateway.*Test'`
Expected: PASS, no regressions.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/application/gateway/GatewayOriginSecret.java \
        src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java \
        src/test/java/com/recsys/application/gateway/GatewayOriginSecretTest.java
git commit -m "feat(cdn): validate CloudFront origin secret at the gateway"
```

---

### Task 7: Kubernetes wiring

Opens the two catalog routes to anonymous reads (so the edge cache is not fragmented by `Authorization`) and plumbs the origin secret from a Kubernetes Secret.

**Files:**
- Modify: `k8s/base/configmap.yaml:32`
- Modify: `k8s/base/api-gateway.yaml:43-49` (gateway container `env`)
- Test: manual `kustomize build` assertions (no Java test applies)

**Interfaces:**
- Consumes: `GATEWAY_PUBLIC_PATHS` (read by `GatewayAuthenticator.fromEnvironment`, `GatewayAuthenticator.java:57`), `GATEWAY_ORIGIN_SECRET` (read by `GatewayOriginSecret.fromEnvironment`, Task 6).
- Produces: no code interfaces.

- [ ] **Step 1: Set the public paths**

In `k8s/base/configmap.yaml`, replace line 32:

```yaml
  GATEWAY_PUBLIC_PATHS: "/health"
```

with:

```yaml
  # Anonymous-readable paths. The two catalog reads are edge-cached by CloudFront and MUST NOT
  # vary on Authorization — a JWT-keyed cache fragments per user and the hit ratio collapses.
  # Movie metadata and item-to-item similarity are treated as non-sensitive.
  # DANGER: these MUST be the exact paths, never the "/api/catalog" prefix. Public-path matching
  # is prefix-with-boundary (GatewayAuthenticator.java:114-117), so "/api/catalog" would also
  # expose "/api/catalog/user?userId=1", which returns user data.
  # See docs/superpowers/specs/2026-07-14-cdn-edge-acceleration-design.md.
  GATEWAY_PUBLIC_PATHS: "/health,/api/catalog/item,/api/catalog/similar"
```

- [ ] **Step 2: Plumb the origin secret**

In `k8s/base/api-gateway.yaml`, append to the gateway container's `env:` list (after the `JAVA_OPTS` entry ending line 49):

```yaml
            # Origin lockdown. Absent until the CDN rollout reaches step 4, at which point the
            # Secret is created out-of-band (docs/runbooks/cdn-operations.md). optional: true keeps
            # the pod schedulable before then and in local/base deploys with no CDN.
            - name: GATEWAY_ORIGIN_SECRET
              valueFrom:
                secretKeyRef:
                  name: recsys-gateway-origin-secret
                  key: secret
                  optional: true
```

- [ ] **Step 3: Verify the manifests render**

Run:
```bash
kubectl kustomize k8s/base | grep -A2 GATEWAY_PUBLIC_PATHS
kubectl kustomize k8s/eks | grep -A5 GATEWAY_ORIGIN_SECRET
```
Expected: the first prints the three-path CSV; the second prints the `secretKeyRef` block with `optional: true`. Both commands must exit 0 — a non-zero exit means the YAML is malformed.

- [ ] **Step 4: Verify the exact-path guard holds**

Run:
```bash
kubectl kustomize k8s/base | grep GATEWAY_PUBLIC_PATHS | grep -c '"/api/catalog"'
```
Expected: prints `0`. Any other value means the bare prefix leaked in and `/api/catalog/user` is exposed — stop and fix.

- [ ] **Step 5: Commit**

```bash
git add k8s/base/configmap.yaml k8s/base/api-gateway.yaml
git commit -m "feat(cdn): open catalog reads to anonymous, plumb origin secret"
```

---

### Task 8: CDN scripts

Idempotent create/update plus invalidation, matching the CLI-script-and-runbook convention already used for the WAF WebACL.

**Files:**
- Create: `scripts/create-cdn-distribution.sh`
- Create: `scripts/invalidate-cdn.sh`

**Interfaces:**
- Consumes: `GATEWAY_ORIGIN_SECRET` value (Task 6), AWS CLI v2, `jq`.
- Produces: a CloudFront distribution whose `Comment` is `recsys-edge` (used as the idempotency key by both scripts).

- [ ] **Step 1: Write the create script**

Create `scripts/create-cdn-distribution.sh`:

```bash
#!/usr/bin/env bash
# Create or update the recsys CloudFront distribution.
#
# Idempotent: keyed on the distribution Comment "recsys-edge". Re-running with changed inputs
# updates the existing distribution rather than creating a second one.
#
# Follows the same out-of-band convention as docs/runbooks/waf-webacl.md — this repo has no IaC.
# See docs/superpowers/specs/2026-07-14-cdn-edge-acceleration-design.md.
#
# Usage:
#   ORIGIN_DOMAIN=origin.recsys.example.com \
#   ALIAS_DOMAIN=app.recsys.example.com \
#   ACM_CERT_ARN=arn:aws:acm:us-east-1:<acct>:certificate/<id> \
#   WEB_ACL_ARN=arn:aws:wafv2:us-east-1:<acct>:global/webacl/recsys-edge/<id> \
#   ORIGIN_SECRET=<same value as the GATEWAY_ORIGIN_SECRET k8s Secret> \
#   ./scripts/create-cdn-distribution.sh
set -euo pipefail

COMMENT="recsys-edge"

: "${ORIGIN_DOMAIN:?ORIGIN_DOMAIN is required (the Route53 failover hostname, NOT the ALB)}"
: "${ALIAS_DOMAIN:?ALIAS_DOMAIN is required (the public hostname)}"
: "${ACM_CERT_ARN:?ACM_CERT_ARN is required (must be in us-east-1)}"
: "${WEB_ACL_ARN:?WEB_ACL_ARN is required (must be scope=CLOUDFRONT)}"
: "${ORIGIN_SECRET:?ORIGIN_SECRET is required}"

if [[ "$ACM_CERT_ARN" != arn:aws:acm:us-east-1:* ]]; then
  echo "ERROR: CloudFront viewer certificates must live in us-east-1. Got: $ACM_CERT_ARN" >&2
  exit 1
fi

config_file="$(mktemp)"
trap 'rm -f "$config_file"' EXIT

# Cache policy ids are AWS-managed and stable:
#   CachingDisabled          4135ea2d-6df8-44a3-9df3-4b5a84be39ad
#   AllViewerExceptHostHeader (origin request) b689b0a8-53d0-40ab-baf2-68738e2966ac
CACHING_DISABLED="4135ea2d-6df8-44a3-9df3-4b5a84be39ad"
ALL_VIEWER_EXCEPT_HOST="b689b0a8-53d0-40ab-baf2-68738e2966ac"

# Per-behavior cache policies are created on first run and reused thereafter.
ensure_cache_policy() {
  local name="$1" min_ttl="$2" default_ttl="$3" max_ttl="$4" query_keys="$5"
  local existing
  existing="$(aws cloudfront list-cache-policies --type custom \
    --query "CachePolicyList.Items[?CachePolicy.CachePolicyConfig.Name=='${name}'].CachePolicy.Id" \
    --output text 2>/dev/null || true)"
  if [[ -n "$existing" && "$existing" != "None" ]]; then
    echo "$existing"
    return
  fi
  aws cloudfront create-cache-policy --cache-policy-config "$(jq -nc \
    --arg name "$name" --argjson min "$min_ttl" --argjson def "$default_ttl" \
    --argjson max "$max_ttl" --argjson keys "$query_keys" '{
      Name: $name, MinTTL: $min, DefaultTTL: $def, MaxTTL: $max,
      ParametersInCacheKeyAndForwardedToOrigin: {
        EnableAcceptEncodingGzip: true, EnableAcceptEncodingBrotli: true,
        HeadersConfig: {HeaderBehavior: "none"},
        CookiesConfig: {CookieBehavior: "none"},
        QueryStringsConfig: {QueryStringBehavior: "whitelist",
                             QueryStrings: {Quantity: ($keys|length), Items: $keys}}
      }}')" --query 'CachePolicy.Id' --output text
}

# Cache keys whitelist ONLY the meaningful params. Forwarding all query strings would let
# ?id=1&cachebuster=N fragment the cache arbitrarily and act as an origin-DoS amplifier.
item_policy="$(ensure_cache_policy recsys-item 0 3600 86400 '["id"]')"
similar_policy="$(ensure_cache_policy recsys-similar 0 300 3600 '["movieId","k"]')"

jq -n \
  --arg comment "$COMMENT" --arg origin "$ORIGIN_DOMAIN" --arg alias "$ALIAS_DOMAIN" \
  --arg cert "$ACM_CERT_ARN" --arg acl "$WEB_ACL_ARN" --arg secret "$ORIGIN_SECRET" \
  --arg item_policy "$item_policy" --arg similar_policy "$similar_policy" \
  --arg caching_disabled "$CACHING_DISABLED" --arg all_viewer "$ALL_VIEWER_EXCEPT_HOST" \
  --arg ref "recsys-edge-1" '
{
  CallerReference: $ref, Comment: $comment, Enabled: true, HttpVersion: "http2and3",
  Aliases: {Quantity: 1, Items: [$alias]},
  Origins: {Quantity: 1, Items: [{
    Id: "alb-origin", DomainName: $origin,
    CustomOriginConfig: {
      HTTPPort: 80, HTTPSPort: 443, OriginProtocolPolicy: "http-only",
      OriginSslProtocols: {Quantity: 1, Items: ["TLSv1.2"]},
      OriginReadTimeout: 30, OriginKeepaliveTimeout: 5
    },
    CustomHeaders: {Quantity: 1, Items: [
      {HeaderName: "x-origin-secret", HeaderValue: $secret}
    ]}
  }]},
  # DEFAULT = CachingDisabled. Everything is uncacheable unless explicitly opted in below.
  # This is what keeps POST /api/recommend and /api/catalog/user out of the cache, today and
  # for any route added later.
  DefaultCacheBehavior: {
    TargetOriginId: "alb-origin", ViewerProtocolPolicy: "redirect-to-https",
    CachePolicyId: $caching_disabled, OriginRequestPolicyId: $all_viewer, Compress: true,
    AllowedMethods: {Quantity: 7,
      Items: ["GET","HEAD","OPTIONS","PUT","POST","PATCH","DELETE"],
      CachedMethods: {Quantity: 2, Items: ["GET","HEAD"]}}
  },
  CacheBehaviors: {Quantity: 2, Items: [
    {PathPattern: "/api/catalog/item*", TargetOriginId: "alb-origin",
     ViewerProtocolPolicy: "redirect-to-https", CachePolicyId: $item_policy, Compress: true,
     AllowedMethods: {Quantity: 2, Items: ["GET","HEAD"],
       CachedMethods: {Quantity: 2, Items: ["GET","HEAD"]}}},
    {PathPattern: "/api/catalog/similar*", TargetOriginId: "alb-origin",
     ViewerProtocolPolicy: "redirect-to-https", CachePolicyId: $similar_policy, Compress: true,
     AllowedMethods: {Quantity: 2, Items: ["GET","HEAD"],
       CachedMethods: {Quantity: 2, Items: ["GET","HEAD"]}}}
  ]},
  ViewerCertificate: {ACMCertificateArn: $cert, SSLSupportMethod: "sni-only",
                      MinimumProtocolVersion: "TLSv1.2_2021"},
  WebACLId: $acl,
  PriceClass: "PriceClass_All"
}' > "$config_file"

existing_id="$(aws cloudfront list-distributions \
  --query "DistributionList.Items[?Comment=='${COMMENT}'].Id" --output text 2>/dev/null || true)"

if [[ -n "$existing_id" && "$existing_id" != "None" ]]; then
  echo "Updating existing distribution ${existing_id}"
  etag="$(aws cloudfront get-distribution-config --id "$existing_id" --query 'ETag' --output text)"
  aws cloudfront update-distribution --id "$existing_id" --if-match "$etag" \
    --distribution-config "file://${config_file}" \
    --query 'Distribution.DomainName' --output text
else
  echo "Creating distribution"
  aws cloudfront create-distribution --distribution-config "file://${config_file}" \
    --query 'Distribution.{Id:Id,Domain:DomainName}' --output table
fi

echo "Done. Validate against the raw cloudfront.net domain BEFORE flipping DNS."
echo "See docs/runbooks/cdn-operations.md."
```

- [ ] **Step 2: Write the invalidation script**

Create `scripts/invalidate-cdn.sh`:

```bash
#!/usr/bin/env bash
# Invalidate cached catalog paths after a bulk embedding or catalog reload.
#
# POST /setembedding rewrites the vectors behind /similar, so a bulk reload leaves the edge
# serving stale neighbours for up to its 300s fresh window (plus 3600s stale-while-revalidate).
#
# Invalidation is deliberately operator-triggered, NOT wired into the write path: per-write
# invalidation during a bulk load would issue thousands of API calls and blow through
# CloudFront's 1,000-free-invalidation-path quota. One bulk reload, one wildcard invalidation.
#
# Usage:
#   ./scripts/invalidate-cdn.sh                      # invalidate /similar (the common case)
#   ./scripts/invalidate-cdn.sh '/api/catalog/*'     # invalidate all catalog reads
set -euo pipefail

COMMENT="recsys-edge"
PATHS="${1:-/api/catalog/similar*}"

dist_id="$(aws cloudfront list-distributions \
  --query "DistributionList.Items[?Comment=='${COMMENT}'].Id" --output text 2>/dev/null || true)"

if [[ -z "$dist_id" || "$dist_id" == "None" ]]; then
  echo "ERROR: no distribution found with Comment='${COMMENT}'." >&2
  echo "Run ./scripts/create-cdn-distribution.sh first." >&2
  exit 1
fi

echo "Invalidating '${PATHS}' on ${dist_id}"
aws cloudfront create-invalidation --distribution-id "$dist_id" --paths "$PATHS" \
  --query 'Invalidation.{Id:Id,Status:Status}' --output table

echo "Invalidations take ~1-3 min to complete. Check with:"
echo "  aws cloudfront list-invalidations --distribution-id ${dist_id}"
```

- [ ] **Step 3: Make executable and verify syntax**

Run:
```bash
chmod +x scripts/create-cdn-distribution.sh scripts/invalidate-cdn.sh
bash -n scripts/create-cdn-distribution.sh && bash -n scripts/invalidate-cdn.sh && echo "syntax ok"
```
Expected: prints `syntax ok`.

- [ ] **Step 4: Verify the required-variable guards fire**

Run:
```bash
( unset ORIGIN_DOMAIN; ./scripts/create-cdn-distribution.sh 2>&1 || true ) | head -1
```
Expected: `ORIGIN_DOMAIN is required (the Route53 failover hostname, NOT the ALB)` — the script must refuse to run rather than create a half-configured distribution. No AWS calls are made.

Run:
```bash
( export ORIGIN_DOMAIN=o ALIAS_DOMAIN=a WEB_ACL_ARN=w ORIGIN_SECRET=s \
         ACM_CERT_ARN=arn:aws:acm:eu-west-1:1:certificate/x; \
  ./scripts/create-cdn-distribution.sh 2>&1 || true ) | head -1
```
Expected: `ERROR: CloudFront viewer certificates must live in us-east-1. Got: ...` — a wrong-region cert is caught locally rather than by an opaque AWS API error.

- [ ] **Step 5: Commit**

```bash
git add scripts/create-cdn-distribution.sh scripts/invalidate-cdn.sh
git commit -m "feat(cdn): add idempotent distribution and invalidation scripts"
```

---

### Task 9: Runbooks and superseding the WAF scope decision

Two existing documents assert the WebACL scope MUST be `REGIONAL`. Left alone they would contradict this work. This task closes that loop and documents the rollout order, whose steps 5 and 6 lock you out of your own origin if reversed.

**Files:**
- Create: `docs/runbooks/cdn-operations.md`
- Create: `docs/runbooks/cdn-rollback.md`
- Modify: `docs/runbooks/waf-webacl.md:14`
- Modify: `docs/superpowers/specs/2026-07-02-gateway-waf-ingress-design.md:103`
- Modify: `.claude/CLAUDE.md` (env-var paragraph and Redis/edge conventions)

**Interfaces:**
- Consumes: scripts from Task 8, env vars from Tasks 6-7.
- Produces: no code interfaces.

- [ ] **Step 1: Write the operations runbook**

Create `docs/runbooks/cdn-operations.md`:

````markdown
# CDN Operations

CloudFront fronts the API gateway. Design:
`docs/superpowers/specs/2026-07-14-cdn-edge-acceleration-design.md`.

Like the WAF WebACL and the Route53 records, the distribution is created out-of-band — this
repo has no IaC. There is no state file and no drift detection.

## What is and is not cached

Cached: `GET /api/catalog/item` (1 h fresh, 24 h stale-while-revalidate) and
`GET /api/catalog/similar` (5 min fresh, 1 h stale-while-revalidate).

Everything else, including `POST /api/recommend`, is `CachingDisabled` by default and always
reaches the origin. **The hit ratio on the primary recommendation route is zero by design** —
that route is POST-only and personalized. The CDN earns its keep here through edge TLS
termination, WAF, and backbone acceleration, not caching.

## Rollout

Order matters. Reversing steps 5 and 6 locks all traffic out of the origin.

1. Deploy the app (cache headers, ETag/304, origin-secret validation **disabled**). Safe no-op.
2. Create the ACM cert (**us-east-1** — CloudFront ignores certs anywhere else), the
   `CLOUDFRONT`-scope WebACL, and the distribution:
   ```bash
   ORIGIN_DOMAIN=origin.recsys.example.com \
   ALIAS_DOMAIN=app.recsys.example.com \
   ACM_CERT_ARN=arn:aws:acm:us-east-1:<acct>:certificate/<id> \
   WEB_ACL_ARN=arn:aws:wafv2:us-east-1:<acct>:global/webacl/recsys-edge/<id> \
   ORIGIN_SECRET="$(openssl rand -hex 32)" \
   ./scripts/create-cdn-distribution.sh
   ```
   Save the `ORIGIN_SECRET` value — step 4 needs it.
3. Validate against the raw distribution domain. Real traffic is still on the old path:
   ```bash
   D=dXXXXXXXXXXXXX.cloudfront.net
   curl -sI "https://$D/api/catalog/item?id=1" | grep -i 'x-cache\|cache-control\|etag'
   curl -sI "https://$D/api/catalog/item?id=1" | grep -i x-cache   # 2nd call: expect Hit
   curl -sI -X POST "https://$D/api/recommend" | grep -i x-cache   # expect Miss, always
   ```
   Expected: `X-Cache: Hit from cloudfront` on the repeated catalog GET;
   `X-Cache: Miss from cloudfront` on the POST.
4. Create the Secret so the gateway enforces the origin check:
   ```bash
   kubectl -n recsys create secret generic recsys-gateway-origin-secret \
     --from-literal=secret='<the ORIGIN_SECRET from step 2>'
   kubectl -n recsys rollout restart deployment/recsys-api-gateway
   kubectl -n recsys rollout status deployment/recsys-api-gateway
   ```
   Verify probes still pass — `/health` and `/metrics` are exempt from the secret check. If the
   pods go NotReady here, that exemption is broken; roll back the Secret immediately.
5. Point `app.*` at the distribution (Route53 alias A record). The failover records move to
   `origin.*` and keep working unchanged.
6. **Only now** narrow the ALB security group to the CloudFront prefix list, and retire the
   REGIONAL WebACL:
   ```bash
   aws ec2 authorize-security-group-ingress --group-id <alb-sg> --ip-permissions \
     'IpProtocol=tcp,FromPort=80,ToPort=80,PrefixListIds=[{PrefixListId=<pl-id>}]'
   ```
   Find the prefix list id with:
   ```bash
   aws ec2 describe-managed-prefix-lists --region <region> \
     --filters Name=prefix-list-name,Values=com.amazonaws.global.cloudfront.origin-facing \
     --query 'PrefixLists[0].PrefixListId' --output text
   ```
   Then remove the old 0.0.0.0/0 rule.

Steps 1-4 are invisible to users.

## Freshness after a bulk embedding reload

`POST /setembedding` rewrites the vectors behind `/similar`. After a **bulk** reload, invalidate
once:

```bash
./scripts/invalidate-cdn.sh '/api/catalog/similar*'
```

Do not invalidate per write. A bulk load would issue thousands of calls and exhaust the
1,000-free-path monthly quota.

Single-item edits need no action: the 300 s fresh window bounds the staleness.

## Monitoring

```bash
# Hit ratio (expect high on catalog, ~0 overall — most traffic is uncacheable POSTs)
aws cloudwatch get-metric-statistics --namespace AWS/CloudFront \
  --metric-name CacheHitRate --dimensions Name=DistributionId,Value=<id> \
  Name=Region,Value=Global --start-time "$(date -u -v-1H +%Y-%m-%dT%H:%M:%SZ)" \
  --end-time "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --period 300 --statistics Average
```

A sudden 403 spike at the origin after step 4 means the origin secret does not match — compare
the Secret value against the distribution's `CustomHeaders`.
````

- [ ] **Step 2: Write the rollback runbook**

Create `docs/runbooks/cdn-rollback.md`:

````markdown
# CDN Rollback

Reverse of `docs/runbooks/cdn-operations.md`. Roll back in this order — it is the rollout order
reversed, and skipping ahead strands traffic.

## 1. Restore direct ALB reachability (do this FIRST)

If the SG was already narrowed to the CloudFront prefix list, re-open it before touching DNS,
or the reverted DNS will point at an origin nothing can reach:

```bash
aws ec2 authorize-security-group-ingress --group-id <alb-sg> \
  --protocol tcp --port 80 --cidr 0.0.0.0/0
```

If the REGIONAL WebACL was retired, re-attach it — see `docs/runbooks/waf-webacl.md`.

## 2. Revert DNS

Point `app.*` back at the Route53 failover record set (primary us-east-1 ALB / secondary
us-west-2 ALB). Propagation is bounded by the 30 s TTL.

## 3. Stop enforcing the origin secret

```bash
kubectl -n recsys delete secret recsys-gateway-origin-secret
kubectl -n recsys rollout restart deployment/recsys-api-gateway
```

The env var is `optional: true`, so the pod starts without it and `GatewayOriginSecret` falls
back to disabled. Nothing else changes.

## 4. Disable the distribution (optional)

Leaving it enabled but unreferenced is harmless and makes re-rollout a DNS flip. To disable,
set `Enabled: false` via `update-distribution`.

## What you do NOT need to roll back

The cache headers, ETag/304 support, and `no-store` markers are correct HTTP semantics
independent of any CDN, and cost nothing with no cache in front. Leave them.

`GATEWAY_PUBLIC_PATHS` may be reverted to `/health` if you want the catalog reads
re-authenticated, but this is not required for rollback.
````

- [ ] **Step 3: Supersede the REGIONAL-scope assertion**

In `docs/runbooks/waf-webacl.md`, at line 14 where the scope is asserted, insert immediately above it:

```markdown
> **Superseded for the edge WebACL (2026-07-14).** The REGIONAL requirement below applied while
> the ALB was the front door. Once CloudFront fronts the gateway, the edge WebACL MUST be
> `CLOUDFRONT` scope, and this REGIONAL WebACL is retired at rollout step 6. See
> `docs/superpowers/specs/2026-07-14-cdn-edge-acceleration-design.md` and
> `docs/runbooks/cdn-operations.md`. Until that rollout completes, the REGIONAL WebACL below
> remains the live configuration.
```

In `docs/superpowers/specs/2026-07-02-gateway-waf-ingress-design.md`, at line 103, insert immediately above the scope assertion:

```markdown
> **Superseded (2026-07-14):** see
> `docs/superpowers/specs/2026-07-14-cdn-edge-acceleration-design.md`. With CloudFront as the
> front door, the edge WebACL is `CLOUDFRONT` scope and this REGIONAL WebACL is retired.
```

- [ ] **Step 4: Update CLAUDE.md**

In `.claude/CLAUDE.md`, append to the "Key env vars" paragraph:

```markdown
`GATEWAY_ORIGIN_SECRET` (default unset = disabled; when set, the gateway rejects any request that
does not carry a matching `x-origin-secret` header with 403, so only our CloudFront distribution
can reach the origin — `/health` and `/metrics` are exempt so ALB/kubelet probes and Prometheus
scrapes still work). `GATEWAY_PUBLIC_PATHS` now defaults to
`/health,/api/catalog/item,/api/catalog/similar` in k8s: the two catalog reads are edge-cached and
must not vary on `Authorization`. It MUST list exact paths — `/api/catalog` would also expose
`/api/catalog/user`. CDN operations are documented in `docs/runbooks/cdn-operations.md`.
```

And append to the Architecture section, after the API Gateway paragraph:

```markdown
**CDN edge** — CloudFront fronts the gateway ALB: viewer TLS via an ACM cert in us-east-1, a
`CLOUDFRONT`-scope WebACL, and origin lockdown (CloudFront prefix list + `x-origin-secret`). Only
`GET /api/catalog/item` and `GET /api/catalog/similar` are cached; everything else, including the
POST-only `/api/recommend`, is CachingDisabled by default. Created out-of-band via
`scripts/create-cdn-distribution.sh`; see `docs/superpowers/specs/2026-07-14-cdn-edge-acceleration-design.md`.
```

- [ ] **Step 5: Verify no contradictions remain**

Run:
```bash
grep -rn "REGIONAL" docs/runbooks/waf-webacl.md docs/superpowers/specs/2026-07-02-gateway-waf-ingress-design.md | head
```
Expected: every remaining `REGIONAL` assertion is now preceded by a superseded note pointing at the CDN design. If any bare "MUST be REGIONAL" survives without the note, add it.

- [ ] **Step 6: Commit**

```bash
git add docs/runbooks/cdn-operations.md docs/runbooks/cdn-rollback.md \
        docs/runbooks/waf-webacl.md \
        docs/superpowers/specs/2026-07-02-gateway-waf-ingress-design.md \
        .claude/CLAUDE.md
git commit -m "docs(cdn): add cdn runbooks, supersede regional waf scope"
```

---

### Task 10: Full verification and PR

**Files:**
- No changes; this task validates the whole branch.

**Interfaces:**
- Consumes: everything from Tasks 1-9.
- Produces: a PR against `main`.

- [ ] **Step 1: Run the full suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test`
Expected: BUILD SUCCESS, 0 failures. Load tests stay excluded by default.

If `RecSysServerIntegrationTest` or `RecSysServerRegressionTest` fail, the cause is almost
certainly a response-body change rather than a header change — the writers in Task 2 serialize
the identical payload. Diff the actual vs expected body before touching the tests.

- [ ] **Step 2: Verify the exempt-path guard against the real server**

Run:
```bash
GATEWAY_ORIGIN_SECRET=test-secret \
  JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  mvn exec:java -Dexec.mainClass=com.recsys.api.gateway.MicroserviceGatewayServer &
sleep 15
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8010/health
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8010/api/catalog/item?id=1
curl -s -o /dev/null -w '%{http_code}\n' -H 'x-origin-secret: test-secret' \
  http://localhost:8010/api/catalog/item?id=1
kill %1
```
Expected, in order: `200` (health exempt — this is what keeps the pod ready), `403` (no secret),
then `200` or `502`/`503` (secret accepted; the upstream may be down locally, which is fine —
anything other than 403 proves the decorator passed the request through).

- [ ] **Step 3: Push and open the PR**

```bash
git push -u origin feat/cdn-edge-acceleration
gh pr create --base main --title "feat: CloudFront edge acceleration" --body "$(cat <<'EOF'
## Summary

Makes the origin CDN-ready and codifies a CloudFront distribution in front of the gateway ALB.

Design: `docs/superpowers/specs/2026-07-14-cdn-edge-acceleration-design.md`

**Framing, up front:** the primary route (`POST /api/recommend`) is POST-only and personalized,
so its cache hit ratio is zero by design. The CDN earns its keep through edge TLS termination
(closing the plaintext-HTTP:80 gap), a `CLOUDFRONT`-scope WebACL that drops attacks before they
cost origin bandwidth, and backbone acceleration for uncacheable POSTs. Caching is a distant
fourth and covers exactly two catalog routes.

## Changes

- `HttpCaching` + `BaseApiService.writeCacheableJson` / `writeNoStoreJson`: Cache-Control, strong
  ETag, RFC 7232 weak `If-None-Match` → 304.
- `/item` cacheable (1 h / 24 h SWR); `/similar` cacheable (5 min / 1 h SWR).
- `/getuser`, `/api/v1/token`, and all error responses marked `no-store`.
- `GatewayOriginSecret`: rejects requests not carrying CloudFront's secret header. Disabled when
  the env var is unset, so local dev is unaffected. `/health` and `/metrics` are exempt — ALB and
  kubelet probes and the Prometheus scrape reach the pod directly, bypassing CloudFront.
- `GATEWAY_PUBLIC_PATHS` opens the two catalog reads to anonymous so the edge cache is not
  fragmented per-JWT. **Exact paths only** — the `/api/catalog` prefix would also expose
  `/api/catalog/user?userId=1`.
- `scripts/create-cdn-distribution.sh` (idempotent) + `scripts/invalidate-cdn.sh`, matching the
  existing out-of-band WAF/Route53 convention. No IaC toolchain introduced.
- Runbooks for operations and rollback; supersedes the REGIONAL-scope WebACL decision in
  `waf-webacl.md` and `2026-07-02-gateway-waf-ingress-design.md`.

## Deployment

**No AWS resource is created by merging this.** The distribution is created out-of-band by
following `docs/runbooks/cdn-operations.md`. Rollout steps 5 and 6 must not be reversed — see
the runbook.

## Test plan

- `mvn test` green.
- New: `HttpCachingTest`, `BaseApiServiceCachingTest`, `CatalogCacheHeadersTest`,
  `SimilarCacheHeadersTest`, `SubmitTokenCacheHeaderTest`, `GatewayOriginSecretTest`.
- CloudFront config itself is validated by the curl checks at rollout step 3.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 4: Confirm the PR is open**

Run: `gh pr view --json number,title,state`
Expected: `"state": "OPEN"`.
