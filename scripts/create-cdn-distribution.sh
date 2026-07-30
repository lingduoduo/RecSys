#!/usr/bin/env bash
# Create or update the recsys CloudFront distribution.
#
# Idempotent: keyed on the distribution Comment "recsys-edge". Re-running with changed inputs
# updates the existing distribution rather than creating a second one.
#
# Follows the same out-of-band convention as docs/runbooks/waf-webacl.md — this repo has no IaC.
# See docs/superpowers/specs/2026-07-14-cdn-edge-acceleration-design.md.
#
# Usage:
#   ORIGIN_DOMAIN=origin.recsys.example.com \
#   ALIAS_DOMAIN=app.recsys.example.com \
#   ACM_CERT_ARN=arn:aws:acm:us-east-1:<acct>:certificate/<id> \
#   WEB_ACL_ARN=arn:aws:wafv2:us-east-1:<acct>:global/webacl/recsys-edge/<id> \
#   ORIGIN_SECRET=<same value as the GATEWAY_ORIGIN_SECRET k8s Secret> \
#   ./scripts/create-cdn-distribution.sh
set -euo pipefail

COMMENT="recsys-edge"

: "${ORIGIN_DOMAIN:?ORIGIN_DOMAIN is required (the Route53 failover hostname, NOT the ALB)}"
: "${ALIAS_DOMAIN:?ALIAS_DOMAIN is required (the public hostname)}"
: "${ACM_CERT_ARN:?ACM_CERT_ARN is required (must be in us-east-1)}"
: "${WEB_ACL_ARN:?WEB_ACL_ARN is required (must be scope=CLOUDFRONT)}"
: "${ORIGIN_SECRET:?ORIGIN_SECRET is required}"

if [[ "$ACM_CERT_ARN" != arn:aws:acm:us-east-1:* ]]; then
  echo "ERROR: CloudFront viewer certificates must live in us-east-1. Got: $ACM_CERT_ARN" >&2
  exit 1
fi

# Origin protocol. https-only is the default because http-only sends x-origin-secret across
# the public CloudFront->ALB hop in cleartext, where it is observable and replayable — by
# exactly the attacker the header exists to stop.
ORIGIN_PROTOCOL_POLICY="${ORIGIN_PROTOCOL_POLICY:-https-only}"

case "$ORIGIN_PROTOCOL_POLICY" in
  https-only)
    ;;
  http-only)
    echo "WARNING: ORIGIN_PROTOCOL_POLICY=http-only." >&2
    echo "         x-origin-secret will cross the CloudFront->ALB hop in CLEARTEXT and is" >&2
    echo "         replayable by anyone who observes it. This weakens the origin lockdown." >&2
    echo "         Prefer https-only (requires an ALB :443 listener + a REGIONAL ACM cert)." >&2
    ;;
  *)
    echo "ERROR: ORIGIN_PROTOCOL_POLICY must be 'https-only' or 'http-only'." >&2
    echo "       Got: ${ORIGIN_PROTOCOL_POLICY}" >&2
    exit 1
    ;;
esac

config_file="$(mktemp)"
trap 'rm -f "$config_file"' EXIT

# Cache policy ids are AWS-managed and stable:
#   CachingDisabled          4135ea2d-6df8-44a3-9df3-4b5a84be39ad
#   AllViewerExceptHostHeader (origin request) b689b0a8-53d0-40ab-baf2-68738e2966ac
CACHING_DISABLED="4135ea2d-6df8-44a3-9df3-4b5a84be39ad"
ALL_VIEWER_EXCEPT_HOST="b689b0a8-53d0-40ab-baf2-68738e2966ac"

# Create the policy, or update it when this script definition has drifted from what is
# deployed. This used to return the existing id unconditionally, which meant a TTL or
# query-whitelist edit here was a silent no-op from the second run onward — the cache key and
# the TTL ceiling live in the POLICY, not in the distribution config, so they are not covered
# by the replace-everything semantics of update-distribution.
#
# All diagnostics go to stderr: stdout is the policy id, consumed by the callers below.
ensure_cache_policy() {
  local name="$1" min_ttl="$2" default_ttl="$3" max_ttl="$4" query_keys="$5"

  local desired
  desired="$(jq -nc \
    --arg name "$name" --argjson min "$min_ttl" --argjson def "$default_ttl" \
    --argjson max "$max_ttl" --argjson keys "$query_keys" '{
      Name: $name, MinTTL: $min, DefaultTTL: $def, MaxTTL: $max,
      ParametersInCacheKeyAndForwardedToOrigin: {
        EnableAcceptEncodingGzip: true, EnableAcceptEncodingBrotli: true,
        HeadersConfig: {HeaderBehavior: "none"},
        CookiesConfig: {CookieBehavior: "none"},
        QueryStringsConfig: {QueryStringBehavior: "whitelist",
                             QueryStrings: {Quantity: ($keys|length), Items: $keys}}
      }}')"

  local existing
  existing="$(aws cloudfront list-cache-policies --type custom \
    --query "CachePolicyList.Items[?CachePolicy.CachePolicyConfig.Name=='${name}'].CachePolicy.Id" \
    --output text 2>/dev/null || true)"

  if [[ -z "$existing" || "$existing" == "None" ]]; then
    aws cloudfront create-cache-policy --cache-policy-config "$desired" \
      --query 'CachePolicy.Id' --output text
    return
  fi

  # Compare only the fields this script manages, so an AWS-added field (or a Comment set in
  # the console) does not read as drift and trigger an update on every run.
  local norm='{MinTTL, DefaultTTL, MaxTTL,
    gzip:   .ParametersInCacheKeyAndForwardedToOrigin.EnableAcceptEncodingGzip,
    brotli: .ParametersInCacheKeyAndForwardedToOrigin.EnableAcceptEncodingBrotli,
    hdr:    .ParametersInCacheKeyAndForwardedToOrigin.HeadersConfig.HeaderBehavior,
    cookie: .ParametersInCacheKeyAndForwardedToOrigin.CookiesConfig.CookieBehavior,
    qsb:    .ParametersInCacheKeyAndForwardedToOrigin.QueryStringsConfig.QueryStringBehavior,
    qs:     ((.ParametersInCacheKeyAndForwardedToOrigin.QueryStringsConfig.QueryStrings.Items // []) | sort)}'

  local current
  current="$(aws cloudfront get-cache-policy --id "$existing" \
    --query 'CachePolicy.CachePolicyConfig' --output json)"

  if [[ "$(jq -cS "$norm" <<<"$current")" == "$(jq -cS "$norm" <<<"$desired")" ]]; then
    echo "$existing"
    return
  fi

  echo "Cache policy ${name} (${existing}) has drifted from this script; updating." >&2
  echo "  deployed: $(jq -cS "$norm" <<<"$current")" >&2
  echo "  desired:  $(jq -cS "$norm" <<<"$desired")" >&2
  echo "  NOTE: this changes cache behavior at every edge as it propagates." >&2
  local etag
  etag="$(aws cloudfront get-cache-policy --id "$existing" --query 'ETag' --output text)"
  aws cloudfront update-cache-policy --id "$existing" --if-match "$etag" \
    --cache-policy-config "$desired" --query 'CachePolicy.Id' --output text
}

# Cache keys whitelist ONLY the meaningful params. Forwarding all query strings would let
# ?id=1&cachebuster=N fragment the cache arbitrarily and act as an origin-DoS amplifier. The
# whitelist bounds parameter NAMES only — the origin canonicalizes the values
# (BaseApiService.cacheKeyIntParam), without which a whitelisted param is a cache-buster too.
#
# The two ceilings are not slack, and must move together with HttpCaching.publicCache:
#   MaxTTL   CloudFront serves stale content for the LESSER of the origin stale-while-revalidate
#            / stale-if-error window and MaxTTL, and drops the object entirely after MaxTTL.
#            These MaxTTLs sit EXACTLY at the stale windows (86400 for
#            item, 3600 for similar), so raising a stale window without raising MaxTTL is a
#            silent no-op.
#   MinTTL   Must stay 0. Above zero CloudFront ignores Cache-Control: no-store, which every
#            error branch on these two routes depends on.
item_policy="$(ensure_cache_policy recsys-item 0 3600 86400 '["id"]')"
similar_policy="$(ensure_cache_policy recsys-similar 0 300 3600 '["movieId","k"]')"

jq -n \
  --arg comment "$COMMENT" --arg origin "$ORIGIN_DOMAIN" --arg alias "$ALIAS_DOMAIN" \
  --arg cert "$ACM_CERT_ARN" --arg acl "$WEB_ACL_ARN" --arg secret "$ORIGIN_SECRET" \
  --arg item_policy "$item_policy" --arg similar_policy "$similar_policy" \
  --arg caching_disabled "$CACHING_DISABLED" --arg all_viewer "$ALL_VIEWER_EXCEPT_HOST" \
  --arg origin_protocol "$ORIGIN_PROTOCOL_POLICY" \
  --arg ref "recsys-edge-1" '
{
  CallerReference: $ref, Comment: $comment, Enabled: true, HttpVersion: "http2and3",
  Aliases: {Quantity: 1, Items: [$alias]},
  Origins: {Quantity: 1, Items: [{
    Id: "alb-origin", DomainName: $origin,
    CustomOriginConfig: {
      HTTPPort: 80, HTTPSPort: 443, OriginProtocolPolicy: $origin_protocol,
      OriginSslProtocols: {Quantity: 1, Items: ["TLSv1.2"]},
      OriginReadTimeout: 30, OriginKeepaliveTimeout: 5
    },
    CustomHeaders: {Quantity: 1, Items: [
      {HeaderName: "x-origin-secret", HeaderValue: $secret}
    ]}
  }]},
  # DEFAULT = CachingDisabled. Everything is uncacheable unless explicitly opted in below.
  # This is what keeps POST /api/recommend and /api/catalog/user out of the cache, today and
  # for any route added later.
  DefaultCacheBehavior: {
    TargetOriginId: "alb-origin", ViewerProtocolPolicy: "redirect-to-https",
    CachePolicyId: $caching_disabled, OriginRequestPolicyId: $all_viewer, Compress: true,
    AllowedMethods: {Quantity: 7,
      Items: ["GET","HEAD","OPTIONS","PUT","POST","PATCH","DELETE"],
      CachedMethods: {Quantity: 2, Items: ["GET","HEAD"]}}
  },
  # PathPatterns are EXACT — no trailing wildcard. CloudFront does not consider query strings
  # when evaluating a path pattern, so "/api/catalog/item" already matches "?id=1"; a "*"
  # would only widen the match to /api/catalog/item<anything>, which is wider than the exact
  # GATEWAY_PUBLIC_PATHS entry the gateway authorizes. On a glob-matched path the edge drops
  # Authorization while the gateway still treats the path as private. Mirrored by the
  # `location =` blocks in docker/cdn/default.conf.template.
  CacheBehaviors: {Quantity: 4, Items: [
    {PathPattern: "/api/catalog/item", TargetOriginId: "alb-origin",
     ViewerProtocolPolicy: "redirect-to-https", CachePolicyId: $item_policy, Compress: true,
     AllowedMethods: {Quantity: 2, Items: ["GET","HEAD"],
       CachedMethods: {Quantity: 2, Items: ["GET","HEAD"]}}},
    {PathPattern: "/api/v1/catalog/item", TargetOriginId: "alb-origin",
     ViewerProtocolPolicy: "redirect-to-https", CachePolicyId: $item_policy, Compress: true,
     AllowedMethods: {Quantity: 2, Items: ["GET","HEAD"],
       CachedMethods: {Quantity: 2, Items: ["GET","HEAD"]}}},
    {PathPattern: "/api/catalog/similar", TargetOriginId: "alb-origin",
     ViewerProtocolPolicy: "redirect-to-https", CachePolicyId: $similar_policy, Compress: true,
     AllowedMethods: {Quantity: 2, Items: ["GET","HEAD"],
       CachedMethods: {Quantity: 2, Items: ["GET","HEAD"]}}},
    {PathPattern: "/api/v1/catalog/similar", TargetOriginId: "alb-origin",
     ViewerProtocolPolicy: "redirect-to-https", CachePolicyId: $similar_policy, Compress: true,
     AllowedMethods: {Quantity: 2, Items: ["GET","HEAD"],
       CachedMethods: {Quantity: 2, Items: ["GET","HEAD"]}}}
  ]},
  ViewerCertificate: {ACMCertificateArn: $cert, SSLSupportMethod: "sni-only",
                      MinimumProtocolVersion: "TLSv1.2_2021"},
  WebACLId: $acl,
  PriceClass: "PriceClass_All"
}' > "$config_file"

existing_id="$(aws cloudfront list-distributions \
  --query "DistributionList.Items[?Comment=='${COMMENT}'].Id" --output text 2>/dev/null || true)"

if [[ -n "$existing_id" && "$existing_id" != "None" ]]; then
  echo "Updating existing distribution ${existing_id}"
  etag="$(aws cloudfront get-distribution-config --id "$existing_id" --query 'ETag' --output text)"
  aws cloudfront update-distribution --id "$existing_id" --if-match "$etag" \
    --distribution-config "file://${config_file}" \
    --query 'Distribution.DomainName' --output text
else
  echo "Creating distribution"
  aws cloudfront create-distribution --distribution-config "file://${config_file}" \
    --query 'Distribution.{Id:Id,Domain:DomainName}' --output table
fi

echo "Done. Validate against the raw cloudfront.net domain BEFORE flipping DNS."
echo "See docs/runbooks/cdn-operations.md."
