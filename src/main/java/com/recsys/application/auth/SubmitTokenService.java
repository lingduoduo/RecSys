package com.recsys.application.auth;

import com.recsys.infrastructure.redis.LazyRedisExecutor;
import com.recsys.infrastructure.redis.LettuceClientFactory;
import com.recsys.infrastructure.redis.RedisExecutor;
import com.recsys.config.SubmitTokenProperties;
import com.recsys.exception.SubmitTokenException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.function.Supplier;

@Service
public class SubmitTokenService {

    public static final String HEADER_NAME = "X-Submit-Token";
    private static final String TOKEN_VALUE = "1";
    static final String CONSUME_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    private final SubmitTokenProperties properties;
    private final RedisExecutor redis;

    @Autowired
    public SubmitTokenService(SubmitTokenProperties properties) {
        this(properties, () -> LettuceClientFactory.fromEnv());
    }

    SubmitTokenService(SubmitTokenProperties properties, Supplier<RedisExecutor> executorFactory) {
        this.properties = properties;
        this.redis = new LazyRedisExecutor(executorFactory);
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public int ttlSeconds() {
        return properties.getTtlSeconds();
    }

    public String createToken() {
        if (!isEnabled()) {
            throw new SubmitTokenException("submit token protection is disabled");
        }

        String token = UUID.randomUUID().toString();
        String result = redis.execute(c ->
                c.set(redisKey(token), TOKEN_VALUE,
                        SetArgs.Builder.nx().ex(properties.getTtlSeconds())));
        if (!"OK".equals(result)) {
            throw new SubmitTokenException("failed to create submit token");
        }
        return token;
    }

    public void validateAndConsume(String token) {
        if (!isEnabled()) {
            return;
        }
        if (token == null || token.isBlank()) {
            throw invalidToken();
        }
        Long result = redis.execute(c -> c.eval(CONSUME_SCRIPT, ScriptOutputType.INTEGER,
                new String[]{redisKey(token.trim())}, TOKEN_VALUE));
        if (result == null || result != 1L) {
            throw invalidToken();
        }
    }

    @PreDestroy
    public void close() {
        redis.close();
    }

    private String redisKey(String token) {
        return properties.getKeyPrefix() + token;
    }

    private static SubmitTokenException invalidToken() {
        return new SubmitTokenException("submit token is invalid or already used; refresh and retry");
    }
}
