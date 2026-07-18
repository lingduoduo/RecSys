package com.recsys.application.outbox;

import com.recsys.domain.outbox.OutboxEvent;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface OutboxDeliveryAdapter {
    CompletionStage<DeliveryReceipt> deliver(OutboxEvent event);
}
