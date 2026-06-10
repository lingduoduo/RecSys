package com.recsys.streaming.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Async, bounded event publisher for the online serving → MQ path.
 *
 * Demonstrates three MQ patterns:
 *
 *   削峰 (peak shaving)  — bounded {@link ArrayBlockingQueue}; events are dropped
 *                          (not blocked) when the queue is full, so the serving
 *                          thread is never stalled by MQ back-pressure.
 *
 *   异步 (async)         — {@link #publish} returns in nanoseconds; the drain
 *                          thread batches events and writes to MQ independently.
 *
 *   解耦 (decoupling)    — the HTTP serving path has no dependency on Kafka
 *                          availability; {@link #sendBatch} is overridable so
 *                          the transport can be swapped (Kafka, Pulsar, log, …)
 *                          without touching the servlet code.
 */
public class AsyncEventPublisher implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AsyncEventPublisher.class);
    private static final int DEFAULT_QUEUE_CAPACITY = 10_000;
    private static final int DEFAULT_BATCH_SIZE = 100;

    private final ArrayBlockingQueue<String> queue;
    private final int batchSize;
    private final Thread drainThread;
    private volatile boolean running = true;
    private final AtomicLong publishedCount = new AtomicLong();
    private final AtomicLong droppedCount  = new AtomicLong();
    private final AtomicLong drainedCount  = new AtomicLong();

    public AsyncEventPublisher() {
        this(
                readIntEnv("ASYNC_EVENT_QUEUE_CAPACITY", DEFAULT_QUEUE_CAPACITY),
                readIntEnv("ASYNC_EVENT_BATCH_SIZE",     DEFAULT_BATCH_SIZE)
        );
    }

    AsyncEventPublisher(int queueCapacity, int batchSize) {
        this.queue      = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        this.batchSize  = Math.max(1, batchSize);
        this.drainThread = new Thread(this::drainLoop, "async-event-publisher");
        this.drainThread.setDaemon(true);
        this.drainThread.start();
    }

    /**
     * Non-blocking publish. Returns {@code true} if the event was queued;
     * {@code false} if the queue was full and the event was dropped (削峰).
     */
    public boolean publish(String event) {
        if (event == null || !running) return false;
        if (queue.offer(event)) {
            publishedCount.incrementAndGet();
            return true;
        }
        long dropped = droppedCount.incrementAndGet();
        log.warn("AsyncEventPublisher queue full — event dropped (total dropped: {})", dropped);
        return false;
    }

    public boolean publish(LogCollector.KafkaEvent event) {
        return event != null && publish(event.value());
    }

    public Snapshot snapshot() {
        return new Snapshot(queue.size(), publishedCount.get(), droppedCount.get(), drainedCount.get());
    }

    /** Flush remaining events and stop the drain thread. */
    @Override
    public void close() {
        running = false;
        drainThread.interrupt();
        List<String> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        if (!remaining.isEmpty()) {
            sendBatch(remaining);
        }
    }

    private void drainLoop() {
        List<String> batch = new ArrayList<>(batchSize);
        while (running || !queue.isEmpty()) {
            try {
                String first = queue.poll(50, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);
                sendBatch(batch);
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

    private static int readIntEnv(String envName, int defaultValue) {
        String raw = System.getenv(envName);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public record Snapshot(int queueSize, long published, long dropped, long drained) {}
}
