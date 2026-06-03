package com.recsys.infrastructure.redis.sharding;

import java.util.Objects;

public record ShardedRecord(
        String     deviceId,
        long       seqNum,
        RecordType type,
        String     eventId,
        String     payload,
        long       timestamp
) {
    public ShardedRecord {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(type,     "type");
        Objects.requireNonNull(eventId,  "eventId");
        if (deviceId.isBlank()) throw new IllegalArgumentException("deviceId must not be blank");
        if (eventId.isBlank())  throw new IllegalArgumentException("eventId must not be blank");
    }

    public ShardedRecord withSeqNum(long seq) {
        return new ShardedRecord(deviceId, seq, type, eventId, payload, timestamp);
    }
}
