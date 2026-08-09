# Gateway base exposure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop `k8s/base` from exposing an anonymous gateway to the internet, and make that pairing fail the build if it is ever reintroduced.

**Architecture:** Turn the base gateway Service into a `ClusterIP` and move its eleven AWS load-balancer annotations out; add a conformance test asserting the anonymous/exposure invariant in base and the coupling in the overlays. No Java source changes.

**Tech Stack:** Kubernetes, Kustomize, Java 17, JUnit 5 + AssertJ, SnakeYAML.

## Global Constraints

- **JDK 17.** Every Maven command: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`, run from the repo root — manifest tests resolve `Path.of("k8s", "base")` relative to the working directory.
- **No Java source changes.** Nothing under `src/main/java` may be touched.
- **Do not flip `GATEWAY_ALLOW_ANONYMOUS` in `k8s/base`.** `GatewayAuthenticator.fromEnvironment` refuses to start without `GATEWAY_API_KEYS` or a Cognito issuer, and base defines neither, so base would stop booting. The fix removes the exposure, not the opt-in.
- **Do not touch the EKS overlays' auth settings.** `k8s/eks-shared/configmap-patch.yaml:26` already sets `GATEWAY_ALLOW_ANONYMOUS: "false"` and the overlays already patch the Service to `ClusterIP`.
- **The new test must be non-docker and added to the `resilience` profile** `<includes>` in `pom.xml` — that profile is the PR gate.
- Do not apply anything to a cluster; `kubectl kustomize` renders only.
- Never merge to `main` directly — this ships as a PR.
- Branch: `fix/gateway-base-exposure`, already created off `main` with the design committed.

## Facts established before this plan — do not rediscover

- The gateway Service is `k8s/base/api-gateway.yaml`, `kind: Service`, `metadata.name: recsys-api-gateway`, starting at line 135. It carries **eleven** `service.beta.kubernetes.io/aws-load-balancer-*` annotations beginning at line 145, and `spec.type: LoadBalancer` at line 157.
- Its `metadata.labels.app` is load-bearing for `ServiceMonitor.spec.selector` — the comment at lines 140-141 says so. **Do not remove the labels block.**
- `k8s/base/configmap.yaml:37` sets `GATEWAY_ALLOW_ANONYMOUS: "true"`.
- The rendered `k8s/eks` gateway Service is already `ClusterIP`; the rendered `k8s/base` one is `LoadBalancer`.
- `ManifestDocuments` exposes exactly `allIn`, `ofKind`, `mapAt`, `listOf`, `stringListOf` and `nameOf`. Do not add a helper.

---

### Task 1: Make base ClusterIP, and pin the invariant

**Files:**
- Modify: `k8s/base/api-gateway.yaml` (the Service at lines 135-168)
- Create: `src/test/java/com/recsys/infrastructure/k8s/GatewayExposureManifestTest.java`
- Modify: `pom.xml` (resilience profile `<includes>`)

**Interfaces:** consumes nothing; produces nothing later tasks depend on.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/infrastructure/k8s/GatewayExposureManifestTest.java`:

```java
package com.recsys.infrastructure.k8s;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An anonymous gateway must not be reachable from outside the cluster.
 *
 * <p>{@code k8s/base} paired {@code GATEWAY_ALLOW_ANONYMOUS: "true"} with a {@code LoadBalancer}
 * Service annotated {@code aws-load-balancer-scheme: internet-facing}, while the EKS overlays did
 * the opposite — {@code ClusterIP} and {@code "false"}. The hardened configuration was
 * cluster-internal and the wide-open one was the only thing on the internet.
 *
 * <p>Worse than a missing check: {@code GatewayAuthenticator} short-circuits when authentication is
 * disabled, so the {@code PROTECTED_PREFIXES} never-public guard is never consulted either. The
 * routes that declare themselves protected were as open as the rest. Nothing in the repo read
 * {@code GATEWAY_ALLOW_ANONYMOUS} at all.
 *
 * <p>Base keeps the opt-in deliberately — {@code GatewayAuthenticator.fromEnvironment} refuses to
 * start without a credential source, and base has none, so flipping the flag would stop base
 * booting. What base gives up is the exposure.
 *
 * <p><strong>Scope, stated rather than implied.</strong> This reads files; it does not render
 * kustomizations, so the overlay assertion is a coupling between texts and cannot see a patch that
 * fails to apply. Both of this week's overlay defects reached {@code main} through exactly that
 * hole. A test that renders each overlay would subsume this one and needs the {@code kustomize}
 * binary on CI.
 */
class GatewayExposureManifestTest {

    private static final Path BASE = Path.of("k8s", "base");
    private static final String GATEWAY_SERVICE = "recsys-api-gateway";
    private static final String ANONYMOUS_KEY = "GATEWAY_ALLOW_ANONYMOUS";
    private static final String SCHEME_ANNOTATION =
            "service.beta.kubernetes.io/aws-load-balancer-scheme";

    /** Service types that publish a Service outside the cluster. */
    private static final List<String> EXTERNAL_TYPES = List.of("LoadBalancer", "NodePort");

    private static final List<Path> OVERLAYS =
            List.of(Path.of("k8s", "eks"), Path.of("k8s", "eks-us-west-2"),
                    Path.of("k8s", "eks-shared"));

    @Test
    void anAnonymousGatewayIsNotExposedOutsideTheCluster() throws IOException {
        List<Map<String, Object>> docs = ManifestDocuments.allIn(BASE);

        String anonymous = anonymousSetting(docs);
        assertThat(anonymous)
                .as("no ConfigMap in %s defines %s — the scan found nothing to check",
                        BASE, ANONYMOUS_KEY)
                .isNotNull();

        Map<String, Object> service = gatewayService(docs);
        assertThat(service)
                .as("no Service named %s in %s — the scan found nothing to check",
                        GATEWAY_SERVICE, BASE)
                .isNotNull();

        if (!"true".equalsIgnoreCase(anonymous)) {
            return; // base authenticates; exposure is then a separate decision
        }

        List<String> problems = new ArrayList<>();
        Map<String, Object> spec = ManifestDocuments.mapAt(service, "spec");
        Object type = spec == null ? null : spec.get("type");
        if (type != null && EXTERNAL_TYPES.contains(String.valueOf(type))) {
            problems.add("Service type is " + type
                    + ", which publishes the gateway outside the cluster");
        }

        Map<String, Object> annotations = ManifestDocuments.mapAt(service, "metadata", "annotations");
        Object scheme = annotations == null ? null : annotations.get(SCHEME_ANNOTATION);
        if (scheme != null) {
            problems.add(SCHEME_ANNOTATION + " is set to " + scheme);
        }

        assertThat(problems)
                .as("%s sets %s=true, so its gateway Service must stay cluster-internal. "
                        + "Flipping the flag instead is not the fix: fromEnvironment refuses to "
                        + "start without a credential source, which base does not have",
                        BASE, ANONYMOUS_KEY)
                .isEmpty();
    }

    /**
     * The overlays are where internet-facing exposure belongs, and they must authenticate. Asserted
     * over file text because this test does not render — see the class comment.
     */
    @Test
    void anyOverlayThatExposesTheGatewayAlsoRequiresAuthentication() throws IOException {
        List<String> problems = new ArrayList<>();
        boolean scanned = false;

        for (Path overlay : OVERLAYS) {
            if (!Files.isDirectory(overlay)) {
                continue;
            }
            StringBuilder combined = new StringBuilder();
            for (Path file : Files.list(overlay).sorted().toList()) {
                if (file.toString().endsWith(".yaml")) {
                    combined.append(Files.readString(file)).append('\n');
                }
            }
            scanned = true;
            String text = combined.toString();
            boolean exposes = text.contains(SCHEME_ANNOTATION + ": internet-facing")
                    || text.contains("type: LoadBalancer")
                    || text.contains("type: NodePort");
            boolean authenticates = text.contains(ANONYMOUS_KEY + ": \"false\"");

            if (exposes && !authenticates) {
                problems.add(overlay + " exposes a Service outside the cluster without setting "
                        + ANONYMOUS_KEY + "=\"false\"");
            }
        }

        assertThat(scanned).as("no overlay directory was scanned").isTrue();
        assertThat(problems).isEmpty();
    }

    private static String anonymousSetting(List<Map<String, Object>> docs) {
        for (Map<String, Object> doc : ManifestDocuments.ofKind(docs, "ConfigMap")) {
            Map<String, Object> data = ManifestDocuments.mapAt(doc, "data");
            Object value = data == null ? null : data.get(ANONYMOUS_KEY);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static Map<String, Object> gatewayService(List<Map<String, Object>> docs) {
        for (Map<String, Object> doc : ManifestDocuments.ofKind(docs, "Service")) {
            if (GATEWAY_SERVICE.equals(ManifestDocuments.nameOf(doc))) {
                return doc;
            }
        }
        return null;
    }
}
```

- [ ] **Step 2: Run it and confirm it fails for the right reason**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=GatewayExposureManifestTest
```

Expected: `anAnonymousGatewayIsNotExposedOutsideTheCluster` **FAILS**, naming both the `LoadBalancer` type and the `internet-facing` annotation. If it passes, the scan is not finding the Service or the ConfigMap key — fix that before continuing, because a scan that sees nothing passes vacuously.

- [ ] **Step 3: Make the base Service cluster-internal**

In `k8s/base/api-gateway.yaml`, in the Service at line 135:

- Delete all **eleven** `service.beta.kubernetes.io/aws-load-balancer-*` annotation lines, and the now-empty `annotations:` key with them.
- Change `spec.type: LoadBalancer` to `spec.type: ClusterIP`.
- **Keep `metadata.labels.app: recsys-api-gateway` and the comment above it.** `ServiceMonitor.spec.selector` matches those labels; removing them silently stops Prometheus scraping the gateway.

Add this comment immediately above `spec:`:

```yaml
# ClusterIP, deliberately. k8s/base sets GATEWAY_ALLOW_ANONYMOUS=true so the gateway authenticates
# nobody — which is fine for development and is why base keeps the opt-in, since
# GatewayAuthenticator.fromEnvironment refuses to start without a credential source that base does
# not have. What must not follow is exposure: this Service used to be a LoadBalancer annotated
# internet-facing, which put an unauthenticated gateway on the internet, and because the
# authenticator short-circuits when disabled, PROTECTED_PREFIXES was not consulted either.
# Reach it locally with `kubectl port-forward svc/recsys-api-gateway 8010:80 -n recsys`.
# Internet-facing exposure belongs in an overlay that also sets GATEWAY_ALLOW_ANONYMOUS=false;
# GatewayExposureManifestTest fails the build if the two are ever paired again.
```

- [ ] **Step 4: Confirm the test now passes and the renders are unchanged elsewhere**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=GatewayExposureManifestTest
kubectl kustomize k8s/base | grep -A 2 "name: recsys-api-gateway" | grep -c "type: ClusterIP" || true
for o in k8s/base k8s/eks k8s/eks-us-west-2; do kubectl kustomize $o > /dev/null && echo "$o OK"; done
```

Expected: 2 tests pass; all three overlays render; the base gateway Service is `ClusterIP`.

- [ ] **Step 5: Confirm the EKS gateway Service is still ClusterIP**

```bash
kubectl kustomize k8s/eks | awk '/name: recsys-api-gateway$/,/^---/' | grep "type:" | head -2
```

Expected: `ClusterIP`. The overlays patched a `LoadBalancer` to `ClusterIP`; a strategic-merge patch that now targets an already-`ClusterIP` Service is a no-op, not an error — but confirm rather than assume, since a JSON-pointer patch would behave differently.

- [ ] **Step 6: Prove both assertions bite**

Each probe must be run, must fail, and must be reverted by naming its exact path. **Commit first** — a `git checkout --` on a single path has already destroyed uncommitted work in this repo.

1. Restore `spec.type: LoadBalancer` in `k8s/base/api-gateway.yaml`. Expected: `anAnonymousGatewayIsNotExposedOutsideTheCluster` fails naming the type.
2. Add `GATEWAY_ALLOW_ANONYMOUS: "true"` to `k8s/eks-shared/configmap-patch.yaml` (replacing the `"false"`). Expected: `anyOverlayThatExposesTheGatewayAlsoRequiresAuthentication` fails naming `k8s/eks-shared`.

- [ ] **Step 7: Add to the PR gate**

In `pom.xml`, in the `resilience` profile's `<includes>`, beside the other `**/k8s/*ManifestTest` entries:

```xml
                <include>**/k8s/GatewayExposureManifestTest.java</include>
```

- [ ] **Step 8: Run the gate and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
```

Expected: BUILD SUCCESS, count up by 2 from 618.

```bash
git add k8s/base/api-gateway.yaml \
        src/test/java/com/recsys/infrastructure/k8s/GatewayExposureManifestTest.java pom.xml
git commit -m "fix: stop k8s/base exposing an anonymous gateway to the internet"
```

---

### Task 2: Documentation

**Files:**
- Modify: `docs/system_design/22_Data_Leakage_Posture.md`
- Modify: `docs/runbooks/gateway-auth.md`

**Interfaces:** consumes nothing; produces nothing.

- [ ] **Step 1: Update the posture document**

`22_Data_Leakage_Posture.md` records gateway authentication as off in base beside an internet-facing NLB — the audit's headline finding. Read the entry as written and update it to the new state: base still anonymous, no longer exposed, with the pairing now asserted by `GatewayExposureManifestTest`. Keep the `PROTECTED_PREFIXES` consequence, since that is what made the finding severe, and keep the note that no test read the value before this one.

Do not renumber any `##` heading and do not add a document.

- [ ] **Step 2: Update the gateway-auth runbook**

In `docs/runbooks/gateway-auth.md`, add: how to reach the gateway under `k8s/base` now that the Service is `ClusterIP` (`kubectl port-forward svc/recsys-api-gateway 8010:80 -n recsys`); that base is anonymous by design while both EKS overlays set `GATEWAY_ALLOW_ANONYMOUS=false` and inject `GATEWAY_API_KEYS`; and that adding internet-facing exposure to any overlay requires setting the flag false in the same change or the build fails.

- [ ] **Step 3: Verify and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=DocumentationIndexTest
```

Expected: BUILD SUCCESS.

```bash
git add docs/system_design/22_Data_Leakage_Posture.md docs/runbooks/gateway-auth.md
git commit -m "docs: record that base is anonymous but no longer exposed"
```
