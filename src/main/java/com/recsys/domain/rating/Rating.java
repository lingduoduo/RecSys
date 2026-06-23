package com.recsys.domain.rating;

public record Rating(int userId, int movieId, float rating, long timestamp) {}
