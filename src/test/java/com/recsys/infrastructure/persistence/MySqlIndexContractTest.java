package com.recsys.infrastructure.persistence;

import com.recsys.application.catalog.CatalogCursorCodec.Position;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

class MySqlIndexContractTest {
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

    private static String migrationSql() throws IOException {
        try (var input = MySqlIndexContractTest.class
                .getResourceAsStream("/db/migration/V1__create_movies_catalog.sql")) {
            if (input == null) {
                throw new IOException("missing V1 movies migration");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
