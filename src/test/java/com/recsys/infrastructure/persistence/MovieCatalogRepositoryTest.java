package com.recsys.infrastructure.persistence;

import com.recsys.application.catalog.CatalogCursorCodec.Position;
import com.recsys.application.pagination.MillionScalePaginationSql.SqlPlan;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MovieCatalogRepositoryTest {

    @Test
    void filteredFirstPageUsesFixedGenreIndexAndBindsGenreAndFetchLimit() throws Exception {
        MySqlClient client = mock(MySqlClient.class);
        when(client.query(any(SqlPlan.class), any(MySqlClient.RowMapper.class))).thenReturn(List.of());

        new MovieCatalogRepository(client).fetch("Sci-Fi", null, 21);

        SqlPlan plan = capturedPlan(client);
        assertThat(plan.sql()).isEqualTo("SELECT id, title, year, genre, popularity_score, updated_at "
                + "FROM movies FORCE INDEX (idx_movies_genre_popularity_id) "
                + "WHERE genre = ? AND (popularity_score, id) < (?, ?) "
                + "ORDER BY popularity_score DESC, id DESC LIMIT ?");
        assertThat(plan.bindValues()).containsExactly("Sci-Fi", new BigDecimal("1000000.000000"),
                Long.MAX_VALUE, 21);
    }

    @Test
    void unfilteredSeekPageUsesFixedPopularityIndexAndBindsExactDecimalTupleAndLimit() throws Exception {
        MySqlClient client = mock(MySqlClient.class);
        when(client.query(any(SqlPlan.class), any(MySqlClient.RowMapper.class))).thenReturn(List.of());
        var position = new Position(null, new BigDecimal("98.120000"), 42L);

        new MovieCatalogRepository(client).fetch(null, position, 101);

        SqlPlan plan = capturedPlan(client);
        assertThat(plan.sql()).isEqualTo("SELECT id, title, year, genre, popularity_score, updated_at "
                + "FROM movies FORCE INDEX (idx_movies_popularity_id) "
                + "WHERE (popularity_score, id) < (?, ?) "
                + "ORDER BY popularity_score DESC, id DESC LIMIT ?");
        assertThat(plan.bindValues()).containsExactly(new BigDecimal("98.120000"), 42L, 101);
    }

    @Test
    void filteredSeekPageBindsGenreBeforeSeekTuple() throws Exception {
        MySqlClient client = mock(MySqlClient.class);
        when(client.query(any(SqlPlan.class), any(MySqlClient.RowMapper.class))).thenReturn(List.of());

        new MovieCatalogRepository(client).fetch("Drama",
                new Position("Drama", new BigDecimal("7.500000"), 9L), 6);

        SqlPlan plan = capturedPlan(client);
        assertThat(plan.sql()).contains("WHERE genre = ? AND (popularity_score, id) < (?, ?)");
        assertThat(plan.bindValues()).containsExactly("Drama", new BigDecimal("7.500000"), 9L, 6);
    }

    @Test
    void mapsExactDecimalInstantAndNullableYear() throws Exception {
        MySqlClient client = mock(MySqlClient.class);
        ArgumentCaptor<MySqlClient.RowMapper> mapperCaptor = ArgumentCaptor.forClass(MySqlClient.RowMapper.class);
        when(client.query(any(SqlPlan.class), mapperCaptor.capture())).thenReturn(List.of());
        new MovieCatalogRepository(client).fetch(null, null, 1);
        ResultSet rs = mock(ResultSet.class);
        Instant updated = Instant.parse("2026-07-15T12:00:00.123456Z");
        when(rs.getLong("id")).thenReturn(42L);
        when(rs.getString("title")).thenReturn("Example");
        when(rs.getObject("year", Integer.class)).thenReturn(null);
        when(rs.getString("genre")).thenReturn("Sci-Fi");
        when(rs.getBigDecimal("popularity_score")).thenReturn(new BigDecimal("98.125000"));
        when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(updated));

        var movie = (com.recsys.domain.catalog.CatalogMovie) mapperCaptor.getValue().map(rs);

        assertThat(movie.id()).isEqualTo(42L);
        assertThat(movie.year()).isNull();
        assertThat(movie.popularityScore()).isEqualByComparingTo("98.125000");
        assertThat(movie.updatedAt()).isEqualTo(updated);
    }

    private static SqlPlan capturedPlan(MySqlClient client) throws Exception {
        ArgumentCaptor<SqlPlan> captor = ArgumentCaptor.forClass(SqlPlan.class);
        verify(client).query(captor.capture(), any(MySqlClient.RowMapper.class));
        return captor.getValue();
    }
}
