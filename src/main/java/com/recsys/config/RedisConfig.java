package com.recsys.config;

import com.recsys.infrastructure.redis.LettuceClientFactory;
import com.recsys.infrastructure.redis.RedisExecutor;
import com.recsys.infrastructure.redis.RedisReadReplicaRouter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {

    private final RedisProperties redisProperties;

    public RedisConfig(RedisProperties redisProperties) {
        this.redisProperties = redisProperties;
    }

    /** Primary Redis executor — use this for writes and non-replica reads. */
    @Bean(destroyMethod = "close")
    public RedisExecutor redisExecutor() {
        return LettuceClientFactory.from(redisProperties);
    }

    /**
     * AZ-aware read-replica router.
     * Falls back to the primary executor when no replica nodes are configured.
     */
    @Bean(destroyMethod = "close")
    public RedisReadReplicaRouter redisReadReplicaRouter() {
        return LettuceClientFactory.routerFrom(redisProperties);
    }
}
