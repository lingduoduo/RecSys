#!/usr/bin/env bash
# Verify scripts/cdn/normalize-catalog-query.js against the REAL CloudFront runtime.
#
# `create-function` places a function in the DEVELOPMENT stage without any distribution, and
# `test-function` runs it in CloudFront's own runtime against an event object we supply. So this
# proves the function's LOGIC on AWS rather than in a local JS shim.
#
# What it cannot prove, and what nothing in this repo can: how CloudFront parses a raw wire request
# INTO that event object. test-function takes an already-parsed event, so every `%37` below is one
# THIS FILE wrote — never one CloudFront produced from a wire byte. Read every rejection case here
# as "the function rejects an event whose value contains a percent sign", which is strictly weaker
# than "the function rejects `?id=%37` at the edge". The function is built not to depend on that
# gap for its cache-key soundness, but its stated reject-rather-than-decode behaviour does depend
# on it. See the header of the .js file and 12_CDNS.md sharp edge 9, unverified item 3.
#
# The probe function is unassociated, in DEVELOPMENT only, and deleted on exit including on
# failure. It serves no traffic and costs nothing.
set -euo pipefail

NAME="recsys-cdn-normalize-probe"
CODE="$(dirname "$0")/cdn/normalize-catalog-query.js"
TMP="$(mktemp -d)"
FAILURES=0
CREATED=0

# Delete ONLY a function this run created. The probe name is fixed, so under `set -e` a
# create-function that fails because the name is already taken would exit straight into this
# trap — and an unguarded delete would then destroy somebody else's function of that name as
# its first act. Cleaning up is for what we made, not for what we found.
cleanup() {
  local etag
  if (( CREATED == 1 )); then
    etag="$(aws cloudfront describe-function --name "$NAME" --query 'ETag' --output text 2>/dev/null || true)"
    if [[ -n "$etag" && "$etag" != "None" ]]; then
      aws cloudfront delete-function --name "$NAME" --if-match "$etag" >/dev/null 2>&1 || true
    fi
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT

etag="$(aws cloudfront create-function --name "$NAME" \
  --function-config '{"Comment":"probe for normalize-catalog-query.js","Runtime":"cloudfront-js-2.0"}' \
  --function-code "fileb://$CODE" --query 'ETag' --output text)"
CREATED=1

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
# Every other rejection case above puts the encoded value on the FIRST whitelisted name, so a
# function that only ever inspected allowed[0] would pass all of them. This one encodes `k`,
# which is second in the similar whitelist, and leaves `movieId` clean.
expect 'encoded value on a non-first parameter is rejected' '"statusCode":400' \
  "$(req /api/catalog/similar '{"movieId":{"value":"1"},"k":{"value":"%35"}}')"
# This function is associated ONLY with the four exact-match cached behaviors, so a URI it
# does not recognize cannot mean "some other, unprotected route" — it can only mean the raw
# URI the function was handed differs from the one CloudFront matched the behavior on (see the
# .js header). The miss branch fails closed rather than passing the request through untouched.
expect 'unrecognised uri is rejected'     '"statusCode":400' \
  "$(req /api/recommend '{"q":{"value":"%20"}}')"
# The three non-canonical spellings 12_CDNS.md sharp edge 9 cites by name. Each would match a
# cached behavior on the NORMALIZED path while missing this map on the raw one, so each is a way
# the whole guard could have been skipped had the miss branch passed through. The doc calls these
# confirmed against the real CloudFront runtime; these three lines are what makes that
# reproducible from the repo rather than from a scratch note.
expect 'double leading slash is rejected' '"statusCode":400' \
  "$(req //api/catalog/item '{"id":{"value":"7"}}')"
expect 'dot-segment path is rejected'     '"statusCode":400' \
  "$(req /api/x/../catalog/item '{"id":{"value":"7"}}')"
expect 'encoded path character is rejected' '"statusCode":400' \
  "$(req /api/catalog/%69tem '{"id":{"value":"7"}}')"
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
