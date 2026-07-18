package com.recsys.infrastructure.saga;

import com.recsys.application.saga.SagaDefinition;
import com.recsys.application.saga.SagaEventPublisher;
import com.recsys.application.saga.SagaOrchestrators;
import com.recsys.domain.outbox.OutboxDestination;
import com.recsys.domain.outbox.OutboxEvent;
import com.recsys.domain.saga.SagaEventType;
import com.recsys.domain.saga.SagaInstance;
import com.recsys.domain.saga.SagaStatus;
import com.recsys.domain.saga.SagaTransitionEvent;
import com.recsys.domain.saga.SagaStep;
import com.recsys.domain.saga.TccParticipant;
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
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    @Test
    void retryOfAlreadyCommittedTransitionIsIdempotent() {
        SagaInstance firstAttempt = saga();
        firstAttempt.mark(SagaStatus.STEP_STARTED, "reserve", NOW);
        SagaInstance retryAfterLostAcknowledgement = firstAttempt.copy();
        SagaTransitionEvent event = transition("saga-1:transition:1", firstAttempt);
        store.saveWithEvent(firstAttempt, event);

        store.saveWithEvent(retryAfterLostAcknowledgement, event);

        assertThat(retryAfterLostAcknowledgement.version()).isEqualTo(1);
        assertThat(store.find(firstAttempt.sagaId())).get().extracting(SagaInstance::version).isEqualTo(1);
        assertThat(outbox.find(uuid(event.eventId()))).isPresent();
    }

    @Test
    void roundTripsAllProgressCollectionsAcrossRestart() {
        SagaInstance saga = saga();
        saga.markStepCompleted("completed", NOW);
        saga.markCompensated("compensated", NOW);
        saga.markTried("tried", NOW);
        saga.markConfirmed("confirmed", NOW);
        saga.markCancelled("cancelled", NOW);

        store.save(saga);

        SagaInstance reloaded = store.find(saga.sagaId()).orElseThrow();
        assertThat(reloaded.completedSteps()).containsExactly("completed");
        assertThat(reloaded.compensatedSteps()).containsExactly("compensated");
        assertThat(reloaded.triedSteps()).containsExactly("tried");
        assertThat(reloaded.confirmedSteps()).containsExactly("confirmed");
        assertThat(reloaded.cancelledSteps()).containsExactly("cancelled");
    }

    @Test
    void updatesPersistedVersionZeroRowInsteadOfTreatingItAsInsert() throws Exception {
        SagaInstance saga = saga();
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO saga_instance (saga_id, saga_type, correlation_id, payload, status, version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 0, ?, ?)")) {
            insert.setString(1, saga.sagaId());
            insert.setString(2, saga.sagaType());
            insert.setString(3, saga.correlationId());
            insert.setString(4, saga.payloadJson());
            insert.setString(5, SagaStatus.STARTED.name());
            insert.setTimestamp(6, java.sql.Timestamp.from(NOW));
            insert.setTimestamp(7, java.sql.Timestamp.from(NOW));
            insert.executeUpdate();
        }
        saga.mark(SagaStatus.STEP_STARTED, "reserve", NOW);

        store.save(saga);

        assertThat(store.find(saga.sagaId())).get().satisfies(stored -> {
            assertThat(stored.version()).isEqualTo(1);
            assertThat(stored.status()).isEqualTo(SagaStatus.STEP_STARTED);
        });
    }

    @Test
    void standardRestartSkipsCompletedStepAndCompensatesItAfterLaterFailure() {
        SagaInstance saga = saga();
        saga.markStepCompleted("first", NOW);
        store.save(saga);
        List<String> calls = new ArrayList<>();
        SagaDefinition definition = new SagaDefinition(saga.sagaType(),
                List.of(SagaStep.local("first"), SagaStep.local("second")));

        new SagaOrchestrators.Standard(store, SagaEventPublisher.NOOP, clock()).execute(
                saga.sagaId(), saga.correlationId(), saga.payloadJson(), definition,
                Map.of("first", (ignored, step) -> calls.add("run:first"),
                        "second", (ignored, step) -> { throw new IllegalStateException("failed"); }),
                Map.of("first", (ignored, step) -> calls.add("compensate:first")));

        assertThat(calls).containsExactly("compensate:first");
        assertThat(store.find(saga.sagaId()).orElseThrow().compensatedSteps()).containsExactly("first");
    }

    @Test
    void tccRestartSkipsTriedAndConfirmedWorkAndCancelsOnlyOutstandingTry() {
        SagaInstance saga = saga();
        saga.markTried("first", NOW);
        saga.markConfirmed("first", NOW);
        saga.markTried("second", NOW);
        store.save(saga);
        List<String> calls = new ArrayList<>();
        SagaDefinition definition = new SagaDefinition(saga.sagaType(),
                List.of(SagaStep.local("first"), SagaStep.local("second")));
        TccParticipant first = participant("first", calls, false);
        TccParticipant second = participant("second", calls, true);

        new SagaOrchestrators.Tcc(store, SagaEventPublisher.NOOP, clock()).execute(
                saga.sagaId(), saga.correlationId(), saga.payloadJson(), definition,
                Map.of("first", first, "second", second));

        assertThat(calls).containsExactly("confirm:second", "cancel:second");
        assertThat(store.find(saga.sagaId()).orElseThrow().cancelledSteps()).containsExactly("second");
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

    private static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static TccParticipant participant(String name, List<String> calls, boolean failConfirm) {
        return new TccParticipant() {
            @Override public void tryReserve(SagaInstance saga, SagaStep step) { calls.add("try:" + name); }
            @Override public void confirm(SagaInstance saga, SagaStep step) {
                calls.add("confirm:" + name);
                if (failConfirm) throw new IllegalStateException("failed");
            }
            @Override public void cancel(SagaInstance saga, SagaStep step) { calls.add("cancel:" + name); }
        };
    }
}
