package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ShardedRecordStore.mergeDevicePages} driven directly, with no Redis.
 *
 * <p>The merge is the highest-churn logic on the dual-read path — rewritten three times, with
 * three separate data-loss defects found in it — and every test that reached it was
 * {@code @Tag("docker")}, which the {@code -Presilience} PR gate excludes. It needs no Redis at
 * all: it is a pure function of two {@link Page}s and a limit. This class is therefore listed in
 * that profile, so a regression in it blocks a merge.
 *
 * <p>What each input {@code Page} means, since the tests are built from hand:
 * <ul>
 *   <li>{@code records} — what {@code fetchRecords} actually resolved. It can be SHORTER than the
 *       index tuples the generation returned, because a tuple whose record hash is gone (TTL,
 *       eviction, or an orphan left by an aborted script) is silently skipped. Empty records with
 *       a non-null cursor is a legal, and load-bearing, input.</li>
 *   <li>{@code next} — the generation's own ceiling: "I returned every index entry up to here,
 *       more may exist above". {@code null} means that generation is exhausted.</li>
 * </ul>
 */
class ShardedRecordStoreMergeTest {

    private static final String DEVICE = "dev-merge";

    private static ShardedRecord rec(long seq, String eventId) {
        return rec(seq, eventId, "payload-" + eventId);
    }

    private static ShardedRecord rec(long seq, String eventId, String payload) {
        return new ShardedRecord(DEVICE, seq, RecordType.EVENT, eventId, payload, seq);
    }

    private static Page<ShardedRecord> page(ShardCursor next, ShardedRecord... records) {
        return new Page<>(List.of(records), next);
    }

    @Test
    void theCursorNeverAdvancesPastAGenerationThatContributedNoRecords() {
        // The whole-branch review's finding. A reshard resets the new generation's counter, so
        // previous-generation sequences are normally much LARGER than current's. Here the current
        // generation filled `limit` with index tuples but every one of their record hashes was
        // gone, so it contributed zero records and the cut came entirely from previous. A cursor
        // taken from the last record RETURNED (9992) would resume above the current generation's
        // ceiling (3) and strand the device's newest records forever.
        Page<ShardedRecord> current = page(ShardCursor.seq(3));   // 3 tuples, 0 records
        Page<ShardedRecord> previous = page(ShardCursor.seq(9992),
                rec(9990, "g1-9990"), rec(9991, "g1-9991"), rec(9992, "g1-9992"));

        Page<ShardedRecord> merged = ShardedRecordStore.mergeDevicePages(current, previous, 3);

        assertThat(merged.records()).extracting(ShardedRecord::eventId)
                .containsExactly("g1-9990", "g1-9991", "g1-9992");
        assertThat(merged.next())
                .as("bounded by the silent generation's ceiling, not by the last record returned")
                .isEqualTo(ShardCursor.seq(3));
    }

    @Test
    void anUntruncatedPageIsStillBoundedByTheLowestUnexhaustedInput() {
        // Same hazard without truncation: the merge fits inside `limit`, so no record-derived
        // ceiling applies at all and the cursor must come purely from the input pages.
        Page<ShardedRecord> current = page(ShardCursor.seq(4));   // 4 tuples, 0 records
        Page<ShardedRecord> previous = page(ShardCursor.seq(500), rec(500, "g1-500"));

        Page<ShardedRecord> merged = ShardedRecordStore.mergeDevicePages(current, previous, 10);

        assertThat(merged.records()).hasSize(1);
        assertThat(merged.next()).isEqualTo(ShardCursor.seq(4));
    }

    @Test
    void anExhaustedGenerationImposesNoCeiling() {
        // null means "exhausted", which is no constraint — not a ceiling of zero. If null won the
        // comparison the cursor would never advance and paging would spin forever.
        Page<ShardedRecord> current = page(ShardCursor.seq(13));  // 3 tuples, 0 records
        Page<ShardedRecord> previous = page(null, rec(4, "g1-4"), rec(5, "g1-5"));

        Page<ShardedRecord> merged = ShardedRecordStore.mergeDevicePages(current, previous, 3);

        assertThat(merged.records()).extracting(ShardedRecord::eventId)
                .containsExactly("g1-4", "g1-5");
        assertThat(merged.next()).isEqualTo(ShardCursor.seq(13));
    }

    @Test
    void dedupesAcrossGenerationsByEventIdPreferringCurrent() {
        // Identity is (deviceId, eventId), the device index's own member — never seqNum, which
        // each generation reissues from 1.
        Page<ShardedRecord> current = page(null, rec(5, "shared", "from-current"));
        Page<ShardedRecord> previous = page(null,
                rec(9990, "shared", "from-previous"), rec(9991, "only-previous"));

        Page<ShardedRecord> merged = ShardedRecordStore.mergeDevicePages(current, previous, 10);

        assertThat(merged.records()).extracting(ShardedRecord::eventId)
                .containsExactly("shared", "only-previous");
        assertThat(merged.records().get(0).payload())
                .as("current wins the duplicate").isEqualTo("from-current");
        assertThat(merged.records().get(0).seqNum())
                .as("and it is the current record, at the current generation's sequence")
                .isEqualTo(5L);
    }

    @Test
    void identicalSequenceNumbersInDifferentGenerationsAreBothKept() {
        // Both counters independently issue 1..n, so deduping on seqNum would silently drop every
        // previous-generation record during a migration window.
        Page<ShardedRecord> current = page(null, rec(1, "g2-1"), rec(2, "g2-2"));
        Page<ShardedRecord> previous = page(null, rec(1, "g1-1"), rec(2, "g1-2"));

        Page<ShardedRecord> merged = ShardedRecordStore.mergeDevicePages(current, previous, 10);

        assertThat(merged.records()).extracting(ShardedRecord::eventId)
                .containsExactlyInAnyOrder("g1-1", "g1-2", "g2-1", "g2-2");
        assertThat(merged.next()).isNull();
    }

    @Test
    void aPageOverDeliversRatherThanStrandingAStragglerAtTheCutSequence() {
        // The cursor IS a sequence, and the next page starts strictly above it, so a record left
        // behind at the cut sequence could never be reached by any later page. The cap is
        // therefore soft: the page extends past `limit` to finish the group.
        Page<ShardedRecord> current = page(ShardCursor.seq(3),
                rec(1, "g2-1"), rec(2, "g2-2"), rec(3, "g2-3"));
        Page<ShardedRecord> previous = page(ShardCursor.seq(3),
                rec(1, "g1-1"), rec(2, "g1-2"), rec(3, "g1-3"));

        Page<ShardedRecord> merged = ShardedRecordStore.mergeDevicePages(current, previous, 3);

        assertThat(merged.records())
                .as("limit 3 cuts between the two records at sequence 2, so the page runs to 4")
                .hasSize(4);
        assertThat(merged.records()).extracting(ShardedRecord::eventId)
                .containsExactlyInAnyOrder("g1-1", "g2-1", "g1-2", "g2-2");
        assertThat(merged.records()).extracting(ShardedRecord::seqNum)
                .as("no sequence is split across the boundary").containsExactly(1L, 1L, 2L, 2L);
        assertThat(merged.next())
                .as("the record ceiling (2) is lower than either input's (3), so it wins")
                .isEqualTo(ShardCursor.seq(2));
    }

    @Test
    void anEmptyMergeStillPagesFromTheLowestUnexhaustedInput() {
        // Zero records does NOT mean the indexes are exhausted. Returning a null cursor here
        // stops the caller at the hole and silently loses everything beyond it.
        Page<ShardedRecord> current = page(ShardCursor.seq(13));
        Page<ShardedRecord> previous = page(ShardCursor.seq(3));

        Page<ShardedRecord> merged = ShardedRecordStore.mergeDevicePages(current, previous, 3);

        assertThat(merged.records()).isEmpty();
        assertThat(merged.next())
                .as("resuming at 14 would skip previous-generation 4..13")
                .isEqualTo(ShardCursor.seq(3));
    }

    @Test
    void bothInputsExhaustedTerminatesPaging() {
        // The terminal case. A cursor that never goes null is an infinite paging loop, which is
        // as broken as losing records.
        Page<ShardedRecord> current = page(null, rec(7, "g2-7"));
        Page<ShardedRecord> previous = page(null, rec(9995, "g1-9995"));

        Page<ShardedRecord> merged = ShardedRecordStore.mergeDevicePages(current, previous, 10);

        assertThat(merged.records()).extracting(ShardedRecord::eventId)
                .containsExactly("g2-7", "g1-9995");
        assertThat(merged.next()).isNull();
    }

    @Test
    void bothInputsEmptyAndExhaustedTerminatesPaging() {
        Page<ShardedRecord> merged =
                ShardedRecordStore.mergeDevicePages(Page.empty(), Page.empty(), 3);

        assertThat(merged.records()).isEmpty();
        assertThat(merged.next()).isNull();
    }

    @Test
    void theCursorStrictlyAdvancesSoPagingIsBounded() {
        // The termination argument, pinned. Every ceiling the merge can pick is drawn from a
        // record or tuple at or above minScore = oldCursor + 1, so whatever it returns is
        // strictly greater than the cursor the caller came in with. Drive the worst shape — the
        // silent-generation case, whose ceiling is the lowest available — and check the cursor
        // moved off the incoming value rather than repeating it.
        long incoming = 3;
        Page<ShardedRecord> current = page(ShardCursor.seq(incoming + 1));  // tuples, no records
        Page<ShardedRecord> previous = page(ShardCursor.seq(9992),
                rec(9990, "g1-9990"), rec(9991, "g1-9991"), rec(9992, "g1-9992"));

        Page<ShardedRecord> merged = ShardedRecordStore.mergeDevicePages(current, previous, 3);

        assertThat(merged.next()).isNotNull();
        assertThat(Long.parseLong(merged.next().value()))
                .as("the cursor must move past the value the caller resumed from")
                .isGreaterThan(incoming);
    }
}
