package com.recsys.service.retrieval;

import java.util.Map;

public record QuotaSpec(Map<String, Integer> slots) {

    public QuotaSpec {
        slots = Map.copyOf(slots);
    }

    public int slotsFor(String channel) {
        return slots.getOrDefault(channel, 0);
    }

    public static QuotaSpec warm(int limit) {
        int emb   = (int) Math.round(limit * 0.60);
        int trend = (int) Math.round(limit * 0.20);
        int genre = (int) Math.round(limit * 0.15);
        int pop   = Math.max(0, limit - emb - trend - genre);
        return new QuotaSpec(Map.of(
                "embedding",     emb,
                "trending",      trend,
                "genre_history", genre,
                "popularity",    pop
        ));
    }

    public static QuotaSpec cold(int limit) {
        int cs    = (int) Math.round(limit * 0.50);
        int trend = (int) Math.round(limit * 0.20);
        int pop   = (int) Math.round(limit * 0.20);
        int genre = Math.max(0, limit - cs - trend - pop);
        return new QuotaSpec(Map.of(
                "cold_start",    cs,
                "trending",      trend,
                "popularity",    pop,
                "genre_history", genre
        ));
    }
}
