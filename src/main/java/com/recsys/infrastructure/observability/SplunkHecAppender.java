package com.recsys.infrastructure.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Logback appender that ships log events to Splunk's HTTP Event Collector.
 *
 * <p>Shape borrowed from {@code AsyncEventPublisher}: a bounded queue, one daemon drain
 * thread, batched writes, and <strong>drop-on-full rather than block</strong>. Logs are
 * diagnostics — a Splunk outage or a log burst must degrade to console-only, never stall a
 * serving thread or grow the heap.
 *
 * <p><strong>This class must never call slf4j.</strong> Any slf4j call from here routes back
 * into this appender and recurses. All diagnostics go through Logback's status API
 * ({@code addInfo}/{@code addWarn}/{@code addError}), which cannot re-enter.
 *
 * <p>Inert unless {@code SPLUNK_HEC_TOKEN} is set, which is why tests and local runs need no
 * opt-out flag.
 */
public final class SplunkHecAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private static final long WARN_THROTTLE_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final long FLUSH_WAIT_MILLIS = 2_000;

    private final SplunkHecConfig injectedConfig;
    private final SplunkHecClient injectedClient;

    private SplunkHecConfig config;
    private SplunkHecClient client;
    private SplunkHecEventSerializer serializer;
    private ArrayBlockingQueue<ILoggingEvent> queue;
    private Thread drainThread;
    private volatile boolean running;

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final Map<SplunkHecClient.Outcome, Long> lastWarnedAt =
            new EnumMap<>(SplunkHecClient.Outcome.class);

    /** Logback's Joran configurator needs a no-arg constructor. */
    public SplunkHecAppender() {
        this(null, null);
    }

    SplunkHecAppender(SplunkHecConfig config, SplunkHecClient client) {
        this.injectedConfig = config;
        this.injectedClient = client;
    }

    @Override
    public void start() {
        config = injectedConfig != null ? injectedConfig : SplunkHecConfig.fromEnvironment();

        if (!config.isEnabled()) {
            // Start anyway: AppenderBase warns on every event delivered to a stopped appender.
            if (config.isMisconfigured()) {
                addError("Splunk HEC appender disabled: " + config.disabledReason());
            } else {
                addInfo("Splunk HEC appender disabled: " + config.disabledReason());
            }
            super.start();
            return;
        }

        if (config.insecureTls()) {
            addWarn("SPLUNK_HEC_INSECURE_TLS=true — Splunk's TLS certificate will NOT be verified. "
                    + "Intended for local development against a self-signed certificate only.");
        }

        serializer = new SplunkHecEventSerializer(
                resolveHost(), config.serviceName(), config.sourcetype(), config.index());
        client = injectedClient != null ? injectedClient : new SplunkHecClient(config);
        queue = new ArrayBlockingQueue<>(config.queueCapacity());
        drainThread = new Thread(this::drainLoop, "splunk-hec-appender");
        drainThread.setDaemon(true);
        // drainThread must be assigned before running flips true: stop() reads running to
        // decide whether to touch drainThread, so a stop() landing between the two would
        // NPE on drainThread.interrupt() if the flag were set first. (The drain loop's own
        // `while (running)` is why the thread cannot simply be started before this point.)
        running = true;
        drainThread.start();

        addInfo("Splunk HEC appender shipping to " + config.uri()
                + " (index=" + config.index() + ", source=" + config.serviceName() + ")");
        super.start();
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!running || event == null) return;
        // Resolve the lazily-computed thread name (and other deferred state) HERE, on the
        // caller's thread. Reading it first from the drain thread mislabels every event.
        event.prepareForDeferredProcessing();
        if (!queue.offer(event)) {
            dropped.incrementAndGet();
        }
    }

    @Override
    public void stop() {
        if (!running) {
            super.stop();
            return;
        }
        running = false;
        drainThread.interrupt();
        if (Thread.currentThread() != drainThread) {
            try {
                drainThread.join(FLUSH_WAIT_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        List<ILoggingEvent> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        // Chunk like the drain loop does: one unbounded ship() here could hand Splunk a
        // single POST with the full queue depth (up to SPLUNK_HEC_QUEUE_CAPACITY events),
        // tripping HEC's max_content_length and losing the *entire* flush to a 413 — the one
        // case at-most-once was supposed to be mitigated.
        for (int start = 0; start < remaining.size(); start += config.batchSize()) {
            int end = Math.min(start + config.batchSize(), remaining.size());
            ship(remaining.subList(start, end));
        }
        addInfo("Splunk HEC appender stopped; " + snapshot());
        super.stop();
    }

    public Snapshot snapshot() {
        return new Snapshot(queue == null ? 0 : queue.size(),
                sent.get(), dropped.get(), failed.get());
    }

    private void drainLoop() {
        List<ILoggingEvent> batch = new ArrayList<>(config.batchSize());
        long lingerMillis = config.linger().toMillis();
        while (running) {
            try {
                ILoggingEvent first = queue.poll(lingerMillis, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.add(first);
                queue.drainTo(batch, config.batchSize() - 1);
                ship(batch);
                batch.clear();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                // A transport or serialization defect — including an OutOfMemoryError or
                // StackOverflowError, most likely to fire during the very burst this appender
                // is supposed to survive — must not kill this thread. A permanently dead
                // shipper is strictly worse than absorbing the failure and continuing.
                batch.clear();
                try {
                    warnThrottled(SplunkHecClient.Outcome.TRANSPORT_FAILURE,
                            "Splunk HEC drain iteration failed: " + t);
                } catch (Throwable reportingFailure) {
                    // The reporting path itself must never be able to kill this thread.
                }
            }
        }
    }

    private void ship(List<ILoggingEvent> batch) {
        List<String> serialized = new ArrayList<>(batch.size());
        for (ILoggingEvent event : batch) {
            try {
                serialized.add(serializer.toJson(event));
            } catch (Exception e) {
                failed.incrementAndGet();
                warnThrottled(SplunkHecClient.Outcome.TRANSPORT_FAILURE,
                        "Splunk HEC could not serialize a log event: " + e);
            }
        }
        if (serialized.isEmpty()) return;

        SplunkHecClient.Outcome outcome =
                client.send(SplunkHecEventSerializer.toBatchBody(serialized));
        if (outcome == SplunkHecClient.Outcome.SUCCESS) {
            sent.addAndGet(serialized.size());
            return;
        }
        failed.addAndGet(serialized.size());
        warnThrottled(outcome, "Splunk HEC delivery failed (" + outcome + "); dropped "
                + serialized.size() + " events. Total failed: " + failed.get());
    }

    /**
     * One message per failure kind per minute. An unthrottled warning per failed batch would
     * itself become a log flood during an outage — the thing this appender exists to avoid.
     */
    private void warnThrottled(SplunkHecClient.Outcome kind, String message) {
        long now = System.nanoTime();
        synchronized (lastWarnedAt) {
            Long previous = lastWarnedAt.get(kind);
            if (previous != null && now - previous < WARN_THROTTLE_NANOS) return;
            lastWarnedAt.put(kind, now);
        }
        if (kind == SplunkHecClient.Outcome.AUTH_REJECTED) {
            addError(message + " Check SPLUNK_HEC_TOKEN.");
        } else {
            addWarn(message);
        }
    }

    private static String resolveHost() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            // Fails on some Docker network configurations. Never block startup over it.
            return "unknown";
        }
    }

    public record Snapshot(int queued, long sent, long dropped, long failed) {}
}
