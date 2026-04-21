package com.recsys.models;

import java.util.List;

public record RecommendationResponse(User user, List<Movie> recommendations) {}
