package com.recsys.application.pagination;
import com.recsys.application.pagination.MillionScalePaginationSql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.recsys.application.pagination.MillionScalePaginationSql.SortDirection.DESC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MillionScalePaginationSqlTest {

    private final MillionScalePaginationSql.TableSpec movieTable = MillionScalePaginationSql.table(
            "movie_events",
            "id",
            "created_at",
            DESC,
            "idx_movie_events_user_created_id",
            List.of("id", "movie_id", "score", "created_at")
    );

    @Test
    void coveringIndexDdl_placesFiltersBeforeSortAndId() {
        String ddl = MillionScalePaginationSql.coveringIndexDdl(
                movieTable,
                List.of("user_id", "event_type"),
                List.of("movie_id", "score")
        );

        assertThat(ddl).isEqualTo(
                "CREATE INDEX idx_movie_events_user_created_id ON movie_events "
                        + "(user_id, event_type, created_at DESC, id DESC, movie_id, score)"
        );
    }

    @Test
    void cursorPage_usesSeekPredicateInsteadOfOffset() {
        var cursor = new MillionScalePaginationSql.SeekCursor("2026-05-18 10:00:00", 42L);
        var plan = MillionScalePaginationSql.cursorPage(
                movieTable,
                List.of("user_id = ?", "event_type = ?"),
                List.of(7L, "click"),
                cursor,
                50
        );

        assertThat(plan.sql()).isEqualTo(
                "SELECT id, movie_id, score, created_at "
                        + "FROM movie_events FORCE INDEX (idx_movie_events_user_created_id) "
                        + "WHERE user_id = ? AND event_type = ? "
                        + "AND (created_at < ? OR (created_at = ? AND id < ?)) "
                        + "ORDER BY created_at DESC, id DESC LIMIT ?"
        );
        assertThat(plan.bindValues()).containsExactly(
                7L, "click", "2026-05-18 10:00:00", "2026-05-18 10:00:00", 42L, 50
        );
    }

    @Test
    void delayedJoinPage_offsetsOnlyInsideCoveringIndexSubquery() {
        var plan = MillionScalePaginationSql.delayedJoinPage(
                movieTable,
                List.of("user_id = ?"),
                List.of(7L),
                1_000_000,
                100
        );

        assertThat(plan.sql()).isEqualTo(
                "SELECT t.id, t.movie_id, t.score, t.created_at "
                        + "FROM movie_events t JOIN ("
                        + "SELECT id, created_at FROM movie_events FORCE INDEX (idx_movie_events_user_created_id) "
                        + "WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?"
                        + ") page_keys ON t.id = page_keys.id "
                        + "ORDER BY page_keys.created_at DESC, page_keys.id DESC"
        );
        assertThat(plan.bindValues()).containsExactly(7L, 100, 1_000_000);
    }

    @Test
    void seekCursor_roundTripsThroughOpaqueToken() {
        var cursor = new MillionScalePaginationSql.SeekCursor("2026-05-18 10:00:00", 42L);

        var decoded = MillionScalePaginationSql.SeekCursor.decode(cursor.encode());

        assertThat(decoded).isEqualTo(cursor);
    }

    @Test
    void countWithCoveringIndex_usesForceIndexAndNoLimitClause() {
        var plan = MillionScalePaginationSql.countWithCoveringIndex(
                movieTable,
                List.of("user_id = ?", "event_type = ?"),
                List.of(7L, "click")
        );

        assertThat(plan.sql()).isEqualTo(
                "SELECT COUNT(*) FROM movie_events"
                        + " FORCE INDEX (idx_movie_events_user_created_id)"
                        + " WHERE user_id = ? AND event_type = ?"
        );
        assertThat(plan.bindValues()).containsExactly(7L, "click");
    }

    @Test
    void countWithCoveringIndex_noPredicates_omitsWhereClause() {
        var plan = MillionScalePaginationSql.countWithCoveringIndex(movieTable, List.of(), List.of());

        assertThat(plan.sql()).isEqualTo(
                "SELECT COUNT(*) FROM movie_events FORCE INDEX (idx_movie_events_user_created_id)"
        );
        assertThat(plan.bindValues()).isEmpty();
    }

    @Test
    void cursorPageBefore_usesBeforeOperatorAndReversedOrder() {
        // DESC table → beforeOperator is ">" and reversed ORDER BY is ASC.
        // Callers reverse the returned list to restore DESC display order.
        var cursor = new MillionScalePaginationSql.SeekCursor("2026-05-18 10:00:00", 42L);
        var plan = MillionScalePaginationSql.cursorPageBefore(
                movieTable,
                List.of("user_id = ?"),
                List.of(7L),
                cursor,
                20
        );

        assertThat(plan.sql()).isEqualTo(
                "SELECT id, movie_id, score, created_at"
                        + " FROM movie_events FORCE INDEX (idx_movie_events_user_created_id)"
                        + " WHERE user_id = ?"
                        + " AND (created_at > ? OR (created_at = ? AND id > ?))"
                        + " ORDER BY created_at ASC, id ASC LIMIT ?"
        );
        assertThat(plan.bindValues()).containsExactly(
                7L, "2026-05-18 10:00:00", "2026-05-18 10:00:00", 42L, 20
        );
    }

    @Test
    void cursorPageBefore_requiresNonNullCursor() {
        assertThatThrownBy(() -> MillionScalePaginationSql.cursorPageBefore(
                movieTable, List.of(), List.of(), null, 10
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void identifiersRejectUnsafeSqlFragments() {
        assertThatThrownBy(() -> MillionScalePaginationSql.table(
                "movie_events; drop table users",
                "id",
                "created_at",
                DESC,
                "idx_movie_events_user_created_id",
                List.of("id")
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
