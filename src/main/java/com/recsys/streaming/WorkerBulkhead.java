// src/main/java/com/recsys/streaming/WorkerBulkhead.java
package com.recsys.streaming;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class WorkerBulkhead {

    private static final AtomicLong THREAD_COUNTER = new AtomicLong();

    private final String name;
    private final ThreadPoolExecutor executor;
    private final AtomicLong rejectedCount = new AtomicLong();

    public WorkerBulkhead(String name, int poolSize, int queueCapacity) {
        this.name = name;
        this.executor = new ThreadPoolExecutor(
                poolSize, poolSize,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                r -> {
                    Thread t = new Thread(r, name + "-worker-" + THREAD_COUNTER.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                (runnable, tpe) -> rejectedCount.incrementAndGet()
        );
    }

    public <T> CompletableFuture<T> submit(Callable<T> task) {
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
        return executor;
    }

    public void close() {
        executor.shutdown();
    }

    public Snapshot snapshot() {
        return new Snapshot(name, executor.getActiveCount(), executor.getQueue().size(),
                executor.getCorePoolSize(), rejectedCount.get());
    }

    public record Snapshot(String name, int active, int queued, int poolSize, long rejected) {}
}
