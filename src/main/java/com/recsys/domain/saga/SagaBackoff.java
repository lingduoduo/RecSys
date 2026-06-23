package com.recsys.domain.saga;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public final class SagaBackoff {
    // Full-jitter exponential backoff cap. Matches the MaxDelaySeconds in the Step Functions ASL.
    public static final long MAX_BACKOFF_MS = 30_000L;

    private SagaBackoff() {}

    public static void sleep(Duration baseBackoff, int attempt) {
        if (baseBackoff == null || baseBackoff.isZero() || baseBackoff.isNegative()) return;
        long baseMs = baseBackoff.toMillis();
        long ceiling = Math.min(MAX_BACKOFF_MS, baseMs << Math.min(attempt - 1, 10));
        long sleepMs = ceiling > 0 ? ThreadLocalRandom.current().nextLong(ceiling + 1) : 0L;
        if (sleepMs <= 0) return;
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SagaException("interrupted during saga retry backoff", e);
        }
    }
}
