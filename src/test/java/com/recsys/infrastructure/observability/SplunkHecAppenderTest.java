package com.recsys.infrastructure.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.status.StatusManager;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

class SplunkHecAppenderTest {

    static final ConcurrentLinkedQueue<String> bodies = new ConcurrentLinkedQueue<>();
    static final ConcurrentLinkedQueue<String> authHeaders = new ConcurrentLinkedQueue<>();

    /**
     * Every appender built by this test class must get a real {@link LoggerContext} before
     * {@code start()}. Without one, {@code addInfo}/{@code addWarn}/{@code addError} fall
     * through to Logback's null-context fallback, which prints a
     * {@code LOGBACK: No context given for ...} line per call and — more importantly — makes
     * the whole status-reporting path (including {@code warnThrottled}'s throttling and the
     * {@code AUTH_REJECTED} branch) untestable, since there is no {@link StatusManager} to
     * assert against.
     */
    private static SplunkHecAppender newAppender(SplunkHecConfig config, SplunkHecClient client) {
        SplunkHecAppender appender = new SplunkHecAppender(config, client);
        appender.setContext(newLoggerContext());
        return appender;
    }

    private static LoggerContext newLoggerContext() {
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        return context;
    }

    @RegisterExtension
    static final ServerExtension collector = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/services/collector/event", (ctx, req) ->
                    HttpResponse.of(req.aggregate().thenApply(agg -> {
                        authHeaders.add(agg.headers().get("authorization"));
                        bodies.add(agg.contentUtf8());
                        return HttpResponse.of(HttpStatus.OK, MediaType.JSON, "{\"code\":0}");
                    })));
        }
    };

    private static LoggingEvent event(String message) {
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        LoggingEvent event = new LoggingEvent();
        event.setLoggerContext(context);
        event.setLoggerName("com.recsys.Test");
        event.setLevel(Level.INFO);
        event.setMessage(message);
        event.setTimeStamp(1_753_970_000_123L);
        return event;
    }

    /** Records outcomes without any network. */
    static final class FakeClient extends SplunkHecClient {
        final AtomicInteger calls = new AtomicInteger();
        final ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();
        private final Outcome outcome;
        private final CountDownLatch gate;

        FakeClient(Outcome outcome, CountDownLatch gate) {
            super(SplunkHecConfig.from(Map.of(
                    "SPLUNK_HEC_TOKEN", "tok",
                    "SPLUNK_HEC_URL", "http://127.0.0.1:1/services/collector/event")));
            this.outcome = outcome;
            this.gate = gate;
        }

        AtomicInteger calls() { return calls; }

        ConcurrentLinkedQueue<String> seen() { return seen; }

        @Override
        Outcome send(String body) {
            if (gate != null) {
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            calls.incrementAndGet();
            seen.add(body);
            return outcome;
        }
    }

    private static SplunkHecConfig enabledConfig(Map<String, String> extra) {
        java.util.Map<String, String> env = new java.util.HashMap<>(Map.of(
                "SPLUNK_HEC_TOKEN", "tok",
                "SPLUNK_HEC_URL", "http://127.0.0.1:" + collector.httpPort() + "/services/collector/event",
                "SPLUNK_HEC_LINGER_MS", "50"));
        env.putAll(extra);
        return SplunkHecConfig.from(env);
    }

    @Test
    void shipsEventsToCollectorWithAuthHeader() {
        bodies.clear();
        authHeaders.clear();
        SplunkHecConfig config = enabledConfig(Map.of("SPLUNK_SERVICE_NAME", "api-gateway"));
        SplunkHecAppender appender = newAppender(config, new SplunkHecClient(config));
        appender.start();
        try {
            appender.doAppend(event("hello splunk"));

            await().atMost(5, TimeUnit.SECONDS).until(() -> !bodies.isEmpty());
            assertThat(bodies.peek()).contains("\"message\":\"hello splunk\"")
                    .contains("\"source\":\"api-gateway\"")
                    .contains("\"sourcetype\":\"recsys:app:log\"");
            assertThat(authHeaders.peek()).isEqualTo("Splunk tok");
        } finally {
            appender.stop();
        }
    }

    @Test
    void batchesMultipleEventsIntoOneRequest() {
        SplunkHecConfig config = enabledConfig(Map.of("SPLUNK_HEC_BATCH_SIZE", "50"));
        CountDownLatch gate = new CountDownLatch(1);
        FakeClient client = new FakeClient(SplunkHecClient.Outcome.SUCCESS, gate);
        SplunkHecAppender appender = newAppender(config, client);
        appender.start();
        try {
            for (int i = 0; i < 10; i++) {
                appender.doAppend(event("event-" + i));
            }
            gate.countDown(); // let the drain thread proceed now that all 10 are queued

            await().atMost(5, TimeUnit.SECONDS).until(() -> appender.snapshot().sent() == 10);
            // 10 events, far fewer than 10 requests
            assertThat(client.calls().get()).isLessThan(10);
            assertThat(String.join("\n", client.seen())).contains("event-0").contains("event-9");
        } finally {
            appender.stop();
        }
    }

    @Test
    void disabledConfigSendsNothing() {
        SplunkHecConfig disabled = SplunkHecConfig.from(Map.of());
        FakeClient client = new FakeClient(SplunkHecClient.Outcome.SUCCESS, null);
        SplunkHecAppender appender = newAppender(disabled, client);
        appender.start();
        try {
            assertThat(appender.isStarted()).isTrue(); // started but inert
            for (int i = 0; i < 20; i++) {
                appender.doAppend(event("ignored-" + i));
            }
            assertThat(client.calls().get()).isZero();
            assertThat(appender.snapshot().sent()).isZero();
            assertThat(appender.snapshot().dropped()).isZero();
        } finally {
            appender.stop();
        }
    }

    @Test
    void dropsWhenQueueIsFullInsteadOfBlocking() {
        SplunkHecConfig config = enabledConfig(Map.of("SPLUNK_HEC_QUEUE_CAPACITY", "2"));
        CountDownLatch gate = new CountDownLatch(1);
        FakeClient client = new FakeClient(SplunkHecClient.Outcome.SUCCESS, gate);
        SplunkHecAppender appender = newAppender(config, client);
        appender.start();
        try {
            long startedAt = System.nanoTime();
            for (int i = 0; i < 500; i++) {
                appender.doAppend(event("burst-" + i));
            }
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(elapsedMs).as("append must never block on a full queue").isLessThan(2_000);
            assertThat(appender.snapshot().dropped()).isPositive();
        } finally {
            gate.countDown();
            appender.stop();
        }
    }

    @Test
    void failingCollectorNeverThrowsIntoAppend() {
        SplunkHecConfig config = enabledConfig(Map.of());
        FakeClient client = new FakeClient(SplunkHecClient.Outcome.SERVER_ERROR, null);
        SplunkHecAppender appender = newAppender(config, client);
        appender.start();
        try {
            assertThatCode(() -> {
                for (int i = 0; i < 50; i++) {
                    appender.doAppend(event("doomed-" + i));
                }
            }).doesNotThrowAnyException();

            await().atMost(5, TimeUnit.SECONDS).until(() -> appender.snapshot().failed() > 0);
            assertThat(appender.snapshot().sent()).isZero();
        } finally {
            appender.stop();
        }
    }

    @Test
    void warnThrottleEmitsOnceNotPerFailedBatch() {
        SplunkHecConfig config = enabledConfig(Map.of("SPLUNK_HEC_BATCH_SIZE", "1"));
        FakeClient client = new FakeClient(SplunkHecClient.Outcome.SERVER_ERROR, null);
        SplunkHecAppender appender = newAppender(config, client);
        StatusManager statusManager = appender.getContext().getStatusManager();
        appender.start();
        try {
            for (int i = 0; i < 20; i++) {
                appender.doAppend(event("failing-" + i));
            }

            // Batch size 1 means each event ships (and fails) on its own, so the drain loop
            // calls warnThrottled 20 times in quick succession.
            await().atMost(5, TimeUnit.SECONDS).until(() -> client.calls().get() >= 20);

            long deliveryFailedWarnings = statusManager.getCopyOfStatusList().stream()
                    .filter(status -> status.getMessage() != null
                            && status.getMessage().contains("Splunk HEC delivery failed"))
                    .count();

            // The 60s per-outcome-kind throttle means only the first of those 20 failures
            // should actually have reached the StatusManager as a logged warning.
            assertThat(deliveryFailedWarnings).isEqualTo(1);
        } finally {
            appender.stop();
        }
    }

    @Test
    void authRejectedIsReportedAsAnError() {
        SplunkHecConfig config = enabledConfig(Map.of());
        FakeClient client = new FakeClient(SplunkHecClient.Outcome.AUTH_REJECTED, null);
        SplunkHecAppender appender = newAppender(config, client);
        StatusManager statusManager = appender.getContext().getStatusManager();
        appender.start();
        try {
            appender.doAppend(event("unauthorized"));

            await().atMost(5, TimeUnit.SECONDS).until(() -> appender.snapshot().failed() > 0);

            boolean sawAuthError = statusManager.getCopyOfStatusList().stream()
                    .anyMatch(status -> status.getLevel() == ch.qos.logback.core.status.Status.ERROR
                            && status.getMessage() != null
                            && status.getMessage().contains("Check SPLUNK_HEC_TOKEN"));
            assertThat(sawAuthError).isTrue();
        } finally {
            appender.stop();
        }
    }

    @Test
    void drainThreadSurvivesAClientThatThrows() {
        SplunkHecConfig config = enabledConfig(Map.of());
        AtomicInteger calls = new AtomicInteger();
        SplunkHecClient exploding = new SplunkHecClient(config) {
            @Override
            Outcome send(String body) {
                if (calls.incrementAndGet() == 1) throw new IllegalStateException("boom");
                return Outcome.SUCCESS;
            }
        };
        SplunkHecAppender appender = newAppender(config, exploding);
        appender.start();
        try {
            appender.doAppend(event("first"));
            await().atMost(5, TimeUnit.SECONDS).until(() -> calls.get() >= 1);

            appender.doAppend(event("second"));
            // The drain thread must still be alive to ship the second event.
            await().atMost(5, TimeUnit.SECONDS).until(() -> appender.snapshot().sent() > 0);
        } finally {
            appender.stop();
        }
    }

    @Test
    void drainThreadSurvivesAClientThatThrowsAnError() {
        SplunkHecConfig config = enabledConfig(Map.of());
        AtomicInteger calls = new AtomicInteger();
        SplunkHecClient exploding = new SplunkHecClient(config) {
            @Override
            Outcome send(String body) {
                if (calls.incrementAndGet() == 1) throw new StackOverflowError("boom");
                return Outcome.SUCCESS;
            }
        };
        SplunkHecAppender appender = newAppender(config, exploding);
        appender.start();
        try {
            appender.doAppend(event("first"));
            await().atMost(5, TimeUnit.SECONDS).until(() -> calls.get() >= 1);

            appender.doAppend(event("second"));
            // The drain thread must survive an Error (not just a RuntimeException) to ship
            // the second event.
            await().atMost(5, TimeUnit.SECONDS).until(() -> appender.snapshot().sent() > 0);
        } finally {
            appender.stop();
        }
    }

    @Test
    void stopFlushesBufferedEvents() {
        SplunkHecConfig config = enabledConfig(Map.of("SPLUNK_HEC_LINGER_MS", "3000"));
        CountDownLatch gate = new CountDownLatch(1);
        FakeClient client = new FakeClient(SplunkHecClient.Outcome.SUCCESS, gate);
        SplunkHecAppender appender = newAppender(config, client);
        appender.start();

        for (int i = 0; i < 5; i++) {
            appender.doAppend(event("pending-" + i));
        }
        gate.countDown();
        appender.stop();

        assertThat(String.join("\n", client.seen())).contains("pending-0").contains("pending-4");
    }

    @Test
    void stopChunksALargeShutdownFlushIntoMultipleSends() {
        // A large queue-capacity default with a small batch size, so 23 leftover events is
        // several times over one batch's worth.
        SplunkHecConfig config = enabledConfig(Map.of(
                "SPLUNK_HEC_BATCH_SIZE", "5",
                "SPLUNK_HEC_LINGER_MS", "3000"));
        CountDownLatch enteredFirstSend = new CountDownLatch(1);
        CountDownLatch releaseFirstSend = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.CopyOnWriteArrayList<Integer> bodySizes =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        SplunkHecClient blockingOnFirstCall = new SplunkHecClient(config) {
            @Override
            Outcome send(String body) {
                bodySizes.add(body.split("\n").length);
                if (calls.incrementAndGet() == 1) {
                    enteredFirstSend.countDown();
                    try {
                        // Never counted down directly: stop()'s drainThread.interrupt() is
                        // what breaks out of this, exercising the real shutdown path rather
                        // than a manual release racing against it.
                        releaseFirstSend.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return Outcome.SUCCESS;
            }
        };
        SplunkHecAppender appender = newAppender(config, blockingOnFirstCall);
        appender.start();

        // These 5 form the drain thread's normal in-flight batch (batch size 5), which
        // blocks inside send() until stop() interrupts it below.
        for (int i = 0; i < 5; i++) {
            appender.doAppend(event("inflight-" + i));
        }
        await().atMost(5, TimeUnit.SECONDS).until(() -> enteredFirstSend.getCount() == 0);

        // The drain thread is stuck shipping the first batch, so these 23 land in the
        // queue and are still there, un-shipped, when stop() runs.
        for (int i = 0; i < 23; i++) {
            appender.doAppend(event("queued-" + i));
        }

        appender.stop();

        // 1 in-flight batch (5 events) plus a chunked shutdown flush of the remaining 23
        // (batch size 5) must be more than one additional send() call, and no single call
        // may exceed the configured batch size — the bug this test guards against is one
        // unbounded ship() of the whole remainder in a single oversized POST.
        assertThat(calls.get()).isGreaterThan(2);
        assertThat(bodySizes).allMatch(size -> size <= 5);
        assertThat(bodySizes.stream().mapToInt(Integer::intValue).sum()).isEqualTo(28);
    }

    @Test
    void capturesCallerThreadNameNotDrainThreadName() {
        SplunkHecConfig config = enabledConfig(Map.of());
        FakeClient client = new FakeClient(SplunkHecClient.Outcome.SUCCESS, null);
        SplunkHecAppender appender = newAppender(config, client);
        appender.start();
        try {
            Thread caller = new Thread(() -> appender.doAppend(event("from-caller")), "test-caller-thread");
            caller.start();
            caller.join();

            await().atMost(5, TimeUnit.SECONDS).until(() -> !client.seen().isEmpty());
            assertThat(client.seen().peek()).contains("\"thread\":\"test-caller-thread\"");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            appender.stop();
        }
    }
}
