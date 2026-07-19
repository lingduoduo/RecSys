package com.recsys.application.outbox;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** A live transport send whose cancellation stage settles only after the send is no longer live. */
public final class DeliveryAttempt {
    private final CompletionStage<DeliveryReceipt> completion;
    private final Supplier<CompletionStage<Void>> cancellation;
    private CompletionStage<Void> cancellationResult;

    public DeliveryAttempt(CompletionStage<DeliveryReceipt> completion,
                           Supplier<CompletionStage<Void>> cancellation) {
        this.completion = Objects.requireNonNull(completion, "completion");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }

    public CompletionStage<DeliveryReceipt> completion() { return completion; }

    public synchronized CompletionStage<Void> cancel() {
        if (cancellationResult != null) return cancellationResult;
        try {
            cancellationResult = Objects.requireNonNull(cancellation.get(), "cancellation returned null stage");
        } catch (Throwable failure) {
            cancellationResult = CompletableFuture.failedFuture(failure);
        }
        return cancellationResult;
    }
}
