package com.recsys.infrastructure.redis.sharding;

import com.recsys.infrastructure.redis.RedisExecutor;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScoredValue;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * Assigns shard-scoped monotonic sequence numbers via Redis INCR.
 *
 * Each shard has its own counter at {prefix}{generation}seq:{shardIndex}.
 * Sequence numbers are shard-scoped (not globally unique across shards).
 */
public final class SequenceGenerator {

    private final RedisExecutor exec;
    private final String prefix;
    private final LongSupplier clockMs;

    public SequenceGenerator(RedisExecutor exec, String prefix) {
        this(exec, prefix, System::currentTimeMillis);
    }

    SequenceGenerator(RedisExecutor exec, String prefix, LongSupplier clockMs) {
        this.exec    = exec;
        this.prefix  = prefix;
        this.clockMs = clockMs;
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
     * Guards against a stale counter after a Redis partial flush: a counter behind the
     * highest sequence number still present reissues that number, the device index's
     * {@code ZADD NX} becomes a no-op, and the record is silently dropped.
     *
     * <p>Scans the device ZSets of one shard <em>in the given topology generation</em> and
     * raises the counter to {@code max(score) + 1} when it is behind. The counter is only
     * ever raised, never lowered.
     *
     * <p>The scan is bounded by {@code budgetMs} of wall-clock time. A truncated scan can
     * only <em>under</em>-estimate the true maximum, so a partial run degrades to less
     * repair rather than to a wrong counter — which is why exceeding the budget is a
     * warning, not a failure.
     *
     * <p>Expensive: one SCAN pass plus a ZREVRANGEBYSCORE per device key. Call it off the
     * request path and off the startup thread.
     *
     * @return {@code true} if the scan completed, {@code false} if the budget truncated it
     */
    public boolean ensureCounterValid(int version, int shardIndex, long budgetMs) {
        ScanResult scan = findMaxSeqInShard(version, shardIndex, budgetMs);
        if (scan.maxSeq() <= 0) return scan.completed();

        String key = seqKey(version, shardIndex);
        String current = exec.execute(c -> c.get(key));
        long currentVal = current == null ? 0L : Long.parseLong(current);
        if (currentVal < scan.maxSeq()) {
            exec.execute(c -> c.set(key, String.valueOf(scan.maxSeq() + 1)));
        }
        return scan.completed();
    }

    private ScanResult findMaxSeqInShard(int version, int shardIndex, long budgetMs) {
        String pattern = prefix + Generations.keyPrefix(version) + "dev:" + shardIndex + ":*";
        ScanArgs params = ScanArgs.Builder.matches(pattern).limit(200);
        long deadline = clockMs.getAsLong() + Math.max(1L, budgetMs);

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
                if (cursor.isFinished()) return new ScanResult(maxSeq, true);
                if (clockMs.getAsLong() >= deadline) return new ScanResult(maxSeq, false);
                cursor = c.scan(cursor, params);
            }
        });
    }

    private String seqKey(int version, int shardIndex) {
        return prefix + Generations.keyPrefix(version) + "seq:" + shardIndex;
    }

    /** Outcome of one bounded scan: the highest score seen, and whether the scan finished. */
    private record ScanResult(long maxSeq, boolean completed) {}
}
