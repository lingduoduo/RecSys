package com.recsys.infrastructure.redis.sharding;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import redis.clients.jedis.util.Pool;

import java.util.List;

/**
 * Assigns shard-scoped monotonic sequence numbers via Redis INCR.
 *
 * Each shard has its own counter at {prefix}seq:{shardIndex}.
 * Sequence numbers are shard-scoped (not globally unique across shards).
 */
public final class SequenceGenerator {

    private final Pool<Jedis> pool;
    private final String prefix;

    public SequenceGenerator(Pool<Jedis> pool, String prefix) {
        this.pool   = pool;
        this.prefix = prefix;
    }

    /** Returns the next sequence number for the given shard. Always >= 1. */
    public long next(int shardIndex) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.incr(seqKey(shardIndex));
        }
    }

    /**
     * Guards against a stale counter after a Redis partial flush.
     * Scans all device ZSets for the shard and resets the counter to max(score)+1
     * unconditionally if the current counter is lower.
     *
     * Call once at startup per shard before accepting writes.
     */
    public void ensureCounterValid(int shardIndex, int shardCount) {
        long maxSeq = findMaxSeqInShard(shardIndex);
        if (maxSeq <= 0) return;

        try (Jedis jedis = pool.getResource()) {
            String key = seqKey(shardIndex);
            String current = jedis.get(key);
            long currentVal = current == null ? 0L : Long.parseLong(current);
            if (currentVal < maxSeq) {
                jedis.set(key, String.valueOf(maxSeq + 1));
            }
        }
    }

    private long findMaxSeqInShard(int shardIndex) {
        String pattern = prefix + "dev:" + shardIndex + ":*";
        ScanParams params = new ScanParams().match(pattern).count(200);
        long maxSeq = 0L;

        try (Jedis jedis = pool.getResource()) {
            String cursor = "0";
            do {
                ScanResult<String> result = jedis.scan(cursor, params);
                for (String devKey : result.getResult()) {
                    List<String> top = jedis.zrevrangeByScore(devKey, "+inf", "-inf", 0, 1);
                    if (!top.isEmpty()) {
                        Double score = jedis.zscore(devKey, top.get(0));
                        if (score != null) maxSeq = Math.max(maxSeq, score.longValue());
                    }
                }
                cursor = result.getCursor();
            } while (!"0".equals(cursor));
        }
        return maxSeq;
    }

    private String seqKey(int shardIndex) {
        return prefix + "seq:" + shardIndex;
    }
}
