package com.recsys.infrastructure.redis.sharding;

public record WriteResult(long seqNum, int shardIndex, WriteStatus status) {
    public boolean isDuplicate() { return status == WriteStatus.DUPLICATE; }
}
