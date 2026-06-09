package com.recsys.domain;

public record Rating(int userId, int movieId, float rating, long timestamp) {}
