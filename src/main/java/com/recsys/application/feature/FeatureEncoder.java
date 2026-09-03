package com.recsys.application.feature;
import com.recsys.application.model.ModelArtifactService;

import com.recsys.api.request.RecommendRequest;
import java.util.Map;

public class FeatureEncoder {

    /** The user-vocab key every bundle uses for the unknown-user row. */
    public static final String UNKNOWN_USER = "__UNK__";

    private final ModelArtifactService artifactService;
    private final int unkIndex;

    public FeatureEncoder(ModelArtifactService artifactService) {
        this.artifactService = artifactService;
        this.unkIndex = artifactService.getUserVocab().getOrDefault(UNKNOWN_USER, 0);
    }

    public EncodedFeatures encode(RecommendRequest request) {
        Map<String, Integer> userVocab = artifactService.getUserVocab();
        long userId = userVocab.getOrDefault(request.getUserId(), unkIndex);
        return new EncodedFeatures(userId);
    }

    public Long encodeItemId(String itemId) {
        Integer itemIndex = artifactService.getItemVocab().get(itemId);
        return itemIndex == null ? null : itemIndex.longValue();
    }

    public static class EncodedFeatures {
        private final long userId;

        public EncodedFeatures(long userId) {
            this.userId = userId;
        }

        public long getUserId() {
            return userId;
        }
    }
}
