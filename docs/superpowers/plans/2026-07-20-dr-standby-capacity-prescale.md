# DR Standby Capacity Pre-Scale Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an operator instantly promote the us-west-2 warm standby from ~50% minReplicas to the primary baseline (2/2/3/2) on a regional failover, via a declarative kustomize overlay + a wrapper script, closing the reactive cold-scale-out window.

**Architecture:** A thin `k8s/eks-us-west-2-active` overlay layers on the existing standby overlay and re-raises `minReplicas` to the base/primary values (last-patch-wins). A `scripts/dr-standby-capacity.sh` wraps `kubectl apply -k` (promote/demote) and adds an offline `verify` drift-check. Runbooks document the promote (failover), demote (failback), and dry-run (game-day) steps.

**Tech Stack:** Kustomize (`kubectl kustomize`), bash, python3 (stdlib) for the verify parser, shellcheck. No Java.

## Global Constraints

- **No application/Java code.** Manifests + one shell script + runbooks only. The Maven test suite must be unaffected.
- **Primary baseline (source of truth `k8s/base/hpa.yaml`):** `recsys-api-gateway` minReplicas **2**, `recsys-catalog-serving` **2**, `recsys-model-serving` **3**, `recsys-online-serving` **2**. maxReplicas (6/8/6/8) is inherited, never set by the active overlay.
- **Warm-standby overlay `k8s/eks-us-west-2` is unchanged** — the active overlay layers on top; failback re-applies the standby overlay.
- **Target is primary `minReplicas`, not `maxReplicas`** — HPA does the final reactive climb.
- **Namespace** for all HPAs is `recsys`.
- **Validation uses `kubectl kustomize`** (no standalone `kustomize` binary); `kubectl` is available. Overlay builds are offline (no cluster).

---

## File Structure

- Create `k8s/eks-us-west-2-active/kustomization.yaml` — layers `../eks-us-west-2` + the full-capacity patch.
- Create `k8s/eks-us-west-2-active/full-capacity-hpa-patch.yaml` — sets the four HPAs' minReplicas to 2/2/3/2.
- Create `scripts/dr-standby-capacity.sh` — `promote|demote|verify` wrapper.
- Modify `docs/runbooks/dr-regional-failover.md` — promote step + declarative-deploy caution.
- Modify `docs/runbooks/dr-failback.md` — demote step.
- Modify `docs/runbooks/dr-game-day.md` — dry-run/verify rehearsal.

---

## Task 1: `eks-us-west-2-active` overlay

**Files:**
- Create: `k8s/eks-us-west-2-active/kustomization.yaml`
- Create: `k8s/eks-us-west-2-active/full-capacity-hpa-patch.yaml`

**Interfaces:**
- Produces: a buildable overlay `k8s/eks-us-west-2-active` whose four HPAs report minReplicas 2/2/3/2, inheriting everything else from `../eks-us-west-2`.

- [ ] **Step 1: Write the overlay kustomization**

Create `k8s/eks-us-west-2-active/kustomization.yaml`:

```yaml
# Failover "active" overlay: the us-west-2 warm standby promoted to full
# PRIMARY-BASELINE capacity. Layers on ../eks-us-west-2 (warm standby, minReplicas
# 1/1/2/1) and re-raises minReplicas to the primary baseline via
# full-capacity-hpa-patch.yaml (kustomize last-patch-wins). maxReplicas is inherited
# from ../base, so HPA still surges above the baseline as traffic arrives.
#
# Apply on regional failover:  kubectl --context <us-west-2-ctx> apply -k k8s/eks-us-west-2-active
# Demote on failback:          kubectl --context <us-west-2-ctx> apply -k k8s/eks-us-west-2
# Prefer scripts/dr-standby-capacity.sh {promote|demote}.
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - ../eks-us-west-2
patches:
  - path: full-capacity-hpa-patch.yaml
```

- [ ] **Step 2: Write the full-capacity HPA patch**

Create `k8s/eks-us-west-2-active/full-capacity-hpa-patch.yaml`:

```yaml
# Restores warm-standby minReplicas to the PRIMARY baseline (source of truth:
# k8s/base/hpa.yaml). Overrides ../eks-us-west-2's warm-standby-hpa-patch.yaml.
# Keep these values equal to k8s/base/hpa.yaml — scripts/dr-standby-capacity.sh verify
# enforces it.
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: recsys-api-gateway
  namespace: recsys
spec:
  minReplicas: 2
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: recsys-catalog-serving
  namespace: recsys
spec:
  minReplicas: 2
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: recsys-model-serving
  namespace: recsys
spec:
  minReplicas: 3
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: recsys-online-serving
  namespace: recsys
spec:
  minReplicas: 2
```

- [ ] **Step 3: Verify the active overlay builds to 2/2/3/2**

Run:
```bash
kubectl kustomize k8s/eks-us-west-2-active | python3 - <<'PY'
import sys, re
for d in re.split(r'(?m)^---\s*$', sys.stdin.read()):
    if 'kind: HorizontalPodAutoscaler' not in d: continue
    n = re.search(r'(?m)^\s+name:\s+(\S+)', d)
    m = re.search(r'(?m)^\s+minReplicas:\s+(\d+)', d)
    if n and m: print(n.group(1), m.group(1))
PY
```
Expected (order may vary):
```
recsys-api-gateway 2
recsys-catalog-serving 2
recsys-model-serving 3
recsys-online-serving 2
```

- [ ] **Step 4: Verify the standby overlay is unchanged (still 1/1/2/1)**

Run:
```bash
kubectl kustomize k8s/eks-us-west-2 | python3 - <<'PY'
import sys, re
for d in re.split(r'(?m)^---\s*$', sys.stdin.read()):
    if 'kind: HorizontalPodAutoscaler' not in d: continue
    n = re.search(r'(?m)^\s+name:\s+(\S+)', d)
    m = re.search(r'(?m)^\s+minReplicas:\s+(\d+)', d)
    if n and m: print(n.group(1), m.group(1))
PY
```
Expected:
```
recsys-api-gateway 1
recsys-catalog-serving 1
recsys-model-serving 2
recsys-online-serving 1
```

> If `kubectl kustomize k8s/eks-us-west-2-active` errors on `resources: [../eks-us-west-2]`
> (older kustomize embedded in kubectl rejecting an overlay-as-resource), fall back to
> pointing the active overlay's `resources` at `../base` + `waf-api-gateway-ingress.yaml` and
> copying the `components`/`images`/`patches` blocks from `../eks-us-west-2/kustomization.yaml`
> with `full-capacity-hpa-patch.yaml` appended last. Report this if it happens — it changes the
> overlay from "layered" to "sibling", which the spec's author should note.

- [ ] **Step 5: Commit**

```bash
git add k8s/eks-us-west-2-active/
git commit -m "feat: eks-us-west-2-active overlay promoting standby to primary baseline"
```

---

## Task 2: `dr-standby-capacity.sh` (promote / demote / verify)

**Files:**
- Create: `scripts/dr-standby-capacity.sh`

**Interfaces:**
- Consumes: the `k8s/eks-us-west-2-active`, `k8s/eks-us-west-2`, and `k8s/base` overlays (Task 1 + existing).
- Produces: `dr-standby-capacity.sh {promote|demote|verify} [--context <ctx>] [--dry-run]`. `promote`/`demote` require `--context`; `verify` is offline and needs none.

- [ ] **Step 1: Write the script**

Create `scripts/dr-standby-capacity.sh`:

```bash
#!/usr/bin/env bash
# Promote/demote the us-west-2 DR warm standby's HPA capacity floor.
#
#   scripts/dr-standby-capacity.sh promote --context <us-west-2-ctx> [--dry-run]
#       Raise standby minReplicas to the PRIMARY baseline (k8s/eks-us-west-2-active).
#   scripts/dr-standby-capacity.sh demote  --context <us-west-2-ctx> [--dry-run]
#       Restore the warm-standby floor 1/1/2/1 (k8s/eks-us-west-2).
#   scripts/dr-standby-capacity.sh verify
#       Offline drift check: the active overlay's minReplicas must equal k8s/base's.
#
# See docs/runbooks/dr-regional-failover.md (promote), dr-failback.md (demote),
# and dr-game-day.md (rehearsal).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ACTIVE_OVERLAY="$REPO_ROOT/k8s/eks-us-west-2-active"
STANDBY_OVERLAY="$REPO_ROOT/k8s/eks-us-west-2"
BASE_OVERLAY="$REPO_ROOT/k8s/base"

usage() {
  echo "Usage: $0 promote --context <ctx> [--dry-run]   # standby -> primary baseline" >&2
  echo "       $0 demote  --context <ctx> [--dry-run]   # restore warm-standby floor" >&2
  echo "       $0 verify                                # offline drift check vs k8s/base" >&2
  exit 2
}

# Reads `kubectl kustomize` YAML on stdin, prints "<hpa-name> <minReplicas>" per HPA, sorted.
extract_min_replicas() {
  python3 - <<'PY'
import sys, re
docs = re.split(r'(?m)^---\s*$', sys.stdin.read())
out = {}
for d in docs:
    if 'kind: HorizontalPodAutoscaler' not in d:
        continue
    name = re.search(r'(?m)^\s+name:\s+(\S+)', d)
    mr = re.search(r'(?m)^\s+minReplicas:\s+(\d+)', d)
    if name and mr:
        out[name.group(1)] = mr.group(1)
for k in sorted(out):
    print(k, out[k])
PY
}

do_apply() {
  local overlay="$1" context="$2" dry_run="$3"
  local args=(--context "$context" apply -k "$overlay")
  if [ "$dry_run" = "true" ]; then
    args+=(--dry-run=server)
  fi
  echo "+ kubectl ${args[*]}" >&2
  kubectl "${args[@]}"
}

verify() {
  local active base
  active="$(kubectl kustomize "$ACTIVE_OVERLAY" | extract_min_replicas)"
  base="$(kubectl kustomize "$BASE_OVERLAY" | extract_min_replicas)"
  if [ "$active" = "$base" ]; then
    echo "OK: active overlay minReplicas match the primary baseline (k8s/base):"
    echo "$active"
  else
    echo "DRIFT: k8s/eks-us-west-2-active minReplicas differ from k8s/base:" >&2
    diff <(echo "$base") <(echo "$active") >&2 || true
    exit 1
  fi
}

MODE="${1:-}"
[ -n "$MODE" ] || usage
shift || true

CONTEXT=""
DRY_RUN="false"
while [ $# -gt 0 ]; do
  case "$1" in
    --context) [ $# -ge 2 ] || usage; CONTEXT="$2"; shift 2 ;;
    --dry-run) DRY_RUN="true"; shift ;;
    -h|--help) usage ;;
    *) echo "Unknown argument: $1" >&2; usage ;;
  esac
done

case "$MODE" in
  promote) [ -n "$CONTEXT" ] || { echo "promote requires --context" >&2; usage; }
           do_apply "$ACTIVE_OVERLAY" "$CONTEXT" "$DRY_RUN" ;;
  demote)  [ -n "$CONTEXT" ] || { echo "demote requires --context" >&2; usage; }
           do_apply "$STANDBY_OVERLAY" "$CONTEXT" "$DRY_RUN" ;;
  verify)  verify ;;
  -h|--help) usage ;;
  *) echo "Unknown mode: $MODE" >&2; usage ;;
esac
```

- [ ] **Step 2: Make it executable**

Run: `chmod +x scripts/dr-standby-capacity.sh`

- [ ] **Step 3: Shellcheck**

Run: `shellcheck scripts/dr-standby-capacity.sh`
Expected: no findings. If `shellcheck` is not installed, report that and skip (do not fail the task); note it was not run.

- [ ] **Step 4: Usage / arg-validation work with no cluster**

Run each and confirm it prints usage to stderr and exits non-zero, without contacting a cluster:
```bash
scripts/dr-standby-capacity.sh; echo "exit=$?"          # missing mode -> 2
scripts/dr-standby-capacity.sh --help; echo "exit=$?"   # -> 2 (usage)
scripts/dr-standby-capacity.sh promote; echo "exit=$?"  # missing --context -> 2
scripts/dr-standby-capacity.sh bogus; echo "exit=$?"    # unknown mode -> 2
```
Expected: each prints the Usage block and `exit=2`.

- [ ] **Step 5: `verify` passes (offline drift check green)**

Run: `scripts/dr-standby-capacity.sh verify`
Expected: `OK: active overlay minReplicas match the primary baseline (k8s/base):` followed by the four `name minReplicas` lines (2/2/3/2). Exit 0.

- [ ] **Step 6: Prove `verify` actually detects drift (temporary edit, then revert)**

Temporarily change `recsys-model-serving` minReplicas from `3` to `9` in `k8s/eks-us-west-2-active/full-capacity-hpa-patch.yaml`, then:
```bash
scripts/dr-standby-capacity.sh verify; echo "exit=$?"
```
Expected: `DRIFT:` diff printed to stderr, `exit=1`. Then **revert** the value back to `3` and re-run `verify` → `OK`, exit 0. (Do not commit the drift edit.)

- [ ] **Step 7: Commit**

```bash
git add scripts/dr-standby-capacity.sh
git commit -m "feat: dr-standby-capacity.sh promote/demote/verify wrapper"
```

---

## Task 3: Runbook updates

**Files:**
- Modify: `docs/runbooks/dr-regional-failover.md`
- Modify: `docs/runbooks/dr-failback.md`
- Modify: `docs/runbooks/dr-game-day.md`

**Interfaces:**
- Consumes: the overlay + script from Tasks 1–2. No new interfaces.

- [ ] **Step 1: Update the failover runbook step 5**

In `docs/runbooks/dr-regional-failover.md`, under "## On a us-east-1 outage", replace step 5:

```
5. Scale-up is automatic (HPA + cluster autoscaler) as traffic arrives; watch
   `kubectl --context <us-west-2-ctx> -n recsys get hpa`.
```

with:

```
5. **Pre-scale the standby to the primary baseline** so full traffic does not hit a
   half-capacity region while HPA reacts:
   ```bash
   scripts/dr-standby-capacity.sh promote --context <us-west-2-ctx>
   ```
   This raises minReplicas from the warm-standby floor (1/1/2/1) to the primary
   baseline (gateway 2, catalog 2, model 3, online 2) via the
   `k8s/eks-us-west-2-active` overlay; HPA + cluster-autoscaler then surge further as
   traffic arrives. Watch `kubectl --context <us-west-2-ctx> -n recsys get hpa`.

   > While failed over, deploy the **-active** overlay to keep the standby current —
   > `kubectl --context <us-west-2-ctx> apply -k k8s/eks-us-west-2-active`. A plain
   > `apply -k k8s/eks-us-west-2` would demote it back to 1/1/2/1. Restore the
   > standby floor on failback with `scripts/dr-standby-capacity.sh demote`.
```

- [ ] **Step 2: Update the failback runbook section 4**

In `docs/runbooks/dr-failback.md`, under "## 4. Return us-west-2 to warm standby", after the existing "Confirm us-west-2 is back to secondary … warm-standby values (gateway 1, catalog 1, model 2, online 1)." line, add:

```
- Demote the standby capacity back to the warm-standby floor (it was promoted to the
  primary baseline during failover):
  ```bash
  scripts/dr-standby-capacity.sh demote --context <us-west-2-ctx>
  ```
  This re-applies `k8s/eks-us-west-2`, restoring minReplicas 1/1/2/1.
```

- [ ] **Step 3: Update the game-day runbook**

In `docs/runbooks/dr-game-day.md`, under "## Procedure", add a rehearsal step:

```
- Rehearse the standby capacity promote/demote without mutating, and confirm the
  overlay has not drifted from the primary baseline:
  ```bash
  scripts/dr-standby-capacity.sh verify
  scripts/dr-standby-capacity.sh promote --context <us-west-2-ctx> --dry-run
  scripts/dr-standby-capacity.sh demote  --context <us-west-2-ctx> --dry-run
  ```
```

> Read each runbook's real surrounding formatting before editing and match it
> (heading levels, code-fence style, list markers). If a section's exact text differs
> slightly from the quoted snippet above, preserve the file's actual wording and just
> insert the new step in the right place.

- [ ] **Step 4: Baseline the Java suite is untouched + docs render**

Run: `git diff --stat`
Expected: only the three runbook files changed in this task; markdown well-formed. (No Java changed anywhere in this feature, so the Maven suite is unaffected — a full `mvn test` is unnecessary but may be run once for confidence.)

- [ ] **Step 5: Commit**

```bash
git add docs/runbooks/dr-regional-failover.md docs/runbooks/dr-failback.md docs/runbooks/dr-game-day.md
git commit -m "docs: DR runbooks — promote/demote standby capacity on failover/failback"
```

---

## Self-Review Notes (author)

- **Spec coverage:** active overlay builds to 2/2/3/2 (T1) ✓; standby unchanged 1/1/2/1 (T1 Step 4) ✓; promote/demote/verify script + shellcheck + arg validation + dry-run (T2) ✓; drift guard implemented AND proven to fire (T2 Steps 5–6) ✓; failover/failback/game-day runbooks (T3) ✓; no Java (all tasks) ✓. Acceptance criteria 1–6 mapped.
- **Validation is real, not asserted:** T1 builds and greps the actual minReplicas; T2 Step 6 deliberately introduces drift to prove `verify` exits 1 (a verify that never fails is worthless).
- **Consistency:** the four minReplicas (2/2/3/2) appear in `full-capacity-hpa-patch.yaml` and are checked against `k8s/base` by `verify` — single source of truth enforced by the drift check.
- **Pre-write checks (flagged inline):** (a) whether kubectl's embedded kustomize accepts `resources: [../eks-us-west-2]` (overlay-as-resource) — T1 Step 4 note gives the sibling-overlay fallback; (b) `shellcheck` availability — T2 Step 3 skips gracefully if absent; (c) each runbook's exact surrounding text — T3 note says match the real formatting.
