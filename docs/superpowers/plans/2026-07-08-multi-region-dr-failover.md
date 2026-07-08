# Multi-Region DR Failover Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a warm-standby `us-west-2` DR region for the EKS deployment, with automatic Route53 failover, delivered as a per-region kustomize overlay plus DR runbooks.

**Architecture:** Extract the region-agnostic EKS patches into a reusable kustomize *component* (`k8s/eks-shared/`), retarget the existing `k8s/eks` (us-east-1) overlay to it as a provable no-op, then add a parallel `k8s/eks-us-west-2` overlay that layers only region-specific values (ECR region, ElastiCache/Aurora endpoints, WAF ARN, `AWS_REGION`, reduced warm-standby replica counts). AWS data replication and failover promotion are documented in runbooks; no IaC is introduced.

**Tech Stack:** Kustomize (via `kubectl kustomize` / `kubectl apply -k`), AWS EKS / ECR / ElastiCache Global Datastore / Aurora Global Database / Route53, Bash. No application-code changes.

## Global Constraints

- Active-passive DR only — no active-active/geo-serving, no data-residency isolation. (spec: Non-Goals)
- **No new IaC toolchain.** AWS infra stays provisioned out-of-band; the repo expresses only kustomize overlays + runbooks. (spec: Goals)
- Secondary region is `us-west-2`; primary is `us-east-1`. (spec: Decisions)
- The us-east-1 overlay's rendered output MUST NOT change during the shared-component extraction (Task 1) — verified by a golden diff. (spec: Risks — blast radius)
- Read path fails over automatically; write path recovers after data-tier promotion (runbook). (spec: Architecture)
- Warm standby = services running at reduced `minReplicas` (~50% of primary), `maxReplicas` unchanged for surge headroom. (spec: Architecture)
- Digest-pinned deploys: the same image digest is valid in both regions via ECR cross-region replication; the `digest:` value MUST be identical across both overlays. (spec: Data replication)
- Follow the repo convention: no CI; validation is local `kubectl kustomize` renders + documented game-day. (spec: Testing)
- Branch `feat/multi-region-dr-failover` is already checked out. Never merge to main directly — open a PR. (user workflow)

---

### Task 1: Extract region-agnostic patches into a shared kustomize component

Pure refactor. Move the region-agnostic pieces of `k8s/eks` into a new `k8s/eks-shared/` component and retarget `k8s/eks` to it. The rendered output of `k8s/eks` must be byte-identical before and after.

**Files:**
- Create: `k8s/eks-shared/kustomization.yaml`
- Move (git mv): `k8s/eks/cloud-map-service-patch.yaml` → `k8s/eks-shared/cloud-map-service-patch.yaml`
- Move: `k8s/eks/configmap-patch.yaml` → `k8s/eks-shared/configmap-patch.yaml`
- Move: `k8s/eks/gateway-irsa-sa.yaml` → `k8s/eks-shared/gateway-irsa-sa.yaml`
- Move: `k8s/eks/gateway-irsa.yaml` → `k8s/eks-shared/gateway-irsa.yaml`
- Move: `k8s/eks/topology-aware-routing-patch.yaml` → `k8s/eks-shared/topology-aware-routing-patch.yaml`
- Move: `k8s/eks/patches/irsa-model-serving.yaml` → `k8s/eks-shared/patches/irsa-model-serving.yaml`
- Modify: `k8s/eks/kustomization.yaml` (remove moved entries, add `components:` reference)
- Keep in `k8s/eks/`: `waf-api-gateway-ingress.yaml`, `redis-elasticache-patch.yaml` (both region-specific)

**Interfaces:**
- Produces: a kustomize component at `k8s/eks-shared/` (`kind: Component`) that any region overlay references via `components: [../eks-shared]`. It contributes: the two IRSA resources, the `redis-primary`/`redis-replica`/`redis-sentinel` → 0 replicas transformer, and the Cloud Map / gateway-timeout / gateway-IRSA / topology-routing / gateway-ClusterIP patches.

- [ ] **Step 1: Capture the golden render of the current us-east-1 overlay**

Run:
```bash
kubectl kustomize k8s/eks > /private/tmp/claude-501/-Users-linghuang-Git-Recsys-Backend-Service/d1cc5d7a-6dc5-4b45-98cf-faeac2115767/scratchpad/eks-golden.yaml
wc -l /private/tmp/claude-501/-Users-linghuang-Git-Recsys-Backend-Service/d1cc5d7a-6dc5-4b45-98cf-faeac2115767/scratchpad/eks-golden.yaml
```
Expected: a non-empty render (several hundred lines). This is the baseline the refactor must preserve.

- [ ] **Step 2: Move the shared files into the component directory**

Run:
```bash
mkdir -p k8s/eks-shared/patches
git mv k8s/eks/cloud-map-service-patch.yaml     k8s/eks-shared/cloud-map-service-patch.yaml
git mv k8s/eks/configmap-patch.yaml             k8s/eks-shared/configmap-patch.yaml
git mv k8s/eks/gateway-irsa-sa.yaml             k8s/eks-shared/gateway-irsa-sa.yaml
git mv k8s/eks/gateway-irsa.yaml                k8s/eks-shared/gateway-irsa.yaml
git mv k8s/eks/topology-aware-routing-patch.yaml k8s/eks-shared/topology-aware-routing-patch.yaml
git mv k8s/eks/patches/irsa-model-serving.yaml  k8s/eks-shared/patches/irsa-model-serving.yaml
rmdir k8s/eks/patches 2>/dev/null || true
```
Expected: files relocated, no errors.

- [ ] **Step 3: Create the shared component kustomization**

Create `k8s/eks-shared/kustomization.yaml`:
```yaml
# Region-agnostic EKS overlay pieces, shared by every region overlay
# (k8s/eks = us-east-1, k8s/eks-us-west-2 = DR). Referenced via `components:`.
# Anything here MUST be identical across regions — no ECR region, no ElastiCache
# endpoint, no WAF ARN, no AWS_REGION. Those live in the per-region overlays.
apiVersion: kustomize.config.k8s.io/v1alpha1
kind: Component

resources:
  - gateway-irsa-sa.yaml            # IRSA ServiceAccount for the API gateway (IAM is global)
  - patches/irsa-model-serving.yaml

replicas:
  # In-cluster Redis is replaced by ElastiCache in every EKS region; scale the
  # StatefulSets/Deployments to zero.
  - name: redis-primary
    count: 0
  - name: redis-replica
    count: 0
  - name: redis-sentinel
    count: 0

patches:
  - path: cloud-map-service-patch.yaml       # Cloud Map registration annotations
  - path: configmap-patch.yaml               # GATEWAY_TIMEOUT_MS; in-cluster URLs use kube-DNS
  - path: gateway-irsa.yaml                  # gateway Deployment -> IRSA ServiceAccount
  - path: topology-aware-routing-patch.yaml  # PreferClose same-AZ routing
  # Drop the NLB: the WAF ALB Ingress is the sole public entry, so the gateway
  # Service becomes ClusterIP and its NLB-only annotations are removed.
  - target:
      kind: Service
      name: recsys-api-gateway
      namespace: recsys
    patch: |
      - op: replace
        path: /spec/type
        value: ClusterIP
      - op: remove
        path: /metadata/annotations
```

- [ ] **Step 4: Retarget the us-east-1 overlay to the shared component**

Replace the entire contents of `k8s/eks/kustomization.yaml` with:
```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - ../base
  - waf-api-gateway-ingress.yaml   # WAF-protected ALB; sole public entry (us-east-1 regional ARN)

components:
  - ../eks-shared

images:
  - name: recsys-backend-service
    newName: 123456789012.dkr.ecr.us-east-1.amazonaws.com/recsys-backend-service
    # Immutable digest — a cached node skips ECR on scale-up (IfNotPresent). PLACEHOLDER;
    # scripts/set-eks-image-digest.sh pins the real digest before `kubectl apply`.
    # The SAME digest is pinned in k8s/eks-us-west-2 (ECR cross-region replication).
    # See docs/runbooks/deploy-image-digest.md.
    digest: sha256:0000000000000000000000000000000000000000000000000000000000000000

patches:
  # Override Redis config for ElastiCache (us-east-1 primary endpoint).
  - path: redis-elasticache-patch.yaml
```

- [ ] **Step 5: Render the refactored overlay and diff against the golden**

Run:
```bash
kubectl kustomize k8s/eks > /private/tmp/claude-501/-Users-linghuang-Git-Recsys-Backend-Service/d1cc5d7a-6dc5-4b45-98cf-faeac2115767/scratchpad/eks-after.yaml
diff /private/tmp/claude-501/-Users-linghuang-Git-Recsys-Backend-Service/d1cc5d7a-6dc5-4b45-98cf-faeac2115767/scratchpad/eks-golden.yaml \
     /private/tmp/claude-501/-Users-linghuang-Git-Recsys-Backend-Service/d1cc5d7a-6dc5-4b45-98cf-faeac2115767/scratchpad/eks-after.yaml && echo "IDENTICAL"
```
Expected: `IDENTICAL` (empty diff). If the diff is non-empty, the extraction changed behavior — do NOT proceed; reconcile until the diff is empty.

- [ ] **Step 6: Commit**

```bash
git add k8s/eks k8s/eks-shared
git commit -m "refactor(k8s): extract region-agnostic EKS patches into shared component

No-op for the us-east-1 overlay (kustomize render byte-identical, verified
by golden diff). Prepares for a second region overlay.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Add the us-west-2 warm-standby overlay

Create the DR region overlay reusing `../base` + `../eks-shared`, overriding only region-specific values and reducing replica minimums for warm standby.

**Files:**
- Create: `k8s/eks-us-west-2/kustomization.yaml`
- Create: `k8s/eks-us-west-2/waf-api-gateway-ingress.yaml`
- Create: `k8s/eks-us-west-2/redis-elasticache-patch.yaml`
- Create: `k8s/eks-us-west-2/region-config-patch.yaml`
- Create: `k8s/eks-us-west-2/warm-standby-hpa-patch.yaml`

**Interfaces:**
- Consumes: the `k8s/eks-shared/` component from Task 1 (via `components: [../eks-shared]`).
- Produces: a second deployable overlay `k8s/eks-us-west-2` rendered with `kubectl kustomize k8s/eks-us-west-2`, whose ConfigMap carries `AWS_REGION: us-west-2`, image `newName` points at the `us-west-2` ECR, and HPA `minReplicas` are the warm-standby values (gateway 1, catalog 1, model 2, online 1).

- [ ] **Step 1: Create the region-config ConfigMap patch (sets AWS_REGION)**

Create `k8s/eks-us-west-2/region-config-patch.yaml`:
```yaml
# us-west-2 region config. AWS_REGION drives the SQS client (application.yml:
# region: ${RECSYS_EVENTS_SQS_REGION:${AWS_REGION:us-east-1}}). Injected into all
# pods via envFrom configMapRef: recsys-config.
apiVersion: v1
kind: ConfigMap
metadata:
  name: recsys-config
  namespace: recsys
data:
  AWS_REGION: "us-west-2"
```

- [ ] **Step 2: Create the ElastiCache patch (us-west-2 endpoint)**

Create `k8s/eks-us-west-2/redis-elasticache-patch.yaml`:
```yaml
# ConfigMap patch: point prod Redis at the us-west-2 ElastiCache Global Datastore
# SECONDARY endpoint (read replica; promoted to primary on failover — see
# docs/runbooks/dr-data-tier-promotion.md). Set REDIS_HOST before deploying.
apiVersion: v1
kind: ConfigMap
metadata:
  name: recsys-config
  namespace: recsys
data:
  REDIS_MODE: "standalone"
  REDIS_HOST: "<us-west-2-elasticache-endpoint>.cache.amazonaws.com"
  REDIS_PORT: "6379"
  REDIS_SENTINEL_MASTER: ""
  REDIS_SENTINEL_NODES: ""
```

- [ ] **Step 3: Create the WAF Ingress (us-west-2 regional ARN)**

Create `k8s/eks-us-west-2/waf-api-gateway-ingress.yaml`:
```yaml
# us-west-2 WAF-protected ALB — sole public entry to the API gateway in the DR
# region. WAFv2 WebACL is regional and created out-of-band; note the us-west-2
# ARN. See docs/runbooks/waf-webacl.md.
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: recsys-api-gateway-waf
  namespace: recsys
  annotations:
    kubernetes.io/ingress.class: alb
    alb.ingress.kubernetes.io/scheme: internet-facing
    alb.ingress.kubernetes.io/target-type: ip
    alb.ingress.kubernetes.io/listen-ports: '[{"HTTP":80}]'
    alb.ingress.kubernetes.io/healthcheck-path: /health
    alb.ingress.kubernetes.io/wafv2-acl-arn: arn:aws:wafv2:us-west-2:123456789012:regional/webacl/recsys-api-gateway/REPLACE_ME
spec:
  rules:
    - http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: recsys-api-gateway
                port:
                  number: 80
```

- [ ] **Step 4: Create the warm-standby HPA patch (reduced minReplicas)**

Create `k8s/eks-us-west-2/warm-standby-hpa-patch.yaml`:
```yaml
# Warm standby: keep every service running but at ~50% of primary minReplicas.
# maxReplicas is inherited from base (unchanged) so HPA + cluster-autoscaler can
# surge to full capacity on failover. Primary minReplicas: gateway 2, catalog 2,
# model 3, online 2 -> standby: 1, 1, 2, 1.
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: recsys-api-gateway
  namespace: recsys
spec:
  minReplicas: 1
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: recsys-catalog-serving
  namespace: recsys
spec:
  minReplicas: 1
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: recsys-model-serving
  namespace: recsys
spec:
  minReplicas: 2
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: recsys-online-serving
  namespace: recsys
spec:
  minReplicas: 1
```

- [ ] **Step 5: Create the us-west-2 overlay kustomization**

Create `k8s/eks-us-west-2/kustomization.yaml`:
```yaml
# us-west-2 DR overlay (warm standby). Reuses ../base + ../eks-shared; overrides
# only region-specific values. Keep the image `digest` identical to k8s/eks
# (ECR cross-region replication). See docs/superpowers/specs/2026-07-08-multi-region-dr-failover-design.md.
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - ../base
  - waf-api-gateway-ingress.yaml   # us-west-2 regional WAF ARN

components:
  - ../eks-shared

images:
  - name: recsys-backend-service
    newName: 123456789012.dkr.ecr.us-west-2.amazonaws.com/recsys-backend-service
    # PLACEHOLDER; pinned by scripts/set-eks-image-digest.sh. MUST match the
    # digest in k8s/eks/kustomization.yaml (same image, replicated to us-west-2 ECR).
    digest: sha256:0000000000000000000000000000000000000000000000000000000000000000

patches:
  - path: redis-elasticache-patch.yaml   # us-west-2 ElastiCache secondary endpoint
  - path: region-config-patch.yaml       # AWS_REGION=us-west-2
  - path: warm-standby-hpa-patch.yaml    # reduced minReplicas
```

- [ ] **Step 6: Render the overlay and assert region-specific values**

Run:
```bash
kubectl kustomize k8s/eks-us-west-2 > /private/tmp/claude-501/-Users-linghuang-Git-Recsys-Backend-Service/d1cc5d7a-6dc5-4b45-98cf-faeac2115767/scratchpad/uswest2.yaml
echo "--- ECR region ---";     grep -c 'dkr.ecr.us-west-2.amazonaws.com' /private/tmp/claude-501/-Users-linghuang-Git-Recsys-Backend-Service/d1cc5d7a-6dc5-4b45-98cf-faeac2115767/scratchpad/uswest2.yaml
echo "--- no us-east-1 ECR ---"; grep -c 'dkr.ecr.us-east-1' /private/tmp/claude-501/-Users-linghuang-Git-Recsys-Backend-Service/d1cc5d7a-6dc5-4b45-98cf-faeac2115767/scratchpad/uswest2.yaml || true
echo "--- AWS_REGION ---";      grep 'AWS_REGION' /private/tmp/claude-501/-Users-linghuang-Git-Recsys-Backend-Service/d1cc5d7a-6dc5-4b45-98cf-faeac2115767/scratchpad/uswest2.yaml
echo "--- WAF ARN region ---";  grep 'wafv2-acl-arn' /private/tmp/claude-501/-Users-linghuang-Git-Recsys-Backend-Service/d1cc5d7a-6dc5-4b45-98cf-faeac2115767/scratchpad/uswest2.yaml
echo "--- gateway HPA minReplicas ---"; grep -A6 'name: recsys-api-gateway' /private/tmp/claude-501/-Users-linghuang-Git-Recsys-Backend-Service/d1cc5d7a-6dc5-4b45-98cf-faeac2115767/scratchpad/uswest2.yaml | grep 'minReplicas'
```
Expected: ECR region grep returns `1`; the us-east-1 ECR grep returns `0`; `AWS_REGION: us-west-2` present; WAF ARN shows `wafv2:us-west-2:`; gateway `minReplicas: 1`.

- [ ] **Step 7: Confirm the image digest matches across both overlays**

Run:
```bash
grep -h 'digest:' k8s/eks/kustomization.yaml k8s/eks-us-west-2/kustomization.yaml | sort -u | wc -l
```
Expected: `1` (both overlays carry the identical digest line — the placeholder now, the real digest after pinning).

- [ ] **Step 8: Commit**

```bash
git add k8s/eks-us-west-2
git commit -m "feat(k8s): add us-west-2 warm-standby DR overlay

Reuses base + eks-shared; overrides ECR region, ElastiCache endpoint, WAF
ARN, AWS_REGION, and reduces HPA minReplicas for warm standby.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Pin the image digest across both region overlays

The deploy script currently pins only `k8s/eks/kustomization.yaml`. Extend it to pin every region overlay so both regions run the identical replicated digest.

**Files:**
- Modify: `scripts/set-eks-image-digest.sh:11` (single `KUSTOMIZATION` var → list) and the pinning/validation block (lines 41-50)

**Interfaces:**
- Consumes: the two overlay kustomizations from Tasks 1-2, each with a `digest:` line.
- Produces: `scripts/set-eks-image-digest.sh` writes the same digest into `k8s/eks/kustomization.yaml` and `k8s/eks-us-west-2/kustomization.yaml`.

- [ ] **Step 1: Replace the single-file target with a list and loop**

In `scripts/set-eks-image-digest.sh`, replace this line (line 11):
```bash
KUSTOMIZATION="$REPO_ROOT/k8s/eks/kustomization.yaml"
```
with:
```bash
# Pin every region overlay so both regions run the identical replicated digest.
KUSTOMIZATIONS=(
  "$REPO_ROOT/k8s/eks/kustomization.yaml"
  "$REPO_ROOT/k8s/eks-us-west-2/kustomization.yaml"
)
```

Then replace the validation-and-pin block (the current lines 41-50, from `if ! grep -Eq '^[[:space:]]*digest:'` through the final `echo "Pinned ..."`):
```bash
for KUSTOMIZATION in "${KUSTOMIZATIONS[@]}"; do
  if ! grep -Eq '^[[:space:]]*digest:[[:space:]]' "$KUSTOMIZATION"; then
    echo "error: no 'digest:' line found in $KUSTOMIZATION (was the overlay pinned by digest?)" >&2
    exit 1
  fi
  # Replace the value on the single 'digest:' line in this overlay's images entry.
  sed -i.bak -E "s|^([[:space:]]*digest:[[:space:]]*).*|\1${DIGEST}|" "$KUSTOMIZATION"
  rm -f "$KUSTOMIZATION.bak"
  echo "Pinned $REPO_NAME to $DIGEST in $KUSTOMIZATION"
done
```

- [ ] **Step 2: Run the script with a test digest and verify both overlays are pinned**

Run:
```bash
scripts/set-eks-image-digest.sh sha256:1111111111111111111111111111111111111111111111111111111111111111
grep -h 'digest:' k8s/eks/kustomization.yaml k8s/eks-us-west-2/kustomization.yaml
```
Expected: two lines, both showing `digest: sha256:1111...1111`. The script prints one `Pinned ...` line per overlay.

- [ ] **Step 3: Restore the placeholder digest (don't commit a fake digest)**

Run:
```bash
scripts/set-eks-image-digest.sh sha256:0000000000000000000000000000000000000000000000000000000000000000
grep -h 'digest:' k8s/eks/kustomization.yaml k8s/eks-us-west-2/kustomization.yaml
```
Expected: both lines back to `sha256:0000...0000`.

- [ ] **Step 4: Commit**

```bash
git add scripts/set-eks-image-digest.sh
git commit -m "feat(deploy): pin image digest across all region overlays

set-eks-image-digest.sh now pins k8s/eks and k8s/eks-us-west-2 to the same
digest (ECR cross-region replication keeps the digest identical).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: DR runbooks

Document the out-of-band AWS infra and the operational procedures. These are the real deliverable for the data-replication and failover layers (no IaC).

**Files:**
- Create: `docs/runbooks/dr-regional-failover.md`
- Create: `docs/runbooks/dr-data-tier-promotion.md`
- Create: `docs/runbooks/dr-failback.md`
- Create: `docs/runbooks/dr-game-day.md`
- Modify: `docs/runbooks/deploy-image-digest.md` (add a one-line note that both overlays are pinned)

**Interfaces:**
- Consumes: the overlays and script from Tasks 1-3 (runbooks reference `k8s/eks-us-west-2` and `scripts/set-eks-image-digest.sh`).
- Produces: operator-facing procedures for provisioning the DR region, failing over, promoting the data tier, and failing back.

- [ ] **Step 1: Write the regional-failover runbook**

Create `docs/runbooks/dr-regional-failover.md`:
```markdown
# Runbook: Regional DR Failover (us-east-1 → us-west-2)

Active-passive DR. `us-east-1` is primary; `us-west-2` is a warm standby running
the `k8s/eks-us-west-2` overlay. See the design at
`docs/superpowers/specs/2026-07-08-multi-region-dr-failover-design.md`.

## One-time AWS setup (out-of-band, no IaC)

1. **Second EKS cluster** in us-west-2 running the four services:
   `kubectl apply -k k8s/eks-us-west-2` (after pinning the digest — see below).
2. **ECR cross-region replication**: add a registry replication rule copying
   `recsys-backend-service` from us-east-1 to us-west-2 so the pinned digest exists
   in both regions.
3. **Aurora Global Database**: primary writer cluster in us-east-1, secondary
   read-replica cluster in us-west-2.
4. **ElastiCache Global Datastore**: primary in us-east-1, secondary (readable) in
   us-west-2.
5. **Route53 health check + failover records** (see below).

## Route53 automatic failover

- Health check: HTTPS/HTTP on the **primary** API Gateway ALB, path `/health`,
  interval 30s, failure threshold 3.
- Two failover records on the public hostname (e.g. `api.recsys.example.com`),
  TTL 30s:
  - PRIMARY → us-east-1 gateway ALB, associated with the health check.
  - SECONDARY → us-west-2 gateway ALB.
- When the health check goes unhealthy, Route53 serves the SECONDARY record
  automatically. No human action restores the **read** path.

## Deploy the standby (keep it current)

Every primary deploy must also deploy the standby so it stays warm and current:
```bash
scripts/set-eks-image-digest.sh --tag <release-tag>   # pins BOTH overlays
kubectl --context <us-east-1-ctx>  apply -k k8s/eks
kubectl --context <us-west-2-ctx>  apply -k k8s/eks-us-west-2
```

## On a us-east-1 outage

1. Confirm the outage (AWS Health Dashboard, primary ALB 5xx / failed health check).
2. Route53 has already cut DNS to us-west-2 — verify:
   `dig +short api.recsys.example.com` resolves to the us-west-2 ALB.
3. Verify the standby serves reads:
   `curl -fsS https://api.recsys.example.com/health` returns healthy.
4. **Writes are degraded until the data tier is promoted** → run
   `docs/runbooks/dr-data-tier-promotion.md`.
5. Scale-up is automatic (HPA + cluster autoscaler) as traffic arrives; watch
   `kubectl --context <us-west-2-ctx> -n recsys get hpa`.

## RTO / RPO

- Reads: seconds (DNS TTL + health-check interval).
- Writes: minutes (data-tier promotion).
- RPO: ~seconds for MySQL/Redis; streaming = in-flight events on the failed
  region's queue.
```

- [ ] **Step 2: Write the data-tier promotion runbook**

Create `docs/runbooks/dr-data-tier-promotion.md`:
```markdown
# Runbook: DR Data-Tier Promotion (us-west-2)

Run this after DNS has failed over (see `dr-regional-failover.md`) to restore the
**write** path. Reads already work from the replicas.

## 1. Promote Aurora Global Database

- Console/CLI: on the us-west-2 secondary cluster, perform "Remove from Global"
  (managed failover) or "Promote" to make it a standalone writable cluster.
- Confirm the writer endpoint is available:
  `aws rds describe-db-clusters --region us-west-2 --db-cluster-identifier <id>`
- If the app reads the DB endpoint from config, ensure `k8s/eks-us-west-2` points
  at the promoted writer endpoint (update the relevant ConfigMap/Secret and
  `kubectl apply -k k8s/eks-us-west-2`).

## 2. Promote ElastiCache Global Datastore

- Console/CLI: "Failover Global Datastore" to make the us-west-2 cluster primary
  (writable).
- Verify `k8s/eks-us-west-2/redis-elasticache-patch.yaml` `REDIS_HOST` points at
  the now-primary us-west-2 endpoint; re-apply if it changed.

## 3. Repoint streaming producers

- There is no cross-region broker replication. Point event producers (the
  streaming/ingestion tier) at the us-west-2 SQS queue / Kafka endpoint.
- Confirm the us-west-2 Flink consumers are processing:
  check the online feature store keys are advancing in the us-west-2 Redis.

## 4. Verify write path

- Exercise a feedback/write request end-to-end against
  `https://api.recsys.example.com` and confirm it persists (Aurona row / Redis
  key written in us-west-2).
```

- [ ] **Step 3: Write the failback runbook**

Create `docs/runbooks/dr-failback.md`:
```markdown
# Runbook: DR Failback (us-west-2 → us-east-1)

Return to us-east-1 as primary after it recovers. Do this deliberately during a
low-traffic window — it is a planned cutover, not an emergency.

## 1. Re-establish us-east-1 as a replica

- Rebuild the Aurora Global Database with us-east-1 as a secondary of the current
  (us-west-2) primary; let it catch up.
- Rebuild the ElastiCache Global Datastore with us-east-1 as secondary.
- Confirm replication lag is near zero before proceeding.

## 2. Deploy / warm us-east-1

```bash
scripts/set-eks-image-digest.sh --tag <current-release-tag>
kubectl --context <us-east-1-ctx> apply -k k8s/eks
kubectl --context <us-east-1-ctx> -n recsys rollout status deploy
```

## 3. Reverse the promotion, then flip DNS

- Promote us-east-1 Aurora + ElastiCache to primary (per `dr-data-tier-promotion.md`,
  reversed) and repoint streaming producers back to us-east-1.
- Re-enable the Route53 PRIMARY record's health check so it points back to
  us-east-1. Because it is the PRIMARY failover record, Route53 returns to it once
  healthy.
- Verify: `dig +short api.recsys.example.com` resolves to the us-east-1 ALB and
  `/health` is green.

## 4. Return us-west-2 to warm standby

- Confirm us-west-2 is back to secondary (read replica) and HPA minReplicas are the
  warm-standby values (gateway 1, catalog 1, model 2, online 1).
```

- [ ] **Step 4: Write the game-day runbook**

Create `docs/runbooks/dr-game-day.md`:
```markdown
# Runbook: DR Game Day

Periodic drill (quarterly) to prove the DR path works. Run in a maintenance window
with stakeholders notified.

## Objectives

- Prove Route53 fails DNS over to us-west-2 automatically.
- Prove the standby serves reads with no human action.
- Prove the data-tier promotion runbook restores writes within the RTO target.
- Measure actual RTO (reads and writes) and RPO.

## Procedure

1. **Baseline**: record current traffic, us-west-2 replica lag, HPA replica counts.
2. **Inject failure**: make the primary ALB health check fail (e.g. temporarily
   block the health-check path at the primary, or scale the primary gateway to 0
   in a non-prod mirror). Do NOT delete data.
3. **Observe DNS failover**: poll `dig +short api.recsys.example.com` until it
   resolves to the us-west-2 ALB. Record elapsed time = **read RTO**.
4. **Verify reads**: `curl -fsS https://api.recsys.example.com/health` and a real
   recommendation request succeed.
5. **Promote data tier**: run `dr-data-tier-promotion.md`. Record elapsed time =
   **write RTO**.
6. **Verify writes**: a feedback request persists in us-west-2.
7. **Fail back**: run `dr-failback.md`. Confirm return to steady state.

## Record

Capture read RTO, write RTO, RPO, and any deviations. File follow-ups for anything
that missed target (esp. warm-standby sizing — see the design's Risks section).
```

- [ ] **Step 5: Add a cross-overlay note to the digest runbook**

In `docs/runbooks/deploy-image-digest.md`, add this note near the top (after the first heading/intro paragraph):
```markdown
> **Multi-region:** `scripts/set-eks-image-digest.sh` pins the digest in **both**
> `k8s/eks` (us-east-1) and `k8s/eks-us-west-2` (DR) to the identical value. ECR
> cross-region replication makes that digest valid in both regions. Deploy the
> standby alongside the primary — see `docs/runbooks/dr-regional-failover.md`.
```

- [ ] **Step 6: Commit**

```bash
git add docs/runbooks
git commit -m "docs(dr): add regional failover, data-tier promotion, failback, and game-day runbooks

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Wire the new overlay into repo docs

Make the new region discoverable in the two docs a future engineer reads first.

**Files:**
- Modify: `.claude/CLAUDE.md` (Kubernetes section)
- Modify: `README.md` (if it documents the EKS overlay / deploy — add the DR overlay reference)

**Interfaces:**
- Consumes: everything from Tasks 1-4.
- Produces: documentation pointers so `k8s/eks-us-west-2` and the DR runbooks are discoverable.

- [ ] **Step 1: Update the Kubernetes section of CLAUDE.md**

In `.claude/CLAUDE.md`, replace the `## Kubernetes` section body:
```markdown
## Kubernetes

`k8s/base/` contains Kustomize manifests for all four services. `k8s/eks-shared/`
is a Kustomize *component* holding the region-agnostic EKS patches (IRSA, Cloud
Map, topology-aware routing, gateway ClusterIP, in-cluster Redis → 0). Each region
overlay composes `../base` + `../eks-shared` and overrides only region-specific
values:

- `k8s/eks/` — **us-east-1** (primary).
- `k8s/eks-us-west-2/` — **us-west-2** warm-standby DR (reduced HPA minReplicas,
  us-west-2 ECR/ElastiCache/WAF, `AWS_REGION=us-west-2`).

`scripts/set-eks-image-digest.sh` pins the identical digest into both overlays
(ECR cross-region replication). DR operations are documented in
`docs/runbooks/dr-*.md`; the design is
`docs/superpowers/specs/2026-07-08-multi-region-dr-failover-design.md`.
```

- [ ] **Step 2: Check whether README documents the EKS overlay and update if so**

Run:
```bash
grep -n 'k8s/eks\|kubectl apply -k\|blue/green' README.md | head
```
If there are hits describing the EKS deploy, add a sentence at the most relevant spot:
```markdown
For DR, `k8s/eks-us-west-2` is a warm-standby overlay for a second region
(us-west-2); see `docs/runbooks/dr-regional-failover.md`.
```
If there are no relevant hits, skip this step (nothing to update).

- [ ] **Step 3: Commit**

```bash
git add .claude/CLAUDE.md README.md
git commit -m "docs: document the us-west-2 DR overlay and eks-shared component

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Final full-render verification

Prove both overlays still render cleanly after all changes, as the pre-PR gate.

**Files:** none (verification only).

- [ ] **Step 1: Render both overlays and confirm no errors**

Run:
```bash
kubectl kustomize k8s/eks          > /dev/null && echo "us-east-1 OK"
kubectl kustomize k8s/eks-us-west-2 > /dev/null && echo "us-west-2 OK"
```
Expected: `us-east-1 OK` and `us-west-2 OK`.

- [ ] **Step 2: Re-confirm the us-east-1 render is still identical to the Task 1 golden**

Run:
```bash
kubectl kustomize k8s/eks | diff /private/tmp/claude-501/-Users-linghuang-Git-Recsys-Backend-Service/d1cc5d7a-6dc5-4b45-98cf-faeac2115767/scratchpad/eks-golden.yaml - && echo "us-east-1 UNCHANGED"
```
Expected: `us-east-1 UNCHANGED` (empty diff). Guards against any later task regressing the primary overlay.

- [ ] **Step 3: Confirm both digests still match**

Run:
```bash
grep -h 'digest:' k8s/eks/kustomization.yaml k8s/eks-us-west-2/kustomization.yaml | sort -u | wc -l
```
Expected: `1`.

- [ ] **Step 4: Push the branch and open a PR**

```bash
git push -u origin feat/multi-region-dr-failover
gh pr create --fill --base main
```
Expected: PR created against `main`. Do not merge directly.

---

## Self-Review

**Spec coverage:**
- Warm-standby second region (us-west-2) → Tasks 1-2. ✓
- Reduced replica counts, max unchanged → Task 2 Step 4. ✓
- MySQL (Aurora Global), ElastiCache (Global Datastore), model artifacts (ECR CRR), streaming → Task 4 runbooks (data replication is infra/out-of-band, correctly documented not coded). ✓
- Route53 automatic failover → Task 4 dr-regional-failover.md. ✓
- Read auto-failover / write-after-promotion split → Task 4 runbooks + design. ✓
- Per-region overlay + runbooks deliverable, no IaC → Tasks 1-5. ✓
- Digest identical across regions (ECR CRR) → Task 3 + verification Tasks 2/6. ✓
- us-east-1 blast-radius guard (render unchanged) → Task 1 golden diff + Task 6 re-check. ✓
- Testing = local renders + game-day → Tasks 1/2/6 renders + Task 4 dr-game-day.md. ✓

**Placeholder scan:** No TBD/TODO/"handle appropriately". Endpoint/ARN placeholders (`<...>`, `REPLACE_ME`, `123456789012`) match the repo's existing out-of-band convention and are called out as such.

**Type consistency:** Overlay/component paths consistent (`k8s/eks-shared` referenced as `../eks-shared` from both overlays); file names match between "Files" blocks and the `patches:`/`resources:` lists; `set-eks-image-digest.sh` variable rename (`KUSTOMIZATION` → `KUSTOMIZATIONS` loop) is self-consistent; warm-standby minReplicas (1/1/2/1) consistent between the patch and the verification asserts.
