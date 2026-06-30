package com.recsys.application.auth;

import com.recsys.config.SubmitTokenProperties;
import com.recsys.exception.SubmitTokenException;
import com.recsys.infrastructure.redis.RedisExecutor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubmitTokenServiceTest {

    @Test
    void createToken_writesOneTimeTokenWithTtl() {
        RedisMocks redis = redis();
        when(redis.cmd.set(any(), eq("1"), any(SetArgs.class))).thenReturn("OK");

        SubmitTokenService service = new SubmitTokenService(enabledProperties(), () -> redis.exec);

        String token = service.createToken();

        assertThat(token).isNotBlank();
        verify(redis.cmd).set(eq("submit_token:" + token), eq("1"), any(SetArgs.class));
    }

    @Test
    void validateAndConsume_deletesTokenAtomicallyViaLua() {
        RedisMocks redis = redis();
        when(redis.cmd.eval(eq(SubmitTokenService.CONSUME_SCRIPT), eq(ScriptOutputType.INTEGER),
                any(String[].class), any(String[].class)))
                .thenReturn(1L);

        SubmitTokenService service = new SubmitTokenService(enabledProperties(), () -> redis.exec);

        assertThatCode(() -> service.validateAndConsume("tok"))
                .doesNotThrowAnyException();

        verify(redis.cmd).eval(
                eq(SubmitTokenService.CONSUME_SCRIPT),
                eq(ScriptOutputType.INTEGER),
                eq(new String[]{"submit_token:tok"}),
                eq("1"));
    }

    @Test
    void validateAndConsume_rejectsMissingOrAlreadyUsedToken() {
        RedisMocks redis = redis();
        when(redis.cmd.eval(eq(SubmitTokenService.CONSUME_SCRIPT), eq(ScriptOutputType.INTEGER),
                any(String[].class), any(String[].class)))
                .thenReturn(0L);

        SubmitTokenService service = new SubmitTokenService(enabledProperties(), () -> redis.exec);

        assertThatThrownBy(() -> service.validateAndConsume("tok"))
                .isInstanceOf(SubmitTokenException.class)
                .hasMessage("submit token is invalid or already used; refresh and retry");
    }

    @Test
    void validateAndConsume_disabledSkipsRedis() {
        RedisMocks redis = redis();
        SubmitTokenProperties properties = enabledProperties();
        properties.setEnabled(false);

        SubmitTokenService service = new SubmitTokenService(properties, () -> redis.exec);

        assertThatCode(() -> service.validateAndConsume(null))
                .doesNotThrowAnyException();

        verify(redis.exec, never()).execute(any());
    }

    private static SubmitTokenProperties enabledProperties() {
        SubmitTokenProperties properties = new SubmitTokenProperties();
        properties.setEnabled(true);
        properties.setTtlSeconds(300);
        properties.setKeyPrefix("submit_token:");
        return properties;
    }

    @SuppressWarnings("unchecked")
    private static RedisMocks redis() {
        RedisExecutor exec = mock(RedisExecutor.class);
        RedisCommands<String, String> cmd = mock(RedisCommands.class);
        when(exec.execute(any())).thenAnswer(i -> i.getArgument(0, Function.class).apply(cmd));
        return new RedisMocks(exec, cmd);
    }

    private record RedisMocks(RedisExecutor exec, RedisCommands<String, String> cmd) {}
}
