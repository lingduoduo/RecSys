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
there is no `servicediscovery` or `route53` API call in the codebase. Cloud Map reaches the gateway
through DNS resolution (the 30-second TTL described in `CLAUDE.md`), and DNS resolution requires no
IAM.

The Route 53 half is justified in the comment as *"so external-dns can manage Route 53 records"*.
external-dns is a separate controller with its own service account; permissions on the **gateway's**
role do not reach it. Following this file literally grants a public-facing pod
`route53:ChangeResourceRecordSets` — DNS write — for another component's benefit.

The inverse problem exists on the workloads that do use AWS:

| Workload | IRSA ServiceAccount | Documented permissions | Actually calls |
|---|---|---|---|
| API gateway | yes | five | none |
| Model serving | yes, ARN only | none | `sqs:SendMessage`, conditionally |
| Outbox relay | **none** | — | `sqs:SendMessage` via `SqsOutboxDeliveryAdapter` |

So the file that needs nothing is the only one that says anything, and one workload that needs a
role has no service account to bind one to.

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
the bound role needs no permissions, because the gateway makes no AWS API calls; Cloud Map is
consumed by DNS, which needs no IAM; and the Route 53 permissions belong to external-dns's own
service account rather than this one.

The comment should say what the code does today, and note that permissions would return alongside a
feature that needs them — health-aware routing through the Cloud Map API would be one. The five
entries may record an intended design rather than a mistake, and the correction should not assert
otherwise.

**`k8s/eks-shared/patches/irsa-model-serving.yaml`** — add the comment it has never had:
`sqs:SendMessage` on the A/B exposure queue, needed only when `recsys.events.sqs.enabled` is true
with a non-blank queue URL, and scoped to that queue's ARN rather than `*`. `SendMessage` is the only
action: queue URLs arrive through environment variables, so nothing calls `GetQueueUrl`.

**`k8s/base/outbox-relay-deployment.yaml`** — record that `SqsOutboxDeliveryAdapter` calls
`sqs:SendMessage` while this deployment has no `serviceAccountName`, so on EKS it runs under the
namespace default service account and the SQS delivery path has no role to assume. Whoever enables
SQS delivery needs a service account and a role first. This is currently written down nowhere.

## Testing

There is nothing to test. The change is three YAML comments; no manifest key, value or structure
changes, so no conformance test's subject moves and no rendered output differs.

The one check worth running is that the manifests still build — `kubectl kustomize` over `k8s/base`,
`k8s/eks` and `k8s/eks-us-west-2` — which guards against a comment breaking YAML parsing.

Adding a test that asserts these comments say particular things would pin prose rather than
behavior, and would need updating whenever the wording improved. The accuracy guarantee here is the
review, not a test.

## Documentation

The manifests are the documentation; these comments are where an operator provisioning a role will
look. No separate doc change is proposed.

`docs/system_design/20_AuthN_AuthZ.md` covers who authenticates to this system and what they may do;
which AWS permissions the pods themselves hold is a different subject and has no section there
today. Creating one for a set of roles that do not exist would document an intention rather than a
mechanism.
