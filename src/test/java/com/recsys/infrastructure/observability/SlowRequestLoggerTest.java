package com.recsys.infrastructure.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SlowRequestLoggerTest {

    /**
     * Populated by the /probe service body below, which -- unlike an assertion made from the
     * JUnit thread -- actually runs on the server's worker thread. A single-threaded
     * workerGroup (below) pins every request, including the /slow completion callback that
     * sets and clears MDC, to that same thread, so a value captured here is what a leaked MDC
     * entry would really look like to the next request that thread serves.
     */
    private static final AtomicReference<Map<String, String>> probeMdc = new AtomicReference<>();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            // One worker thread, deliberately: it is the only way to guarantee that /probe
            // below observes the exact thread the /slow completion callback ran on, rather
            // than a different member of the pool that never saw the leak either way.
            sb.workerGroup(1);
            sb.decorator(SlowRequestLogger.newDecorator("test-service", 100));
            sb.service("/fast", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
            sb.service("/slow", (ctx, req) -> {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return HttpResponse.of(HttpStatus.OK);
            });
            sb.service("/boom", (ctx, req) -> HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR));
            sb.service("/badrequest", (ctx, req) -> HttpResponse.of(HttpStatus.BAD_REQUEST));
            sb.service("/probe", (ctx, req) -> {
                probeMdc.set(MDC.getCopyOfContextMap());
                return HttpResponse.of(HttpStatus.OK);
            });
        }
    };

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger(SlowRequestLogger.class);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private void get(String path) {
        WebClient.of(server.httpUri()).blocking().get(path);
    }

    @Test
    void aFastSuccessfulRequestLogsNothing() {
        get("/fast");
        // The event would be emitted from the request-log completion callback, so a bare
        // assertion could pass simply by racing it. Give it a real window to appear.
        await().pollDelay(300, TimeUnit.MILLISECONDS)
               .atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(appender.list).isEmpty());
    }

    @Test
    void aSlowRequestLogsOneWarnWithItsFields() {
        get("/slow");
        await().atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(appender.list).hasSize(1));

        ILoggingEvent e = appender.list.get(0);
        assertThat(e.getLevel()).isEqualTo(Level.WARN);
        assertThat(e.getMDCPropertyMap())
                .containsEntry("service", "test-service")
                .containsEntry("route", "/slow")
                .containsEntry("httpMethod", "GET")
                .containsEntry("statusCode", "200")
                .containsEntry("outcome", "slow")
                .containsKey("durationMs");
        assertThat(Long.parseLong(e.getMDCPropertyMap().get("durationMs")))
                .isGreaterThanOrEqualTo(250);
    }

    @Test
    void aFastServerErrorStillLogs() {
        get("/boom");
        await().atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(appender.list).hasSize(1));
        assertThat(appender.list.get(0).getMDCPropertyMap())
                .containsEntry("outcome", "failed")
                .containsEntry("statusCode", "500");
    }

    @Test
    void aFastClientErrorLogsNothing() {
        get("/badrequest");
        await().pollDelay(300, TimeUnit.MILLISECONDS)
               .atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(appender.list).isEmpty());
    }

    /**
     * Armeria runs the completion callback on a pooled event loop thread. A leaked MDC entry
     * would attach itself to unrelated later log lines from that thread.
     *
     * <p>Asserting on the JUnit thread's own MDC (as a first draft of this test did) proves
     * nothing: that thread is never touched by the decorator, since the decorator's completion
     * callback and the /probe service body below both run on the server's worker thread, not
     * the caller's. workerGroup(1) above pins every request to the same single worker thread,
     * so /probe -- which never sets any MDC entry of its own -- observes exactly what a leaked
     * entry from /slow's processing would look like to the next request on that thread.
     */
    @Test
    void mdcIsClearedAfterTheEvent() {
        probeMdc.set(null);

        get("/slow");
        await().atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(appender.list).hasSize(1));

        get("/probe");

        // Nothing from /slow's MDC.put calls survived on the worker thread that ran them.
        assertThat(probeMdc.get()).satisfiesAnyOf(
                map -> assertThat(map).isNull(),
                map -> assertThat(map).doesNotContainKeys("route", "durationMs"));
    }
}
