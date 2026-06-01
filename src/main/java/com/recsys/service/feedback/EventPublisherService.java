package com.recsys.service.feedback;

public interface EventPublisherService {
    EventPublisherService NOOP = event -> { };

    void publish(FeedbackEvent event);
}
