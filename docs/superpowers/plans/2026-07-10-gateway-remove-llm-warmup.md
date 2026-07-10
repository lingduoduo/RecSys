# Remove LLM Startup Warmup From the API Gateway — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete the LLM startup warmup path from the API gateway so it starts leaner and does no upstream I/O at boot, with no change to request-time routing, caching, auth, rate-limit, circuit-breaker, retry, timeout, or health behavior.

**Architecture:** Warmup lives in two places. `LlmProxyService.warmUp()` issues a best-effort GET to the upstream health path; `MicroserviceGatewayServer.registerLlmRoutes(...)` fires it per LLM route when `LLM_WARMUP_ENABLED` (default true). Remove both, drop the returned futures list (`registerLlmRoutes` becomes `void`), retire the `LLM_WARMUP_ENABLED` env var from docs, delete the two warmup test classes, and preserve one unrelated `buildLlmClientFactory` test by relocating it.

**Tech Stack:** Java 17, Armeria (server + `WebClient`/`ClientFactory`), JUnit 5, AssertJ, Maven. Build with `JAVA_HOME=$(/usr/libexec/java_home -v 17)`.

## Global Constraints

- JDK 17 for all Maven commands: prefix with `JAVA_HOME=$(/usr/libexec/java_home -v 17)`.
- No change to any request-time behavior of `LlmProxyService.serve(...)`, `LlmResponseCache`, the tuned LLM `ClientFactory` (`LLM_CONNECT_TIMEOUT_MS`/`LLM_IDLE_TIMEOUT_MS`/`LLM_PING_INTERVAL_MS`), routes, or health.
- Do not modify historical `docs/superpowers/**/*warmup*` design/plan files — they are archival.
- Never merge to main directly; work stays on branch `feat/gateway-remove-llm-warmup` and integrates via PR.
- Commit after each task.

---

### Task 1: Remove `warmUp()` from `LlmProxyService`

**Files:**
- Modify: `src/main/java/com/recsys/application/gateway/LlmProxyService.java` (delete the `warmUp()` method + its javadoc, currently lines 130–151)
- Test: `src/test/java/com/recsys/application/gateway/LlmProxyServiceWarmupTest.java` (delete)

**Interfaces:**
- Consumes: nothing new.
- Produces: `LlmProxyService` no longer exposes a public `warmUp()` method. Its constructor, fields (`route`, `webClient`, `authenticator`, …), and `serve(ServiceRequestContext, HttpRequest)` are unchanged. The `import java.net.URI;` stays (still used by `route.rewrite(...)` in `serve`).

- [ ] **Step 1: Delete the warmup test class**

```bash
git rm src/test/java/com/recsys/application/gateway/LlmProxyServiceWarmupTest.java
```

- [ ] **Step 2: Confirm it now fails to compile the reference**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -o test-compile 2>&1 | tail -20`
Expected: this may still PASS (the method still exists) — this step just confirms the test file is gone. If offline (`-o`) fails to resolve, drop `-o`.

- [ ] **Step 3: Delete the `warmUp()` method + javadoc from `LlmProxyService`**

Remove exactly this block (the javadoc comment immediately above `serve`, lines 130–151):

```java
    /**
     * Best-effort pre-connect: issues a GET to the upstream health path through this service's
     * own {@link WebClient}, seating the pooled connection (DNS + TCP + TLS + HTTP/2 preface)
     * that real requests will reuse. Never blocks startup and never throws — failures are logged.
     */
    public CompletableFuture<Void> warmUp() {
        try {
            URI healthUri = route.healthUri();
            String rawQuery = healthUri.getRawQuery();
            String target = rawQuery != null ? healthUri.getRawPath() + "?" + rawQuery : healthUri.getRawPath();
            return webClient.get(target).aggregate()
                    .thenAccept(agg -> log.info("LLM warmup for {} -> {}", route.name(), agg.status()))
                    .exceptionally(t -> {
                        log.warn("LLM warmup for {} failed (non-fatal): {}", route.name(), t.toString());
                        return null;
                    })
                    .toCompletableFuture();
        } catch (Throwable t) {
            log.warn("LLM warmup for {} failed to start (non-fatal): {}", route.name(), t.toString());
            return CompletableFuture.completedFuture(null);
        }
    }
```

The line before this block is the closing `}` of the constructor; the line after is `@Override` on `serve`. Leave one blank line between them.

- [ ] **Step 4: Remove the now-unused `CompletableFuture` import if unused**

Check whether `java.util.concurrent.CompletableFuture` is still referenced in the file:

Run: `grep -n "CompletableFuture" src/main/java/com/recsys/application/gateway/LlmProxyService.java`
Expected: no matches. If there are none, delete the line `import java.util.concurrent.CompletableFuture;`. If there are still matches, leave the import. (Do NOT remove `import java.net.URI;` — `serve` still uses it.)

- [ ] **Step 5: Compile**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test-compile 2>&1 | tail -20`
Expected: BUILD does not fail on `LlmProxyService` (it may fail on `MicroserviceGatewayServer` / `LlmGatewayWarmupIntegrationTest` which still call `warmUp()`/`registerLlmRoutes(...,warmupEnabled)` — those are fixed in Tasks 2–3). If the only errors reference `MicroserviceGatewayServer.java` line ~185 (`llmProxyService.warmUp()`) and the warmup test, proceed.

- [ ] **Step 6: Commit**

```bash
git add -A src/main/java/com/recsys/application/gateway/LlmProxyService.java src/test/java/com/recsys/application/gateway/LlmProxyServiceWarmupTest.java
git commit -m "refactor(gateway): drop LlmProxyService.warmUp()

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Simplify `registerLlmRoutes` and `main()` in `MicroserviceGatewayServer`

**Files:**
- Modify: `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java`

**Interfaces:**
- Consumes: `LlmProxyService` (constructor unchanged; no `warmUp()`).
- Produces: `static void registerLlmRoutes(ServerBuilder sb, List<MicroserviceRoute> llmRoutes, ClientFactory llmClientFactory, Duration llmTimeout, Map<String, RouteCircuitBreaker> circuitBreakers, LlmTokenRateLimiter tokenRateLimiter, LlmResponseCache responseCache, int defaultTokenEstimate, long maxRetryWaitMs, GatewayAuthenticator authenticator)` — **no `warmupEnabled` parameter, returns `void`**. `buildLlmClientFactory(EnvVars.EnvReader)` is unchanged.

- [ ] **Step 1: Update the LLM-route registration block in `main()`**

Replace the current block (lines 89–100):

```java
        // LLM path: build a tuned, shared ClientFactory (only when LLM routes exist) and register
        // each LLM route from it, then best-effort pre-connect so the first request is warm.
        // Register LLM routes before the catch-all so Armeria's more-specific prefix wins.
        ClientFactory llmClientFactory = null;
        if (!llmRoutes.isEmpty()) {
            llmClientFactory = buildLlmClientFactory(System::getenv);
            boolean warmupEnabled = EnvVars.readBool("LLM_WARMUP_ENABLED", true);
            registerLlmRoutes(sb, llmRoutes, llmClientFactory, llmTimeout, circuitBreakers,
                    llmTokenRateLimiter, llmResponseCache, llmDefaultTokenEstimate, llmMaxRetryWaitMs,
                    authenticator, warmupEnabled);
            // Warmup futures are intentionally not joined — startup must not block on the upstream.
        }
```

with:

```java
        // LLM path: build a tuned, shared ClientFactory (only when LLM routes exist) and register
        // each LLM route from it. Register LLM routes before the catch-all so Armeria's
        // more-specific prefix wins. Connections are established lazily on the first request.
        ClientFactory llmClientFactory = null;
        if (!llmRoutes.isEmpty()) {
            llmClientFactory = buildLlmClientFactory(System::getenv);
            registerLlmRoutes(sb, llmRoutes, llmClientFactory, llmTimeout, circuitBreakers,
                    llmTokenRateLimiter, llmResponseCache, llmDefaultTokenEstimate, llmMaxRetryWaitMs,
                    authenticator);
        }
```

- [ ] **Step 2: Rewrite `registerLlmRoutes` to return `void` without warmup**

Replace the current method (lines 155–189):

```java
    static List<CompletableFuture<Void>> registerLlmRoutes(
            ServerBuilder sb,
            List<MicroserviceRoute> llmRoutes,
            ClientFactory llmClientFactory,
            Duration llmTimeout,
            Map<String, RouteCircuitBreaker> circuitBreakers,
            LlmTokenRateLimiter tokenRateLimiter,
            LlmResponseCache responseCache,
            int defaultTokenEstimate,
            long maxRetryWaitMs,
            GatewayAuthenticator authenticator,
            boolean warmupEnabled) {
        List<CompletableFuture<Void>> warmups = new ArrayList<>();
        for (MicroserviceRoute llmRoute : llmRoutes) {
            LlmProxyService llmProxyService = new LlmProxyService(
                    llmRoute,
                    llmTimeout,
                    circuitBreakers.get(llmRoute.name()),
                    tokenRateLimiter,
                    responseCache,
                    defaultTokenEstimate,
                    maxRetryWaitMs,
                    authenticator,
                    llmClientFactory);
            sb.service(
                    com.linecorp.armeria.server.Route.builder()
                            .pathPrefix(llmRoute.prefix() + "/")
                            .build(),
                    llmProxyService);
            if (warmupEnabled) {
                warmups.add(llmProxyService.warmUp());
            }
        }
        return warmups;
    }
```

with:

```java
    static void registerLlmRoutes(
            ServerBuilder sb,
            List<MicroserviceRoute> llmRoutes,
            ClientFactory llmClientFactory,
            Duration llmTimeout,
            Map<String, RouteCircuitBreaker> circuitBreakers,
            LlmTokenRateLimiter tokenRateLimiter,
            LlmResponseCache responseCache,
            int defaultTokenEstimate,
            long maxRetryWaitMs,
            GatewayAuthenticator authenticator) {
        for (MicroserviceRoute llmRoute : llmRoutes) {
            LlmProxyService llmProxyService = new LlmProxyService(
                    llmRoute,
                    llmTimeout,
                    circuitBreakers.get(llmRoute.name()),
                    tokenRateLimiter,
                    responseCache,
                    defaultTokenEstimate,
                    maxRetryWaitMs,
                    authenticator,
                    llmClientFactory);
            sb.service(
                    com.linecorp.armeria.server.Route.builder()
                            .pathPrefix(llmRoute.prefix() + "/")
                            .build(),
                    llmProxyService);
        }
    }
```

- [ ] **Step 3: Remove now-unused imports**

Delete these two import lines (no longer referenced anywhere in the file after Steps 1–2):

```java
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
```

Verify first:

Run: `grep -n "ArrayList\|CompletableFuture" src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java`
Expected: no matches after the edits above — then remove the import lines. (`List` and `Map` imports stay; still used.)

- [ ] **Step 4: Compile main sources**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q compile 2>&1 | tail -20`
Expected: BUILD SUCCESS (main sources compile cleanly; the `LlmGatewayWarmupIntegrationTest` is test scope and is handled in Task 3).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java
git commit -m "refactor(gateway): remove LLM startup warmup wiring

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Replace the warmup integration test, preserving factory coverage

**Files:**
- Delete: `src/test/java/com/recsys/api/gateway/LlmGatewayWarmupIntegrationTest.java`
- Create: `src/test/java/com/recsys/api/gateway/LlmClientFactoryTest.java`

**Interfaces:**
- Consumes: `MicroserviceGatewayServer.buildLlmClientFactory(EnvVars.EnvReader)` (static, unchanged) and the new `registerLlmRoutes(...)` signature from Task 2 (no `warmupEnabled`).
- Produces: nothing consumed downstream.

- [ ] **Step 1: Delete the warmup integration test**

```bash
git rm src/test/java/com/recsys/api/gateway/LlmGatewayWarmupIntegrationTest.java
```

- [ ] **Step 2: Create `LlmClientFactoryTest` preserving the factory test**

Create `src/test/java/com/recsys/api/gateway/LlmClientFactoryTest.java`:

```java
package com.recsys.api.gateway;

import com.linecorp.armeria.client.ClientFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmClientFactoryTest {

    @Test
    void buildLlmClientFactory_returnsUsableFactory() {
        ClientFactory factory = MicroserviceGatewayServer.buildLlmClientFactory(k -> null);
        try {
            assertThat(factory).isNotNull();
        } finally {
            factory.close();
        }
    }
}
```

- [ ] **Step 3: Compile tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test-compile 2>&1 | tail -20`
Expected: BUILD SUCCESS — no remaining references to `warmUp()`, `warmupEnabled`, or the old `registerLlmRoutes` arity.

- [ ] **Step 4: Run the focused gateway test suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest='LlmClientFactoryTest,GatewayServerIntegrationTest,MicroserviceRouteTest,RecommendationGatewayServiceTest,LlmProxyServiceTest' 2>&1 | tail -30`
Expected: PASS. (Omit any listed class that does not exist in the repo — the goal is the gateway/LLM proxy tests minus the two deleted warmup classes. If a name is unknown, run `ls src/test/java/com/recsys/api/gateway src/test/java/com/recsys/application/gateway` and select the present gateway/LLM tests.)

- [ ] **Step 5: Commit**

```bash
git add -A src/test/java/com/recsys/api/gateway/
git commit -m "test(gateway): drop warmup tests, keep LLM client-factory coverage

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Retire `LLM_WARMUP_ENABLED` from operator docs

**Files:**
- Modify: `.claude/CLAUDE.md` (the "Key env vars" line, currently line 55)

**Interfaces:** none.

- [ ] **Step 1: Remove the `LLM_WARMUP_ENABLED` clause from the env-var list**

In `.claude/CLAUDE.md`, find the substring:

```
`LLM_WARMUP_ENABLED` (default true; gateway pre-connects to each LLM upstream's health path at startup), `LLM_CONNECT_TIMEOUT_MS`
```

Replace it with:

```
`LLM_CONNECT_TIMEOUT_MS`
```

Then update the trailing clause in the same sentence that reads `the last three tune the gateway's dedicated LLM ClientFactory` — it now describes all three remaining LLM vars, so change `the last three` to `these three`:

Find: `the last three tune the gateway's dedicated LLM \`ClientFactory\``
Replace: `these three tune the gateway's dedicated LLM \`ClientFactory\``

- [ ] **Step 2: Verify no stray `LLM_WARMUP_ENABLED` references remain in live docs**

Run: `grep -rn "LLM_WARMUP_ENABLED" .claude/ README* docs/runbooks 2>/dev/null`
Expected: no matches. (Historical `docs/superpowers/plans|specs/*warmup*` files may still mention it — those are archival and left untouched.)

- [ ] **Step 3: Commit**

```bash
git add .claude/CLAUDE.md
git commit -m "docs(gateway): retire LLM_WARMUP_ENABLED env var

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Full verification and PR

**Files:** none (verification + integration).

- [ ] **Step 1: Full build + test**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test 2>&1 | tail -30`
Expected: BUILD SUCCESS, no failures. (Load tests remain excluded by default.)

- [ ] **Step 2: Sanity-check the gateway still boots and serves**

Run the gateway briefly and confirm it starts without warmup log lines:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) timeout 25 mvn -q exec:java -Dexec.mainClass=com.recsys.api.gateway.MicroserviceGatewayServer 2>&1 | tee /tmp/gw.log | grep -iE "Starting RecSys API gateway|LLM warmup" || true
```

Expected: a `Starting RecSys API gateway on port 8010` line and **no** `LLM warmup` lines. (`timeout` stops the server; a non-zero exit from the timeout is fine.)

- [ ] **Step 3: Push the branch and open the PR**

```bash
git push -u origin feat/gateway-remove-llm-warmup
gh pr create --title "refactor(gateway): remove LLM startup warmup" --body "$(cat <<'EOF'
## Summary
- Removes the LLM startup warmup path from the API gateway. The gateway is a pure routing proxy; warmup only pre-seated the first LLM connection at boot. Connections are now established lazily on the first request.
- `LlmProxyService.warmUp()` deleted; `registerLlmRoutes(...)` drops the `warmupEnabled` param and returns `void`.
- Retires the now-inert `LLM_WARMUP_ENABLED` env var from operator docs.
- Cache and routing behavior unchanged — `LlmResponseCache` hits still serve immediately.

## Testing
- `mvn test` — full suite green.
- Deleted `LlmProxyServiceWarmupTest` and the two warmup wiring tests; preserved `buildLlmClientFactory` coverage in new `LlmClientFactoryTest`.
- Verified the gateway boots and logs no `LLM warmup` lines.

Spec: `docs/superpowers/specs/2026-07-10-gateway-remove-llm-warmup-design.md`
Plan: `docs/superpowers/plans/2026-07-10-gateway-remove-llm-warmup.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: PR created against `main`.

---

## Self-Review

**Spec coverage:**
- Remove `warmUp()` → Task 1. ✓
- Simplify `registerLlmRoutes`/`main()`, drop imports → Task 2. ✓
- Delete warmup tests, preserve factory test → Task 3. ✓
- Retire `LLM_WARMUP_ENABLED` from docs → Task 4. ✓
- Full suite + gateway-boot verification + PR → Task 5. ✓
- Leave historical warmup docs untouched → Global Constraints + Task 4 Step 2. ✓
- No request-time / cache / ClientFactory changes → Global Constraints. ✓

**Placeholder scan:** No TBD/TODO; every code step shows the exact before/after block or full new file. ✓

**Type consistency:** `registerLlmRoutes` new signature (10 params, `void`) is defined in Task 2 Interfaces and consumed identically in Task 3 (the deleted test used the old 11-param signature; the new `LlmClientFactoryTest` does not call it). `buildLlmClientFactory(EnvVars.EnvReader)` unchanged and called with `k -> null` in Task 3, matching the original. ✓
