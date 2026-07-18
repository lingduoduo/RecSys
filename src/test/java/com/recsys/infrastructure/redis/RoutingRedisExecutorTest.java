package com.recsys.infrastructure.redis;

import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutingRedisExecutorTest {

    @Test
    void primaryReadNeverUsesReplica() {
        RedisExecutor primary = mock(RedisExecutor.class);
        RedisExecutor replica = mock(RedisExecutor.class);
        when(primary.executeRead(any())).thenReturn("PRIMARY_READ");
        var router = new RedisReadReplicaRouter(primary,
                List.of(new RedisReadReplicaRouter.AzExecutor(replica, "us-east-1b")), "us-east-1b");

        try (var exec = new RoutingRedisExecutor(router)) {
            assertThat(exec.executePrimaryRead(anyFn())).isEqualTo("PRIMARY_READ");
        }

        verify(primary).executeRead(any());
        verify(replica, never()).execute(any());
        verify(replica, never()).executeRead(any());
    }

    @SuppressWarnings("unchecked")
    private static Function<RedisCommands<String, String>, String> anyFn() {
        return c -> "ignored";
    }

    @Test
    void execute_goesToPrimary() {
        RedisExecutor primary = mock(RedisExecutor.class);
        RedisExecutor replica = mock(RedisExecutor.class);
        when(primary.execute(any())).thenReturn("PRIMARY");
        var router = new RedisReadReplicaRouter(primary,
                List.of(new RedisReadReplicaRouter.AzExecutor(replica, "us-east-1b")), "us-east-1b");
        try (var exec = new RoutingRedisExecutor(router)) {
            assertThat(exec.execute(anyFn())).isEqualTo("PRIMARY");
        }
        verify(primary).execute(any());
        verify(replica, never()).execute(any());
        verify(replica, never()).executeRead(any());
    }

    @Test
    void executeRead_goesToReplicaWhenConfigured() {
        RedisExecutor primary = mock(RedisExecutor.class);
        RedisExecutor replica = mock(RedisExecutor.class);
        when(replica.executeRead(any())).thenReturn("REPLICA");
        var router = new RedisReadReplicaRouter(primary,
                List.of(new RedisReadReplicaRouter.AzExecutor(replica, "us-east-1b")), "us-east-1b");
        try (var exec = new RoutingRedisExecutor(router)) {
            assertThat(exec.executeRead(anyFn())).isEqualTo("REPLICA");
        }
        verify(replica).executeRead(any());
        verify(primary, never()).execute(any());
        verify(primary, never()).executeRead(any());
    }

    @Test
    void executeRead_fallsBackToPrimaryWhenNoReplicas() {
        RedisExecutor primary = mock(RedisExecutor.class);
        when(primary.executeRead(any())).thenReturn("PRIMARY_READ");
        var router = new RedisReadReplicaRouter(primary, List.of(), "us-east-1a");
        try (var exec = new RoutingRedisExecutor(router)) {
            assertThat(exec.executeRead(anyFn())).isEqualTo("PRIMARY_READ");
        }
        verify(primary).executeRead(any());
    }

    @Test
    void executePipelined_goesToPrimary() {
        RedisExecutor primary = mock(RedisExecutor.class);
        RedisExecutor replica = mock(RedisExecutor.class);
        var router = new RedisReadReplicaRouter(primary,
                List.of(new RedisReadReplicaRouter.AzExecutor(replica, "us-east-1b")), "us-east-1b");
        try (var exec = new RoutingRedisExecutor(router)) {
            exec.executePipelined(conn -> { /* no-op */ });
        }
        verify(primary).executePipelined(any());
        verify(replica, never()).executePipelined(any());
    }

    @Test
    void executeReadPipelined_goesToReplicaWhenConfigured() {
        RedisExecutor primary = mock(RedisExecutor.class);
        RedisExecutor replica = mock(RedisExecutor.class);
        var router = new RedisReadReplicaRouter(primary,
                List.of(new RedisReadReplicaRouter.AzExecutor(replica, "us-east-1b")), "us-east-1b");
        try (var exec = new RoutingRedisExecutor(router)) {
            exec.executeReadPipelined(conn -> { /* no-op */ });
        }
        verify(replica).executeReadPipelined(any());
        verify(primary, never()).executePipelined(any());
        verify(primary, never()).executeReadPipelined(any());
    }

    @Test
    void executeReadPipelined_fallsBackToPrimaryWhenNoReplicas() {
        RedisExecutor primary = mock(RedisExecutor.class);
        var router = new RedisReadReplicaRouter(primary, List.of(), "us-east-1a");
        try (var exec = new RoutingRedisExecutor(router)) {
            exec.executeReadPipelined(conn -> { });
        }
        verify(primary).executeReadPipelined(any());
    }

    @Test
    void close_closesRouterAndAllExecutors() {
        RedisExecutor primary = mock(RedisExecutor.class);
        RedisExecutor replica = mock(RedisExecutor.class);
        var router = new RedisReadReplicaRouter(primary,
                List.of(new RedisReadReplicaRouter.AzExecutor(replica, "us-east-1b")), "us-east-1b");
        new RoutingRedisExecutor(router).close();
        verify(primary).close();
        verify(replica).close();
    }
}
