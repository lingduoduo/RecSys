package com.recsys.application.auth;

import com.recsys.application.model.LazyRedisExecutor;
import com.recsys.infrastructure.redis.LettuceClientFactory;
import com.recsys.infrastructure.redis.RedisExecutor;
import com.recsys.exception.UnauthorizedException;
import io.lettuce.core.SetArgs;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

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

    private final LazyRedisExecutor redis;

    public LoginTokenService() {
        this(() -> LettuceClientFactory.fromEnv());
    }

    LoginTokenService(Supplier<RedisExecutor> executorFactory) {
        this.redis = new LazyRedisExecutor(executorFactory);
    }

    /** Creates a session token for the given userId and stores it in Redis. */
    public String create(String userId) {
        String token = UUID.randomUUID().toString();
        String result = redis.execute(c ->
                c.set(redisKey(token), userId, SetArgs.Builder.nx().ex(TTL_SECONDS)));
        if (!"OK".equals(result)) {
            throw new IllegalStateException("failed to persist login token");
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
        String userId = redis.execute(c -> c.get(redisKey(token.trim())));
        if (userId == null) {
            throw new UnauthorizedException("login token is invalid or expired");
        }
        return userId;
    }

    /** Invalidates an existing session token (logout). */
    public void invalidate(String token) {
        if (token == null || token.isBlank()) return;
        redis.execute(c -> c.del(redisKey(token.trim())));
    }

    @PreDestroy
    public void close() {
        redis.close();
    }

    private static String redisKey(String token) {
        return KEY_PREFIX + token;
    }
}
