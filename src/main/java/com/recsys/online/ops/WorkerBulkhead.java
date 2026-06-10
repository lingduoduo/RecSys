// src/main/java/com/recsys/streaming/WorkerBulkhead.java
package com.recsys.online.ops;

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

public final class WorkerBulkhead {

    private final AtomicLong threadCounter = new AtomicLong();

    private final String name;
    private final ThreadPoolExecutor executor;
    private final AtomicLong rejectedCount = new AtomicLong();

    public WorkerBulkhead(String name, int poolSize, int queueCapacity) {
        if (poolSize < 1) throw new IllegalArgumentException("poolSize must be >= 1, got: " + poolSize);
        this.name = name;
        this.executor = new ThreadPoolExecutor(
                poolSize, poolSize,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
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
            rejectedCount.incrementAndGet();
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

    public Snapshot snapshot() {
        return new Snapshot(name, executor.getActiveCount(), executor.getQueue().size(),
                executor.getCorePoolSize(), rejectedCount.get());
    }

    public record Snapshot(String name, int active, int queued, int poolSize, long rejected) {}
}
