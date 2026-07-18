package com.recsys.infrastructure.persistence;

import com.recsys.application.catalog.CatalogCursorCodec.Position;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class MySqlIndexContractTest {
    private static final Pattern INLINE_INDEX = Pattern.compile(
            "(?im)(?:^|[,\\(])\\s*(?:INDEX|KEY)\\s+`?([a-zA-Z0-9_]+)`?\\s*\\(");
    private static final Pattern STANDALONE_INDEX = Pattern.compile(
            "(?i)\\bCREATE\\s+INDEX\\s+`?([a-zA-Z0-9_]+)`?\\s+ON\\b");
    private static final Position AFTER =
            new Position(null, new BigDecimal("42.000000"), 8L);

    @Test
    void filteredCatalogQueryMatchesItsFlywayManagedCompositeIndex() throws Exception {
        MySqlIndexContractAssertions.assertContract(
                migrationSql(),
                MovieCatalogRepository.plan("Drama",
                        new Position("Drama", AFTER.popularityScore(), AFTER.movieId()), 21),
                "idx_movies_genre_popularity_id",
                List.of("genre", "popularity_score DESC", "id DESC"),
                "WHERE genre = ? AND (popularity_score, id) < (?, ?)",
                "ORDER BY popularity_score DESC, id DESC");
    }

    @Test
    void unfilteredCatalogQueryMatchesItsFlywayManagedCompositeIndex() throws Exception {
        MySqlIndexContractAssertions.assertContract(
                migrationSql(),
                MovieCatalogRepository.plan(null, AFTER, 21),
                "idx_movies_popularity_id",
                List.of("popularity_score DESC", "id DESC"),
                "WHERE (popularity_score, id) < (?, ?)",
                "ORDER BY popularity_score DESC, id DESC");
    }

    @Test
    void discoversInlineAndStandaloneSecondaryIndexDeclarations() {
        String sql = """
                CREATE TABLE movies (INDEX idx_inline (genre));
                CREATE INDEX idx_standalone ON movies (popularity_score);
                """;

        org.junit.jupiter.api.Assertions.assertEquals(
                Set.of("idx_inline", "idx_standalone"), secondaryIndexNames(sql));
    }

    @Test
    void flywaySecondaryIndexInventoryContainsOnlyWorkloadRequiredIndexes() throws Exception {
        org.junit.jupiter.api.Assertions.assertEquals(
                Set.of("idx_movies_genre_popularity_id", "idx_movies_popularity_id"),
                secondaryIndexNames(migrationSql()));
    }

    private static Set<String> secondaryIndexNames(String sql) {
        var names = new TreeSet<String>();
        for (Pattern pattern : List.of(INLINE_INDEX, STANDALONE_INDEX)) {
            var matcher = pattern.matcher(sql);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    private static String migrationSql() throws IOException {
        Path migrationDirectory = Path.of("src/main/resources/db/migration");
        try (var migrations = Files.list(migrationDirectory)) {
            List<Path> sqlFiles = migrations
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .toList();
            if (sqlFiles.isEmpty()) {
                throw new IOException("no Flyway SQL migrations found in " + migrationDirectory);
            }
            return sqlFiles.stream()
                    .map(MySqlIndexContractTest::readMigration)
                    .collect(Collectors.joining("\n"));
        }
    }

    private static String readMigration(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException("failed to read " + path, exception);
        }
    }
}
