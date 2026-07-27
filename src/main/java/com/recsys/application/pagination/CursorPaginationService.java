package com.recsys.application.pagination;

import java.util.List;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * Slices an already-ranked list into a page using a {@link RankedListCursor} seek anchor.
 */
public class CursorPaginationService {

    public <T> Page<T> page(List<T> rankedItems,
                            RankedListCursor anchor,
                            int limit,
                            ToDoubleFunction<T> scoreOf,
                            Function<T, String> idOf) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (rankedItems == null || rankedItems.isEmpty()) {
            return new Page<>(List.of(), null, false);
        }

        validateRankedItems(rankedItems, scoreOf, idOf);
        RankedListCursor position = anchor == null ? RankedListCursor.START : anchor;
        int start = position.isStart() ? 0 : firstAfterAnchor(rankedItems, position, scoreOf, idOf);
        int available = rankedItems.size() - start;
        if (available == 0) {
            return new Page<>(List.of(), null, false);
        }

        boolean hasMore = available > limit;
        int endExclusive = start + Math.min(available, limit);
        RankedListCursor nextPosition = null;
        if (hasMore) {
            T last = rankedItems.get(endExclusive - 1);
            nextPosition = new RankedListCursor(scoreOf.applyAsDouble(last), idOf.apply(last));
        }
        return new Page<>(rankedItems.subList(start, endExclusive), nextPosition, hasMore);
    }

    private static <T> void validateRankedItems(List<T> items,
                                                ToDoubleFunction<T> scoreOf,
                                                Function<T, String> idOf) {
        double previousScore = Double.NaN;
        String previousId = null;
        for (int index = 0; index < items.size(); index++) {
            T item = items.get(index);
            double score = scoreOf.applyAsDouble(item);
            String id = idOf.apply(item);
            if (!Double.isFinite(score)) {
                throw new IllegalArgumentException("ranked item score must be finite");
            }
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("ranked item id must not be blank");
            }
            if (index > 0 && !isStrictlyBefore(previousScore, previousId, score, id)) {
                throw new IllegalArgumentException("ranked items must be strictly ordered");
            }
            previousScore = score;
            previousId = id;
        }
    }

    private static boolean isStrictlyBefore(double score, String id, double nextScore, String nextId) {
        return Double.compare(score, nextScore) > 0
                || (Double.compare(score, nextScore) == 0 && id.compareTo(nextId) < 0);
    }

    private static <T> int firstAfterAnchor(List<T> items,
                                            RankedListCursor anchor,
                                            ToDoubleFunction<T> scoreOf,
                                            Function<T, String> idOf) {
        for (int index = 0; index < items.size(); index++) {
            T item = items.get(index);
            if (isAfter(scoreOf.applyAsDouble(item), idOf.apply(item), anchor)) {
                return index;
            }
        }
        return items.size();
    }

    private static boolean isAfter(double score, String id, RankedListCursor anchor) {
        return Double.compare(score, anchor.score()) < 0
                || (Double.compare(score, anchor.score()) == 0 && id.compareTo(anchor.itemId()) > 0);
    }
}
