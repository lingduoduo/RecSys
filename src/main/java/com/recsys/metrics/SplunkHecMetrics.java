package com.recsys.metrics;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import com.recsys.infrastructure.observability.SplunkHecAppender;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.function.Supplier;

/**
 * Publishes {@link SplunkHecAppender}'s delivery counters as Micrometer metrics.
 *
 * <p>Phase 1 shipped those counters and exposed them to nothing: log-shipping loss shows up
 * only as {@code WARN in ch.qos.logback...} lines on stdout, which is not where anyone looks
 * when asking "are we losing logs?". This is the bridge that makes them alertable.
 *
 * <p>The four {@code _total} metrics are registered as {@link FunctionCounter}s wrapping the
 * appender's externally-owned monotonic {@code AtomicLong}s; {@code queue_depth} is a
 * {@link Gauge} because it rises and falls. {@code FunctionCounter} is designed precisely for
 * this: reading an already-monotonic external value without a second increment site or
 * double-counting.
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
        register(registry, appender::snapshot);
    }

    /** Package-private overload for testing with a custom snapshot supplier. */
    static void register(MeterRegistry registry, Supplier<SplunkHecAppender.Snapshot> snapshotSupplier) {
        SnapshotHolder holder = new SnapshotHolder(snapshotSupplier);
        FunctionCounter.builder("splunk_hec_events_sent_total", holder,
                h -> h.getSnapshot().sent())
                .description("Log events confirmed accepted by Splunk (2xx).")
                .register(registry);
        FunctionCounter.builder("splunk_hec_events_dropped_total", holder,
                h -> h.getSnapshot().dropped())
                .description("Log events discarded because the bounded queue was full.")
                .register(registry);
        FunctionCounter.builder("splunk_hec_events_failed_total", holder,
                h -> h.getSnapshot().failed())
                .description("Log events Splunk definitively refused, or that never left the host.")
                .register(registry);
        FunctionCounter.builder("splunk_hec_events_indeterminate_total", holder,
                h -> h.getSnapshot().indeterminate())
                .description("Log events sent but never acknowledged: possibly delivered, possibly lost.")
                .register(registry);
        Gauge.builder("splunk_hec_queue_depth", holder,
                h -> h.getSnapshot().queued())
                .description("Log events currently waiting in the appender's bounded queue.")
                .register(registry);
    }

    /** Helper to hold a snapshot supplier for use with Micrometer builders. */
    private static class SnapshotHolder {
        private final Supplier<SplunkHecAppender.Snapshot> supplier;

        SnapshotHolder(Supplier<SplunkHecAppender.Snapshot> supplier) {
            this.supplier = supplier;
        }

        SplunkHecAppender.Snapshot getSnapshot() {
            return supplier.get();
        }
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
