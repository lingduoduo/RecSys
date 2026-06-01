package com.recsys.modelbased.service;

import com.recsys.features.DataManager;
import com.recsys.models.Movie;
import com.recsys.models.Rating;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class CandidateSelectionService {

    private static final int LIMIT_PER_GENRE = 50;
    private static final int GLOBAL_POOL_SIZE = 100;

    private final ModelArtifactService artifactService;
    private final DataManager dataManager = DataManager.getInstance();

    public CandidateSelectionService(ModelArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    public Set<String> selectCandidates(Integer numericUserId, Set<String> excludedItemIds) {
        Set<String> availableItems = artifactService.getAvailableItemIds();
        Set<String> excluded = excludedItemIds == null ? Set.of() : excludedItemIds;
        Set<String> candidates = new LinkedHashSet<>();

        if (numericUserId != null) {
            List<Rating> history = dataManager.getRatingsByUser(numericUserId);
            Set<Integer> watched = new HashSet<>();
            Set<String> genres = new LinkedHashSet<>();
            for (Rating rating : history) {
                watched.add(rating.movieId());
                Movie movie = dataManager.getMovieById(rating.movieId());
                if (movie != null) {
                    genres.addAll(movie.genres());
                }
            }

            for (String genre : genres) {
                for (Movie movie : dataManager.getMoviesByGenre(genre, LIMIT_PER_GENRE)) {
                    if (!watched.contains(movie.id())) {
                        addIfAvailable(candidates, availableItems, excluded, movie.id());
                    }
                }
            }
        }

        for (Movie movie : dataManager.getTopRatedMovies(GLOBAL_POOL_SIZE)) {
            addIfAvailable(candidates, availableItems, excluded, movie.id());
        }
        for (Movie movie : dataManager.getLatestMovies(GLOBAL_POOL_SIZE)) {
            addIfAvailable(candidates, availableItems, excluded, movie.id());
        }

        return candidates;
    }

    private static void addIfAvailable(Set<String> candidates, Set<String> availableItems, Set<String> excluded, int movieId) {
        String itemId = String.valueOf(movieId);
        if (!excluded.contains(itemId) && availableItems.contains(itemId)) {
            candidates.add(itemId);
        }
    }

}
