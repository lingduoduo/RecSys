package com.recsys.mysql;

import com.recsys.pagination.MillionScalePaginationSql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;

/**
 * Tiny JDBC helper for optional MySQL access.
 *
 * This intentionally avoids a framework or connection pool. Existing serving paths can keep using
 * classpath data and Redis; MySQL callers pay for a connection only when they invoke a query.
 */
public class MySqlClient {

    private final MySqlConnectionSettings settings;

    public MySqlClient(MySqlConnectionSettings settings) {
        this.settings = settings == null ? MySqlConnectionSettings.disabled() : settings;
    }

    public static MySqlClient fromEnv() {
        return new MySqlClient(MySqlConnectionSettings.fromEnv());
    }

    public boolean isEnabled() {
        return settings.enabled();
    }

    public MySqlConnectionSettings settings() {
        return settings;
    }

    public Connection openConnection() throws SQLException {
        if (!settings.enabled()) {
            throw new IllegalStateException("MySQL is disabled; set MYSQL_ENABLED=true before opening connections");
        }
        Properties props = new Properties();
        props.setProperty("user", settings.username());
        props.setProperty("password", settings.password());
        Connection connection = DriverManager.getConnection(settings.url(), props);
        connection.setReadOnly(true);
        return connection;
    }

    public HealthCheck healthCheck() {
        if (!settings.enabled()) {
            return new HealthCheck(false, false, "disabled");
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
            statement.setQueryTimeout(1);
            try (ResultSet rs = statement.executeQuery()) {
                boolean hasRow = rs.next();
                return new HealthCheck(true, hasRow, hasRow ? "ok" : "empty health check result");
            }
        } catch (SQLTimeoutException e) {
            return new HealthCheck(true, false, "timeout");
        } catch (SQLException | RuntimeException e) {
            return new HealthCheck(true, false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public <T> List<T> query(MillionScalePaginationSql.SqlPlan plan, RowMapper<T> mapper) throws SQLException {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(mapper, "mapper");
        try (Connection connection = openConnection()) {
            return query(connection, plan, mapper, 0);
        }
    }

    /**
     * @param queryTimeoutSeconds JDBC query timeout; 0 means no timeout (driver default)
     */
    public <T> List<T> query(
            Connection connection,
            MillionScalePaginationSql.SqlPlan plan,
            RowMapper<T> mapper,
            int queryTimeoutSeconds
    ) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(mapper, "mapper");
        try (PreparedStatement statement = connection.prepareStatement(plan.sql())) {
            if (queryTimeoutSeconds > 0) {
                statement.setQueryTimeout(queryTimeoutSeconds);
            }
            bind(statement, plan.bindValues());
            try (ResultSet rs = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.map(rs));
                }
                return rows;
            }
        }
    }

    public <T> List<T> query(
            Connection connection,
            MillionScalePaginationSql.SqlPlan plan,
            RowMapper<T> mapper
    ) throws SQLException {
        return query(connection, plan, mapper, 0);
    }

    public <T> PageResult<T> queryPage(
            Connection connection,
            MillionScalePaginationSql.SqlPlan plan,
            int pageSize,
            Function<T, MillionScalePaginationSql.SeekCursor> cursorExtractor,
            RowMapper<T> mapper
    ) throws SQLException {
        return queryPage(connection, plan, pageSize, cursorExtractor, mapper, 0);
    }

    /**
     * Executes a cursor-page query and automatically extracts the next-page token from the last row.
     *
     * <p>{@link PageResult#nextCursor()} is {@code null} when fewer than {@code pageSize} rows were
     * returned, signalling the last page.
     *
     * @param pageSize            expected page size; used to detect whether more rows exist
     * @param cursorExtractor     extracts the stable (sortValue, id) position from a mapped row;
     *                            return {@code null} to suppress next-cursor generation for that row
     * @param queryTimeoutSeconds JDBC query timeout; 0 means no timeout (driver default)
     */
    public <T> PageResult<T> queryPage(
            Connection connection,
            MillionScalePaginationSql.SqlPlan plan,
            int pageSize,
            Function<T, MillionScalePaginationSql.SeekCursor> cursorExtractor,
            RowMapper<T> mapper,
            int queryTimeoutSeconds
    ) throws SQLException {
        List<T> rows = query(connection, plan, mapper, queryTimeoutSeconds);
        String nextCursor = null;
        if (rows.size() == pageSize) {
            MillionScalePaginationSql.SeekCursor pos = cursorExtractor.apply(rows.get(rows.size() - 1));
            if (pos != null) {
                nextCursor = pos.encode();
            }
        }
        return new PageResult<>(rows, nextCursor);
    }

    private static void bind(PreparedStatement statement, List<Object> bindValues) throws SQLException {
        for (int i = 0; i < bindValues.size(); i++) {
            statement.setObject(i + 1, bindValues.get(i));
        }
    }

    public record HealthCheck(boolean enabled, boolean reachable, String message) {}

    public record PageResult<T>(List<T> rows, String nextCursor) {
        public PageResult {
            // Wrap without copying — query() always returns a fresh ArrayList owned solely by PageResult.
            rows = Collections.unmodifiableList(rows);
        }

        public boolean hasMore() {
            return nextCursor != null;
        }
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet resultSet) throws SQLException;
    }
}
