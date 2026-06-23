package com.recsys.domain.online;

public record OnlineRecommendationRequest(int userId, String window, int k) {}
