package com.recsys.application.outbox;

import com.recsys.domain.outbox.OutboxEvent;
import java.time.Duration;
import java.util.Optional;

@FunctionalInterface
public interface OutboxDeliveryAdapter {
    DeliveryAttempt deliver(OutboxEvent event);

    /** Deadline built into the native transport, when the adapter owns one. */
    default Optional<Duration> deliveryDeadline() { return Optional.empty(); }
}
