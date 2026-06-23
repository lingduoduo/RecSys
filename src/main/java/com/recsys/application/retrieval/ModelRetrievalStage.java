package com.recsys.application.retrieval;

import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.infrastructure.store.RecentHistoryStore;
import com.recsys.application.retrieval.multichannel.MultiChannelRecallService;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Per-variant retrieval stage: delegates candidate generation to the shared
 * {@link MultiChannelRecallService}, and excludes the user's recent watched movies from the
 * results (so just-watched titles do not reappear). Warm/cold classification is resolved inside
 * the recall service via the per-variant {@link VocabMembershipEmbeddingStore} wired in
 * {@code RecallConfig}.
 */
public class ModelRetrievalStage {

    private static final int RECENT_EXCLUDE_LIMIT = 20;

    private final MultiChannelRecallService recallService;
    private final RecentHistoryStore recentHistoryStore;

    public ModelRetrievalStage(MultiChannelRecallService recallService, RecentHistoryStore recentHistoryStore) {
        this.recallService = Objects.requireNonNull(recallService, "recallService");
        this.recentHistoryStore = Objects.requireNonNull(recentHistoryStore, "recentHistoryStore");
    }

    public List<MovieCandidate> retrieve(RecommendationQuery query, int limit) {
        return recallService.recall(withRecentExclusions(query), limit);
    }

    /**
     * Adds the user's recent watched ids to the query's excluded set. Non-numeric userId or any
     * store/Redis error skips augmentation — retrieval must never fail on the recent-history path.
     */
    private RecommendationQuery withRecentExclusions(RecommendationQuery query) {
        try {
            int userId = Integer.parseInt(query.userId());
            List<Integer> recent = recentHistoryStore.getRecentMovieIds(userId, RECENT_EXCLUDE_LIMIT);
            if (recent.isEmpty()) {
                return query;
            }
            Set<String> excluded = new HashSet<>(query.excludedItemIds());
            for (Integer id : recent) {
                excluded.add(String.valueOf(id));
            }
            return new RecommendationQuery(query.userId(), query.limit(), excluded, query.cursor());
        } catch (NumberFormatException e) {
            return query;
        } catch (RuntimeException e) {
            return query;
        }
    }
}
