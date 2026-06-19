package com.recsys.model.service;

import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.service.retrieval.multichannel.MultiChannelRecallService;

import java.util.List;
import java.util.Objects;

/**
 * Per-variant retrieval stage: delegates candidate generation to the shared
 * {@link MultiChannelRecallService}. Warm/cold classification is resolved inside the recall
 * service via the per-variant {@link VocabMembershipEmbeddingStore} wired in {@code RecallConfig}.
 */
public class ModelRetrievalStage {

    private final MultiChannelRecallService recallService;

    public ModelRetrievalStage(MultiChannelRecallService recallService) {
        this.recallService = Objects.requireNonNull(recallService, "recallService");
    }

    public List<MovieCandidate> retrieve(RecommendationQuery query, int limit) {
        return recallService.recall(query, limit);
    }
}
