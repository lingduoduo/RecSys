package com.recsys.integration;

import com.recsys.application.consistency.ConsistencyToken;
import com.recsys.application.consistency.ConsistencyTokenCodec;
import com.recsys.application.consistency.ConsistencyWaiter;
import com.recsys.application.consistency.RedisLineageReader;
import com.recsys.application.outbox.DeliveryAttempt;
import com.recsys.application.outbox.DeliveryReceipt;
import com.recsys.application.outbox.DurableEventPublisher;
import com.recsys.application.outbox.OutboxDeliveryAdapter;
import com.recsys.domain.outbox.OutboxDestination;
import com.recsys.domain.outbox.OutboxRetryPolicy;
import com.recsys.domain.outbox.OutboxStatus;
import com.recsys.infrastructure.outbox.MySqlOutboxRepository;
import com.recsys.infrastructure.persistence.CatalogDatabaseBootstrap;
import com.recsys.infrastructure.persistence.MySqlConnectionSettings;
import com.recsys.infrastructure.persistence.TransactionalMySql;
import com.recsys.infrastructure.redis.LettuceRedisExecutor;
import com.recsys.infrastructure.redis.RedisExecutor;
import com.recsys.application.outbox.OutboxRelay;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end closure of the durable path: a synchronously accepted outbox event is delivered by the
 * relay, its Redis lineage becomes visible, and a signed consistency token then resolves to APPLIED.
 *
 * <p>Kafka and Flink themselves are covered by their own docker-tagged tests
 * (KafkaFlinkPartitionIntegrationTest, OnlineFeatureStreamingJobTest). Here the delivery adapter
 * stands in for the consumer + atomic Flink sink so the MySQL -> relay -> Redis-lineage -> token-read
 * contract is exercised with real MySQL and Redis.
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
class DurableEventualConsistencyIntegrationTest {
    private static final String TOKEN_SECRET = "durable-e2e-consistency-token-secret-32b";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4").withDatabaseName("recsys");

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private TransactionalMySql mysql;
    private MySqlOutboxRepository repository;
    private RedisExecutor redis;

    @BeforeEach
    void setUp() throws Exception {
        MySqlConnectionSettings settings = new MySqlConnectionSettings(true, MYSQL.getJdbcUrl(),
                MYSQL.getUsername(), MYSQL.getPassword(), 5, 1, 0, "test-only-e2e-cursor-signing-key-32-bytes");
        new CatalogDatabaseBootstrap().migrate(settings);
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            connection.createStatement().executeUpdate("DELETE FROM event_outbox");
        }
        mysql = new TransactionalMySql(settings);
        repository = new MySqlOutboxRepository(mysql);
        redis = new LettuceRedisExecutor(
                RedisClient.create(RedisURI.create(REDIS.getHost(), REDIS.getMappedPort(6379))),
                new GenericObjectPoolConfig<StatefulRedisConnection<String, String>>(), true);
    }

    @AfterEach
    void tearDown() {
        if (redis != null) redis.close();
        if (mysql != null) mysql.close();
    }

    @Test
    void acceptedEventIsRelayedAppliedAndReadableThroughAConsistencyToken() {
        int userId = 4242;
        UUID eventId = UUID.randomUUID();

        // 1. Synchronous durable acceptance commits before the API would acknowledge.
        DurableEventPublisher publisher = new DurableEventPublisher(repository);
        DurableEventPublisher.Acceptance acceptance =
                publisher.publishOnline(eventId, userId, "ONLINE_INTERACTION", "{\"movieId\":7}");
        assertThat(repository.find(acceptance.eventId())).get()
                .extracting(e -> e.status()).isEqualTo(OutboxStatus.PENDING);

        // 2. The relay delivers; the adapter stands in for consumer + atomic Flink sink writing lineage.
        OutboxDeliveryAdapter applyingAdapter = event -> {
            int subject = Integer.parseInt(event.aggregateId());
            redis.execute(c -> c.sadd("lineage:event:" + event.eventId(), "user:" + subject + ":recent_movies"));
            return new DeliveryAttempt(
                    CompletableFuture.completedFuture(new DeliveryReceipt(Instant.now())),
                    () -> CompletableFuture.completedFuture(null));
        };
        OutboxRetryPolicy retryPolicy =
                new OutboxRetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(5), 8, () -> 0.5);
        try (OutboxRelay relay = new OutboxRelay(repository,
                Map.of(OutboxDestination.KAFKA_ONLINE, applyingAdapter), retryPolicy, "e2e-worker",
                Clock.systemUTC(), 10, Duration.ofSeconds(30), Duration.ofSeconds(2), 4)) {
            relay.runOnce();
            await().atMost(Duration.ofSeconds(5)).until(() ->
                    repository.find(eventId).map(e -> e.status() == OutboxStatus.DELIVERED).orElse(false));
        }

        // 3. A signed token round-trips and the read-your-writes wait resolves to APPLIED.
        ConsistencyTokenCodec codec = new ConsistencyTokenCodec(TOKEN_SECRET);
        Instant issued = Instant.now();
        String encoded = codec.encode(new ConsistencyToken(eventId, userId, issued, issued.plus(Duration.ofHours(24))));
        ConsistencyToken verified = codec.decodeAndVerify(encoded);
        assertThat(verified.eventId()).isEqualTo(eventId);

        ConsistencyWaiter waiter = new ConsistencyWaiter(new RedisLineageReader(redis));
        assertThat(waiter.await(verified.eventId(), verified.userId(), Duration.ofSeconds(2)))
                .isEqualTo(ConsistencyWaiter.WaitResult.APPLIED);
    }
}
