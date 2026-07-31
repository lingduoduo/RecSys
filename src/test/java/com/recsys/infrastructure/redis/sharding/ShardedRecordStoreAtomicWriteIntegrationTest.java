package com.recsys.infrastructure.redis.sharding;

import io.lettuce.core.Range;
import io.lettuce.core.StreamMessage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The write script against a real Redis, on a <em>tagged</em> generation.
 *
 * <p>{@code ShardedRecordStoreAtomicWriteTest} mocks {@code eval} and can only assert what the
 * store sends; it cannot execute a line of Lua. Every other Docker test in this package predates
 * the script and builds an untagged topology, so before this class the script had never run
 * against the tagged key format at all — and the tagged format is what makes a multi-key script
 * legal on Redis Cluster. Everything here therefore uses {@link ShardKeys#FORMAT_TAGGED} except
 * {@link #deviceReadMergesAnUntaggedPreviousWithATaggedCurrentGeneration}, which deliberately
 * mixes the two.
 */
@Tag("docker")
class ShardedRecordStoreAtomicWriteIntegrationTest extends RedisShardingTestBase {

    private static final String PREFIX = "sr:";
    private static final int VERSION = 3;
    private static final int SHARDS = 4;
    private static final int VNODES = 150;

    /** The generation every test but the migration one writes to: version 3, tagged. */
    private static final ShardTopology TAGGED =
            new ShardTopology(VERSION, SHARDS, VNODES, 0L, ShardKeys.FORMAT_TAGGED);
    private static final ShardKeys KEYS = ShardKeys.of(PREFIX, TAGGED);

    private ShardedRecordStore storeOn(ShardTopologyProvider provider) {
        return new ShardedRecordStore(exec, exec, provider, new SequenceGenerator(exec, PREFIX), PREFIX);
    }

    private ShardedRecordStore taggedStore() {
        return storeOn(ShardTopologyProvider.fixedAtVersion(
                VERSION, SHARDS, VNODES, ShardKeys.FORMAT_TAGGED));
    }

    private static List<StreamMessage<String, String>> stream(int shardIndex) {
        return cmd().xrange(KEYS.stream(shardIndex), Range.create("-", "+"));
    }

    @Test
    void writeStoresRecordIndexAndStreamTogether() {
        // Given a tagged generation, when a record is written, then all three structures exist
        // and agree on the sequence number the script assigned.
        ShardedRecordStore store = taggedStore();
        ShardedRecord input = new ShardedRecord("dev-together", 0, RecordType.EVENT,
                "evt-together", "{\"movieId\":7}", 1_700_000_000_000L);

        WriteResult wr = store.write(input);
        int shard = wr.shardIndex();

        assertThat(wr.status()).isEqualTo(WriteStatus.OK);
        assertThat(wr.seqNum()).isEqualTo(1L);

        // The keys really are tagged — the whole point of the format work.
        String recKey = KEYS.rec(shard, wr.seqNum());
        assertThat(recKey).isEqualTo("sr:g3:rec:{" + shard + "}:1");
        assertThat(KEYS.dev(shard, "dev-together")).isEqualTo("sr:g3:dev:{" + shard + "}:dev-together");
        assertThat(KEYS.stream(shard)).isEqualTo("sr:g3:stream:{" + shard + "}");

        // 1. The record hash, at exactly the key ShardKeys.rec() names.
        assertThat(cmd().hgetall(recKey))
                .containsEntry("deviceId", "dev-together")
                .containsEntry("eventId", "evt-together")
                .containsEntry("payload", "{\"movieId\":7}");

        // 2. The device index, scored with the same sequence.
        assertThat(cmd().zscore(KEYS.dev(shard, "dev-together"), "evt-together"))
                .isEqualTo((double) wr.seqNum());

        // 3. The shard stream, carrying the same sequence.
        List<StreamMessage<String, String>> entries = stream(shard);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getBody()).containsEntry("seq", Long.toString(wr.seqNum()));

        // 4. The counter the script INCRed is the tagged one, left at the assigned sequence.
        assertThat(cmd().get(KEYS.seq(shard))).isEqualTo(Long.toString(wr.seqNum()));

        // And the store reads back what the script wrote.
        assertThat(store.readDevice("dev-together", ShardCursor.start(), 10).records())
                .extracting(ShardedRecord::eventId).containsExactly("evt-together");
    }

    @Test
    void duplicateInsertWritesNothingBeyondTheFirst() {
        // Write the same eventId twice. Assert: status DUPLICATE on the second, the stream has
        // exactly one entry, and no record key exists at the second INCR's sequence number.
        ShardedRecordStore store = taggedStore();

        WriteResult first = store.write(new ShardedRecord("dev-dup", 0, RecordType.EVENT,
                "evt-dup", "first-payload", 1L));
        WriteResult second = store.write(new ShardedRecord("dev-dup", 0, RecordType.EVENT,
                "evt-dup", "second-payload", 2L));
        int shard = first.shardIndex();

        assertThat(first.status()).isEqualTo(WriteStatus.OK);
        assertThat(second.status()).isEqualTo(WriteStatus.DUPLICATE);
        // The sequence is still consumed: the INCR runs before the ZADD NX that rejects.
        assertThat(second.seqNum()).isEqualTo(first.seqNum() + 1);
        assertThat(cmd().get(KEYS.seq(shard))).isEqualTo(Long.toString(second.seqNum()));

        // Nothing beyond the first write landed: no second record hash, ...
        assertThat(cmd().exists(KEYS.rec(shard, second.seqNum()))).isZero();
        // ... the first record was not overwritten, ...
        assertThat(cmd().hget(KEYS.rec(shard, first.seqNum()), "payload")).isEqualTo("first-payload");
        // ... no second stream entry, ...
        assertThat(cmd().xlen(KEYS.stream(shard))).isEqualTo(1L);
        assertThat(stream(shard).get(0).getBody())
                .containsEntry("seq", Long.toString(first.seqNum()));
        // ... and the index still holds one member, still at the first sequence.
        String devKey = KEYS.dev(shard, "dev-dup");
        assertThat(cmd().zcard(devKey)).isEqualTo(1L);
        assertThat(cmd().zscore(devKey, "evt-dup")).isEqualTo((double) first.seqNum());
    }

    @Test
    void nonAdvancingUpdateStillRefreshesTheRecordAndAppendsToTheStream() {
        // update() with a score that does not advance must leave the hash refreshed and add a
        // stream entry — the behavior ZADD XX GT preserves.
        ShardedRecordStore store = taggedStore();
        String device = "dev-nonadvancing";
        int shard = TAGGED.shardFor(device);
        String devKey = KEYS.dev(shard, device);

        // Seed the index at a score the next sequence cannot beat, so ZADD XX GT returns 0
        // *and* leaves the score alone. That is the non-advancing update, not a duplicate.
        cmd().zadd(devKey, 500.0, "evt-update");

        WriteResult wr = store.update(new ShardedRecord(device, 0, RecordType.FEATURE,
                "evt-update", "refreshed-payload", 99L));

        assertThat(wr.seqNum()).isEqualTo(1L);          // the counter started from scratch
        assertThat(wr.status()).isEqualTo(WriteStatus.OK); // XX GT returning 0 is NOT a duplicate
        // GT kept the higher score: the index was not rewound.
        assertThat(cmd().zscore(devKey, "evt-update")).isEqualTo(500.0);

        // The hash was still written, at the new sequence.
        Map<String, String> hash = cmd().hgetall(KEYS.rec(shard, wr.seqNum()));
        assertThat(hash)
                .containsEntry("deviceId", device)
                .containsEntry("type", "FEATURE")
                .containsEntry("eventId", "evt-update")
                .containsEntry("payload", "refreshed-payload")
                .containsEntry("timestamp", "99");

        // And a stream entry was still appended.
        List<StreamMessage<String, String>> entries = stream(shard);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getBody())
                .containsEntry("seq", Long.toString(wr.seqNum()))
                .containsEntry("eventId", "evt-update");
    }

    @Test
    void deviceReadMergesAnUntaggedPreviousWithATaggedCurrentGeneration() {
        // Write under a format-1 generation, publish a reshard (which writes format 2), write
        // again, and assert readDevice returns both records.
        String device = "dev-migrating";
        String topologyKey = "shard:topology:migration";

        // A generation-1 document written before keyFormat existed — so its keys are untagged.
        cmd().set(topologyKey, "{\"version\":1,\"shardCount\":2,\"vnodes\":150,\"createdAtMs\":0}");

        ShardTopology v1 = new ShardTopology(1, 2, VNODES, 0L, ShardKeys.FORMAT_UNTAGGED);
        ShardKeys v1Keys = ShardKeys.of(PREFIX, v1);

        WriteResult old = storeOn(ShardTopologyProvider.fixedAtVersion(
                1, 2, VNODES, ShardKeys.FORMAT_UNTAGGED))
                .write(new ShardedRecord(device, 0, RecordType.EVENT, "evt-old", "old", 1L));

        assertThat(old.seqNum()).isEqualTo(1L);
        String oldRecKey = v1Keys.rec(old.shardIndex(), old.seqNum());
        assertThat(oldRecKey).doesNotContain("{").isEqualTo("sr:rec:" + old.shardIndex() + ":1");
        assertThat(cmd().exists(oldRecKey)).isEqualTo(1L);

        // Publish the reshard: version 2, tagged, with untagged version 1 as previous.
        ShardTopologyStore topoStore = new ShardTopologyStore(exec, topologyKey);
        ShardTopologyStore.Snapshot v2 = topoStore.publishReshard(4, 1_000L, 60_000L);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.effectiveKeyFormat()).isEqualTo(ShardKeys.FORMAT_TAGGED);
        assertThat(v2.effectivePrevKeyFormat()).isEqualTo(ShardKeys.FORMAT_UNTAGGED);

        // A provider reading that document, with a clock inside the dual-read window.
        ShardTopologyProvider migrating =
                new ShardTopologyProvider(topoStore, VNODES, 4, 0L, () -> 2_000L);
        migrating.start();
        assertThat(migrating.current().keyFormat()).isEqualTo(ShardKeys.FORMAT_TAGGED);
        assertThat(migrating.previousIfActive()).isNotNull();
        assertThat(migrating.previousIfActive().keyFormat()).isEqualTo(ShardKeys.FORMAT_UNTAGGED);

        ShardedRecordStore store = storeOn(migrating);
        WriteResult fresh = store.write(
                new ShardedRecord(device, 0, RecordType.EVENT, "evt-new", "new", 2L));

        ShardKeys v2Keys = ShardKeys.of(PREFIX, migrating.current());
        String newRecKey = v2Keys.rec(fresh.shardIndex(), fresh.seqNum());
        assertThat(newRecKey)
                .contains("{" + fresh.shardIndex() + "}")
                .isEqualTo("sr:g2:rec:{" + fresh.shardIndex() + "}:" + fresh.seqNum());
        assertThat(cmd().exists(newRecKey)).isEqualTo(1L);

        // The dual read spans the format change: one untagged keyspace, one tagged.
        assertThat(store.readDevice(device, ShardCursor.start(), 10).records())
                .extracting(ShardedRecord::eventId)
                .containsExactlyInAnyOrder("evt-old", "evt-new");
    }

    @Test
    void deviceReadKeepsBothGenerationsWhenSequenceNumbersCollide() {
        // Sequence counters are per generation, so both generations independently issue seq 1
        // for the same device. Deduping on (deviceId, seqNum) would drop the older record.
        // Deliberately does NOT seed the counters apart — the collision is the point.
        String device = "dev-collide";
        String topologyKey = "shard:topology:collision";

        // Generation 1: bootstrapped fresh, so it is tagged like the rest of this class.
        ShardTopologyStore topoStore = new ShardTopologyStore(exec, topologyKey);
        ShardTopologyStore.Snapshot v1 = topoStore.bootstrap(2, VNODES, 0L);
        assertThat(v1.version()).isEqualTo(1);
        assertThat(v1.effectiveKeyFormat()).isEqualTo(ShardKeys.FORMAT_TAGGED);

        WriteResult old = storeOn(ShardTopologyProvider.fixedAtVersion(
                1, 2, VNODES, ShardKeys.FORMAT_TAGGED))
                .write(new ShardedRecord(device, 0, RecordType.EVENT, "evt-gen1", "old", 1L));
        assertThat(old.seqNum()).isEqualTo(1L);

        // Reshard to generation 2, with generation 1 still inside the dual-read window.
        ShardTopologyStore.Snapshot v2 = topoStore.publishReshard(4, 1_000L, 60_000L);
        assertThat(v2.version()).isEqualTo(2);

        ShardTopologyProvider migrating =
                new ShardTopologyProvider(topoStore, VNODES, 4, 0L, () -> 2_000L);
        migrating.start();
        assertThat(migrating.previousIfActive()).isNotNull();

        WriteResult fresh = storeOn(migrating)
                .write(new ShardedRecord(device, 0, RecordType.EVENT, "evt-gen2", "new", 2L));

        // The collision itself: generation 2's counter also started at 1, so the two records
        // carry the same sequence number under the same deviceId. If this assertion ever fails,
        // the test is no longer exercising the collision and the read assertion below is vacuous.
        assertThat(fresh.seqNum()).isEqualTo(old.seqNum());
        assertThat(cmd().get(ShardKeys.of(PREFIX, migrating.current()).seq(fresh.shardIndex())))
                .isEqualTo("1");

        // Both records exist; the dual read must return both, not silently drop the older one.
        assertThat(storeOn(migrating).readDevice(device, ShardCursor.start(), 10).records())
                .extracting(ShardedRecord::eventId)
                .containsExactlyInAnyOrder("evt-gen1", "evt-gen2");
    }

    @Test
    void pagingADualReadWindowReturnsEveryRecordAcrossPages() {
        // Both generations hold more than one page of records for the same device, at overlapping
        // sequence numbers (each generation's counter independently issues 1..6). Paging
        // readDevice to exhaustion must return every record exactly once.
        //
        // Before the fix the merged first page returned the four LOWEST sequences but handed back
        // the current generation's HIGHEST returned sequence as the cursor, so the next page
        // started above records the page never returned: g1-3, g1-4, g2-3 and g2-4 were returned
        // by no page at all.
        String device = "dev-paging";
        int limit = 4;
        ShardTopologyProvider migrating = twoGenerations(device, "shard:topology:paging", 6, 6);

        List<String> everythingWritten = new ArrayList<>();
        for (int i = 1; i <= 6; i++) everythingWritten.add("g1-" + i);
        for (int i = 1; i <= 6; i++) everythingWritten.add("g2-" + i);

        ShardedRecordStore reader = storeOn(migrating);
        List<String> seen = new ArrayList<>();
        ShardCursor cursor = ShardCursor.start();
        int pages = 0;
        while (pages < 20) {
            Page<ShardedRecord> page = reader.readDevice(device, cursor, limit);
            for (ShardedRecord r : page.records()) seen.add(r.eventId());
            pages++;
            cursor = page.next();
            if (cursor == null) break;
        }

        // A cursor that never goes null is an infinite paging loop, which is just as broken as
        // losing records — pin the terminal case rather than letting the guard hide it.
        assertThat(cursor).as("paging must terminate: the last page's cursor is null").isNull();
        assertThat(pages).as("the setup must span more than one page").isGreaterThan(1);
        assertThat(seen).containsExactlyInAnyOrderElementsOf(everythingWritten);
    }

    @Test
    void aPageNeverEndsInTheMiddleOfASequenceNumber() {
        // Both generations hold records at sequences 1, 2 and 3, so the merged page is
        // [g2-1, g1-1, g2-2, g1-2, g2-3, g1-3] and a limit of 3 cuts *between* the two records
        // that share sequence 2. The cursor is a sequence, so the next page starts strictly above
        // it: a straggler left at sequence 2 could never be reached by any later page. The page
        // must therefore hold both records at every sequence it touches, or neither.
        String device = "dev-nosplit";
        int limit = 3;
        ShardTopologyProvider migrating = twoGenerations(device, "shard:topology:nosplit", 3, 3);

        Page<ShardedRecord> first = storeOn(migrating).readDevice(device, ShardCursor.start(), limit);

        // The truncation path really was reached; otherwise the assertion below is vacuous.
        assertThat(first.next()).as("the merge must have truncated").isNotNull();

        // eventIds are named after their sequence, so the complete group at sequence n is
        // exactly {g1-n, g2-n}.
        List<String> wholeGroups = first.records().stream()
                .map(ShardedRecord::seqNum).distinct().sorted()
                .flatMap(seq -> java.util.stream.Stream.of("g1-" + seq, "g2-" + seq))
                .toList();
        assertThat(first.records()).extracting(ShardedRecord::eventId)
                .as("no sequence may be split across the page boundary")
                .containsExactlyInAnyOrderElementsOf(wholeGroups);

        // The cursor names the last record actually returned, not either input page's own high
        // water mark, and paging on from it still reaches everything exactly once.
        assertThat(first.next()).isEqualTo(ShardCursor.seq(
                first.records().get(first.records().size() - 1).seqNum()));

        List<String> seen = new ArrayList<>(
                first.records().stream().map(ShardedRecord::eventId).toList());
        ShardCursor cursor = first.next();
        int pages = 1;
        while (cursor != null && pages < 20) {
            Page<ShardedRecord> page = storeOn(migrating).readDevice(device, cursor, limit);
            for (ShardedRecord r : page.records()) seen.add(r.eventId());
            pages++;
            cursor = page.next();
        }
        assertThat(cursor).isNull();
        assertThat(seen).containsExactlyInAnyOrder(
                "g1-1", "g1-2", "g1-3", "g2-1", "g2-2", "g2-3");
    }

    @Test
    void anEmptyMergedPageStillPagesPastRecordsWhoseHashesAreGone() throws InterruptedException {
        // fetchRecords skips ZSet entries whose record hash is gone — TTL expiry, or an orphan
        // left behind by an aborted script. So a merged page can legitimately hold zero records
        // while the index still has plenty above it. Returning a null cursor there stops the
        // caller at the hole and silently loses everything beyond it.
        //
        // The cursor must be non-null if and only if either input page reports more, taken as the
        // LOWEST of the two: here the current generation says 13 and the previous says 3, so
        // resuming at 14 would skip previous-generation records 4 and 5. Resuming at 4 re-reads
        // current 11-13, which is harmless — their hashes are gone, and the dedup absorbs any
        // that reappear. Skipping is unrecoverable; re-reading is not.
        //
        // ShardedRecordStoreTtlTest.readDevice_hasMore_notAffectedByExpiredRecords pins the same
        // property for the single-generation path, but it builds a fixed ring, so prev == null
        // and it never reaches the merge.
        String device = "dev-hole";
        int limit = 3;

        ShardTopologyStore topoStore = new ShardTopologyStore(exec, "shard:topology:hole");
        assertThat(topoStore.bootstrap(2, VNODES, 0L).version()).isEqualTo(1);
        ShardKeys v1Keys = ShardKeys.of(
                PREFIX, new ShardTopology(1, 2, VNODES, 0L, ShardKeys.FORMAT_TAGGED));

        // Generation 1: seqs 1..5, of which 1-3 will be made unfetchable.
        ShardedRecordStore gen1 = storeOn(ShardTopologyProvider.fixedAtVersion(
                1, 2, VNODES, ShardKeys.FORMAT_TAGGED));
        int shard1 = 0;
        for (int i = 1; i <= 5; i++) {
            WriteResult wr = gen1.write(new ShardedRecord(
                    device, 0, RecordType.EVENT, "g1-" + i, "v", i));
            assertThat(wr.seqNum()).isEqualTo((long) i);
            shard1 = wr.shardIndex();
        }
        // The orphan route: drop the hash, leave the ZSet entry — what an aborted script leaves.
        for (int i = 1; i <= 3; i++) {
            assertThat(cmd().del(v1Keys.rec(shard1, i))).isEqualTo(1L);
        }

        // Reshard into the dual-read window.
        assertThat(topoStore.publishReshard(4, 1_000L, 60_000L).version()).isEqualTo(2);
        ShardTopologyProvider migrating =
                new ShardTopologyProvider(topoStore, VNODES, 4, 0L, () -> 2_000L);
        migrating.start();
        assertThat(migrating.previousIfActive()).isNotNull();

        // Push generation 2's counter well above generation 1's so the two input pages report
        // *different* cursors — that difference is what makes "take the lowest" testable.
        ShardKeys v2Keys = ShardKeys.of(PREFIX, migrating.current());
        int shard2 = migrating.current().shardFor(device);
        cmd().set(v2Keys.seq(shard2), "10");

        // Generation 2: seqs 11-13 expire (the TTL route), 14-15 stay live.
        ShardedRecordStore gen2 = storeOn(migrating);
        for (int i = 11; i <= 15; i++) {
            WriteResult wr = gen2.write(
                    new ShardedRecord(device, 0, RecordType.EVENT, "g2-" + i, "v", i),
                    i <= 13 ? 1 : 0);
            assertThat(wr.seqNum()).isEqualTo((long) i);
        }

        Thread.sleep(1100); // let 11-13 expire

        // Precondition: the hashes are gone but both indexes still hold every entry. Without
        // this the test could pass vacuously on a setup that simply wrote nothing.
        for (int i = 1; i <= 3; i++) assertThat(cmd().exists(v1Keys.rec(shard1, i))).isZero();
        for (int i = 11; i <= 13; i++) assertThat(cmd().exists(v2Keys.rec(shard2, i))).isZero();
        assertThat(cmd().zcard(v1Keys.dev(shard1, device))).isEqualTo(5L);
        assertThat(cmd().zcard(v2Keys.dev(shard2, device))).isEqualTo(5L);

        ShardedRecordStore reader = storeOn(migrating);
        Page<ShardedRecord> first = reader.readDevice(device, ShardCursor.start(), limit);

        assertThat(first.records())
                .as("every record on the first page has a missing hash").isEmpty();
        assertThat(first.next())
                .as("an empty page must not end paging while the index still holds more")
                .isNotNull();
        assertThat(first.next())
                .as("resume from the LOWEST unexhausted generation (previous=3), not the current "
                        + "one (13) — resuming at 14 would skip g1-4 and g1-5")
                .isEqualTo(ShardCursor.seq(3));

        // And paging on from there reaches every live record, then terminates.
        List<String> seen = new ArrayList<>();
        ShardCursor cursor = first.next();
        int pages = 1;
        while (cursor != null && pages < 20) {
            Page<ShardedRecord> page = reader.readDevice(device, cursor, limit);
            for (ShardedRecord r : page.records()) seen.add(r.eventId());
            pages++;
            cursor = page.next();
        }
        assertThat(cursor).as("paging must terminate").isNull();
        assertThat(seen).containsExactlyInAnyOrder("g1-4", "g1-5", "g2-14", "g2-15");
    }

    @Test
    void aMergedPageMustNotAdvancePastAGenerationWhoseRecordsWereDropped() {
        // The cut can fill entirely from ONE generation while the other contributed tuples but no
        // records — fetchRecords drops tuples whose record hash is gone. A cursor taken from the
        // last record RETURNED then jumps past the silent generation's ceiling, and everything in
        // between is returned by no page, ever.
        //
        // A reshard resets the new generation's counter, so previous-generation sequences are
        // normally much LARGER than current's — which is exactly the shape that hides the bug:
        // the previous generation's live records sort above the current generation's, so they
        // fill the cut and drag the cursor with them.
        //
        // anEmptyMergedPageStillPagesPastRecordsWhoseHashesAreGone does NOT cover this: its hole
        // makes BOTH input pages record-less, so the merge takes the empty branch. Here the merge
        // is non-empty and the bound has to come from the input pages' own cursors.
        String device = "dev-cursor-bound";
        int limit = 3;

        ShardTopologyStore topoStore = new ShardTopologyStore(exec, "shard:topology:cursorbound");
        assertThat(topoStore.bootstrap(2, VNODES, 0L).version()).isEqualTo(1);

        ShardTopology v1 = new ShardTopology(1, 2, VNODES, 0L, ShardKeys.FORMAT_TAGGED);
        ShardKeys v1Keys = ShardKeys.of(PREFIX, v1);
        int shard1 = v1.shardFor(device);

        // Previous generation: seqs 9990-9995, every record live.
        cmd().set(v1Keys.seq(shard1), "9989");
        ShardedRecordStore gen1 = storeOn(ShardTopologyProvider.fixedAtVersion(
                1, 2, VNODES, ShardKeys.FORMAT_TAGGED));
        for (int i = 9990; i <= 9995; i++) {
            assertThat(gen1.write(new ShardedRecord(
                    device, 0, RecordType.EVENT, "g1-" + i, "v", i)).seqNum()).isEqualTo((long) i);
        }

        // Reshard into the dual-read window. Generation 2's counter starts from scratch.
        assertThat(topoStore.publishReshard(4, 1_000L, 60_000L).version()).isEqualTo(2);
        ShardTopologyProvider migrating =
                new ShardTopologyProvider(topoStore, VNODES, 4, 0L, () -> 2_000L);
        migrating.start();
        assertThat(migrating.previousIfActive()).isNotNull();

        ShardedRecordStore gen2 = storeOn(migrating);
        for (int i = 1; i <= 8; i++) {
            assertThat(gen2.write(new ShardedRecord(
                    device, 0, RecordType.EVENT, "g2-" + i, "v", i)).seqNum()).isEqualTo((long) i);
        }

        // Drop the hashes for the current generation's three LOWEST sequences, leaving the ZSet
        // entries — the orphan an aborted script leaves behind. Three is exactly `limit`, so the
        // current generation's first page reports a cursor while returning nothing.
        ShardKeys v2Keys = ShardKeys.of(PREFIX, migrating.current());
        int shard2 = migrating.current().shardFor(device);
        for (int i = 1; i <= 3; i++) {
            assertThat(cmd().del(v2Keys.rec(shard2, i))).isEqualTo(1L);
        }

        // Preconditions, so a setup regression cannot make the assertions below vacuous.
        assertThat(cmd().zcard(v1Keys.dev(shard1, device))).isEqualTo(6L);
        assertThat(cmd().zcard(v2Keys.dev(shard2, device))).isEqualTo(8L);
        for (int i = 4; i <= 8; i++) assertThat(cmd().exists(v2Keys.rec(shard2, i))).isEqualTo(1L);

        ShardedRecordStore reader = storeOn(migrating);

        // The first page is the trap: current contributes tuples 1-3 and zero records, previous
        // contributes 9990-9992 and three records, so the whole cut comes from previous.
        Page<ShardedRecord> first = reader.readDevice(device, ShardCursor.start(), limit);
        assertThat(first.records()).extracting(ShardedRecord::eventId)
                .containsExactly("g1-9990", "g1-9991", "g1-9992");
        assertThat(first.next())
                .as("the cursor must be bounded by the current generation's ceiling (3), not the "
                        + "last record returned (9992) — resuming at 9993 strands g2-4..g2-8")
                .isEqualTo(ShardCursor.seq(3));

        List<String> expected = new ArrayList<>();
        for (int i = 9990; i <= 9995; i++) expected.add("g1-" + i);
        for (int i = 1; i <= 8; i++) if (i > 3) expected.add("g2-" + i);

        List<String> seen = new ArrayList<>(
                first.records().stream().map(ShardedRecord::eventId).toList());
        ShardCursor cursor = first.next();
        int pages = 1;
        while (cursor != null && pages < 30) {
            Page<ShardedRecord> page = reader.readDevice(device, cursor, limit);
            for (ShardedRecord r : page.records()) seen.add(r.eventId());
            pages++;
            cursor = page.next();
        }
        assertThat(cursor).as("paging must terminate").isNull();
        // Duplicates across page boundaries are allowed — reads are at-least-once. Losing a
        // record is not.
        assertThat(seen).as("every live record must be returned by some page")
                .containsAll(expected);
    }

    /**
     * Writes {@code gen1Count} records under a fresh generation 1 and {@code gen2Count} under
     * generation 2 after a real reshard, for the one device, and returns a provider sitting
     * inside the dual-read window. Sequence counters are per generation, so generation 2 reissues
     * 1..n — the collision is deliberate, exactly as in
     * {@link #deviceReadKeepsBothGenerationsWhenSequenceNumbersCollide}.
     */
    private ShardTopologyProvider twoGenerations(String device, String topologyKey,
                                                 int gen1Count, int gen2Count) {
        ShardTopologyStore topoStore = new ShardTopologyStore(exec, topologyKey);
        assertThat(topoStore.bootstrap(2, VNODES, 0L).version()).isEqualTo(1);

        ShardedRecordStore gen1 = storeOn(ShardTopologyProvider.fixedAtVersion(
                1, 2, VNODES, ShardKeys.FORMAT_TAGGED));
        for (int i = 1; i <= gen1Count; i++) {
            WriteResult wr = gen1.write(new ShardedRecord(
                    device, 0, RecordType.EVENT, "g1-" + i, "old-" + i, i));
            assertThat(wr.seqNum()).isEqualTo((long) i);
        }

        assertThat(topoStore.publishReshard(4, 1_000L, 60_000L).version()).isEqualTo(2);
        ShardTopologyProvider migrating =
                new ShardTopologyProvider(topoStore, VNODES, 4, 0L, () -> 2_000L);
        migrating.start();
        assertThat(migrating.previousIfActive()).isNotNull();

        ShardedRecordStore gen2 = storeOn(migrating);
        for (int i = 1; i <= gen2Count; i++) {
            WriteResult wr = gen2.write(new ShardedRecord(
                    device, 0, RecordType.EVENT, "g2-" + i, "new-" + i, i));
            // Generation 2's counter starts from scratch, so it reissues the same numbers.
            assertThat(wr.seqNum()).isEqualTo((long) i);
        }
        return migrating;
    }

    @Test
    void ensureCounterValidRepairsATaggedGenerationsCounter() {
        // Seed a tagged device ZSet with a high score, set the counter behind it, run the
        // repair, and assert the counter was raised.
        int shard = 2;
        SequenceGenerator gen = new SequenceGenerator(exec, PREFIX);

        cmd().zadd(KEYS.dev(shard, "dev-repair"), 500.0, "evt-500");
        cmd().zadd(KEYS.dev(shard, "dev-repair-2"), 250.0, "evt-250");
        cmd().set(KEYS.seq(shard), "10");
        // A device key in a *different* shard must not be scanned into this shard's repair —
        // this is what proves the tagged SCAN glob "sr:g3:dev:{2}:*" matches braces literally.
        cmd().zadd(KEYS.dev(3, "dev-elsewhere"), 9_000.0, "evt-9000");

        assertThat(gen.ensureCounterValid(TAGGED, shard, 30_000L)).isTrue();

        assertThat(cmd().get(KEYS.seq(shard))).isEqualTo("501");
        assertThat(gen.next(TAGGED, shard)).isEqualTo(502L);
        // The neighbouring shard's counter was not touched.
        assertThat(cmd().get(KEYS.seq(3))).isNull();
    }

    @Test
    void everyArgvFieldLandsInTheRightPlace() {
        // Write one record with distinct values in every field, then assert the stored hash's
        // deviceId/type/eventId/payload/timestamp and the stream entry's deviceId/seq/type/
        // eventId each carry the value they were given. Catches a positional ARGV transposition
        // — the failure mode that corrupts every record while still returning OK.
        ShardedRecordStore store = taggedStore();
        ShardedRecord input = new ShardedRecord(
                "device-value", 0, RecordType.FEATURE, "event-value", "payload-value",
                1_234_567_890_123L);

        WriteResult wr = store.write(input);
        int shard = wr.shardIndex();

        assertThat(cmd().hgetall(KEYS.rec(shard, wr.seqNum()))).containsExactlyInAnyOrderEntriesOf(
                Map.of("deviceId", "device-value",
                        "type", "FEATURE",
                        "eventId", "event-value",
                        "payload", "payload-value",
                        "timestamp", "1234567890123"));

        List<StreamMessage<String, String>> entries = stream(shard);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getBody()).containsExactlyInAnyOrderEntriesOf(
                Map.of("deviceId", "device-value",
                        "seq", Long.toString(wr.seqNum()),
                        "type", "FEATURE",
                        "eventId", "event-value"));

        // Round-tripping through the store proves the field names, not just the values, match.
        ShardedRecord out = store.readDevice("device-value", ShardCursor.start(), 10).records().get(0);
        assertThat(out).isEqualTo(input.withSeqNum(wr.seqNum()));
    }

    @Test
    void zeroTtlLeavesTheRecordKeyWithoutAnExpiry() {
        // write(record) with no TTL argument must leave TTL == -1 on the record key, not -2 and
        // not a positive value. Only the positive-TTL case is covered today.
        ShardedRecordStore store = taggedStore();
        String device = "dev-ttl-zero";
        int shard = TAGGED.shardFor(device);

        WriteResult noTtl = store.write(
                new ShardedRecord(device, 0, RecordType.LOG, "evt-no-ttl", "v", 1L));

        // -1 = key exists, no expiry. -2 would mean the key is gone; a positive value would mean
        // an EXPIRE ran on a write that asked for none.
        assertThat(cmd().ttl(KEYS.rec(shard, noTtl.seqNum()))).isEqualTo(-1L);

        // Contrast: a positive TTL applies to the record key and to nothing else the script wrote.
        WriteResult withTtl = store.write(
                new ShardedRecord(device, 0, RecordType.LOG, "evt-with-ttl", "v", 2L), 3600);

        assertThat(cmd().ttl(KEYS.rec(shard, withTtl.seqNum()))).isBetween(3590L, 3600L);
        assertThat(cmd().ttl(KEYS.dev(shard, device))).isEqualTo(-1L);
        assertThat(cmd().ttl(KEYS.stream(shard))).isEqualTo(-1L);
        assertThat(cmd().ttl(KEYS.seq(shard))).isEqualTo(-1L);
        // The earlier no-TTL record was not caught by the later write's EXPIRE.
        assertThat(cmd().ttl(KEYS.rec(shard, noTtl.seqNum()))).isEqualTo(-1L);
    }

    @Test
    void theSequenceRendersAsAnIntegerNotAFloat() {
        // The script concatenates a Lua number into the record key. Assert the key at
        // "<recPrefix><seq>" exists and that "<recPrefix><seq>.0" does not, so a %.14g
        // rendering regression is caught rather than silently producing unreadable keys.
        ShardedRecordStore store = taggedStore();
        String device = "dev-seq-format";
        int shard = TAGGED.shardFor(device);
        String recPrefix = KEYS.recPrefix(shard);

        WriteResult small = store.write(
                new ShardedRecord(device, 0, RecordType.EVENT, "evt-small", "v", 1L));

        assertThat(small.seqNum()).isEqualTo(1L);
        assertThat(cmd().exists(recPrefix + small.seqNum())).isEqualTo(1L);
        assertThat(cmd().exists(recPrefix + small.seqNum() + ".0")).isZero();

        // Again at 13 digits, where an integer-formatting regression would render "1e+12"
        // rather than a `.0` suffix. Still inside %.14g's 14 significant digits, so this is the
        // largest sequence the current script is specified to handle correctly.
        cmd().set(KEYS.seq(shard), "1000000000000");
        WriteResult big = store.write(
                new ShardedRecord(device, 0, RecordType.EVENT, "evt-big", "v", 2L));

        assertThat(big.seqNum()).isEqualTo(1_000_000_000_001L);
        assertThat(cmd().exists(KEYS.rec(shard, big.seqNum()))).isEqualTo(1L);

        // Exhaustive: the only record keys in this shard are the two ShardKeys.rec() names.
        // Any float or scientific rendering would show up here as a third, unreadable key.
        assertThat(cmd().keys(recPrefix + "*")).containsExactlyInAnyOrder(
                KEYS.rec(shard, small.seqNum()), KEYS.rec(shard, big.seqNum()));

        // And both are readable through the store, which builds the key from ShardKeys.rec().
        assertThat(store.readDevice(device, ShardCursor.start(), 10).records())
                .extracting(ShardedRecord::eventId).containsExactlyInAnyOrder("evt-small", "evt-big");
    }
}
