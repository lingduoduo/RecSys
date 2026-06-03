package com.recsys.infrastructure.redis.sharding;

import java.util.Objects;

public record ShardCursor(String value) {

    private static final ShardCursor START = new ShardCursor("0-0");

    public ShardCursor {
        Objects.requireNonNull(value, "value");
    }

    public static ShardCursor start() { return START; }
    public static ShardCursor of(String value) { return new ShardCursor(value); }
    public boolean isStart() { return "0-0".equals(value); }
}
