package com.recsys.application.reconciliation;

import com.recsys.application.consistency.RedisLineageReader;
import com.recsys.application.outbox.KafkaOutboxDeliveryAdapter;
import com.recsys.infrastructure.outbox.MySqlOutboxRepository;
import com.recsys.infrastructure.persistence.MySqlConnectionSettings;
import com.recsys.infrastructure.persistence.TransactionalMySql;
import com.recsys.infrastructure.redis.LettuceClientFactory;
import com.recsys.infrastructure.redis.RedisExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Entry point for the standalone reconciliation CronJob. Scans the previous
 * {@code RECONCILIATION_WINDOW_HOURS} of delivered outbox rows and, when {@code RECONCILIATION_REPAIR}
 * is enabled, republishes events whose Redis lineage is missing.
 */
public final class ReconciliationCommand {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationCommand.class);

    private final OutboxReconciler reconciler;
    private final Clock clock;
    private final int windowHours;
    private final int maxBatch;
    private final boolean repair;

    public ReconciliationCommand(OutboxReconciler reconciler, Clock clock,
                                 int windowHours, int maxBatch, boolean repair) {
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (windowHours < 1) throw new IllegalArgumentException("windowHours must be positive");
        if (maxBatch < 1) throw new IllegalArgumentException("maxBatch must be positive");
        this.windowHours = windowHours;
        this.maxBatch = maxBatch;
        this.repair = repair;
    }

    public ReconciliationResult run(String[] args) {
        Instant now = clock.instant();
        Instant from = now.minus(Duration.ofHours(windowHours));
        ReconciliationResult result = reconciler.reconcile(from, now, maxBatch, repair);
        log.info("Reconciliation window={}h repair={} scanned={} missing={} republished={} repaired={} failed={}",
                windowHours, repair, result.scanned(), result.missing(),
                result.republished(), result.repaired(), result.failed());
        return result;
    }

    public static void main(String[] args) {
        Map<String, String> env = System.getenv();
        int windowHours = intEnv(env, "RECONCILIATION_WINDOW_HOURS", 24);
        int maxBatch = intEnv(env, "RECONCILIATION_MAX_BATCH", 500);
        boolean repair = "true".equalsIgnoreCase(env.getOrDefault("RECONCILIATION_REPAIR", "false"));
        Duration leaseDuration = Duration.ofSeconds(intEnv(env, "RECONCILIATION_LEASE_SECONDS", 300));
        Duration deliveryDeadline = Duration.ofMillis(intEnv(env, "OUTBOX_DELIVERY_DEADLINE_MS", 5_000));
        String bootstrapServers = requireEnv(env, "OUTBOX_KAFKA_BOOTSTRAP_SERVERS");
        String topic = env.getOrDefault("OUTBOX_KAFKA_ONLINE_TOPIC", "online-events");
        String worker = env.getOrDefault("HOSTNAME", "reconciler-" + UUID.randomUUID());

        TransactionalMySql mysql = new TransactionalMySql(MySqlConnectionSettings.fromEnv());
        RedisExecutor redis = LettuceClientFactory.routingFromEnv();
        KafkaOutboxDeliveryAdapter adapter = new KafkaOutboxDeliveryAdapter(bootstrapServers, topic, deliveryDeadline);
        try {
            OutboxReconciler reconciler = new OutboxReconciler(new MySqlOutboxRepository(mysql),
                    new RedisLineageReader(redis), adapter, null, worker, leaseDuration, Clock.systemUTC());
            new ReconciliationCommand(reconciler, Clock.systemUTC(), windowHours, maxBatch, repair).run(args);
        } finally {
            adapter.close();
            redis.close();
            mysql.close();
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

    private static String requireEnv(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set");
        return value;
    }
}
