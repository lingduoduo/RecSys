# Gateway URL Versioning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `/api/v{n}` a routing dimension the gateway owns, so clients get a versioned public surface while the four backends keep their current internal paths unchanged.

**Architecture:** A new `ApiVersion` value type parses and strips a leading `/api/v{n}` segment. Each of the three gateway entry points (`GatewayProxyService`, `RecommendationGatewayService`, `LlmProxyService`) normalizes on entry, *before* calling `authenticator.check` — so `PROTECTED_PREFIXES` and `GATEWAY_PUBLIC_PATHS` need no versioned entries and cannot be bypassed with a version segment. A separate server-wide `ApiDeprecationDecorator` adds `Deprecation`/`Sunset`/`Link` response headers to unversioned spellings and back-compat alias routes.

**Tech Stack:** Java 17, Armeria 1.28.4, JUnit 5, AssertJ, Maven.

**Spec:** [2026-07-27-gateway-url-versioning-design.md](../specs/2026-07-27-gateway-url-versioning-design.md)

## Global Constraints

- JDK 17 required: prefix every Maven command with `JAVA_HOME=$(/usr/libexec/java_home -v 17)`.
- `SUPPORTED_VERSIONS = Set.of(1)`. Only v1 exists.
- A version segment is `/api/` + `v` + **one to four digits** + (`/` or end-of-path). Anything else is not a version segment and passes through untouched.
- Normalization MUST happen before `authenticator.check` at every entry point. This is the security property the design rests on.
- Unsupported explicit version → `400` (never 404), via the existing `GatewayProxyService.gatewayError`, which already sets `Cache-Control: no-store`.
- `MicroserviceRoute` and `MicroserviceRouteTable` are NOT modified by any task.
- `GATEWAY_PUBLIC_PATHS` is NOT modified by any task.
- Branch: `feat/gateway-url-versioning`. Commit after every task.

---

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `src/main/java/com/recsys/application/gateway/ApiVersion.java` | Parse/strip the version segment; build the canonical versioned spelling. Pure, no I/O. |
| `src/main/java/com/recsys/application/gateway/ApiDeprecationDecorator.java` | Classify a request path as deprecated and add response headers. No routing effect. |
| `src/test/java/com/recsys/application/gateway/ApiVersionTest.java` | Parse table. |
| `src/test/java/com/recsys/application/gateway/ApiDeprecationDecoratorTest.java` | Header emission and exemptions. |
| `docs/api-compatibility-policy.md` | Client-facing compatibility contract. |

**Modified:**

| File | Change |
|---|---|
| `src/main/java/com/recsys/application/gateway/GatewayProxyService.java:46-56` | Normalize on entry; reject unsupported versions. |
| `src/main/java/com/recsys/application/gateway/RecommendationGatewayService.java:51-68` | Same. |
| `src/main/java/com/recsys/application/gateway/LlmProxyService.java:129-133` | Same. |
| `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java:160,246-250` | Register versioned twin routes; register the deprecation decorator. |
| `src/test/java/com/recsys/api/gateway/GatewayServerIntegrationTest.java` | Versioned-path integration coverage. |
| `src/test/java/com/recsys/application/gateway/GatewayAuthenticatorTest.java` | Security regression: versioned protected paths stay protected. |
| `scripts/create-cdn-distribution.sh:122-131` | Two new cache behaviors. |
| `docker/cdn/default.conf.template` | Two new nginx locations. |
| `k8s/base/configmap.yaml` | `GATEWAY_DEPRECATION_SUNSET`. |
| `CONFIG_GUIDE.md:165` | Document the new variable. |
| `docs/system_design/09_API_Gateway.md` | Rewrite the versioning subsection. |
| `docs/system_design/12_CDNS.md` | Cache table + sharp edge 6. |
| `docs/system_design/20_AuthN_AuthZ.md` | Normalization-precedes-auth note. |
| `README.md` | Link the policy. |

---

### Task 1: `ApiVersion` value type

**Files:**
- Create: `src/main/java/com/recsys/application/gateway/ApiVersion.java`
- Test: `src/test/java/com/recsys/application/gateway/ApiVersionTest.java`

**Interfaces:**
- Consumes: `MicroserviceRoute.normalizePath(String)` — existing package-private static.
- Produces:
  - `record ApiVersion(int version, String path, boolean explicit)`
  - `static ApiVersion parse(String requestPath)`
  - `boolean supported()`
  - `String unsupportedMessage()`
  - `static String versioned(int version, String normalizedPath)`
  - `static final Set<Integer> SUPPORTED_VERSIONS`
  - `static final int DEFAULT_VERSION` — used by Tasks 3 and 4 for the twin registrations
    and the successor `Link`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/application/gateway/ApiVersionTest.java`:

```java
package com.recsys.application.gateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiVersionTest {

    @Test
    void parse_stripsExplicitVersionSegment() {
        ApiVersion v = ApiVersion.parse("/api/v1/users/profile");
        assertThat(v.version()).isEqualTo(1);
        assertThat(v.path()).isEqualTo("/api/users/profile");
        assertThat(v.explicit()).isTrue();
        assertThat(v.supported()).isTrue();
    }

    @Test
    void parse_treatsUnversionedPathAsImplicitV1() {
        ApiVersion v = ApiVersion.parse("/api/users/profile");
        assertThat(v.version()).isEqualTo(1);
        assertThat(v.path()).isEqualTo("/api/users/profile");
        assertThat(v.explicit()).isFalse();
        assertThat(v.supported()).isTrue();
    }

    @Test
    void parse_reportsUnsupportedVersionButStillStrips() {
        ApiVersion v = ApiVersion.parse("/api/v2/users");
        assertThat(v.version()).isEqualTo(2);
        assertThat(v.path()).isEqualTo("/api/users");
        assertThat(v.explicit()).isTrue();
        assertThat(v.supported()).isFalse();
    }

    @Test
    void parse_doesNotTreatResourceNamedVersionAsAVersion() {
        ApiVersion v = ApiVersion.parse("/api/version/x");
        assertThat(v.path()).isEqualTo("/api/version/x");
        assertThat(v.explicit()).isFalse();
    }

    @Test
    void parse_requiresAtLeastOneDigit() {
        ApiVersion v = ApiVersion.parse("/api/v/x");
        assertThat(v.path()).isEqualTo("/api/v/x");
        assertThat(v.explicit()).isFalse();
    }

    @Test
    void parse_rejectsMoreThanFourDigitsSoIntegerCannotOverflow() {
        ApiVersion v = ApiVersion.parse("/api/v99999/x");
        assertThat(v.path()).isEqualTo("/api/v99999/x");
        assertThat(v.explicit()).isFalse();
    }

    @Test
    void parse_requiresASegmentBoundaryAfterTheDigits() {
        ApiVersion v = ApiVersion.parse("/api/v1x/foo");
        assertThat(v.path()).isEqualTo("/api/v1x/foo");
        assertThat(v.explicit()).isFalse();
    }

    @Test
    void parse_handlesBareVersionedApiRoot() {
        ApiVersion v = ApiVersion.parse("/api/v1");
        assertThat(v.path()).isEqualTo("/api");
        assertThat(v.explicit()).isTrue();
    }

    @Test
    void parse_leavesNonApiPathsAlone() {
        ApiVersion v = ApiVersion.parse("/health");
        assertThat(v.path()).isEqualTo("/health");
        assertThat(v.explicit()).isFalse();
        assertThat(v.supported()).isTrue();
    }

    @Test
    void parse_normalizesNullAndBlankToRoot() {
        assertThat(ApiVersion.parse(null).path()).isEqualTo("/");
        assertThat(ApiVersion.parse("").path()).isEqualTo("/");
    }

    @Test
    void unsupportedMessage_namesTheSupportedVersions() {
        assertThat(ApiVersion.parse("/api/v2/users").unsupportedMessage())
                .isEqualTo("unsupported API version: v2; supported: v1");
    }

    @Test
    void versioned_buildsTheCanonicalSpelling() {
        assertThat(ApiVersion.versioned(1, "/api/catalog/item")).isEqualTo("/api/v1/catalog/item");
        assertThat(ApiVersion.versioned(1, "/api")).isEqualTo("/api/v1");
        assertThat(ApiVersion.versioned(1, "/health")).isEqualTo("/health");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=ApiVersionTest
```

Expected: FAIL — compilation error, `ApiVersion` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/recsys/application/gateway/ApiVersion.java`:

```java
package com.recsys.application.gateway;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * A gateway request path split into its API version and the version-free path the rest of the
 * gateway works with.
 *
 * <p>The gateway owns versioning: backends keep their current internal paths, and a client's
 * {@code /api/v1/users} becomes {@code /api/users} before routing, authorization, or rate-limit
 * keying sees it. An unversioned path is implicit v1, which is what keeps every existing client
 * working.
 *
 * <p>{@link #parse} is total — it never throws — so a hostile path cannot turn into a 500.
 */
public record ApiVersion(int version, String path, boolean explicit) {

    /** Versions this gateway serves. Adding a version is a one-line change here. */
    public static final Set<Integer> SUPPORTED_VERSIONS = Set.of(1);

    /** The version assumed when a request carries no explicit version segment. */
    public static final int DEFAULT_VERSION = 1;

    private static final String API_ROOT = "/api";
    private static final String API_PREFIX = API_ROOT + "/";

    /**
     * Bounds the digit run so {@link Integer#parseInt} cannot overflow. A longer run is not a
     * version segment at all, so {@code /api/v99999/x} routes as an ordinary path and 404s
     * rather than becoming a confusing 400.
     */
    private static final int MAX_VERSION_DIGITS = 4;

    public static ApiVersion parse(String requestPath) {
        String normalized = MicroserviceRoute.normalizePath(requestPath);
        if (!normalized.startsWith(API_PREFIX)) {
            return implicit(normalized);
        }
        int digitsStart = API_PREFIX.length() + 1;
        if (normalized.length() <= API_PREFIX.length() || normalized.charAt(API_PREFIX.length()) != 'v') {
            return implicit(normalized);
        }
        int cursor = digitsStart;
        while (cursor < normalized.length() && Character.isDigit(normalized.charAt(cursor))) {
            cursor++;
        }
        int digits = cursor - digitsStart;
        if (digits == 0 || digits > MAX_VERSION_DIGITS) {
            return implicit(normalized);
        }
        // The digits must end the segment: "/api/v1x/foo" is a resource named "v1x", not v1.
        if (cursor < normalized.length() && normalized.charAt(cursor) != '/') {
            return implicit(normalized);
        }
        int version = Integer.parseInt(normalized.substring(digitsStart, cursor));
        String remainder = normalized.substring(cursor);
        return new ApiVersion(version, remainder.isEmpty() ? API_ROOT : API_ROOT + remainder, true);
    }

    public boolean supported() {
        return SUPPORTED_VERSIONS.contains(version);
    }

    /** Client-facing rejection text; names every version the gateway will accept. */
    public String unsupportedMessage() {
        String supported = SUPPORTED_VERSIONS.stream()
                .sorted()
                .map(v -> "v" + v)
                .collect(Collectors.joining(", "));
        return "unsupported API version: v" + version + "; supported: " + supported;
    }

    /**
     * The canonical versioned spelling of an already version-free path, e.g.
     * {@code /api/catalog/item} to {@code /api/v1/catalog/item}. Non-{@code /api} paths are
     * returned unchanged, so {@code /health} never grows a version.
     */
    public static String versioned(int version, String normalizedPath) {
        String normalized = MicroserviceRoute.normalizePath(normalizedPath);
        if (!normalized.equals(API_ROOT) && !normalized.startsWith(API_PREFIX)) {
            return normalized;
        }
        return API_ROOT + "/v" + version + normalized.substring(API_ROOT.length());
    }

    private static ApiVersion implicit(String normalizedPath) {
        return new ApiVersion(DEFAULT_VERSION, normalizedPath, false);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=ApiVersionTest
```

Expected: PASS, 12 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/application/gateway/ApiVersion.java \
        src/test/java/com/recsys/application/gateway/ApiVersionTest.java
git commit -m "feat(gateway): add ApiVersion for /api/v{n} parsing and normalization"
```

---

### Task 2: Normalize in `GatewayProxyService` (the catch-all)

**Files:**
- Modify: `src/main/java/com/recsys/application/gateway/GatewayProxyService.java:46-56`
- Test: `src/test/java/com/recsys/application/gateway/GatewayAuthenticatorTest.java` (add cases)
- Test: `src/test/java/com/recsys/api/gateway/GatewayServerIntegrationTest.java` (add cases)

**Interfaces:**
- Consumes: `ApiVersion.parse`, `ApiVersion.supported()`, `ApiVersion.unsupportedMessage()` from Task 1.
- Produces: no new public API. Behavior only.

- [ ] **Step 1: Write the failing tests**

Append to `src/test/java/com/recsys/api/gateway/GatewayServerIntegrationTest.java` (inside the class, alongside the existing tests):

```java
    @Test
    void versionedPathProxiesIdenticallyToUnversioned() {
        AggregatedHttpResponse unversioned = gateway.blockingWebClient().get("/api/recsys/health");
        AggregatedHttpResponse versioned = gateway.blockingWebClient().get("/api/v1/recsys/health");

        assertThat(versioned.status()).isEqualTo(unversioned.status());
        // The upstream echoes the path it was called with: the version segment must be gone.
        assertThat(versioned.contentUtf8()).isEqualTo(unversioned.contentUtf8());
        assertThat(versioned.contentUtf8()).contains("\"path\":\"/health\"");
    }

    @Test
    void unsupportedVersionIsRejectedWith400() {
        AggregatedHttpResponse response = gateway.blockingWebClient().get("/api/v2/recsys/health");

        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.contentUtf8()).contains("unsupported API version: v2");
        assertThat(response.contentUtf8()).contains("supported: v1");
    }

    @Test
    void pathThatMerelyLooksLikeAVersionIsNotStripped() {
        // "/api/v1x" is a resource segment, not v1 — it must not be stripped, so no route matches.
        AggregatedHttpResponse response = gateway.blockingWebClient().get("/api/v1x/recsys/health");

        assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }
```

Append to `src/test/java/com/recsys/application/gateway/GatewayAuthenticatorTest.java` — the security regression:

```java
    @Test
    void check_versionedProtectedPathIsStillProtectedAfterNormalization() {
        GatewayAuthenticator auth = authenticator(rsaKeyPairOrThrow());
        // The gateway normalizes before calling check(), so a caller cannot reach a protected
        // path by adding a version segment. This asserts the normalized form is still rejected.
        RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/api/v1/users/profile");

        String normalized = ApiVersion.parse("/api/v1/users/profile").path();
        assertThat(normalized).isEqualTo("/api/users/profile");
        assertTrue(auth.check(headers, normalized).rejected());
    }

    @Test
    void check_versionedCatalogUserIsStillProtectedAfterNormalization() {
        GatewayAuthenticator auth = authenticator(rsaKeyPairOrThrow());
        RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/api/v1/catalog/user?userId=1");

        String normalized = ApiVersion.parse("/api/v1/catalog/user").path();
        assertThat(normalized).isEqualTo("/api/catalog/user");
        assertTrue(auth.check(headers, normalized).rejected());
    }
```

Add to that file's imports:

```java
import static org.assertj.core.api.Assertions.assertThat;
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=GatewayServerIntegrationTest+GatewayAuthenticatorTest
```

Expected: FAIL — `versionedPathProxiesIdenticallyToUnversioned` gets `404` (no route matches `/api/v1/movies/...`) and `unsupportedVersionIsRejectedWith400` gets `404` instead of `400`.

- [ ] **Step 3: Write the implementation**

In `src/main/java/com/recsys/application/gateway/GatewayProxyService.java`, replace the body of `serve` (lines 46-56) so normalization precedes authorization:

```java
    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
        // Normalize BEFORE authorization. GATEWAY_PUBLIC_PATHS and PROTECTED_PREFIXES are matched
        // against version-free paths, so a caller must not be able to reach a protected route by
        // adding a version segment. Every consumer below sees the normalized path.
        ApiVersion apiVersion = ApiVersion.parse(ctx.path());
        if (!apiVersion.supported()) {
            return gatewayError(HttpStatus.BAD_REQUEST, apiVersion.unsupportedMessage());
        }
        String path = apiVersion.path();

        GatewayAuthResult auth = authenticator.check(req.headers(), path);
        if (auth.rejected()) return auth.rejection();
        GatewayPrincipal principal = auth.principal();

        MicroserviceRoute route = routeTable.match(path);
        if (route == null) {
            return gatewayError(HttpStatus.NOT_FOUND, "no route found");
        }
```

The rest of the method is unchanged — `route.rewrite(path, ctx.query())` already reads the local `path`.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=GatewayServerIntegrationTest+GatewayAuthenticatorTest
```

Expected: PASS, including all pre-existing tests in both classes.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/application/gateway/GatewayProxyService.java \
        src/test/java/com/recsys/api/gateway/GatewayServerIntegrationTest.java \
        src/test/java/com/recsys/application/gateway/GatewayAuthenticatorTest.java
git commit -m "feat(gateway): normalize /api/v{n} before authorization in the proxy catch-all"
```

---

### Task 3: Versioned twins for the recommend and LLM routes

The catch-all handled Task 2. These two entry points are registered as their own Armeria routes, so `/api/v1/...` would never reach them without an explicit registration — and both rewrite by path, so both also need normalization.

**Files:**
- Modify: `src/main/java/com/recsys/application/gateway/RecommendationGatewayService.java:51-68`
- Modify: `src/main/java/com/recsys/application/gateway/LlmProxyService.java:129-133`
- Modify: `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java:160` and `:246-250`
- Test: `src/test/java/com/recsys/api/gateway/GatewayServerIntegrationTest.java`

**Interfaces:**
- Consumes: `ApiVersion.parse`, `ApiVersion.supported()`, `ApiVersion.unsupportedMessage()`, `ApiVersion.versioned(int, String)` from Task 1.
- Produces: no new public API.

- [ ] **Step 1: Write the failing test**

**First**, register the versioned twin in the harness. `GatewayServerIntegrationTest` builds its own `ServerBuilder` rather than calling `MicroserviceGatewayServer`, so it must mirror the twin registration or these tests cannot pass. Replace the final registration block in the gateway `ServerExtension.configure`:

```java
            RecommendationGatewayService recommendationService =
                    new RecommendationGatewayService(routes, forwarder, auth);
            sb.service("/health", new GatewayHealthService(routes, timeout, cbs, GATEWAY_SELF_PORT))
              .service("/api/recommend", recommendationService)
              // Mirrors MicroserviceGatewayServer: the canonical endpoint is an exact Armeria
              // route, so the versioned spelling needs its own registration.
              .service("/api/v1/recommend", recommendationService)
              .service("prefix:/", new GatewayProxyService(routes, forwarder, auth));
```

**Then** append the tests:

```java
    @Test
    void versionedCanonicalRecommendBehavesLikeUnversioned() {
        AggregatedHttpResponse response = gateway.blockingWebClient().post(
                "/api/v1/recommend", "{\"userId\":42,\"strategy\":\"online\"}");

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        // Dispatched to the online backend, and the strategy selector was stripped from the body.
        assertThat(response.contentUtf8()).contains("\"upstream\":\"online\"");
        assertThat(response.contentUtf8()).contains("\"path\":\"/v2/recommend\"");
        assertThat(response.contentUtf8()).doesNotContain("strategy");
    }

    @Test
    void versionedCanonicalRecommendRejectsUnsupportedVersion() {
        AggregatedHttpResponse response = gateway.blockingWebClient().post(
                "/api/v2/recommend", "{\"userId\":42}");

        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.contentUtf8()).contains("unsupported API version: v2");
    }
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='GatewayServerIntegrationTest#versionedCanonicalRecommend*'
```

Expected: FAIL — `versionedCanonicalRecommendRejectsUnsupportedVersion` gets `200` instead of `400`, because `RecommendationGatewayService` does not yet reject unsupported versions.

- [ ] **Step 3: Implement — normalize in `RecommendationGatewayService`**

In `serve`, insert the version check between the method check and the auth check:

```java
    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
        if (req.method() != HttpMethod.POST) {
            return HttpResponse.of(
                    ResponseHeaders.builder(HttpStatus.METHOD_NOT_ALLOWED)
                            .contentType(MediaType.JSON_UTF_8)
                            .set(HttpHeaderNames.ALLOW, HttpMethod.POST.name())
                            .build(),
                    HttpData.ofUtf8("{\"error\":\"method not allowed\"}"));
        }

        // Same ordering rule as GatewayProxyService: normalize before authorization.
        ApiVersion apiVersion = ApiVersion.parse(ctx.path());
        if (!apiVersion.supported()) {
            return GatewayProxyService.gatewayError(
                    HttpStatus.BAD_REQUEST, apiVersion.unsupportedMessage());
        }

        GatewayAuthResult auth = authenticator.check(req.headers(), apiVersion.path());
        if (auth.rejected()) {
            return auth.rejection();
        }

        return HttpResponse.of(req.aggregate().thenApply(request ->
                dispatch(ctx, request, auth.principal())));
    }
```

- [ ] **Step 4: Implement — normalize in `LlmProxyService`**

Replace lines 129-133 of `serve`:

```java
    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
        // Normalize before authorization, and before route.rewrite below — the LLM route's prefix
        // is the version-free "/api/llm", so a versioned path would fail its matchesPrefix check.
        ApiVersion apiVersion = ApiVersion.parse(ctx.path());
        if (!apiVersion.supported()) {
            return GatewayProxyService.gatewayError(
                    HttpStatus.BAD_REQUEST, apiVersion.unsupportedMessage());
        }
        String path = apiVersion.path();

        GatewayAuthResult auth = authenticator.check(req.headers(), path);
        if (auth.rejected()) return auth.rejection();
        GatewayPrincipal principal = auth.principal();
```

The existing `route.rewrite(path, ctx.query())` further down already reads this local `path`.

- [ ] **Step 5: Implement — register the versioned twins**

In `MicroserviceGatewayServer`, replace the single canonical registration at line 160:

```java
        // Canonical recommendation endpoint — exact path takes precedence over the catch-all.
        // Both spellings are registered because this is an exact Armeria route, not a route-table
        // entry: the catch-all would normalize /api/v1/recommend to /api/recommend, which matches
        // no route-table prefix and would 404.
        sb.service("/api/recommend", recommendationService);
        sb.service(ApiVersion.versioned(ApiVersion.DEFAULT_VERSION, "/api/recommend"),
                recommendationService);
```

And in `registerLlmRoutes`, replace the single `sb.service(...)` with both spellings:

```java
            // LLM routes are filtered out of proxyRoutes, so the catch-all cannot serve them.
            // Register the versioned twin explicitly or /api/v1/llm/... would 404.
            sb.service(
                    com.linecorp.armeria.server.Route.builder()
                            .pathPrefix(llmRoute.prefix() + "/")
                            .build(),
                    llmProxyService);
            sb.service(
                    com.linecorp.armeria.server.Route.builder()
                            .pathPrefix(ApiVersion.versioned(
                                    ApiVersion.DEFAULT_VERSION, llmRoute.prefix()) + "/")
                            .build(),
                    llmProxyService);
```

Add the import to `MicroserviceGatewayServer`:

```java
import com.recsys.application.gateway.ApiVersion;
```

- [ ] **Step 6: Run the tests to verify they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=GatewayServerIntegrationTest+RecommendationGatewayServiceTest+LlmProxyServiceTest
```

Expected: PASS, including all pre-existing tests in those classes.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/application/gateway/RecommendationGatewayService.java \
        src/main/java/com/recsys/application/gateway/LlmProxyService.java \
        src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java \
        src/test/java/com/recsys/api/gateway/GatewayServerIntegrationTest.java
git commit -m "feat(gateway): serve /api/v1 on the canonical recommend and LLM routes"
```

---

### Task 4: `ApiDeprecationDecorator`

**Files:**
- Create: `src/main/java/com/recsys/application/gateway/ApiDeprecationDecorator.java`
- Create: `src/test/java/com/recsys/application/gateway/ApiDeprecationDecoratorTest.java`
- Modify: `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java` (register after the origin-secret decorator)

**Interfaces:**
- Consumes: `ApiVersion.parse`, `ApiVersion.versioned` from Task 1; `EnvVars.EnvReader` (existing).
- Produces:
  - `static ApiDeprecationDecorator fromEnvironment(EnvVars.EnvReader env)`
  - `boolean isEnabled()`
  - `boolean isDeprecated(String requestPath)`
  - `String successorLink(String requestPath)` — returns `null` when no `Link` should be emitted
  - `Function<? super HttpService, ? extends HttpService> newDecorator()`
  - `String sunsetHeaderValue()` — package-private, asserted directly by the test, which
    lives in the same `com.recsys.application.gateway` package

**Note on `EnvVars.EnvReader`:** it is a `@FunctionalInterface` with a single
`String get(String name)`, so the test's `values::get` on a `Map<String, String>` satisfies it.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/application/gateway/ApiDeprecationDecoratorTest.java`:

```java
package com.recsys.application.gateway;

import com.recsys.config.EnvVars;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiDeprecationDecoratorTest {

    private static EnvVars.EnvReader env(Map<String, String> values) {
        return values::get;
    }

    private static ApiDeprecationDecorator enabled() {
        return ApiDeprecationDecorator.fromEnvironment(
                env(Map.of("GATEWAY_DEPRECATION_SUNSET", "2027-07-27")));
    }

    @Test
    void disabledWhenSunsetUnset() {
        ApiDeprecationDecorator decorator = ApiDeprecationDecorator.fromEnvironment(env(Map.of()));
        assertThat(decorator.isEnabled()).isFalse();
        assertThat(decorator.isDeprecated("/api/catalog/item")).isFalse();
    }

    @Test
    void disabledWhenSunsetIsUnparseable() {
        ApiDeprecationDecorator decorator = ApiDeprecationDecorator.fromEnvironment(
                env(Map.of("GATEWAY_DEPRECATION_SUNSET", "not-a-date")));
        assertThat(decorator.isEnabled()).isFalse();
    }

    @Test
    void unversionedApiPathIsDeprecated() {
        assertThat(enabled().isDeprecated("/api/movies/movie")).isTrue();
    }

    @Test
    void versionedApiPathIsNotDeprecated() {
        assertThat(enabled().isDeprecated("/api/v1/movies/movie")).isFalse();
    }

    @Test
    void aliasRouteStaysDeprecatedEvenWhenVersioned() {
        assertThat(enabled().isDeprecated("/api/v1/catalog/item")).isTrue();
        assertThat(enabled().isDeprecated("/api/v1/model/predict")).isTrue();
        assertThat(enabled().isDeprecated("/api/v1/online/features")).isTrue();
    }

    @Test
    void healthAndMetricsAreExempt() {
        assertThat(enabled().isDeprecated("/health")).isFalse();
        assertThat(enabled().isDeprecated("/health/ready")).isFalse();
        assertThat(enabled().isDeprecated("/metrics")).isFalse();
    }

    @Test
    void nonApiPathIsNotDeprecated() {
        assertThat(enabled().isDeprecated("/some/other/path")).isFalse();
    }

    @Test
    void sunsetIsFormattedAsAnHttpDate() {
        assertThat(enabled().sunsetHeaderValue()).isEqualTo("Tue, 27 Jul 2027 00:00:00 GMT");
    }

    @Test
    void successorLinkIsEmittedForTheUnversionedClass() {
        assertThat(enabled().successorLink("/api/catalog/item"))
                .isEqualTo("</api/v1/catalog/item>; rel=\"successor-version\"");
    }

    @Test
    void successorLinkIsAbsentForAnAlreadyVersionedAliasRoute() {
        // /api/v1/catalog/item is deprecated as an alias route, but /api/catalog and /api/movies
        // strip to different backend paths — there is no mechanical successor to advertise.
        assertThat(enabled().successorLink("/api/v1/catalog/item")).isNull();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=ApiDeprecationDecoratorTest
```

Expected: FAIL — compilation error, `ApiDeprecationDecorator` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/recsys/application/gateway/ApiDeprecationDecorator.java`:

```java
package com.recsys.application.gateway;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.HttpService;
import com.recsys.config.EnvVars;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Adds RFC 8594 {@code Sunset} and {@code Deprecation} response headers to the two deprecated
 * request shapes, from one place, so no route has to remember to do it.
 *
 * <p>Two independent deprecation classes:
 * <ul>
 *   <li><b>Unversioned spelling</b> — an {@code /api} path with no explicit version segment.
 *       Its successor is mechanically derivable, so a {@code Link} is emitted.
 *   <li><b>Back-compat alias route</b> — {@code /api/catalog}, {@code /api/model},
 *       {@code /api/online}. These duplicate other routes and stay deprecated even when
 *       versioned. Their successors are NOT mechanical (the aliases strip to different backend
 *       paths), so no {@code Link} is emitted for them.
 * </ul>
 *
 * <p>Disabled when {@code GATEWAY_DEPRECATION_SUNSET} is unset or unparseable: the compatibility
 * policy says a sunset date is published when a deprecation is announced, so emitting
 * {@code Deprecation} without a date would be a promise with no expiry attached.
 *
 * <p>This decorator only adds headers. It never changes status, body, or routing.
 */
public final class ApiDeprecationDecorator {

    private static final Logger log = LoggerFactory.getLogger(ApiDeprecationDecorator.class);

    /** Probes and scrapes reach the pod directly and carry no client contract. */
    static final Set<String> EXEMPT_PREFIXES = Set.of("/health", "/metrics");

    /** Backend-oriented aliases, deprecated in favour of the resource-oriented routes. */
    static final Set<String> ALIAS_PREFIXES = Set.of("/api/catalog", "/api/model", "/api/online");

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

    private final String sunsetHeaderValue;

    private ApiDeprecationDecorator(String sunsetHeaderValue) {
        this.sunsetHeaderValue = sunsetHeaderValue;
    }

    public static ApiDeprecationDecorator fromEnvironment(EnvVars.EnvReader env) {
        String raw = env.get("GATEWAY_DEPRECATION_SUNSET");
        if (raw == null || raw.isBlank()) {
            return new ApiDeprecationDecorator(null);
        }
        try {
            LocalDate date = LocalDate.parse(raw.trim());
            return new ApiDeprecationDecorator(
                    HTTP_DATE.format(date.atStartOfDay(ZoneOffset.UTC)));
        } catch (RuntimeException e) {
            log.warn("GATEWAY_DEPRECATION_SUNSET=\"{}\" is not an ISO-8601 date (expected "
                    + "yyyy-MM-dd); deprecation headers are disabled.", raw);
            return new ApiDeprecationDecorator(null);
        }
    }

    public boolean isEnabled() {
        return sunsetHeaderValue != null;
    }

    String sunsetHeaderValue() {
        return sunsetHeaderValue;
    }

    public boolean isDeprecated(String requestPath) {
        if (!isEnabled()) {
            return false;
        }
        String path = MicroserviceRoute.normalizePath(requestPath);
        if (matchesAny(path, EXEMPT_PREFIXES)) {
            return false;
        }
        return isUnversionedApiPath(path) || matchesAny(ApiVersion.parse(path).path(), ALIAS_PREFIXES);
    }

    /** The {@code Link} header value, or null when no mechanical successor exists. */
    public String successorLink(String requestPath) {
        if (!isDeprecated(requestPath)) {
            return null;
        }
        String path = MicroserviceRoute.normalizePath(requestPath);
        if (!isUnversionedApiPath(path)) {
            return null;
        }
        return "<" + ApiVersion.versioned(ApiVersion.DEFAULT_VERSION, path)
                + ">; rel=\"successor-version\"";
    }

    public Function<? super HttpService, ? extends HttpService> newDecorator() {
        return delegate -> (ctx, req) -> {
            String requestPath = ctx.path();
            if (!isDeprecated(requestPath)) {
                return delegate.serve(ctx, req);
            }
            String link = successorLink(requestPath);
            return delegate.serve(ctx, req).mapHeaders(headers -> {
                var builder = headers.toBuilder()
                        .set(HttpHeaderNames.of("deprecation"), "true")
                        .set(HttpHeaderNames.of("sunset"), sunsetHeaderValue);
                if (link != null) {
                    builder.set(HttpHeaderNames.LINK, link);
                }
                return builder.build();
            });
        };
    }

    private static boolean isUnversionedApiPath(String normalizedPath) {
        return normalizedPath.startsWith("/api") && !ApiVersion.parse(normalizedPath).explicit();
    }

    private static boolean matchesAny(String path, Set<String> prefixes) {
        return prefixes.stream().anyMatch(prefix ->
                path.equals(prefix) || path.startsWith(prefix + "/"));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=ApiDeprecationDecoratorTest
```

Expected: PASS, 10 tests. If `sunsetIsFormattedAsAnHttpDate` fails on the day name, check the date: 2027-07-27 is a Tuesday.

- [ ] **Step 5: Register the decorator**

In `MicroserviceGatewayServer`, immediately after the origin-secret decorator block:

```java
        // Deprecation signalling for unversioned spellings and back-compat alias routes.
        // Registered as a server-wide decorator so every entry point — catch-all, canonical
        // recommend, and the LLM routes — is covered from one place. No-op when
        // GATEWAY_DEPRECATION_SUNSET is unset.
        ApiDeprecationDecorator deprecation =
                ApiDeprecationDecorator.fromEnvironment(System::getenv);
        if (deprecation.isEnabled()) {
            sb.decorator(deprecation.newDecorator());
        }
```

Add the import:

```java
import com.recsys.application.gateway.ApiDeprecationDecorator;
```

- [ ] **Step 6: Add integration coverage**

The harness must actually register the decorator, or these assertions prove nothing. In the
gateway `ServerExtension.configure`, before the service registrations, add:

```java
            // Server-wide, with an explicit sunset so the headers are live in this harness.
            sb.decorator(ApiDeprecationDecorator.fromEnvironment(
                    name -> "GATEWAY_DEPRECATION_SUNSET".equals(name) ? "2027-07-27" : null)
                    .newDecorator());
```

with the import `com.recsys.application.gateway.ApiDeprecationDecorator`. Then append:

```java
    @Test
    void unversionedPathCarriesDeprecationHeadersAndSuccessorLink() {
        AggregatedHttpResponse r = gateway.blockingWebClient().get("/api/recsys/health");

        assertThat(r.headers().get("deprecation")).isEqualTo("true");
        assertThat(r.headers().get("sunset")).isEqualTo("Tue, 27 Jul 2027 00:00:00 GMT");
        assertThat(r.headers().get("link"))
                .isEqualTo("</api/v1/recsys/health>; rel=\"successor-version\"");
    }

    @Test
    void versionedPathCarriesNoDeprecationHeaders() {
        AggregatedHttpResponse r = gateway.blockingWebClient().get("/api/v1/recsys/health");

        assertThat(r.headers().get("deprecation")).isNull();
        assertThat(r.headers().get("sunset")).isNull();
        assertThat(r.headers().get("link")).isNull();
    }

    @Test
    void aliasRouteStaysDeprecatedWhenVersionedButCarriesNoSuccessorLink() {
        // /api/model is a back-compat alias, deprecated for a different reason than the
        // unversioned spelling — and it has no mechanically equivalent successor.
        AggregatedHttpResponse r = gateway.blockingWebClient().get("/api/v1/model/health");

        assertThat(r.headers().get("deprecation")).isEqualTo("true");
        assertThat(r.headers().get("link")).isNull();
    }

    @Test
    void healthIsExemptFromDeprecationHeaders() {
        AggregatedHttpResponse r = gateway.blockingWebClient().get("/health");

        assertThat(r.headers().get("deprecation")).isNull();
    }
```

- [ ] **Step 7: Run the full gateway suite**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='*Gateway*+ApiVersionTest+ApiDeprecationDecoratorTest'
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/recsys/application/gateway/ApiDeprecationDecorator.java \
        src/test/java/com/recsys/application/gateway/ApiDeprecationDecoratorTest.java \
        src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java \
        src/test/java/com/recsys/api/gateway/GatewayServerIntegrationTest.java
git commit -m "feat(gateway): emit Deprecation and Sunset on unversioned and alias routes"
```

---

### Task 5: Compatibility policy and documentation

**Files:**
- Create: `docs/api-compatibility-policy.md`
- Modify: `docs/system_design/09_API_Gateway.md`, `docs/system_design/12_CDNS.md`, `docs/system_design/20_AuthN_AuthZ.md`, `README.md`
- Modify: the three internal `/v2/…` route registrations for the pipeline-variant comments

**Interfaces:**
- Consumes: nothing. Documentation only.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the policy document**

Create `docs/api-compatibility-policy.md`:

```markdown
# API Compatibility Policy

This is the contract between the RecSys gateway and its callers: what may change
without warning, what may not, and how much notice you get before something is removed.

## Versioning

The public surface is versioned in the URL path, immediately after `/api`:

```
POST /api/v1/recommend
GET  /api/v1/catalog/item?id=1
```

The version is owned by the gateway. Backends keep their own internal paths, which are
not part of this contract and are not reachable from outside the cluster.

**Unversioned paths are implicit v1 and are deprecated.** `GET /api/catalog/item` still
works and returns exactly what `GET /api/v1/catalog/item` returns; it carries deprecation
headers. Migrating is a path change only — request and response bodies are identical.

An unknown version returns `400`:

```json
{"error":"unsupported API version: v2; supported: v1"}
```

## What is a breaking change

**Additive — may ship at any time, without a version bump:**

- A new optional request field.
- A new response field.
- A new route.
- A new value in a field documented as open-ended.

Clients must tolerate unknown response fields. A client that rejects unrecognised JSON
keys is not compatible with this policy.

**Breaking — requires a new version:**

- Removing or renaming a response field.
- Tightening validation on an existing request field.
- Changing the status code returned for an unchanged condition.
- Changing default behaviour when a field is omitted.
- Removing a route.

## Support window

Two versions are supported concurrently: the current version N and its predecessor N−1.
A third is never promised.

## Deprecation and notice

A deprecated route or spelling responds with:

| Header | Meaning |
|---|---|
| `Deprecation: true` | This request shape is deprecated. |
| `Sunset: <HTTP-date>` | The earliest date it may be removed (RFC 8594). |
| `Link: <...>; rel="successor-version"` | The replacement path, when one is mechanically equivalent. |

`Sunset` is published when the deprecation is announced, never later. There is a minimum
of **twelve months** between that announcement and removal.

`Link` is emitted for the unversioned-spelling deprecation, where the successor is exactly
the same path under `/api/v1`. It is **not** emitted for the back-compat alias routes
(`/api/catalog`, `/api/model`, `/api/online`), because those do not map one-to-one onto
their replacements — see below.

## Deprecated today

| Deprecated | Replacement | Notes |
|---|---|---|
| Any unversioned `/api/...` path | The same path under `/api/v1` | Bodies identical; path change only |
| `/api/catalog/...` | `/api/v1/movies/...` and `/api/v1/recommend` | Not a one-to-one mapping — check the route you need |
| `/api/model/...` | `/api/v1/recommend` with `{"strategy":"model"}` | |
| `/api/online/...` | `/api/v1/recommend` with `{"strategy":"online"}`, `/api/v1/features` | |

## Removal

Removal is always an explicit, reviewed pull request. Nothing in the gateway expires a
route automatically, and a `Sunset` date passing does not by itself change behaviour.

This is deliberate. The project has no client inventory, so it cannot know who is still
calling a deprecated path. An enforcing sunset would be a scheduled outage for whoever
did not read the header. The date is a commitment about the *earliest* removal, not an
automated one.

## Detecting deprecation

Check for the `Deprecation` header on any response. In CI, failing a build when a
dependency starts returning `Deprecation: true` is the cheapest way to catch this early.

```bash
curl -sI https://<gateway>/api/catalog/item?id=1 | grep -i '^deprecation\|^sunset\|^link'
```
```

- [ ] **Step 2: Update `09_API_Gateway.md`**

Replace the entire "### API versioning and deprecation" subsection under §1 (it currently
describes the *pre-change* state) with:

```markdown
### API versioning and deprecation

The public surface is versioned in the path, immediately after `/api`:
`POST /api/v1/recommend`, `GET /api/v1/catalog/item`. The **gateway owns the version** —
backends keep their existing internal paths, so four services do not each reimplement
versioning.

**Edge paths carry API versions; internal paths carry pipeline names.** `/api/v1/recommend`
is API version 1. `/v2/recommend` on 6010, 7010, and 8080 is the *v2 pipeline* — the shared
recall → rank → hydrate → paginate contract that `CrossPathConsistencyTest` pins — and is
**not** API version 2. Internal paths are not part of the public contract and are
unreachable from outside the cluster.

[`ApiVersion`](../../src/main/java/com/recsys/application/gateway/ApiVersion.java) strips a
leading `/api/v{n}` segment, where `{n}` is one to four digits ending at a segment boundary.
Anything else is an ordinary path segment: `/api/version/x` and `/api/v1x/foo` are untouched.
An unversioned `/api` path is **implicit v1**, which is what keeps every existing client
working. An explicit unsupported version returns `400`
(`{"error":"unsupported API version: v2; supported: v1"}`) rather than `404`, matching the
precedent already set by the canonical `/api/recommend` strategy validation.

**Normalization strictly precedes authorization**, at all three entry points
(`GatewayProxyService`, `RecommendationGatewayService`, `LlmProxyService`). `/api/v1/users`
becomes `/api/users` before `authenticator.check` runs, so `PROTECTED_PREFIXES` and
`GATEWAY_PUBLIC_PATHS` keep working with **no versioned entries** and a version segment
cannot be used to slip past the never-public guard. The route table, rate-limit keys, and
circuit-breaker names are likewise unchanged, because route matching still sees
`/api/users`. Registering versioned prefixes in the route table instead would have required
a versioned twin in each of those lists, where a missed entry is a silent auth bypass.

Two entry points need explicit versioned twin registrations, because they are exact/prefix
Armeria routes rather than route-table entries: the canonical `/api/recommend`, and each LLM
route (LLM routes are filtered out of `proxyRoutes`, so the catch-all cannot serve them).

[`ApiDeprecationDecorator`](../../src/main/java/com/recsys/application/gateway/ApiDeprecationDecorator.java)
is a single server-wide decorator adding `Deprecation: true` and `Sunset` to two classes:

| Class | Example | `Link: rel="successor-version"` |
|---|---|---|
| Unversioned spelling | `/api/catalog/item` | yes — `</api/v1/catalog/item>` |
| Back-compat alias route | `/api/v1/catalog/item` | no |

The alias routes (`/api/catalog`, `/api/model`, `/api/online`) stay deprecated even when
versioned, because their deprecation is a different one: they duplicate the
resource-oriented routes. No `Link` is emitted for them — they strip to different backend
paths, so there is no mechanically equivalent successor to advertise. `/health` and
`/metrics` are exempt. The decorator is a no-op when `GATEWAY_DEPRECATION_SUNSET` is unset,
so a `Deprecation` header is never emitted without a published expiry.

The contract itself — breaking vs additive, the two-version support window, the twelve-month
notice, and why removal is never automatic — is in the
[API compatibility policy](../api-compatibility-policy.md).
```

(Link depths above are already correct for `docs/system_design/`: `../api-compatibility-policy.md`
for the policy, `../../src/...` for sources.)

- [ ] **Step 3: Add pipeline-variant comments at the three internal route registrations**

`src/main/java/com/recsys/api/serving/RecSysServer.java` at the `ROUTE_V2_RECOMMEND` constant,
`src/main/java/com/recsys/api/online/OnlinePredictionServer.java` at the `/v2/recommend`
registration, and `src/main/java/com/recsys/api/rest/RecommendationV2Controller.java` above the
class, each get:

```java
// "v2" is the PIPELINE name (recall -> rank -> hydrate -> paginate), not API version 2.
// API versions live only at the gateway edge as /api/v{n} — see docs/api-compatibility-policy.md.
```

- [ ] **Step 4: Update the remaining docs**

**`12_CDNS.md`** — in the §1 cache-behavior table, add two rows so all four behaviors appear:

```markdown
| `/api/v1/catalog/item*` | `recsys-item` (Cache) | 3600s (1h) | 86400s (24h) | `id` |
| `/api/v1/catalog/similar*` | `recsys-similar` (Cache) | 300s (5min) | 3600s (1h) | `movieId`, `k` |
```

and replace sharp edge 6 — which currently *predicts* this work — with the shipped state:

```markdown
6. **A versioned path is a separate cache key, and a separate behavior.** The versioned and
   unversioned catalog reads are four distinct CloudFront behaviors over the same two cache
   policies, so they occupy separate cache entries and warm independently. Adding a future
   `/api/v2/...` means new behaviors again — the gateway normalizes the version away, but
   the edge does not. Deploy order is one-way-dangerous: **gateway first, distribution
   second.** Adding a cache behavior before the gateway can normalize that path makes
   CloudFront drop `Authorization` on a path the origin still treats as private, turning
   every request into a `401` on a cached behavior.
```

**`20_AuthN_AuthZ.md`** — append to §3, after the `warnOnProtectedOverlap` bullet:

```markdown
Version segments cannot be used to evade either mechanism. The gateway normalizes
`/api/v1/users` to `/api/users` *before* `authenticator.check` runs (see
[09_API_Gateway §1](09_API_Gateway.md#api-versioning-and-deprecation)), so `PROTECTED_PREFIXES`
and `GATEWAY_PUBLIC_PATHS` are matched against version-free paths and need no versioned
entries. Had versioned prefixes instead been registered in the route table, every entry in
both lists would need a twin, and a missing one would be a silent auth bypass.
```
- `README.md` — add to the documentation map, after the API-versioning entry:

```markdown
- [API compatibility policy](docs/api-compatibility-policy.md) — what counts as a
  breaking change, the two-version support window, twelve-month deprecation notice,
  and the `Deprecation` / `Sunset` headers clients should watch.
```

- [ ] **Step 5: Verify links resolve**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q compile
grep -rn 'api-compatibility-policy' README.md docs/ | grep -v '\.worktrees'
```

Expected: compile succeeds (the comments are syntactically valid), and the policy is
referenced from README and `09_API_Gateway.md`.

- [ ] **Step 6: Commit**

```bash
git add docs/api-compatibility-policy.md README.md docs/system_design/ \
        src/main/java/com/recsys/api/serving/RecSysServer.java \
        src/main/java/com/recsys/api/online/OnlinePredictionServer.java \
        src/main/java/com/recsys/api/rest/RecommendationV2Controller.java
git commit -m "docs: add the API compatibility policy and disambiguate internal /v2"
```

---

### Task 6: Edge and deployment configuration

**Files:**
- Modify: `scripts/create-cdn-distribution.sh:122-131`
- Modify: `docker/cdn/default.conf.template`
- Modify: `k8s/base/configmap.yaml`
- Modify: `CONFIG_GUIDE.md`

**Interfaces:**
- Consumes: `GATEWAY_DEPRECATION_SUNSET`, read by `ApiDeprecationDecorator` from Task 4.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Add the two CloudFront cache behaviors**

In `scripts/create-cdn-distribution.sh`, change `CacheBehaviors` from `Quantity: 2` to
`Quantity: 4` and add the two versioned patterns, reusing the same policy variables:

```
  CacheBehaviors: {Quantity: 4, Items: [
    {PathPattern: "/api/catalog/item*", TargetOriginId: "alb-origin",
     ViewerProtocolPolicy: "redirect-to-https", CachePolicyId: $item_policy, Compress: true,
     AllowedMethods: {Quantity: 2, Items: ["GET","HEAD"],
       CachedMethods: {Quantity: 2, Items: ["GET","HEAD"]}}},
    {PathPattern: "/api/v1/catalog/item*", TargetOriginId: "alb-origin",
     ViewerProtocolPolicy: "redirect-to-https", CachePolicyId: $item_policy, Compress: true,
     AllowedMethods: {Quantity: 2, Items: ["GET","HEAD"],
       CachedMethods: {Quantity: 2, Items: ["GET","HEAD"]}}},
    {PathPattern: "/api/catalog/similar*", TargetOriginId: "alb-origin",
     ViewerProtocolPolicy: "redirect-to-https", CachePolicyId: $similar_policy, Compress: true,
     AllowedMethods: {Quantity: 2, Items: ["GET","HEAD"],
       CachedMethods: {Quantity: 2, Items: ["GET","HEAD"]}}},
    {PathPattern: "/api/v1/catalog/similar*", TargetOriginId: "alb-origin",
     ViewerProtocolPolicy: "redirect-to-https", CachePolicyId: $similar_policy, Compress: true,
     AllowedMethods: {Quantity: 2, Items: ["GET","HEAD"],
       CachedMethods: {Quantity: 2, Items: ["GET","HEAD"]}}}
  ]},
```

- [ ] **Step 2: Mirror them in the local nginx stand-in**

In `docker/cdn/default.conf.template`, add two locations after the existing ones. They are
byte-identical to their unversioned twins apart from the path — the versioned spelling must
cache exactly the same way:

```nginx
    # --- Mirrors: CacheBehavior /api/v1/catalog/item*, identical to the unversioned twin ---
    location = /api/v1/catalog/item {
        proxy_pass http://origin_upstream;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header x-origin-secret ${CDN_ORIGIN_SECRET};

        proxy_cache cdn;
        proxy_cache_key "$uri|$arg_id";
        proxy_cache_background_update on;
        proxy_cache_use_stale updating error timeout http_500 http_502 http_503 http_504;
        proxy_cache_revalidate on;
        add_header X-Cache $upstream_cache_status always;
    }

    # --- Mirrors: CacheBehavior /api/v1/catalog/similar*, identical to the unversioned twin ---
    location = /api/v1/catalog/similar {
        proxy_pass http://origin_upstream;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header x-origin-secret ${CDN_ORIGIN_SECRET};

        proxy_cache cdn;
        proxy_cache_key "$uri|$arg_movieId|$arg_k";
        proxy_cache_background_update on;
        proxy_cache_use_stale updating error timeout http_500 http_502 http_503 http_504;
        proxy_cache_revalidate on;
        add_header X-Cache $upstream_cache_status always;
    }
```

Note `$uri` is part of both cache keys, so the versioned and unversioned spellings occupy
separate cache entries. That is correct — they are separate CloudFront behaviors too.

- [ ] **Step 3: Set the sunset date in the base ConfigMap**

In `k8s/base/configmap.yaml`, beside the other `GATEWAY_*` entries:

```yaml
  # Twelve months' notice for unversioned /api paths and the backend-oriented alias routes.
  # Unset disables deprecation headers entirely — see docs/api-compatibility-policy.md.
  GATEWAY_DEPRECATION_SUNSET: "2027-07-27"
```

- [ ] **Step 4: Document the variable**

In `CONFIG_GUIDE.md`, in the "Gateway, authentication, and LLM integration" table, after the
`GATEWAY_ORIGIN_SECRET` row:

```markdown
| `GATEWAY_DEPRECATION_SUNSET` | unset | ISO-8601 date published as the `Sunset` header on unversioned `/api` paths and the `/api/catalog`, `/api/model`, `/api/online` aliases. Unset or unparseable disables deprecation headers. Base ConfigMap sets `2027-07-27`. |
```

- [ ] **Step 5: Verify the nginx template parses**

```bash
docker run --rm -e CDN_ORIGIN_HOST=localhost -e CDN_ORIGIN_PORT=8010 \
  -e CDN_ORIGIN_SECRET=test -e NGINX_ENVSUBST_FILTER=CDN_ \
  -v "$PWD/docker/cdn/default.conf.template:/etc/nginx/templates/default.conf.template:ro" \
  nginx:1.27-alpine nginx -t
```

Expected: `syntax is ok` / `test is successful`. If Docker is unavailable, skip and note it.

- [ ] **Step 6: Verify the CDN script's JSON is still well-formed**

```bash
bash -n scripts/create-cdn-distribution.sh
```

Expected: no output (syntax OK). The JSON itself is built by `jq` at runtime and cannot be
validated without AWS credentials; the `Quantity: 4` must match the four `Items` entries —
recount them by eye before committing.

- [ ] **Step 7: Run the full test suite**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test
```

Expected: PASS. This is the first full-suite run; it catches any integration test elsewhere
that asserted on gateway paths.

- [ ] **Step 8: Commit**

```bash
git add scripts/create-cdn-distribution.sh docker/cdn/default.conf.template \
        k8s/base/configmap.yaml CONFIG_GUIDE.md
git commit -m "feat(cdn): cache the versioned catalog reads and publish the sunset date"
```

---

## Rollout note for the reviewer

Ship order is **gateway first, distribution second**. Deploying the CloudFront behaviors
before the gateway would have CloudFront dropping `Authorization` on `/api/v1/catalog/item`
while the gateway still treats it as a non-public path — producing `401` on a cached
behavior. Tasks 1–5 are the gateway; Task 6 step 1 is the only change that must wait for
the deploy. Steps 2–4 of Task 6 are safe to land with the gateway.

## Follow-up, explicitly not in this plan

The `/v2/recommend` protection gap — 7010's `/v2/recommend` is not wrapped in
`OnlineAdmissionControl` although `/online/recommendation` beside it is, and 8080's has no
rate limiter, submit token, load shedder, A/B exposure logging, or metrics, unlike
`/api/v1/recommend`. That is a live correctness issue and gets its own spec.
