package com.recsys.models;

public record Rating(int userId, int movieId, float rating, long timestamp) {}
