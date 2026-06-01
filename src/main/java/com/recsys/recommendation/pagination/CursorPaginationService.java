package com.recsys.recommendation.pagination;

import java.util.List;

public class CursorPaginationService {
    public <T> Page<T> page(List<T> rankedItems, String cursor, int limit) {
        if (rankedItems == null || rankedItems.isEmpty() || limit <= 0) {
            return new Page<>(List.of(), null);
        }

        int offset = RankedListCursor.decode(cursor).offset();
        if (offset >= rankedItems.size()) {
            return new Page<>(List.of(), null);
        }

        int endExclusive = Math.min(rankedItems.size(), offset + limit);
        String nextCursor = endExclusive < rankedItems.size()
                ? new RankedListCursor(endExclusive).encode()
                : null;
        return new Page<>(rankedItems.subList(offset, endExclusive), nextCursor);
    }
}
