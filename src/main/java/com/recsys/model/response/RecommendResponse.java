package com.recsys.model.response;

import com.recsys.model.dto.ScoredItem;

import java.util.List;

public record RecommendResponse(String userId, String modelVersion, String abTestVariant, List<ScoredItem> recommendations) {}
