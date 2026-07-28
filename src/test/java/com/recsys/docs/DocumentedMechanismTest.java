package com.recsys.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the topic docs honest about what is actually wired up.
 *
 * <p>An audit of the Redis key-value layer in July 2026 found the same defect five times:
 * <strong>code that exists, that the docs describe as live protection, and that nothing in
 * production calls.</strong> The top-K shard fan-out (`seedAllShards`), the sequence-counter
 * guard (`ensureCounterValid`) and `readAllShards` were each documented as active — down to
 * default shard counts and startup behaviour — while their only caller was a test. In every
 * case the documentation was the more dangerous artifact: an operator would believe hot-key
 * sharding or a startup integrity check was protecting them when nothing was.
 *
 * <p>That is a class of defect, not three bugs, and discipline does not catch it — the code
 * compiles, the tests pass, and the doc reads plausibly. This asserts it instead, in the same
 * spirit as {@link DocumentationIndexTest}.
 *
 * <p>Two invariants:
 *
 * <ol>
 *   <li>Every repo-relative source link in the topic docs resolves to a file that exists, so
 *       deleting or renaming a class cannot silently leave the docs pointing at nothing.</li>
 *   <li>Every method named in the topic docs still has a production caller — or is listed in
 *       {@link #DOCUMENTED_WITHOUT_A_CALLER} with a reason. A new entry appearing means
 *       someone documented a mechanism that nothing invokes, or removed the last caller of one
 *       that is still documented as live. Either way it needs a decision, not a default.</li>
 * </ol>
 */
class DocumentedMechanismTest {

    /** Surefire runs with the project root as the working directory. */
    private static final Path REPO = Path.of("");
    private static final List<Path> TOPIC_DOCS =
            List.of(Path.of("docs", "system_design"), Path.of("docs", "runbooks"));
    private static final Path MAIN = Path.of("src", "main", "java", "com", "recsys");

    /** A markdown link into the source tree, e.g. {@code ](../../src/main/java/...Foo.java)}. */
    private static final Pattern SOURCE_LINK =
            Pattern.compile("]\\((\\.\\./\\.\\./(src/[A-Za-z0-9_./-]+?\\.java))(?:#[A-Za-z0-9_-]*)?\\)");

    /**
     * A {@code public}/{@code protected} method declaration, capturing any annotations
     * immediately above it so framework-invoked methods can be filtered out.
     */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "((?:^[ \\t]*@\\w+[^\\n]*\\n)*)"
                    + "^[ \\t]*(?:public|protected)\\s+"
                    + "(?:static\\s+|final\\s+|synchronized\\s+|default\\s+)*"
                    + "(?!class|interface|enum|record)"
                    + "[\\w<>\\[\\],.?\\s]+?\\s+(\\w+)\\s*\\(",
            Pattern.MULTILINE);

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern CALL_SITE = Pattern.compile("\\b(\\w+)\\s*\\(");

    /** Invoked reflectively or by a framework, so a textual call site proves nothing. */
    private static final Set<String> FRAMEWORK_ANNOTATIONS = Set.of(
            "@Override", "@Bean", "@GetMapping", "@PostMapping", "@PutMapping", "@DeleteMapping",
            "@RequestMapping", "@EventListener", "@Scheduled", "@PostConstruct", "@PreDestroy",
            "@ExceptionHandler");

    /** Object/functional-interface methods, and reactive callbacks a framework drives. */
    private static final Set<String> FRAMEWORK_METHODS = Set.of(
            "main", "toString", "equals", "hashCode", "close", "run", "call", "get", "apply",
            "accept", "compare", "compareTo", "iterator", "onError", "onComplete", "onNext",
            "onSubscribe", "onFailure", "onSuccess");

    /**
     * Packages that deliberately model infrastructure rather than run it — AWS ALB and
     * Auto Scaling value objects, and the Flink job (excluded from the Maven compile entirely).
     * Nothing here is expected to have a production caller inside this repo.
     */
    private static final List<String> REFERENCE_PACKAGES =
            List.of("infrastructure/alb/", "infrastructure/autoscaling/", "online/flink/");

    /**
     * Methods the topic docs name that have no production caller, each with the reason it is
     * acceptable. Adding an entry is a deliberate act: it means "the docs describe this, and it
     * is correct that nothing calls it."
     *
     * <p>Do not add an entry to silence the test. If a doc presents something as live and
     * nothing calls it, fix the doc — that is the defect this test exists to catch.
     */
    private static final Map<String, String> DOCUMENTED_WITHOUT_A_CALLER = Map.of(
            // 13_DB_Indexing §3 and 19_Pagination §3 present these access patterns as a reference
            // implementation. The live catalog path (MovieCatalogRepository) hand-writes its SQL
            // as tuned FORCE INDEX constants and only borrows the SqlPlan/SeekCursor types, so
            // these builders are demonstrative. The docs say so explicitly.
            "cursorPage", "reference implementation — see 13_DB_Indexing §3",
            "cursorPageBefore", "reference implementation — see 13_DB_Indexing §3",
            "countWithCoveringIndex", "reference implementation — see 13_DB_Indexing §3",
            "coveringIndexDdl", "reference implementation — see 13_DB_Indexing §3",
            "delayedJoinPage", "reference implementation — see 19_Pagination §3",
            // 06_Consistent_Hashing §"Diagnosis" and 14_Partitioning describe this as a helper
            // available for hot-shard diagnosis; no endpoint or metric exposes it today, and the
            // docs say that rather than implying a live surface.
            "distribution", "library helper, no wired surface — see 06_Consistent_Hashing");

    // ── Invariant 1: doc → source links resolve ──────────────────────────────────────

    @Test
    void everySourceLinkInTopicDocsResolvesToAFileThatExists() throws IOException {
        Set<String> broken = new TreeSet<>();
        int checked = 0;

        for (Path dir : TOPIC_DOCS) {
            for (Path doc : markdownFilesIn(dir)) {
                String text = Files.readString(doc, StandardCharsets.UTF_8);
                Matcher m = SOURCE_LINK.matcher(text);
                while (m.find()) {
                    checked++;
                    if (!Files.isRegularFile(REPO.resolve(m.group(2)))) {
                        broken.add(doc.getFileName() + " -> " + m.group(2));
                    }
                }
            }
        }

        // A broken regex would parse nothing and pass vacuously. Pin a floor.
        assertThat(checked).as("source links parsed out of the topic docs").isGreaterThan(100);
        assertThat(broken)
                .as("topic docs link to source files that do not exist — most likely a class was "
                        + "renamed or deleted without updating the doc that describes it")
                .isEmpty();
    }

    // ── Invariant 2: documented mechanisms are actually invoked ──────────────────────

    @Test
    void everyMethodTheDocsNameStillHasAProductionCaller() throws IOException {
        Map<Path, String> sources = mainSources();
        String docText = topicDocText();

        // One pass over all of src/main: how often is each identifier used as a call?
        Map<String, Integer> callsAcrossMain = new HashMap<>();
        Map<Path, Map<String, Integer>> callsPerFile = new HashMap<>();
        for (Map.Entry<Path, String> e : sources.entrySet()) {
            Map<String, Integer> perFile = countCalls(e.getValue());
            callsPerFile.put(e.getKey(), perFile);
            perFile.forEach((name, n) -> callsAcrossMain.merge(name, n, Integer::sum));
        }

        Set<String> orphans = new TreeSet<>();
        int declarationsScanned = 0;

        for (Map.Entry<Path, String> entry : sources.entrySet()) {
            Path file = entry.getKey();
            String relative = normalize(file);
            if (REFERENCE_PACKAGES.stream().anyMatch(relative::contains)) continue;

            Matcher m = METHOD_DECL.matcher(entry.getValue());
            while (m.find()) {
                String annotations = m.group(1) == null ? "" : m.group(1);
                String name = m.group(2);
                declarationsScanned++;

                if (FRAMEWORK_METHODS.contains(name) || name.length() < 5) continue;
                if (FRAMEWORK_ANNOTATIONS.stream().anyMatch(annotations::contains)) continue;

                int inThisFile = callsPerFile.get(file).getOrDefault(name, 0);
                int elsewhere = callsAcrossMain.getOrDefault(name, 0) - inThisFile;
                if (elsewhere > 0) continue;          // some other production class calls it
                if (inThisFile > 1) continue;         // used internally besides its declaration

                if (docText.contains("`" + name + "`") || docText.contains(name + "(")) {
                    orphans.add(name + "  (" + relative + ")");
                }
            }
        }

        // Same vacuous-pass guard as above: if the declaration regex breaks, nothing is scanned.
        assertThat(declarationsScanned)
                .as("method declarations scanned across src/main").isGreaterThan(500);

        Set<String> unexpected = new TreeSet<>(orphans);
        unexpected.removeIf(o -> DOCUMENTED_WITHOUT_A_CALLER.containsKey(methodNameOf(o)));

        assertThat(unexpected)
                .as("the topic docs describe these methods, but nothing in src/main calls them. "
                        + "Either the doc is describing a mechanism that is not actually wired up "
                        + "(fix the doc, or wire it up), or this is deliberate — in which case add "
                        + "it to DOCUMENTED_WITHOUT_A_CALLER with the reason")
                .isEmpty();
    }

    @Test
    void theAllowListDoesNotOutliveTheMethodsItExcuses() throws IOException {
        Map<Path, String> sources = mainSources();
        String allMain = String.join("\n", sources.values());

        Set<String> vanished = new TreeSet<>();
        for (String name : DOCUMENTED_WITHOUT_A_CALLER.keySet()) {
            if (!allMain.contains(name + "(")) vanished.add(name);
        }

        assertThat(vanished)
                .as("DOCUMENTED_WITHOUT_A_CALLER excuses methods that no longer exist in src/main "
                        + "— remove the stale entries so the list keeps meaning something")
                .isEmpty();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────

    private static Map<String, Integer> countCalls(String source) {
        Map<String, Integer> counts = new HashMap<>();
        Matcher m = CALL_SITE.matcher(source);
        while (m.find()) counts.merge(m.group(1), 1, Integer::sum);
        return counts;
    }

    private static String methodNameOf(String orphanEntry) {
        int space = orphanEntry.indexOf(' ');
        return space < 0 ? orphanEntry : orphanEntry.substring(0, space);
    }

    private static Map<Path, String> mainSources() throws IOException {
        Path root = REPO.resolve(MAIN);
        assertThat(Files.isDirectory(root)).as("main source root %s exists", MAIN).isTrue();
        Map<Path, String> sources = new HashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                // Strip block comments so javadoc examples do not read as call sites.
                sources.put(p, BLOCK_COMMENT.matcher(text).replaceAll(""));
            }
        }
        assertThat(sources).as("java sources under src/main").hasSizeGreaterThan(100);
        return sources;
    }

    private static String topicDocText() throws IOException {
        StringBuilder all = new StringBuilder();
        for (Path dir : TOPIC_DOCS) {
            for (Path doc : markdownFilesIn(dir)) {
                all.append(Files.readString(doc, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return all.toString();
    }

    private static List<Path> markdownFilesIn(Path dir) throws IOException {
        Path resolved = REPO.resolve(dir);
        assertThat(Files.isDirectory(resolved)).as("topic doc directory %s exists", dir).isTrue();
        try (Stream<Path> files = Files.list(resolved)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList();
        }
    }

    private static String normalize(Path p) {
        return p.toString().replace('\\', '/');
    }
}
