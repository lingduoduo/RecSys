package com.recsys.application.outbox;

import com.recsys.application.consistency.ConsistencyTokenCodec;

import java.time.Duration;
import java.util.Map;

/**
 * Environment-gated durable-consistency settings shared by the online server and the standalone
 * outbox relay. When {@code enabled} is false the service keeps its legacy in-memory, replica-and-cache
 * eventual-consistency behavior. When enabled, MySQL, a &ge;32-byte token secret, and a Kafka bootstrap
 * address are mandatory and validated at construction so misconfiguration fails fast at startup.
 */
public record DurableConsistencyConfiguration(
        boolean enabled,
        String tokenSecret,
        String kafkaBootstrapServers,
        String kafkaOnlineTopic,
        Duration deliveryDeadline) {

    public static DurableConsistencyConfiguration fromEnv(Map<String, String> env) {
        boolean enabled = Boolean.parseBoolean(env.getOrDefault("ONLINE_DURABLE_EVENTS_ENABLED", "false"));
        if (!enabled) {
            return new DurableConsistencyConfiguration(false, null, null, null, Duration.ZERO);
        }
        if (!Boolean.parseBoolean(env.getOrDefault("MYSQL_ENABLED", "false"))) {
            throw new IllegalStateException("MYSQL_ENABLED=true is required for durable event acceptance");
        }
        String secret = env.get("ONLINE_CONSISTENCY_TOKEN_SECRET");
        new ConsistencyTokenCodec(secret);   // fail fast on a missing/short secret
        String bootstrap = require(env, "OUTBOX_KAFKA_BOOTSTRAP_SERVERS");
        String topic = env.getOrDefault("OUTBOX_KAFKA_ONLINE_TOPIC", "online-events");
        Duration deadline = Duration.ofMillis(intEnv(env, "OUTBOX_DELIVERY_DEADLINE_MS", 5_000));
        return new DurableConsistencyConfiguration(true, secret, bootstrap, topic, deadline);
    }

    private static String require(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for durable event acceptance");
        }
        return value;
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
}
