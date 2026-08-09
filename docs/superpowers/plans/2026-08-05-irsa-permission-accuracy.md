# IRSA Permission Accuracy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the IRSA manifests state the AWS permissions each workload actually needs, so provisioning a role from these files does not over-grant.

**Architecture:** Three YAML comment edits. No manifest key, value or structure changes, and no IAM is created — none exists to create against.

**Tech Stack:** Kustomize, YAML.

## Global Constraints

- **Comments only.** No key, value or structural change to any manifest. `kubectl kustomize` output must be byte-identical before and after, apart from comments.
- **No IAM is created or modified.** Account `362934839387` has no `recsys*` roles, no EKS clusters and no OIDC provider; every ARN in these files is AWS's `123456789012` placeholder.
- **Leave the gateway's IRSA annotation in place.** Removing it is the logical conclusion of the gateway needing no permissions, but that is structural rather than documentation. It is harmless as-is: a service account pointing at a non-existent role yields no usable AWS credentials, which is correct for a workload making no AWS calls.
- **Do not add a service account for the outbox relay.** That would ship a second placeholder ARN for a path that is off by default. Record the gap instead.
- Write the comments against what the code does **today**, and note that permissions return alongside a feature that needs them. The five existing entries may record an intended design rather than a mistake; the correction must not assert otherwise.
- Never merge to `main` directly — this ships as a PR.
- Branch: `docs/irsa-permission-accuracy` (already created; the spec is already committed on it).

---

### Task 1: Correct the three IRSA permission comments

**Files:**
- Modify: `k8s/eks-shared/gateway-irsa-sa.yaml:1-13`
- Modify: `k8s/eks-shared/patches/irsa-model-serving.yaml:1`
- Modify: `k8s/base/outbox-relay-deployment.yaml` (comment above `spec.template.spec`)

**Interfaces:**
- Consumes: nothing.
- Produces: nothing — this plan has one task.

Facts verified against the repo, so you need not rediscover them:

- The only AWS SDK service imported anywhere in `src/main/java` is `sqs`. There is no `servicediscovery` or `route53` call in the codebase.
- The only SQS action called is `sendMessage` — in `SqsOutboxDeliveryAdapter:41` and `SqsSagaEventPublisher:46`. Queue URLs arrive via environment variables, so nothing calls `GetQueueUrl`.
- Model serving's SQS path is gated by `RECSYS_EVENTS_SQS_ENABLED` plus a non-blank `RECSYS_EVENTS_SQS_QUEUE_URL` (`application.yml:135-136`). Neither key appears in `k8s/base/configmap.yaml`, so it defaults off.
- The outbox relay's SQS path is gated by `SAGA_EVENTS_SQS_ENABLED` plus a non-blank `SAGA_EVENTS_SQS_QUEUE_URL` (`OutboxRelayCommand:67-71`); both are in `k8s/base/configmap.yaml:93-94`, defaulting to `"false"` and `""`.
- `SqsSagaEventPublisher` is **not** wired: `SagaEventPublishers` has no callers outside itself. Do not cite it as a live permission need.
- `k8s/base/outbox-relay-deployment.yaml` has no `serviceAccountName`, so on EKS it runs under the namespace default service account.

- [ ] **Step 1: Rewrite the gateway comment**

Replace the comment block at the top of `k8s/eks-shared/gateway-irsa-sa.yaml` (everything above
`apiVersion:`) with:

```yaml
# IRSA ServiceAccount for the API gateway.
#
# The bound IAM role needs NO permissions. The gateway makes no AWS API calls — the only AWS SDK
# client anywhere in src/main/java is SQS, and the gateway does not use it. Cloud Map reaches the
# gateway through DNS resolution (the 30 s TTL described in CLAUDE.md), and resolving a name needs
# no IAM.
#
# This file previously asked for servicediscovery:GetService / ListInstances and
# route53:ChangeResourceRecordSets / ListHostedZones / ListResourceRecordSets. The Route 53 entries
# were justified as letting external-dns manage records for recsys.internal — but external-dns is a
# separate controller with its own ServiceAccount, and permissions on this role do not reach it.
# Granting them here would give a public-facing pod DNS write for another component's benefit.
# Those permissions belong on external-dns's own role.
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
# The bound IAM role needs exactly one permission, and only conditionally:
#
#   sqs:SendMessage   on the A/B exposure queue named by RECSYS_EVENTS_SQS_QUEUE_URL
#
# Required only when RECSYS_EVENTS_SQS_ENABLED=true with a non-blank queue URL (application.yml).
# Neither key is set in k8s/base/configmap.yaml, so the path is off by default and the role needs
# nothing until it is enabled. Scope the statement to that queue's ARN — not "*".
#
# SendMessage is the only action: the queue URL arrives through the environment, so nothing calls
# GetQueueUrl.
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
```

Match the file's existing comment indentation. If placing it above `template:` reads awkwardly
against the surrounding structure, put it immediately above `spec:` at the document level instead —
the requirement is that a reader configuring this deployment sees it, not its exact line.

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
        k8s/base/outbox-relay-deployment.yaml
git commit -m "docs: state the AWS permissions each workload actually needs"
```

Do not push and do not open a PR; the controller handles that.

---

## Verification

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
```

Must pass. No test targets these files, so this is a regression check rather than evidence for the
change — the guarantee that the rendered manifests are unchanged comes from the `diff` in Step 4.
