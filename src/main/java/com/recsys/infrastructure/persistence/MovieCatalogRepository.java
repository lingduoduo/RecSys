package com.recsys.infrastructure.persistence;

import com.recsys.application.catalog.CatalogCursorCodec.Position;
import com.recsys.application.pagination.MillionScalePaginationSql.SqlPlan;
import com.recsys.domain.catalog.CatalogMovie;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

public class MovieCatalogRepository {
    private static final BigDecimal BEFORE_FIRST_SCORE = new BigDecimal("1000000.000000");
    private static final String FILTERED_SQL =
            "SELECT id, title, year, genre, popularity_score, updated_at "
                    + "FROM movies FORCE INDEX (idx_movies_genre_popularity_id) "
                    + "WHERE genre = ? AND (popularity_score, id) < (?, ?) "
                    + "ORDER BY popularity_score DESC, id DESC LIMIT ?";
    private static final String UNFILTERED_SQL =
            "SELECT id, title, year, genre, popularity_score, updated_at "
                    + "FROM movies FORCE INDEX (idx_movies_popularity_id) "
                    + "WHERE (popularity_score, id) < (?, ?) "
                    + "ORDER BY popularity_score DESC, id DESC LIMIT ?";

    private final MySqlClient client;

    public MovieCatalogRepository(MySqlClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public List<CatalogMovie> fetch(String genre, Position after, int fetchLimit) throws SQLException {
        if (fetchLimit <= 0) {
            throw new IllegalArgumentException("fetchLimit must be positive");
        }
        BigDecimal score = after == null ? BEFORE_FIRST_SCORE : after.popularityScore();
        long movieId = after == null ? Long.MAX_VALUE : after.movieId();
        SqlPlan plan = genre == null
                ? new SqlPlan(UNFILTERED_SQL, List.of(score, movieId, fetchLimit))
                : new SqlPlan(FILTERED_SQL, List.of(genre, score, movieId, fetchLimit));
        return client.query(plan, rs -> new CatalogMovie(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getObject("year", Integer.class),
                rs.getString("genre"),
                rs.getBigDecimal("popularity_score"),
                rs.getTimestamp("updated_at").toInstant()
        ));
    }
}
