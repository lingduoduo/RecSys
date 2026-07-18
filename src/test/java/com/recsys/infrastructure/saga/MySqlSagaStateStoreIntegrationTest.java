package com.recsys.infrastructure.saga;

import com.recsys.domain.outbox.OutboxDestination;
import com.recsys.domain.outbox.OutboxEvent;
import com.recsys.domain.saga.SagaEventType;
import com.recsys.domain.saga.SagaInstance;
import com.recsys.domain.saga.SagaStatus;
import com.recsys.domain.saga.SagaTransitionEvent;
import com.recsys.infrastructure.outbox.MySqlOutboxRepository;
import com.recsys.infrastructure.outbox.OutboxConflictException;
import com.recsys.infrastructure.persistence.CatalogDatabaseBootstrap;
import com.recsys.infrastructure.persistence.MySqlConnectionSettings;
import com.recsys.infrastructure.persistence.TransactionalMySql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
class MySqlSagaStateStoreIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("recsys");

    private MySqlSagaStateStore store;
    private MySqlOutboxRepository outbox;

    @BeforeEach
    void setUp() throws Exception {
        var settings = new MySqlConnectionSettings(true, MYSQL.getJdbcUrl(), MYSQL.getUsername(),
                MYSQL.getPassword(), 5, 1, 0, "test-only-saga-cursor-signing-key-32-bytes");
        new CatalogDatabaseBootstrap().migrate(settings);
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            connection.createStatement().executeUpdate("DELETE FROM event_outbox");
            connection.createStatement().executeUpdate("DELETE FROM saga_instance");
        }
        var mysql = new TransactionalMySql(settings);
        outbox = new MySqlOutboxRepository(mysql);
        store = new MySqlSagaStateStore(mysql, outbox);
    }

    @Test
    void sagaTransitionAndOutboxEventCommitTogether() {
        SagaInstance saga = saga();
        saga.mark(SagaStatus.STEP_STARTED, "reserve", NOW);
        SagaTransitionEvent transition = transition("transition-1", saga);

        store.saveWithEvent(saga, transition);

        assertThat(store.find(saga.sagaId())).get().extracting(SagaInstance::status)
                .isEqualTo(SagaStatus.STEP_STARTED);
        assertThat(outbox.find(uuid(transition.eventId()))).isPresent();
    }

    @Test
    void outboxInsertFailureRollsBackSagaVersion() {
        SagaInstance saga = saga();
        store.save(saga);
        int storedVersion = saga.version();
        SagaTransitionEvent transition = transition("conflict", saga);
        outbox.enqueue(OutboxEvent.pending(uuid(transition.eventId()), "Saga", saga.sagaId(),
                transition.type().name(), OutboxDestination.SQS_SAGA, saga.sagaId(),
                "{\"different\":true}", NOW));
        saga.mark(SagaStatus.STEP_STARTED, "reserve", NOW);

        assertThatThrownBy(() -> store.saveWithEvent(saga, transition("conflict", saga)))
                .isInstanceOf(OutboxConflictException.class);
        assertThat(store.find(saga.sagaId())).get().extracting(SagaInstance::version)
                .isEqualTo(storedVersion);
        assertThat(saga.version()).isEqualTo(storedVersion);
    }

    private static SagaInstance saga() {
        return new SagaInstance("saga-1", "recommendation", "request-1", "{}", NOW);
    }

    private static SagaTransitionEvent transition(String eventId, SagaInstance saga) {
        return new SagaTransitionEvent(eventId, saga.sagaId(), saga.sagaType(), saga.correlationId(),
                SagaEventType.STEP_STARTED, saga.status(), saga.currentStep(), saga.payloadJson(), NOW);
    }

    private static UUID uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
