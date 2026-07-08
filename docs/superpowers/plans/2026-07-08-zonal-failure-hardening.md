# Zonal Failure Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the four single-AZ-failure gaps in the us-east-1 deployment: enforce hard pod spread, activate the AZ-aware Redis reader, fix the recovery-blocking PDB, and document the AZ infra assumptions.

**Architecture:** Kubernetes manifest + config edits plus one documentation file. No behavioral application-code changes. Verified by local `kubectl kustomize` render assertions and the existing `RedisReadReplicaRouterTest` (the router logic is already fully tested; gap #2 is config-only).

**Tech Stack:** Kustomize (`kubectl kustomize` / `kubectl apply -k`), Kubernetes `topologySpreadConstraints` / `PodDisruptionBudget`, ElastiCache reader endpoints, Maven/JUnit (existing tests only).

## Global Constraints

- Branch is `feat/zonal-hardening` off `main`, independent of DR PR #176. Never merge to main directly — open a PR. (user workflow)
- No behavioral application-code changes; no new IaC. (spec: Non-Goals)
- No changes to HPA min/max or replica counts. (spec: Non-Goals)
- Gap #2 is scoped to `k8s/eks` (us-east-1) ONLY; `k8s/eks-us-west-2` mirror is a deferred follow-up (that overlay only exists on the DR branch). (spec: Decisions)
- Hard spread MUST use `nodeTaintsPolicy: Honor` (not naive `DoNotSchedule`) so a dead AZ's tainted nodes are excluded from the skew calc and recovery scheduling is not blocked. (spec: Gap #1)
- `REDIS_REPLICA_NODES` format is comma-separated `host:port@az` (e.g. `node-b.cache.amazonaws.com:6379@us-east-1b`). (verified: `ReplicaConfig.parse`)
- The model-serving PDB must switch from `minAvailable` to `maxUnavailable` (they are mutually exclusive in a PDB). (spec: Gap #3)
- `mvn` requires JDK 17: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`. (repo build note)

---

### Task 1: Enforce hard AZ spread on all four service Deployments

Change the shared `topologySpreadConstraints` block in each service Deployment from best-effort to enforced, with the taint policy that keeps recovery scheduling unblocked during an AZ loss.

**Files:**
- Modify: `k8s/base/api-gateway.yaml` (spread block ~lines 23-29)
- Modify: `k8s/base/catalog-serving.yaml` (spread block ~lines 23-29)
- Modify: `k8s/base/model-serving.yaml` (spread block ~lines 35-41)
- Modify: `k8s/base/online-serving.yaml` (spread block ~lines 27-33)

**Interfaces:**
- Produces: all four Deployments render `whenUnsatisfiable: DoNotSchedule` + `nodeTaintsPolicy: Honor` under their `topologySpreadConstraints`.

- [ ] **Step 1: Edit api-gateway.yaml**

In `k8s/base/api-gateway.yaml`, replace:
```yaml
          whenUnsatisfiable: ScheduleAnyway
          labelSelector:
```
with:
```yaml
          whenUnsatisfiable: DoNotSchedule
          nodeTaintsPolicy: Honor
          labelSelector:
```

- [ ] **Step 2: Edit catalog-serving.yaml**

Apply the identical replacement in `k8s/base/catalog-serving.yaml` (same two-line context → three-line result as Step 1).

- [ ] **Step 3: Edit model-serving.yaml**

Apply the identical replacement in `k8s/base/model-serving.yaml`.

- [ ] **Step 4: Edit online-serving.yaml**

Apply the identical replacement in `k8s/base/online-serving.yaml`.

- [ ] **Step 5: Render and assert all four carry the hard-spread settings**

Run:
```bash
kubectl kustomize k8s/base > /private/tmp/claude-501/-Users-linghuang-Git-Recsys-Backend-Service/d1cc5d7a-6dc5-4b45-98cf-faeac2115767/scratchpad/zh-base.yaml
echo "DoNotSchedule count (expect 4):"; grep -c 'whenUnsatisfiable: DoNotSchedule' /private/tmp/claude-501/-Users-linghuang-Git-Recsys-Backend-Service/d1cc5d7a-6dc5-4b45-98cf-faeac2115767/scratchpad/zh-base.yaml
echo "ScheduleAnyway count (expect 0):"; grep -c 'whenUnsatisfiable: ScheduleAnyway' /private/tmp/claude-501/-Users-linghuang-Git-Recsys-Backend-Service/d1cc5d7a-6dc5-4b45-98cf-faeac2115767/scratchpad/zh-base.yaml || true
echo "nodeTaintsPolicy: Honor count (expect 4):"; grep -c 'nodeTaintsPolicy: Honor' /private/tmp/claude-501/-Users-linghuang-Git-Recsys-Backend-Service/d1cc5d7a-6dc5-4b45-98cf-faeac2115767/scratchpad/zh-base.yaml
```
Expected: DoNotSchedule = 4, ScheduleAnyway = 0, nodeTaintsPolicy: Honor = 4.

- [ ] **Step 6: Commit**

```bash
git add k8s/base/api-gateway.yaml k8s/base/catalog-serving.yaml k8s/base/model-serving.yaml k8s/base/online-serving.yaml
git commit -m "feat(k8s): enforce hard AZ spread with nodeTaintsPolicy Honor

Change all four service Deployments from ScheduleAnyway to DoNotSchedule and
add nodeTaintsPolicy: Honor so a dead AZ's tainted nodes are excluded from the
skew calc (spread is enforced without wedging recovery scheduling).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Fix the recovery-blocking model-serving PDB

Switch model-serving's PDB from `minAvailable: 2` to `maxUnavailable: 1` so a degraded (2-pod) state does not block node drains / rollouts.

**Files:**
- Modify: `k8s/base/pdb.yaml` (model-serving PDB, ~line 29)

**Interfaces:**
- Produces: the `recsys-model-serving-pdb` renders `maxUnavailable: 1` and no `minAvailable`.

- [ ] **Step 1: Edit the model-serving PDB**

In `k8s/base/pdb.yaml`, within the `recsys-model-serving-pdb` resource (the one whose selector matches `app: recsys-model-serving`), replace:
```yaml
  minAvailable: 2
```
with:
```yaml
  maxUnavailable: 1
```
Do NOT change the other three PDBs (`recsys-api-gateway-pdb`, `recsys-catalog-serving-pdb`, `recsys-online-serving-pdb` — all `minAvailable: 1`).

- [ ] **Step 2: Render and assert**

Run:
```bash
kubectl kustomize k8s/base > /private/tmp/claude-501/-Users-linghuang-Git-Recsys-Backend-Service/d1cc5d7a-6dc5-4b45-98cf-faeac2115767/scratchpad/zh-base2.yaml
echo "model PDB block:"; grep -A8 'name: recsys-model-serving-pdb' /private/tmp/claude-501/-Users-linghuang-Git-Recsys-Backend-Service/d1cc5d7a-6dc5-4b45-98cf-faeac2115767/scratchpad/zh-base2.yaml | grep -E 'maxUnavailable|minAvailable'
echo "minAvailable: 1 count (expect 3 - the other PDBs):"; grep -c 'minAvailable: 1' /private/tmp/claude-501/-Users-linghuang-Git-Recsys-Backend-Service/d1cc5d7a-6dc5-4b45-98cf-faeac2115767/scratchpad/zh-base2.yaml
```
Expected: the model PDB block shows `maxUnavailable: 1` and NOT `minAvailable`; `minAvailable: 1` count = 3.

- [ ] **Step 3: Commit**

```bash
git add k8s/base/pdb.yaml
git commit -m "fix(k8s): model-serving PDB maxUnavailable:1 to unblock degraded recovery

minAvailable:2 (of 3) plus the deployment's maxUnavailable:0 blocked drains and
rollouts once an AZ loss left 2 pods. maxUnavailable:1 always permits one
voluntary disruption regardless of current replica count.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Activate the AZ-aware Redis reader (us-east-1 overlay)

Add `REDIS_REPLICA_NODES` to the EKS ElastiCache ConfigMap patch so the already-tested `RedisReadReplicaRouter` routes reads to replicas and they survive a primary-AZ failover.

**Files:**
- Modify: `k8s/eks/redis-elasticache-patch.yaml` (header comment + `data:` block)

**Interfaces:**
- Consumes: `application.yml:92` `replica-nodes: ${REDIS_REPLICA_NODES:}` → `RedisProperties.replicaNodes` → `LettuceClientFactory` (`REDIS_REPLICA_NODES` env, line 67) → `ReplicaConfig.parse` (`host:port@az`) → `RedisReadReplicaRouter`. All already present; this task only supplies the value.
- Produces: `kubectl kustomize k8s/eks` renders `recsys-config` with a `REDIS_REPLICA_NODES` key.

- [ ] **Step 1: Verify the existing router tests are green (baseline)**

The router logic is already covered by `src/test/java/com/recsys/infrastructure/redis/RedisReadReplicaRouterTest.java` (replica-when-configured, primary-fallback-when-empty, same-AZ preference). Confirm it passes before the config change so gap #2 needs no new test:
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest=RedisReadReplicaRouterTest
```
Expected: BUILD SUCCESS, tests pass.

- [ ] **Step 2: Add REDIS_REPLICA_NODES to the ElastiCache patch**

In `k8s/eks/redis-elasticache-patch.yaml`, in the `data:` block, add the `REDIS_REPLICA_NODES` line after `REDIS_PORT`:
```yaml
data:
  REDIS_MODE: "standalone"
  REDIS_HOST: "<elasticache-primary-endpoint>.cache.amazonaws.com"
  REDIS_PORT: "6379"
  # AZ-aware read replicas: comma-separated host:port@az. Use the ElastiCache
  # per-node (not the single reader) endpoints so the router can prefer a
  # same-AZ replica and reads survive a primary-AZ failover. Leave the primary
  # AZ out is fine; list the replica nodes with their AZ labels.
  REDIS_REPLICA_NODES: "<replica-node-b>.cache.amazonaws.com:6379@us-east-1b,<replica-node-c>.cache.amazonaws.com:6379@us-east-1c"
  REDIS_SENTINEL_MASTER: ""
  REDIS_SENTINEL_NODES: ""
```

- [ ] **Step 3: Update the header comment to document the reader requirement**

In the same file, add these lines to the top comment block (after the existing "Automatic failover: enabled" line):
```yaml
#   - A reader endpoint / read replicas provisioned in the other AZs, referenced
#     by REDIS_REPLICA_NODES below (host:port@az). Reads route to a same-AZ
#     replica and survive a primary-AZ failover instead of stalling ~30s.
```

- [ ] **Step 4: Render and assert the key is present**

Run:
```bash
kubectl kustomize k8s/eks > /private/tmp/claude-501/-Users-linghuang-Git-Recsys-Backend-Service/d1cc5d7a-6dc5-4b45-98cf-faeac2115767/scratchpad/zh-eks.yaml
echo "REDIS_REPLICA_NODES present (expect 1):"; grep -c 'REDIS_REPLICA_NODES' /private/tmp/claude-501/-Users-linghuang-Git-Recsys-Backend-Service/d1cc5d7a-6dc5-4b45-98cf-faeac2115767/scratchpad/zh-eks.yaml
grep 'REDIS_REPLICA_NODES' /private/tmp/claude-501/-Users-linghuang-Git-Recsys-Backend-Service/d1cc5d7a-6dc5-4b45-98cf-faeac2115767/scratchpad/zh-eks.yaml
```
Expected: count = 1; the value shows the `host:port@az,host:port@az` placeholder.

- [ ] **Step 5: Commit**

```bash
git add k8s/eks/redis-elasticache-patch.yaml
git commit -m "feat(k8s): wire REDIS_REPLICA_NODES to activate AZ-aware Redis reads (us-east-1)

Supplies the reader-endpoint value the existing RedisReadReplicaRouter needs so
reads route to same-AZ replicas and survive a primary-AZ ElastiCache failover
instead of stalling ~30s. Router logic already covered by RedisReadReplicaRouterTest.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Document the out-of-band AZ assumptions (runbook)

Write the zonal-resilience runbook and link it from CLAUDE.md.

**Files:**
- Create: `docs/runbooks/zonal-resilience.md`
- Modify: `.claude/CLAUDE.md` (Kubernetes section — add a cross-link line)

**Interfaces:**
- Consumes: the changes from Tasks 1-3 (the runbook describes the now-enforced spread and now-wired reader).
- Produces: an operator-facing document of AZ infra requirements and the degradation profile.

- [ ] **Step 1: Write the runbook**

Create `docs/runbooks/zonal-resilience.md`:
```markdown
# Runbook: Single-AZ Failure Resilience (us-east-1)

How the us-east-1 deployment survives the loss of one Availability Zone, and the
out-of-band infrastructure it assumes. This is intra-region AZ resilience — for a
full-region outage see the multi-region DR runbooks (`dr-*.md`).

## Required infrastructure (provision out-of-band)

- **ALB** spans **≥2 AZ subnets**. ALBs are always cross-zone; the WAF ALB
  (`k8s/eks/waf-api-gateway-ingress.yaml`) routes around a dead AZ's targets via
  health checks — but only if it was created across multiple AZ subnets.
- **Node groups span all 3 AZs.** Hard pod spread (`DoNotSchedule`, see below)
  requires real nodes in each AZ; if node groups collapse to one AZ, pods go
  `Pending`. Keep the managed node group / Karpenter provisioners multi-AZ.
- **ElastiCache Multi-AZ + automatic failover enabled**, with **read replicas in
  the other AZs** exposed via `REDIS_REPLICA_NODES` (host:port@az) in
  `k8s/eks/redis-elasticache-patch.yaml`.

## What survives a single-AZ loss automatically

- **Edge ingress** — cross-zone ALB health-checks around the dead AZ's pods.
- **In-cluster routing** — `trafficDistribution: PreferClose` falls back
  cluster-wide when the local AZ has no ready endpoints (no black-holing).
- **Pod placement** — enforced spread (`topologySpreadConstraints` with
  `maxSkew: 1`, `whenUnsatisfiable: DoNotSchedule`, `nodeTaintsPolicy: Honor`)
  guarantees replicas sit in different AZs, so a single-AZ loss always leaves ≥1
  replica per service. `nodeTaintsPolicy: Honor` excludes the dead AZ's tainted
  nodes from the skew calc, so replacement pods still schedule in surviving AZs.
- **Redis reads** — `RedisReadReplicaRouter` prefers a same-AZ replica; a
  primary-AZ loss no longer stalls reads (they route to a surviving replica).

## Expected degradation profile

- Brief endpoint-not-ready blip until readiness probes mark the dead-AZ pods out
  (~15s: `periodSeconds: 5` × `failureThreshold: 3`); app-layer retry/timeouts
  (recall 200ms fail-open, route circuit breakers, load shedding, stale-TTL
  caches) absorb it.
- **Writes** still ride the ElastiCache primary's Multi-AZ DNS failover (~30s) if
  the primary's AZ is the one lost. Reads are covered by the replica router.
- HPA re-scales survivors onto remaining capacity (node headroom permitting).

## Operator checklist during a suspected AZ event

1. Confirm the AZ impact (AWS Health Dashboard).
2. Verify each service still has Ready pods in the surviving AZs:
   `kubectl -n recsys get pods -o wide` (check the NODE/zone spread).
3. Confirm no service is stuck with `Pending` pods (would indicate node groups
   are not multi-AZ — see Required infrastructure).
4. If the ElastiCache primary was in the lost AZ, expect a ~30s write blip during
   its DNS failover; reads should stay up via `REDIS_REPLICA_NODES`.
5. After AZ recovery, confirm pods rebalance and PDBs are satisfied.
```

- [ ] **Step 2: Add the cross-link to CLAUDE.md**

In `.claude/CLAUDE.md`, in the `## Kubernetes` section, add this line at the end of the section body:
```markdown
Single-AZ failure resilience (pod spread, AZ-aware Redis reads, PDB tuning) is
documented in `docs/runbooks/zonal-resilience.md`.
```

- [ ] **Step 3: Commit**

```bash
git add docs/runbooks/zonal-resilience.md .claude/CLAUDE.md
git commit -m "docs(zonal): add single-AZ resilience runbook + CLAUDE.md link

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Final verification + PR

**Files:** none (verification only).

- [ ] **Step 1: Render both kustomizations cleanly**

Run:
```bash
kubectl kustomize k8s/base >/dev/null && echo "base OK"
kubectl kustomize k8s/eks  >/dev/null && echo "eks OK"
```
Expected: `base OK` and `eks OK`.

- [ ] **Step 2: Run the full test suite**

Run:
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test
```
Expected: BUILD SUCCESS. (No production code changed; this confirms nothing regressed and the existing router tests still pass.)

- [ ] **Step 3: Confirm the four hardening changes are all present**

Run:
```bash
kubectl kustomize k8s/base | grep -c 'nodeTaintsPolicy: Honor'   # expect 4
kubectl kustomize k8s/base | grep -A8 'recsys-model-serving-pdb' | grep -c 'maxUnavailable: 1'  # expect 1
kubectl kustomize k8s/eks  | grep -c 'REDIS_REPLICA_NODES'        # expect 1
test -f docs/runbooks/zonal-resilience.md && echo "runbook present"
```
Expected: 4, 1, 1, and `runbook present`.

- [ ] **Step 4: Push and open a PR**

```bash
git push -u origin feat/zonal-hardening
gh pr create --fill --base main
```
Expected: PR created against `main`. Do not merge directly.

---

## Self-Review

**Spec coverage:**
- Gap #1 hard spread + `nodeTaintsPolicy: Honor` → Task 1. ✓
- Gap #2 wire `REDIS_REPLICA_NODES` (us-east-1 only) → Task 3; us-west-2 mirror correctly deferred (that overlay isn't on this branch). ✓
- Gap #3 model PDB `maxUnavailable: 1` → Task 2. ✓
- Gap #4 zonal-resilience runbook + CLAUDE.md link → Task 4. ✓
- Testing: render assertions (Tasks 1/2/3/5), existing router tests + full suite (Tasks 3/5). ✓ — note the spec's "one unit test" is satisfied by the *existing* `RedisReadReplicaRouterTest` (adding a duplicate would be a test-hygiene defect), so no new test task; this is an intentional deviation flagged to the user.

**Placeholder scan:** No TBD/TODO. Endpoint placeholders (`<...>`) follow the repo's existing out-of-band convention (matching `REDIS_HOST`).

**Type/consistency:** `REDIS_REPLICA_NODES` format `host:port@az` matches `ReplicaConfig.parse` exactly. PDB switches `minAvailable`→`maxUnavailable` (mutually exclusive — correct). Render-assertion counts are internally consistent (4 deployments, 1 model PDB, 3 other PDBs at `minAvailable: 1`).
