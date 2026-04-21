package com.recsys.training.modelbased.twotower.model;

import java.util.List;

public class RecommendResponse {
    private String userId;
    private String modelVersion;
    private List<ScoredItem> recommendations;

    public RecommendResponse() {
    }

    public RecommendResponse(String userId, String modelVersion, List<ScoredItem> recommendations) {
        this.userId = userId;
        this.modelVersion = modelVersion;
        this.recommendations = recommendations;
    }

    public String getUserId() {
        return userId;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public List<ScoredItem> getRecommendations() {
        return recommendations;
    }
}
