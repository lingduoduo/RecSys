package com.recsys.application.outbox;

import com.recsys.domain.outbox.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public final class OutboxRelay {
    private final OutboxRepository repository;
    private final Map<OutboxDestination, OutboxDeliveryAdapter> adapters;
    private final OutboxRetryPolicy retryPolicy;
    private final String worker;
    private final Clock clock;
    private final int batchSize;
    private final Duration leaseDuration;
    private final Duration cycleDeadline;
    private final Semaphore sendCapacity;

    public OutboxRelay(OutboxRepository repository,
                       Map<OutboxDestination, OutboxDeliveryAdapter> adapters,
                       OutboxRetryPolicy retryPolicy, String worker, Clock clock,
                       int batchSize, Duration leaseDuration, Duration cycleDeadline,
                       int maxConcurrentSends) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.adapters = Map.copyOf(Objects.requireNonNull(adapters, "adapters"));
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.worker = requireText(worker, "worker");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (batchSize < 1 || maxConcurrentSends < 1) throw new IllegalArgumentException("limits must be positive");
        this.batchSize = batchSize;
        this.sendCapacity = new Semaphore(maxConcurrentSends);
        this.leaseDuration = positive(leaseDuration, "leaseDuration");
        this.cycleDeadline = positive(cycleDeadline, "cycleDeadline");
    }

    public synchronized int runOnce() {
        int available = sendCapacity.availablePermits();
        if (available == 0) return 0;
        Instant claimedAt = clock.instant();
        List<OutboxEvent> claimed = repository.claimBatch(worker, claimedAt,
                Math.min(batchSize, available), leaseDuration);
        for (OutboxEvent event : claimed) {
            if (!sendCapacity.tryAcquire()) throw new IllegalStateException("relay send capacity invariant violated");
            dispatch(event);
        }
        return claimed.size();
    }

    private void dispatch(OutboxEvent event) {
        OutboxDeliveryAdapter adapter = adapters.get(event.destination());
        CompletionStage<DeliveryReceipt> delivery;
        try {
            if (adapter == null) throw new IllegalStateException("no adapter for " + event.destination());
            delivery = Objects.requireNonNull(adapter.deliver(event), "adapter returned null stage");
        } catch (Throwable failure) {
            try { handleFailure(event, failure); }
            finally { sendCapacity.release(); }
            return;
        }

        delivery.toCompletableFuture().thenApply(receipt -> receipt)
                .orTimeout(cycleDeadline.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((receipt, failure) -> {
                    try {
                        if (failure == null) {
                            repository.markDelivered(event.eventId(), event.version(), event.leaseOwner(),
                                    receipt.acknowledgedAt());
                        } else {
                            handleFailure(event, unwrap(failure));
                        }
                    } finally { sendCapacity.release(); }
                });
    }

    private void handleFailure(OutboxEvent event, Throwable failure) {
        String message = failure.getClass().getSimpleName() + ": " + Objects.toString(failure.getMessage(), "");
        if (retryPolicy.isDead(event.attemptCount())) {
            repository.markDead(event.eventId(), event.version(), event.leaseOwner(), message);
        } else {
            Instant failedAt = clock.instant();
            repository.reschedule(event.eventId(), event.version(), event.leaseOwner(),
                    retryPolicy.nextAttempt(event.attemptCount(), failedAt), message);
        }
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
}
