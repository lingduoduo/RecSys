package com.recsys.application.outbox;

import com.recsys.domain.outbox.OutboxDestination;
import com.recsys.domain.outbox.OutboxRetryPolicy;
import com.recsys.infrastructure.outbox.MySqlOutboxRepository;
import com.recsys.infrastructure.persistence.MySqlConnectionSettings;
import com.recsys.infrastructure.persistence.TransactionalMySql;
import com.recsys.metrics.ConsistencyMetrics;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.metric.PrometheusExpositionService;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Standalone entry point for the outbox relay. Runs as its own Deployment — never inside every
 * online-serving replica — so relay concurrency and Kafka producer sessions are decoupled from the
 * request tier. Claims durable outbox rows on a fixed cadence, delivers them idempotently, and
 * exposes {@code /health/live}, {@code /health/ready} (MySQL reachable and backlog bounded), and
 * {@code /metrics}.
 */
public final class OutboxRelayCommand {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelayCommand.class);

    public static void main(String[] args) throws InterruptedException {
        Map<String, String> env = System.getenv();
        DurableConsistencyConfiguration durable = DurableConsistencyConfiguration.fromEnv(env);
        if (!durable.enabled()) {
            log.warn("ONLINE_DURABLE_EVENTS_ENABLED is false; outbox relay has nothing to do and is exiting");
            return;
        }
        int port = intEnv(env, "OUTBOX_RELAY_PORT", 7020);
        int batchSize = intEnv(env, "OUTBOX_RELAY_BATCH_SIZE", 100);
        int maxConcurrentSends = intEnv(env, "OUTBOX_RELAY_MAX_CONCURRENT_SENDS", 16);
        int maxAttempts = intEnv(env, "OUTBOX_RELAY_MAX_ATTEMPTS", 8);
        Duration leaseDuration = Duration.ofSeconds(intEnv(env, "OUTBOX_RELAY_LEASE_SECONDS", 30));
        Duration pollInterval = Duration.ofMillis(intEnv(env, "OUTBOX_RELAY_POLL_MS", 500));
        Duration cycleDeadline = durable.deliveryDeadline();
        long readinessMaxBacklog = intEnv(env, "OUTBOX_RELAY_READINESS_MAX_BACKLOG", 100_000);
        String worker = env.getOrDefault("HOSTNAME", "relay-" + UUID.randomUUID());

        TransactionalMySql mysql = new TransactionalMySql(MySqlConnectionSettings.fromEnv());
        MySqlOutboxRepository repository = new MySqlOutboxRepository(mysql);

        KafkaOutboxDeliveryAdapter kafka =
                new KafkaOutboxDeliveryAdapter(durable.kafkaBootstrapServers(), durable.kafkaOnlineTopic(), cycleDeadline);
        Map<OutboxDestination, OutboxDeliveryAdapter> adapters = new EnumMap<>(OutboxDestination.class);
        adapters.put(OutboxDestination.KAFKA_ONLINE, kafka);

        SqsOutboxDeliveryAdapter sqs = null;
        String sagaQueue = env.get("SAGA_EVENTS_SQS_QUEUE_URL");
        if (Boolean.parseBoolean(env.getOrDefault("SAGA_EVENTS_SQS_ENABLED", "false"))
                && sagaQueue != null && !sagaQueue.isBlank()) {
            sqs = new SqsOutboxDeliveryAdapter(SqsAsyncClient.create(), sagaQueue, cycleDeadline);
            adapters.put(OutboxDestination.SQS_SAGA, sqs);
        }

        PrometheusMeterRegistry registry = PrometheusMeterRegistries.defaultRegistry();
        ConsistencyMetrics metrics = new ConsistencyMetrics(registry,
                () -> repository.countRetryableBacklog(Clock.systemUTC().instant()));
        OutboxRetryPolicy retryPolicy = new OutboxRetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(5),
                maxAttempts, () -> ThreadLocalRandom.current().nextDouble());
        OutboxRelay relay = new OutboxRelay(repository, adapters, retryPolicy, worker, Clock.systemUTC(),
                batchSize, leaseDuration, cycleDeadline, maxConcurrentSends, metrics);

        ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "outbox-relay-loop");
            thread.setDaemon(true);
            return thread;
        });
        loop.scheduleWithFixedDelay(() -> {
            try {
                relay.runOnce();
            } catch (Throwable cycleFailure) {
                log.warn("Outbox relay cycle failed", cycleFailure);
            }
        }, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);

        Server server = Server.builder()
                .http(port)
                .service("/health/live", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                .service("/health/ready", (ctx, req) -> readiness(repository, readinessMaxBacklog))
                .service("/metrics", PrometheusExpositionService.of(registry.getPrometheusRegistry()))
                .build();
        server.start().join();

        SqsOutboxDeliveryAdapter sqsToClose = sqs;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop().join();          // stop accepting probes before draining
            loop.shutdownNow();
            relay.close();                 // drains in-flight sends
            kafka.close();
            if (sqsToClose != null) sqsToClose.close();
            mysql.close();
        }));
        log.info("Outbox relay started on :{} worker={} batchSize={} cycleDeadline={} pollInterval={}",
                port, worker, batchSize, cycleDeadline, pollInterval);
        server.blockUntilShutdown();
    }

    private static HttpResponse readiness(MySqlOutboxRepository repository, long maxBacklog) {
        try {
            long backlog = repository.countRetryableBacklog(Clock.systemUTC().instant());
            if (backlog > maxBacklog) {
                return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE, MediaType.PLAIN_TEXT_UTF_8,
                        "backlog " + backlog + " exceeds " + maxBacklog);
            }
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "ready backlog=" + backlog);
        } catch (RuntimeException mysqlUnavailable) {
            return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE, MediaType.PLAIN_TEXT_UTF_8, "mysql unavailable");
        }
    }

    private static int intEnv(Map<String, String> env, String name, int fallback) {
        String raw = env.get(name);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " must be an integer, got '" + raw + "'");
        }
    }

    private OutboxRelayCommand() {}
}
