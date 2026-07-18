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
    void concurrentWorkersNeverClaimTheSameRow() {
        repository.enqueue(event("e1"));

        var first = repository.claimBatch("worker-a", NOW, 10, Duration.ofSeconds(30));
        var second = repository.claimBatch("worker-b", NOW, 10, Duration.ofSeconds(30));

        assertThat(first).extracting(OutboxEvent::eventId).containsExactly(uuid("e1"));
        assertThat(second).isEmpty();
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
    void onlyCurrentLeaseVersionCanCompleteDeliveryAndDeliveredRowsCanBeScanned() {
        repository.enqueue(event("e1"));
        OutboxEvent claimed = repository.claimBatch("worker-a", NOW, 1, Duration.ofSeconds(30)).get(0);

        assertThat(repository.markDelivered(claimed.eventId(), claimed.version(), NOW.plusSeconds(1))).isTrue();
        assertThat(repository.markDelivered(claimed.eventId(), claimed.version(), NOW.plusSeconds(2))).isFalse();
        assertThat(repository.scanDelivered(NOW.minusSeconds(1), NOW.plusSeconds(2), 10))
                .extracting(OutboxEvent::eventId).containsExactly(claimed.eventId());
    }

    private static OutboxEvent event(String seed) {
        return OutboxEvent.pending(uuid(seed), "movie", "42", "MovieRated",
                OutboxDestination.KAFKA_ONLINE, "user-7", "{\"rating\":5}", NOW);
    }

    private static UUID uuid(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
