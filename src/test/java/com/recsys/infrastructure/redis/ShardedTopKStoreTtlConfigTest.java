package com.recsys.infrastructure.redis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShardedTopKStoreTtlConfigTest {

    @Test
    void cacheTtlEnvVar_defaultsToTwoSecondsWhenUnset() {
        // ONLINE_TOPK_CACHE_TTL_MS is not set in the test environment.
        long resolved = ShardedTopKStore.readLongEnv("ONLINE_TOPK_CACHE_TTL_MS",
                ShardedTopKStore.DEFAULT_CACHE_TTL_MS);
        assertThat(resolved).isEqualTo(2_000L);
    }

    @Test
    void cacheTtlEnvVar_isReadableByName() {
        // Documents the contract: operators set ONLINE_TOPK_CACHE_TTL_MS=1000 to align
        // the topk fresh TTL with GlobalPopularityStore. With the var unset, the helper
        // returns the supplied default unchanged.
        long resolved = ShardedTopKStore.readLongEnv("ONLINE_TOPK_CACHE_TTL_MS", 1_000L);
        assertThat(resolved).isEqualTo(1_000L);
    }
}
