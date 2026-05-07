package com.recsys.streaming;

import com.recsys.models.Movie;
import com.recsys.models.User;

import java.util.List;

public record OnlineRecommendationResult(
        User user,
        String window,
        String strategy,
        List<Movie> recentMovies,
        List<Movie> trendingMovies,
        List<Movie> recommendations
) {}
