package com.recsys.infrastructure.registry;

import com.recsys.infrastructure.redis.RedisExecutor;

import io.lettuce.core.KeyValue;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceRegistryStoreTest {

    @SuppressWarnings("unchecked")
    private static RedisExecutor executorOver(RedisCommands<String, String> cmds) {
        RedisExecutor exec = Mockito.mock(RedisExecutor.class);
        when(exec.execute(any()))
                .thenAnswer(inv -> ((Function<RedisCommands<String, String>, Object>) inv.getArgument(0)).apply(cmds));
        when(exec.executeRead(any()))
                .thenAnswer(inv -> ((Function<RedisCommands<String, String>, Object>) inv.getArgument(0)).apply(cmds));
        return exec;
    }

    @Test
    @SuppressWarnings("unchecked")
    void registerSetsKeyWithTtl() {
        RedisCommands<String, String> cmds = Mockito.mock(RedisCommands.class);
        ServiceRegistryStore store = new ServiceRegistryStore(executorOver(cmds), "svc:registry:");

        store.register("recsys-catalog-serving", "http://host:6010", 30_000L);

        verify(cmds).set(eq("svc:registry:recsys-catalog-serving"), eq("http://host:6010"), any(SetArgs.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void lookupMapsPresentEntriesAndOmitsAbsent() {
        RedisCommands<String, String> cmds = Mockito.mock(RedisCommands.class);
        when(cmds.mget("svc:registry:a", "svc:registry:b")).thenReturn(List.of(
                KeyValue.fromNullable("svc:registry:a", "http://a:1"),
                KeyValue.fromNullable("svc:registry:b", null)));
        ServiceRegistryStore store = new ServiceRegistryStore(executorOver(cmds), "svc:registry:");

        Map<String, String> got = store.lookup(List.of("a", "b"));

        assertThat(got).containsExactly(Map.entry("a", "http://a:1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void deregisterDeletesKey() {
        RedisCommands<String, String> cmds = Mockito.mock(RedisCommands.class);
        ServiceRegistryStore store = new ServiceRegistryStore(executorOver(cmds), "svc:registry:");

        store.deregister("svc-a");

        verify(cmds).del("svc:registry:svc-a");
    }

    @Test
    @SuppressWarnings("unchecked")
    void lookupOfEmptyReturnsEmpty() {
        RedisCommands<String, String> cmds = Mockito.mock(RedisCommands.class);
        ServiceRegistryStore store = new ServiceRegistryStore(executorOver(cmds), "svc:registry:");
        assertThat(store.lookup(List.of())).isEmpty();
    }
}
