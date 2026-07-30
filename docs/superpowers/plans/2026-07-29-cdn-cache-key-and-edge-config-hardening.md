# CDN Cache-Key and Edge-Config Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the cache-key fragmentation vector on the two edge-cached read routes, narrow the edge's path and policy configuration to match every other layer, and make the CDN's hit ratio observable.

**Architecture:** Three independent PRs. PR1 is origin-side Java: one new `BaseApiService` helper that accepts exactly one canonical spelling per cache-key parameter value, wired into the two cacheable routes. PR2 and PR3 are `scripts/create-cdn-distribution.sh` plus documentation — no Java. Nothing changes what is cached or for how long.

**Tech Stack:** Java 17, Armeria 1.28.4 (server + `ServerExtension` test support), JUnit 5, AssertJ, Mockito, Testcontainers (docker-tagged tests only), Maven, bash + AWS CLI v2 + jq.

**Spec:** `docs/superpowers/specs/2026-07-29-cdn-cache-key-and-edge-config-hardening-design.md`. Read it before starting — every task below implements a numbered finding from it (F1–F5).

## Global Constraints

- **JDK 17 is required.** Build and test with `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`. On JDK 25 a clean compile fails on two pre-existing files unrelated to this work.
- **The PR gate runs only `-Presilience`**, whose `<includes>` in `pom.xml` is an explicit allow-list, and whose `<excludedGroups>` is `load,docker`. A test is merge-blocking only if it is both non-docker and listed in that profile. Adding a `@Tag("docker")` class to the includes is forbidden — it would imply coverage the gate does not provide.
- **Never commit to `main`; every change lands via PR.** Work on branch `fix/cdn-cache-key-and-edge-config-hardening`, already created off `main`.
- **Do not renumber existing `##` sections** in `docs/system_design/*.md`. Weave additions into the existing numbered investigation.
- Files under `docs/superpowers/` are exempt from `DocumentationIndexTest`'s README-index requirement; files under `docs/system_design/` and `docs/runbooks/` are not — but every file this plan touches is already indexed, so no README edit is needed.
- **End every commit message with:** `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`
- Exact cache-header strings that must not change for valid requests:
  - `/item`: `public, s-maxage=3600, stale-while-revalidate=86400, stale-if-error=86400`
  - `/similar`: `public, s-maxage=300, stale-while-revalidate=3600, stale-if-error=3600`

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `src/main/java/com/recsys/api/serving/BaseApiService.java` | Modify | Add `cacheKeyIntParam` (2 overloads) next to the existing param helpers. |
| `src/main/java/com/recsys/api/serving/CatalogService.java` | Modify | `/item` uses the new helper for `id`. |
| `src/main/java/com/recsys/api/serving/RecommendationService.java` | Modify | `/similar` uses it for `movieId` and `k`. |
| `src/test/java/com/recsys/api/serving/CacheKeyParamTest.java` | Create | Unit coverage of the helper's accept/reject rules through a fixture route. |
| `src/test/java/com/recsys/api/serving/CatalogCacheHeadersTest.java` | Modify | `/item` rejection cases. |
| `src/test/java/com/recsys/api/serving/SimilarCacheHeadersTest.java` | Modify | `/similar` rejection cases. |
| `src/test/java/com/recsys/api/serving/LocalCdnCacheTest.java` | Modify | Docker-tagged: a rejection is not cached; a glob-adjacent path is BYPASS. |
| `pom.xml` | Modify | Add the three non-docker test classes to the `resilience` includes. |
| `scripts/create-cdn-distribution.sh` | Modify | Exact path patterns; cache-policy update-or-create; monitoring subscription. |
| `docker/cdn/default.conf.template` | Modify | Comment tying `location =` to the now-exact CloudFront patterns. |
| `docs/system_design/12_CDNS.md` | Modify | TTL model, error-caching model, canonical cache-key rule, new sharp edge. |
| `docs/runbooks/cdn-operations.md` | Modify | Scope the source-of-truth claim; monitoring verification step. |
| `docs/system_design/09_API_Gateway.md` | Modify | Record the breaking change in the compatibility contract. |

---

## PR1 — Canonicalize the cache-key surface at the origin (F1)

### Task 1: The `cacheKeyIntParam` helper

**Files:**
- Modify: `src/main/java/com/recsys/api/serving/BaseApiService.java` (add imports; add methods after `optionalBoundedIntParam`, which ends at line 207)
- Create: `src/test/java/com/recsys/api/serving/CacheKeyParamTest.java`
- Modify: `pom.xml` (resilience profile includes)

**Interfaces:**
- Consumes: nothing from earlier tasks. Uses the existing `BaseApiService.BadRequestException` (a `protected static final` nested class) and `ServiceRequestContext.queryParams(String)`, which returns `List<String>` of every occurrence of a parameter.
- Produces: `protected static int cacheKeyIntParam(ServiceRequestContext ctx, String name)` — required, full `int` range. And `protected static int cacheKeyIntParam(ServiceRequestContext ctx, String name, Integer defaultValue, int min, int max)` — `defaultValue == null` means required. Both throw `BadRequestException`. Tasks 2 and 3 call these.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/api/serving/CacheKeyParamTest.java`. The fixture-route pattern mirrors `BaseApiServiceCachingTest`, which drives `BaseApiService` helpers through a real in-process Armeria server.

```java
package com.recsys.api.serving;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the cache-key parameter contract: exactly one canonical spelling per value reaches
 * the origin, so the set of CloudFront cache keys is the set of distinct response bodies.
 * See docs/superpowers/specs/2026-07-29-cdn-cache-key-and-edge-config-hardening-design.md.
 */
class CacheKeyParamTest {

    /** Fixture route with the same shape as the two real cacheable routes. */
    static final class Probe extends BaseApiService {
        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
            try {
                int id = cacheKeyIntParam(ctx, "id");
                int k = cacheKeyIntParam(ctx, "k", 10, 1, 200);
                return writeCacheableJson(HttpStatus.OK, Map.of("id", id, "k", k),
                        HttpCaching.publicCache(3600, 86400), req);
            } catch (BadRequestException e) {
                return writeNoStoreError(HttpStatus.BAD_REQUEST, e.getMessage());
            }
        }
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/probe", new Probe());
        }
    };

    private AggregatedHttpResponse get(String query) {
        return WebClient.of(server.httpUri()).get("/probe" + query).aggregate().join();
    }

    private void assertRejected(String query) {
        AggregatedHttpResponse res = get(query);
        assertThat(res.status()).as("query %s", query).isEqualTo(HttpStatus.BAD_REQUEST);
        // A rejection must never be cacheable: it is reachable on a cached behavior, and an
        // edge-pinned rejection would outlive the bad request that caused it.
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void canonicalValuesAreAccepted() {
        AggregatedHttpResponse res = get("?id=7&k=5");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).contains("\"id\":7").contains("\"k\":5");
    }

    @Test
    void absentOptionalParamUsesItsDefault() {
        AggregatedHttpResponse res = get("?id=7");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).contains("\"k\":10");
    }

    @Test
    void rangeBoundariesAreAccepted() {
        assertThat(get("?id=7&k=1").status()).isEqualTo(HttpStatus.OK);
        assertThat(get("?id=7&k=200").status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void zeroAndNegativeAreCanonicalWhenInRange() {
        // id has the full int range, so 0 and -5 are valid spellings and must survive: the
        // real /item route answers them with a 404, and changing that to a 400 would be a
        // status change on an unchanged condition for no cache-key benefit.
        assertThat(get("?id=0").status()).isEqualTo(HttpStatus.OK);
        assertThat(get("?id=-5").status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void leadingZeroSpellingsAreRejected() {
        // The unbounded channel: id=7, 07, 007, 0007 ... all parse to 7 under
        // Integer.parseInt, so each is a distinct edge cache key over one identical body.
        assertRejected("?id=07");
        assertRejected("?id=007");
        assertRejected("?id=0000007");
    }

    @Test
    void signAndWhitespaceSpellingsAreRejected() {
        assertRejected("?id=%2B7");   // "+7" — parseInt accepts it, so it aliases 7
        assertRejected("?id=+7");     // "+" arrives as a space or as a literal '+'; both alias 7
        assertRejected("?id=%207");   // " 7"
        assertRejected("?id=7%20");   // "7 "
        assertRejected("?id=-0");     // aliases 0
    }

    @Test
    void outOfRangeValuesAreRejectedNotClamped() {
        // optionalIntParam would clamp these to 200 and 10 respectively, making every value
        // above 200 and every value below 1 a distinct cache key over one body.
        assertRejected("?id=7&k=201");
        assertRejected("?id=7&k=999999");
        assertRejected("?id=7&k=0");
        assertRejected("?id=7&k=-1");
    }

    @Test
    void valuesWiderThanIntAreRejected() {
        assertRejected("?id=99999999999999999999");
    }

    @Test
    void repeatedParametersAreRejected() {
        // CloudFront's cache key includes every occurrence of a whitelisted parameter, while
        // ctx.queryParam reads only the first — so ?id=1&id=<n> is an unbounded family of
        // keys over one body. Value canonicalization alone cannot close this.
        assertRejected("?id=1&id=2");
        assertRejected("?id=7&k=5&k=6");
    }

    @Test
    void presentButEmptyIsRejected() {
        // "?k=" is a third spelling of the default alongside "?k=10" and an absent k.
        assertRejected("?id=7&k=");
        assertRejected("?id=");
    }

    @Test
    void missingRequiredParameterIsRejected() {
        assertRejected("?k=5");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=CacheKeyParamTest
```

Expected: compilation failure — `cannot find symbol: method cacheKeyIntParam`.

- [ ] **Step 3: Implement the helper**

In `src/main/java/com/recsys/api/serving/BaseApiService.java`, add to the existing import block:

```java
import java.util.List;
import java.util.regex.Pattern;
```

Add this field beside the other statics near the top of the class (after `protected final Logger log`):

```java
    /**
     * One canonical decimal spelling per value: no leading zeros, no sign except a single
     * leading '-', no whitespace, and no "-0" (which aliases "0").
     */
    private static final Pattern CANONICAL_INT = Pattern.compile("0|-?[1-9][0-9]*");
```

Add these methods immediately after `optionalBoundedIntParam` (which ends at line 207):

```java
    /** Required cache-key parameter over the full {@code int} range. */
    protected static int cacheKeyIntParam(ServiceRequestContext ctx, String name) {
        return cacheKeyIntParam(ctx, name, null, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Parses a query parameter that forms part of a CDN cache key, accepting exactly one
     * canonical spelling per value so the set of edge cache keys is the set of distinct
     * response bodies.
     *
     * <p>{@link #optionalIntParam} clamps instead — safe only where no cache key is derived
     * from the value. CloudFront's query-string whitelist bounds <em>which</em> parameters can
     * fragment the cache, not which values, so a clamped whitelisted parameter is itself an
     * unbounded cache-buster: {@code k=201}…{@code k=2147483647} would each be a distinct key
     * over one identical body, and every miss costs a full candidate scan on a public,
     * unauthenticated route. See
     * docs/superpowers/specs/2026-07-29-cdn-cache-key-and-edge-config-hardening-design.md.
     *
     * <p>Rejects, rather than clamps or normalizes, so that the rejection is visible to the
     * caller and cheap for the origin. Every caller reaches a {@code no-store} 400 via its
     * existing {@code BadRequestException} branch.
     *
     * @param defaultValue {@code null} makes the parameter required; a present-but-empty
     *                     value is a rejection either way, since it is a second spelling of
     *                     the default
     */
    protected static int cacheKeyIntParam(ServiceRequestContext ctx, String name,
                                          Integer defaultValue, int min, int max) {
        List<String> values = ctx.queryParams(name);
        if (values.size() > 1) {
            throw new BadRequestException(name + " must not be repeated");
        }
        if (values.isEmpty()) {
            if (defaultValue != null) return defaultValue;
            throw new BadRequestException("missing required query parameter: " + name);
        }
        String value = values.get(0);
        if (!CANONICAL_INT.matcher(value).matches()) {
            throw new BadRequestException(name + " must be a canonical decimal integer: no "
                    + "leading zeros, sign, or whitespace");
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                throw new BadRequestException(name + " must be between " + min + " and " + max);
            }
            return parsed;
        } catch (NumberFormatException e) {
            // Canonical in form but wider than int, e.g. 99999999999999999999.
            throw new BadRequestException(name + " must be between " + min + " and " + max);
        }
    }
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=CacheKeyParamTest
```

Expected: PASS, 11 tests.

- [ ] **Step 5: Add the test to the PR gate**

In `pom.xml`, inside the `resilience` profile's `<includes>`, add this line immediately after `<include>**/serving/EmbeddingSeedRepairTest.java</include>` (line 354):

```xml
                <include>**/serving/CacheKeyParamTest.java</include>
```

Verify it runs under the gate:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience -Dtest=CacheKeyParamTest
```

Expected: PASS. (Running the whole profile is the Task 3 check; here just confirm the include resolves rather than silently matching nothing.)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/api/serving/BaseApiService.java \
        src/test/java/com/recsys/api/serving/CacheKeyParamTest.java pom.xml
git commit -m "$(cat <<'EOF'
feat: accept one canonical spelling per cache-key parameter

The query-string whitelist bounds which parameters can fragment the edge
cache, not which values, so a clamped whitelisted parameter is itself an
unbounded cache-buster. cacheKeyIntParam rejects non-canonical spellings
(leading zeros, sign, whitespace, -0), out-of-range values, repeated
occurrences, and present-but-empty values, so one cache key maps to one
body. No call sites yet.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Wire `/item` onto the helper

**Files:**
- Modify: `src/main/java/com/recsys/api/serving/CatalogService.java:38`
- Modify: `src/test/java/com/recsys/api/serving/CatalogCacheHeadersTest.java`
- Modify: `pom.xml`

**Interfaces:**
- Consumes: `cacheKeyIntParam(ServiceRequestContext, String)` from Task 1.
- Produces: nothing new. `GET /item?id=<canonical int>` behaves exactly as before, including its `Cache-Control` and `ETag`.

- [ ] **Step 1: Write the failing tests**

Append these to `src/test/java/com/recsys/api/serving/CatalogCacheHeadersTest.java`, before the closing brace. The existing static mock returns a `Movie` for id 1, `null` for any other id, and throws for id 500.

```java
    @Test
    void item_leadingZeroIdIsRejected() {
        // id=01 parses to 1 and would return movie 1's body under a second cache key.
        AggregatedHttpResponse res = client().get("/item?id=01").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void item_repeatedIdIsRejected() {
        AggregatedHttpResponse res = client().get("/item?id=1&id=2").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void item_negativeIdIsStillNotFound() {
        // Canonical, so it reaches the lookup and 404s as it always did. Turning this into a
        // 400 would be a status change on an unchanged condition for no cache-key benefit.
        AggregatedHttpResponse res = client().get("/item?id=-5").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-store");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=CatalogCacheHeadersTest
```

Expected: `item_leadingZeroIdIsRejected` and `item_repeatedIdIsRejected` FAIL — both currently return 200 with movie 1's body, because `requiredIntParam` accepts `01` and `ctx.queryParam` reads only the first `id`. `item_negativeIdIsStillNotFound` passes already; it is a regression guard for Step 3.

- [ ] **Step 3: Switch the call site**

In `src/main/java/com/recsys/api/serving/CatalogService.java`, replace line 38:

```java
                    int movieId = requiredIntParam(ctx, "id");
```

with:

```java
                    // Cache-key parameter: canonical spellings only, so one edge cache key
                    // maps to one body. requiredIntParam would accept 007/+7/" 7" as aliases.
                    int movieId = cacheKeyIntParam(ctx, "id");
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=CatalogCacheHeadersTest
```

Expected: PASS, 9 tests. In particular `item_isPubliclyCacheableWithEtag`, `item_revalidatesTo304`, and `item_notFoundIsNotCacheable` must still pass unchanged — valid callers see identical behavior.

- [ ] **Step 5: Add the test class to the PR gate**

In `pom.xml`, after the `CacheKeyParamTest` include added in Task 1, add:

```xml
                <include>**/serving/CatalogCacheHeadersTest.java</include>
```

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience -Dtest=CatalogCacheHeadersTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/api/serving/CatalogService.java \
        src/test/java/com/recsys/api/serving/CatalogCacheHeadersTest.java pom.xml
git commit -m "$(cat <<'EOF'
fix: canonicalize the /item cache key

id=7, 07, 007 and " 7" all parsed to 7, so each was a distinct CloudFront
cache key over one identical body, and ?id=1&id=<n> was an unbounded
family of them. A canonical id is now required; negative and zero ids
stay canonical and keep their existing 404.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Wire `/similar` onto the helper

**Files:**
- Modify: `src/main/java/com/recsys/api/serving/RecommendationService.java:152-153`
- Modify: `src/test/java/com/recsys/api/serving/SimilarCacheHeadersTest.java`
- Modify: `pom.xml`

**Interfaces:**
- Consumes: both `cacheKeyIntParam` overloads from Task 1.
- Produces: nothing new. `k` keeps its default of 10 and its `[1, 200]` range; out-of-range is now a rejection rather than a clamp.

- [ ] **Step 1: Write the failing tests**

Append to `src/test/java/com/recsys/api/serving/SimilarCacheHeadersTest.java`, before the closing brace:

```java
    @Test
    void similar_outOfRangeKIsRejectedNotClamped() {
        // Was 200 with k clamped to 200. Every k above 200 was a distinct cache key over the
        // k=200 body, and every miss ran a full candidate scan.
        AggregatedHttpResponse res = client().get("/similar?movieId=1&k=201").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void similar_nonPositiveKIsRejected() {
        // Was 200 with k silently reset to the default of 10.
        assertThat(client().get("/similar?movieId=1&k=0").aggregate().join().status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(client().get("/similar?movieId=1&k=-1").aggregate().join().status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void similar_repeatedMovieIdIsRejected() {
        AggregatedHttpResponse res =
                client().get("/similar?movieId=1&movieId=2").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void similar_leadingZeroMovieIdIsRejected() {
        AggregatedHttpResponse res = client().get("/similar?movieId=01").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void similar_boundaryKIsStillCacheable() {
        // k=200 is the top of the range and must keep serving a cacheable 200 with the
        // unchanged /similar cache-control string.
        AggregatedHttpResponse res = client().get("/similar?movieId=1&k=200").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL))
                .isEqualTo("public, s-maxage=300, stale-while-revalidate=3600, stale-if-error=3600");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=SimilarCacheHeadersTest
```

Expected: the four rejection tests FAIL with 200 instead of 400. `similar_boundaryKIsStillCacheable` passes already; it is the regression guard.

- [ ] **Step 3: Switch the call sites**

In `src/main/java/com/recsys/api/serving/RecommendationService.java`, replace lines 152-153:

```java
                    int movieId = requiredIntParam(ctx, "movieId");
                    int k = optionalIntParam(ctx, "k", 10, 1, 200);
```

with:

```java
                    // Both are cache-key parameters (the recsys-similar policy whitelists
                    // movieId and k), so canonical spellings only and no clamping: a clamped
                    // k made every value above 200 a distinct key over the k=200 body.
                    int movieId = cacheKeyIntParam(ctx, "movieId");
                    int k = cacheKeyIntParam(ctx, "k", 10, 1, 200);
```

Leave `RecommendationService.V1`'s `optionalIntParam(ctx, "k", 20, 1, 100)` on line 60 alone — `/getrecommendation` is not edge-cached, so clamping derives no cache key and tightening it would break clients for no benefit.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=SimilarCacheHeadersTest
```

Expected: PASS, 10 tests.

- [ ] **Step 5: Add the test class to the PR gate and run the whole gate**

In `pom.xml`, after the `CatalogCacheHeadersTest` include, add:

```xml
                <include>**/serving/SimilarCacheHeadersTest.java</include>
```

Then run the full gate and the full default suite, since Task 2 and 3 changed shared request-parsing behavior:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test
```

Expected: both green. If another test asserts a clamped `k` on `/similar` or a non-canonical `id`, fix that test to the new contract rather than weakening the helper — and note it in the commit body.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/api/serving/RecommendationService.java \
        src/test/java/com/recsys/api/serving/SimilarCacheHeadersTest.java pom.xml
git commit -m "$(cat <<'EOF'
fix: reject rather than clamp the /similar cache-key parameters

k is on the recsys-similar cache-key whitelist and was clamped to
[1,200], so k=201..2147483647 were each a distinct edge cache key over
one identical body — an unbounded cache-buster on the only route that is
public, unauthenticated and compute-heavy, with gateway rate limiting off
by default. movieId gains the same canonical-spelling rule. V1's k keeps
clamping: /getrecommendation is not edge-cached.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Prove the rejection is not itself cacheable, and record the contract change

**Files:**
- Modify: `src/test/java/com/recsys/api/serving/LocalCdnCacheTest.java`
- Modify: `docs/system_design/09_API_Gateway.md` (the compatibility contract's "Deprecated today" area, around line 155)
- Modify: `docs/system_design/12_CDNS.md` (§1 cache-key discussion around line 52, and the sharp-edges list)

**Interfaces:**
- Consumes: the rejection behavior from Tasks 2 and 3.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the docker-tagged harness test**

The stub origin in `LocalCdnCacheTest` is deliberately minimal and does not run the real services. The property worth proving is that nginx honours a `no-store` 400 on a *cacheable* location — `location = /api/catalog/similar`, where `proxy_cache` is active — not merely on the default-deny `location /`, which bypasses the cache structurally regardless of any response header. `SimilarCacheHeadersTest` is what proves the real origin returns that 400.

Amend the existing `/api/catalog/similar` stub handler inside `startAll()` so it mirrors the real route's new contract: when the `k` query parameter is present and outside `[1, 200]`, return a `no-store` 400 instead of the cacheable 200. Increment `similarOriginHits` on every request including the rejection, so the counter still reflects origin arrivals, and keep the canonical-`k` path byte-identical to what it does today so the existing `similarCacheKeyIncludesK_missThenHit` test keeps passing unchanged:

```java
                // Mirrors the /api/catalog/item handler's shape, but keyed on movieId+k so the
                // more complex two-parameter cache key ($uri|$arg_movieId|$arg_k) is exercised.
                // Also mirrors what cacheKeyIntParam now returns for an out-of-range `k`: a
                // no-store 400. The real contract is proven by SimilarCacheHeadersTest; this
                // fixture exists to show what the cache does with such a response.
                .service("/api/catalog/similar", (ctx, req) -> {
                    similarOriginHits.incrementAndGet();
                    String secret = req.headers().get(HttpHeaderNames.of("x-origin-secret"));
                    receivedSecrets.add(secret == null ? "<absent>" : secret);
                    String movieId = ctx.queryParam("movieId");
                    String k = ctx.queryParam("k");
                    if (k != null) {
                        int kValue = Integer.parseInt(k);
                        if (kValue < 1 || kValue > 200) {
                            return HttpResponse.of(ResponseHeaders.builder(HttpStatus.BAD_REQUEST)
                                    .contentType(MediaType.JSON_UTF_8)
                                    .set(HttpHeaderNames.CACHE_CONTROL, "no-store")
                                    .build(), HttpData.ofUtf8("{\"error\":\"k must be between 1 and 200\"}"));
                        }
                    }
                    byte[] body = ("{\"movieId\":" + movieId + ",\"k\":" + k + ",\"neighbors\":[]}")
                            .getBytes();
                    // ...unchanged below: ETag / If-None-Match / 200 OK with publicCache(300, 3600)
                })
```

Do **not** add a `/api/catalog/reject` stub or any new `location` block to `docker/cdn/default.conf.template` — the point is to exercise a location where `proxy_cache` is already active. Add the test:

```java
    // Uses movieId=77, disjoint from the ids the other tests use (1, 7, 3, 42, 55), per the
    // isolation note above.
    @Test
    void aNoStoreRejectionOnACacheableLocationIsNeverCached() {
        int before = similarOriginHits.get();

        AggregatedHttpResponse first =
                cdn().get("/api/catalog/similar?movieId=77&k=201").aggregate().join();
        AggregatedHttpResponse second =
                cdn().get("/api/catalog/similar?movieId=77&k=201").aggregate().join();

        assertThat(first.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(second.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        // The load-bearing part: this path matches `location = /api/catalog/similar`, where
        // proxy_cache IS active — so unlike the default-deny block, nothing here bypasses the
        // cache structurally. Both requests reaching the origin proves nginx honoured the
        // origin's Cache-Control: no-store on a cacheable location. A rejection that WERE
        // cached would be served to every viewer at that POP who sent a valid request under
        // the same key.
        assertThat(similarOriginHits.get()).isEqualTo(before + 2);
        // And the k=201 rejection did not disturb the canonical entry for the same movieId.
        assertThat(cdn().get("/api/catalog/similar?movieId=77&k=5").aggregate().join().status())
                .isEqualTo(HttpStatus.OK);
    }
```

- [ ] **Step 2: Run the docker-tagged test**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test \
  -DexcludedGroups=load -Dgroups=docker -Dtest=LocalCdnCacheTest
```

Expected: PASS, 7 tests. Requires a running Docker daemon. If Docker is unavailable, say so in the PR description rather than deleting the test — it is not merge-blocking by design.

- [ ] **Step 3: Record the breaking change in the compatibility contract**

In `docs/system_design/09_API_Gateway.md`, immediately after the "Deprecated today" table (which ends just before "**Removal is never automatic.**"), insert:

```markdown
**Breaking changes shipped as reviewed exceptions:**

| Date | Change | Why it did not wait for notice |
|---|---|---|
| 2026-07-29 | `GET /api/catalog/item` and `/api/catalog/similar` (either spelling) reject non-canonical numeric parameters — leading zeros, `+`, whitespace, `-0` — and reject `k` outside `[1, 200]` instead of clamping it. Repeated occurrences of `id`, `movieId` or `k` are also rejected. All return a `no-store` 400. | These parameters form the CloudFront cache key. Clamping and alias-accepting made them unbounded cache-busters on the only public, unauthenticated, compute-heavy route, with gateway rate limiting off by default. Waiting twelve months would have left the amplification open for twelve months. Affected callers were already receiving something other than what they asked for — a clamped `k` silently returned fewer results. See `docs/superpowers/specs/2026-07-29-cdn-cache-key-and-edge-config-hardening-design.md`. |

An exception is a reviewed decision recorded here, not a precedent: the default remains a
new version plus twelve months' notice.
```

- [ ] **Step 4: Weave the cache-key rule into 12_CDNS**

In `docs/system_design/12_CDNS.md` §1, the `QueryStringBehavior: whitelist` bullet currently ends with "…and act as an origin-DoS amplifier." Append to that bullet:

```markdown
  The whitelist bounds *which* parameters can fragment the cache, not which **values** — so a
  whitelisted parameter that the origin clamps or alias-accepts is itself an unbounded
  cache-buster. `k` was exactly that until 2026-07-29: `k=201`…`k=2147483647` all clamped to
  200 and each was a distinct cache key over one identical body, as were `id=007` and
  `?id=1&id=<n>`. The origin now accepts one canonical spelling per value
  (`BaseApiService.cacheKeyIntParam`) and rejects everything else with a `no-store` 400, so
  one cache key maps to one body. One bounded alias remains and is accepted deliberately: an
  absent `k` and an explicit `k=10` are two keys for the same body, which cannot be removed
  without rejecting the default spelling.
```

Then add a sharp edge after the existing item 6:

```markdown
7. **A whitelisted cache-key parameter is a cache-buster unless the origin canonicalizes it.**
   The query-string whitelist is necessary but not sufficient — it constrains parameter names,
   while the fragmentation budget is set by the number of accepted *spellings* per value. Any
   new cacheable route must parse its cache-key parameters with `cacheKeyIntParam`, not
   `optionalIntParam`; clamping is only safe off the cached behaviors.
```

- [ ] **Step 5: Verify the docs still pass their gate**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
```

Expected: PASS — `DocumentedMechanismTest` checks that every source path and method the docs name still exists, so a typo in `BaseApiService.cacheKeyIntParam` fails here.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/recsys/api/serving/LocalCdnCacheTest.java \
        docs/system_design/09_API_Gateway.md docs/system_design/12_CDNS.md
git commit -m "$(cat <<'EOF'
docs: record the cache-key rule, and the contract exception it needed

The whitelist constrains parameter names; the fragmentation budget is set
by how many spellings per value the origin accepts. Records that in
12_CDNS as sharp edge 7, and logs the breaking validation change in 09's
compatibility contract as an explicitly reviewed exception to the
twelve-month notice rule rather than a silent one. Adds a docker-tagged
harness case showing a no-store rejection creates no cache entry.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 7: Open PR1**

```bash
git push -u origin fix/cdn-cache-key-and-edge-config-hardening
gh pr create --title "CDN: canonicalize the cache-key surface on the two edge-cached reads" --body "$(cat <<'EOF'
## Summary

The query-string whitelist exists to stop cache-key fragmentation, but it bounds *which*
parameters can fragment the cache, not which values. `k` is on the whitelist for `/similar`
and the origin clamped it, so `k=201`…`k=2147483647` were each a distinct CloudFront cache
key over one byte-identical body. `id`/`movieId` aliased the same way through leading zeros,
`+`, whitespace, and repeated occurrences.

Every distinct key is an origin miss running a `k × 5`-candidate scan on the one route that
is public, unauthenticated, and compute-heavy — with gateway rate limiting off by default and
keyed to a shared `anonymous` principal even when on, because the cached behaviors strip
`Authorization`.

`BaseApiService.cacheKeyIntParam` now accepts exactly one canonical spelling per value.
Valid callers see byte-identical responses with unchanged `Cache-Control` and `ETag`.

## Breaking change, shipped as a reviewed exception

`k` outside `[1, 200]` is now a `no-store` 400 rather than a silent clamp, and non-canonical
numeric spellings are rejected. Under `09_API_Gateway.md`'s compatibility contract that is
breaking and would normally need a new version plus twelve months' notice; the exception and
its reasoning are recorded in that document rather than left implicit.

## Testing

- `CacheKeyParamTest` (new, gated), `CatalogCacheHeadersTest` and `SimilarCacheHeadersTest`
  (extended, now gated) — all three added to `-Presilience`, so they block a merge.
- `LocalCdnCacheTest` (docker, not gated) — a `no-store` rejection creates no cache entry.

Design: `docs/superpowers/specs/2026-07-29-cdn-cache-key-and-edge-config-hardening-design.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## PR2 — Narrow the edge, and unfreeze the cache policies (F2, F3)

Start from a fresh branch off PR1's head so review stays separable:

```bash
git checkout -b fix/cdn-exact-path-patterns-and-policy-updates
```

### Task 5: Exact path patterns

**Files:**
- Modify: `scripts/create-cdn-distribution.sh:122-139`
- Modify: `docker/cdn/default.conf.template` (comment only)
- Modify: `src/test/java/com/recsys/api/serving/LocalCdnCacheTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: the four `CacheBehaviors` entries keyed on exact paths. Task 6 edits the same script but a different function.

- [ ] **Step 1: Write the failing harness test**

The nginx template already uses `location =`, so this test passes immediately — it exists to pin exactness so a future widening of either side is caught. Add a stub route in `LocalCdnCacheTest.startAll()`, next to the others:

```java
                // A path that the OLD CloudFront glob "/api/catalog/item*" would have matched
                // but that is not the exact item route. It must land in the default-deny
                // behavior, not in a cached one — the glob was wider than the exact
                // GATEWAY_PUBLIC_PATHS entry, so on a glob-matched path the edge dropped
                // Authorization while the gateway still treated the path as private.
                .service("/api/catalog/items", (ctx, req) -> {
                    globAdjacentOriginHits.incrementAndGet();
                    byte[] body = "{\"items\":[]}".getBytes();
                    return HttpResponse.of(ResponseHeaders.builder(HttpStatus.OK)
                            .contentType(MediaType.JSON_UTF_8)
                            .set(HttpHeaderNames.CACHE_CONTROL, HttpCaching.publicCache(3600, 86400))
                            .set(HttpHeaderNames.ETAG, HttpCaching.etagFor(body))
                            .build(), HttpData.wrap(body));
                })
```

With its counter beside the others:

```java
    /** Origin hits on the glob-adjacent path — it must never be served from cache. */
    static final AtomicInteger globAdjacentOriginHits = new AtomicInteger();
```

And the test:

```java
    @Test
    void aGlobAdjacentPathIsNotCachedEvenWhenTheOriginSaysItIsCacheable() {
        int before = globAdjacentOriginHits.get();

        AggregatedHttpResponse first = cdn().get("/api/catalog/items?id=1").aggregate().join();
        AggregatedHttpResponse second = cdn().get("/api/catalog/items?id=1").aggregate().join();

        // The origin sends s-maxage, so this is cacheable content by HTTP rules. It is not
        // cached because scope is decided by the behavior, not by the response: `location =`
        // here, an exact PathPattern at CloudFront.
        assertThat(cacheStatus(first)).isEqualTo("BYPASS");
        assertThat(cacheStatus(second)).isEqualTo("BYPASS");
        assertThat(globAdjacentOriginHits.get()).isEqualTo(before + 2);
    }
```

- [ ] **Step 2: Run it**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test \
  -DexcludedGroups=load -Dgroups=docker -Dtest=LocalCdnCacheTest
```

Expected: PASS, 8 tests. It passes before the script change because nginx was already exact — that asymmetry is the finding. If it fails, the nginx `location` blocks are not exact and must be fixed first.

- [ ] **Step 3: Narrow the CloudFront patterns**

In `scripts/create-cdn-distribution.sh`, insert this comment immediately above the `CacheBehaviors:` line (currently line 122):

```bash
  # PathPatterns are EXACT — no trailing wildcard. CloudFront does not consider query strings
  # when evaluating a path pattern, so "/api/catalog/item" already matches "?id=1"; a "*"
  # would only widen the match to /api/catalog/item<anything>, which is wider than the exact
  # GATEWAY_PUBLIC_PATHS entry the gateway authorizes. On a glob-matched path the edge drops
  # Authorization while the gateway still treats the path as private. Mirrored by
  # docker/cdn/default.conf.template's `location =` blocks.
```

Then remove the trailing `*` from all four `PathPattern` values:

- `"/api/catalog/item*"` → `"/api/catalog/item"`
- `"/api/v1/catalog/item*"` → `"/api/v1/catalog/item"`
- `"/api/catalog/similar*"` → `"/api/catalog/similar"`
- `"/api/v1/catalog/similar*"` → `"/api/v1/catalog/similar"`

- [ ] **Step 4: Verify the script parses and the payload is well-formed**

```bash
bash -n scripts/create-cdn-distribution.sh
grep -c 'PathPattern: "/api/\(v1/\)\?catalog/\(item\|similar\)"' scripts/create-cdn-distribution.sh
grep -c 'PathPattern: "[^"]*\*"' scripts/create-cdn-distribution.sh
```

Expected: no syntax errors; the first count is `4`; the second is `0`.

- [ ] **Step 5: Tie the harness to the narrowed config**

In `docker/cdn/default.conf.template`, update the four behavior-mirror comments so the exactness is explicit rather than incidental. Change:

```
    # --- Mirrors: CacheBehavior /api/catalog/item*, cache key whitelists `id` ----------
```

to:

```
    # --- Mirrors: CacheBehavior /api/catalog/item, cache key whitelists `id` ----------
    # `location =` is an EXACT match, mirroring the wildcard-free CloudFront PathPattern.
    # Widening this to a prefix would make the harness stop mirroring the distribution and
    # would silently cache /api/catalog/item<anything> with Authorization dropped.
```

Apply the same rename (dropping `*`) to the other three mirror comments; the extra two-line note only needs to appear once, on the first block.

- [ ] **Step 6: Re-run the harness test and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test \
  -DexcludedGroups=load -Dgroups=docker -Dtest=LocalCdnCacheTest
git add scripts/create-cdn-distribution.sh docker/cdn/default.conf.template \
        src/test/java/com/recsys/api/serving/LocalCdnCacheTest.java
git commit -m "$(cat <<'EOF'
fix: make the four cache behaviors match exact paths

CloudFront does not consider query strings when evaluating a path
pattern, so the trailing "*" bought nothing and only widened the match to
/api/catalog/item<anything> — wider than the exact GATEWAY_PUBLIC_PATHS
entry, and wide in the dangerous direction, since the edge drops
Authorization on a matched path while the gateway still treats it as
private. The nginx harness was already exact via `location =`, so it
could not have caught this; the comments now tie the two together.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Make a cache-policy edit apply instead of vanishing

**Files:**
- Modify: `scripts/create-cdn-distribution.sh:63-89`

**Interfaces:**
- Consumes: nothing.
- Produces: `ensure_cache_policy` still echoes a policy id on stdout, so the two call sites (`item_policy=…`, `similar_policy=…`) are unchanged. All diagnostics must go to stderr, or they will be captured into the policy id.

- [ ] **Step 1: Replace the function body**

Replace `ensure_cache_policy` (lines 63-84) with:

```bash
# Create the policy, or update it when this script's definition has drifted from what is
# deployed. This used to return the existing id unconditionally, which meant a TTL or
# query-whitelist edit here was a silent no-op from the second run onward — the cache key and
# the TTL ceiling live in the POLICY, not in the distribution config, so they are not covered
# by update-distribution's replace-everything semantics.
#
# All diagnostics go to stderr: stdout is the policy id, consumed by the callers below.
ensure_cache_policy() {
  local name="$1" min_ttl="$2" default_ttl="$3" max_ttl="$4" query_keys="$5"

  local desired
  desired="$(jq -nc \
    --arg name "$name" --argjson min "$min_ttl" --argjson def "$default_ttl" \
    --argjson max "$max_ttl" --argjson keys "$query_keys" '{
      Name: $name, MinTTL: $min, DefaultTTL: $def, MaxTTL: $max,
      ParametersInCacheKeyAndForwardedToOrigin: {
        EnableAcceptEncodingGzip: true, EnableAcceptEncodingBrotli: true,
        HeadersConfig: {HeaderBehavior: "none"},
        CookiesConfig: {CookieBehavior: "none"},
        QueryStringsConfig: {QueryStringBehavior: "whitelist",
                             QueryStrings: {Quantity: ($keys|length), Items: $keys}}
      }}')"

  local existing
  existing="$(aws cloudfront list-cache-policies --type custom \
    --query "CachePolicyList.Items[?CachePolicy.CachePolicyConfig.Name=='${name}'].CachePolicy.Id" \
    --output text 2>/dev/null || true)"

  if [[ -z "$existing" || "$existing" == "None" ]]; then
    aws cloudfront create-cache-policy --cache-policy-config "$desired" \
      --query 'CachePolicy.Id' --output text
    return
  fi

  # Compare only the fields this script manages, so an AWS-added field (or a Comment set in
  # the console) does not read as drift and trigger an update on every run.
  local norm='{MinTTL, DefaultTTL, MaxTTL,
    gzip:   .ParametersInCacheKeyAndForwardedToOrigin.EnableAcceptEncodingGzip,
    brotli: .ParametersInCacheKeyAndForwardedToOrigin.EnableAcceptEncodingBrotli,
    hdr:    .ParametersInCacheKeyAndForwardedToOrigin.HeadersConfig.HeaderBehavior,
    cookie: .ParametersInCacheKeyAndForwardedToOrigin.CookiesConfig.CookieBehavior,
    qsb:    .ParametersInCacheKeyAndForwardedToOrigin.QueryStringsConfig.QueryStringBehavior,
    qs:     ((.ParametersInCacheKeyAndForwardedToOrigin.QueryStringsConfig.QueryStrings.Items // []) | sort)}'

  local current
  current="$(aws cloudfront get-cache-policy --id "$existing" \
    --query 'CachePolicy.CachePolicyConfig' --output json)"

  if [[ "$(jq -cS "$norm" <<<"$current")" == "$(jq -cS "$norm" <<<"$desired")" ]]; then
    echo "$existing"
    return
  fi

  echo "Cache policy ${name} (${existing}) has drifted from this script; updating." >&2
  echo "  deployed: $(jq -cS "$norm" <<<"$current")" >&2
  echo "  desired:  $(jq -cS "$norm" <<<"$desired")" >&2
  echo "  NOTE: this changes cache behavior at every edge as it propagates." >&2
  local etag
  etag="$(aws cloudfront get-cache-policy --id "$existing" --query 'ETag' --output text)"
  aws cloudfront update-cache-policy --id "$existing" --if-match "$etag" \
    --cache-policy-config "$desired" --query 'CachePolicy.Id' --output text
}
```

- [ ] **Step 2: Record the TTL coupling at the call sites**

Replace the comment above the two `ensure_cache_policy` invocations (currently lines 86-87) with:

```bash
# Cache keys whitelist ONLY the meaningful params. Forwarding all query strings would let
# ?id=1&cachebuster=N fragment the cache arbitrarily and act as an origin-DoS amplifier. The
# whitelist bounds parameter NAMES only — the origin canonicalizes the values
# (BaseApiService.cacheKeyIntParam), without which a whitelisted param is a cache-buster too.
#
# The two ceilings are not slack, and must move together with HttpCaching.publicCache:
#   MaxTTL   CloudFront serves stale content for the LESSER of the origin's
#            stale-while-revalidate / stale-if-error window and MaxTTL, and drops the object
#            entirely after MaxTTL. These MaxTTLs sit EXACTLY at the stale windows (86400 for
#            item, 3600 for similar), so raising a stale window without raising MaxTTL is a
#            silent no-op.
#   MinTTL   Must stay 0. Above zero CloudFront ignores Cache-Control: no-store, which every
#            error branch on these two routes depends on.
```

- [ ] **Step 3: Verify the drift comparison with canned input**

This needs no AWS access. Confirm the normalizer treats an AWS-added field as identical and a real TTL change as drift:

```bash
bash -n scripts/create-cdn-distribution.sh

norm='{MinTTL, DefaultTTL, MaxTTL,
  gzip:   .ParametersInCacheKeyAndForwardedToOrigin.EnableAcceptEncodingGzip,
  brotli: .ParametersInCacheKeyAndForwardedToOrigin.EnableAcceptEncodingBrotli,
  hdr:    .ParametersInCacheKeyAndForwardedToOrigin.HeadersConfig.HeaderBehavior,
  cookie: .ParametersInCacheKeyAndForwardedToOrigin.CookiesConfig.CookieBehavior,
  qsb:    .ParametersInCacheKeyAndForwardedToOrigin.QueryStringsConfig.QueryStringBehavior,
  qs:     ((.ParametersInCacheKeyAndForwardedToOrigin.QueryStringsConfig.QueryStrings.Items // []) | sort)}'

base='{"Name":"recsys-item","MinTTL":0,"DefaultTTL":3600,"MaxTTL":86400,
  "ParametersInCacheKeyAndForwardedToOrigin":{"EnableAcceptEncodingGzip":true,
  "EnableAcceptEncodingBrotli":true,"HeadersConfig":{"HeaderBehavior":"none"},
  "CookiesConfig":{"CookieBehavior":"none"},"QueryStringsConfig":{
  "QueryStringBehavior":"whitelist","QueryStrings":{"Quantity":1,"Items":["id"]}}}}'

# 1. An extra Comment field and a reordered whitelist must NOT read as drift.
withComment="$(jq -c '. + {Comment:"set in console"}' <<<"$base")"
[[ "$(jq -cS "$norm" <<<"$base")" == "$(jq -cS "$norm" <<<"$withComment")" ]] \
  && echo "OK: extra fields ignored" || echo "FAIL: spurious drift"

# 2. A MaxTTL change MUST read as drift.
changed="$(jq -c '.MaxTTL = 90000' <<<"$base")"
[[ "$(jq -cS "$norm" <<<"$base")" != "$(jq -cS "$norm" <<<"$changed")" ]] \
  && echo "OK: TTL change detected" || echo "FAIL: drift missed"

# 3. A whitelist change MUST read as drift.
rekeyed="$(jq -c '.ParametersInCacheKeyAndForwardedToOrigin.QueryStringsConfig.QueryStrings.Items = ["id","v"]' <<<"$base")"
[[ "$(jq -cS "$norm" <<<"$base")" != "$(jq -cS "$norm" <<<"$rekeyed")" ]] \
  && echo "OK: whitelist change detected" || echo "FAIL: drift missed"
```

Expected: three `OK:` lines. If any prints `FAIL`, fix the `norm` expression in the script and re-run — the jq here must stay character-identical to the script's.

- [ ] **Step 4: Commit**

```bash
git add scripts/create-cdn-distribution.sh
git commit -m "$(cat <<'EOF'
fix: apply cache-policy edits instead of silently dropping them

ensure_cache_policy looked the policy up by name and returned the
existing id, with no update path — so editing a TTL or the query-string
whitelist in this script was a silent no-op from the second run onward.
The cache key and the TTL ceiling live in the policy, not the
distribution config, so they are not covered by update-distribution's
replace-everything semantics that cdn-operations.md describes.

Compares only the fields this script manages, so a console-set Comment
does not read as drift; prints the diff and updates with --if-match when
it does. Also records the two ceilings that are load-bearing: MaxTTL
truncates the stale window, and MinTTL must stay 0 or no-store is
ignored.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Correct the documentation these two changes falsify

**Files:**
- Modify: `docs/system_design/12_CDNS.md` (§1 TTL paragraph around line 69; §1 behavior table; §5; sharp edges)
- Modify: `docs/runbooks/cdn-operations.md` (source-of-truth blockquote at lines 11-20; "What is and is not cached")

**Interfaces:**
- Consumes: the changes from Tasks 5 and 6.
- Produces: nothing.

- [ ] **Step 1: Replace the TTL paragraph in 12_CDNS §1**

Replace this text (starting at line 69):

```markdown
TTL comes from the origin's `Cache-Control: s-maxage`
([`HttpCaching.publicCache`](../../src/main/java/com/recsys/api/serving/HttpCaching.java)),
and `stale-while-revalidate` / `stale-if-error` share the same window
```

with:

```markdown
The origin **proposes** freshness via `Cache-Control: s-maxage`
([`HttpCaching.publicCache`](../../src/main/java/com/recsys/api/serving/HttpCaching.java)) and
the cache policy sets the **bounds** that proposal is clamped into. The three policy TTLs play
different roles, and only one of them is inert:

| Field | Role |
|---|---|
| `MinTTL: 0` | Load-bearing. Above zero CloudFront ignores `Cache-Control: no-store` outright, and every `no-store` on these routes — the 404s, the rejections, the gateway's 5xx — would stop working. |
| `DefaultTTL` | Inert for cached 200s. It applies only when the origin sends no `max-age`/`s-maxage`, and both cacheable routes always send one. |
| `MaxTTL` | Binding. CloudFront caches for the lesser of `s-maxage` and `MaxTTL`, serves stale content for the lesser of the stale directive and `MaxTTL`, and drops the object entirely after `MaxTTL`. |

Because `MaxTTL` sits *exactly at* each stale window (86400 for item, 3600 for similar), the
edge ceiling — not the origin directive — is what currently bounds outage tolerance. Raising
`stale-if-error` without raising `MaxTTL` changes nothing.

`stale-while-revalidate` / `stale-if-error` share the same window
```

- [ ] **Step 2: Update the behavior table's path patterns in 12_CDNS §1**

In the §1 table, change the four `Path pattern` cells from `/api/catalog/item*` etc. to the exact forms `/api/catalog/item`, `/api/v1/catalog/item`, `/api/catalog/similar`, `/api/v1/catalog/similar`, and append to the sentence introducing the table:

```markdown
Path patterns are exact: CloudFront evaluates them against the path only, never the query
string, so no wildcard is needed and one would make the edge's scope wider than the exact
`GATEWAY_PUBLIC_PATHS` entries the gateway authorizes.
```

- [ ] **Step 3: Make §5's mirror claim true**

In §5, the sentence "mirrors the five CloudFront behaviors one-for-one" is now accurate on path scope. Append to that paragraph:

```markdown
The mirror extends to path scope: nginx's `location =` blocks are exact matches, and the
CloudFront `PathPattern`s are wildcard-free for the same reason. `LocalCdnCacheTest` pins this
by driving a glob-adjacent path (`/api/catalog/items?id=1`) that the origin marks cacheable
and asserting it is `BYPASS` — scope is decided by the behavior, not by the response.
```

- [ ] **Step 4: Add sharp edge 8 to 12_CDNS**

After the sharp edge 7 added in Task 4:

```markdown
8. **The cache policies were create-once; the distribution is create-or-update.** They are
   different AWS resources with different update semantics, and the cache key plus the TTL
   ceilings live in the *policy*. Until 2026-07-29 `ensure_cache_policy` returned the existing
   id unconditionally, so a TTL or whitelist edit in the script never reached AWS — the exact
   inverse of the drift hazard the runbook warns about for the distribution. It now diffs the
   fields it manages and updates with `--if-match`, which propagates a cache-behavior change
   to every edge: not a step to take casually mid-incident.
```

- [ ] **Step 5: Scope the runbook's source-of-truth claim**

In `docs/runbooks/cdn-operations.md`, append to the blockquote that ends "…does not survive the next run." (line 20):

```markdown
>
> **This applies to the distribution config only.** The two cache policies (`recsys-item`,
> `recsys-similar`) are separate resources, and they hold the cache key and the TTL ceilings.
> `ensure_cache_policy` diffs the fields the script manages and issues
> `update-cache-policy --if-match` when they differ, printing the deployed-versus-desired diff
> first. Before 2026-07-29 it had no update path at all, so a policy edit in the script was a
> silent no-op — if you are debugging a TTL or cache-key change that "did not take", check
> `aws cloudfront get-cache-policy --id <id>` against the script rather than assuming the
> script won.
```

- [ ] **Step 6: Point the cached-window figures at their ceiling**

In the "What is and is not cached" section, append to the paragraph ending "…and `/similar` (either spelling) for up to 1 h.":

```markdown
Those windows are ceilings set by the cache policies' `MaxTTL`, which sits exactly at each
stale directive — so they are the *smaller* of the two limits, not the origin's number
winning. See the TTL table in [12_CDNS §1](../system_design/12_CDNS.md#1-what-is-cached-and-what-isnt).
```

- [ ] **Step 7: Verify and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
```

Expected: PASS. `DocumentationIndexTest` checks README reachability (both files are already indexed) and `DocumentedMechanismTest` checks that named source paths resolve — including the new `HttpCaching.java` link and the anchor-free path references.

```bash
git add docs/system_design/12_CDNS.md docs/runbooks/cdn-operations.md
git commit -m "$(cat <<'EOF'
docs: state the edge/origin TTL split, and scope the source-of-truth rule

"TTL comes from the origin's s-maxage" reads as though the edge sets no
lifetime, which is what made the MaxTTL coupling invisible: MaxTTL sits
exactly at each stale window, so it is the binding constraint on outage
tolerance, and MinTTL: 0 is what makes every no-store on these routes
work. Tabulates which of the three TTL fields is load-bearing, inert, and
binding. Scopes the runbook's replace-everything warning to the
distribution config and states the opposite behavior for cache policies.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 8: Open PR2**

```bash
git push -u origin fix/cdn-exact-path-patterns-and-policy-updates
gh pr create --base fix/cdn-cache-key-and-edge-config-hardening \
  --title "CDN: exact path patterns, and cache-policy edits that actually apply" --body "$(cat <<'EOF'
## Summary

Two edge-config defects from the same audit as #PR1.

**Path patterns were globs.** `/api/catalog/item*` matches `/api/catalog/item<anything>`.
CloudFront evaluates path patterns against the path only — *"CloudFront does not consider
query strings or cookies when evaluating the path pattern"* — so the wildcard bought nothing
and only widened the match beyond the exact `GATEWAY_PUBLIC_PATHS` entry, in the direction
where the edge drops `Authorization` on a path the gateway still treats as private. Reachable
today: `/api/catalog` is a prefix route, so `/api/catalog/itemX?id=<n>` reaches 6010, misses
every route, and returns Armeria's framework 404 — the one 404 in the read path with no
`Cache-Control`, and 404 is unconditionally edge-cached.

**Cache-policy edits vanished.** `ensure_cache_policy` had no update path, so a TTL or
whitelist change in the script was a silent no-op after the first run — and the cache key and
TTL ceilings live in the policy, not the distribution config. It now diffs the fields it
manages and updates with `--if-match`.

Docs corrected accordingly, including the claim that TTL comes from the origin alone: `MaxTTL`
sits exactly at each stale window and is the binding constraint.

## Testing

`LocalCdnCacheTest` (docker, not gated) gains a glob-adjacent path case: the origin marks it
cacheable, the edge must still `BYPASS` it. The script changes are verified by `bash -n` plus
a canned-JSON check that the drift comparison ignores AWS-added fields and catches real TTL
and whitelist changes.

Design: `docs/superpowers/specs/2026-07-29-cdn-cache-key-and-edge-config-hardening-design.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## PR3 — Make the hit ratio observable, and fix the error-caching model (F4, F5)

```bash
git checkout -b fix/cdn-metrics-and-error-caching-accuracy
```

### Task 8: Enable additional CloudFront metrics

**Files:**
- Modify: `scripts/create-cdn-distribution.sh` (add a function; call it from both branches)
- Modify: `docs/runbooks/cdn-operations.md` ("Monitoring" section, around line 253)

**Interfaces:**
- Consumes: nothing.
- Produces: `enable_additional_metrics <distribution-id>`. The create branch must now capture the new distribution's id instead of printing a table, while keeping the literal string `Creating distribution` that `cdn-operations.md:284` quotes.

- [ ] **Step 1: Add the function**

In `scripts/create-cdn-distribution.sh`, add above the `existing_id=` lookup (currently line 146):

```bash
# CacheHitRate, OriginLatency and error-rate-by-status-code are ADDITIONAL metrics: off by
# default, and what cdn-operations.md's hit-ratio query reads. Without a monitoring
# subscription that query returns an empty Datapoints array and exit 0 — indistinguishable
# from zero traffic, on the one number that says whether the cache earns anything.
#
# This is a separate API from the distribution config, so unlike Logging it is NOT reset by
# update-distribution's replace-everything semantics. Safe to re-run.
enable_additional_metrics() {
  local dist_id="$1"
  local status
  status="$(aws cloudfront get-monitoring-subscription --distribution-id "$dist_id" \
    --query 'MonitoringSubscription.RealtimeMetricsSubscriptionConfig.RealtimeMetricsSubscriptionStatus' \
    --output text 2>/dev/null || true)"
  if [[ "$status" == "Enabled" ]]; then
    echo "Additional CloudFront metrics already enabled for ${dist_id}"
    return
  fi
  echo "Enabling additional CloudFront metrics for ${dist_id} (CacheHitRate, OriginLatency)"
  aws cloudfront create-monitoring-subscription --distribution-id "$dist_id" \
    --monitoring-subscription \
    'RealtimeMetricsSubscriptionConfig={RealtimeMetricsSubscriptionStatus=Enabled}' >/dev/null
}
```

- [ ] **Step 2: Call it from both branches**

Replace the final `if/else` block (currently lines 149-159) with:

```bash
if [[ -n "$existing_id" && "$existing_id" != "None" ]]; then
  echo "Updating existing distribution ${existing_id}"
  etag="$(aws cloudfront get-distribution-config --id "$existing_id" --query 'ETag' --output text)"
  aws cloudfront update-distribution --id "$existing_id" --if-match "$etag" \
    --distribution-config "file://${config_file}" \
    --query 'Distribution.DomainName' --output text
else
  echo "Creating distribution"
  # Captured rather than printed as a table: the id is needed for the monitoring
  # subscription below, which is a per-distribution call.
  created="$(aws cloudfront create-distribution --distribution-config "file://${config_file}" \
    --query 'Distribution.[Id,DomainName]' --output text)"
  existing_id="$(awk '{print $1}' <<<"$created")"
  echo "  Id:     ${existing_id}"
  echo "  Domain: $(awk '{print $2}' <<<"$created")"
fi

enable_additional_metrics "$existing_id"
```

- [ ] **Step 3: Verify**

```bash
bash -n scripts/create-cdn-distribution.sh
grep -c 'Creating distribution' scripts/create-cdn-distribution.sh   # must stay 1
grep -c 'enable_additional_metrics' scripts/create-cdn-distribution.sh  # 2: definition + call
aws cloudfront create-monitoring-subscription help >/dev/null 2>&1 \
  && echo "OK: subcommand exists in the installed AWS CLI" \
  || echo "WARN: check the AWS CLI version — create-monitoring-subscription is missing"
```

Expected: no syntax error; counts `1` and `2`; `OK:`. The `Creating distribution` string must survive because `cdn-operations.md:284` quotes it when explaining a swallowed-credentials failure.

- [ ] **Step 4: Document the verification step**

In `docs/runbooks/cdn-operations.md`, replace the "Monitoring" code block's opening comment and prepend a verification command, so the section reads:

```markdown
## Monitoring

`CacheHitRate` is an **additional** CloudFront metric, off by default.
`create-cdn-distribution.sh` turns it on via `create-monitoring-subscription`, but an empty
`Datapoints` array below means "not enabled", not "no traffic" — check the subscription first:

```bash
aws cloudfront get-monitoring-subscription --distribution-id <id>
```

```bash
# Hit ratio (expect high on catalog, ~0 overall — most traffic is uncacheable POSTs)
aws cloudwatch get-metric-statistics --namespace AWS/CloudFront \
  --metric-name CacheHitRate --dimensions Name=DistributionId,Value=<id> \
  Name=Region,Value=Global --start-time "$(date -u -v-1H +%Y-%m-%dT%H:%M:%SZ)" \
  --end-time "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --period 300 --statistics Average
```
```

- [ ] **Step 5: Commit**

```bash
git add scripts/create-cdn-distribution.sh docs/runbooks/cdn-operations.md
git commit -m "$(cat <<'EOF'
feat: enable the additional metrics the hit-ratio runbook depends on

CacheHitRate is an additional CloudFront metric requiring a
create-monitoring-subscription call per distribution. Nothing enabled it,
so the documented get-metric-statistics query returned an empty
Datapoints array and exit 0 — a silent empty answer on the one number
that says whether the cache earns anything, and the only signal that
would show cache-key fragmentation being exercised. It is a separate API
from the distribution config, so it survives update-distribution.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: Correct the error-caching model

**Files:**
- Modify: `src/main/java/com/recsys/api/serving/BaseApiService.java:127-136` (the `writeNoStoreError` javadoc)
- Modify: `src/main/java/com/recsys/api/serving/CatalogService.java:40-41, 46-47`
- Modify: `src/main/java/com/recsys/api/serving/RecommendationService.java:165-166`
- Modify: `src/main/java/com/recsys/application/gateway/GatewayProxyService.java` (the `gatewayError` comment)
- Modify: `src/test/java/com/recsys/api/serving/CatalogCacheHeadersTest.java` and `SimilarCacheHeadersTest.java` (the `badRequest` test comments)
- Modify: `docs/system_design/12_CDNS.md` (§1 `no-store` sentence)

**Interfaces:**
- Consumes: nothing. **Comments and docs only — no behavior change.** Every `no-store` stays exactly where it is; only the stated reason changes.

- [ ] **Step 1: Confirm the current tests pass before touching anything**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
```

Expected: PASS. This is a comment-only task, so the same command must pass identically at the end — that is the whole verification.

- [ ] **Step 2: Fix the `writeNoStoreError` javadoc**

Replace the second paragraph of the javadoc (lines 128-133) with:

```java
     * Same body as {@link #writeError(HttpStatus, String)} but with {@code Cache-Control: no-store}
     * instead of no cache header at all. For error branches on otherwise-cacheable routes.
     *
     * <p>CloudFront splits error caching by status code: 404, 414, 500, 501, 502, 503 and 504
     * are cached unconditionally for the Error Caching Minimum TTL (10 s by default), while
     * 400, 403, 405, 412 and 415 are cached <em>only</em> if the origin returns
     * {@code Cache-Control: max-age} or {@code s-maxage}. So on the 404 and 5xx branches this
     * {@code no-store} is load-bearing, and on the 400 branches it is defensive — applied
     * anyway so the rule for a cacheable route is simply "errors are never cacheable", with no
     * per-status reasoning to get wrong later. All of it depends on both cache policies keeping
     * {@code MinTTL: 0}; above zero CloudFront ignores {@code no-store} outright. Behaviors on
     * the {@code CachingDisabled} policy do not cache error responses at all.
```

- [ ] **Step 3: Fix the two `CatalogService` comments**

Replace the 404 comment (lines 40-41) — this is the load-bearing one, so say so:

```java
                    // Load-bearing no-store: 404 is on CloudFront's unconditionally-cached
                    // list, so without it a miss would be pinned at the edge for the 10 s
                    // Error Caching Minimum TTL — and the movie may be added at any time, so a
                    // pinned 404 would outlive the gap.
```

Replace the 400 comment (lines 46-47):

```java
                    // Defensive, not load-bearing: CloudFront caches a 400 only when the
                    // origin sends max-age/s-maxage, which this response does not. Applied
                    // anyway so the rule for this route stays "errors are never cacheable".
```

- [ ] **Step 4: Fix the `RecommendationService.Similar` comment**

Replace lines 165-166:

```java
                    // Defensive, not load-bearing: CloudFront caches a 400 only when the
                    // origin sends max-age/s-maxage. The unconditionally-cached codes are 404,
                    // 414 and 5xx — see the no-store 404 above and writeNoStoreError's javadoc.
```

- [ ] **Step 5: Fix the `gatewayError` comment**

In `src/main/java/com/recsys/application/gateway/GatewayProxyService.java`, replace the `no-store` comment inside `gatewayError` with:

```java
        // no-store is load-bearing for the 502/503 this helper returns: both are on
        // CloudFront's unconditionally-cached list, so on one of the four cached catalog
        // behaviors a circuit-open 503 would otherwise be pinned at the edge for the 10 s Error
        // Caching Minimum TTL and served to every viewer at that POP. For its 400 and 404
        // callers it is defensive — CloudFront caches 400 only with max-age/s-maxage, which
        // this response does not send. Depends on both cache policies keeping MinTTL: 0.
```

- [ ] **Step 6: Fix the two test comments**

In `CatalogCacheHeadersTest.item_badRequestIsNotCacheable` and
`SimilarCacheHeadersTest.similar_badRequestIsNotCacheable`, replace the "CloudFront's default
Error Caching Minimum TTL (10s) would otherwise pin this at the edge" comment with:

```java
        // no-store on every error branch, uniformly. CloudFront would cache this particular
        // 400 only with max-age/s-maxage, so the header is defensive here — but the 404 and
        // 5xx branches depend on it, and one rule per route beats per-status reasoning.
```

- [ ] **Step 7: Correct the model in 12_CDNS §1**

Replace the sentence "Conversely, `GET /getuser`, `GET /api/v1/token`, and not-found responses are `Cache-Control: no-store`, so a miss or a single-use token can never be pinned at the edge." with:

```markdown
Conversely, `GET /getuser`, `GET /api/v1/token`, and not-found responses are
`Cache-Control: no-store`, so a miss or a single-use token can never be pinned at the edge.
Which of those `no-store` headers is load-bearing depends on the status: CloudFront caches
404, 414, 500, 501, 502, 503 and 504 unconditionally for the Error Caching Minimum TTL (10 s
by default), and caches 400, 403, 405, 412 and 415 *only* if the origin sends
`max-age`/`s-maxage`. So the 404 branches and the gateway's 5xx genuinely need it, while the
400 branches are belt-and-braces — the routes apply it uniformly so there is no per-status
reasoning to get wrong. Behaviors on `CachingDisabled` cache no error responses at all, which
is why this only matters on the four cached catalog behaviors. All of it assumes
`MinTTL: 0`: above zero CloudFront ignores `no-store`.
```

- [ ] **Step 8: Verify nothing changed behaviorally, and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test
git diff --stat   # expect only comments/docs; no assertion or logic lines
```

Expected: both suites PASS with the same counts as Step 1.

```bash
git add src/main/java/com/recsys/api/serving/BaseApiService.java \
        src/main/java/com/recsys/api/serving/CatalogService.java \
        src/main/java/com/recsys/api/serving/RecommendationService.java \
        src/main/java/com/recsys/application/gateway/GatewayProxyService.java \
        src/test/java/com/recsys/api/serving/CatalogCacheHeadersTest.java \
        src/test/java/com/recsys/api/serving/SimilarCacheHeadersTest.java \
        docs/system_design/12_CDNS.md
git commit -m "$(cat <<'EOF'
docs: name the error status codes CloudFront actually caches

Five comments justified no-store by claiming a 400 or 403 would be pinned
for 10s. CloudFront caches 400/403/405/412/415 only when the origin sends
max-age or s-maxage, which none of these responses do; the
unconditionally-cached codes are 404, 414 and 5xx. So the load-bearing
no-store is the one on the 404s and on the gateway's 502/503, and the
400s are defensive — which is the direction that matters, since the
inverted model is what would justify dropping a 404's no-store. Also
records that all of it depends on MinTTL: 0. No behavior change.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 9: Open PR3**

```bash
git push -u origin fix/cdn-metrics-and-error-caching-accuracy
gh pr create --base fix/cdn-exact-path-patterns-and-policy-updates \
  --title "CDN: enable the hit-ratio metric, and correct the error-caching model" --body "$(cat <<'EOF'
## Summary

**`CacheHitRate` was never enabled.** It is an *additional* CloudFront metric requiring
`create-monitoring-subscription` per distribution. The runbook documents a
`get-metric-statistics` query against it; that query returned an empty `Datapoints` array and
exit 0. The script now enables it (a separate API, so it survives `update-distribution`), and
the runbook says to check the subscription before believing an empty result.

**The error-caching model in the comments was inverted.** Five sites justified `no-store` by
citing a 400 or 403 being pinned for 10 s. CloudFront caches 400/403/405/412/415 *only* when
the origin sends `max-age`/`s-maxage` — none of these do. The unconditionally-cached codes are
404, 414, and 5xx, so the load-bearing `no-store` is the one on the 404s and the gateway's
502/503. Getting this backwards is what would justify dropping a 404's `no-store`, which is
the one that matters. Also records the `MinTTL: 0` dependency: above zero, CloudFront ignores
`no-store` entirely.

Comment and doc changes only for the second half — no behavior change, same test counts.

Design: `docs/superpowers/specs/2026-07-29-cdn-cache-key-and-edge-config-hardening-design.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Verification Checklist

Run after all three PRs:

- [ ] `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience` — the PR gate, green.
- [ ] `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test` — full default suite, green.
- [ ] `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -DexcludedGroups=load -Dgroups=docker -Dtest=LocalCdnCacheTest` — 8 tests, green (the 6 existing plus one from Task 4 and one from Task 5; needs Docker).
- [ ] `bash -n scripts/create-cdn-distribution.sh` — clean.
- [ ] `grep -c 'PathPattern: "[^"]*\*"' scripts/create-cdn-distribution.sh` — `0`.
- [ ] `grep -n 'cacheKeyIntParam' src/main/java/com/recsys/api/serving/*.java` — the helper plus exactly three call sites (`id`, `movieId`, `k`).
- [ ] `grep -rn 'optionalIntParam' src/main/java/com/recsys/api/serving/` — remaining uses are on non-cached routes only (`RecommendationService.V1`).
- [ ] The three new/extended test classes appear in `pom.xml`'s `resilience` includes, and none of them is `@Tag("docker")`.
