package com.recsys.infrastructure.redis.sharding;

public enum RecordType {
    /** Click, watch, rating, dwell, search — from LogCollector / Kafka. */
    EVENT,
    /**
     * Flink-written behavioral features: recent history updates, engagement events, session data.
     * Raw float[] embeddings are NOT stored here — use RedisEmbeddingStore for those.
     */
    FEATURE,
    /** General audit / debug log entries. */
    LOG
}
