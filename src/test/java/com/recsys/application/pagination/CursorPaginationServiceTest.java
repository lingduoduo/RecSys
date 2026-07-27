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

    private Page<RankedMovie> page(List<RankedMovie> ranked, RankedListCursor position, int limit) {
        return svc.page(ranked, position, limit, RankedMovie::score, RankedMovie::itemId);
    }

    private static List<String> ids(Page<RankedMovie> page) {
        return page.items().stream().map(RankedMovie::itemId).toList();
    }

    @Test
    void exactTerminalPageHasNoNextPosition() {
        Page<RankedMovie> page = page(List.of(m("a", .9), m("b", .8)), RankedListCursor.START, 2);

        assertEquals(List.of("a", "b"), ids(page));
        assertFalse(page.hasMore());
        assertNull(page.nextPosition());
    }

    @Test
    void lookaheadProducesPositionFromLastReturnedItem() {
        Page<RankedMovie> page = page(
                List.of(m("a", .9), m("b", .8), m("c", .7)), RankedListCursor.START, 2);

        assertTrue(page.hasMore());
        assertEquals(new RankedListCursor(.8, "b"), page.nextPosition());
    }

    @Test
    void legacyStringAdapterDelegatesToTuplePaging() {
        Page<RankedMovie> page = svc.page(
                List.of(m("a", .9), m("b", .8), m("c", .7)),
                new RankedListCursor(.9, "a").encode(), 2, RankedMovie::score, RankedMovie::itemId);

        assertEquals(List.of("b", "c"), ids(page));
        assertNull(page.nextCursor());
    }

    @Test
    void legacyPageConstructorDecodesItsNextPosition() {
        String cursor = new RankedListCursor(.8, "b").encode();

        Page<RankedMovie> page = new Page<>(List.of(m("a", .9)), cursor);

        assertTrue(page.hasMore());
        assertEquals(new RankedListCursor(.8, "b"), page.nextPosition());
        assertEquals(cursor, page.nextCursor());
    }

    @Test
    void changedAnchorScoreUsesFullTupleInsteadOfIdFastPath() {
        Page<RankedMovie> page = page(
                List.of(m("a", .9), m("b", .6), m("c", .5)), new RankedListCursor(.8, "b"), 2);

        assertEquals(List.of("b", "c"), ids(page));
    }

    @Test
    void tiedScoresResumeAtTheLexicallyNextId() {
        Page<RankedMovie> page = page(
                List.of(m("a", .9), m("b", .9), m("c", .8)), new RankedListCursor(.9, "a"), 2);

        assertEquals(List.of("b", "c"), ids(page));
    }

    @Test
    void insertedItemBeforeAnchorIsNotRepeated() {
        Page<RankedMovie> page = page(
                List.of(m("new", 1.0), m("a", .9), m("b", .8), m("c", .7)),
                new RankedListCursor(.8, "b"), 2);

        assertEquals(List.of("c"), ids(page));
    }

    @Test
    void insertedItemAfterAnchorIsIncluded() {
        Page<RankedMovie> page = page(
                List.of(m("a", .9), m("b", .8), m("new", .75), m("c", .7)),
                new RankedListCursor(.8, "b"), 2);

        assertEquals(List.of("new", "c"), ids(page));
    }

    @Test
    void removedAnchorResumesAtItsTupleInsertionPoint() {
        Page<RankedMovie> page = page(
                List.of(m("a", .9), m("c", .7), m("d", .6)), new RankedListCursor(.8, "b"), 2);

        assertEquals(List.of("c", "d"), ids(page));
    }

    @Test
    void anchorPastEndYieldsEmptyTerminalPage() {
        Page<RankedMovie> page = page(
                List.of(m("a", .9), m("b", .8)), new RankedListCursor(.1, "z"), 2);

        assertTrue(page.items().isEmpty());
        assertFalse(page.hasMore());
        assertNull(page.nextPosition());
    }

    @Test
    void nonPositiveLimitIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> page(List.of(m("a", .9)), RankedListCursor.START, 0));
    }

    @Test
    void duplicateTupleIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> page(
                List.of(m("a", .9), m("a", .9)), RankedListCursor.START, 2));
    }

    @Test
    void unorderedInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> page(
                List.of(m("a", .8), m("b", .9)), RankedListCursor.START, 2));
    }

    @Test
    void nonFiniteScoresAndBlankIdsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> page(
                List.of(m("a", Double.NaN)), RankedListCursor.START, 1));
        assertThrows(IllegalArgumentException.class, () -> page(
                List.of(m(" ", .9)), RankedListCursor.START, 1));
    }

    @Test
    void pageRequiresHasMoreAndNextPositionToAgree() {
        assertThrows(IllegalArgumentException.class,
                () -> new Page<>(List.of(m("a", .9)), null, true));
        assertThrows(IllegalArgumentException.class,
                () -> new Page<>(List.of(m("a", .9)), new RankedListCursor(.9, "a"), false));
    }
}
