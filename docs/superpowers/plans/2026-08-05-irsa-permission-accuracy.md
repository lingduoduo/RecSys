# IRSA Permission Accuracy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the IRSA manifests state the AWS permissions each workload actually needs, so provisioning a role from these files does not over-grant.

**Architecture:** Four YAML comment edits, plus one test that pins the source facts those comments are derived from. No manifest key, value or structure changes, and no IAM is created — none exists to create against.

**Tech Stack:** Kustomize, YAML, JUnit 5 + AssertJ.

## Global Constraints

- **Comments only.** No key, value or structural change to any manifest. `kubectl kustomize` output must be byte-identical before and after, apart from comments.
- **No IAM is created or modified.** Account `362934839387` has no `recsys*` roles, no EKS clusters and no OIDC provider; every ARN in these files is AWS's `123456789012` placeholder.
- **Leave the gateway's IRSA annotation in place.** Removing it is the logical conclusion of the gateway needing no permissions, but that is structural rather than documentation. It is harmless as-is: a service account pointing at a non-existent role yields no usable AWS credentials, which is correct for a workload making no AWS calls.
- **Do not add a service account for the outbox relay.** That would ship a second placeholder ARN for a path that is off by default. Record the gap instead.
- Write the comments against what the code does **today**, and note that permissions return alongside a feature that needs them. The five existing entries may record an intended design rather than a mistake; the correction must not assert otherwise.
- Never merge to `main` directly — this ships as a PR.
- Branch: `docs/irsa-permission-accuracy` (already created; the spec is already committed on it).

---

### Task 1: Correct the four IRSA permission comments

**Files:**
- Modify: `k8s/eks-shared/gateway-irsa-sa.yaml:1-13`
- Modify: `k8s/eks-shared/patches/irsa-model-serving.yaml:1`
- Modify: `k8s/base/outbox-relay-deployment.yaml` (comment above `spec.template.spec`)
- Modify: `k8s/base/online-serving.yaml` (comment above the `ONLINE_EVENTS_SQS_*` env entries)

**Interfaces:**
- Consumes: nothing.
- Produces: the source facts Task 2 pins.

Facts verified against the repo, so you need not rediscover them:

- The only AWS SDK service imported anywhere in `src/main/java` is `sqs`. There is no `servicediscovery` or `route53` call in the codebase.
- Two different SQS actions are called, by different workloads. `SqsOutboxDeliveryAdapter:41` and `SqsSagaEventPublisher:46` call `sendMessage`. **`SqsAsyncEventPublisher:68` calls `sendMessageBatch`** — always, even for a single event — and that is the class `ModelEventConfig.abExposurePublisher` wires for the model-serving path. `sqs:SendMessage` and `sqs:SendMessageBatch` are distinct IAM actions, so the model-serving comment must name the batch one. Queue URLs arrive via environment variables, so nothing calls `GetQueueUrl`.
- Model serving's SQS path is gated by `RECSYS_EVENTS_SQS_ENABLED` plus a non-blank `RECSYS_EVENTS_SQS_QUEUE_URL` (`application.yml:135-136`). Neither key appears in `k8s/base/configmap.yaml`, so it defaults off.
- The outbox relay's SQS path is gated by `SAGA_EVENTS_SQS_ENABLED` plus a non-blank `SAGA_EVENTS_SQS_QUEUE_URL` (`OutboxRelayCommand:67-71`); both are in `k8s/base/configmap.yaml:93-94`, defaulting to `"false"` and `""`.
- Online serving is a **fourth** SQS-calling workload: `OnlinePredictionServer.createAsyncEventPublisher` calls `AsyncEventPublisherFactory.fromEnvironment("ONLINE_EVENTS")`, which builds an `SqsClient` + `SqsAsyncEventPublisher` (`sendMessageBatch`) when `ONLINE_EVENTS_SQS_ENABLED=true` and `ONLINE_EVENTS_SQS_QUEUE_URL` is non-blank. Both keys are set in `k8s/base/online-serving.yaml:73-76` to `"false"` / `""`.
- `SqsSagaEventPublisher` is **not** wired: `SagaEventPublishers` has no callers outside itself. Do not cite it as a live permission need.
- The relay has an outer gate: `OutboxRelayCommand.main` returns immediately unless `ONLINE_DURABLE_EVENTS_ENABLED` is true (`DurableConsistencyConfiguration.fromEnv`), and `k8s/base/configmap.yaml:118` sets it `"false"`.
- `k8s/base/outbox-relay-deployment.yaml` and `k8s/base/online-serving.yaml` have no `serviceAccountName`, so on EKS they run under the namespace default service account.
- Neither does model serving: the **only** `serviceAccountName` anywhere in `k8s/` is `recsys-gateway` (`k8s/eks-shared/gateway-irsa.yaml:10`). So `recsys-model-serving` exists as a ServiceAccount object that no pod uses, and its comment must say so rather than implying the role is live.

- [ ] **Step 1: Rewrite the gateway comment**

Replace the comment block at the top of `k8s/eks-shared/gateway-irsa-sa.yaml` (everything above
`apiVersion:`) with:

```yaml
# IRSA ServiceAccount for the API gateway.
#
# The bound IAM role needs NO permissions. The gateway makes no AWS API calls — the only AWS SDK
# client anywhere in src/main/java is SQS, and the gateway does not use it. The relationship with
# Cloud Map runs the other way round: the gateway is the consumer, resolving its upstream hostnames
# through Armeria's default DNS resolver (UpstreamEndpointGroups.java:32) — kube-DNS ClusterIP names
# for in-cluster calls, Cloud Map's *.recsys.internal names where those are used (see
# configmap-patch.yaml). Plain name resolution needs no IAM either way. And the "30 s Cloud Map DNS
# TTL" in CLAUDE.md is really a client-side setting the gateway applies to itself — it caps the JVM's
# own address cache (networkaddress.cache.ttl, MicroserviceGatewayServer.java:46-61) so blue/green
# endpoint changes propagate. It is not a TTL Cloud Map grants, and nothing about it involves IAM.
#
# This file previously asked for servicediscovery:GetService / ListInstances and
# route53:ChangeResourceRecordSets / ListHostedZones / ListResourceRecordSets. The Route 53 entries
# were justified as letting external-dns manage records for recsys.internal — but this repo does not
# deploy external-dns (only references it via annotations in cloud-map-service-patch.yaml). Route 53
# permissions belong to wherever external-dns actually runs, not to the gateway. Granting them here
# would give a public-facing pod DNS write for another component's benefit.
#
# If health-aware routing through the Cloud Map API is ever built, servicediscovery:GetService and
# ListInstances come back with it — scoped to the recsys.internal namespace. Add them when the code
# calls them, not before.
#
# The role ARN below is AWS's documentation placeholder; no such role exists. Replace it with your
# account-specific value before applying. If the gateway still makes no AWS calls at that point,
# consider dropping the annotation entirely rather than binding an empty role.
```

- [ ] **Step 2: Document the model-serving role**

Add above `apiVersion:` in `k8s/eks-shared/patches/irsa-model-serving.yaml`:

```yaml
# IRSA ServiceAccount for model serving (8080).
#
# Nothing binds this ServiceAccount to a pod: no manifest in k8s/ sets
# serviceAccountName: recsys-model-serving — the only serviceAccountName in the whole tree is
# recsys-gateway (k8s/eks-shared/gateway-irsa.yaml:10). So a role provisioned from the statement
# below is assumed by nobody, and turning the SQS path on would still fail for want of credentials.
# Enabling it needs the serviceAccountName added to the model-serving Deployment as well as the
# role — the same gap k8s/base/outbox-relay-deployment.yaml records about itself.
#
# The bound IAM role needs one permission for what the code calls today, and only conditionally:
#
#   sqs:SendMessageBatch   on the A/B exposure queue named by RECSYS_EVENTS_SQS_QUEUE_URL
#
# Required only when RECSYS_EVENTS_SQS_ENABLED=true with a non-blank queue URL (application.yml).
# Neither key is set in k8s/base/configmap.yaml, so the path is off by default and the role needs
# nothing until it is enabled. Scope the statement to that queue's ARN — not "*". Re-derive the list
# against the queue you actually create rather than treating it as final: server-side encryption
# with a customer-managed key, for one, puts requirements on the sender beyond the sqs: action.
#
# SendMessageBatch, not SendMessage: ModelEventConfig.abExposurePublisher constructs
# SqsAsyncEventPublisher, which always calls sqsClient.sendMessageBatch(...) and never plain
# sendMessage. They are distinct IAM actions, and a role granting only SendMessage fails with
# AccessDenied the moment this is enabled. No other SQS action is called on this path: the queue URL
# arrives through the environment, so nothing calls GetQueueUrl.
#
# The role ARN below is AWS's documentation placeholder; no such role exists. Replace it with your
# account-specific value before applying.
```

- [ ] **Step 3: Record the outbox relay's gap**

In `k8s/base/outbox-relay-deployment.yaml`, add above the `template:` key inside `spec:` (keeping
the existing `replicas` comment where it is):

```yaml
  # AWS: this deployment has no serviceAccountName, so on EKS it runs under the namespace default
  # ServiceAccount, which is bound to no IAM role. OutboxRelayCommand constructs
  # SqsOutboxDeliveryAdapter — which calls sqs:SendMessage — when SAGA_EVENTS_SQS_ENABLED=true and
  # SAGA_EVENTS_SQS_QUEUE_URL is non-blank. Both default off in k8s/base/configmap.yaml, so nothing
  # is broken today; but enabling SQS delivery needs an IRSA ServiceAccount and a role granting
  # sqs:SendMessage on that queue, and neither exists yet. Kafka delivery is unaffected — it uses no
  # AWS credentials.
  #
  # There is an outer gate too: OutboxRelayCommand.main returns immediately unless
  # ONLINE_DURABLE_EVENTS_ENABLED is true (DurableConsistencyConfiguration.fromEnv), and
  # k8s/base/configmap.yaml sets it "false" — so this pod delivers nothing at all today, SQS or
  # Kafka. Enabling SQS delivery therefore means turning on durable events (which itself requires
  # MYSQL_ENABLED, a consistency-token secret and Kafka bootstrap servers), setting the two
  # SAGA_EVENTS_SQS_* keys, and provisioning the ServiceAccount and role.
```

Match the file's existing comment indentation. If placing it above `template:` reads awkwardly
against the surrounding structure, put it immediately above `spec:` at the document level instead —
the requirement is that a reader configuring this deployment sees it, not its exact line.

- [ ] **Step 3b: Record the same gap for online serving**

The fourth SQS-calling workload. In `k8s/base/online-serving.yaml`, immediately above the
`ONLINE_EVENTS_SQS_ENABLED` env entry (12-space indentation, matching the surrounding env list):

```yaml
            # AWS: OnlinePredictionServer builds its event publisher from
            # AsyncEventPublisherFactory.fromEnvironment("ONLINE_EVENTS"), which constructs an
            # SqsClient and an SqsAsyncEventPublisher — i.e. calls sqs:SendMessageBatch, which is
            # the only send the batching publisher ever makes — when ONLINE_EVENTS_SQS_ENABLED=true
            # and ONLINE_EVENTS_SQS_QUEUE_URL is non-blank. Both default off just below, so nothing
            # is broken today; but this Deployment has no serviceAccountName, so on EKS it runs
            # under the namespace default ServiceAccount, which is bound to no IAM role. Enabling
            # SQS delivery needs an IRSA ServiceAccount and a role granting sqs:SendMessageBatch on
            # that queue, scoped to its ARN, and neither exists yet. Kafka delivery
            # (ONLINE_EVENTS_KAFKA_*) is unaffected — it uses no AWS credentials.
```

- [ ] **Step 4: Verify the manifests still build and nothing but comments changed**

```bash
kubectl kustomize k8s/base > /tmp/base-after.yaml && echo "base OK"
kubectl kustomize k8s/eks > /tmp/eks-after.yaml && echo "us-east-1 OK"
kubectl kustomize k8s/eks-us-west-2 > /tmp/west-after.yaml && echo "us-west-2 OK"
```

All three must print OK — that is the check that a comment has not broken YAML parsing.

Then prove the rendered output is unchanged, which is the real guarantee for a comments-only change:

```bash
git stash
kubectl kustomize k8s/base > /tmp/base-before.yaml
kubectl kustomize k8s/eks > /tmp/eks-before.yaml
kubectl kustomize k8s/eks-us-west-2 > /tmp/west-before.yaml
git stash pop
diff /tmp/base-before.yaml /tmp/base-after.yaml && echo "base identical"
diff /tmp/eks-before.yaml /tmp/eks-after.yaml && echo "us-east-1 identical"
diff /tmp/west-before.yaml /tmp/west-after.yaml && echo "us-west-2 identical"
```

Expected: all three `diff`s produce no output. Kustomize strips comments, so a non-empty diff means
you changed something other than a comment — find it and revert that part.

If `kubectl` is unavailable, say so in your report rather than skipping the check silently; in that
case fall back to `git diff` and confirm by inspection that every changed line begins with `#` or is
whitespace.

- [ ] **Step 5: Commit**

```bash
git add k8s/eks-shared/gateway-irsa-sa.yaml \
        k8s/eks-shared/patches/irsa-model-serving.yaml \
        k8s/base/outbox-relay-deployment.yaml \
        k8s/base/online-serving.yaml
git commit -m "docs: state the AWS permissions each workload actually needs"
```

Do not push and do not open a PR; the controller handles that.

---

### Task 2: Pin the source facts the comments are derived from

**Files:**
- Create: `src/test/java/com/recsys/infrastructure/k8s/IrsaPermissionSourceFactsTest.java`
- Modify: `pom.xml` (`resilience` profile `<includes>`)

**Interfaces:**
- Consumes: Task 1's facts.
- Produces: nothing.

Task 1's comments are the instructions an operator provisions IAM from, and nothing checks them.
The proof that this matters is that online serving — a fourth SQS-calling workload — went
undocumented through two reviews of this very change. Assert the **source facts**, not the prose, so
the test does not rot when wording improves:

- [ ] **Step 1: No AWS SDK service client other than `sqs`**

Scan every `.java` file under `src/main/java` for `software.amazon.awssdk.services.<x>` and assert
the set of `<x>` is exactly `{sqs}`. This is what falsifies the gateway comment the moment somebody
adds a `servicediscovery`, `route53` or `s3` client.

- [ ] **Step 2: The set of SQS send call sites is an explicit expected set**

Assert both directions against an expected map of filename → (action(s) called, one-line note naming
the workload). Derive the map from the source, not from this plan. A new caller, or an existing one
switching between `sendMessage` and `sendMessageBatch`, then fails the build until somebody
documents which role needs which permission.

- [ ] **Step 3: Add it to the PR gate and commit**

Add `**/k8s/IrsaPermissionSourceFactsTest.java` to the `resilience` profile's `<includes>` in
`pom.xml`, next to the other `**/k8s/*ManifestTest.java` entries, with a comment saying why. The
profile is what `.github/workflows/resilience-pr.yml` runs, so it is the only place this can block a
merge.

---

## Verification

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
```

Must pass. No test targets these files, so this is a regression check rather than evidence for the
change — the guarantee that the rendered manifests are unchanged comes from the `diff` in Step 4.
