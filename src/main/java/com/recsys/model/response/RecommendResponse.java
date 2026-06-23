package com.recsys.model.response;

import com.recsys.domain.prediction.ScoredItem;

import java.util.List;

public record RecommendResponse(String userId, String modelVersion, String abTestVariant, List<ScoredItem> recommendations) {}
