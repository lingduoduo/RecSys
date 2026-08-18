package com.recsys.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

class SlowRequestInterceptorTest {

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;
    private SlowRequestInterceptor interceptor;

    @BeforeEach
    void setUp() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger(SlowRequestInterceptor.class);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
        interceptor = new SlowRequestInterceptor(100);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private MockHttpServletRequest request(String pattern) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/models/recmodel:predict");
        req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        return req;
    }

    private void run(MockHttpServletRequest req, MockHttpServletResponse res, long elapsedMs) {
        interceptor.preHandle(req, res, new Object());
        // Rewind the recorded start so the interceptor measures a controlled duration rather
        // than the test's own runtime.
        req.setAttribute(SlowRequestInterceptor.START_NANOS_ATTRIBUTE,
                System.nanoTime() - elapsedMs * 1_000_000L);
        interceptor.afterCompletion(req, res, new Object(), null);
    }

    @Test
    void aFastSuccessfulRequestLogsNothing() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(200);
        run(request("/v1/models/{name}:predict"), res, 5);
        assertThat(appender.list).isEmpty();
    }

    @Test
    void aSlowRequestLogsOneWarnWithItsFields() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(200);
        run(request("/v1/models/{name}:predict"), res, 250);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent e = appender.list.get(0);
        assertThat(e.getLevel()).isEqualTo(Level.WARN);
        assertThat(e.getMDCPropertyMap())
                .containsEntry("service", "model-serving")
                .containsEntry("route", "/v1/models/{name}:predict")
                .containsEntry("httpMethod", "GET")
                .containsEntry("statusCode", "200")
                .containsEntry("outcome", "slow");
    }

    @Test
    void aFastServerErrorStillLogs() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(500);
        run(request("/v1/models/{name}:predict"), res, 1);
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getMDCPropertyMap()).containsEntry("outcome", "failed");
    }

    @Test
    void aFastClientErrorLogsNothing() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(404);
        run(request("/v1/models/{name}:predict"), res, 1);
        assertThat(appender.list).isEmpty();
    }

    /**
     * The route field must be the matched pattern, never the raw URI. A path carrying an id
     * would make every request its own distinct route value in Splunk.
     */
    @Test
    void theRouteIsTheMatchedPatternNotTheRawUri() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(200);
        run(request("/v1/models/{name}:predict"), res, 250);
        assertThat(appender.list.get(0).getMDCPropertyMap().get("route"))
                .isEqualTo("/v1/models/{name}:predict");
    }

    @Test
    void mdcIsClearedAfterTheEvent() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(200);
        run(request("/v1/models/{name}:predict"), res, 250);
        assertThat(org.slf4j.MDC.getCopyOfContextMap()).satisfiesAnyOf(
                map -> assertThat(map).isNull(),
                map -> assertThat(map).doesNotContainKeys("route", "durationMs"));
    }
}
