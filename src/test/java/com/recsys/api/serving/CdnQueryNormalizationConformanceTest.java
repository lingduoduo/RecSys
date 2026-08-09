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
