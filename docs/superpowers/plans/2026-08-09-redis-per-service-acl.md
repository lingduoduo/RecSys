# Redis per-service ACL users Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give each Redis-using workload its own ACL user, scoped to the keys it actually writes, instead of five workloads sharing the full-keyspace `default` credential.

**Architecture:** A committed ACL template with password placeholders, mounted into the Redis primary and replica from `recsys-secrets` via `--aclfile`; `REDIS_USERNAME` wired per Deployment; and a conformance test pinning each user's key patterns to the prefixes that service's code actually uses. No Java changes — `LettuceClientFactory` already reads `REDIS_USERNAME`.

**Tech Stack:** Redis 7 ACLs (`%R~` / `%W~` selectors), Kubernetes, Kustomize, Java 17, JUnit 5 + AssertJ, SnakeYAML.

## Global Constraints

- **JDK 17.** Every Maven command: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`, run from the repo root — manifest tests resolve `Path.of("k8s", "base")` relative to the working directory.
- **No Java source changes.** `LettuceClientFactory:120` and `:188` already read `REDIS_USERNAME`. Nothing under `src/main/java` may be touched.
- **`user default` MUST appear in the ACL file**, as `user default on >__REDIS_PASSWORD__ ~* &* +@all`. This was inverted in an earlier draft. An aclfile that omits it does **not** leave `requirepass` governing default — `ACLLoadFromFile` rebuilds default as `on nopass ~* &* +@all`, leaving Redis unauthenticated, breaking replication and Flink, and keeping both `redis-cli ping` probes green throughout. Measured on a real server. `default` must also keep full access: the Flink job, deployed by nothing in this repo, writes `u2vEmb:*` and `topk:*` as `default`.
- **No real password may be committed.** The ACL template carries `__<USER>_PASSWORD__` placeholders, matching the existing `__REDIS_PASSWORD__` convention in the sentinel template. The operator renders it into the `recsys-secrets` Secret.
- **The ACL file may contain no comments.** Redis aborts startup on any non-blank line not beginning with `user`, which CrashLoops the whole Redis tier. Rationale goes in the runbook and beside the `--aclfile` argument, never in the file.
- **`+@scripting` is required** for `catalog`, `model` and `online`: it is in neither `@read` nor `@write`, and without it `EVAL` fails on the trending read path, the sharded record write, topology publish, the online rate limiter and the model service's submit-token consume.
- **The new test must be non-docker and added to the `resilience` profile** `<includes>` in `pom.xml` — that profile is the PR gate.
- **`redis:7-alpine`** is the image (`redis-cluster.yaml:66, 198, 316, 337`). `%R~` and `%W~` require Redis 7; do not use them if that image is ever downgraded.
- Never merge to `main` directly — this ships as a PR.
- Branch: `feat/redis-per-service-acl`, already created off `main` (`3bbaddb`) with the design committed.

## Facts established before this plan — do not rediscover

Derived from the call graph, **not** from prefix names. The ACL audit's claim of "cleanly disjoint key ownership" is wrong and must not be reintroduced.

| Prefix | Written by | Read by |
|---|---|---|
| `i2vEmb:` | catalog serving (`EmbeddingService.setEmbedding` via `/setembedding`; `RecSysServer.writeMissing` at startup) | catalog, model |
| `u2vEmb:` | Flink as `default`, **and catalog** via `RecSysServer.seedEmbeddings` -> `writeMissing` | catalog, model, online |
| `topk:` | nothing directly, but read through `EVAL`, which Redis requires write permission for | catalog, model, online |
| `sr:*` | online serving | online serving |
| `shard:topology` | online serving | online serving |
| `rate:online:*` | online serving (`RedisRateLimiter`) | online serving |
| `submit_token:*`, `login:*` | model serving | model serving |
| `svc:registry:<service>` | each service writes only its own key | gateway reads all |
| `lineage:event:*` | Flink | reconciliation CronJob, read-only |
| `user:*:recent_movies` | Flink | reconciliation CronJob, **and online and model** (`OnlineFeatureStore:93,98`) |

- `LlmResponseCache` is an **in-memory LRU** keyed by a SHA-256 of the request body. The gateway's only Redis use is the service registry.
- Six manifests reference `REDIS_PASSWORD`: `api-gateway.yaml`, `catalog-serving.yaml`, `model-serving.yaml`, `online-serving.yaml`, `outbox-reconciliation-cronjob.yaml`, and `redis-cluster.yaml` itself. `outbox-relay-deployment.yaml` uses no Redis.
- The Redis primary takes its entire configuration as **command-line arguments** (`redis-cluster.yaml:75-86`) and mounts only `/data`. The single ConfigMap in that file is the *sentinel* template.
- Clients authenticate to the data nodes with username+password (`LettuceClientFactory.withAuth`), and to Sentinel with **no** credentials — `sentinelUri` calls `withSentinel(host, port)` without a password, and the sentinel config sets no `requirepass`. ACL users therefore affect only the master/replica leg.

---

## File Structure

| File | Responsibility |
|---|---|
| `k8s/base/redis-users.acl.template` | The five ACL user rules, with password placeholders. The security-relevant content, and what the test reads. |
| `k8s/base/redis-cluster.yaml` | Mounts the rendered ACL file from `recsys-secrets` and adds `--aclfile` to primary and replica. |
| `k8s/base/{api-gateway,catalog-serving,model-serving,online-serving}.yaml`, `outbox-reconciliation-cronjob.yaml` | Each gains `REDIS_USERNAME`. |
| `src/test/java/com/recsys/infrastructure/k8s/RedisAclManifestTest.java` | Pins each user's key patterns to the prefixes its service uses, and each workload's `REDIS_USERNAME` to a user that exists. |
| `pom.xml` | One `<include>` in the `resilience` profile. |
| `docs/runbooks/redis-auth.md`, `docs/system_design/20_AuthN_AuthZ.md` | Rendering the template, per-user rotation, and the ElastiCache gap. |

---

### Task 1: The ACL template and the Redis wiring

**Files:**
- Create: `k8s/base/redis-users.acl.template`
- Modify: `k8s/base/redis-cluster.yaml`

**Interfaces:**
- Produces: six user names — `default`, `catalog`, `model`, `online`, `gateway`, `reconciliation` — and the key patterns Task 2's test parses. The template's line format is `user <name> on >__<UPPER>_PASSWORD__ <rules...>`, one user per line, no comments.

- [ ] **Step 1: Write the ACL template**

Create `k8s/base/redis-users.acl.template`.

**No comments.** Redis aborts startup on any non-blank line in an ACL file that does not begin with
`user` — measured, not assumed. All rationale lives in the runbook and beside the `--aclfile`
argument in `redis-cluster.yaml`. Blank lines are permitted; nothing else is.

```
user default on >__REDIS_PASSWORD__ ~* &* +@all
user catalog on >__CATALOG_PASSWORD__ -@all +@read +@write +@connection +@scripting -@dangerous ~i2vEmb:* ~u2vEmb:* ~topk:* ~svc:registry:recsys-catalog-serving
user model on >__MODEL_PASSWORD__ -@all +@read +@write +@connection +@scripting -@dangerous ~submit_token:* ~login:* ~topk:* ~svc:registry:recsys-model-serving %R~i2vEmb:* %R~u2vEmb:* %R~user:*:recent_movies
user online on >__ONLINE_PASSWORD__ -@all +@read +@write +@connection +@scripting -@dangerous ~sr:* ~shard:topology ~rate:online:* ~topk:* ~svc:registry:recsys-online-serving %R~u2vEmb:* %R~user:*:recent_movies
user gateway on >__GATEWAY_PASSWORD__ -@all +@read +@write +@connection -@dangerous ~svc:registry:recsys-api-gateway %R~svc:registry:*
user reconciliation on >__RECONCILIATION_PASSWORD__ -@all +@read +@connection -@dangerous %R~lineage:event:* %R~user:*:recent_movies
```

**`user default` is present and must stay.** An ACL file that omits it does not leave `requirepass`
governing the default user — `ACLLoadFromFile` rebuilds default as `on nopass ~* &* +@all`, which
leaves Redis accepting unauthenticated connections, breaks replication, breaks Flink, and keeps both
`redis-cli ping` probes green while it happens. Measured. `--requirepass` stays in the argument list
for the sentinel's `auth-pass` pairing, but this line is what actually governs.

`topk:` is `~` rather than `%R~` for all three serving users because `ShardedTopKStore` reads it
through `EVAL`, and Redis demands full read-write permission on every key passed to a script — even
one carrying a `#!lua flags=no-writes` shebang. Also measured.

- [ ] **Step 2: Mount the rendered file into the primary**

In `k8s/base/redis-cluster.yaml`, in the **primary** StatefulSet's container (the one whose args end with `--requirepass` / `"$(REDIS_PASSWORD)"` around lines 85-86), append two more args immediately after `"$(REDIS_PASSWORD)"`:

```yaml
            # ACL users, one per workload — see k8s/base/redis-users.acl.template. Rules are
            # passed as a file rather than as `--user` arguments because a user rule carries its
            # password inline, and this manifest already refuses to put a credential in the
            # process table (see REDISCLI_AUTH below). Read-only is sufficient: nothing calls
            # ACL SAVE, so the file is only ever read at startup.
            - "--aclfile"
            - "/etc/redis/users.acl"
```

Then in that container's `volumeMounts` (line 104-106, which currently mounts only `/data`), add:

```yaml
            - name: redis-acl
              mountPath: /etc/redis
              readOnly: true
```

And add a `volumes:` entry to that StatefulSet's pod spec:

```yaml
        - name: redis-acl
          secret:
            secretName: recsys-secrets
            items:
              - key: redis-users.acl
                path: users.acl
```

- [ ] **Step 3: Do the same for the replica**

Repeat Step 2 exactly for the **replica** StatefulSet container (its `--requirepass` is around line 212). ACLs are per-node state, not replicated, so a replica without the file rejects every authenticated read the moment a client is routed to it — which is the AZ-aware read path in normal operation, not a failure mode.

- [ ] **Step 4: Verify the manifests still render**

```bash
kubectl kustomize k8s/base > /dev/null && echo BASE_OK
kubectl kustomize k8s/eks > /dev/null && echo EKS_OK
kubectl kustomize k8s/eks-us-west-2 > /dev/null && echo WEST_OK
```

Expected: all three print their OK line. Do **not** apply anything.

- [ ] **Step 5: Confirm `user default` is absent and every user has a placeholder**

```bash
grep -c "^user " k8s/base/redis-users.acl.template
grep -c "^user default on >__REDIS_PASSWORD__ ~\\* &\\* +@all$" k8s/base/redis-users.acl.template
grep -c "^#" k8s/base/redis-users.acl.template
```

Expected: `6`, `1`, and `0`. The third is the one that matters — a single `#` line aborts Redis
startup and CrashLoops the whole tier.

- [ ] **Step 6: Commit**

```bash
git add k8s/base/redis-users.acl.template k8s/base/redis-cluster.yaml
git commit -m "feat: define per-service Redis ACL users"
```

---

### Task 2: Wire `REDIS_USERNAME` and pin the ACLs to the code

**Files:**
- Modify: `k8s/base/api-gateway.yaml`, `catalog-serving.yaml`, `model-serving.yaml`, `online-serving.yaml`, `outbox-reconciliation-cronjob.yaml`
- Create: `src/test/java/com/recsys/infrastructure/k8s/RedisAclManifestTest.java`
- Modify: `pom.xml`

**Interfaces:**
- Consumes: the five user names and the `user <name> on >__<UPPER>_PASSWORD__ <rules...>` line format from Task 1.

- [ ] **Step 1: Add `REDIS_USERNAME` to each workload**

In each of the five manifests, immediately **above** the existing `- name: REDIS_PASSWORD` block, insert the matching entry. The user names map to workloads as: `api-gateway.yaml` → `gateway`, `catalog-serving.yaml` → `catalog`, `model-serving.yaml` → `model`, `online-serving.yaml` → `online`, `outbox-reconciliation-cronjob.yaml` → `reconciliation`.

For `catalog-serving.yaml`:

```yaml
            # Authenticates as the `catalog` ACL user, not `default`. See
            # k8s/base/redis-users.acl.template for what this user may write. Unset falls back to
            # a legacy default-user AUTH (LettuceClientFactory:220), so a cluster whose Secret
            # predates the ACL rollout keeps working rather than failing closed at startup.
            - name: REDIS_USERNAME
              value: "catalog"
```

Use the same block for the others with the user name substituted, and match each file's existing indentation — the CronJob nests two levels deeper than the Deployments.

- [ ] **Step 2: Write the conformance test**

Create `src/test/java/com/recsys/infrastructure/k8s/RedisAclManifestTest.java`:

```java
package com.recsys.infrastructure.k8s;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each workload's Redis ACL user may write exactly the prefixes its code writes.
 *
 * <p>Before this, all five Redis clients authenticated as {@code default} with one shared
 * password and the whole keyspace. The gateway could overwrite item embeddings; model serving,
 * which writes nothing at all, could {@code FLUSHALL}.
 *
 * <p>The interesting direction is over-permission, and it is invisible to any test that asks
 * "can this service reach what it needs" — a user granted {@code ~*} passes that trivially,
 * which is exactly the state this replaces. So the assertion is equality: the write patterns in
 * {@code redis-users.acl.template} must equal {@link #EXPECTED_WRITE_PATTERNS}, neither more nor
 * less.
 *
 * <p>Scope: this compares two committed files. It cannot see a rendered Secret, a running
 * server, or ElastiCache — the EKS overlays point Redis at ElastiCache, whose RBAC user groups
 * are a different mechanism entirely and are not covered here at all.
 */
class RedisAclManifestTest {

    private static final Path BASE = Path.of("k8s", "base");
    private static final Path TEMPLATE = BASE.resolve("redis-users.acl.template");

    /**
     * Write access per user, derived from the call graph rather than from prefix names. The
     * audit that prompted this work recorded "cleanly disjoint key ownership"; it is not — three
     * services read the same embedding and trending keyspace, and only writes can be split.
     *
     * <p>{@code topk:} and {@code u2vEmb:} appear in nobody's write set on purpose: no class in
     * src/main/java writes them ({@code ShardedTopKStore} exposes no write method), so the Flink
     * job writes both as {@code default}.
     */
    private static final Map<String, Set<String>> EXPECTED_WRITE_PATTERNS = Map.of(
            "catalog", Set.of("i2vEmb:*", "u2vEmb:*", "topk:*",
                    "svc:registry:recsys-catalog-serving"),
            "model", Set.of("submit_token:*", "login:*", "topk:*",
                    "svc:registry:recsys-model-serving"),
            "online", Set.of("sr:*", "shard:topology", "rate:online:*", "topk:*",
                    "svc:registry:recsys-online-serving"),
            "gateway", Set.of("svc:registry:recsys-api-gateway"),
            "reconciliation", Set.of());

    /** Which ACL user each Redis-using workload authenticates as. */
    private static final Map<String, String> EXPECTED_USERNAMES = Map.of(
            "recsys-api-gateway", "gateway",
            "recsys-catalog-serving", "catalog",
            "recsys-model-serving", "model",
            "recsys-online-serving", "online",
            "recsys-outbox-reconciliation", "reconciliation");

    @Test
    void everyUsersWriteAccessMatchesWhatItsServiceWrites() throws IOException {
        Map<String, Set<String>> actual = new TreeMap<>();
        for (Map.Entry<String, List<String>> user : usersInTemplate().entrySet()) {
            Set<String> writes = new TreeSet<>();
            for (String rule : user.getValue()) {
                // `~p` is read+write; `%W~p` is write-only. `%R~p` is read-only and is not a
                // write grant. Anything else is a command rule.
                if (rule.startsWith("~")) {
                    writes.add(rule.substring(1));
                } else if (rule.startsWith("%W~")) {
                    writes.add(rule.substring(3));
                } else if (rule.startsWith("%RW~")) {
                    writes.add(rule.substring(4));
                }
            }
            actual.put(user.getKey(), writes);
        }

        // A silently-empty scan would pass this test while proving nothing.
        assertThat(actual)
                .as("no users parsed from %s — the scan found nothing to check", TEMPLATE)
                .isNotEmpty();
        assertThat(actual)
                .as("each ACL user must be able to write exactly what its service writes")
                .isEqualTo(new TreeMap<>(EXPECTED_WRITE_PATTERNS));
    }

    /** A user that cannot complete a handshake, or that keeps a dangerous command, is broken. */
    @Test
    void everyUserDeniesDangerousCommandsAndPermitsTheHandshake() throws IOException {
        List<String> problems = new ArrayList<>();
        usersInTemplate().forEach((name, rules) -> {
            if (!rules.contains("-@all")) problems.add(name + " does not start from -@all");
            if (!rules.contains("+@connection")) problems.add(name + " cannot AUTH/HELLO/PING");
            if (!rules.contains("-@dangerous")) problems.add(name + " keeps @dangerous");
            int allIdx = rules.indexOf("-@all");
            int dangerousIdx = rules.indexOf("-@dangerous");
            if (allIdx >= 0 && dangerousIdx >= 0 && dangerousIdx < allIdx) {
                problems.add(name + " applies -@dangerous before -@all, which re-grants it");
            }
        });
        assertThat(problems).isEmpty();
    }

    /**
     * {@code requirepass} is Redis's shortcut for the default user's password, and the Flink job
     * — deployed by nothing in this repo — writes u2vEmb:* and topk:* as default. A default entry
     * here would contend with one and silently narrow the other.
     */
    @Test
    void theTemplateDoesNotRedefineTheDefaultUser() throws IOException {
        assertThat(usersInTemplate()).doesNotContainKey("default");
    }

    /** No real credential may be committed; every user carries a placeholder. */
    @Test
    void everyUserPasswordIsAPlaceholder() throws IOException {
        List<String> problems = new ArrayList<>();
        usersInTemplate().forEach((name, rules) -> rules.stream()
                .filter(r -> r.startsWith(">"))
                .forEach(r -> {
                    if (!r.matches(">__[A-Z_]+_PASSWORD__")) {
                        problems.add(name + " has a non-placeholder password rule: " + r);
                    }
                }));
        assertThat(problems).isEmpty();
    }

    /** Every Redis-using workload must name a user the template actually defines. */
    @Test
    void everyWorkloadAuthenticatesAsItsOwnUser() throws IOException {
        Set<String> defined = usersInTemplate().keySet();
        Map<String, String> actual = new TreeMap<>();

        for (Map<String, Object> doc : ManifestDocuments.allIn(BASE)) {
            String name = ManifestDocuments.nameOf(doc);
            if (!EXPECTED_USERNAMES.containsKey(name)) continue;
            String username = envValue(doc, "REDIS_USERNAME");
            assertThat(username)
                    .as("%s sets no REDIS_USERNAME, so it still authenticates as default", name)
                    .isNotNull();
            assertThat(defined)
                    .as("%s names REDIS_USERNAME=%s, which the ACL template does not define",
                            name, username)
                    .contains(username);
            actual.put(name, username);
        }

        assertThat(actual)
                .as("every Redis-using workload must authenticate as its own ACL user")
                .isEqualTo(new TreeMap<>(EXPECTED_USERNAMES));
    }

    /** user name -> its rule tokens, in file order. */
    private static Map<String, List<String>> usersInTemplate() throws IOException {
        Map<String, List<String>> users = new LinkedHashMap<>();
        for (String line : Files.readAllLines(TEMPLATE)) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("user ")) continue;
            String[] tokens = trimmed.split("\\s+");
            List<String> rules = new ArrayList<>(List.of(tokens).subList(2, tokens.length));
            users.put(tokens[1], rules);
        }
        return users;
    }

    /**
     * The literal {@code value:} of an env entry in a workload's containers, or null.
     *
     * <p>A Deployment's pod spec is at {@code spec.template.spec}; a CronJob's is two levels
     * deeper, at {@code spec.jobTemplate.spec.template.spec}. Both are checked so the
     * reconciliation CronJob is covered by the same assertion as the four Deployments.
     */
    private static String envValue(Map<String, Object> doc, String key) {
        List<Map<String, Object>> podSpecs = new ArrayList<>();
        Map<String, Object> deploymentPod = ManifestDocuments.mapAt(doc, "spec", "template", "spec");
        if (deploymentPod != null) podSpecs.add(deploymentPod);
        Map<String, Object> cronPod =
                ManifestDocuments.mapAt(doc, "spec", "jobTemplate", "spec", "template", "spec");
        if (cronPod != null) podSpecs.add(cronPod);

        for (Map<String, Object> podSpec : podSpecs) {
            for (Map<String, Object> container : ManifestDocuments.listOf(podSpec, "containers")) {
                for (Map<String, Object> entry : ManifestDocuments.listOf(container, "env")) {
                    if (key.equals(entry.get("name")) && entry.get("value") != null) {
                        return String.valueOf(entry.get("value"));
                    }
                }
            }
        }
        return null;
    }
}
```

`ManifestDocuments` exposes exactly `allIn`, `ofKind`, `mapAt`, `listOf`, `stringListOf` and `nameOf` — verified. Do not add a helper there; the code above uses only those.

- [ ] **Step 3: Run the test**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=RedisAclManifestTest
```

Expected: PASS, 5 tests. A failure names the mismatch — reconcile the template and the manifests with the expected maps rather than loosening the assertion.

- [ ] **Step 4: Prove all three drift directions actually fail**

Each of these must be run, must fail, and must then be reverted by naming its exact path. **Commit your work first** — a `git checkout --` on a single path has already destroyed uncommitted work in this repo.

1. **Over-permission.** Change `model`'s `%R~i2vEmb:*` to `~i2vEmb:*` in the template. Expected: `everyUsersWriteAccessMatchesWhatItsServiceWrites` fails naming `i2vEmb:*` under `model`. This is the direction that matters — it is the shape of the state being replaced.
2. **Under-permission.** Delete `~sr:*` from `online`. Expected: the same test fails.
3. **Unwired workload.** Delete the `REDIS_USERNAME` block from `model-serving.yaml`. Expected: `everyWorkloadAuthenticatesAsItsOwnUser` fails saying it still authenticates as default.

- [ ] **Step 5: Add the test to the PR gate**

In `pom.xml`, in the `resilience` profile's `<includes>`, beside the other `**/k8s/*ManifestTest` entries:

```xml
                <include>**/k8s/RedisAclManifestTest.java</include>
```

- [ ] **Step 6: Run the gate and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
```

Expected: BUILD SUCCESS, test count up by 5 from 608.

```bash
git add k8s/base/api-gateway.yaml k8s/base/catalog-serving.yaml k8s/base/model-serving.yaml \
        k8s/base/online-serving.yaml k8s/base/outbox-reconciliation-cronjob.yaml \
        src/test/java/com/recsys/infrastructure/k8s/RedisAclManifestTest.java pom.xml
git commit -m "feat: authenticate each workload as its own Redis ACL user"
```

---

### Task 3: Documentation

**Files:**
- Modify: `docs/runbooks/redis-auth.md`
- Modify: `docs/system_design/20_AuthN_AuthZ.md`

**Interfaces:** consumes nothing; produces nothing.

- [ ] **Step 1: Document rendering the template**

In `docs/runbooks/redis-auth.md`, add a section before the existing `## Rotation` heading titled `## ACL users`, covering: that `k8s/base/redis-users.acl.template` is the source of the five users; that the operator substitutes each `__*_PASSWORD__` with a distinct generated password and stores the result as the `redis-users.acl` key of `recsys-secrets`; that the primary and replica mount it at `/etc/redis/users.acl` via `--aclfile`; and that `default` is deliberately absent so `requirepass` keeps governing it, because the Flink job authenticates as `default` to write `u2vEmb:*` and `topk:*`.

- [ ] **Step 2: Replace the rotation section's ACL aside with the real procedure**

`docs/runbooks/redis-auth.md:52-54` currently says rotation is a coordinated restart and that "moving the `default` user to an `aclfile` buys multi-password overlap". Now that five users live in an ACL file, replace that aside with the per-user procedure — a `user` line accepts several passwords at once, so rotating one service is: add the new password to that user's line, re-render the Secret, restart Redis, roll that one Deployment, then drop the old password. State plainly that `default` still rotates the old way, by coordinated restart, because it is still governed by `requirepass`.

- [ ] **Step 3: Record the authorization half in §8 of the design doc**

In `docs/system_design/20_AuthN_AuthZ.md`, in the data-tier discussion that covers Redis authentication, add that authentication (PR #274) and authorization are now separate: five ACL users scoped by write access, with the read/write asymmetry explained — three services read the same embedding and trending keyspace, so only writes can be split, and the claim of disjoint key ownership in the 2026-08-05 ACL audit was wrong.

Also record the two limits: the EKS overlays point Redis at ElastiCache, whose RBAC user groups are a different mechanism that none of this touches; and nothing is enforced anywhere today, since no cluster exists.

- [ ] **Step 4: Verify the docs build**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=DocumentationIndexTest
```

Expected: BUILD SUCCESS. No `##` heading may be renumbered and no new document is added.

- [ ] **Step 5: Commit**

```bash
git add docs/runbooks/redis-auth.md docs/system_design/20_AuthN_AuthZ.md
git commit -m "docs: record the Redis ACL users and per-user rotation"
```

---

## First thing to check when a Redis is reachable

Two behaviours are asserted from Redis documentation and could not be measured — no cluster exists and Docker is unavailable on these machines. Both are load-bearing: if either is wrong, the StatefulSet fails to start.

1. `--aclfile` is accepted as a command-line argument like any other directive.
2. An ACL file that omits `user default` leaves `requirepass` governing the default user, rather than erroring or resetting it.

The conformance test cannot cover either — it reads manifests, not a running server. Verify both before trusting a deploy, ideally with `docker run --rm redis:7-alpine redis-server --requirepass x --aclfile /tmp/users.acl` and an `AUTH default x` against it.
