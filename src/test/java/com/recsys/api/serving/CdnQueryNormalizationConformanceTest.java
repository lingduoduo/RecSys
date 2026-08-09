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
 * <p><b>Every scan here is closed, not best-effort.</b> A text scan that quietly drops what it
 * cannot parse compares two subsets and passes by omission — which is exactly how the first version
 * of this test shipped green while both {@code /api/*&#47;catalog/similar} behaviors were missing
 * from the function's map. Four reproduced holes, and the invariants that now close them:
 *
 * <ul>
 *   <li>Reformatting an {@code ensure_cache_policy} call across two lines made {@code POLICY_DECL}
 *       (applied per line) miss it, so {@code $similar_policy} resolved to nothing and both
 *       {@code similar} paths vanished from the script-side map — matching a function map they had
 *       also been deleted from. Closed by {@link #everyCachedBehaviorResolvesToADeclaredCachePolicy}:
 *       every {@code PathPattern} in the file must appear in the resolved map, and an unresolved
 *       {@code CachePolicyId} reference is reported by name.
 *   <li>A cached behavior written on one line was skipped entirely: the old loop {@code continue}d
 *       after a {@code PathPattern} match, so a same-line {@code CachePolicyId} was never seen, and
 *       the fixed six-line association window borrowed the <em>next</em> behavior's
 *       {@code FunctionAssociations}. Closed by parsing the same line for both, by bounding each
 *       association block at the next {@code PathPattern}, and by cross-checking the behavior count
 *       against the {@code CacheBehaviors: {Quantity: N} } the script declares.
 *   <li>{@link #policyVariables} scanned every line of the shell script and {@code put} into a map,
 *       so a commented-out {@code ensure_cache_policy} call placed <em>below</em> the live one
 *       silently redefined the baseline this test compares against — real drift in the live line
 *       (e.g. dropping a key from the whitelist) still passed, because the stale comment's value
 *       won the last-write-wins map. Closed by skipping any line whose first non-whitespace
 *       character is {@code #}, and by failing loudly on a genuine duplicate definition of the same
 *       variable rather than letting either line win silently.
 *   <li>{@code ALLOWED_ENTRY} and {@code JS_STRING} matched single-quoted keys and strings only, so
 *       a double-quoted entry added to the function's {@code ALLOWED} literal silently vanished from
 *       {@link #allowedInFunction} instead of surfacing as an unexpected extra route — the drop
 *       direction was already caught by the equality check, only the extra-route direction was
 *       silent. Closed by accepting either quote style for both the route key and the parameter
 *       names.
 * </ul>
 *
 * <p>Scope: this compares two committed files. A cache policy edited by hand in the AWS console is
 * invisible here, as it is to every other conformance test in this repo. One known residue is the
 * safe direction rather than the dangerous one: a commented-out entry <em>inside</em> the
 * {@code ALLOWED} literal (e.g. {@code // '/api/x': ['y']}) is parsed as if it were live, so it
 * fails the build with a phantom extra route rather than silently doing nothing — noisy, not
 * closed, but never a false pass.
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
    /** {@code CacheBehaviors: {Quantity: 4, Items: [} */
    private static final Pattern BEHAVIOR_QUANTITY = Pattern.compile(
            "CacheBehaviors:\\s*\\{\\s*Quantity:\\s*(\\d+)");
    /**
     * {@code '/api/catalog/similar': ['movieId', 'k']} — key quoted with either {@code '} or
     * {@code "}. A backreference ties the closing quote to the opener so a key cannot itself
     * contain the other quote character; that is already true of every path here.
     */
    private static final Pattern ALLOWED_ENTRY = Pattern.compile(
            "(['\"])(/[^'\"]*)\\1\\s*:\\s*\\[([^\\]]*)\\]");
    /** A single JS string literal, quoted with either {@code '} or {@code "}. */
    private static final Pattern JS_STRING = Pattern.compile("(['\"])([^'\"]*)\\1");

    /** First line of the {@code CacheBehaviors} array's successor — the scan's hard right edge. */
    private static final String BEHAVIORS_END = "ViewerCertificate:";

    @Test
    void theFunctionsAllowListMatchesTheCachePolicies() throws IOException {
        Map<String, List<String>> fromScript = parseScript().whitelistsByPath();
        Map<String, List<String>> fromFunction = allowedInFunction();

        // A silently-empty scan would pass this test while proving nothing.
        assertThat(fromScript)
                .as("no cached behavior parsed from %s — the scan found nothing to check", SCRIPT)
                .isNotEmpty();
        assertThat(new TreeMap<>(fromFunction))
                .as("the function's ALLOWED map must equal the cache-policy whitelists")
                .isEqualTo(new TreeMap<>(fromScript));
    }

    /**
     * Nothing may drop out of the script-side scan. Both maps agreeing on a subset of the real
     * behaviors is the failure this test exists to make impossible, so the resolved map is checked
     * against two independent counts of what the script declares: every {@code PathPattern} line,
     * and the {@code Quantity} the {@code CacheBehaviors} array states.
     */
    @Test
    void everyCachedBehaviorResolvesToADeclaredCachePolicy() throws IOException {
        CachedBehaviors behaviors = parseScript();

        assertThat(behaviors.unresolvedPolicyRefs())
                .as("a cached behavior references a cache-policy variable this scan could not "
                        + "resolve — its whitelist would otherwise vanish from the comparison")
                .isEmpty();
        assertThat(behaviors.whitelistsByPath().keySet())
                .as("every PathPattern in %s must resolve to a cache policy", SCRIPT)
                .containsExactlyInAnyOrderElementsOf(behaviors.declaredPaths());
        assertThat(behaviors.declaredQuantity())
                .as("CacheBehaviors declares Quantity: %d but %d behaviors were parsed",
                        behaviors.declaredQuantity(), behaviors.whitelistsByPath().size())
                .isEqualTo(behaviors.whitelistsByPath().size());
    }

    /** Every cached behavior must actually run the function, or its whitelist is decoration. */
    @Test
    void everyCachedBehaviorAssociatesTheViewerRequestFunction() throws IOException {
        List<String> lines = Files.readAllLines(SCRIPT);
        int end = indexOfLineContaining(lines, BEHAVIORS_END);
        assertThat(end)
                .as("'%s' not found in %s — cannot bound the CacheBehaviors scan", BEHAVIORS_END, SCRIPT)
                .isNotNegative();

        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < end; i++) {
            if (PATH_PATTERN.matcher(lines.get(i)).find()) {
                starts.add(i);
            }
        }
        assertThat(starts).as("no cached behavior found in %s", SCRIPT).isNotEmpty();

        List<String> unassociated = new ArrayList<>();
        for (int k = 0; k < starts.size(); k++) {
            int from = starts.get(k);
            // Bounded by the NEXT behavior, never by a fixed line count: a fixed window either
            // truncates a long behavior or reads the following one's association as this one's.
            int to = k + 1 < starts.size() ? starts.get(k + 1) : end;
            String block = String.join("\n", lines.subList(from, to));
            if (!block.contains("FunctionAssociations")
                    || !block.contains("\"viewer-request\"")
                    || !block.contains("FunctionARN: $fn")) {
                unassociated.add(pathOf(lines.get(from)));
            }
        }

        assertThat(unassociated)
                .as("every cached behavior must associate the viewer-request function")
                .isEmpty();
    }

    /**
     * The function must NOT run on the default behavior. It fails closed on any URI outside its
     * four-key map, so associating it there would turn every non-catalog route — including
     * {@code POST /api/recommend} — into a hard 400 at the edge.
     */
    @Test
    void theDefaultCacheBehaviorDoesNotAssociateTheFunction() throws IOException {
        List<String> lines = Files.readAllLines(SCRIPT);
        int from = indexOfLineContaining(lines, "DefaultCacheBehavior: {");
        int to = indexOfLineContaining(lines, "CacheBehaviors: {");

        assertThat(from).as("DefaultCacheBehavior not found in %s", SCRIPT).isNotNegative();
        assertThat(to).as("CacheBehaviors not found after DefaultCacheBehavior in %s", SCRIPT)
                .isGreaterThan(from);

        assertThat(String.join("\n", lines.subList(from, to)))
                .as("the fail-closed function must not be associated with DefaultCacheBehavior — "
                        + "it would 400 every route that is not one of the four catalog reads")
                .doesNotContain("FunctionAssociations");
    }

    /**
     * The scans above pass vacuously if the patterns cannot see a real declaration. This asserts
     * against the committed files themselves, not against inline copies of what they are believed
     * to say — an inline copy keeps matching after the file it quotes has been reformatted, which
     * is precisely how a reformatted {@code ensure_cache_policy} call went undetected here.
     */
    @Test
    void thePatternsRecogniseTheSpellingsTheseFilesActuallyUse() throws IOException {
        Map<String, List<String>> byVariable = policyVariables(Files.readAllLines(SCRIPT));
        assertThat(byVariable)
                .as("POLICY_DECL must match the ensure_cache_policy calls %s actually contains", SCRIPT)
                .containsKeys("item_policy", "similar_policy");
        assertThat(byVariable.get("item_policy")).containsExactly("id");
        assertThat(byVariable.get("similar_policy")).containsExactly("movieId", "k");

        CachedBehaviors behaviors = parseScript();
        assertThat(behaviors.declaredPaths())
                .as("PATH_PATTERN must match the cached behaviors %s actually contains", SCRIPT)
                .contains("/api/catalog/item", "/api/v1/catalog/similar");
        assertThat(behaviors.whitelistsByPath())
                .as("POLICY_REF must resolve the CachePolicyId lines %s actually contains", SCRIPT)
                .containsEntry("/api/catalog/item", List.of("id"))
                .containsEntry("/api/v1/catalog/similar", List.of("movieId", "k"));

        Map<String, List<String>> allowed = allowedInFunction();
        assertThat(allowed)
                .as("ALLOWED_ENTRY must match the entries %s actually contains", FUNCTION)
                .containsEntry("/api/catalog/similar", List.of("movieId", "k"));
    }

    /** What the distribution script declares about its cached behaviors. */
    private record CachedBehaviors(
            Map<String, List<String>> whitelistsByPath,
            List<String> declaredPaths,
            List<String> unresolvedPolicyRefs,
            int declaredQuantity) {
    }

    private static CachedBehaviors parseScript() throws IOException {
        List<String> lines = Files.readAllLines(SCRIPT);
        Map<String, List<String>> byVariable = policyVariables(lines);

        Map<String, List<String>> byPath = new LinkedHashMap<>();
        List<String> declaredPaths = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();

        String pendingPath = null;
        for (String line : lines) {
            Matcher p = PATH_PATTERN.matcher(line);
            if (p.find()) {
                pendingPath = p.group(1);
                declaredPaths.add(pendingPath);
                // Deliberately no `continue`: a behavior written on one line carries its
                // CachePolicyId on this same line, and skipping it dropped the whole behavior.
            }
            Matcher r = POLICY_REF.matcher(line);
            if (pendingPath != null && r.find()) {
                List<String> names = byVariable.get(r.group(1));
                if (names == null) {
                    unresolved.add(pendingPath + " -> $" + r.group(1));
                } else {
                    byPath.put(pendingPath, names);
                }
                pendingPath = null;
            }
        }

        int quantity = -1;
        Matcher q = BEHAVIOR_QUANTITY.matcher(String.join("\n", lines));
        if (q.find()) {
            quantity = Integer.parseInt(q.group(1));
        }
        return new CachedBehaviors(byPath, declaredPaths, unresolved, quantity);
    }

    /**
     * cache-policy shell variable -> whitelisted parameter names.
     *
     * <p>Two invariants close a shell-comment shaped hole: a commented-out {@code
     * ensure_cache_policy} call below the live one used to silently redefine the baseline this
     * test compares against (last write wins), masking real drift in the live line — see NEW-1.
     * Comment lines are skipped outright, and a genuine duplicate definition of the same variable
     * (two live lines, not one live + one commented) fails loudly instead of picking either.
     */
    private static Map<String, List<String>> policyVariables(List<String> lines) {
        Map<String, List<String>> byVariable = new LinkedHashMap<>();
        for (String line : lines) {
            if (line.stripLeading().startsWith("#")) {
                continue;
            }
            Matcher m = POLICY_DECL.matcher(line);
            if (m.find()) {
                List<String> names = new ArrayList<>();
                Matcher s = Pattern.compile("\"([^\"]*)\"").matcher(m.group(2));
                while (s.find()) {
                    names.add(s.group(1));
                }
                List<String> previous = byVariable.putIfAbsent(m.group(1), names);
                if (previous != null) {
                    throw new AssertionError(
                            "duplicate ensure_cache_policy definition for $" + m.group(1)
                                    + " in " + SCRIPT + " — first: " + previous + ", second: " + names);
                }
            }
        }
        return byVariable;
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
            Matcher s = JS_STRING.matcher(m.group(3));
            while (s.find()) {
                names.add(s.group(2));
            }
            allowed.put(m.group(2), names);
        }
        return allowed;
    }

    private static int indexOfLineContaining(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static String pathOf(String line) {
        Matcher m = PATH_PATTERN.matcher(line);
        return m.find() ? m.group(1) : line.trim();
    }
}
