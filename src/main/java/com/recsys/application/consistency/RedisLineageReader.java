package com.recsys.application.consistency;

import com.recsys.infrastructure.redis.RedisExecutor;
import java.util.Objects;
import java.util.UUID;

/** Primary-only lookup for the feature lineage written atomically by the Flink sink. */
public final class RedisLineageReader implements ConsistencyWaiter.LineageReader {
    private final RedisExecutor redis;

    public RedisLineageReader(RedisExecutor redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    @Override
    public boolean contains(UUID eventId, int userId) {
        String lineageKey = "lineage:event:" + eventId;
        String featureKey = "user:" + userId + ":recent_movies";
        return redis.executePrimaryRead(c -> c.sismember(lineageKey, featureKey));
    }
}
