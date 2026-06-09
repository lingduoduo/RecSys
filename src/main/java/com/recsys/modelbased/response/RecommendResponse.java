package com.recsys.modelbased.response;

import com.recsys.modelbased.dto.ScoredItem;

import java.util.List;

public record RecommendResponse(String userId, String modelVersion, String abTestVariant, List<ScoredItem> recommendations) {}
