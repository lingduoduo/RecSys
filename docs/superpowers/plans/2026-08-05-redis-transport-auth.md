# Redis Transport Authentication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the Redis client TLS and ACL-username support, then actually authenticate the
in-cluster Redis, so no service connects as the unauthenticated `default` user over a plaintext
socket.

**Architecture:** `LettuceClientFactory` has exactly two URI construction points plus two
replica call sites; all four gain username, password, and TLS. A fail-closed guard on the public
entry points refuses to open a connection without a credential unless `REDIS_ALLOW_NO_AUTH=true`
says so. The in-cluster Redis gets `requirepass`/`masterauth`/`sentinel auth-pass` from a Secret,
and a manifest conformance test keeps all of it from drifting back.

**Tech Stack:** Lettuce 6.3.2, JUnit 5 + AssertJ + SnakeYAML, Kubernetes StatefulSets, Maven.

**Spec:** [docs/superpowers/specs/2026-08-05-redis-transport-auth-design.md](../specs/2026-08-05-redis-transport-auth-design.md)

## Global Constraints

- **JDK 17.** Every Maven command runs as `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`.
- **Run Maven from the repo root.** Manifest tests resolve `Path.of("k8s", "base")` relative to the
  working directory.
- **Branch:** `feat/redis-transport-auth`, created off `main` (`cf661ae`) with the spec committed at
  `de59680`. Ships as a PR; never merge to `main` directly.
- **Lettuce 6.3.2.RELEASE.** `RedisURI` exposes `getUsername()`, `getPassword()`, `isSsl()`, and
  `RedisURI.Builder.withAuthentication(String, CharSequence)`. `getUsername()`/`getPassword()` are
  deprecated in favour of the credentials provider but still functional — a deprecation warning in
  test code is expected and acceptable.
- **No insecure-TLS escape hatch.** Peer verification stays at Lettuce's default. Do not add a
  `REDIS_TLS_INSECURE` knob, even if it would make a local experiment easier.
- **Five workloads dial Redis**: `recsys-api-gateway`, `recsys-catalog-serving`,
  `recsys-model-serving`, `recsys-online-serving`, and the `recsys-outbox-reconciliation` CronJob.
  `recsys-outbox-relay` does **not** — it touches MySQL and Kafka only. Never add `REDIS_PASSWORD`
  to the relay.

---

### Task 1: TLS and username support in the client

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/redis/LettuceClientFactory.java`
- Modify: `src/main/java/com/recsys/config/RedisProperties.java`
- Modify: `src/main/resources/application.yml` (the `redis:` block, around line 95)
- Test: `src/test/java/com/recsys/infrastructure/redis/LettuceClientFactoryTest.java`

**Interfaces:**
- Produces, for Tasks 2 and 4:
  - `static RedisURI standaloneUri(String host, int port, String username, String password, boolean tls, int timeoutMs)`
  - `static RedisURI sentinelUri(String master, String nodes, String username, String password, boolean tls, int timeoutMs)`
  - `static RedisURI replicaUri(ReplicaConfig cfg, String username, String password, boolean tls, int timeoutMs)`
  - `RedisProperties.getUsername()` → `String`, `RedisProperties.isTls()` → `boolean`

- [ ] **Step 1: Write the failing tests**

Add to `LettuceClientFactoryTest`:

```java
    @Test
    void tlsIsOffByDefault() {
        RedisURI uri = LettuceClientFactory.uriFromEnv(
                Map.of("REDIS_HOST", "cache"), Integer.MAX_VALUE);
        assertFalse(uri.isSsl(), "REDIS_TLS must default to false");
    }

    @Test
    void tlsFlagEnablesSslOnTheUri() {
        RedisURI uri = LettuceClientFactory.uriFromEnv(
                Map.of("REDIS_HOST", "cache", "REDIS_TLS", "true"), Integer.MAX_VALUE);
        assertTrue(uri.isSsl(), "REDIS_TLS=true must produce an SSL RedisURI");
    }

    @Test
    void usernameProducesAnAclLogin() {
        RedisURI uri = LettuceClientFactory.uriFromEnv(
                Map.of("REDIS_HOST", "cache", "REDIS_USERNAME", "catalog",
                        "REDIS_PASSWORD", "s3cret"),
                Integer.MAX_VALUE);
        assertEquals("catalog", uri.getUsername());
    }

    @Test
    void passwordWithoutUsernameStaysOnTheDefaultUser() {
        RedisURI uri = LettuceClientFactory.uriFromEnv(
                Map.of("REDIS_HOST", "cache", "REDIS_PASSWORD", "s3cret"), Integer.MAX_VALUE);
        assertNull(uri.getUsername(), "no REDIS_USERNAME means legacy default-user AUTH");
    }

    @Test
    void sentinelUriCarriesAuthAndTls() {
        RedisURI uri = LettuceClientFactory.uriFromEnv(
                Map.of("REDIS_MODE", "sentinel", "REDIS_SENTINEL_NODES", "s1:26379",
                        "REDIS_USERNAME", "catalog", "REDIS_PASSWORD", "s3cret",
                        "REDIS_TLS", "true"),
                Integer.MAX_VALUE);
        assertTrue(uri.isSsl());
        assertEquals("catalog", uri.getUsername());
    }

    /**
     * The replica URIs are built outside uriFromEnv, so a change that updates only the primary
     * path leaves every replica connection unauthenticated and in the clear while the primary
     * looks correct — and reads route to replicas, so that is most of the traffic. Nothing in the
     * diff makes the omission visible, which is why this assertion exists.
     */
    @Test
    void replicaUriInheritsAuthAndTls() {
        RedisURI uri = LettuceClientFactory.replicaUri(
                ReplicaConfig.parse("replica-a:6379@us-east-1b"),
                "catalog", "s3cret", true, 2000);
        assertEquals("replica-a", uri.getHost());
        assertEquals("catalog", uri.getUsername());
        assertTrue(uri.isSsl());
    }
```

- [ ] **Step 2: Run them to verify they fail**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=LettuceClientFactoryTest
```

Expected: compilation failure — `replicaUri` does not exist, and `uriFromEnv` ignores the new keys.

- [ ] **Step 3: Add the shared auth helper and update both URI builders**

In `LettuceClientFactory`, replace `standaloneUri` and `sentinelUri` with:

```java
    /**
     * AUTH with a username is a Redis 6 ACL login; without one it is the legacy default-user
     * AUTH. Kept in one place so the standalone, sentinel and replica paths cannot drift apart —
     * the replica URIs are built separately and are the easiest of the three to forget.
     */
    private static RedisURI.Builder withAuth(RedisURI.Builder b, String username, String password) {
        boolean hasPassword = password != null && !password.isBlank();
        if (username != null && !username.isBlank()) {
            return b.withAuthentication(username, hasPassword ? password : "");
        }
        return hasPassword ? b.withPassword((CharSequence) password) : b;
    }

    static RedisURI standaloneUri(String host, int port, String username, String password,
                                  boolean tls, int timeoutMs) {
        RedisURI.Builder b = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withTimeout(Duration.ofMillis(Math.max(1, timeoutMs)));
        b = withAuth(b, username, password);
        if (tls) b = b.withSsl(true);
        return b.build();
    }

    static RedisURI sentinelUri(String master, String nodes, String username, String password,
                                boolean tls, int timeoutMs) {
        RedisURI.Builder b = RedisURI.builder().withSentinelMasterId(master);
        for (String node : nodes.split(",")) {
            node = node.strip();
            if (node.isEmpty()) continue;
            int c = node.lastIndexOf(':');
            if (c > 0) {
                b = b.withSentinel(node.substring(0, c), parsePort(node.substring(c + 1)));
            } else {
                b = b.withSentinel(node, 26379);
            }
        }
        b = withAuth(b, username, password);
        if (tls) b = b.withSsl(true);
        return b.withTimeout(Duration.ofMillis(Math.max(1, timeoutMs))).build();
    }

    /**
     * A read-replica URI. Extracted from the router loops so the auth and TLS wiring is directly
     * assertable: these URIs are built outside uriFromEnv and are where credentials go missing.
     */
    static RedisURI replicaUri(ReplicaConfig cfg, String username, String password,
                               boolean tls, int timeoutMs) {
        return standaloneUri(cfg.host(), cfg.port(), username, password, tls, timeoutMs);
    }
```

- [ ] **Step 4: Read the new keys in both env paths**

Replace `uriFromEnv` with:

```java
    static RedisURI uriFromEnv(Map<String, String> env, int maxTimeoutMs) {
        String mode = env.getOrDefault("REDIS_MODE", "standalone");
        String username = env.getOrDefault("REDIS_USERNAME", "");
        String password = env.getOrDefault("REDIS_PASSWORD", "");
        boolean tls = Boolean.parseBoolean(env.getOrDefault("REDIS_TLS", "false"));
        int timeoutMs = Math.min(readPositiveInt(env, "REDIS_TIMEOUT_MS", DEFAULT_TIMEOUT_MS),
                Math.max(1, maxTimeoutMs));
        if ("sentinel".equalsIgnoreCase(mode)) {
            String master = env.getOrDefault("REDIS_SENTINEL_MASTER", "mymaster");
            String nodes = env.getOrDefault("REDIS_SENTINEL_NODES", "localhost:26379");
            return sentinelUri(master, nodes, username, password, tls, timeoutMs);
        }
        return standaloneUri(env.getOrDefault("REDIS_HOST", "localhost"),
                parsePort(env.getOrDefault("REDIS_PORT", "6379")), username, password, tls, timeoutMs);
    }

    static RedisURI uriFrom(RedisProperties props) {
        if (props.isSentinelMode()) {
            String nodes = props.getSentinelNodes() == null || props.getSentinelNodes().isBlank()
                    ? "localhost:26379" : props.getSentinelNodes();
            return sentinelUri(props.getSentinelMaster(), nodes, props.getUsername(),
                    props.getPassword(), props.isTls(), props.getTimeoutMs());
        }
        return standaloneUri(props.getHost(), props.getPort(), props.getUsername(),
                props.getPassword(), props.isTls(), props.getTimeoutMs());
    }
```

- [ ] **Step 5: Update both replica loops**

In `routerFromEnv(Map<String, String> env, int maxTimeoutMs)`, replace the existing
`String password = env.getOrDefault("REDIS_PASSWORD", "");` line and the loop body's URI
construction:

```java
        String username = env.getOrDefault("REDIS_USERNAME", "");
        String password = env.getOrDefault("REDIS_PASSWORD", "");
        boolean tls = Boolean.parseBoolean(env.getOrDefault("REDIS_TLS", "false"));
```

and inside the loop:

```java
                RedisURI uri = replicaUri(cfg, username, password, tls, timeoutMs);
```

In `routerFrom(RedisProperties props)`, inside its loop:

```java
                RedisURI uri = replicaUri(cfg, props.getUsername(), props.getPassword(),
                        props.isTls(), props.getTimeoutMs());
```

- [ ] **Step 6: Add the RedisProperties fields**

In `RedisProperties`, beside the existing `password` field:

```java
    /** ACL username. Blank means legacy default-user AUTH. */
    private String username = "";

    /** Connect over TLS. Required by ElastiCache with encryption-in-transit enabled. */
    private boolean tls = false;
```

and the accessors, beside `getPassword`/`setPassword`:

```java
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username == null ? "" : username; }

    public boolean isTls() { return tls; }
    public void setTls(boolean tls) { this.tls = tls; }
```

- [ ] **Step 7: Wire the Spring properties**

In `src/main/resources/application.yml`, in the `redis:` block immediately after the `password:`
line:

```yaml
    username: ${REDIS_USERNAME:}
    tls: ${REDIS_TLS:false}
```

- [ ] **Step 8: Run the tests to verify they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=LettuceClientFactoryTest
```

Expected: PASS. A deprecation warning on `getUsername()` is expected — do not "fix" it by
switching the assertion to the credentials provider, which resolves asynchronously and makes the
test harder to read.

- [ ] **Step 9: Run the neighbouring Redis tests**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='Redis*Test,Lettuce*Test,Routing*Test,ReplicaConfigTest'
```

Expected: PASS. These exercise the router and executor paths whose call sites changed.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/redis/LettuceClientFactory.java \
        src/main/java/com/recsys/config/RedisProperties.java \
        src/main/resources/application.yml \
        src/test/java/com/recsys/infrastructure/redis/LettuceClientFactoryTest.java
git commit -m "feat: support TLS and ACL usernames in the Redis client"
```

---

### Task 2: Fail-closed guard on unauthenticated connections

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/redis/LettuceClientFactory.java`
- Modify: `pom.xml` (Surefire `<environmentVariables>`, around line 92)
- Modify: `scripts/run-microservices-local.sh`
- Test: `src/test/java/com/recsys/infrastructure/redis/LettuceClientFactoryTest.java`

**Interfaces:**
- Consumes: nothing from Task 1 beyond the file being edited.
- Produces: `static void requireAuthentication(String password, Map<String, String> env)` —
  throws `IllegalStateException` when the password is blank and `REDIS_ALLOW_NO_AUTH` is not
  `true`.

- [ ] **Step 1: Write the failing tests**

Add to `LettuceClientFactoryTest`:

```java
    @Test
    void blankPasswordWithoutTheOptOutIsRefused() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> LettuceClientFactory.requireAuthentication("", Map.of()));
        assertTrue(e.getMessage().contains("REDIS_PASSWORD"),
                "the message must name the variable to set");
        assertTrue(e.getMessage().contains("REDIS_ALLOW_NO_AUTH"),
                "the message must name the deliberate escape, or the reader has no way out");
    }

    @Test
    void blankPasswordIsAllowedWhenExplicitlyOptedOut() {
        assertDoesNotThrow(() ->
                LettuceClientFactory.requireAuthentication("", Map.of("REDIS_ALLOW_NO_AUTH", "true")));
    }

    @Test
    void aPasswordNeedsNoOptOut() {
        assertDoesNotThrow(() -> LettuceClientFactory.requireAuthentication("s3cret", Map.of()));
    }
```

- [ ] **Step 2: Run them to verify they fail**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=LettuceClientFactoryTest
```

Expected: compilation failure — `requireAuthentication` does not exist.

- [ ] **Step 3: Implement the guard**

Add to `LettuceClientFactory`:

```java
    /**
     * Refuses to open a connection to an unauthenticated Redis unless something says so out loud.
     * Mirrors GatewayAuthenticator.fromEnvironment: REDIS_PASSWORD was supported by this class and
     * set by no manifest, which is exactly how every service ended up connecting as the
     * unauthenticated default user. A warning would not have changed that.
     *
     * <p>Guards the public entry points only. The package-private URI builders and the map-taking
     * router overload stay unguarded so unit tests can construct and inspect URIs without a
     * credential.
     */
    static void requireAuthentication(String password, Map<String, String> env) {
        if (password != null && !password.isBlank()) return;
        if (Boolean.parseBoolean(env.getOrDefault("REDIS_ALLOW_NO_AUTH", "false"))) return;
        throw new IllegalStateException(
                "REDIS_PASSWORD is not set, so this service would connect to Redis as the "
                        + "unauthenticated default user. Redis holds the item and user embeddings, "
                        + "device history, the service registry, and the login-token to API-key "
                        + "mapping. Set REDIS_PASSWORD — in Kubernetes, the redis-password key of "
                        + "the recsys-secrets Secret — or set REDIS_ALLOW_NO_AUTH=true to accept an "
                        + "unauthenticated connection in local development.");
    }
```

- [ ] **Step 4: Call it from the public entry points**

Four call sites. In `fromEnv(int maxTimeoutMs)`:

```java
    public static RedisExecutor fromEnv(int maxTimeoutMs) {
        Map<String, String> env = System.getenv();
        requireAuthentication(env.getOrDefault("REDIS_PASSWORD", ""), env);
        return executor(uriFromEnv(env, maxTimeoutMs), poolConfig(defaultPoolKnobs(env)));
    }
```

In `routingFromEnv()` and `routingFromEnv(int maxTimeoutMs)`, and in the no-arg
`routerFromEnv()`:

```java
    public static RedisExecutor routingFromEnv() {
        Map<String, String> env = System.getenv();
        requireAuthentication(env.getOrDefault("REDIS_PASSWORD", ""), env);
        return new RoutingRedisExecutor(routerFromEnv(env));
    }

    public static RedisExecutor routingFromEnv(int maxTimeoutMs) {
        Map<String, String> env = System.getenv();
        requireAuthentication(env.getOrDefault("REDIS_PASSWORD", ""), env);
        return new RoutingRedisExecutor(routerFromEnv(env, maxTimeoutMs));
    }

    public static RedisReadReplicaRouter routerFromEnv() {
        Map<String, String> env = System.getenv();
        requireAuthentication(env.getOrDefault("REDIS_PASSWORD", ""), env);
        return routerFromEnv(env);
    }
```

And both Spring-properties entry points:

```java
    public static RedisExecutor from(RedisProperties props) {
        requireAuthentication(props.getPassword(), System.getenv());
        return executor(uriFrom(props), poolConfig(props.getPool()));
    }
```

```java
    public static RedisReadReplicaRouter routerFrom(RedisProperties props) {
        requireAuthentication(props.getPassword(), System.getenv());
        // ... existing body unchanged
```

Do **not** add the guard to the package-private `routerFromEnv(Map, int)` overload. Tests pass
synthetic maps to it, and guarding it would make every one of them require the opt-out key.

- [ ] **Step 5: Set the opt-out for the test suite**

In `pom.xml`, inside the Surefire `<environmentVariables>` block, add:

```xml
            <!-- Tests reach a local, passwordless Redis. The guard in LettuceClientFactory is a
                 production control; opting out here keeps it from turning every Redis-touching
                 test into a credential-management exercise. -->
            <REDIS_ALLOW_NO_AUTH>true</REDIS_ALLOW_NO_AUTH>
```

- [ ] **Step 6: Set the opt-out for local development**

In `scripts/run-microservices-local.sh`, near the other `export` lines at the top of the file:

```bash
# Local Redis runs without a password. LettuceClientFactory refuses an unauthenticated
# connection unless this says so explicitly — see docs/runbooks/redis-auth.md.
export REDIS_ALLOW_NO_AUTH=true
```

`docker-compose.cdn.yml` (nginx), `docker-compose.streaming.yml`, and `docker-compose.splunk.yml`
run no Java service, so none of them needs this.

- [ ] **Step 7: Run the full Redis test set**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='Redis*Test,Lettuce*Test,Routing*Test'
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/redis/LettuceClientFactory.java \
        src/test/java/com/recsys/infrastructure/redis/LettuceClientFactoryTest.java \
        pom.xml scripts/run-microservices-local.sh
git commit -m "feat: refuse an unauthenticated Redis connection unless opted out"
```

---

### Task 3: Wire REDIS_PASSWORD into the workloads

**Files:**
- Create: `src/test/java/com/recsys/infrastructure/k8s/RedisAuthManifestTest.java`
- Modify: `k8s/base/api-gateway.yaml`, `k8s/base/catalog-serving.yaml`,
  `k8s/base/model-serving.yaml`, `k8s/base/online-serving.yaml`,
  `k8s/base/outbox-reconciliation-cronjob.yaml`
- Modify: `pom.xml` (the `resilience` profile `<includes>`, around line 365)

**Interfaces:**
- Consumes: `ManifestDocuments.allIn`, `ofKind`, `mapAt`, `listOf`, `nameOf` — package-private in
  `com.recsys.infrastructure.k8s`, which is why this test lives there rather than beside
  `RedisEvictionPolicyManifestTest` in the `redis` package.
- Produces, for Task 4: the test class, which Task 4 extends with server-side assertions.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/infrastructure/k8s/RedisAuthManifestTest.java`:

```java
package com.recsys.infrastructure.k8s;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.recsys.infrastructure.k8s.ManifestDocuments.listOf;
import static com.recsys.infrastructure.k8s.ManifestDocuments.mapAt;
import static com.recsys.infrastructure.k8s.ManifestDocuments.nameOf;
import static com.recsys.infrastructure.k8s.ManifestDocuments.ofKind;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis holds the embeddings, device history, the service registry and the login-token to
 * API-key mapping, and until 2026-08 every service reached it as the unauthenticated default
 * user — REDIS_PASSWORD was supported by LettuceClientFactory and set by no manifest.
 *
 * <p>This is the drift catcher for that. The runtime guard refuses to start without a credential,
 * so a workload that loses its REDIS_PASSWORD wiring fails loudly rather than silently
 * downgrading — but it fails in a cluster, at rollout time. This fails in CI instead.
 */
class RedisAuthManifestTest {

    private static final Path BASE = Path.of("k8s", "base");

    /**
     * Workloads that open a Redis connection. The outbox relay is deliberately absent: it dials
     * MySQL and Kafka only, and granting it a Redis credential would widen its blast radius for
     * nothing.
     */
    private static final Set<String> REDIS_CLIENTS = Set.of(
            "recsys-api-gateway", "recsys-catalog-serving", "recsys-model-serving",
            "recsys-online-serving", "recsys-outbox-reconciliation");

    private static List<Map<String, Object>> baseDocuments() throws IOException {
        return ManifestDocuments.allIn(BASE);
    }

    /** Container env entries across Deployments, CronJobs and StatefulSets, by workload name. */
    private static List<Map<String, Object>> envOf(Map<String, Object> workload) {
        Map<String, Object> podSpec = mapAt(workload, "spec", "template", "spec");
        if (podSpec == null) {
            // CronJob: spec.jobTemplate.spec.template.spec
            podSpec = mapAt(workload, "spec", "jobTemplate", "spec", "template", "spec");
        }
        return listOf(podSpec, "containers").stream()
                .flatMap(c -> listOf(c, "env").stream())
                .toList();
    }

    @Test
    void everyRedisClientReceivesTheRedisPassword() throws IOException {
        List<Map<String, Object>> docs = baseDocuments();

        Set<String> missing = new TreeSet<>(REDIS_CLIENTS);
        for (String kind : List.of("Deployment", "CronJob")) {
            for (Map<String, Object> workload : ofKind(docs, kind)) {
                if (!REDIS_CLIENTS.contains(nameOf(workload))) continue;
                boolean wired = envOf(workload).stream().anyMatch(e -> {
                    if (!"REDIS_PASSWORD".equals(e.get("name"))) return false;
                    Map<String, Object> ref = mapAt(e, "valueFrom", "secretKeyRef");
                    return ref != null && "recsys-secrets".equals(ref.get("name"))
                            && "redis-password".equals(ref.get("key"));
                });
                if (wired) missing.remove(nameOf(workload));
            }
        }

        assertThat(missing)
                .as("these workloads dial Redis with no REDIS_PASSWORD wired from the "
                        + "redis-password key of recsys-secrets. LettuceClientFactory refuses to "
                        + "start without it, so each of these is a CrashLoopBackOff at rollout — "
                        + "and before the guard existed it was a silent unauthenticated connection")
                .isEmpty();
    }

    @Test
    void theOutboxRelayIsNotGivenARedisCredential() throws IOException {
        Map<String, Object> relay = ofKind(baseDocuments(), "Deployment").stream()
                .filter(d -> "recsys-outbox-relay".equals(nameOf(d)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Deployment named recsys-outbox-relay"));

        assertThat(envOf(relay).stream().map(e -> e.get("name")))
                .as("the relay dials MySQL and Kafka only. Handing it a Redis credential widens "
                        + "the blast radius of a relay compromise for no functional gain; if the "
                        + "relay genuinely starts using Redis, add it to REDIS_CLIENTS in the same "
                        + "commit that adds the code")
                .doesNotContain("REDIS_PASSWORD");
    }
}
```

- [ ] **Step 2: Register the test in the PR gate**

In `pom.xml`, in the `resilience` profile's `<includes>` beside the existing
`**/k8s/NetworkPolicyEgressManifestTest.java` entry:

```xml
                <include>**/k8s/RedisAuthManifestTest.java</include>
```

- [ ] **Step 3: Run it to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=RedisAuthManifestTest
```

Expected: `everyRedisClientReceivesTheRedisPassword` FAILS listing all five workloads;
`theOutboxRelayIsNotGivenARedisCredential` PASSES already.

- [ ] **Step 4: Wire the credential into all five workloads**

In each of `k8s/base/api-gateway.yaml`, `catalog-serving.yaml`, `model-serving.yaml`,
`online-serving.yaml`, and `outbox-reconciliation-cronjob.yaml`, add to the container's `env:`
list (beside the existing `recsys-secrets` references where there are any):

```yaml
            # Redis AUTH. optional: true keeps the pod schedulable before the Secret exists —
            # LettuceClientFactory then refuses to start with a message naming this key, which is
            # a readable failure rather than a pod stuck Pending with an empty log.
            - name: REDIS_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: recsys-secrets
                  key: redis-password
                  optional: true
```

Match each file's existing indentation — the CronJob nests two levels deeper than the Deployments.

- [ ] **Step 5: Run the test to verify it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=RedisAuthManifestTest
```

Expected: PASS, both tests.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/recsys/infrastructure/k8s/RedisAuthManifestTest.java pom.xml k8s/base/
git commit -m "feat: wire REDIS_PASSWORD into every workload that dials Redis"
```

---

### Task 4: Authenticate the in-cluster Redis and Sentinel

**Files:**
- Modify: `k8s/base/redis-cluster.yaml` (ConfigMap template ~line 8, primary ~line 63,
  replica ~line 178, sentinel ~line 274)
- Test: `src/test/java/com/recsys/infrastructure/k8s/RedisAuthManifestTest.java`

**Interfaces:**
- Consumes: the test class from Task 3.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Write the failing test**

Add to `RedisAuthManifestTest`:

```java
    /**
     * Client-side credentials are worthless if the server accepts anonymous connections. Each of
     * these three has a different failure mode when missing: no requirepass and Redis is open;
     * no masterauth and replication stops, so replicas serve indefinitely stale reads rather than
     * failing; no sentinel auth-pass and the sentinels never reach quorum, which presents as a
     * Redis outage rather than as an auth error.
     */
    @Test
    void theRedisServersRequireAuthentication() throws IOException {
        List<Map<String, Object>> docs = baseDocuments();

        Map<String, List<String>> argsByName = new java.util.LinkedHashMap<>();
        for (Map<String, Object> sts : ofKind(docs, "StatefulSet")) {
            List<String> args = listOf(mapAt(sts, "spec", "template", "spec"), "containers").stream()
                    .flatMap(c -> ManifestDocuments.stringListOf(c, "args").stream())
                    .toList();
            argsByName.put(nameOf(sts), args);
        }

        assertThat(argsByName.get("redis-primary"))
                .as("the primary must set --requirepass, or it accepts anonymous connections "
                        + "regardless of what every client is configured to send")
                .contains("--requirepass");
        assertThat(argsByName.get("redis-replica"))
                .as("the replica must set --requirepass (it serves reads) and --masterauth "
                        + "(it authenticates to the primary). Without masterauth replication stops "
                        + "and the replica serves indefinitely stale data instead of failing")
                .contains("--requirepass", "--masterauth");

        String sentinelConf = ofKind(docs, "ConfigMap").stream()
                .filter(c -> "redis-sentinel-config".equals(nameOf(c)))
                .findFirst()
                .map(c -> String.valueOf(mapAt(c, "data").get("sentinel-template.conf")))
                .orElseThrow(() -> new AssertionError("no ConfigMap named redis-sentinel-config"));

        assertThat(sentinelConf)
                .as("the sentinel template must carry auth-pass, or the sentinels cannot "
                        + "authenticate to the primary, never reach quorum, and never fail over — "
                        + "which looks exactly like a Redis outage")
                .contains("sentinel auth-pass mymaster");
    }

    @Test
    void redisProbesDoNotPutThePasswordOnTheCommandLine() throws IOException {
        Set<String> offenders = new TreeSet<>();
        for (Map<String, Object> sts : ofKind(baseDocuments(), "StatefulSet")) {
            for (Map<String, Object> container : listOf(mapAt(sts, "spec", "template", "spec"), "containers")) {
                for (String probe : List.of("readinessProbe", "livenessProbe")) {
                    List<String> cmd = ManifestDocuments.stringListOf(
                            mapAt(container, probe, "exec"), "command");
                    if (cmd.contains("-a")) offenders.add(nameOf(sts) + "." + probe);
                }
            }
        }

        assertThat(offenders)
                .as("a redis-cli -a flag puts the password in the process table and echoes it in "
                        + "probe failure output. Set REDISCLI_AUTH in the container env instead")
                .isEmpty();
    }
```

- [ ] **Step 2: Run it to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=RedisAuthManifestTest
```

Expected: `theRedisServersRequireAuthentication` FAILS on the primary's missing `--requirepass`.
`redisProbesDoNotPutThePasswordOnTheCommandLine` PASSES already — it is there to stop the obvious
wrong fix in the next step.

- [ ] **Step 3: Authenticate the primary**

In `k8s/base/redis-cluster.yaml`, in the `redis-primary` container, append to `args`:

```yaml
            - "--requirepass"
            - "$(REDIS_PASSWORD)"
```

and add an `env:` block to that container, before `ports:`:

```yaml
          env:
            - name: REDIS_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: recsys-secrets
                  key: redis-password
                  optional: true
            # redis-cli reads REDISCLI_AUTH from the environment. A -a flag on the probe command
            # would put the password in the process table and in probe failure output.
            - name: REDISCLI_AUTH
              valueFrom:
                secretKeyRef:
                  name: recsys-secrets
                  key: redis-password
                  optional: true
```

Kubernetes expands `$(REDIS_PASSWORD)` in `args` from the container's own env, so the ordering
above matters only for readability.

- [ ] **Step 4: Authenticate the replica**

In the `redis-replica` container, append to `args`:

```yaml
            - "--requirepass"
            - "$(REDIS_PASSWORD)"
            # The replica authenticates TO the primary with this. Without it the replication
            # stream is rejected and the replica serves indefinitely stale reads rather than
            # failing — the most expensive way for this change to go wrong.
            - "--masterauth"
            - "$(REDIS_PASSWORD)"
```

and add the identical `env:` block from Step 3 to that container.

- [ ] **Step 5: Authenticate Sentinel**

In the `redis-sentinel-config` ConfigMap, append to `sentinel-template.conf`:

```
    sentinel auth-pass mymaster __REDIS_PASSWORD__
```

Then in the sentinel StatefulSet, change the init container's `command` from the plain `cp` to a
substitution, and give both containers the env:

```yaml
      initContainers:
        - name: copy-sentinel-config
          image: redis:7-alpine
          # sed rather than cp: the template lives in a ConfigMap, which cannot hold the password,
          # so the placeholder is substituted from the environment at startup.
          command:
            - sh
            - -c
            - sed "s|__REDIS_PASSWORD__|$REDIS_PASSWORD|" /etc/redis/sentinel-template.conf > /data/sentinel.conf
          env:
            - name: REDIS_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: recsys-secrets
                  key: redis-password
                  optional: true
          volumeMounts:
            - name: sentinel-config-template
              mountPath: /etc/redis
            - name: sentinel-data
              mountPath: /data
```

and on the `sentinel` container itself, add the `REDISCLI_AUTH` env entry from Step 3 so its two
`redis-cli -p 26379 ping` probes authenticate.

- [ ] **Step 6: Run the test to verify it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=RedisAuthManifestTest
```

Expected: PASS, all four tests.

- [ ] **Step 7: Verify the manifests still render**

```bash
kubectl kustomize k8s/base > /dev/null && echo "base renders"
kubectl kustomize k8s/eks > /dev/null && echo "eks renders"
```

Expected: both print their success line. If `kubectl` is unavailable, skip this step and note it
in the PR body — the YAML is still parsed by the test above.

- [ ] **Step 8: Commit**

```bash
git add k8s/base/redis-cluster.yaml src/test/java/com/recsys/infrastructure/k8s/RedisAuthManifestTest.java
git commit -m "feat: require authentication on the in-cluster Redis and Sentinel"
```

---

### Task 5: EKS overlays and documentation

**Files:**
- Modify: `k8s/eks/redis-elasticache-patch.yaml`
- Modify: `k8s/eks-us-west-2/redis-elasticache-patch.yaml`
- Modify: `docs/system_design/20_AuthN_AuthZ.md`
- Create: `docs/runbooks/redis-auth.md`

**Interfaces:** none.

- [ ] **Step 1: Set REDIS_TLS explicitly in both overlays**

In `k8s/eks/redis-elasticache-patch.yaml`, beside the existing `REDIS_PORT` entry:

```yaml
  # Explicitly false rather than absent: an absent key reads as "not applicable", and this is a
  # prerequisite an operator has to act on. Flip to "true" only once the ElastiCache cluster has
  # encryption-in-transit enabled and an AUTH token provisioned into the redis-password key of
  # recsys-secrets. The client verifies the certificate against the JVM truststore, which already
  # trusts the Amazon Root CA that ElastiCache certificates chain to.
  REDIS_TLS: "false"
```

Apply the identical entry to `k8s/eks-us-west-2/redis-elasticache-patch.yaml`.

- [ ] **Step 2: Document the data tier in 20_AuthN_AuthZ**

`docs/system_design/20_AuthN_AuthZ.md` describes four ACL layers and states the data tier has
none. Add a new numbered section after §8 (the L3/L4 ACL), renumbering nothing — append it as §9
and shift the existing "Testing" and "Sharp edges" headings down:

```markdown
## 9. Data-tier authentication

Until 2026-08 every service connected to Redis as the unauthenticated `default` user over a
plaintext socket. `LettuceClientFactory` read `REDIS_PASSWORD` and no manifest set it — a
supported control that nothing switched on.

Redis now requires a password: `--requirepass` on the primary and replica, `--masterauth` so the
replica can authenticate to the primary, and `sentinel auth-pass` in the Sentinel template,
substituted from the environment by the init container because a ConfigMap cannot hold a secret.
Clients read `REDIS_PASSWORD` from the `redis-password` key of `recsys-secrets`.

`LettuceClientFactory` refuses to open a connection without a credential unless
`REDIS_ALLOW_NO_AUTH=true` — the same fail-closed shape as `GatewayAuthenticator.fromEnvironment`
in §1. Local development and the test suite set it; no overlay does.

The client also supports `REDIS_USERNAME` (Redis 6 ACL login) and `REDIS_TLS`. Both are unused
today: the in-cluster Redis has no certificates, and per-service ACL users are a separate project.
They exist so that project is configuration rather than a client change.

Two things this does not do. Traffic between the pods and Redis is still unencrypted, so the
NetworkPolicy remains the only control on who can read it in transit. And all five clients share
one credential over the whole keyspace, despite cleanly disjoint key ownership — that is the ACL
work, not this.

Design: [Redis transport authentication](../superpowers/specs/2026-08-05-redis-transport-auth-design.md).
```

- [ ] **Step 3: Write the rotation runbook**

Create `docs/runbooks/redis-auth.md`:

```markdown
# Redis authentication

## Provisioning

`REDIS_PASSWORD` comes from the `redis-password` key of the `recsys-secrets` Secret, consumed by
the four serving workloads, the reconciliation CronJob, and the Redis and Sentinel StatefulSets
themselves. The outbox relay does not use Redis and is deliberately not given the credential.

```bash
kubectl -n recsys create secret generic recsys-secrets \
  --from-literal=redis-password="$(openssl rand -base64 32)" \
  --dry-run=client -o yaml | kubectl apply -f -
```

The secret reference is `optional: true`, so pods stay schedulable before it exists. They will not
*run*: `LettuceClientFactory` refuses an unauthenticated connection and the pod crash-loops with a
message naming `REDIS_PASSWORD`. That is the intended failure — a readable crash rather than a
silent downgrade to anonymous access.

## Local development

`scripts/run-microservices-local.sh` exports `REDIS_ALLOW_NO_AUTH=true`, and Surefire sets it for
the test suite. Set it yourself if you run a service by hand against a passwordless Redis:

```bash
export REDIS_ALLOW_NO_AUTH=true
```

## Rotation

`requirepass` holds exactly one password, so rotation is a coordinated restart rather than an
overlap window. This is the accepted cost of `requirepass` over an ACL file; if rotation frequency
ever makes it unacceptable, moving the `default` user to an `aclfile` buys multi-password overlap.

Expect Redis to be unreachable for the duration. Everything degrades to its no-Redis path, which is
not a full outage but is a visible one.

1. Update the Secret:

   ```bash
   kubectl -n recsys patch secret recsys-secrets \
     -p "{\"stringData\":{\"redis-password\":\"$(openssl rand -base64 32)\"}}"
   ```

2. Restart Redis and Sentinel first — clients cannot authenticate until the server has the new
   password:

   ```bash
   kubectl -n recsys rollout restart statefulset/redis-primary
   kubectl -n recsys rollout restart statefulset/redis-replica
   kubectl -n recsys rollout restart statefulset/redis-sentinel
   ```

3. Then the clients:

   ```bash
   kubectl -n recsys rollout restart deployment/recsys-api-gateway
   kubectl -n recsys rollout restart deployment/recsys-catalog-serving
   kubectl -n recsys rollout restart deployment/recsys-model-serving
   kubectl -n recsys rollout restart deployment/recsys-online-serving
   ```

   The reconciliation CronJob picks the new value up on its next scheduled run.

## ElastiCache

Both EKS overlays set `REDIS_TLS: "false"` explicitly. Flipping it to `"true"` requires the
ElastiCache cluster to have encryption-in-transit enabled and its AUTH token placed in
`redis-password`. The client verifies the certificate against the JVM truststore, which already
trusts the Amazon Root CA that ElastiCache certificates chain to — there is no truststore to
configure and no verification bypass to enable.
```

- [ ] **Step 4: Verify the docs index still passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=DocumentationIndexTest
```

Expected: PASS. `docs/runbooks/redis-auth.md` is new, so if this fails, add it to the runbook index
the test names in its failure message.

- [ ] **Step 5: Commit**

```bash
git add k8s/eks/ k8s/eks-us-west-2/ docs/system_design/20_AuthN_AuthZ.md docs/runbooks/redis-auth.md
git commit -m "docs: document Redis authentication and its rotation procedure"
```

---

### Task 6: Full gate run and PR

- [ ] **Step 1: Run the PR gate profile**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
```

Expected: PASS, including the two new manifest tests.

- [ ] **Step 2: Run the full suite**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test
```

Expected: PASS. This is the run that catches anything reaching `LettuceClientFactory`'s public
entry points that the `-Presilience` allow-list does not cover. If a test fails on the guard, it
means Surefire's `REDIS_ALLOW_NO_AUTH` is not reaching it — fix the Surefire configuration rather
than the individual test.

- [ ] **Step 3: Open the PR**

```bash
git push -u origin feat/redis-transport-auth
gh pr create --title "feat: authenticate Redis and support TLS" --body "$(cat <<'EOF'
Every service connected to Redis as the unauthenticated `default` user over a plaintext socket.
`LettuceClientFactory` read `REDIS_PASSWORD` and no manifest set it, and the client had no TLS
capability at all — so ElastiCache encryption-in-transit was a code change here, not a config
toggle there.

- **Client:** `REDIS_USERNAME` (Redis 6 ACL login) and `REDIS_TLS`, applied to the standalone,
  sentinel, and read-replica URIs. The replica path is called out in its own test: it is built
  outside `uriFromEnv`, so a partial change leaves most of the traffic unauthenticated while the
  primary looks right.
- **Guard:** an unauthenticated connection refuses to start unless `REDIS_ALLOW_NO_AUTH=true`,
  mirroring `GatewayAuthenticator.fromEnvironment`.
- **Server:** `requirepass`, `masterauth`, and `sentinel auth-pass` from `recsys-secrets`; probes
  use `REDISCLI_AUTH` rather than a `-a` flag.
- **Drift catcher:** `RedisAuthManifestTest`, in the `-Presilience` gate.

In-cluster TLS and per-service ACL users stay out of scope. `REDIS_USERNAME` is what unblocks the
latter.

Design: `docs/superpowers/specs/2026-08-05-redis-transport-auth-design.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```
