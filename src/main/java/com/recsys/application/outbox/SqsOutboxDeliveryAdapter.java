package com.recsys.application.outbox;

import com.recsys.domain.outbox.OutboxEvent;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.*;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;

import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class SqsOutboxDeliveryAdapter implements OutboxDeliveryAdapter, AutoCloseable {
    private final SqsAsyncClient client;
    private final String queueUrl;
    private final Clock clock;
    private final Duration deliveryDeadline;

    public SqsOutboxDeliveryAdapter(SqsAsyncClient client, String queueUrl, Duration deliveryDeadline) {
        this(client, queueUrl, Clock.systemUTC(), deliveryDeadline);
    }

    SqsOutboxDeliveryAdapter(SqsAsyncClient client, String queueUrl, Clock clock, Duration deliveryDeadline) {
        this.client = Objects.requireNonNull(client, "client");
        if (queueUrl == null || queueUrl.isBlank()) throw new IllegalArgumentException("queueUrl must not be blank");
        this.queueUrl = queueUrl;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.deliveryDeadline = positive(deliveryDeadline);
    }

    @Override public DeliveryAttempt deliver(OutboxEvent event) {
        Objects.requireNonNull(event, "event");
        Map<String, MessageAttributeValue> attributes = new LinkedHashMap<>();
        attributes.put("eventId", text(event.eventId().toString()));
        attributes.put("aggregateId", text(event.aggregateId()));
        attributes.put("eventType", text(event.eventType()));
        AwsRequestOverrideConfiguration timeout = AwsRequestOverrideConfiguration.builder()
                .apiCallTimeout(deliveryDeadline).apiCallAttemptTimeout(deliveryDeadline).build();
        SendMessageRequest request = SendMessageRequest.builder().queueUrl(queueUrl).messageBody(event.payload())
                .overrideConfiguration(timeout)
                .messageAttributes(attributes).build();
        CompletableFuture<SendMessageResponse> nativeFuture = client.sendMessage(request);
        CompletableFuture<DeliveryReceipt> completion = nativeFuture
                .thenApply(ignored -> new DeliveryReceipt(clock.instant()));
        return new DeliveryAttempt(completion, () -> {
            CompletableFuture<Void> settled = new CompletableFuture<>();
            nativeFuture.whenComplete((ignored, failure) -> settled.complete(null));
            return settled;
        });
    }

    private static MessageAttributeValue text(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
    @Override public void close() { client.close(); }
    @Override public Optional<Duration> deliveryDeadline() { return Optional.of(deliveryDeadline); }
    private static Duration positive(Duration deadline) {
        Objects.requireNonNull(deadline, "deliveryDeadline");
        if (deadline.isZero() || deadline.isNegative())
            throw new IllegalArgumentException("deliveryDeadline must be positive");
        return deadline;
    }
}
