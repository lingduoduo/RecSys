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
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

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

    /**
     * Every other test in this class constructs {@code SplunkHecMetrics} directly, so deleting
     * the {@code SplunkHecMetrics.register(...)} call from any of the three Armeria mains, or
     * from the Spring model service's {@code SplunkHecMetricsConfig}, would leave the whole
     * suite green — the wiring, not just the class, would be untested. This is a
     * source-scanning assertion in the same manifest-checking style
     * {@code ScrapeTargetManifestTest} uses for the k8s manifests: it does not boot any
     * service, it just proves the call site still exists in each of the four files that must
     * make it, so a deleted registration call fails a test instead of only being noticed when
     * the Splunk metrics silently stop appearing on `/metrics`.
     */
    @Test
    void allFourServiceMainsCallSplunkHecMetricsRegister() throws IOException {
        List<Path> mustRegister = List.of(
                Path.of("src/main/java/com/recsys/api/serving/RecSysServer.java"),
                Path.of("src/main/java/com/recsys/api/online/OnlinePredictionServer.java"),
                Path.of("src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java"),
                Path.of("src/main/java/com/recsys/config/SplunkHecMetricsConfig.java"));

        for (Path file : mustRegister) {
            String source = Files.readString(file);
            assertThat(source)
                    .as("%s must call SplunkHecMetrics.register(...), or Splunk delivery "
                            + "metrics silently stop being published from that service", file)
                    .contains("SplunkHecMetrics.register(");
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

    @Test
    void metersSurviveGarbageCollectionOfTheirBackingState() {
        // Micrometer stores the state object handed to FunctionCounter/Gauge builders as a
        // WeakReference. register()'s local `holder` variable is not captured by any of the
        // five value-lambdas (they take it as a parameter instead), so unless register() keeps
        // its own strong reference alive, `holder` is eligible for collection the moment
        // register() returns — and a GC then freezes every counter and NaNs the gauge, silently.
        // This test proves the fix survives a *real* GC, not a hopeful one: it forces an actual
        // collection (verified via a canary WeakReference) before making its assertions.
        MeterRegistry registry = new SimpleMeterRegistry();
        AtomicLong failed = new AtomicLong(7);
        AtomicInteger queued = new AtomicInteger(3);
        Supplier<SplunkHecAppender.Snapshot> mutableSupplier = () ->
                new SplunkHecAppender.Snapshot(queued.get(), 0, 0, failed.get(), 0);

        SplunkHecMetrics.register(registry, mutableSupplier);

        assertThat(registry.get("splunk_hec_events_failed_total").functionCounter().count())
                .isEqualTo(7.0);
        assertThat(registry.get("splunk_hec_queue_depth").gauge().value()).isEqualTo(3.0);

        // Confirm the meters track live state before touching the GC at all.
        failed.set(20);
        queued.set(9);
        assertThat(registry.get("splunk_hec_events_failed_total").functionCounter().count())
                .isEqualTo(20.0);
        assertThat(registry.get("splunk_hec_queue_depth").gauge().value()).isEqualTo(9.0);

        forceARealGarbageCollection();

        // Post-GC: if register()'s state object was collected, the counter freezes at 20.0
        // forever and the gauge goes NaN. Neither may happen.
        failed.set(999);
        queued.set(42);
        assertThat(registry.get("splunk_hec_events_failed_total").functionCounter().count())
                .as("a FunctionCounter must keep tracking its backing state after a GC, "
                        + "not silently freeze at its last pre-GC value")
                .isEqualTo(999.0);
        assertThat(registry.get("splunk_hec_queue_depth").gauge().value())
                .as("a Gauge must not go NaN after a GC collects its Micrometer-weakly-held "
                        + "state object")
                .isEqualTo(42.0);
    }

    /**
     * Forces an actual garbage collection and proves it happened, rather than merely hoping
     * {@code System.gc()} did something. A canary object is wrapped in a {@link WeakReference};
     * once that reference clears, a real collection is known to have run, so the test's own
     * premise — "a GC occurred" — is not itself a flaky assumption.
     */
    private static void forceARealGarbageCollection() {
        WeakReference<Object> canary = new WeakReference<>(new Object());
        for (int attempt = 0; attempt < 50 && canary.get() != null; attempt++) {
            System.gc();
            System.runFinalization();
            // Allocate pressure: a bare System.gc() call is only ever a JVM hint. Forcing real
            // allocation churn makes an actual young-gen collection far more likely to run.
            byte[][] pressure = new byte[64][];
            for (int i = 0; i < pressure.length; i++) {
                pressure[i] = new byte[1024 * 1024];
            }
            pressure = null;
        }
        assertThat(canary.get())
                .as("test bug, not product bug: could not force a real GC to occur, "
                        + "so this test cannot prove anything")
                .isNull();
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
