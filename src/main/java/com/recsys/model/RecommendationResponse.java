package com.recsys.model;

import java.util.List;

public record RecommendationResponse(User user, List<Movie> recommendations) {}
