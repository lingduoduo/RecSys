package com.recsys.infrastructure.observability;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SplunkHecClientTest {

    record Captured(String authorization, String contentType, String body) {}

    static final ConcurrentLinkedQueue<Captured> captured = new ConcurrentLinkedQueue<>();

    @RegisterExtension
    static final ServerExtension collector = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/services/collector/event", (ctx, req) ->
                    HttpResponse.of(req.aggregate().thenApply(agg -> {
                        captured.add(new Captured(
                                agg.headers().get("authorization"),
                                agg.headers().get("content-type"),
                                agg.contentUtf8()));
                        return HttpResponse.of(HttpStatus.OK, com.linecorp.armeria.common.MediaType.JSON,
                                "{\"text\":\"Success\",\"code\":0}");
                    })));
            sb.service("/services/collector/unavailable", (ctx, req) ->
                    HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE));
            sb.service("/services/collector/forbidden", (ctx, req) ->
                    HttpResponse.of(HttpStatus.FORBIDDEN));
        }
    };

    private static SplunkHecClient clientFor(String path) {
        return new SplunkHecClient(SplunkHecConfig.from(Map.of(
                "SPLUNK_HEC_TOKEN", "tok-abc",
                "SPLUNK_HEC_URL", "http://127.0.0.1:" + collector.httpPort() + path,
                "SPLUNK_HEC_TIMEOUT_MS", "2000")));
    }

    @Test
    void postsBodyWithSplunkAuthorizationHeader() {
        captured.clear();

        SplunkHecClient.Outcome outcome = clientFor("/services/collector/event")
                .send("{\"event\":{\"message\":\"one\"}}\n{\"event\":{\"message\":\"two\"}}");

        assertThat(outcome).isEqualTo(SplunkHecClient.Outcome.SUCCESS);
        Captured request = captured.poll();
        assertThat(request).isNotNull();
        assertThat(request.authorization()).isEqualTo("Splunk tok-abc");
        assertThat(request.contentType()).startsWith("application/json");
        assertThat(request.body()).isEqualTo(
                "{\"event\":{\"message\":\"one\"}}\n{\"event\":{\"message\":\"two\"}}");
    }

    @Test
    void serverErrorIsReportedNotThrown() {
        SplunkHecClient client = clientFor("/services/collector/unavailable");

        assertThatCode(() -> assertThat(client.send("{}"))
                .isEqualTo(SplunkHecClient.Outcome.SERVER_ERROR))
                .doesNotThrowAnyException();
    }

    @Test
    void forbiddenIsReportedAsAuthRejected() {
        assertThat(clientFor("/services/collector/forbidden").send("{}"))
                .isEqualTo(SplunkHecClient.Outcome.AUTH_REJECTED);
    }

    @Test
    void unreachableCollectorIsReportedNotThrown() {
        // Port 1 is reserved and never listening, so this exercises the connect-failure path.
        SplunkHecClient client = new SplunkHecClient(SplunkHecConfig.from(Map.of(
                "SPLUNK_HEC_TOKEN", "tok-abc",
                "SPLUNK_HEC_URL", "http://127.0.0.1:1/services/collector/event",
                "SPLUNK_HEC_TIMEOUT_MS", "500")));

        assertThatCode(() -> assertThat(client.send("{}"))
                .isEqualTo(SplunkHecClient.Outcome.TRANSPORT_FAILURE))
                .doesNotThrowAnyException();
    }

    @Test
    void interruptionIsReportedAndFlagRestored() throws Exception {
        SplunkHecClient client = new SplunkHecClient(SplunkHecConfig.from(Map.of(
                "SPLUNK_HEC_TOKEN", "tok-abc",
                "SPLUNK_HEC_URL", "http://127.0.0.1:1/services/collector/event",
                "SPLUNK_HEC_TIMEOUT_MS", "500")));

        Thread.currentThread().interrupt();
        try {
            assertThat(client.send("{}")).isEqualTo(SplunkHecClient.Outcome.TRANSPORT_FAILURE);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // clear so it does not leak into the next test
        }
    }
}
