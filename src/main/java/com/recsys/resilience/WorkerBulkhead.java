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
                },
                // A custom RejectedExecutionHandler, not the default AbortPolicy, so that both
                // production callers are counted -- not just submit() below. asExecutorService()
                // is what production recall actually goes through (via
                // CompletableFuture.supplyAsync), and it never calls submit(), so a handler
                // installed here is the only place that sees every rejection regardless of which
                // entry point produced it.
                //
                // isShutdown() is read here, inside the handler ThreadPoolExecutor itself invokes
                // at the point it decides to reject -- not, as before, after the exception has
                // already propagated out through execute() and back to a caller. That is tighter
                // than the previous catch-block read, but it is still not atomic with the
                // rejection decision: there is no race-free discriminator available, and telling
                // the two apart for certain would need ThreadPoolExecutor internals this handler
                // doesn't have either. So the same known TOCTOU window remains, just narrower: if
                // close() lands between the internal reject() call and this read, a genuine
                // queue-full rejection can still be misattributed to SHUTDOWN. The error only runs
                // one direction -- FULL can be undercounted as SHUTDOWN, never the reverse -- which
                // is the safe direction for what this split exists to prevent: it can only
                // suppress a page, never manufacture a spurious one. The cost is that saturation
                // gets under-reported during a deploy that happens under load, which is exactly
                // when that signal matters most; cross-check with recsys_queue_utilization there
                // rather than trusting the reason breakdown alone.
                (r, e) -> {
                    if (e.isShutdown()) {
                        shutdownRejectedCount.incrementAndGet();
                    } else {
                        rejectedCount.incrementAndGet();
                    }
                    // Must throw: this replaces AbortPolicy, and every existing caller --
                    // submit()'s catch below and MultiChannelRecallService's catch around its
                    // asExecutorService() usage -- depends on RejectedExecutionException
                    // propagating out of execute()/supplyAsync(). A handler that returns
                    // normally instead of throwing would silently turn every rejection into a
                    // no-op: the task is simply dropped, no exception, no future completion.
                    throw new RejectedExecutionException("Task " + r + " rejected from " + e);
                }
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
            // Classification and counting already happened in the RejectedExecutionHandler
            // installed on the executor above -- that handler runs for every rejection,
            // regardless of entry point, so counting again here would double-count every
            // rejection submit() observes.
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

    /**
     * @param rejected count of rejections classified {@link QueueMetrics.RejectionReason#FULL}
     *                 only -- i.e. queue-full saturation. It excludes
     *                 {@link QueueMetrics.RejectionReason#SHUTDOWN} rejections (routine during a
     *                 clean drain), which used to be folded into this same count before the two
     *                 reasons were split apart. No consumer of this record depended on the old,
     *                 unsplit meaning as of this note, but it is public API, so callers written
     *                 against an earlier version of this class should not assume {@code rejected}
     *                 still reports every rejection.
     */
    public record Snapshot(String name, int active, int queued, int poolSize, int queueCapacity,
                           long rejected) {}
}
