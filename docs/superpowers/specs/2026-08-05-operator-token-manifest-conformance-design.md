# Operator token manifest conformance

Stop a workload enforcing the operator tier from shipping without the credential that makes the
tier work.

## The gap

PR #276 made the gateway enforce an operator tier: `OPERATOR`-class routes — `/setembedding` and
the model version activate/rollback/preload endpoints — require `X-Admin-Token`, checked against
`SHARD_ADMIN_TOKEN`. Unset means the tier authorizes nobody.

That is the correct fail-closed behaviour, and it is also a silent one. The code change and the
manifest change are in different files with nothing tying them together: `MicroserviceGatewayServer`
began reading `SHARD_ADMIN_TOKEN`, and `k8s/base/api-gateway.yaml` had to start injecting it. Had
the second half been forgotten, nothing would have failed — no test, no build, no startup error.
The gateway would have come up, logged one WARN, and returned 403 on every operator route until
someone noticed a model rollback not working.

The same trap is armed for the next service that picks up the tier.

## Scope

In scope: a build-time assertion tying the code that reads `SHARD_ADMIN_TOKEN` to the manifests that
supply it.

Out of scope:

- Overlay rendering. Tests cannot run `kubectl kustomize`, so this reads `k8s/base` only. An overlay
  that patched the env var away would not be caught. Unlike this design, the NetworkPolicy
  conformance test (`NetworkPolicyEgressManifestTest`) is not base-only — it also reads
  `k8s/eks-shared` to assert the ElastiCache egress patch exists. `20_AuthN_AuthZ.md` records *that*
  test's base-only limitation as sharp edge 7; this design's equivalent gap is recorded only here
  and in the test's own javadoc.
- Whether the Secret exists in any cluster. That is provisioning, verifiable only against a live
  cluster.
- Any other credential. `GATEWAY_API_KEYS`, `GATEWAY_ORIGIN_SECRET` and the Redis credentials have
  their own manifests and, in the Redis case, their own conformance test.

## The invariant

Every workload whose code reads `SHARD_ADMIN_TOKEN` must have a Deployment that injects it.

One direction, deliberately. The reverse — a Deployment injecting the token for a service that no
longer reads it — is harmless: an unused env var, not a broken control. Enforcing it would add
machinery for a case whose worst outcome is a stale line in a manifest, so this design leaves it
alone rather than pretending symmetry it does not need.

Requirements are **derived from source**, not restated. That is the same principle
`NetworkPolicyEgressManifestTest` applies when it derives egress destinations from
`k8s/base/configmap.yaml`: change what the code dials and the requirement follows it. A test that
merely asserted "api-gateway and online-serving inject the token" would pin today's two instances
and stay silent on the third service, which is the case that matters.

## The test

`OperatorTokenManifestTest` in `com.recsys.infrastructure.k8s`, using the existing
`ManifestDocuments` helpers (`allIn`, `ofKind`, `nameOf`, `mapAt`, `listOf`).

**Finding the readers.** Scan `src/main/java` for source files mentioning the bare name
`SHARD_ADMIN_TOKEN`, anywhere and in any form.

The narrower alternative — matching only the `getenv("SHARD_ADMIN_TOKEN")` call — was the first
draft and is wrong here, because it misses the idioms this codebase already prefers.
`MicroserviceGatewayServer` reads other variables through `GatewayAuthenticator.fromEnvironment()`
and passes `System::getenv` into factories; `OnlinePredictionServer` uses the
`System.getenv().getOrDefault(...)` map form for another variable; both files call `EnvVars` helpers.
The most likely next adopter is the Spring model service, whose `VersionController` hosts the model
version endpoints and would read the token via `@Value`. Every one of those is invisible to a
call-shaped marker, and `k8s/base/model-serving.yaml` has no token block — exactly the gap this
design exists to close.

The asymmetry decides it. A false positive is a loud build failure a human resolves with one line;
a false negative is a workload enforcing a tier it has no credential for, discovered when an
operator command fails in production. For a control whose only value is failing, the broad marker
is correct.

`MENTIONS_ONLY` carries the cost: a map from filename to the reason it mentions the variable without
reading it. Today it holds one entry — `AdminTokenGuard.java`, whose javadoc names the variable while
the token arrives as a constructor argument. **Every exemption is asserted still-matched by the
scan**, so an entry left behind after a file stops mentioning the variable fails the build rather than
sitting there as a hole for the next real reader to hide behind.

Today exactly two files read it: `OnlinePredictionServer` (twice — the `/online/ops` guard and
`ShardedRecordService`) and `MicroserviceGatewayServer`.

**Assertion 1 — every reader is classified.** `READER_WORKLOADS` maps a reading file to its
Deployment name:

| Reading file | Deployment |
|---|---|
| `OnlinePredictionServer.java` | `recsys-online-serving` |
| `MicroserviceGatewayServer.java` | `recsys-api-gateway` |

The scanned set and the map's key set must be **equal**, and each direction has its own message. A
file that mentions the variable and is absent from the map fails as an unmapped reader — a third
service picking up the operator tier therefore has to say where its credential comes from rather
than shipping without one. A mapped file that no longer mentions the variable fails as a stale
entry, which is the direction that matters most: without it, a refactor that moved every read
elsewhere would leave the scan empty, both assertions passing vacuously, and this test quietly
reduced to the hardcoded "api-gateway and online-serving inject the token" that the invariant above
explicitly refuses to be.

This is the forbid-omission shape that `BackendRouteCoverageTest` uses: the test does not try to
infer which workload a file belongs to, it refuses to let the question go unanswered.

Attribution is by file because both current reads sit in service mains. If a read ever lands in a
shared class that cannot be attributed to one workload, this assertion fails and forces the
question — the right failure, not a wrong answer.

**Assertion 2 — every mapped Deployment injects it.** For each Deployment named in the map, at least
one container in `spec.template.spec.containers` must carry a `SHARD_ADMIN_TOKEN` entry in its `env`
whose `valueFrom.secretKeyRef` names Secret `recsys-online-admin`, key `admin-token`, with
`optional: true`. A Deployment named in the map but absent from the manifests fails too — a renamed
workload is the same gap as a missing env block.

`optional: true` gets its own assertion and its own failure message. Without it, a cluster lacking
the Secret cannot schedule the pod at all — which converts a degraded operator tier into an outage,
turning a fail-closed design into a fail-fatal one.

## What this does not prove

It reads `k8s/base`. Both EKS overlays compose that base and neither patches the env block in a way
that drops the var — verified once, by hand, with `kubectl kustomize`. Nothing keeps that true. The
honest statement is that this test proves the base manifests match the code, not that any rendered
overlay does.

It also cannot know whether `recsys-online-admin` exists in a cluster. `optional: true` means a
cluster without it starts normally and rejects every operator request, which is the documented
behaviour, not a defect this test can detect.

## Testing and placement

The test is the deliverable. It must be added to the `resilience` profile in `pom.xml`, which is
what the PR gate runs — outside it, it gates nothing.

Its own correctness is verified by breaking it deliberately before commit, once per assertion it
claims to make, each reverted afterwards:

1. Remove the `SHARD_ADMIN_TOKEN` env block from `k8s/base/api-gateway.yaml` — assertion 2 must fail
   naming that Deployment.
2. Add a `System.getenv("SHARD_ADMIN_TOKEN")` call to a third main — assertion 1 must fail naming
   that file.
3. Replace it with an **indirect** read the call-shaped marker would have missed, such as the
   `System.getenv().get(...)` map form — assertion 1 must still fail. This is what proves the broad
   marker earns the false positives it invites.
4. Remove the javadoc mention from `AdminTokenGuard.java` — the stale-exemption assertion must fail,
   proving `MENTIONS_ONLY` cannot rot into a silent excuse.
5. Change `optional: true` to `optional: false` in `k8s/base/online-serving.yaml` — assertion 2 must
   fail on that specific requirement, since SnakeYAML yields a `Boolean` and an assertion comparing
   against the string `"true"` would never fire.

A conformance test that cannot fail reads as coverage while providing none. The remaining branches —
wrong Secret name, wrong key, `optional` absent rather than false, a Deployment renamed out of
existence — share their code paths with the five above and are left unexercised deliberately.
