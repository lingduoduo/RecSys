package com.recsys.application.pagination;

import java.util.List;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * Slices an already-ranked list into a page using a {@link RankedListCursor} seek anchor.
 *
 * <p>Because the anchor is the previous page's last {@code (score, itemId)} rather than an absolute
 * offset, the ranked list may legitimately change between requests — items excluded via
 * {@code excludedItemIds}, new trending entries, online-learner re-scores — without skipping or
 * duplicating results. The caller supplies extractors so paging stays generic over the item type.
 */
public class CursorPaginationService {

    public <T> Page<T> page(List<T> rankedItems,
                            String cursor,
                            int limit,
                            ToDoubleFunction<T> scoreOf,
                            Function<T, String> idOf) {
        if (rankedItems == null || rankedItems.isEmpty() || limit <= 0) {
            return new Page<>(List.of(), null);
        }

        RankedListCursor anchor = RankedListCursor.decode(cursor);
        int start = anchor.isStart() ? 0 : firstAfterAnchor(rankedItems, anchor, scoreOf, idOf);
        if (start >= rankedItems.size()) {
            return new Page<>(List.of(), null);
        }

        int endExclusive = Math.min(rankedItems.size(), start + limit);
        String nextCursor = null;
        if (endExclusive < rankedItems.size()) {
            T last = rankedItems.get(endExclusive - 1);
            nextCursor = new RankedListCursor(scoreOf.applyAsDouble(last), idOf.apply(last)).encode();
        }
        return new Page<>(rankedItems.subList(start, endExclusive), nextCursor);
    }

    /**
     * Index of the first item ranked strictly after the anchor in (score desc, itemId asc) order.
     *
     * <p>Fast path: if the anchor item is still present, resume immediately after it — exact and
     * free of floating-point comparison. Fallback (anchor excluded or re-ranked out of the window):
     * seek by the {@code (score, itemId)} predicate to the correct insertion point so no item is
     * skipped or repeated.
     */
    private static <T> int firstAfterAnchor(List<T> items,
                                            RankedListCursor anchor,
                                            ToDoubleFunction<T> scoreOf,
                                            Function<T, String> idOf) {
        for (int i = 0; i < items.size(); i++) {
            if (anchor.itemId().equals(idOf.apply(items.get(i)))) {
                return i + 1;
            }
        }
        for (int i = 0; i < items.size(); i++) {
            double score = scoreOf.applyAsDouble(items.get(i));
            String id = idOf.apply(items.get(i));
            boolean afterAnchor = score < anchor.score()
                    || (score == anchor.score() && id.compareTo(anchor.itemId()) > 0);
            if (afterAnchor) {
                return i;
            }
        }
        return items.size();
    }
}
