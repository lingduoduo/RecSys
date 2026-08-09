# CDN percent-encoding cache key Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reject percent-encoded query strings on the two CDN-cached catalog paths at the gateway, so one CloudFront cache key means one response body.

**Architecture:** One package-private constant and one static method in `GatewayProxyService`, called from `serve` immediately after the existing path guard. The check is a single `indexOf('%')` — it parses nothing, so it cannot disagree with Armeria's decoder. Two new non-docker test classes: one for behaviour, one pinning the guarded path set against the committed edge configuration.

**Tech Stack:** Java 17, Armeria, JUnit 5, Maven.

## Global Constraints

- Build and test with JDK 17: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`.
- Both new test classes must be non-docker and added to the `resilience` profile's `<includes>` in `pom.xml` — that profile is what the PR gate runs, so a test outside it does not block merges.
- **Do not extend `LocalCdnCacheTest`.** It is `@Tag("docker")` and has never been executed on any machine used for this work; a case added there would ship unverified.
- **Do not change `cacheKeyIntParam`** (`BaseApiService.java:250`). The guard lives at the gateway; direct-to-6010 requests are a documented residue, not a bug this branch fixes.
- The guard rejects; it never normalizes. Canonicalizing the query here would fix the origin while leaving the edge key on whatever spelling the client chose.
- Never merge to `main` directly — this ships as a PR.
- Branch: `feat/cdn-percent-encoding-cache-key` (already created; the spec is already committed on it).

---

## File Structure

| File | Responsibility |
|---|---|
| `src/main/java/com/recsys/application/gateway/GatewayProxyService.java` | The guard: `CDN_CACHED_PATHS` + `rejectEncodedCacheKeyQuery`, wired into `serve`. |
| `src/test/java/com/recsys/application/gateway/GatewayCacheKeyQueryTest.java` | Behaviour: the three channels rejected, all four public spellings covered, clean and non-cached queries untouched. |
| `src/test/java/com/recsys/application/gateway/CdnCachedPathConformanceTest.java` | Pins `CDN_CACHED_PATHS` against `scripts/create-cdn-distribution.sh` and `docker/cdn/default.conf.template`. |
| `pom.xml` | Two `<include>` entries in the `resilience` profile. |
| `docs/system_design/12_CDNS.md` | Sharp edge 9 rewritten; the cache-key bullet around line 68 updated. |

---

### Task 1: The gateway guard

**Files:**
- Modify: `src/main/java/com/recsys/application/gateway/GatewayProxyService.java`
- Create: `src/test/java/com/recsys/application/gateway/GatewayCacheKeyQueryTest.java`
- Modify: `pom.xml` (resilience profile `<includes>`)

**Interfaces:**
- Consumes: `GatewayProxyService.gatewayError(HttpStatus, String)` (already exists, already sets `Cache-Control: no-store`); `ApiVersion.parse(String).path()` (already exists, strips `/api/v1/...` to `/api/...`).
- Produces: package-private `static final Set<String> CDN_CACHED_PATHS` and package-private `static HttpResponse rejectEncodedCacheKeyQuery(String path, String query)` returning `null` when the request is acceptable. Task 2 reads the constant.

Facts verified against the repo, so you need not rediscover them:

- `GatewayProxyService.serve` already calls `ApiVersion.parse(ctx.path())` and then `rejectNonCanonicalPath(path)` at lines 50-58. `path` is the **version-free** path from that point on.
- The four CloudFront `PathPattern` entries are `/api/catalog/item`, `/api/v1/catalog/item`, `/api/catalog/similar`, `/api/v1/catalog/similar` (`scripts/create-cdn-distribution.sh:187-200`). After version stripping those collapse to **two** paths.
- The real cache policies in the AWS account whitelist `id` (`recsys-item`) and `movieId`, `k` (`recsys-similar`), matching the script at lines 146-147.
- `gatewayError` sets `Cache-Control: no-store` (`GatewayProxyService.java:181-184`), so the 400 is never edge-cached. Do not add cache headers yourself.
- `GatewayPathCanonicalizationTest` is the sibling test for the path half of this guard. Follow its shape: full-`serve()` assertions for rejections, direct static-method assertions for acceptances (a `serve()` that is *not* rejected would try to reach a real backend).
- `ServiceRequestContext.of(request)` populates `ctx.query()` from the request path's query string.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/application/gateway/GatewayCacheKeyQueryTest.java`:

```java
package com.recsys.application.gateway;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.ratelimit.GatewayRateLimiter;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cached catalog route accepts no percent-encoding in its query string.
 *
 * <p>{@code cacheKeyIntParam} validates the <em>decoded</em> query value; the CloudFront cache key
 * is built from the <em>raw</em> query string. Three channels follow from that asymmetry, and this
 * class asserts each is closed:
 *
 * <ol>
 *   <li>{@code ?id=%37} decodes to a valid {@code 7} — a second cache key over the {@code id=7}
 *       body, so one response is split across several keys.
 *   <li>{@code ?%69d=7} decodes its parameter <em>name</em> to {@code id} and is accepted by the
 *       origin, but presents no whitelisted parameter to the edge, colliding with
 *       {@code ?%69%64=8} and with a bare parameterless request.
 *   <li>{@code ?movieId=1&%6b=200} is the worst: {@code k} has a default, so this is a complete
 *       request whose body reflects {@code k=200} while the edge sees only {@code movieId=1}. Two
 *       bodies under one key, and the {@code k} ceiling bypassed — the parameter it bounds is not
 *       the parameter the edge keys on.
 * </ol>
 *
 * <p>The guard is one {@code indexOf}: it locates no parameter, splits no pair and decodes
 * nothing, so it cannot disagree with Armeria's decoder about what a query string means. The
 * sibling of {@link GatewayPathCanonicalizationTest}, for the query half of the same problem.
 */
class GatewayCacheKeyQueryTest {

    private static final MicroserviceRoute CATALOG = new MicroserviceRoute(
            "catalog", "/api/catalog", "CATALOG_SERVICE_URL",
            URI.create("http://localhost:6010"), "/health", "recsys-catalog-serving");

    /** Channel 1: an encoded value is a second cache key over one body. */
    @Test
    void anEncodedCacheKeyValueIsRejected() {
        assertRejected("/api/catalog/item?id=%37");
        assertRejected("/api/catalog/similar?movieId=%31&k=5");
    }

    /** Channel 2: an encoded parameter name presents nothing whitelisted to the edge. */
    @Test
    void anEncodedCacheKeyParameterNameIsRejected() {
        assertRejected("/api/catalog/item?%69d=7");
        assertRejected("/api/catalog/item?%69%64=8");
    }

    /**
     * Channel 3: {@code k} has a default, so the origin honours a parameter the edge cannot see —
     * two bodies under the cache key of a bare {@code ?movieId=1}.
     */
    @Test
    void anEncodedNameForADefaultedParameterIsRejected() {
        assertRejected("/api/catalog/similar?movieId=1&%6b=200");
        assertRejected("/api/catalog/similar?movieId=1&%6B=5");
    }

    /**
     * All four public spellings reach the guard. It runs on the version-free path, so the
     * {@code /api/v1} twins are covered by the same two entries — proving the guard sits after
     * {@link ApiVersion} stripping, which is the only reason two entries can cover four behaviors.
     */
    @Test
    void everyPublicSpellingOfACachedRouteIsGuarded() {
        assertRejected("/api/catalog/item?id=%37");
        assertRejected("/api/v1/catalog/item?id=%37");
        assertRejected("/api/catalog/similar?movieId=%31");
        assertRejected("/api/v1/catalog/similar?movieId=%31");
    }

    /** The clean spellings are what the routes exist to serve; none of them may be rejected. */
    @Test
    void canonicalQueriesOnCachedRoutesAreAccepted() {
        assertAccepted("/api/catalog/item", "id=7");
        assertAccepted("/api/catalog/similar", "movieId=1&k=5");
        assertAccepted("/api/catalog/similar", "movieId=1");
        assertAccepted("/api/catalog/item", null);
        assertAccepted("/api/catalog/similar", "");
    }

    /**
     * Non-cached routes keep accepting encoded queries. They have no cache key to protect, and a
     * blanket rejection would break parameters with legitimately encoded values.
     */
    @Test
    void encodedQueriesOnNonCachedRoutesAreUntouched() {
        assertAccepted("/api/model/api/v1/recommend", "q=%20");
        assertAccepted("/api/catalog/user", "id=%37");
        assertAccepted("/api/catalog", "id=%37");
        assertAccepted("/api/catalog/items", "id=%37");
        assertAccepted("/health", "x=%20");
    }

    /** The 400 must never be cacheable, or the edge pins it for every viewer at that POP. */
    @Test
    void theRejectionIsNotCacheable() {
        AggregatedHttpResponse response = serve("/api/catalog/item?id=%37");
        assertEquals(HttpStatus.BAD_REQUEST, response.status());
        assertEquals("no-store", response.headers().get("cache-control"));
        assertTrue(response.contentUtf8().contains("percent-encoding"), response.contentUtf8());
    }

    private static void assertRejected(String pathAndQuery) {
        AggregatedHttpResponse response = serve(pathAndQuery);
        assertEquals(HttpStatus.BAD_REQUEST, response.status(),
                "expected 400 for " + pathAndQuery);
        assertEquals("no-store", response.headers().get("cache-control"),
                "a rejection must never be cacheable at the edge");
        assertNotNull(GatewayProxyService.rejectEncodedCacheKeyQuery(
                ApiVersion.parse(pathAndQuery.substring(0, pathAndQuery.indexOf('?'))).path(),
                pathAndQuery.substring(pathAndQuery.indexOf('?') + 1)));
    }

    private static void assertAccepted(String path, String query) {
        assertNull(GatewayProxyService.rejectEncodedCacheKeyQuery(path, query),
                "expected acceptance for " + path + "?" + query);
    }

    private static AggregatedHttpResponse serve(String pathAndQuery) {
        GatewayProxyService service = new GatewayProxyService(
                List.of(CATALOG),
                new GatewayRequestForwarder(List.of(CATALOG), Duration.ofSeconds(1), Map.of(),
                        GatewayRateLimiter.disabled(),
                        new UpstreamEndpointGroups.HealthCheckConfig(false, 0L)),
                GatewayAuthenticator.disabled());
        HttpRequest request = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, pathAndQuery));
        ServiceRequestContext ctx = ServiceRequestContext.of(request);
        HttpResponse response = service.serve(ctx, request);
        return response.aggregate().join();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=GatewayCacheKeyQueryTest
```

Expected: compilation failure — `cannot find symbol: method rejectEncodedCacheKeyQuery`.

- [ ] **Step 3: Add the constant and the guard method**

In `GatewayProxyService.java`, add `import java.util.Set;` beside the existing `java.util.*` imports.

Add the constant immediately above the constructors, after the three instance fields:

```java
    /**
     * The version-free paths CloudFront caches. Four {@code PathPattern} entries exist
     * ({@code scripts/create-cdn-distribution.sh}), but {@link ApiVersion} strips {@code /api/v1}
     * before this class sees a path, so two entries cover all four — the same reason
     * {@code GATEWAY_PUBLIC_PATHS} carries no versioned twins.
     *
     * <p>Pinned against the committed edge configuration by {@code CdnCachedPathConformanceTest}:
     * a fifth cached behavior cannot be added to the script or the local nginx template without an
     * entry here.
     */
    static final Set<String> CDN_CACHED_PATHS =
            Set.of("/api/catalog/item", "/api/catalog/similar");
```

Add the guard method immediately after `rejectNonCanonicalPath` (which ends at line 109):

```java
    /**
     * Rejects a percent-encoded query string on a CDN-cached route, with {@code 400}.
     *
     * <p>The query-string twin of {@link #rejectNonCanonicalPath}, and the same disagreement one
     * field over. {@code BaseApiService.cacheKeyIntParam} validates the <em>decoded</em> value —
     * Armeria decodes both parameter names and values before {@code queryParams} returns — while
     * the CloudFront cache key is built from the <em>raw</em> query string, matched against a
     * literal whitelist ({@code id}; {@code movieId}, {@code k}). Three channels follow:
     * {@code ?id=%37} is a second key over one body; {@code ?%69d=7} presents no whitelisted
     * parameter at all and collides with {@code ?%69%64=8} and with a bare request; and
     * {@code ?movieId=1&%6b=200} is a complete request whose body reflects {@code k=200} while the
     * edge sees only {@code movieId=1} — two bodies under one key, and the {@code k} ceiling
     * bypassed, since the parameter it bounds is not the parameter the edge keys on.
     *
     * <p>One {@code indexOf}, deliberately. The guard locates no parameter, splits no pair and
     * decodes nothing, so it cannot disagree with Armeria's decoder about what a query string
     * means — a guard that re-implements the parser it guards inherits that parser's blind spots.
     * Rejecting {@code %} wholesale is sound because the only parameters these two routes accept
     * are three integers: no legitimate request needs an encoded character. Non-cached routes are
     * untouched; they have no cache key to protect.
     *
     * <p>Like the path guard, this rejects rather than normalizes — canonicalizing here would fix
     * the origin while leaving the edge key on whatever spelling the client chose — and it is a
     * malformed-request rejection rather than an authorization decision, so it binds service-tier
     * callers too.
     *
     * @param path  the version-free path, after {@link ApiVersion} stripping
     * @param query the raw query string, or null when the request carries none
     * @return the 400 to return, or null when the query is acceptable
     */
    static HttpResponse rejectEncodedCacheKeyQuery(String path, String query) {
        if (query == null || !CDN_CACHED_PATHS.contains(path) || query.indexOf('%') < 0) {
            return null;
        }
        return gatewayError(HttpStatus.BAD_REQUEST,
                "bad request: percent-encoding is not allowed in the query string of a cached route");
    }
```

- [ ] **Step 4: Wire it into `serve`**

In `serve`, immediately after the `rejectNonCanonicalPath` block that ends at line 58, insert:

```java
        HttpResponse encodedQuery = rejectEncodedCacheKeyQuery(path, ctx.query());
        if (encodedQuery != null) {
            return encodedQuery;
        }
```

It must sit **after** the `ApiVersion` stripping (so two entries cover four behaviors) and **before** `authenticator.check` (it is a malformed-request rejection, and both cached paths are in `GATEWAY_PUBLIC_PATHS`, so authentication would not reject them anyway).

- [ ] **Step 5: Run the test to verify it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=GatewayCacheKeyQueryTest
```

Expected: PASS, 7 tests.

- [ ] **Step 6: Add the test to the PR gate**

In `pom.xml`, in the `resilience` profile's `<includes>` list, beside the other `**/gateway/*` entries (around line 436, next to `GatewayPathCanonicalizationTest`), add:

```xml
                <include>**/gateway/GatewayCacheKeyQueryTest.java</include>
```

- [ ] **Step 7: Verify the gate still passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
```

Expected: BUILD SUCCESS, test count increased by 7 over the pre-change baseline.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/recsys/application/gateway/GatewayProxyService.java \
        src/test/java/com/recsys/application/gateway/GatewayCacheKeyQueryTest.java \
        pom.xml
git commit -m "fix: reject percent-encoded queries on the CDN-cached catalog routes"
```

---

### Task 2: Pin the guarded paths to the edge configuration, and correct the docs

**Files:**
- Create: `src/test/java/com/recsys/application/gateway/CdnCachedPathConformanceTest.java`
- Modify: `pom.xml` (resilience profile `<includes>`)
- Modify: `docs/system_design/12_CDNS.md` (the cache-key bullet around line 68; sharp edge 9 at lines 333-342; the sharp-edge-7 cross-reference at lines 324-325)

**Interfaces:**
- Consumes: `GatewayProxyService.CDN_CACHED_PATHS` (package-private `Set<String>`, added in Task 1); `ApiVersion.parse(String).path()`.
- Produces: nothing consumed later.

Facts verified against the repo:

- `scripts/create-cdn-distribution.sh` declares cached behaviors as `PathPattern: "/api/catalog/item"` and friends at lines 187-200 — four entries, each on its own line, always double-quoted.
- `docker/cdn/default.conf.template` declares them as `location = /api/catalog/item {` at lines 50, 73, 89 and 104, each block containing exactly one `proxy_cache_key` line (60, 80, 96, 111). There is also a `location / {` at line 35 with **no** `proxy_cache_key` — the pass-through — which must not be collected.
- The test lives in `com.recsys.application.gateway`, not `com.recsys.infrastructure.k8s`, because its subject is the gateway constant and that constant is package-private.
- Use `ApiVersion.parse(p).path()` to strip versions rather than writing your own string surgery. It is the same normalizer the guard runs behind, so the test cannot disagree with it.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/application/gateway/CdnCachedPathConformanceTest.java`:

```java
package com.recsys.application.gateway;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The set of paths the gateway guards equals the set of behaviors the edge caches.
 *
 * <p>{@link GatewayProxyService#CDN_CACHED_PATHS} exists to keep percent-encoded queries away from
 * a CloudFront cache key. It is only as good as its membership: a fifth cached behavior added to
 * the distribution without a matching entry would be guarded by nothing, and the omission would be
 * invisible — the route would keep working, and only the cache key would be wrong.
 *
 * <p>So the constant is pinned against the two places the edge configuration actually lives, both
 * committed to this repo: the {@code PathPattern} entries in
 * {@code scripts/create-cdn-distribution.sh}, which create the real distribution, and the
 * {@code location =} blocks carrying a {@code proxy_cache_key} in
 * {@code docker/cdn/default.conf.template}, which is the local stand-in. Each is compared after
 * {@link ApiVersion} stripping, because the guard runs on the version-free path.
 *
 * <p>Scope: a cache behavior created by hand in the AWS console is invisible here, as it is to
 * every other conformance test in this repo. This checks the committed sources against the code,
 * not the code against AWS.
 */
class CdnCachedPathConformanceTest {

    private static final Path SCRIPT = Path.of("scripts", "create-cdn-distribution.sh");
    private static final Path NGINX = Path.of("docker", "cdn", "default.conf.template");

    private static final Pattern PATH_PATTERN = Pattern.compile("PathPattern:\\s*\"([^\"]+)\"");
    private static final Pattern LOCATION = Pattern.compile("^\\s*location\\s+=\\s+(\\S+)\\s*\\{");

    @Test
    void theGuardedPathsAreExactlyTheCloudFrontCachedBehaviors() throws IOException {
        Set<String> fromScript = new TreeSet<>();
        for (String line : Files.readAllLines(SCRIPT)) {
            Matcher m = PATH_PATTERN.matcher(line);
            while (m.find()) {
                fromScript.add(ApiVersion.parse(m.group(1)).path());
            }
        }

        // A silently-empty scan would pass this test while proving nothing.
        assertThat(fromScript)
                .as("no PathPattern found in %s — the scan found nothing to check", SCRIPT)
                .isNotEmpty();
        assertThat(fromScript)
                .as("every CloudFront cached behavior must be guarded against encoded queries")
                .isEqualTo(new TreeSet<>(GatewayProxyService.CDN_CACHED_PATHS));
    }

    @Test
    void theGuardedPathsAreExactlyTheLocalStandInsCachedLocations() throws IOException {
        Set<String> cached = new TreeSet<>();
        String current = null;
        for (String line : Files.readAllLines(NGINX)) {
            Matcher m = LOCATION.matcher(line);
            if (m.find()) {
                current = m.group(1);
            } else if (line.contains("proxy_cache_key") && current != null) {
                cached.add(ApiVersion.parse(current).path());
            }
        }

        assertThat(cached)
                .as("no cached location found in %s — the scan found nothing to check", NGINX)
                .isNotEmpty();
        assertThat(cached)
                .as("the local stand-in must cache exactly the behaviors the guard covers")
                .isEqualTo(new TreeSet<>(GatewayProxyService.CDN_CACHED_PATHS));
    }

    /**
     * The scans above pass vacuously if the matchers cannot see a real declaration, and both files
     * are currently correct — so nothing there exercises the detection side. These are the exact
     * spellings each file uses today.
     */
    @Test
    void theMatchersRecogniseTheSpellingsTheseFilesActuallyUse() {
        assertThat(PATH_PATTERN.matcher(
                "    {PathPattern: \"/api/v1/catalog/item\", TargetOriginId: \"alb-origin\",").find())
                .isTrue();
        assertThat(LOCATION.matcher("    location = /api/catalog/similar {").find()).isTrue();
        // The pass-through has no proxy_cache_key and must never be collected as a cached path.
        assertThat(LOCATION.matcher("    location / {").find()).isFalse();
    }

    /** The guarded set is not empty and every entry is version-free, as the guard requires. */
    @Test
    void everyGuardedPathIsAlreadyVersionFree() {
        assertThat(GatewayProxyService.CDN_CACHED_PATHS).isNotEmpty();
        List<String> paths = List.copyOf(GatewayProxyService.CDN_CACHED_PATHS);
        for (String path : paths) {
            assertThat(ApiVersion.parse(path).path())
                    .as("%s must already be version-free", path)
                    .isEqualTo(path);
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=CdnCachedPathConformanceTest
```

Expected: PASS, 4 tests. If it fails, the failure message names the difference — reconcile `CDN_CACHED_PATHS` with the two files rather than loosening the assertion.

- [ ] **Step 3: Verify the test actually detects drift**

Temporarily add a fifth behavior to `scripts/create-cdn-distribution.sh`, immediately after the block at line 199:

```
    {PathPattern: "/api/catalog/probe", TargetOriginId: "alb-origin",
     ViewerProtocolPolicy: "redirect-to-https", CachePolicyId: $item_policy, Compress: true,
```

Re-run the test. Expected: `theGuardedPathsAreExactlyTheCloudFrontCachedBehaviors` FAILS, naming `/api/catalog/probe`.

Then revert **only that file**: `git checkout -- scripts/create-cdn-distribution.sh`, and confirm with `git status` that the new test file is still present and unmodified. Name the single path explicitly — a bare `git checkout --` or `git stash` at this point would destroy the uncommitted test you just wrote, which has happened twice in this repo's recent history.

Do not skip this step. A conformance test that cannot fail is worse than none, because it retires the question: this exact class of test shipped last week asserting a guarantee it did not provide, and only a probe caught it.

- [ ] **Step 4: Add the test to the PR gate**

In `pom.xml`, in the `resilience` profile's `<includes>` list, immediately after the `GatewayCacheKeyQueryTest` entry added in Task 1:

```xml
                <include>**/gateway/CdnCachedPathConformanceTest.java</include>
```

- [ ] **Step 5: Update the cache-key bullet in `12_CDNS.md`**

Replace this passage (currently lines 68-74, inside the `QueryStringBehavior` bullet):

```
  without rejecting the default spelling. Two channels are *not* closed, because
  `cacheKeyIntParam` validates the **decoded** query value while the CDN cache key is built
  from the **raw** query string: a percent-encoded value (`?id=%37`) is a second cache key for
  the same body, and — the more serious direction, since it collapses distinct responses onto
  one key rather than merely splitting one response across several — a percent-encoded
  parameter **name** (`?%69d=7`) presents no whitelisted parameter to the edge at all, so it
  collides with `?%69%64=8` and with a bare parameterless request. See sharp edge 9.
```

with:

```
  without rejecting the default spelling. The percent-encoding channels `cacheKeyIntParam`
  cannot close — it validates the **decoded** query value while the cache key is built from the
  **raw** query string — are closed one layer up instead: the gateway rejects any query string
  containing `%` on the two cached catalog paths, before routing and before authorization. See
  sharp edge 9.
```

- [ ] **Step 6: Rewrite sharp edge 9**

Replace the whole of item 9 (currently lines 333-342) with:

```
9. **The percent-encoding channels are closed at the gateway, not in `cacheKeyIntParam`.**
   `cacheKeyIntParam` validates the decoded value while the edge builds its key from the raw
   query string, which opened three channels. `?id=%37` decoded to a valid `7` and was a second
   cache key over the `id=7` body. `?%69d=7` decoded its parameter *name* to `id` and was
   likewise accepted, but presented no whitelisted parameter to the edge, colliding with
   `?%69%64=8` and with a bare parameterless request. Worst, and unrecorded here until
   2026-08-08: `k` has a default, so `?movieId=1&%6b=200` was a complete request whose body
   reflected `k=200` while the edge saw only `movieId=1` — two bodies under one key, and the `k`
   ceiling of sharp edge 7 bypassed, because the parameter that ceiling bounds is not the
   parameter the edge keys on. `GatewayProxyService.rejectEncodedCacheKeyQuery` now rejects any
   query string containing `%` on `/api/catalog/item` and `/api/catalog/similar` with a
   `no-store` 400, before routing and before authorization. It matches with one `indexOf` and
   parses nothing, so it cannot disagree with Armeria's decoder about what a query string means.
   `CdnCachedPathConformanceTest` pins the guarded set against the `PathPattern` entries in
   `scripts/create-cdn-distribution.sh` and the `proxy_cache_key` blocks in
   `docker/cdn/default.conf.template`, so a fifth cached behavior cannot be added without a
   guard entry. Two residues: a request reaching 6010 directly bypasses the gateway, and
   `cacheKeyIntParam` still accepts `?id=%37` there (nothing caches those responses); and
   whether CloudFront percent-decodes before whitelist matching remains unverified — the guard
   makes the question moot rather than answering it, since no encoded query now reaches a cached
   behavior at all.
```

- [ ] **Step 7: Update the sharp-edge-7 cross-reference**

Replace this passage (currently lines 324-325):

```
   the decoded-value spellings (leading zeros, sign, whitespace, repeats, out-of-range) — it is
   not on its own sufficient to make one cache key map to one body; see sharp edge 9 for the
   percent-encoding channels it does not close.
```

with:

```
   the decoded-value spellings (leading zeros, sign, whitespace, repeats, out-of-range) — it is
   not on its own sufficient to make one cache key map to one body; see sharp edge 9 for the
   percent-encoding channels, which are closed at the gateway instead.
```

- [ ] **Step 8: Verify the docs index still passes and run the gate**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=DocumentationIndexTest
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
```

Expected: both BUILD SUCCESS. No `##` heading was renumbered and no new document was added, so the index is unaffected — the run confirms it.

- [ ] **Step 9: Commit**

```bash
git add src/test/java/com/recsys/application/gateway/CdnCachedPathConformanceTest.java \
        pom.xml docs/system_design/12_CDNS.md
git commit -m "test: pin the guarded cache paths to the committed edge configuration"
```
