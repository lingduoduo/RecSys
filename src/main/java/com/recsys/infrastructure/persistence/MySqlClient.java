package com.recsys.infrastructure.persistence;

import com.recsys.application.pagination.MillionScalePaginationSql;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tiny JDBC helper for optional MySQL access.
 *
 * Connections are borrowed from a lazily-created HikariCP pool (built on first use, only when
 * MySQL is enabled) so callers avoid a TCP+auth handshake on every query. The pool is read-only,
 * small, and env-tunable; {@link #close()} shuts it down.
 */
public class MySqlClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MySqlClient.class);

    private final MySqlConnectionSettings settings;
    private final ConnectionProvider connectionProvider;
    private final Sleeper sleeper;
    private volatile HikariDataSource dataSource;

    public MySqlClient(MySqlConnectionSettings settings) {
        this.settings = settings == null ? MySqlConnectionSettings.disabled() : settings;
        this.connectionProvider = null;
        this.sleeper = Thread::sleep;
    }

    MySqlClient(MySqlConnectionSettings settings, ConnectionProvider connectionProvider, Sleeper sleeper) {
        this.settings = settings == null ? MySqlConnectionSettings.disabled() : settings;
        this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
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
        if (connectionProvider != null) {
            return connectionProvider.open();
        }
        if (!settings.enabled()) {
            throw new IllegalStateException("MySQL is disabled; set MYSQL_ENABLED=true before opening connections");
        }
        try {
            return dataSource().getConnection();
        } catch (RuntimeException failure) {
            throw new MySqlPoolUnavailableException(failure);
        }
    }

    // Lazily build the pool on first use so a disabled/unused client never opens a connection.
    private HikariDataSource dataSource() {
        HikariDataSource ds = dataSource;
        if (ds == null) {
            synchronized (this) {
                ds = dataSource;
                if (ds == null) {
                    HikariConfig cfg = new HikariConfig();
                    cfg.setPoolName("recsys-mysql");
                    cfg.setJdbcUrl(settings.url());
                    cfg.setUsername(settings.username());
                    cfg.setPassword(settings.password());
                    cfg.setReadOnly(true); // matches the previous per-connection setReadOnly(true)
                    cfg.setMaximumPoolSize(readIntEnv("MYSQL_POOL_MAX_SIZE", 5));
                    cfg.setMinimumIdle(readIntEnv("MYSQL_POOL_MIN_IDLE", 1));
                    cfg.setConnectionTimeout(readLongEnv("MYSQL_POOL_CONNECTION_TIMEOUT_MS", 10_000L));
                    cfg.setIdleTimeout(readLongEnv("MYSQL_POOL_IDLE_TIMEOUT_MS", 60_000L));
                    cfg.setMaxLifetime(readLongEnv("MYSQL_POOL_MAX_LIFETIME_MS", 1_800_000L));
                    ds = new HikariDataSource(cfg);
                    dataSource = ds;
                }
            }
        }
        return ds;
    }

    /** Shuts down the connection pool if one was created. Safe to call multiple times. */
    @Override
    public synchronized void close() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }

    private static int readIntEnv(String name, int def) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return def;
        try { return Integer.parseInt(raw.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static long readLongEnv(String name, long def) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return def;
        try { return Long.parseLong(raw.trim()); } catch (NumberFormatException e) { return def; }
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
        return query(plan, mapper, settings.queryTimeoutSeconds());
    }

    public <T> List<T> query(MillionScalePaginationSql.SqlPlan plan, RowMapper<T> mapper, int queryTimeoutSeconds) throws SQLException {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(mapper, "mapper");
        return withReadRetry(connection -> query(connection, plan,
                nonRetryableMapper(mapper), effectiveTimeout(queryTimeoutSeconds)));
    }

    /**
     * @param queryTimeoutSeconds JDBC query timeout; non-positive values use the configured deadline
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
            statement.setQueryTimeout(effectiveTimeout(queryTimeoutSeconds));
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
        return query(connection, plan, mapper, settings.queryTimeoutSeconds());
    }

    public <T> PageResult<T> queryPage(
            Connection connection,
            MillionScalePaginationSql.SqlPlan plan,
            int pageSize,
            Function<T, MillionScalePaginationSql.SeekCursor> cursorExtractor,
            RowMapper<T> mapper
    ) throws SQLException {
        return queryPage(connection, plan, pageSize, cursorExtractor, mapper, settings.queryTimeoutSeconds());
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
     * @param queryTimeoutSeconds JDBC query timeout; non-positive values use the configured deadline
     */
    public <T> PageResult<T> queryPage(
            Connection connection,
            MillionScalePaginationSql.SqlPlan plan,
            int pageSize,
            Function<T, MillionScalePaginationSql.SeekCursor> cursorExtractor,
            RowMapper<T> mapper,
            int queryTimeoutSeconds
    ) throws SQLException {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be >= 1");
        }
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

    public <T> PageResult<T> queryPage(
            MillionScalePaginationSql.SqlPlan plan,
            int pageSize,
            Function<T, MillionScalePaginationSql.SeekCursor> cursorExtractor,
            RowMapper<T> mapper
    ) throws SQLException {
        return queryPage(plan, pageSize, cursorExtractor, mapper, settings.queryTimeoutSeconds());
    }

    public <T> PageResult<T> queryPage(
            MillionScalePaginationSql.SqlPlan plan,
            int pageSize,
            Function<T, MillionScalePaginationSql.SeekCursor> cursorExtractor,
            RowMapper<T> mapper,
            int queryTimeoutSeconds
    ) throws SQLException {
        return withReadRetry(connection -> queryPage(
                connection, plan, pageSize, cursorExtractor, nonRetryableMapper(mapper),
                effectiveTimeout(queryTimeoutSeconds)));
    }

    private static <T> RowMapper<T> nonRetryableMapper(RowMapper<T> mapper) {
        return resultSet -> {
            try {
                return mapper.map(resultSet);
            } catch (SQLException failure) {
                throw new RowMappingException(failure);
            }
        };
    }

    private int effectiveTimeout(int requestedSeconds) {
        return requestedSeconds > 0 ? requestedSeconds : settings.queryTimeoutSeconds();
    }

    private <T> T withReadRetry(ConnectionRead<T> read) throws SQLException {
        long started = System.nanoTime();
        for (int attempt = 1; ; attempt++) {
            try (Connection connection = openConnection()) {
                return read.execute(connection);
            } catch (RowMappingException failure) {
                throw failure.original();
            } catch (SQLException failure) {
                if (attempt >= settings.maxReadAttempts()
                        || !MySqlExceptionClassifier.isRetryableRead(failure)) {
                    log.warn("MySQL read failed class={} sqlState={} attempt={} elapsedMs={}",
                            failure.getClass().getSimpleName(), MySqlExceptionClassifier.firstSqlState(failure),
                            attempt, elapsedMs(started));
                    throw failure;
                }
                log.warn("Retrying MySQL read class={} sqlState={} attempt={} elapsedMs={}",
                        failure.getClass().getSimpleName(), MySqlExceptionClassifier.firstSqlState(failure),
                        attempt, elapsedMs(started));
                try {
                    sleeper.sleep(settings.retryBackoffMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failure.addSuppressed(interrupted);
                    throw failure;
                }
            }
        }
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
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

    @FunctionalInterface
    interface ConnectionProvider {
        Connection open() throws SQLException;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @FunctionalInterface
    private interface ConnectionRead<T> {
        T execute(Connection connection) throws SQLException;
    }

    private static final class RowMappingException extends SQLException {
        private final SQLException original;

        private RowMappingException(SQLException original) {
            super(original.getMessage(), original.getSQLState(), original.getErrorCode(), original);
            this.original = original;
        }

        private SQLException original() {
            return original;
        }
    }
}
