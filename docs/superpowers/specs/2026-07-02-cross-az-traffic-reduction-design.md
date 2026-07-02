# Cross-AZ Runtime Traffic Reduction — Design

**Date:** 2026-07-02
**Status:** Approved (pending spec review)
**Scope:** `k8s/eks/` overlay only (`configmap-patch.yaml`, a new
`topology-aware-routing-patch.yaml`, `kustomization.yaml`). No changes to
`k8s/base/`, no application-code changes, no CI (repo has none).

## Goal

Reduce runtime inter-AZ network traffic between the deployed services in the
single, multi-AZ EKS cluster. Today most in-cluster service-to-service calls
needlessly cross AZ boundaries; keep that traffic same-AZ where possible while
preserving the external-caller and blue/green paths.

## Context

- **Topology:** one EKS cluster spread across AZs. Every service Deployment has
  a `topologySpreadConstraints` on `topology.kubernetes.io/zone` (`maxSkew: 1`),
  so pods are already balanced across AZs — the precondition for topology-aware
  routing.
- **Call graph:** client → `recsys-api-gateway` (external NLB) → backends
  (`recsys-model-serving:8080`, `recsys-online-serving:7010`,
  `recsys-catalog-serving:6010`) → Redis. Backends also call each other (e.g.
  feature/ranking lookups) via the same backend Services. All services read the
  shared `recsys-config` ConfigMap (`envFrom`) for peer URLs.
- **Root cause:** the EKS overlay's
  [`configmap-patch.yaml`](../../../k8s/eks/configmap-patch.yaml) overrides the
  three primary internal URLs (`CATALOG_SERVICE_URL`, `MODEL_SERVICE_URL`,
  `ONLINE_SERVICE_URL`) to AWS Cloud Map DNS (`*.recsys.internal`). Cloud Map is
  **topology-blind**: it returns healthy pod IPs across all AZs round-robin and
  bypasses kube-proxy, so in a 3-AZ cluster ≈⅔ of these calls cross an AZ
  boundary. Cloud Map is only actually needed for callers outside the cluster
  (Lambda/EC2) and for blue/green cutover — not for in-cluster calls. (The
  patch's own comment even notes kube-DNS "is still the fast path" in-cluster.)

## Design (Approach A: kube-DNS for internal calls + topology-aware routing)

Two config-only, independently-revertable changes, both in the EKS overlay.

### Change 1 — Route in-cluster calls through Kubernetes ClusterIP (kube-DNS)

Remove the three Cloud Map URL overrides from
[`configmap-patch.yaml`](../../../k8s/eks/configmap-patch.yaml), keeping only
`GATEWAY_TIMEOUT_MS`. EKS then inherits the base kube-DNS names from
`k8s/base/configmap.yaml`:

- `CATALOG_SERVICE_URL: "http://recsys-catalog-serving:6010"`
- `MODEL_SERVICE_URL: "http://recsys-model-serving:8080"`
- `ONLINE_SERVICE_URL: "http://recsys-online-serving:7010"`

(The base ConfigMap's other internal URLs — `USER_PROFILE_SERVICE_URL`,
`FEATURE_SERVICE_URL`, `RANKING_SERVICE_URL`, etc. — already use these kube-DNS
names and were never overridden, so they benefit automatically.)

The Cloud Map **registration** stays: the Service annotations in
[`cloud-map-service-patch.yaml`](../../../k8s/eks/cloud-map-service-patch.yaml)
are unchanged, so external callers still resolve `*.recsys.internal`. Update the
`configmap-patch.yaml` comment to state that in-cluster calls use ClusterIP
(topology-capable, in-cluster, stable VIP) and Cloud Map serves external/
cross-boundary callers only.

**Why this is a prerequisite for Change 2:** topology-aware routing acts on the
kube-proxy / ClusterIP path via EndpointSlice zone hints. Cloud Map DNS bypasses
kube-proxy entirely, so AZ-locality is impossible while internal calls resolve
through Cloud Map. Routing internal calls back onto ClusterIP is what makes
Change 2 effective.

**Simplification bonus:** removes a DNS indirection from the internal hot path
and, with it, the 15s Cloud Map TTL and the 30s JVM DNS-cache
(`networkaddress.cache.ttl=30`) tuning that existed to cope with Cloud Map
endpoint churn — a ClusterIP is a stable virtual IP.

### Change 2 — Topology-aware routing on the backend Services

Add a new overlay patch `k8s/eks/topology-aware-routing-patch.yaml` that sets
`spec.trafficDistribution: PreferClose` on the three internal backend Services:
`recsys-catalog-serving`, `recsys-model-serving`, `recsys-online-serving`. Wire
it into `k8s/eks/kustomization.yaml`'s `patches:` list. kube-proxy then routes to
same-AZ endpoints when available.

Example patch entry (one per Service):

```yaml
apiVersion: v1
kind: Service
metadata:
  name: recsys-model-serving
  namespace: recsys
spec:
  trafficDistribution: PreferClose
```

The gateway Service is intentionally excluded: it is an external `LoadBalancer`
(NLB) with cross-zone LB enabled, so client→gateway ingress is inherently
cross-zone and not an in-cluster locality decision. Only the internal backend
ClusterIP Services get the field; this covers both gateway→backend and
backend→backend calls, since both target these three Services.

**Version dependency & fallback:** `spec.trafficDistribution: PreferClose` is GA
in Kubernetes 1.31+ (standard on current EKS). For clusters older than 1.31, use
the equivalent annotation instead:

```yaml
metadata:
  annotations:
    service.kubernetes.io/topology-mode: Auto
```

Default to `PreferClose`; the annotation is the documented fallback if the target
EKS version is < 1.31.

**Safety / degradation:** `PreferClose` is best-effort. If a zone has zero ready
endpoints (during a rollout, a scale-down, or when a Service's replica count is
below the number of AZs), routing falls back to cluster-wide — no blackholing and
no availability regression. The existing `maxSkew: 1` topologySpreadConstraints
keep per-zone endpoints balanced, which is what makes locality effective in
steady state.

## Out of Scope (follow-ups, not addressed here)

- **Redis / ElastiCache cross-AZ reads.** In EKS the in-cluster Redis is scaled
  to 0 and ElastiCache is used; it is not a Kubernetes Service, so
  `trafficDistribution` cannot apply. Same-AZ read routing is an ElastiCache-side
  concern (reader endpoint / AZ affinity on the `REDIS_HOST` target). Flagged for
  a separate effort.
- **Response compression** between services. Reduces bytes but not cross-AZ hops,
  and spends CPU; the locality win is the direct lever for the stated goal. YAGNI.
- **Service mesh (Istio/Cilium/Linkerd) locality-aware load balancing.** Delivers
  locality + mTLS but adds a control plane and sidecars — disproportionate for a
  single-cluster AZ-locality goal.

## Verification

Config-only; validated by rendering the overlay:

- `kubectl kustomize k8s/eks` renders without error.
- Internal `*_SERVICE_URL` values resolve to kube-DNS names
  (`http://recsys-model-serving:8080`, etc.) — **no** `*.recsys.internal` in any
  internal URL.
- The three backend Services (`recsys-catalog-serving`, `recsys-model-serving`,
  `recsys-online-serving`) carry `trafficDistribution: PreferClose`.
- The Cloud Map registration annotations remain on those three Services (external
  path intact).
- `kubectl kustomize k8s/base` is unchanged from before (overlay-only change;
  base portability preserved).

**Post-deploy observability (documented, not an automated test):** inter-AZ bytes
via VPC flow logs / CloudWatch should drop after rollout, and
`kubectl get endpointslices -l kubernetes.io/service-name=recsys-model-serving -o yaml`
should show per-endpoint zone `hints`.

## Cross-cutting

- Changes are confined to `k8s/eks/`; `k8s/base/` and application code are
  untouched.
- One commit per change (Change 1, Change 2) so each is independently revertable.
- No CI exists to change; verification is local `kubectl kustomize`.
