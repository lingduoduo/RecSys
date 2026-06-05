package com.recsys.infrastructure.redis.sharding;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.params.XReadParams;
import redis.clients.jedis.params.ZAddParams;
import redis.clients.jedis.resps.StreamEntry;
import redis.clients.jedis.resps.Tuple;
import redis.clients.jedis.util.Pool;

import java.util.ArrayList;
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

    // ── Read ─────────────────────────────────────────────────────────────────────

    public Page<ShardedRecord> readDevice(String deviceId, ShardCursor cursor, int limit) {
        int shardIndex = ring.shardFor(deviceId);
        String devKey  = devKey(shardIndex, deviceId);
        double minScore = cursor.isStart() ? Double.NEGATIVE_INFINITY
                                           : Double.parseDouble(cursor.value()) + 1;

        List<Tuple> tuples;
        try (Jedis jedis = pool.getResource()) {
            tuples = jedis.zrangeByScoreWithScores(devKey, minScore, Double.POSITIVE_INFINITY, 0, limit);
        }
        if (tuples.isEmpty()) return Page.empty();

        List<Long> seqNums = tuples.stream()
                .map(t -> (long) t.getScore())
                .toList();
        List<ShardedRecord> records = fetchRecords(shardIndex, seqNums);

        // Use tuples.size() (pre-TTL-filter) to decide hasMore: if Redis returned a full
        // page of ZSet entries, more may exist even if some HGETALL results were expired.
        long lastSeq = (long) tuples.get(tuples.size() - 1).getScore();
        ShardCursor next = tuples.size() < limit ? null : ShardCursor.of(String.valueOf(lastSeq));
        return new Page<>(records, next);
    }

    public Page<ShardedRecord> readShard(int shardIndex, ShardCursor cursor, int limit) {
        String streamKey = streamKey(shardIndex);

        // Fetch limit+1 to detect whether more entries exist beyond this page.
        List<StreamEntry> entries;
        try (Jedis jedis = pool.getResource()) {
            StreamEntryID fromId = new StreamEntryID(cursor.value());
            List<Map.Entry<String, List<StreamEntry>>> result = jedis.xread(
                    XReadParams.xReadParams().count(limit + 1),
                    Map.of(streamKey, fromId));
            entries = (result == null || result.isEmpty()) ? List.of()
                    : result.get(0).getValue();
        }
        if (entries.isEmpty()) return Page.empty();

        boolean hasMore = entries.size() > limit;
        List<StreamEntry> page = hasMore ? entries.subList(0, limit) : entries;

        List<Long> seqNums = page.stream()
                .map(e -> Long.parseLong(e.getFields().get("seq")))
                .toList();
        List<ShardedRecord> records = fetchRecords(shardIndex, seqNums);

        ShardCursor next = hasMore
                ? ShardCursor.of(page.get(page.size() - 1).getID().toString())
                : null;
        return new Page<>(records, next);
    }

    public List<Page<ShardedRecord>> readAllShards(ShardCursor cursor, int limitPerShard) {
        List<Page<ShardedRecord>> pages = new ArrayList<>();
        for (int i = 0; i < ring.shardCount(); i++) {
            // Drain the shard fully, merging all pages into one result Page.
            List<ShardedRecord> all = new ArrayList<>();
            ShardCursor cur = cursor;
            Page<ShardedRecord> p;
            do {
                p = readShard(i, cur, limitPerShard);
                all.addAll(p.records());
                cur = p.next();
            } while (p.hasMore());
            pages.add(new Page<>(all, null));
        }
        return pages;
    }

    // Pipelined multi-HGETALL — one Redis round-trip for up to `limit` records.
    private List<ShardedRecord> fetchRecords(int shardIndex, List<Long> seqNums) {
        if (seqNums.isEmpty()) return List.of();
        List<Response<Map<String, String>>> responses = new ArrayList<>();
        try (Jedis jedis = pool.getResource(); Pipeline pipe = jedis.pipelined()) {
            for (long seq : seqNums) responses.add(pipe.hgetAll(recKey(shardIndex, seq)));
            pipe.sync();
        }

        List<ShardedRecord> records = new ArrayList<>();
        for (int i = 0; i < seqNums.size(); i++) {
            Map<String, String> fields = responses.get(i).get();
            if (fields == null || fields.isEmpty()) continue; // TTL expired — skip
            records.add(new ShardedRecord(
                    fields.get("deviceId"),
                    seqNums.get(i),
                    RecordType.valueOf(fields.get("type")),
                    fields.get("eventId"),
                    fields.get("payload"),
                    Long.parseLong(fields.get("timestamp"))
            ));
        }
        return records;
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
