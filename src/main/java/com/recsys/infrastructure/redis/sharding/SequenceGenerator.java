package com.recsys.infrastructure.redis.sharding;

import com.recsys.infrastructure.redis.RedisExecutor;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScoredValue;

import java.util.List;

/**
 * Assigns shard-scoped monotonic sequence numbers via Redis INCR.
 *
 * Each shard has its own counter at {prefix}seq:{shardIndex}.
 * Sequence numbers are shard-scoped (not globally unique across shards).
 */
public final class SequenceGenerator {

    private final RedisExecutor exec;
    private final String prefix;

    public SequenceGenerator(RedisExecutor exec, String prefix) {
        this.exec   = exec;
        this.prefix = prefix;
    }

    /** Next sequence for (version, shard). Always >= 1. */
    public long next(int version, int shardIndex) {
        return exec.execute(c -> c.incr(seqKey(version, shardIndex)));
    }

    /** Back-compat: version-1 (unversioned) sequence. */
    public long next(int shardIndex) {
        return next(1, shardIndex);
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

        String key = seqKey(1, shardIndex);
        String current = exec.execute(c -> c.get(key));
        long currentVal = current == null ? 0L : Long.parseLong(current);
        if (currentVal < maxSeq) {
            exec.execute(c -> c.set(key, String.valueOf(maxSeq + 1)));
        }
    }

    private long findMaxSeqInShard(int shardIndex) {
        String pattern = prefix + "dev:" + shardIndex + ":*";
        ScanArgs params = ScanArgs.Builder.matches(pattern).limit(200);

        return exec.execute(c -> {
            long maxSeq = 0L;
            KeyScanCursor<String> cursor = c.scan(params);
            while (true) {
                for (String devKey : cursor.getKeys()) {
                    List<ScoredValue<String>> top = c.zrevrangebyscoreWithScores(
                            devKey, Range.unbounded(), Limit.create(0, 1));
                    if (!top.isEmpty()) {
                        maxSeq = Math.max(maxSeq, (long) top.get(0).getScore());
                    }
                }
                if (cursor.isFinished()) break;
                cursor = c.scan(cursor, params);
            }
            return maxSeq;
        });
    }

    private String seqKey(int version, int shardIndex) {
        return prefix + Generations.keyPrefix(version) + "seq:" + shardIndex;
    }
}
