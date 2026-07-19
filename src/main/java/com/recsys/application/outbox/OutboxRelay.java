package com.recsys.application.outbox;

import com.recsys.domain.outbox.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import com.recsys.metrics.ConsistencyMetrics;

public final class OutboxRelay implements AutoCloseable {
    private final OutboxRepository repository;
    private final Map<OutboxDestination, OutboxDeliveryAdapter> adapters;
    private final OutboxRetryPolicy retryPolicy;
    private final String worker;
    private final Clock clock;
    private final int batchSize;
    private final Duration leaseDuration;
    private final Duration cycleDeadline;
    private final Semaphore sendCapacity;
    private final int maxConcurrentSends;
    private final ExecutorService terminalExecutor;
    private final ScheduledExecutorService deadlineExecutor;
    private final Consumer<Throwable> failureObserver;
    private final ConsistencyMetrics metrics;
    private final AtomicInteger pendingDeliveries = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    public OutboxRelay(OutboxRepository repository,
                       Map<OutboxDestination, OutboxDeliveryAdapter> adapters,
                       OutboxRetryPolicy retryPolicy, String worker, Clock clock,
                       int batchSize, Duration leaseDuration, Duration cycleDeadline,
                       int maxConcurrentSends) {
        this(repository, adapters, retryPolicy, worker, clock, batchSize, leaseDuration, cycleDeadline,
                maxConcurrentSends, failure -> failure.printStackTrace(System.err), null);
    }

    public OutboxRelay(OutboxRepository repository, Map<OutboxDestination, OutboxDeliveryAdapter> adapters,
                       OutboxRetryPolicy retryPolicy, String worker, Clock clock, int batchSize,
                       Duration leaseDuration, Duration cycleDeadline, int maxConcurrentSends,
                       ConsistencyMetrics metrics) {
        this(repository, adapters, retryPolicy, worker, clock, batchSize, leaseDuration, cycleDeadline,
                maxConcurrentSends, failure -> failure.printStackTrace(System.err), Objects.requireNonNull(metrics));
    }

    OutboxRelay(OutboxRepository repository,
                Map<OutboxDestination, OutboxDeliveryAdapter> adapters,
                OutboxRetryPolicy retryPolicy, String worker, Clock clock,
                int batchSize, Duration leaseDuration, Duration cycleDeadline,
                int maxConcurrentSends, Consumer<Throwable> failureObserver) {
        this(repository, adapters, retryPolicy, worker, clock, batchSize, leaseDuration, cycleDeadline,
                maxConcurrentSends, failureObserver, null);
    }

    OutboxRelay(OutboxRepository repository, Map<OutboxDestination, OutboxDeliveryAdapter> adapters,
                OutboxRetryPolicy retryPolicy, String worker, Clock clock, int batchSize,
                Duration leaseDuration, Duration cycleDeadline, int maxConcurrentSends,
                Consumer<Throwable> failureObserver, ConsistencyMetrics metrics) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.adapters = Map.copyOf(Objects.requireNonNull(adapters, "adapters"));
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.worker = requireText(worker, "worker");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (batchSize < 1 || maxConcurrentSends < 1) throw new IllegalArgumentException("limits must be positive");
        this.batchSize = batchSize;
        this.sendCapacity = new Semaphore(maxConcurrentSends);
        this.maxConcurrentSends = maxConcurrentSends;
        this.leaseDuration = positive(leaseDuration, "leaseDuration");
        this.cycleDeadline = positive(cycleDeadline, "cycleDeadline");
        this.adapters.forEach((destination, adapter) -> adapter.deliveryDeadline().ifPresent(adapterDeadline -> {
            if (!this.cycleDeadline.equals(adapterDeadline))
                throw new IllegalArgumentException(destination + " adapter deadline " + adapterDeadline
                        + " must equal relay cycleDeadline " + this.cycleDeadline);
        }));
        this.failureObserver = Objects.requireNonNull(failureObserver, "failureObserver");
        this.metrics = metrics;
        this.terminalExecutor = new ThreadPoolExecutor(
                Math.min(maxConcurrentSends, 4), Math.min(maxConcurrentSends, 4),
                0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(maxConcurrentSends),
                runnable -> {
                    Thread thread = new Thread(runnable, "outbox-relay-" + THREAD_SEQUENCE.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.deadlineExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "outbox-relay-deadline");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized int runOnce() {
        if (closed.get()) return 0;
        int available = sendCapacity.availablePermits();
        if (available == 0) return 0;
        Instant claimedAt = clock.instant();
        List<OutboxEvent> claimed = repository.claimBatch(worker, claimedAt,
                Math.min(batchSize, available), leaseDuration);
        if (metrics != null) metrics.updateInFlightEvents(pendingDeliveries.addAndGet(claimed.size()));
        for (OutboxEvent event : claimed) {
            if (!sendCapacity.tryAcquire()) throw new IllegalStateException("relay send capacity invariant violated");
            dispatch(event);
        }
        return claimed.size();
    }

    private void dispatch(OutboxEvent event) {
        OutboxDeliveryAdapter adapter = adapters.get(event.destination());
        DeliveryAttempt attempt;
        try {
            if (adapter == null) throw new IllegalStateException("no adapter for " + event.destination());
            attempt = Objects.requireNonNull(adapter.deliver(event), "adapter returned null attempt");
        } catch (Throwable failure) {
            submitTerminal(event, null, failure);
            return;
        }
        AtomicBoolean terminalSelected = new AtomicBoolean();
        ScheduledFuture<?> deadline = deadlineExecutor.schedule(() -> attempt.cancel().whenComplete((ignored, cancellationFailure) -> {
            if (cancellationFailure != null) {
                report(unwrap(cancellationFailure));
                return;
            }
            if (terminalSelected.compareAndSet(false, true))
                submitTerminal(event, null, new TimeoutException("delivery attempt exceeded " + cycleDeadline));
        }), cycleDeadline.toNanos(), TimeUnit.NANOSECONDS);
        attempt.completion().whenComplete((receipt, failure) -> {
            if (terminalSelected.compareAndSet(false, true)) {
                deadline.cancel(false);
                submitTerminal(event, receipt, failure);
            }
        });
    }

    private void submitTerminal(OutboxEvent event, DeliveryReceipt receipt, Throwable failure) {
        try {
            terminalExecutor.execute(() -> {
                try {
                    if (failure == null) {
                        DeliveryReceipt requiredReceipt = Objects.requireNonNull(receipt, "adapter completed with null receipt");
                        requireTransition(repository.markDelivered(event.eventId(), event.version(), event.leaseOwner(),
                                requiredReceipt.acknowledgedAt()), event, "markDelivered");
                        if (metrics != null) metrics.recordDelivered(destination(event.destination()),
                                Duration.between(event.createdAt(), requiredReceipt.acknowledgedAt()));
                    } else {
                        handleFailure(event, unwrap(failure));
                    }
                } catch (Throwable terminalFailure) {
                    if (metrics != null) metrics.recordDeliveryFailure(destination(event.destination()));
                    report(terminalFailure);
                } finally {
                    if (metrics != null) metrics.updateInFlightEvents(pendingDeliveries.decrementAndGet());
                    sendCapacity.release();
                }
            });
        } catch (RejectedExecutionException rejected) {
            if (metrics != null) metrics.updateInFlightEvents(pendingDeliveries.decrementAndGet());
            sendCapacity.release();
            report(rejected);
        }
    }

    private void report(Throwable failure) {
        try { failureObserver.accept(failure); }
        catch (Throwable observerFailure) { observerFailure.printStackTrace(System.err); }
    }

    private void handleFailure(OutboxEvent event, Throwable failure) {
        if (metrics != null) metrics.recordDeliveryFailure(destination(event.destination()));
        String message = failure.getClass().getSimpleName() + ": " + Objects.toString(failure.getMessage(), "");
        if (retryPolicy.isDead(event.attemptCount())) {
            requireTransition(repository.markDead(event.eventId(), event.version(), event.leaseOwner(), message),
                    event, "markDead");
        } else {
            Instant failedAt = clock.instant();
            requireTransition(repository.reschedule(event.eventId(), event.version(), event.leaseOwner(),
                    retryPolicy.nextAttempt(event.attemptCount(), failedAt), message), event, "reschedule");
        }
    }

    private static ConsistencyMetrics.Destination destination(OutboxDestination value) {
        return value == OutboxDestination.KAFKA_ONLINE ? ConsistencyMetrics.Destination.KAFKA_ONLINE
                : ConsistencyMetrics.Destination.SAGA_SNS;
    }

    private static void requireTransition(boolean updated, OutboxEvent event, String operation) {
        if (!updated) throw new IllegalStateException("outbox transition conflict during " + operation
                + " for event " + event.eventId() + " version " + event.version()
                + " owner " + event.leaseOwner());
    }

    private static Throwable unwrap(Throwable failure) {
        if ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null)
            return failure.getCause();
        return failure;
    }
    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    /** Stops new claims, waits up to the cycle deadline for live sends and terminal I/O, then stops workers. */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        long deadline = System.nanoTime() + cycleDeadline.toNanos();
        while (sendCapacity.availablePermits() != maxConcurrentSends && System.nanoTime() < deadline) {
            try { Thread.sleep(5); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        deadlineExecutor.shutdownNow();
        terminalExecutor.shutdown();
        try {
            long remaining = Math.max(0, deadline - System.nanoTime());
            if (!terminalExecutor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) terminalExecutor.shutdownNow();
        } catch (InterruptedException interrupted) {
            terminalExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
