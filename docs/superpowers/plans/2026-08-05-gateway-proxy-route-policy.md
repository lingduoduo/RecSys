# Gateway Proxy Route Policy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the gateway refuse to proxy anything it has not been told to proxy, and require the operator token for control-plane writes.

**Architecture:** One table, `BackendRoutePolicy`, classifies every backend route as `NO_PROXY`, `OPERATOR`, `USER_SCOPED`, or `AUTHENTICATED`. It absorbs today's `UserScopedRoutes` rather than sitting beside it, because both are keyed on the same `(serviceName, backendPath)` pair. `GatewayRequestForwarder.forward` consults it at the same choke point that already runs the user-scope check: an unclassified or `NO_PROXY` path 404s, an `OPERATOR` path requires `X-Admin-Token`, a `USER_SCOPED` path runs the existing check.

**Tech Stack:** Java 17, Armeria, Spring Boot (8080 only), Micrometer, JUnit 5, Maven, Kustomize.

## Global Constraints

- Build with JDK 17: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`. Newer JDKs fail a clean compile of two pre-existing files — a known pre-existing condition.
- Design doc: `docs/superpowers/specs/2026-08-05-gateway-proxy-route-policy-design.md`. Read it before starting.
- Every new or modified test must be added to the `resilience` profile in `pom.xml`, or it does not gate PRs.
- Exact-path matching is the default. Only `/actuator` and `/shards` are prefixes, and only because neither can be enumerated. Prefix matching is consulted **after** an exact miss, never before.
- A withheld path returns **404 with the body `{"error":"no route found"}`** — byte-identical to the response for a path that was never routed. A distinct status or body turns the allow-list into an enumeration oracle.
- The `OPERATOR` check is **tier-independent**: service-tier (API-key) callers are subject to it too. That is the point of the class.
- `SHARD_ADMIN_TOKEN` unset means the operator tier authorizes nobody (403), matching `AdminTokenGuard.isConfigured()`.
- Never merge to `main` directly — this work ships as a PR.
- Branch: `feat/gateway-proxy-route-policy` (already created; the spec is already committed on it).

---

### Task 1: Move `AdminTokenGuard` to a neutral package

**Files:**
- Move: `src/main/java/com/recsys/api/online/AdminTokenGuard.java` → `src/main/java/com/recsys/application/auth/AdminTokenGuard.java`
- Move: `src/test/java/com/recsys/api/online/AdminTokenGuardTest.java` → `src/test/java/com/recsys/application/auth/AdminTokenGuardTest.java`
- Modify (imports only): `src/main/java/com/recsys/api/online/OnlinePredictionServer.java`, `src/main/java/com/recsys/infrastructure/store/ShardedRecordService.java`, `src/test/java/com/recsys/api/online/OnlinePredictionServerIntegrationTest.java`, `src/test/java/com/recsys/infrastructure/store/ShardedRecordServiceIntegrationTest.java`, `src/test/java/com/recsys/application/gateway/UserScopedRouteCoverageTest.java`
- Modify: `pom.xml`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `com.recsys.application.auth.AdminTokenGuard` — unchanged API: `public AdminTokenGuard(String token)`, `public boolean isConfigured()`, `public boolean isAuthorized(String provided)`, `public static final String HEADER = "X-Admin-Token"`, `public static Function<? super HttpService, ? extends HttpService> newDecorator(AdminTokenGuard)`.

Task 4 has the gateway use this class. Left in `com.recsys.api.online`, that would be an `application/` layer importing from `api/` — backwards under the package map in CLAUDE.md, where `application/` holds use-case orchestration and `api/` holds transport. `com.recsys.application.auth` already holds `LoginTokenService` and `SubmitTokenService`, so an operator-token guard belongs there.

**This task changes no behavior.** It is a move plus import updates.

- [ ] **Step 1: Move both files and update the package declarations**

```bash
git mv src/main/java/com/recsys/api/online/AdminTokenGuard.java \
       src/main/java/com/recsys/application/auth/AdminTokenGuard.java
git mv src/test/java/com/recsys/api/online/AdminTokenGuardTest.java \
       src/test/java/com/recsys/application/auth/AdminTokenGuardTest.java
```

In each moved file change the first line to `package com.recsys.application.auth;`.

- [ ] **Step 2: Fix every consumer**

Add `import com.recsys.application.auth.AdminTokenGuard;` to each of the five files listed above.
`ShardedRecordService` is in `com.recsys.infrastructure.store` and `OnlinePredictionServer` in
`com.recsys.api.online`, so both need the import; the two integration tests and the coverage test
likewise. Find any you missed:

```bash
grep -rln "AdminTokenGuard" src/main src/test
```

Every hit outside `src/main/java/com/recsys/application/auth/` and
`src/test/java/com/recsys/application/auth/` must carry the import.

- [ ] **Step 3: Compile and run the affected tests**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test-compile
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='AdminTokenGuardTest,UserScopedRouteCoverageTest'
```

Expected: PASS, with no source change beyond package/import lines.

- [ ] **Step 4: Update the test's path in the PR gate**

In `pom.xml`, the `resilience` profile includes `**/online/AdminTokenGuardTest.java` if it is
listed. Check:

```bash
grep -n "AdminTokenGuardTest" pom.xml
```

If it appears, change the pattern to `**/auth/AdminTokenGuardTest.java`. If it does not appear,
add it after the existing `**/gateway/...` includes — an operator-token guard belongs in the gate:

```xml
                <include>**/auth/AdminTokenGuardTest.java</include>
```

- [ ] **Step 5: Run the PR gate**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: move AdminTokenGuard to the application auth package"
```

---

### Task 2: Classify every backend route in one table

**Files:**
- Create: `src/main/java/com/recsys/application/gateway/BackendRoutePolicy.java`
- Delete: `src/main/java/com/recsys/application/gateway/UserScopedRoutes.java`
- Modify: `src/main/java/com/recsys/application/gateway/GatewayRequestForwarder.java`, `src/main/java/com/recsys/application/gateway/GatewayAuthenticator.java`, `src/main/java/com/recsys/application/gateway/LlmProxyService.java`
- Rename: `src/test/java/com/recsys/application/gateway/UserScopedRoutesTest.java` → `BackendRoutePolicyTest.java`; `UserScopedRouteCoverageTest.java` → `BackendRouteCoverageTest.java`
- Modify: `pom.xml`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces:
  - `enum BackendRoutePolicy.Access { NO_PROXY, OPERATOR, USER_SCOPED, AUTHENTICATED }`
  - `record BackendRoutePolicy.Policy(Access access, UserIdSource userIdSource)`
  - `BackendRoutePolicy.lookup(String serviceName, String backendPath)` → `Policy` or null
  - `BackendRoutePolicy.pathWithoutQuery(String targetPath)` → `String`
  - `BackendRoutePolicy.effectiveServiceName(MicroserviceRoute route, List<MicroserviceRoute> known)` → `String` or null
  - `BackendRoutePolicy.declaresAnyUserScopedFor(String serviceName)` → `boolean`
  - `BackendRoutePolicy.userScopedGatewayPaths(List<MicroserviceRoute> routes)` → `Set<String>`
  - `BackendRoutePolicy.exactPaths(String serviceName)` → `Set<String>` (for the coverage test's orphan check)
  - `BackendRoutePolicy.prefixPaths(String serviceName)` → `Set<String>`

**This task changes no behavior.** Nothing enforces `NO_PROXY` or `OPERATOR` yet — Tasks 3 and 4 do. The three existing call sites keep working with renamed methods.

- [ ] **Step 1: Write the failing tests**

Rename `UserScopedRoutesTest.java` to `BackendRoutePolicyTest.java`, change the class name to
match, and replace every `UserScopedRoutes.lookup(svc, path)` assertion that expected a
`UserIdSource` with one that expects a `Policy`. For example, the existing

```java
assertEquals(UserIdSource.QUERY, UserScopedRoutes.lookup("recsys-catalog-serving", "/getuser"));
```

becomes

```java
assertEquals(new BackendRoutePolicy.Policy(BackendRoutePolicy.Access.USER_SCOPED, UserIdSource.QUERY),
        BackendRoutePolicy.lookup("recsys-catalog-serving", "/getuser"));
```

Keep every existing `UserIdSource.extract` test exactly as it is — that enum is unchanged.

Then add these:

```java
    @Test
    void telemetryIsClassifiedNoProxy() {
        assertEquals(BackendRoutePolicy.Access.NO_PROXY,
                BackendRoutePolicy.lookup("recsys-catalog-serving", "/metrics").access());
        assertEquals(BackendRoutePolicy.Access.NO_PROXY,
                BackendRoutePolicy.lookup("recsys-online-serving", "/metrics").access());
        assertEquals(BackendRoutePolicy.Access.NO_PROXY,
                BackendRoutePolicy.lookup("recsys-model-serving", "/health/ab-tests").access());
    }

    @Test
    void controlPlaneWritesAreClassifiedOperator() {
        assertEquals(BackendRoutePolicy.Access.OPERATOR,
                BackendRoutePolicy.lookup("recsys-catalog-serving", "/setembedding").access());
        assertEquals(BackendRoutePolicy.Access.OPERATOR,
                BackendRoutePolicy.lookup("recsys-model-serving", "/api/v1/model/versions/activate").access());
        assertEquals(BackendRoutePolicy.Access.OPERATOR,
                BackendRoutePolicy.lookup("recsys-model-serving", "/api/v1/model/versions/rollback").access());
        assertEquals(BackendRoutePolicy.Access.OPERATOR,
                BackendRoutePolicy.lookup("recsys-online-serving", "/online/ops").access());
    }

    @Test
    void ordinaryDataPathsAreClassifiedAuthenticated() {
        assertEquals(BackendRoutePolicy.Access.AUTHENTICATED,
                BackendRoutePolicy.lookup("recsys-catalog-serving", "/item").access());
        assertEquals(BackendRoutePolicy.Access.AUTHENTICATED,
                BackendRoutePolicy.lookup("recsys-model-serving", "/api/v1/token").access());
    }

    @Test
    void anUndeclaredPathHasNoPolicy() {
        assertNull(BackendRoutePolicy.lookup("recsys-catalog-serving", "/nope"));
        assertNull(BackendRoutePolicy.lookup("recsys-catalog-serving", "/getuser/extra"));
        assertNull(BackendRoutePolicy.lookup("no-such-service", "/item"));
        assertNull(BackendRoutePolicy.lookup(null, "/item"));
    }

    @Test
    void prefixEntriesMatchWithABoundaryAndOnlyAfterAnExactMiss() {
        // /actuator is config-driven and /shards is one Armeria pathPrefix — neither is enumerable.
        assertEquals(BackendRoutePolicy.Access.NO_PROXY,
                BackendRoutePolicy.lookup("recsys-model-serving", "/actuator").access());
        assertEquals(BackendRoutePolicy.Access.NO_PROXY,
                BackendRoutePolicy.lookup("recsys-model-serving", "/actuator/prometheus").access());
        assertEquals(BackendRoutePolicy.Access.AUTHENTICATED,
                BackendRoutePolicy.lookup("recsys-online-serving", "/shards/device").access());
        // Boundary: a longer name that merely starts with the prefix is not a match.
        assertNull(BackendRoutePolicy.lookup("recsys-model-serving", "/actuatorx"));
        assertNull(BackendRoutePolicy.lookup("recsys-online-serving", "/shardsx"));
    }

    @Test
    void userScopedPolicyCarriesItsSourceAndOthersDoNot() {
        assertEquals(UserIdSource.BODY_INSTANCES,
                BackendRoutePolicy.lookup("recsys-catalog-serving", "/v1/models/recmodel:predict").userIdSource());
        assertNull(BackendRoutePolicy.lookup("recsys-catalog-serving", "/item").userIdSource());
    }

    @Test
    void aPolicyCannotClaimUserScopeWithoutASource() {
        // The invariant is enforced in the record, not left to the table author's discipline.
        assertThrows(IllegalArgumentException.class,
                () -> new BackendRoutePolicy.Policy(BackendRoutePolicy.Access.USER_SCOPED, null));
        assertThrows(IllegalArgumentException.class,
                () -> new BackendRoutePolicy.Policy(BackendRoutePolicy.Access.AUTHENTICATED, UserIdSource.QUERY));
    }
```

Add `import static org.junit.jupiter.api.Assertions.assertThrows;` and `assertNull` if absent.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=BackendRoutePolicyTest
```

Expected: compilation failure — `BackendRoutePolicy` does not exist.

- [ ] **Step 3: Create `BackendRoutePolicy`**

Create `src/main/java/com/recsys/application/gateway/BackendRoutePolicy.java`. Copy
`effectiveServiceName`, its private `authorityOf` helper, and `pathWithoutQuery` from
`UserScopedRoutes.java` **verbatim, including their javadoc** — they were reviewed and are
unchanged in meaning. `gatewayPaths` becomes `userScopedGatewayPaths` and filters on
`Access.USER_SCOPED`; `declaresAnyFor` becomes `declaresAnyUserScopedFor`.

```java
package com.recsys.application.gateway;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the gateway is willing to proxy, per backend route, and what it demands first.
 *
 * <p>Keyed on the <em>backend</em> service and path rather than the gateway path. Three route
 * prefixes — {@code /api/users}, {@code /api/movies}, {@code /api/catalog} — all resolve to 6010,
 * and {@link MicroserviceRoute#rewrite} forwards the suffix verbatim, so one handler is reachable
 * under several gateway spellings. Keying on the handler describes it once and covers all of them.
 *
 * <p>This table absorbed the former {@code UserScopedRoutes} rather than sitting beside it: both
 * are keyed on the same pair, and two tables on an identical key that must agree is exactly how
 * {@code PROTECTED_PREFIXES} and the user-scope declaration drifted apart before.
 *
 * <p><strong>An unclassified path is denied.</strong> That is what makes this close a class rather
 * than a list of instances — a diagnostic route added to a backend tomorrow is unreachable through
 * the gateway, not exposed by it. {@code BackendRouteCoverageTest} requires every scanned backend
 * route to appear here, so the denial surfaces at build time rather than in production.
 */
final class BackendRoutePolicy {

    /** What the gateway requires before forwarding a request to a backend route. */
    enum Access {
        /** Never proxied. Telemetry and diagnostics, reachable only on the pod. */
        NO_PROXY,
        /** Requires the operator token, for every caller including service-tier ones. */
        OPERATOR,
        /** Requires that a user-tier caller name its own userId. */
        USER_SCOPED,
        /** Proxied to any authenticated caller — today's behaviour for ordinary data paths. */
        AUTHENTICATED
    }

    /** @param userIdSource where the userId lives; non-null exactly when access is USER_SCOPED. */
    record Policy(Access access, UserIdSource userIdSource) {
        Policy {
            if ((access == Access.USER_SCOPED) != (userIdSource != null)) {
                throw new IllegalArgumentException(
                        "userIdSource must be present exactly when access is USER_SCOPED, but was "
                                + userIdSource + " for " + access);
            }
        }
    }

    private static Policy of(Access access) {
        return new Policy(access, null);
    }

    private static Policy userScoped(UserIdSource source) {
        return new Policy(Access.USER_SCOPED, source);
    }

    // Exact match, always tried first. Map.ofEntries because Map.of caps at ten pairs.
    private static final Map<String, Map<String, Policy>> EXACT = Map.of(
            "recsys-catalog-serving", Map.ofEntries(
                    Map.entry("/getuser", userScoped(UserIdSource.QUERY)),
                    Map.entry("/user", userScoped(UserIdSource.QUERY)),
                    Map.entry("/getrecommendation", userScoped(UserIdSource.QUERY)),
                    Map.entry("/recommendation", userScoped(UserIdSource.QUERY)),
                    Map.entry("/setuserembedding", userScoped(UserIdSource.QUERY)),
                    Map.entry("/v2/recommend", userScoped(UserIdSource.BODY)),
                    // Scores against u2vEmb:<userId>, so naming another user reads their
                    // embedding — and the "user embedding not found" error is an existence oracle.
                    Map.entry("/v1/models/recmodel:predict", userScoped(UserIdSource.BODY_INSTANCES)),
                    // Overwrites item embeddings for every user of the system.
                    Map.entry("/setembedding", of(Access.OPERATOR)),
                    Map.entry("/item", of(Access.AUTHENTICATED)),
                    Map.entry("/movie", of(Access.AUTHENTICATED)),
                    Map.entry("/similar", of(Access.AUTHENTICATED)),
                    Map.entry("/v1/catalog/movies", of(Access.AUTHENTICATED)),
                    Map.entry("/metrics", of(Access.NO_PROXY)),
                    Map.entry("/health", of(Access.NO_PROXY)),
                    Map.entry("/health/ready", of(Access.NO_PROXY)),
                    Map.entry("/health/load", of(Access.NO_PROXY))),
            "recsys-online-serving", Map.ofEntries(
                    Map.entry("/online/recommendation", userScoped(UserIdSource.QUERY)),
                    Map.entry("/online/features", userScoped(UserIdSource.QUERY)),
                    Map.entry("/v2/recommend", userScoped(UserIdSource.BODY)),
                    // Already guarded by AdminTokenGuard on 7010; covered twice on purpose.
                    Map.entry("/online/ops", of(Access.OPERATOR)),
                    Map.entry("/metrics", of(Access.NO_PROXY)),
                    Map.entry("/health", of(Access.NO_PROXY)),
                    Map.entry("/health/live", of(Access.NO_PROXY)),
                    Map.entry("/health/ready", of(Access.NO_PROXY))),
            "recsys-model-serving", Map.ofEntries(
                    Map.entry("/api/v1/recommend", userScoped(UserIdSource.BODY)),
                    Map.entry("/v2/recommend", userScoped(UserIdSource.BODY)),
                    Map.entry("/v2/sequential/recommend", userScoped(UserIdSource.BODY)),
                    // Swap or warm the serving model for everyone.
                    Map.entry("/api/v1/model/versions", of(Access.OPERATOR)),
                    Map.entry("/api/v1/model/versions/activate", of(Access.OPERATOR)),
                    Map.entry("/api/v1/model/versions/rollback", of(Access.OPERATOR)),
                    Map.entry("/api/v1/model/versions/preload", of(Access.OPERATOR)),
                    Map.entry("/api/v1/token", of(Access.AUTHENTICATED)),
                    Map.entry("/api/v1/auth/login", of(Access.AUTHENTICATED)),
                    Map.entry("/api/v1/auth/logout", of(Access.AUTHENTICATED)),
                    Map.entry("/api/v1/knowledge-bases", of(Access.AUTHENTICATED)),
                    Map.entry("/api/v1/knowledge-bases/{knowledgeBaseId}", of(Access.AUTHENTICATED)),
                    Map.entry("/health/jvm", of(Access.NO_PROXY)),
                    Map.entry("/health/gc", of(Access.NO_PROXY)),
                    Map.entry("/health/live", of(Access.NO_PROXY)),
                    Map.entry("/health/metrics", of(Access.NO_PROXY)),
                    Map.entry("/health/load", of(Access.NO_PROXY)),
                    Map.entry("/health/cache", of(Access.NO_PROXY)),
                    Map.entry("/health/ab-tests", of(Access.NO_PROXY)),
                    Map.entry("/health/ready", of(Access.NO_PROXY))));

    /**
     * The only two paths that cannot be enumerated, so the only two matched by prefix.
     *
     * <p>{@code /actuator}'s membership comes from {@code MANAGEMENT_ENDPOINTS_EXPOSURE}, not from
     * source, so no scanner can list it. {@code /shards} is registered as a single Armeria
     * {@code pathPrefix} whose sub-paths are dispatched inside {@code ShardedRecordService}.
     *
     * <p>Stored without a trailing slash and matched with the boundary rule, so {@code /actuatorx}
     * is not {@code /actuator}. Consulted only after an exact miss — prefix-first matching is what
     * produced the {@code /api/catalog} trap of {@code 20_AuthN_AuthZ} §3.
     *
     * <p>{@code /shards} is AUTHENTICATED rather than OPERATOR because the prefix mixes tiers:
     * {@code POST /shards/topology} and {@code GET /shards/shard} are operator surfaces that
     * {@code ShardedRecordService} already guards, while {@code /shards/device} and
     * {@code /shards/records} are ordinary data paths that OPERATOR here would break.
     */
    private static final Map<String, Map<String, Policy>> PREFIX = Map.of(
            "recsys-model-serving", Map.of("/actuator", of(Access.NO_PROXY)),
            "recsys-online-serving", Map.of("/shards", of(Access.AUTHENTICATED)));

    private BackendRoutePolicy() {}

    /** @return the policy for this backend route, or null when it is not classified at all. */
    static Policy lookup(String serviceName, String backendPath) {
        if (serviceName == null || backendPath == null) {
            return null;
        }
        Map<String, Policy> exact = EXACT.get(serviceName);
        if (exact != null) {
            Policy hit = exact.get(backendPath);
            if (hit != null) {
                return hit;
            }
        }
        Map<String, Policy> prefixes = PREFIX.get(serviceName);
        if (prefixes == null) {
            return null;
        }
        for (Map.Entry<String, Policy> entry : prefixes.entrySet()) {
            String prefix = entry.getKey();
            if (backendPath.equals(prefix) || backendPath.startsWith(prefix + "/")) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** @return true when this backend service has any user-scoped route at all. */
    static boolean declaresAnyUserScopedFor(String serviceName) {
        Map<String, Policy> paths = serviceName == null ? null : EXACT.get(serviceName);
        return paths != null && paths.values().stream().anyMatch(p -> p.access() == Access.USER_SCOPED);
    }

    /** Declared exact backend paths for a service, for the coverage test's orphan check. */
    static Set<String> exactPaths(String serviceName) {
        Map<String, Policy> paths = EXACT.get(serviceName);
        return paths == null ? Set.of() : paths.keySet();
    }

    /** Declared prefix backend paths for a service; exempt from the orphan check by nature. */
    static Set<String> prefixPaths(String serviceName) {
        Map<String, Policy> paths = PREFIX.get(serviceName);
        return paths == null ? Set.of() : paths.keySet();
    }
```

Then append `userScopedGatewayPaths`, `effectiveServiceName`, `authorityOf`, and
`pathWithoutQuery`. Copy the last three from `UserScopedRoutes.java` unchanged. The first is the
old `gatewayPaths` with one added filter:

```java
    /**
     * The gateway-facing spellings of every <em>user-scoped</em> route, derived from the route
     * table: {@code route.prefix() + backendPath}. {@link GatewayAuthenticator} derives its
     * never-public guard from this rather than restating it — a user-scoped route listed in
     * {@code GATEWAY_PUBLIC_PATHS} would make its callers anonymous, hence service-tier, hence
     * exempt from the very check declared here.
     *
     * <p>User-scoped only, deliberately. NO_PROXY paths need no never-public guard because they
     * are not proxied at all, and OPERATOR paths carry their own credential.
     */
    static Set<String> userScopedGatewayPaths(List<MicroserviceRoute> routes) {
        Set<String> paths = new LinkedHashSet<>();
        for (MicroserviceRoute route : routes) {
            Map<String, Policy> declared = EXACT.get(route.serviceName());
            if (declared == null) {
                continue;
            }
            declared.forEach((backendPath, policy) -> {
                if (policy.access() == Access.USER_SCOPED) {
                    paths.add(route.prefix() + backendPath);
                }
            });
        }
        return Set.copyOf(paths);
    }
}
```

- [ ] **Step 4: Delete `UserScopedRoutes` and repoint its three call sites**

```bash
git rm src/main/java/com/recsys/application/gateway/UserScopedRoutes.java
```

- `GatewayAuthenticator`: `UserScopedRoutes.gatewayPaths(...)` → `BackendRoutePolicy.userScopedGatewayPaths(...)`.
- `LlmProxyService`: `UserScopedRoutes.effectiveServiceName(...)` → `BackendRoutePolicy.effectiveServiceName(...)`, and `UserScopedRoutes.declaresAnyFor(...)` → `BackendRoutePolicy.declaresAnyUserScopedFor(...)`.
- `GatewayRequestForwarder.authorizeUserScope`: replace

```java
        UserIdSource source = UserScopedRoutes.lookup(
                UserScopedRoutes.effectiveServiceName(route, MicroserviceRoute.defaults()),
                UserScopedRoutes.pathWithoutQuery(targetPath));
        if (source == null) {
            return null;
        }
```

with

```java
        BackendRoutePolicy.Policy policy = BackendRoutePolicy.lookup(
                BackendRoutePolicy.effectiveServiceName(route, MicroserviceRoute.defaults()),
                BackendRoutePolicy.pathWithoutQuery(targetPath));
        if (policy == null || policy.access() != BackendRoutePolicy.Access.USER_SCOPED) {
            return null;
        }
        UserIdSource source = policy.userIdSource();
```

Leave the rest of the method alone.

- [ ] **Step 5: Rewrite the coverage test to demand a class**

Rename `UserScopedRouteCoverageTest.java` to `BackendRouteCoverageTest.java` and the class with
it. Keep the scanners, the `Files.walk` recursion, the `everyRouteRegistrationLivesWhereAScannerLooks`
sweep, the per-service floors (16/9/20), and
`everyRouteReachingABackendDeclaresItsRegistryServiceName` exactly as they are.

**Delete the entire `NOT_USER_SCOPED` map.** Every route it excused now carries a real
classification instead. Replace the main assertion body with:

```java
        List<String> unclassified = new ArrayList<>();
        routes.forEach((service, paths) -> {
            int floor = MINIMUM_ROUTES.get(service);
            assertTrue(paths.size() >= floor,
                    "Route scan for " + service + " found only " + paths.size() + " routes (expected at "
                            + "least " + floor + "). The scanner has probably stopped matching — fix it "
                            + "rather than lowering the floor, or this test silently passes forever. "
                            + "Found: " + paths);
            for (String path : paths) {
                if (BackendRoutePolicy.lookup(service, path) == null) {
                    unclassified.add(service + path);
                }
            }
        });

        assertTrue(unclassified.isEmpty(),
                "Unclassified backend routes: " + unclassified + ". Every gateway-reachable route must "
                        + "be classified in BackendRoutePolicy as NO_PROXY, OPERATOR, USER_SCOPED or "
                        + "AUTHENTICATED. An unclassified route is denied at the gateway, so shipping "
                        + "one silently breaks it. See "
                        + "docs/superpowers/specs/2026-08-05-gateway-proxy-route-policy-design.md.");
```

Add two new test methods:

```java
    /**
     * The reverse direction: a declared exact path that no scan finds is dead weight that would
     * pre-classify a future route of the same name. Prefixes are exempt — /actuator is
     * config-driven and cannot be scanned at all.
     */
    @Test
    void noDeclaredExactPathIsAnOrphan() throws IOException {
        Map<String, Set<String>> scanned = scanAllServices();
        List<String> orphans = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : scanned.entrySet()) {
            for (String declared : BackendRoutePolicy.exactPaths(entry.getKey())) {
                if (!entry.getValue().contains(declared)) {
                    orphans.add(entry.getKey() + declared);
                }
            }
        }
        assertTrue(orphans.isEmpty(),
                "BackendRoutePolicy declares exact paths that no backend registers: " + orphans
                        + ". Remove them — a stale entry silently pre-classifies a future route.");
    }

    /**
     * Prefix matching runs only after an exact miss, so a prefix that covers a declared exact path
     * can never take effect — and reading the table would suggest otherwise.
     */
    @Test
    void noPrefixEntryShadowsADeclaredExactPath() {
        for (String service : MINIMUM_ROUTES.keySet()) {
            for (String prefix : BackendRoutePolicy.prefixPaths(service)) {
                for (String exact : BackendRoutePolicy.exactPaths(service)) {
                    assertFalse(exact.equals(prefix) || exact.startsWith(prefix + "/"),
                            "Prefix " + service + prefix + " shadows declared exact path " + exact);
                }
            }
        }
    }
```

Extract the three-service scan the main test does into a `scanAllServices()` helper returning
`Map<String, Set<String>>` so both tests use it. Add `assertFalse` to the imports if absent.

- [ ] **Step 6: Run the tests**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='BackendRoutePolicyTest,BackendRouteCoverageTest,UserScopeAuthorizationTest,LlmProxyServiceTest,GatewayAuthenticatorTest'
```

Expected: PASS. If `noDeclaredExactPathIsAnOrphan` fails, a path in the table above does not exist
on its backend — **fix the table**, do not weaken the test. If the main assertion reports an
unclassified route, classify it on its merits by reading the handler; do not guess from the name.
`/v1/models/recmodel:predict` is the standing example of a route whose name says nothing about
what it reads.

- [ ] **Step 7: Update the PR gate**

In `pom.xml`, change the two renamed includes:

```xml
                <include>**/gateway/BackendRoutePolicyTest.java</include>
                <include>**/gateway/BackendRouteCoverageTest.java</include>
```

Remove the `UserScopedRoutesTest` and `UserScopedRouteCoverageTest` includes.

- [ ] **Step 8: Run the PR gate and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
git add -A
git commit -m "refactor: classify every backend route in one policy table"
```

---

### Task 3: Deny anything not classified for proxying

**Files:**
- Modify: `src/main/java/com/recsys/application/gateway/GatewayRequestForwarder.java`
- Test: `src/test/java/com/recsys/application/gateway/ProxyRoutePolicyEnforcementTest.java` (create)
- Modify: `pom.xml`

**Interfaces:**
- Consumes: `BackendRoutePolicy.lookup`, `.Access`, `.Policy`, `.effectiveServiceName`, `.pathWithoutQuery` (Task 2).
- Produces: `GatewayRequestForwarder.enforceRoutePolicy(MicroserviceRoute route, String targetPath, AggregatedHttpRequest request, GatewayPrincipal principal)` returning `HttpResponse` (the denial) or null when the request may proceed. Task 4 adds the OPERATOR branch to this method.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/application/gateway/ProxyRoutePolicyEnforcementTest.java`:

```java
package com.recsys.application.gateway;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.recsys.ratelimit.GatewayRateLimiter;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The gateway proxies only what it has been told to proxy.
 *
 * <p>{@code MicroserviceRoute.rewrite} forwards the suffix under a matched prefix verbatim, so
 * before this every backend route was reachable through every prefix targeting its service —
 * telemetry included. Denial is an allow-list default, not a denylist: a diagnostic route added
 * tomorrow is unreachable rather than exposed.
 */
class ProxyRoutePolicyEnforcementTest {

    private static final MicroserviceRoute CATALOG = new MicroserviceRoute(
            "catalog", "/api/catalog", "CATALOG_SERVICE_URL",
            URI.create("http://localhost:6010"), "/health", "recsys-catalog-serving");

    private static final MicroserviceRoute MODEL = new MicroserviceRoute(
            "model", "/api/model", "MODEL_SERVICE_URL",
            URI.create("http://localhost:8080"), "/health/ready", "recsys-model-serving");

    private static final MicroserviceRoute LLM = new MicroserviceRoute(
            "llm", "/api/llm", "LLM_SERVICE_URL", URI.create("http://localhost:11434"), "/api/tags");

    @Test
    void telemetryIsNotProxied() {
        assertDenied404(forwarder().enforceRoutePolicy(CATALOG, "/metrics", get(), apiKey()));
        assertDenied404(forwarder().enforceRoutePolicy(MODEL, "/actuator/prometheus", get(), apiKey()));
        assertDenied404(forwarder().enforceRoutePolicy(MODEL, "/health/ab-tests", get(), apiKey()));
    }

    @Test
    void anUnclassifiedPathIsNotProxied() {
        assertDenied404(forwarder().enforceRoutePolicy(CATALOG, "/brand-new-endpoint", get(), apiKey()));
    }

    /**
     * A withheld path must be indistinguishable from one that was never routed, or the allow-list
     * becomes an enumeration oracle: probe a path, read the status, learn whether it exists.
     */
    @Test
    void aDenialIsIndistinguishableFromAnUnroutedPath() {
        AggregatedHttpResponse denied = forwarder()
                .enforceRoutePolicy(CATALOG, "/metrics", get(), apiKey()).aggregate().join();
        AggregatedHttpResponse unrouted = GatewayProxyService
                .gatewayError(HttpStatus.NOT_FOUND, "no route found").aggregate().join();

        assertEquals(unrouted.status(), denied.status());
        assertEquals(unrouted.contentUtf8(), denied.contentUtf8());
    }

    @Test
    void ordinaryDataPathsStillProxy() {
        assertNull(forwarder().enforceRoutePolicy(CATALOG, "/item?id=7", get(), apiKey()));
        assertNull(forwarder().enforceRoutePolicy(MODEL, "/api/v1/token", get(), apiKey()));
        assertNull(forwarder().enforceRoutePolicy(CATALOG, "/similar?id=7", get(), apiKey()));
    }

    /** The allow-list governs our backends. An LLM upstream is not ours to classify. */
    @Test
    void aRouteReachingNoKnownBackendIsUnaffected() {
        assertNull(forwarder().enforceRoutePolicy(LLM, "/api/generate", get(), apiKey()));
    }

    /** User-scope enforcement is unchanged: still applied, still only to user-tier callers. */
    @Test
    void userScopedRoutesKeepTheirExistingCheck() {
        assertNull(forwarder().enforceRoutePolicy(CATALOG, "/getuser?userId=99", get(), apiKey()));
        assertNotNull(forwarder().enforceRoutePolicy(CATALOG, "/getuser?userId=99", get(), user("42")));
        assertNull(forwarder().enforceRoutePolicy(CATALOG, "/getuser?userId=42", get(), user("42")));
    }

    private static void assertDenied404(HttpResponse response) {
        assertNotNull(response, "expected a denial");
        AggregatedHttpResponse aggregated = response.aggregate().join();
        assertEquals(HttpStatus.NOT_FOUND, aggregated.status());
        assertEquals("{\"error\":\"no route found\"}", aggregated.contentUtf8());
    }

    private static GatewayPrincipal apiKey() {
        return GatewayPrincipal.ofApiKey("key-1");
    }

    private static GatewayPrincipal user(String appUserId) {
        return GatewayPrincipal.ofJwt(
                new CognitoJwtVerifier.VerifiedClaims("sub-1", "app-client", "access", appUserId));
    }

    private static AggregatedHttpRequest get() {
        return AggregatedHttpRequest.of(
                RequestHeaders.of(HttpMethod.GET, "/api/catalog/metrics"), HttpData.empty());
    }

    private static GatewayRequestForwarder forwarder() {
        // Health checking off: this test never intends a network call.
        return new GatewayRequestForwarder(
                List.of(CATALOG, MODEL, LLM), Duration.ofSeconds(1), Map.of(),
                GatewayRateLimiter.disabled(),
                new UpstreamEndpointGroups.HealthCheckConfig(false, 0L), null);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=ProxyRoutePolicyEnforcementTest
```

Expected: compilation failure — no `enforceRoutePolicy`.

- [ ] **Step 3: Add `enforceRoutePolicy` and call it from `forward`**

In `GatewayRequestForwarder`, add above `authorizeUserScope`:

```java
    /**
     * Denies a request the gateway is not willing to proxy.
     *
     * <p>An allow-list: a path with no policy is denied, so a backend route added without a
     * classification is unreachable through the gateway rather than exposed by it. Routes that
     * resolve to no known backend — a genuine LLM upstream — are outside the table's remit and
     * pass through.
     *
     * <p>Both denials return the unrouted-path response verbatim. A path that exists but is
     * withheld must not be distinguishable from one that was never routed.
     *
     * @return the denial to return, or null when the request may proceed
     */
    HttpResponse enforceRoutePolicy(MicroserviceRoute route,
                                    String targetPath,
                                    AggregatedHttpRequest request,
                                    GatewayPrincipal principal) {
        String service = BackendRoutePolicy.effectiveServiceName(route, MicroserviceRoute.defaults());
        if (service == null) {
            return null;
        }
        BackendRoutePolicy.Policy policy = BackendRoutePolicy.lookup(
                service, BackendRoutePolicy.pathWithoutQuery(targetPath));
        if (policy == null || policy.access() == BackendRoutePolicy.Access.NO_PROXY) {
            return GatewayProxyService.gatewayError(HttpStatus.NOT_FOUND, "no route found");
        }
        return authorizeUserScope(route, targetPath, request, principal);
    }
```

In `forward`, change the existing call so the new method is the entry point:

```java
        HttpResponse denied = enforceRoutePolicy(route, targetPath, request, principal);
        if (denied != null) {
            return denied;
        }
```

Leave its position exactly where it is — after the rate-limit gate, before the circuit-breaker
permit. That ordering is load-bearing and `aDenialNeverConsumesTheCircuitBreakerProbeSlot` pins it.

- [ ] **Step 4: Run the tests**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='ProxyRoutePolicyEnforcementTest,UserScopeAuthorizationTest,GatewayServerIntegrationTest,RecommendationGatewayServiceTest'
```

Expected: PASS. The existing suites prove the user-scope path and normal proxying are unchanged.

- [ ] **Step 5: Add to the PR gate and commit**

```xml
                <include>**/gateway/ProxyRoutePolicyEnforcementTest.java</include>
```

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
git add -A
git commit -m "feat: proxy only the backend routes the gateway declares"
```

---

### Task 4: Require the operator token for control-plane writes

**Files:**
- Modify: `src/main/java/com/recsys/application/gateway/GatewayRequestForwarder.java`
- Modify: `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java`
- Test: `src/test/java/com/recsys/application/gateway/ProxyRoutePolicyEnforcementTest.java`
- Modify: `pom.xml` (only if a new test file is added — it is not)

**Interfaces:**
- Consumes: `com.recsys.application.auth.AdminTokenGuard` (Task 1); `enforceRoutePolicy` (Task 3).
- Produces: a `GatewayRequestForwarder` constructor overload taking an `AdminTokenGuard`; existing constructors delegate with `null`, which means "not configured" and therefore denies.

- [ ] **Step 1: Write the failing tests**

Add to `ProxyRoutePolicyEnforcementTest`:

```java
    @Test
    void operatorPathsRequireTheOperatorToken() {
        GatewayRequestForwarder forwarder = forwarder(new AdminTokenGuard("s3cret"));

        assertDenied403(forwarder.enforceRoutePolicy(CATALOG, "/setembedding", get(), apiKey()));
        assertDenied403(forwarder.enforceRoutePolicy(
                MODEL, "/api/v1/model/versions/activate", get(), apiKey()));
        assertDenied403(forwarder.enforceRoutePolicy(
                CATALOG, "/setembedding", withToken("wrong"), apiKey()));
    }

    @Test
    void operatorPathsProxyWithTheCorrectToken() {
        GatewayRequestForwarder forwarder = forwarder(new AdminTokenGuard("s3cret"));
        assertNull(forwarder.enforceRoutePolicy(CATALOG, "/setembedding", withToken("s3cret"), apiKey()));
        assertNull(forwarder.enforceRoutePolicy(
                MODEL, "/api/v1/model/versions/rollback", withToken("s3cret"), apiKey()));
    }

    /**
     * Unlike the user-scope check, this one binds service-tier callers too. An API key is the
     * credential every real caller holds today; if it were sufficient here, the class would mean
     * nothing — swapping the serving model would sit in the same tier as reading a movie.
     */
    @Test
    void theOperatorCheckBindsServiceTierCallersToo() {
        GatewayRequestForwarder forwarder = forwarder(new AdminTokenGuard("s3cret"));
        assertDenied403(forwarder.enforceRoutePolicy(CATALOG, "/setembedding", get(), apiKey()));
        assertDenied403(forwarder.enforceRoutePolicy(CATALOG, "/setembedding", get(),
                GatewayPrincipal.anonymous()));
    }

    /** No token configured authorizes nobody — the rule AdminTokenGuard already applies on 7010. */
    @Test
    void operatorPathsFailClosedWhenNoTokenIsConfigured() {
        assertDenied403(forwarder(new AdminTokenGuard("")).enforceRoutePolicy(
                CATALOG, "/setembedding", withToken("anything"), apiKey()));
        assertDenied403(forwarder(null).enforceRoutePolicy(
                CATALOG, "/setembedding", withToken("anything"), apiKey()));
    }

    private static void assertDenied403(HttpResponse response) {
        assertNotNull(response, "expected a denial");
        AggregatedHttpResponse aggregated = response.aggregate().join();
        assertEquals(HttpStatus.FORBIDDEN, aggregated.status());
        assertEquals("{\"error\":\"operator token required\"}", aggregated.contentUtf8());
    }

    private static AggregatedHttpRequest withToken(String token) {
        return AggregatedHttpRequest.of(
                RequestHeaders.builder(HttpMethod.POST, "/api/catalog/setembedding")
                        .add(AdminTokenGuard.HEADER, token)
                        .build(),
                HttpData.empty());
    }

    private static GatewayRequestForwarder forwarder(AdminTokenGuard guard) {
        return new GatewayRequestForwarder(
                List.of(CATALOG, MODEL, LLM), Duration.ofSeconds(1), Map.of(),
                GatewayRateLimiter.disabled(),
                new UpstreamEndpointGroups.HealthCheckConfig(false, 0L), null, guard);
    }
```

Add `import com.recsys.application.auth.AdminTokenGuard;`.

- [ ] **Step 2: Run to verify failure**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=ProxyRoutePolicyEnforcementTest
```

Expected: compilation failure — no seven-argument constructor.

- [ ] **Step 3: Thread an `AdminTokenGuard` into the forwarder**

Add the field and import:

```java
import com.recsys.application.auth.AdminTokenGuard;
...
    private final AdminTokenGuard operatorGuard;   // null means not configured, so nobody passes
```

Every existing constructor must initialise it. Follow the pattern the `MeterRegistry` parameter
already uses: keep every current signature working by delegating with `null`, and add one overload
that accepts the guard. The full canonical constructor becomes
`(routes, timeout, circuitBreakers, rateLimiter, healthConfig, registry, operatorGuard)`; the
six-argument form delegates with `null`, and so does the registry-backed path. Do not change any
existing call site.

- [ ] **Step 4: Add the OPERATOR branch**

In `enforceRoutePolicy`, between the NO_PROXY check and the `authorizeUserScope` delegation:

```java
        if (policy.access() == BackendRoutePolicy.Access.OPERATOR) {
            // Tier-independent on purpose: an API key is what every real caller holds, so if it
            // were sufficient here the class would mean nothing. Unset token authorizes nobody.
            String presented = request.headers().get(AdminTokenGuard.HEADER);
            if (operatorGuard == null || !operatorGuard.isAuthorized(presented)) {
                return GatewayProxyService.gatewayError(
                        HttpStatus.FORBIDDEN, "operator token required");
            }
            return null;
        }
```

Returning null here rather than falling through is deliberate: `OPERATOR` and `USER_SCOPED` are
distinct classes, so an operator path never also runs the user-scope check.

- [ ] **Step 5: Wire it at the gateway server**

In `MicroserviceGatewayServer`, build the guard beside the meter registry and pass it into both
forwarder construction branches:

```java
        // Same operator credential as 7010's AdminTokenGuard: one operator tier system-wide.
        AdminTokenGuard operatorGuard = new AdminTokenGuard(System.getenv("SHARD_ADMIN_TOKEN"));
```

Log once at startup when it is not configured, so a fail-closed operator tier is visible rather
than mysterious:

```java
        if (!operatorGuard.isConfigured()) {
            log.warn("SHARD_ADMIN_TOKEN is not set: operator-class routes (setembedding, model "
                    + "version activate/rollback/preload, /online/ops) will reject every request "
                    + "with 403. See docs/runbooks/gateway-auth.md.");
        }
```

- [ ] **Step 6: Run the tests**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='ProxyRoutePolicyEnforcementTest,UserScopeAuthorizationTest,MicroserviceGatewayServerTest,GatewayServerIntegrationTest'
```

Expected: PASS.

- [ ] **Step 7: Run the PR gate and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
git add -A
git commit -m "feat: require the operator token for control-plane writes at the gateway"
```

---

### Task 5: Ship the token to the gateway, and document the tier

**Files:**
- Modify: `k8s/base/api-gateway.yaml`
- Modify: `docs/system_design/20_AuthN_AuthZ.md`
- Modify: `docs/runbooks/gateway-auth.md`
- Modify: `.claude/CLAUDE.md`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

**This task must not be skipped or reordered after Task 4.** Without the env var the gateway's
operator tier authorizes nobody, so model activate/rollback through the gateway returns 403.

- [ ] **Step 1: Add `SHARD_ADMIN_TOKEN` to the gateway Deployment**

`k8s/base/api-gateway.yaml` already mentions `recsys-online-admin` in a comment around line 61 but
does not consume the Secret. Add this to its container `env:` block, mirroring
`k8s/base/online-serving.yaml:64-72` exactly:

```yaml
            # Operator token for the control-plane routes the gateway now gates: POST
            # /api/catalog/setembedding and the model version activate/rollback/preload endpoints,
            # plus /api/online/online/ops. When unset these fail closed (403) — see
            # docs/system_design/20_AuthN_AuthZ.md and docs/runbooks/gateway-auth.md.
            - name: SHARD_ADMIN_TOKEN
              valueFrom:
                secretKeyRef:
                  name: recsys-online-admin
                  key: admin-token
                  optional: true
```

`optional: true` is load-bearing: without it a cluster lacking the Secret cannot start the gateway
pod at all, turning a degraded operator tier into a full outage.

- [ ] **Step 2: Verify the manifests still build**

```bash
kubectl kustomize k8s/base > /dev/null && echo "base OK"
kubectl kustomize k8s/eks > /dev/null && echo "us-east-1 OK"
kubectl kustomize k8s/eks-us-west-2 > /dev/null && echo "us-west-2 OK"
```

Expected: all three print OK. If `kubectl` is unavailable, say so in your report rather than
skipping the check silently.

- [ ] **Step 3: Document the tier**

Append a new `##` section to `docs/system_design/20_AuthN_AuthZ.md` — **do not renumber existing
headings**; it follows the last one. Cover:

- The gateway proxies only classified backend routes; unclassified and `NO_PROXY` return the
  unrouted-path 404, deliberately indistinguishable.
- Telemetry (`/metrics`, `/actuator/*`, 8080's diagnostic `/health/*`) is no longer proxied, and
  nothing legitimate depended on it: `ServiceMonitor`s scrape the pods, probes hit the pods, and
  upstream health checking dials `baseUri + healthPath` directly rather than through the proxy.
- `OPERATOR` requires `X-Admin-Token` from `SHARD_ADMIN_TOKEN`, binds service-tier callers too,
  and fails closed when unset.
- `/shards` stays `AUTHENTICATED` because the prefix mixes tiers and `ShardedRecordService` already
  guards its two operator sub-paths.
- `/actuator` is declared, not scanned — Spring exposure is configuration, so no test cross-checks
  that declaration.

Then rewrite sharp edge 1 a second time. It currently says two authorization rules exist and most
paths are under neither. That is now wrong in the caller's favour: control-plane writes require the
operator token **through the gateway**, while remaining reachable on a direct pod connection
because the backends authenticate nobody (sharp edge 6). Say exactly that — the tier is a gateway
property, not a system property.

- [ ] **Step 4: Update the runbook and CLAUDE.md**

In `docs/runbooks/gateway-auth.md`, add how to call an operator path
(`curl -H "X-Admin-Token: $SHARD_ADMIN_TOKEN" ...`), what a 403 there means, and the break-glass:
if the token is unset, operator paths are reachable only on the pod directly.

In `.claude/CLAUDE.md`, note in the gateway env-var paragraph that `SHARD_ADMIN_TOKEN` is now read
by the gateway as well as 7010, and that unset means operator-class routes reject everything.

- [ ] **Step 5: Verify and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='DocumentationIndexTest,DocumentedMechanismTest'
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
git add -A
git commit -m "docs: document the gateway proxy policy and its operator tier"
```

Do not push and do not open a PR; the controller handles that.

---

## Verification

Before opening the PR:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='*Gateway*,BackendRoute*,ProxyRoutePolicy*,UserScope*,AdminTokenGuard*'
```

Both must pass. The two claims worth stating in the PR body from evidence rather than assertion:
telemetry is no longer proxied, and the operator tier binds every caller including API keys.
