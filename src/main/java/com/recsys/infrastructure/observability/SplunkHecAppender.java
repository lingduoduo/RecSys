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
    /** Grace after an escalated interrupt, so shutdown stays bounded at ~2.5s worst case. */
    private static final long INTERRUPT_WAIT_MILLIS = 500;

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
    /** Sent but never acknowledged — see {@link SplunkHecClient.Outcome#INDETERMINATE}. */
    private final AtomicLong indeterminate = new AtomicLong();
    /** Batches currently inside {@code client.send()}; read at shutdown for the summary. */
    private final java.util.concurrent.atomic.AtomicInteger inFlightBatches =
            new java.util.concurrent.atomic.AtomicInteger();
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

        // Shutdown is the one moment where an operator most needs to know what happened to the
        // tail of the log stream, and it is exactly when the least is normally reported. These
        // are captured before anything drains so the numbers describe the shutdown itself.
        long startedAtNanos = System.nanoTime();
        int queuedAtShutdown = queue.size();
        int inFlightAtShutdown = inFlightBatches.get();
        boolean forcedInterrupt = false;

        // Do NOT interrupt first. Clearing `running` is already enough for the drain loop to
        // exit — it re-checks the flag after each poll, so it stops within one linger interval.
        // An immediate interrupt instead aborts whatever the thread is doing, and if that is an
        // in-flight POST the batch cannot be confirmed even though Splunk may well have accepted
        // it. A real collector takes long enough for that window to be hit routinely; a stub
        // answers instantly, which is why only the real-Splunk integration test caught it.
        if (Thread.currentThread() != drainThread) {
            joinQuietly(FLUSH_WAIT_MILLIS);
            if (drainThread.isAlive()) {
                // It overran the budget — most likely blocked on a send with a longer timeout
                // than we are willing to wait. Now escalate.
                forcedInterrupt = true;
                drainThread.interrupt();
                joinQuietly(INTERRUPT_WAIT_MILLIS);
            }
        }
        long drainMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);

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

        Snapshot finalSnapshot = snapshot();
        String summary = "Splunk HEC appender stopped."
                + " queuedAtShutdown=" + queuedAtShutdown
                + " inFlightBatchesAtShutdown=" + inFlightAtShutdown
                + " flushedAtShutdown=" + remaining.size()
                + " gracefulDrainMillis=" + drainMillis
                + " forcedInterrupt=" + forcedInterrupt
                + " confirmed=" + finalSnapshot.sent()
                + " failed=" + finalSnapshot.failed()
                + " indeterminate=" + finalSnapshot.indeterminate()
                + " droppedQueueFull=" + finalSnapshot.dropped();
        // A forced interrupt means we gave up on an in-flight batch, so the tail of the log
        // stream is genuinely uncertain. That is a warning, not an FYI.
        if (forcedInterrupt || finalSnapshot.indeterminate() > 0) {
            addWarn(summary + ". Events counted indeterminate were sent but never acknowledged —"
                    + " Splunk may or may not have indexed them.");
        } else {
            addInfo(summary);
        }
        super.stop();
    }

    public Snapshot snapshot() {
        return new Snapshot(queue == null ? 0 : queue.size(),
                sent.get(), dropped.get(), failed.get(), indeterminate.get());
    }

    private void joinQuietly(long millis) {
        try {
            drainThread.join(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
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

        SplunkHecClient.Outcome outcome;
        inFlightBatches.incrementAndGet();
        try {
            outcome = client.send(SplunkHecEventSerializer.toBatchBody(serialized));
        } finally {
            inFlightBatches.decrementAndGet();
        }

        if (outcome == SplunkHecClient.Outcome.SUCCESS) {
            sent.addAndGet(serialized.size());
            return;
        }

        // Include Splunk's own explanation — "Incorrect index", "Server is busy", "Invalid
        // token". Without it the operator sees only the Outcome enum and cannot tell a
        // misconfiguration from back-pressure.
        String detail = client.lastFailureDetail();

        if (outcome == SplunkHecClient.Outcome.INDETERMINATE) {
            // The request went out and no answer came back. Counting these as `failed` would
            // assert a loss we cannot substantiate, and would hide that these are the events
            // a retry — here or upstream — could duplicate.
            indeterminate.addAndGet(serialized.size());
            warnThrottled(outcome, "Splunk HEC delivery UNKNOWN for " + serialized.size()
                    + " events: the batch was sent but never acknowledged, so Splunk may or may"
                    + " not have indexed it. Total indeterminate: " + indeterminate.get()
                    + (detail == null ? "" : ". Cause: " + detail));
            return;
        }

        failed.addAndGet(serialized.size());
        warnThrottled(outcome, "Splunk HEC delivery failed (" + outcome + "); dropped "
                + serialized.size() + " events. Total failed: " + failed.get()
                + (detail == null ? "" : ". Splunk said: " + detail));
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

    /**
     * @param queued        events still waiting in the bounded queue
     * @param sent          confirmed by a 2xx from Splunk
     * @param dropped       never sent: the queue was full when {@code append()} ran
     * @param failed        sent and definitively refused, or never left the host
     * @param indeterminate sent but never acknowledged — Splunk may or may not hold them.
     *                      Kept separate from {@code failed} because the risks differ: failed
     *                      events are lost, indeterminate ones are lost <em>or</em> duplicated.
     */
    public record Snapshot(int queued, long sent, long dropped, long failed, long indeterminate) {}
}
