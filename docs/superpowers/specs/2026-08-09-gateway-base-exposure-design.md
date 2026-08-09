# Gateway base exposure — design

Stop `k8s/base` from pairing an anonymous gateway with an internet-facing load balancer, and make
that pairing impossible to reintroduce without failing the build.

## The gap

The two settings are exactly inverted between base and the EKS overlays:

| | gateway Service | `GATEWAY_ALLOW_ANONYMOUS` |
|---|---|---|
| `k8s/base` | `LoadBalancer`, `aws-load-balancer-scheme: internet-facing` (`api-gateway.yaml:147,157`) | **`true`** (`configmap.yaml:37`) |
| `k8s/eks`, `k8s/eks-us-west-2` | `ClusterIP` | `false` (`eks-shared/configmap-patch.yaml:26`) |

So the hardened configuration is cluster-internal and the wide-open one is the only thing exposed to
the internet. Anyone standing up `k8s/base` — a first cluster, an evaluation, a fork — gets a
gateway that authenticates nobody, reachable from anywhere.

It is worse than a missing check. `GatewayAuthenticator` short-circuits when authentication is
disabled, so the `PROTECTED_PREFIXES` never-public guard is never consulted either: the routes that
list themselves as protected are as open as the rest.

**Nothing asserts the value.** No test in the repo reads `GATEWAY_ALLOW_ANONYMOUS` at all. Found by
the 2026-08-09 posture audit (`docs/system_design/22_Data_Leakage_Posture.md`), which established
that only 3 of 20 data-leakage controls actually enforce in `k8s/base`.

## The fix

**Base keeps the dev opt-in and loses the internet exposure.** The gateway Service in
`k8s/base/api-gateway.yaml` becomes `ClusterIP`, and the eleven `aws-load-balancer-*` annotations —
including `scheme: internet-facing` — move out of base. Access for anyone running base is
`kubectl port-forward`, which is what a ClusterIP dev deployment implies anyway.

**Flipping `GATEWAY_ALLOW_ANONYMOUS` to `false` in base instead would be wrong.**
`GatewayAuthenticator.fromEnvironment` refuses to start unless `GATEWAY_API_KEYS` or a Cognito
issuer is set — that refusal is deliberate, added so the gateway cannot silently run wide open. Base
defines neither, and `recsys-gateway-auth` is created out-of-band, so base would fail to boot.
`scripts/run-microservices-local.sh` and every first-cluster walkthrough would break. The opt-in
exists for development and is fine there. What does not belong in development is an internet-facing
NLB.

The EKS overlays are unaffected: they already patch the Service to `ClusterIP` and set
`GATEWAY_ALLOW_ANONYMOUS: "false"`, and real ingress there is the ALB, not this Service.

## The invariant, as a merge-blocking test

`GatewayExposureManifestTest`, non-docker, in the `resilience` profile:

1. **In `k8s/base`:** if `GATEWAY_ALLOW_ANONYMOUS` is `true`, the gateway Service must not be
   `LoadBalancer` or `NodePort`, and must carry no `aws-load-balancer-scheme: internet-facing`
   annotation.
2. **Coupling:** any overlay that introduces internet-facing exposure must also set
   `GATEWAY_ALLOW_ANONYMOUS: "false"`.
3. The scan must fail loudly if it finds no gateway Service or no `GATEWAY_ALLOW_ANONYMOUS` key —
   a silently-empty scan would pass while proving nothing.

Each assertion must be shown to fail before it is trusted: restore the `LoadBalancer` type and the
test must name it; set an overlay internet-facing without flipping the flag and it must name that
too.

## What this does not do

**It does not render kustomizations.** Assertion 2 compares files, so it cannot see a patch that
fails to apply or a value overridden somewhere unexpected. That is the same limitation
`RedisUsernameOverlayTest` carries, and it is why *both* of this week's overlay defects reached
`main` — `RedisAclManifestTest` passed green through a change that would have refused every Redis
`AUTH` in both EKS regions. A test that renders each overlay and asserts against the result would
subsume this one and needs the `kustomize` binary on CI. It is worth doing and is deliberately not
smuggled into this change.

**It does not authenticate base.** Base still runs anonymous; it simply is not exposed. Making base
authenticate needs a credential source base does not have.

**It changes nothing about what is deployed**, because nothing is deployed — no cluster exists in
either region. This closes a trap for whoever stands one up.

## Documentation

`docs/system_design/22_Data_Leakage_Posture.md` — the gateway-authentication row moves from "off in
base, internet-facing" to the new state, with the `PROTECTED_PREFIXES` consequence recorded, since
that entry is the audit's headline finding.

`docs/runbooks/gateway-auth.md` — how to reach the gateway under base now that it is ClusterIP, and
why base is anonymous while the overlays are not.
