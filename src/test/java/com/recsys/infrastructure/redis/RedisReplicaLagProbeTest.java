package com.recsys.infrastructure.redis;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.protocol.CommandArgs;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RedisReplicaLagProbeTest {
    @Test void writesPrimaryAndMeasuresReplicaLagFromPreviouslyReplicatedProbe() {
        Map<String, String> primary = new HashMap<>();
        Map<String, String> replica = new HashMap<>();
        replica.put("probe:test", "0:1750000000000");
        RedisReplicaLagProbe probe = new RedisReplicaLagProbe(executor(primary, replica),
                Clock.fixed(Instant.ofEpochMilli(1750000001500L), ZoneOffset.UTC), "probe:test");

        RedisReplicaLagProbe.ProbeResult result = probe.sample();

        assertThat(result.available()).isTrue();
        assertThat(result.lagSeconds()).isEqualTo(1.5);
        assertThat(primary.get("probe:test")).startsWith("1:1750000001500");
    }

    @Test void probeFailureReportsUnavailableNotZero() {
        RedisExecutor failing = new StubExecutor() {
            @Override public <T> T executeRead(Function<RedisCommands<String, String>, T> fn) {
                throw new IllegalStateException("replica offline");
            }
            @Override public <T> Optional<T> executeReplicaRead(Function<RedisCommands<String, String>, T> fn) {
                throw new IllegalStateException("replica offline");
            }
        };
        RedisReplicaLagProbe.ProbeResult result = new RedisReplicaLagProbe(failing, Clock.systemUTC()).sample();
        assertThat(result.available()).isFalse();
    }

    @Test void markerWriteCarriesATtlSoADeadInstanceLeavesNoPermanentKey() {
        Map<String, String> primary = new HashMap<>();
        Map<String, String> replica = new HashMap<>();
        replica.put("probe:test", "0:1750000000000");
        AtomicReference<SetArgs> captured = new AtomicReference<>();
        RedisExecutor exec = new StubExecutor() {
            @Override public <T> T execute(Function<RedisCommands<String, String>, T> fn) {
                return fn.apply(capturingCommands(primary, captured));
            }
            @Override public <T> T executeRead(Function<RedisCommands<String, String>, T> fn) {
                return fn.apply(commands(replica));
            }
            @Override public <T> Optional<T> executeReplicaRead(Function<RedisCommands<String, String>, T> fn) {
                return Optional.ofNullable(fn.apply(commands(replica)));
            }
        };

        new RedisReplicaLagProbe(exec, Clock.systemUTC(), "probe:test").sample();

        assertThat(captured.get())
                .as("the marker is written per process instance; a bare SET makes it permanently "
                        + "resident and unevictable under volatile-lru")
                .isNotNull();
        CommandArgs<String, String> rendered = new CommandArgs<>(StringCodec.UTF8);
        captured.get().build(rendered);
        assertThat(rendered.toString()).contains("EX");
    }

    private static RedisExecutor executor(Map<String, String> primary, Map<String, String> replica) {
        return new StubExecutor() {
            @Override public <T> T execute(Function<RedisCommands<String, String>, T> fn) { return fn.apply(commands(primary)); }
            @Override public <T> T executeRead(Function<RedisCommands<String, String>, T> fn) { return fn.apply(commands(replica)); }
            @Override public <T> Optional<T> executeReplicaRead(Function<RedisCommands<String, String>, T> fn) {
                return Optional.ofNullable(fn.apply(commands(replica)));
            }
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

    @SuppressWarnings("unchecked")
    private static RedisCommands<String, String> capturingCommands(Map<String, String> values,
                                                                   AtomicReference<SetArgs> captured) {
        return (RedisCommands<String, String>) Proxy.newProxyInstance(RedisCommands.class.getClassLoader(),
                new Class<?>[]{RedisCommands.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> values.get(args[0]);
                    case "set" -> {
                        values.put((String) args[0], (String) args[1]);
                        if (args.length > 2) captured.set((SetArgs) args[2]);
                        yield "OK";
                    }
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
