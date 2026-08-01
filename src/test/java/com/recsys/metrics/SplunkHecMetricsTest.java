package com.recsys.metrics;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.Appender;
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

        assertThat(registry.find("splunk_hec_events_sent_total").gauge()).isNull();
    }

    @Test
    void registersGaugesWhenTheAppenderIsPresent() throws Exception {
        MeterRegistry registry = new SimpleMeterRegistry();
        LoggerContext context = configuredContext();
        try {
            SplunkHecMetrics.register(registry, context);

            assertThat(registry.find("splunk_hec_events_sent_total").gauge()).isNotNull();
            assertThat(registry.find("splunk_hec_events_dropped_total").gauge()).isNotNull();
            assertThat(registry.find("splunk_hec_events_failed_total").gauge()).isNotNull();
            assertThat(registry.find("splunk_hec_events_indeterminate_total").gauge()).isNotNull();
            assertThat(registry.find("splunk_hec_queue_depth").gauge()).isNotNull();
        } finally {
            context.stop();
        }
    }

    @Test
    void gaugesReportTheAppendersSnapshot() throws Exception {
        MeterRegistry registry = new SimpleMeterRegistry();
        LoggerContext context = configuredContext();
        try {
            SplunkHecMetrics.register(registry, context);

            // With no SPLUNK_HEC_TOKEN the appender is inert, so every counter is zero —
            // which is exactly what a healthy idle service should report.
            assertThat(registry.get("splunk_hec_events_sent_total").gauge().value()).isZero();
            assertThat(registry.get("splunk_hec_events_dropped_total").gauge().value()).isZero();
            assertThat(registry.get("splunk_hec_queue_depth").gauge().value()).isZero();
        } finally {
            context.stop();
        }
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
