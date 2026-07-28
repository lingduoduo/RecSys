#!/usr/bin/env bash
# Invalidate cached catalog paths after a bulk embedding or catalog reload.
#
# POST /setembedding rewrites the vectors behind /similar, so a bulk reload leaves the edge
# serving stale neighbours for up to its 300s fresh window (plus 3600s stale-while-revalidate).
#
# Invalidation is deliberately operator-triggered, NOT wired into the write path: per-write
# invalidation during a bulk load would issue thousands of API calls and blow through
# CloudFront's 1,000-free-invalidation-path quota. One bulk reload, one wildcard invalidation.
#
# CloudFront invalidation patterns glob only at the tail (a pattern's "*" only ever anchors the
# end of the string). A pattern rooted at /api/catalog/... can NEVER also match
# /api/v1/catalog/..., because the versioned request has an extra path segment BEFORE the glob,
# not after it. The unversioned and versioned catalog reads are separate CacheBehaviors
# (Quantity: 4 in create-cdn-distribution.sh), so both spellings must be purged explicitly or
# one of them keeps serving stale data for the full stale-while-revalidate window.
#
# Usage:
#   ./scripts/invalidate-cdn.sh                                            # both spellings of /similar (the common case)
#   ./scripts/invalidate-cdn.sh --paths '/api/catalog/*' '/api/v1/catalog/*'   # all catalog reads, both spellings
#
# There is no bare-positional-argument form: a path pattern MUST be preceded by --paths, since
# both the unversioned and versioned spellings normally need to be listed together (see above).
set -euo pipefail

COMMENT="recsys-edge"

if [[ $# -eq 0 ]]; then
  PATHS=("/api/catalog/similar*" "/api/v1/catalog/similar*")
elif [[ "$1" == "--paths" ]]; then
  shift
  PATHS=("$@")
  if [[ ${#PATHS[@]} -eq 0 ]]; then
    echo "ERROR: --paths requires at least one path pattern." >&2
    exit 1
  fi
else
  echo "ERROR: unrecognized argument '$1'." >&2
  echo "Path patterns must be passed via --paths, e.g.:" >&2
  echo "  ./scripts/invalidate-cdn.sh --paths '$1' '<versioned-equivalent>'" >&2
  exit 1
fi

dist_id="$(aws cloudfront list-distributions \
  --query "DistributionList.Items[?Comment=='${COMMENT}'].Id" --output text 2>/dev/null || true)"

if [[ -z "$dist_id" || "$dist_id" == "None" ]]; then
  echo "ERROR: no distribution found with Comment='${COMMENT}'." >&2
  echo "Run ./scripts/create-cdn-distribution.sh first." >&2
  exit 1
fi

echo "Invalidating [${PATHS[*]}] on ${dist_id}"
aws cloudfront create-invalidation --distribution-id "$dist_id" --paths "${PATHS[@]}" \
  --query 'Invalidation.{Id:Id,Status:Status}' --output table

echo "Invalidations take ~1-3 min to complete. Check with:"
echo "  aws cloudfront list-invalidations --distribution-id ${dist_id}"
