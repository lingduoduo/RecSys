#!/usr/bin/env bash
# Pin the EKS image to an immutable digest in every region overlay's kustomization.yaml.
#
#   scripts/set-eks-image-digest.sh sha256:<64-hex>   # pin an explicit digest
#   scripts/set-eks-image-digest.sh --tag <ecr-tag>   # resolve the tag's digest via ECR, then pin
#
# See docs/runbooks/deploy-image-digest.md.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Pin every region overlay so both regions run the identical replicated digest.
KUSTOMIZATIONS=(
  "$REPO_ROOT/k8s/eks/kustomization.yaml"
  "$REPO_ROOT/k8s/eks-us-west-2/kustomization.yaml"
)
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

# Pass 1: validate every overlay has a digest line BEFORE touching any file,
# so a bad overlay can't leave the regions pinned to different digests.
for KUSTOMIZATION in "${KUSTOMIZATIONS[@]}"; do
  if ! grep -Eq '^[[:space:]]*digest:[[:space:]]' "$KUSTOMIZATION"; then
    echo "error: no 'digest:' line found in $KUSTOMIZATION (was the overlay pinned by digest?)" >&2
    exit 1
  fi
done

# Pass 2: pin all overlays to the same digest.
for KUSTOMIZATION in "${KUSTOMIZATIONS[@]}"; do
  sed -i.bak -E "s|^([[:space:]]*digest:[[:space:]]*).*|\1${DIGEST}|" "$KUSTOMIZATION"
  rm -f "$KUSTOMIZATION.bak"
  echo "Pinned $REPO_NAME to $DIGEST in $KUSTOMIZATION"
done
