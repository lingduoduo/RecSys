package com.recsys.online.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.online.learner.OnlineJoiner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ExperienceCollector {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REQUEST_ID_FEATURE = "event.requestId";

    public List<RecommendationExperience> collect(List<OnlineJoiner.JoinedSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return List.of();
        }

        Map<ExperienceKey, List<OnlineJoiner.JoinedSample>> samplesByRequest = new LinkedHashMap<>();
        for (OnlineJoiner.JoinedSample sample : samples) {
            if (sample == null) {
                continue;
            }
            ExperienceKey key = new ExperienceKey(sample.userId(), requestId(sample));
            samplesByRequest.computeIfAbsent(key, ignored -> new ArrayList<>()).add(sample);
        }

        List<RecommendationExperience> experiences = new ArrayList<>(samplesByRequest.size());
        for (Map.Entry<ExperienceKey, List<OnlineJoiner.JoinedSample>> entry : samplesByRequest.entrySet()) {
            experiences.add(toExperience(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(experiences);
    }

    public String toJson(RecommendationExperience experience) {
        try {
            return MAPPER.writeValueAsString(experience);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unable to serialize recommendation experience", e);
        }
    }

    private static RecommendationExperience toExperience(ExperienceKey key,
                                                         List<OnlineJoiner.JoinedSample> samples) {
        List<OnlineJoiner.JoinedSample> rankedSamples = samples.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparingInt(ExperienceCollector::rankOrMax)
                        .thenComparingLong(OnlineJoiner.JoinedSample::eventTimeMillis)
                        .thenComparingInt(OnlineJoiner.JoinedSample::movieId))
                .toList();

        if (rankedSamples.isEmpty()) {
            throw new IllegalArgumentException("experience requires at least one sample");
        }

        Map<Integer, ItemAccumulator> itemByMovieId = new LinkedHashMap<>();
        int maxLabel = 0;
        long eventTimeMillis = Long.MAX_VALUE;
        for (int i = 0; i < rankedSamples.size(); i++) {
            OnlineJoiner.JoinedSample sample = rankedSamples.get(i);
            int label = sample.label();
            maxLabel = Math.max(maxLabel, label);
            eventTimeMillis = Math.min(eventTimeMillis, sample.eventTimeMillis());
            itemByMovieId.computeIfAbsent(sample.movieId(), ItemAccumulator::new)
                    .merge(sample, rankOrDefault(sample, i + 1));
        }

        List<ItemFeedback> items = itemByMovieId.values().stream()
                .sorted(Comparator
                        .comparingInt((ItemAccumulator item) -> item.rank)
                        .thenComparingInt(item -> item.movieId))
                .map(ItemAccumulator::toItemFeedback)
                .toList();

        return new RecommendationExperience(
                key.requestId,
                key.userId,
                eventTimeMillis,
                maxLabel,
                List.copyOf(items)
        );
    }

    private static String requestId(OnlineJoiner.JoinedSample sample) {
        String requestId = featuresOf(sample).get(REQUEST_ID_FEATURE);
        if (requestId != null && !requestId.isBlank()) {
            return requestId.trim();
        }
        return "event:" + sample.eventId();
    }

    private static int rankOrMax(OnlineJoiner.JoinedSample sample) {
        return rankOrDefault(sample, Integer.MAX_VALUE);
    }

    private static int rankOrDefault(OnlineJoiner.JoinedSample sample, int defaultRank) {
        String rank = featuresOf(sample).get("event.rank");
        if (rank == null || rank.isBlank()) {
            return defaultRank;
        }
        try {
            return Math.max(1, Integer.parseInt(rank.trim()));
        } catch (NumberFormatException ignored) {
            return defaultRank;
        }
    }

    private static Map<String, String> featuresOf(OnlineJoiner.JoinedSample sample) {
        return sample.features() == null ? Map.of() : sample.features();
    }

    private record ExperienceKey(int userId, String requestId) {}

    private static final class ItemAccumulator {
        private final int movieId;
        private int rank = Integer.MAX_VALUE;
        private int label = 0;
        private String eventType = "";
        private Map<String, String> features = Map.of();

        private ItemAccumulator(int movieId) {
            this.movieId = movieId;
        }

        private void merge(OnlineJoiner.JoinedSample sample, int sampleRank) {
            rank = Math.min(rank, sampleRank);
            if (sample.label() >= label) {
                label = sample.label();
                eventType = sample.eventType();
                features = featuresOf(sample);
            }
        }

        private ItemFeedback toItemFeedback() {
            return new ItemFeedback(movieId, rank, label, eventType, features);
        }
    }

    public record RecommendationExperience(String requestId,
                                           int userId,
                                           long eventTimeMillis,
                                           int label,
                                           List<ItemFeedback> items) {}

    public record ItemFeedback(int movieId,
                               int rank,
                               int label,
                               String eventType,
                               Map<String, String> features) {}
}
