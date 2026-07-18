package com.recsys.infrastructure.redis;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LettuceRedisExecutorDeadlineTest {
    @Test @SuppressWarnings("unchecked")
    void borrowAndCommandShareOneDeadlineAndTimeoutIsRestored() throws Exception {
        GenericObjectPool<StatefulRedisConnection<String, String>> pool = mock(GenericObjectPool.class);
        StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        AtomicLong now = new AtomicLong();
        when(pool.borrowObject(anyLong())).thenAnswer(invocation -> {
            now.set(Duration.ofMillis(60).toNanos());
            return connection;
        });
        when(connection.sync()).thenReturn(commands);
        when(connection.getTimeout()).thenReturn(Duration.ofSeconds(5));
        when(commands.get("key")).thenReturn("value");
        LettuceRedisExecutor executor = new LettuceRedisExecutor(null, pool, false, now::get);

        String result = executor.executePrimaryRead(c -> c.get("key"), Duration.ofMillis(100));
        assertThat(result).isEqualTo("value");

        verify(pool).borrowObject(100L);
        var ordered = inOrder(connection, commands, pool);
        ordered.verify(connection).setTimeout(Duration.ofMillis(40));
        ordered.verify(commands).get("key");
        ordered.verify(connection).setTimeout(Duration.ofSeconds(5));
        ordered.verify(pool).returnObject(connection);
    }

    @Test @SuppressWarnings("unchecked")
    void subMillisecondBudgetIsBorrowedSafely() throws Exception {
        GenericObjectPool<StatefulRedisConnection<String, String>> pool = mock(GenericObjectPool.class);
        StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        when(pool.borrowObject(anyLong())).thenReturn(connection);
        when(connection.sync()).thenReturn(commands);
        when(connection.getTimeout()).thenReturn(Duration.ofSeconds(1));
        LettuceRedisExecutor executor = new LettuceRedisExecutor(null, pool, false, () -> 0L);

        executor.executePrimaryRead(c -> null, Duration.ofNanos(500_000));

        verify(pool).borrowObject(1L);
        verify(connection).setTimeout(Duration.ofNanos(500_000));
        verify(connection).setTimeout(Duration.ofSeconds(1));
    }
}
