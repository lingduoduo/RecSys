package com.recsys.infrastructure.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real {@code docker/splunk/init.sh} against a real Splunk, then ships real events
 * through a real {@link SplunkHecAppender} and asserts they are searchable.
 *
 * <p>This test exists because two questions could not be answered on the machine the feature
 * was written on. That host is arm64; Splunk publishes no arm64 image, and under emulation
 * {@code splunkd} segfaults during first-boot indexing. The open questions were:
 *
 * <ol>
 *   <li>Does the provisioning actually enable HEC over <em>plain HTTP</em>? The design sets
 *       {@code SPLUNK_HEC_SSL} <em>and</em> has {@code init.sh} repair via the management API,
 *       because it was unknown which one takes effect. {@link #initScriptEnablesPlainHttpHec()}
 *       settles it — the script's own verification loop exits non-zero if HEC will not accept
 *       a plain-HTTP event.</li>
 *   <li>Does what the appender emits actually parse and index correctly in Splunk? The unit
 *       tests prove the wire format against a stub collector, which cannot catch a payload
 *       Splunk rejects or silently mangles. {@link #shippedEventsAreSearchableInSplunk()}
 *       closes that gap.</li>
 * </ol>
 *
 * <p>Tagged docker: excluded from {@code mvn test} by default (pom.xml). Run with
 * {@code mvn test -DexcludedGroups=load -Dgroups=docker -Dtest=SplunkHecIntegrationTest}
 * on an <strong>x86_64</strong> host. CI runs it on {@code ubuntu-latest}
 * (.github/workflows/splunk-hec-integration.yml, plus the weekly docker job).
 *
 * <p>Deliberately drives the container's own {@code curl} for the search API rather than an
 * in-JVM HTTPS client: Splunk's management port always presents a self-signed certificate, and
 * shelling out avoids building a trust-all {@code SSLContext} in test code purely to talk to
 * it. It also exercises the same query the runbook tells operators to run.
 */
@Tag("docker")
class SplunkHecIntegrationTest {

    private static final String PASSWORD = "integration-test-password";
    private static final String HEC_TOKEN = "integration-test-hec-token";
    private static final String INDEX = "recsys";
    private static final String SERVICE_NAME = "integration-test-service";

    /** Splunk's ansible layer prints this once the instance is actually up. */
    private static final String READY_LOG = ".*Ansible playbook complete.*";

    private static GenericContainer<?> splunk;

    @BeforeAll
    static void startSplunk() {
        splunk = new GenericContainer<>("splunk/splunk:latest")
                .withEnv("SPLUNK_START_ARGS", "--accept-license")
                .withEnv("SPLUNK_GENERAL_TERMS", "--accept-sgt-current-at-splunk-com")
                .withEnv("SPLUNK_PASSWORD", PASSWORD)
                .withEnv("SPLUNK_HEC_TOKEN", HEC_TOKEN)
                .withEnv("SPLUNK_HEC_SSL", "False")
                .withExposedPorts(8088, 8089)
                // The real script, not a copy of its steps — mode 0755 so it is executable.
                .withCopyFileToContainer(
                        MountableFile.forHostPath("docker/splunk/init.sh", 0755),
                        "/tmp/init.sh")
                // Splunk's first boot is slow even on x86_64; this is not a hang.
                .waitingFor(Wait.forLogMessage(READY_LOG, 1)
                        .withStartupTimeout(Duration.ofMinutes(10)));
        splunk.start();
    }

    @AfterAll
    static void stopSplunk() {
        if (splunk != null) splunk.stop();
    }

    /**
     * Open question 1. Runs the shipped provisioning script inside the container, pointed at
     * localhost instead of the compose service name. The script's own final loop POSTs a real
     * event to plain-HTTP 8088 and exits non-zero if that never returns 200, so a zero exit
     * IS the proof — there is nothing further to assert.
     */
    @Test
    void initScriptEnablesPlainHttpHec() throws Exception {
        GenericContainer.ExecResult result = runInitScript();

        assertThat(result.getStdout())
                .as("init.sh should reach its success message")
                .contains("HEC is accepting events over plain HTTP");
        assertThat(result.getExitCode())
                .as("init.sh stdout:%n%s%nstderr:%n%s", result.getStdout(), result.getStderr())
                .isZero();
    }

    /**
     * Open question 2. Ships events through the real appender to the real collector, then
     * searches for them. Asserts the envelope fields Splunk indexes as metadata (source,
     * sourcetype, index) and the payload fields, including an MDC entry — the runbook's
     * correlation query depends on all of them arriving intact.
     */
    @Test
    void shippedEventsAreSearchableInSplunk() throws Exception {
        runInitScript();

        // Unique per run so a re-run against a warm container cannot match a previous run's events.
        String marker = "splunk-hec-itest-" + UUID.randomUUID();
        String traceId = "trace-" + UUID.randomUUID();

        SplunkHecConfig config = SplunkHecConfig.from(Map.of(
                "SPLUNK_HEC_TOKEN", HEC_TOKEN,
                "SPLUNK_HEC_URL",
                "http://" + splunk.getHost() + ":" + splunk.getMappedPort(8088)
                        + "/services/collector/event",
                "SPLUNK_HEC_INDEX", INDEX,
                "SPLUNK_SERVICE_NAME", SERVICE_NAME,
                // Small batch so the shutdown flush path is exercised alongside the drain loop.
                "SPLUNK_HEC_BATCH_SIZE", "3",
                "SPLUNK_HEC_TIMEOUT_MS", "10000"));
        assertThat(config.isEnabled()).isTrue();

        SplunkHecClient client = new SplunkHecClient(config);
        SplunkHecAppender appender = new SplunkHecAppender(config, client);
        appender.setContext(new LoggerContext());
        appender.start();
        try {
            for (int i = 0; i < 10; i++) {
                appender.doAppend(event(marker + " event-" + i, traceId));
            }
        } finally {
            // Flushes anything still queued; the appender is at-most-once, so this is what
            // guarantees all ten are handed to Splunk before we search.
            appender.stop();
        }

        SplunkHecAppender.Snapshot snapshot = appender.snapshot();
        // Splunk's own rejection text, surfaced by SplunkHecClient. Without it a failure here
        // says only "3 failed" and gives no way to tell a misconfiguration from back-pressure.
        String diagnostics = "snapshot=" + snapshot
                + ", lastFailureDetail=" + client.lastFailureDetail();

        assertThat(snapshot.dropped())
                .as("queue capacity is 10000 and we sent 10; nothing should be dropped. %s",
                        diagnostics)
                .isZero();
        assertThat(snapshot.failed())
                .as("no batch should be rejected by a real collector. %s", diagnostics)
                .isZero();
        assertThat(snapshot.sent()).as(diagnostics).isEqualTo(10);

        String hits = searchUntilFound(marker, 10);

        assertThat(hits).contains(marker);
        assertThat(hits)
                .as("SPLUNK_SERVICE_NAME must land in Splunk's `source` metadata field")
                .contains("\"source\":\"" + SERVICE_NAME + "\"");
        assertThat(hits).contains("\"sourcetype\":\"recsys:app:log\"");
        assertThat(hits).contains("\"index\":\"" + INDEX + "\"");
        // Payload fields, extracted by Splunk from the JSON event body.
        assertThat(hits).contains("WARN");
        assertThat(hits)
                .as("MDC entries must survive the trip; the runbook's correlation query needs this")
                .contains(traceId);
    }

    // --- helpers ---------------------------------------------------------------------------

    private static GenericContainer.ExecResult runInitScript() throws Exception {
        // SPLUNK_HOST=localhost because we are running inside the Splunk container itself.
        // SPLUNK_PASSWORD / SPLUNK_HEC_TOKEN are already in the container's environment.
        return splunk.execInContainer("sh", "-c", "SPLUNK_HOST=localhost sh /tmp/init.sh");
    }

    /**
     * Splunk indexing is not synchronous with ingestion — a search immediately after a 200 from
     * HEC routinely returns nothing. Polls until all expected events are visible rather than
     * sleeping a fixed interval and hoping.
     */
    private static String searchUntilFound(String marker, int expectedCount) throws Exception {
        String query = "search index=" + INDEX + " \"" + marker + "\"";
        String command = "curl -kfsS -u admin:" + PASSWORD
                + " https://localhost:8089/services/search/jobs/export"
                + " --data-urlencode 'search=" + query + "'"
                + " -d output_mode=json -d earliest_time=-5m";

        String last = "";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            GenericContainer.ExecResult result = splunk.execInContainer("sh", "-c", command);
            last = result.getStdout();
            if (countOccurrences(last, marker) >= expectedCount) {
                return last;
            }
            Thread.sleep(2_000);
        }
        throw new AssertionError("Splunk never returned " + expectedCount + " events for marker '"
                + marker + "' within 120s. Last search response:\n" + last);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private static LoggingEvent event(String message, String traceId) {
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        LoggingEvent event = new LoggingEvent();
        event.setLoggerContext(context);
        event.setLoggerName("com.recsys.IntegrationTest");
        event.setLevel(Level.WARN);
        event.setMessage(message);
        event.setTimeStamp(System.currentTimeMillis());
        Map<String, String> mdc = new HashMap<>();
        mdc.put("traceId", traceId);
        event.setMDCPropertyMap(mdc);
        return event;
    }
}
