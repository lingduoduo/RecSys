# Operator Token Manifest Conformance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fail the build when a workload reads `SHARD_ADMIN_TOKEN` but its Deployment does not inject it.

**Architecture:** One test class. It scans `src/main/java` for source files that call `System.getenv("SHARD_ADMIN_TOKEN")`, requires each to be classified against a Deployment name, and asserts that Deployment injects the token from Secret `recsys-online-admin` with `optional: true`. Requirements are derived from source rather than restated, so a third service picking up the operator tier fails the build until its manifest catches up.

**Tech Stack:** Java 17, JUnit 5, AssertJ, SnakeYAML, Maven.

## Global Constraints

- Build/test with JDK 17: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`. Newer JDKs fail a clean compile of two pre-existing files — a known pre-existing condition, not something you introduced.
- Design doc: `docs/superpowers/specs/2026-08-05-operator-token-manifest-conformance-design.md`. Read it before starting.
- The test must be added to the `resilience` profile in `pom.xml`, which is what the PR gate runs. Outside it, it gates nothing.
- The test lives in `com.recsys.infrastructure.k8s` because `ManifestDocuments` and its helpers are package-private.
- Scan for the `getenv` **call**, not the bare string `SHARD_ADMIN_TOKEN`. `AdminTokenGuard.java` names the variable in its javadoc while receiving the token as a constructor argument; matching the bare string would flag it as a reader needing a manifest, which is false.
- The test reads `k8s/base` only. Tests cannot render overlays, and the class comment must say so rather than implying coverage the test does not have.
- Never merge to `main` directly — this work ships as a PR.
- Branch: `test/operator-token-manifest-conformance` (already created; the spec is already committed on it).

---

### Task 1: Assert every reader of the operator token has a Deployment that supplies it

**Files:**
- Create: `src/test/java/com/recsys/infrastructure/k8s/OperatorTokenManifestTest.java`
- Modify: `pom.xml` (resilience profile includes)

**Interfaces:**
- Consumes: the existing package-private helpers in `com.recsys.infrastructure.k8s.ManifestDocuments` — `allIn(Path dir)` returning `List<Map<String, Object>>` of every YAML document in a directory, `ofKind(List<Map<String,Object>> docs, String kind)`, `nameOf(Map<String,Object> doc)`, `mapAt(Map<String,Object> doc, String... path)`, and `listOf(Map<String,Object> map, String key)`.
- Produces: nothing consumed by later work — this plan has one task.

Facts already verified against the repo, so you do not need to rediscover them:

- Exactly two files call `System.getenv("SHARD_ADMIN_TOKEN")` today: `src/main/java/com/recsys/api/online/OnlinePredictionServer.java` (twice) and `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java` (once).
- `src/main/java/com/recsys/application/auth/AdminTokenGuard.java` mentions the variable in a javadoc comment only.
- The Deployments are named `recsys-online-serving` and `recsys-api-gateway`.
- Both already carry the env block, and `optional: true` parses as a Java `Boolean`, not a `String`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/infrastructure/k8s/OperatorTokenManifestTest.java`:

```java
package com.recsys.infrastructure.k8s;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.recsys.infrastructure.k8s.ManifestDocuments.allIn;
import static com.recsys.infrastructure.k8s.ManifestDocuments.listOf;
import static com.recsys.infrastructure.k8s.ManifestDocuments.mapAt;
import static com.recsys.infrastructure.k8s.ManifestDocuments.nameOf;
import static com.recsys.infrastructure.k8s.ManifestDocuments.ofKind;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A workload that enforces the operator tier must be given the credential the tier runs on.
 *
 * <p>{@code SHARD_ADMIN_TOKEN} gates the control-plane routes — {@code /setembedding}, the model
 * version activate/rollback/preload endpoints, {@code /online/ops}, {@code POST /shards/topology}
 * and {@code GET /shards/shard}. Unset means the tier authorizes nobody, which is the right
 * fail-closed behaviour and a completely silent one: a service that starts reading the variable
 * without a matching manifest change comes up, logs one warning, and rejects every operator request
 * until somebody notices a rollback not working. Nothing else in the build would catch it.
 *
 * <p>The requirement is therefore <em>derived</em> from the source that reads the variable rather
 * than restated as a list of workloads — the same principle {@link NetworkPolicyEgressManifestTest}
 * applies to egress destinations. A test asserting "the gateway and online-serving inject the
 * token" would pin today's two instances and stay silent on the third, which is the case that
 * matters.
 *
 * <p>Only one direction is enforced: reader ⇒ manifest. A Deployment injecting a token nobody reads
 * is a stale line, not a broken control.
 *
 * <p><strong>Scope:</strong> this reads {@code k8s/base}. Tests cannot run {@code kubectl
 * kustomize}, so an overlay that patched the env block away would not be caught here — the same
 * boundary {@code CLAUDE.md} records for the NetworkPolicy conformance test.
 */
class OperatorTokenManifestTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");
    private static final Path BASE = Path.of("k8s", "base");

    private static final String ENV_VAR = "SHARD_ADMIN_TOKEN";
    private static final String SECRET_NAME = "recsys-online-admin";
    private static final String SECRET_KEY = "admin-token";

    /**
     * The {@code getenv} call, not the bare variable name. {@code AdminTokenGuard} documents the
     * variable in its javadoc while receiving the token as a constructor argument; matching the
     * name alone would demand a manifest for a file that reads nothing.
     */
    private static final String READ_MARKER = "getenv(\"" + ENV_VAR + "\")";

    /** Which Deployment supplies the token to each file that reads it. */
    private static final Map<String, String> READER_WORKLOADS = Map.of(
            "OnlinePredictionServer.java", "recsys-online-serving",
            "MicroserviceGatewayServer.java", "recsys-api-gateway");

    @Test
    void everyReaderOfTheOperatorTokenIsClassified() throws IOException {
        List<String> unclassified = new ArrayList<>();
        for (Path reader : filesReadingTheToken()) {
            if (!READER_WORKLOADS.containsKey(reader.getFileName().toString())) {
                unclassified.add(reader.toString());
            }
        }

        assertThat(unclassified)
                .describedAs("These files read %s but READER_WORKLOADS does not say which Deployment "
                        + "supplies it. Add the mapping and the manifest env block together — a "
                        + "service that enforces the operator tier without the credential rejects "
                        + "every operator request and only says so in one startup warning.", ENV_VAR)
                .isEmpty();
    }

    @Test
    void everyClassifiedWorkloadInjectsTheOperatorToken() throws IOException {
        List<Map<String, Object>> deployments = ofKind(allIn(BASE), "Deployment");
        Set<String> required = new LinkedHashSet<>(READER_WORKLOADS.values());

        List<String> problems = new ArrayList<>();
        for (String workload : required) {
            Map<String, Object> deployment = deployments.stream()
                    .filter(d -> workload.equals(nameOf(d)))
                    .findFirst()
                    .orElse(null);
            if (deployment == null) {
                problems.add(workload + ": no Deployment of that name in " + BASE);
                continue;
            }
            problems.addAll(describeTokenProblems(workload, deployment));
        }

        assertThat(problems)
                .describedAs("Workloads whose code reads %s must be given it from Secret %s/%s.",
                        ENV_VAR, SECRET_NAME, SECRET_KEY)
                .isEmpty();
    }

    /** @return one message per defect in this Deployment's injection of the token; empty when correct. */
    private static List<String> describeTokenProblems(String workload, Map<String, Object> deployment) {
        Map<String, Object> podSpec = mapAt(deployment, "spec", "template", "spec");
        for (Map<String, Object> container : listOf(podSpec, "containers")) {
            for (Map<String, Object> env : listOf(container, "env")) {
                if (!ENV_VAR.equals(env.get("name"))) {
                    continue;
                }
                Map<String, Object> ref = mapAt(env, "valueFrom", "secretKeyRef");
                if (ref == null) {
                    return List.of(workload + ": " + ENV_VAR + " is set, but not from a secretKeyRef");
                }
                List<String> defects = new ArrayList<>();
                if (!SECRET_NAME.equals(ref.get("name"))) {
                    defects.add(workload + ": secret is " + ref.get("name") + ", expected " + SECRET_NAME);
                }
                if (!SECRET_KEY.equals(ref.get("key"))) {
                    defects.add(workload + ": secret key is " + ref.get("key") + ", expected " + SECRET_KEY);
                }
                // Load-bearing: without optional, a cluster that has not been given the Secret
                // cannot schedule the pod at all, which turns a fail-closed tier into an outage.
                if (!Boolean.TRUE.equals(ref.get("optional"))) {
                    defects.add(workload + ": secretKeyRef must set optional: true, so a cluster "
                            + "without the Secret degrades to 403 on operator routes instead of "
                            + "failing to start the pod");
                }
                return defects;
            }
        }
        return List.of(workload + ": no " + ENV_VAR + " entry in any container's env");
    }

    private static List<Path> filesReadingTheToken() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            List<Path> readers = new ArrayList<>();
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (Files.readString(file).contains(READ_MARKER)) {
                    readers.add(file);
                }
            }
            return readers;
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it compiles and passes on the current tree**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=OperatorTokenManifestTest
```

Expected: PASS, 2 tests. Both manifests already carry the env block, so a green run here means the
test agrees with reality — it does **not** yet prove the test can fail. Step 3 proves that.

If it fails instead, do not weaken it: read the message. Either the scan found a reader the map does
not name (add it, with its Deployment), or a manifest is genuinely missing the block (fix the
manifest).

- [ ] **Step 3: Prove the test bites, in both directions**

A conformance test that cannot fail reads as coverage while providing none. Run both experiments and
record the exact failure output for your report.

Experiment A — a workload stops being given the token:

```bash
# Temporarily delete the SHARD_ADMIN_TOKEN env entry (name + valueFrom block, 6 lines)
# from k8s/base/api-gateway.yaml, then:
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=OperatorTokenManifestTest
```

Expected: `everyClassifiedWorkloadInjectsTheOperatorToken` fails with
`recsys-api-gateway: no SHARD_ADMIN_TOKEN entry in any container's env`. Restore the file with
`git checkout -- k8s/base/api-gateway.yaml`.

Experiment B — a third service starts reading the token:

```bash
# Temporarily add a line to src/main/java/com/recsys/api/serving/RecSysServer.java inside main:
#     String unusedOperatorToken = System.getenv("SHARD_ADMIN_TOKEN");
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=OperatorTokenManifestTest
```

Expected: `everyReaderOfTheOperatorTokenIsClassified` fails naming
`src/main/java/com/recsys/api/serving/RecSysServer.java`. Restore with
`git checkout -- src/main/java/com/recsys/api/serving/RecSysServer.java`.

Experiment C — `optional: true` is the assertion it claims to be:

```bash
# Temporarily change optional: true to optional: false in k8s/base/online-serving.yaml, then:
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=OperatorTokenManifestTest
```

Expected: failure naming `recsys-online-serving` and the `optional: true` requirement. Restore with
`git checkout -- k8s/base/online-serving.yaml`.

After all three, confirm the tree is clean:

```bash
git status --short
```

Expected: only your new test file (and, after Step 4, `pom.xml`) appear.

- [ ] **Step 4: Add the test to the PR gate**

In `pom.xml`, inside the `resilience` profile's `<includes>`, next to the other k8s manifest tests
(`**/k8s/NetworkPolicyEgressManifestTest.java`, `**/k8s/RedisAuthManifestTest.java`):

```xml
                <!-- The operator tier's credential and the code that reads it live in different
                     files with nothing else tying them together; a service that enforces the tier
                     without the Secret rejects every operator request and says so only in a
                     startup warning. Pure file parsing. -->
                <include>**/k8s/OperatorTokenManifestTest.java</include>
```

- [ ] **Step 5: Run the PR gate**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
```

Expected: BUILD SUCCESS, with the test count two higher than before.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/recsys/infrastructure/k8s/OperatorTokenManifestTest.java pom.xml
git commit -m "test: require every operator-tier workload to be given SHARD_ADMIN_TOKEN"
```

Do not push and do not open a PR; the controller handles that.

---

## Verification

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
```

Must pass. The claim worth stating in the PR body from evidence rather than assertion: the test
fails when a manifest drops the token, when a new reader appears, and when `optional: true` is
removed — all three demonstrated in Step 3.
