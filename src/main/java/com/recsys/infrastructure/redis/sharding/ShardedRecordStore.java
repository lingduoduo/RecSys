package com.recsys.infrastructure.redis.sharding;

import com.recsys.infrastructure.redis.RedisExecutor;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.LettuceFutures;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XReadArgs;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Redis-backed sharded record store.
 *
 * Write path: one Lua script per write — INCR the shard's sequence counter, claim the device
 * index, store the record hash, and append to the shard stream. Redis runs the script in
 * isolation, so no other client ever observes a partial write and no client-side or network
 * failure can interleave between the commands. That is isolation, not a transaction: a
 * {@code redis.call} that errors mid-script aborts with the preceding writes already applied,
 * and there is no rollback.
 *
 * Read paths: per-device reads dual-read the current and previous generation during a
 * migration window; shard reads are current-generation only.
 */
public final class ShardedRecordStore {

    private static final long STREAM_MAXLEN = 1_000_000L;
    private static final Duration PIPELINE_TIMEOUT = Duration.ofSeconds(5);

    /**
     * One write in one script: assign the sequence, claim the device index, then store the
     * record and append to the stream. Returns {seq, zaddResult}. Redis's script isolation is
     * what this buys — no other client sees the write half-done, and nothing can fail between
     * the commands. It is not a transaction: an erroring {@code redis.call} aborts the script
     * with the earlier writes already applied and no rollback.
     *
     * <p>On an insert whose event ID is already indexed, the script returns before writing
     * anything else — a retry therefore neither burns a record key nor appends a duplicate
     * stream entry. On an update it always proceeds, because ZADD XX GT legitimately returns 0
     * for a non-advancing score.
     *
     * <p>The record key is built inside the script because its sequence number does not exist
     * until the INCR runs. Under the tagged key format every key here shares one hash tag, so
     * the constructed key lands in the same Cluster slot as the declared ones.
     */
    private static final String WRITE_RECORD_LUA = """
            local seq = redis.call('INCR', KEYS[1])
            local isUpdate = ARGV[7] == '1'
            local zadd
            if isUpdate then
              zadd = redis.call('ZADD', KEYS[2], 'XX', 'GT', seq, ARGV[3])
            else
              zadd = redis.call('ZADD', KEYS[2], 'NX', seq, ARGV[3])
              if zadd == 0 then return {seq, 0} end
            end
            -- Lua renders seq with %.14g: at seq >= 1e14 this emits '1e+14' and the record key
            -- is malformed. Unreachable in practice, and silent if it ever were reached.
            local recKey = ARGV[1] .. seq
            redis.call('HSET', recKey,
              'deviceId', ARGV[2], 'type', ARGV[8], 'eventId', ARGV[3],
              'payload', ARGV[4], 'timestamp', ARGV[5])
            local ttl = tonumber(ARGV[6])
            if ttl > 0 then redis.call('EXPIRE', recKey, ttl) end
            redis.call('XADD', KEYS[3], 'MAXLEN', '~', ARGV[9], '*',
              'deviceId', ARGV[2], 'seq', seq, 'type', ARGV[8], 'eventId', ARGV[3])
            return {seq, zadd}
            """;

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
        int shardIndex = topo.shardFor(record.deviceId());
        ShardKeys keys = ShardKeys.of(prefix, topo);

        List<Long> result = writeExec.execute(c -> c.eval(WRITE_RECORD_LUA, ScriptOutputType.MULTI,
                new String[]{
                        keys.seq(shardIndex),
                        keys.dev(shardIndex, record.deviceId()),
                        keys.stream(shardIndex)},
                keys.recPrefix(shardIndex),
                record.deviceId(),
                record.eventId(),
                record.payload() != null ? record.payload() : "",
                String.valueOf(record.timestamp()),
                Integer.toString(ttlSeconds),
                isUpdate ? "1" : "0",
                record.type().name(),
                Long.toString(STREAM_MAXLEN)));

        long seqNum = result.get(0);
        long zadd   = result.get(1);
        // On DUPLICATE the sequence was still consumed by the INCR but the script wrote nothing,
        // so this seqNum names a record key that does not and never will exist. Callers
        // (ShardedRecordService among them) receive it anyway; treat it as a burnt number, not a
        // handle. Before the script it resolved to an orphaned record hash instead.
        WriteStatus status = (!isUpdate && zadd == 0L) ? WriteStatus.DUPLICATE : WriteStatus.OK;
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

        Page<ShardedRecord> currentPage = readDeviceAt(ShardKeys.of(prefix, cur),
                cur.shardFor(deviceId), deviceId, cursor, limit);

        if (prev == null) return currentPage;

        Page<ShardedRecord> prevPage = readDeviceAt(ShardKeys.of(prefix, prev),
                prev.shardFor(deviceId), deviceId, cursor, limit);
        return mergeDevicePages(currentPage, prevPage, limit);
    }

    // Single-generation device read (the former readDevice body, now generation-scoped).
    private Page<ShardedRecord> readDeviceAt(ShardKeys keys, int shardIndex, String deviceId,
                                             ShardCursor cursor, int limit) {
        String devKey = keys.dev(shardIndex, deviceId);
        double minScore = cursor.isStart() ? Double.NEGATIVE_INFINITY
                                           : Double.parseDouble(cursor.value()) + 1;
        Range<Double> range = Range.create(minScore, Double.POSITIVE_INFINITY);
        Limit pageLimit = Limit.create(0, limit);
        List<ScoredValue<String>> tuples =
                readExec.executeRead(c -> c.zrangebyscoreWithScores(devKey, range, pageLimit));
        if (tuples.isEmpty()) return Page.empty();
        List<Long> seqNums = tuples.stream().map(t -> (long) t.getScore()).toList();
        List<ShardedRecord> records = fetchRecords(keys, shardIndex, seqNums);
        long lastSeq = (long) tuples.get(tuples.size() - 1).getScore();
        ShardCursor next = tuples.size() < limit ? null : ShardCursor.seq(lastSeq);
        return new Page<>(records, next);
    }

    // Merge current + previous device records, dedupe by (deviceId, eventId) preferring current,
    // sort by seqNum ascending, cap at `limit`. eventId — not seqNum — is the identity: sequence
    // counters are per generation, so both generations issue the same numbers and deduping on
    // seqNum would silently drop previous-generation records during a migration window.
    private Page<ShardedRecord> mergeDevicePages(Page<ShardedRecord> current,
                                                 Page<ShardedRecord> previous, int limit) {
        java.util.LinkedHashMap<String, ShardedRecord> byKey = new java.util.LinkedHashMap<>();
        for (ShardedRecord r : current.records()) byKey.put(r.deviceId() + ":" + r.eventId(), r);
        for (ShardedRecord r : previous.records()) byKey.putIfAbsent(r.deviceId() + ":" + r.eventId(), r);
        List<ShardedRecord> merged = new ArrayList<>(byKey.values());
        // Ordering across generations is approximate for the same reason: two generations can
        // issue equal sequence numbers, so a merged page is not a strict chronological sequence.
        // Within one generation it is exact, and previous-generation data TTLs out of the window.
        merged.sort(java.util.Comparator.comparingLong(ShardedRecord::seqNum));
        if (merged.size() > limit) merged = new ArrayList<>(merged.subList(0, limit));
        // Pagination is driven by the current generation's cursor; previous-generation records
        // beyond the first page are not paged — they self-heal when the migration window closes.
        return new Page<>(merged, current.next());
    }

    public Page<ShardedRecord> readShard(int shardIndex, ShardCursor cursor, int limit) {
        // Reject a sequence-number cursor here rather than letting XREAD fail on a bad ID.
        cursor.requireKind(ShardCursor.Kind.STREAM);
        ShardKeys keys = ShardKeys.of(prefix, provider.current());
        String streamKey = keys.stream(shardIndex);

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
        List<ShardedRecord> records = fetchRecords(keys, shardIndex, seqNums);

        ShardCursor next = hasMore
                ? ShardCursor.stream(page.get(page.size() - 1).getId())
                : null;
        return new Page<>(records, next);
    }

    // Pipelined multi-HGETALL — one Redis round-trip for up to `limit` records.
    private List<ShardedRecord> fetchRecords(ShardKeys keys, int shardIndex, List<Long> seqNums) {
        if (seqNums.isEmpty()) return List.of();
        @SuppressWarnings("unchecked")
        RedisFuture<Map<String, String>>[] responses = new RedisFuture[seqNums.size()];
        readExec.executeReadPipelined(conn -> {
            var async = conn.async();
            for (int i = 0; i < seqNums.size(); i++) {
                responses[i] = async.hgetall(keys.rec(shardIndex, seqNums.get(i)));
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
}
