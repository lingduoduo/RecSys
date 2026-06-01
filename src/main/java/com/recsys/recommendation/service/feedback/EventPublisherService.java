package com.recsys.recommendation.service.feedback;

public interface EventPublisherService {
    EventPublisherService NOOP = event -> { };

    void publish(FeedbackEvent event);
}
