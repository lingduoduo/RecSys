package com.recsys.infrastructure.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SplunkHecEventSerializerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SplunkHecEventSerializer serializer =
            new SplunkHecEventSerializer("host-1", "api-gateway", "recsys:app:log", "recsys");

    private static LoggingEvent event(Level level, String message) {
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        LoggingEvent event = new LoggingEvent();
        event.setLoggerContext(context);
        event.setLoggerName("com.recsys.application.gateway.LlmProxy");
        event.setLevel(level);
        event.setMessage(message);
        event.setThreadName("armeria-common-worker-1");
        event.setTimeStamp(1_753_970_000_123L);
        return event;
    }

    private JsonNode parse(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void mapsEnvelopeFields() throws Exception {
        JsonNode node = parse(serializer.toJson(event(Level.WARN, "upstream timed out")));

        assertThat(node.get("host").asText()).isEqualTo("host-1");
        assertThat(node.get("source").asText()).isEqualTo("api-gateway");
        assertThat(node.get("sourcetype").asText()).isEqualTo("recsys:app:log");
        assertThat(node.get("index").asText()).isEqualTo("recsys");
    }

    @Test
    void timeIsEpochSecondsWithMillisecondFraction() throws Exception {
        String json = serializer.toJson(event(Level.INFO, "hello"));

        assertThat(json).contains("\"time\":1753970000.123");
        assertThat(parse(json).get("time").decimalValue())
                .isEqualByComparingTo(new BigDecimal("1753970000.123"));
    }

    @Test
    void mapsEventFields() throws Exception {
        JsonNode payload = parse(serializer.toJson(event(Level.WARN, "upstream timed out"))).get("event");

        assertThat(payload.get("level").asText()).isEqualTo("WARN");
        assertThat(payload.get("logger").asText()).isEqualTo("com.recsys.application.gateway.LlmProxy");
        assertThat(payload.get("thread").asText()).isEqualTo("armeria-common-worker-1");
        assertThat(payload.get("message").asText()).isEqualTo("upstream timed out");
        assertThat(payload.has("exception")).isFalse();
    }

    @Test
    void formatsParameterizedMessage() throws Exception {
        LoggingEvent event = event(Level.INFO, "took {}ms for user {}");
        event.setArgumentArray(new Object[]{42, "u-7"});

        JsonNode payload = parse(serializer.toJson(event)).get("event");

        assertThat(payload.get("message").asText()).isEqualTo("took 42ms for user u-7");
    }

    @Test
    void nullMessageBecomesEmptyString() throws Exception {
        JsonNode payload = parse(serializer.toJson(event(Level.INFO, null))).get("event");

        assertThat(payload.get("message").asText()).isEmpty();
    }

    @Test
    void mergesMdcEntries() throws Exception {
        LoggingEvent event = event(Level.INFO, "hello");
        event.setMDCPropertyMap(Map.of("traceId", "a1b2c3d4", "userId", "7"));

        JsonNode payload = parse(serializer.toJson(event)).get("event");

        assertThat(payload.get("traceId").asText()).isEqualTo("a1b2c3d4");
        assertThat(payload.get("userId").asText()).isEqualTo("7");
    }

    @Test
    void reservedKeysWinOverMdc() throws Exception {
        LoggingEvent event = event(Level.ERROR, "real message");
        java.util.Map<String, String> mdc = new java.util.HashMap<>();
        mdc.put("level", "INFO");
        mdc.put("logger", "evil");
        mdc.put("thread", "spoofed-thread");
        mdc.put("message", "spoofed");
        mdc.put("exception", "fake-exception");
        event.setMDCPropertyMap(mdc);

        JsonNode payload = parse(serializer.toJson(event)).get("event");

        // All five reserved keys must hold their real values, not MDC-spoofed values
        assertThat(payload.get("level").asText()).isEqualTo("ERROR");
        assertThat(payload.get("logger").asText()).isEqualTo("com.recsys.application.gateway.LlmProxy");
        assertThat(payload.get("thread").asText()).isEqualTo("armeria-common-worker-1");
        assertThat(payload.get("message").asText()).isEqualTo("real message");
        assertThat(payload.has("exception")).isFalse(); // No exception in this event, so not present
    }

    @Test
    void reservedKeysAlsoCoverSplunkEnvelopeMetadataNames() throws Exception {
        LoggingEvent event = event(Level.INFO, "real message");
        java.util.Map<String, String> mdc = new java.util.HashMap<>();
        mdc.put("host", "spoofed-host");
        mdc.put("source", "spoofed-source");
        mdc.put("sourcetype", "spoofed-sourcetype");
        mdc.put("index", "spoofed-index");
        mdc.put("time", "spoofed-time");
        event.setMDCPropertyMap(mdc);

        JsonNode node = parse(serializer.toJson(event));
        JsonNode payload = node.get("event");

        // The five envelope metadata names must be dropped from the MDC merge rather than
        // surfacing inside `event` alongside (and shadowing, under Splunk's automatic
        // key-value extraction) the real envelope-level metadata fields.
        assertThat(payload.has("host")).isFalse();
        assertThat(payload.has("source")).isFalse();
        assertThat(payload.has("sourcetype")).isFalse();
        assertThat(payload.has("index")).isFalse();
        assertThat(payload.has("time")).isFalse();

        // And the real envelope-level fields must be untouched by the spoofed MDC values.
        assertThat(node.get("host").asText()).isEqualTo("host-1");
        assertThat(node.get("source").asText()).isEqualTo("api-gateway");
        assertThat(node.get("sourcetype").asText()).isEqualTo("recsys:app:log");
        assertThat(node.get("index").asText()).isEqualTo("recsys");
    }

    @Test
    void blankMdcKeysAreSkipped() throws Exception {
        LoggingEvent event = event(Level.INFO, "hello");
        java.util.Map<String, String> mdc = new java.util.HashMap<>();
        mdc.put("  ", "ignored");
        mdc.put("kept", null);
        mdc.put("good", "yes");
        event.setMDCPropertyMap(mdc);

        JsonNode payload = parse(serializer.toJson(event)).get("event");

        assertThat(payload.has("  ")).isFalse();
        assertThat(payload.has("kept")).isFalse();
        assertThat(payload.get("good").asText()).isEqualTo("yes");
    }

    @Test
    void rendersThrowableWithCauses() throws Exception {
        LoggingEvent event = event(Level.ERROR, "boom");
        Exception cause = new IllegalStateException("inner cause");
        event.setThrowableProxy(new ch.qos.logback.classic.spi.ThrowableProxy(
                new java.io.UncheckedIOException(new java.io.IOException("outer", cause))));

        JsonNode payload = parse(serializer.toJson(event)).get("event");

        assertThat(payload.get("exception").asText())
                .contains("java.io.UncheckedIOException")
                .contains("java.io.IOException: outer")
                .contains("inner cause");
    }

    @Test
    void batchBodyIsNewlineJoined() {
        String body = SplunkHecEventSerializer.toBatchBody(List.of("{\"a\":1}", "{\"b\":2}"));

        assertThat(body).isEqualTo("{\"a\":1}\n{\"b\":2}");
    }

    @Test
    void batchBodyOfOneEventHasNoNewline() {
        assertThat(SplunkHecEventSerializer.toBatchBody(List.of("{\"a\":1}"))).isEqualTo("{\"a\":1}");
    }
}
