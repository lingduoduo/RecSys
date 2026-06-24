package com.recsys.infrastructure.redis.sharding;

/** Package-private test helpers for constructing ShardTopologyProvider test doubles. */
class TestProviders {

    private TestProviders() {}

    /**
     * Returns a constant provider whose current/previous/expiry triple is set directly,
     * bypassing any live store or refresh cycle. Useful for dual-read tests that need to
     * bracket the migration window.
     */
    static ShardTopologyProvider withPrevious(ShardTopology current, ShardTopology previous,
                                              long prevExpiresAtMs) {
        return ShardTopologyProvider.fixedWithPrevious(current, previous, prevExpiresAtMs);
    }
}
