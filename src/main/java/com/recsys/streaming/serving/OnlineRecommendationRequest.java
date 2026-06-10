package com.recsys.streaming.serving;

public record OnlineRecommendationRequest(int userId, String window, int k) {}
