package com.recsys.infrastructure.redis;

import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class RedisReplicaLagProbeTest {
    @Test void writesPrimaryAndMeasuresReplicaLagFromPreviouslyReplicatedProbe() {
        Map<String, String> primary = new HashMap<>();
        Map<String, String> replica = new HashMap<>();
        replica.put("recsys:replica-lag-probe", "3:1750000000000");
        RedisReplicaLagProbe probe = new RedisReplicaLagProbe(executor(primary, replica),
                Clock.fixed(Instant.ofEpochMilli(1750000001500L), ZoneOffset.UTC));

        RedisReplicaLagProbe.ProbeResult result = probe.sample();

        assertThat(result.available()).isTrue();
        assertThat(result.lagSeconds()).isEqualTo(1.5);
        assertThat(primary.get("recsys:replica-lag-probe")).startsWith("1:1750000001500");
    }

    @Test void probeFailureReportsUnavailableNotZero() {
        RedisExecutor failing = new StubExecutor() {
            @Override public <T> T executeRead(Function<RedisCommands<String, String>, T> fn) {
                throw new IllegalStateException("replica offline");
            }
        };
        RedisReplicaLagProbe.ProbeResult result = new RedisReplicaLagProbe(failing, Clock.systemUTC()).sample();
        assertThat(result.available()).isFalse();
    }

    private static RedisExecutor executor(Map<String, String> primary, Map<String, String> replica) {
        return new StubExecutor() {
            @Override public <T> T execute(Function<RedisCommands<String, String>, T> fn) { return fn.apply(commands(primary)); }
            @Override public <T> T executeRead(Function<RedisCommands<String, String>, T> fn) { return fn.apply(commands(replica)); }
        };
    }

    @SuppressWarnings("unchecked")
    private static RedisCommands<String, String> commands(Map<String, String> values) {
        return (RedisCommands<String, String>) Proxy.newProxyInstance(RedisCommands.class.getClassLoader(),
                new Class<?>[]{RedisCommands.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> values.get(args[0]);
                    case "set" -> { values.put((String) args[0], (String) args[1]); yield "OK"; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private abstract static class StubExecutor implements RedisExecutor {
        @Override public <T> T execute(Function<RedisCommands<String, String>, T> fn) { return null; }
        @Override public <T> T executeRead(Function<RedisCommands<String, String>, T> fn) { return null; }
        @Override public void executePipelined(java.util.function.Consumer<io.lettuce.core.api.StatefulRedisConnection<String, String>> fn) {}
        @Override public void close() {}
    }
}
