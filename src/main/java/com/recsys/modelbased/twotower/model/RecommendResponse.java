package com.recsys.modelbased.twotower.model;

import java.util.List;

public record RecommendResponse(String userId, String modelVersion, List<ScoredItem> recommendations) {}
