package com.recsys.metrics;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import com.recsys.infrastructure.observability.SplunkHecAppender;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.LoggerFactory;

import java.util.Iterator;

/**
 * Publishes {@link SplunkHecAppender}'s delivery counters as Micrometer gauges.
 *
 * <p>Phase 1 shipped those counters and exposed them to nothing: log-shipping loss shows up
 * only as {@code WARN in ch.qos.logback...} lines on stdout, which is not where anyone looks
 * when asking "are we losing logs?". This is the bridge that makes them alertable.
 *
 * <p>Registered as <strong>gauges over a monotonic source</strong> rather than counters. The
 * appender already owns {@code AtomicLong}s; a Micrometer {@code Counter} would need a second
 * increment site and would double-count. Prometheus {@code rate()} and {@code increase()}
 * work correctly over a monotonically increasing gauge.
 *
 * <p>The appender is constructed by Logback before any {@link MeterRegistry} exists, which is
 * why this looks it up from the root logger instead of being handed one. It is a no-op when
 * the appender is absent or the config declares no {@code SPLUNK} appender.
 */
public final class SplunkHecMetrics {

    /** Must match the appender's name in {@code logback-common.xml}. */
    private static final String APPENDER_NAME = "SPLUNK";

    private SplunkHecMetrics() {}

    /** Registers against the JVM's active Logback context. */
    public static void register(MeterRegistry registry) {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            register(registry, context);
        }
    }

    static void register(MeterRegistry registry, LoggerContext context) {
        SplunkHecAppender appender = findAppender(context);
        if (appender == null) {
            return; // No Splunk appender configured — nothing to publish.
        }

        gauge(registry, "splunk_hec_events_sent_total", appender,
                a -> a.snapshot().sent(),
                "Log events confirmed accepted by Splunk (2xx).");
        gauge(registry, "splunk_hec_events_dropped_total", appender,
                a -> a.snapshot().dropped(),
                "Log events discarded because the bounded queue was full.");
        gauge(registry, "splunk_hec_events_failed_total", appender,
                a -> a.snapshot().failed(),
                "Log events Splunk definitively refused, or that never left the host.");
        gauge(registry, "splunk_hec_events_indeterminate_total", appender,
                a -> a.snapshot().indeterminate(),
                "Log events sent but never acknowledged: possibly delivered, possibly lost.");
        gauge(registry, "splunk_hec_queue_depth", appender,
                a -> a.snapshot().queued(),
                "Log events currently waiting in the appender's bounded queue.");
    }

    private static void gauge(MeterRegistry registry, String name, SplunkHecAppender appender,
                              java.util.function.ToDoubleFunction<SplunkHecAppender> value,
                              String description) {
        Gauge.builder(name, appender, value)
                .description(description)
                .register(registry);
    }

    private static SplunkHecAppender findAppender(LoggerContext context) {
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        for (Iterator<Appender<ILoggingEvent>> it = root.iteratorForAppenders(); it.hasNext(); ) {
            Appender<ILoggingEvent> appender = it.next();
            if (APPENDER_NAME.equals(appender.getName()) && appender instanceof SplunkHecAppender s) {
                return s;
            }
        }
        return null;
    }
}
