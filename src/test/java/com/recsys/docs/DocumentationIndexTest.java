package com.recsys.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps README's documentation map complete in both directions.
 *
 * <p>The map used to be "intentionally curated", which in practice meant 14 of 20 system-design
 * investigations and 12 of 17 runbooks had no entry point, and a renamed file left dangling links
 * behind. Curation by discipline does not survive contact with a rename; this asserts it instead.
 *
 * <p>Deliberately scoped to {@code docs/system_design/} and {@code docs/runbooks/} — the topic
 * documentation. {@code docs/superpowers/} holds ~130 point-in-time specs and plans; those are
 * historical records of individual changes, not topics a reader should be routed to from README.
 */
class DocumentationIndexTest {

    /** Surefire runs with the project root as the working directory. */
    private static final Path REPO = Path.of("");
    private static final Path README = REPO.resolve("README.md");

    /**
     * {@code markdownFilesIn} uses {@link Files#list} rather than {@code walk}, so listing
     * {@code docs} itself covers only root-level documents and does not pull in
     * {@code docs/superpowers/}. Root is indexed because a document parked there — today only
     * {@code api-compatibility-policy.md} — was in a blind spot: the README-link direction
     * caught a *dangling* link, but nothing caught the file simply never being linked at all.
     */
    private static final List<Path> INDEXED_DIRECTORIES = List.of(
            Path.of("docs"),
            Path.of("docs", "system_design"),
            Path.of("docs", "runbooks"));

    /** Matches a repo-relative markdown link target, with any #anchor stripped by group 1. */
    private static final Pattern DOC_LINK =
            Pattern.compile("\\((docs/[A-Za-z0-9_./-]+?\\.md)(?:#[A-Za-z0-9_-]*)?\\)");

    @Test
    void everyTopicDocIsLinkedFromTheReadme() throws IOException {
        Set<String> linked = linkedDocPaths();

        Set<String> unlinked = new TreeSet<>();
        for (Path dir : INDEXED_DIRECTORIES) {
            for (Path doc : markdownFilesIn(dir)) {
                String relative = normalize(doc);
                if (!linked.contains(relative)) unlinked.add(relative);
            }
        }

        assertThat(unlinked)
                .as("docs with no entry point in README — add them to the documentation map, "
                        + "or move them under docs/superpowers/ if they are point-in-time records")
                .isEmpty();
    }

    @Test
    void everyReadmeDocLinkResolvesToAFileThatExists() throws IOException {
        Set<String> broken = new TreeSet<>();
        for (String link : linkedDocPaths()) {
            if (!Files.isRegularFile(REPO.resolve(link))) broken.add(link);
        }

        assertThat(broken)
                .as("README links to docs that do not exist — most likely a file was renamed "
                        + "without updating the link")
                .isEmpty();
    }

    private static Set<String> linkedDocPaths() throws IOException {
        String readme = Files.readString(README, StandardCharsets.UTF_8);
        Set<String> links = new TreeSet<>();
        Matcher m = DOC_LINK.matcher(readme);
        while (m.find()) links.add(m.group(1));
        // A regression here would silently pass both tests: no links parsed means nothing to
        // check. Pin a floor so a broken regex fails loudly instead of vacuously succeeding.
        assertThat(links).as("doc links parsed out of README").hasSizeGreaterThan(20);
        return links;
    }

    private static List<Path> markdownFilesIn(Path dir) throws IOException {
        Path resolved = REPO.resolve(dir);
        assertThat(Files.isDirectory(resolved)).as("indexed directory %s exists", dir).isTrue();
        try (Stream<Path> files = Files.list(resolved)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList();
        }
    }

    private static String normalize(Path p) {
        return p.toString().replace('\\', '/');
    }
}
