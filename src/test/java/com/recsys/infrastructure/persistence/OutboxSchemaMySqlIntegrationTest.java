package com.recsys.infrastructure.persistence;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
class OutboxSchemaMySqlIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("recsys");

    @Test
    void migrationCreatesDurableOutboxAndSagaContracts() throws Exception {
        new CatalogDatabaseBootstrap().migrate(settings());

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            assertThat(columnNames(connection, "event_outbox")).contains(
                    "event_id", "aggregate_type", "aggregate_id", "event_type", "payload",
                    "destination", "partition_key", "status", "attempt_count", "next_attempt_at", "lease_owner",
                    "lease_expires_at", "broker_acknowledged_at", "last_error", "version",
                    "created_at", "updated_at");
            assertThat(indexNames(connection, "event_outbox")).contains(
                    "idx_outbox_claim", "idx_outbox_lease", "idx_outbox_reconcile",
                    "idx_outbox_aggregate");
            assertThat(columnNames(connection, "saga_instance")).contains(
                    "saga_id", "saga_type", "correlation_id", "payload", "status",
                    "current_step", "failure_reason", "version", "created_at", "updated_at");
        }
    }

    private static MySqlConnectionSettings settings() {
        return new MySqlConnectionSettings(true, MYSQL.getJdbcUrl(), MYSQL.getUsername(),
                MYSQL.getPassword(), 5, 1, 0,
                "test-only-outbox-cursor-signing-key-32-bytes");
    }

    private static Set<String> columnNames(Connection connection, String table) throws Exception {
        Set<String> names = new HashSet<>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rows = metadata.getColumns(connection.getCatalog(), null, table, null)) {
            while (rows.next()) names.add(rows.getString("COLUMN_NAME"));
        }
        return names;
    }

    private static Set<String> indexNames(Connection connection, String table) throws Exception {
        Set<String> names = new HashSet<>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rows = metadata.getIndexInfo(connection.getCatalog(), null, table, false, false)) {
            while (rows.next()) names.add(rows.getString("INDEX_NAME"));
        }
        return names;
    }
}
