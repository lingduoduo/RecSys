package com.recsys.domain.catalog;

import java.util.List;
import java.util.Objects;

public record CatalogPage(List<CatalogMovie> items, String nextCursor, boolean hasMore) {
    public CatalogPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (hasMore != (nextCursor != null)) {
            throw new IllegalArgumentException("hasMore and nextCursor must agree");
        }
    }
}
