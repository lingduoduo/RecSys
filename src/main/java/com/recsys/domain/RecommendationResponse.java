package com.recsys.domain;

import java.util.List;

public record RecommendationResponse(User user, List<Movie> recommendations) {}
