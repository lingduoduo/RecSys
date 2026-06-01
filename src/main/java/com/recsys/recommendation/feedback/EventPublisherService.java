package com.recsys.recommendation.feedback;

public interface EventPublisherService {
    EventPublisherService NOOP = event -> { };

    void publish(FeedbackEvent event);
}
