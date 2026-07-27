package com.recsys.infrastructure.persistence;

import com.recsys.application.pagination.MillionScalePaginationSql;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MySqlClientTest {

    // Shared mock wiring for tests that exercise the query/queryPage path.
    // Override resultSet.next() stubs in each test to control the returned rows.
    private record Mocks(Connection connection, PreparedStatement statement, ResultSet resultSet) {}

    private static Mocks mockQuery() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);
        return new Mocks(connection, statement, resultSet);
    }

    @Test
    void isAutoCloseable_andCloseIsSafeBeforeAnyConnection() throws Exception {
        MySqlClient client = new MySqlClient(MySqlConnectionSettings.disabled());
        assertThat(client).isInstanceOf(AutoCloseable.class);
        client.close();  // no pool was ever created -> no-op, must not throw
        client.close();  // idempotent
    }

    @Test
    void disabledClient_doesNotOpenConnections() {
        MySqlClient client = new MySqlClient(MySqlConnectionSettings.disabled());

        assertThat(client.isEnabled()).isFalse();
        assertThat(client.healthCheck()).isEqualTo(new MySqlClient.HealthCheck(false, false, "disabled"));
        assertThatThrownBy(client::openConnection)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MySQL is disabled");
    }

    @Test
    void query_bindsPlanValuesAndMapsRows() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement("SELECT id, name FROM movies WHERE user_id = ? LIMIT ?"))
                .thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getInt("id")).thenReturn(1, 2);
        when(resultSet.getString("name")).thenReturn("Toy Story", "Heat");

        MySqlClient client = new MySqlClient(MySqlConnectionSettings.disabled());
        var plan = new MillionScalePaginationSql.SqlPlan(
                "SELECT id, name FROM movies WHERE user_id = ? LIMIT ?",
                List.of(7, 2)
        );

        List<String> rows = client.query(connection, plan,
                rs -> rs.getInt("id") + ":" + rs.getString("name"));

        assertThat(rows).containsExactly("1:Toy Story", "2:Heat");
        var inOrder = inOrder(statement, resultSet);
        inOrder.verify(statement).setObject(1, 7);
        inOrder.verify(statement).setObject(2, 2);
        inOrder.verify(statement).executeQuery();
        verify(resultSet).close();
        verify(statement).close();
    }

    @Test
    void query_setsTimeoutWhenPositive() throws Exception {
        var m = mockQuery();
        new MySqlClient(MySqlConnectionSettings.disabled())
                .query(m.connection(), new MillionScalePaginationSql.SqlPlan("SELECT 1", List.of()),
                        rs -> rs.getInt(1), 5);
        verify(m.statement()).setQueryTimeout(5);
    }

    @Test
    void query_usesConfiguredTimeoutWhenCallerDoesNotSupplyOne() throws Exception {
        var m = mockQuery();
        new MySqlClient(MySqlConnectionSettings.disabled())
                .query(m.connection(), new MillionScalePaginationSql.SqlPlan("SELECT 1", List.of()),
                        rs -> rs.getInt(1));
        verify(m.statement()).setQueryTimeout(2);
    }

    @Test
    void queryPage_exactTerminalPageDoesNotEmitCursor() throws Exception {
        var m = mockQuery();
        when(m.resultSet().next()).thenReturn(true, true, false);
        when(m.resultSet().getInt("id")).thenReturn(10, 20);
        when(m.resultSet().getString("created_at")).thenReturn("2026-01-01", "2026-01-02");

        record Row(int id, String createdAt) {}
        var plan = new MillionScalePaginationSql.SqlPlan(
                "SELECT id, created_at FROM movies LIMIT ?", List.of(3));

        MySqlClient.PageResult<Row> result = new MySqlClient(MySqlConnectionSettings.disabled())
                .queryPage(m.connection(), plan, 2,
                        row -> new MillionScalePaginationSql.SeekCursor(row.createdAt(), row.id()),
                        rs -> new Row(rs.getInt("id"), rs.getString("created_at")));

        assertThat(result.rows()).extracting(Row::id).containsExactly(10, 20);
        assertThat(result.hasMore()).isFalse();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void queryPage_lookaheadIsTrimmedAndCursorUsesLastReturnedRow() throws Exception {
        var m = mockQuery();
        when(m.resultSet().next()).thenReturn(true, true, true, false);
        when(m.resultSet().getInt("id")).thenReturn(10, 20, 30);
        when(m.resultSet().getString("created_at")).thenReturn("2026-01-01", "2026-01-02", "2026-01-03");

        record Row(int id, String createdAt) {}
        var plan = new MillionScalePaginationSql.SqlPlan(
                "SELECT id, created_at FROM movies LIMIT ?", List.of(3));

        MySqlClient.PageResult<Row> result = new MySqlClient(MySqlConnectionSettings.disabled())
                .queryPage(m.connection(), plan, 2,
                        row -> new MillionScalePaginationSql.SeekCursor(row.createdAt(), row.id()),
                        rs -> new Row(rs.getInt("id"), rs.getString("created_at")));

        assertThat(result.rows()).extracting(Row::id).containsExactly(10, 20);
        assertThat(result.hasMore()).isTrue();
        var decoded = MillionScalePaginationSql.SeekCursor.decode(result.nextCursor());
        assertThat(decoded.id()).isEqualTo(20);
        assertThat(decoded.sortValue()).isEqualTo("2026-01-02");
    }

    @Test
    void queryPage_rejectsMoreThanPageSizePlusOneRows() throws Exception {
        var m = mockQuery();
        when(m.resultSet().next()).thenReturn(true, true, true, true, false);
        when(m.resultSet().getInt("id")).thenReturn(1, 2, 3, 4);
        var plan = new MillionScalePaginationSql.SqlPlan("SELECT id FROM movies LIMIT ?", List.of(3));

        assertThatThrownBy(() -> new MySqlClient(MySqlConnectionSettings.disabled())
                .queryPage(m.connection(), plan, 2,
                        row -> new MillionScalePaginationSql.SeekCursor("2026-01-01", row),
                        rs -> rs.getInt("id")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pageSize + 1");
    }

    @Test
    void queryPage_returnsNullNextCursorOnLastPage() throws Exception {
        var m = mockQuery();
        when(m.resultSet().next()).thenReturn(true, false);
        when(m.resultSet().getInt("id")).thenReturn(10);
        when(m.resultSet().getString("created_at")).thenReturn("2026-01-01");

        record Row(int id, String createdAt) {}
        var plan = new MillionScalePaginationSql.SqlPlan(
                "SELECT id, created_at FROM movies LIMIT ?", List.of(10));

        MySqlClient.PageResult<Row> result = new MySqlClient(MySqlConnectionSettings.disabled())
                .queryPage(m.connection(), plan, 10,
                        row -> new MillionScalePaginationSql.SeekCursor(row.createdAt(), row.id()),
                        rs -> new Row(rs.getInt("id"), rs.getString("created_at")));

        assertThat(result.rows()).hasSize(1);
        assertThat(result.hasMore()).isFalse();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void queryPage_returnsNullNextCursorWhenExtractorReturnsNull() throws Exception {
        var m = mockQuery();
        when(m.resultSet().next()).thenReturn(true, true, true, false);
        when(m.resultSet().getInt("id")).thenReturn(1, 2, 3);

        var plan = new MillionScalePaginationSql.SqlPlan("SELECT id FROM movies LIMIT ?", List.of(3));

        MySqlClient.PageResult<Integer> result = new MySqlClient(MySqlConnectionSettings.disabled())
                .queryPage(m.connection(), plan, 2,
                        row -> null,
                        rs -> rs.getInt("id"));

        assertThat(result.rows()).hasSize(2);
        assertThat(result.hasMore()).isFalse();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void queryPage_rejectsNonPositivePageSize() throws Exception {
        var m = mockQuery();
        var plan = new MillionScalePaginationSql.SqlPlan("SELECT 1", List.of());

        assertThatThrownBy(() -> new MySqlClient(MySqlConnectionSettings.disabled())
                .queryPage(m.connection(), plan, 0, row -> null, rs -> rs.getInt(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageSize");
    }

    @Test
    void query_returnsEmptyListOnNoRows() throws Exception {
        var m = mockQuery();
        var plan = new MillionScalePaginationSql.SqlPlan("SELECT id FROM movies LIMIT ?", List.of(10));

        List<Integer> rows = new MySqlClient(MySqlConnectionSettings.disabled())
                .query(m.connection(), plan, rs -> rs.getInt("id"));

        assertThat(rows).isEmpty();
    }

    @Test
    void query_withTimeout_convenenceOverload_setsTimeout() throws Exception {
        var m = mockQuery();
        var plan = new MillionScalePaginationSql.SqlPlan("SELECT 1", List.of());

        new MySqlClient(MySqlConnectionSettings.disabled())
                .query(m.connection(), plan, rs -> rs.getInt(1), 3);

        verify(m.statement()).setQueryTimeout(3);
    }

    @Test
    void owningQuery_reacquiresConnectionAndSucceedsOnSecondAttempt() throws Exception {
        var first = mockQuery();
        var second = mockQuery();
        when(first.statement().executeQuery()).thenThrow(new SQLException("lost", "08006"));
        AtomicInteger opens = new AtomicInteger();
        MySqlClient client = new MySqlClient(MySqlConnectionSettings.disabled(),
                () -> opens.getAndIncrement() == 0 ? first.connection() : second.connection(), millis -> {});

        List<Integer> result = client.query(
                new MillionScalePaginationSql.SqlPlan("SELECT 1", List.of()), rs -> rs.getInt(1));

        assertThat(result).isEmpty();
        assertThat(opens).hasValue(2);
        verify(first.connection()).close();
        verify(second.connection()).close();
        verify(first.statement()).setQueryTimeout(2);
        verify(second.statement()).setQueryTimeout(2);
    }

    @Test
    void owningQuery_doesNotRetryTimeoutOrSyntaxFailure() throws Exception {
        for (SQLException failure : List.of(
                new SQLTimeoutException("deadline"), new SQLException("syntax", "42000"))) {
            var m = mockQuery();
            when(m.statement().executeQuery()).thenThrow(failure);
            AtomicInteger opens = new AtomicInteger();
            MySqlClient client = new MySqlClient(MySqlConnectionSettings.disabled(),
                    () -> { opens.incrementAndGet(); return m.connection(); }, millis -> {});

            assertThatThrownBy(() -> client.query(
                    new MillionScalePaginationSql.SqlPlan("SELECT 1", List.of()), rs -> rs.getInt(1)))
                    .isSameAs(failure);
            assertThat(opens).hasValue(1);
        }
    }

    @Test
    void owningQuery_doesNotRetryMapperConnectionFailures() throws Exception {
        for (SQLException failure : List.of(
                new SQLException("mapper connection state", "08006"),
                new SQLTransientConnectionException("mapper transient connection failure"))) {
            var m = mockQuery();
            when(m.resultSet().next()).thenReturn(true);
            AtomicInteger opens = new AtomicInteger();
            MySqlClient client = new MySqlClient(MySqlConnectionSettings.disabled(),
                    () -> { opens.incrementAndGet(); return m.connection(); }, millis -> {});

            assertThatThrownBy(() -> client.query(
                    new MillionScalePaginationSql.SqlPlan("SELECT 1", List.of()), rs -> { throw failure; }))
                    .isSameAs(failure);
            assertThat(opens).hasValue(1);
            verify(m.resultSet()).close();
            verify(m.statement()).close();
            verify(m.connection()).close();
        }
    }

    @Test
    void owningQuery_propagatesFailureAfterConfiguredMaximumAttempts() throws Exception {
        var first = mockQuery();
        var second = mockQuery();
        SQLException firstFailure = new SQLException("lost-1", "08006");
        SQLException finalFailure = new SQLException("lost-2", "08006");
        when(first.statement().executeQuery()).thenThrow(firstFailure);
        when(second.statement().executeQuery()).thenThrow(finalFailure);
        AtomicInteger opens = new AtomicInteger();
        MySqlClient client = new MySqlClient(MySqlConnectionSettings.disabled(),
                () -> opens.getAndIncrement() == 0 ? first.connection() : second.connection(), millis -> {});

        assertThatThrownBy(() -> client.query(
                new MillionScalePaginationSql.SqlPlan("SELECT 1", List.of()), rs -> rs.getInt(1)))
                .isSameAs(finalFailure);
        assertThat(opens).hasValue(2);
    }

    @Test
    void owningQuery_restoresInterruptAndStopsRetryingWhenBackoffIsInterrupted() throws Exception {
        var m = mockQuery();
        SQLException failure = new SQLException("lost", "08006");
        when(m.statement().executeQuery()).thenThrow(failure);
        AtomicInteger opens = new AtomicInteger();
        InterruptedException interrupted = new InterruptedException("stop");
        MySqlClient client = new MySqlClient(MySqlConnectionSettings.disabled(),
                () -> { opens.incrementAndGet(); return m.connection(); },
                millis -> { throw interrupted; });

        try {
            assertThatThrownBy(() -> client.query(
                    new MillionScalePaginationSql.SqlPlan("SELECT 1", List.of()), rs -> rs.getInt(1)))
                    .isSameAs(failure);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(failure.getSuppressed()).containsExactly(interrupted);
            assertThat(opens).hasValue(1);
        } finally {
            Thread.interrupted();
        }
    }
}
