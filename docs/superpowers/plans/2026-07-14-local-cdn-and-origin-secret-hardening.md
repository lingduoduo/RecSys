# Local CDN and Origin-Secret Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the three origin-secret follow-ups from the CloudFront design, and make the CDN's caching semantics runnable and testable locally with no AWS account.

**Architecture:** `GatewayOriginSecret` accepts a set of secrets (zero-downtime rotation) and its decorator gains a Prometheus counter plus a first-occurrence log. The distribution script defaults to `https-only`. An nginx container mirrors each CloudFront behavior one-for-one in front of the local gateway, proven by a `@Tag("docker")` testcontainers test.

**Tech Stack:** Java 17, Armeria, Micrometer/Prometheus, JUnit 5 + AssertJ, Testcontainers 1.20.4, nginx 1.27-alpine, Docker Compose, AWS CLI v2.

Spec: `docs/superpowers/specs/2026-07-14-local-cdn-and-origin-secret-hardening-design.md`

## Global Constraints

- **JDK 17 required.** Every Maven command must be prefixed exactly: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`. On the default JDK the build fails on unrelated pre-existing files (`LlmResponseCache.java`, `RecommendationCache.java`).
- **Run multiple test classes with a comma:** `-Dtest=A,B`. The `+` form does not work in this setup.
- **Branch `feat/cdn-edge-acceleration`** (PR #187 is open against it). Never commit to `main`.
- **The existing suite is 954 tests and must stay green.** `@Tag("docker")` and `@Tag("load")` are excluded by default (`pom.xml:22`); docker tests run with `-DexcludedGroups=load -Dgroups=docker`.
- **Constant-time comparison must not short-circuit.** Accumulate with `matched |= ...`; never `break` on first match. An early exit leaks how many secrets are configured and which one matched. Mirror `GatewayAuthenticator.check`.
- **`GATEWAY_ORIGIN_SECRET` unset or blank still means disabled.** Local dev and the whole existing suite run without it.
- **`/health` and `/metrics` stay exempt.** The ALB health check, all three k8s probes, and the Prometheus scrape reach the pod directly with no secret.
- Counter name is exactly `gateway_origin_secret_rejected_total`, matching the underscore convention already used in `GatewayRegistryMetrics`.
- Origin Cache-Control values are unchanged: `/item` → `public, s-maxage=3600, stale-while-revalidate=86400, stale-if-error=86400`; `/similar` → `public, s-maxage=300, stale-while-revalidate=3600, stale-if-error=3600`.

---

### Task 1: Accept a set of origin secrets

Turns rotation from a guaranteed 403 window into a zero-downtime sequence. Also hoists the header name off the hot path (a Minor flagged in an earlier review).

**Files:**
- Modify: `src/main/java/com/recsys/application/gateway/GatewayOriginSecret.java`
- Test: `src/test/java/com/recsys/application/gateway/GatewayOriginSecretTest.java` (extend)

**Interfaces:**
- Consumes: `EnvVars.EnvReader` (functional, `String get(String)`).
- Produces (signatures unchanged except internals):
  - `public static final String GatewayOriginSecret.HEADER` = `"x-origin-secret"`
  - `public static GatewayOriginSecret fromEnvironment(EnvVars.EnvReader env)` — now CSV-aware
  - `public static GatewayOriginSecret disabled()`
  - `public boolean isEnabled()`
  - `public boolean isAllowed(RequestHeaders headers, String path)`
  - `public static Function<? super HttpService, ? extends HttpService> newDecorator(GatewayOriginSecret secret)` — **unchanged in this task; Task 2 replaces it**

- [ ] **Step 1: Write the failing tests**

Append these to `src/test/java/com/recsys/application/gateway/GatewayOriginSecretTest.java` (the file already has `withSecret`/`headers` helpers — reuse them):

```java
    @Test
    void acceptsEitherSecretDuringRotation() {
        GatewayOriginSecret secret = withSecret("old-secret,new-secret");
        assertThat(secret.isEnabled()).isTrue();
        assertThat(secret.isAllowed(headers("/api/recommend", "old-secret"), "/api/recommend")).isTrue();
        assertThat(secret.isAllowed(headers("/api/recommend", "new-secret"), "/api/recommend")).isTrue();
    }

    @Test
    void rejectsASecretNotInTheSet() {
        GatewayOriginSecret secret = withSecret("old-secret,new-secret");
        assertThat(secret.isAllowed(headers("/api/recommend", "other"), "/api/recommend")).isFalse();
    }

    @Test
    void trimsWhitespaceAroundCsvEntries() {
        GatewayOriginSecret secret = withSecret("  old-secret , new-secret  ");
        assertThat(secret.isAllowed(headers("/api/recommend", "old-secret"), "/api/recommend")).isTrue();
        assertThat(secret.isAllowed(headers("/api/recommend", "new-secret"), "/api/recommend")).isTrue();
    }

    @Test
    void ignoresEmptyCsvEntries() {
        GatewayOriginSecret secret = withSecret("old-secret,,new-secret,");
        assertThat(secret.isEnabled()).isTrue();
        assertThat(secret.isAllowed(headers("/api/recommend", "old-secret"), "/api/recommend")).isTrue();
        // An empty entry must never become a match-anything secret.
        assertThat(secret.isAllowed(headers("/api/recommend", ""), "/api/recommend")).isFalse();
    }

    @Test
    void commaOnlyValueIsTreatedAsDisabled() {
        GatewayOriginSecret secret = withSecret(",,,");
        assertThat(secret.isEnabled()).isFalse();
    }
```

Note the existing test file uses `assertThat` from AssertJ or JUnit assertions — check the imports at the top and match whichever is already there. If the file uses `assertTrue`/`assertFalse` (JUnit), write these assertions in that style instead; do not add a second assertion library to the file.

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=GatewayOriginSecretTest`
Expected: FAIL — `acceptsEitherSecretDuringRotation` fails, because `"old-secret,new-secret"` is currently treated as one literal secret so neither individual value matches.

- [ ] **Step 3: Write the implementation**

Replace the field, constructor, factories, and `isAllowed` in `src/main/java/com/recsys/application/gateway/GatewayOriginSecret.java`. Keep `HEADER`, `EXEMPT_PATHS`, `isExempt`, and `newDecorator` as they are.

Add these imports:

```java
import com.linecorp.armeria.common.HttpHeaderNames;
import io.netty.util.AsciiString;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
```

Replace lines 34-71 (from `EXEMPT_PATHS` through the end of `isAllowed`) with:

```java
    private static final Set<String> EXEMPT_PATHS = Set.of("/health", "/metrics");

    /** Hoisted off the hot path — every gateway request would otherwise re-resolve it. */
    private static final AsciiString HEADER_NAME = HttpHeaderNames.of(HEADER);

    private static final GatewayOriginSecret DISABLED = new GatewayOriginSecret(Set.of());

    /**
     * All currently-accepted secrets. More than one is the rotation window: add the new secret
     * alongside the old, roll the pods, flip the distribution, then drop the old one — see
     * docs/runbooks/cdn-operations.md.
     */
    private final Set<String> secrets;

    private GatewayOriginSecret(Set<String> secrets) {
        this.secrets = Set.copyOf(secrets);
    }

    public static GatewayOriginSecret disabled() {
        return DISABLED;
    }

    public static GatewayOriginSecret fromEnvironment(EnvVars.EnvReader env) {
        Set<String> parsed = parseCsv(env.get("GATEWAY_ORIGIN_SECRET"));
        return parsed.isEmpty() ? DISABLED : new GatewayOriginSecret(parsed);
    }

    public boolean isEnabled() {
        return !secrets.isEmpty();
    }

    public boolean isAllowed(RequestHeaders headers, String path) {
        if (!isEnabled() || isExempt(path)) {
            return true;
        }
        String provided = headers.get(HEADER_NAME);
        if (provided == null || provided.isBlank()) {
            return false;
        }
        String trimmed = provided.trim();

        // Deliberately does NOT break on the first match: an early exit would make the loop
        // count depend on which secret matched, leaking set size and match position through
        // timing and undoing the point of the constant-time compare. Mirrors
        // GatewayAuthenticator.check.
        boolean matched = false;
        for (String secret : secrets) {
            matched |= constantTimeEquals(secret, trimmed);
        }
        return matched;
    }

    private static boolean constantTimeEquals(String expected, String provided) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static Set<String> parseCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=GatewayOriginSecretTest`
Expected: PASS — 13 tests (8 pre-existing + 5 new), 0 failures. All pre-existing single-value tests must still pass; they are the backward-compatibility proof.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/application/gateway/GatewayOriginSecret.java \
        src/test/java/com/recsys/application/gateway/GatewayOriginSecretTest.java
git commit -m "feat(cdn): accept a set of origin secrets for zero-downtime rotation"
```

---

### Task 2: Observable reject path

A counter and one log line, so a botched rotation has an origin-side signal instead of only a CloudFront-side 4xx graph.

The side effects live in the decorator, not in `isAllowed` — `isAllowed` stays a pure predicate. This also keeps the counter out of the `DISABLED` singleton.

**Files:**
- Modify: `src/main/java/com/recsys/application/gateway/GatewayOriginSecret.java` (`newDecorator` only)
- Modify: `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java` (the decorator registration added by the prior branch, just after `ServerBuilder sb = Server.builder().http(port);`)
- Test: `src/test/java/com/recsys/application/gateway/GatewayOriginSecretMetricsTest.java` (create)

**Interfaces:**
- Consumes: `GatewayOriginSecret.isAllowed` (Task 1); `GatewayProxyService.gatewayError(HttpStatus, String)` (existing, public static).
- Produces:
  - `public static Function<? super HttpService, ? extends HttpService> newDecorator(GatewayOriginSecret secret, MeterRegistry registry)` — **replaces the 1-arg version**. `registry` may be null (no metrics).
  - Meter: `gateway_origin_secret_rejected_total` (Counter).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/application/gateway/GatewayOriginSecretMetricsTest.java`:

```java
package com.recsys.application.gateway;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayOriginSecretMetricsTest {

    static final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            GatewayOriginSecret secret = GatewayOriginSecret.fromEnvironment(
                    Map.of("GATEWAY_ORIGIN_SECRET", "s3cret")::get);
            sb.decorator(GatewayOriginSecret.newDecorator(secret, registry));
            sb.service("/api/recommend",
                    (ctx, req) -> HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, "{}"));
            sb.service("/health",
                    (ctx, req) -> HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, "{}"));
        }
    };

    private double rejectCount() {
        return registry.get("gateway_origin_secret_rejected_total").counter().count();
    }

    @Test
    void countsRejectionsButNotAllowedOrExemptRequests() {
        WebClient client = WebClient.of(server.httpUri());
        double before = rejectCount();

        // Rejected: no secret.
        AggregatedHttpResponse rejected = client.get("/api/recommend").aggregate().join();
        assertThat(rejected.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rejectCount()).isEqualTo(before + 1);

        // Allowed: correct secret. Must not increment.
        AggregatedHttpResponse allowed = client.execute(RequestHeaders.of(
                HttpMethod.GET, "/api/recommend",
                HttpHeaderNames.of(GatewayOriginSecret.HEADER), "s3cret")).aggregate().join();
        assertThat(allowed.status()).isEqualTo(HttpStatus.OK);
        assertThat(rejectCount()).isEqualTo(before + 1);

        // Exempt: /health with no secret. Must not increment.
        AggregatedHttpResponse exempt = client.get("/health").aggregate().join();
        assertThat(exempt.status()).isEqualTo(HttpStatus.OK);
        assertThat(rejectCount()).isEqualTo(before + 1);
    }

    @Test
    void nullRegistryIsSupported() {
        GatewayOriginSecret secret = GatewayOriginSecret.fromEnvironment(
                Map.of("GATEWAY_ORIGIN_SECRET", "s3cret")::get);
        // Must not throw — the counter is optional.
        assertThat(GatewayOriginSecret.newDecorator(secret, null)).isNotNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=GatewayOriginSecretMetricsTest`
Expected: FAIL — compilation error, `newDecorator` does not accept two arguments.

- [ ] **Step 3: Write the implementation**

In `src/main/java/com/recsys/application/gateway/GatewayOriginSecret.java`, add imports:

```java
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
```

Add a logger field next to `HEADER_NAME`:

```java
    private static final Logger LOG = LoggerFactory.getLogger(GatewayOriginSecret.class);
```

Replace `newDecorator` entirely:

```java
    /**
     * Server-wide decorator: rejects any non-exempt request lacking a valid secret with 403.
     *
     * <p>Side effects live here rather than in {@link #isAllowed}, which stays a pure predicate.
     *
     * @param registry may be null, in which case no counter is registered.
     */
    public static Function<? super HttpService, ? extends HttpService> newDecorator(
            GatewayOriginSecret secret, MeterRegistry registry) {

        Counter rejected = registry == null ? null
                : Counter.builder("gateway_origin_secret_rejected_total")
                        .description("Requests rejected for a missing or invalid CloudFront origin secret")
                        .register(registry);

        // Logged once, not per request: under a scan or a botched rotation this fires on every
        // request, and a per-request log would flood. The counter is the real signal; this is
        // the breadcrumb that explains it.
        AtomicBoolean warned = new AtomicBoolean();

        return delegate -> (ctx, req) -> {
            if (!secret.isAllowed(req.headers(), ctx.path())) {
                if (rejected != null) {
                    rejected.increment();
                }
                if (warned.compareAndSet(false, true)) {
                    LOG.warn("Rejected a request with a missing or invalid {} header (first "
                                    + "occurrence, path={}). If this coincides with a secret "
                                    + "rotation, the distribution and GATEWAY_ORIGIN_SECRET "
                                    + "disagree — see docs/runbooks/cdn-operations.md. Further "
                                    + "rejections are counted in gateway_origin_secret_rejected_total "
                                    + "and not logged.",
                            HEADER, ctx.path());
                }
                return GatewayProxyService.gatewayError(
                        HttpStatus.FORBIDDEN, "direct origin access is not permitted");
            }
            return delegate.serve(ctx, req);
        };
    }
```

Then update the single caller in `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java`. The prior branch added a block just after `ServerBuilder sb = Server.builder().http(port);` that reads:

```java
        GatewayOriginSecret originSecret = GatewayOriginSecret.fromEnvironment(System::getenv);
        if (originSecret.isEnabled()) {
            sb.decorator(GatewayOriginSecret.newDecorator(originSecret));
        }
```

The `PrometheusMeterRegistry meterRegistry` is created a few lines below it. **Move the origin-secret block to just after `meterRegistry` is created** (after the `sb.service("/metrics", ...)` line) so the registry exists, and change the call to pass it:

```java
        // Origin lockdown: when CloudFront fronts this gateway, reject anything that did not come
        // through our distribution. No-op when GATEWAY_ORIGIN_SECRET is unset (local dev).
        // Registered after meterRegistry so rejections are counted and scrapeable at /metrics.
        GatewayOriginSecret originSecret = GatewayOriginSecret.fromEnvironment(System::getenv);
        if (originSecret.isEnabled()) {
            sb.decorator(GatewayOriginSecret.newDecorator(originSecret, meterRegistry));
        }
```

A server-level `sb.decorator(...)` applies to every service regardless of registration order, so moving the block below `sb.service("/metrics", ...)` does not change which routes are covered.

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=GatewayOriginSecretMetricsTest,GatewayOriginSecretTest`
Expected: PASS — 15 tests, 0 failures.

Then the gateway regression package:

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='com.recsys.application.gateway.*Test'`
Expected: PASS, no regressions.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/application/gateway/GatewayOriginSecret.java \
        src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java \
        src/test/java/com/recsys/application/gateway/GatewayOriginSecretMetricsTest.java
git commit -m "feat(cdn): count and log origin-secret rejections"
```

---

### Task 3: Default the origin protocol to https-only

Makes the `HTTPSPort: 443` / `OriginSslProtocols: TLSv1.2` fields already in the payload live instead of inert.

**Files:**
- Modify: `scripts/create-cdn-distribution.sh`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: env var `ORIGIN_PROTOCOL_POLICY`, default `https-only`, accepted values `https-only` | `http-only`.

- [ ] **Step 1: Add the variable and its guard**

In `scripts/create-cdn-distribution.sh`, after the existing `ACM_CERT_ARN` region guard (the block that exits if the cert is not in us-east-1), add:

```bash
# Origin protocol. https-only is the default because http-only sends x-origin-secret across
# the public CloudFront->ALB hop in cleartext, where it is observable and replayable — by
# exactly the attacker the header exists to stop.
ORIGIN_PROTOCOL_POLICY="${ORIGIN_PROTOCOL_POLICY:-https-only}"

case "$ORIGIN_PROTOCOL_POLICY" in
  https-only)
    ;;
  http-only)
    echo "WARNING: ORIGIN_PROTOCOL_POLICY=http-only." >&2
    echo "         x-origin-secret will cross the CloudFront->ALB hop in CLEARTEXT and is" >&2
    echo "         replayable by anyone who observes it. This weakens the origin lockdown." >&2
    echo "         Prefer https-only (requires an ALB :443 listener + a REGIONAL ACM cert)." >&2
    ;;
  *)
    echo "ERROR: ORIGIN_PROTOCOL_POLICY must be 'https-only' or 'http-only'." >&2
    echo "       Got: ${ORIGIN_PROTOCOL_POLICY}" >&2
    exit 1
    ;;
esac
```

- [ ] **Step 2: Wire it into the jq payload**

In the same script, the `jq -n` invocation that builds the distribution config currently hardcodes `OriginProtocolPolicy: "http-only"`. Add a `--arg` to that `jq` call, alongside the existing `--arg` flags:

```bash
  --arg origin_protocol "$ORIGIN_PROTOCOL_POLICY" \
```

and change the `CustomOriginConfig` line from the hardcoded value to the variable:

```
      HTTPPort: 80, HTTPSPort: 443, OriginProtocolPolicy: $origin_protocol,
```

Leave `HTTPSPort: 443` and `OriginSslProtocols` exactly as they are — they stop being dead config under the new default.

- [ ] **Step 3: Verify the syntax and both guards**

Run:
```bash
bash -n scripts/create-cdn-distribution.sh && echo "syntax ok"
```
Expected: prints `syntax ok`.

Run:
```bash
( export ORIGIN_DOMAIN=o ALIAS_DOMAIN=a WEB_ACL_ARN=w ORIGIN_SECRET=s \
         ACM_CERT_ARN=arn:aws:acm:us-east-1:1:certificate/x \
         ORIGIN_PROTOCOL_POLICY=bogus; \
  ./scripts/create-cdn-distribution.sh 2>&1 || true ) | head -2
```
Expected: `ERROR: ORIGIN_PROTOCOL_POLICY must be 'https-only' or 'http-only'.` then `       Got: bogus`. No AWS calls are made — the guard fires first.

Run:
```bash
( export ORIGIN_DOMAIN=o ALIAS_DOMAIN=a WEB_ACL_ARN=w ORIGIN_SECRET=s \
         ACM_CERT_ARN=arn:aws:acm:us-east-1:1:certificate/x \
         ORIGIN_PROTOCOL_POLICY=http-only; \
  ./scripts/create-cdn-distribution.sh 2>&1 || true ) | head -1
```
Expected: `WARNING: ORIGIN_PROTOCOL_POLICY=http-only.` — it warns but does not exit. (The script will then fail later on a real AWS call; that is expected and fine, we only care that the warning fired.)

- [ ] **Step 4: Commit**

```bash
git add scripts/create-cdn-distribution.sh
git commit -m "feat(cdn): default origin protocol to https-only"
```

---

### Task 4: Local CDN environment

An nginx stand-in that mirrors each CloudFront behavior. Uses the official image's envsubst templating so the **same template** serves both docker-compose and the test in Task 5 — no drift between what you run and what is tested.

**Files:**
- Create: `docker/cdn/default.conf.template`
- Create: `docker-compose.cdn.yml`
- Create: `scripts/invalidate-local-cdn.sh`

**Interfaces:**
- Consumes: nothing from earlier tasks (the gateway is reached over HTTP).
- Produces: template env vars `CDN_ORIGIN_HOST`, `CDN_ORIGIN_PORT`, `CDN_ORIGIN_SECRET`; nginx listening on `8090`; cache zone named `cdn`; container name `recsys-cdn`.

- [ ] **Step 1: Write the nginx template**

Create `docker/cdn/default.conf.template`:

```nginx
# Local stand-in for the CloudFront distribution.
# Each block mirrors a behavior from
# docs/superpowers/specs/2026-07-14-cdn-edge-acceleration-design.md.
#
# Rendered by the nginx image's envsubst at container start. Only ${CDN_*} variables are
# substituted (NGINX_ENVSUBST_FILTER=CDN_) — without that filter envsubst would also eat
# nginx's own $uri, $arg_id and $upstream_cache_status.
#
# This file lands in /etc/nginx/conf.d/, which the stock nginx.conf includes inside http{},
# so proxy_cache_path is in a legal context here.

proxy_cache_path /var/cache/nginx levels=1:2 keys_zone=cdn:10m max_size=100m inactive=24h use_temp_path=off;

upstream origin_upstream {
    server ${CDN_ORIGIN_HOST}:${CDN_ORIGIN_PORT};
    keepalive 16;
}

server {
    listen 8090;

    # --- Mirrors: DefaultCacheBehavior = CachingDisabled -------------------------------
    # Default-deny. A cache is attached but every request bypasses it, so $upstream_cache_status
    # reports BYPASS rather than being empty. POST /api/recommend and /api/catalog/user land here.
    location / {
        proxy_pass http://origin_upstream;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header x-origin-secret ${CDN_ORIGIN_SECRET};
        proxy_cache cdn;
        proxy_cache_bypass 1;
        proxy_no_cache 1;
        add_header X-Cache $upstream_cache_status always;
    }

    # --- Mirrors: CacheBehavior /api/catalog/item*, cache key whitelists `id` ----------
    location = /api/catalog/item {
        proxy_pass http://origin_upstream;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header x-origin-secret ${CDN_ORIGIN_SECRET};

        proxy_cache cdn;
        # ONLY `id` is in the key. nginx's default key is the whole $request_uri, which would
        # let ?id=1&cachebuster=N fragment the cache arbitrarily and act as an origin-DoS
        # amplifier — the exact thing CloudFront's query-string whitelist prevents.
        proxy_cache_key "$uri|$arg_id";

        # NOTE: there is deliberately NO proxy_cache_valid here. Cache lifetime comes only from
        # the origin's Cache-Control: s-maxage. If a proxy_cache_valid were added, objects would
        # be cached even if nginx ignored s-maxage, and LocalCdnCacheTest would silently stop
        # proving anything.
        proxy_cache_background_update on;
        proxy_cache_use_stale updating error timeout http_500 http_502 http_503 http_504;
        proxy_cache_revalidate on;
        add_header X-Cache $upstream_cache_status always;
    }

    # --- Mirrors: CacheBehavior /api/catalog/similar*, key whitelists `movieId` + `k` --
    location = /api/catalog/similar {
        proxy_pass http://origin_upstream;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header x-origin-secret ${CDN_ORIGIN_SECRET};

        proxy_cache cdn;
        proxy_cache_key "$uri|$arg_movieId|$arg_k";
        # No proxy_cache_valid — see the note above.
        proxy_cache_background_update on;
        proxy_cache_use_stale updating error timeout http_500 http_502 http_503 http_504;
        proxy_cache_revalidate on;
        add_header X-Cache $upstream_cache_status always;
    }
}
```

- [ ] **Step 2: Write the compose file**

Create `docker-compose.cdn.yml`:

```yaml
# Local CloudFront stand-in in front of the gateway.
#
#   sh scripts/run-microservices-local.sh          # gateway on :8010
#   docker compose -f docker-compose.cdn.yml up    # nginx on :8090
#   curl -i localhost:8090/api/catalog/item?id=1   # X-Cache: MISS, then HIT
#
# Demonstrates CACHING SEMANTICS ONLY. No WAF, no Shield, no edge TLS, no geographic
# distribution — see docs/runbooks/cdn-local.md for the full list of divergences.
services:
  cdn:
    image: nginx:1.27-alpine
    container_name: recsys-cdn
    ports:
      - "8090:8090"
    environment:
      # Only CDN_* are substituted; nginx's own $uri/$arg_id/$upstream_cache_status survive.
      NGINX_ENVSUBST_FILTER: "CDN_"
      CDN_ORIGIN_HOST: "${CDN_ORIGIN_HOST:-host.docker.internal}"
      CDN_ORIGIN_PORT: "${CDN_ORIGIN_PORT:-8010}"
      # Must match GATEWAY_ORIGIN_SECRET on the gateway, or every request 403s.
      CDN_ORIGIN_SECRET: "${CDN_ORIGIN_SECRET:-local-dev-secret}"
    volumes:
      - ./docker/cdn/default.conf.template:/etc/nginx/templates/default.conf.template:ro
    extra_hosts:
      # Makes host.docker.internal resolve on Linux too, not just Docker Desktop.
      - "host.docker.internal:host-gateway"
```

- [ ] **Step 3: Write the local invalidation script**

Create `scripts/invalidate-local-cdn.sh`:

```bash
#!/usr/bin/env bash
# Local counterpart to scripts/invalidate-cdn.sh.
#
# IMPORTANT DIVERGENCE: this purges the ENTIRE cache. nginx OSS has no path-scoped purge
# (proxy_cache_purge is nginx Plus or a third-party module), whereas CloudFront invalidates
# by path pattern. Coarser than the real thing — see docs/runbooks/cdn-local.md.
set -euo pipefail

CONTAINER="${CDN_CONTAINER:-recsys-cdn}"

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "ERROR: container '${CONTAINER}' is not running." >&2
  echo "Start it with: docker compose -f docker-compose.cdn.yml up -d" >&2
  exit 1
fi

echo "Purging the entire local CDN cache in ${CONTAINER}"
docker exec "$CONTAINER" find /var/cache/nginx -type f -delete
echo "Done. The next request for any path will be a MISS."
```

- [ ] **Step 4: Verify syntax and that nginx accepts the rendered config**

Run:
```bash
chmod +x scripts/invalidate-local-cdn.sh
bash -n scripts/invalidate-local-cdn.sh && echo "syntax ok"
```
Expected: prints `syntax ok`.

Now prove nginx parses the template once rendered — this catches a typo before Task 5 spends a container on it:
```bash
docker run --rm \
  -e NGINX_ENVSUBST_FILTER=CDN_ \
  -e CDN_ORIGIN_HOST=example.com -e CDN_ORIGIN_PORT=8010 -e CDN_ORIGIN_SECRET=x \
  -v "$PWD/docker/cdn/default.conf.template:/etc/nginx/templates/default.conf.template:ro" \
  nginx:1.27-alpine \
  sh -c '/docker-entrypoint.sh nginx -t 2>&1'
```
Expected: output contains `syntax is ok` and `test is successful`. If it instead complains about an unknown directive or an empty upstream host, envsubst did not run or the filter is wrong.

Also confirm the filter protected nginx's own variables:
```bash
docker run --rm \
  -e NGINX_ENVSUBST_FILTER=CDN_ \
  -e CDN_ORIGIN_HOST=example.com -e CDN_ORIGIN_PORT=8010 -e CDN_ORIGIN_SECRET=x \
  -v "$PWD/docker/cdn/default.conf.template:/etc/nginx/templates/default.conf.template:ro" \
  nginx:1.27-alpine \
  sh -c '/docker-entrypoint.sh true >/dev/null 2>&1; grep -c "upstream_cache_status" /etc/nginx/conf.d/default.conf'
```
Expected: prints `3` (one `add_header X-Cache` per location). If it prints `0`, envsubst ate nginx's variables and the filter is not applied.

- [ ] **Step 5: Commit**

```bash
git add docker/cdn/default.conf.template docker-compose.cdn.yml scripts/invalidate-local-cdn.sh
git commit -m "feat(cdn): local nginx CDN stand-in mirroring the CloudFront behaviors"
```

---

### Task 5: Prove the local CDN's semantics

The test that makes the local environment worth having. It settles, with evidence, a fact nginx's documentation does not state: that `s-maxage` drives cache lifetime.

**Files:**
- Create: `src/test/java/com/recsys/api/serving/LocalCdnCacheTest.java`

**Interfaces:**
- Consumes: `docker/cdn/default.conf.template` (Task 4); `com.recsys.api.serving.HttpCaching.publicCache(long, long)` (existing).
- Produces: nothing.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/api/serving/LocalCdnCacheTest.java`:

```java
package com.recsys.api.serving;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real docker/cdn/default.conf.template against a stub origin, proving the local CDN
 * actually mirrors the CloudFront behaviors rather than merely looking like it does.
 *
 * <p>Tagged docker: excluded from `mvn test` by default (pom.xml). Run with
 * `mvn test -DexcludedGroups=load -Dgroups=docker -Dtest=LocalCdnCacheTest`.
 */
@Tag("docker")
class LocalCdnCacheTest {

    private static final String SECRET = "local-test-secret";

    /** Origin hits that actually reached the stub — a cache HIT must not increment this. */
    static final AtomicInteger originHits = new AtomicInteger();
    /** Every x-origin-secret value the stub received. */
    static final List<String> receivedSecrets = new CopyOnWriteArrayList<>();

    static Server origin;
    static GenericContainer<?> nginx;
    static String etag;

    @BeforeAll
    static void startAll() {
        origin = Server.builder()
                .http(0)
                // Emits ONLY s-maxage — no max-age, no Expires. Combined with the template's
                // deliberate lack of proxy_cache_valid, a HIT is only possible if nginx honours
                // s-maxage. That is the whole point of this fixture.
                .service("/api/catalog/item", (ctx, req) -> {
                    originHits.incrementAndGet();
                    String secret = req.headers().get(HttpHeaderNames.of("x-origin-secret"));
                    receivedSecrets.add(secret == null ? "<absent>" : secret);
                    byte[] body = "{\"id\":1,\"title\":\"Test Movie\"}".getBytes();
                    String tag = HttpCaching.etagFor(body);
                    if (HttpCaching.matches(req.headers().get(HttpHeaderNames.IF_NONE_MATCH), tag)) {
                        return HttpResponse.of(ResponseHeaders.builder(HttpStatus.NOT_MODIFIED)
                                .set(HttpHeaderNames.CACHE_CONTROL, HttpCaching.publicCache(3600, 86400))
                                .set(HttpHeaderNames.ETAG, tag)
                                .build());
                    }
                    return HttpResponse.of(ResponseHeaders.builder(HttpStatus.OK)
                            .contentType(MediaType.JSON_UTF_8)
                            .set(HttpHeaderNames.CACHE_CONTROL, HttpCaching.publicCache(3600, 86400))
                            .set(HttpHeaderNames.ETAG, tag)
                            .build(), HttpData.wrap(body));
                })
                .service("/api/recommend", (ctx, req) -> {
                    originHits.incrementAndGet();
                    return HttpResponse.of(ResponseHeaders.builder(HttpStatus.OK)
                            .contentType(MediaType.JSON_UTF_8)
                            .set(HttpHeaderNames.CACHE_CONTROL, "no-store")
                            .build(), HttpData.ofUtf8("{\"personalized\":true}"));
                })
                .build();
        origin.start().join();
        int originPort = origin.activeLocalPort();
        etag = HttpCaching.etagFor("{\"id\":1,\"title\":\"Test Movie\"}".getBytes());

        Testcontainers.exposeHostPorts(originPort);
        nginx = new GenericContainer<>("nginx:1.27-alpine")
                .withCopyFileToContainer(
                        MountableFile.forHostPath("docker/cdn/default.conf.template"),
                        "/etc/nginx/templates/default.conf.template")
                .withEnv("NGINX_ENVSUBST_FILTER", "CDN_")
                .withEnv("CDN_ORIGIN_HOST", "host.testcontainers.internal")
                .withEnv("CDN_ORIGIN_PORT", String.valueOf(originPort))
                .withEnv("CDN_ORIGIN_SECRET", SECRET)
                .withExposedPorts(8090)
                .waitingFor(Wait.forListeningPort());
        nginx.start();
    }

    @AfterAll
    static void stopAll() {
        if (nginx != null) nginx.stop();
        if (origin != null) origin.stop().join();
    }

    private WebClient cdn() {
        return WebClient.of("http://" + nginx.getHost() + ":" + nginx.getMappedPort(8090));
    }

    private static String cacheStatus(AggregatedHttpResponse res) {
        return res.headers().get(HttpHeaderNames.of("x-cache"));
    }

    @Test
    void sMaxAgeAloneIsEnoughToCache_missThenHit() {
        int before = originHits.get();

        AggregatedHttpResponse first = cdn().get("/api/catalog/item?id=1").aggregate().join();
        assertThat(first.status()).isEqualTo(HttpStatus.OK);
        assertThat(cacheStatus(first)).isEqualTo("MISS");

        AggregatedHttpResponse second = cdn().get("/api/catalog/item?id=1").aggregate().join();
        assertThat(second.status()).isEqualTo(HttpStatus.OK);
        // The load-bearing assertion. The origin sends only `s-maxage` and the nginx config has
        // no proxy_cache_valid, so a HIT is possible ONLY if nginx honours s-maxage.
        assertThat(cacheStatus(second)).isEqualTo("HIT");
        // And the origin was hit exactly once, proving the HIT was served from cache.
        assertThat(originHits.get()).isEqualTo(before + 1);
    }

    @Test
    void cacheKeyWhitelistMeansCachebusterCannotFragmentTheCache() {
        cdn().get("/api/catalog/item?id=7").aggregate().join();   // prime
        int before = originHits.get();

        AggregatedHttpResponse busted =
                cdn().get("/api/catalog/item?id=7&cachebuster=99").aggregate().join();

        // The key is "$uri|$arg_id", so cachebuster is not part of it.
        assertThat(cacheStatus(busted)).isEqualTo("HIT");
        assertThat(originHits.get()).isEqualTo(before);
    }

    @Test
    void defaultBehaviorNeverCaches() {
        int before = originHits.get();

        AggregatedHttpResponse first = cdn().post("/api/recommend", "{}").aggregate().join();
        AggregatedHttpResponse second = cdn().post("/api/recommend", "{}").aggregate().join();

        assertThat(cacheStatus(first)).isEqualTo("BYPASS");
        assertThat(cacheStatus(second)).isEqualTo("BYPASS");
        // Every request reached the origin.
        assertThat(originHits.get()).isEqualTo(before + 2);
    }

    @Test
    void ifNoneMatchReturns304ThroughTheCdn() {
        cdn().get("/api/catalog/item?id=3").aggregate().join();   // prime

        AggregatedHttpResponse res = cdn().execute(RequestHeaders.of(
                HttpMethod.GET, "/api/catalog/item?id=3",
                HttpHeaderNames.IF_NONE_MATCH, etag)).aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    @Test
    void originSecretIsInjectedOnEveryForwardedRequest() {
        receivedSecrets.clear();
        cdn().get("/api/catalog/item?id=42").aggregate().join();

        assertThat(receivedSecrets).isNotEmpty();
        assertThat(receivedSecrets).allMatch(SECRET::equals);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Docker must be running. Run:
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -DexcludedGroups=load -Dgroups=docker -Dtest=LocalCdnCacheTest
```
Expected before Task 4's template exists: FAIL at startup with a file-not-found for `docker/cdn/default.conf.template`. Since Task 4 is already committed, expect instead: PASS. If it fails, the template is wrong — fix the template, not the test.

If Docker is unavailable, report that rather than skipping silently.

- [ ] **Step 3: No implementation step**

This task adds only a test; the implementation is Task 4's template. If any assertion fails, the defect is in `docker/cdn/default.conf.template` and must be fixed there.

Specifically, if `sMaxAgeAloneIsEnoughToCache_missThenHit` reports `MISS` on the second call, nginx did not honour `s-maxage`. Do **not** paper over that by adding `proxy_cache_valid` to the template — that would make the test pass while proving the opposite of what it claims. Report it instead: it would mean the local environment cannot faithfully mirror CloudFront's TTL handling, which is a design-level finding.

- [ ] **Step 4: Run the full default suite to confirm nothing regressed**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test`
Expected: BUILD SUCCESS. The docker-tagged test is excluded by default, so the count stays at the Task 2 total.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/recsys/api/serving/LocalCdnCacheTest.java
git commit -m "test(cdn): prove the local CDN mirrors CloudFront caching semantics"
```

---

### Task 6: Documentation

**Files:**
- Create: `docs/runbooks/cdn-local.md`
- Modify: `docs/runbooks/cdn-operations.md` (rotation section + the https-only prerequisite)
- Modify: `.claude/CLAUDE.md`

**Interfaces:**
- Consumes: everything from Tasks 1-5.
- Produces: nothing.

- [ ] **Step 1: Write the local runbook**

Create `docs/runbooks/cdn-local.md`:

````markdown
# Local CDN

An nginx stand-in for the CloudFront distribution, so the caching semantics can be run and
observed with no AWS account.

Design: `docs/superpowers/specs/2026-07-14-local-cdn-and-origin-secret-hardening-design.md`

## Run it

```bash
# 1. Gateway + backends on the host (gateway listens on :8010)
sh scripts/run-microservices-local.sh

# 2. The CDN in front of it, on :8090
docker compose -f docker-compose.cdn.yml up
```

If the gateway has `GATEWAY_ORIGIN_SECRET` set, `CDN_ORIGIN_SECRET` must match it or every
request 403s:

```bash
CDN_ORIGIN_SECRET=my-secret docker compose -f docker-compose.cdn.yml up
```

## See it work

```bash
curl -sI 'localhost:8090/api/catalog/item?id=1' | grep -i x-cache   # X-Cache: MISS
curl -sI 'localhost:8090/api/catalog/item?id=1' | grep -i x-cache   # X-Cache: HIT

# The cache key whitelists `id`, so a cachebuster cannot fragment the cache
curl -sI 'localhost:8090/api/catalog/item?id=1&cachebuster=99' | grep -i x-cache   # HIT

# Default-deny: the personalized route is never cached
curl -sI -X POST localhost:8090/api/recommend | grep -i x-cache   # X-Cache: BYPASS
```

## Invalidate

```bash
./scripts/invalidate-local-cdn.sh
```

## What this DOES mirror

Each nginx block is a deliberate mirror of a CloudFront decision:

| CloudFront | nginx |
|---|---|
| `DefaultCacheBehavior` = CachingDisabled | default `location /` with bypass → `X-Cache: BYPASS` |
| `/api/catalog/item*` key whitelists `id` | `proxy_cache_key "$uri\|$arg_id"` |
| `/api/catalog/similar*` whitelists `movieId`,`k` | `proxy_cache_key "$uri\|$arg_movieId\|$arg_k"` |
| `CustomHeaders` inject the origin secret | `proxy_set_header x-origin-secret` |
| `X-Cache: Hit from cloudfront` | `add_header X-Cache $upstream_cache_status` |
| Honours `s-maxage` / SWR / `stale-if-error` | same directives, natively |

`LocalCdnCacheTest` (`@Tag("docker")`) proves these:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  mvn test -DexcludedGroups=load -Dgroups=docker -Dtest=LocalCdnCacheTest
```

## What this does NOT mirror

Read this before drawing any conclusion from the local environment.

- **Invalidation is coarser.** `invalidate-local-cdn.sh` purges the WHOLE cache. nginx OSS has
  no path-scoped purge (`proxy_cache_purge` is nginx Plus or a third-party module), whereas
  CloudFront invalidates by path pattern.
- **No WAF, no Shield, no edge TLS, no geographic distribution.** These are most of the CDN's
  actual value in the CloudFront design. None are exercised here.
- **One nginx, not 400+ POPs.** No POP-to-POP behaviour, no Origin Shield tiering.
- **nginx and CloudFront are not bit-identical.** The claim is that the three `Cache-Control`
  directives we emit behave the same. Nothing broader.

This is a semantics harness, not a CloudFront emulator.

## Config note

`docker/cdn/default.conf.template` is rendered by the nginx image's envsubst.
`NGINX_ENVSUBST_FILTER=CDN_` is **required**: without it envsubst also substitutes nginx's own
`$uri`, `$arg_id`, and `$upstream_cache_status`, silently producing a config that caches
everything under one key.

The template deliberately has **no `proxy_cache_valid`**. Cache lifetime comes only from the
origin's `s-maxage`. Adding one would make `LocalCdnCacheTest` pass even if nginx ignored
`s-maxage` — i.e. it would prove nothing.
````

- [ ] **Step 2: Update the rotation section**

In `docs/runbooks/cdn-operations.md`, the "Rotating the origin secret" section currently says a
403 window is unavoidable because the gateway accepts exactly one secret. That is no longer
true. Replace that section's body with:

````markdown
`GATEWAY_ORIGIN_SECRET` accepts a comma-separated **set** of secrets, so rotation has no 403
window. Both the old and the new secret are accepted while the distribution catches up.

```bash
# 1. Accept both. Pods now take either value.
kubectl -n recsys create secret generic recsys-gateway-origin-secret \
  --from-literal=secret='old-secret,new-secret' --dry-run=client -o yaml | kubectl apply -f -
kubectl -n recsys rollout status deployment/recsys-api-gateway

# 2. Flip the distribution to the new secret (CloudFront propagation takes minutes).
ORIGIN_SECRET='new-secret' ... ./scripts/create-cdn-distribution.sh

# 3. Once propagated, retire the old one.
kubectl -n recsys create secret generic recsys-gateway-origin-secret \
  --from-literal=secret='new-secret' --dry-run=client -o yaml | kubectl apply -f -
kubectl -n recsys rollout status deployment/recsys-api-gateway
```

Do not skip step 1. Going straight to step 2 reintroduces the window this ordering exists to
avoid: the distribution sends a secret no pod accepts, and 100% of non-exempt traffic 403s
until step 3 completes.

**Watch the rejections.** `gateway_origin_secret_rejected_total` is exposed on the gateway's
`/metrics`. It should stay flat throughout a correct rotation. A rise means the distribution and
the pods disagree — the most likely cause is step 1 being skipped or not yet rolled out. The
first rejection also emits one WARN log (only the first, to avoid flooding).
````

- [ ] **Step 3: Document the https-only prerequisite**

In `docs/runbooks/cdn-operations.md`, in the rollout section where `create-cdn-distribution.sh`
is invoked, add after that command block:

````markdown
**Origin protocol.** `ORIGIN_PROTOCOL_POLICY` defaults to `https-only`, which requires the ALB
to have a `:443` listener and a **regional** ACM certificate (separate from the us-east-1 cert
CloudFront uses for viewers). The ALB today listens on `:80` only
(`k8s/eks/waf-api-gateway-ingress.yaml`), so that listener must exist before this default works.

`ORIGIN_PROTOCOL_POLICY=http-only` still works and warns. It is a real weakening: the origin
secret then crosses the public CloudFront→ALB hop in cleartext, where it is observable and
replayable by exactly the attacker the header exists to stop. Prefer fixing the listener.
````

- [ ] **Step 4: Update CLAUDE.md**

In `.claude/CLAUDE.md`, find the sentence describing `GATEWAY_ORIGIN_SECRET` and replace it with:

```markdown
`GATEWAY_ORIGIN_SECRET` (default unset = disabled; accepts a comma-separated SET of secrets so
rotation has no 403 window — the gateway rejects any request without a matching
`x-origin-secret` header with 403 and counts it in `gateway_origin_secret_rejected_total`.
`/health` and `/metrics` are exempt so ALB/kubelet probes and Prometheus scrapes still work).
```

And append to the CDN paragraph in the Architecture section:

```markdown
A local nginx stand-in (`docker-compose.cdn.yml`, port 8090) mirrors the distribution's cache
behaviors for development — see `docs/runbooks/cdn-local.md`. It demonstrates caching semantics
only: no WAF, Shield, edge TLS, or geographic distribution.
```

- [ ] **Step 5: Verify the docs match the code**

Run:
```bash
grep -n "unavoidable\|exactly one value\|single value" docs/runbooks/cdn-operations.md || echo "no stale single-secret claims"
grep -rn "gateway_origin_secret_rejected_total" docs/ .claude/CLAUDE.md src/main | head
```
Expected: the first prints `no stale single-secret claims` — any surviving claim that rotation
has an unavoidable window now contradicts Task 1 and must be removed. The second shows the
counter named consistently in the runbook, CLAUDE.md, and `GatewayOriginSecret.java`.

- [ ] **Step 6: Commit**

```bash
git add docs/runbooks/cdn-local.md docs/runbooks/cdn-operations.md .claude/CLAUDE.md
git commit -m "docs(cdn): local CDN runbook, zero-downtime rotation, https-only prerequisite"
```

---

### Task 7: Full verification and push to PR #187

**Files:**
- No changes; validates the branch.

**Interfaces:**
- Consumes: Tasks 1-6.
- Produces: updated PR #187.

- [ ] **Step 1: Run the default suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test`
Expected: BUILD SUCCESS, 0 failures. Baseline before this plan was **954 tests**; expect ~961
(+5 rotation, +2 metrics). Report the real number. `LocalCdnCacheTest` is excluded by default.

- [ ] **Step 2: Run the docker suite**

Docker must be running.

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -DexcludedGroups=load -Dgroups=docker`
Expected: BUILD SUCCESS. This runs `LocalCdnCacheTest` alongside the pre-existing Redis sharding
docker tests. If the Redis ones fail, check whether they failed before this branch — do not
attribute a pre-existing flake to this work.

- [ ] **Step 3: Confirm the working tree is clean**

Run: `git status --porcelain`
Expected: empty. `.superpowers/sdd/` is gitignored, but `task-*-report.md` files are tracked
from an earlier project — if any show as modified, restore them with
`git checkout -- .superpowers/sdd/` so scratch does not leak into the PR.

- [ ] **Step 4: Push**

```bash
git push origin feat/cdn-edge-acceleration
```
This updates the existing PR #187 — do not open a new one.

- [ ] **Step 5: Update the PR description**

The PR body currently lists the three items this plan closes under "Known follow-ups". Remove
them from that list (they are now fixed) and add to the Changes section:

```bash
gh pr edit 187 --body "$(gh pr view 187 --json body -q .body)"
```
Do not run that command literally — it is a no-op. Instead, read the current body with
`gh pr view 187 --json body -q .body`, edit the text so that:
- "Known follow-ups" no longer lists the rotation window, the silent reject path, or the
  cleartext origin hop (all three are now addressed).
- The Changes section mentions: multi-secret rotation, the
  `gateway_origin_secret_rejected_total` counter, the `https-only` origin default, and the local
  nginx CDN with its docker test.
- The "Accepted tradeoffs" section no longer claims http-only is the default. It now warns.
- The test plan cites the real new totals from Steps 1 and 2.

Then apply it with `gh pr edit 187 --body-file <file>`.

- [ ] **Step 6: Confirm**

Run: `gh pr view 187 --json state,title -q '.state + " " + .title'`
Expected: `OPEN feat(cdn): CloudFront edge acceleration`.
