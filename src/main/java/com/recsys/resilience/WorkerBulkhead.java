// src/main/java/com/recsys/resilience/WorkerBulkhead.java
package com.recsys.resilience;

import com.recsys.metrics.QueueMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class WorkerBulkhead implements QueueMetrics.Source {

    private static final Logger log = LoggerFactory.getLogger(WorkerBulkhead.class);

    private final AtomicLong threadCounter = new AtomicLong();

    private final String name;
    private final ThreadPoolExecutor executor;
    private final AtomicLong rejectedCount = new AtomicLong();

    /**
     * The effective bound, captured here rather than read back from the executor's queue:
     * ArrayBlockingQueue.remainingCapacity() + size() is racy under concurrent access, and
     * this value is immutable for the object's life anyway.
     */
    private final int queueCapacity;

    private final AtomicLong shutdownRejectedCount = new AtomicLong();

    public WorkerBulkhead(String name, int poolSize, int queueCapacity) {
        if (poolSize < 1) throw new IllegalArgumentException("poolSize must be >= 1, got: " + poolSize);
        this.name = name;

        int effectiveCapacity = Math.max(1, queueCapacity);
        if (effectiveCapacity != queueCapacity) {
            log.warn("WorkerBulkhead '{}' requested queue capacity {} but the effective bound is {};"
                            + " metrics report the effective value.",
                    name, queueCapacity, effectiveCapacity);
        }
        this.queueCapacity = effectiveCapacity;

        this.executor = new ThreadPoolExecutor(
                poolSize, poolSize,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(effectiveCapacity),
                r -> {
                    Thread t = new Thread(r, name + "-worker-" + threadCounter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
                // No custom RejectedExecutionHandler — let it throw RejectedExecutionException
        );
    }

    public <T> CompletableFuture<T> submit(Callable<T> task) {
        Objects.requireNonNull(task, "task must not be null");
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(task.call());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            // ThreadPoolExecutor throws this for a full queue OR a shut-down executor. Counting
            // both as saturation would fire the queue alert on every rolling deploy.
            if (executor.isShutdown()) {
                shutdownRejectedCount.incrementAndGet();
            } else {
                rejectedCount.incrementAndGet();
            }
            future.completeExceptionally(e);
        }
        return future;
    }

    public ExecutorService asExecutorService() {
        return Executors.unconfigurableExecutorService(executor);
    }

    public void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public int depth() {
        return executor.getQueue().size();
    }

    @Override
    public int capacity() {
        return queueCapacity;
    }

    @Override
    public long rejected(QueueMetrics.RejectionReason reason) {
        return switch (reason) {
            case FULL -> rejectedCount.get();
            case SHUTDOWN -> shutdownRejectedCount.get();
            case INVALID_KEY -> 0L;   // a bulkhead has no keys to be invalid
        };
    }

    public Snapshot snapshot() {
        return new Snapshot(name, executor.getActiveCount(), executor.getQueue().size(),
                executor.getCorePoolSize(), queueCapacity, rejectedCount.get());
    }

    public record Snapshot(String name, int active, int queued, int poolSize, int queueCapacity,
                           long rejected) {}
}
