package com.recsys.application.pagination;

import java.util.List;

public record Page<T>(List<T> items, RankedListCursor nextPosition, boolean hasMore) {
    public Page {
        items = items == null || items.isEmpty() ? List.of() : List.copyOf(items);
        if (hasMore != (nextPosition != null)) {
            throw new IllegalArgumentException("hasMore and nextPosition must agree");
        }
    }

}
