package com.recsys.ratelimit;

import com.recsys.infrastructure.redis.LettuceRedisExecutor;
import com.recsys.infrastructure.redis.RedisExecutor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("docker")
@Testcontainers
class RedisRateLimiterSlidingWindowIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static RedisExecutor exec;

    @BeforeAll
    static void startRedis() {
        RedisClient client = RedisClient.create(
                RedisURI.create(REDIS.getHost(), REDIS.getMappedPort(6379)));
        GenericObjectPoolConfig<StatefulRedisConnection<String, String>> cfg =
                new GenericObjectPoolConfig<>();
        exec = new LettuceRedisExecutor(client, cfg, true);
    }

    @AfterAll
    static void stopRedis() {
        if (exec != null) exec.close();
    }

    @AfterEach
    void flush() {
        exec.execute(c -> { c.flushall(); return null; });
    }

    private static int admitted(RedisRateLimiter limiter, int attempts) {
        int allowed = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.tryAcquire("online").allowed()) allowed++;
        }
        return allowed;
    }

    @Test
    void steadyState_admitsUpToLimitThenRejects() {
        AtomicLong clock = new AtomicLong(5_000L); // window 5 (windowMs=1000), elapsed 0
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:test:", 100L, 1, clock::get);

        assertThat(admitted(limiter, 100)).isEqualTo(100);        // fills the window exactly
        assertThat(limiter.tryAcquire("online").allowed()).isFalse(); // 101st rejected
        assertThat(limiter.tryAcquire("online").retryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void boundaryBurst_staysNearLimit_notDouble() {
        AtomicLong clock = new AtomicLong(5_000L); // window 5, elapsed 0
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:test:", 100L, 1, clock::get);

        // Fill window 5 to the limit.
        assertThat(admitted(limiter, 100)).isEqualTo(100);

        // Step to the very start of window 6 (elapsed 0 → prev-window weight ~1.0).
        clock.set(6_000L);
        // A fixed-window limiter would reset here and admit another 100 (→ 200 in a rolling 1s).
        // The sliding window estimates prev(100)*1.0 + cur(0) = 100, so it admits ~0.
        int admittedAtBoundary = admitted(limiter, 100);
        assertThat(admittedAtBoundary).isLessThanOrEqualTo(1);

        // Halfway into window 6 (elapsed 500 → weight 0.5), ~50 of the prior window has aged out.
        clock.set(6_500L);
        int admittedMidWindow = admitted(limiter, 100);
        assertThat(admittedMidWindow).isBetween(45, 55);
    }

    @Test
    void windowKey_selfExpiresAfterTwoWindows() {
        AtomicLong clock = new AtomicLong(5_000L);
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:test:", 100L, 1, clock::get);

        limiter.tryAcquire("online"); // writes rate:test:online:5, PEXPIRE 2000ms

        Long pttl = exec.execute((RedisCommands<String, String> c) -> c.pttl("rate:test:online:5"));
        assertThat(pttl).isGreaterThan(0L).isLessThanOrEqualTo(2000L);
    }
}
