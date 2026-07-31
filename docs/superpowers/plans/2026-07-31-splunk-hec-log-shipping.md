# Splunk HEC Log Shipping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship all four services' application logs as structured JSON events directly to Splunk's HTTP Event Collector, opt-in via a single environment variable.

**Architecture:** A Logback appender (`SplunkHecAppender`) hands log events to a bounded queue; one daemon thread drains it in batches and POSTs them to `/services/collector/event`. Drop-on-full and no-retry, so a Splunk outage degrades to console-only and never stalls a serving thread. Four small classes: config, serializer, HTTP client, appender.

**Tech Stack:** Java 17, Logback 1.5.8 (already on the classpath, `compile` scope via `spring-boot-starter-web`), Jackson (already a dependency), `java.net.http.HttpClient` (JDK), JUnit 5 + AssertJ + `armeria-junit5` `ServerExtension` for the stub collector.

**Spec:** [2026-07-31-splunk-hec-log-shipping-design.md](../specs/2026-07-31-splunk-hec-log-shipping-design.md)

## Global Constraints

These apply to **every** task. Re-read them before starting any task.

- **Build with JDK 17.** Prefix every Maven command: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`. On JDK 25 a clean compile fails on two pre-existing files.
- **Never call slf4j from any class in `com.recsys.infrastructure.observability`.** No `LoggerFactory`, no `Logger`, no `log.warn`. It routes back into the appender and recurses. Use Logback's status API instead: `addInfo(String)`, `addWarn(String)`, `addError(String)` — inherited from `UnsynchronizedAppenderBase` in the appender, and available via `ContextAwareBase` elsewhere. In `SplunkHecConfig`, `SplunkHecEventSerializer`, and `SplunkHecClient`, do not log at all — return values that let the appender report.
- **No new Maven dependencies.** Everything needed is already on the classpath.
- **`SplunkHecClient.send` must never throw.** It returns an `Outcome`. This is the load-bearing property of the whole design.
- **Package:** all new production classes go in `com.recsys.infrastructure.observability`; all new tests in the same package under `src/test/java`.
- **Visibility:** only `SplunkHecAppender` and `SplunkHecConfig` are `public` (the appender is named in XML, and `fromEnvironment()` is its entry point). `SplunkHecEventSerializer` and `SplunkHecClient` are package-private.
- **Env parsing follows the existing repo idiom** in `AsyncEventPublisher.readIntEnv`: a malformed or non-positive integer falls back to the default rather than throwing.
- **Config defaults, exact values:** URL `http://splunk:8088/services/collector/event`, index `recsys`, sourcetype `recsys:app:log`, service name `recsys`, queue capacity `10000`, batch size `100`, linger `1000` ms, timeout `2000` ms, insecure TLS `false`.
- **Commit after every task.** End commit messages with:
  `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`
- Work on branch `feat/splunk-hec-log-shipping` (already created, already holds the spec commit).

## File Structure

**Create — production:**

| File | Responsibility |
|---|---|
| `src/main/java/com/recsys/infrastructure/observability/SplunkHecConfig.java` | Read + validate `SPLUNK_*` env. Knows nothing about Logback or HTTP. |
| `src/main/java/com/recsys/infrastructure/observability/SplunkHecEventSerializer.java` | One `ILoggingEvent` → one HEC event JSON object. Pure; no I/O. |
| `src/main/java/com/recsys/infrastructure/observability/SplunkHecClient.java` | POST a batch body. Never throws; returns `Outcome`. |
| `src/main/java/com/recsys/infrastructure/observability/SplunkHecAppender.java` | Bounded queue, drain thread, batching, counters, throttled diagnostics. |
| `src/main/resources/logback-common.xml` | Shared `CONSOLE` + `SPLUNK` appender definitions and root logger. |
| `src/main/resources/logback.xml` | Plain-Logback entry point (the three Armeria mains). Includes the common file. |
| `docker/splunk/init.sh` | Idempotent one-shot: create the `recsys` index, ensure HEC is on over plain HTTP, verify with a real event. |

**Create — tests:**

| File | Covers |
|---|---|
| `src/test/java/com/recsys/infrastructure/observability/SplunkHecConfigTest.java` | Task 1 |
| `src/test/java/com/recsys/infrastructure/observability/SplunkHecEventSerializerTest.java` | Task 2 |
| `src/test/java/com/recsys/infrastructure/observability/SplunkHecClientTest.java` | Task 3 |
| `src/test/java/com/recsys/infrastructure/observability/SplunkHecAppenderTest.java` | Task 4 |
| `src/test/java/com/recsys/infrastructure/observability/SplunkLogbackWiringTest.java` | Task 5 |

**Modify:**

| File | Change |
|---|---|
| `pom.xml:330-361` | Add five test includes to the `resilience` profile |
| `scripts/run-microservices-local.sh` | Per-service `SPLUNK_SERVICE_NAME`, `localhost` URL default, forward tunables |
| `k8s/base/catalog-serving.yaml`, `model-serving.yaml`, `online-serving.yaml`, `api-gateway.yaml` | `SPLUNK_*` env block |
| `docker-splunk.yml` → `docker-compose.splunk.yml` | Rename, drop stale `redis-*` volumes, add `splunk-init` |
| `README.md:~520` | Runbook index entry (`DocumentationIndexTest` enforces this) |
| `.claude/CLAUDE.md` | `SPLUNK_*` env vars |

**Create — docs:** `docs/runbooks/splunk-hec-logging.md`

---

### Task 1: `SplunkHecConfig`

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/observability/SplunkHecConfig.java`
- Test: `src/test/java/com/recsys/infrastructure/observability/SplunkHecConfigTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `public static SplunkHecConfig fromEnvironment()`
  - `static SplunkHecConfig from(Map<String, String> env)` — package-private, the seam tests use (same idiom as `AsyncEventPublisherFactory.from`)
  - `public boolean isEnabled()`, `public boolean isMisconfigured()`, `public String disabledReason()` (null when enabled)
  - Accessors: `URI uri()`, `String token()`, `String index()`, `String sourcetype()`, `String serviceName()`, `int queueCapacity()`, `int batchSize()`, `Duration linger()`, `Duration timeout()`, `boolean insecureTls()`

**Why three state methods:** the appender reports a missing token at INFO (the normal off state) but a *present* token with a broken URL at ERROR (someone tried to enable this and it silently won't work). `isMisconfigured()` is what distinguishes them.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/infrastructure/observability/SplunkHecConfigTest.java`:

```java
package com.recsys.infrastructure.observability;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SplunkHecConfigTest {

    @Test
    void disabledWhenTokenAbsent() {
        SplunkHecConfig config = SplunkHecConfig.from(Map.of());

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.isMisconfigured()).isFalse();
        assertThat(config.disabledReason()).contains("SPLUNK_HEC_TOKEN");
    }

    @Test
    void blankTokenIsTreatedAsAbsent() {
        SplunkHecConfig config = SplunkHecConfig.from(Map.of("SPLUNK_HEC_TOKEN", "   "));

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.isMisconfigured()).isFalse();
    }

    @Test
    void enabledWithTokenAndDefaults() {
        SplunkHecConfig config = SplunkHecConfig.from(Map.of("SPLUNK_HEC_TOKEN", "tok-1"));

        assertThat(config.isEnabled()).isTrue();
        assertThat(config.disabledReason()).isNull();
        assertThat(config.token()).isEqualTo("tok-1");
        assertThat(config.uri()).hasToString("http://splunk:8088/services/collector/event");
        assertThat(config.index()).isEqualTo("recsys");
        assertThat(config.sourcetype()).isEqualTo("recsys:app:log");
        assertThat(config.serviceName()).isEqualTo("recsys");
        assertThat(config.queueCapacity()).isEqualTo(10_000);
        assertThat(config.batchSize()).isEqualTo(100);
        assertThat(config.linger()).isEqualTo(Duration.ofMillis(1_000));
        assertThat(config.timeout()).isEqualTo(Duration.ofMillis(2_000));
        assertThat(config.insecureTls()).isFalse();
    }

    @Test
    void overridesEveryField() {
        SplunkHecConfig config = SplunkHecConfig.from(Map.ofEntries(
                Map.entry("SPLUNK_HEC_TOKEN", "tok-2"),
                Map.entry("SPLUNK_HEC_URL", "https://splunk.internal:8088/services/collector/event"),
                Map.entry("SPLUNK_HEC_INDEX", "prod"),
                Map.entry("SPLUNK_HEC_SOURCETYPE", "recsys:prod:log"),
                Map.entry("SPLUNK_SERVICE_NAME", "api-gateway"),
                Map.entry("SPLUNK_HEC_QUEUE_CAPACITY", "500"),
                Map.entry("SPLUNK_HEC_BATCH_SIZE", "25"),
                Map.entry("SPLUNK_HEC_LINGER_MS", "250"),
                Map.entry("SPLUNK_HEC_TIMEOUT_MS", "750"),
                Map.entry("SPLUNK_HEC_INSECURE_TLS", "true")));

        assertThat(config.isEnabled()).isTrue();
        assertThat(config.uri()).hasToString("https://splunk.internal:8088/services/collector/event");
        assertThat(config.index()).isEqualTo("prod");
        assertThat(config.sourcetype()).isEqualTo("recsys:prod:log");
        assertThat(config.serviceName()).isEqualTo("api-gateway");
        assertThat(config.queueCapacity()).isEqualTo(500);
        assertThat(config.batchSize()).isEqualTo(25);
        assertThat(config.linger()).isEqualTo(Duration.ofMillis(250));
        assertThat(config.timeout()).isEqualTo(Duration.ofMillis(750));
        assertThat(config.insecureTls()).isTrue();
    }

    @Test
    void malformedIntegerFallsBackToDefault() {
        SplunkHecConfig config = SplunkHecConfig.from(Map.of(
                "SPLUNK_HEC_TOKEN", "tok-3",
                "SPLUNK_HEC_QUEUE_CAPACITY", "not-a-number"));

        assertThat(config.queueCapacity()).isEqualTo(10_000);
    }

    @Test
    void nonPositiveIntegerFallsBackToDefault() {
        SplunkHecConfig config = SplunkHecConfig.from(Map.of(
                "SPLUNK_HEC_TOKEN", "tok-4",
                "SPLUNK_HEC_BATCH_SIZE", "0"));

        assertThat(config.batchSize()).isEqualTo(100);
    }

    @Test
    void blankUrlWithTokenIsMisconfigured() {
        SplunkHecConfig config = SplunkHecConfig.from(Map.of(
                "SPLUNK_HEC_TOKEN", "tok-5",
                "SPLUNK_HEC_URL", "   "));

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.isMisconfigured()).isTrue();
        assertThat(config.disabledReason()).contains("SPLUNK_HEC_URL");
    }

    @Test
    void nonHttpUrlIsMisconfigured() {
        SplunkHecConfig config = SplunkHecConfig.from(Map.of(
                "SPLUNK_HEC_TOKEN", "tok-6",
                "SPLUNK_HEC_URL", "ftp://splunk:8088/services/collector/event"));

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.isMisconfigured()).isTrue();
        assertThat(config.disabledReason()).contains("SPLUNK_HEC_URL");
    }

    @Test
    void relativeUrlIsMisconfigured() {
        SplunkHecConfig config = SplunkHecConfig.from(Map.of(
                "SPLUNK_HEC_TOKEN", "tok-7",
                "SPLUNK_HEC_URL", "/services/collector/event"));

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.isMisconfigured()).isTrue();
    }

    @Test
    void nullEnvironmentIsDisabledNotAnError() {
        SplunkHecConfig config = SplunkHecConfig.from(null);

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.isMisconfigured()).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=SplunkHecConfigTest`
Expected: FAIL — compilation error, `SplunkHecConfig` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/recsys/infrastructure/observability/SplunkHecConfig.java`:

```java
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

        String rawUrl = trimToEmpty(source.get("SPLUNK_HEC_URL"));
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=SplunkHecConfigTest`
Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/observability/SplunkHecConfig.java \
        src/test/java/com/recsys/infrastructure/observability/SplunkHecConfigTest.java
git commit -m "feat: add Splunk HEC configuration from environment

SPLUNK_HEC_TOKEN is the enablement switch, so tests and local runs need
no opt-out. A token with an unusable URL reports isMisconfigured() so the
appender can distinguish 'deliberately off' from 'tried to enable, broken'.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `SplunkHecEventSerializer`

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/observability/SplunkHecEventSerializer.java`
- Test: `src/test/java/com/recsys/infrastructure/observability/SplunkHecEventSerializerTest.java`

**Interfaces:**
- Consumes: nothing from Task 1 (kept pure so it can be tested without any config).
- Produces:
  - `SplunkHecEventSerializer(String host, String source, String sourcetype, String index)` — package-private constructor
  - `String toJson(ILoggingEvent event) throws JsonProcessingException`
  - `static String toBatchBody(List<String> serializedEvents)` — newline-joins

**Reserved keys:** `level`, `logger`, `thread`, `message`, `exception`. An MDC entry with one of these names is dropped, not merged, so a search on `level=ERROR` can't be poisoned by application code.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/infrastructure/observability/SplunkHecEventSerializerTest.java`:

```java
package com.recsys.infrastructure.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
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
        event.setMDCPropertyMap(Map.of("level", "INFO", "message", "spoofed", "logger", "evil"));

        JsonNode payload = parse(serializer.toJson(event)).get("event");

        assertThat(payload.get("level").asText()).isEqualTo("ERROR");
        assertThat(payload.get("message").asText()).isEqualTo("real message");
        assertThat(payload.get("logger").asText()).isEqualTo("com.recsys.application.gateway.LlmProxy");
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=SplunkHecEventSerializerTest`
Expected: FAIL — compilation error, `SplunkHecEventSerializer` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/recsys/infrastructure/observability/SplunkHecEventSerializer.java`:

```java
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

        Map<String, String> mdc = event.getMDCPropertyMap();
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=SplunkHecEventSerializerTest`
Expected: PASS, 11 tests.

If `timeIsEpochSecondsWithMillisecondFraction` fails on the raw-string assertion, check that Jackson is not configured to write `BigDecimal` as a string; the default `ObjectMapper` writes it as a JSON number. Do not "fix" this by switching to a double — that reintroduces the formatting problem the `BigDecimal` avoids.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/observability/SplunkHecEventSerializer.java \
        src/test/java/com/recsys/infrastructure/observability/SplunkHecEventSerializerTest.java
git commit -m "feat: serialize Logback events into Splunk HEC envelopes

MDC merges into the event payload, but the five reserved field names win
over MDC so application code cannot shadow level/message/logger and poison
a search. Time is a BigDecimal so the millisecond fraction is exact.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `SplunkHecClient`

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/observability/SplunkHecClient.java`
- Test: `src/test/java/com/recsys/infrastructure/observability/SplunkHecClientTest.java`

**Interfaces:**
- Consumes: `SplunkHecConfig` (Task 1) — `uri()`, `token()`, `timeout()`, `insecureTls()`.
- Produces:
  - `enum SplunkHecClient.Outcome { SUCCESS, AUTH_REJECTED, SERVER_ERROR, TRANSPORT_FAILURE }`
  - `SplunkHecClient(SplunkHecConfig config)` — package-private
  - `SplunkHecClient(HttpClient httpClient, SplunkHecConfig config)` — package-private, test seam
  - `Outcome send(String body)` — **never throws**

The class is **not `final`**: `SplunkHecAppenderTest` subclasses it to fake outcomes without a server.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/infrastructure/observability/SplunkHecClientTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=SplunkHecClientTest`
Expected: FAIL — compilation error, `SplunkHecClient` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/recsys/infrastructure/observability/SplunkHecClient.java`:

```java
package com.recsys.infrastructure.observability;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;

/**
 * Posts a batch body to Splunk's HTTP Event Collector.
 *
 * <p><strong>{@link #send} never throws.</strong> Every failure becomes an {@link Outcome}.
 * The appender's drain thread depends on this: an escaping exception would kill the thread
 * and silently stop all log shipping for the life of the JVM.
 *
 * <p>Uses the JDK's {@link HttpClient} deliberately. It logs through {@code System.Logger},
 * not slf4j, so it cannot re-enter the appender. Do not swap in an slf4j-backed HTTP client
 * without re-checking that.
 *
 * <p>Not {@code final}: tests subclass it to fake outcomes without standing up a server.
 */
class SplunkHecClient {

    enum Outcome { SUCCESS, AUTH_REJECTED, SERVER_ERROR, TRANSPORT_FAILURE }

    private final HttpClient httpClient;
    private final URI uri;
    private final String token;
    private final Duration timeout;

    SplunkHecClient(SplunkHecConfig config) {
        this(buildHttpClient(config), config);
    }

    SplunkHecClient(HttpClient httpClient, SplunkHecConfig config) {
        this.httpClient = httpClient;
        this.uri = config.uri();
        this.token = config.token();
        this.timeout = config.timeout();
    }

    Outcome send(String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Authorization", "Splunk " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<Void> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 300) return Outcome.SUCCESS;
            if (status == 401 || status == 403) return Outcome.AUTH_REJECTED;
            return Outcome.SERVER_ERROR;
        } catch (InterruptedException e) {
            // Shutdown in progress. Restore the flag so the drain loop sees it and exits.
            Thread.currentThread().interrupt();
            return Outcome.TRANSPORT_FAILURE;
        } catch (Exception e) {
            // Connect refused, DNS failure, timeout, TLS failure — all the same to the caller.
            return Outcome.TRANSPORT_FAILURE;
        }
    }

    private static HttpClient buildHttpClient(SplunkHecConfig config) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(config.timeout())
                .followRedirects(HttpClient.Redirect.NEVER);
        if (config.insecureTls() && "https".equalsIgnoreCase(config.uri().getScheme())) {
            SSLContext insecure = insecureSslContext();
            if (insecure != null) builder.sslContext(insecure);
        }
        return builder.build();
    }

    /**
     * Trust-all context for pointing a developer at a Splunk instance using its stock
     * self-signed certificate. Reachable only via SPLUNK_HEC_INSECURE_TLS=true, which
     * defaults to false and is never set in any committed manifest.
     */
    private static SSLContext insecureSslContext() {
        try {
            TrustManager[] trustAll = {
                    new X509TrustManager() {
                        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new java.security.SecureRandom());
            return context;
        } catch (Exception e) {
            return null; // Fall back to strict verification rather than failing startup.
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=SplunkHecClientTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/observability/SplunkHecClient.java \
        src/test/java/com/recsys/infrastructure/observability/SplunkHecClientTest.java
git commit -m "feat: add non-throwing Splunk HEC HTTP client

send() converts every failure to an Outcome. An exception escaping into the
drain thread would kill it and silently stop shipping for the life of the
JVM, so the no-throw contract is asserted directly against a stub collector
and against an unreachable port.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: `SplunkHecAppender`

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/observability/SplunkHecAppender.java`
- Test: `src/test/java/com/recsys/infrastructure/observability/SplunkHecAppenderTest.java`

**Interfaces:**
- Consumes: `SplunkHecConfig` (Task 1), `SplunkHecEventSerializer` (Task 2), `SplunkHecClient` + `Outcome` (Task 3).
- Produces:
  - `public SplunkHecAppender()` — the no-arg constructor Logback's Joran needs
  - `SplunkHecAppender(SplunkHecConfig config, SplunkHecClient client)` — package-private test seam
  - `public Snapshot snapshot()` returning `record Snapshot(int queued, long sent, long dropped, long failed)`

**Two subtleties that must not be lost:**

1. **`prepareForDeferredProcessing()` on the caller thread.** Serialization happens on the drain thread to keep the request path cheap, but `LoggingEvent.getThreadName()` resolves lazily — read first from the drain thread, it returns *that* thread's name and every event is mislabelled. Logback's own `AsyncAppender` calls `prepareForDeferredProcessing()` in `append()` for exactly this reason. Do the same.
2. **`super.start()` even when disabled.** `AppenderBase.doAppend` warns on every event delivered to a stopped appender. Start, then no-op in `append()`.

**Linger semantics:** `SPLUNK_HEC_LINGER_MS` is how long the drain thread blocks waiting for the *first* event of a batch. Once an event is available the batch ships immediately, topped up with whatever else is already queued. So batches fill up naturally under load and a lone event is never delayed. Same shape as `AsyncEventPublisher.drainLoop`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/infrastructure/observability/SplunkHecAppenderTest.java`:

```java
package com.recsys.infrastructure.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

class SplunkHecAppenderTest {

    static final ConcurrentLinkedQueue<String> bodies = new ConcurrentLinkedQueue<>();
    static final ConcurrentLinkedQueue<String> authHeaders = new ConcurrentLinkedQueue<>();

    @RegisterExtension
    static final ServerExtension collector = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/services/collector/event", (ctx, req) ->
                    HttpResponse.of(req.aggregate().thenApply(agg -> {
                        authHeaders.add(agg.headers().get("authorization"));
                        bodies.add(agg.contentUtf8());
                        return HttpResponse.of(HttpStatus.OK, MediaType.JSON, "{\"code\":0}");
                    })));
        }
    };

    private static LoggingEvent event(String message) {
        LoggerContext context = new LoggerContext();
        LoggingEvent event = new LoggingEvent();
        event.setLoggerContext(context);
        event.setLoggerName("com.recsys.Test");
        event.setLevel(Level.INFO);
        event.setMessage(message);
        event.setTimeStamp(1_753_970_000_123L);
        return event;
    }

    /** Records outcomes without any network. */
    static final class FakeClient extends SplunkHecClient {
        final AtomicInteger calls = new AtomicInteger();
        final ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();
        private final Outcome outcome;
        private final CountDownLatch gate;

        FakeClient(Outcome outcome, CountDownLatch gate) {
            super(SplunkHecConfig.from(Map.of(
                    "SPLUNK_HEC_TOKEN", "tok",
                    "SPLUNK_HEC_URL", "http://127.0.0.1:1/services/collector/event")));
            this.outcome = outcome;
            this.gate = gate;
        }

        AtomicInteger calls() { return calls; }

        ConcurrentLinkedQueue<String> seen() { return seen; }

        @Override
        Outcome send(String body) {
            if (gate != null) {
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            calls.incrementAndGet();
            seen.add(body);
            return outcome;
        }
    }

    private static SplunkHecConfig enabledConfig(Map<String, String> extra) {
        java.util.Map<String, String> env = new java.util.HashMap<>(Map.of(
                "SPLUNK_HEC_TOKEN", "tok",
                "SPLUNK_HEC_URL", "http://127.0.0.1:" + collector.httpPort() + "/services/collector/event",
                "SPLUNK_HEC_LINGER_MS", "50"));
        env.putAll(extra);
        return SplunkHecConfig.from(env);
    }

    @Test
    void shipsEventsToCollectorWithAuthHeader() {
        bodies.clear();
        authHeaders.clear();
        SplunkHecConfig config = enabledConfig(Map.of("SPLUNK_SERVICE_NAME", "api-gateway"));
        SplunkHecAppender appender = new SplunkHecAppender(config, new SplunkHecClient(config));
        appender.start();
        try {
            appender.doAppend(event("hello splunk"));

            await().atMost(5, TimeUnit.SECONDS).until(() -> !bodies.isEmpty());
            assertThat(bodies.peek()).contains("\"message\":\"hello splunk\"")
                    .contains("\"source\":\"api-gateway\"")
                    .contains("\"sourcetype\":\"recsys:app:log\"");
            assertThat(authHeaders.peek()).isEqualTo("Splunk tok");
        } finally {
            appender.stop();
        }
    }

    @Test
    void batchesMultipleEventsIntoOneRequest() {
        SplunkHecConfig config = enabledConfig(Map.of("SPLUNK_HEC_BATCH_SIZE", "50"));
        CountDownLatch gate = new CountDownLatch(1);
        FakeClient client = new FakeClient(SplunkHecClient.Outcome.SUCCESS, gate);
        SplunkHecAppender appender = new SplunkHecAppender(config, client);
        appender.start();
        try {
            for (int i = 0; i < 10; i++) {
                appender.doAppend(event("event-" + i));
            }
            gate.countDown(); // let the drain thread proceed now that all 10 are queued

            await().atMost(5, TimeUnit.SECONDS).until(() -> appender.snapshot().sent() == 10);
            // 10 events, far fewer than 10 requests
            assertThat(client.calls().get()).isLessThan(10);
            assertThat(String.join("\n", client.seen())).contains("event-0").contains("event-9");
        } finally {
            appender.stop();
        }
    }

    @Test
    void disabledConfigSendsNothing() {
        SplunkHecConfig disabled = SplunkHecConfig.from(Map.of());
        FakeClient client = new FakeClient(SplunkHecClient.Outcome.SUCCESS, null);
        SplunkHecAppender appender = new SplunkHecAppender(disabled, client);
        appender.start();
        try {
            assertThat(appender.isStarted()).isTrue(); // started but inert
            for (int i = 0; i < 20; i++) {
                appender.doAppend(event("ignored-" + i));
            }
            assertThat(client.calls().get()).isZero();
            assertThat(appender.snapshot().sent()).isZero();
            assertThat(appender.snapshot().dropped()).isZero();
        } finally {
            appender.stop();
        }
    }

    @Test
    void dropsWhenQueueIsFullInsteadOfBlocking() {
        SplunkHecConfig config = enabledConfig(Map.of("SPLUNK_HEC_QUEUE_CAPACITY", "2"));
        CountDownLatch gate = new CountDownLatch(1);
        FakeClient client = new FakeClient(SplunkHecClient.Outcome.SUCCESS, gate);
        SplunkHecAppender appender = new SplunkHecAppender(config, client);
        appender.start();
        try {
            long startedAt = System.nanoTime();
            for (int i = 0; i < 500; i++) {
                appender.doAppend(event("burst-" + i));
            }
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(elapsedMs).as("append must never block on a full queue").isLessThan(2_000);
            assertThat(appender.snapshot().dropped()).isPositive();
        } finally {
            gate.countDown();
            appender.stop();
        }
    }

    @Test
    void failingCollectorNeverThrowsIntoAppend() {
        SplunkHecConfig config = enabledConfig(Map.of());
        FakeClient client = new FakeClient(SplunkHecClient.Outcome.SERVER_ERROR, null);
        SplunkHecAppender appender = new SplunkHecAppender(config, client);
        appender.start();
        try {
            assertThatCode(() -> {
                for (int i = 0; i < 50; i++) {
                    appender.doAppend(event("doomed-" + i));
                }
            }).doesNotThrowAnyException();

            await().atMost(5, TimeUnit.SECONDS).until(() -> appender.snapshot().failed() > 0);
            assertThat(appender.snapshot().sent()).isZero();
        } finally {
            appender.stop();
        }
    }

    @Test
    void drainThreadSurvivesAClientThatThrows() {
        SplunkHecConfig config = enabledConfig(Map.of());
        AtomicInteger calls = new AtomicInteger();
        SplunkHecClient exploding = new SplunkHecClient(config) {
            @Override
            Outcome send(String body) {
                if (calls.incrementAndGet() == 1) throw new IllegalStateException("boom");
                return Outcome.SUCCESS;
            }
        };
        SplunkHecAppender appender = new SplunkHecAppender(config, exploding);
        appender.start();
        try {
            appender.doAppend(event("first"));
            await().atMost(5, TimeUnit.SECONDS).until(() -> calls.get() >= 1);

            appender.doAppend(event("second"));
            // The drain thread must still be alive to ship the second event.
            await().atMost(5, TimeUnit.SECONDS).until(() -> appender.snapshot().sent() > 0);
        } finally {
            appender.stop();
        }
    }

    @Test
    void stopFlushesBufferedEvents() {
        SplunkHecConfig config = enabledConfig(Map.of("SPLUNK_HEC_LINGER_MS", "3000"));
        CountDownLatch gate = new CountDownLatch(1);
        FakeClient client = new FakeClient(SplunkHecClient.Outcome.SUCCESS, gate);
        SplunkHecAppender appender = new SplunkHecAppender(config, client);
        appender.start();

        for (int i = 0; i < 5; i++) {
            appender.doAppend(event("pending-" + i));
        }
        gate.countDown();
        appender.stop();

        assertThat(String.join("\n", client.seen())).contains("pending-0").contains("pending-4");
    }

    @Test
    void capturesCallerThreadNameNotDrainThreadName() {
        SplunkHecConfig config = enabledConfig(Map.of());
        FakeClient client = new FakeClient(SplunkHecClient.Outcome.SUCCESS, null);
        SplunkHecAppender appender = new SplunkHecAppender(config, client);
        appender.start();
        try {
            Thread caller = new Thread(() -> appender.doAppend(event("from-caller")), "test-caller-thread");
            caller.start();
            caller.join();

            await().atMost(5, TimeUnit.SECONDS).until(() -> !client.seen().isEmpty());
            assertThat(client.seen().peek()).contains("\"thread\":\"test-caller-thread\"");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            appender.stop();
        }
    }
}
```

**Note on Awaitility:** verify it is already a test dependency before relying on `org.awaitility.Awaitility`:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -o dependency:tree -Dincludes=org.awaitility 2>&1 | grep -E "awaitility|BUILD"
```

If it is absent, do **not** add a dependency (Global Constraints forbid it). Replace each `await().atMost(...).until(cond)` with a small local helper in the test class:

```java
private static void awaitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
        if (condition.getAsBoolean()) return;
        Thread.sleep(10);
    }
    throw new AssertionError("condition not met within 5s");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=SplunkHecAppenderTest`
Expected: FAIL — compilation error, `SplunkHecAppender` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/recsys/infrastructure/observability/SplunkHecAppender.java`:

```java
package com.recsys.infrastructure.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Logback appender that ships log events to Splunk's HTTP Event Collector.
 *
 * <p>Shape borrowed from {@code AsyncEventPublisher}: a bounded queue, one daemon drain
 * thread, batched writes, and <strong>drop-on-full rather than block</strong>. Logs are
 * diagnostics — a Splunk outage or a log burst must degrade to console-only, never stall a
 * serving thread or grow the heap.
 *
 * <p><strong>This class must never call slf4j.</strong> Any slf4j call from here routes back
 * into this appender and recurses. All diagnostics go through Logback's status API
 * ({@code addInfo}/{@code addWarn}/{@code addError}), which cannot re-enter.
 *
 * <p>Inert unless {@code SPLUNK_HEC_TOKEN} is set, which is why tests and local runs need no
 * opt-out flag.
 */
public final class SplunkHecAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private static final long WARN_THROTTLE_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final long FLUSH_WAIT_MILLIS = 2_000;

    private final SplunkHecConfig injectedConfig;
    private final SplunkHecClient injectedClient;

    private SplunkHecConfig config;
    private SplunkHecClient client;
    private SplunkHecEventSerializer serializer;
    private ArrayBlockingQueue<ILoggingEvent> queue;
    private Thread drainThread;
    private volatile boolean running;

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final Map<SplunkHecClient.Outcome, Long> lastWarnedAt =
            new EnumMap<>(SplunkHecClient.Outcome.class);

    /** Logback's Joran configurator needs a no-arg constructor. */
    public SplunkHecAppender() {
        this(null, null);
    }

    SplunkHecAppender(SplunkHecConfig config, SplunkHecClient client) {
        this.injectedConfig = config;
        this.injectedClient = client;
    }

    @Override
    public void start() {
        config = injectedConfig != null ? injectedConfig : SplunkHecConfig.fromEnvironment();

        if (!config.isEnabled()) {
            // Start anyway: AppenderBase warns on every event delivered to a stopped appender.
            if (config.isMisconfigured()) {
                addError("Splunk HEC appender disabled: " + config.disabledReason());
            } else {
                addInfo("Splunk HEC appender disabled: " + config.disabledReason());
            }
            super.start();
            return;
        }

        if (config.insecureTls()) {
            addWarn("SPLUNK_HEC_INSECURE_TLS=true — Splunk's TLS certificate will NOT be verified. "
                    + "Intended for local development against a self-signed certificate only.");
        }

        serializer = new SplunkHecEventSerializer(
                resolveHost(), config.serviceName(), config.sourcetype(), config.index());
        client = injectedClient != null ? injectedClient : new SplunkHecClient(config);
        queue = new ArrayBlockingQueue<>(config.queueCapacity());
        running = true;
        drainThread = new Thread(this::drainLoop, "splunk-hec-appender");
        drainThread.setDaemon(true);
        drainThread.start();

        addInfo("Splunk HEC appender shipping to " + config.uri()
                + " (index=" + config.index() + ", source=" + config.serviceName() + ")");
        super.start();
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!running || event == null) return;
        // Resolve the lazily-computed thread name (and other deferred state) HERE, on the
        // caller's thread. Reading it first from the drain thread mislabels every event.
        event.prepareForDeferredProcessing();
        if (!queue.offer(event)) {
            dropped.incrementAndGet();
        }
    }

    @Override
    public void stop() {
        if (!running) {
            super.stop();
            return;
        }
        running = false;
        drainThread.interrupt();
        if (Thread.currentThread() != drainThread) {
            try {
                drainThread.join(FLUSH_WAIT_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        List<ILoggingEvent> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        if (!remaining.isEmpty()) {
            ship(remaining);
        }
        addInfo("Splunk HEC appender stopped; " + snapshot());
        super.stop();
    }

    public Snapshot snapshot() {
        return new Snapshot(queue == null ? 0 : queue.size(),
                sent.get(), dropped.get(), failed.get());
    }

    private void drainLoop() {
        List<ILoggingEvent> batch = new ArrayList<>(config.batchSize());
        long lingerMillis = config.linger().toMillis();
        while (running) {
            try {
                ILoggingEvent first = queue.poll(lingerMillis, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.add(first);
                queue.drainTo(batch, config.batchSize() - 1);
                ship(batch);
                batch.clear();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // A transport or serialization defect must not kill this thread — that would
                // silently stop all log shipping for the life of the JVM.
                batch.clear();
                warnThrottled(SplunkHecClient.Outcome.TRANSPORT_FAILURE,
                        "Splunk HEC drain iteration failed: " + e);
            }
        }
    }

    private void ship(List<ILoggingEvent> batch) {
        List<String> serialized = new ArrayList<>(batch.size());
        for (ILoggingEvent event : batch) {
            try {
                serialized.add(serializer.toJson(event));
            } catch (Exception e) {
                failed.incrementAndGet();
                warnThrottled(SplunkHecClient.Outcome.TRANSPORT_FAILURE,
                        "Splunk HEC could not serialize a log event: " + e);
            }
        }
        if (serialized.isEmpty()) return;

        SplunkHecClient.Outcome outcome =
                client.send(SplunkHecEventSerializer.toBatchBody(serialized));
        if (outcome == SplunkHecClient.Outcome.SUCCESS) {
            sent.addAndGet(serialized.size());
            return;
        }
        failed.addAndGet(serialized.size());
        warnThrottled(outcome, "Splunk HEC delivery failed (" + outcome + "); dropped "
                + serialized.size() + " events. Total failed: " + failed.get());
    }

    /**
     * One message per failure kind per minute. An unthrottled warning per failed batch would
     * itself become a log flood during an outage — the thing this appender exists to avoid.
     */
    private void warnThrottled(SplunkHecClient.Outcome kind, String message) {
        long now = System.nanoTime();
        synchronized (lastWarnedAt) {
            Long previous = lastWarnedAt.get(kind);
            if (previous != null && now - previous < WARN_THROTTLE_NANOS) return;
            lastWarnedAt.put(kind, now);
        }
        if (kind == SplunkHecClient.Outcome.AUTH_REJECTED) {
            addError(message + " Check SPLUNK_HEC_TOKEN.");
        } else {
            addWarn(message);
        }
    }

    private static String resolveHost() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            // Fails on some Docker network configurations. Never block startup over it.
            return "unknown";
        }
    }

    public record Snapshot(int queued, long sent, long dropped, long failed) {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=SplunkHecAppenderTest`
Expected: PASS, 8 tests.

If `dropsWhenQueueIsFullInsteadOfBlocking` is flaky, the cause is the drain thread emptying the queue faster than the loop fills it — raise the burst count rather than adding a sleep.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/observability/SplunkHecAppender.java \
        src/test/java/com/recsys/infrastructure/observability/SplunkHecAppenderTest.java
git commit -m "feat: add bounded drop-on-full Splunk HEC Logback appender

Same shape as AsyncEventPublisher: bounded queue, one daemon drain thread,
batched writes, at-most-once. A Splunk outage degrades to console-only and
append() never blocks, which the tests assert directly.

prepareForDeferredProcessing() runs on the caller thread because
LoggingEvent.getThreadName() resolves lazily and would otherwise report the
drain thread's name for every event.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Logback wiring for all four services

**Files:**
- Create: `src/main/resources/logback-common.xml`
- Create: `src/main/resources/logback.xml`
- Test: `src/test/java/com/recsys/infrastructure/observability/SplunkLogbackWiringTest.java`

(No `logback-spring.xml`: Spring Boot's standard-location check finds `logback.xml`
before it ever looks for a `-spring` variant, so that file would be unreachable dead
code — see the corrected prose below.)

**Interfaces:**
- Consumes: `SplunkHecAppender` (Task 4) by fully-qualified class name in XML.
- Produces: nothing for later tasks.

**Why a test for XML:** a typo in the appender's FQCN fails *silently* — Logback records a status error and the appender is simply absent. This test is the only thing that catches it before production.

**Behaviour change to be aware of:** the three Armeria mains have no Logback config today and use Logback's built-in default. Adding `logback.xml` gives them the repo's console pattern (with `traceId`) at INFO. That is an improvement, and intentional.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/infrastructure/observability/SplunkLogbackWiringTest.java`:

```java
package com.recsys.infrastructure.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.joran.spi.JoranException;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A wrong class name in the Logback XML fails silently — Logback records a status error and
 * the appender is simply missing. These tests are what catch that.
 *
 * <p>Note there is no test that configures {@code logback-common.xml} directly: its root
 * element is {@code <included>}, which Joran only accepts through an {@code <include>}, not
 * as a standalone configuration. It is covered transitively by the config tests below.
 */
class SplunkLogbackWiringTest {

    /**
     * Returns a configured context. The CALLER must stop it — stopping it here would also
     * stop the appenders, and {@code isStarted()} would then read false for the wrong reason.
     */
    private static LoggerContext configure(String configPath) throws JoranException {
        LoggerContext context = new LoggerContext();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(new File(configPath));
        return context;
    }

    private static List<Appender<?>> rootAppendersOf(LoggerContext context) {
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        List<Appender<?>> appenders = new ArrayList<>();
        for (Iterator<Appender<ch.qos.logback.classic.spi.ILoggingEvent>> it = root.iteratorForAppenders();
             it.hasNext(); ) {
            appenders.add(it.next());
        }
        return appenders;
    }

    @Test
    void logbackConfigAttachesConsoleAndSplunk() throws Exception {
        LoggerContext context = configure("src/main/resources/logback.xml");
        try {
            List<Appender<?>> appenders = rootAppendersOf(context);

            assertThat(appenders).hasSize(2);
            assertThat(appenders).anyMatch(a -> a instanceof ConsoleAppender);
            assertThat(appenders).anyMatch(a -> a instanceof SplunkHecAppender);
        } finally {
            context.stop();
        }
    }

    @Test
    void splunkAppenderIsInertWithoutATokenInTheEnvironment() throws Exception {
        // CI and local runs set no SPLUNK_HEC_TOKEN, so the appender must start and do nothing.
        // This is the property that means the test suite needs no opt-out flag. Skip rather
        // than fail if a developer happens to have the token exported in their shell.
        assumeTrue(System.getenv("SPLUNK_HEC_TOKEN") == null,
                "SPLUNK_HEC_TOKEN is set in this environment");

        LoggerContext context = configure("src/main/resources/logback.xml");
        try {
            SplunkHecAppender splunk = (SplunkHecAppender) rootAppendersOf(context).stream()
                    .filter(a -> a instanceof SplunkHecAppender)
                    .findFirst()
                    .orElseThrow();

            assertThat(splunk.isStarted()).isTrue(); // started, so Logback does not warn per event
            assertThat(splunk.snapshot().sent()).isZero();
            assertThat(splunk.snapshot().dropped()).isZero();
            assertThat(splunk.snapshot().failed()).isZero();
        } finally {
            context.stop();
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=SplunkLogbackWiringTest`
Expected: FAIL — `logback.xml` and `logback-common.xml` do not exist.

- [ ] **Step 3: Write the two config files**

Create `src/main/resources/logback-common.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!--
  Shared appender definitions for all four service mains.

  Included by logback.xml, which Logback self-initializes from for all four services
  (including Spring Boot on port 8080). Spring Boot's LogbackLoggingSystem checks
  standard locations — logback-test.xml, then logback.xml — BEFORE it ever looks at a
  -spring location, so once logback.xml exists on the classpath it wins for all four
  mains, including the model service; logback-spring.xml is unreachable and would be
  dead code. Keeping one shared appender definition means the four services cannot
  drift apart.

  CONSOLE is attached unconditionally: whatever Splunk is doing, nothing that reaches
  Logback is ever lost from stdout. SPLUNK is inert unless SPLUNK_HEC_TOKEN is set.
-->
<included>
  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} [traceId=%X{traceId:-none}] - %msg%n</pattern>
    </encoder>
  </appender>

  <appender name="SPLUNK" class="com.recsys.infrastructure.observability.SplunkHecAppender"/>

  <root level="INFO">
    <appender-ref ref="CONSOLE"/>
    <appender-ref ref="SPLUNK"/>
  </root>
</included>
```

Create `src/main/resources/logback.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!--
  Single entry point for all four service mains: the Spring Boot model service
  (port 8080) and the three Armeria mains (RecSysServer 6010, OnlinePredictionServer
  7010, MicroserviceGatewayServer 8010). Spring Boot defers to standard-location config
  files when present, so this file wins for all four. Logback prefers logback-test.xml
  over this file, so the test suite is unaffected.
-->
<configuration>
  <include resource="logback-common.xml"/>
</configuration>
```

There is no `logback-spring.xml`. It cannot be reached while `logback.xml` exists on the
classpath — Spring Boot's precedence check is standard locations first, `-spring`
locations only as a fallback when none of those exist — so a separate Spring config
would be dead code. All four services load the single `logback.xml` above.

The `springProperty`-sourced `appName` some earlier drafts of this plan carried is
deliberately dropped: it was unused by the console pattern, and the service identity
that matters here comes from `SPLUNK_SERVICE_NAME`. Since there is no `-spring` file in
the shipped design, `springProperty`/`springProfile` could not be used in the shared
fragment anyway — they only work from a `-spring` file, and there isn't one.

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=SplunkLogbackWiringTest`
Expected: PASS, 2 tests.

Then confirm nothing else regressed and that no recursion or startup noise appeared:

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test`
Expected: PASS, with no `StackOverflowError` and no flood of Splunk connection warnings.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/logback-common.xml src/main/resources/logback.xml \
        src/test/java/com/recsys/infrastructure/observability/SplunkLogbackWiringTest.java
git commit -m "feat: share one Logback config across all four service mains

Appenders move to logback-common.xml, included by a new logback.xml — the
single entry point for all four mains. Spring Boot's standard-location check
finds logback.xml before it ever looks for a -spring variant, so this file
wins for the model service too; there is no logback-spring.xml; a separate
Spring config would be unreachable dead code. The three Armeria mains
previously had no Logback config at all and used the built-in default.

A wrong appender FQCN in XML fails silently, so SplunkLogbackWiringTest
asserts the config actually attaches a SplunkHecAppender, and that it is
inert without SPLUNK_HEC_TOKEN.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Make the tests gate the PR, and wire the environment

**Files:**
- Modify: `pom.xml:330-361` (the `resilience` profile `<includes>`)
- Modify: `scripts/run-microservices-local.sh`
- Modify: `k8s/base/catalog-serving.yaml`, `k8s/base/model-serving.yaml`, `k8s/base/online-serving.yaml`, `k8s/base/api-gateway.yaml`

**Interfaces:**
- Consumes: the five test classes from Tasks 1–5; `SplunkHecConfig`'s env names.
- Produces: nothing for later tasks.

The `resilience` profile is what the PR gate runs, so it is the only place a check can block a merge. All five tests are pure unit-level with no Redis and no Docker, which is the bar that profile documents.

- [ ] **Step 1: Add the test includes to the resilience profile**

In `pom.xml`, immediately after the `<include>**/metrics/RedisCacheMetricsTest.java</include>` line, insert:

```xml
                <include>**/observability/SplunkHecConfigTest.java</include>
                <include>**/observability/SplunkHecEventSerializerTest.java</include>
                <include>**/observability/SplunkHecClientTest.java</include>
                <include>**/observability/SplunkHecAppenderTest.java</include>
                <include>**/observability/SplunkLogbackWiringTest.java</include>
```

- [ ] **Step 2: Verify the gate picks them up**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience`
Expected: PASS. Confirm the five `Splunk*` classes appear in the Surefire output — if they do not, the include pattern is wrong.

- [ ] **Step 3: Wire the local run script**

In `scripts/run-microservices-local.sh`, add this block immediately after the `mkdir -p logs` line:

```sh
# Splunk HEC log shipping is off unless SPLUNK_HEC_TOKEN is exported.
#   docker compose -f docker-compose.splunk.yml up -d
#   export SPLUNK_HEC_TOKEN=local-dev-hec-token
# The default URL targets the Docker service name `splunk`, which is right inside the
# compose network and in EKS. These four JVMs run on the HOST, where only the published
# localhost port resolves — so default to that instead.
SPLUNK_HEC_TOKEN="${SPLUNK_HEC_TOKEN:-}"
SPLUNK_HEC_URL="${SPLUNK_HEC_URL:-http://localhost:8088/services/collector/event}"
export SPLUNK_HEC_TOKEN SPLUNK_HEC_URL
```

Then add `SPLUNK_SERVICE_NAME=<name>` to each of the four `start_service` invocations, immediately after the existing `env` keyword's first assignment:

- `recsys-serving` → `SPLUNK_SERVICE_NAME=recsys-serving`
- `model-serving` → `SPLUNK_SERVICE_NAME=model-serving`
- `online-serving` → `SPLUNK_SERVICE_NAME=online-serving`
- `api-gateway` → `SPLUNK_SERVICE_NAME=api-gateway`

For example, the first becomes:

```sh
start_service recsys-serving env PORT=6010 \
  SPLUNK_SERVICE_NAME=recsys-serving \
  RECOMMENDATION_CURSOR_SIGNING_KEY="$RECOMMENDATION_CURSOR_SIGNING_KEY" \
```

`env` inherits the exported `SPLUNK_HEC_TOKEN` and `SPLUNK_HEC_URL`, so they need no per-service repetition.

- [ ] **Step 4: Verify the script still parses and starts**

Run: `sh -n scripts/run-microservices-local.sh`
Expected: no output (syntax OK).

Then, with no Splunk running and no token set:

```bash
RECOMMENDATION_CURSOR_SIGNING_KEY="$(openssl rand -hex 32)" sh scripts/run-microservices-local.sh
```

Expected: services start as before. `grep -ri splunk logs/` should show nothing — no token means the appender never even resolves a URL. Stop with Ctrl-C.

- [ ] **Step 5: Wire the Kubernetes manifests**

In each of the four `k8s/base/*.yaml` files, add this block to the container's `env:` list. Use the matching `SPLUNK_SERVICE_NAME` per file: `catalog-serving.yaml` → `recsys-serving`, `model-serving.yaml` → `model-serving`, `online-serving.yaml` → `online-serving`, `api-gateway.yaml` → `api-gateway`.

```yaml
            # Splunk HEC log shipping. Inert until the recsys-splunk Secret exists, so
            # optional: true keeps pods schedulable before it is provisioned — same pattern
            # as recsys-online-admin / SHARD_ADMIN_TOKEN. See
            # docs/runbooks/splunk-hec-logging.md.
            - name: SPLUNK_SERVICE_NAME
              value: "api-gateway"
            - name: SPLUNK_HEC_URL
              value: "http://splunk:8088/services/collector/event"
            - name: SPLUNK_HEC_TOKEN
              valueFrom:
                secretKeyRef:
                  name: recsys-splunk
                  key: hec-token
                  optional: true
```

- [ ] **Step 6: Verify the manifests still build**

Run: `kubectl kustomize k8s/base > /dev/null && kubectl kustomize k8s/eks > /dev/null && kubectl kustomize k8s/eks-us-west-2 > /dev/null`
Expected: no output, exit 0. Both region overlays must build, not just the base.

Then confirm all four services got the block:

Run: `kubectl kustomize k8s/base | grep -c SPLUNK_HEC_TOKEN`
Expected: `4`.

- [ ] **Step 7: Commit**

```bash
git add pom.xml scripts/run-microservices-local.sh k8s/base/
git commit -m "feat: gate Splunk appender tests in CI and wire the environment

All five tests join the resilience profile, which is what the PR gate runs
and so the only place a check can block a merge; they are pure unit-level,
matching that profile's documented bar.

k8s sources SPLUNK_HEC_TOKEN from an optional recsys-splunk Secret so pods
stay schedulable before it exists. The local script defaults the URL to
localhost because those four JVMs run on the host, where the Docker service
name splunk does not resolve.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Local Splunk stack

**Files:**
- Rename + modify: `docker-splunk.yml` → `docker-compose.splunk.yml`
- Create: `docker/splunk/init.sh`

**Interfaces:**
- Consumes: `SPLUNK_HEC_TOKEN` (must match what the app uses).
- Produces: a running Splunk with HEC on plain HTTP at `localhost:8088` and a `recsys` index.

**The one genuinely uncertain step.** A stock Splunk serves HEC over HTTPS with a self-signed certificate, but the requirement is plain `http://splunk:8088`. The `splunk/splunk` image's ansible layer accepts `SPLUNK_HEC_TOKEN` and `SPLUNK_HEC_SSL` environment variables, but their exact behaviour varies by image version. So: set the env vars **and** have `init.sh` verify and repair, then prove it with a real event. Do not skip Step 4 — it is what turns the assumption into a fact.

- [ ] **Step 1: Write the init script**

Create `docker/splunk/init.sh`:

```sh
#!/usr/bin/env sh
# Idempotent HEC provisioning for the local Splunk stand-in.
#
# Runs once after splunkd reports healthy. Re-running against existing volumes is a no-op,
# so `docker compose up` after a restart does not drift.
#
# Splunk's management API is always HTTPS with a self-signed cert, hence -k throughout.
# That is the MANAGEMENT port (8089). The HEC port (8088) is what we force to plain HTTP.
set -eu

MGMT="https://splunk:8089"
AUTH="admin:${SPLUNK_PASSWORD}"
INDEX="${SPLUNK_HEC_INDEX:-recsys}"
TOKEN="${SPLUNK_HEC_TOKEN}"

echo "==> Creating index '${INDEX}' (409 = already exists, fine)"
curl -kfsS -u "$AUTH" "${MGMT}/services/data/indexes" \
  -d "name=${INDEX}" -o /dev/null -w '%{http_code}\n' || true

echo "==> Enabling HEC globally with SSL off"
curl -kfsS -u "$AUTH" \
  "${MGMT}/servicesNS/nobody/splunk_httpinput/data/inputs/http/http" \
  -d disabled=0 -d enableSSL=0 -o /dev/null || true

echo "==> Creating HEC token 'recsys'"
curl -kfsS -u "$AUTH" \
  "${MGMT}/servicesNS/nobody/splunk_httpinput/data/inputs/http" \
  -d "name=recsys" -d "token=${TOKEN}" -d "index=${INDEX}" -d "indexes=${INDEX}" \
  -o /dev/null || true

echo "==> Verifying plain-HTTP HEC delivery"
i=0
while [ "$i" -lt 30 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' \
    -H "Authorization: Splunk ${TOKEN}" \
    -H 'Content-Type: application/json' \
    --data '{"event":{"message":"splunk-init verification"},"sourcetype":"recsys:app:log"}' \
    "http://splunk:8088/services/collector/event" || true)
  if [ "$code" = "200" ]; then
    echo "==> HEC is accepting events over plain HTTP. Ready."
    exit 0
  fi
  echo "    not ready yet (HTTP ${code}); retrying..."
  i=$((i + 1))
  sleep 5
done

echo "!!! HEC did not accept a plain-HTTP event after 150s." >&2
echo "!!! Check: docker compose -f docker-compose.splunk.yml logs splunk" >&2
exit 1
```

- [ ] **Step 2: Replace the compose file**

```bash
git mv docker-splunk.yml docker-compose.splunk.yml
```

(If `docker-splunk.yml` is still untracked, use `mv` instead.)

Then replace its contents with:

```yaml
# Local Splunk stand-in for HEC log shipping.
#
#   export SPLUNK_PASSWORD=changeme-splunk-admin
#   export SPLUNK_HEC_TOKEN=local-dev-hec-token
#   docker compose -f docker-compose.splunk.yml up -d
#   sh scripts/run-microservices-local.sh          # picks up SPLUNK_HEC_TOKEN
#   open http://localhost:8000                     # admin / $SPLUNK_PASSWORD
#
# Demonstrates HEC INGESTION ONLY. No indexer clustering, no search head cluster, no
# forwarders, no retention tiering — see docs/runbooks/splunk-hec-logging.md.
services:
  splunk:
    image: splunk/splunk:latest
    container_name: splunk
    environment:
      SPLUNK_START_ARGS: --accept-license
      SPLUNK_PASSWORD: ${SPLUNK_PASSWORD:?set SPLUNK_PASSWORD before starting Splunk}
      # Honoured by the image's ansible layer. splunk-init verifies and repairs both,
      # so a version that ignores them still ends up correct.
      SPLUNK_HEC_TOKEN: ${SPLUNK_HEC_TOKEN:?set SPLUNK_HEC_TOKEN before starting Splunk}
      SPLUNK_HEC_SSL: "False"
    ports:
      - "8000:8000"   # Splunk web UI
      - "8088:8088"   # HTTP Event Collector
    volumes:
      - splunk-etc:/opt/splunk/etc
      - splunk-var:/opt/splunk/var
    healthcheck:
      test:
        [
          "CMD-SHELL",
          "curl -kfsS https://localhost:8089/services/server/health/splunkd/details \
           -u admin:${SPLUNK_PASSWORD} >/dev/null"
        ]
      interval: 20s
      timeout: 10s
      retries: 10
      start_period: 90s

  # One-shot: create the index, force HEC onto plain HTTP, prove it accepts an event.
  splunk-init:
    image: curlimages/curl:8.10.1
    container_name: splunk-init
    depends_on:
      splunk:
        condition: service_healthy
    environment:
      SPLUNK_PASSWORD: ${SPLUNK_PASSWORD}
      SPLUNK_HEC_TOKEN: ${SPLUNK_HEC_TOKEN}
      SPLUNK_HEC_INDEX: ${SPLUNK_HEC_INDEX:-recsys}
    volumes:
      - ./docker/splunk/init.sh:/init.sh:ro
    entrypoint: ["sh", "/init.sh"]
    restart: "no"

volumes:
  splunk-etc:
  splunk-var:
```

Note the two removals from the draft: the four stale `redis-*` volume entries (nothing here referenced them) and the unguarded `${SPLUNK_PASSWORD}` (now `:?`, so a missing password fails loudly instead of creating an admin account with an empty password).

- [ ] **Step 3: Make the script executable and check syntax**

```bash
chmod +x docker/splunk/init.sh
sh -n docker/splunk/init.sh
docker compose -f docker-compose.splunk.yml config > /dev/null
```

Expected: no output from the first two; the third prints nothing on success (it will fail unless `SPLUNK_PASSWORD` and `SPLUNK_HEC_TOKEN` are exported — that is the `:?` guard working).

- [ ] **Step 4: Verify end to end (do not skip)**

```bash
export SPLUNK_PASSWORD=changeme-splunk-admin
export SPLUNK_HEC_TOKEN=local-dev-hec-token
docker compose -f docker-compose.splunk.yml up -d
docker compose -f docker-compose.splunk.yml logs -f splunk-init
```

Expected: `splunk-init` ends with `HEC is accepting events over plain HTTP. Ready.` and exit code 0. Splunk's first boot takes 1–3 minutes.

If it exits 1, HEC is still on HTTPS. Diagnose with:

```bash
curl -k -u "admin:$SPLUNK_PASSWORD" \
  "https://localhost:8089/servicesNS/nobody/splunk_httpinput/data/inputs/http/http?output_mode=json" \
  | grep -o '"enableSSL":[^,]*'
```

If it reports `enableSSL":true`, the settings POST did not take. Add a `splunkd` restart to `init.sh` after the enable step (`curl -kfsS -u "$AUTH" -X POST "${MGMT}/services/server/control/restart"`), then wait for health again before the verification loop. Record whichever path worked in the runbook in Task 8.

Now confirm the app path works. In a second shell:

```bash
RECOMMENDATION_CURSOR_SIGNING_KEY="$(openssl rand -hex 32)" sh scripts/run-microservices-local.sh
```

Then search in the UI at `http://localhost:8000` (admin / `$SPLUNK_PASSWORD`):

```
index=recsys sourcetype="recsys:app:log" | stats count by source
```

Expected: non-zero counts, with `source` showing the four service names. This is the acceptance criterion for the whole feature.

Tear down: `docker compose -f docker-compose.splunk.yml down` (add `-v` to discard the indexed data).

- [ ] **Step 5: Commit**

```bash
git add docker-compose.splunk.yml docker/splunk/init.sh
git rm --cached docker-splunk.yml 2>/dev/null || true
git commit -m "feat: add self-provisioning local Splunk stack

Renamed to match docker-compose.cdn.yml / .streaming.yml. Dropped four stale
redis-* volume entries nothing in the file referenced, and made
SPLUNK_PASSWORD required rather than silently empty.

Starting Splunk is not enough: HEC ships off, tokenless, and HTTPS-only. A
one-shot splunk-init creates the index, forces HEC onto plain HTTP, and
verifies by posting a real event, so 'up' means genuinely ready.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Documentation

**Files:**
- Create: `docs/runbooks/splunk-hec-logging.md`
- Modify: `README.md` (runbook index, the `*Traffic and load*` group area around line 520)
- Modify: `.claude/CLAUDE.md` (env var section)

**Interfaces:**
- Consumes: everything from Tasks 1–7, including whichever HEC-enabling path actually worked in Task 7 Step 4.
- Produces: nothing.

`DocumentationIndexTest` asserts in both directions — a new runbook with no README entry fails the build, and it runs in the `resilience` profile, so it blocks the PR.

- [ ] **Step 1: Write the runbook**

Create `docs/runbooks/splunk-hec-logging.md` covering, with real commands:

1. **What this is** — all four services ship structured JSON log events straight to Splunk HEC. Off unless `SPLUNK_HEC_TOKEN` is set. Console logging is never affected.
2. **The delivery contract** — at-most-once. Events are dropped, not retried, when the queue is full or Splunk is unreachable. **A Splunk search result is therefore a lower bound, not a complete record**; stdout / `logs/*.log` is the authoritative copy. State this plainly — someone will eventually use a Splunk count as evidence and needs to know its limits.
3. **Local bring-up** — the exact Task 7 Step 4 sequence, and whichever enable path proved correct.
4. **Enabling in EKS** — create the Secret, then restart the pods (the appender reads env at startup, so an existing pod will not pick it up):
   ```bash
   kubectl -n recsys create secret generic recsys-splunk --from-literal=hec-token='<token>'
   kubectl -n recsys rollout restart deployment/recsys-api-gateway
   ```
   Confirm the Secret name matches Task 6 Step 5 exactly.
5. **Disabling** — delete the Secret and restart; console logging continues untouched.
6. **Useful searches** — `index=recsys sourcetype="recsys:app:log" | stats count by source`; filtering by `level=ERROR`; correlating one request via `traceId` (noting that only the model service populates `traceId` today, because `TraceIdAspect` is Spring AOP).
7. **Tuning** — the full env-var table from the spec, and what to change when: raise `SPLUNK_HEC_QUEUE_CAPACITY` if `dropped` climbs during bursts; raise `SPLUNK_HEC_BATCH_SIZE` for throughput; raise `SPLUNK_HEC_TIMEOUT_MS` for a distant collector.
8. **Diagnosing** — the appender reports through Logback's status system, not slf4j, so its own failures appear as `WARN in ch.qos.logback...` lines on stdout rather than in Splunk. List the four `Outcome` values and what each means. Note `SPLUNK_HEC_INSECURE_TLS` is dev-only.
9. **Divergences from a production Splunk** — single instance, no clustering, no forwarders, no retention tiering, HEC on plain HTTP with a shared dev token. Mirror the framing `docs/runbooks/cdn-local.md` uses.

- [ ] **Step 2: Add the README index entry**

In `README.md`, in the runbook index under the `*Traffic and load*` group (it sits with the other operational-visibility entries), add:

```markdown
- [Splunk HEC logging](docs/runbooks/splunk-hec-logging.md) — shipping structured application logs to Splunk, and the at-most-once limits of what lands there.
```

- [ ] **Step 3: Add the CLAUDE.md env vars**

In `.claude/CLAUDE.md`, in the "Key env vars" paragraph of the Services & Ports section, append:

```markdown
`SPLUNK_HEC_TOKEN` (default unset = Splunk log shipping is off; setting it makes all four
services ship structured JSON log events to `SPLUNK_HEC_URL`, default
`http://splunk:8088/services/collector/event`, via a bounded drop-on-full Logback appender —
console logging is unaffected either way). `SPLUNK_SERVICE_NAME` sets the Splunk `source`
field per service; `SPLUNK_HEC_INDEX` / `_SOURCETYPE` / `_QUEUE_CAPACITY` / `_BATCH_SIZE` /
`_LINGER_MS` / `_TIMEOUT_MS` / `_INSECURE_TLS` tune the appender. Delivery is **at-most-once**
by design, so a Splunk search is a lower bound on what was logged — see
`docs/runbooks/splunk-hec-logging.md`.
```

- [ ] **Step 4: Verify the docs index test passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=DocumentationIndexTest`
Expected: PASS. A failure here means the README link and the file path disagree — fix the link, not the test.

Then the full gate:

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add docs/runbooks/splunk-hec-logging.md README.md .claude/CLAUDE.md
git commit -m "docs: add Splunk HEC logging runbook

Documents bring-up, EKS enablement, tuning, and diagnosis. States plainly
that delivery is at-most-once, so a Splunk search result is a lower bound
rather than a complete record — someone will eventually cite a Splunk count
as evidence and needs to know its limits.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Final Verification

- [ ] Full suite: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test` — PASS
- [ ] PR gate: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience` — PASS, five `Splunk*` classes present in output
- [ ] Package: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn package -DskipTests` — PASS
- [ ] Manifests: `kubectl kustomize k8s/base && kubectl kustomize k8s/eks && kubectl kustomize k8s/eks-us-west-2` — all build
- [ ] End-to-end: Task 7 Step 4 search returns events from all four `source` values
- [ ] **Off by default:** with no `SPLUNK_HEC_TOKEN`, start the services and confirm `logs/*.log` contains no Splunk connection errors and no `StackOverflowError`
- [ ] Open a PR (never merge to `main` directly)
