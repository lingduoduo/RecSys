package com.recsys.infrastructure.messaging;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqsAsyncEventPublisherTest {

    @Test
    void sendBatch_splitsEventsIntoSqsBatchesOfTenAndPreservesBodies() {
        SqsClient sqs = mock(SqsClient.class);
        when(sqs.sendMessageBatch(any(SendMessageBatchRequest.class)))
                .thenReturn(SendMessageBatchResponse.builder().build());
        SqsAsyncEventPublisher publisher =
                new SqsAsyncEventPublisher(sqs, "https://sqs.us-east-1.amazonaws.com/123/events", 100, 100);

        List<String> events = java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> "{\"event\":" + i + "}")
                .toList();

        publisher.sendBatch(events);

        org.mockito.ArgumentCaptor<SendMessageBatchRequest> captor =
                org.mockito.ArgumentCaptor.forClass(SendMessageBatchRequest.class);
        verify(sqs, times(2)).sendMessageBatch(captor.capture());

        List<SendMessageBatchRequest> requests = captor.getAllValues();
        assertThat(requests).extracting(SendMessageBatchRequest::queueUrl)
                .containsOnly("https://sqs.us-east-1.amazonaws.com/123/events");
        assertThat(requests.get(0).entries()).hasSize(10);
        assertThat(requests.get(1).entries()).hasSize(2);
        assertThat(requests.stream()
                .flatMap(r -> r.entries().stream())
                .map(e -> e.messageBody())
                .toList()).containsExactlyElementsOf(events);

        publisher.close();
    }

    @Test
    void sendBatch_logsPartialFailuresAndContinues() {
        SqsClient sqs = mock(SqsClient.class);
        when(sqs.sendMessageBatch(any(SendMessageBatchRequest.class)))
                .thenReturn(SendMessageBatchResponse.builder()
                        .failed(BatchResultErrorEntry.builder()
                                .id("msg-0")
                                .code("InvalidMessageContents")
                                .message("bad body")
                                .senderFault(true)
                                .build())
                        .build())
                .thenReturn(SendMessageBatchResponse.builder().build());
        SqsAsyncEventPublisher publisher = new SqsAsyncEventPublisher(sqs, "queue-url", 100, 100);

        assertThatCode(() -> publisher.sendBatch(List.of(
                "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k")))
                .doesNotThrowAnyException();

        verify(sqs, times(2)).sendMessageBatch(any(SendMessageBatchRequest.class));
        publisher.close();
    }

    @Test
    void sendBatch_swallowsSendThrowsAndAttemptsLaterChunks() {
        SqsClient sqs = mock(SqsClient.class);
        when(sqs.sendMessageBatch(any(SendMessageBatchRequest.class)))
                .thenThrow(new RuntimeException("sqs unavailable"))
                .thenReturn(SendMessageBatchResponse.builder().build());
        SqsAsyncEventPublisher publisher = new SqsAsyncEventPublisher(sqs, "queue-url", 100, 100);

        assertThatCode(() -> publisher.sendBatch(List.of(
                "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10")))
                .doesNotThrowAnyException();

        verify(sqs, times(2)).sendMessageBatch(any(SendMessageBatchRequest.class));
        publisher.close();
    }
}
