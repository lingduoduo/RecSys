package com.recsys.application.outbox;

import com.recsys.domain.outbox.OutboxEvent;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class SqsOutboxDeliveryAdapter implements OutboxDeliveryAdapter, AutoCloseable {
    private final SqsAsyncClient client;
    private final String queueUrl;
    private final Clock clock;

    public SqsOutboxDeliveryAdapter(SqsAsyncClient client, String queueUrl) {
        this(client, queueUrl, Clock.systemUTC());
    }

    SqsOutboxDeliveryAdapter(SqsAsyncClient client, String queueUrl, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        if (queueUrl == null || queueUrl.isBlank()) throw new IllegalArgumentException("queueUrl must not be blank");
        this.queueUrl = queueUrl;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public DeliveryAttempt deliver(OutboxEvent event) {
        Objects.requireNonNull(event, "event");
        Map<String, MessageAttributeValue> attributes = new LinkedHashMap<>();
        attributes.put("eventId", text(event.eventId().toString()));
        attributes.put("aggregateId", text(event.aggregateId()));
        attributes.put("eventType", text(event.eventType()));
        SendMessageRequest request = SendMessageRequest.builder().queueUrl(queueUrl).messageBody(event.payload())
                .messageAttributes(attributes).build();
        CompletableFuture<SendMessageResponse> nativeFuture = client.sendMessage(request);
        CompletableFuture<DeliveryReceipt> completion = nativeFuture
                .thenApply(ignored -> new DeliveryReceipt(clock.instant()));
        return new DeliveryAttempt(completion, () -> {
            CompletableFuture<Void> settled = new CompletableFuture<>();
            nativeFuture.whenComplete((ignored, failure) -> settled.complete(null));
            nativeFuture.cancel(false);
            return settled;
        });
    }

    private static MessageAttributeValue text(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
    @Override public void close() { client.close(); }
}
