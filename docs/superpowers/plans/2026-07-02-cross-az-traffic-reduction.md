# Cross-AZ Runtime Traffic Reduction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut inter-AZ runtime traffic in the single multi-AZ EKS cluster by routing in-cluster service-to-service calls over Kubernetes ClusterIP (kube-DNS) and preferring same-AZ endpoints, while keeping Cloud Map for external callers.

**Architecture:** Two config-only changes in the `k8s/eks/` overlay. Task 1 removes the Cloud Map URL overrides so internal calls inherit base kube-DNS names (topology-capable, in-cluster). Task 2 adds `trafficDistribution: PreferClose` to the three backend Services so kube-proxy prefers same-AZ endpoints. `k8s/base/` and application code are untouched.

**Tech Stack:** Kubernetes 1.31+ (EKS), Kustomize (via `kubectl kustomize`, embedded v5.7.1), AWS Cloud Map (external path, unchanged).

## Global Constraints

- Edit only files under `k8s/eks/`. No changes to `k8s/base/`, no application-code changes, no CI (repo has none).
- Keep the Cloud Map **registration** intact: `k8s/eks/cloud-map-service-patch.yaml` and its Service annotations (`cloudmap.aws.amazon.com/*`, `external-dns.alpha.kubernetes.io/hostname`) must remain so external callers still resolve `*.recsys.internal`.
- In-cluster `*_SERVICE_URL` values must be kube-DNS names (`http://recsys-<svc>:<port>`), never `*.recsys.internal`.
- `trafficDistribution: PreferClose` applies to the three internal backend Services only (`recsys-catalog-serving`, `recsys-model-serving`, `recsys-online-serving`) — NOT the gateway (external NLB). Requires Kubernetes 1.31+; on older clusters substitute the annotation `service.kubernetes.io/topology-mode: Auto`.
- One commit per task so each is independently revertable. Never commit to `main`; work stays on branch `feat/cross-az-traffic-reduction`.
- Verification is local `kubectl kustomize` (the standalone `kustomize` binary is absent). Each task must leave both `k8s/eks` and `k8s/base` renderable.

---

### Task 1: Route in-cluster calls through kube-DNS (drop Cloud Map URL overrides)

**Files:**
- Modify: `k8s/eks/configmap-patch.yaml` (remove the three `*.recsys.internal` URL overrides; keep `GATEWAY_TIMEOUT_MS`; rewrite the comment)

**Interfaces:**
- Consumes: the base ConfigMap `k8s/base/configmap.yaml`, which already defines `CATALOG_SERVICE_URL: "http://recsys-catalog-serving:6010"`, `MODEL_SERVICE_URL: "http://recsys-model-serving:8080"`, `ONLINE_SERVICE_URL: "http://recsys-online-serving:7010"`.
- Produces: an EKS-merged `recsys-config` ConfigMap whose internal URLs are kube-DNS names (Cloud Map no longer in the internal path). Task 2 relies on these calls riding the ClusterIP/kube-proxy path.

- [ ] **Step 1: Capture the pre-change render as a baseline**

```bash
kubectl kustomize k8s/eks > /tmp/eks-before.yaml
echo "internal URLs before:"; grep -E '(CATALOG|MODEL|ONLINE)_SERVICE_URL:' /tmp/eks-before.yaml
echo "cloud-map registrations before:"; grep -c 'cloud-map-service-name' /tmp/eks-before.yaml
```

Expected: the three URLs show `*.recsys.internal`; the cloud-map count is `3`.

- [ ] **Step 2: Replace `configmap-patch.yaml` with the kube-DNS version**

Replace the entire contents of `k8s/eks/configmap-patch.yaml` with:

```yaml
# EKS-specific ConfigMap patch.
#
# In-cluster service-to-service calls use Kubernetes ClusterIP (kube-DNS) names,
# inherited from k8s/base/configmap.yaml (e.g. http://recsys-model-serving:8080).
# A ClusterIP is a stable virtual IP and rides the kube-proxy path — which is what
# lets topology-aware routing (trafficDistribution: PreferClose, see
# topology-aware-routing-patch.yaml) keep these calls in the same AZ.
#
# Cloud Map (*.recsys.internal) is retained ONLY for callers outside the cluster
# (Lambda/EC2) and blue/green cutover — see cloud-map-service-patch.yaml, which
# keeps the Service registration annotations. It is intentionally NOT used for
# in-cluster calls: Cloud Map DNS is topology-blind and bypasses kube-proxy, so
# routing through it forces most calls across AZ boundaries.
apiVersion: v1
kind: ConfigMap
metadata:
  name: recsys-config
  namespace: recsys
data:
  GATEWAY_TIMEOUT_MS: "3000"
```

(Removing the three `*_SERVICE_URL` keys from the patch means the strategic-merge no longer overrides them, so the base kube-DNS values apply. `GATEWAY_TIMEOUT_MS` is kept so gateway timeout behavior is unchanged.)

- [ ] **Step 3: Render and verify the internal URLs are kube-DNS (this is the test)**

```bash
kubectl kustomize k8s/eks > /tmp/eks-after.yaml && echo "RENDER OK"
echo "internal URLs after:"; grep -E '(CATALOG|MODEL|ONLINE)_SERVICE_URL:' /tmp/eks-after.yaml
echo "recsys.internal among *_SERVICE_URL (expect 0):"; grep -E '_SERVICE_URL:' /tmp/eks-after.yaml | grep -c 'recsys.internal'
echo "cloud-map registrations still present (expect 3):"; grep -c 'cloud-map-service-name' /tmp/eks-after.yaml
echo "external-dns hostnames still present (expect 3):"; grep -c 'external-dns.alpha.kubernetes.io/hostname' /tmp/eks-after.yaml
```

Expected: `RENDER OK`; the three URLs now read `http://recsys-catalog-serving:6010`, `http://recsys-model-serving:8080`, `http://recsys-online-serving:7010`; **0** `recsys.internal` occurrences among `_SERVICE_URL` lines; cloud-map count still `3`; external-dns hostname count still `3` (external path intact). `GATEWAY_TIMEOUT_MS: "3000"` still present.

- [ ] **Step 4: Confirm the base overlay is untouched**

```bash
kubectl kustomize k8s/base > /dev/null && echo "base renders OK"
git status --short k8s/base/
```

Expected: base renders OK; `git status` shows no changes under `k8s/base/`.

- [ ] **Step 5: Commit**

```bash
git add k8s/eks/configmap-patch.yaml
git commit -m "perf(k8s): route in-cluster calls via kube-DNS, not Cloud Map

Cloud Map DNS is topology-blind and bypasses kube-proxy, forcing most
in-cluster calls across AZ boundaries. Drop the Cloud Map URL overrides so
internal calls use ClusterIP (kube-DNS), keeping Cloud Map registration for
external callers only. Prerequisite for topology-aware routing.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Topology-aware routing on the backend Services

**Files:**
- Create: `k8s/eks/topology-aware-routing-patch.yaml` (adds `trafficDistribution: PreferClose` to the three backend Services)
- Modify: `k8s/eks/kustomization.yaml` (append the new patch to `patches:`)

**Interfaces:**
- Consumes: the three backend ClusterIP Services from `k8s/base/` (`recsys-catalog-serving`, `recsys-model-serving`, `recsys-online-serving`) and the kube-DNS routing established in Task 1.
- Produces: those three Services rendered with `spec.trafficDistribution: PreferClose` in the EKS overlay.

- [ ] **Step 1: Create the topology-aware routing patch**

Create `k8s/eks/topology-aware-routing-patch.yaml` with:

```yaml
# EKS-specific: prefer same-AZ endpoints for the in-cluster backend Services to
# cut inter-AZ traffic. trafficDistribution: PreferClose is best-effort — it falls
# back to cluster-wide routing when a zone has no ready endpoints (rollout,
# scale-down, or replicas < AZ count), so there is no blackholing risk. Requires
# Kubernetes 1.31+ (GA); on older clusters replace each `spec` block below with
# `metadata.annotations: { service.kubernetes.io/topology-mode: Auto }`.
#
# Only the internal backend ClusterIP Services get this. The gateway is an
# external NLB (client ingress is inherently cross-zone) and is excluded.
apiVersion: v1
kind: Service
metadata:
  name: recsys-catalog-serving
  namespace: recsys
spec:
  trafficDistribution: PreferClose
---
apiVersion: v1
kind: Service
metadata:
  name: recsys-model-serving
  namespace: recsys
spec:
  trafficDistribution: PreferClose
---
apiVersion: v1
kind: Service
metadata:
  name: recsys-online-serving
  namespace: recsys
spec:
  trafficDistribution: PreferClose
```

- [ ] **Step 2: Wire the patch into the EKS kustomization**

In `k8s/eks/kustomization.yaml`, in the `patches:` list, add a new entry after the existing `gateway-irsa.yaml` line:

```yaml
  # Prefer same-AZ endpoints for in-cluster backend Services (cut inter-AZ traffic).
  - path: topology-aware-routing-patch.yaml
```

- [ ] **Step 3: Render and verify the field lands on exactly the three backend Services (this is the test)**

```bash
kubectl kustomize k8s/eks > /tmp/eks-t2.yaml && echo "RENDER OK"
echo "PreferClose count (expect 3):"; grep -c 'trafficDistribution: PreferClose' /tmp/eks-t2.yaml
echo "which Services carry it:"; grep -B40 'trafficDistribution: PreferClose' /tmp/eks-t2.yaml | grep -E 'name: recsys-(catalog|model|online|api-gateway)-serving|name: recsys-api-gateway' | sort | uniq
```

Expected: `RENDER OK`; `trafficDistribution: PreferClose` count is `3`; the Services carrying it are `recsys-catalog-serving`, `recsys-model-serving`, `recsys-online-serving` — and **not** `recsys-api-gateway`.

- [ ] **Step 4: Confirm base is untouched and still has no topology field**

```bash
kubectl kustomize k8s/base > /dev/null && echo "base renders OK"
echo "trafficDistribution in base (expect 0):"; kubectl kustomize k8s/base | grep -c 'trafficDistribution' || true
git status --short k8s/base/
```

Expected: base renders OK; `0` `trafficDistribution` occurrences in base; no changes under `k8s/base/`.

- [ ] **Step 5: Commit**

```bash
git add k8s/eks/topology-aware-routing-patch.yaml k8s/eks/kustomization.yaml
git commit -m "perf(k8s): prefer same-AZ endpoints for backend Services

Add trafficDistribution: PreferClose to the three internal backend Services so
kube-proxy routes to same-AZ endpoints, cutting inter-AZ traffic. Best-effort
(falls back to cluster-wide when a zone has no endpoints); gateway (external
NLB) excluded.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Change 1 (drop Cloud Map URL overrides → kube-DNS internal calls; keep `GATEWAY_TIMEOUT_MS`; keep Cloud Map registration) → Task 1. ✓
- Change 2 (`trafficDistribution: PreferClose` on the three backend Services via a new overlay patch; gateway excluded; 1.31+ with annotation fallback) → Task 2. ✓
- Out-of-scope (Redis/ElastiCache, compression, service mesh) → no tasks, correctly absent. ✓
- Verification: internal URLs are kube-DNS with zero `*.recsys.internal`; three Services carry `PreferClose`; Cloud Map annotations intact; base unchanged → Task 1 Steps 3-4, Task 2 Steps 3-4. ✓
- Cross-cutting: eks-only edits, base untouched, one commit per task, branch `feat/cross-az-traffic-reduction`, `kubectl kustomize` verification → Global Constraints + each task's final steps. ✓

**Placeholder scan:** no TBD/TODO; every edit shows the exact full-file or appended YAML; every verify step is a concrete command with expected output. ✓

**Value/consistency check:** Service names (`recsys-catalog-serving`, `recsys-model-serving`, `recsys-online-serving`) and their ports (6010/8080/7010) match `k8s/base/configmap.yaml` and the base Service manifests. The kube-DNS URLs Task 1 relies on inheriting are the exact base values. `PreferClose` count `3` excludes the gateway, matching the spec's Service selection. ✓
