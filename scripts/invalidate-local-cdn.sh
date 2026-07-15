#!/usr/bin/env bash
# Local counterpart to scripts/invalidate-cdn.sh.
#
# IMPORTANT DIVERGENCE: this purges the ENTIRE cache. nginx OSS has no path-scoped purge
# (proxy_cache_purge is nginx Plus or a third-party module), whereas CloudFront invalidates
# by path pattern. Coarser than the real thing — see docs/runbooks/cdn-local.md.
set -euo pipefail

CONTAINER="${CDN_CONTAINER:-recsys-cdn}"

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "ERROR: container '${CONTAINER}' is not running." >&2
  echo "Start it with: docker compose -f docker-compose.cdn.yml up -d" >&2
  exit 1
fi

echo "Purging the entire local CDN cache in ${CONTAINER}"
docker exec "$CONTAINER" find /var/cache/nginx -type f -delete
echo "Done. The next request for any path will be a MISS."
