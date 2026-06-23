package com.recsys.online.serving;

import com.recsys.domain.item.Movie;
import com.recsys.domain.user.User;

import java.util.List;

public record OnlineRecommendationResult(
        User user,
        String window,
        String strategy,
        List<Movie> recentMovies,
        List<Movie> trendingMovies,
        List<Movie> recommendations
) {}
