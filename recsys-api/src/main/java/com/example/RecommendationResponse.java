package com.example;

import java.util.List;

public class RecommendationResponse {

    private User user;
    private List<Movie> recommendations;

    public RecommendationResponse(User user, List<Movie> recommendations) {
        this.user = user;
        this.recommendations = recommendations;
    }

    public User getUser() {
        return user;
    }

    public List<Movie> getRecommendations() {
        return recommendations;
    }
}
