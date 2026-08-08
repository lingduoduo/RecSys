# Reject Unsigned Pagination Cursors Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the signed-cursor mechanism the default behavior instead of something the deployed configuration opts out of.

**Architecture:** Flip `RECOMMENDATION_CURSOR_ACCEPT_LEGACY` from `true` to `false` in the code default and in `k8s/base/configmap.yaml`. Keep `decodeLegacy` and the environment variable as an escape hatch, and pin the new default with a test so it cannot drift back.

**Tech Stack:** Java 17, JUnit 5, Maven, Kustomize.

## Global Constraints

- Build/test with JDK 17: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`. Newer JDKs fail a clean compile of two pre-existing files — a known pre-existing condition.
- Design doc: `docs/superpowers/specs/2026-08-05-reject-unsigned-pagination-cursors-design.md`. Read it before starting.
- **Do not delete `decodeLegacy` or the environment variable.** They are the escape hatch; deleting the path belongs in a later cleanup once the flag has sat at `false` through a release.
- The default must be flipped **in code**, not only in the manifest, so safe-by-default holds in local runs, tests, and any future overlay rather than only where a ConfigMap says so.
- The test that pins the default must be in the `resilience` profile in `pom.xml`, which is what the PR gate runs.
- Never merge to `main` directly — this work ships as a PR.
- Branch: `fix/reject-unsigned-pagination-cursors` (already created; the spec is already committed on it).

---

### Task 1: Flip the default and pin it

**Files:**
- Modify: `src/main/java/com/recsys/application/pagination/RecommendationPaginationConfig.java:41-42`
- Modify: `k8s/base/configmap.yaml:56`
- Test: `src/test/java/com/recsys/application/pagination/RecommendationPaginationConfigTest.java:34-42`
- Modify: `pom.xml`
- Modify: `docs/system_design/19_Pagination.md`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: nothing — this plan has one task.

Facts already verified against the repo, so you need not rediscover them:

- `RecommendationPaginationConfig.fromEnvironment` reads the flag via
  `strictBoolean(env, "RECOMMENDATION_CURSOR_ACCEPT_LEGACY", true)` — the `true` is the default to flip.
- `k8s/base/configmap.yaml:56` sets `RECOMMENDATION_CURSOR_ACCEPT_LEGACY: "true"`.
- `RecommendationPaginationConfigTest` has two assertions on `acceptLegacy()`. `readsValidatedConfiguration` sets the variable to `"true"` explicitly, so **its assertion stays as it is**. `defaultsOptionalEnvironmentValues` relies on the default, so **that one flips** — it is already the test that pins the default, and amending it is better than adding a second test asserting the same thing.
- `RecommendationPaginationConfigTest` is **not** currently in the `resilience` profile.
- Nothing in the codebase issues a legacy cursor: `LEGACY_PREFIX` appears only inside `decodeLegacy`.

- [ ] **Step 1: Flip the assertion that pins the default**

In `src/test/java/com/recsys/application/pagination/RecommendationPaginationConfigTest.java`, in
`defaultsOptionalEnvironmentValues`, replace

```java
        assertTrue(config.acceptLegacy());
```

with

```java
        // Unsigned "v2:" cursors are refused unless an operator opts back in. decodeLegacy checks
        // no signature, no expiry, no userId binding and no query fingerprint, so a permissive
        // default makes the signed mechanism bypassable — which is exactly how it reached
        // production config unnoticed. Pinning the default here is what stops it drifting back.
        assertFalse(config.acceptLegacy());
```

`assertFalse` is already imported in this file. Leave `readsValidatedConfiguration` untouched: it
sets the variable explicitly, so it proves the opt-in still works and must keep asserting `true`.

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=RecommendationPaginationConfigTest
```

Expected: FAIL on `defaultsOptionalEnvironmentValues` — the default is still `true`.

- [ ] **Step 3: Flip the code default**

In `src/main/java/com/recsys/application/pagination/RecommendationPaginationConfig.java`:

```java
        // Default false: an unsigned legacy cursor is accepted with no signature, no expiry, no
        // userId binding and no query-fingerprint check. Signed cursors shipped 2026-07-27 and
        // expire in 900s, so nothing a live client holds can be legacy. The variable remains as an
        // escape hatch — see docs/system_design/19_Pagination.md.
        boolean acceptLegacy = strictBoolean(env,
                "RECOMMENDATION_CURSOR_ACCEPT_LEGACY", false);
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='RecommendationPaginationConfigTest,RecommendationCursorCodecTest,CursorPaginationServiceTest,RecommendationPaginationCoordinatorTest,CrossPathConsistencyTest'
```

Expected: PASS. `CrossPathConsistencyTest` constructs its own fixtures with an explicit
`acceptLegacy` value, so it is unaffected by the default and proves both flag states still behave.
If anything else fails, it was relying on the permissive default — report which, and do not change
the default back.

- [ ] **Step 5: Flip the manifest**

In `k8s/base/configmap.yaml`, line 56:

```yaml
  # Unsigned "v2:" cursors are refused. Set to "true" only to re-admit cursors issued before
  # 2026-07-27; the legacy path validates no signature, expiry, user binding or query fingerprint.
  RECOMMENDATION_CURSOR_ACCEPT_LEGACY: "false"
```

Then confirm the overlays still build:

```bash
kubectl kustomize k8s/base > /dev/null && echo "base OK"
kubectl kustomize k8s/eks > /dev/null && echo "us-east-1 OK"
kubectl kustomize k8s/eks-us-west-2 > /dev/null && echo "us-west-2 OK"
```

Expected: all three print OK. If `kubectl` is unavailable, say so in your report rather than
skipping the check silently.

- [ ] **Step 6: Add the test to the PR gate**

In `pom.xml`, inside the `resilience` profile's `<includes>`:

```xml
                <!-- Pins RECOMMENDATION_CURSOR_ACCEPT_LEGACY=false. The legacy cursor path
                     validates no signature, expiry, user binding or query fingerprint, and a
                     permissive default is how it reached production config unnoticed. -->
                <include>**/pagination/RecommendationPaginationConfigTest.java</include>
```

- [ ] **Step 7: Document it**

In `docs/system_design/19_Pagination.md`, add a short subsection recording:

- Unsigned `v2:` cursors are refused by default; `RECOMMENDATION_CURSOR_ACCEPT_LEGACY` re-admits
  them and exists only as an escape hatch, not a supported mode.
- What the legacy path skips: signature, expiry, `userId` binding, query fingerprint. Name all four.
- Why flipping was safe: signed cursors shipped 2026-07-27, a signed cursor expires after 900
  seconds, and nothing in the codebase issues a legacy cursor — so the only holders are clients of
  builds more than nine days old. The change is empty in practice by elapsed time, not by
  construction; a caller holding a pre-2026-07-27 cursor now receives a rejection.
- That `decodeLegacy` remaining in the codebase means the bypass is one configuration flip away, and
  that removing it is a later cleanup.

Verify against the code before writing — do not restate this list without checking `decodeLegacy`
and `decodeSigned` yourself.

- [ ] **Step 8: Run the PR gate and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
git add src/main/java/com/recsys/application/pagination/RecommendationPaginationConfig.java \
        src/test/java/com/recsys/application/pagination/RecommendationPaginationConfigTest.java \
        k8s/base/configmap.yaml pom.xml docs/system_design/19_Pagination.md
git commit -m "fix: refuse unsigned pagination cursors by default"
```

Do not push and do not open a PR; the controller handles that.

---

## Verification

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
```

Must pass. The claim worth stating in the PR body from evidence rather than assertion: the flip is
pinned by a test that fails if the default drifts back, and `readsValidatedConfiguration` still
proves the opt-in works.
