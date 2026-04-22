package com.recsys.training.modelbased.twotower.service;

import com.recsys.features.DataManager;
import com.recsys.features.VectorMath;
import com.recsys.models.Movie;
import com.recsys.models.Rating;
import com.recsys.training.modelbased.twotower.model.ScoredItem;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RetrievalService {

    private static final int METADATA_LIMIT_PER_GENRE = 20;
    private static final double GENRE_RECALL_SCORE = 0.20;
    private static final double TOP_RATED_RECALL_SCORE = 0.10;
    private static final double LATEST_RECALL_SCORE = 0.05;

    private final ModelArtifactService artifactService;
    private final DataManager dataManager = DataManager.getInstance();

    public RetrievalService(ModelArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    public List<ScoredItem> recall(float[] userEmbedding, String userId, Set<String> candidateItemIds, int recallSize) {
        if (userEmbedding == null || candidateItemIds == null || candidateItemIds.isEmpty() || recallSize <= 0) {
            return List.of();
        }

        Map<String, ScoredItem> merged = new LinkedHashMap<>();
        mergeByBestScore(merged, retrievalCandidatesByEmbedding(userEmbedding, candidateItemIds, recallSize));
        mergeByBestScore(merged, retrievalCandidatesByMetadata(userId, candidateItemIds, recallSize));
        return List.copyOf(merged.values());
    }

    private List<ScoredItem> retrievalCandidatesByEmbedding(float[] userEmbedding, Set<String> candidateItemIds, int recallSize) {
        PriorityQueue<ScoredItem> best = new PriorityQueue<>(Comparator.comparingDouble(ScoredItem::score));

        Map<String, float[]> itemEmbeddings = artifactService.getItemEmbeddings();
        for (String itemId : candidateItemIds) {
            float[] itemEmbedding = itemEmbeddings.get(itemId);
            if (itemEmbedding == null) continue;

            double score = VectorMath.innerProduct(userEmbedding, itemEmbedding);
            if (score == Double.NEGATIVE_INFINITY) continue;
            if (best.size() < recallSize) {
                best.offer(new ScoredItem(itemId, score));
            } else if (score > best.peek().score()) {
                best.poll();
                best.offer(new ScoredItem(itemId, score));
            }
        }

        return new ArrayList<>(best);
    }

    private List<ScoredItem> retrievalCandidatesByMetadata(String userId, Set<String> candidateItemIds, int recallSize) {
        Integer numericUserId = parseUserId(userId);
        if (numericUserId == null) {
            return List.of();
        }

        List<Rating> history = dataManager.getRatingsByUser(numericUserId);
        Set<Integer> watched = new LinkedHashSet<>();
        Map<String, ScoredItem> recalled = new LinkedHashMap<>();

        Set<String> genres = new LinkedHashSet<>();
        for (Rating rating : history) {
            watched.add(rating.movieId());
            Movie movie = dataManager.getMovieById(rating.movieId());
            if (movie != null) {
                genres.addAll(movie.genres());
            }
        }

        for (String genre : genres) {
            for (Movie movie : dataManager.getMoviesByGenre(genre, METADATA_LIMIT_PER_GENRE)) {
                addMetadataCandidate(recalled, movie.id(), candidateItemIds, watched, GENRE_RECALL_SCORE);
            }
        }

        for (Movie movie : dataManager.getTopRatedMovies(recallSize)) {
            addMetadataCandidate(recalled, movie.id(), candidateItemIds, watched, TOP_RATED_RECALL_SCORE);
        }
        for (Movie movie : dataManager.getLatestMovies(recallSize)) {
            addMetadataCandidate(recalled, movie.id(), candidateItemIds, watched, LATEST_RECALL_SCORE);
        }

        return recalled.values().stream()
                .limit(recallSize)
                .toList();
    }

    private static void mergeByBestScore(Map<String, ScoredItem> merged, List<ScoredItem> candidates) {
        for (ScoredItem candidate : candidates) {
            merged.merge(
                    candidate.itemId(),
                    candidate,
                    (existing, incoming) -> incoming.score() > existing.score() ? incoming : existing
            );
        }
    }

    private static void addMetadataCandidate(
            Map<String, ScoredItem> recalled,
            int movieId,
            Set<String> candidateItemIds,
            Set<Integer> watched,
            double score
    ) {
        if (watched.contains(movieId)) {
            return;
        }
        String itemId = String.valueOf(movieId);
        if (candidateItemIds.contains(itemId)) {
            recalled.merge(itemId, new ScoredItem(itemId, score),
                    (existing, incoming) -> incoming.score() > existing.score() ? incoming : existing);
        }
    }

    private static Integer parseUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(userId);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
