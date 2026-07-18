package com.recsys.infrastructure.saga;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.application.saga.SagaStateStore;
import com.recsys.domain.outbox.OutboxDestination;
import com.recsys.domain.outbox.OutboxEvent;
import com.recsys.domain.saga.SagaConflictException;
import com.recsys.domain.saga.SagaInstance;
import com.recsys.domain.saga.SagaStatus;
import com.recsys.domain.saga.SagaTransitionEvent;
import com.recsys.infrastructure.outbox.MySqlOutboxRepository;
import com.recsys.infrastructure.persistence.TransactionalMySql;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** MySQL saga persistence with optimistic locking and transactional outbox enqueue. */
public final class MySqlSagaStateStore implements SagaStateStore {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final TransactionalMySql mysql;
    private final MySqlOutboxRepository outbox;

    public MySqlSagaStateStore(TransactionalMySql mysql, MySqlOutboxRepository outbox) {
        this.mysql = Objects.requireNonNull(mysql, "mysql");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
    }

    @Override
    public Optional<SagaInstance> find(String sagaId) {
        Objects.requireNonNull(sagaId, "sagaId");
        return mysql.inTransaction(connection -> find(connection, sagaId));
    }

    @Override
    public void save(SagaInstance saga) {
        int expected = saga.version();
        mysql.inTransaction(connection -> { saveConditionally(connection, saga, expected); return null; });
        saga.setVersion(expected + 1);
    }

    @Override
    public void saveConditionally(SagaInstance saga) {
        save(saga);
    }

    @Override
    public void saveWithEvent(SagaInstance saga, SagaTransitionEvent event) {
        Objects.requireNonNull(event, "event");
        int expected = saga.version();
        mysql.inTransaction(connection -> {
            saveConditionally(connection, saga, expected);
            outbox.enqueue(connection, OutboxEvent.pending(uuid(event.eventId()), "Saga", event.sagaId(),
                    event.type().name(), OutboxDestination.SQS_SAGA, event.sagaId(), json(event), event.occurredAt()));
            return null;
        });
        saga.setVersion(expected + 1);
    }

    @Override
    public boolean storesEventsDurably() {
        return true;
    }

    private static void saveConditionally(Connection connection, SagaInstance saga, int expected) throws SQLException {
        Objects.requireNonNull(saga, "saga");
        int affected = expected == 0 ? insert(connection, saga) : update(connection, saga, expected);
        if (affected != 1) {
            int actual = findVersion(connection, saga.sagaId()).orElse(0);
            throw new SagaConflictException(saga.sagaId(), expected, actual);
        }
    }

    private static int insert(Connection connection, SagaInstance saga) throws SQLException {
        String sql = "INSERT IGNORE INTO saga_instance (saga_id, saga_type, correlation_id, payload, status, "
                + "current_step, failure_reason, version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindState(statement, saga, 1);
            statement.setTimestamp(8, Timestamp.from(saga.createdAt()));
            statement.setTimestamp(9, Timestamp.from(saga.updatedAt()));
            return statement.executeUpdate();
        }
    }

    private static int update(Connection connection, SagaInstance saga, int expected) throws SQLException {
        String sql = "UPDATE saga_instance SET saga_type = ?, correlation_id = ?, payload = ?, status = ?, "
                + "current_step = ?, failure_reason = ?, version = version + 1, updated_at = ? "
                + "WHERE saga_id = ? AND version = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, saga.sagaType());
            statement.setString(2, saga.correlationId());
            statement.setString(3, saga.payloadJson());
            statement.setString(4, saga.status().name());
            statement.setString(5, saga.currentStep());
            statement.setString(6, saga.failureReason());
            statement.setTimestamp(7, Timestamp.from(saga.updatedAt()));
            statement.setString(8, saga.sagaId());
            statement.setInt(9, expected);
            return statement.executeUpdate();
        }
    }

    private static void bindState(PreparedStatement statement, SagaInstance saga, int offset) throws SQLException {
        statement.setString(offset, saga.sagaId());
        statement.setString(offset + 1, saga.sagaType());
        statement.setString(offset + 2, saga.correlationId());
        statement.setString(offset + 3, saga.payloadJson());
        statement.setString(offset + 4, saga.status().name());
        statement.setString(offset + 5, saga.currentStep());
        statement.setString(offset + 6, saga.failureReason());
    }

    private static Optional<SagaInstance> find(Connection connection, String sagaId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM saga_instance WHERE saga_id = ?")) {
            statement.setString(1, sagaId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                SagaInstance saga = new SagaInstance(row.getString("saga_id"), row.getString("saga_type"),
                        row.getString("correlation_id"), row.getString("payload"),
                        row.getTimestamp("created_at").toInstant());
                SagaStatus status = SagaStatus.valueOf(row.getString("status"));
                if (status == SagaStatus.FAILED) saga.fail(row.getString("failure_reason"), row.getTimestamp("updated_at").toInstant());
                else saga.mark(status, row.getString("current_step"), row.getTimestamp("updated_at").toInstant());
                saga.setVersion(row.getInt("version"));
                return Optional.of(saga);
            }
        }
    }

    private static Optional<Integer> findVersion(Connection connection, String sagaId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT version FROM saga_instance WHERE saga_id = ?")) {
            statement.setString(1, sagaId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(row.getInt(1)) : Optional.empty();
            }
        }
    }

    private static String json(SagaTransitionEvent event) {
        try { return JSON.writeValueAsString(event); }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("invalid saga event", failure); }
    }

    private static UUID uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
