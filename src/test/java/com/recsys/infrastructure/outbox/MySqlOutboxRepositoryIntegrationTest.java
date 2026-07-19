package com.recsys.infrastructure.outbox;

import com.recsys.domain.outbox.OutboxDestination;
import com.recsys.domain.outbox.OutboxEvent;
import com.recsys.domain.outbox.OutboxStatus;
import com.recsys.infrastructure.persistence.CatalogDatabaseBootstrap;
import com.recsys.infrastructure.persistence.MySqlConnectionSettings;
import com.recsys.infrastructure.persistence.TransactionalMySql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
class MySqlOutboxRepositoryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("recsys");

    private TransactionalMySql mysql;
    private MySqlOutboxRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        var settings = new MySqlConnectionSettings(true, MYSQL.getJdbcUrl(), MYSQL.getUsername(),
                MYSQL.getPassword(), 5, 1, 0, "test-only-outbox-cursor-signing-key-32-bytes");
        new CatalogDatabaseBootstrap().migrate(settings);
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            connection.createStatement().executeUpdate("DELETE FROM event_outbox");
        }
        mysql = new TransactionalMySql(settings);
        repository = new MySqlOutboxRepository(mysql);
    }

    @Test
    void concurrentWorkersNeverClaimTheSameRow() throws Exception {
        repository.enqueue(event("e1"));
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> { start.await(); return repository.claimBatch("worker-a", NOW, 10, Duration.ofSeconds(30)); });
            var second = workers.submit(() -> { start.await(); return repository.claimBatch("worker-b", NOW, 10, Duration.ofSeconds(30)); });
            start.countDown();

            assertThat(first.get().size() + second.get().size()).isEqualTo(1);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void expiredLeaseCanBeReclaimed() {
        repository.enqueue(event("e1"));
        repository.claimBatch("worker-a", NOW, 10, Duration.ofSeconds(30));

        var reclaimed = repository.claimBatch("worker-b", NOW.plusSeconds(31), 10, Duration.ofSeconds(30));

        assertThat(reclaimed).extracting(OutboxEvent::eventId).containsExactly(uuid("e1"));
        assertThat(reclaimed.get(0).version()).isEqualTo(2);
    }

    @Test
    void duplicateIdentityIsIdempotentButConflictingContentFails() {
        OutboxEvent stored = repository.enqueue(event("e1"));

        assertThat(repository.enqueue(event("e1"))).isEqualTo(stored);
        assertThatThrownBy(() -> repository.enqueue(event("e1").withPayload("{\"changed\":true}")))
                .isInstanceOf(OutboxConflictException.class);
    }

    @Test
    void duplicateIdentityUsesSemanticJsonAndMicrosecondTimestampEquality() {
        Instant nanos = NOW.plusNanos(123_456_789);
        OutboxEvent first = event("e1", "{\"rating\":5,\"meta\":{\"ok\":true}}", nanos);
        OutboxEvent equivalent = event("e1", "{ \"meta\" : { \"ok\" : true }, \"rating\" : 5 }", nanos);

        OutboxEvent stored = repository.enqueue(first);

        assertThat(repository.enqueue(equivalent)).isEqualTo(stored);
        assertThat(stored.createdAt()).isEqualTo(Instant.parse("2026-07-18T12:00:00.123456Z"));
    }

    @Test
    void onlyCurrentLeaseVersionCanCompleteDeliveryAndDeliveredRowsCanBeScanned() {
        repository.enqueue(event("e1"));
        OutboxEvent claimed = repository.claimBatch("worker-a", NOW, 1, Duration.ofSeconds(30)).get(0);

        assertThat(repository.markDelivered(claimed.eventId(), claimed.version(), "worker-b", NOW.plusSeconds(1))).isFalse();
        assertThat(repository.markDelivered(claimed.eventId(), claimed.version(), "worker-a", NOW.plusSeconds(1))).isTrue();
        assertThat(repository.markDelivered(claimed.eventId(), claimed.version(), "worker-a", NOW.plusSeconds(2))).isFalse();
        assertThat(repository.scanDelivered(NOW.minusSeconds(1), NOW.plusSeconds(2), 10))
                .extracting(OutboxEvent::eventId).containsExactly(claimed.eventId());
    }


    @Test
    void deliveredRescheduledAndDeadEventsFollowClaimOwnerLifecycle() {
        repository.enqueue(event("delivered"));
        repository.enqueue(event("rescheduled"));
        repository.enqueue(event("dead"));
        var claimed = repository.claimBatch("worker-a", NOW, 10, Duration.ofSeconds(30));

        OutboxEvent delivered = claimed.stream().filter(e -> e.eventId().equals(uuid("delivered"))).findFirst().orElseThrow();
        OutboxEvent rescheduled = claimed.stream().filter(e -> e.eventId().equals(uuid("rescheduled"))).findFirst().orElseThrow();
        OutboxEvent dead = claimed.stream().filter(e -> e.eventId().equals(uuid("dead"))).findFirst().orElseThrow();
        String longError = "x".repeat(2_001);

        assertThat(repository.markDelivered(delivered.eventId(), delivered.version(), "worker-a", NOW)).isTrue();
        assertThat(repository.reschedule(rescheduled.eventId(), rescheduled.version(), "worker-b", NOW.plusSeconds(10), "retry")).isFalse();
        assertThat(repository.reschedule(rescheduled.eventId(), rescheduled.version(), "worker-a", NOW.plusSeconds(10), longError)).isTrue();
        assertThat(repository.markDead(dead.eventId(), dead.version(), "worker-b", "fatal")).isFalse();
        assertThat(repository.markDead(dead.eventId(), dead.version(), "worker-a", longError)).isTrue();

        assertThat(repository.find(rescheduled.eventId()).orElseThrow().lastError()).hasSize(2_000);
        assertThat(repository.find(dead.eventId()).orElseThrow().lastError()).hasSize(2_000);
    }

    private static OutboxEvent event(String seed) {
        return event(seed, "{\"rating\":5}", NOW);
    }

    private static OutboxEvent event(String seed, String payload, Instant createdAt) {
        return OutboxEvent.pending(uuid(seed), "movie", "42", "MovieRated",
                OutboxDestination.KAFKA_ONLINE, "user-7", payload, createdAt);
    }

    private static UUID uuid(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
