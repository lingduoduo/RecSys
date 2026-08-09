# IRSA permission accuracy

Make the IRSA manifests describe the AWS permissions each workload actually needs, so provisioning
them from these files does not over-grant.

## The gap

`k8s/eks-shared/gateway-irsa-sa.yaml` documents the permissions its bound role should carry:

```
servicediscovery:GetService, ListInstances
route53:ChangeResourceRecordSets, ListHostedZones, ListResourceRecordSets
```

**The gateway uses none of them.** The only AWS SDK client anywhere in `src/main/java` is SQS —
there is no `servicediscovery` or `route53` API call in the codebase. The gateway is Cloud Map's
*consumer*, not its subject: it resolves upstream hostnames through Armeria's default DNS resolver
(`UpstreamEndpointGroups.java:32`), and name resolution requires no IAM. (The "30 s Cloud Map DNS
TTL" in `CLAUDE.md` is a client-side cap the gateway puts on the JVM's own address cache —
`networkaddress.cache.ttl`, `MicroserviceGatewayServer.java:46-61` — not a TTL Cloud Map hands out,
and not a permission either way.)

The Route 53 half is justified in the comment as *"so external-dns can manage Route 53 records"*.
This repo does not deploy external-dns at all — it appears only as
`external-dns.alpha.kubernetes.io/hostname` annotations in `cloud-map-service-patch.yaml`. Route 53
permissions belong to wherever external-dns actually runs, which is not this ServiceAccount.
Following this file literally grants a public-facing pod `route53:ChangeResourceRecordSets` — DNS
write — for another component's benefit.

The inverse problem exists on the workloads that do use AWS:

| Workload | IRSA ServiceAccount | Documented permissions | Actually calls |
|---|---|---|---|
| API gateway | yes, and bound to the pod | five | none |
| Model serving | object exists, ARN only — **bound to no pod** | none | `sqs:SendMessageBatch`, conditionally |
| Online serving | **none** | — | `sqs:SendMessageBatch` via `AsyncEventPublisherFactory`, conditionally |
| Outbox relay | **none** | — | `sqs:SendMessage` via `SqsOutboxDeliveryAdapter`, conditionally |

So the file that needs nothing is the only one that says anything, and every workload that could
need a role has no service account actually bound to it — `recsys-model-serving` exists as an
object, but nothing in `k8s/` sets `serviceAccountName: recsys-model-serving`; the tree's only
`serviceAccountName` is `recsys-gateway` (`k8s/eks-shared/gateway-irsa.yaml:10`).

Found while auditing the IRSA dimension of the 2026-08-05 zero-data-leakage audit — the last of the
three dimensions that had never been examined.

## What is not being changed, and why

**No IAM is created or modified.** None exists to modify: account `362934839387` has no `recsys*`
roles, no EKS clusters, and no OIDC provider — IRSA's hard prerequisite. Established with read-only
calls (`iam get-role`, `iam list-roles`, `iam list-open-id-connect-providers`, `eks list-clusters`),
so it is cheap to re-check if the account state changes. Every role ARN in these
manifests points at `123456789012`, AWS's canonical documentation placeholder, and
`gateway-irsa-sa.yaml` says so: *"Replace the role ARN with your account-specific value before
applying."* These files are instructions for a future provisioning step, and the change is to make
those instructions correct.

**The gateway's IRSA annotation stays.** If the gateway needs no permissions, binding a role to it
serves nothing, and removing the annotation would be the logical conclusion — but that is a
structural change beyond documentation, and the annotation is harmless as it stands: pointing a
service account at a non-existent role means the pod gets no usable AWS credentials, which is the
correct outcome for a workload that makes no AWS calls. The comment will state this so the next
person can decide deliberately.

**No service account is added for the outbox relay.** Creating one would ship a second placeholder
ARN for a delivery path that is off by default and whose IAM does not exist. The gap is recorded
instead.

## The changes

**`k8s/eks-shared/gateway-irsa-sa.yaml`** — replace the permission list with an accurate statement:
the bound role needs no permissions, because the gateway makes no AWS API calls; the gateway
*consumes* Cloud Map by resolving names, which needs no IAM; and the Route 53 permissions belong to
wherever external-dns runs — which is not this repo — rather than to this service account.

The comment should say what the code does today, and note that permissions would return alongside a
feature that needs them — health-aware routing through the Cloud Map API would be one. The five
entries may record an intended design rather than a mistake, and the correction should not assert
otherwise.

**`k8s/eks-shared/patches/irsa-model-serving.yaml`** — add the comment it has never had:
`sqs:SendMessageBatch` on the A/B exposure queue, needed only when `recsys.events.sqs.enabled` is
true with a non-blank queue URL, and scoped to that queue's ARN rather than `*`. `SendMessageBatch`
rather than `SendMessage`, because `ModelEventConfig.abExposurePublisher` wires
`SqsAsyncEventPublisher`, which batches unconditionally (`SqsAsyncEventPublisher.java:68`) — they
are distinct IAM actions, and a role granting only `SendMessage` fails with `AccessDenied`. Nothing
calls `GetQueueUrl`: queue URLs arrive through environment variables. The comment must also say that
this ServiceAccount is bound to no pod, so the role it describes is assumed by nobody until a
`serviceAccountName` is added.

**`k8s/base/online-serving.yaml`** — record the same shape of gap for the fourth SQS-calling
workload: `OnlinePredictionServer` builds `AsyncEventPublisherFactory.fromEnvironment("ONLINE_EVENTS")`,
which constructs an `SqsAsyncEventPublisher` (`sqs:SendMessageBatch`) when
`ONLINE_EVENTS_SQS_ENABLED=true` with a non-blank `ONLINE_EVENTS_SQS_QUEUE_URL` — both set off in
this file — and the Deployment has no `serviceAccountName` to bind a role to.

**`k8s/base/outbox-relay-deployment.yaml`** — record that `SqsOutboxDeliveryAdapter` calls
`sqs:SendMessage` while this deployment has no `serviceAccountName`, so on EKS it runs under the
namespace default service account and the SQS delivery path has no role to assume. Whoever enables
SQS delivery needs a service account and a role first — and, upstream of that,
`ONLINE_DURABLE_EVENTS_ENABLED=true`, without which `OutboxRelayCommand.main` exits immediately.
This is currently written down nowhere.

## Testing

The change is YAML comments only; no manifest key, value or structure changes, so no conformance
test's subject moves and no rendered output differs. The check that proves that is `kubectl
kustomize` over `k8s/base`, `k8s/eks` and `k8s/eks-us-west-2` before and after — three empty diffs,
which also guards against a comment breaking YAML parsing.

A test asserting that these comments *say* particular things would pin prose, need updating whenever
the wording improved, and still not catch the failure that actually happened here: a fourth
SQS-calling workload (online serving) went undocumented through two reviews, because nothing checked
the claims mechanically. So `IrsaPermissionSourceFactsTest` pins the **source facts the comments are
derived from**, not the comments:

1. No file under `src/main/java` imports an AWS SDK service client other than `sqs`. This is what
   falsifies the gateway comment the moment someone adds a `servicediscovery`, `route53` or `s3`
   client — the claim "the only AWS SDK client anywhere is SQS" stops being true and the build says
   so.
2. The set of files calling `sendMessage(` / `sendMessageBatch(`, and which of the two each calls,
   equals an explicit expected set with a one-line note per entry naming the workload it belongs to.
   A new caller — or an existing one switching action — fails the build until somebody documents
   which role needs which permission.

Both checks are pure file scans: no Redis, no Docker, no timing. It joins the `resilience` profile,
which is what the PR gate runs, alongside the other `**/k8s/*ManifestTest` entries.

Wording accuracy itself remains a review responsibility; the test guarantees only that the facts
underneath the wording are still the facts.

## Documentation

The manifests are the documentation; these comments are where an operator provisioning a role will
look. No separate doc change is proposed.

`docs/system_design/20_AuthN_AuthZ.md` covers who authenticates to this system and what they may do;
which AWS permissions the pods themselves hold is a different subject and has no section there
today. Creating one for a set of roles that do not exist would document an intention rather than a
mechanism.
