package com.recsys.infrastructure.redis.sharding;

import java.util.List;
import java.util.Objects;

public record Page<T>(List<T> records, ShardCursor next) {

    public Page {
        Objects.requireNonNull(records, "records");
        records = List.copyOf(records);
    }

    public boolean hasMore() { return next != null; }
    public static <T> Page<T> empty() { return new Page<>(List.of(), null); }
}
