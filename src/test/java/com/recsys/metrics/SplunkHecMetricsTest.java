package com.recsys.metrics;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.Appender;
import com.recsys.infrastructure.observability.SplunkHecAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Iterator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SplunkHecMetricsTest {

    @Test
    void registersNothingWhenNoSplunkAppenderIsAttached() {
        MeterRegistry registry = new SimpleMeterRegistry();
        LoggerContext bare = new LoggerContext(); // no appenders at all

        assertThatCode(() -> SplunkHecMetrics.register(registry, bare))
                .as("a JVM whose Logback config has no SPLUNK appender must not blow up")
                .doesNotThrowAnyException();

        assertThat(registry.find("splunk_hec_events_sent_total").functionCounter()).isNull();
    }

    @Test
    void registersMetersWhenTheAppenderIsPresent() throws Exception {
        MeterRegistry registry = new SimpleMeterRegistry();
        LoggerContext context = configuredContext();
        try {
            SplunkHecMetrics.register(registry, context);

            assertThat(registry.find("splunk_hec_events_sent_total").functionCounter()).isNotNull();
            assertThat(registry.find("splunk_hec_events_dropped_total").functionCounter()).isNotNull();
            assertThat(registry.find("splunk_hec_events_failed_total").functionCounter()).isNotNull();
            assertThat(registry.find("splunk_hec_events_indeterminate_total").functionCounter()).isNotNull();
            assertThat(registry.find("splunk_hec_queue_depth").gauge()).isNotNull();
        } finally {
            context.stop();
        }
    }

    @Test
    void metersReportTheAppendersSnapshot() throws Exception {
        MeterRegistry registry = new SimpleMeterRegistry();
        LoggerContext context = configuredContext();
        try {
            SplunkHecMetrics.register(registry, context);

            // With no SPLUNK_HEC_TOKEN the appender is inert, so every counter is zero —
            // which is exactly what a healthy idle service should report.
            assertThat(registry.get("splunk_hec_events_sent_total").functionCounter().count()).isZero();
            assertThat(registry.get("splunk_hec_events_dropped_total").functionCounter().count()).isZero();
            assertThat(registry.get("splunk_hec_queue_depth").gauge().value()).isZero();
        } finally {
            context.stop();
        }
    }

    @Test
    void eachMeterReportsItsOwnFieldWithDistinctValues() {
        // All non-appender-backed tests run against a disabled appender where every field is 0.
        // Swapping any two lambdas in the implementation leaves all tests green (0 == 0).
        // This test drives distinct non-zero values through each field and asserts the mapping.
        // Transposing any two lambdas will fail this test.
        MeterRegistry registry = new SimpleMeterRegistry();
        SplunkHecAppender.Snapshot snapshot = new SplunkHecAppender.Snapshot(
                1, // queued (int)
                2, // sent (long)
                3, // dropped (long)
                4, // failed (long)
                5  // indeterminate (long)
        );

        SplunkHecMetrics.register(registry, () -> snapshot);

        assertThat(registry.get("splunk_hec_events_sent_total").functionCounter().count())
                .as("sent_total should report sent field (2)")
                .isEqualTo(2.0);
        assertThat(registry.get("splunk_hec_events_dropped_total").functionCounter().count())
                .as("dropped_total should report dropped field (3)")
                .isEqualTo(3.0);
        assertThat(registry.get("splunk_hec_events_failed_total").functionCounter().count())
                .as("failed_total should report failed field (4)")
                .isEqualTo(4.0);
        assertThat(registry.get("splunk_hec_events_indeterminate_total").functionCounter().count())
                .as("indeterminate_total should report indeterminate field (5)")
                .isEqualTo(5.0);
        assertThat(registry.get("splunk_hec_queue_depth").gauge().value())
                .as("queue_depth should report queued field (1)")
                .isEqualTo(1.0);
    }

    @Test
    void theRealLogbackConfigStillDeclaresAnAppenderNamedSplunk() throws Exception {
        // register() finds the appender by the name "SPLUNK". If someone renames it in
        // logback-common.xml, the lookup silently returns null and every metric vanishes.
        // This turns that rename into a test failure instead.
        LoggerContext context = configuredContext();
        try {
            Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
            boolean found = false;
            for (Iterator<Appender<ch.qos.logback.classic.spi.ILoggingEvent>> it =
                    root.iteratorForAppenders(); it.hasNext(); ) {
                if ("SPLUNK".equals(it.next().getName())) found = true;
            }
            assertThat(found)
                    .as("logback-common.xml must keep an appender named SPLUNK, or "
                            + "SplunkHecMetrics.register finds nothing and reports no metrics")
                    .isTrue();
        } finally {
            context.stop();
        }
    }

    /** Configures a context from the real logback.xml, as the running services do. */
    private static LoggerContext configuredContext() throws Exception {
        LoggerContext context = new LoggerContext();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(new File("src/main/resources/logback.xml"));
        return context;
    }
}
