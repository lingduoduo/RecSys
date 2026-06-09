package com.recsys.modelbased.service;

import com.recsys.infrastructure.redis.RedisConnectionFactory;
import com.recsys.modelbased.config.SubmitTokenProperties;
import com.recsys.modelbased.exception.SubmitTokenException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.util.Pool;

import java.util.List;
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
    private final Supplier<Pool<Jedis>> poolFactory;
    private volatile Pool<Jedis> pool;

    @Autowired
    public SubmitTokenService(SubmitTokenProperties properties) {
        this(properties, () -> RedisConnectionFactory.fromEnv());
    }

    SubmitTokenService(SubmitTokenProperties properties, Supplier<Pool<Jedis>> poolFactory) {
        this.properties = properties;
        this.poolFactory = poolFactory;
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
        try (Jedis jedis = jedis()) {
            String result = jedis.set(redisKey(token), TOKEN_VALUE,
                    SetParams.setParams().nx().ex(properties.getTtlSeconds()));
            if (!"OK".equals(result)) {
                throw new SubmitTokenException("failed to create submit token");
            }
            return token;
        }
    }

    public void validateAndConsume(String token) {
        if (!isEnabled()) {
            return;
        }
        if (token == null || token.isBlank()) {
            throw invalidToken();
        }
        try (Jedis jedis = jedis()) {
            Object result = jedis.eval(CONSUME_SCRIPT, List.of(redisKey(token.trim())), List.of(TOKEN_VALUE));
            if (!(result instanceof Number n) || n.longValue() != 1L) {
                throw invalidToken();
            }
        }
    }

    @PreDestroy
    public void close() {
        Pool<Jedis> current = pool;
        if (current != null) {
            current.close();
        }
    }

    private Jedis jedis() {
        Pool<Jedis> current = pool;
        if (current == null) {
            synchronized (this) {
                current = pool;
                if (current == null) {
                    current = poolFactory.get();
                    pool = current;
                }
            }
        }
        return current.getResource();
    }

    private String redisKey(String token) {
        return properties.getKeyPrefix() + token;
    }

    private static SubmitTokenException invalidToken() {
        return new SubmitTokenException("submit token is invalid or already used; refresh and retry");
    }
}
