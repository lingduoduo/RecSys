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
# Usage:
#   ./scripts/invalidate-cdn.sh                      # invalidate /similar (the common case)
#   ./scripts/invalidate-cdn.sh '/api/catalog/*'     # invalidate all catalog reads
set -euo pipefail

COMMENT="recsys-edge"
PATHS="${1:-/api/catalog/similar*}"

dist_id="$(aws cloudfront list-distributions \
  --query "DistributionList.Items[?Comment=='${COMMENT}'].Id" --output text 2>/dev/null || true)"

if [[ -z "$dist_id" || "$dist_id" == "None" ]]; then
  echo "ERROR: no distribution found with Comment='${COMMENT}'." >&2
  echo "Run ./scripts/create-cdn-distribution.sh first." >&2
  exit 1
fi

echo "Invalidating '${PATHS}' on ${dist_id}"
aws cloudfront create-invalidation --distribution-id "$dist_id" --paths "$PATHS" \
  --query 'Invalidation.{Id:Id,Status:Status}' --output table

echo "Invalidations take ~1-3 min to complete. Check with:"
echo "  aws cloudfront list-invalidations --distribution-id ${dist_id}"
