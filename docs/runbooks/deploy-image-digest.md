# Deploy: pinning the EKS image digest

The EKS overlay pins the shared image by an **immutable digest** (not a mutable tag) with
`imagePullPolicy: IfNotPresent`, so a node that already has the digest performs **zero** ECR
pull on scale-up/rollout. The digest in `k8s/eks/kustomization.yaml` is a placeholder; the deploy
process pins the real digest before applying.

> **Multi-region:** `scripts/set-eks-image-digest.sh` pins the digest in **both**
> `k8s/eks` (us-east-1) and `k8s/eks-us-west-2` (DR) to the identical value. ECR
> cross-region replication makes that digest valid in both regions. Deploy the
> standby alongside the primary — see `docs/runbooks/dr-regional-failover.md`.

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
