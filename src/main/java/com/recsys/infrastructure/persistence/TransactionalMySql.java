package com.recsys.infrastructure.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Writable MySQL boundary for work that must commit atomically. */
public final class TransactionalMySql implements AutoCloseable {
    private final MySqlConnectionSettings settings;
    private final ConnectionProvider connectionProvider;
    private volatile HikariDataSource dataSource;

    public TransactionalMySql(MySqlConnectionSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.connectionProvider = null;
    }

    TransactionalMySql(MySqlConnectionSettings settings, ConnectionProvider connectionProvider) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
    }

    @FunctionalInterface
    public interface SqlTransaction<T> {
        T execute(Connection connection) throws Exception;
    }

    public Connection openConnection() throws SQLException {
        if (connectionProvider != null) {
            return connectionProvider.open();
        }
        if (!settings.enabled()) {
            throw new IllegalStateException(
                    "MySQL is disabled; set MYSQL_ENABLED=true before opening connections");
        }
        try {
            return dataSource().getConnection();
        } catch (RuntimeException failure) {
            throw new MySqlPoolUnavailableException(failure);
        }
    }

    public <T> T inTransaction(SqlTransaction<T> work) {
        Objects.requireNonNull(work, "work");
        try (Connection connection = openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            Throwable transactionFailure = null;
            try {
                T value = work.execute(connection);
                connection.commit();
                return value;
            } catch (Throwable failure) {
                transactionFailure = failure;
                try {
                    connection.rollback();
                } catch (Throwable rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw propagate(failure);
            } finally {
                try {
                    connection.setAutoCommit(originalAutoCommit);
                } catch (Throwable restoreFailure) {
                    if (transactionFailure != null) {
                        transactionFailure.addSuppressed(restoreFailure);
                    } else {
                        throw propagate(restoreFailure);
                    }
                }
            }
        } catch (SQLException failure) {
            throw new MySqlPoolUnavailableException(new RuntimeException(failure));
        }
    }

    private HikariDataSource dataSource() {
        HikariDataSource current = dataSource;
        if (current == null) {
            synchronized (this) {
                current = dataSource;
                if (current == null) {
                    HikariConfig config = new HikariConfig();
                    config.setPoolName("recsys-mysql-writable");
                    config.setJdbcUrl(settings.url());
                    config.setUsername(settings.username());
                    config.setPassword(settings.password());
                    config.setReadOnly(false);
                    config.setMaximumPoolSize(5);
                    config.setMinimumIdle(1);
                    current = new HikariDataSource(config);
                    dataSource = current;
                }
            }
        }
        return current;
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) return runtimeFailure;
        if (failure instanceof Error error) throw error;
        return new RuntimeException(failure);
    }

    @Override
    public synchronized void close() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }

    @FunctionalInterface
    interface ConnectionProvider {
        Connection open() throws SQLException;
    }
}
