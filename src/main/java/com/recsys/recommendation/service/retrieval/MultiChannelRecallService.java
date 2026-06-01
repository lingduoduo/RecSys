package com.recsys.recommendation.service.retrieval;

import com.recsys.recommendation.model.MovieCandidate;
import com.recsys.recommendation.model.RecommendationQuery;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MultiChannelRecallService {
    private final List<RecallChannel> channels;

    public MultiChannelRecallService(List<RecallChannel> channels) {
        if (channels == null || channels.isEmpty()) {
            throw new IllegalArgumentException("at least one recall channel is required");
        }
        this.channels = List.copyOf(channels);
    }

    public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
        Objects.requireNonNull(query, "query");
        if (limit <= 0) {
            return List.of();
        }

        Map<String, MovieCandidate> merged = new LinkedHashMap<>();
        for (RecallChannel channel : channels) {
            List<MovieCandidate> recalled = channel.recall(query, limit);
            if (recalled == null) {
                continue;
            }
            for (MovieCandidate candidate : recalled) {
                if (query.excludedItemIds().contains(candidate.itemId())) {
                    continue;
                }
                merged.merge(candidate.itemId(), candidate,
                        (existing, incoming) -> incoming.score() > existing.score() ? incoming : existing);
            }
        }

        return merged.values().stream()
                .sorted(Comparator.comparingDouble(MovieCandidate::score).reversed()
                        .thenComparing(MovieCandidate::itemId))
                .limit(limit)
                .toList();
    }
}
