package com.recsys.infrastructure.redis.sharding;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.params.ZAddParams;
import redis.clients.jedis.util.Pool;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Redis-backed sharded record store.
 *
 * Write path:
 *   1. INCR seq counter (separate round-trip — return value needed).
 *   2. Pipeline: HSET full record + ZADD NX/GT device index + XADD shard stream.
 *
 * Read paths implemented in Task 6.
 */
public final class ShardedRecordStore {

    private static final long STREAM_MAXLEN = 1_000_000L;

    private final Pool<Jedis> pool;
    private final ConsistentHashRing ring;
    private final SequenceGenerator seqGen;
    private final String prefix;

    public ShardedRecordStore(Pool<Jedis> pool, ConsistentHashRing ring,
                               SequenceGenerator seqGen, String prefix) {
        this.pool   = Objects.requireNonNull(pool,   "pool");
        this.ring   = Objects.requireNonNull(ring,   "ring");
        this.seqGen = Objects.requireNonNull(seqGen, "seqGen");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
    }

    // ── Write ────────────────────────────────────────────────────────────────────

    public WriteResult write(ShardedRecord record) {
        return doWrite(record, false, 0);
    }

    public WriteResult write(ShardedRecord record, int ttlSeconds) {
        return doWrite(record, false, ttlSeconds);
    }

    public WriteResult update(ShardedRecord record) {
        return doWrite(record, true, 0);
    }

    private WriteResult doWrite(ShardedRecord record, boolean isUpdate, int ttlSeconds) {
        int shardIndex = ring.shardFor(record.deviceId());
        long seqNum    = seqGen.next(shardIndex);

        String recKey    = recKey(shardIndex, seqNum);
        String devKey    = devKey(shardIndex, record.deviceId());
        String streamKey = streamKey(shardIndex);

        long zaddResult;
        try (Jedis jedis = pool.getResource()) {
            Pipeline pipe = jedis.pipelined();

            pipe.hset(recKey, Map.of(
                    "deviceId",  record.deviceId(),
                    "type",      record.type().name(),
                    "eventId",   record.eventId(),
                    "payload",   record.payload() != null ? record.payload() : "",
                    "timestamp", String.valueOf(record.timestamp())
            ));
            if (ttlSeconds > 0) pipe.expire(recKey, ttlSeconds);

            var zaddFuture = isUpdate
                    ? pipe.zadd(devKey, seqNum, record.eventId(),
                            ZAddParams.zAddParams().xx().gt())
                    : pipe.zadd(devKey, seqNum, record.eventId(),
                            ZAddParams.zAddParams().nx());

            pipe.xadd(streamKey,
                    XAddParams.xAddParams()
                            .id(StreamEntryID.NEW_ENTRY)
                            .maxLen(STREAM_MAXLEN)
                            .approximateTrimming(),
                    Map.of(
                            "deviceId", record.deviceId(),
                            "seq",      String.valueOf(seqNum),
                            "type",     record.type().name(),
                            "eventId",  record.eventId()
                    ));
            pipe.sync();

            zaddResult = (Long) zaddFuture.get();
        }

        WriteStatus status = (!isUpdate && zaddResult == 0L)
                ? WriteStatus.DUPLICATE : WriteStatus.OK;
        return new WriteResult(seqNum, shardIndex, status);
    }

    // ── Read — stubs, implemented in Task 6 ─────────────────────────────────────

    public Page<ShardedRecord> readDevice(String deviceId, ShardCursor cursor, int limit) {
        throw new UnsupportedOperationException("implemented in Task 6");
    }

    public Page<ShardedRecord> readShard(int shardIndex, ShardCursor cursor, int limit) {
        throw new UnsupportedOperationException("implemented in Task 6");
    }

    public List<Page<ShardedRecord>> readAllShards(ShardCursor cursor, int limitPerShard) {
        throw new UnsupportedOperationException("implemented in Task 6");
    }

    // ── Key helpers ──────────────────────────────────────────────────────────────

    String recKey(int shardIndex, long seqNum) {
        return prefix + "rec:" + shardIndex + ":" + seqNum;
    }

    String devKey(int shardIndex, String deviceId) {
        return prefix + "dev:" + shardIndex + ":" + deviceId;
    }

    String streamKey(int shardIndex) {
        return prefix + "stream:" + shardIndex;
    }
}
