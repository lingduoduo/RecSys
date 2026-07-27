package com.recsys.application.pagination;

import java.util.List;

public record Page<T>(List<T> items, RankedListCursor nextPosition, boolean hasMore) {
    public Page {
        items = items == null || items.isEmpty() ? List.of() : List.copyOf(items);
        if (hasMore != (nextPosition != null)) {
            throw new IllegalArgumentException("hasMore and nextPosition must agree");
        }
    }

    /**
     * @deprecated Use {@link #Page(List, RankedListCursor, boolean)}; retained only while callers
     * migrate to tuple cursors.
     */
    @Deprecated
    public Page(List<T> items, String nextCursor) {
        this(items,
                nextCursor == null || nextCursor.isBlank() ? null : RankedListCursor.decode(nextCursor),
                nextCursor != null && !nextCursor.isBlank());
    }

    /**
     * @deprecated Use {@link #nextPosition()}; retained only while callers migrate to tuple cursors.
     */
    @Deprecated
    public String nextCursor() {
        return nextPosition == null ? null : nextPosition.encode();
    }
}
