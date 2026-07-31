package com.recsys.infrastructure.redis.sharding;

import com.recsys.infrastructure.redis.RedisExecutor;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.LettuceFutures;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.ZAddArgs;

import java.time.Duration;
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
    private static final Duration PIPELINE_TIMEOUT = Duration.ofSeconds(5);

    private final RedisExecutor writeExec;
    private final RedisExecutor readExec;
    private final ShardTopologyProvider provider;
    private final SequenceGenerator seqGen;
    private final String prefix;

    public ShardedRecordStore(RedisExecutor writeExec, RedisExecutor readExec,
                              ShardTopologyProvider provider,
                              SequenceGenerator seqGen, String prefix) {
        this.writeExec = Objects.requireNonNull(writeExec, "writeExec");
        this.readExec  = Objects.requireNonNull(readExec,  "readExec");
        this.provider  = Objects.requireNonNull(provider,  "provider");
        this.seqGen    = Objects.requireNonNull(seqGen,    "seqGen");
        this.prefix    = Objects.requireNonNull(prefix,    "prefix");
    }

    // Back-compat: fixed single-version topology from a ring.
    public ShardedRecordStore(RedisExecutor exec, ConsistentHashRing ring,
                              SequenceGenerator seqGen, String prefix) {
        this(exec, exec, ShardTopologyProvider.fixed(ring), seqGen, prefix);
    }

    public ShardedRecordStore(RedisExecutor writeExec, RedisExecutor readExec,
                              ConsistentHashRing ring, SequenceGenerator seqGen, String prefix) {
        this(writeExec, readExec, ShardTopologyProvider.fixed(ring), seqGen, prefix);
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
        ShardTopology topo = provider.current();
        int version    = topo.version();
        int shardIndex = topo.shardFor(record.deviceId());
        long seqNum    = seqGen.next(topo, shardIndex);

        String recKey    = recKey(version, shardIndex, seqNum);
        String devKey    = devKey(version, shardIndex, record.deviceId());
        String streamKey = streamKey(version, shardIndex);

        // Captured from inside the pipeline so we can inspect the ZADD result after awaitAll.
        @SuppressWarnings("unchecked")
        RedisFuture<Long>[] zaddHolder = new RedisFuture[1];

        writeExec.executePipelined(conn -> {
            var async = conn.async();
            List<RedisFuture<?>> futures = new ArrayList<>();

            futures.add(async.hset(recKey, Map.of(
                    "deviceId",  record.deviceId(),
                    "type",      record.type().name(),
                    "eventId",   record.eventId(),
                    "payload",   record.payload() != null ? record.payload() : "",
                    "timestamp", String.valueOf(record.timestamp())
            )));
            if (ttlSeconds > 0) futures.add(async.expire(recKey, ttlSeconds));

            RedisFuture<Long> zaddFuture = isUpdate
                    ? async.zadd(devKey, ZAddArgs.Builder.xx().gt(), (double) seqNum, record.eventId())
                    : async.zadd(devKey, ZAddArgs.Builder.nx(), (double) seqNum, record.eventId());
            zaddHolder[0] = zaddFuture;
            futures.add(zaddFuture);

            futures.add(async.xadd(streamKey,
                    XAddArgs.Builder.maxlen(STREAM_MAXLEN).approximateTrimming(),
                    Map.of(
                            "deviceId", record.deviceId(),
                            "seq",      String.valueOf(seqNum),
                            "type",     record.type().name(),
                            "eventId",  record.eventId()
                    )));

            conn.flushCommands();
            LettuceFutures.awaitAll(PIPELINE_TIMEOUT,
                    futures.toArray(new RedisFuture[0]));
        });

        Long zaddResult = awaitResult(zaddHolder[0]);
        long zadd = zaddResult == null ? 0L : zaddResult;

        WriteStatus status = (!isUpdate && zadd == 0L)
                ? WriteStatus.DUPLICATE : WriteStatus.OK;
        return new WriteResult(seqNum, shardIndex, status);
    }

    // ── Read ─────────────────────────────────────────────────────────────────────

    /**
     * Device-level read: dual-read across current + previous generation when a migration window
     * is active. Captures current and previousIfActive exactly once (avoids torn reads on a
     * mid-call topology shift). readShard remains current-generation only.
     */
    public Page<ShardedRecord> readDevice(String deviceId, ShardCursor cursor, int limit) {
        // Reject a stream-ID cursor here rather than letting Double.parseDouble throw below.
        cursor.requireKind(ShardCursor.Kind.SEQ);
        ShardTopology cur  = provider.current();
        ShardTopology prev = provider.previousIfActive();

        Page<ShardedRecord> currentPage = readDeviceAt(cur.version(), cur.shardFor(deviceId),
                deviceId, cursor, limit);

        if (prev == null) return currentPage;

        Page<ShardedRecord> prevPage = readDeviceAt(prev.version(), prev.shardFor(deviceId),
                deviceId, cursor, limit);
        return mergeDevicePages(currentPage, prevPage, limit);
    }

    // Single-generation device read (the former readDevice body, now version-scoped).
    private Page<ShardedRecord> readDeviceAt(int version, int shardIndex, String deviceId,
                                             ShardCursor cursor, int limit) {
        String devKey = devKey(version, shardIndex, deviceId);
        double minScore = cursor.isStart() ? Double.NEGATIVE_INFINITY
                                           : Double.parseDouble(cursor.value()) + 1;
        Range<Double> range = Range.create(minScore, Double.POSITIVE_INFINITY);
        Limit pageLimit = Limit.create(0, limit);
        List<ScoredValue<String>> tuples =
                readExec.executeRead(c -> c.zrangebyscoreWithScores(devKey, range, pageLimit));
        if (tuples.isEmpty()) return Page.empty();
        List<Long> seqNums = tuples.stream().map(t -> (long) t.getScore()).toList();
        List<ShardedRecord> records = fetchRecords(version, shardIndex, seqNums);
        long lastSeq = (long) tuples.get(tuples.size() - 1).getScore();
        ShardCursor next = tuples.size() < limit ? null : ShardCursor.seq(lastSeq);
        return new Page<>(records, next);
    }

    // Merge current + previous device records, dedupe by (deviceId, seqNum) preferring current,
    // sort by seqNum ascending, cap at `limit`. Cursor: current's cursor drives pagination
    // (previous-generation data is finite and TTLs out within the window).
    private Page<ShardedRecord> mergeDevicePages(Page<ShardedRecord> current,
                                                 Page<ShardedRecord> previous, int limit) {
        java.util.LinkedHashMap<String, ShardedRecord> byKey = new java.util.LinkedHashMap<>();
        for (ShardedRecord r : current.records()) byKey.put(r.deviceId() + ":" + r.seqNum(), r);
        for (ShardedRecord r : previous.records()) byKey.putIfAbsent(r.deviceId() + ":" + r.seqNum(), r);
        List<ShardedRecord> merged = new ArrayList<>(byKey.values());
        merged.sort(java.util.Comparator.comparingLong(ShardedRecord::seqNum));
        if (merged.size() > limit) merged = new ArrayList<>(merged.subList(0, limit));
        // Pagination is driven by the current generation's cursor; previous-generation records
        // beyond the first page are not paged — they self-heal when the migration window closes.
        return new Page<>(merged, current.next());
    }

    public Page<ShardedRecord> readShard(int shardIndex, ShardCursor cursor, int limit) {
        // Reject a sequence-number cursor here rather than letting XREAD fail on a bad ID.
        cursor.requireKind(ShardCursor.Kind.STREAM);
        int version = provider.current().version();
        String streamKey = streamKey(version, shardIndex);

        // Fetch limit+1 to detect whether more entries exist beyond this page.
        List<StreamMessage<String, String>> entries = readExec.executeRead(c -> c.xread(
                XReadArgs.Builder.count(limit + 1),
                XReadArgs.StreamOffset.from(streamKey, cursor.value())));
        if (entries == null || entries.isEmpty()) return Page.empty();

        boolean hasMore = entries.size() > limit;
        List<StreamMessage<String, String>> page = hasMore ? entries.subList(0, limit) : entries;

        List<Long> seqNums = page.stream()
                .map(e -> Long.parseLong(e.getBody().get("seq")))
                .toList();
        List<ShardedRecord> records = fetchRecords(version, shardIndex, seqNums);

        ShardCursor next = hasMore
                ? ShardCursor.stream(page.get(page.size() - 1).getId())
                : null;
        return new Page<>(records, next);
    }

    // Pipelined multi-HGETALL — one Redis round-trip for up to `limit` records.
    private List<ShardedRecord> fetchRecords(int version, int shardIndex, List<Long> seqNums) {
        if (seqNums.isEmpty()) return List.of();
        @SuppressWarnings("unchecked")
        RedisFuture<Map<String, String>>[] responses = new RedisFuture[seqNums.size()];
        readExec.executeReadPipelined(conn -> {
            var async = conn.async();
            for (int i = 0; i < seqNums.size(); i++) {
                responses[i] = async.hgetall(recKey(version, shardIndex, seqNums.get(i)));
            }
            conn.flushCommands();
            LettuceFutures.awaitAll(PIPELINE_TIMEOUT,
                    responses);
        });

        List<ShardedRecord> records = new ArrayList<>();
        for (int i = 0; i < seqNums.size(); i++) {
            Map<String, String> fields = awaitResult(responses[i]);
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

    // Reads a future that awaitAll has already completed; rethrows failures unchecked.
    private static <T> T awaitResult(RedisFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted awaiting Redis result", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("Redis pipeline command failed", e.getCause());
        }
    }

    // ── Key helpers ──────────────────────────────────────────────────────────────

    String recKey(int version, int shardIndex, long seqNum) {
        return prefix + Generations.keyPrefix(version) + "rec:" + shardIndex + ":" + seqNum;
    }

    String devKey(int version, int shardIndex, String deviceId) {
        return prefix + Generations.keyPrefix(version) + "dev:" + shardIndex + ":" + deviceId;
    }

    String streamKey(int version, int shardIndex) {
        return prefix + Generations.keyPrefix(version) + "stream:" + shardIndex;
    }
}
