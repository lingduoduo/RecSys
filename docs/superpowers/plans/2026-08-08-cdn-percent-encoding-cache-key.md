# CDN percent-encoding cache key Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the one real percent-encoding cache-key channel with a CloudFront viewer-request Function, and correct the two false claims in `12_CDNS.md` that sent two earlier attempts at the wrong layer.

**Architecture:** A JavaScript function file plus its wiring in `scripts/create-cdn-distribution.sh`, a shell script that verifies the function against the real CloudFront runtime with `aws cloudfront test-function`, a Java conformance test pinning the function's whitelist to the cache policies, and a rewritten sharp edge 9.

**Tech Stack:** CloudFront Functions (`cloudfront-js-2.0`), bash + jq + AWS CLI, nginx, Java 17, JUnit 5 + AssertJ.

## Global Constraints

- Build and test with JDK 17: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`.
- The Java conformance test must be non-docker and added to the `resilience` profile's `<includes>` in `pom.xml` — that profile is the PR gate.
- **Do not create a CloudFront distribution.** None exists in this account and none is to be created. Only the two cache policies exist.
- **Do not extend `LocalCdnCacheTest`.** It is `@Tag("docker")` and Docker has never run on any machine used for this work.
- **Do not change `cacheKeyIntParam`** (`BaseApiService.java:250`) or anything under `src/main/java`. The origin provably cannot see raw query spellings; this branch does not pretend otherwise.
- Any CloudFront Function created for verification must be in the `DEVELOPMENT` stage, unassociated, and **deleted before the task ends**. Leave no AWS resource behind.
- Never merge to `main` directly — this ships as a PR.
- Branch: `feat/cdn-percent-encoding-cache-key` (already created; the design is already committed on it).

## Facts established before this plan, so you need not rediscover them

- The four cached behaviors carry **no** `OriginRequestPolicyId` (`scripts/create-cdn-distribution.sh:187-200`); only `DefaultCacheBehavior` sets `$all_viewer` (line 175). AWS: *"Other information from the viewer request, such as URL query strings, HTTP headers, and cookies, is not included in the origin request by default."* So a non-whitelisted parameter never reaches the origin — which is why `?%69d=7` and `?movieId=1&%6b=200` are **not** bugs.
- The one real channel is `?id=%37`: `id` is whitelisted, so the encoded value is keyed *and* forwarded, and the origin decodes it to `7`. A second cache key over one body.
- The real cache policies in the account whitelist `id` (`recsys-item`) and `movieId`, `k` (`recsys-similar`), matching the script at lines 146-147.
- **The exact function below has already been run against the real CloudFront runtime** via `create-function` (DEVELOPMENT) + `test-function`, and produced exactly the outputs asserted in Task 1 Step 3. It works; your job is to commit it and make the verification repeatable.
- On a non-cached URI the function returns the request untouched, and `querystring` comes back as the original **object**, not a string. That is the passthrough signature.

## File Structure

| File | Responsibility |
|---|---|
| `scripts/cdn/normalize-catalog-query.js` | The viewer-request function. |
| `scripts/test-cdn-function.sh` | Creates a DEVELOPMENT function, runs the six cases through `test-function`, deletes it. |
| `scripts/create-cdn-distribution.sh` | `ensure_function` helper + `FunctionAssociations` on the four cached behaviors. |
| `src/test/java/com/recsys/api/serving/CdnQueryNormalizationConformanceTest.java` | Pins the function's `ALLOWED` map to the cache policies and asserts every cached behavior is associated. |
| `docker/cdn/default.conf.template` | Local stand-in mirrors the rejection. |
| `pom.xml` | One `<include>` in the `resilience` profile. |
| `docs/system_design/12_CDNS.md` | Sharp edge 9 rewritten; the `QueryStringBehavior` bullet corrected. |

---

### Task 1: The function, its wiring, and its verification

**Files:**
- Create: `scripts/cdn/normalize-catalog-query.js`
- Create: `scripts/test-cdn-function.sh`
- Modify: `scripts/create-cdn-distribution.sh`

**Interfaces:**
- Produces: `ALLOWED`, a JS object literal mapping each of the four `PathPattern` values to an array of whitelisted parameter names — Task 2's conformance test parses exactly this literal.
- Produces: a `FunctionAssociations` entry on each of the four cached behaviors — Task 2 asserts its presence.

- [ ] **Step 1: Write the function**

Create `scripts/cdn/normalize-catalog-query.js`:

```js
// Viewer-request function for the four cached catalog behaviors.
//
// CloudFront computes the cache key from THIS function's output, so whatever it emits is what
// fragments the cache. It rebuilds the query string from the whitelisted parameters alone:
//
//   1. Rejects a percent-encoded value on a whitelisted name. `?id=%37` is a distinct cache key
//      for the byte-identical `?id=7` body, and so is every other spelling of the same integer —
//      an unbounded, attacker-controlled cache-buster on a public route where each miss costs a
//      full candidate scan. Rejecting rather than decoding matches the origin: cacheKeyIntParam
//      already refuses every non-canonical spelling, so decoding %37 to 7 here would CREATE a
//      second working spelling rather than remove one. One spelling, one identity, both layers.
//   2. Rejects a repeated parameter, as cacheKeyIntParam does.
//   3. Emits the allowed names in declaration order, so ?movieId=1&k=5 and ?k=5&movieId=1 are one
//      cache key rather than two.
//   4. Drops unlisted parameters. They are already excluded from the key and, with no origin
//      request policy on these behaviors, never forwarded — so this is defensive, not corrective.
//
// It is deliberately robust to a fact nobody can measure without a distribution: whether
// CloudFront percent-decodes parameter NAMES before whitelist matching. If `%69d` stays raw it is
// unlisted and dropped; if it decodes to `id` it is whitelisted and passes through as `id=7`,
// which was already correct. Nothing here rests on the answer.
//
// The 400 carries no-store, so a rejection is never cached at the edge.
//
// Pinned to the cache policies by CdnQueryNormalizationConformanceTest — the ALLOWED literal
// below is parsed by that test, so keep it a plain object of string arrays.
var ALLOWED = {
    '/api/catalog/item':       ['id'],
    '/api/v1/catalog/item':    ['id'],
    '/api/catalog/similar':    ['movieId', 'k'],
    '/api/v1/catalog/similar': ['movieId', 'k']
};

function handler(event) {
    var request = event.request;
    var allowed = ALLOWED[request.uri];
    if (!allowed) {
        return request;
    }
    var qs = [];
    for (var i = 0; i < allowed.length; i++) {
        var name = allowed[i];
        var param = request.querystring[name];
        if (!param) {
            continue;
        }
        if (param.multiValue || param.value.indexOf('%') >= 0) {
            return {
                statusCode: 400,
                statusDescription: 'Bad Request',
                headers: { 'cache-control': { value: 'no-store' } }
            };
        }
        qs.push(name + '=' + param.value);
    }
    request.querystring = qs.join('&');
    return request;
}
```

- [ ] **Step 2: Write the verification script**

Create `scripts/test-cdn-function.sh`, executable (`chmod +x`):

```bash
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
# On a non-cached URI the request is returned untouched, so querystring is still an OBJECT.
expect 'non-cached uri is untouched'      '"querystring":{"q":{"value":"%20"}}' \
  "$(req /api/recommend '{"q":{"value":"%20"}}')"

if (( FAILURES > 0 )); then
  printf '\n%d check(s) failed\n' "$FAILURES"
  exit 1
fi
printf '\nall checks passed\n'
```

- [ ] **Step 3: Run it and confirm every case**

```bash
sh scripts/test-cdn-function.sh
```

Expected — all seven lines `ok`, then `all checks passed`. These outputs were already observed against the real runtime, so a failure means a transcription error, not a design problem. Afterwards confirm nothing was left behind:

```bash
aws cloudfront list-functions --query 'FunctionList.Items[].Name' --output text
```

Expected: no `recsys-cdn-normalize-probe`.

- [ ] **Step 4: Add the `ensure_function` helper to the distribution script**

In `scripts/create-cdn-distribution.sh`, immediately after the `ensure_cache_policy` function definition ends, add:

```bash
# Create or update the viewer-request function, publish it, and return its LIVE ARN.
#
# Same create-or-update shape as ensure_cache_policy, and for the same reason: an edit to the .js
# that never reached AWS would be a silent no-op, and the cache key is computed from this
# function's OUTPUT. Publishing is separate from updating — an updated but unpublished function
# still serves its old LIVE copy to every association.
#
# All diagnostics go to stderr: stdout is the ARN, consumed by the caller below.
ensure_function() {
  local name="$1" code_file="$2"
  local config='{"Comment":"Normalize catalog cache-key query strings","Runtime":"cloudfront-js-2.0"}'
  local etag

  etag="$(aws cloudfront describe-function --name "$name" --query 'ETag' --output text 2>/dev/null || true)"
  if [[ -z "$etag" || "$etag" == "None" ]]; then
    aws cloudfront create-function --name "$name" \
      --function-config "$config" --function-code "fileb://${code_file}" >&2
  else
    aws cloudfront update-function --name "$name" --if-match "$etag" \
      --function-config "$config" --function-code "fileb://${code_file}" >&2
  fi

  etag="$(aws cloudfront describe-function --name "$name" --query 'ETag' --output text)"
  aws cloudfront publish-function --name "$name" --if-match "$etag" >&2
  aws cloudfront describe-function --name "$name" --stage LIVE \
    --query 'FunctionSummary.FunctionMetadata.FunctionARN' --output text
}
```

- [ ] **Step 5: Call it and associate it with the four cached behaviors**

Immediately after the two `ensure_cache_policy` calls (lines 146-147), add:

```bash
normalize_fn="$(ensure_function recsys-normalize-catalog-query \
  "$(dirname "$0")/cdn/normalize-catalog-query.js")"
```

Add `--arg fn "$normalize_fn"` to the `jq` invocation that builds the distribution config, beside the existing `--arg item_policy` / `--arg similar_policy` arguments.

Then in each of the **four** cached behavior objects (and **not** in `DefaultCacheBehavior`), add this line after the `CachePolicyId:` line:

```
     FunctionAssociations: {Quantity: 1, Items: [{EventType: "viewer-request", FunctionARN: $fn}]},
```

- [ ] **Step 6: Check the script still parses and builds valid JSON**

```bash
bash -n scripts/create-cdn-distribution.sh
```

Expected: no output (syntax OK). Do **not** run the script itself — it would create a distribution, which the global constraints forbid.

- [ ] **Step 7: Commit**

```bash
git add scripts/cdn/normalize-catalog-query.js scripts/test-cdn-function.sh \
        scripts/create-cdn-distribution.sh
git commit -m "fix: normalize catalog cache-key query strings at the edge"
```

---

### Task 2: Pin the whitelist, mirror it locally, and correct the docs

**Files:**
- Create: `src/test/java/com/recsys/api/serving/CdnQueryNormalizationConformanceTest.java`
- Modify: `pom.xml` (resilience profile `<includes>`)
- Modify: `docker/cdn/default.conf.template`
- Modify: `docs/system_design/12_CDNS.md`

**Interfaces:**
- Consumes: the `ALLOWED` object literal in `scripts/cdn/normalize-catalog-query.js` and the `FunctionAssociations` entries in `scripts/create-cdn-distribution.sh`, both produced by Task 1.

Facts verified against the repo:

- `scripts/create-cdn-distribution.sh:146-147` declares the whitelists as the fifth argument to `ensure_cache_policy`, single-quoted JSON: `'["id"]'` and `'["movieId","k"]'`, with policy names `recsys-item` and `recsys-similar`.
- Each cached behavior block names its policy as `CachePolicyId: $item_policy` or `$similar_policy` on the line following its `PathPattern`.
- `docker/cdn/default.conf.template` has cached `location =` blocks at lines 50, 73, 89 and 104, each with one `proxy_cache_key` (lines 60, 80, 96, 111), plus a `location / {` pass-through at line 35 with none.

- [ ] **Step 1: Write the conformance test**

Create `src/test/java/com/recsys/api/serving/CdnQueryNormalizationConformanceTest.java`:

```java
package com.recsys.api.serving;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The edge function's whitelist equals the cache policies' whitelist.
 *
 * <p>{@code scripts/cdn/normalize-catalog-query.js} rebuilds each cached route's query string from
 * an allow-list. If that list drifts from the cache policy, the damage is silent in both
 * directions: a name in the policy but not the function is dropped from the forwarded query, so
 * the origin quietly serves a default; a name in the function but not the policy is emitted into a
 * query string the cache key ignores. Neither shows up as an error.
 *
 * <p>So the function is pinned to {@code scripts/create-cdn-distribution.sh}, which is what creates
 * the real policies. The whitelists there are the fifth argument to {@code ensure_cache_policy};
 * the mapping from route to policy is the {@code CachePolicyId} line under each
 * {@code PathPattern}.
 *
 * <p>Scope: this compares two committed files. A cache policy edited by hand in the AWS console is
 * invisible here, as it is to every other conformance test in this repo.
 */
class CdnQueryNormalizationConformanceTest {

    private static final Path SCRIPT = Path.of("scripts", "create-cdn-distribution.sh");
    private static final Path FUNCTION = Path.of("scripts", "cdn", "normalize-catalog-query.js");

    /** {@code item_policy="$(ensure_cache_policy recsys-item 0 3600 86400 '["id"]')"} */
    private static final Pattern POLICY_DECL = Pattern.compile(
            "(\\w+)=\"\\$\\(ensure_cache_policy\\s+\\S+(?:\\s+\\d+){3}\\s+'(\\[[^']*\\])'");
    /** {@code {PathPattern: "/api/catalog/item", ...} */
    private static final Pattern PATH_PATTERN = Pattern.compile("PathPattern:\\s*\"([^\"]+)\"");
    /** {@code CachePolicyId: $item_policy} */
    private static final Pattern POLICY_REF = Pattern.compile("CachePolicyId:\\s*\\$(\\w+)");
    /** {@code '/api/catalog/similar': ['movieId', 'k']} */
    private static final Pattern ALLOWED_ENTRY = Pattern.compile(
            "'(/[^']*)'\\s*:\\s*\\[([^\\]]*)\\]");
    private static final Pattern JS_STRING = Pattern.compile("'([^']*)'");

    @Test
    void theFunctionsAllowListMatchesTheCachePolicies() throws IOException {
        Map<String, List<String>> fromScript = whitelistsByPath();
        Map<String, List<String>> fromFunction = allowedInFunction();

        // A silently-empty scan would pass this test while proving nothing.
        assertThat(fromScript)
                .as("no cached behavior parsed from %s — the scan found nothing to check", SCRIPT)
                .isNotEmpty();
        assertThat(new TreeMap<>(fromFunction))
                .as("the function's ALLOWED map must equal the cache-policy whitelists")
                .isEqualTo(new TreeMap<>(fromScript));
    }

    /** Every cached behavior must actually run the function, or its whitelist is decoration. */
    @Test
    void everyCachedBehaviorAssociatesTheViewerRequestFunction() throws IOException {
        List<String> lines = Files.readAllLines(SCRIPT);
        List<String> unassociated = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            Matcher m = PATH_PATTERN.matcher(lines.get(i));
            if (!m.find()) {
                continue;
            }
            String block = String.join("\n", lines.subList(i, Math.min(i + 6, lines.size())));
            if (!block.contains("FunctionAssociations")
                    || !block.contains("\"viewer-request\"")) {
                unassociated.add(m.group(1));
            }
        }

        assertThat(unassociated)
                .as("every cached behavior must associate the viewer-request function")
                .isEmpty();
    }

    /**
     * The scans above pass vacuously if the patterns cannot see a real declaration, and both files
     * are currently correct — so nothing there exercises the detection side. These are the exact
     * spellings each file uses today.
     */
    @Test
    void thePatternsRecogniseTheSpellingsTheseFilesActuallyUse() {
        Matcher policy = POLICY_DECL.matcher(
                "similar_policy=\"$(ensure_cache_policy recsys-similar 0 300 3600 '[\"movieId\",\"k\"]')\"");
        assertThat(policy.find()).isTrue();
        assertThat(policy.group(1)).isEqualTo("similar_policy");
        assertThat(policy.group(2)).isEqualTo("[\"movieId\",\"k\"]");

        assertThat(POLICY_REF.matcher("     CachePolicyId: $item_policy, Compress: true,").find())
                .isTrue();

        Matcher allowed = ALLOWED_ENTRY.matcher("    '/api/catalog/similar':    ['movieId', 'k']");
        assertThat(allowed.find()).isTrue();
        assertThat(allowed.group(1)).isEqualTo("/api/catalog/similar");
    }

    /** path -> whitelisted parameter names, as the distribution script declares them. */
    private static Map<String, List<String>> whitelistsByPath() throws IOException {
        List<String> lines = Files.readAllLines(SCRIPT);

        Map<String, List<String>> byVariable = new LinkedHashMap<>();
        for (String line : lines) {
            Matcher m = POLICY_DECL.matcher(line);
            if (m.find()) {
                List<String> names = new ArrayList<>();
                Matcher s = Pattern.compile("\"([^\"]*)\"").matcher(m.group(2));
                while (s.find()) {
                    names.add(s.group(1));
                }
                byVariable.put(m.group(1), names);
            }
        }

        Map<String, List<String>> byPath = new LinkedHashMap<>();
        String pendingPath = null;
        for (String line : lines) {
            Matcher p = PATH_PATTERN.matcher(line);
            if (p.find()) {
                pendingPath = p.group(1);
                continue;
            }
            Matcher r = POLICY_REF.matcher(line);
            if (pendingPath != null && r.find()) {
                List<String> names = byVariable.get(r.group(1));
                if (names != null) {
                    byPath.put(pendingPath, names);
                }
                pendingPath = null;
            }
        }
        return byPath;
    }

    /** path -> allowed parameter names, as the function's ALLOWED literal declares them. */
    private static Map<String, List<String>> allowedInFunction() throws IOException {
        String source = Files.readString(FUNCTION);
        int start = source.indexOf("var ALLOWED = {");
        assertThat(start).as("ALLOWED literal not found in %s", FUNCTION).isNotNegative();
        String body = source.substring(start, source.indexOf("};", start));

        Map<String, List<String>> allowed = new LinkedHashMap<>();
        Matcher m = ALLOWED_ENTRY.matcher(body);
        while (m.find()) {
            List<String> names = new ArrayList<>();
            Matcher s = JS_STRING.matcher(m.group(2));
            while (s.find()) {
                names.add(s.group(1));
            }
            allowed.put(m.group(1), names);
        }
        return allowed;
    }
}
```

- [ ] **Step 2: Run the test**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=CdnQueryNormalizationConformanceTest
```

Expected: PASS, 3 tests.

- [ ] **Step 3: Verify the test actually detects drift**

Temporarily change line 146 of `scripts/create-cdn-distribution.sh` from `'["id"]'` to `'["id","extra"]'` and re-run. Expected: `theFunctionsAllowListMatchesTheCachePolicies` FAILS naming `extra`.

Then revert **only that file**: `git checkout -- scripts/create-cdn-distribution.sh`, and confirm with `git status` that your new uncommitted test file is still present. Name the single path explicitly — a bare `git checkout --` or `git stash` here would destroy it, which has happened twice in this repo's recent history.

Do not skip this step. A conformance test that cannot fail is worse than none, because it retires the question.

- [ ] **Step 4: Add the test to the PR gate**

In `pom.xml`, in the `resilience` profile's `<includes>` list, beside the other `**/serving/*` entries if any exist, otherwise after the last `**/k8s/*ManifestTest` entry:

```xml
                <include>**/serving/CdnQueryNormalizationConformanceTest.java</include>
```

Re-run `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience` and confirm BUILD SUCCESS with the test count up by 3.

- [ ] **Step 5: Mirror the rejection in the local stand-in**

In `docker/cdn/default.conf.template`, inside each of the four cached `location =` blocks, immediately above its `proxy_cache_key` line, add the matching rejection. For the two `item` blocks (lines 50 and 89):

```nginx
        # Mirrors scripts/cdn/normalize-catalog-query.js: a percent-encoded value is a second
        # cache key for the same body. nginx's $arg_id is the raw, undecoded value, so this sees
        # what the edge sees. Unexecuted — Docker has never run on this project's machines.
        if ($arg_id ~ "%") { return 400; }
```

For the two `similar` blocks (lines 73 and 104):

```nginx
        # Mirrors scripts/cdn/normalize-catalog-query.js: a percent-encoded value is a second
        # cache key for the same body. nginx's $arg_ variables are raw and undecoded, so these see
        # what the edge sees. Unexecuted — Docker has never run on this project's machines.
        if ($arg_movieId ~ "%") { return 400; }
        if ($arg_k ~ "%") { return 400; }
```

- [ ] **Step 6: Rewrite sharp edge 9**

Replace the whole of item 9 in `docs/system_design/12_CDNS.md` (currently lines 333-342) with:

```
9. **Two of the three percent-encoding channels never existed; the third is closed at the edge.**
   This entry claimed until 2026-08-08 that `?%69d=7` "collides with `?%69%64=8` and with a bare
   parameterless request — many bodies under one key". It does not. The four cached behaviors
   carry no `OriginRequestPolicyId`, and AWS forwards to the origin only what is in the cache key
   ("Other information from the viewer request, such as URL query strings, HTTP headers, and
   cookies, is not included in the origin request by default"). So a non-whitelisted `%69d` never
   reaches the origin: it 400s on the missing `id`, `no-store`, uncached, and no bodies collide.
   The same reasoning kills the `k`-default variant — `?movieId=1&%6b=200` forwards no `k`, the
   origin uses its default, and the body matches its key. **One channel is real:** `?id=%37` is a
   whitelisted name, so the encoded value is both keyed and forwarded, and the origin decodes it
   to `7` — a second cache key over one body, and an unbounded attacker-controlled cache-buster
   of exactly the kind sharp edge 7's ceiling exists to prevent. It is closed by
   `scripts/cdn/normalize-catalog-query.js`, a viewer-request function that rebuilds each cached
   route's query string from the whitelist alone, rejecting a percent-encoded or repeated value
   with a `no-store` 400 and emitting the rest in a fixed order. The cache key is computed from
   the function's output, so that is what fragments the cache.
   **The origin cannot help here, and must not be asked to.** Armeria's codecs overwrite `:path`
   with the normalized request target before any service runs (`Http1RequestDecoder:177` into
   `ArmeriaHttpUtil:679`; `Http2RequestDecoder:128` into `:614`), and
   `QUERY_MUST_PRESERVE_ENCODING` excludes alphanumerics — so `?id=%37` arrives at the gateway as
   `id=7`. Measured on a real server over a raw socket. A gateway-side guard was written, passed
   its unit tests, and was inert in production, because `ServiceRequestContext.of(request)` is the
   one place that preserves the caller's request verbatim.
   **Still unverified:** whether CloudFront percent-decodes parameter *names* before whitelist
   matching. The function is built not to depend on the answer, and `scripts/test-cdn-function.sh`
   verifies its logic against the real CloudFront runtime — but no distribution exists in this
   account, so the association and the parsing behaviour are unexercised.
```

- [ ] **Step 7: Correct the `QueryStringBehavior` bullet**

Replace this passage (currently lines 68-74):

```
  without rejecting the default spelling. Two channels are *not* closed, because
  `cacheKeyIntParam` validates the **decoded** query value while the CDN cache key is built
  from the **raw** query string: a percent-encoded value (`?id=%37`) is a second cache key for
  the same body, and — the more serious direction, since it collapses distinct responses onto
  one key rather than merely splitting one response across several — a percent-encoded
  parameter **name** (`?%69d=7`) presents no whitelisted parameter to the edge at all, so it
  collides with `?%69%64=8` and with a bare parameterless request. See sharp edge 9.
```

with:

```
  without rejecting the default spelling. One channel is not closed here, because
  `cacheKeyIntParam` validates the **decoded** query value while the cache key is built from the
  **raw** query string: a percent-encoded value (`?id=%37`) is a second cache key for the same
  body. It is closed at the edge instead, by the viewer-request function in
  `scripts/cdn/normalize-catalog-query.js`. An encoded parameter *name* is not a channel at all —
  it is neither keyed nor forwarded. See sharp edge 9.
```

- [ ] **Step 8: Verify the docs index and the gate**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=DocumentationIndexTest
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
```

Expected: both BUILD SUCCESS. No `##` heading was renumbered and no document was added.

- [ ] **Step 9: Commit**

```bash
git add src/test/java/com/recsys/api/serving/CdnQueryNormalizationConformanceTest.java \
        pom.xml docker/cdn/default.conf.template docs/system_design/12_CDNS.md
git commit -m "test: pin the edge function's whitelist, and correct sharp edge 9"
```
