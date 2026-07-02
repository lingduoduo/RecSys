# Gateway Credential Stripping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the gateway from forwarding the caller's `x-api-key` / `Authorization` credentials to backend services — the gateway consumes them and forwards only the verified identity (`x-authenticated-*`).

**Architecture:** Extend the upstream-header filter in both proxy services (`GatewayProxyService`, `LlmProxyService`) to drop `authorization` and `x-api-key`, alongside the existing hop-by-hop + `x-authenticated-*` strip. Java-only.

**Tech Stack:** Java 17, Armeria, JUnit 5 + Jupiter Assertions.

## Global Constraints

- Edit only `com.recsys.application.gateway` (`GatewayProxyService`, `LlmProxyService`, and their tests). No k8s or app-wide changes. No new Maven dependencies.
- Strip `authorization` AND `x-api-key` (case-insensitive) from the **upstream request** headers in both proxies' `buildUpstreamHeaders`. Keep them in a dedicated `GATEWAY_CONSUMED_CREDENTIALS` set, separate from the transport `HOP_BY_HOP` set.
- Do NOT change the identity-injection step (`principal.identityHeaders()`) or the response-side header copies.
- JUnit 5 with `org.junit.jupiter.api.Assertions`.
- One commit. Never commit to `main`; work stays on branch `feat/gateway-strip-credentials`.
- Verify with `mvn test -Dtest=GatewayProxyServiceTest,LlmProxyServiceTest` and `mvn test`.

---

### Task 1: Strip gateway-consumed credentials in both proxies

**Files:**
- Modify: `src/main/java/com/recsys/application/gateway/GatewayProxyService.java`
- Modify: `src/main/java/com/recsys/application/gateway/LlmProxyService.java`
- Test: `src/test/java/com/recsys/application/gateway/GatewayProxyServiceTest.java`
- Test: `src/test/java/com/recsys/application/gateway/LlmProxyServiceTest.java`

**Interfaces:**
- Consumes: existing `static RequestHeaders buildUpstreamHeaders(RequestHeaders, String, ServiceRequestContext, GatewayPrincipal)` in both proxies; `GatewayPrincipal.ofApiKey(String)`.
- Produces: the same method, now excluding `authorization`/`x-api-key` from the copied inbound headers.

- [ ] **Step 1: Add the failing test to `GatewayProxyServiceTest`**

Append this method inside `GatewayProxyServiceTest` (after the existing test):

```java
    @Test
    void buildUpstreamHeaders_stripsGatewayConsumedCredentials() {
        RequestHeaders incoming = RequestHeaders.builder(HttpMethod.GET, "/api/model/predict")
                .add("x-api-key", "secret-key")
                .add("authorization", "Bearer secret.jwt.token")
                .add("x-custom", "keep-me")
                .build();
        ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(incoming));
        GatewayPrincipal principal = GatewayPrincipal.ofApiKey("secret-key");

        RequestHeaders upstream = GatewayProxyService.buildUpstreamHeaders(
                incoming, "/model/predict", ctx, principal);

        // The gateway's own credentials are consumed here, not forwarded to the backend.
        assertNull(upstream.get("x-api-key"));
        assertNull(upstream.get("authorization"));
        // Identity + normal headers still pass through.
        assertEquals("service", upstream.get("x-authenticated-client-id"));
        assertEquals("keep-me", upstream.get("x-custom"));
    }
```

- [ ] **Step 2: Add the same failing test to `LlmProxyServiceTest`**

Append this method inside `LlmProxyServiceTest` (after the existing test):

```java
    @Test
    void buildUpstreamHeaders_stripsGatewayConsumedCredentials() {
        RequestHeaders incoming = RequestHeaders.builder(HttpMethod.POST, "/api/llm/explain")
                .add("x-api-key", "secret-key")
                .add("authorization", "Bearer secret.jwt.token")
                .add("x-custom", "keep-me")
                .build();
        ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(incoming));
        GatewayPrincipal principal = GatewayPrincipal.ofApiKey("secret-key");

        RequestHeaders upstream = LlmProxyService.buildUpstreamHeaders(
                incoming, "/llm/explain", ctx, principal);

        // The gateway's own credentials are consumed here, not forwarded to the backend.
        assertNull(upstream.get("x-api-key"));
        assertNull(upstream.get("authorization"));
        // Identity + normal headers still pass through.
        assertEquals("service", upstream.get("x-authenticated-client-id"));
        assertEquals("keep-me", upstream.get("x-custom"));
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `mvn test -Dtest=GatewayProxyServiceTest,LlmProxyServiceTest`
Expected: FAIL — both new tests fail on `assertNull(upstream.get("x-api-key"))` / `assertNull(upstream.get("authorization"))`, because the current filter forwards those headers (their values are still present).

- [ ] **Step 4: Strip the credentials in `GatewayProxyService`**

In `src/main/java/com/recsys/application/gateway/GatewayProxyService.java`:

(a) Add the credential set next to the existing `HOP_BY_HOP` field (which reads `private static final Set<String> HOP_BY_HOP = Set.of(...)`). Immediately after the `HOP_BY_HOP` declaration, add:

```java
    // Credentials the gateway consumes at its auth boundary — never forwarded upstream.
    private static final Set<String> GATEWAY_CONSUMED_CREDENTIALS = Set.of("authorization", "x-api-key");
```

(b) In `buildUpstreamHeaders`, change the inbound-copy filter from:

```java
            if (!isHopByHop(n) && !n.regionMatches(true, 0, "x-authenticated-", 0, 16)) {
                b.add(name, value);
            }
```

to:

```java
            if (!isHopByHop(n) && !isGatewayCredential(n)
                    && !n.regionMatches(true, 0, "x-authenticated-", 0, 16)) {
                b.add(name, value);
            }
```

(c) Add a helper next to the existing `isHopByHop` method:

```java
    private static boolean isGatewayCredential(String name) {
        return name != null && GATEWAY_CONSUMED_CREDENTIALS.contains(name.toLowerCase(Locale.ROOT));
    }
```

(`java.util.Set` and `java.util.Locale` are already imported for `HOP_BY_HOP` / `isHopByHop`.)

- [ ] **Step 5: Strip the credentials in `LlmProxyService`** (identical change)

In `src/main/java/com/recsys/application/gateway/LlmProxyService.java`:

(a) After the existing `HOP_BY_HOP` field declaration, add:

```java
    // Credentials the gateway consumes at its auth boundary — never forwarded upstream.
    private static final Set<String> GATEWAY_CONSUMED_CREDENTIALS = Set.of("authorization", "x-api-key");
```

(b) In `buildUpstreamHeaders`, change the inbound-copy filter from:

```java
            if (!isHopByHop(n) && !n.regionMatches(true, 0, "x-authenticated-", 0, 16)) {
                b.add(name, value);
            }
```

to:

```java
            if (!isHopByHop(n) && !isGatewayCredential(n)
                    && !n.regionMatches(true, 0, "x-authenticated-", 0, 16)) {
                b.add(name, value);
            }
```

(c) Add the helper next to the existing `isHopByHop` method:

```java
    private static boolean isGatewayCredential(String name) {
        return name != null && GATEWAY_CONSUMED_CREDENTIALS.contains(name.toLowerCase(Locale.ROOT));
    }
```

(`java.util.Set` and `java.util.Locale` are already imported.)

- [ ] **Step 6: Run the tests to verify they pass, then the full suite**

Run: `mvn test -Dtest=GatewayProxyServiceTest,LlmProxyServiceTest`
Expected: PASS — each test class now `Tests run: 2, Failures: 0, Errors: 0` (the existing anti-spoof test plus the new credential-strip test).

Run: `mvn test`
Expected: full suite `BUILD SUCCESS` (a known pre-existing `OnlineAdmissionControlTest` timing flake, if it appears, passes in isolation — re-run `mvn test -Dtest=OnlineAdmissionControlTest`; not a regression).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/application/gateway/GatewayProxyService.java \
        src/main/java/com/recsys/application/gateway/LlmProxyService.java \
        src/test/java/com/recsys/application/gateway/GatewayProxyServiceTest.java \
        src/test/java/com/recsys/application/gateway/LlmProxyServiceTest.java
git commit -m "fix(gateway): strip x-api-key and Authorization before proxying upstream

The gateway is the auth boundary and forwards identity via x-authenticated-*;
its own consumed credentials (x-api-key, Authorization) must not leak to backends.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Strip `authorization` + `x-api-key` (case-insensitive) in both proxies' `buildUpstreamHeaders` via a dedicated `GATEWAY_CONSUMED_CREDENTIALS` set separate from `HOP_BY_HOP` → Task 1 Steps 4-5. ✓
- Identity injection unchanged; response-side copies untouched (only the `buildUpstreamHeaders` inbound-copy filter changes) → Steps 4-5 leave `principal.identityHeaders()` and the rest of the method intact. ✓
- Tests assert both credentials absent + identity/normal headers preserved, in both proxies → Steps 1-2. ✓
- Out of scope (no de-dup, no other headers, not configurable) → none added. ✓

**Placeholder scan:** every edit shows the exact before/after; every verify step is a concrete `mvn` command with expected counts. No TBD/TODO. ✓

**Type consistency:** `GATEWAY_CONSUMED_CREDENTIALS` (`Set<String>`), `isGatewayCredential(String)`, and the filter change are identical across both files; the tests call each proxy's `buildUpstreamHeaders(RequestHeaders, String, ServiceRequestContext, GatewayPrincipal)` with `GatewayPrincipal.ofApiKey(...)`, matching the existing signature and test pattern. ✓
