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
