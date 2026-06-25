package com.recsys.application.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.domain.saga.SagaException;
import com.recsys.domain.saga.SagaTransitionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class SqsSagaEventPublisher implements SagaEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SqsSagaEventPublisher.class);
    private static final String STRING = "String";

    private final SqsClient sqsClient;
    private final String queueUrl;
    private final ObjectMapper objectMapper;
    private final boolean bestEffort;

    public SqsSagaEventPublisher(SqsClient sqsClient, String queueUrl) {
        this(sqsClient, queueUrl, new ObjectMapper(), false);
    }

    public SqsSagaEventPublisher(SqsClient sqsClient, String queueUrl,
                                 ObjectMapper objectMapper,
                                 boolean bestEffort) {
        this.sqsClient = Objects.requireNonNull(sqsClient, "sqsClient");
        this.queueUrl = requireNonBlank(queueUrl, "queueUrl");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper")
                .copy()
                .findAndRegisterModules();
        this.bestEffort = bestEffort;
    }

    @Override
    public void publish(SagaTransitionEvent event) {
        Objects.requireNonNull(event, "event");
        try {
            String body = objectMapper.writeValueAsString(event);
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .messageAttributes(attributes(event))
                    .build());
        } catch (Exception e) {
            if (bestEffort) {
                log.warn("failed to publish saga event '{}' to SQS", event.eventId(), e);
                return;
            }
            throw new SagaException("failed to publish saga event to SQS: " + event.eventId(), e);
        }
    }

    private static Map<String, MessageAttributeValue> attributes(SagaTransitionEvent event) {
        Map<String, MessageAttributeValue> attrs = new LinkedHashMap<>();
        put(attrs, "sagaId", event.sagaId());
        put(attrs, "sagaType", event.sagaType());
        put(attrs, "eventType", event.type() == null ? null : event.type().name());
        put(attrs, "status", event.status() == null ? null : event.status().name());
        put(attrs, "correlationId", event.correlationId());
        return attrs;
    }

    private static void put(Map<String, MessageAttributeValue> attrs, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        attrs.put(name, MessageAttributeValue.builder()
                .dataType(STRING)
                .stringValue(value)
                .build());
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
