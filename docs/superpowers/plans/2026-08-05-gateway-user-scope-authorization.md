# Gateway User-Scope Authorization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop an authenticated end user from reading or writing another user's data by making the gateway compare the request's `userId` to the authenticated principal's identity.

**Architecture:** JWT callers become `USER`-tier principals carrying an application userId read from a configurable Cognito claim; API-key and anonymous callers stay `SERVICE`-tier and unrestricted. A table keyed on `(backend service, backend path)` declares which backend handlers take a `userId` and whether it arrives in the query string or the JSON body. `GatewayRequestForwarder.forward` — the single choke point every proxied and gateway-originated request already passes through — consults the table for `USER`-tier callers and returns 403 on a mismatch. A conformance test requires every gateway-reachable backend route to be classified, so a new route cannot ship unclassified.

**Tech Stack:** Java 17, Armeria, Jackson, Micrometer, JUnit 5, Maven.

## Global Constraints

- Build with JDK 17: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`. Newer JDKs fail a clean compile of two pre-existing files.
- Design doc: `docs/superpowers/specs/2026-08-05-gateway-user-scope-authorization-design.md`. Read it before starting.
- Every new or modified test in this plan must be added to the `resilience` profile in `pom.xml`, or it does not gate PRs.
- No new `x-authenticated-*` header is forwarded to backends. The comparison happens at the gateway.
- Behavior must not change for API-key or anonymous callers. Task 4's service-tier test is the regression that proves it.
- Never merge to `main` directly — this work ships as a PR.
- Branch: `feat/gateway-user-scope-authorization` (already created, spec already committed).

---

### Task 1: Extract the application userId claim from a verified JWT

**Files:**
- Modify: `src/main/java/com/recsys/application/gateway/CognitoConfig.java`
- Modify: `src/main/java/com/recsys/application/gateway/CognitoJwtVerifier.java:80-84,170`
- Test: `src/test/java/com/recsys/application/gateway/CognitoJwtVerifierTest.java`
- Modify: `pom.xml` (resilience profile includes)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `CognitoConfig.userIdClaim()` returning `String`; `CognitoJwtVerifier.VerifiedClaims(String subject, String clientId, String tokenUse, String appUserId)` with a 3-arg convenience constructor defaulting `appUserId` to `subject`.

Both records gain a component. A 3-arg convenience constructor on each keeps the nine existing call sites compiling — and for `VerifiedClaims` it is semantically exact, because the default claim name is `sub`.

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/com/recsys/application/gateway/CognitoJwtVerifierTest.java`:

```java
    @Test
    void verify_readsAppUserIdFromConfiguredClaim() throws Exception {
        KeyPair keyPair = keyPair();
        CognitoJwtVerifier verifier = verifierWithClaim(keyPair, "custom:recsys_user_id");
        String token = token(keyPair, claims() + ",\"custom:recsys_user_id\":\"42\"");

        assertEquals("42", verifier.verify(token).appUserId());
    }

    @Test
    void verify_coercesNumericClaimToString() throws Exception {
        KeyPair keyPair = keyPair();
        CognitoJwtVerifier verifier = verifierWithClaim(keyPair, "custom:recsys_user_id");
        String token = token(keyPair, claims() + ",\"custom:recsys_user_id\":42");

        assertEquals("42", verifier.verify(token).appUserId());
    }

    @Test
    void verify_blankAppUserIdWhenClaimIsAbsentOrNotScalar() throws Exception {
        KeyPair keyPair = keyPair();
        CognitoJwtVerifier verifier = verifierWithClaim(keyPair, "custom:recsys_user_id");

        // Absent entirely.
        assertEquals("", verifier.verify(token(keyPair, claims())).appUserId());
        // Present but an object — a claim shape we must never coerce into an identity.
        assertEquals("", verifier.verify(
                token(keyPair, claims() + ",\"custom:recsys_user_id\":{\"id\":\"42\"}")).appUserId());
        // Present but an array.
        assertEquals("", verifier.verify(
                token(keyPair, claims() + ",\"custom:recsys_user_id\":[\"42\"]")).appUserId());
    }

    @Test
    void verify_defaultsAppUserIdToSubject() throws Exception {
        KeyPair keyPair = keyPair();
        CognitoJwtVerifier verifier = verifier(keyPair);   // default claim name is "sub"
        String token = token(keyPair, claims());

        CognitoJwtVerifier.VerifiedClaims verified = verifier.verify(token);
        assertEquals(verified.subject(), verified.appUserId());
    }

    private CognitoJwtVerifier verifierWithClaim(KeyPair keyPair, String claimName) {
        CognitoConfig config = new CognitoConfig(
                "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_demo",
                "app-client",
                Set.of("access", "id"),
                claimName);
        return new CognitoJwtVerifier(
                config,
                new CognitoJwtVerifier.StaticJwkProvider(Map.of("kid-1", keyPair.getPublic())),
                clock);
    }
```

Add the two helpers the tests above rely on, adapting them to whatever the file already
provides for key generation and claim strings — read the top of the file first. If the file
already has a key-pair fixture and a valid-claims string, reuse them and delete these:

```java
    private KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /** Issuer, audience, subject, token_use, and an exp far enough ahead of `clock` to be valid. */
    private String claims() {
        return "\"iss\":\"https://cognito-idp.us-east-1.amazonaws.com/us-east-1_demo\""
                + ",\"aud\":\"app-client\""
                + ",\"sub\":\"11111111-2222-3333-4444-555555555555\""
                + ",\"token_use\":\"access\""
                + ",\"exp\":" + (clock.instant().getEpochSecond() + 3600);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=CognitoJwtVerifierTest
```

Expected: compilation failure — `CognitoConfig` has no 4-arg constructor and `VerifiedClaims` has no `appUserId()`.

- [ ] **Step 3: Add `userIdClaim` to `CognitoConfig`**

Replace the record header and `fromEnvironment` in `src/main/java/com/recsys/application/gateway/CognitoConfig.java`:

```java
record CognitoConfig(String issuer, String audience, Set<String> tokenUses, String userIdClaim) {

    /** Cognito's own subject claim. The default, so a pool that mints app userIds as `sub` needs no config. */
    static final String DEFAULT_USER_ID_CLAIM = "sub";

    /** Callers that predate the user-scope work; `sub` is the claim they implicitly meant. */
    CognitoConfig(String issuer, String audience, Set<String> tokenUses) {
        this(issuer, audience, tokenUses, DEFAULT_USER_ID_CLAIM);
    }

    CognitoConfig {
        userIdClaim = userIdClaim == null || userIdClaim.isBlank()
                ? DEFAULT_USER_ID_CLAIM
                : userIdClaim.trim();
    }

    static CognitoConfig fromEnvironment(EnvVars.EnvReader env) {
        String issuer = stripTrailingSlash(read(env, "GATEWAY_COGNITO_ISSUER", ""));
        String audience = read(env, "GATEWAY_COGNITO_AUDIENCE", "");
        if (!issuer.isBlank() && audience.isBlank()) {
            throw new IllegalStateException(
                    "GATEWAY_COGNITO_AUDIENCE is required when GATEWAY_COGNITO_ISSUER is set");
        }
        String tokenUseCsv = read(env, "GATEWAY_COGNITO_TOKEN_USE", "access");
        Set<String> tokenUses = Stream.of(tokenUseCsv.split(","))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        String userIdClaim = read(env, "GATEWAY_COGNITO_USER_ID_CLAIM", DEFAULT_USER_ID_CLAIM);
        return new CognitoConfig(issuer, audience, tokenUses, userIdClaim);
    }
```

Leave the rest of the file (`isConfigured`, `read`, `stripTrailingSlash`) unchanged.

- [ ] **Step 4: Extract the claim in `CognitoJwtVerifier`**

In `src/main/java/com/recsys/application/gateway/CognitoJwtVerifier.java`, change the tail of `verify`:

```java
        String subject = text(payload, "sub");
        String clientId = firstText(payload, "client_id", "aud");
        String tokenUse = text(payload, "token_use");
        String appUserId = scalarText(payload, config.userIdClaim());
        return new VerifiedClaims(subject, clientId, tokenUse, appUserId);
```

Add next to the existing `text` helper:

```java
    /**
     * A claim value usable as an identity: textual or numeric only. An object or array is
     * rejected rather than stringified — {@code asText()} on a container yields something that
     * looks like an id and is not one.
     */
    private static String scalarText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isContainerNode()) {
            return "";
        }
        return value.asText("").trim();
    }
```

Replace the `VerifiedClaims` record:

```java
    record VerifiedClaims(String subject, String clientId, String tokenUse, String appUserId) {

        /** The default claim is `sub`, so a caller that supplies no appUserId means exactly the subject. */
        VerifiedClaims(String subject, String clientId, String tokenUse) {
            this(subject, clientId, tokenUse, subject);
        }
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='CognitoJwtVerifierTest,CognitoJwtVerifierJwksTest,GatewayAuthenticatorTest'
```

Expected: PASS. The two untouched suites prove the convenience constructors kept existing call sites working.

- [ ] **Step 6: Add the test to the PR gate**

In `pom.xml`, inside the `resilience` profile's `<includes>` (after the `**/observability/...` entries), add:

```xml
                <!-- User-scope authorization. The claim these tests pin is the only thing
                     standing between an end-user token and another user's data once
                     GATEWAY_COGNITO_ISSUER is set; a claim silently read as blank fails
                     closed, but a claim silently coerced from a container would not. -->
                <include>**/gateway/CognitoJwtVerifierTest.java</include>
```

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/java/com/recsys/application/gateway/CognitoConfig.java \
        src/main/java/com/recsys/application/gateway/CognitoJwtVerifier.java \
        src/test/java/com/recsys/application/gateway/CognitoJwtVerifierTest.java
git commit -m "feat: read an application userId claim from verified JWTs"
```

---

### Task 2: Tier the gateway principal

**Files:**
- Modify: `src/main/java/com/recsys/application/gateway/GatewayPrincipal.java`
- Test: `src/test/java/com/recsys/application/gateway/GatewayPrincipalTest.java`
- Modify: `pom.xml`

**Interfaces:**
- Consumes: `CognitoJwtVerifier.VerifiedClaims.appUserId()` from Task 1.
- Produces: `GatewayPrincipal.Tier` (enum, values `SERVICE` and `USER`); `GatewayPrincipal.tier()` returning `Tier`; `GatewayPrincipal.appUserId()` returning `String`. Record component order becomes `(subject, clientId, tokenUse, rateLimitKey, tier, appUserId)`.

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/com/recsys/application/gateway/GatewayPrincipalTest.java`:

```java
    @Test
    void ofJwt_isUserTierCarryingTheAppUserId() {
        GatewayPrincipal p = GatewayPrincipal.ofJwt(
                new CognitoJwtVerifier.VerifiedClaims("sub-1", "app-client", "access", "42"));
        assertEquals(GatewayPrincipal.Tier.USER, p.tier());
        assertEquals("42", p.appUserId());
    }

    @Test
    void ofJwt_isUserTierEvenWhenTheClaimIsMissing() {
        // Credential type decides the tier, not claim presence. A JWT whose userId claim did not
        // resolve must stay USER-tier so user-scoped routes deny it, rather than falling through
        // to service-tier freedom — that fall-through is how a claim-name typo becomes a bypass.
        GatewayPrincipal p = GatewayPrincipal.ofJwt(
                new CognitoJwtVerifier.VerifiedClaims("sub-1", "app-client", "access", ""));
        assertEquals(GatewayPrincipal.Tier.USER, p.tier());
        assertEquals("", p.appUserId());
    }

    @Test
    void ofApiKey_isServiceTierWithNoAppUserId() {
        GatewayPrincipal p = GatewayPrincipal.ofApiKey("super-secret-key");
        assertEquals(GatewayPrincipal.Tier.SERVICE, p.tier());
        assertEquals("", p.appUserId());
    }

    @Test
    void anonymous_isServiceTier() {
        // Anonymous exists only under GATEWAY_ALLOW_ANONYMOUS=true, which is dev/local. Tiering it
        // USER would 403 every local request against a user-scoped route.
        assertEquals(GatewayPrincipal.Tier.SERVICE, GatewayPrincipal.anonymous().tier());
    }

    @Test
    void appUserId_isNotForwardedAsAHeader() {
        // The finding being closed is "the gateway injects an identity header no backend reads".
        // A second unread header would reproduce it. The comparison stays at the gateway.
        GatewayPrincipal p = GatewayPrincipal.ofJwt(
                new CognitoJwtVerifier.VerifiedClaims("sub-1", "app-client", "access", "42"));
        assertFalse(p.identityHeaders().containsKey("x-authenticated-user-id"));
        assertEquals(Map.of(
                "x-authenticated-subject", "sub-1",
                "x-authenticated-client-id", "app-client",
                "x-authenticated-token-use", "access"), p.identityHeaders());
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=GatewayPrincipalTest
```

Expected: compilation failure — no `Tier`, no `tier()`, no `appUserId()`.

- [ ] **Step 3: Add the tier and the app userId**

Replace the record header and factories in `src/main/java/com/recsys/application/gateway/GatewayPrincipal.java`:

```java
public record GatewayPrincipal(String subject, String clientId, String tokenUse,
                               String rateLimitKey, Tier tier, String appUserId) {

    /**
     * Which authorization rules apply to this caller.
     *
     * <p>Decided by credential type, never by claim presence. A JWT caller is a {@code USER}
     * even when its userId claim did not resolve, so a claim-name misconfiguration fails closed
     * on user-scoped routes instead of promoting every end user to unrestricted access.
     */
    public enum Tier { SERVICE, USER }

    private static final GatewayPrincipal ANONYMOUS =
            new GatewayPrincipal("", "", "", "anonymous", Tier.SERVICE, "");

    public static GatewayPrincipal anonymous() {
        return ANONYMOUS;
    }

    public static GatewayPrincipal ofJwt(CognitoJwtVerifier.VerifiedClaims claims) {
        String subject = claims.subject() == null ? "" : claims.subject();
        String clientId = claims.clientId() == null ? "" : claims.clientId();
        String tokenUse = claims.tokenUse() == null ? "" : claims.tokenUse();
        String appUserId = claims.appUserId() == null ? "" : claims.appUserId();
        String key = !subject.isBlank() ? "user:" + subject
                : !clientId.isBlank() ? "client:" + clientId
                : "anonymous";
        return new GatewayPrincipal(subject, clientId, tokenUse, key, Tier.USER, appUserId);
    }

    public static GatewayPrincipal ofApiKey(String matchedKey) {
        return new GatewayPrincipal("", "service", "", "apikey:" + sha256Prefix(matchedKey),
                Tier.SERVICE, "");
    }
```

Leave `identityHeaders()` and `sha256Prefix` exactly as they are — no new header.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='GatewayPrincipalTest,GatewayAuthenticatorTest,GatewayRequestForwarderTest,LlmProxyServiceTest'
```

Expected: PASS. If anything constructs `GatewayPrincipal` positionally, fix it to use a factory.

- [ ] **Step 5: Add the test to the PR gate**

In `pom.xml`, immediately after the `CognitoJwtVerifierTest` include from Task 1:

```xml
                <include>**/gateway/GatewayPrincipalTest.java</include>
```

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/java/com/recsys/application/gateway/GatewayPrincipal.java \
        src/test/java/com/recsys/application/gateway/GatewayPrincipalTest.java
git commit -m "feat: tier the gateway principal as SERVICE or USER"
```

---

### Task 3: Declare which backend routes are user-scoped

**Files:**
- Create: `src/main/java/com/recsys/application/gateway/UserIdSource.java` — `QUERY`, `BODY`, and `BODY_INSTANCES` (added during execution; see Task 5)
- Create: `src/main/java/com/recsys/application/gateway/UserScopedRoutes.java`
- Test: `src/test/java/com/recsys/application/gateway/UserScopedRoutesTest.java`
- Modify: `pom.xml`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `enum UserIdSource { QUERY, BODY }` with `String extract(String targetPath, AggregatedHttpRequest request)`.
  - `UserScopedRoutes.lookup(String serviceName, String backendPath)` returning `UserIdSource` or `null`.
  - `UserScopedRoutes.pathWithoutQuery(String targetPath)` returning `String`.
  - `UserScopedRoutes.table()` returning `Map<String, Map<String, UserIdSource>>` for the Task 5 conformance test.

The key is `(serviceName, backendPath)`, not the gateway path: `/api/users`, `/api/movies`, and
`/api/catalog` all resolve to 6010 and `MicroserviceRoute.rewrite` forwards the suffix verbatim,
so one handler is reachable under three prefixes.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/recsys/application/gateway/UserScopedRoutesTest.java`:

```java
package com.recsys.application.gateway;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.RequestHeaders;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UserScopedRoutesTest {

    @Test
    void lookup_findsQueryAndBodyRoutes() {
        assertEquals(UserIdSource.QUERY,
                UserScopedRoutes.lookup("recsys-catalog-serving", "/getuser"));
        assertEquals(UserIdSource.QUERY,
                UserScopedRoutes.lookup("recsys-online-serving", "/online/features"));
        assertEquals(UserIdSource.BODY,
                UserScopedRoutes.lookup("recsys-model-serving", "/v2/sequential/recommend"));
    }

    @Test
    void lookup_isExactNeverPrefix() {
        // Prefix-with-boundary matching is what created the /api/catalog trap that
        // PROTECTED_PREFIXES exists to survive (20_AuthN_AuthZ §3). Not repeated here.
        assertNull(UserScopedRoutes.lookup("recsys-catalog-serving", "/getuserprofile"));
        assertNull(UserScopedRoutes.lookup("recsys-catalog-serving", "/getuser/extra"));
    }

    @Test
    void lookup_returnsNullForUnknownOrNullService() {
        assertNull(UserScopedRoutes.lookup("recsys-llm", "/getuser"));
        assertNull(UserScopedRoutes.lookup(null, "/getuser"));
    }

    @Test
    void pathWithoutQuery_splitsOnTheFirstQuestionMark() {
        assertEquals("/getuser", UserScopedRoutes.pathWithoutQuery("/getuser?userId=42"));
        assertEquals("/getuser", UserScopedRoutes.pathWithoutQuery("/getuser"));
        assertEquals("/getuser", UserScopedRoutes.pathWithoutQuery("/getuser?a=1?b=2"));
    }

    @Test
    void query_extractsFromTargetPathNotRequestHeaders() {
        // The request's own path is still the pre-rewrite gateway path; the rewritten query
        // lives in targetPath. Reading the wrong one silently extracts nothing.
        AggregatedHttpRequest request = AggregatedHttpRequest.of(
                RequestHeaders.of(HttpMethod.GET, "/api/catalog/getuser?userId=999"),
                HttpData.empty());
        assertEquals("42", UserIdSource.QUERY.extract("/getuser?userId=42", request));
    }

    @Test
    void query_blankWhenAbsent() {
        AggregatedHttpRequest request = AggregatedHttpRequest.of(
                RequestHeaders.of(HttpMethod.GET, "/api/catalog/getuser"), HttpData.empty());
        assertEquals("", UserIdSource.QUERY.extract("/getuser", request));
        assertEquals("", UserIdSource.QUERY.extract("/getuser?limit=5", request));
    }

    @Test
    void body_extractsStringAndNumericUserId() {
        assertEquals("42", UserIdSource.BODY.extract("/v2/recommend", body("{\"userId\":\"42\"}")));
        // 6010 and 7010 bind userId as an int, so the JSON may legitimately be a number.
        assertEquals("42", UserIdSource.BODY.extract("/v2/recommend", body("{\"userId\":42}")));
    }

    @Test
    void body_blankWhenMissingUnparseableOrNotScalar() {
        assertEquals("", UserIdSource.BODY.extract("/v2/recommend", body("{\"limit\":5}")));
        assertEquals("", UserIdSource.BODY.extract("/v2/recommend", body("not json")));
        assertEquals("", UserIdSource.BODY.extract("/v2/recommend", body("[1,2]")));
        assertEquals("", UserIdSource.BODY.extract("/v2/recommend", body("{\"userId\":{\"id\":1}}")));
        assertEquals("", UserIdSource.BODY.extract("/v2/recommend", body("")));
    }

    private static AggregatedHttpRequest body(String json) {
        return AggregatedHttpRequest.of(
                RequestHeaders.of(HttpMethod.POST, "/api/recommend"), HttpData.ofUtf8(json));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=UserScopedRoutesTest
```

Expected: compilation failure — neither class exists.

- [ ] **Step 3: Create `UserIdSource`**

Create `src/main/java/com/recsys/application/gateway/UserIdSource.java`:

```java
package com.recsys.application.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.QueryParams;

/**
 * Where a user-scoped backend route carries the {@code userId} it acts on.
 *
 * <p>Extraction is deliberately total: anything it cannot read as a scalar id — absent, blank,
 * malformed, an object, an array — comes back as {@code ""}, which the caller treats as a denial.
 * A request whose subject cannot be determined is a request that cannot be authorized.
 */
enum UserIdSource {

    QUERY {
        @Override
        String extract(String targetPath, AggregatedHttpRequest request) {
            int mark = targetPath.indexOf('?');
            if (mark < 0) {
                return "";
            }
            String value = QueryParams.fromQueryString(targetPath.substring(mark + 1)).get(PARAM);
            return value == null ? "" : value.trim();
        }
    },

    BODY {
        @Override
        String extract(String targetPath, AggregatedHttpRequest request) {
            try {
                JsonNode root = MAPPER.readTree(request.contentUtf8());
                if (root == null || !root.isObject()) {
                    return "";
                }
                JsonNode value = root.get(PARAM);
                if (value == null || value.isNull() || value.isContainerNode()) {
                    return "";
                }
                return value.asText("").trim();
            } catch (Exception e) {
                return "";
            }
        }
    };

    /** The parameter and JSON field name is `userId` on every route in the table. */
    static final String PARAM = "userId";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @param targetPath the rewritten backend path, including its query string
     * @param request    the already-aggregated request; reading it here costs no extra buffering
     */
    abstract String extract(String targetPath, AggregatedHttpRequest request);
}
```

- [ ] **Step 4: Create `UserScopedRoutes`**

Create `src/main/java/com/recsys/application/gateway/UserScopedRoutes.java`:

```java
package com.recsys.application.gateway;

import java.util.Map;

/**
 * The backend routes that act on a caller-named {@code userId}, and where that id arrives.
 *
 * <p>Keyed on the <em>backend</em> service and path rather than the gateway path. Three route
 * prefixes — {@code /api/users}, {@code /api/movies}, {@code /api/catalog} — all resolve to 6010,
 * and {@link MicroserviceRoute#rewrite} forwards the suffix verbatim, so {@code /api/catalog/getuser}
 * and {@code /api/users/getuser} are one handler reached two ways. Keying on the handler describes
 * it once and covers every prefix that reaches it.
 *
 * <p>Matching is exact, never by prefix: prefix-with-boundary matching is precisely what created
 * the {@code /api/catalog} trap documented in {@code 20_AuthN_AuthZ} §3.
 *
 * <p>{@code UserScopedRouteCoverageTest} requires every gateway-reachable backend route to appear
 * here or in that test's explicit not-user-scoped list, so a new route cannot ship unclassified.
 */
final class UserScopedRoutes {

    private static final Map<String, Map<String, UserIdSource>> TABLE = Map.of(
            "recsys-catalog-serving", Map.of(
                    "/getuser", UserIdSource.QUERY,
                    "/user", UserIdSource.QUERY,
                    "/getrecommendation", UserIdSource.QUERY,
                    "/recommendation", UserIdSource.QUERY,
                    "/setuserembedding", UserIdSource.QUERY,
                    "/v2/recommend", UserIdSource.BODY,
                    // The id lives at instances[].userId, not the top level — see Task 5.
                    "/v1/models/recmodel:predict", UserIdSource.BODY_INSTANCES),
            "recsys-online-serving", Map.of(
                    "/online/recommendation", UserIdSource.QUERY,
                    "/online/features", UserIdSource.QUERY,
                    "/v2/recommend", UserIdSource.BODY),
            "recsys-model-serving", Map.of(
                    "/api/v1/recommend", UserIdSource.BODY,
                    "/v2/recommend", UserIdSource.BODY,
                    "/v2/sequential/recommend", UserIdSource.BODY));

    private UserScopedRoutes() {}

    /** @return where this route's userId lives, or null when the route is not user-scoped. */
    static UserIdSource lookup(String serviceName, String backendPath) {
        if (serviceName == null || backendPath == null) {
            return null;
        }
        Map<String, UserIdSource> paths = TABLE.get(serviceName);
        return paths == null ? null : paths.get(backendPath);
    }

    /** Strips the query string from a forwarder targetPath, which arrives as `rawPath?rawQuery`. */
    static String pathWithoutQuery(String targetPath) {
        if (targetPath == null) {
            return "";
        }
        int mark = targetPath.indexOf('?');
        return mark < 0 ? targetPath : targetPath.substring(0, mark);
    }

    /** The declaration itself, for the conformance test. */
    static Map<String, Map<String, UserIdSource>> table() {
        return TABLE;
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=UserScopedRoutesTest
```

Expected: PASS.

- [ ] **Step 6: Add the test to the PR gate**

In `pom.xml`, after the `GatewayPrincipalTest` include:

```xml
                <include>**/gateway/UserScopedRoutesTest.java</include>
```

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/java/com/recsys/application/gateway/UserIdSource.java \
        src/main/java/com/recsys/application/gateway/UserScopedRoutes.java \
        src/test/java/com/recsys/application/gateway/UserScopedRoutesTest.java
git commit -m "feat: declare the user-scoped backend routes and how to read their userId"
```

---

### Task 4: Enforce user scope in the forwarder

**Files:**
- Modify: `src/main/java/com/recsys/application/gateway/GatewayRequestForwarder.java:30-90,127-155`
- Modify: `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java:85-130`
- Test: `src/test/java/com/recsys/application/gateway/UserScopeAuthorizationTest.java`
- Modify: `pom.xml`

**Interfaces:**
- Consumes: `GatewayPrincipal.Tier`/`tier()`/`appUserId()` (Task 2); `UserScopedRoutes.lookup`, `UserScopedRoutes.pathWithoutQuery`, `UserIdSource.extract` (Task 3).
- Produces: `GatewayRequestForwarder.authorizeUserScope(MicroserviceRoute route, String targetPath, AggregatedHttpRequest request, GatewayPrincipal principal)` returning `HttpResponse` (the 403) or `null` when allowed — package-private so the test drives it directly without standing up upstreams. Also a `MeterRegistry` overload on the public constructor and on `registryBacked`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/recsys/application/gateway/UserScopeAuthorizationTest.java`:

```java
package com.recsys.application.gateway;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.recsys.ratelimit.GatewayRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class UserScopeAuthorizationTest {

    private static final MicroserviceRoute CATALOG = new MicroserviceRoute(
            "catalog", "/api/catalog", "CATALOG_SERVICE_URL",
            URI.create("http://localhost:6010"), "/health", "recsys-catalog-serving");

    private static final MicroserviceRoute LLM = new MicroserviceRoute(
            "llm", "/api/llm", "LLM_SERVICE_URL", URI.create("http://localhost:11434"), "/api/tags");

    @Test
    void serviceTierIsUnaffected() {
        // The regression that proves "no behavior change today": every real caller is an API key.
        assertNull(forwarder().authorizeUserScope(
                CATALOG, "/getuser?userId=999", get(), GatewayPrincipal.ofApiKey("key-1")));
        assertNull(forwarder().authorizeUserScope(
                CATALOG, "/getuser?userId=999", get(), GatewayPrincipal.anonymous()));
    }

    @Test
    void userTierMatchingItsOwnIdIsAllowed() {
        assertNull(forwarder().authorizeUserScope(
                CATALOG, "/getuser?userId=42", get(), user("42")));
    }

    @Test
    void userTierNamingAnotherUserIsForbidden() {
        HttpResponse denied = forwarder().authorizeUserScope(
                CATALOG, "/getuser?userId=43", get(), user("42"));
        assertNotNull(denied);
        AggregatedHttpResponseAssert.assertForbidden(denied);
    }

    @Test
    void userTierWithNoResolvedClaimIsForbidden() {
        // Fails closed: a claim-name misconfiguration must not read as service-tier freedom.
        assertNotNull(forwarder().authorizeUserScope(
                CATALOG, "/getuser?userId=42", get(), user("")));
    }

    @Test
    void userTierWithNoUserIdInTheRequestIsForbidden() {
        // Authorize before validate: an unidentifiable subject is denied, not forwarded for the
        // backend to 400.
        assertNotNull(forwarder().authorizeUserScope(CATALOG, "/getuser", get(), user("42")));
    }

    @Test
    void bodyRoutesAreCheckedToo() {
        assertNull(forwarder().authorizeUserScope(
                CATALOG, "/v2/recommend", post("{\"userId\":\"42\"}"), user("42")));
        assertNotNull(forwarder().authorizeUserScope(
                CATALOG, "/v2/recommend", post("{\"userId\":\"43\"}"), user("42")));
    }

    @Test
    void idsAreComparedExactlyWithNoNumericNormalization() {
        // Same discipline as cacheKeyIntParam on the CDN-cached routes: one spelling, one identity.
        assertNotNull(forwarder().authorizeUserScope(
                CATALOG, "/getuser?userId=042", get(), user("42")));
    }

    @Test
    void undeclaredRoutesAreUntouched() {
        assertNull(forwarder().authorizeUserScope(
                CATALOG, "/item?id=7", get(), user("42")));
        // A route with no registry service name can never match the table.
        assertNull(forwarder().authorizeUserScope(LLM, "/api/tags", get(), user("42")));
    }

    @Test
    void aDenialNeverConsumesTheCircuitBreakerProbeSlot() {
        // Not a style point. A HALF_OPEN permit claims the breaker's single probe slot and is
        // released only by recordSuccess/recordFailure, which run on the upstream-response path.
        // A 403 returned while holding one would wedge the route into 503s until process restart.
        // threshold 1, cooldown 0: one failure opens the breaker and elapsed >= 0 makes it
        // immediately HALF_OPEN — deterministic, no clock injection needed.
        RouteCircuitBreaker cb = new RouteCircuitBreaker(1, 0L);
        cb.recordFailure(cb.tryAcquirePermit());
        assertEquals(RouteCircuitBreaker.State.HALF_OPEN, cb.state());

        GatewayRequestForwarder forwarder = forwarder(null, Map.of(CATALOG.name(), cb));
        AggregatedHttpRequest request = get();
        AggregatedHttpResponseAssert.assertForbidden(forwarder.forward(
                ServiceRequestContext.of(request.toHttpRequest()),
                request, CATALOG, "/getuser?userId=43", user("42")));

        assertNotNull(cb.tryAcquirePermit(),
                "the 403 must be returned before the probe permit is acquired — otherwise the "
                        + "unsettled permit wedges the route open forever");
    }

    @Test
    void denialsAreCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayRequestForwarder forwarder = forwarder(registry);

        forwarder.authorizeUserScope(CATALOG, "/getuser?userId=43", get(), user("42"));
        forwarder.authorizeUserScope(CATALOG, "/getuser?userId=44", get(), user("42"));

        assertEquals(2.0, registry.get("gateway_user_scope_rejected_total").counter().count());
    }

    private static GatewayPrincipal user(String appUserId) {
        return GatewayPrincipal.ofJwt(
                new CognitoJwtVerifier.VerifiedClaims("sub-1", "app-client", "access", appUserId));
    }

    private static AggregatedHttpRequest get() {
        return AggregatedHttpRequest.of(
                RequestHeaders.of(HttpMethod.GET, "/api/catalog/getuser"), HttpData.empty());
    }

    private static AggregatedHttpRequest post(String json) {
        return AggregatedHttpRequest.of(
                RequestHeaders.of(HttpMethod.POST, "/api/recommend"), HttpData.ofUtf8(json));
    }

    private static GatewayRequestForwarder forwarder() {
        return forwarder(null);
    }

    private static GatewayRequestForwarder forwarder(io.micrometer.core.instrument.MeterRegistry registry) {
        // Health checking off: this test never intends a network call, and probing dead localhost
        // ports costs ~12s and floods the PR gate with Connection refused traces. Reachable
        // because the test shares the package with the 6-arg constructor.
        return new GatewayRequestForwarder(
                List.of(CATALOG, LLM), Duration.ofSeconds(1), Map.of(),
                GatewayRateLimiter.disabled(),
                new UpstreamEndpointGroups.HealthCheckConfig(false, 0L), registry);
    }

    /** Keeps the status assertion in one place; the body must never echo the requested id. */
    private static final class AggregatedHttpResponseAssert {
        static void assertForbidden(HttpResponse response) {
            var aggregated = response.aggregate().join();
            assertEquals(HttpStatus.FORBIDDEN, aggregated.status());
            assertEquals("{\"error\":\"forbidden: request is not scoped to the authenticated user\"}",
                    aggregated.contentUtf8());
        }
    }
}
```

`GatewayRateLimiter.disabled()` lives in `com.recsys.ratelimit` — hence the import above.

Give the `forwarder(...)` helper a second overload taking the circuit-breaker map, so the
placement test can pass a real breaker while every other test passes `Map.of()`.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=UserScopeAuthorizationTest
```

Expected: compilation failure — no `authorizeUserScope`, no `MeterRegistry` constructor overload.

- [ ] **Step 3: Add the counter and the authorization method to the forwarder**

In `src/main/java/com/recsys/application/gateway/GatewayRequestForwarder.java`, add imports:

```java
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
```

Add fields next to the existing ones:

```java
    private final Counter userScopeRejected;   // null when no registry was supplied
    private final AtomicBoolean userScopeWarned = new AtomicBoolean();
```

Every existing constructor must initialise `userScopeRejected`. Add the registry-taking
overloads and have the existing signatures delegate with `null`, so the nine existing call
sites keep compiling:

```java
    public GatewayRequestForwarder(List<MicroserviceRoute> routes,
                                   Duration timeout,
                                   Map<String, RouteCircuitBreaker> circuitBreakers,
                                   GatewayRateLimiter rateLimiter) {
        this(routes, timeout, circuitBreakers, rateLimiter, (MeterRegistry) null);
    }

    /** @param registry may be null, in which case denials are not counted. */
    public GatewayRequestForwarder(List<MicroserviceRoute> routes,
                                   Duration timeout,
                                   Map<String, RouteCircuitBreaker> circuitBreakers,
                                   GatewayRateLimiter rateLimiter,
                                   MeterRegistry registry) {
        this(routes, timeout, circuitBreakers, rateLimiter,
                UpstreamEndpointGroups.HealthCheckConfig.fromEnvironment(), registry);
    }
```

Give the existing package-private `HealthCheckConfig` constructor a `MeterRegistry` parameter
and add a 5-arg delegate for its current callers:

```java
    // Package-private: lets tests inject an explicit health-check config (e.g. a short probe interval).
    GatewayRequestForwarder(List<MicroserviceRoute> routes,
                            Duration timeout,
                            Map<String, RouteCircuitBreaker> circuitBreakers,
                            GatewayRateLimiter rateLimiter,
                            UpstreamEndpointGroups.HealthCheckConfig healthConfig) {
        this(routes, timeout, circuitBreakers, rateLimiter, healthConfig, null);
    }

    GatewayRequestForwarder(List<MicroserviceRoute> routes,
                            Duration timeout,
                            Map<String, RouteCircuitBreaker> circuitBreakers,
                            GatewayRateLimiter rateLimiter,
                            UpstreamEndpointGroups.HealthCheckConfig healthConfig,
                            MeterRegistry registry) {
        this.circuitBreakers = Map.copyOf(circuitBreakers);
        this.rateLimiter = rateLimiter == null ? GatewayRateLimiter.disabled() : rateLimiter;
        this.staticUpstreams = UpstreamEndpointGroups.create(routes, timeout, retryDecorator(), healthConfig);
        this.registryUpstreams = null;
        this.userScopeRejected = counter(registry);
    }
```

Do the same for the registry-backed path: give the private `(circuitBreakers, rateLimiter,
registryUpstreams)` constructor a `MeterRegistry` parameter, and add a `registryBacked` overload
that accepts one while the existing five-argument `registryBacked` delegates with `null`.

Add the counter factory and the authorization method:

```java
    private static Counter counter(MeterRegistry registry) {
        return registry == null ? null
                : Counter.builder("gateway_user_scope_rejected_total")
                        .description("Requests rejected because the caller named a userId that is not their own")
                        .register(registry);
    }

    /**
     * Denies a user-tier caller that names a userId other than its own.
     *
     * <p>Service-tier callers — API keys and, in dev, anonymous — are exempt: the trust model is
     * that they are backends legitimately acting for many users. Routes absent from
     * {@link UserScopedRoutes} are not user-scoped and are never checked.
     *
     * @return the 403 to return, or null when the request may proceed
     */
    HttpResponse authorizeUserScope(MicroserviceRoute route,
                                    String targetPath,
                                    AggregatedHttpRequest request,
                                    GatewayPrincipal principal) {
        if (principal == null || principal.tier() != GatewayPrincipal.Tier.USER) {
            return null;
        }
        UserIdSource source = UserScopedRoutes.lookup(
                route.serviceName(), UserScopedRoutes.pathWithoutQuery(targetPath));
        if (source == null) {
            return null;
        }
        String requested = source.extract(targetPath, request);
        // Blank on either side is a denial, not an exemption: a subject we cannot determine is a
        // request we cannot authorize, so we authorize before the backend gets to validate.
        if (principal.appUserId().isBlank()
                || requested.isBlank()
                || !requested.equals(principal.appUserId())) {
            if (userScopeRejected != null) {
                userScopeRejected.increment();
            }
            // Logged once, like GatewayOriginSecret: under a broken claim mapping this fires on
            // every request. Neither id is logged — the counter is the signal.
            if (userScopeWarned.compareAndSet(false, true)) {
                LOG.warn("Rejected a user-scoped request whose userId is not the caller's (first "
                                + "occurrence, route={}, principal={}). If this began at a "
                                + "deployment, GATEWAY_COGNITO_USER_ID_CLAIM and the user pool "
                                + "disagree. Further rejections are counted in "
                                + "gateway_user_scope_rejected_total and not logged.",
                        route.name(), principal.rateLimitKey());
            }
            return GatewayProxyService.gatewayError(HttpStatus.FORBIDDEN,
                    "forbidden: request is not scoped to the authenticated user");
        }
        return null;
    }
```

If the class has no `LOG` field, add one matching the convention in `GatewayOriginSecret`.

- [ ] **Step 4: Call it from `forward`**

In `forward`, insert immediately after the rate-limit block and **before** the
`RouteCircuitBreaker` permit is acquired:

```java
        // After rate limiting, so a probing caller still spends their own tokens on each denial.
        // Before the circuit-breaker permit, because success/failure is recorded only on the
        // upstream-response path below — returning 403 holding a permit would leak it.
        HttpResponse denied = authorizeUserScope(route, targetPath, request, principal);
        if (denied != null) {
            return denied;
        }
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=UserScopeAuthorizationTest
```

Expected: PASS.

- [ ] **Step 6: Wire the registry in at the gateway server**

In `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java`, move the
meter-registry line above the forwarder construction block (it is Armeria's JVM-wide singleton,
so the position is free) and pass it to both branches:

```java
        // Created before the forwarder so it can register gateway_user_scope_rejected_total.
        // PrometheusMeterRegistries.defaultRegistry() is a JVM-wide singleton; order is free.
        PrometheusMeterRegistry meterRegistry = PrometheusMeterRegistries.defaultRegistry();
```

Then, in the `registryEnabled` branch, pass `meterRegistry` as the final argument to
`GatewayRequestForwarder.registryBacked(...)`, and in the `else` branch:

```java
            forwarder = new GatewayRequestForwarder(
                    proxyRoutes, timeout, circuitBreakers, rateLimiter, meterRegistry);
```

Delete the now-duplicated declaration at the old location, keeping any comment that sat with it.

- [ ] **Step 7: Run the gateway suite**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='*Gateway*,UserScope*,MicroserviceRouteTest,LlmProxyServiceTest,RecommendationGatewayServiceTest'
```

Expected: PASS, with no change to any existing assertion.

- [ ] **Step 8: Add the test to the PR gate**

In `pom.xml`, after the `UserScopedRoutesTest` include:

```xml
                <include>**/gateway/UserScopeAuthorizationTest.java</include>
```

- [ ] **Step 9: Commit**

```bash
git add pom.xml src/main/java/com/recsys/application/gateway/GatewayRequestForwarder.java \
        src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java \
        src/test/java/com/recsys/application/gateway/UserScopeAuthorizationTest.java
git commit -m "feat: deny a user-tier caller that names another user's id"
```

---

### Task 5: Require every backend route to be classified

**Files:**
- Create: `src/test/java/com/recsys/application/gateway/UserScopedRouteCoverageTest.java`
- Modify: `pom.xml`

**Interfaces:**
- Consumes: `UserScopedRoutes.table()` (Task 3).
- Produces: nothing consumed by later tasks.

This is the test that closes the class rather than the instances. It works by **inverted
classification**: it does not try to infer which handlers are user-scoped — it enumerates every
gateway-reachable backend route and demands each one be either declared in `UserScopedRoutes` or
listed as explicitly not user-scoped, with a reason.

A scanner that silently matches nothing would pass vacuously, so the test also asserts a minimum
route count per service. That floor is the test's own smoke alarm.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/application/gateway/UserScopedRouteCoverageTest.java`:

```java
package com.recsys.application.gateway;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every route a gateway caller can reach on a backend must be classified: either declared in
 * {@link UserScopedRoutes}, or listed below as not user-scoped with a reason.
 *
 * <p>Adding a backend route therefore fails this test until someone decides which it is. That is
 * the point — the gap this closes was never one missing check, it was that nothing forced the
 * question to be asked.
 */
class UserScopedRouteCoverageTest {

    /**
     * Routes that take no caller-named userId. The value is why, so the next reader does not have
     * to re-derive it.
     */
    private static final Map<String, String> NOT_USER_SCOPED = Map.ofEntries(
            Map.entry("recsys-catalog-serving/item", "movie by id; no user"),
            Map.entry("recsys-catalog-serving/movie", "alias of /item"),
            Map.entry("recsys-catalog-serving/similar", "item-to-item; no user"),
            Map.entry("recsys-catalog-serving/setembedding", "item embedding; control-plane, not user-scoped"),
            Map.entry("recsys-catalog-serving/health", "liveness"),
            Map.entry("recsys-catalog-serving/health/ready", "readiness"),
            Map.entry("recsys-catalog-serving/health/load", "admission-control snapshot"),
            Map.entry("recsys-catalog-serving/metrics", "Prometheus exposition"),
            // NOTE: /v1/models/recmodel:predict is NOT here. The plan's first draft excused it as
            // "items, not a user profile" — wrong. PredictInstance carries a caller-supplied
            // userId and PairPredictionService loads u2vEmb:<userId>, so it is declared in
            // UserScopedRoutes with the instances[] source kind instead.
            Map.entry("recsys-catalog-serving/v1/catalog/movies", "catalog listing; no user"),
            Map.entry("recsys-online-serving/health", "liveness"),
            Map.entry("recsys-online-serving/health/live", "liveness"),
            Map.entry("recsys-online-serving/health/ready", "readiness"),
            Map.entry("recsys-online-serving/metrics", "Prometheus exposition"),
            Map.entry("recsys-online-serving/online/ops", "operator surface; guarded by AdminTokenGuard"),
            Map.entry("recsys-online-serving/shards/", "device-keyed, not user-keyed; no device-to-owner mapping exists"),
            Map.entry("recsys-model-serving/api/v1/token", "issues a submit token; no user named"),
            Map.entry("recsys-model-serving/api/v1/knowledge-bases", "knowledge bases; no user"),
            Map.entry("recsys-model-serving/api/v1/knowledge-bases/{knowledgeBaseId}", "knowledge base by id; no user"),
            Map.entry("recsys-model-serving/api/v1/auth/login", "issues a session token"),
            Map.entry("recsys-model-serving/api/v1/auth/logout", "ends a session"),
            Map.entry("recsys-model-serving/api/v1/model/versions", "control-plane; see 20_AuthN_AuthZ sharp edge 1"),
            Map.entry("recsys-model-serving/api/v1/model/versions/activate", "control-plane"),
            Map.entry("recsys-model-serving/api/v1/model/versions/rollback", "control-plane"),
            Map.entry("recsys-model-serving/health", "liveness"),
            Map.entry("recsys-model-serving/health/jvm", "diagnostics"),
            Map.entry("recsys-model-serving/health/gc", "diagnostics"),
            Map.entry("recsys-model-serving/health/live", "liveness"),
            Map.entry("recsys-model-serving/health/metrics", "diagnostics"),
            Map.entry("recsys-model-serving/health/load", "diagnostics"),
            Map.entry("recsys-model-serving/health/cache", "diagnostics"),
            Map.entry("recsys-model-serving/health/ab-tests", "A/B config; no user named"),
            Map.entry("recsys-model-serving/health/ready", "readiness"));

    /**
     * Floors, not exact counts: a regex that silently stops matching would otherwise make this
     * whole test vacuous. Raise them when a service genuinely grows.
     */
    private static final Map<String, Integer> MINIMUM_ROUTES = Map.of(
            "recsys-catalog-serving", 14,
            "recsys-online-serving", 8,
            "recsys-model-serving", 14);

    @Test
    void everyBackendRouteIsClassified() throws IOException {
        Map<String, Set<String>> routes = new LinkedHashMap<>();
        routes.put("recsys-catalog-serving",
                armeriaRoutes(Path.of("src/main/java/com/recsys/api/serving/RecSysServer.java")));
        routes.put("recsys-online-serving",
                armeriaRoutes(Path.of("src/main/java/com/recsys/api/online/OnlinePredictionServer.java")));
        routes.put("recsys-model-serving",
                springRoutes(Path.of("src/main/java/com/recsys/api/rest")));

        List<String> unclassified = new ArrayList<>();
        routes.forEach((service, paths) -> {
            int floor = MINIMUM_ROUTES.get(service);
            assertTrue(paths.size() >= floor,
                    "Route scan for " + service + " found only " + paths.size() + " routes (expected at "
                            + "least " + floor + "). The scanner has probably stopped matching — fix it "
                            + "rather than lowering the floor, or this test silently passes forever.");
            for (String path : paths) {
                boolean declared = UserScopedRoutes.lookup(service, path) != null;
                boolean excused = NOT_USER_SCOPED.containsKey(service + path);
                if (!declared && !excused) {
                    unclassified.add(service + path);
                }
            }
        });

        assertTrue(unclassified.isEmpty(),
                "Unclassified backend routes: " + unclassified + ". Every gateway-reachable route must "
                        + "either declare where its userId lives in UserScopedRoutes, or be listed in "
                        + "NOT_USER_SCOPED with a reason. See "
                        + "docs/superpowers/specs/2026-08-05-gateway-user-scope-authorization-design.md.");
    }

    // ---- scanners -------------------------------------------------------------------------

    private static final Pattern ROUTE_CONSTANT =
            Pattern.compile("String\\s+(ROUTE_[A-Z0-9_]+)\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern SERVICE_CALL =
            Pattern.compile("\\.service\\(\\s*(?:(ROUTE_[A-Z0-9_]+)|\"([^\"]+)\")");
    private static final Pattern PATH_PREFIX = Pattern.compile("pathPrefix\\(\"([^\"]+)\"\\)");
    private static final Pattern REGEX_ROUTE =
            Pattern.compile("\\.regex\\(\\s*\"\\^\"\\s*\\+\\s*(ROUTE_[A-Z0-9_]+)");

    /** Armeria: `.service(ROUTE_X, ...)`, `.service("/literal", ...)`, pathPrefix and regex routes. */
    private static Set<String> armeriaRoutes(Path file) throws IOException {
        String source = Files.readString(file);
        Map<String, String> constants = new LinkedHashMap<>();
        Matcher constant = ROUTE_CONSTANT.matcher(source);
        while (constant.find()) {
            constants.put(constant.group(1), constant.group(2));
        }
        Set<String> paths = new LinkedHashSet<>();
        Matcher call = SERVICE_CALL.matcher(source);
        while (call.find()) {
            String value = call.group(1) != null ? constants.get(call.group(1)) : call.group(2);
            if (value != null && value.startsWith("/")) {
                paths.add(value);
            }
        }
        Matcher prefix = PATH_PREFIX.matcher(source);
        while (prefix.find()) {
            paths.add(prefix.group(1));
        }
        Matcher regex = REGEX_ROUTE.matcher(source);
        while (regex.find()) {
            String value = constants.get(regex.group(1));
            if (value != null) {
                paths.add(value);
            }
        }
        return paths;
    }

    private static final Pattern CLASS_MAPPING =
            Pattern.compile("@RequestMapping\\(\\s*\"([^\"]+)\"\\s*\\)");
    private static final Pattern METHOD_MAPPING = Pattern.compile(
            "@(?:Get|Post|Put|Delete|Patch)Mapping\\(\\s*(?:value\\s*=\\s*)?(?:\"([^\"]*)\")?");

    /**
     * Spring: class-level @RequestMapping joined with each method mapping's path (possibly empty).
     *
     * <p>Recursive. A non-recursive listing would leave a controller in a sub-package invisible —
     * and an invisible route ships unclassified while this test still reports green, which is a
     * hole in the exact guarantee the test exists to provide. The sweep below is the other half.
     */
    private static Set<String> springRoutes(Path directory) throws IOException {
        Set<String> paths = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Matcher classMatcher = CLASS_MAPPING.matcher(source);
                String base = classMatcher.find() ? classMatcher.group(1) : "";
                Matcher methodMatcher = METHOD_MAPPING.matcher(source);
                while (methodMatcher.find()) {
                    String suffix = methodMatcher.group(1) == null ? "" : methodMatcher.group(1);
                    String path = base + suffix;
                    if (path.startsWith("/")) {
                        paths.add(path);
                    }
                }
            }
        }
        return paths;
    }
}
```

- [ ] **Step 2: Run the test**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=UserScopedRouteCoverageTest
```

Expected on first run: **failure is likely and informative.** Two failure shapes, each with a
different fix:

- *"Route scan for X found only N routes"* — a regex does not match the source as written. Fix
  the scanner, print the scanned set to see what it found, and do not lower the floor.
- *"Unclassified backend routes: [...]"* — the scan found real routes this plan did not
  anticipate. For each, decide: does a gateway caller name a `userId` on it? If yes, add it to
  `UserScopedRoutes` **and** to `UserScopedRoutesTest`; if no, add it to `NOT_USER_SCOPED` with a
  one-line reason. Do not mass-excuse a list to make the test green.

Iterate until it passes on merit.

- [ ] **Step 2b: Police the scanners themselves**

Add a second test method: a repo-wide sweep asserting that every file containing `@RestController`
lives under `api/rest`, and every file registering backend routes (`ServerBuilder` / `.service(`)
is one of the two scanned mains — failing with the offending path otherwise. Without it, a route
added in a new location is invisible to the scan and ships unclassified while this test stays
green. Watch the `@RestControllerAdvice` substring case (`GlobalExceptionHandler`) so it does not
produce a false failure.

- [ ] **Step 3: Verify the test actually bites**

Temporarily add a bogus route to the online server — `.service("/online/newthing", ...)` — and
confirm the test fails with that path in the unclassified list. Then revert it. A conformance
test that cannot fail is worse than none, because it reads as coverage.

- [ ] **Step 4: Add the test to the PR gate**

In `pom.xml`, after the `UserScopeAuthorizationTest` include:

```xml
                <!-- The conformance half: user-scope enforcement covers a route only if the route
                     is declared, so an undeclared new route is the failure mode that matters. -->
                <include>**/gateway/UserScopedRouteCoverageTest.java</include>
```

- [ ] **Step 5: Run the full PR gate**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/test/java/com/recsys/application/gateway/UserScopedRouteCoverageTest.java
git commit -m "test: require every gateway-reachable backend route to be classified"
```

---

### Task 6: Document the enforcement and the new env var

**Files:**
- Modify: `docs/system_design/20_AuthN_AuthZ.md` (new section before "Sharp edges — notes"; sharp edge 1 rewritten)
- Modify: `.claude/CLAUDE.md` (gateway env-var list)

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

Append a new `##` section — do **not** renumber the existing ones. `DocumentationIndexTest`
requires numbered design docs to be indexed, but this file is already indexed, so no README
change is needed.

- [ ] **Step 1: Add the new section**

Insert into `docs/system_design/20_AuthN_AuthZ.md`, immediately before `## Sharp edges — notes`:

```markdown
## 10. User-scope authorization — is this caller allowed to name this user?

§1–§4 answer "is this caller authenticated". This section answers "may this caller act on *this*
user", which is a separate question and was, until now, unasked: `userId` is an ordinary request
field, so any authenticated caller could name any user.

The rule is one comparison, applied at the gateway:

- **Credential type decides the tier.** `GatewayPrincipal.Tier` is `USER` for a JWT caller and
  `SERVICE` for an API key or (dev-only) anonymous. Service-tier callers are exempt — the trust
  model is that they are backends legitimately acting for many users, which is what
  `ModelRateLimiter` already assumes when it keys on the served userId rather than the caller.
- **User-tier callers may act only on their own id.** `GATEWAY_COGNITO_USER_ID_CLAIM` (default
  `sub`) names the JWT claim carrying the application userId; the gateway compares it, as an exact
  string, to the `userId` in the request.
- **Anything indeterminate is a denial.** A blank claim, a missing `userId`, an unparseable body
  — all 403. Tiering by credential type rather than by claim presence is what makes this hold: a
  JWT whose claim did not resolve stays user-tier and is denied, instead of falling through to
  service-tier freedom.

`UserScopedRoutes` declares which backend routes take a `userId` and whether it arrives in the
query string or the body, keyed on `(backend service, backend path)` — not on the gateway path,
because `/api/users`, `/api/movies`, and `/api/catalog` all resolve to 6010 and
`MicroserviceRoute.rewrite` forwards the suffix verbatim, so one handler is reachable under three
prefixes. `UserScopedRouteCoverageTest` requires every gateway-reachable backend route to be
declared there or explicitly listed as not user-scoped, so the enforcement cannot silently
develop holes as routes are added.

Enforcement lives in `GatewayRequestForwarder.forward`, beside the credential stripping and
identity injection of §2 — one function is the whole identity story, in both directions. It runs
after rate limiting (so a probing caller spends their own tokens) and before the circuit-breaker
permit is acquired (so a denial cannot leak one). Denials increment
`gateway_user_scope_rejected_total` and log once.

**What this does not yet prove.** No environment sets `GATEWAY_COGNITO_ISSUER`, so every caller
today is service-tier and this section describes a path that is never taken in production. Tests
construct verified claims directly; the extraction of a real claim from a real user pool is
untested until the first deployment that enables Cognito. Its failure mode is a 403 on every
user-scoped route, not an opening.

Design: [user-scope authorization](../superpowers/specs/2026-08-05-gateway-user-scope-authorization-design.md).
```

- [ ] **Step 2: Rewrite sharp edge 1**

Replace sharp edge 1 in the same file with:

```markdown
1. **Authorization is one comparison and one privilege tier.** §10 scopes user-tier callers to
   their own `userId`, but that is the only authorization rule in the system. Beyond it — and for
   every service-tier caller — any authenticated caller can reach every routed data-plane path,
   including control-plane writes such as `/api/catalog/setembedding` (overwrite item embeddings
   on 6010) and `/api/model/api/v1/model/versions/activate` and `/rollback` (swap the serving
   model). Those sit in the same privilege tier as a catalog read. The trust model is "callers are
   trusted backends", so this is consistent — but it means an API-key leak is still a
   control-plane compromise, and, because API keys are service-tier, still a read of every user.
```

- [ ] **Step 3: Document the env var**

In `.claude/CLAUDE.md`, in the paragraph covering `GATEWAY_ALLOW_ANONYMOUS` and the Cognito
variables, append:

```
`GATEWAY_COGNITO_USER_ID_CLAIM` (default `sub`) names the JWT claim carrying the application
userId. A JWT caller is user-tier and may only name its own `userId` on user-scoped routes;
API-key and anonymous callers are service-tier and unrestricted. Denials are 403 and counted in
`gateway_user_scope_rejected_total`. See `docs/system_design/20_AuthN_AuthZ.md` §10.
```

- [ ] **Step 4: Verify the docs tests still pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='DocumentationIndexTest,DocumentedMechanismTest'
```

Expected: PASS.

- [ ] **Step 5: Commit and open the PR**

```bash
git add docs/system_design/20_AuthN_AuthZ.md .claude/CLAUDE.md
git commit -m "docs: document gateway user-scope authorization"
git push -u origin feat/gateway-user-scope-authorization
gh pr create --title "feat: scope user-tier callers to their own userId at the gateway" --body "$(cat <<'EOF'
Closes the data-plane half of `20_AuthN_AuthZ` sharp edge 1: `userId` was an ordinary request
field, so any authenticated caller could read or write any user's data.

**The gap is latent, and this change is a no-op today.** No environment sets
`GATEWAY_COGNITO_ISSUER`, so every caller is an API-key service principal, exempt by design —
they are backends legitimately acting for many users. The enforcement becomes live the moment a
JWT path exists, and fails closed when it does.

- JWT callers are `USER`-tier and may only name their own `userId`; the id comes from
  `GATEWAY_COGNITO_USER_ID_CLAIM` (default `sub`). API-key and anonymous callers stay
  `SERVICE`-tier. Tiering by credential type, not claim presence, is what keeps a claim-name
  typo from silently promoting every end user to unrestricted access.
- `UserScopedRoutes` is keyed on `(backend service, backend path)`, not the gateway path:
  `/api/users`, `/api/movies`, and `/api/catalog` all resolve to 6010 and the suffix is forwarded
  verbatim, so one handler is reachable under three prefixes.
- `UserScopedRouteCoverageTest` requires every gateway-reachable backend route to be declared or
  explicitly excused with a reason, so a new route cannot ship unclassified.
- Denials are 403 with a fixed body that never echoes the requested id, counted in
  `gateway_user_scope_rejected_total`.

**Not verified end to end.** With no Cognito issuer anywhere, tests construct verified claims
directly; extracting a real claim from a real user pool is untested until the first deployment
that enables Cognito. Its failure mode is a 403 on every user-scoped route, not an opening.

Design: `docs/superpowers/specs/2026-08-05-gateway-user-scope-authorization-design.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Verification

Before opening the PR:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='*Gateway*,UserScope*,Cognito*'
```

Both must pass. The service-tier tests in Task 4 are the ones that matter most: they are the
evidence for the claim that this change alters nothing for any caller that exists today.
