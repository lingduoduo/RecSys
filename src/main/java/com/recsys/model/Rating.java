package com.recsys.model;

public record Rating(int userId, int movieId, float rating, long timestamp) {}
