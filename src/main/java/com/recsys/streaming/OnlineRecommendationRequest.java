package com.recsys.streaming;

public record OnlineRecommendationRequest(int userId, String window, int k) {}
