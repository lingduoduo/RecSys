package com.recsys.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionalMySqlTest {
    private final TransactionFixture fixture = new TransactionFixture();

    @Test
    void commitsSuccessfulWork() {
        TransactionalMySql mysql = fixture.writableClient();

        mysql.inTransaction(connection -> {
            fixture.insertProbe(connection, "committed");
            return null;
        });

        assertThat(fixture.probes()).containsExactly("committed");
    }

    @Test
    void rollsBackFailedWork() {
        TransactionalMySql mysql = fixture.writableClient();

        assertThatThrownBy(() -> mysql.inTransaction(connection -> {
            fixture.insertProbe(connection, "rolled-back");
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        assertThat(fixture.probes()).isEmpty();
    }

    private static final class TransactionFixture {
        private final List<String> committed = new ArrayList<>();
        private List<String> pending;
        private boolean autoCommit = true;

        TransactionalMySql writableClient() {
            return new TransactionalMySql(MySqlConnectionSettings.disabled(), this::openConnection);
        }

        void insertProbe(Connection connection, String value) throws SQLException {
            assertThat(connection.getAutoCommit()).isFalse();
            pending.add(value);
        }

        List<String> probes() {
            return List.copyOf(committed);
        }

        private Connection openConnection() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getAutoCommit" -> autoCommit;
                        case "setAutoCommit" -> {
                            autoCommit = (boolean) args[0];
                            if (!autoCommit) pending = new ArrayList<>();
                            yield null;
                        }
                        case "commit" -> {
                            committed.addAll(pending);
                            pending.clear();
                            yield null;
                        }
                        case "rollback" -> {
                            pending.clear();
                            yield null;
                        }
                        case "close" -> null;
                        case "isClosed" -> false;
                        case "unwrap" -> throw new SQLException("not a wrapper");
                        case "isWrapperFor" -> false;
                        case "toString" -> "transaction-fixture-connection";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
