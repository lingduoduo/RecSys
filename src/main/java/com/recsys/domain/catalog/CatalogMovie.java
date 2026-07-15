package com.recsys.domain.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record CatalogMovie(
        long id,
        String title,
        Integer year,
        String genre,
        BigDecimal popularityScore,
        Instant updatedAt
) {
    public CatalogMovie {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(popularityScore, "popularityScore");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
