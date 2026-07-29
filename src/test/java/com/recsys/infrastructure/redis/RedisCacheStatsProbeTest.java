package com.recsys.infrastructure.redis;

import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisCacheStatsProbeTest {

    private static final String INFO = """
            # Memory
            used_memory:1048576
            maxmemory:209715200
            maxmemory_policy:volatile-lru
            # Stats
            evicted_keys:42
            keyspace_hits:900
            keyspace_misses:100
            """;

    @SuppressWarnings("unchecked")
    private static RedisExecutor execReturning(String info) {
        RedisCommands<String, String> cmd = mock(RedisCommands.class);
        when(cmd.info()).thenReturn(info);
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any())).thenAnswer(i -> i.getArgument(0, Function.class).apply(cmd));
        return exec;
    }

    @Test
    void parsesMemoryAndKeyspaceCountersFromInfo() {
        RedisCacheStatsProbe.CacheStats stats = new RedisCacheStatsProbe(execReturning(INFO)).sample();

        assertThat(stats.available()).isTrue();
        assertThat(stats.usedMemoryBytes()).isEqualTo(1_048_576L);
        assertThat(stats.maxMemoryBytes()).isEqualTo(209_715_200L);
        assertThat(stats.evictedKeys()).isEqualTo(42L);
        assertThat(stats.keyspaceHits()).isEqualTo(900L);
        assertThat(stats.keyspaceMisses()).isEqualTo(100L);
    }

    @Test
    void reportsWhetherTheRunningPolicyProtectsKeysWithoutATtl() {
        assertThat(new RedisCacheStatsProbe(execReturning(INFO)).sample().evictsOnlyVolatileKeys())
                .isTrue();

        String drifted = INFO.replace("volatile-lru", "allkeys-lru");
        assertThat(new RedisCacheStatsProbe(execReturning(drifted)).sample().evictsOnlyVolatileKeys())
                .as("a live cluster running allkeys-lru must be visible even if the manifests say otherwise")
                .isFalse();
    }

    @Test
    void reportsUnavailableWithoutPropagatingWhenRedisFails() {
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any())).thenThrow(new IllegalStateException("redis down"));

        RedisCacheStatsProbe.CacheStats stats = new RedisCacheStatsProbe(exec).sample();

        assertThat(stats.available()).isFalse();
    }

    @Test
    void toleratesAnInfoPayloadMissingTheFieldsItWants() {
        RedisCacheStatsProbe.CacheStats stats =
                new RedisCacheStatsProbe(execReturning("# Server\nredis_version:7.2.4\n")).sample();

        assertThat(stats.available()).isTrue();
        assertThat(stats.usedMemoryBytes()).isZero();
        // maxmemory absent reads as 0, which is also Redis's own encoding of "no limit".
        assertThat(stats.maxMemoryBytes()).isZero();
        assertThat(stats.evictsOnlyVolatileKeys()).isFalse();
    }
}
