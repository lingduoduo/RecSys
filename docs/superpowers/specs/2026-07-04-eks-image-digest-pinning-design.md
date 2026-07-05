# EKS Image Digest Pinning — Design

**Date:** 2026-07-04
**Status:** Approved (pending spec review)
**Component:** EKS deploy overlay (`k8s/eks/`) + deploy tooling

## Problem

The EKS overlay pins the shared image by the **mutable tag `latest`** and forces
`imagePullPolicy: Always` on all four Deployments ([k8s/eks/image-pull-policy-patch.yaml](../../../k8s/eks/image-pull-policy-patch.yaml)).
Consequence: every pod, on every rollout and every autoscale scale-up, contacts ECR to
re-resolve the tag — even when the image is byte-identical to what the node already has cached.
With `topologySpreadConstraints` fanning pods across AZs (each new node a fresh pull) and HPA
maxes summing to 28 pods, this is redundant registry traffic on the deploy/scale path.

The larger image-distribution wins (image size, ONNX native strip, layer split) are already done.
Digest pinning is the remaining in-repo lever: an **immutable** reference lets `IfNotPresent`
short-circuit the pull entirely on a node that already has that digest.

## Goal

Pin the EKS image by immutable digest and revert to `IfNotPresent`, so a scale-up or rollout onto
a node that already has the digest performs **zero** ECR contact — eliminating redundant
registry round-trips on the deploy path — while keeping deploys correct and auditable.

## Non-Goals

- No change to the base overlay (`k8s/base/` stays `newTag: local` for local dev).
- No CI (the repo has none) — the digest is updated by a helper script + runbook at deploy time.
- No infra changes (ECR VPC endpoints, pull-through cache, peer-to-peer distribution) — those are
  cluster/Terraform-level and out of scope for this app repo.
- No app-code changes.

## Why this is safe (and better than the status quo)

Pinning by digest is *safer* than mutable-tag + `Always`:
- A new digest produces a new pod template → a normal RollingUpdate honoring existing
  `maxSurge`/PDBs. Already-running pods keep their previously-cached digest.
- Rollback is re-pinning the previous digest and applying — no node ever silently serves a
  stale-but-differently-tagged image (the exact failure mode the `Always` patch was working
  around).
- `IfNotPresent` + immutable digest cannot serve the wrong image: a cached digest *is* that image.

## Components

### 1. `k8s/eks/kustomization.yaml` — pin by digest

Change the `images:` entry from `newTag: latest` to a `digest:` (mutually exclusive with
`newTag`). Kustomize renders `…/recsys-backend-service@sha256:…`:

```yaml
images:
  - name: recsys-backend-service
    newName: 123456789012.dkr.ecr.us-east-1.amazonaws.com/recsys-backend-service
    # Immutable digest. PLACEHOLDER — the deploy process pins the real digest via
    # scripts/set-eks-image-digest.sh before `kubectl apply`. See docs/runbooks/deploy-image-digest.md.
    digest: sha256:0000000000000000000000000000000000000000000000000000000000000000
```

Also remove the `image-pull-policy-patch.yaml` entry from the `patches:` list.

### 2. Delete `k8s/eks/image-pull-policy-patch.yaml`

Removing the patch reverts all four Deployments to the base default `imagePullPolicy: IfNotPresent`.
This is the piece that yields the traffic saving (a cached digest → no ECR contact).

### 3. `scripts/set-eks-image-digest.sh` — pin the digest

- Usage:
  - `scripts/set-eks-image-digest.sh sha256:<64-hex>` — pin an explicit digest.
  - `scripts/set-eks-image-digest.sh --tag <tag>` — resolve via
    `aws ecr describe-images --repository-name recsys-backend-service --image-ids imageTag=<tag>
    --query 'imageDetails[0].imageDigest' --output text`, then pin.
- Validates the digest against `^sha256:[0-9a-f]{64}$`; exits non-zero otherwise.
- Pins into `k8s/eks/kustomization.yaml` via `kustomize edit set image
  recsys-backend-service=<newName>@<digest>` when the standalone `kustomize` binary is available,
  else a targeted in-place edit of the `digest:` line (the plan selects the mechanism after
  checking tool availability). Idempotent: re-running with the same digest yields no diff.
- `set -euo pipefail`; fails loudly on a bad digest or missing image.

### 4. `docs/runbooks/deploy-image-digest.md` — deploy + blue/green flow

Documents the deploy sequence — build → `docker push` → `scripts/set-eks-image-digest.sh
--tag <tag>` → `kubectl apply -k k8s/eks` — and the **revised blue/green flow**: pin the new
digest and apply (new digest → new ReplicaSet → RollingUpdate); to roll back, re-pin the previous
digest and apply. Notes that the committed digest is a placeholder that MUST be pinned before apply.

## Testing (all local; no cluster / no AWS — matches the repo's `kubectl kustomize`-only pattern)

1. **Render assertion:** run `scripts/set-eks-image-digest.sh sha256:<64 fake hex>`, then
   `kubectl kustomize k8s/eks` → assert:
   - every `image:` is `…/recsys-backend-service@sha256:<that>` (digest ref),
   - no `:latest` and no `imagePullPolicy: Always` anywhere in the render,
   - the four Deployments show `imagePullPolicy: IfNotPresent`.
2. **Script checks:** a malformed digest (`sha256:xyz`) → non-zero exit, kustomization unchanged;
   a valid digest rewrites the file idempotently (running twice → no diff).
3. **Base unaffected:** `kubectl kustomize k8s/base` still renders `recsys-backend-service:local`.

## Files Touched

- Modify: `k8s/eks/kustomization.yaml` (images `newTag` → `digest`; drop the pull-policy patch ref).
- Delete: `k8s/eks/image-pull-policy-patch.yaml`.
- Create: `scripts/set-eks-image-digest.sh`.
- Create: `docs/runbooks/deploy-image-digest.md`.
- Create: a small test (shell/bats-style or a documented `kubectl kustomize` assertion script) under
  the repo's test conventions — the plan will place it where verification scripts live.
