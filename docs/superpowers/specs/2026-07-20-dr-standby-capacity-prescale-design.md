# DR Standby Capacity Pre-Scale Design

## Objective

On a regional failover (us-east-1 → us-west-2), Route53 cuts the read path to the
warm standby automatically, but the standby runs at ~50% of primary `minReplicas`
(gateway 1 / catalog 1 / model 2 / online 1). Full production traffic then hits a
half-capacity region, and capacity is restored only *reactively* — HPA
(60–120 s stabilization) plus cluster-autoscaler node provisioning (minutes).
That cold-scale-out window is the gap this design closes.

Add an operator-run **pre-scale step**: on cutover, raise the standby's
`minReplicas` to the primary baseline (gateway 2 / catalog 2 / model 3 / online 2)
via a declarative `eks-us-west-2-active` overlay, so the standby starts at the
same floor primary runs at and HPA climbs from there instead of from 50%.

## Scope

In scope:

- A new `k8s/eks-us-west-2-active/` kustomize overlay that layers on the existing
  warm-standby overlay and re-raises `minReplicas` to the base/primary values.
- A `scripts/dr-standby-capacity.sh {promote|demote}` wrapper.
- Runbook updates: `dr-regional-failover.md`, `dr-failback.md`, `dr-game-day.md`.

Out of scope (explicit non-goals):

- **No application/Java code.** Manifests + one shell script + runbooks only.
- **No automation infra** (no Route53→CloudWatch→Lambda trigger) — this is a manual
  operator step, consistent with the DR design's manual data-tier promotion in v1.
- **The warm-standby overlay (`k8s/eks-us-west-2`) is unchanged** — the active
  overlay layers on top; failback restores the standby overlay.
- **Target is primary `minReplicas`, not `maxReplicas`** — HPA still performs the
  final reactive climb from the primary baseline (deliberate; documented).

## Background

- Base HPAs (`k8s/base/hpa.yaml`) — the source of truth for primary capacity:
  `recsys-api-gateway` min 2/max 6, `recsys-catalog-serving` min 2/max 8,
  `recsys-model-serving` min 3/max 6, `recsys-online-serving` min 2/max 8.
- `k8s/eks-us-west-2/` overlay = `../base` + `waf-api-gateway-ingress.yaml`
  resources, `../eks-shared` component, region `images`, and `patches`:
  `redis-elasticache-patch`, `region-config-patch`, `warm-standby-hpa-patch`
  (the last sets `minReplicas` to 1/1/2/1).
- Failover today: `dr-regional-failover.md` step 5 says "Scale-up is automatic
  (HPA + cluster autoscaler) as traffic arrives."
- Tooling: `kubectl kustomize <dir>` is available (no standalone `kustomize`).

## The `eks-us-west-2-active` overlay

`k8s/eks-us-west-2-active/kustomization.yaml`:

```yaml
# Failover "active" overlay: the warm standby promoted to full primary-baseline
# capacity. Layers on ../eks-us-west-2 (which sets minReplicas 1/1/2/1) and
# re-raises minReplicas to the primary baseline via full-capacity-hpa-patch.yaml.
# Apply this on regional failover; apply ../eks-us-west-2 again to demote (failback).
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - ../eks-us-west-2
patches:
  - path: full-capacity-hpa-patch.yaml
```

`k8s/eks-us-west-2-active/full-capacity-hpa-patch.yaml` — a strategic-merge patch
setting each HPA's `minReplicas` to the base/primary value (source of truth:
`k8s/base/hpa.yaml`; keep in sync — the drift check below enforces it):

```yaml
# Restores warm-standby minReplicas to the PRIMARY baseline (k8s/base/hpa.yaml).
# Kustomize last-patch-wins overrides ../eks-us-west-2's warm-standby-hpa-patch.
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

`maxReplicas` is untouched (inherited from base), so HPA can still surge above the
baseline as traffic arrives.

## The `dr-standby-capacity.sh` script

`scripts/dr-standby-capacity.sh`:

- Usage: `dr-standby-capacity.sh {promote|demote} --context <kube-context> [--dry-run]`.
- `promote` → `kubectl --context <ctx> apply -k k8s/eks-us-west-2-active`
  (standby → full primary baseline).
- `demote` → `kubectl --context <ctx> apply -k k8s/eks-us-west-2`
  (restore warm-standby 1/1/2/1).
- `--dry-run` → append `--dry-run=server` to the `kubectl apply` (rehearsable in a
  game-day against the real standby cluster without mutating). `--help` and argument
  validation must work with no cluster reachable; a server dry-run itself needs the
  `--context` to be reachable (that is the rehearsal target).
- Requires `mode` and `--context`; prints `--help` and exits non-zero on missing
  args or an unknown mode. Resolves the repo root relative to the script so it runs
  from anywhere. `set -euo pipefail`; shellcheck-clean.

## Runbook updates

- **`dr-regional-failover.md` step 5** — replace "Scale-up is automatic …" with a
  step that runs `scripts/dr-standby-capacity.sh promote --context <us-west-2-ctx>`
  immediately after cutover to pre-warm to the primary baseline (shrinking the cold
  window); HPA + cluster-autoscaler then climb the rest. Add a caution: the active
  overlay is declarative, so while failed over, deploy the **-active** overlay
  (`kubectl apply -k k8s/eks-us-west-2-active`) to keep the standby current — a
  plain `apply -k k8s/eks-us-west-2` would demote it back to 50%.
- **`dr-failback.md`** — add a step (after us-east-1 is healthy and traffic returns)
  to run `scripts/dr-standby-capacity.sh demote --context <us-west-2-ctx>` to restore
  the warm-standby floor.
- **`dr-game-day.md`** — add a rehearsal line exercising
  `dr-standby-capacity.sh promote --dry-run` and `… demote --dry-run`.

## Validation

No unit tests (manifests + shell). Validation is explicit and reproducible:

1. `kubectl kustomize k8s/eks-us-west-2-active` builds successfully and the four
   HPAs report `minReplicas` **2 / 2 / 3 / 2** (gateway / catalog / model / online).
2. `kubectl kustomize k8s/eks-us-west-2` still reports **1 / 1 / 2 / 1** (standby
   overlay unchanged).
3. **Drift guard:** the active overlay's built `minReplicas` equal `k8s/base`'s
   built `minReplicas` for all four HPAs — so if the primary baseline changes and
   `full-capacity-hpa-patch.yaml` isn't updated, it is caught. Implement as a
   `scripts/`-level check (compare the two `kubectl kustomize` outputs' HPA
   `minReplicas`) invoked in the game-day rehearsal.
4. `shellcheck scripts/dr-standby-capacity.sh` is clean; `--help` and argument
   validation work with no cluster reachable (printing usage must not require a
   cluster). A `--dry-run` promote/demote performs a server-side dry-run against the
   supplied `--context` and mutates nothing.

## Acceptance Criteria

1. `k8s/eks-us-west-2-active` overlay builds and raises the four HPAs' `minReplicas`
   to the primary baseline (2/2/3/2), inheriting everything else (region images,
   ElastiCache/region patches, eks-shared, maxReplicas) from the standby overlay.
2. The warm-standby overlay `k8s/eks-us-west-2` is unmodified and still builds to
   1/1/2/1.
3. `scripts/dr-standby-capacity.sh promote|demote --context <ctx>` applies the
   correct overlay; `--dry-run` mutates nothing; missing/invalid args fail with
   usage; shellcheck-clean.
4. The drift check confirms active `minReplicas` == base `minReplicas` for all four
   HPAs.
5. `dr-regional-failover.md`, `dr-failback.md`, and `dr-game-day.md` document the
   promote (failover), demote (failback), and dry-run (rehearsal) steps, including
   the deploy-the-active-overlay-while-failed-over caution.
6. No application/Java code changes; the full existing test suite is unaffected.
