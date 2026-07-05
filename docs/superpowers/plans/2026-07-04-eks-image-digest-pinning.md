# EKS Image Digest Pinning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pin the EKS image by immutable digest and revert to `IfNotPresent`, so a scale-up/rollout onto a node that already has the digest performs zero ECR contact — cutting redundant registry round-trips on the deploy path.

**Architecture:** EKS Kustomize overlay change (`newTag: latest` → `digest:`; drop the `Always` pull-policy patch), plus a `sed`-based helper script that pins a digest into the kustomization and a deploy/blue-green runbook. Verification is local `kubectl kustomize` render assertions (the repo's established pattern — no CI, no cluster needed).

**Tech Stack:** Kustomize (via `kubectl kustomize` — the standalone `kustomize`/`yq`/`bats` binaries are NOT installed), Bash + `sed`, AWS CLI (`aws ecr`, only on the real deploy path), ECR.

## Global Constraints

- EKS overlay only. The base overlay (`k8s/base/kustomization.yaml`) stays `newTag: local` for local dev — do NOT touch it.
- The committed digest is a **placeholder** (`sha256:` + 64 zeros) with a comment saying the deploy process pins the real digest before `kubectl apply`.
- `imagePullPolicy` must become `IfNotPresent` for all four app containers (achieved by deleting the `Always` patch — the base already defaults to `IfNotPresent`).
- No standalone `kustomize`/`yq`/`bats` binary exists — the helper edits the file with `sed`; tests use `kubectl kustomize` (confirmed to support the `digest:` field) and plain shell assertions.
- No app-code changes. No infra (VPC endpoints / pull-through cache) — out of scope.
- Render tool: `kubectl kustomize k8s/eks` (works; embedded kustomize renders `…@sha256:…`).
- Commit config (Task 1) and tooling (Task 2) separately. Work stays on branch `feat/eks-image-digest-pinning` (already created). Do not merge to main.

## File Structure

- **Modify** `k8s/eks/kustomization.yaml` — images `newTag: latest` → `digest: <placeholder>`; remove the `image-pull-policy-patch.yaml` entry from `patches:`.
- **Delete** `k8s/eks/image-pull-policy-patch.yaml`.
- **Create** `scripts/set-eks-image-digest.sh` — pins a digest into the kustomization.
- **Create** `docs/runbooks/deploy-image-digest.md` — deploy + blue/green + rollback flow.

---

### Task 1: Pin the EKS image by digest and drop the forced-Always patch

**Files:**
- Modify: `k8s/eks/kustomization.yaml`
- Delete: `k8s/eks/image-pull-policy-patch.yaml`

**Interfaces:**
- Consumes: nothing.
- Produces: the EKS render now references `…/recsys-backend-service@sha256:<placeholder>` with `imagePullPolicy: IfNotPresent` on all four app containers; no `:latest`, no `Always`.

- [ ] **Step 1 (RED): confirm the current render has the mutable tag + Always**

Run:
```bash
cd /Users/linghuang/Git/Recsys-Backend-Service
kubectl kustomize k8s/eks > /tmp/eks-before.yaml
echo "recsys :latest refs: $(grep 'recsys-backend-service' /tmp/eks-before.yaml | grep -c ':latest')"
echo "Always policies:      $(grep -c 'imagePullPolicy: Always' /tmp/eks-before.yaml)"
```
Expected (RED — the state we are removing): `recsys :latest refs: 4` and `Always policies: 4`.

- [ ] **Step 2 (implement): pin the digest in `k8s/eks/kustomization.yaml`**

Replace the images block:
```yaml
images:
  - name: recsys-backend-service
    newName: 123456789012.dkr.ecr.us-east-1.amazonaws.com/recsys-backend-service
    newTag: latest
```
with:
```yaml
images:
  - name: recsys-backend-service
    newName: 123456789012.dkr.ecr.us-east-1.amazonaws.com/recsys-backend-service
    # Immutable digest — a cached node skips ECR on scale-up (IfNotPresent). This value is a
    # PLACEHOLDER; the deploy process pins the real digest with scripts/set-eks-image-digest.sh
    # before `kubectl apply`. See docs/runbooks/deploy-image-digest.md.
    digest: sha256:0000000000000000000000000000000000000000000000000000000000000000
```

- [ ] **Step 3 (implement): drop the Always pull-policy patch**

3a. In `k8s/eks/kustomization.yaml`, remove these two lines from the `patches:` list:
```yaml
  # Force Always pull policy so ECR tag reassignment is picked up on every rollout.
  - path: image-pull-policy-patch.yaml
```

3b. Delete the patch file:
```bash
git rm k8s/eks/image-pull-policy-patch.yaml
```

- [ ] **Step 4 (GREEN): render and assert the new state**

Run:
```bash
cd /Users/linghuang/Git/Recsys-Backend-Service
kubectl kustomize k8s/eks > /tmp/eks-after.yaml
echo "digest refs:     $(grep 'recsys-backend-service' /tmp/eks-after.yaml | grep -c '@sha256:0000000000000000000000000000000000000000000000000000000000000000')"
echo "latest refs:     $(grep 'recsys-backend-service' /tmp/eks-after.yaml | grep -c ':latest')"
echo "Always policies: $(grep -c 'imagePullPolicy: Always' /tmp/eks-after.yaml)"
echo "IfNotPresent:    $(grep -c 'imagePullPolicy: IfNotPresent' /tmp/eks-after.yaml)"
```
Expected (GREEN): `digest refs: 4`, `latest refs: 0`, `Always policies: 0`, `IfNotPresent: 4` (or more — the four app containers now inherit the base `IfNotPresent`).

Also confirm the render still succeeds with exit 0 (no kustomize error): the four lines above printing without an error means the overlay is valid.

- [ ] **Step 5: Commit**

```bash
git add k8s/eks/kustomization.yaml
git rm --cached k8s/eks/image-pull-policy-patch.yaml 2>/dev/null || true
git commit -m "perf(deploy): pin EKS image by digest + IfNotPresent, drop forced-Always pull

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```
(The `git rm` in Step 3b already staged the deletion; the commit captures both the kustomization edit and the file removal.)

---

### Task 2: Digest-pinning helper script + deploy runbook

**Files:**
- Create: `scripts/set-eks-image-digest.sh`
- Create: `docs/runbooks/deploy-image-digest.md`

**Interfaces:**
- Consumes: the `digest:` line in `k8s/eks/kustomization.yaml` created by Task 1.
- Produces: `scripts/set-eks-image-digest.sh sha256:<64-hex>` (or `--tag <tag>`) rewrites that `digest:` line; a runbook documenting the deploy/blue-green/rollback flow.

- [ ] **Step 1 (RED): confirm the script does not exist yet**

Run:
```bash
cd /Users/linghuang/Git/Recsys-Backend-Service
test -x scripts/set-eks-image-digest.sh && echo "exists" || echo "absent (expected)"
```
Expected: `absent (expected)`.

- [ ] **Step 2 (implement): create `scripts/set-eks-image-digest.sh`**

```bash
#!/usr/bin/env bash
# Pin the EKS image to an immutable digest in k8s/eks/kustomization.yaml.
#
#   scripts/set-eks-image-digest.sh sha256:<64-hex>   # pin an explicit digest
#   scripts/set-eks-image-digest.sh --tag <ecr-tag>   # resolve the tag's digest via ECR, then pin
#
# See docs/runbooks/deploy-image-digest.md.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KUSTOMIZATION="$REPO_ROOT/k8s/eks/kustomization.yaml"
REPO_NAME="recsys-backend-service"

usage() {
  echo "Usage: $0 sha256:<64-hex>          # pin an explicit digest" >&2
  echo "       $0 --tag <ecr-image-tag>    # resolve the tag's digest via ECR, then pin" >&2
  exit 2
}

case "${1:-}" in
  --tag)
    [ $# -eq 2 ] || usage
    DIGEST="$(aws ecr describe-images \
        --repository-name "$REPO_NAME" \
        --image-ids "imageTag=$2" \
        --query 'imageDetails[0].imageDigest' \
        --output text)"
    ;;
  "" ) usage ;;
  * )
    [ $# -eq 1 ] || usage
    DIGEST="$1"
    ;;
esac

if ! printf '%s' "$DIGEST" | grep -Eq '^sha256:[0-9a-f]{64}$'; then
  echo "error: not a valid image digest: '$DIGEST'" >&2
  exit 1
fi

if ! grep -Eq '^[[:space:]]*digest:[[:space:]]' "$KUSTOMIZATION"; then
  echo "error: no 'digest:' line found in $KUSTOMIZATION (was the overlay pinned by digest?)" >&2
  exit 1
fi

# Replace the value on the single 'digest:' line in the EKS kustomization images entry.
sed -i.bak -E "s|^([[:space:]]*digest:[[:space:]]*).*|\1${DIGEST}|" "$KUSTOMIZATION"
rm -f "$KUSTOMIZATION.bak"

echo "Pinned $REPO_NAME to $DIGEST in $KUSTOMIZATION"
```

Make it executable:
```bash
chmod +x scripts/set-eks-image-digest.sh
```

- [ ] **Step 3 (GREEN): exercise the script (run-and-restore; the committed kustomization keeps the placeholder)**

```bash
cd /Users/linghuang/Git/Recsys-Backend-Service
FAKE=sha256:1111111111111111111111111111111111111111111111111111111111111111

# pins the fake digest
scripts/set-eks-image-digest.sh "$FAKE"
grep -q "digest: $FAKE" k8s/eks/kustomization.yaml && echo "file-updated OK"

# render uses it on all four app images
echo "render digest refs: $(kubectl kustomize k8s/eks | grep 'recsys-backend-service' | grep -c "@$FAKE")"   # expect 4

# idempotent: a second run produces no change
cp k8s/eks/kustomization.yaml /tmp/k-after-1
scripts/set-eks-image-digest.sh "$FAKE"
diff -q /tmp/k-after-1 k8s/eks/kustomization.yaml && echo "idempotent OK"

# NEGATIVE: malformed digest is rejected non-zero and does not touch the file
cp k8s/eks/kustomization.yaml /tmp/k-before-bad
if scripts/set-eks-image-digest.sh sha256:xyz; then echo "BUG: bad digest accepted"; else echo "bad-digest rejected OK"; fi
diff -q /tmp/k-before-bad k8s/eks/kustomization.yaml && echo "unchanged-on-reject OK"

# NEGATIVE: no args -> usage, exit 2
if scripts/set-eks-image-digest.sh; then echo "BUG: no-arg accepted"; else echo "no-arg usage OK"; fi

# RESTORE the placeholder so only the script/runbook get committed (not the fake test digest)
git checkout k8s/eks/kustomization.yaml
grep -q "digest: sha256:0000000000000000000000000000000000000000000000000000000000000000" k8s/eks/kustomization.yaml && echo "placeholder restored OK"
```
Expected lines: `file-updated OK`, `render digest refs: 4`, `idempotent OK`, `bad-digest rejected OK`, `unchanged-on-reject OK`, `no-arg usage OK`, `placeholder restored OK`.

- [ ] **Step 4 (implement): create `docs/runbooks/deploy-image-digest.md`**

```markdown
# Deploy: pinning the EKS image digest

The EKS overlay pins the shared image by an **immutable digest** (not a mutable tag) with
`imagePullPolicy: IfNotPresent`, so a node that already has the digest performs **zero** ECR
pull on scale-up/rollout. The digest in `k8s/eks/kustomization.yaml` is a placeholder; the deploy
process pins the real digest before applying.

## Deploy

```bash
# 1. Build and push the image to ECR (tag is just a human label; the digest is what deploys).
DOCKER_BUILDKIT=1 docker build -t <ecr-repo>/recsys-backend-service:<tag> .
docker push <ecr-repo>/recsys-backend-service:<tag>

# 2. Pin the pushed image's digest into the overlay.
scripts/set-eks-image-digest.sh --tag <tag>        # resolves the digest via ECR
#   or, if you already have it:
scripts/set-eks-image-digest.sh sha256:<64-hex>

# 3. Review and apply.
git diff k8s/eks/kustomization.yaml
kubectl apply -k k8s/eks
```

## Blue/green

Instead of reassigning a mutable `latest` tag, blue/green is a digest change:

1. Push the new (green) image; `scripts/set-eks-image-digest.sh --tag <green-tag>`.
2. `kubectl apply -k k8s/eks` — the new digest creates a new pod template → a normal RollingUpdate
   (honoring each Deployment's `maxSurge`/PDB). Already-running (blue) pods keep their cached digest.
3. Watch `kubectl rollout status deploy/<name> -n recsys`.

## Rollback

Re-pin the previous (blue) digest and apply:

```bash
scripts/set-eks-image-digest.sh sha256:<previous-digest>
kubectl apply -k k8s/eks
```

No node ever serves a stale-but-differently-tagged image: with an immutable digest, a cached
digest *is* that exact image.

## Notes

- The committed digest is a placeholder (`sha256:` + 64 zeros); pinning a real digest is a required
  deploy step. Applying the placeholder as-is fails to pull (by design).
- `scripts/set-eks-image-digest.sh` validates the digest format and edits only the `digest:` line in
  `k8s/eks/kustomization.yaml`. The base overlay (`k8s/base`) is untouched (`newTag: local` for local dev).
```

- [ ] **Step 5 (GREEN): sanity-check the runbook + tree state**

```bash
cd /Users/linghuang/Git/Recsys-Backend-Service
test -f docs/runbooks/deploy-image-digest.md && echo "runbook exists OK"
# working tree should show only the new script + runbook (kustomization was restored to the placeholder in Step 3)
git status --porcelain
```
Expected: `runbook exists OK`; `git status` shows only the new untracked `scripts/set-eks-image-digest.sh` and `docs/runbooks/deploy-image-digest.md` (kustomization.yaml NOT modified — it was committed in Task 1 and restored to placeholder in Step 3).

- [ ] **Step 6: Commit**

```bash
git add scripts/set-eks-image-digest.sh docs/runbooks/deploy-image-digest.md
git commit -m "docs(deploy): add digest-pinning helper script + deploy/blue-green runbook

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Pin by digest (`newTag` → `digest` placeholder) → Task 1 Step 2. ✓
- Drop `image-pull-policy-patch.yaml` + revert to `IfNotPresent` → Task 1 Steps 3–4. ✓
- Helper script (explicit digest + `--tag` via ECR, validates format, idempotent) → Task 2 Step 2. ✓
- Runbook (deploy + blue/green + rollback + placeholder note) → Task 2 Step 4. ✓
- Base overlay untouched → Global Constraints; not modified in any task. ✓
- Testing: render assertions (digest ref, no latest, no Always, IfNotPresent), script pins+renders, bad-digest rejected, idempotent, base unaffected → Task 1 Step 4 + Task 2 Step 3. ✓

**Placeholder scan:** No TBD/TODO. Every step has exact file content and commands. The `sha256:0000…`/`sha256:1111…` values are intentional placeholders/test fixtures, not plan gaps. ✓

**Consistency:** The placeholder digest (`sha256:` + 64 zeros) is identical in Task 1 Step 2, Task 1 Step 4 grep, Task 2 Step 3 restore-check, and the runbook. The repo name `recsys-backend-service` and kustomization path `k8s/eks/kustomization.yaml` are consistent across script, tasks, and runbook. ✓

**Risk notes (verify at implementation):**
- `kubectl kustomize k8s/eks` supports the `digest:` field — pre-verified during planning (renders `…@sha256:…` + `IfNotPresent`). Task 1 Step 4 re-confirms on the real overlay.
- The Task 2 Step 3 test pins a FAKE digest into the tracked kustomization, then `git checkout`s it — the committed file must keep the placeholder, not the fake. Step 5 asserts the tree shows only the script + runbook.
- `sed -i.bak` is used for BSD/GNU portability (macOS build host); the `.bak` is removed after.
