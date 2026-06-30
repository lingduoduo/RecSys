package com.recsys.loadshed;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Shuts executors down gracefully: stop accepting new work, let running tasks
 * finish within a bounded deadline, then force-terminate. Used on the service
 * shutdown paths so in-flight recall work drains instead of being interrupted.
 */
public final class GracefulExecutors {

    private static final long DEFAULT_TIMEOUT_MS = 5000L;
    private static final String TIMEOUT_ENV = "RECSYS_EXECUTOR_SHUTDOWN_TIMEOUT_MS";

    private GracefulExecutors() {
    }

    public static Duration defaultTimeout() {
        String raw = System.getenv(TIMEOUT_ENV);
        if (raw == null || raw.isBlank()) {
            return Duration.ofMillis(DEFAULT_TIMEOUT_MS);
        }
        try {
            return Duration.ofMillis(Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            return Duration.ofMillis(DEFAULT_TIMEOUT_MS);
        }
    }

    public static void shutdownGracefully(ExecutorService ex) {
        shutdownGracefully(ex, defaultTimeout());
    }

    public static void shutdownGracefully(ExecutorService ex, Duration timeout) {
        if (ex == null) {
            return;
        }
        ex.shutdown();
        try {
            if (!ex.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                ex.shutdownNow();
            }
        } catch (InterruptedException e) {
            ex.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
