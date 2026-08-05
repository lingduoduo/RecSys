# Splunk and DNS Egress Conformance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permit the Splunk HEC egress every serving workload already dials, scope the DNS egress
rule to a destination, and widen `NetworkPolicyEgressManifestTest` so a Deployment-declared upstream
can never again be invisible to the conformance check.

**Architecture:** Two manifest edits to `k8s/base/network-policy.yaml` (applied to all four
Egress-restricted policies), and three test changes: a widened source of truth (ConfigMap ∪ base
Deployment inline `env:`), a coverage guard so every base Deployment is claimed, and a new assertion
that port-53 rules carry a destination. Everything is pure YAML file parsing — no cluster, no
containers, no Redis.

**Tech Stack:** Kubernetes NetworkPolicy (Kustomize), JUnit 5 + AssertJ + SnakeYAML, Maven.

**Spec:** [docs/superpowers/specs/2026-08-05-splunk-dns-egress-conformance-design.md](../specs/2026-08-05-splunk-dns-egress-conformance-design.md)

## Global Constraints

- **JDK 17.** Every Maven command must run as
  `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`. Newer JDKs fail a clean compile of two
  pre-existing files.
- **Run Maven from the repo root.** The test resolves `Path.of("k8s", "base")` relative to the
  working directory.
- **Branch:** `feat/splunk-dns-egress-conformance`, already created off `main` (`cf661ae`) with the
  spec committed at `dd75c8c`. Do not merge to `main` directly — this ships as a PR.
- **The test file is already in the `resilience` profile** (`pom.xml:365`). No `pom.xml` change is
  needed in this plan.
- **All four Egress-restricted policies get every manifest edit**: `recsys-api-gateway`,
  `recsys-catalog-serving`, `recsys-model-serving`, `recsys-online-serving`. `recsys-outbox-relay`
  is `policyTypes: [Ingress]` and is never edited.
- **Comment style:** every new rule carries a comment saying what breaks without it, matching the
  existing rules in the file.

---

### Task 1: Scope the DNS egress rule to kube-system

Today each of the four policies ends with a bare `- ports: [53/UDP, 53/TCP]` and no `to:`. A
NetworkPolicy rule with no `to[]` permits that port to **every** destination — a tunnel out of an
otherwise tight allow-list.

**Files:**
- Modify: `k8s/base/network-policy.yaml` (four rules: lines ~71-75, ~121-125, ~171-175, ~230-234)
- Test: `src/test/java/com/recsys/infrastructure/k8s/NetworkPolicyEgressManifestTest.java`

**Interfaces:**
- Consumes: existing helpers `baseDocuments()`, `restrictsEgress(policy)`, and the static imports
  `ofKind`, `mapAt`, `listOf`, `nameOf` — all already present in the file.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Write the failing test**

Add this method to `NetworkPolicyEgressManifestTest`, after
`theOutboxRelayEgressIsDeliberatelyUnrestricted()`:

```java
    /**
     * A NetworkPolicy egress rule with no `to[]` permits its ports to EVERY destination, in the
     * cluster or outside it. Four policies carried a bare `ports: [53]`, so the one channel every
     * workload needs was also the one channel that reached anywhere — a tunnel out of an allow-list
     * built to prevent exactly that.
     *
     * <p>This assertion exists because over-permissiveness is invisible to every other test here:
     * they all ask whether a destination is *reachable*, and a rule that reaches everything passes
     * all of them. Deleting the `to[]` to "simplify" the file would reopen the hole silently.
     */
    @Test
    void dnsEgressIsScopedToADestination() throws IOException {
        List<Map<String, Object>> docs = baseDocuments();

        Set<String> unscoped = new TreeSet<>();
        for (Map<String, Object> policy : ofKind(docs, "NetworkPolicy")) {
            if (!restrictsEgress(policy)) continue;
            for (Map<String, Object> rule : listOf(mapAt(policy, "spec"), "egress")) {
                boolean namesPort53 = listOf(rule, "ports").stream()
                        .anyMatch(p -> Integer.valueOf(53).equals(p.get("port")));
                if (namesPort53 && listOf(rule, "to").isEmpty()) unscoped.add(nameOf(policy));
            }
        }

        assertThat(unscoped)
                .as("these policies permit port 53 with no `to[]`, which in NetworkPolicy means "
                        + "every destination — so a workload whose other egress is confined to a "
                        + "hand-checked allow-list can still send arbitrary UDP or TCP anywhere on "
                        + "53. Scope the rule to `namespaceSelector: "
                        + "kubernetes.io/metadata.name=kube-system`")
                .isEmpty();
    }
```

- [ ] **Step 2: Run it to make sure it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=NetworkPolicyEgressManifestTest#dnsEgressIsScopedToADestination
```

Expected: FAIL. The assertion message lists all four policy names — `recsys-api-gateway`,
`recsys-catalog-serving`, `recsys-model-serving`, `recsys-online-serving`.

- [ ] **Step 3: Scope the four DNS rules**

In `k8s/base/network-policy.yaml`, replace **each** of the four occurrences of:

```yaml
    - ports:
        - port: 53
          protocol: UDP
        - port: 53
          protocol: TCP
```

with:

```yaml
    # Scoped to kube-system rather than left destination-free. A rule with no `to[]` permits
    # port 53 to ANY address, inside the cluster or out, which is a tunnel through an otherwise
    # tight allow-list. Namespace rather than `podSelector: k8s-app=kube-dns` on purpose:
    # NodeLocal DNSCache is host-networked and a podSelector may not match it on every CNI, and
    # the cost of over-tightening here is every DNS lookup dropped — a total outage on a control
    # nobody has confirmed the cluster even enforces.
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: kube-system
      ports:
        - port: 53
          protocol: UDP
        - port: 53
          protocol: TCP
```

There are exactly four. `recsys-outbox-relay` has no egress block and `redis` / `redis-sentinel`
are `policyTypes: [Ingress]` — none of them contains a DNS rule to edit.

- [ ] **Step 4: Run the whole test class to verify it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=NetworkPolicyEgressManifestTest
```

Expected: PASS, all tests. If `everyDeclaredUpstreamIsPermittedByEgress` fails here, the `to:`
indentation is wrong — `to:` and `ports:` must be siblings at the same depth inside one `-` list
entry.

- [ ] **Step 5: Commit**

```bash
git add k8s/base/network-policy.yaml src/test/java/com/recsys/infrastructure/k8s/NetworkPolicyEgressManifestTest.java
git commit -m "fix: scope DNS egress to kube-system instead of every destination"
```

---

### Task 2: Derive upstreams from Deployment env, and permit Splunk

All four serving Deployments carry `SPLUNK_HEC_URL: "http://splunk:8088/services/collector/event"`
as a literal env value. No egress rule permits 8088, and the test cannot see the key because it
derives only from `recsys-config`. Widening the derivation is what makes the missing rule fail the
build; adding the rule is what fixes it.

**Files:**
- Modify: `src/test/java/com/recsys/infrastructure/k8s/NetworkPolicyEgressManifestTest.java`
- Modify: `k8s/base/network-policy.yaml` (four egress blocks)

**Interfaces:**
- Consumes: `Upstream.parse(String key, Map<String,String> values)` → `List<Upstream>` with
  accessors `host()` and `port()` (unchanged, `src/test/java/com/recsys/infrastructure/k8s/Upstream.java`);
  `configMap(docs)` → `Map<String,String>`; `nameOf`, `ofKind`, `mapAt`, `listOf`.
- Produces, for Task 3:
  - `static Map<String, Map<String, String>> deploymentEnv(List<Map<String, Object>> docs)` —
    Deployment name → its inline env `name`/`value` pairs.
  - `record Dialed(Set<String> keys, Map<String, String> values)`.
  - `static Dialed dialedBy(String workload, List<Map<String, Object>> docs)`.
  - `static boolean isUpstreamKey(String key)`.

- [ ] **Step 1: Add the derivation helpers**

Add to `NetworkPolicyEgressManifestTest`, immediately after the existing `configMap(...)` method:

```java
    /**
     * Inline `env:` entries carrying a literal `value:`, keyed by Deployment name. This is the
     * other half of the source of truth. SPLUNK_HEC_URL is declared here rather than in
     * recsys-config, so a ConfigMap-only derivation could not see that all four serving workloads
     * dial splunk:8088 — and the Splunk appender is bounded, drop-on-full and at-most-once, so a
     * blocked connection loses events with no signal at all.
     *
     * <p>valueFrom entries (Secret refs, field refs) are skipped: they name no address.
     */
    static Map<String, Map<String, String>> deploymentEnv(List<Map<String, Object>> docs) {
        Map<String, Map<String, String>> byWorkload = new LinkedHashMap<>();
        for (Map<String, Object> deployment : ofKind(docs, "Deployment")) {
            Map<String, String> env = new LinkedHashMap<>();
            Map<String, Object> podSpec = mapAt(deployment, "spec", "template", "spec");
            for (Map<String, Object> container : listOf(podSpec, "containers")) {
                for (Map<String, Object> entry : listOf(container, "env")) {
                    Object name = entry.get("name");
                    Object value = entry.get("value");
                    if (name != null && value != null) {
                        env.put(String.valueOf(name), String.valueOf(value));
                    }
                }
            }
            byWorkload.put(nameOf(deployment), env);
        }
        return byWorkload;
    }

    /** Key shapes that name a network destination. */
    static boolean isUpstreamKey(String key) {
        return UPSTREAM_KEY_SUFFIXES.stream().anyMatch(key::endsWith);
    }

    /** The upstream keys a workload dials, and the values to resolve them against. */
    record Dialed(Set<String> keys, Map<String, String> values) {}

    /**
     * ConfigMap keys the workload claims in {@link #OWNED_KEYS}, unioned with every inline
     * Deployment env key shaped like an upstream.
     *
     * <p>The asymmetry is deliberate. recsys-config is one ConfigMap envFrom'd into five
     * workloads, so a ConfigMap key proves nothing about who dials it and ownership has to be
     * declared. A Deployment env var names its own dialer, so ownership is derived there and
     * cannot drift — which is why no OWNED_KEYS entry is required, or accepted, for one.
     *
     * <p>Blank values are skipped: an empty string names no destination. That is what keeps
     * ONLINE_EVENTS_SQS_QUEUE_URL: "" from demanding an egress rule to a queue nobody dials.
     *
     * <p>values starts as the ConfigMap so Upstream.parse can resolve REDIS_HOST against
     * REDIS_PORT, then Deployment env overrides it — the same precedence Kubernetes applies
     * between inline env and envFrom.
     */
    static Dialed dialedBy(String workload, List<Map<String, Object>> docs) {
        Map<String, String> values = new LinkedHashMap<>(configMap(docs));
        Set<String> keys = new TreeSet<>(OWNED_KEYS.getOrDefault(workload, Set.of()));
        deploymentEnv(docs).getOrDefault(workload, Map.of()).forEach((key, value) -> {
            if (isUpstreamKey(key) && !value.isBlank()) {
                keys.add(key);
                values.put(key, value);
            }
        });
        return new Dialed(keys, values);
    }
```

- [ ] **Step 2: Point the two egress assertions at the widened derivation**

In `everyDeclaredUpstreamIsPermittedByEgress()`, delete the line
`Map<String, String> cfg = configMap(docs);` and replace the loop body so it reads:

```java
        Set<String> unreachable = new TreeSet<>();
        for (String workload : OWNED_KEYS.keySet()) {
            Map<String, Object> policy = policyFor(workload, docs);
            assertThat(policy).as("no NetworkPolicy named %s in k8s/base", workload).isNotNull();
            if (!restrictsEgress(policy)) continue;

            Dialed dialed = dialedBy(workload, docs);
            for (String key : dialed.keys()) {
                for (Upstream upstream : Upstream.parse(key, dialed.values())) {
                    Map<String, Object> destLabels = destinationLabels(upstream.host(), docs);
                    if (!permitsEgress(policy, destLabels, upstream.port())) {
                        unreachable.add(workload + " -> " + key + " (" + upstream.host()
                                + ":" + upstream.port() + ", pods " + destLabels + ")");
                    }
                }
            }
        }
```

Extend that assertion's `.as(...)` message with one more sentence, after the MySQL clause and
before "Add a matching egress rule":

```java
                        + "and a blocked Splunk connection loses log events entirely, because the "
                        + "appender is bounded, drop-on-full and at-most-once by design — nothing "
                        + "retries and nothing errors. "
```

In `everyPermittedEgressIsAdmittedByItsDestination()`, delete
`Map<String, String> cfg = configMap(docs);` and make the same substitution: iterate
`OWNED_KEYS.keySet()`, and inside the loop replace the `for (String key : entry.getValue())` /
`Upstream.parse(key, cfg)` pair with:

```java
            Dialed dialed = dialedBy(workload, docs);
            for (String key : dialed.keys()) {
                for (Upstream upstream : Upstream.parse(key, dialed.values())) {
```

The rest of that method's body is unchanged.

- [ ] **Step 3: Register splunk as an externally-deployed host**

`destinationLabels` is strict and throws before the assertion can report anything, so this must
land in the same step. Change:

```java
    static final Set<String> EXTERNALLY_DEPLOYED = Set.of("ollama", "mysql", "kafka");
```

to:

```java
    static final Set<String> EXTERNALLY_DEPLOYED = Set.of("ollama", "mysql", "kafka", "splunk");
```

- [ ] **Step 4: Run the test to verify it fails for the right reason**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=NetworkPolicyEgressManifestTest#everyDeclaredUpstreamIsPermittedByEgress
```

Expected: FAIL, listing exactly four entries of the form
`recsys-api-gateway -> SPLUNK_HEC_URL (splunk:8088, pods {app=splunk})` — one per serving workload.
If it instead fails with "host 'splunk' resolves to no Service in k8s/base", Step 3 was skipped.

- [ ] **Step 5: Add the Splunk egress rule to all four policies**

In `k8s/base/network-policy.yaml`, insert this rule into each of the four egress blocks,
immediately **before** the DNS rule from Task 1:

```yaml
    # Structured log shipping (SPLUNK_HEC_TOKEN present). SPLUNK_HEC_URL is a literal env value
    # on each Deployment rather than a recsys-config key, which is why the ConfigMap-only
    # derivation never required this rule. No Splunk is deployed by these manifests — the
    # operator supplies a Service named `splunk`, see docs/runbooks/splunk-hec-logging.md.
    # Blocked, this loses log events silently: the appender is bounded, drop-on-full and
    # at-most-once, so nothing retries and nothing errors.
    - to:
        - podSelector:
            matchLabels:
              app: splunk
      ports:
        - port: 8088
```

- [ ] **Step 6: Run the whole class to verify it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=NetworkPolicyEgressManifestTest
```

Expected: PASS, all tests including `dnsEgressIsScopedToADestination` from Task 1.

- [ ] **Step 7: Commit**

```bash
git add k8s/base/network-policy.yaml src/test/java/com/recsys/infrastructure/k8s/NetworkPolicyEgressManifestTest.java
git commit -m "fix: permit Splunk HEC egress and derive upstreams from Deployment env"
```

---

### Task 3: Guard the Deployment half of the drift catcher

`everyDeclaredUpstreamIsPermittedByEgress` iterates `OWNED_KEYS.keySet()`. A new base Deployment
absent from that map would have its inline env upstreams checked by nothing — the same shape of
hole Task 2 just closed, one level up. Also update the class javadoc, which still describes a
ConfigMap-only derivation.

**Files:**
- Modify: `src/test/java/com/recsys/infrastructure/k8s/NetworkPolicyEgressManifestTest.java`

**Interfaces:**
- Consumes: `deploymentEnv(docs)` from Task 2.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Write the failing test**

Append to the end of `everyConfigMapUpstreamKeyIsClaimed()`, after the existing
`assertThat(cfg.keySet())...containsAll(claimed);` block:

```java
        // The Deployment half. everyDeclaredUpstreamIsPermittedByEgress iterates OWNED_KEYS, so a
        // base Deployment missing from that map has its inline env upstreams checked by nothing —
        // which is precisely how SPLUNK_HEC_URL went unnoticed, one level up. An entry with an
        // empty key set is a valid claim: it says "this workload dials nothing from the ConfigMap".
        Set<String> unmapped = new TreeSet<>(deploymentEnv(baseDocuments()).keySet());
        unmapped.removeAll(OWNED_KEYS.keySet());
        assertThat(unmapped)
                .as("these k8s/base Deployments have no OWNED_KEYS entry, so no egress assertion "
                        + "iterates them and any upstream in their inline env is unchecked. Add an "
                        + "entry — Set.of() if the workload dials nothing from recsys-config")
                .isEmpty();
```

- [ ] **Step 2: Run it to make sure it passes for the right reason**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=NetworkPolicyEgressManifestTest#everyConfigMapUpstreamKeyIsClaimed
```

Expected: PASS. All five base Deployments (`recsys-api-gateway`, `recsys-catalog-serving`,
`recsys-model-serving`, `recsys-online-serving`, `recsys-outbox-relay`) already have `OWNED_KEYS`
entries, so this guard starts green.

- [ ] **Step 3: Prove the guard actually catches drift**

Temporarily comment out the `"recsys-outbox-relay", Set.of(...)` entry in `OWNED_KEYS` and re-run
the command from Step 2. Expected: FAIL naming `recsys-outbox-relay`. **Restore the entry** and
re-run to confirm PASS. This step verifies the assertion is wired to something real; a guard that
starts green is worthless if it can never go red.

- [ ] **Step 4: Remove the now-duplicated suffix check**

`everyConfigMapUpstreamKeyIsClaimed` computes the suffix match inline, which `isUpstreamKey` from
Task 2 now duplicates. Replace:

```java
            boolean isUpstream = UPSTREAM_KEY_SUFFIXES.stream().anyMatch(key::endsWith);
            if (isUpstream && !claimed.contains(key)) unclaimed.add(key);
```

with:

```java
            if (isUpstreamKey(key) && !claimed.contains(key)) unclaimed.add(key);
```

- [ ] **Step 5: Update the class javadoc**

In the class-level javadoc, replace this sentence:

```
 * outage. So the addresses are <em>derived</em> from k8s/base/configmap.yaml rather than
 * restated here: change an upstream's address and the requirement follows it.
```

with:

```
 * outage. So the addresses are <em>derived</em> from the manifests rather than restated here:
 * change an upstream's address and the requirement follows it. The source is the union of
 * k8s/base/configmap.yaml and every base Deployment's inline env — SPLUNK_HEC_URL is declared
 * only in the latter, and a ConfigMap-only derivation could not see that all four serving
 * workloads dial splunk:8088.
```

And extend the following paragraph, after "...and {@link #everyConfigMapUpstreamKeyIsClaimed} is
what stops it going stale.", with:

```
 * A Deployment env var is the opposite case — it names its own dialer, so ownership there is
 * derived and needs no OWNED_KEYS entry.
```

- [ ] **Step 6: Run the whole class and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=NetworkPolicyEgressManifestTest
git add src/test/java/com/recsys/infrastructure/k8s/NetworkPolicyEgressManifestTest.java
git commit -m "test: require every base Deployment to be claimed in OWNED_KEYS"
```

Expected: PASS, all tests.

---

### Task 4: Documentation

**Files:**
- Modify: `docs/system_design/20_AuthN_AuthZ.md` (§8, and sharp edge 9)
- Modify: `docs/runbooks/splunk-hec-logging.md` (the "repoint `SPLUNK_HEC_URL`" section)

**Interfaces:**
- Consumes: nothing. Produces: nothing.

- [ ] **Step 1: Update §8 to describe the two new rules and the widened derivation**

In `docs/system_design/20_AuthN_AuthZ.md` §8, replace this sentence:

```
`NetworkPolicyEgressManifestTest` is the response: it derives every
upstream address from `recsys-config` and requires a matching rule, so the two sets can no longer
diverge without failing a PR.
```

with:

```
`NetworkPolicyEgressManifestTest` is the response: it derives every
upstream address from the union of `recsys-config` and every base Deployment's inline `env:`, and
requires a matching rule, so the two sets can no longer diverge without failing a PR. The
Deployment half was added after `SPLUNK_HEC_URL` — a literal env value on all four serving
Deployments, not a ConfigMap key — turned out to be dialing an unpermitted `splunk:8088`.
```

Then append this paragraph immediately before the `Design:` line:

```
Two rules in that file are worth reading directly. Egress to `app: splunk` on 8088 exists because
every serving workload ships structured logs there, and the appender's at-most-once, drop-on-full
delivery means a blocked connection loses events with no error anywhere. The DNS rule is scoped to
`namespaceSelector: kubernetes.io/metadata.name=kube-system` rather than left destination-free: a
NetworkPolicy rule with no `to[]` permits its port to *every* address, so the bare `ports: [53]`
this file used to carry was an unrestricted outbound channel from every workload. It stops at the
namespace rather than adding `podSelector: k8s-app=kube-dns` because NodeLocal DNSCache is
host-networked and a podSelector may not match it, and over-tightening costs every DNS lookup.
```

- [ ] **Step 2: Reword sharp edge 9**

Sharp edge 9 currently ends with a claim the test change falsifies. Replace:

```
   set), IRSA/STS, Cloud Map, and SQS all leave the cluster on 443 against no rule. On an
   enforcing CNI, turning on Cognito JWT auth would fail every verification — and §8's test would
   still be green, because a destination absent from the ConfigMap is a destination it cannot know
   about.
```

with:

```
   set), IRSA/STS, Cloud Map, SQS, and PostHog feature flags (`PostHogFeatureFlagProvider`, which
   POSTs `distinct_id` and `person_properties` to an external SaaS) all leave the cluster on 443
   against no rule. On an enforcing CNI, turning on Cognito JWT auth would fail every verification
   — and §8's test would still be green, because a destination named in neither `recsys-config`
   nor a base Deployment's inline `env:` is a destination it cannot know about. Overlay-patched
   addresses are outside its reach for the same reason.
```

- [ ] **Step 3: Add the egress step to the Splunk runbook**

In `docs/runbooks/splunk-hec-logging.md`, in the "**Before restarting, repoint `SPLUNK_HEC_URL` at
a real collector.**" list, append this paragraph immediately after the third bullet ("Any other
real HEC endpoint"):

```
Whichever option you take, the destination needs an egress rule. `k8s/base/network-policy.yaml`
permits `app: splunk` on 8088 from all four serving policies, which covers the first two options
unchanged. The third does not: changing `SPLUNK_HEC_URL` in `k8s/base` fails
`NetworkPolicyEgressManifestTest` until a matching rule exists, but changing it through an *overlay*
patch is invisible to that test — it reads `k8s/base` only. An overlay that repoints Splunk must add
the matching egress rule by hand, the same way `network-policy-elasticache-patch.yaml` does for
ElastiCache.
```

- [ ] **Step 4: Verify the docs build still passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=DocumentationIndexTest
```

Expected: PASS. Both edited files are already indexed; no new file is created, so no README entry
is needed.

- [ ] **Step 5: Commit**

```bash
git add docs/system_design/20_AuthN_AuthZ.md docs/runbooks/splunk-hec-logging.md
git commit -m "docs: describe the Splunk and DNS egress rules and the widened derivation"
```

---

### Task 5: Full gate run and PR

**Files:** none modified.

- [ ] **Step 1: Run the PR gate profile**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
```

Expected: PASS. This is the profile the PR gate runs; `NetworkPolicyEgressManifestTest` is already
in its `<includes>` (`pom.xml:365`).

- [ ] **Step 2: Review the rendered diff**

```bash
git diff main... -- k8s/base/network-policy.yaml
```

Confirm by eye: four `app: splunk` rules added, four DNS rules gained a `to:` block, and **no
existing rule was removed**. `spec.egress` has no patchMergeKey, so a dropped rule here is exactly
the failure mode the ElastiCache patch assertion exists to prevent.

- [ ] **Step 3: Open the PR**

```bash
git push -u origin feat/splunk-dns-egress-conformance
gh pr create --title "fix: permit Splunk HEC egress and scope DNS to kube-system" --body "$(cat <<'EOF'
Follow-up to #273. Two destinations sat outside the set that work aligned.

- **Splunk.** `SPLUNK_HEC_URL` is a literal Deployment env var on all four serving workloads, so
  no egress rule permitted `splunk:8088` — and the conformance test, deriving from `recsys-config`
  alone, structurally could not see it. The appender is at-most-once and drop-on-full, so a blocked
  connection loses log events with no error anywhere.
- **DNS.** Each policy carried a bare `ports: [53]` with no `to[]`, which permits port 53 to every
  destination in or out of the cluster.

The test's source of truth widens to the union of `recsys-config` and every base Deployment's
inline `env:`, so the *class* of gap closes rather than the one instance. Deployment env keys are
owned implicitly by the Deployment that declares them; ConfigMap keys still need an `OWNED_KEYS`
claim, because one ConfigMap is `envFrom`'d into five workloads.

Design: `docs/superpowers/specs/2026-08-05-splunk-dns-egress-conformance-design.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Out of scope

Recorded in the spec, not implemented here: unrestricted egress from Redis and Sentinel pods; the
`recsys-outbox-reconciliation` CronJob matching no policy at all; a namespace default-deny; PostHog
third-party egress (documented in Task 4 Step 2, not blocked); the absent caller-to-subject binding;
`LlmResponseCache` keying on the body hash alone; and overlay rendering in CI.
