# NetworkPolicy egress conformance — design

`k8s/base/network-policy.yaml` is the repo's only L3/L4 access-control list, and
[20_AuthN_AuthZ](../../system_design/20_AuthN_AuthZ.md) rests the entire backend trust model on
it: 6010, 7010, and 8080 authenticate nobody *because* the policy proves the gateway is their
only reachable caller. The ingress half of that claim holds. The egress half has drifted — the
policy permits a set of destinations that no longer matches the set the services actually dial,
and every one of those mismatches fails silently or not at all depending on the CNI.

This design closes six gaps and adds a manifest-conformance test that keeps the two sets aligned.

## The problem

Egress rules for the four Armeria/Spring workloads permit `podSelector: app: redis` on 6379 plus
DNS, and nothing else. What the workloads actually dial, per `k8s/base/configmap.yaml`:

| # | Workload | Dials | Permitted today | Symptom when blocked |
|---|---|---|---|---|
| 1 | catalog, model, online serving | `redis-sentinel-*:26379` (`REDIS_MODE: "sentinel"` is the base default) | No — sentinel pods carry `app: redis-sentinel`, a different label | Redis discovery fails at startup |
| 2 | api-gateway | `ollama:11434` (`LLM_SERVICE_URL`) | No | LLM explanation route fails |
| 3 | api-gateway | `redis:6379` when `SERVICE_REGISTRY_ENABLED=true` | No | **Silent** — registry degrades to static-route fallback |
| 4 | online serving | `mysql:3306` when `MYSQL_ENABLED=true` | No | Outbox writes fail |
| 5 | — | gateway → Redis, ingress side | No — the `redis` policy admits only the three serving pods | Other half of #3 |
| 6 | all serving | ElastiCache in `k8s/eks*`, outside the cluster | No — `podSelector` cannot match an external endpoint, and no overlay patches the policy | All Redis traffic fails in EKS |

Two structural notes that shape the fix:

- **The policy exists only in `k8s/base`.** Neither `k8s/eks/` nor `k8s/eks-us-west-2/` patches
  it, yet both replace in-cluster Redis with an ElastiCache endpoint. Gap 6 is a direct
  consequence.
- **Enforcement is CNI-dependent.** EKS's default VPC CNI does not enforce NetworkPolicy unless
  policy support is explicitly enabled. So today either the policy is enforced and gaps 1 and 6
  are outages, or it is not enforced and the trust model in 20_AuthN_AuthZ §"structural facts"
  describes a control that is not running. Both readings are worth fixing; neither is currently
  observable, because no ElastiCache instance exists in the account.

A seventh, adjacent finding: `k8s/base` deploys no Service named `redis`, so `REDIS_HOST: "redis"`
resolves to nothing in-cluster. It is inert only because sentinel mode ignores the standalone
host. The EKS overlays set `REDIS_SENTINEL_MASTER: ""` and rely on `REDIS_HOST`, so the standalone
path is live in production — and any base-shaped deployment that flips `REDIS_MODE` breaks on DNS
resolution before policy ever enters the picture.

## Manifest changes

In `k8s/base/network-policy.yaml`:

1. Add `podSelector: app: redis-sentinel` on 26379 to the egress of all three serving policies.
2. Add `podSelector: app: ollama` on 11434 to `recsys-api-gateway` egress.
3. Add `podSelector: app: redis` on 6379 to `recsys-api-gateway` egress.
4. Add `podSelector: app: mysql` on 3306 to `recsys-online-serving` egress.
5. Add `recsys-api-gateway` to the `redis` policy's ingress `from[]`.
6. Add a new NetworkPolicy selecting `app: redis-sentinel`, admitting ingress on 26379 from the
   three serving pods and from the sentinel pods themselves (sentinels gossip among each other).
   Those pods match no policy today, so their ingress is currently unrestricted by default.
7. Add a `redis` Service selecting `app: redis` on 6379, making `REDIS_HOST: "redis"` resolve.

Rules 3 and 4 are added even though `SERVICE_REGISTRY_ENABLED` and `MYSQL_ENABLED` default to
false. A NetworkPolicy rule is declarative and narrowly scoped; the alternative is a flag flipped
on in production against a policy that silently drops the traffic.

In `k8s/eks-shared/`, a new `network-policy-elasticache-patch.yaml` adding an `ipBlock` egress rule
on 6379 and 26379 to the three serving policies, with a `REPLACE_ME` VPC CIDR documented as an
operator prerequisite — the same pattern `wafv2-acl-arn` already uses. Wired into
`k8s/eks-shared/kustomization.yaml` so both region overlays inherit it.

## The conformance test

`NetworkPolicyEgressManifestTest`, in `src/test/java/com/recsys/infrastructure/k8s/`, reading
`k8s/base/*.yaml` with SnakeYAML exactly as `ScrapeTargetManifestTest` and
`RedisEvictionPolicyManifestTest` do.

### Source of truth

Addresses are **derived** from the ConfigMap; ownership is **declared** in the test. Pure
derivation is impossible: `recsys-config` is a single ConfigMap `envFrom`'d into all five
workloads, so every service receives `LLM_SERVICE_URL` and `MYSQL_URL` in its environment whether
or not it dials them.

```
GATEWAY  -> *_SERVICE_URL (all), REDIS_HOST/PORT, REDIS_SENTINEL_NODES
CATALOG  -> REDIS_HOST/PORT, REDIS_SENTINEL_NODES
MODEL    -> REDIS_HOST/PORT, REDIS_SENTINEL_NODES
ONLINE   -> REDIS_HOST/PORT, REDIS_SENTINEL_NODES, MYSQL_URL
RELAY    -> MYSQL_URL, OUTBOX_KAFKA_BOOTSTRAP_SERVERS
```

Value parsing by shape: `http(s)://host:port` for `*_SERVICE_URL`, `jdbc:mysql://host:port/db` for
`MYSQL_URL`, comma-separated `host:port` for `*_NODES` and `*_BOOTSTRAP_SERVERS`, and the
`REDIS_HOST` + `REDIS_PORT` pair. A host resolves to destination pod labels through the
`spec.selector` of the base Service whose `metadata.name` matches its first DNS label. Resolution
is **strict**: a host with no base Service fails unless it is listed in a declared
`EXTERNALLY_DEPLOYED` set — `ollama` and `mysql`, neither of which `k8s/base` deploys — which
falls back to the `app: <label>` convention the manifests already follow. Strictness is what makes
manifest change 7 load-bearing: without a `redis` Service, `REDIS_HOST: "redis"` names a host that
resolves to nothing, and a permissive fallback would paper over exactly that.

### Assertions

1. **Every declared upstream is permitted.** For each Egress-restricted workload and each owned
   key, require an egress rule whose `to[]` carries a `podSelector` matching the destination
   labels **and** whose `ports[]` includes the derived port — in the *same* rule. Selector and
   port matching in different rules is the exact false pass `ScrapeTargetManifestTest` documents
   having slipped past its own earlier version, so this test structurally rejects it too. An
   `ipBlock` does not satisfy this assertion: base deploys every destination in-cluster, so a
   CIDR rule appearing here would mean the policy had been widened rather than corrected.
   `ipBlock` is accepted only by assertion (4), against the overlay.

2. **The sentinel pods are ingress-restricted.** Assert a NetworkPolicy selects
   `app: redis-sentinel` and admits 26379 only from the three serving pods and the sentinel pods
   themselves. This is the one ingress assertion in the test, and it exists because those pods
   match no policy at all today — meaning Kubernetes admits every source by default, the one
   failure mode that looks identical to "working fine".

3. **Egress and ingress agree.** A NetworkPolicy is enforced at both ends, so an egress rule
   permitting A → D is worthless if D's own policy does not admit A. For every permitted
   destination that is itself governed by a base NetworkPolicy, require that policy's ingress to
   admit the source workload's pod labels on the same port. This is the assertion that covers
   manifest change 5: the gateway may be granted egress to Redis and still be dropped by the
   `redis` policy, which admits only the three serving pods.

4. **Every ConfigMap upstream key is claimed.** Any key matching `*_SERVICE_URL`, `*_HOST`,
   `*_URL`, `*_NODES`, or `*_BOOTSTRAP_SERVERS` that appears in no workload's ownership set fails
   the build. This is the drift catcher: it makes adding a future upstream a deliberate decision
   in two files rather than a gap nobody notices.

5. **Unrestricted-egress workloads are recognized explicitly.** `recsys-outbox-relay` declares
   `policyTypes: [Ingress]`, so it has no egress restriction and satisfies (1) trivially. The test
   asserts that shape with its reason attached, so that a later "completeness" edit adding `Egress`
   to the relay fails here instead of black-holing Kafka delivery in production.

6. **The EKS overlay carries the ElastiCache patch.** Read `k8s/eks-shared/` YAML directly rather
   than shelling out to `kustomize`, and assert the patch exists with `ipBlock` egress on 6379 and
   26379. Deliberately shallow: the CIDR is an operator-supplied `REPLACE_ME`, so the test checks
   the rule's shape, never its correctness.

### Failure messages

House style — name the unreachable endpoint, the policy missing the rule, and the runtime symptom.
The registry and outbox cases in particular must say *how* they fail, because "silent fallback to
static routes" and "write error on first outbox append" send an operator to entirely different
places.

## Testing and CI

The test is added to the `<includes>` of the `resilience` profile in `pom.xml`. That profile is
what the PR gate runs, and it excludes `@Tag("docker")` unconditionally, so a merge-blocking test
must be both non-docker and listed there. This one is pure file parsing: no Redis, no containers,
no cluster.

Every manifest change is verified by the test failing before it and passing after. Mapping the
numbered manifest changes to the assertions that cover them:

| Manifest change | Covered by |
|---|---|
| 1 sentinel egress, 2 ollama, 3 gateway→Redis, 4 online→MySQL | (1) upstream permitted |
| 5 `redis` ingress admits the gateway | (3) egress and ingress agree |
| 6 sentinel ingress policy | (2) sentinel pods ingress-restricted |
| 7 `redis` Service | (1), via strict host resolution |
| `eks-shared` ElastiCache patch | (6) overlay patch shape |

## Documentation

A new `## Access-control lists` section in `docs/system_design/20_AuthN_AuthZ.md` covering the
L3/L4 ACL, since that document already depends on the policy for its central claim but never
describes it. Two additions to its sharp-edges list:

- the policy is base-only, so an overlay that changes a destination address changes it out from
  under the ACL;
- enforcement is CNI-dependent, so "the NetworkPolicy protects the backends" is a claim about
  cluster configuration, not about this repo.

`docs/superpowers/` is excluded from `DocumentationIndexTest`, so this spec needs no README entry;
the `20_AuthN_AuthZ.md` edit lands in a document that is already indexed.

## Out of scope

Deliberately not in this change, and each already recorded in the ACL investigation:

- **Egress on 443 to AWS and identity endpoints.** No policy in this repo permits 443 to anything.
  Four consumers exist: the Cognito JWKS fetch once `GATEWAY_COGNITO_ISSUER` is set per region,
  IRSA/STS `AssumeRoleWithWebIdentity`, Cloud Map service discovery, and the `SAGA_EVENTS_SQS_*`
  path. None is caught by the drift catcher — `_ISSUER` is not an upstream key suffix, and the
  rest never appear in the ConfigMap at all. They are deferred because their destinations are AWS
  service endpoints reached by IP rather than pod label, so they need the same `ipBlock` treatment
  as ElastiCache and the same per-region operator input. Recording it here matters because a green
  conformance run would otherwise read as proof of completeness.
- **Redis ACL users.** `LettuceClientFactory` supports only `AUTH <password>` against the `default`
  user; per-service ACL users with key-pattern and command restrictions are a separate project
  touching runtime code, manifests, and ElastiCache RBAC.
- **An L7 route ACL.** Control-plane writes (`setembedding`, model activate/rollback) sharing a
  privilege tier with catalog reads is sharp edge 1 in 20_AuthN_AuthZ and needs its own design.
- **Rendering the overlays in CI.** Assertion (4) is a shape check on the patch file. Full
  overlay conformance needs `kustomize` in the test path, which no test currently requires.
