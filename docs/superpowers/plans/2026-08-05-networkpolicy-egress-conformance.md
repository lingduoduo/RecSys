# NetworkPolicy Egress Conformance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close six egress gaps in `k8s/base/network-policy.yaml` and add a manifest-conformance test that derives upstream addresses from the ConfigMap so the policy and the endpoints services actually dial cannot drift apart silently again.

**Architecture:** Four new test-source files under `src/test/java/com/recsys/infrastructure/k8s/` — a YAML reader, an upstream-address parser with its own unit test, and the conformance test carrying six assertions. Manifest fixes land in `k8s/base/network-policy.yaml`, `k8s/base/redis-cluster.yaml`, and a new `k8s/eks-shared/network-policy-elasticache-patch.yaml`. Each task writes the assertion first, watches it fail against the current manifests, then fixes the manifest.

**Tech Stack:** JUnit 5, AssertJ, SnakeYAML (already on the test classpath via Spring Boot — no new dependency), Maven Surefire `resilience` profile, Kustomize manifests.

## Global Constraints

- **JDK 17 required.** Every Maven command must be prefixed `JAVA_HOME=$(/usr/libexec/java_home -v 17)`. On JDK 25 a clean compile fails on two pre-existing files unrelated to this work.
- **No new Maven dependencies.** SnakeYAML and AssertJ are already available to tests; `ScrapeTargetManifestTest` proves it.
- **Tests must be non-`@Tag("docker")`** and listed in the `resilience` profile's `<includes>` in `pom.xml` to block PRs. `<excludedGroups>load,docker</excludedGroups>` drops docker-tagged tests regardless of any include.
- **Surefire runs with the repo root as the working directory**, so manifest paths are `Path.of("k8s", "base")` — relative, never absolute.
- **`docs/superpowers/` is excluded from `DocumentationIndexTest`**; the spec and this plan need no README entry. The `20_AuthN_AuthZ.md` edit lands in an already-indexed document.
- **Branch:** `feat/networkpolicy-egress-conformance`, already created, with the design doc committed. Never merge to main directly — open a PR.
- **Placeholder CIDR value is exactly `10.0.0.0/16` with a `REPLACE_ME` comment**, matching the `wafv2-acl-arn` operator-prerequisite pattern.

---

### Task 1: Manifest reader and upstream-address parser

Pure logic with no manifest dependency, so it can be fully unit-tested before any assertion exists. Everything later tasks build on lands here.

**Files:**
- Create: `src/test/java/com/recsys/infrastructure/k8s/ManifestDocuments.java`
- Create: `src/test/java/com/recsys/infrastructure/k8s/Upstream.java`
- Test: `src/test/java/com/recsys/infrastructure/k8s/UpstreamTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `ManifestDocuments.allIn(Path dir) -> List<Map<String,Object>>`
  - `ManifestDocuments.ofKind(List<Map<String,Object>>, String kind) -> List<Map<String,Object>>`
  - `ManifestDocuments.mapAt(Map<String,Object>, String... path) -> Map<String,Object>` (null when absent)
  - `ManifestDocuments.listOf(Map<String,Object>, String key) -> List<Map<String,Object>>` (empty when absent)
  - `ManifestDocuments.nameOf(Map<String,Object>) -> String`
  - `ManifestDocuments.stringListOf(Map<String,Object>, String key) -> List<String>`
  - `Upstream` — a record `(String host, int port)`
  - `Upstream.parse(String key, Map<String,String> configMap) -> List<Upstream>`

- [ ] **Step 1: Write the failing parser test**

Create `src/test/java/com/recsys/infrastructure/k8s/UpstreamTest.java`:

```java
package com.recsys.infrastructure.k8s;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conformance test is only as good as its address derivation: a key whose value it
 * silently fails to parse becomes an upstream it silently stops requiring a rule for.
 */
class UpstreamTest {

    @Test
    void parsesHttpServiceUrl() {
        Map<String, String> cfg = Map.of("CATALOG_SERVICE_URL", "http://recsys-catalog-serving:6010");
        assertThat(Upstream.parse("CATALOG_SERVICE_URL", cfg))
                .containsExactly(new Upstream("recsys-catalog-serving", 6010));
    }

    @Test
    void defaultsSchemePortWhenUrlOmitsIt() {
        Map<String, String> cfg = Map.of("A_SERVICE_URL", "https://example.internal");
        assertThat(Upstream.parse("A_SERVICE_URL", cfg))
                .containsExactly(new Upstream("example.internal", 443));
    }

    @Test
    void parsesJdbcMysqlUrl() {
        Map<String, String> cfg = Map.of("MYSQL_URL", "jdbc:mysql://mysql:3306/recsys");
        assertThat(Upstream.parse("MYSQL_URL", cfg))
                .containsExactly(new Upstream("mysql", 3306));
    }

    @Test
    void pairsRedisHostWithRedisPort() {
        Map<String, String> cfg = Map.of("REDIS_HOST", "redis", "REDIS_PORT", "6379");
        assertThat(Upstream.parse("REDIS_HOST", cfg))
                .containsExactly(new Upstream("redis", 6379));
    }

    @Test
    void splitsCommaSeparatedNodeLists() {
        Map<String, String> cfg = Map.of("REDIS_SENTINEL_NODES",
                "redis-sentinel-0.redis-sentinel-headless.recsys.svc.cluster.local:26379,"
                        + "redis-sentinel-1.redis-sentinel-headless.recsys.svc.cluster.local:26379");
        assertThat(Upstream.parse("REDIS_SENTINEL_NODES", cfg)).containsExactly(
                new Upstream("redis-sentinel-0.redis-sentinel-headless.recsys.svc.cluster.local", 26379),
                new Upstream("redis-sentinel-1.redis-sentinel-headless.recsys.svc.cluster.local", 26379));
    }

    @Test
    void parsesBootstrapServers() {
        Map<String, String> cfg = Map.of("OUTBOX_KAFKA_BOOTSTRAP_SERVERS", "kafka:9092");
        assertThat(Upstream.parse("OUTBOX_KAFKA_BOOTSTRAP_SERVERS", cfg))
                .containsExactly(new Upstream("kafka", 9092));
    }

    /**
     * SAGA_EVENTS_SQS_QUEUE_URL and GATEWAY_COGNITO_ISSUER are both "" in k8s/base. A blank
     * value is a disabled feature, not an upstream — it must yield no requirement rather than
     * throwing and taking the whole conformance test down with it.
     */
    @Test
    void blankValueYieldsNoUpstream() {
        assertThat(Upstream.parse("SAGA_EVENTS_SQS_QUEUE_URL",
                Map.of("SAGA_EVENTS_SQS_QUEUE_URL", ""))).isEmpty();
        assertThat(Upstream.parse("ABSENT_SERVICE_URL", Map.of())).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=UpstreamTest
```

Expected: FAIL — compilation error, `Upstream` does not exist.

- [ ] **Step 3: Write `ManifestDocuments`**

Create `src/test/java/com/recsys/infrastructure/k8s/ManifestDocuments.java`:

```java
package com.recsys.infrastructure.k8s;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads Kustomize manifests as plain maps. Structural access only — every accessor returns a
 * null/empty neutral rather than throwing, so an assertion reports "no rule permits X" instead
 * of a NullPointerException that says nothing about the manifest.
 */
final class ManifestDocuments {

    private ManifestDocuments() {
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> allIn(Path dir) throws IOException {
        List<Map<String, Object>> all = new ArrayList<>();
        try (var files = Files.list(dir)) {
            List<Path> yamls = files.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
            for (Path p : yamls) {
                try (InputStream in = Files.newInputStream(p)) {
                    for (Object doc : new Yaml().loadAll(in)) {
                        if (doc instanceof Map<?, ?> map) all.add((Map<String, Object>) map);
                    }
                }
            }
        }
        return all;
    }

    static List<Map<String, Object>> ofKind(List<Map<String, Object>> docs, String kind) {
        return docs.stream().filter(d -> kind.equals(d.get("kind"))).toList();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mapAt(Map<String, Object> doc, String... path) {
        Map<String, Object> cursor = doc;
        for (String key : path) {
            Object next = cursor == null ? null : cursor.get(key);
            cursor = next instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        }
        return cursor;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> listOf(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    static List<String> stringListOf(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    static String nameOf(Map<String, Object> doc) {
        Map<String, Object> metadata = mapAt(doc, "metadata");
        return metadata == null ? null : String.valueOf(metadata.get("name"));
    }
}
```

- [ ] **Step 4: Write `Upstream`**

Create `src/test/java/com/recsys/infrastructure/k8s/Upstream.java`:

```java
package com.recsys.infrastructure.k8s;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * A host:port a workload dials, derived from a ConfigMap value. Derivation rather than a
 * hand-written list is the point: the addresses live in exactly one place, so changing an
 * upstream's address in the ConfigMap re-points the requirement automatically.
 */
record Upstream(String host, int port) {

    static List<Upstream> parse(String key, Map<String, String> configMap) {
        String value = configMap.getOrDefault(key, "");
        if (value.isBlank()) return List.of();

        if ("REDIS_HOST".equals(key)) {
            int port = Integer.parseInt(configMap.getOrDefault("REDIS_PORT", "6379").strip());
            return List.of(new Upstream(value.strip(), port));
        }
        if (key.endsWith("_NODES") || key.endsWith("_BOOTSTRAP_SERVERS")) {
            return java.util.Arrays.stream(value.split(","))
                    .map(String::strip)
                    .filter(s -> !s.isBlank())
                    .map(Upstream::fromHostPort)
                    .toList();
        }
        if (value.startsWith("jdbc:")) {
            // jdbc:mysql://host:port/db — strip the jdbc: prefix so URI sees a normal scheme.
            return List.of(fromUri(URI.create(value.substring("jdbc:".length()))));
        }
        return List.of(fromUri(URI.create(value.strip())));
    }

    private static Upstream fromHostPort(String hostPort) {
        int colon = hostPort.lastIndexOf(':');
        if (colon < 0) throw new IllegalArgumentException("no port in node entry: " + hostPort);
        return new Upstream(hostPort.substring(0, colon), Integer.parseInt(hostPort.substring(colon + 1)));
    }

    private static Upstream fromUri(URI uri) {
        int port = uri.getPort();
        if (port < 0) port = "https".equals(uri.getScheme()) ? 443 : 80;
        return new Upstream(uri.getHost(), port);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=UpstreamTest
```

Expected: PASS, 7 tests.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/recsys/infrastructure/k8s/
git commit -m "test: add manifest reader and upstream address parser"
```

---

### Task 2: Assertion — every declared upstream is permitted

The core assertion, plus the four egress rules and one Service it exposes as missing.

**Files:**
- Create: `src/test/java/com/recsys/infrastructure/k8s/NetworkPolicyEgressManifestTest.java`
- Modify: `k8s/base/network-policy.yaml`
- Modify: `k8s/base/redis-cluster.yaml` (append a `redis` Service)
- Modify: `pom.xml` (`resilience` profile `<includes>`)

**Interfaces:**
- Consumes: `ManifestDocuments.*`, `Upstream.parse` from Task 1.
- Produces, for Tasks 3–6 to reuse:
  - `NetworkPolicyEgressManifestTest.OWNED_KEYS` — `Map<String, Set<String>>`, workload name → ConfigMap keys it dials
  - `NetworkPolicyEgressManifestTest.EXTERNALLY_DEPLOYED` — `Set<String>` of hosts base does not deploy
  - `private static Map<String,Object> destinationLabels(String host, List<Map<String,Object>> docs)`
  - `private static Map<String,Object> policyFor(String workload, List<Map<String,Object>> docs)`
  - `private static boolean restrictsEgress(Map<String,Object> policy)`
  - `private static boolean permitsEgress(Map<String,Object> policy, Map<String,Object> destLabels, int port)`
  - `private static Map<String,String> configMap(List<Map<String,Object>> docs)`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/infrastructure/k8s/NetworkPolicyEgressManifestTest.java`:

```java
package com.recsys.infrastructure.k8s;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.recsys.infrastructure.k8s.ManifestDocuments.listOf;
import static com.recsys.infrastructure.k8s.ManifestDocuments.mapAt;
import static com.recsys.infrastructure.k8s.ManifestDocuments.nameOf;
import static com.recsys.infrastructure.k8s.ManifestDocuments.ofKind;
import static com.recsys.infrastructure.k8s.ManifestDocuments.stringListOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * k8s/base/network-policy.yaml is this repo's only L3/L4 access-control list, and
 * docs/system_design/20_AuthN_AuthZ.md rests the whole backend trust model on it: 6010, 7010
 * and 8080 authenticate nobody *because* the policy proves the gateway is their only reachable
 * caller. That argument only holds while the policy's destination set matches the set the
 * services actually dial — and before this test it did not, in six places at once.
 *
 * <p>Egress drift is uniquely nasty because its failure modes are quiet. A blocked service
 * registry silently falls back to static routes and looks like a registry that resolved
 * nothing; a blocked sentinel connection fails at startup in a way that reads as a Redis
 * outage. So the addresses are <em>derived</em> from k8s/base/configmap.yaml rather than
 * restated here: change an upstream's address and the requirement follows it.
 *
 * <p>Ownership cannot be derived the same way — recsys-config is a single ConfigMap
 * envFrom'd into all five workloads, so every service receives LLM_SERVICE_URL and MYSQL_URL
 * in its environment whether or not it dials them. {@link #OWNED_KEYS} is that missing half,
 * and {@link #everyConfigMapUpstreamKeyIsClaimed} is what stops it going stale.
 */
class NetworkPolicyEgressManifestTest {

    private static final Path BASE = Path.of("k8s", "base");

    /** Which ConfigMap keys each workload actually dials. See the class comment for why. */
    static final Map<String, Set<String>> OWNED_KEYS = Map.of(
            // The gateway proxies to all three backends and the LLM, and opens its own Redis
            // connection (LettuceClientFactory.routingFromEnv) when SERVICE_REGISTRY_ENABLED
            // is true — which in base means the sentinel path, since REDIS_MODE is "sentinel".
            "recsys-api-gateway", Set.of(
                    "CATALOG_SERVICE_URL", "MODEL_SERVICE_URL", "ONLINE_SERVICE_URL",
                    "USER_PROFILE_SERVICE_URL", "MOVIE_METADATA_SERVICE_URL",
                    "FEATURE_SERVICE_URL", "RECOMMENDATION_RETRIEVAL_SERVICE_URL",
                    "RANKING_SERVICE_URL", "AGENT_WORKFLOW_SERVICE_URL",
                    "OBSERVABILITY_SERVICE_URL", "LLM_SERVICE_URL",
                    "LLM_EXPLANATION_SERVICE_URL", "REDIS_HOST", "REDIS_SENTINEL_NODES"),
            "recsys-catalog-serving", Set.of("REDIS_HOST", "REDIS_SENTINEL_NODES"),
            "recsys-model-serving", Set.of("REDIS_HOST", "REDIS_SENTINEL_NODES"),
            // Online serving writes the outbox rows when MYSQL_ENABLED=true.
            "recsys-online-serving", Set.of("REDIS_HOST", "REDIS_SENTINEL_NODES", "MYSQL_URL"),
            "recsys-outbox-relay", Set.of(
                    "MYSQL_URL", "OUTBOX_KAFKA_BOOTSTRAP_SERVERS", "SAGA_EVENTS_SQS_QUEUE_URL"));

    /**
     * Hosts k8s/base names but does not deploy, so no Service can resolve their pod labels.
     * Resolution is otherwise strict — see {@link #destinationLabels}.
     */
    static final Set<String> EXTERNALLY_DEPLOYED = Set.of("ollama", "mysql", "kafka");

    private static List<Map<String, Object>> baseDocuments() throws IOException {
        return ManifestDocuments.allIn(BASE);
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> configMap(List<Map<String, Object>> docs) {
        Map<String, Object> cm = ofKind(docs, "ConfigMap").stream()
                .filter(d -> "recsys-config".equals(nameOf(d)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ConfigMap named recsys-config in k8s/base"));
        Map<String, String> data = new LinkedHashMap<>();
        ((Map<String, Object>) cm.get("data")).forEach((k, v) -> data.put(k, String.valueOf(v)));
        return data;
    }

    static Map<String, Object> policyFor(String workload, List<Map<String, Object>> docs) {
        return ofKind(docs, "NetworkPolicy").stream()
                .filter(p -> workload.equals(nameOf(p)))
                .findFirst()
                .orElse(null);
    }

    /** A policy that does not list Egress in policyTypes leaves egress unrestricted. */
    static boolean restrictsEgress(Map<String, Object> policy) {
        return stringListOf(mapAt(policy, "spec"), "policyTypes").contains("Egress");
    }

    /**
     * Resolve a host to the labels of the pods behind it. Strict on purpose: an unresolvable
     * host is dead configuration, and a permissive `app: <host>` fallback would hide exactly
     * that. REDIS_HOST "redis" named no Service before this change.
     */
    static Map<String, Object> destinationLabels(String host, List<Map<String, Object>> docs) {
        Map<String, Map<String, Object>> selectors = new LinkedHashMap<>();
        for (Map<String, Object> svc : ofKind(docs, "Service")) {
            Map<String, Object> selector = mapAt(svc, "spec", "selector");
            if (selector != null) selectors.put(nameOf(svc), selector);
        }
        String[] labels = host.split("\\.");
        // StatefulSet pod FQDN: <pod>.<headless-service>.<ns>.svc.cluster.local
        if (labels.length >= 2 && selectors.containsKey(labels[1])) return selectors.get(labels[1]);
        if (selectors.containsKey(labels[0])) return selectors.get(labels[0]);
        // Explicit witness: Map.of("app", x) infers Map<String,String>, which is not a
        // Map<String,Object> and will not compile as this method's return value.
        if (EXTERNALLY_DEPLOYED.contains(labels[0])) return Map.<String, Object>of("app", labels[0]);
        throw new AssertionError("host '" + host + "' resolves to no Service in k8s/base and is not "
                + "listed in EXTERNALLY_DEPLOYED — it is dead configuration, or a Service is missing");
    }

    /** A selector matches a pod when every one of its label pairs is present on that pod. */
    private static boolean selects(Map<String, Object> selector, Map<String, Object> podLabels) {
        return selector != null && !selector.isEmpty()
                && podLabels.entrySet().containsAll(selector.entrySet());
    }

    /**
     * Selector AND port must sit in the SAME rule. Checking "some rule names the destination"
     * and "some rule names the port" independently is the exact false pass
     * ScrapeTargetManifestTest documents having slipped past its own earlier version.
     */
    static boolean permitsEgress(Map<String, Object> policy, Map<String, Object> destLabels, int port) {
        if (!restrictsEgress(policy)) return true;
        return listOf(mapAt(policy, "spec"), "egress").stream().anyMatch(rule -> {
            boolean toDestination = listOf(rule, "to").stream()
                    .anyMatch(to -> selects(mapAt(to, "podSelector", "matchLabels"), destLabels));
            boolean onPort = listOf(rule, "ports").stream()
                    .anyMatch(p -> Integer.valueOf(port).equals(p.get("port")));
            return toDestination && onPort;
        });
    }

    @Test
    void everyDeclaredUpstreamIsPermittedByEgress() throws IOException {
        List<Map<String, Object>> docs = baseDocuments();
        Map<String, String> cfg = configMap(docs);

        Set<String> unreachable = new TreeSet<>();
        for (Map.Entry<String, Set<String>> entry : OWNED_KEYS.entrySet()) {
            String workload = entry.getKey();
            Map<String, Object> policy = policyFor(workload, docs);
            assertThat(policy).as("no NetworkPolicy named %s in k8s/base", workload).isNotNull();
            if (!restrictsEgress(policy)) continue;

            for (String key : entry.getValue()) {
                for (Upstream upstream : Upstream.parse(key, cfg)) {
                    Map<String, Object> destLabels = destinationLabels(upstream.host(), docs);
                    if (!permitsEgress(policy, destLabels, upstream.port())) {
                        unreachable.add(workload + " -> " + key + " (" + upstream.host()
                                + ":" + upstream.port() + ", pods " + destLabels + ")");
                    }
                }
            }
        }

        assertThat(unreachable)
                .as("each of these is an endpoint a workload dials with no egress rule permitting "
                        + "it, so under an enforcing CNI the connection is dropped. The failures are "
                        + "quiet in different ways and need different fixes: a blocked service "
                        + "registry falls back to static routes and logs nothing unusual, a blocked "
                        + "sentinel connection fails at startup looking like a Redis outage, and a "
                        + "blocked MySQL connection surfaces only on the first outbox append. Add a "
                        + "matching egress rule to k8s/base/network-policy.yaml")
                .isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=NetworkPolicyEgressManifestTest
```

Expected: FAIL. The assertion error lists the sentinel entries for all four workloads, the gateway's `LLM_SERVICE_URL` and `REDIS_HOST`, and online serving's `MYSQL_URL` — *unless* it fails earlier with `host 'redis' resolves to no Service`, which is the strict-resolution failure and is equally expected. Fix the Service first (Step 3), rerun, then fix the rules.

- [ ] **Step 3: Add the missing `redis` Service**

Append to the end of `k8s/base/redis-cluster.yaml`:

```yaml
---
# REDIS_HOST is "redis" in the ConfigMap, but until this Service existed that name resolved to
# nothing in-cluster. It was inert only because REDIS_MODE is "sentinel", so
# LettuceClientFactory takes the sentinel path and ignores the standalone host. The EKS
# overlays set REDIS_SENTINEL_MASTER: "" and rely on REDIS_HOST, so the standalone path IS
# live in production — and any base-shaped deployment that flips REDIS_MODE would fail on DNS
# resolution before NetworkPolicy ever entered the picture.
apiVersion: v1
kind: Service
metadata:
  name: redis
  namespace: recsys
spec:
  type: ClusterIP
  selector:
    app: redis
  ports:
    - name: redis
      port: 6379
      targetPort: 6379
```

- [ ] **Step 4: Add the four missing egress rule sets**

In `k8s/base/network-policy.yaml`, add to the `egress:` list of **each** of the four policies `recsys-api-gateway`, `recsys-catalog-serving`, `recsys-model-serving`, and `recsys-online-serving` — insert immediately before that policy's existing DNS rule:

```yaml
    # REDIS_MODE is "sentinel" in the base ConfigMap, so every Redis client discovers the
    # primary through the sentinels before it ever connects on 6379. Sentinel pods carry
    # app: redis-sentinel, a different label from the redis primary/replica pods, so the
    # 6379 rule above does not cover them.
    - to:
        - podSelector:
            matchLabels:
              app: redis-sentinel
      ports:
        - port: 26379
```

Then, in the `recsys-api-gateway` policy only, add these two rules in the same place:

```yaml
    # The LLM explanation proxy. LLM_SERVICE_URL / LLM_EXPLANATION_SERVICE_URL both point here.
    - to:
        - podSelector:
            matchLabels:
              app: ollama
      ports:
        - port: 11434
    # SERVICE_REGISTRY_ENABLED=true makes the gateway resolve upstreams from Redis. Added
    # unconditionally even though the flag defaults off: the registry falls back to the static
    # route address when Redis is unreachable, so a blocked connection degrades silently
    # rather than failing, and nobody would find this from the symptom.
    - to:
        - podSelector:
            matchLabels:
              app: redis
      ports:
        - port: 6379
```

And in the `recsys-online-serving` policy only:

```yaml
    # Outbox rows when MYSQL_ENABLED=true. Added unconditionally for the same reason as the
    # gateway's Redis rule — a policy that has to be edited in the same change as the flag is
    # a policy that will not be.
    - to:
        - podSelector:
            matchLabels:
              app: mysql
      ports:
        - port: 3306
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=NetworkPolicyEgressManifestTest
```

Expected: PASS.

- [ ] **Step 6: Verify the manifests still render**

```bash
kustomize build k8s/base > /dev/null && echo "BASE RENDER OK"
kustomize build k8s/eks > /dev/null && echo "EKS RENDER OK"
```

Expected: both print OK. If `kustomize` is not installed, use `kubectl kustomize` instead; if neither exists, skip this step and note it — the JUnit test does not depend on rendering.

- [ ] **Step 7: Wire the test into the PR gate**

In `pom.xml`, inside the `resilience` profile's `<includes>`, add immediately after the `**/metrics/ScrapeTargetManifestTest.java` line:

```xml
                <!-- The egress counterpart to ScrapeTargetManifestTest's ingress checks. The
                     NetworkPolicy is the L3/L4 ACL the whole backend trust model rests on
                     (20_AuthN_AuthZ), and its failure mode is silence: a dropped egress rule
                     looks like a dependency outage, not a policy bug. Pure file parsing. -->
                <include>**/k8s/NetworkPolicyEgressManifestTest.java</include>
                <include>**/k8s/UpstreamTest.java</include>
```

- [ ] **Step 8: Verify the gate profile runs both tests**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience 2>&1 | grep -E "NetworkPolicyEgressManifestTest|UpstreamTest|BUILD"
```

Expected: both class names appear in the run and `BUILD SUCCESS`.

- [ ] **Step 9: Commit**

```bash
git add src/test/java/com/recsys/infrastructure/k8s/NetworkPolicyEgressManifestTest.java \
        k8s/base/network-policy.yaml k8s/base/redis-cluster.yaml pom.xml
git commit -m "fix: permit the endpoints each service actually dials

Sentinel discovery on 26379, the gateway's LLM upstream and registry Redis
connection, and online serving's MySQL outbox all had no egress rule. Adds a
redis Service so REDIS_HOST resolves, and a conformance test deriving every
requirement from the ConfigMap."
```

---

### Task 3: Assertion — the sentinel pods are ingress-restricted

Task 2 granted egress *to* the sentinels. Nothing restricts what may reach them.

**Files:**
- Modify: `src/test/java/com/recsys/infrastructure/k8s/NetworkPolicyEgressManifestTest.java`
- Modify: `k8s/base/network-policy.yaml`

**Interfaces:**
- Consumes: `policyFor`, `restrictsEgress`, `ManifestDocuments.*` from Task 2.
- Produces: `private static boolean admitsIngress(Map<String,Object> policy, Map<String,Object> sourceLabels, int port)` — reused by Task 4.

- [ ] **Step 1: Write the failing test**

Add to `NetworkPolicyEgressManifestTest`:

```java
    /** Mirror of {@link #permitsEgress}: selector and port must sit in the same ingress rule. */
    static boolean admitsIngress(Map<String, Object> policy, Map<String, Object> sourceLabels, int port) {
        return listOf(mapAt(policy, "spec"), "ingress").stream().anyMatch(rule -> {
            boolean fromSource = listOf(rule, "from").stream()
                    .anyMatch(from -> selects(mapAt(from, "podSelector", "matchLabels"), sourceLabels));
            boolean onPort = listOf(rule, "ports").stream()
                    .anyMatch(p -> Integer.valueOf(port).equals(p.get("port")));
            return fromSource && onPort;
        });
    }

    /**
     * The sentinel pods matched no NetworkPolicy at all, which in Kubernetes means every source
     * is admitted by default — the one failure mode indistinguishable from working correctly.
     * Every other data-tier pod in this namespace is ingress-restricted; these were missed
     * because the redis policy selects app: redis and sentinels carry app: redis-sentinel.
     */
    @Test
    void sentinelPodsAreIngressRestricted() throws IOException {
        List<Map<String, Object>> docs = baseDocuments();

        Map<String, Object> policy = ofKind(docs, "NetworkPolicy").stream()
                .filter(p -> Map.of("app", "redis-sentinel").equals(mapAt(p, "spec", "podSelector", "matchLabels")))
                .findFirst()
                .orElse(null);

        assertThat(policy)
                .as("no NetworkPolicy selects app: redis-sentinel, so Kubernetes admits ingress "
                        + "from every pod in the cluster to port 26379 — sentinels can rewrite a "
                        + "client's view of which node is primary")
                .isNotNull();

        Set<String> notAdmitted = new TreeSet<>();
        List<String> expectedSources = List.of(
                "recsys-api-gateway", "recsys-catalog-serving",
                "recsys-model-serving", "recsys-online-serving");
        for (String source : expectedSources) {
            // Map.<String, Object>of — the inferred Map<String,String> does not match the
            // Map<String,Object> parameter.
            if (!admitsIngress(policy, Map.<String, Object>of("app", source), 26379)) {
                notAdmitted.add(source);
            }
        }
        // Sentinels gossip among themselves to agree on a primary; without this they cannot
        // reach quorum and never fail over.
        if (!admitsIngress(policy, Map.<String, Object>of("app", "redis-sentinel"), 26379)) {
            notAdmitted.add("redis-sentinel (sentinel-to-sentinel quorum)");
        }

        assertThat(notAdmitted)
                .as("the sentinel policy must admit 26379 from every workload granted egress to it "
                        + "and from the sentinels themselves; a policy that blocks one of these is "
                        + "worse than no policy, because it fails at startup instead of at review")
                .isEmpty();
    }
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=NetworkPolicyEgressManifestTest#sentinelPodsAreIngressRestricted
```

Expected: FAIL — "no NetworkPolicy selects app: redis-sentinel".

- [ ] **Step 3: Add the sentinel ingress policy**

Append to `k8s/base/network-policy.yaml`:

```yaml
---
# Redis Sentinel: ingress from the four workloads that discover Redis through it, plus the
# sentinels themselves. Until this existed the sentinel pods matched no policy, so Kubernetes
# admitted every source in the cluster — the redis policy above selects app: redis, and these
# pods carry app: redis-sentinel. A sentinel decides which node clients treat as primary, so
# an unrestricted one is a redirection primitive, not just an information leak.
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: redis-sentinel
  namespace: recsys
spec:
  podSelector:
    matchLabels:
      app: redis-sentinel
  policyTypes: [Ingress]
  ingress:
    - from:
        - podSelector:
            matchLabels:
              app: recsys-api-gateway
        - podSelector:
            matchLabels:
              app: recsys-catalog-serving
        - podSelector:
            matchLabels:
              app: recsys-model-serving
        - podSelector:
            matchLabels:
              app: recsys-online-serving
        # Sentinels gossip to agree on a primary; without this they never reach quorum.
        - podSelector:
            matchLabels:
              app: redis-sentinel
      ports:
        - port: 26379
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=NetworkPolicyEgressManifestTest
```

Expected: PASS, both tests.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/recsys/infrastructure/k8s/NetworkPolicyEgressManifestTest.java \
        k8s/base/network-policy.yaml
git commit -m "fix: restrict ingress to the Redis Sentinel pods

They matched no NetworkPolicy, so every pod in the cluster could reach 26379 --
and a sentinel controls which node clients treat as primary."
```

---

### Task 4: Assertion — egress and ingress agree

A NetworkPolicy is enforced at both ends. Task 2 gave the gateway egress to Redis; the `redis` policy still admits only the three serving pods, so the connection is dropped anyway.

**Files:**
- Modify: `src/test/java/com/recsys/infrastructure/k8s/NetworkPolicyEgressManifestTest.java`
- Modify: `k8s/base/network-policy.yaml`

**Interfaces:**
- Consumes: `OWNED_KEYS`, `destinationLabels`, `policyFor`, `restrictsEgress`, `admitsIngress`, `configMap`.
- Produces: nothing new.

- [ ] **Step 1: Write the failing test**

Add to `NetworkPolicyEgressManifestTest`:

```java
    /**
     * Both ends enforce independently, so a granted egress rule is worthless if the destination's
     * own policy does not admit the source. This is the half that is easy to forget, because the
     * egress side is the one you edit when you notice the connection failing.
     */
    @Test
    void everyPermittedEgressIsAdmittedByItsDestination() throws IOException {
        List<Map<String, Object>> docs = baseDocuments();
        Map<String, String> cfg = configMap(docs);
        List<Map<String, Object>> policies = ofKind(docs, "NetworkPolicy");

        Set<String> blocked = new TreeSet<>();
        for (Map.Entry<String, Set<String>> entry : OWNED_KEYS.entrySet()) {
            String workload = entry.getKey();
            Map<String, Object> sourcePolicy = policyFor(workload, docs);
            if (sourcePolicy == null || !restrictsEgress(sourcePolicy)) continue;
            Map<String, Object> sourceLabels = mapAt(sourcePolicy, "spec", "podSelector", "matchLabels");

            for (String key : entry.getValue()) {
                for (Upstream upstream : Upstream.parse(key, cfg)) {
                    Map<String, Object> destLabels = destinationLabels(upstream.host(), docs);

                    // Only destinations governed by a policy restrict ingress; ollama and mysql
                    // are deployed outside k8s/base and have none.
                    Map<String, Object> destPolicy = policies.stream()
                            .filter(p -> {
                                Map<String, Object> sel = mapAt(p, "spec", "podSelector", "matchLabels");
                                return sel != null && !sel.isEmpty()
                                        && destLabels.entrySet().containsAll(sel.entrySet())
                                        && stringListOf(mapAt(p, "spec"), "policyTypes").contains("Ingress");
                            })
                            .findFirst()
                            .orElse(null);
                    if (destPolicy == null) continue;

                    if (!admitsIngress(destPolicy, sourceLabels, upstream.port())) {
                        blocked.add(workload + " -> " + upstream.host() + ":" + upstream.port()
                                + " (egress granted, but NetworkPolicy " + nameOf(destPolicy)
                                + " does not admit " + sourceLabels + ")");
                    }
                }
            }
        }

        assertThat(blocked)
                .as("these connections are permitted on the way out and dropped on the way in. "
                        + "NetworkPolicy is enforced at both ends, so granting egress alone changes "
                        + "nothing observable — the connection still fails, and it fails identically "
                        + "to having no egress rule at all. Add the matching ingress rule")
                .isEmpty();
    }
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=NetworkPolicyEgressManifestTest#everyPermittedEgressIsAdmittedByItsDestination
```

Expected: FAIL, listing `recsys-api-gateway -> redis:6379 (egress granted, but NetworkPolicy redis does not admit {app=recsys-api-gateway})`.

- [ ] **Step 3: Admit the gateway to Redis**

In `k8s/base/network-policy.yaml`, in the `redis` policy's `ingress[0].from` list, add:

```yaml
        # The gateway reads svc:registry:* when SERVICE_REGISTRY_ENABLED=true. Its egress rule
        # alone would not be enough: both ends of a NetworkPolicy are enforced independently.
        - podSelector:
            matchLabels:
              app: recsys-api-gateway
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=NetworkPolicyEgressManifestTest
```

Expected: PASS, three tests.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/recsys/infrastructure/k8s/NetworkPolicyEgressManifestTest.java \
        k8s/base/network-policy.yaml
git commit -m "fix: admit the gateway to Redis on the ingress side too

Both ends of a NetworkPolicy are enforced independently, so the egress rule
added for the service registry was doing nothing on its own."
```

---

### Task 5: Assertions — unclaimed ConfigMap keys and unrestricted-egress workloads

Two assertions that pass against the current manifests. They are the drift guards: without them, the ownership map goes stale exactly the way the policy itself did.

**Files:**
- Modify: `src/test/java/com/recsys/infrastructure/k8s/NetworkPolicyEgressManifestTest.java`

**Interfaces:**
- Consumes: `OWNED_KEYS`, `configMap`, `policyFor`, `restrictsEgress`.
- Produces: nothing new.

- [ ] **Step 1: Write both tests**

Add to `NetworkPolicyEgressManifestTest`:

```java
    /** Key shapes that name a network destination. Anything matching these needs an owner. */
    private static final List<String> UPSTREAM_KEY_SUFFIXES =
            List.of("_SERVICE_URL", "_HOST", "_URL", "_NODES", "_BOOTSTRAP_SERVERS");

    /**
     * The drift catcher. Adding an upstream to the ConfigMap without claiming it here is exactly
     * how the policy fell six rules behind reality — every one of those gaps was a config change
     * that nobody paired with a policy change. Claiming a key is cheap; the claim is what makes
     * {@link #everyDeclaredUpstreamIsPermittedByEgress} require a rule for it.
     */
    @Test
    void everyConfigMapUpstreamKeyIsClaimed() throws IOException {
        Map<String, String> cfg = configMap(baseDocuments());

        Set<String> claimed = OWNED_KEYS.values().stream()
                .flatMap(Set::stream).collect(java.util.stream.Collectors.toSet());

        Set<String> unclaimed = new TreeSet<>();
        for (String key : cfg.keySet()) {
            boolean isUpstream = UPSTREAM_KEY_SUFFIXES.stream().anyMatch(key::endsWith);
            if (isUpstream && !claimed.contains(key)) unclaimed.add(key);
        }

        assertThat(unclaimed)
                .as("these ConfigMap keys name a network destination that no workload claims in "
                        + "OWNED_KEYS. Either add the key to the workload that dials it — which "
                        + "will then require a matching egress rule — or, if nothing dials it, "
                        + "delete it from the ConfigMap rather than leaving dead configuration "
                        + "that reads like a live dependency")
                .isEmpty();
    }

    /**
     * The relay declares policyTypes: [Ingress] deliberately: its whole job is reaching MySQL,
     * Kafka and (in EKS) ElastiCache, so an egress allow-list there would black-hole delivery the
     * moment it drifted. That makes it satisfy the egress assertion trivially, which is precisely
     * why it needs asserting — a later edit "completing" the policy with an Egress type would
     * pass every other test in this class while stopping outbox delivery in production.
     */
    @Test
    void theOutboxRelayEgressIsDeliberatelyUnrestricted() throws IOException {
        Map<String, Object> policy = policyFor("recsys-outbox-relay", baseDocuments());

        assertThat(policy).as("no NetworkPolicy named recsys-outbox-relay in k8s/base").isNotNull();
        assertThat(restrictsEgress(policy))
                .as("recsys-outbox-relay must NOT declare Egress in policyTypes. Its destinations "
                        + "(MySQL, Kafka, ElastiCache) are partly external and partly per-overlay, "
                        + "so an allow-list here silently stops outbox delivery. If you are adding "
                        + "Egress deliberately, extend OWNED_KEYS to cover every destination it "
                        + "dials and delete this assertion in the same commit")
                .isFalse();
    }
```

- [ ] **Step 2: Run both tests and confirm they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=NetworkPolicyEgressManifestTest
```

Expected: PASS, five tests.

- [ ] **Step 3: Prove the drift catcher actually catches drift**

A test that has never failed has not been shown to work. Temporarily add a line to the `data:` block of `k8s/base/configmap.yaml`:

```yaml
  TEMPORARY_PROBE_SERVICE_URL: "http://nowhere:1234"
```

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=NetworkPolicyEgressManifestTest#everyConfigMapUpstreamKeyIsClaimed
```

Expected: FAIL listing `TEMPORARY_PROBE_SERVICE_URL`. Then revert:

```bash
git checkout k8s/base/configmap.yaml
```

- [ ] **Step 4: Prove the relay assertion catches drift**

Temporarily change the relay policy's `policyTypes: [Ingress]` to `policyTypes: [Ingress, Egress]` in `k8s/base/network-policy.yaml`. Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=NetworkPolicyEgressManifestTest#theOutboxRelayEgressIsDeliberatelyUnrestricted
```

Expected: FAIL. Then revert:

```bash
git checkout k8s/base/network-policy.yaml
```

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/recsys/infrastructure/k8s/NetworkPolicyEgressManifestTest.java
git commit -m "test: assert upstream keys are claimed and relay egress stays open"
```

---

### Task 6: The EKS ElastiCache egress patch

In both EKS overlays, in-cluster Redis is scaled to zero and `REDIS_HOST` becomes an ElastiCache endpoint outside the cluster. A `podSelector` cannot match an external address, and no overlay patches the policy — so under an enforcing CNI every Redis call in EKS is dropped.

**Files:**
- Create: `k8s/eks-shared/network-policy-elasticache-patch.yaml`
- Modify: `k8s/eks-shared/kustomization.yaml`
- Modify: `src/test/java/com/recsys/infrastructure/k8s/NetworkPolicyEgressManifestTest.java`

**Interfaces:**
- Consumes: `ManifestDocuments.allIn`, `ofKind`, `listOf`, `mapAt`, `nameOf`.
- Produces: nothing new.

- [ ] **Step 1: Write the failing test**

Add to `NetworkPolicyEgressManifestTest`:

```java
    private static final Path EKS_SHARED = Path.of("k8s", "eks-shared");

    /**
     * Both EKS overlays scale in-cluster Redis to zero and point REDIS_HOST at an ElastiCache
     * endpoint outside the cluster. A podSelector cannot match an external address, and nothing
     * patched the policy, so every Redis call in EKS was unpermitted.
     *
     * <p>Deliberately a shape check, not a correctness check: the CIDR is an operator-supplied
     * REPLACE_ME value like the wafv2-acl-arn placeholder, so this asserts the rule exists on
     * both Redis ports and stops there. Whether the CIDR is the right one is a deployment-time
     * question no unit test can answer.
     */
    @Test
    void theEksOverlayPatchesEgressForExternalRedis() throws IOException {
        List<Map<String, Object>> docs = ManifestDocuments.allIn(EKS_SHARED);

        List<Map<String, Object>> patches = ofKind(docs, "NetworkPolicy");
        assertThat(patches)
                .as("k8s/eks-shared has no NetworkPolicy patch. Both region overlays replace "
                        + "in-cluster Redis with ElastiCache, which no podSelector in k8s/base can "
                        + "match, so the serving pods have no permitted route to Redis at all")
                .isNotEmpty();

        Set<String> missing = new TreeSet<>();
        for (String workload : List.of("recsys-api-gateway", "recsys-catalog-serving",
                "recsys-model-serving", "recsys-online-serving")) {
            Map<String, Object> patch = patches.stream()
                    .filter(p -> workload.equals(nameOf(p)))
                    .findFirst()
                    .orElse(null);
            if (patch == null) {
                missing.add(workload + " (no patch)");
                continue;
            }
            for (int port : new int[] {6379, 26379}) {
                boolean hasCidrRule = listOf(mapAt(patch, "spec"), "egress").stream()
                        .anyMatch(rule -> listOf(rule, "to").stream()
                                .anyMatch(to -> mapAt(to, "ipBlock") != null)
                                && listOf(rule, "ports").stream()
                                        .anyMatch(p -> Integer.valueOf(port).equals(p.get("port"))));
                if (!hasCidrRule) missing.add(workload + " (no ipBlock rule on " + port + ")");
            }
        }

        assertThat(missing)
                .as("each of these needs an ipBlock egress rule on both 6379 and 26379 in "
                        + "k8s/eks-shared/network-policy-elasticache-patch.yaml. ElastiCache is "
                        + "reached by IP, not by pod label")
                .isEmpty();
    }
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=NetworkPolicyEgressManifestTest#theEksOverlayPatchesEgressForExternalRedis
```

Expected: FAIL — "k8s/eks-shared has no NetworkPolicy patch".

- [ ] **Step 3: Create the patch**

Create `k8s/eks-shared/network-policy-elasticache-patch.yaml`:

```yaml
# ElastiCache egress for every EKS region.
#
# k8s/base's Redis egress rules select `podSelector: app: redis` and `app: redis-sentinel`. In
# EKS the in-cluster Redis StatefulSets are scaled to zero (see this component's `replicas:`
# block) and REDIS_HOST points at an ElastiCache endpoint OUTSIDE the cluster. A podSelector
# cannot match an external address, so without this patch the serving pods have no permitted
# route to Redis at all — every call is dropped by the CNI, if the CNI enforces policy.
#
# OPERATOR PREREQUISITE — the CIDR below is a placeholder, exactly like the REPLACE_ME in
# wafv2-acl-arn. Before deploying, replace 10.0.0.0/16 with the CIDR of the subnets your
# ElastiCache replication group's nodes sit in. Find it with:
#
#   aws elasticache describe-cache-subnet-groups \
#     --cache-subnet-group-name <group> \
#     --query 'CacheSubnetGroups[0].Subnets[].SubnetIdentifier' --output text
#   aws ec2 describe-subnets --subnet-ids <ids> --query 'Subnets[].CidrBlock' --output text
#
# Narrow it to the ElastiCache subnets rather than the whole VPC: a VPC-wide rule permits the
# serving pods to reach every workload in the VPC on 6379, which is most of what the policy
# exists to prevent.
#
# 26379 is included because REDIS_MODE may be "sentinel" in a region overlay. It is a no-op for
# a standalone ElastiCache endpoint and costs nothing to leave in.
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: recsys-api-gateway
  namespace: recsys
spec:
  podSelector:
    matchLabels:
      app: recsys-api-gateway
  policyTypes: [Ingress, Egress]
  egress:
    - to:
        - ipBlock:
            cidr: 10.0.0.0/16   # REPLACE_ME: ElastiCache subnet CIDR
      ports:
        - port: 6379
        - port: 26379
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: recsys-catalog-serving
  namespace: recsys
spec:
  podSelector:
    matchLabels:
      app: recsys-catalog-serving
  policyTypes: [Ingress, Egress]
  egress:
    - to:
        - ipBlock:
            cidr: 10.0.0.0/16   # REPLACE_ME: ElastiCache subnet CIDR
      ports:
        - port: 6379
        - port: 26379
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: recsys-model-serving
  namespace: recsys
spec:
  podSelector:
    matchLabels:
      app: recsys-model-serving
  policyTypes: [Ingress, Egress]
  egress:
    - to:
        - ipBlock:
            cidr: 10.0.0.0/16   # REPLACE_ME: ElastiCache subnet CIDR
      ports:
        - port: 6379
        - port: 26379
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: recsys-online-serving
  namespace: recsys
spec:
  podSelector:
    matchLabels:
      app: recsys-online-serving
  policyTypes: [Ingress, Egress]
  egress:
    - to:
        - ipBlock:
            cidr: 10.0.0.0/16   # REPLACE_ME: ElastiCache subnet CIDR
      ports:
        - port: 6379
        - port: 26379
```

- [ ] **Step 4: Wire it into the component**

In `k8s/eks-shared/kustomization.yaml`, add to the `patches:` list, after the `topology-aware-routing-patch.yaml` line:

```yaml
  - path: network-policy-elasticache-patch.yaml  # ipBlock egress to ElastiCache (REPLACE_ME CIDR)
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=NetworkPolicyEgressManifestTest
```

Expected: PASS, six tests.

- [ ] **Step 6: Verify the overlay renders and the patch merged**

```bash
kustomize build k8s/eks | grep -A6 'kind: NetworkPolicy' | grep -c 'ipBlock'
kustomize build k8s/eks-us-west-2 | grep -c 'ipBlock'
```

Expected: at least 4 in each. A strategic-merge patch appends to the `egress` list rather than replacing it — if the count is 4 but the base `podSelector` rules have disappeared from the render, the patch replaced instead of merged and needs `$patch: merge` semantics reviewed. Skip if `kustomize` is unavailable and note it in the PR.

- [ ] **Step 7: Commit**

```bash
git add k8s/eks-shared/network-policy-elasticache-patch.yaml k8s/eks-shared/kustomization.yaml \
        src/test/java/com/recsys/infrastructure/k8s/NetworkPolicyEgressManifestTest.java
git commit -m "fix: permit egress to ElastiCache in the EKS overlays

Both regions replace in-cluster Redis with an external endpoint that no
podSelector can match, and nothing patched the policy."
```

---

### Task 7: Document the ACL layer

`20_AuthN_AuthZ.md` already rests its central claim on this policy but never describes it.

**Files:**
- Modify: `docs/system_design/20_AuthN_AuthZ.md`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing.

- [ ] **Step 1: Add the ACL section**

In `docs/system_design/20_AuthN_AuthZ.md`, insert a new section between §7 ("Signed tokens that are not credentials") and §8 ("Testing"), renumbering §8 to §9 and updating the heading only — the section bodies do not change:

```markdown
## 8. The L3/L4 access-control list

Everything above is L7. The control that makes it *sufficient* is
[`network-policy.yaml`](../../k8s/base/network-policy.yaml): the backends authenticate nobody
because the policy is what proves the gateway is their only reachable caller.

Each of the four services declares `policyTypes: [Ingress, Egress]` and permits an explicit
destination set. The relay is the deliberate exception — `policyTypes: [Ingress]`, leaving egress
unrestricted, because its destinations (MySQL, Kafka, and in EKS ElastiCache) are partly external
and partly per-overlay, so an allow-list there would black-hole delivery the moment it drifted.

The egress half is the half that rots. Ingress rules are stated once and stay true; egress rules
encode the *addresses of dependencies*, and those move — a new upstream in the ConfigMap, a flag
that opens a connection, an overlay that relocates Redis outside the cluster. Six such gaps had
accumulated by 2026-08. `NetworkPolicyEgressManifestTest` is the response: it derives every
upstream address from `recsys-config` and requires a matching rule, so the two sets can no longer
diverge without failing a PR. Ownership is declared rather than derived — `recsys-config` is one
ConfigMap `envFrom`'d into all five workloads, so possession of an env var proves nothing about
who dials it.

Design: [NetworkPolicy egress conformance](../superpowers/specs/2026-08-05-networkpolicy-egress-conformance-design.md).
```

- [ ] **Step 2: Add two sharp edges**

Append to the "Sharp edges — notes" list in the same file, after item 6:

```markdown
7. **The NetworkPolicy is base-only.** No overlay patched it until the ElastiCache egress patch
   in `k8s/eks-shared`, yet both EKS overlays relocate Redis outside the cluster. An overlay that
   changes *where* a dependency lives changes it out from under the ACL, and nothing in `k8s/base`
   can notice — the conformance test reads base, so overlay coverage stops at asserting the patch
   exists.
8. **Enforcement is CNI-dependent.** EKS's default VPC CNI does not enforce NetworkPolicy unless
   policy support is explicitly enabled. "The NetworkPolicy protects the backends" is therefore a
   claim about cluster configuration, not about anything in this repo. If it is not enforced, §8's
   rules are documentation and the backends — which authenticate nobody — are reachable by any pod
   in the cluster.
```

- [ ] **Step 3: Verify the docs tests still pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=DocumentationIndexTest+DocumentedMechanismTest
```

Expected: PASS. `20_AuthN_AuthZ.md` is already linked from README, and `docs/superpowers/` is excluded from indexing.

- [ ] **Step 4: Commit**

```bash
git add docs/system_design/20_AuthN_AuthZ.md
git commit -m "docs: describe the L3/L4 ACL the trust model depends on"
```

---

### Task 8: Full verification and PR

**Files:**
- No source changes.

**Interfaces:**
- Consumes: everything above.
- Produces: a pull request.

- [ ] **Step 1: Run the PR gate profile in full**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
```

Expected: `BUILD SUCCESS`. This is the exact profile `.github/workflows/resilience-pr.yml` runs.

- [ ] **Step 2: Run the complete test suite**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test
```

Expected: `BUILD SUCCESS`. If anything unrelated to this change fails, check whether it also fails on `main` before investigating — record the answer either way rather than assuming.

- [ ] **Step 3: Review the whole diff**

```bash
git diff main...HEAD
```

Confirm: seven manifest changes present, no `REPLACE_ME` left un-commented, no debugging leftovers from the Task 5 revert steps (`git status` must be clean).

- [ ] **Step 4: Push and open the PR**

```bash
git push -u origin feat/networkpolicy-egress-conformance
gh pr create --title "fix: close six NetworkPolicy egress gaps and assert conformance" --body "$(cat <<'BODY'
`k8s/base/network-policy.yaml` is the repo's only L3/L4 ACL, and `20_AuthN_AuthZ` rests the
backend trust model on it — 6010/7010/8080 authenticate nobody *because* the policy proves the
gateway is their only reachable caller. The ingress half of that holds. The egress half had
drifted six rules behind what the services actually dial.

## Gaps closed

| # | Workload | Was unreachable | Symptom |
|---|---|---|---|
| 1 | all four | `redis-sentinel-*:26379` — `REDIS_MODE` is `sentinel` in base | startup failure that reads as a Redis outage |
| 2 | gateway | `ollama:11434` | LLM explanation route fails |
| 3 | gateway | `redis:6379` (service registry) | **silent** — falls back to static routes |
| 4 | online | `mysql:3306` (outbox) | fails on first append |
| 5 | gateway | `redis` policy admitted only the three serving pods | #3 stays broken with egress alone |
| 6 | all four | ElastiCache in both EKS overlays | no podSelector can match an external address |

Plus a `redis` Service — `REDIS_HOST: "redis"` resolved to nothing in-cluster, inert only because
sentinel mode ignores the standalone host — and an ingress policy for the sentinel pods, which
matched no policy at all and so admitted every pod in the cluster on 26379.

## Not drifting again

`NetworkPolicyEgressManifestTest` derives every upstream address from `recsys-config` and requires
a matching egress rule, with five further assertions: sentinel ingress is restricted, egress and
ingress agree at both ends, every upstream-shaped ConfigMap key is claimed by a workload, the
relay's egress stays deliberately unrestricted, and the EKS overlay carries its ipBlock patch.
Added to the `resilience` profile, so it blocks PRs.

## Operator action required before deploying to EKS

`k8s/eks-shared/network-policy-elasticache-patch.yaml` carries a placeholder CIDR
(`10.0.0.0/16`), the same shape as the `wafv2-acl-arn` `REPLACE_ME`. Replace it with your
ElastiCache subnet CIDR — the file documents the two `aws` commands that find it.

Design: `docs/superpowers/specs/2026-08-05-networkpolicy-egress-conformance-design.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

- [ ] **Step 5: Report the PR URL**

---

## Deferred

Recorded here so they are not silently dropped:

- **`GATEWAY_COGNITO_ISSUER` egress.** Blank in base, so it generates no requirement, and its key
  suffix (`_ISSUER`) is not in `UPSTREAM_KEY_SUFFIXES`. When a region overlay sets it, the gateway
  fetches JWKS over HTTPS to an AWS endpoint that no egress rule permits. Same class of bug as gap
  6, but overlay-only — it needs the overlay-rendering coverage this plan explicitly scoped out.
- **Redis ACL users.** `LettuceClientFactory` supports only `AUTH <password>` against the `default`
  user. Per-service ACL users with key-pattern and command restrictions are a separate project.
- **An L7 route ACL.** Control-plane writes sharing a privilege tier with catalog reads is sharp
  edge 1 in `20_AuthN_AuthZ`.
