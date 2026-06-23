package com.recsys.application.retrieval.coldstart;

import java.util.Map;
import java.util.Objects;

public record QuotaSpec(Map<String, Integer> slots) {

    public QuotaSpec {
        slots = Map.copyOf(slots);
    }

    public int slotsFor(String channel) {
        Objects.requireNonNull(channel, "channel");
        return slots.getOrDefault(channel, 0);
    }
}
