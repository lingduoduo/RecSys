package com.recsys.application.outbox;

import com.recsys.domain.outbox.OutboxEvent;

@FunctionalInterface
public interface OutboxDeliveryAdapter {
    DeliveryAttempt deliver(OutboxEvent event);
}
