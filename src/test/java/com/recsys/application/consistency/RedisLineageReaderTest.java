package com.recsys.application.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.recsys.infrastructure.redis.RedisExecutor;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class RedisLineageReaderTest {
    @Test @SuppressWarnings("unchecked")
    void primaryLookupUsesRemainingCommandBudgetAndNeverOrdinaryRead() {
        RedisExecutor redis = mock(RedisExecutor.class);
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        Duration remaining = Duration.ofMillis(317);
        when(redis.executePrimaryRead(any(), eq(remaining))).thenAnswer(invocation ->
                invocation.getArgument(0, Function.class).apply(commands));
        when(commands.sismember(any(), any())).thenReturn(true);
        UUID eventId = UUID.randomUUID();

        assertThat(new RedisLineageReader(redis).contains(eventId, 42, remaining)).isTrue();

        verify(redis).executePrimaryRead(any(), eq(remaining));
        verify(redis, never()).executeRead(any());
    }
}
