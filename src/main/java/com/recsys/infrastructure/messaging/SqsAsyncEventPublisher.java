package com.recsys.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link AsyncEventPublisher} transport that writes drained JSON event bodies to one SQS queue.
 * The serving path remains best-effort: SQS failures are logged and never escape the drain loop.
 */
public class SqsAsyncEventPublisher extends AsyncEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SqsAsyncEventPublisher.class);
    private static final int MAX_BATCH_SIZE = 10;

    private final SqsClient sqsClient;
    private final String queueUrl;
    private final boolean closeClient;

    public SqsAsyncEventPublisher(SqsClient sqsClient, String queueUrl) {
        this(sqsClient, queueUrl, false);
    }

    public SqsAsyncEventPublisher(SqsClient sqsClient, String queueUrl, boolean closeClient) {
        this.sqsClient = Objects.requireNonNull(sqsClient, "sqsClient");
        this.queueUrl = requireNonBlank(queueUrl, "queueUrl");
        this.closeClient = closeClient;
    }

    SqsAsyncEventPublisher(SqsClient sqsClient, String queueUrl, int queueCapacity, int batchSize) {
        super(queueCapacity, batchSize);
        this.sqsClient = Objects.requireNonNull(sqsClient, "sqsClient");
        this.queueUrl = requireNonBlank(queueUrl, "queueUrl");
        this.closeClient = false;
    }

    @Override
    protected void sendBatch(List<String> events) {
        super.sendBatch(events);
        if (events == null || events.isEmpty() || sqsClient == null) {
            return;
        }

        for (int start = 0; start < events.size(); start += MAX_BATCH_SIZE) {
            int end = Math.min(events.size(), start + MAX_BATCH_SIZE);
            sendChunk(events.subList(start, end), start);
        }
    }

    private void sendChunk(List<String> events, int offset) {
        List<SendMessageBatchRequestEntry> entries = new ArrayList<>(events.size());
        for (int i = 0; i < events.size(); i++) {
            entries.add(SendMessageBatchRequestEntry.builder()
                    .id("msg-" + (offset + i))
                    .messageBody(events.get(i))
                    .build());
        }

        try {
            SendMessageBatchResponse response = sqsClient.sendMessageBatch(
                    SendMessageBatchRequest.builder()
                            .queueUrl(queueUrl)
                            .entries(entries)
                            .build());
            for (BatchResultErrorEntry failure : response.failed()) {
                log.warn("SQS event send failed for entry '{}' code={} senderFault={} message={}",
                        failure.id(), failure.code(), failure.senderFault(), failure.message());
            }
        } catch (RuntimeException e) {
            log.warn("SQS event batch send threw for queue '{}' (batchSize={})",
                    queueUrlSuffix(queueUrl), events.size(), e);
        }
    }

    @Override
    public void close() {
        super.close();
        if (closeClient) {
            sqsClient.close();
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String queueUrlSuffix(String queueUrl) {
        int slash = queueUrl == null ? -1 : queueUrl.lastIndexOf('/');
        return slash >= 0 && slash + 1 < queueUrl.length() ? queueUrl.substring(slash + 1) : queueUrl;
    }
}
