package com.recsys.resilience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.infrastructure.redis.LettuceRedisExecutor;
import com.recsys.infrastructure.redis.RedisExecutor;
import com.recsys.ratelimit.RedisRateLimiter;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Publishes Redis boundary evidence observed against a real Redis container and Lua script. */
@Tag("docker")
@Testcontainers
class DockerRedisResilienceEvidenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Test
    @Timeout(20)
    void publishesRealSlidingWindowBoundaryEvidence() throws Exception {
        long started = System.nanoTime();
        RedisClient client = RedisClient.create(
                RedisURI.create(REDIS.getHost(), REDIS.getMappedPort(6379)));
        GenericObjectPoolConfig<StatefulRedisConnection<String, String>> config =
                new GenericObjectPoolConfig<>();
        try (RedisExecutor executor = new LettuceRedisExecutor(client, config, true)) {
            AtomicLong clock = new AtomicLong(5_000);
            int limit = 100;
            RedisRateLimiter limiter = new RedisRateLimiter(
                    executor, "rate:evidence:", limit, 1,
                    5, 30_000, 25, 25, System::nanoTime, clock::get);
            int initialAllowed = admitted(limiter, limit);
            clock.set(6_000);
            int boundaryAttempted = 100;
            int boundaryAllowed = admitted(limiter, boundaryAttempted);
            int boundaryRejected = boundaryAttempted - boundaryAllowed;

            Map<String, Object> measurements = Map.of(
                    "redisBoundary", Map.of(
                            "limit", limit,
                            "initialAllowed", initialAllowed,
                            "attempted", boundaryAttempted,
                            "allowed", boundaryAllowed,
                            "rejected", boundaryRejected),
                    "performance", Map.of(
                            "elapsedMillis",
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
            Map<String, Boolean> invariants = Map.of(
                    "redisBoundaryEnforced",
                    initialAllowed == limit
                            && boundaryAllowed <= 1
                            && boundaryRejected >= boundaryAttempted - 1);

            writeSidecar(measurements, invariants);
            assertThat(invariants.get("redisBoundaryEnforced"))
                    .as("real Redis sliding-window boundary")
                    .isTrue();
        }
    }

    private static int admitted(RedisRateLimiter limiter, int attempts) {
        int allowed = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.tryAcquire("online").allowed()) allowed++;
        }
        return allowed;
    }

    private static void writeSidecar(
            Map<String, Object> measurements, Map<String, Boolean> invariants) throws Exception {
        String suite = System.getProperty("resilience.evidence.suite", "docker");
        Path output = Path.of(System.getProperty(
                "resilience.evidence.output", "target/resilience-measurements-docker.json"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(
                output.toAbsolutePath().getParent(), output.getFileName().toString(), ".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), Map.of(
                "schemaVersion", 1,
                "suite", suite,
                "source", "docker-real-redis-lua",
                "applicability", Map.of(
                        "load", false,
                        "loadCoveredBy", "load",
                        "redisBoundary", true),
                "measurements", measurements,
                "invariants", invariants));
        Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }
}
