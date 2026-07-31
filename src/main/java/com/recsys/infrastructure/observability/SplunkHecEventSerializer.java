package com.recsys.infrastructure.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps one Logback event to one Splunk HEC event object.
 *
 * <p>Pure and I/O-free so it can be tested without a collector. Logs nothing — see the
 * recursion note on {@link SplunkHecAppender}.
 */
final class SplunkHecEventSerializer {

    /** MDC entries with these names are dropped rather than shadowing the real field. */
    private static final Set<String> RESERVED_KEYS =
            Set.of("level", "logger", "thread", "message", "exception");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String host;
    private final String source;
    private final String sourcetype;
    private final String index;

    SplunkHecEventSerializer(String host, String source, String sourcetype, String index) {
        this.host = host;
        this.source = source;
        this.sourcetype = sourcetype;
        this.index = index;
    }

    String toJson(ILoggingEvent event) throws JsonProcessingException {
        ObjectNode envelope = MAPPER.createObjectNode();
        // Epoch seconds with a millisecond fraction, which is what HEC parses. BigDecimal
        // rather than a double so the fraction is exact and never renders in scientific notation.
        envelope.put("time", BigDecimal.valueOf(event.getTimeStamp(), 3));
        envelope.put("host", host);
        envelope.put("source", source);
        envelope.put("sourcetype", sourcetype);
        envelope.put("index", index);

        ObjectNode payload = envelope.putObject("event");
        payload.put("level", event.getLevel().toString());
        payload.put("logger", event.getLoggerName());
        payload.put("thread", event.getThreadName());
        String message = event.getFormattedMessage();
        payload.put("message", message == null ? "" : message);

        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            payload.put("exception", ThrowableProxyUtil.asString(throwable));
        }

        Map<String, String> mdc;
        try {
            mdc = event.getMDCPropertyMap();
        } catch (NullPointerException e) {
            // MDC adapter may not be initialized in some LoggerContext instances
            mdc = null;
        }
        if (mdc != null) {
            for (Map.Entry<String, String> entry : mdc.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isBlank() || entry.getValue() == null) continue;
                if (RESERVED_KEYS.contains(key)) continue;
                payload.put(key, entry.getValue());
            }
        }

        return MAPPER.writeValueAsString(envelope);
    }

    /** HEC's /event endpoint accepts concatenated JSON objects in one request body. */
    static String toBatchBody(List<String> serializedEvents) {
        return String.join("\n", serializedEvents);
    }
}
