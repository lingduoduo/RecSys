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
            // Mirrors a real HEC rejection body — that text is the whole reason the client
            // reads the response instead of discarding it.
            sb.service("/services/collector/unavailable", (ctx, req) ->
                    HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE,
                            com.linecorp.armeria.common.MediaType.JSON,
                            "{\"text\":\"Server is busy\",\"code\":9}"));
            sb.service("/services/collector/forbidden", (ctx, req) ->
                    HttpResponse.of(HttpStatus.FORBIDDEN,
                            com.linecorp.armeria.common.MediaType.JSON,
                            "{\"text\":\"Invalid token\",\"code\":4}"));
            // A proxy that echoes the request headers back in its error page — the realistic
            // way our own Authorization header ends up in a response body.
            sb.service("/services/collector/echoes-token", (ctx, req) ->
                    HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                            com.linecorp.armeria.common.MediaType.PLAIN_TEXT_UTF_8,
                            "Bad gateway. Request was:\nAuthorization: Splunk tok-abc\nHost: x"));
            // Oversized body with embedded newlines: bounds + control-character stripping.
            sb.service("/services/collector/huge", (ctx, req) ->
                    HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                            com.linecorp.armeria.common.MediaType.HTML_UTF_8,
                            "<html>\n" + "PADDING\n".repeat(500) + "</html>"));
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
    void capturesSplunksOwnRejectionText() {
        // The Outcome enum alone cannot distinguish a bad index from back-pressure from a
        // revoked token. HEC explains itself in the response body; the appender puts this
        // string in its warning so an operator has something to act on.
        SplunkHecClient busy = clientFor("/services/collector/unavailable");
        busy.send("{}");
        assertThat(busy.lastFailureDetail()).contains("HTTP 503").contains("Server is busy");

        SplunkHecClient rejected = clientFor("/services/collector/forbidden");
        rejected.send("{}");
        assertThat(rejected.lastFailureDetail()).contains("HTTP 403").contains("Invalid token");
    }

    @Test
    void failureDetailIsClearedAfterASuccess() {
        // A stale detail from an earlier outage must not be reported alongside a later,
        // unrelated failure — it would send the operator after the wrong cause.
        SplunkHecClient client = clientFor("/services/collector/event");
        assertThat(client.lastFailureDetail()).isNull();

        client.send("{\"event\":{\"message\":\"ok\"}}");
        assertThat(client.lastFailureDetail()).isNull();
    }

    @Test
    void transportFailureDetailNamesTheCause() {
        SplunkHecClient client = new SplunkHecClient(SplunkHecConfig.from(Map.of(
                "SPLUNK_HEC_TOKEN", "tok-abc",
                "SPLUNK_HEC_URL", "http://127.0.0.1:1/services/collector/event",
                "SPLUNK_HEC_TIMEOUT_MS", "500")));

        client.send("{}");

        assertThat(client.lastFailureDetail())
                .as("a connect failure should name the exception, not be empty")
                .isNotBlank();
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
    void interruptionIsIndeterminateAndRestoresTheFlag() throws Exception {
        SplunkHecClient client = new SplunkHecClient(SplunkHecConfig.from(Map.of(
                "SPLUNK_HEC_TOKEN", "tok-abc",
                "SPLUNK_HEC_URL", "http://127.0.0.1:1/services/collector/event",
                "SPLUNK_HEC_TIMEOUT_MS", "500")));

        Thread.currentThread().interrupt();
        try {
            // INDETERMINATE, not TRANSPORT_FAILURE: an InterruptedException does not tell us
            // whether the request was written. Reporting a definite loss we cannot substantiate
            // is worse than reporting "unknown" — and it would hide that these are precisely
            // the events a retry could duplicate.
            assertThat(client.send("{}")).isEqualTo(SplunkHecClient.Outcome.INDETERMINATE);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(client.lastFailureDetail()).contains("delivery unknown");
        } finally {
            Thread.interrupted(); // clear so it does not leak into the next test
        }
    }

    @Test
    void connectionRefusedIsADefiniteLossNotIndeterminate() {
        // Nothing was ever written to a socket, so unlike an interrupt or a timeout this one
        // IS a known loss. Keeping the two apart is the whole point of the distinction.
        SplunkHecClient client = new SplunkHecClient(SplunkHecConfig.from(Map.of(
                "SPLUNK_HEC_TOKEN", "tok-abc",
                "SPLUNK_HEC_URL", "http://127.0.0.1:1/services/collector/event",
                "SPLUNK_HEC_TIMEOUT_MS", "500")));

        assertThat(client.send("{}")).isEqualTo(SplunkHecClient.Outcome.TRANSPORT_FAILURE);
    }

    @Test
    void redactsTheHecTokenFromALoggedResponseBody() {
        // A proxy or misrouted endpoint can echo the request back, including our Authorization
        // header. That body reaches a WARN line and lands in logs/*.log and Splunk itself, so
        // the token must not survive the trip.
        SplunkHecClient client = clientFor("/services/collector/echoes-token");

        assertThat(client.send("{}")).isEqualTo(SplunkHecClient.Outcome.SERVER_ERROR);

        assertThat(client.lastFailureDetail())
                .as("the configured token must never reach a log line")
                .doesNotContain("tok-abc")
                .contains("<redacted");
    }

    @Test
    void boundsAnOversizedResponseBodyAndStripsControlCharacters() {
        SplunkHecClient client = clientFor("/services/collector/huge");
        client.send("{}");

        String detail = client.lastFailureDetail();
        assertThat(detail).endsWith("...<truncated>");
        // 300 chars + the "HTTP 500: " prefix + the truncation marker — bounded, not megabytes.
        assertThat(detail.length()).isLessThan(400);
        // A body containing newlines must not be able to forge extra log lines.
        assertThat(detail).doesNotContain("\n").doesNotContain("\r");
    }
}
