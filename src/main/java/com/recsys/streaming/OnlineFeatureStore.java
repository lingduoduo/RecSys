package com.recsys.streaming;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.ArrayList;
import java.util.List;

public final class OnlineFeatureStore implements RecentHistoryStore {
    private final JedisPool pool;

    public OnlineFeatureStore(JedisPool pool) {
        this.pool = pool;
    }

    @Override
    public List<Integer> getRecentMovieIds(int userId, int limit) {
        String key = "user:" + userId + ":recent_movies";
        try (Jedis jedis = pool.getResource()) {
            String value = jedis.get(key);
            if (value == null || value.isBlank()) {
                return List.of();
            }

            String[] tokens = value.trim().split("\\s+");
            List<Integer> ids = new ArrayList<>(Math.min(tokens.length, limit));
            int start = Math.max(0, tokens.length - limit);
            for (int i = start; i < tokens.length; i++) {
                try {
                    ids.add(Integer.parseInt(tokens[i]));
                } catch (NumberFormatException ignore) {
                    // Ignore malformed feature values so one bad token does not break the demo.
                }
            }
            return List.copyOf(ids);
        }
    }
}
