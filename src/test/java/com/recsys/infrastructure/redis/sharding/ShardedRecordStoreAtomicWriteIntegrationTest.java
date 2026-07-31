package com.recsys.infrastructure.redis.sharding;

import io.lettuce.core.Range;
import io.lettuce.core.StreamMessage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
