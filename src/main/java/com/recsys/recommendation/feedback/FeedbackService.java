package com.recsys.recommendation.feedback;

import java.util.Objects;

public class FeedbackService {
    private final EventPublisherService publisher;

    public FeedbackService(EventPublisherService publisher) {
        this.publisher = publisher == null ? EventPublisherService.NOOP : publisher;
    }

    public void record(FeedbackEvent event) {
        publisher.publish(Objects.requireNonNull(event, "event"));
    }
}
