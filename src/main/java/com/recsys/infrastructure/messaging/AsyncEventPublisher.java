package com.recsys.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import com.recsys.metrics.ConsistencyMetrics;
import com.recsys.metrics.QueueMetrics;

/**
 * Async, bounded event publisher for the online serving → MQ path.
 *
 * Demonstrates three MQ patterns:
 *
 *   Peak shaving  — bounded {@link ArrayBlockingQueue}; events are dropped
 *                   (not blocked) when the queue is full, so the serving
 *                   thread is never stalled by MQ back-pressure.
 *
 *   Async         — {@link #publish} returns in nanoseconds; the drain
 *                   thread batches events and writes to MQ independently.
 *
 *   Decoupling    — the HTTP serving path has no dependency on Kafka
 *                   availability; {@link #sendBatch} is overridable so
 *                   the transport can be swapped (Kafka, Pulsar, log, …)
 *                   without touching the HTTP service code.
 */
public class AsyncEventPublisher implements AutoCloseable, QueueMetrics.Source {
    private static final Logger log = LoggerFactory.getLogger(AsyncEventPublisher.class);
    private static final int DEFAULT_QUEUE_CAPACITY = 10_000;
    private static final int DEFAULT_BATCH_SIZE = 100;

    private final ArrayBlockingQueue<EventEnvelope> queue;
    private final int batchSize;
    private final Thread drainThread;
    private volatile boolean running = true;
    private final AtomicLong publishedCount = new AtomicLong();
    private final AtomicLong droppedCount  = new AtomicLong();
    private final AtomicLong drainedCount  = new AtomicLong();
    private final AtomicLong deliveryFailureCount = new AtomicLong();
    private volatile ConsistencyMetrics consistencyMetrics;
    private final ConsistencyMetrics.EventType metricEventType;

    /**
     * The effective bound, captured here rather than read back from the queue: the queue's own
     * size accessors don't expose the capacity it was constructed with, and this value is
     * immutable for the object's life anyway.
     */
    private final int queueCapacity;
    private final AtomicLong fullRejectedCount = new AtomicLong();
    private final AtomicLong shutdownRejectedCount = new AtomicLong();
    private final AtomicLong invalidKeyRejectedCount = new AtomicLong();

    public AsyncEventPublisher() {
        this(
                readIntEnv("ASYNC_EVENT_QUEUE_CAPACITY", DEFAULT_QUEUE_CAPACITY),
                readIntEnv("ASYNC_EVENT_BATCH_SIZE",     DEFAULT_BATCH_SIZE), null
        );
    }

    AsyncEventPublisher(int queueCapacity, int batchSize) {
        this(queueCapacity, batchSize, null);
    }

    public AsyncEventPublisher(int queueCapacity, int batchSize, ConsistencyMetrics consistencyMetrics) {
        this(queueCapacity, batchSize, consistencyMetrics, ConsistencyMetrics.EventType.ONLINE_INTERACTION);
    }

    public AsyncEventPublisher(int queueCapacity, int batchSize, ConsistencyMetrics consistencyMetrics,
                               ConsistencyMetrics.EventType metricEventType) {
        int effectiveCapacity = Math.max(1, queueCapacity);
        if (effectiveCapacity != queueCapacity) {
            log.warn("AsyncEventPublisher requested queue capacity {} but the effective bound is {};"
                    + " metrics report the effective value.", queueCapacity, effectiveCapacity);
        }
        this.queueCapacity = effectiveCapacity;
        this.queue      = new ArrayBlockingQueue<>(effectiveCapacity);
        this.batchSize  = Math.max(1, batchSize);
        this.consistencyMetrics = consistencyMetrics;
        this.metricEventType = Objects.requireNonNull(metricEventType, "metricEventType");
        this.drainThread = new Thread(this::drainLoop, "async-event-publisher");
        this.drainThread.setDaemon(true);
        this.drainThread.start();
    }

    /**
     * Non-blocking publish. Returns {@code true} if the event was queued;
     * {@code false} if the queue was full and the event was dropped (peak shaving).
     */
    public boolean publish(String event) {
        return publish(null, event);
    }

    public boolean publish(String key, String event) {
        if (event == null) return false;
        if (!running) return recordRejectedEvent(QueueMetrics.RejectionReason.SHUTDOWN);
        if (queue.offer(new EventEnvelope(key, event))) {
            publishedCount.incrementAndGet();
            return true;
        }
        return recordRejectedEvent(QueueMetrics.RejectionReason.FULL);
    }

    public boolean publish(LogCollector.KafkaEvent event) {
        return event != null && publish(event.value());
    }

    public Snapshot snapshot() {
        return new Snapshot(queue.size(), queueCapacity, publishedCount.get(), droppedCount.get(),
                drainedCount.get(), deliveryFailureCount.get());
    }

    public void bindConsistencyMetrics(ConsistencyMetrics metrics) {
        this.consistencyMetrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** Flush remaining events and stop the drain thread. */
    @Override
    public void close() {
        running = false;
        drainThread.interrupt();
        if (Thread.currentThread() != drainThread) {
            try {
                drainThread.join(TimeUnit.SECONDS.toMillis(2));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        List<EventEnvelope> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        if (!remaining.isEmpty()) {
            sendEnvelopes(remaining);
        }
    }

    private void drainLoop() {
        List<EventEnvelope> batch = new ArrayList<>(batchSize);
        while (running || !queue.isEmpty()) {
            try {
                EventEnvelope first = queue.poll(50, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);
                sendEnvelopes(batch);
                batch.clear();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Write a batch of serialized event JSON strings to MQ.
     * Default implementation logs at DEBUG; override or subclass to send to Kafka.
     */
    protected void sendBatch(List<String> events) {
        drainedCount.addAndGet(events.size());
        log.debug("AsyncEventPublisher draining {} events to MQ", events.size());
    }

    protected void sendEnvelopes(List<EventEnvelope> events) {
        sendBatch(events.stream().map(EventEnvelope::value).toList());
    }

    /**
     * Retained so subclasses compiled against the old shape keep working; FULL is the historical
     * meaning of a bare rejection here.
     */
    protected boolean recordRejectedEvent() {
        return recordRejectedEvent(QueueMetrics.RejectionReason.FULL);
    }

    protected boolean recordRejectedEvent(QueueMetrics.RejectionReason reason) {
        long dropped = droppedCount.incrementAndGet();
        switch (reason) {
            case FULL -> fullRejectedCount.incrementAndGet();
            case SHUTDOWN -> shutdownRejectedCount.incrementAndGet();
            case INVALID_KEY -> invalidKeyRejectedCount.incrementAndGet();
        }
        if (consistencyMetrics != null) consistencyMetrics.recordAsyncDrop(metricEventType);
        log.warn("AsyncEventPublisher event rejected, reason={} (total dropped: {})",
                reason.tag(), dropped);
        return false;
    }

    protected void recordDeliveryFailure() {
        deliveryFailureCount.incrementAndGet();
    }

    @Override
    public int depth() {
        return queue.size();
    }

    @Override
    public int capacity() {
        return queueCapacity;
    }

    @Override
    public long rejected(QueueMetrics.RejectionReason reason) {
        // Each reason is its own monotonic AtomicLong, incremented alongside droppedCount in
        // recordRejectedEvent(...). Deliberately not derived by subtracting from droppedCount:
        // that arithmetic reads three independent AtomicLongs as an untorn triple, which they
        // are not, so a concurrent reader could observe a transiently negative FULL -- and a
        // Prometheus FunctionCounter interprets any decrease as a counter reset, producing a
        // spurious increase() spike on the very next scrape. A dedicated counter per reason is
        // monotonic by construction; there is nothing to race.
        return switch (reason) {
            case FULL -> fullRejectedCount.get();
            case SHUTDOWN -> shutdownRejectedCount.get();
            case INVALID_KEY -> invalidKeyRejectedCount.get();
        };
    }

    private static int readIntEnv(String envName, int defaultValue) {
        String raw = System.getenv(envName);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public record Snapshot(int queueSize, int queueCapacity, long published, long dropped,
                           long drained, long deliveryFailures) {
        public Snapshot(int queueSize, long published, long dropped, long drained) {
            this(queueSize, 1, published, dropped, drained, 0L);
        }
    }

    public record EventEnvelope(String key, String value) {
        public EventEnvelope {
            Objects.requireNonNull(value, "value");
        }
    }
}
