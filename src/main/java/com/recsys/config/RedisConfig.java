package com.recsys.config;

import com.recsys.infrastructure.redis.RedisConnectionFactory;
import com.recsys.infrastructure.redis.RedisReadReplicaRouter;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.Pool;

@Configuration
public class RedisConfig {

    private final RedisProperties redisProperties;

    public RedisConfig(RedisProperties redisProperties) {
        this.redisProperties = redisProperties;
    }

    /** Primary connection pool — use this for writes and non-replica reads. */
    @Bean
    public Pool<Jedis> jedisPool() {
        return RedisConnectionFactory.from(redisProperties);
    }

    /**
     * AZ-aware read-replica router.
     * Falls back to the primary pool when no replica nodes are configured.
     */
    @Bean
    public RedisReadReplicaRouter redisReadReplicaRouter() {
        return RedisConnectionFactory.routerFrom(redisProperties);
    }

    @PreDestroy
    public void close() {
        // Beans manage their own lifecycle; Spring calls @PreDestroy on each Pool bean.
    }
}
