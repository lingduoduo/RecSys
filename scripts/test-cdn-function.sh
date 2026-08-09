#!/usr/bin/env bash
# Verify scripts/cdn/normalize-catalog-query.js against the REAL CloudFront runtime.
#
# `create-function` places a function in the DEVELOPMENT stage without any distribution, and
# `test-function` runs it in CloudFront's own runtime against an event object we supply. So this
# proves the function's LOGIC on AWS rather than in a local JS shim.
#
# What it cannot prove, and what nothing in this repo can: how CloudFront parses a raw wire query
# string INTO that event object. test-function takes an already-parsed event. The function is
# designed not to depend on the answer — see the header of the .js file.
#
# The probe function is unassociated, in DEVELOPMENT only, and deleted on exit including on
# failure. It serves no traffic and costs nothing.
set -euo pipefail

NAME="recsys-cdn-normalize-probe"
CODE="$(dirname "$0")/cdn/normalize-catalog-query.js"
TMP="$(mktemp -d)"
FAILURES=0

cleanup() {
  local etag
  etag="$(aws cloudfront describe-function --name "$NAME" --query 'ETag' --output text 2>/dev/null || true)"
  if [[ -n "$etag" && "$etag" != "None" ]]; then
    aws cloudfront delete-function --name "$NAME" --if-match "$etag" >/dev/null 2>&1 || true
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT

etag="$(aws cloudfront create-function --name "$NAME" \
  --function-config '{"Comment":"probe for normalize-catalog-query.js","Runtime":"cloudfront-js-2.0"}' \
  --function-code "fileb://$CODE" --query 'ETag' --output text)"

# expect <label> <expected-substring> <event-json>
expect() {
  local label="$1" want="$2" event="$3" got
  printf '%s' "$event" > "$TMP/event.json"
  got="$(aws cloudfront test-function --name "$NAME" --if-match "$etag" --stage DEVELOPMENT \
    --event-object "fileb://$TMP/event.json" \
    --query 'TestResult.FunctionOutput' --output text)"
  if [[ "$got" == *"$want"* ]]; then
    printf 'ok   %s\n' "$label"
  else
    printf 'FAIL %s\n     want substring: %s\n     got:            %s\n' "$label" "$want" "$got"
    FAILURES=$((FAILURES + 1))
  fi
}

req() {  # req <uri> <querystring-json>
  printf '{"version":"1.0","context":{"eventType":"viewer-request"},"viewer":{"ip":"1.2.3.4"},"request":{"method":"GET","uri":"%s","querystring":%s,"headers":{},"cookies":{}}}' "$1" "$2"
}

expect 'encoded value is rejected'        '"statusCode":400' \
  "$(req /api/catalog/item '{"id":{"value":"%37"}}')"
expect 'rejection is not cacheable'       '"cache-control":{"value":"no-store"}' \
  "$(req /api/catalog/item '{"id":{"value":"%37"}}')"
expect 'clean value passes through'       '"querystring":"id=7"' \
  "$(req /api/catalog/item '{"id":{"value":"7"}}')"
expect 'parameter order is normalized'    '"querystring":"movieId=1&k=5"' \
  "$(req /api/catalog/similar '{"k":{"value":"5"},"movieId":{"value":"1"}}')"
expect 'unlisted parameter is dropped'    '"querystring":"movieId=1"' \
  "$(req /api/catalog/similar '{"movieId":{"value":"1"},"%6b":{"value":"200"}}')"
expect 'repeated parameter is rejected'   '"statusCode":400' \
  "$(req /api/catalog/item '{"id":{"value":"7","multiValue":[{"value":"7"},{"value":"8"}]}}')"
# This function is associated ONLY with the four exact-match cached behaviors, so a URI it
# does not recognize cannot mean "some other, unprotected route" — it can only mean the raw
# URI the function was handed differs from the one CloudFront matched the behavior on (see the
# .js header). The miss branch fails closed rather than passing the request through untouched.
expect 'unrecognised uri is rejected'     '"statusCode":400' \
  "$(req /api/recommend '{"q":{"value":"%20"}}')"
expect 'v1 item: encoded value is rejected' '"statusCode":400' \
  "$(req /api/v1/catalog/item '{"id":{"value":"%37"}}')"
expect 'v1 item: clean value passes through' '"querystring":"id=7"' \
  "$(req /api/v1/catalog/item '{"id":{"value":"7"}}')"
expect 'v1 similar: encoded value is rejected' '"statusCode":400' \
  "$(req /api/v1/catalog/similar '{"movieId":{"value":"%37"}}')"
expect 'v1 similar: clean value passes through' '"querystring":"movieId=1&k=5"' \
  "$(req /api/v1/catalog/similar '{"movieId":{"value":"1"},"k":{"value":"5"}}')"

if (( FAILURES > 0 )); then
  printf '\n%d check(s) failed\n' "$FAILURES"
  exit 1
fi
printf '\nall checks passed\n'
