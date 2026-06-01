package com.recsys.recommendation.pagination;

import java.util.List;

public record Page<T>(List<T> items, String nextCursor) {
    public Page {
        items = items == null || items.isEmpty() ? List.of() : List.copyOf(items);
        nextCursor = nextCursor == null || nextCursor.isBlank() ? null : nextCursor;
    }
}
