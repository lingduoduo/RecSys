package com.recsys.infrastructure.persistence;

import com.recsys.application.catalog.CatalogCursorCodec;
import com.recsys.application.catalog.MovieCatalogService;
import com.recsys.domain.catalog.CatalogMovie;
import com.recsys.domain.catalog.CatalogPage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("docker")
@Testcontainers
class MovieCatalogMySqlIntegrationTest {
    private static final String SIGNING_KEY = "test-only-catalog-cursor-signing-key-32-bytes";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("recsys");

    @Test
    void productionMigrationIndexesAndServiceProvideStableExactSizedPages() throws Exception {
        MySqlConnectionSettings settings = settings();
        new CatalogDatabaseBootstrap().migrate(settings);
        seedMovies();

        try (MySqlClient client = new MySqlClient(settings)) {
            MovieCatalogService service = new MovieCatalogService(
                    new MovieCatalogRepository(client), new CatalogCursorCodec(SIGNING_KEY));

            assertTraversal(service, null, 4, List.of(8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L));
            assertTraversal(service, "Drama", 2, List.of(8L, 6L, 4L, 2L));
        }

        assertExplainUsesRepositoryIndex("UNFILTERED_SQL", "idx_movies_popularity_id", null);
        assertExplainUsesRepositoryIndex("FILTERED_SQL", "idx_movies_genre_popularity_id", "Drama");
    }

    private static MySqlConnectionSettings settings() {
        return new MySqlConnectionSettings(true, MYSQL.getJdbcUrl(), MYSQL.getUsername(),
                MYSQL.getPassword(), 5, 1, 0, SIGNING_KEY);
    }

    private static void seedMovies() throws Exception {
        String insert = "INSERT INTO movies (id, title, year, genre, popularity_score) VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             PreparedStatement statement = connection.prepareStatement(insert)) {
            for (long id = 1; id <= 8; id++) {
                statement.setLong(1, id);
                statement.setString(2, "Movie " + id);
                statement.setInt(3, 2020 + (int) id);
                statement.setString(4, id % 2 == 0 ? "Drama" : "Sci-Fi");
                // Every boundary is a score tie, so only the id tie-breaker can prevent gaps.
                statement.setBigDecimal(5, new BigDecimal("42.000000"));
                statement.addBatch();
            }
            assertThat(statement.executeBatch()).hasSize(8);
        }
    }

    private static void assertTraversal(
            MovieCatalogService service, String genre, int pageSize, List<Long> expectedIds) throws Exception {
        List<Long> actualIds = new ArrayList<>();
        Set<Long> uniqueIds = new HashSet<>();
        String cursor = null;
        CatalogPage page;
        int pageCount = 0;
        do {
            page = service.list(genre, pageSize, cursor);
            pageCount++;
            assertThat(page.items()).hasSize(pageSize);
            List<Long> pageIds = page.items().stream().map(CatalogMovie::id).toList();
            assertThat(uniqueIds).as("page %s contains no duplicate ids", pageCount)
                    .doesNotContainAnyElementsOf(pageIds);
            uniqueIds.addAll(pageIds);
            actualIds.addAll(pageIds);
            cursor = page.nextCursor();
        } while (page.hasMore());

        assertThat(pageCount).isEqualTo(2);
        assertThat(actualIds).containsExactlyElementsOf(expectedIds);
        assertThat(uniqueIds).hasSize(expectedIds.size());
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    private static void assertExplainUsesRepositoryIndex(
            String sqlField, String expectedKey, String genre) throws Exception {
        String repositorySql = repositorySql(sqlField);
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             PreparedStatement statement = connection.prepareStatement("EXPLAIN " + repositorySql)) {
            int parameter = 1;
            if (genre != null) {
                statement.setString(parameter++, genre);
            }
            statement.setBigDecimal(parameter++, new BigDecimal("1000000.000000"));
            statement.setLong(parameter++, Long.MAX_VALUE);
            statement.setInt(parameter, 5);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("key")).isEqualTo(expectedKey);
                assertThat(result.next()).isFalse();
            }
        }
    }

    private static String repositorySql(String fieldName) throws Exception {
        Field field = MovieCatalogRepository.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String) field.get(null);
    }
}
