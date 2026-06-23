package com.recsys.domain.recommendation;

import com.recsys.domain.item.Movie;
import com.recsys.domain.user.User;
import java.util.List;

public record RecommendationResponse(User user, List<Movie> recommendations) {}
