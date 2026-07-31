package com.recsys.infrastructure.observability;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Environment-sourced configuration for {@link SplunkHecAppender}.
 *
 * <p>Deliberately logs nothing: this class is constructed from inside a Logback appender,
 * where any slf4j call would route back into that appender and recurse. It returns
 * {@link #disabledReason()} instead and lets the appender report through Logback's status API.
 *
 * <p>{@code SPLUNK_HEC_TOKEN} is the enablement switch. With no token the appender is inert,
 * which is why local runs, tests, and CI need no opt-out flag.
 */
public final class SplunkHecConfig {

    static final String DEFAULT_URL = "http://splunk:8088/services/collector/event";
    static final String DEFAULT_INDEX = "recsys";
    static final String DEFAULT_SOURCETYPE = "recsys:app:log";
    static final String DEFAULT_SERVICE_NAME = "recsys";
    static final int DEFAULT_QUEUE_CAPACITY = 10_000;
    static final int DEFAULT_BATCH_SIZE = 100;
    static final int DEFAULT_LINGER_MS = 1_000;
    static final int DEFAULT_TIMEOUT_MS = 2_000;

    private final String token;
    private final URI uri;
    private final String index;
    private final String sourcetype;
    private final String serviceName;
    private final int queueCapacity;
    private final int batchSize;
    private final Duration linger;
    private final Duration timeout;
    private final boolean insecureTls;
    private final String disabledReason;
    private final boolean misconfigured;

    private SplunkHecConfig(String token, URI uri, String index, String sourcetype, String serviceName,
                            int queueCapacity, int batchSize, Duration linger, Duration timeout,
                            boolean insecureTls, String disabledReason, boolean misconfigured) {
        this.token = token;
        this.uri = uri;
        this.index = index;
        this.sourcetype = sourcetype;
        this.serviceName = serviceName;
        this.queueCapacity = queueCapacity;
        this.batchSize = batchSize;
        this.linger = linger;
        this.timeout = timeout;
        this.insecureTls = insecureTls;
        this.disabledReason = disabledReason;
        this.misconfigured = misconfigured;
    }

    public static SplunkHecConfig fromEnvironment() {
        return from(System.getenv());
    }

    static SplunkHecConfig from(Map<String, String> env) {
        Map<String, String> source = env == null ? Map.of() : env;

        String token = trimToEmpty(source.get("SPLUNK_HEC_TOKEN"));
        if (token.isEmpty()) {
            return disabled("SPLUNK_HEC_TOKEN is not set; Splunk HEC log shipping is off", false);
        }

        String rawUrlValue = source.get("SPLUNK_HEC_URL");
        String rawUrl = trimToEmpty(rawUrlValue);

        // If the key was explicitly provided but the value is blank after trimming,
        // that's a misconfiguration when a token is present
        if (rawUrlValue != null && rawUrl.isEmpty()) {
            return disabled("SPLUNK_HEC_URL must not be empty", true);
        }

        if (rawUrl.isEmpty()) {
            rawUrl = DEFAULT_URL;
        }
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException e) {
            return disabled("SPLUNK_HEC_URL is not a valid URI: '" + rawUrl + "'", true);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || !("http".equals(scheme) || "https".equals(scheme))) {
            return disabled("SPLUNK_HEC_URL must be an absolute http(s) URL but was '" + rawUrl + "'", true);
        }

        return new SplunkHecConfig(
                token,
                uri,
                orDefault(source.get("SPLUNK_HEC_INDEX"), DEFAULT_INDEX),
                orDefault(source.get("SPLUNK_HEC_SOURCETYPE"), DEFAULT_SOURCETYPE),
                orDefault(source.get("SPLUNK_SERVICE_NAME"), DEFAULT_SERVICE_NAME),
                positiveInt(source.get("SPLUNK_HEC_QUEUE_CAPACITY"), DEFAULT_QUEUE_CAPACITY),
                positiveInt(source.get("SPLUNK_HEC_BATCH_SIZE"), DEFAULT_BATCH_SIZE),
                Duration.ofMillis(positiveInt(source.get("SPLUNK_HEC_LINGER_MS"), DEFAULT_LINGER_MS)),
                Duration.ofMillis(positiveInt(source.get("SPLUNK_HEC_TIMEOUT_MS"), DEFAULT_TIMEOUT_MS)),
                Boolean.parseBoolean(trimToEmpty(source.get("SPLUNK_HEC_INSECURE_TLS"))),
                null,
                false);
    }

    private static SplunkHecConfig disabled(String reason, boolean misconfigured) {
        return new SplunkHecConfig(
                "", null, DEFAULT_INDEX, DEFAULT_SOURCETYPE, DEFAULT_SERVICE_NAME,
                DEFAULT_QUEUE_CAPACITY, DEFAULT_BATCH_SIZE,
                Duration.ofMillis(DEFAULT_LINGER_MS), Duration.ofMillis(DEFAULT_TIMEOUT_MS),
                false, reason, misconfigured);
    }

    public boolean isEnabled() {
        return disabledReason == null;
    }

    /** True when a token was supplied but the rest of the configuration is unusable. */
    public boolean isMisconfigured() {
        return misconfigured;
    }

    /** Null when enabled; otherwise why the appender will be inert. */
    public String disabledReason() {
        return disabledReason;
    }

    public String token() { return token; }
    public URI uri() { return uri; }
    public String index() { return index; }
    public String sourcetype() { return sourcetype; }
    public String serviceName() { return serviceName; }
    public int queueCapacity() { return queueCapacity; }
    public int batchSize() { return batchSize; }
    public Duration linger() { return linger; }
    public Duration timeout() { return timeout; }
    public boolean insecureTls() { return insecureTls; }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String orDefault(String value, String fallback) {
        String trimmed = trimToEmpty(value);
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    /** Malformed or non-positive values fall back, matching {@code AsyncEventPublisher.readIntEnv}. */
    private static int positiveInt(String value, int fallback) {
        String trimmed = trimToEmpty(value);
        if (trimmed.isEmpty()) return fallback;
        try {
            int parsed = Integer.parseInt(trimmed);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
