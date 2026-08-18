package com.recsys.jvm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GcEventLoggingTest {

    private static final long GB = 1024L * 1024L * 1024L;

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;
    private GcEventTracker tracker;

    @BeforeEach
    void setUp() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger(GcEventTracker.class);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        tracker = new GcEventTracker();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private List<ILoggingEvent> events() {
        return appender.list;
    }

    private void gc(long pauseMs, long heapUsedBytes) {
        tracker.evaluateForLogging("G1 Young Generation", "end of minor GC", "G1 Evacuation Pause",
                pauseMs, true, heapUsedBytes, 10 * GB);
    }

    @Test
    void logsAPauseOverTheThreshold() {
        gc(500, 1 * GB);
        assertThat(events()).hasSize(1);
        ILoggingEvent e = events().get(0);
        assertThat(e.getLevel()).isEqualTo(Level.WARN);
        assertThat(e.getMDCPropertyMap())
                .containsEntry("pauseMs", "500")
                .containsEntry("gcCause", "G1 Evacuation Pause")
                .containsKey("heapUsedFraction");
    }

    @Test
    void staysSilentBelowTheThreshold() {
        gc(10, 1 * GB);
        assertThat(events()).isEmpty();
    }

    @Test
    void concurrentCyclesNeverProduceAPauseEvent() {
        // A ZGC cycle's reported wall time includes concurrent phases; its true STW pause is
        // sub-millisecond. Thresholding on wall time would fire constantly on a healthy service.
        tracker.evaluateForLogging("ZGC Cycles", "end of GC cycle", "Warmup",
                5000, false, 1 * GB, 10 * GB);
        assertThat(events()).isEmpty();
    }

    @Test
    void heapPressureIsEdgeTriggeredWithHysteresis() {
        gc(1, 5 * GB);            // 0.50 — below threshold, silent
        assertThat(events()).isEmpty();

        gc(1, (long) (9.5 * GB)); // 0.95 — crosses 0.90 upward: one WARN
        assertThat(events()).hasSize(1);
        assertThat(events().get(0).getLevel()).isEqualTo(Level.WARN);

        gc(1, (long) (9.6 * GB)); // still above: no further event
        gc(1, (long) (9.7 * GB));
        assertThat(events()).hasSize(1);

        gc(1, (long) (8.5 * GB)); // 0.85 — between recovery and threshold: still no event
        assertThat(events()).hasSize(1);

        gc(1, (long) (7.0 * GB)); // 0.70 — below recovery: one INFO
        assertThat(events()).hasSize(2);
        assertThat(events().get(1).getLevel()).isEqualTo(Level.INFO);

        gc(1, (long) (9.5 * GB)); // crosses again: one more WARN
        assertThat(events()).hasSize(3);
    }

    @Test
    void anUnsetHeapMaxProducesNoPressureEvent() {
        // MemoryUsage.getMax() is -1 for pools with no maximum; a fraction is meaningless there.
        tracker.evaluateForLogging("G1 Young Generation", "end of minor GC", "Allocation Failure",
                1, true, 1 * GB, -1);
        assertThat(events()).isEmpty();
    }
}
