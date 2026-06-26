package com.recsys.application.pagination;

import com.recsys.domain.item.RankedMovie;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CursorPaginationServiceTest {

    private final CursorPaginationService svc = new CursorPaginationService();

    private static RankedMovie m(String id, double score) {
        return new RankedMovie(id, score, 1, Map.of());
    }

    private Page<RankedMovie> page(List<RankedMovie> ranked, String cursor, int limit) {
        return svc.page(ranked, cursor, limit, RankedMovie::score, RankedMovie::itemId);
    }

    private static List<String> ids(Page<RankedMovie> p) {
        return p.items().stream().map(RankedMovie::itemId).toList();
    }

    @Test
    void firstPageStartsAtHeadAndEmitsAnchorCursor() {
        Page<RankedMovie> p = page(List.of(m("a", 0.9), m("b", 0.8), m("c", 0.7)), null, 2);
        assertEquals(List.of("a", "b"), ids(p));
        // anchor is the last returned item (b), not an offset
        assertEquals(new RankedListCursor(0.8, "b"), RankedListCursor.decode(p.nextCursor()));
    }

    @Test
    void lastPageHasNullCursor() {
        Page<RankedMovie> p = page(List.of(m("a", 0.9), m("b", 0.8)), null, 5);
        assertEquals(List.of("a", "b"), ids(p));
        assertNull(p.nextCursor());
    }

    /**
     * The core fix: an item ranked BEFORE the anchor is excluded between pages (the list shifts
     * left). A seek cursor must still resume right after the anchor — an offset cursor would have
     * skipped "c" here and returned only ["d"].
     */
    @Test
    void excludingAnItemBeforeTheAnchorDoesNotSkipResults() {
        Page<RankedMovie> p1 = page(
                List.of(m("a", 0.9), m("b", 0.8), m("c", 0.7), m("d", 0.6)), null, 2);
        assertEquals(List.of("a", "b"), ids(p1));

        // Next request re-recalls with "a" excluded — the ranked list no longer contains it.
        Page<RankedMovie> p2 = page(
                List.of(m("b", 0.8), m("c", 0.7), m("d", 0.6)), p1.nextCursor(), 2);
        assertEquals(List.of("c", "d"), ids(p2));
        assertNull(p2.nextCursor());
    }

    /**
     * Fallback path: the anchor item itself is excluded between pages. With no exact id match the
     * service seeks by (score, itemId) to the correct insertion point — still no skip, no repeat.
     */
    @Test
    void excludingTheAnchorItemFallsBackToScoreSeek() {
        // Anchor at (0.8, "b"); next list drops "b" entirely.
        String cursor = new RankedListCursor(0.8, "b").encode();
        Page<RankedMovie> p = page(
                List.of(m("a", 0.9), m("c", 0.7), m("d", 0.6)), cursor, 2);
        assertEquals(List.of("c", "d"), ids(p));
    }

    @Test
    void anchorPastEndYieldsEmptyTerminalPage() {
        String cursor = new RankedListCursor(0.1, "z").encode();
        Page<RankedMovie> p = page(List.of(m("a", 0.9), m("b", 0.8)), cursor, 2);
        assertTrue(p.items().isEmpty());
        assertNull(p.nextCursor());
    }

    @Test
    void invalidCursorIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> page(List.of(m("a", 0.9)), "<nextCursor>", 2));
    }

    @Test
    void cursorRoundTripsAndBlankIsStart() {
        RankedListCursor c = new RankedListCursor(0.42, "movie:7");
        assertEquals(c, RankedListCursor.decode(c.encode()));
        assertTrue(RankedListCursor.decode("").isStart());
        assertTrue(RankedListCursor.decode(null).isStart());
        assertFalse(c.isStart());
    }
}
