package com.recsys.modelbased.model.service;

import com.recsys.modelbased.model.config.SubmitTokenProperties;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.util.List;

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
        when(redis.jedis.set(any(), eq("1"), any(SetParams.class))).thenReturn("OK");

        SubmitTokenService service = new SubmitTokenService(enabledProperties(), () -> redis.pool);

        String token = service.createToken();

        assertThat(token).isNotBlank();
        verify(redis.jedis).set(eq("submit_token:" + token), eq("1"), any(SetParams.class));
    }

    @Test
    void validateAndConsume_deletesTokenAtomicallyViaLua() {
        RedisMocks redis = redis();
        when(redis.jedis.eval(eq(SubmitTokenService.CONSUME_SCRIPT), anyStringList(), anyStringList()))
                .thenReturn(1L);

        SubmitTokenService service = new SubmitTokenService(enabledProperties(), () -> redis.pool);

        assertThatCode(() -> service.validateAndConsume("tok"))
                .doesNotThrowAnyException();

        verify(redis.jedis).eval(
                eq(SubmitTokenService.CONSUME_SCRIPT),
                eq(List.of("submit_token:tok")),
                eq(List.of("1")));
    }

    @Test
    void validateAndConsume_rejectsMissingOrAlreadyUsedToken() {
        RedisMocks redis = redis();
        when(redis.jedis.eval(eq(SubmitTokenService.CONSUME_SCRIPT), anyStringList(), anyStringList()))
                .thenReturn(0L);

        SubmitTokenService service = new SubmitTokenService(enabledProperties(), () -> redis.pool);

        assertThatThrownBy(() -> service.validateAndConsume("tok"))
                .isInstanceOf(SubmitTokenException.class)
                .hasMessage("submit token is invalid or already used; refresh and retry");
    }

    @Test
    void validateAndConsume_disabledSkipsRedis() {
        RedisMocks redis = redis();
        SubmitTokenProperties properties = enabledProperties();
        properties.setEnabled(false);

        SubmitTokenService service = new SubmitTokenService(properties, () -> redis.pool);

        assertThatCode(() -> service.validateAndConsume(null))
                .doesNotThrowAnyException();

        verify(redis.pool, never()).getResource();
    }

    private static SubmitTokenProperties enabledProperties() {
        SubmitTokenProperties properties = new SubmitTokenProperties();
        properties.setEnabled(true);
        properties.setTtlSeconds(300);
        properties.setKeyPrefix("submit_token:");
        return properties;
    }

    private static RedisMocks redis() {
        JedisPool pool = mock(JedisPool.class);
        Jedis jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);
        return new RedisMocks(pool, jedis);
    }

    @SuppressWarnings("unchecked")
    private static List<String> anyStringList() {
        return any(List.class);
    }

    private record RedisMocks(JedisPool pool, Jedis jedis) {}
}
