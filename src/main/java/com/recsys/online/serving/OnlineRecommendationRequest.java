package com.recsys.online.serving;

public record OnlineRecommendationRequest(int userId, String window, int k) {}
