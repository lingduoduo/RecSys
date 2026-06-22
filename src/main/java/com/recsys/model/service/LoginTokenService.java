package com.recsys.model.service;

import com.recsys.infrastructure.redis.RedisConnectionFactory;
import com.recsys.model.exception.UnauthorizedException;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.util.Pool;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Redis-backed login session token store.
 * Unlike SubmitTokenService, tokens are NOT consumed on validation —
 * they remain valid until they expire or are explicitly invalidated.
 */
@Service
public class LoginTokenService {

    static final String KEY_PREFIX = "login:";
    public static final int TTL_SECONDS = 86_400; // 24 hours

    private final LazyJedisPool jedisPool;

    public LoginTokenService() {
        this(() -> RedisConnectionFactory.fromEnv());
    }

    LoginTokenService(Supplier<Pool<Jedis>> poolFactory) {
        this.jedisPool = new LazyJedisPool(poolFactory);
    }

    /** Creates a session token for the given userId and stores it in Redis. */
    public String create(String userId) {
        String token = UUID.randomUUID().toString();
        try (Jedis jedis = jedisPool.resource()) {
            String result = jedis.set(redisKey(token), userId,
                    SetParams.setParams().nx().ex(TTL_SECONDS));
            if (!"OK".equals(result)) {
                throw new IllegalStateException("failed to persist login token");
            }
        }
        return token;
    }

    /**
     * Validates the token and returns the associated userId.
     * Throws {@link UnauthorizedException} if the token is missing or expired.
     */
    public String validate(String token) {
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException("missing or invalid Authorization header");
        }
        try (Jedis jedis = jedisPool.resource()) {
            String userId = jedis.get(redisKey(token.trim()));
            if (userId == null) {
                throw new UnauthorizedException("login token is invalid or expired");
            }
            return userId;
        }
    }

    /** Invalidates an existing session token (logout). */
    public void invalidate(String token) {
        if (token == null || token.isBlank()) return;
        try (Jedis jedis = jedisPool.resource()) {
            jedis.del(redisKey(token.trim()));
        }
    }

    @PreDestroy
    public void close() {
        jedisPool.close();
    }

    private static String redisKey(String token) {
        return KEY_PREFIX + token;
    }
}
